#!/usr/bin/env python3
"""Build RockMap's compact offline search index from decoded RockMap PMTiles features.

The input must be newline-delimited GeoJSON Features produced by pinned
`tippecanoe-decode -c` from a low/medium-zoom extract of RockMap's own immutable
Colorado basemap PMTiles. This script never contacts the network itself.
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import re
import sys
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

INDEX_HEADER = "# RockMap place index v1"
MAX_RECORDS = 120_000
MAX_COMPRESSED_BYTES = 12 * 1024 * 1024
MAX_INPUT_BYTES = 500 * 1024 * 1024
COLORADO_BOUNDS = (-109.10, 36.95, -102.00, 41.05)
ALLOWED_LAYERS = {"places", "pois", "water", "roads"}


@dataclass
class Candidate:
    name: str
    kind: str
    lat: float
    lon: float
    aliases: set[str] = field(default_factory=set)
    importance: int = 20
    ref: str = ""
    hint: str = ""
    layer: str = ""
    zoom: int = 0


def clean(value: object) -> str:
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value).replace("\t", " ").replace("\r", " ").replace("\n", " ")).strip()


def normalize(value: str) -> str:
    text = unicodedata.normalize("NFD", clean(value).lower())
    text = "".join(ch for ch in text if unicodedata.category(ch) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", text).strip()


def split_aliases(value: str) -> Iterable[str]:
    for item in re.split(r"[;|]", value or ""):
        item = clean(item)
        if item:
            yield item


def geometry_center(geometry: dict | None) -> tuple[float, float] | None:
    if not isinstance(geometry, dict):
        return None
    coords = geometry.get("coordinates")
    if coords is None:
        return None
    points: list[tuple[float, float]] = []

    def walk(value):
        if isinstance(value, (list, tuple)):
            if len(value) >= 2 and isinstance(value[0], (int, float)) and isinstance(value[1], (int, float)):
                lon = float(value[0])
                lat = float(value[1])
                if math.isfinite(lat) and math.isfinite(lon):
                    points.append((lon, lat))
                return
            for child in value:
                walk(child)

    walk(coords)
    if not points:
        return None
    lons = [p[0] for p in points]
    lats = [p[1] for p in points]
    lon = (min(lons) + max(lons)) / 2.0
    lat = (min(lats) + max(lats)) / 2.0
    west, south, east, north = COLORADO_BOUNDS
    if not (south <= lat <= north and west <= lon <= east):
        return None
    return lat, lon


def number(value: object, fallback: float) -> float:
    try:
        parsed = float(value)
        return parsed if math.isfinite(parsed) else fallback
    except (TypeError, ValueError):
        return fallback


def zoom_importance(properties: dict, base: int) -> int:
    # Protomaps' min_zoom/sort_rank are rendering-priority hints. Lower min_zoom means
    # a feature is important enough to survive farther out. Keep the effect modest so
    # exact/fuzzy text quality remains the primary search signal in the Android engine.
    min_zoom = number(properties.get("min_zoom"), 99)
    boost = 0 if min_zoom >= 30 else max(0, min(22, int(round((14 - min_zoom) * 2))))
    return max(1, min(120, base + boost))


def place_classification(properties: dict) -> tuple[str, int, str] | None:
    detail = clean(properties.get("kind_detail")).lower()
    kind = clean(properties.get("kind")).lower()
    mapping = {
        "city": ("City", 100),
        "town": ("Town", 92),
        "village": ("Village", 80),
        "hamlet": ("Hamlet", 68),
        "locality": ("Locality", 58),
        "isolated_dwelling": ("Locality", 44),
        "farm": ("Locality", 42),
        "neighbourhood": ("Neighborhood", 42),
        "quarter": ("Neighborhood", 40),
        "macrohood": ("Neighborhood", 44),
        "region": ("Region", 70),
        "state": ("Region", 72),
    }
    label, base = mapping.get(detail, mapping.get(kind, ("Place", 52)))
    return label, zoom_importance(properties, base), ""


POI_KINDS: dict[str, tuple[str, int]] = {
    "peak": ("Peak", 84),
    "volcano": ("Peak", 82),
    "saddle": ("Mountain pass / saddle", 70),
    "mountain_pass": ("Mountain pass", 74),
    "viewpoint": ("Viewpoint", 62),
    "landmark": ("Landmark", 62),
    "attraction": ("Landmark", 56),
    "national_park": ("Park / protected area", 66),
    "nature_reserve": ("Park / protected area", 62),
    "park": ("Park", 52),
    "camp_site": ("Campground", 58),
    "ranger_station": ("Ranger station", 58),
    "trailhead": ("Trailhead", 62),
    "alpine_hut": ("Hut / shelter", 52),
    "wilderness_hut": ("Hut / shelter", 52),
    "museum": ("Museum", 48),
    "memorial": ("Historic landmark", 48),
    "monument": ("Historic landmark", 50),
    "historic": ("Historic site", 48),
    "archaeological_site": ("Historic site", 48),
    "ruins": ("Historic site", 44),
    "cave_entrance": ("Cave", 46),
    "spring": ("Spring", 44),
}


def poi_classification(properties: dict) -> tuple[str, int, str] | None:
    kind = clean(properties.get("kind")).lower()
    detail = clean(properties.get("kind_detail")).lower()
    picked = POI_KINDS.get(kind) or POI_KINDS.get(detail)
    if picked is None:
        return None
    label, base = picked
    ele = clean(properties.get("ele"))
    hint = f"elev. {ele}" if ele else ""
    return label, zoom_importance(properties, base), hint


def water_classification(properties: dict) -> tuple[str, int, str] | None:
    kind = clean(properties.get("kind")).lower()
    detail = clean(properties.get("kind_detail")).lower()
    reservoir = properties.get("reservoir") is True or clean(properties.get("reservoir")).lower() in {"1", "yes", "true"}
    if reservoir or detail == "reservoir":
        return "Reservoir", zoom_importance(properties, 58), ""
    mapping = {
        "lake": ("Lake", 56),
        "river": ("River", 52),
        "stream": ("Stream / creek", 46),
        "canal": ("Canal", 42),
        "ditch": ("Ditch", 30),
        "drain": ("Drain", 28),
        "basin": ("Basin", 40),
        "water": ("Lake / water", 46),
        "playa": ("Playa", 40),
    }
    picked = mapping.get(detail) or mapping.get(kind)
    if picked is None:
        return None
    label, base = picked
    return label, zoom_importance(properties, base), ""


def road_classification(properties: dict) -> tuple[str, int, str] | None:
    kind = clean(properties.get("kind")).lower()
    detail = clean(properties.get("kind_detail")).lower()
    ref = clean(properties.get("ref")) or clean(properties.get("shield_text"))
    trailish = {"path", "track", "footway", "bridleway", "cycleway", "steps"}
    if kind == "path" or detail in trailish:
        label = "Trail / path" if detail != "track" else "Track"
        return label, zoom_importance(properties, 46), ref
    mapping = {
        "highway": ("Road / highway", 72),
        "major_road": ("Road", 64),
        "medium_road": ("Road", 58),
        "minor_road": ("Road", 46),
        "other": ("Road / path", 36),
    }
    picked = mapping.get(kind)
    if picked is None:
        # Older Protomaps extracts may expose OSM-like kind_detail while the normalized
        # `kind` field is absent or simplified.
        if detail in {"motorway", "trunk", "primary", "secondary", "tertiary"}:
            picked = ("Road / highway", 66)
        elif detail in {"residential", "unclassified", "service", "road"}:
            picked = ("Road", 42)
        else:
            return None
    label, base = picked
    return label, zoom_importance(properties, base), ref


def classify(layer: str, properties: dict) -> tuple[str, int, str] | None:
    if layer == "places":
        return place_classification(properties)
    if layer == "pois":
        return poi_classification(properties)
    if layer == "water":
        return water_classification(properties)
    if layer == "roads":
        return road_classification(properties)
    return None


def synthetic_aliases(name: str, ref: str) -> set[str]:
    aliases: set[str] = set()
    match = re.match(r"(?i)^mount\s+(.+)$", name)
    if match:
        aliases.add(match.group(1))
        aliases.add("Mt " + match.group(1))
        aliases.add("Mtn " + match.group(1))
    match = re.match(r"(?i)^county\s+road\s+(.+)$", name)
    if match:
        aliases.add("CR " + match.group(1))
        aliases.add("Co Rd " + match.group(1))
    if ref:
        for ref_part in split_aliases(ref):
            aliases.add(ref_part)
            highway_ref = re.match(r"(?i)^US\s*(\d+[A-Za-z]?)$", ref_part)
            if highway_ref:
                aliases.add("US Highway " + highway_ref.group(1))
                aliases.add("Highway " + highway_ref.group(1))
            state_ref = re.match(r"(?i)^(?:CO|SH)\s*(\d+[A-Za-z]?)$", ref_part)
            if state_ref:
                aliases.add("Colorado " + state_ref.group(1))
                aliases.add("State Highway " + state_ref.group(1))
    aliases.discard(name)
    return aliases


def iter_features(path: Path):
    if path.stat().st_size > MAX_INPUT_BYTES:
        raise SystemExit(f"Decoded map feature stream exceeds {MAX_INPUT_BYTES} bytes")
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, 1):
            line = line.lstrip("\x1e").strip()
            if not line:
                continue
            # tippecanoe-decode -c is a feature stream, one JSON object per line. Be
            # tolerant of a trailing comma so a future JSON-writer formatting tweak does
            # not turn a safe build into a cryptic parse failure.
            if line.endswith(","):
                line = line[:-1].rstrip()
            try:
                obj = json.loads(line)
            except json.JSONDecodeError as exc:
                raise SystemExit(f"Invalid tippecanoe-decode JSON at line {line_number}: {exc}")
            if isinstance(obj, dict) and obj.get("type") == "Feature":
                yield obj


def read_candidates(path: Path) -> list[Candidate]:
    out: list[Candidate] = []
    for feature in iter_features(path):
        tippecanoe = feature.get("tippecanoe") or {}
        layer = clean(tippecanoe.get("layer"))
        if layer not in ALLOWED_LAYERS:
            continue
        try:
            zoom = int(tippecanoe.get("minzoom", tippecanoe.get("zoom", 0)))
        except (TypeError, ValueError):
            zoom = 0
        properties = feature.get("properties") or {}
        if not isinstance(properties, dict):
            continue
        classification = classify(layer, properties)
        if classification is None:
            continue
        center = geometry_center(feature.get("geometry"))
        if center is None:
            continue
        kind, importance, hint = classification
        name = clean(properties.get("name:en")) or clean(properties.get("name"))
        ref = clean(properties.get("ref")) or clean(properties.get("shield_text"))
        if not name and layer == "roads" and ref:
            name = ref
        if not name:
            continue
        aliases: set[str] = set()
        raw_name = clean(properties.get("name"))
        english_name = clean(properties.get("name:en"))
        if raw_name and raw_name != name:
            aliases.add(raw_name)
        if english_name and english_name != name:
            aliases.add(english_name)
        if ref:
            aliases.update(split_aliases(ref))
        shield = clean(properties.get("shield_text"))
        if shield:
            aliases.update(split_aliases(shield))
        aliases.update(synthetic_aliases(name, ref))
        aliases.discard(name)
        out.append(Candidate(
            name=name,
            kind=kind,
            lat=center[0],
            lon=center[1],
            aliases=aliases,
            importance=importance,
            ref=ref,
            hint=hint,
            layer=layer,
            zoom=zoom,
        ))
    return out


def linearish(item: Candidate) -> bool:
    return item.layer in {"roads", "water"} and item.kind not in {"Lake", "Reservoir", "Lake / water", "Basin", "Playa"}


def grouping_key(item: Candidate) -> tuple:
    name = normalize(item.name)
    if linearish(item):
        # Long roads/rivers can cross a large part of Colorado. Keep at most one search
        # target per ~0.5° cell rather than collapsing a statewide feature to one arbitrary
        # point or preserving every vector-tile fragment.
        return (item.kind, name, round(item.lat * 2) / 2, round(item.lon * 2) / 2)
    return (item.kind, name, round(item.lat, 2), round(item.lon, 2))


def dedupe(candidates: list[Candidate]) -> list[Candidate]:
    groups: dict[tuple, list[Candidate]] = defaultdict(list)
    for item in candidates:
        groups[grouping_key(item)].append(item)
    result: list[Candidate] = []
    for items in groups.values():
        base = max(items, key=lambda x: (x.zoom, x.importance, -len(x.name)))
        aliases: set[str] = set()
        for item in items:
            aliases.update(item.aliases)
            if item.name != base.name:
                aliases.add(item.name)
        result.append(Candidate(
            base.name,
            base.kind,
            sum(x.lat for x in items) / len(items),
            sum(x.lon for x in items) / len(items),
            aliases,
            max(x.importance for x in items),
            base.ref,
            base.hint,
            base.layer,
            max(x.zoom for x in items),
        ))
    return result


def haversine_km(a_lat: float, a_lon: float, b_lat: float, b_lon: float) -> float:
    radius = 6371.0088
    p1 = math.radians(a_lat)
    p2 = math.radians(b_lat)
    dp = math.radians(b_lat - a_lat)
    dl = math.radians(b_lon - a_lon)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return radius * 2 * math.atan2(math.sqrt(h), math.sqrt(max(0.0, 1 - h)))


def add_context(records: list[Candidate]) -> None:
    place_kinds = {"City", "Town", "Village", "Hamlet", "Locality", "Place"}
    grid: dict[tuple[int, int], list[Candidate]] = defaultdict(list)
    for item in records:
        if item.kind in place_kinds:
            grid[(math.floor(item.lat), math.floor(item.lon))].append(item)

    for item in records:
        if item.kind in place_kinds or item.kind == "Region":
            item.hint = "Colorado"
            continue
        best = None
        best_distance = 65.0
        gy, gx = math.floor(item.lat), math.floor(item.lon)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                for place in grid.get((gy + dy, gx + dx), ()):
                    distance = haversine_km(item.lat, item.lon, place.lat, place.lon)
                    if distance < best_distance:
                        best = place
                        best_distance = distance
        pieces = []
        if item.hint:
            pieces.append(item.hint)
        if best is not None and normalize(best.name) != normalize(item.name):
            pieces.append(f"near {best.name}")
        item.hint = " · ".join(pieces)


def safe_field(value: str) -> str:
    return clean(value).replace("|", "/")


def write_index(records: list[Candidate], output: Path, source_label: str) -> None:
    records.sort(key=lambda x: (-x.importance, x.name.casefold(), x.kind, x.lat, x.lon))
    if len(records) > MAX_RECORDS:
        raise SystemExit(f"Place index has {len(records)} records, above safe maximum {MAX_RECORDS}.")
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as zipped:
            def write(line: str):
                zipped.write((line + "\n").encode("utf-8"))
            write(INDEX_HEADER)
            write("# source=" + safe_field(source_label))
            for item in records:
                aliases = sorted({safe_field(a) for a in item.aliases if safe_field(a)}, key=str.casefold)
                row = "\t".join([
                    safe_field(item.name), safe_field(item.kind), safe_field(item.hint),
                    f"{item.lat:.6f}", f"{item.lon:.6f}", "|".join(aliases), str(item.importance),
                ])
                write(row)


def validate(records: list[Candidate], output: Path) -> None:
    if len(records) < 500:
        raise SystemExit(f"Place index unexpectedly small: {len(records)} records")
    names = {normalize(item.name) for item in records}
    for required in ("denver", "buena vista", "mount antero"):
        if required not in names:
            raise SystemExit(f"Required RockMap basemap search sanity record missing: {required}")
    if not any(item.kind == "Peak" for item in records):
        raise SystemExit("Place index contains no Peak records")
    if not any(item.layer == "water" for item in records):
        raise SystemExit("Place index contains no named water records")
    if not any(item.layer == "roads" for item in records):
        raise SystemExit("Place index contains no named road records")
    if not output.is_file() or output.stat().st_size < 10_000:
        raise SystemExit("Compressed place index is missing or unexpectedly small.")
    if output.stat().st_size > MAX_COMPRESSED_BYTES:
        raise SystemExit(f"Compressed place index exceeds 12 MiB: {output.stat().st_size} bytes")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--source", required=True)
    args = parser.parse_args()
    if not args.input.is_file():
        raise SystemExit(f"Decoded RockMap PMTiles feature stream missing: {args.input}")

    raw = read_candidates(args.input)
    if not raw:
        raise SystemExit("No searchable named features were found in the decoded RockMap basemap tiles.")
    records = dedupe(raw)
    add_context(records)
    write_index(records, args.output, args.source)
    validate(records, args.output)

    counts: dict[str, int] = defaultdict(int)
    for item in records:
        counts[item.kind] += 1
    print(f"RockMap offline basemap search index: {len(records)} records; {args.output.stat().st_size} compressed bytes")
    for kind, count in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"  {kind}: {count}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
