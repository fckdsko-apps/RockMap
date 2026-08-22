#!/usr/bin/env python3
"""Build RockMap's immutable Colorado SGMC geology SQLite snapshot.

The Android app never scrapes the live USGS service at runtime. This builder runs in
GitHub Actions, obtains the complete Colorado object-ID set first, fetches those IDs
in bounded batches, validates every feature, writes the exact SQLite schema RockMap
queries locally, and packages a versioned gzip asset plus a small signed-by-hash
manifest containing exact compressed and installed byte counts.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import os
import shutil
import sqlite3
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Sequence, Tuple

LAYER_URL = (
    "https://services.arcgis.com/v01gqwM5QqNysAAi/ArcGIS/rest/services/"
    "SGMC_featureservice/FeatureServer/0"
)
QUERY_URL = LAYER_URL + "/query"
SOURCE_TITLE = "USGS State Geologic Map Compilation (SGMC)"
SOURCE_DOI = "10.5066/F7WH2N65"
SOURCE_SCALE = "1:500,000 Colorado source map"
SOURCE_WHERE = "STATE='CO'"
SCHEMA_VERSION = 1
MIN_EXPECTED_RECORDS = 500
MAX_EXPECTED_RECORDS = 100_000
DEFAULT_BATCH_SIZE = 100
MAX_HTTP_BYTES = 192 * 1024 * 1024
USER_AGENT = "RockMap-geology-builder/1.0"

FIELDS = [
    "OBJECTID", "STATE", "ORIG_LABEL", "SGMC_LABEL", "UNIT_LINK", "UNIT_NAME",
    "AGE_MIN", "AGE_MAX", "GENERALIZED_LITH", "MAJOR1", "MAJOR2", "MAJOR3",
    "MINOR1", "MINOR2", "MINOR3", "MINOR4", "MINOR5", "INCIDENTAL",
    "INDETERMINATE", "REF_ID", "REFERENCE", "DIGITAL_URL", "NGMDB1", "NGMDB2",
    "NGMDB3", "rgba",
]

TEXT_FIELDS = [
    "ORIG_LABEL", "SGMC_LABEL", "UNIT_LINK", "UNIT_NAME", "AGE_MIN", "AGE_MAX",
    "GENERALIZED_LITH", "MAJOR1", "MAJOR2", "MAJOR3", "MINOR1", "MINOR2",
    "MINOR3", "MINOR4", "MINOR5", "INCIDENTAL", "INDETERMINATE", "REF_ID",
    "REFERENCE", "DIGITAL_URL", "NGMDB1", "NGMDB2", "NGMDB3",
]
LITH_FIELDS = [
    "GENERALIZED_LITH", "MAJOR1", "MAJOR2", "MAJOR3", "MINOR1", "MINOR2",
    "MINOR3", "MINOR4", "MINOR5", "INCIDENTAL", "INDETERMINATE",
]
AGE_FIELDS = ["AGE_MIN", "AGE_MAX", "UNIT_NAME", "ORIG_LABEL", "SGMC_LABEL"]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def safe_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (dict, list)):
        return canonical_json(value)
    return str(value).strip()


def normalized_join(properties: Mapping[str, Any], names: Iterable[str]) -> str:
    seen = set()
    values: List[str] = []
    for name in names:
        raw = safe_text(properties.get(name))
        if not raw:
            continue
        value = " ".join(raw.lower().split())
        if value and value not in seen:
            seen.add(value)
            values.append(value)
    return " ".join(values)


def iter_coordinate_pairs(value: Any) -> Iterable[Tuple[float, float]]:
    if not isinstance(value, list):
        return
    if len(value) >= 2 and isinstance(value[0], (int, float)) and isinstance(value[1], (int, float)):
        lon = float(value[0])
        lat = float(value[1])
        if not math.isfinite(lon) or not math.isfinite(lat):
            raise ValueError("geometry contains a non-finite coordinate")
        if lon < -180.0 or lon > 180.0 or lat < -90.0 or lat > 90.0:
            raise ValueError(f"geometry coordinate is outside WGS84 bounds: {lon}, {lat}")
        yield lon, lat
        return
    for child in value:
        yield from iter_coordinate_pairs(child)


def geometry_bounds(geometry: Mapping[str, Any]) -> Tuple[float, float, float, float]:
    geom_type = safe_text(geometry.get("type"))
    if geom_type not in {"Polygon", "MultiPolygon"}:
        raise ValueError(f"unsupported geology geometry type: {geom_type!r}")
    coordinates = geometry.get("coordinates")
    pairs = list(iter_coordinate_pairs(coordinates))
    if len(pairs) < 3:
        raise ValueError("polygon geometry has fewer than three coordinate pairs")
    west = min(pair[0] for pair in pairs)
    east = max(pair[0] for pair in pairs)
    south = min(pair[1] for pair in pairs)
    north = max(pair[1] for pair in pairs)
    return south, west, north, east


def validate_feature(feature: Mapping[str, Any]) -> Dict[str, Any]:
    if feature.get("type") != "Feature":
        raise ValueError("USGS response contained a non-Feature item")
    props = feature.get("properties")
    geometry = feature.get("geometry")
    if not isinstance(props, dict) or not isinstance(geometry, dict):
        raise ValueError("feature is missing properties or polygon geometry")

    try:
        object_id = int(props.get("OBJECTID"))
    except (TypeError, ValueError) as exc:
        raise ValueError("feature is missing a valid OBJECTID") from exc
    if object_id <= 0:
        raise ValueError(f"invalid OBJECTID: {object_id}")
    if safe_text(props.get("STATE")).upper() != "CO":
        raise ValueError(f"OBJECTID {object_id} is not a Colorado record")

    south, west, north, east = geometry_bounds(geometry)
    geom_json = canonical_json(geometry)

    row = {
        "object_id": object_id,
        "state": "CO",
        "orig_label": safe_text(props.get("ORIG_LABEL")),
        "sgmc_label": safe_text(props.get("SGMC_LABEL")),
        "unit_link": safe_text(props.get("UNIT_LINK")),
        "unit_name": safe_text(props.get("UNIT_NAME")),
        "age_min": safe_text(props.get("AGE_MIN")),
        "age_max": safe_text(props.get("AGE_MAX")),
        "generalized_lith": safe_text(props.get("GENERALIZED_LITH")),
        "major1": safe_text(props.get("MAJOR1")),
        "major2": safe_text(props.get("MAJOR2")),
        "major3": safe_text(props.get("MAJOR3")),
        "minor1": safe_text(props.get("MINOR1")),
        "minor2": safe_text(props.get("MINOR2")),
        "minor3": safe_text(props.get("MINOR3")),
        "minor4": safe_text(props.get("MINOR4")),
        "minor5": safe_text(props.get("MINOR5")),
        "incidental": safe_text(props.get("INCIDENTAL")),
        "indeterminate": safe_text(props.get("INDETERMINATE")),
        "ref_id": safe_text(props.get("REF_ID")),
        "reference_text": safe_text(props.get("REFERENCE")),
        "digital_url": safe_text(props.get("DIGITAL_URL")),
        "ngmdb1": safe_text(props.get("NGMDB1")),
        "ngmdb2": safe_text(props.get("NGMDB2")),
        "ngmdb3": safe_text(props.get("NGMDB3")),
        "rgba": safe_text(props.get("rgba")),
        "south": south,
        "west": west,
        "north": north,
        "east": east,
        "geometry_json": geom_json,
        "search_text": normalized_join(props, TEXT_FIELDS),
        "lithology_text": normalized_join(props, LITH_FIELDS),
        "age_text": normalized_join(props, AGE_FIELDS),
    }
    return row


def _read_limited(response, limit: int = MAX_HTTP_BYTES) -> bytes:
    output = bytearray()
    while True:
        chunk = response.read(128 * 1024)
        if not chunk:
            break
        output.extend(chunk)
        if len(output) > limit:
            raise RuntimeError(f"USGS response exceeded {limit} bytes")
    return bytes(output)


def request_json(url: str, params: Mapping[str, Any], attempts: int = 5) -> Dict[str, Any]:
    query = urllib.parse.urlencode(params, doseq=True, safe="',")
    full_url = url + "?" + query
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        request = urllib.request.Request(
            full_url,
            headers={
                "Accept": "application/json, application/geo+json",
                "User-Agent": USER_AGENT,
                "Cache-Control": "no-cache",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                body = _read_limited(response)
            payload = json.loads(body.decode("utf-8"))
            if not isinstance(payload, dict):
                raise RuntimeError("USGS response was not a JSON object")
            if "error" in payload:
                raise RuntimeError("USGS ArcGIS error: " + canonical_json(payload["error"]))
            return payload
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, json.JSONDecodeError, RuntimeError) as exc:
            last_error = exc
            if attempt == attempts:
                break
            time.sleep(min(8, 2 ** (attempt - 1)))
    raise RuntimeError(f"USGS request failed after {attempts} attempts: {last_error}")


def fetch_object_ids() -> List[int]:
    payload = request_json(
        QUERY_URL,
        {
            "f": "json",
            "where": SOURCE_WHERE,
            "returnIdsOnly": "true",
            "returnGeometry": "false",
        },
    )
    raw_ids = payload.get("objectIds")
    if not isinstance(raw_ids, list):
        raise RuntimeError("USGS ID query did not return objectIds")
    ids: List[int] = []
    for raw in raw_ids:
        try:
            value = int(raw)
        except (TypeError, ValueError) as exc:
            raise RuntimeError(f"USGS returned an invalid OBJECTID: {raw!r}") from exc
        if value <= 0:
            raise RuntimeError(f"USGS returned an invalid OBJECTID: {value}")
        ids.append(value)
    unique = sorted(set(ids))
    if len(unique) != len(ids):
        raise RuntimeError("USGS returned duplicate Colorado OBJECTIDs")
    if not (MIN_EXPECTED_RECORDS <= len(unique) <= MAX_EXPECTED_RECORDS):
        raise RuntimeError(
            f"Colorado geology record count {len(unique)} is outside the fail-closed range "
            f"{MIN_EXPECTED_RECORDS}..{MAX_EXPECTED_RECORDS}"
        )
    return unique


def fetch_features(ids: Sequence[int], batch_size: int = DEFAULT_BATCH_SIZE) -> List[Dict[str, Any]]:
    if batch_size < 1 or batch_size > 1000:
        raise ValueError("batch_size must be between 1 and 1000")
    expected = set(ids)
    seen: Dict[int, Dict[str, Any]] = {}
    out_fields = ",".join(FIELDS)
    for offset in range(0, len(ids), batch_size):
        batch = ids[offset : offset + batch_size]
        payload = request_json(
            QUERY_URL,
            {
                "f": "geojson",
                "objectIds": ",".join(str(value) for value in batch),
                "outFields": out_fields,
                "returnGeometry": "true",
                "outSR": "4326",
            },
        )
        features = payload.get("features")
        if not isinstance(features, list):
            raise RuntimeError(f"USGS batch beginning {batch[0]} did not return a GeoJSON feature list")
        batch_expected = set(batch)
        batch_seen = set()
        for feature in features:
            row = validate_feature(feature)
            object_id = row["object_id"]
            if object_id not in batch_expected:
                raise RuntimeError(f"USGS returned unexpected OBJECTID {object_id} in an explicit-ID batch")
            if object_id in batch_seen or object_id in seen:
                raise RuntimeError(f"USGS returned duplicate feature OBJECTID {object_id}")
            batch_seen.add(object_id)
            seen[object_id] = row
        if batch_seen != batch_expected:
            missing = sorted(batch_expected - batch_seen)
            raise RuntimeError(f"USGS explicit-ID batch was incomplete; missing OBJECTIDs {missing[:20]}")
        print(f"Fetched {min(offset + len(batch), len(ids))}/{len(ids)} Colorado geology areas", flush=True)
    if set(seen) != expected:
        missing = sorted(expected - set(seen))
        extra = sorted(set(seen) - expected)
        raise RuntimeError(f"Final feature set mismatch; missing={missing[:20]} extra={extra[:20]}")
    return [seen[value] for value in sorted(seen)]


SCHEMA_SQL = """
CREATE TABLE metadata (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);
CREATE TABLE units (
    object_id INTEGER PRIMARY KEY NOT NULL,
    state TEXT NOT NULL,
    orig_label TEXT NOT NULL,
    sgmc_label TEXT NOT NULL,
    unit_link TEXT NOT NULL,
    unit_name TEXT NOT NULL,
    age_min TEXT NOT NULL,
    age_max TEXT NOT NULL,
    generalized_lith TEXT NOT NULL,
    major1 TEXT NOT NULL,
    major2 TEXT NOT NULL,
    major3 TEXT NOT NULL,
    minor1 TEXT NOT NULL,
    minor2 TEXT NOT NULL,
    minor3 TEXT NOT NULL,
    minor4 TEXT NOT NULL,
    minor5 TEXT NOT NULL,
    incidental TEXT NOT NULL,
    indeterminate TEXT NOT NULL,
    ref_id TEXT NOT NULL,
    reference_text TEXT NOT NULL,
    digital_url TEXT NOT NULL,
    ngmdb1 TEXT NOT NULL,
    ngmdb2 TEXT NOT NULL,
    ngmdb3 TEXT NOT NULL,
    rgba TEXT NOT NULL,
    south REAL NOT NULL,
    west REAL NOT NULL,
    north REAL NOT NULL,
    east REAL NOT NULL,
    geometry_json TEXT NOT NULL,
    search_text TEXT NOT NULL,
    lithology_text TEXT NOT NULL,
    age_text TEXT NOT NULL,
    CHECK (state = 'CO'),
    CHECK (south <= north),
    CHECK (west <= east)
);
CREATE INDEX idx_units_bounds_south_north ON units(south, north);
CREATE INDEX idx_units_bounds_west_east ON units(west, east);
CREATE INDEX idx_units_unit_name ON units(unit_name COLLATE NOCASE);
CREATE INDEX idx_units_generalized_lith ON units(generalized_lith COLLATE NOCASE);
"""

INSERT_COLUMNS = [
    "object_id", "state", "orig_label", "sgmc_label", "unit_link", "unit_name",
    "age_min", "age_max", "generalized_lith", "major1", "major2", "major3",
    "minor1", "minor2", "minor3", "minor4", "minor5", "incidental", "indeterminate",
    "ref_id", "reference_text", "digital_url", "ngmdb1", "ngmdb2", "ngmdb3", "rgba",
    "south", "west", "north", "east", "geometry_json", "search_text", "lithology_text",
    "age_text",
]


def build_database(rows: Sequence[Mapping[str, Any]], output: Path, built_at: str) -> None:
    if not (MIN_EXPECTED_RECORDS <= len(rows) <= MAX_EXPECTED_RECORDS):
        # Unit tests may intentionally use tiny synthetic datasets. They call with ROCKMAP_TEST_SMALL_DB=1.
        if os.environ.get("ROCKMAP_TEST_SMALL_DB") != "1":
            raise ValueError(f"refusing to build unreasonable geology row count: {len(rows)}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_name(output.name + ".part")
    temp.unlink(missing_ok=True)
    connection = sqlite3.connect(str(temp))
    try:
        connection.execute("PRAGMA journal_mode=DELETE")
        connection.execute("PRAGMA synchronous=FULL")
        connection.executescript(SCHEMA_SQL)
        metadata = {
            "schema_version": str(SCHEMA_VERSION),
            "record_count": str(len(rows)),
            "source_title": SOURCE_TITLE,
            "source_doi": SOURCE_DOI,
            "source_scale": SOURCE_SCALE,
            "source_service": LAYER_URL,
            "source_where": SOURCE_WHERE,
            "built_at": built_at,
            # Retained for compatibility with the existing Android getter name.
            "downloaded_at": built_at,
        }
        connection.executemany(
            "INSERT INTO metadata(key,value) VALUES(?,?)",
            sorted(metadata.items()),
        )
        placeholders = ",".join("?" for _ in INSERT_COLUMNS)
        sql = f"INSERT INTO units({','.join(INSERT_COLUMNS)}) VALUES({placeholders})"
        values = [tuple(row[column] for column in INSERT_COLUMNS) for row in rows]
        connection.executemany(sql, values)
        connection.commit()
        integrity = connection.execute("PRAGMA quick_check").fetchone()
        if not integrity or integrity[0] != "ok":
            raise RuntimeError(f"SQLite quick_check failed: {integrity}")
        count = connection.execute("SELECT COUNT(*) FROM units").fetchone()[0]
        if count != len(rows):
            raise RuntimeError(f"SQLite row count mismatch: expected {len(rows)}, got {count}")
        bad_state = connection.execute("SELECT COUNT(*) FROM units WHERE state <> 'CO'").fetchone()[0]
        if bad_state:
            raise RuntimeError(f"SQLite contains {bad_state} non-Colorado rows")
    finally:
        connection.close()
    os.replace(temp, output)


def deterministic_gzip(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_name(target.name + ".part")
    temp.unlink(missing_ok=True)
    with source.open("rb") as src, temp.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as gz:
            shutil.copyfileobj(src, gz, length=1024 * 1024)
        raw.flush()
        os.fsync(raw.fileno())
    os.replace(temp, target)


def package_release(
    rows: Sequence[Mapping[str, Any]],
    output_dir: Path,
    repo: str,
    tag: str,
    version: str,
    built_at: str,
    database_name: str,
) -> Dict[str, Any]:
    if "/" not in repo or repo.startswith("/") or repo.endswith("/"):
        raise ValueError("--repo must be owner/name")
    if not database_name.endswith(".db") or "/" in database_name or "\\" in database_name:
        raise ValueError("--database-name must be a simple .db filename")
    output_dir.mkdir(parents=True, exist_ok=True)
    db_path = output_dir / database_name
    asset_path = output_dir / (database_name + ".gz")
    build_database(rows, db_path, built_at)
    deterministic_gzip(db_path, asset_path)

    db_bytes = db_path.stat().st_size
    asset_bytes = asset_path.stat().st_size
    db_sha = sha256_file(db_path)
    asset_sha = sha256_file(asset_path)
    object_ids = [int(row["object_id"]) for row in rows]
    object_id_hash = hashlib.sha256(
        ("\n".join(str(value) for value in sorted(object_ids)) + "\n").encode("ascii")
    ).hexdigest()

    asset_url = f"https://github.com/{repo}/releases/download/{tag}/{asset_path.name}"
    manifest = {
        "manifestVersion": 1,
        "status": "published",
        "pack": "Colorado Geology",
        "version": version,
        "publishedAt": built_at,
        "minimumAppVersionCode": 1,
        "source": {
            "title": SOURCE_TITLE,
            "doi": SOURCE_DOI,
            "scale": SOURCE_SCALE,
            "service": LAYER_URL,
            "where": SOURCE_WHERE,
            "recordCount": len(rows),
        },
        "asset": {
            "fileName": asset_path.name,
            "url": asset_url,
            "bytes": asset_bytes,
            "sha256": asset_sha,
        },
        "database": {
            "fileName": db_path.name,
            "bytes": db_bytes,
            "sha256": db_sha,
            "schemaVersion": SCHEMA_VERSION,
        },
    }
    manifest_path = output_dir / "geology-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    metadata = {
        "builder": "scripts/build_colorado_geology.py",
        "built_at": built_at,
        "layer_url": LAYER_URL,
        "query_where": SOURCE_WHERE,
        "record_count": len(rows),
        "object_id_sha256": object_id_hash,
        "minimum_object_id": min(object_ids) if object_ids else None,
        "maximum_object_id": max(object_ids) if object_ids else None,
        "database_bytes": db_bytes,
        "database_sha256": db_sha,
        "asset_bytes": asset_bytes,
        "asset_sha256": asset_sha,
        "fields": FIELDS,
    }
    metadata_path = output_dir / "geology-source-metadata.json"
    metadata_path.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    sums = []
    for path in (asset_path, manifest_path, metadata_path):
        sums.append(f"{sha256_file(path)}  {path.name}")
    (output_dir / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8")
    return manifest


def load_geojson_fixture(path: Path) -> List[Dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    features = payload.get("features") if isinstance(payload, dict) else None
    if not isinstance(features, list):
        raise ValueError("fixture must be a GeoJSON FeatureCollection")
    rows = [validate_feature(feature) for feature in features]
    ids = [row["object_id"] for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError("fixture contains duplicate OBJECTIDs")
    return sorted(rows, key=lambda row: row["object_id"])


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, help="GitHub repository owner/name")
    parser.add_argument("--tag", required=True, help="Immutable GitHub release tag")
    parser.add_argument("--version", required=True, help="Geology data version shown in RockMap")
    parser.add_argument("--built-at", required=True, help="ISO-8601 publication/build timestamp")
    parser.add_argument("--database-name", required=True, help="Immutable .db filename")
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--input-geojson", type=Path, help="Offline fixture; tests only")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv or sys.argv[1:])
    if args.input_geojson:
        rows = load_geojson_fixture(args.input_geojson)
    else:
        start_ids = fetch_object_ids()
        print(f"USGS reports {len(start_ids)} Colorado SGMC geology areas", flush=True)
        rows = fetch_features(start_ids, args.batch_size)
        end_ids = fetch_object_ids()
        if start_ids != end_ids:
            raise RuntimeError(
                "USGS Colorado OBJECTID set changed during the build; refusing a mixed-time snapshot"
            )
        if [row["object_id"] for row in rows] != start_ids:
            raise RuntimeError("Validated feature IDs do not exactly match the locked Colorado ID set")

    manifest = package_release(
        rows=rows,
        output_dir=args.output_dir,
        repo=args.repo,
        tag=args.tag,
        version=args.version,
        built_at=args.built_at,
        database_name=args.database_name,
    )
    print(json.dumps(manifest, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
