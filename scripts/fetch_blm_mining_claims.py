#!/usr/bin/env python3
"""Fetch and normalize BLM MLRS mining-claim cases whose disposition is not closed.

Alpha 5 intentionally uses the official national "Mining Claims- Not Closed" FeatureServer
and spatially selects features intersecting a conservative Colorado envelope. Every OBJECTID
selected by returnIdsOnly must be received exactly once before any quality filtering occurs.

The output deliberately contains only RockMap's stable claim contract. County-only score 25
geometry is excluded because it is too coarse to present as a claim footprint. BLM quality
scores that indicate attribute-only/no-geometry records are also excluded if they ever appear
with geometry unexpectedly.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_URL = (
    "https://gis.blm.gov/nlsdb/rest/services/HUB/"
    "BLM_Natl_MLRS_Mining_Claims_Not_Closed/FeatureServer/0"
)
USER_AGENT = "RockMap-Alpha5-ClaimsBuilder/1.0"
BATCH_SIZE = 200
MAX_ATTEMPTS = 5

# Colorado is nearly rectangular. The small buffer prevents survey-line/transform rounding
# from dropping a claim that touches the state edge; RockMap remains a Colorado-only test app.
COLORADO_QUERY_ENVELOPE = {
    "xmin": -109.10,
    "ymin": 36.95,
    "xmax": -102.00,
    "ymax": 41.05,
    "spatialReference": {"wkid": 4326},
}
BROAD_BOUNDS = (-110.5, 36.0, -101.0, 42.0)

# Current official BLM Active Mining Claims renderer codes. The not-closed service publishes
# the same CSE_TYPE_NR field. Unknown codes fail the build instead of being guessed.
TYPE_NAMES = {
    "384101": "Lode Claim",
    "384103": "Lode Claim",
    "384201": "Placer Claim",
    "384203": "Placer Claim",
    "384301": "Tunnel Site",
    "384303": "Tunnel Site",
    "384401": "Mill Site",
    "384403": "Mill Site",
}

NO_FOOTPRINT_QUALITY_SCORES = {"11", "12", "20", "21", "22", "25"}
QUALITY_GROUPS = {
    "0": "Direct PLSS match", "1": "Direct PLSS match", "2": "Direct PLSS match", "3": "Direct PLSS match",
    "4": "Calculated PLSS match", "4.1": "Calculated PLSS match", "5": "Calculated PLSS match",
    "6": "Calculated PLSS match", "7": "Calculated PLSS match", "8": "Calculated PLSS match",
    "8.1": "Mapped to section", "8.2": "Mapped to section", "8.3": "Mapped to section",
    "9": "Mapped to section", "10": "Mapped to section",
    "15": "Combination of mapped and unmapped areas",
    "11": "No NLSDB geometry / attributes only", "12": "No NLSDB geometry / attributes only",
    "20": "No NLSDB geometry / attributes only", "21": "No NLSDB geometry / attributes only",
    "22": "No NLSDB geometry / attributes only",
    "25": "Mapped to county — excluded from footprint overlay",
    "100": "Improved geometry edited by BLM staff",
}
QUALITY_RE = re.compile(r"^\s*(100|25|22|21|20|15|12|11|10|9|8\.3|8\.2|8\.1|8|7|6|5|4\.1|4|3|2|1|0)(?:\s|$|[-:;,])")

REQUIRED_SOURCE_FIELDS = {
    "OBJECTID", "CSE_NAME", "BLM_PROD", "CSE_TYPE_NR", "CSE_NR", "LEG_CSE_NR",
    "CSE_DISP", "SRC", "QLTY", "RCRD_ACRS", "Modified",
}


def request_json(url: str, params: dict[str, str] | None = None, post: bool = False) -> dict:
    encoded = urllib.parse.urlencode(params or {}).encode("utf-8")
    if post:
        req = urllib.request.Request(url, data=encoded, method="POST")
    else:
        suffix = ("?" + encoded.decode("utf-8")) if encoded else ""
        req = urllib.request.Request(url + suffix, method="GET")
    req.add_header("User-Agent", USER_AGENT)
    if post:
        req.add_header("Content-Type", "application/x-www-form-urlencoded")

    last_error: Exception | None = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(req, timeout=90) as response:
                raw = response.read(100 * 1024 * 1024 + 1)
                if len(raw) > 100 * 1024 * 1024:
                    raise RuntimeError("BLM response exceeded 100 MB safety limit")
                payload = json.loads(raw.decode("utf-8"))
                if isinstance(payload, dict) and payload.get("error"):
                    raise RuntimeError("BLM ArcGIS error: " + json.dumps(payload["error"], sort_keys=True))
                if not isinstance(payload, dict):
                    raise RuntimeError("BLM response was not a JSON object")
                return payload
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, RuntimeError) as exc:
            last_error = exc
            if attempt == MAX_ATTEMPTS:
                break
            time.sleep(min(2 ** (attempt - 1), 12))
    raise RuntimeError(f"BLM request failed after {MAX_ATTEMPTS} attempts: {last_error}")


def clean_text(value, limit: int = 255) -> str:
    text = "" if value is None else str(value).strip()
    return text[:limit]


def walk_coordinates(value):
    if isinstance(value, (list, tuple)):
        if len(value) >= 2 and isinstance(value[0], (int, float)) and isinstance(value[1], (int, float)):
            yield float(value[0]), float(value[1])
        else:
            for item in value:
                yield from walk_coordinates(item)


def validate_geometry(geometry: dict, oid: int) -> None:
    if not isinstance(geometry, dict) or geometry.get("type") not in {"Polygon", "MultiPolygon"}:
        raise RuntimeError(f"OBJECTID {oid} has unexpected/missing polygon geometry")
    coords = geometry.get("coordinates")
    seen = 0
    min_lon, min_lat, max_lon, max_lat = BROAD_BOUNDS
    for lon, lat in walk_coordinates(coords):
        seen += 1
        if not math.isfinite(lon) or not math.isfinite(lat):
            raise RuntimeError(f"OBJECTID {oid} contains non-finite coordinates")
        if not (min_lon <= lon <= max_lon and min_lat <= lat <= max_lat):
            raise RuntimeError(
                f"OBJECTID {oid} coordinate {lon},{lat} lies outside broad Colorado safety bounds"
            )
    if seen < 4:
        raise RuntimeError(f"OBJECTID {oid} polygon has too few coordinates")


def quality_score(raw_quality: str) -> str:
    if not raw_quality:
        return ""
    match = QUALITY_RE.match(raw_quality)
    return match.group(1) if match else ""


def quality_description(raw_quality: str, score: str) -> str:
    if not raw_quality:
        return "BLM mapping quality not reported"
    group = QUALITY_GROUPS.get(score)
    if group:
        if raw_quality == score:
            return f"BLM quality score {score}: {group}"
        return f"BLM quality score {score}: {group}; source value: {raw_quality}"[:500]
    return ("BLM mapping quality source value: " + raw_quality)[:500]


def normalize_feature(feature: dict, object_id_field: str) -> tuple[int, dict | None, str, str, str]:
    props = feature.get("properties")
    if not isinstance(props, dict):
        raise RuntimeError("BLM claim feature has no properties object")
    try:
        oid = int(props[object_id_field])
    except Exception as exc:
        raise RuntimeError(f"BLM claim feature has invalid {object_id_field}") from exc

    serial = clean_text(props.get("CSE_NR"))
    if not serial:
        raise RuntimeError(f"OBJECTID {oid} is missing required case serial number")
    type_code = clean_text(props.get("CSE_TYPE_NR"))
    if type_code not in TYPE_NAMES:
        raise RuntimeError(f"BLM claim type-code schema drift: OBJECTID {oid} has {type_code!r}")
    disposition = clean_text(props.get("CSE_DISP"))
    if disposition.lower() == "closed":
        raise RuntimeError(f"OBJECTID {oid} is Closed inside the official Not Closed dataset")
    if not disposition:
        disposition = "Not reported (record selected from BLM Not Closed dataset)"

    raw_quality = clean_text(props.get("QLTY"))
    score = quality_score(raw_quality)
    geometry = feature.get("geometry")
    validate_geometry(geometry, oid)

    # Scores 11/12/20/21/22 are documented by BLM as attribute-only/no-geometry; score 25
    # is county-only. If any arrive with geometry, do not present them as claim footprints.
    if score in NO_FOOTPRINT_QUALITY_SCORES:
        return oid, None, type_code, disposition, score

    acres_value = props.get("RCRD_ACRS")
    acres = ""
    if acres_value is not None:
        try:
            number = float(acres_value)
            if math.isfinite(number) and number >= 0:
                acres = f"{number:.2f}".rstrip("0").rstrip(".")
        except (TypeError, ValueError):
            pass

    name = clean_text(props.get("CSE_NAME")) or "Unnamed claim"
    legacy = clean_text(props.get("LEG_CSE_NR"))
    source = clean_text(props.get("SRC"))
    normalized = {
        "type": "Feature",
        "id": oid,
        "properties": {
            "name": name,
            "serial": serial,
            "legacy_serial": legacy,
            "type": TYPE_NAMES[type_code],
            "type_code": type_code,
            "disposition": disposition,
            "acres": acres,
            "quality": raw_quality or "not_reported",
            "quality_description": quality_description(raw_quality, score),
            "source": source,
        },
        "geometry": geometry,
    }
    return oid, normalized, type_code, disposition, score or "unparsed"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--output", required=True)
    parser.add_argument("--metadata", required=True)
    args = parser.parse_args()

    layer_url = args.url.rstrip("/")
    metadata = request_json(layer_url, {"f": "pjson"})
    if metadata.get("geometryType") != "esriGeometryPolygon":
        raise SystemExit("BLM mining-claims layer is no longer a polygon feature layer")
    if metadata.get("name") != "Mining Claims- Not Closed":
        raise SystemExit("BLM mining-claims source name unexpectedly changed")
    fields = {f.get("name") for f in metadata.get("fields", []) if isinstance(f, dict)}
    missing = sorted(REQUIRED_SOURCE_FIELDS - fields)
    if missing:
        raise SystemExit("BLM mining-claims schema missing required fields: " + ", ".join(missing))
    object_id_field = metadata.get("objectIdField") or "OBJECTID"
    if object_id_field not in fields:
        raise SystemExit("BLM mining-claims objectIdField is missing from fields")

    geometry_json = json.dumps(COLORADO_QUERY_ENVELOPE, separators=(",", ":"))
    ids_payload = request_json(
        layer_url + "/query",
        {
            "where": "1=1",
            "geometry": geometry_json,
            "geometryType": "esriGeometryEnvelope",
            "inSR": "4326",
            "spatialRel": "esriSpatialRelIntersects",
            "returnIdsOnly": "true",
            "f": "json",
        },
        post=True,
    )
    raw_ids = ids_payload.get("objectIds")
    if not isinstance(raw_ids, list) or len(raw_ids) < 100:
        raise SystemExit("BLM Colorado claim query returned an implausibly small/invalid feature set")
    object_ids = sorted({int(v) for v in raw_ids})
    if len(object_ids) != len(raw_ids):
        raise SystemExit("BLM Colorado claim query contained duplicate OBJECTIDs")

    seen_ids: set[int] = set()
    included: dict[int, dict] = {}
    type_counts: Counter[str] = Counter()
    disposition_counts: Counter[str] = Counter()
    quality_counts: Counter[str] = Counter()
    excluded_quality_counts: Counter[str] = Counter()
    unique_serials: set[str] = set()

    out_fields = ",".join([
        object_id_field, "CSE_NAME", "BLM_PROD", "CSE_TYPE_NR", "CSE_NR", "LEG_CSE_NR",
        "CSE_DISP", "SRC", "QLTY", "RCRD_ACRS", "Modified",
    ])
    for start in range(0, len(object_ids), BATCH_SIZE):
        batch = object_ids[start:start + BATCH_SIZE]
        payload = request_json(
            layer_url + "/query",
            {
                "objectIds": ",".join(str(v) for v in batch),
                "outFields": out_fields,
                "returnGeometry": "true",
                "outSR": "4326",
                "f": "geojson",
            },
            post=True,
        )
        if payload.get("type") != "FeatureCollection" or not isinstance(payload.get("features"), list):
            raise SystemExit("BLM mining-claims GeoJSON batch was not a FeatureCollection")
        for feature in payload["features"]:
            oid, normalized, type_code, disposition, score = normalize_feature(feature, object_id_field)
            if oid in seen_ids:
                raise SystemExit(f"BLM mining-claims query returned duplicate OBJECTID {oid}")
            seen_ids.add(oid)
            if normalized is None:
                excluded_quality_counts[score or "unknown"] += 1
                continue
            included[oid] = normalized
            type_counts[TYPE_NAMES[type_code]] += 1
            disposition_counts[disposition] += 1
            quality_counts[score] += 1
            unique_serials.add(normalized["properties"]["serial"])

    expected = set(object_ids)
    if seen_ids != expected:
        missing_ids = sorted(expected - seen_ids)[:20]
        extra_ids = sorted(seen_ids - expected)[:20]
        raise SystemExit(
            f"BLM claim completeness check failed: missing={missing_ids} extra={extra_ids} "
            f"expected={len(expected)} actual={len(seen_ids)}"
        )
    if len(included) < 100 or len(unique_serials) < 100:
        raise SystemExit("Too few usable Colorado claim geometries remained after quality filtering")
    for required_type in ("Lode Claim", "Placer Claim"):
        if type_counts[required_type] == 0:
            raise SystemExit(f"BLM claim type sanity check failed; no {required_type} features")

    collection = {"type": "FeatureCollection", "features": [included[oid] for oid in sorted(included)]}
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(collection, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    output_path.write_bytes(encoded)

    source_meta = {
        "source": layer_url,
        "source_name": metadata.get("name"),
        "copyright": "U.S. Department of the Interior, Bureau of Land Management (BLM)",
        "fetched_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "object_id_field": object_id_field,
        "query_envelope_wgs84": COLORADO_QUERY_ENVELOPE,
        "selected_feature_count": len(object_ids),
        "included_feature_count": len(included),
        "excluded_quality_count": sum(excluded_quality_counts.values()),
        "excluded_quality_score_counts": dict(sorted(excluded_quality_counts.items())),
        "unique_case_serial_count": len(unique_serials),
        "claim_type_counts": dict(sorted(type_counts.items())),
        "disposition_counts": dict(sorted(disposition_counts.items())),
        "quality_score_counts": dict(sorted(quality_counts.items())),
        "normalized_geojson_sha256": hashlib.sha256(encoded).hexdigest(),
        "normalized_properties": [
            "name", "serial", "legacy_serial", "type", "type_code", "disposition",
            "acres", "quality", "quality_description", "source",
        ],
        "service_current_version": metadata.get("currentVersion"),
        "service_max_record_count": metadata.get("maxRecordCount"),
        "quality_policy": "Exclude BLM quality scores 11/12/20/21/22 (attribute-only/no-geometry) and 25 (county-only) from footprint overlay.",
    }
    metadata_path = Path(args.metadata)
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(json.dumps(source_meta, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Selected {len(object_ids)} BLM MLRS not-closed claim geometries intersecting Colorado envelope")
    print(f"Included {len(included)} geometries across {len(unique_serials)} unique case serials")
    print("Claim types: " + ", ".join(f"{k}={v}" for k, v in sorted(type_counts.items())))
    if excluded_quality_counts:
        print("Excluded coarse/no-footprint quality scores: " + ", ".join(f"{k}={v}" for k, v in sorted(excluded_quality_counts.items())))


if __name__ == "__main__":
    main()
