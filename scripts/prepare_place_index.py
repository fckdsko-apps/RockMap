#!/usr/bin/env python3
"""Build RockMap Alpha 6.6's compact APK-bundled Colorado Find index.

The generator runs only during the APK build. It downloads name/coordinate records from
current official USGS National Map Gazetteer layers and, when available, major state-highway
geometry from Colorado DOT, reduces them to a small deterministic gzip TSV, and bundles only
that final index in the APK. Nothing is downloaded or indexed by the Android app at runtime.
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

INDEX_HEADER = "# RockMap place index v3"
GNIS_SERVICE = "https://cartowfs.nationalmap.gov/arcgis/rest/services/geonames/FeatureServer"
CDOT_HIGHWAY_SOURCES = (
    "https://dtdapps.coloradodot.info/arcgis/rest/services/CPLAN/HighwayBackground_RouteSign/MapServer/0",
    "https://dtdapps.coloradodot.info/arcgis/rest/services/CPLAN/HighwaySegments/MapServer/1",
)
COLORADO_BBOX = (-109.10, 36.95, -102.00, 41.05)
PAGE_SIZE = 2000
MAX_SOURCE_FEATURES = 150_000
MAX_HTTP_BYTES = 24 * 1024 * 1024
MIN_INDEX_RECORDS = 2_000
MAX_INDEX_RECORDS = 100_000
MIN_INDEX_BYTES = 20_000
MAX_INDEX_BYTES = 8 * 1024 * 1024

GNIS_LAYERS = {
    0: "Administrative",
    1: "Transportation",
    2: "Landform",
    3: "Hydro Lines",
    4: "Hydro Points",
}


@dataclass
class Record:
    name: str
    kind: str
    context: str
    lat: float
    lon: float
    aliases: set[str] = field(default_factory=set)
    importance: int = 50


def clean(value: object) -> str:
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value).replace("\t", " ").replace("\r", " ").replace("\n", " ")).strip()


def normalize(value: str) -> str:
    text = unicodedata.normalize("NFD", clean(value).lower())
    text = "".join(ch for ch in text if unicodedata.category(ch) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


def safe_field(value: str) -> str:
    return clean(value).replace("|", "/")


def valid_coordinate(lat: float, lon: float) -> bool:
    west, south, east, north = COLORADO_BBOX
    return math.isfinite(lat) and math.isfinite(lon) and south <= lat <= north and west <= lon <= east


def geometry_center(geometry: dict | None) -> tuple[float, float] | None:
    if not isinstance(geometry, dict):
        return None
    points: list[tuple[float, float]] = []
    if isinstance(geometry.get("x"), (int, float)) and isinstance(geometry.get("y"), (int, float)):
        points.append((float(geometry["x"]), float(geometry["y"])))
    for key in ("points", "paths", "rings"):
        value = geometry.get(key)
        if not isinstance(value, list):
            continue

        def walk(item):
            if isinstance(item, list):
                if len(item) >= 2 and isinstance(item[0], (int, float)) and isinstance(item[1], (int, float)):
                    points.append((float(item[0]), float(item[1])))
                else:
                    for child in item:
                        walk(child)
        walk(value)
    if not points:
        return None
    lon = (min(p[0] for p in points) + max(p[0] for p in points)) / 2.0
    lat = (min(p[1] for p in points) + max(p[1] for p in points)) / 2.0
    return (lat, lon) if valid_coordinate(lat, lon) else None


def aliases_for_name(name: str) -> set[str]:
    aliases: set[str] = set()
    mount = re.match(r"(?i)^mount\s+(.+)$", name)
    if mount:
        tail = mount.group(1).strip()
        aliases.update({tail, f"Mt {tail}", f"Mtn {tail}"})
    saint = re.match(r"(?i)^saint\s+(.+)$", name)
    if saint:
        aliases.add(f"St {saint.group(1).strip()}")
    aliases.discard(name)
    return aliases


def gnis_coordinates_usable(value: object) -> bool:
    """GNIS isunknowncoords domain: 1=Yes, 2=No, 0=Unknown.

    Reject only an explicit Yes. The service's normal known-coordinate records are coded 2;
    the previous Alpha 6.6 generator accidentally rejected those and therefore accepted zero
    Colorado records from every USGS layer.
    """
    if value is None:
        return True
    try:
        return int(value) != 1
    except (TypeError, ValueError):
        return True


def classify_gnis(layer_id: int, feature_class: str) -> tuple[str, int]:
    fc = clean(feature_class)
    low = fc.lower()
    exact = {
        "populated place": ("Place", 94),
        "civil": ("Administrative place", 88),
        "census": ("Administrative place", 82),
        "summit": ("Peak", 86),
        "gap": ("Mountain pass / gap", 74),
        "ridge": ("Ridge", 66),
        "range": ("Mountain range", 70),
        "valley": ("Valley", 60),
        "basin": ("Basin", 56),
        "lake": ("Lake", 66),
        "reservoir": ("Reservoir", 68),
        "stream": ("Stream / river", 62),
        "canal": ("Canal", 52),
        "channel": ("Channel", 48),
        "spring": ("Spring", 50),
        "falls": ("Falls", 54),
        "glacier": ("Glacier", 62),
        "trail": ("Trail", 60),
        "park": ("Park", 58),
        "forest": ("Forest", 56),
        "airport": ("Airport", 50),
        "bridge": ("Bridge", 44),
    }
    if low in exact:
        return exact[low]
    if "summit" in low or "peak" in low:
        return "Peak", 82
    if "trail" in low:
        return "Trail", 58
    if any(token in low for token in ("stream", "river", "creek", "arroyo")):
        return "Stream / river", 58
    if any(token in low for token in ("lake", "reservoir", "pond")):
        return "Lake / reservoir", 60
    if layer_id == 0:
        return fc or "Administrative place", 72
    if layer_id == 1:
        return fc or "Transportation feature", 48
    if layer_id == 2:
        return fc or "Landform", 52
    if layer_id == 3:
        return fc or "Waterway", 54
    if layer_id == 4:
        return fc or "Water feature", 54
    return fc or "Place", 50


def http_json(url: str, params: dict[str, object], *, attempts: int = 3) -> dict:
    query = urllib.parse.urlencode(params)
    full = f"{url}?{query}"
    last: Exception | None = None
    for attempt in range(attempts):
        request = urllib.request.Request(full, headers={"User-Agent": "RockMap-Alpha6.6-index-builder/2"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                length = response.headers.get("Content-Length")
                if length and int(length) > MAX_HTTP_BYTES:
                    raise RuntimeError(f"source response too large: {length} bytes")
                raw = response.read(MAX_HTTP_BYTES + 1)
                if len(raw) > MAX_HTTP_BYTES:
                    raise RuntimeError("source response exceeded HTTP safety limit")
            payload = json.loads(raw.decode("utf-8"))
            if not isinstance(payload, dict):
                raise RuntimeError("source returned non-object JSON")
            if "error" in payload:
                raise RuntimeError(f"ArcGIS source error: {payload['error']}")
            return payload
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, RuntimeError, ValueError) as exc:
            last = exc
            if attempt + 1 < attempts:
                time.sleep(2 ** attempt)
    raise RuntimeError(f"failed to query {url}: {last}")


def iter_arcgis(url: str, *, where: str, out_fields: str, geometry_filter: bool = False) -> Iterable[dict]:
    offset = 0
    total = 0
    while True:
        params: dict[str, object] = {
            "f": "json",
            "where": where,
            "outFields": out_fields,
            "returnGeometry": "true",
            "outSR": 4326,
            "resultOffset": offset,
            "resultRecordCount": PAGE_SIZE,
            "orderByFields": "OBJECTID",
            "returnTrueCurves": "false",
            "maxAllowableOffset": 0.002,
            "geometryPrecision": 6,
        }
        if geometry_filter:
            west, south, east, north = COLORADO_BBOX
            params.update({
                "geometry": f"{west},{south},{east},{north}",
                "geometryType": "esriGeometryEnvelope",
                "inSR": 4326,
                "spatialRel": "esriSpatialRelIntersects",
            })
        payload = http_json(url + "/query", params)
        features = payload.get("features") or []
        if not isinstance(features, list):
            raise RuntimeError("ArcGIS source returned malformed features")
        for feature in features:
            if isinstance(feature, dict):
                yield feature
        total += len(features)
        if total > MAX_SOURCE_FEATURES:
            raise RuntimeError("source feature count exceeded safety limit")
        if not features:
            break
        offset += len(features)
        if not payload.get("exceededTransferLimit") and len(features) < PAGE_SIZE:
            break


def load_gnis() -> list[Record]:
    records: list[Record] = []
    fields = "OBJECTID,gaz_id,gaz_name,gaz_featureclass,state_alpha,county_name,isunknowncoords,fcode"
    for layer_id, layer_name in GNIS_LAYERS.items():
        url = f"{GNIS_SERVICE}/{layer_id}"
        count = 0
        for feature in iter_arcgis(url, where="state_alpha LIKE '%CO%'", out_fields=fields):
            attrs = feature.get("attributes") or {}
            if not isinstance(attrs, dict):
                continue
            if not gnis_coordinates_usable(attrs.get("isunknowncoords")):
                continue
            name = clean(attrs.get("gaz_name"))
            if not name:
                continue
            center = geometry_center(feature.get("geometry"))
            if center is None:
                continue
            feature_class = clean(attrs.get("gaz_featureclass"))
            kind, importance = classify_gnis(layer_id, feature_class)
            county = clean(attrs.get("county_name"))
            context = f"{county} County · USGS" if county and not county.lower().endswith("county") else f"{county} · USGS" if county else "USGS National Map Gazetteer"
            records.append(Record(name, kind, context, center[0], center[1], aliases_for_name(name), importance))
            count += 1
        print(f"USGS {layer_name}: {count} Colorado searchable records", flush=True)
    return records


def highway_display(route: str, sign: str) -> tuple[str, set[str]] | None:
    route = clean(route).upper()
    sign = clean(sign).upper()
    if not route:
        return None
    match = re.match(r"0*(\d+)([A-Z]?)$", route)
    if not match:
        return route, {route}
    number = str(int(match.group(1)))
    raw = route
    aliases = {raw, number, f"Highway {number}"}
    if sign == "I":
        display = f"I {number}"
        aliases.update({f"I-{number}", f"Interstate {number}"})
    elif sign in {"U.S.", "US", "U S"}:
        display = f"US {number}"
        aliases.update({f"US-{number}", f"U.S. {number}", f"US Highway {number}"})
    elif sign in {"SH", "STATE", "STATE HIGHWAY"}:
        display = f"CO {number}"
        aliases.update({f"SH {number}", f"State Highway {number}", f"Colorado {number}"})
    else:
        display = f"Highway {number}"
    aliases.discard(display)
    return display, aliases


def load_cdot_highways() -> list[Record]:
    last_error: Exception | None = None
    features: list[dict] | None = None
    fields = "OBJECTID,ROUTE,ROUTESIGN"
    for source in CDOT_HIGHWAY_SOURCES:
        try:
            features = list(iter_arcgis(source, where="1=1", out_fields=fields, geometry_filter=False))
            if features:
                print(f"CDOT highway source selected: {source}", flush=True)
                break
        except Exception as exc:
            last_error = exc
            print(f"CDOT highway source unavailable, trying fallback: {exc}", flush=True)
            features = None
    if not features:
        print(f"CDOT highway enrichment unavailable; continuing with USGS core index: {last_error}", flush=True)
        return []

    grouped: dict[tuple[str, str], dict[str, object]] = {}
    for feature in features:
        attrs = feature.get("attributes") or {}
        if not isinstance(attrs, dict):
            continue
        route = clean(attrs.get("ROUTE"))
        sign = clean(attrs.get("ROUTESIGN"))
        display = highway_display(route, sign)
        if display is None:
            continue
        center = geometry_center(feature.get("geometry"))
        if center is None:
            continue
        name, aliases = display
        key = (normalize(name), sign.upper())
        group = grouped.setdefault(key, {"name": name, "aliases": set(), "lat": [], "lon": []})
        group["aliases"].update(aliases)
        group["lat"].append(center[0])
        group["lon"].append(center[1])
    records: list[Record] = []
    for group in grouped.values():
        lats = group["lat"]
        lons = group["lon"]
        records.append(Record(
            str(group["name"]), "Road / highway", "CDOT state highway",
            sum(lats) / len(lats), sum(lons) / len(lons), set(group["aliases"]), 78,
        ))
    print(f"CDOT highways: {len(records)} route search records", flush=True)
    return records


def dedupe(records: list[Record]) -> list[Record]:
    groups: dict[tuple[str, str, int, int], Record] = {}
    for item in records:
        if not valid_coordinate(item.lat, item.lon) or not clean(item.name):
            continue
        key = (normalize(item.kind), normalize(item.name), round(item.lat * 100), round(item.lon * 100))
        existing = groups.get(key)
        if existing is None:
            groups[key] = item
        else:
            existing.aliases.update(item.aliases)
            existing.importance = max(existing.importance, item.importance)
            if len(item.context) > len(existing.context):
                existing.context = item.context
    return list(groups.values())


def validate(records: list[Record]) -> None:
    if len(records) < MIN_INDEX_RECORDS:
        raise RuntimeError(f"place index unexpectedly small: {len(records)} records")
    if len(records) > MAX_INDEX_RECORDS:
        raise RuntimeError(f"place index unexpectedly large: {len(records)} records")
    names = {normalize(r.name) for r in records}
    for required in ("denver", "buena vista", "mount antero", "twin lakes"):
        if required not in names:
            raise RuntimeError(f"required live USGS Colorado sanity record missing: {required}")
    if not any(r.kind == "Peak" for r in records):
        raise RuntimeError("place index contains no peak records")
    if not any("lake" in r.kind.lower() or "reservoir" in r.kind.lower() or "stream" in r.kind.lower() for r in records):
        raise RuntimeError("place index contains no named water records")


def write_index(records: list[Record], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    records.sort(key=lambda r: (-r.importance, r.name.casefold(), r.kind, r.lat, r.lon))
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0, compresslevel=9) as zipped:
            def write(line: str) -> None:
                zipped.write((line + "\n").encode("utf-8"))
            write(INDEX_HEADER)
            write("# source=USGS National Map Gazetteer; optional Colorado DOT state-highway enrichment; generated during APK build")
            for item in records:
                aliases = sorted({safe_field(a) for a in item.aliases if safe_field(a)}, key=str.casefold)
                write("\t".join([
                    safe_field(item.name), safe_field(item.kind), safe_field(item.context),
                    f"{item.lat:.6f}", f"{item.lon:.6f}", "|".join(aliases), str(item.importance),
                ]))
    size = output.stat().st_size
    if size < MIN_INDEX_BYTES or size > MAX_INDEX_BYTES:
        raise RuntimeError(f"compressed place index size outside safe range: {size} bytes")


def build(output: Path) -> tuple[int, int]:
    gnis_records = load_gnis()
    cdot_records = load_cdot_highways()
    records = dedupe(gnis_records + cdot_records)
    validate(records)
    write_index(records, output)
    print(
        f"RockMap bundled offline Find index: {len(records)} records "
        f"({len(gnis_records)} USGS source records, {len(cdot_records)} CDOT route records); "
        f"{output.stat().st_size} compressed bytes",
        flush=True,
    )
    return len(records), output.stat().st_size


def self_test() -> None:
    point = geometry_center({"x": -106.2462, "y": 38.6741})
    multi = geometry_center({"points": [[-106.30, 38.65], [-106.20, 38.70]]})
    line = geometry_center({"paths": [[[-106.4, 38.5], [-106.0, 38.9]]]})
    assert point == (38.6741, -106.2462)
    assert multi is not None and abs(multi[0] - 38.675) < 1e-9
    assert line is not None and abs(line[1] + 106.2) < 1e-9
    assert gnis_coordinates_usable(2)
    assert gnis_coordinates_usable("2")
    assert not gnis_coordinates_usable(1)
    assert classify_gnis(2, "Summit")[0] == "Peak"
    assert "Mt Antero" in aliases_for_name("Mount Antero")
    assert highway_display("024A", "U.S.")[0] == "US 24"
    assert "Interstate 70" in highway_display("070A", "I")[1]
    fixture = dedupe([
        Record("Mount Antero", "Peak", "Chaffee County · USGS", 38.6741, -106.2462, aliases_for_name("Mount Antero"), 86),
        Record("Buena Vista", "Place", "Chaffee County · USGS", 38.8422, -106.1311, set(), 94),
        Record("Denver", "Place", "Denver County · USGS", 39.7392, -104.9903, set(), 94),
        Record("Twin Lakes", "Lake", "Lake County · USGS", 39.0825, -106.3820, set(), 66),
        Record("US 24", "Road / highway", "CDOT state highway", 39.0, -105.8, {"US Highway 24"}, 78),
    ])
    assert len(fixture) == 5
    print("RockMap bundled place-index generator self-test passed.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.output_dir is None:
        parser.error("--output-dir is required unless --self-test is used")
    output = args.output_dir.resolve() / "rockmap_place_index.tsv.gz"
    build(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
