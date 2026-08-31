#!/usr/bin/env python3
"""
Build a Colorado-only RockMap CNGM production-candidate SQLite pack from the
official USGS Cooperative National Geologic Map (CNGM) Earth's Surface GeMS
geodatabase.

THIS SCRIPT DOES NOT PUBLISH A RELEASE AND DOES NOT MODIFY THE ANDROID APP.

Scientific-source policy
------------------------
The authoritative input for this candidate is the USGS Earth's Surface GeMS
geodatabase (DOI 10.5066/P146VGVM), a derivative of the full CNGM relational
database (DOI 10.5066/P1DC4XFG). The exported GeMS database preserves original
Source_MapUnit values and source Description of Map Units records, DataSources,
and the synthesis-to-source crosswalk.

This builder deliberately does NOT use RockMap string heuristics to invent
lithology, age, mineral occurrences, or scientific confidence. It stores:
  * original source-map unit facts,
  * USGS CNGM synthesis-unit records as a distinct class,
  * original source citations/provenance,
  * exact polygon geometry clipped only to the Colorado state boundary.

The result is a RockMap-specific normalized SQLite candidate intended for
offline use. It is an intermediate migration artifact: Android schema/runtime
changes and immutable release publication happen only after this artifact has
been reviewed.

Upstreams
---------
CNGM Earth's Surface GeMS data release:
  https://doi.org/10.5066/P146VGVM
CNGM full relational geospatial database:
  https://doi.org/10.5066/P1DC4XFG
USGS Data Report 1210:
  https://doi.org/10.3133/dr1210
NGMDB product/download page:
  https://ngmdb.usgs.gov/Prodesc/proddesc_118545.htm
TIGERweb state boundary (clip only):
  https://tigerweb.geo.census.gov/

Runtime dependencies (GitHub Actions installs these):
  GDAL/OGR command line tools, Python 3 standard library.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import html
from html.parser import HTMLParser
import json
import math
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from typing import Any, Dict, Iterable, Iterator, List, Mapping, Optional, Sequence, Tuple

NGMDB_PRODUCT_PAGE = "https://ngmdb.usgs.gov/Prodesc/proddesc_118545.htm"
EARTH_SURFACE_DOI = "10.5066/P146VGVM"
PARENT_DATABASE_DOI = "10.5066/P1DC4XFG"
DATA_REPORT_DOI = "10.3133/dr1210"
SOURCE_TITLE = "USGS Cooperative National Geologic Map: Earth's surface geology"
SOURCE_SCALE = "1:500,000"
SOURCE_SCOPE = "Colorado-only clip of the official Earth's Surface GeMS geodatabase"
TIGER_STATES_QUERY = (
    "https://tigerweb.geo.census.gov/arcgis/rest/services/TIGERweb/"
    "State_County/MapServer/0/query"
)
SCHEMA_VERSION = 2
STATE = "CO"

# These are fail-closed source-package bounds, not expected final phone-pack sizes.
MIN_SOURCE_ARCHIVE_BYTES = 100 * 1024 * 1024
MAX_SOURCE_ARCHIVE_BYTES = 5 * 1024 * 1024 * 1024
MAX_EXTRACTED_BYTES = 10 * 1024 * 1024 * 1024
MIN_POLYGONS = 100
MAX_POLYGONS = 100_000
MAX_CANDIDATE_DB_BYTES = 250 * 1024 * 1024
MAX_CANDIDATE_GZIP_BYTES = 150 * 1024 * 1024
USER_AGENT = "RockMap-CNGM-production-candidate/1.0"

# Official-host allowlist. Redirects are checked too.
ALLOWED_DOWNLOAD_HOSTS = {
    "ngmdb.usgs.gov",
    "data.usgs.gov",
    "pubs.usgs.gov",
    "www.usgs.gov",
    "usgs.gov",
    "sciencebase.gov",
    "www.sciencebase.gov",
    "doi.org",
    "prd-tnm.s3.amazonaws.com",
}

REQUIRED_LOGICAL_LAYERS = {
    "polygons": ("mapunitpolys",),
    "source_units": ("source_descriptionofmapunits",),
    "synthesis_units": ("descriptionofmapunits",),
    "crosswalk": ("synthesis_to_source_units", "synthesistosourceunits"),
    "data_sources": ("datasources",),
}


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


def norm_token(value: str) -> str:
    value = unicodedata.normalize("NFKD", safe_text(value))
    value = value.replace("’", "'").replace("–", "-").replace("—", "-")
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def normalized_search_text(values: Iterable[Any]) -> str:
    seen = set()
    out: List[str] = []
    for raw in values:
        value = " ".join(safe_text(raw).lower().split())
        if value and value not in seen:
            seen.add(value)
            out.append(value)
    return " ".join(out)


def is_allowed_download_url(url: str) -> bool:
    try:
        parsed = urllib.parse.urlparse(url)
    except ValueError:
        return False
    return parsed.scheme == "https" and (parsed.hostname or "").lower() in ALLOWED_DOWNLOAD_HOSTS


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        absolute = urllib.parse.urljoin(req.full_url, newurl)
        if not is_allowed_download_url(absolute):
            raise urllib.error.URLError(f"Refusing redirect to non-whitelisted host: {absolute}")
        return super().redirect_request(req, fp, code, msg, headers, absolute)


def open_allowed(url: str, timeout: int = 120):
    if not is_allowed_download_url(url):
        raise RuntimeError(f"Refusing non-whitelisted source URL: {url}")
    opener = urllib.request.build_opener(SafeRedirect())
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "*/*",
            "Cache-Control": "no-cache",
        },
    )
    return opener.open(request, timeout=timeout)


class LinkCollector(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links: List[Tuple[str, str]] = []
        self._href: Optional[str] = None
        self._text: List[str] = []

    def handle_starttag(self, tag, attrs):
        if tag.lower() != "a":
            return
        href = dict(attrs).get("href")
        self._href = href
        self._text = []

    def handle_data(self, data):
        if self._href is not None:
            self._text.append(data)

    def handle_endtag(self, tag):
        if tag.lower() == "a" and self._href is not None:
            self.links.append((self._href, html.unescape(" ".join(self._text))))
            self._href = None
            self._text = []


def resolve_earth_surface_download(product_page: str = NGMDB_PRODUCT_PAGE) -> str:
    with open_allowed(product_page, timeout=60) as response:
        raw = response.read(4 * 1024 * 1024)
    parser = LinkCollector()
    parser.feed(raw.decode("utf-8", errors="replace"))
    matches: List[str] = []
    for href, label in parser.links:
        normalized = " ".join(label.replace("’", "'").replace("—", "-").replace("–", "-").split()).lower()
        if "gis data" in normalized and "surface geology" in normalized and "earth" in normalized:
            absolute = urllib.parse.urljoin(product_page, href)
            if is_allowed_download_url(absolute):
                matches.append(absolute)
    unique = list(dict.fromkeys(matches))
    if len(unique) != 1:
        raise RuntimeError(
            "Could not resolve exactly one official Earth's Surface GIS-data download link "
            f"from NGMDB; found {len(unique)}. Supply --source-url with the official link."
        )
    return unique[0]


def download_source(url: str, target: Path) -> Dict[str, Any]:
    digest = hashlib.sha256()
    total = 0
    target.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    with open_allowed(url, timeout=180) as response, target.open("wb") as output:
        final_url = response.geturl()
        if not is_allowed_download_url(final_url):
            raise RuntimeError(f"Final download URL is not whitelisted: {final_url}")
        declared = response.headers.get("Content-Length")
        if declared:
            declared_n = int(declared)
            if declared_n < MIN_SOURCE_ARCHIVE_BYTES or declared_n > MAX_SOURCE_ARCHIVE_BYTES:
                raise RuntimeError(f"Official source archive Content-Length is outside safe bounds: {declared_n}")
        while True:
            chunk = response.read(4 * 1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_SOURCE_ARCHIVE_BYTES:
                raise RuntimeError("Official source archive exceeded the fail-closed size limit.")
            output.write(chunk)
            digest.update(chunk)
            if total % (256 * 1024 * 1024) < len(chunk):
                print(f"Downloaded {total / (1024**3):.2f} GiB", flush=True)
    if total < MIN_SOURCE_ARCHIVE_BYTES:
        raise RuntimeError(f"Official source archive is implausibly small: {total} bytes")
    return {
        "requested_url": url,
        "final_url": final_url,
        "bytes": total,
        "sha256": digest.hexdigest(),
        "seconds": round(time.monotonic() - started, 3),
    }


def safe_extract_zip(archive: Path, destination: Path) -> None:
    if not zipfile.is_zipfile(archive):
        raise RuntimeError("Official Earth's Surface package is not a ZIP archive as expected.")
    destination.mkdir(parents=True, exist_ok=True)
    total = 0
    root = destination.resolve()
    with zipfile.ZipFile(archive) as zf:
        for info in zf.infolist():
            total += int(info.file_size)
            if total > MAX_EXTRACTED_BYTES:
                raise RuntimeError("Extracted official source would exceed the fail-closed disk budget.")
            candidate = (destination / info.filename).resolve()
            if root != candidate and root not in candidate.parents:
                raise RuntimeError(f"Unsafe ZIP member path: {info.filename}")
        zf.extractall(destination)


def find_gdb(root: Path) -> Path:
    candidates = sorted(path for path in root.rglob("*.gdb") if path.is_dir())
    if not candidates:
        raise RuntimeError("No Esri .gdb directory found in the official Earth's Surface package.")
    # Prefer a database whose name explicitly mentions surface/earth.
    preferred = [p for p in candidates if "surf" in p.name.lower() or "earth" in p.name.lower()]
    chosen = preferred[0] if len(preferred) == 1 else (candidates[0] if len(candidates) == 1 else None)
    if chosen is None:
        raise RuntimeError("Multiple .gdb directories found and the Earth's Surface geodatabase was ambiguous: "
                           + ", ".join(p.name for p in candidates))
    return chosen


def run(cmd: Sequence[str], *, capture: bool = False) -> str:
    print("+ " + " ".join(str(x) for x in cmd), flush=True)
    proc = subprocess.run(
        list(map(str, cmd)),
        check=True,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )
    return proc.stdout or ""


def parse_ogrinfo_layer_json(text: str) -> List[str]:
    """Parse GDAL 3.7+ ogrinfo -json output and return exact layer/table names."""
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as exc:
        raise RuntimeError("GDAL layer inventory was not valid JSON.") from exc
    raw_layers = payload.get("layers")
    if not isinstance(raw_layers, list):
        raise RuntimeError("GDAL JSON layer inventory did not contain a layers array.")
    layers: List[str] = []
    seen = set()
    for item in raw_layers:
        if not isinstance(item, dict):
            continue
        name = safe_text(item.get("name"))
        if name and name not in seen:
            seen.add(name)
            layers.append(name)
    if not layers:
        raise RuntimeError("GDAL could not enumerate any layers/tables in the official geodatabase.")
    return layers


def list_ogr_layers(gdb: Path) -> List[str]:
    # GDAL 3.8 on ubuntu-24.04 supports -json (introduced in GDAL 3.7).
    # Do not use -q here: quiet mode suppresses the datasource inventory that we need.
    text = run(["ogrinfo", "-ro", "-json", str(gdb)], capture=True)
    return parse_ogrinfo_layer_json(text)


def resolve_layers(names: Sequence[str]) -> Dict[str, str]:
    by_norm = {norm_token(name): name for name in names}
    resolved: Dict[str, str] = {}
    for logical, aliases in REQUIRED_LOGICAL_LAYERS.items():
        found = None
        for alias in aliases:
            token = norm_token(alias)
            if token in by_norm:
                found = by_norm[token]
                break
        if found is None:
            raise RuntimeError(
                f"Official geodatabase is missing required {logical} layer/table. "
                f"Available: {', '.join(names)}"
            )
        resolved[logical] = found
    return resolved


def fetch_colorado_boundary(target: Path) -> None:
    params = urllib.parse.urlencode({
        "where": "STUSAB='CO'",
        "outFields": "STUSAB,NAME",
        "returnGeometry": "true",
        "outSR": "4326",
        "f": "geojson",
    }).encode("utf-8")
    req = urllib.request.Request(
        TIGER_STATES_QUERY,
        data=params,
        method="POST",
        headers={
            "User-Agent": USER_AGENT,
            "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept": "application/geo+json,application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=120) as response:
        raw = response.read(32 * 1024 * 1024)
    payload = json.loads(raw.decode("utf-8"))
    features = payload.get("features") or []
    if len(features) != 1:
        raise RuntimeError(f"Expected one TIGER Colorado boundary feature; got {len(features)}")
    target.write_text(canonical_json(payload), encoding="utf-8")


def export_candidate_inputs(gdb: Path, layers: Mapping[str, str], work: Path) -> Dict[str, Path]:
    boundary = work / "colorado-boundary.geojson"
    fetch_colorado_boundary(boundary)

    polygons = work / "colorado-mapunitpolys.geojsonl"
    run([
        "ogr2ogr", "-overwrite", "-f", "GeoJSONSeq", str(polygons), str(gdb),
        layers["polygons"],
        "-clipsrc", str(boundary),
        "-t_srs", "EPSG:4326",
    ])

    table_dir = work / "tables"
    table_dir.mkdir(parents=True, exist_ok=True)
    outputs = {"polygons": polygons}
    for logical in ("source_units", "synthesis_units", "crosswalk", "data_sources"):
        target = table_dir / f"{logical}.csv"
        # CSV driver output is a directory when using ogr2ogr; write a temporary directory
        # then rename the single emitted CSV deterministically.
        tmp_dir = table_dir / f".{logical}-csv"
        if tmp_dir.exists():
            shutil.rmtree(tmp_dir)
        tmp_dir.mkdir()
        run(["ogr2ogr", "-overwrite", "-f", "CSV", str(tmp_dir), str(gdb), layers[logical],
             "-lco", "LINEFORMAT=LF"])
        emitted = list(tmp_dir.glob("*.csv"))
        if len(emitted) != 1:
            raise RuntimeError(f"Expected one CSV for {logical}; found {len(emitted)}")
        emitted[0].replace(target)
        shutil.rmtree(tmp_dir)
        outputs[logical] = target
    outputs["boundary"] = boundary
    return outputs


def read_csv(path: Path) -> Tuple[List[str], List[Dict[str, str]]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if not reader.fieldnames:
            raise RuntimeError(f"CSV has no header: {path.name}")
        rows = [{k: safe_text(v) for k, v in row.items()} for row in reader]
        return list(reader.fieldnames), rows


def field_index(fieldnames: Sequence[str]) -> Dict[str, str]:
    return {norm_token(name): name for name in fieldnames}


def pick(row: Mapping[str, Any], index: Mapping[str, str], *aliases: str, required: bool = False) -> str:
    for alias in aliases:
        actual = index.get(norm_token(alias))
        if actual is not None:
            return safe_text(row.get(actual))
    if required:
        raise RuntimeError(f"Required field missing. Tried aliases: {aliases}")
    return ""


def row_id(row: Mapping[str, Any], index: Mapping[str, str], *aliases: str) -> str:
    value = pick(row, index, *aliases, required=True)
    if not value:
        raise RuntimeError(f"Required identifier is blank: {aliases}")
    return value


def polygon_bounds(geometry: Mapping[str, Any]) -> Tuple[float, float, float, float]:
    coords = geometry.get("coordinates")
    geom_type = safe_text(geometry.get("type"))
    if geom_type not in {"Polygon", "MultiPolygon"} or not isinstance(coords, list):
        raise RuntimeError(f"Unsupported/missing polygon geometry: {geom_type!r}")
    west = 180.0
    east = -180.0
    south = 90.0
    north = -90.0
    count = 0

    def walk(value):
        nonlocal west, east, south, north, count
        if isinstance(value, list) and len(value) >= 2 and isinstance(value[0], (int, float)) and isinstance(value[1], (int, float)):
            lon = float(value[0])
            lat = float(value[1])
            if not (math.isfinite(lon) and math.isfinite(lat) and -180 <= lon <= 180 and -90 <= lat <= 90):
                raise RuntimeError("Polygon contains an invalid WGS84 coordinate.")
            west = min(west, lon)
            east = max(east, lon)
            south = min(south, lat)
            north = max(north, lat)
            count += 1
            return
        if isinstance(value, list):
            for child in value:
                walk(child)

    walk(coords)
    if count < 3:
        raise RuntimeError("Polygon has too few coordinate pairs.")
    return south, west, north, east


SCHEMA_SQL = """
PRAGMA page_size=4096;
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
PRAGMA temp_store=MEMORY;
PRAGMA foreign_keys=ON;

CREATE TABLE metadata (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
) WITHOUT ROWID;

CREATE TABLE data_sources (
    source_id TEXT PRIMARY KEY NOT NULL,
    citation TEXT NOT NULL,
    url TEXT NOT NULL,
    notes TEXT NOT NULL
) WITHOUT ROWID;

CREATE TABLE source_units (
    id INTEGER PRIMARY KEY,
    source_unit_upstream_id TEXT NOT NULL UNIQUE,
    source_mapunit TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    full_name TEXT NOT NULL,
    source_age_text TEXT NOT NULL,
    description TEXT NOT NULL,
    geomaterial TEXT NOT NULL,
    geomaterial_confidence TEXT NOT NULL,
    label TEXT NOT NULL,
    hierarchy_key TEXT NOT NULL,
    symbol TEXT NOT NULL,
    map_source_id TEXT NOT NULL,
    description_source_id TEXT NOT NULL,
    area_fill_rgb TEXT NOT NULL,
    additional_attributes TEXT NOT NULL,
    source_citation TEXT NOT NULL,
    source_url TEXT NOT NULL,
    search_text TEXT NOT NULL
);

CREATE TABLE synthesis_units (
    id INTEGER PRIMARY KEY,
    synthesis_upstream_id TEXT NOT NULL UNIQUE,
    synthesis_key TEXT NOT NULL UNIQUE,
    mapunit TEXT NOT NULL,
    name TEXT NOT NULL,
    full_name TEXT NOT NULL,
    age_text TEXT NOT NULL,
    description TEXT NOT NULL,
    geomaterial TEXT NOT NULL,
    label TEXT NOT NULL,
    hierarchy_key TEXT NOT NULL,
    symbol TEXT NOT NULL,
    search_attribute TEXT NOT NULL,
    search_operation TEXT NOT NULL,
    search_argument TEXT NOT NULL,
    source_citation TEXT NOT NULL,
    source_url TEXT NOT NULL,
    search_text TEXT NOT NULL
);

CREATE TABLE source_synthesis (
    source_unit_id INTEGER NOT NULL REFERENCES source_units(id),
    synthesis_unit_id INTEGER NOT NULL REFERENCES synthesis_units(id),
    source_unit_upstream_id TEXT NOT NULL,
    synthesis_upstream_id TEXT NOT NULL,
    source_mapunit TEXT NOT NULL,
    mapunit TEXT NOT NULL,
    PRIMARY KEY(source_unit_id, synthesis_unit_id)
) WITHOUT ROWID;

CREATE TABLE polygons (
    id INTEGER PRIMARY KEY,
    upstream_polygon_id TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK(state='CO'),
    source_unit_id INTEGER NOT NULL REFERENCES source_units(id),
    synthesis_unit_id INTEGER REFERENCES synthesis_units(id),
    source_mapunit TEXT NOT NULL,
    mapunit TEXT NOT NULL,
    map_source_id TEXT NOT NULL,
    data_source_id TEXT NOT NULL,
    symbol TEXT NOT NULL,
    south REAL NOT NULL,
    west REAL NOT NULL,
    north REAL NOT NULL,
    east REAL NOT NULL,
    geometry_json TEXT NOT NULL,
    search_text TEXT NOT NULL,
    CHECK(south <= north),
    CHECK(west <= east)
);

CREATE INDEX idx_polygons_bounds_south_north ON polygons(south, north);
CREATE INDEX idx_polygons_bounds_west_east ON polygons(west, east);
CREATE INDEX idx_polygons_source_unit ON polygons(source_unit_id);
CREATE INDEX idx_polygons_synthesis_unit ON polygons(synthesis_unit_id);
CREATE INDEX idx_source_units_name ON source_units(name COLLATE NOCASE);
CREATE INDEX idx_source_units_geomaterial ON source_units(geomaterial COLLATE NOCASE);
CREATE INDEX idx_synthesis_units_name ON synthesis_units(name COLLATE NOCASE);
"""


def make_source_records(csv_path: Path) -> Tuple[List[Dict[str, str]], Dict[str, Dict[str, str]]]:
    fields, rows = read_csv(csv_path)
    idx = field_index(fields)
    records = []
    by_mapunit: Dict[str, Dict[str, str]] = {}
    for row in rows:
        source_mapunit = pick(row, idx, "Source_MapUnit", "source_mapunit")
        if not source_mapunit:
            # GeMS DMU heading/group rows do not represent polygon map units.
            continue
        upstream_id = row_id(
            row, idx,
            "Source_DescriptionOfMapUnits_ID", "Source_DescriptionOfMapUnitsID",
            "source_descriptionofmapunits_id",
        )
        rec = {
            "source_unit_upstream_id": upstream_id,
            "source_mapunit": source_mapunit,
            "name": pick(row, idx, "Name"),
            "full_name": pick(row, idx, "FullName", "Full_Name"),
            "source_age_text": pick(row, idx, "Age"),
            "description": pick(row, idx, "Description"),
            "geomaterial": pick(row, idx, "GeoMaterial", "Geomaterial"),
            "geomaterial_confidence": pick(row, idx, "GeoMaterialConfidence", "GeomaterialConfidence"),
            "label": pick(row, idx, "Label"),
            "hierarchy_key": pick(row, idx, "HierarchyKey"),
            "symbol": pick(row, idx, "Symbol"),
            "map_source_id": pick(row, idx, "MapSourceID"),
            "description_source_id": pick(row, idx, "DescriptionSourceID"),
            "area_fill_rgb": pick(row, idx, "AreaFillRGB"),
            "additional_attributes": pick(row, idx, "Additional_Attributes", "AdditionalAttributes"),
        }
        if source_mapunit in by_mapunit:
            raise RuntimeError(f"Duplicate Source_MapUnit in official source DMU: {source_mapunit}")
        records.append(rec)
        by_mapunit[source_mapunit] = rec
    return records, by_mapunit


def make_synthesis_records(csv_path: Path) -> Tuple[List[Dict[str, str]], Dict[str, Dict[str, str]]]:
    fields, rows = read_csv(csv_path)
    idx = field_index(fields)
    records = []
    by_mapunit: Dict[str, Dict[str, str]] = {}
    for row in rows:
        mapunit = pick(row, idx, "MapUnit", required=True)
        if not mapunit:
            # GeMS DMU can include heading rows. They are not polygon units.
            continue
        raw_id = row_id(row, idx, "DescriptionOfMapUnits_ID", "DescriptionOfMapUnitsID")
        # Earth Surface is one synthesis layer, but keep a stable composite key anyway.
        key = raw_id or mapunit
        rec = {
            "synthesis_upstream_id": raw_id,
            "synthesis_key": key,
            "mapunit": mapunit,
            "name": pick(row, idx, "Name"),
            "full_name": pick(row, idx, "FullName", "Full_Name"),
            "age_text": pick(row, idx, "Age"),
            "description": pick(row, idx, "Description"),
            "geomaterial": pick(row, idx, "GeoMaterial", "Geomaterial"),
            "label": pick(row, idx, "Label"),
            "hierarchy_key": pick(row, idx, "HierarchyKey"),
            "symbol": pick(row, idx, "Symbol"),
            "search_attribute": pick(row, idx, "Search_Attribute", "SearchAttribute"),
            "search_operation": pick(row, idx, "Search_Operation", "SearchOperation"),
            "search_argument": pick(row, idx, "Search_Argument", "SearchArgument"),
        }
        # If MapUnit repeats, this candidate cannot safely infer which row a polygon references.
        if mapunit in by_mapunit:
            raise RuntimeError(f"Duplicate synthesis MapUnit in Earth's Surface DMU: {mapunit}")
        records.append(rec)
        by_mapunit[mapunit] = rec
    return records, by_mapunit


def make_data_sources(csv_path: Path) -> Dict[str, Dict[str, str]]:
    fields, rows = read_csv(csv_path)
    idx = field_index(fields)
    out: Dict[str, Dict[str, str]] = {}
    for row in rows:
        source_id = pick(row, idx, "DataSources_ID", "DataSource_ID", "DataSourceID", "SourceID")
        if not source_id:
            continue
        citation = pick(row, idx, "Source", "Citation", "Reference")
        url = pick(row, idx, "URL", "Url", "DigitalURL")
        notes = pick(row, idx, "Notes")
        out[source_id] = {
            "source_id": source_id,
            "citation": citation,
            "url": url,
            "notes": notes,
        }
    if not out:
        raise RuntimeError("Official DataSources table contained no identifiable records.")
    return out


def make_crosswalk(csv_path: Path) -> List[Dict[str, str]]:
    fields, rows = read_csv(csv_path)
    idx = field_index(fields)
    out: List[Dict[str, str]] = []
    seen = set()
    for row in rows:
        source_mapunit = pick(row, idx, "Source_MapUnit", "source_mapunit")
        mapunit = pick(row, idx, "MapUnit", "mapunit")
        source_upstream = pick(
            row, idx,
            "Source_DescriptionOfMapUnitsID", "Source_DescriptionOfMapUnits_ID",
            "source_descriptionofmapunits_id",
        )
        synthesis_upstream = pick(
            row, idx,
            "DescriptionOfMapUnitsID", "DescriptionOfMapUnits_ID",
            "descriptionofmapunits_id",
        )
        if not source_mapunit or not mapunit:
            continue
        key = (source_mapunit, mapunit, source_upstream, synthesis_upstream)
        if key not in seen:
            seen.add(key)
            out.append({
                "source_mapunit": source_mapunit,
                "mapunit": mapunit,
                "source_unit_upstream_id": source_upstream,
                "synthesis_upstream_id": synthesis_upstream,
            })
    if not out:
        raise RuntimeError("Official synthesis-to-source crosswalk contained no usable rows.")
    return out


def citation_for(source_id: str, sources: Mapping[str, Dict[str, str]]) -> Tuple[str, str]:
    rec = sources.get(source_id)
    if not rec:
        return "", ""
    return rec.get("citation", ""), rec.get("url", "")


def build_candidate(inputs: Mapping[str, Path], output_db: Path, *, built_at: str,
                    source_download: Mapping[str, Any]) -> Dict[str, Any]:
    source_records, source_by_key = make_source_records(inputs["source_units"])
    synthesis_records, synth_by_key = make_synthesis_records(inputs["synthesis_units"])
    data_sources = make_data_sources(inputs["data_sources"])
    crosswalk = make_crosswalk(inputs["crosswalk"])

    # First pass polygons: determine exactly which source/synthesis units Colorado references.
    polygon_rows: List[Dict[str, Any]] = []
    used_sources = set()
    used_synth = set()
    polygon_field_index = None

    with inputs["polygons"].open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            feature = json.loads(line.lstrip("\x1e"))
            if feature.get("type") != "Feature":
                raise RuntimeError(f"Polygon export line {line_no} is not a GeoJSON Feature.")
            props = feature.get("properties")
            geom = feature.get("geometry")
            if not isinstance(props, dict) or not isinstance(geom, dict):
                raise RuntimeError(f"Polygon export line {line_no} is missing properties/geometry.")
            if polygon_field_index is None:
                polygon_field_index = field_index(list(props.keys()))
            idx = polygon_field_index

            source_mapunit = pick(props, idx, "Source_MapUnit", "source_mapunit", required=True)
            mapunit = pick(props, idx, "MapUnit", "mapunit")
            if source_mapunit not in source_by_key:
                raise RuntimeError(f"Colorado polygon references missing Source_MapUnit: {source_mapunit}")
            if mapunit and mapunit not in synth_by_key:
                raise RuntimeError(f"Colorado polygon references missing synthesis MapUnit: {mapunit}")

            upstream_id = pick(
                props, idx,
                "MapUnitPolys_ID", "MapUnitPolysID", "mapunitpolys_id",
                "OBJECTID", "ObjectID", "FID",
            )
            if not upstream_id:
                # Stable deterministic fallback based on source identifiers + geometry.
                upstream_id = hashlib.sha256(
                    (source_mapunit + "\x1f" + mapunit + "\x1f" + canonical_json(geom)).encode("utf-8")
                ).hexdigest()

            south, west, north, east = polygon_bounds(geom)
            map_source_id = pick(props, idx, "MapSourceID")
            data_source_id = pick(props, idx, "DataSourceID")
            symbol = pick(props, idx, "Symbol", "FGDC_Symbol")
            source_rec = source_by_key[source_mapunit]
            synth_rec = synth_by_key.get(mapunit)
            polygon_rows.append({
                "upstream_polygon_id": upstream_id,
                "source_mapunit": source_mapunit,
                "mapunit": mapunit,
                "map_source_id": map_source_id,
                "data_source_id": data_source_id,
                "symbol": symbol,
                "south": south,
                "west": west,
                "north": north,
                "east": east,
                "geometry_json": canonical_json(geom),
                "search_text": normalized_search_text([
                    source_rec["name"], source_rec["full_name"], source_rec["source_age_text"],
                    source_rec["description"], source_rec["geomaterial"],
                    synth_rec["name"] if synth_rec else "",
                    synth_rec["description"] if synth_rec else "",
                ]),
            })
            used_sources.add(source_mapunit)
            if mapunit:
                used_synth.add(mapunit)

    if not (MIN_POLYGONS <= len(polygon_rows) <= MAX_POLYGONS):
        raise RuntimeError(
            f"Colorado polygon count {len(polygon_rows)} outside fail-closed range "
            f"{MIN_POLYGONS}..{MAX_POLYGONS}"
        )

    used_source_records = [source_by_key[k] for k in sorted(used_sources)]
    used_synth_records = [synth_by_key[k] for k in sorted(used_synth)]

    # Restrict crosswalk to source/synthesis rows actually relevant to Colorado.
    relevant_crosswalk = [
        row for row in crosswalk
        if row["source_mapunit"] in used_sources and row["mapunit"] in used_synth
    ]
    crosswalk_sources = {row["source_mapunit"] for row in relevant_crosswalk}
    missing_crosswalk = sorted(
        row["source_mapunit"] for row in polygon_rows
        if row["mapunit"] and row["source_mapunit"] not in crosswalk_sources
    )
    if missing_crosswalk:
        raise RuntimeError(
            "Colorado polygons with synthesis units are missing from the official crosswalk; "
            f"first examples: {missing_crosswalk[:10]}"
        )

    # Collect only source citations referenced by used source records or Colorado polygons.
    used_data_source_ids = set()
    for rec in used_source_records:
        used_data_source_ids.update(x for x in (rec["map_source_id"], rec["description_source_id"]) if x)
    for rec in polygon_rows:
        used_data_source_ids.update(x for x in (rec["map_source_id"], rec["data_source_id"]) if x)

    output_db.parent.mkdir(parents=True, exist_ok=True)
    if output_db.exists():
        output_db.unlink()
    db = sqlite3.connect(output_db)
    try:
        db.executescript(SCHEMA_SQL)
        metadata = {
            "schema_version": str(SCHEMA_VERSION),
            "state": STATE,
            "source_title": SOURCE_TITLE,
            "source_doi": EARTH_SURFACE_DOI,
            "parent_database_doi": PARENT_DATABASE_DOI,
            "data_report_doi": DATA_REPORT_DOI,
            "source_scale": SOURCE_SCALE,
            "source_scope": SOURCE_SCOPE,
            "built_at": built_at,
            "polygon_count": str(len(polygon_rows)),
            "source_unit_count": str(len(used_source_records)),
            "synthesis_unit_count": str(len(used_synth_records)),
            "crosswalk_count": str(len(relevant_crosswalk)),
            "geometry_storage": "GeoJSON WGS84; exact source geometry except Colorado boundary clipping",
            "scientific_interpretation_policy": (
                "No RockMap-generated geologic interpretation. Source facts and CNGM synthesis "
                "records are stored separately."
            ),
            "source_archive_sha256": safe_text(source_download.get("sha256")),
            "source_archive_bytes": str(source_download.get("bytes", "")),
            "source_download_url": safe_text(source_download.get("final_url") or source_download.get("requested_url")),
        }
        db.executemany(
            "INSERT INTO metadata(key,value) VALUES(?,?)",
            sorted(metadata.items()),
        )

        for source_id in sorted(used_data_source_ids):
            rec = data_sources.get(source_id)
            if rec is None:
                # Fail closed: a referenced source ID must resolve.
                raise RuntimeError(f"Referenced DataSource ID is missing from official DataSources table: {source_id}")
            db.execute(
                "INSERT INTO data_sources(source_id,citation,url,notes) VALUES(?,?,?,?)",
                (source_id, rec["citation"], rec["url"], rec["notes"]),
            )

        source_ids: Dict[str, int] = {}
        for rec in used_source_records:
            citation, url = citation_for(
                rec["description_source_id"] or rec["map_source_id"], data_sources
            )
            search_text = normalized_search_text([
                rec["source_mapunit"], rec["name"], rec["full_name"], rec["source_age_text"],
                rec["description"], rec["geomaterial"], citation
            ])
            cur = db.execute(
                """INSERT INTO source_units(
                    source_unit_upstream_id,source_mapunit,name,full_name,source_age_text,description,geomaterial,
                    geomaterial_confidence,label,hierarchy_key,symbol,map_source_id,
                    description_source_id,area_fill_rgb,additional_attributes,source_citation,source_url,search_text
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    rec["source_unit_upstream_id"], rec["source_mapunit"], rec["name"], rec["full_name"], rec["source_age_text"],
                    rec["description"], rec["geomaterial"], rec["geomaterial_confidence"],
                    rec["label"], rec["hierarchy_key"], rec["symbol"], rec["map_source_id"],
                    rec["description_source_id"], rec["area_fill_rgb"], rec["additional_attributes"], citation, url, search_text,
                ),
            )
            source_ids[rec["source_mapunit"]] = int(cur.lastrowid)

        synth_ids: Dict[str, int] = {}
        for rec in used_synth_records:
            # Synthesis rows are CNGM derivative records. Citation defaults to CNGM Earth Surface.
            citation = (
                "Colgan, J.P., Johnstone, S.A., Campos, J.M., Platt, B.W., Hirtz, J.A., "
                "and Barrette, N.C., 2025, Cooperative National Geologic Map: Earth's surface geology."
            )
            source_url = "https://doi.org/" + EARTH_SURFACE_DOI
            search_text = normalized_search_text([
                rec["mapunit"], rec["name"], rec["full_name"], rec["age_text"],
                rec["description"], rec["geomaterial"], citation
            ])
            cur = db.execute(
                """INSERT INTO synthesis_units(
                    synthesis_upstream_id,synthesis_key,mapunit,name,full_name,age_text,description,geomaterial,
                    label,hierarchy_key,symbol,search_attribute,search_operation,search_argument,
                    source_citation,source_url,search_text
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    rec["synthesis_upstream_id"], rec["synthesis_key"], rec["mapunit"], rec["name"], rec["full_name"],
                    rec["age_text"], rec["description"], rec["geomaterial"], rec["label"],
                    rec["hierarchy_key"], rec["symbol"], rec["search_attribute"], rec["search_operation"],
                    rec["search_argument"], citation, source_url, search_text,
                ),
            )
            synth_ids[rec["mapunit"]] = int(cur.lastrowid)

        for link in relevant_crosswalk:
            src = link["source_mapunit"]
            syn = link["mapunit"]
            source_upstream = link["source_unit_upstream_id"] or source_by_key[src]["source_unit_upstream_id"]
            synthesis_upstream = link["synthesis_upstream_id"] or synth_by_key[syn]["synthesis_upstream_id"]
            if source_upstream != source_by_key[src]["source_unit_upstream_id"]:
                raise RuntimeError(f"Crosswalk source ID mismatch for {src}")
            if synthesis_upstream != synth_by_key[syn]["synthesis_upstream_id"]:
                raise RuntimeError(f"Crosswalk synthesis ID mismatch for {syn}")
            db.execute(
                """INSERT OR IGNORE INTO source_synthesis(
                    source_unit_id,synthesis_unit_id,source_unit_upstream_id,synthesis_upstream_id,
                    source_mapunit,mapunit
                ) VALUES(?,?,?,?,?,?)""",
                (source_ids[src], synth_ids[syn], source_upstream, synthesis_upstream, src, syn),
            )

        seen_upstream = set()
        for rec in polygon_rows:
            upstream = rec["upstream_polygon_id"]
            if upstream in seen_upstream:
                raise RuntimeError(f"Duplicate Colorado upstream polygon identifier: {upstream}")
            seen_upstream.add(upstream)
            db.execute(
                """INSERT INTO polygons(
                    upstream_polygon_id,state,source_unit_id,synthesis_unit_id,source_mapunit,mapunit,
                    map_source_id,data_source_id,symbol,south,west,north,east,geometry_json,search_text
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    upstream, STATE, source_ids[rec["source_mapunit"]],
                    synth_ids.get(rec["mapunit"]), rec["source_mapunit"], rec["mapunit"],
                    rec["map_source_id"], rec["data_source_id"], rec["symbol"],
                    rec["south"], rec["west"], rec["north"], rec["east"],
                    rec["geometry_json"], rec["search_text"],
                ),
            )

        db.execute("ANALYZE")
        db.commit()
        quick = db.execute("PRAGMA quick_check").fetchone()[0]
        if str(quick).lower() != "ok":
            raise RuntimeError(f"SQLite quick_check failed: {quick}")
    finally:
        db.close()

    if output_db.stat().st_size > MAX_CANDIDATE_DB_BYTES:
        raise RuntimeError(
            f"Candidate SQLite exceeds {MAX_CANDIDATE_DB_BYTES} bytes: {output_db.stat().st_size}"
        )

    return {
        "state": STATE,
        "schema_version": SCHEMA_VERSION,
        "polygons": len(polygon_rows),
        "source_units": len(used_source_records),
        "synthesis_units": len(used_synth_records),
        "crosswalk_rows": len(relevant_crosswalk),
        "data_sources": len(used_data_source_ids),
    }


def gzip_reproducible(source: Path, target: Path) -> None:
    with source.open("rb") as src, target.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as gz:
            shutil.copyfileobj(src, gz, length=1024 * 1024)


def validate_candidate(db_path: Path, gzip_path: Path, metrics: Mapping[str, Any]) -> Dict[str, Any]:
    db_bytes = db_path.stat().st_size
    gz_bytes = gzip_path.stat().st_size
    if gz_bytes > MAX_CANDIDATE_GZIP_BYTES:
        raise RuntimeError(f"Candidate gzip exceeds fail-closed download budget: {gz_bytes}")
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    try:
        assert con.execute("PRAGMA quick_check").fetchone()[0].lower() == "ok"
        meta = dict(con.execute("SELECT key,value FROM metadata"))
        assert meta["schema_version"] == str(SCHEMA_VERSION)
        assert meta["state"] == "CO"
        assert meta["source_doi"] == EARTH_SURFACE_DOI
        assert meta["parent_database_doi"] == PARENT_DATABASE_DOI
        polygon_count = con.execute("SELECT COUNT(*) FROM polygons").fetchone()[0]
        source_count = con.execute("SELECT COUNT(*) FROM source_units").fetchone()[0]
        synth_count = con.execute("SELECT COUNT(*) FROM synthesis_units").fetchone()[0]
        crosswalk_count = con.execute("SELECT COUNT(*) FROM source_synthesis").fetchone()[0]
        wrong_state = con.execute("SELECT COUNT(*) FROM polygons WHERE state <> 'CO'").fetchone()[0]
        missing_geom = con.execute(
            "SELECT COUNT(*) FROM polygons WHERE geometry_json IS NULL OR length(geometry_json) < 20"
        ).fetchone()[0]
        orphan_source = con.execute(
            """SELECT COUNT(*) FROM polygons p LEFT JOIN source_units s ON s.id=p.source_unit_id
               WHERE s.id IS NULL"""
        ).fetchone()[0]
        assert polygon_count == metrics["polygons"]
        assert source_count == metrics["source_units"]
        assert synth_count == metrics["synthesis_units"]
        assert crosswalk_count == metrics["crosswalk_rows"]
        assert wrong_state == 0
        assert missing_geom == 0
        assert orphan_source == 0
    finally:
        con.close()

    return {
        **dict(metrics),
        "sqlite_bytes": db_bytes,
        "sqlite_mib": round(db_bytes / (1024**2), 3),
        "gzip_bytes": gz_bytes,
        "gzip_mib": round(gz_bytes / (1024**2), 3),
        "sqlite_sha256": sha256_file(db_path),
        "gzip_sha256": sha256_file(gzip_path),
    }


def write_summary(output: Path, summary: Mapping[str, Any], source_download: Mapping[str, Any],
                  layers: Mapping[str, str]) -> None:
    (output / "candidate-summary.json").write_text(
        json.dumps({
            "candidate": summary,
            "source_download": dict(source_download),
            "resolved_layers": dict(layers),
            "production_release_approved": False,
            "android_runtime_migrated": False,
        }, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    lines = [
        "# RockMap Colorado CNGM production candidate",
        "",
        "**This is an artifact-only migration checkpoint. It is not a published RockMap data release.**",
        "",
        f"- Source: {SOURCE_TITLE}",
        f"- Earth Surface DOI: {EARTH_SURFACE_DOI}",
        f"- Parent relational database DOI: {PARENT_DATABASE_DOI}",
        f"- Data Report: {DATA_REPORT_DOI}",
        f"- Scale: {SOURCE_SCALE}",
        f"- Colorado polygons: {summary['polygons']:,}",
        f"- Source units used: {summary['source_units']:,}",
        f"- CNGM synthesis units used: {summary['synthesis_units']:,}",
        f"- Source↔synthesis crosswalk rows: {summary['crosswalk_rows']:,}",
        f"- Data-source citations retained: {summary['data_sources']:,}",
        f"- Installed SQLite: {summary['sqlite_mib']:.3f} MiB",
        f"- Compressed candidate: {summary['gzip_mib']:.3f} MiB",
        f"- SQLite SHA-256: `{summary['sqlite_sha256']}`",
        f"- Gzip SHA-256: `{summary['gzip_sha256']}`",
        "",
        "## Science/provenance rules",
        "",
        "- Source map-unit facts remain distinct from CNGM synthesis-unit records.",
        "- Original source citations referenced by Colorado records are retained.",
        "- No RockMap lithology/age/mineral inference is generated by this builder.",
        "- Geometry is not simplified; polygons are clipped only to the Colorado boundary.",
        "- The candidate must be reviewed before Android schema v2 or a public geology release is created.",
        "",
    ]
    (output / "summary.md").write_text("\n".join(lines), encoding="utf-8")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", default="dist-cngm-production-candidate")
    parser.add_argument("--database-name", default="colorado-geology-cngm-candidate-v2.db")
    parser.add_argument("--source-url", default="")
    parser.add_argument("--built-at", default="")
    parser.add_argument("--keep-source", action="store_true")
    args = parser.parse_args(argv)

    output = Path(args.output_dir).resolve()
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)

    work = output / "_work"
    work.mkdir()
    built_at = args.built_at.strip() or time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    source_url = args.source_url.strip() or resolve_earth_surface_download()
    if not is_allowed_download_url(source_url):
        raise RuntimeError(f"Resolved source URL failed host validation: {source_url}")

    archive = work / "cngm-earth-surface-official.zip"
    source_download = download_source(source_url, archive)
    extracted = work / "source"
    safe_extract_zip(archive, extracted)
    gdb = find_gdb(extracted)
    layer_names = list_ogr_layers(gdb)
    layers = resolve_layers(layer_names)
    inputs = export_candidate_inputs(gdb, layers, work)

    db_path = output / args.database_name
    metrics = build_candidate(inputs, db_path, built_at=built_at, source_download=source_download)
    gz_path = output / (args.database_name + ".gz")
    gzip_reproducible(db_path, gz_path)
    summary = validate_candidate(db_path, gz_path, metrics)
    write_summary(output, summary, source_download, layers)

    sha_lines = [
        f"{sha256_file(db_path)}  {db_path.name}",
        f"{sha256_file(gz_path)}  {gz_path.name}",
        f"{sha256_file(output / 'candidate-summary.json')}  candidate-summary.json",
        f"{sha256_file(output / 'summary.md')}  summary.md",
    ]
    (output / "SHA256SUMS.txt").write_text("\n".join(sha_lines) + "\n", encoding="utf-8")

    if not args.keep_source:
        shutil.rmtree(work)

    print((output / "summary.md").read_text(encoding="utf-8"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
