#!/usr/bin/env python3
"""Build state-sized offline storage proxies from the official USGS CNGM Earth Surface layer.

IMPORTANT: This is an audit/prototyping tool, not RockMap's production geology source builder.
It deliberately uses the official flattened CNGM Earth Surface FeatureServer because downloading
and unpacking the 6.4 GB full relational CNGM database is unnecessary for the first storage test.
Production geology packs should be compiled from the full relational CNGM release so RockMap can
preserve source/assignment/provenance relationships that are not all present in this web layer.

The proxy models the storage costs RockMap is likely to care about on-device:
  * state-clipped polygon geometry stored as WKB blobs
  * R-tree spatial index
  * normalized source-unit and synthesis-unit text
  * FTS5 offline search index over source geology text
  * SQLite indexes and metadata
  * gzip download asset

Authoritative upstreams used by this audit:
  USGS CNGM Earth Surface layer (DOI 10.5066/P146VGVM)
  U.S. Census Bureau TIGERweb state boundaries (for clipping only)
"""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import json
import os
import shutil
import sqlite3
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Sequence, Tuple

try:
    from shapely.geometry import shape
    from shapely.wkb import dumps as wkb_dumps
except ImportError as exc:  # pragma: no cover - runtime guard
    raise SystemExit(
        "Shapely is required for the audit. Install with: python3 -m pip install 'shapely==2.1.1'"
    ) from exc

CNGM_LAYER = (
    "https://energy.usgs.gov/arcgis/rest/services/Hosted/"
    "mapunitpolys_esurf/FeatureServer/0"
)
CNGM_QUERY = CNGM_LAYER + "/query"
TIGER_STATES_QUERY = (
    "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/"
    "State_County/MapServer/0/query"
)
CNGM_DOI = "10.5066/P146VGVM"
CNGM_TITLE = "Cooperative National Geologic Map: Earth's surface geology"
CNGM_SERVICE_ITEM_ID = "5847ed9140284b73aa2f49b28c993f28"
USER_AGENT = "RockMap-CNGM-storage-audit/1.0 (+offline-pack-prototype)"
DEFAULT_STATES = ("CO", "CA", "UT", "AZ", "MI")
MAX_RESPONSE_BYTES = 96 * 1024 * 1024
FETCH_BATCH_SIZE = 150
REQUEST_ATTEMPTS = 5

# These are the fields exposed by the official flattened Earth Surface layer that are useful
# for the storage proxy. The full relational CNGM release has additional assignment/vocabulary
# relationships; therefore this proxy MUST NOT be treated as the final production schema.
CNGM_FIELDS = (
    "objectid",
    "f_mapunitpolys_id",
    "mapunit",
    "name",
    "source_fgdc_symbol",
    "description",
    "geomaterial",
    "geomaterialconfidence",
    "age",
    "synthesis_mapunit",
    "synthesis_mapunitname",
    "synthesis_fgdc_symbol",
    "synthesis_description",
    "map_citation",
    "ngmdb_url",
    "synthesis_citation",
    "synthesis_url",
    "f_synthesissources_id",
    "min_age",
    "max_age",
)

STATE_NAMES = {
    "AZ": "Arizona",
    "CA": "California",
    "CO": "Colorado",
    "MI": "Michigan",
    "UT": "Utah",
}


@dataclass
class Metrics:
    state: str
    state_name: str
    candidate_features_bbox: int
    kept_polygons: int
    source_units: int
    synthesis_units: int
    downloaded_http_bytes: int
    geometry_payload_bytes: int
    text_payload_bytes: int
    rtree_page_bytes: int
    fts_page_bytes: int
    sqlite_bytes: int
    gzip_bytes: int
    gzip_ratio: float
    gzip_mib: float
    sqlite_mib: float
    seconds: float
    sha256_db: str
    sha256_gzip: str


def safe_text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _read_limited(response, limit: int = MAX_RESPONSE_BYTES) -> bytes:
    out = bytearray()
    while True:
        chunk = response.read(128 * 1024)
        if not chunk:
            break
        out.extend(chunk)
        if len(out) > limit:
            raise RuntimeError(f"HTTP response exceeded fail-closed limit of {limit} bytes")
    return bytes(out)


def post_json(url: str, params: Mapping[str, Any], *, max_bytes: int = MAX_RESPONSE_BYTES) -> Tuple[Dict[str, Any], int]:
    if not (url.startswith("https://energy.usgs.gov/") or url.startswith("https://tigerweb.geo.census.gov/")):
        raise ValueError(f"Refusing non-whitelisted audit host: {url}")
    body = urllib.parse.urlencode(params).encode("utf-8")
    last_error: Exception | None = None
    for attempt in range(1, REQUEST_ATTEMPTS + 1):
        request = urllib.request.Request(
            url,
            data=body,
            headers={
                "Accept": "application/json, application/geo+json",
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent": USER_AGENT,
                "Cache-Control": "no-cache",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                raw = _read_limited(response, max_bytes)
            payload = json.loads(raw.decode("utf-8"))
            if not isinstance(payload, dict):
                raise RuntimeError("Expected a JSON object from upstream service")
            if "error" in payload:
                raise RuntimeError(f"Upstream ArcGIS error: {payload['error']}")
            return payload, len(raw)
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, json.JSONDecodeError, RuntimeError) as exc:
            last_error = exc
            if attempt >= REQUEST_ATTEMPTS:
                break
            time.sleep(min(2 ** (attempt - 1), 12))
    raise RuntimeError(f"Request failed after {REQUEST_ATTEMPTS} attempts: {last_error}")


def fetch_state_shape(state: str) -> Tuple[Any, int, str]:
    payload, nbytes = post_json(
        TIGER_STATES_QUERY,
        {
            "where": f"STUSAB='{state}'",
            "outFields": "STUSAB,NAME",
            "returnGeometry": "true",
            "outSR": "4326",
            "f": "geojson",
        },
        max_bytes=24 * 1024 * 1024,
    )
    features = payload.get("features") or []
    if len(features) != 1:
        raise RuntimeError(f"Expected exactly one TIGER state feature for {state}; got {len(features)}")
    feature = features[0]
    props = feature.get("properties") or {}
    geom = shape(feature.get("geometry"))
    if geom.is_empty:
        raise RuntimeError(f"TIGER geometry for {state} is empty")
    if not geom.is_valid:
        geom = geom.buffer(0)
    if geom.is_empty or not geom.is_valid:
        raise RuntimeError(f"TIGER geometry for {state} remains invalid after repair")
    return geom, nbytes, safe_text(props.get("NAME")) or STATE_NAMES.get(state, state)


def fetch_candidate_ids(bounds: Tuple[float, float, float, float]) -> Tuple[List[int], int]:
    minx, miny, maxx, maxy = bounds
    geometry = canonical_json(
        {
            "xmin": minx,
            "ymin": miny,
            "xmax": maxx,
            "ymax": maxy,
            "spatialReference": {"wkid": 4326},
        }
    )
    payload, nbytes = post_json(
        CNGM_QUERY,
        {
            "where": "1=1",
            "geometry": geometry,
            "geometryType": "esriGeometryEnvelope",
            "inSR": "4326",
            "spatialRel": "esriSpatialRelIntersects",
            "returnIdsOnly": "true",
            "returnGeometry": "false",
            "f": "json",
        },
        max_bytes=16 * 1024 * 1024,
    )
    ids = payload.get("objectIds") or []
    clean = sorted({int(v) for v in ids})
    if not clean:
        raise RuntimeError("CNGM returned no candidate polygons for the state envelope")
    return clean, nbytes


def batched(items: Sequence[int], size: int) -> Iterable[Sequence[int]]:
    for start in range(0, len(items), size):
        yield items[start:start + size]


def fetch_features(object_ids: Sequence[int]) -> Tuple[List[Dict[str, Any]], int]:
    payload, nbytes = post_json(
        CNGM_QUERY,
        {
            "objectIds": ",".join(str(v) for v in object_ids),
            "outFields": ",".join(CNGM_FIELDS),
            "returnGeometry": "true",
            "outSR": "4326",
            "f": "geojson",
        },
        max_bytes=MAX_RESPONSE_BYTES,
    )
    features = payload.get("features") or []
    if not isinstance(features, list):
        raise RuntimeError("CNGM GeoJSON response did not contain a feature list")
    return features, nbytes


def create_schema(db: sqlite3.Connection) -> None:
    db.executescript(
        """
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA temp_store=MEMORY;
        PRAGMA foreign_keys=ON;
        PRAGMA page_size=4096;

        CREATE TABLE metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE source_units (
            id INTEGER PRIMARY KEY,
            unit_key TEXT NOT NULL UNIQUE,
            mapunit TEXT,
            name TEXT,
            source_fgdc_symbol TEXT,
            description TEXT,
            geomaterial TEXT,
            geomaterialconfidence TEXT,
            age TEXT,
            min_age TEXT,
            max_age TEXT,
            map_citation TEXT,
            ngmdb_url TEXT
        );

        CREATE TABLE synthesis_units (
            id INTEGER PRIMARY KEY,
            unit_key TEXT NOT NULL UNIQUE,
            mapunit TEXT,
            name TEXT,
            fgdc_symbol TEXT,
            description TEXT,
            citation TEXT,
            source_url TEXT
        );

        CREATE TABLE polygons (
            id INTEGER PRIMARY KEY,
            cngm_objectid INTEGER NOT NULL UNIQUE,
            cngm_mapunitpolys_id INTEGER,
            cngm_synthesissources_id INTEGER,
            source_unit_id INTEGER NOT NULL REFERENCES source_units(id),
            synthesis_unit_id INTEGER REFERENCES synthesis_units(id),
            min_lon REAL NOT NULL,
            min_lat REAL NOT NULL,
            max_lon REAL NOT NULL,
            max_lat REAL NOT NULL,
            geometry_wkb BLOB NOT NULL
        );

        CREATE VIRTUAL TABLE polygon_rtree USING rtree(
            id,
            min_lon, max_lon,
            min_lat, max_lat
        );

        CREATE INDEX idx_source_units_name ON source_units(name);
        CREATE INDEX idx_source_units_geomaterial ON source_units(geomaterial);
        CREATE INDEX idx_source_units_age ON source_units(age);
        CREATE INDEX idx_polygons_source_unit ON polygons(source_unit_id);
        CREATE INDEX idx_polygons_synthesis_unit ON polygons(synthesis_unit_id);
        """
    )


def text_fingerprint(values: Sequence[str]) -> str:
    joined = "\x1f".join(values).encode("utf-8")
    return hashlib.sha256(joined).hexdigest()


def insert_source_unit(db: sqlite3.Connection, cache: Dict[str, int], p: Mapping[str, Any]) -> int:
    values = [
        safe_text(p.get("mapunit")),
        safe_text(p.get("name")),
        safe_text(p.get("source_fgdc_symbol")),
        safe_text(p.get("description")),
        safe_text(p.get("geomaterial")),
        safe_text(p.get("geomaterialconfidence")),
        safe_text(p.get("age")),
        safe_text(p.get("min_age")),
        safe_text(p.get("max_age")),
        safe_text(p.get("map_citation")),
        safe_text(p.get("ngmdb_url")),
    ]
    key = text_fingerprint(values)
    if key in cache:
        return cache[key]
    cur = db.execute(
        """INSERT INTO source_units(
            unit_key,mapunit,name,source_fgdc_symbol,description,geomaterial,
            geomaterialconfidence,age,min_age,max_age,map_citation,ngmdb_url
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
        (key, *values),
    )
    row_id = int(cur.lastrowid)
    cache[key] = row_id
    return row_id


def insert_synthesis_unit(db: sqlite3.Connection, cache: Dict[str, int], p: Mapping[str, Any]) -> int | None:
    values = [
        safe_text(p.get("synthesis_mapunit")),
        safe_text(p.get("synthesis_mapunitname")),
        safe_text(p.get("synthesis_fgdc_symbol")),
        safe_text(p.get("synthesis_description")),
        safe_text(p.get("synthesis_citation")),
        safe_text(p.get("synthesis_url")),
    ]
    if not any(values):
        return None
    key = text_fingerprint(values)
    if key in cache:
        return cache[key]
    cur = db.execute(
        """INSERT INTO synthesis_units(
            unit_key,mapunit,name,fgdc_symbol,description,citation,source_url
        ) VALUES(?,?,?,?,?,?,?)""",
        (key, *values),
    )
    row_id = int(cur.lastrowid)
    cache[key] = row_id
    return row_id


def polygonal_intersection(feature_geom: Any, state_geom: Any) -> Any | None:
    if feature_geom.is_empty or not feature_geom.intersects(state_geom):
        return None
    clipped = feature_geom.intersection(state_geom)
    if clipped.is_empty:
        return None
    if clipped.geom_type in {"Polygon", "MultiPolygon"}:
        return clipped
    if clipped.geom_type == "GeometryCollection":
        polys = [g for g in clipped.geoms if g.geom_type in {"Polygon", "MultiPolygon"} and not g.is_empty]
        if not polys:
            return None
        from shapely.ops import unary_union
        merged = unary_union(polys)
        return merged if not merged.is_empty else None
    return None


def finalize_indexes(db: sqlite3.Connection) -> None:
    # External-content FTS keeps source text canonical in source_units while giving the phone
    # a realistic full-text search index cost.
    db.executescript(
        """
        CREATE VIRTUAL TABLE source_units_fts USING fts5(
            name,
            description,
            age,
            geomaterial,
            map_citation,
            content='source_units',
            content_rowid='id'
        );
        INSERT INTO source_units_fts(source_units_fts) VALUES('rebuild');
        ANALYZE;
        """
    )


def sum_text_payload(db: sqlite3.Connection) -> int:
    source_expr = " + ".join(
        f"length(CAST(COALESCE({c}, '') AS BLOB))" for c in (
            "unit_key", "mapunit", "name", "source_fgdc_symbol", "description", "geomaterial",
            "geomaterialconfidence", "age", "min_age", "max_age", "map_citation", "ngmdb_url"
        )
    )
    synth_expr = " + ".join(
        f"length(CAST(COALESCE({c}, '') AS BLOB))" for c in (
            "unit_key", "mapunit", "name", "fgdc_symbol", "description", "citation", "source_url"
        )
    )
    a = db.execute(f"SELECT COALESCE(SUM({source_expr}),0) FROM source_units").fetchone()[0]
    b = db.execute(f"SELECT COALESCE(SUM({synth_expr}),0) FROM synthesis_units").fetchone()[0]
    c = db.execute("SELECT COALESCE(SUM(length(CAST(key AS BLOB))+length(CAST(value AS BLOB))),0) FROM metadata").fetchone()[0]
    return int(a or 0) + int(b or 0) + int(c or 0)


def dbstat_pages(db: sqlite3.Connection) -> Dict[str, int]:
    try:
        rows = db.execute("SELECT name, COALESCE(SUM(pgsize),0) FROM dbstat GROUP BY name").fetchall()
        return {str(name): int(size or 0) for name, size in rows}
    except sqlite3.DatabaseError:
        return {}


def gzip_file(src: Path, dst: Path) -> None:
    # Use GzipFile directly so mtime=0 makes byte-for-byte output reproducible.
    with src.open("rb") as input_handle, dst.open("wb") as raw_output:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw_output, compresslevel=9, mtime=0) as output_handle:
            shutil.copyfileobj(input_handle, output_handle, length=1024 * 1024)


def build_state(state: str, output_root: Path) -> Metrics:
    started = time.monotonic()
    state_geom, tiger_bytes, state_name = fetch_state_shape(state)
    ids, id_bytes = fetch_candidate_ids(state_geom.bounds)
    downloaded = tiger_bytes + id_bytes

    state_dir = output_root / state.lower()
    state_dir.mkdir(parents=True, exist_ok=True)
    db_path = state_dir / f"rockmap-cngm-{state.lower()}-proxy.db"
    gz_path = db_path.with_suffix(db_path.suffix + ".gz")
    if db_path.exists():
        db_path.unlink()
    if gz_path.exists():
        gz_path.unlink()

    db = sqlite3.connect(db_path)
    source_cache: Dict[str, int] = {}
    synth_cache: Dict[str, int] = {}
    kept = 0
    try:
        create_schema(db)
        metadata = {
            "audit_type": "CNGM_OFFLINE_STORAGE_PROXY_NOT_PRODUCTION_SOURCE",
            "state": state,
            "state_name": state_name,
            "cngm_title": CNGM_TITLE,
            "cngm_doi": CNGM_DOI,
            "cngm_layer": CNGM_LAYER,
            "cngm_service_item_id": CNGM_SERVICE_ITEM_ID,
            "boundary_source": "U.S. Census Bureau TIGERweb States, January 1 2025 vintage",
            "geometry_encoding": "WKB 2D EPSG:4326, clipped to TIGER state boundary",
            "generated_utc": datetime.now(timezone.utc).isoformat(),
            "production_warning": (
                "Storage proxy only. Production RockMap geology must be compiled from the full "
                "relational CNGM release so source, vocabulary, assignment, confidence, and "
                "provenance relationships can be preserved explicitly."
            ),
        }
        db.executemany("INSERT INTO metadata(key,value) VALUES(?,?)", metadata.items())

        for batch_no, batch in enumerate(batched(ids, FETCH_BATCH_SIZE), start=1):
            features, nbytes = fetch_features(batch)
            downloaded += nbytes
            for feature in features:
                p = feature.get("properties") or {}
                raw_geom = feature.get("geometry")
                if not isinstance(raw_geom, dict):
                    continue
                geom = shape(raw_geom)
                if not geom.is_valid:
                    geom = geom.buffer(0)
                if geom.is_empty or not geom.is_valid:
                    continue
                clipped = polygonal_intersection(geom, state_geom)
                if clipped is None:
                    continue

                objectid = int(p.get("objectid"))
                source_id = insert_source_unit(db, source_cache, p)
                synth_id = insert_synthesis_unit(db, synth_cache, p)
                minx, miny, maxx, maxy = clipped.bounds
                geometry_wkb = wkb_dumps(clipped, hex=False, output_dimension=2)
                cur = db.execute(
                    """INSERT INTO polygons(
                        cngm_objectid,cngm_mapunitpolys_id,cngm_synthesissources_id,
                        source_unit_id,synthesis_unit_id,min_lon,min_lat,max_lon,max_lat,geometry_wkb
                    ) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    (
                        objectid,
                        p.get("f_mapunitpolys_id"),
                        p.get("f_synthesissources_id"),
                        source_id,
                        synth_id,
                        minx,
                        miny,
                        maxx,
                        maxy,
                        sqlite3.Binary(geometry_wkb),
                    ),
                )
                poly_id = int(cur.lastrowid)
                db.execute(
                    "INSERT INTO polygon_rtree(id,min_lon,max_lon,min_lat,max_lat) VALUES(?,?,?,?,?)",
                    (poly_id, minx, maxx, miny, maxy),
                )
                kept += 1

            if batch_no % 10 == 0:
                db.commit()
                print(f"{state}: fetched {min(batch_no * FETCH_BATCH_SIZE, len(ids))}/{len(ids)} candidates; kept {kept}")

        if kept < 10:
            raise RuntimeError(f"Fail closed: only {kept} CNGM polygons survived state clipping for {state}")
        db.commit()
        finalize_indexes(db)
        db.commit()
        db.execute("VACUUM")
        db.commit()
        quick = db.execute("PRAGMA quick_check").fetchone()[0]
        if str(quick).lower() != "ok":
            raise RuntimeError(f"SQLite quick_check failed for {state}: {quick}")
        geometry_payload = int(db.execute("SELECT COALESCE(SUM(length(geometry_wkb)),0) FROM polygons").fetchone()[0] or 0)
        text_payload = sum_text_payload(db)
        pages = dbstat_pages(db)
        rtree_pages = sum(v for k, v in pages.items() if k.startswith("polygon_rtree"))
        fts_pages = sum(v for k, v in pages.items() if k.startswith("source_units_fts"))
        source_count = int(db.execute("SELECT COUNT(*) FROM source_units").fetchone()[0])
        synth_count = int(db.execute("SELECT COUNT(*) FROM synthesis_units").fetchone()[0])
    finally:
        db.close()

    gzip_file(db_path, gz_path)
    sqlite_bytes = db_path.stat().st_size
    gzip_bytes = gz_path.stat().st_size
    metrics = Metrics(
        state=state,
        state_name=state_name,
        candidate_features_bbox=len(ids),
        kept_polygons=kept,
        source_units=source_count,
        synthesis_units=synth_count,
        downloaded_http_bytes=downloaded,
        geometry_payload_bytes=geometry_payload,
        text_payload_bytes=text_payload,
        rtree_page_bytes=rtree_pages,
        fts_page_bytes=fts_pages,
        sqlite_bytes=sqlite_bytes,
        gzip_bytes=gzip_bytes,
        gzip_ratio=(gzip_bytes / sqlite_bytes) if sqlite_bytes else 0.0,
        gzip_mib=gzip_bytes / (1024 * 1024),
        sqlite_mib=sqlite_bytes / (1024 * 1024),
        seconds=time.monotonic() - started,
        sha256_db=sha256_file(db_path),
        sha256_gzip=sha256_file(gz_path),
    )
    (state_dir / "metrics.json").write_text(json.dumps(asdict(metrics), indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"{state}: {metrics.kept_polygons} polygons; {metrics.sqlite_mib:.2f} MiB installed; {metrics.gzip_mib:.2f} MiB gzip")
    return metrics


def write_summary(output_root: Path, rows: Sequence[Metrics]) -> None:
    fieldnames = list(asdict(rows[0]).keys())
    with (output_root / "summary.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow(asdict(row))

    lines = [
        "# RockMap CNGM Offline Pack Storage Audit",
        "",
        "> **Storage proxy only.** These packs use the official flattened USGS CNGM Earth's Surface web layer to measure realistic phone storage. They are not approved production geology packs. Production ingestion must use the full relational CNGM release.",
        "",
        "| State | Polygons | Source units | Installed MiB | Download MiB | Gzip ratio | Geometry payload MiB | Text payload MiB | R-tree MiB | FTS MiB |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for r in rows:
        lines.append(
            f"| {r.state} | {r.kept_polygons:,} | {r.source_units:,} | {r.sqlite_mib:.2f} | {r.gzip_mib:.2f} | "
            f"{r.gzip_ratio:.3f} | {r.geometry_payload_bytes/1048576:.2f} | {r.text_payload_bytes/1048576:.2f} | "
            f"{r.rtree_page_bytes/1048576:.2f} | {r.fts_page_bytes/1048576:.2f} |"
        )
    total_sqlite = sum(r.sqlite_bytes for r in rows)
    total_gzip = sum(r.gzip_bytes for r in rows)
    lines += [
        "",
        f"Five-state total installed: **{total_sqlite/1048576:.2f} MiB**",
        f"Five-state total download: **{total_gzip/1048576:.2f} MiB**",
        "",
        "## Interpretation rules",
        "",
        "- Do not compare the 6.4 GB full CNGM master database directly with these state packs; the master contains nationwide source maps, assignments, vocabularies, synthesis machinery, and multiple thematic products.",
        "- This proxy is expected to underestimate the final production pack somewhat because it does not include all relational assignment/vocabulary tables from the full CNGM release.",
        "- It may overestimate some production costs because the production compiler can deduplicate shared vocabularies nationally and may use more compact numeric vocabulary IDs.",
        "- No geometry simplification is performed by this script after retrieving the published web geometry. Any future simplification must be justified against source scale and tested for scientific/cartographic consequences.",
    ]
    (output_root / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_states(raw: str) -> List[str]:
    values = []
    for item in raw.split(","):
        state = item.strip().upper()
        if not state:
            continue
        if len(state) != 2 or not state.isalpha():
            raise argparse.ArgumentTypeError(f"Invalid state abbreviation: {item!r}")
        if state not in values:
            values.append(state)
    if not values:
        raise argparse.ArgumentTypeError("At least one state is required")
    return values


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--states",
        default=",".join(DEFAULT_STATES),
        help="Comma-separated state abbreviations (default: CO,CA,UT,AZ,MI)",
    )
    parser.add_argument("--output-dir", default="dist-cngm-storage-audit")
    args = parser.parse_args(argv)
    states = parse_states(args.states)
    output_root = Path(args.output_dir).resolve()
    if output_root.exists():
        shutil.rmtree(output_root)
    output_root.mkdir(parents=True)

    audit_meta = {
        "audit": "RockMap CNGM offline state-pack storage proxy",
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "states": states,
        "cngm_doi": CNGM_DOI,
        "cngm_layer": CNGM_LAYER,
        "cngm_service_item_id": CNGM_SERVICE_ITEM_ID,
        "production_source_approved": False,
        "production_requirement": "Rebuild from full relational CNGM release before app integration.",
    }
    (output_root / "audit-metadata.json").write_text(json.dumps(audit_meta, indent=2) + "\n", encoding="utf-8")

    rows: List[Metrics] = []
    for state in states:
        rows.append(build_state(state, output_root))
    write_summary(output_root, rows)
    print(f"Audit complete: {output_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
