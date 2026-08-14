#!/usr/bin/env python3
"""Fetch and normalize the official BLM Colorado Surface Management Agency layer.

The output deliberately contains only RockMap's stable land contract:
  manager_code, manager_name

Every OBJECTID returned by the service's returnIdsOnly query must be received exactly
once in the GeoJSON pages. The script fails closed on schema/category drift.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_URL = (
    "https://gis.blm.gov/coarcgis/rest/services/lands/"
    "BLM_Colorado_Surface_Management_Agency/FeatureServer/1"
)
USER_AGENT = "RockMap-Alpha4-LandBuilder/1.0"
BATCH_SIZE = 200
MAX_ATTEMPTS = 5

# These are the coded values currently published by the official BLM Colorado layer.
MANAGER_NAMES = {
    "BLM": "Bureau of Land Management",
    "BOR": "Bureau of Reclamation",
    "BIA": "Indian Reservation",
    "DOD": "Military Reservation",
    "USFS_NG": "National Grasslands",
    "NPS": "National Park Service",
    "OTHER": "Other Federal",
    "PRI": "Private",
    "STA": "State",
    "LOCAL": "State, County, City: Recreation Areas",
    "BLM_LU": "Bankhead-Jones Land Use Lands",
    "USFS_LU": "Bankhead-Jones Land Use Lands",
    "USFW": "US Fish and Wildlife Service",
    "USFS": "US Forest Service",
}
REQUIRED_CODES = {"BLM", "PRI", "STA", "USFS"}
BROAD_BOUNDS = (-110.5, 36.0, -101.0, 42.0)


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
                f"OBJECTID {oid} coordinate {lon},{lat} lies outside the broad Colorado safety bounds"
            )
    if seen < 4:
        raise RuntimeError(f"OBJECTID {oid} polygon has too few coordinates")


def normalize_feature(feature: dict, object_id_field: str) -> tuple[int, dict]:
    props = feature.get("properties")
    if not isinstance(props, dict):
        raise RuntimeError("BLM feature has no properties object")
    try:
        oid = int(props[object_id_field])
    except Exception as exc:
        raise RuntimeError(f"BLM feature has invalid {object_id_field}") from exc

    code = str(props.get("adm_manage") or "").strip().upper()
    if code not in MANAGER_NAMES:
        raise RuntimeError(f"BLM manager-code schema drift: OBJECTID {oid} has {code!r}")
    name = str(props.get("adm_name") or "").strip()
    if not name:
        name = MANAGER_NAMES[code]
    if len(name) > 200:
        raise RuntimeError(f"OBJECTID {oid} has an unreasonable manager name length")

    geometry = feature.get("geometry")
    validate_geometry(geometry, oid)
    return oid, {
        "type": "Feature",
        "id": oid,
        "properties": {"manager_code": code, "manager_name": name},
        "geometry": geometry,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--output", required=True)
    parser.add_argument("--metadata", required=True)
    args = parser.parse_args()

    layer_url = args.url.rstrip("/")
    metadata = request_json(layer_url, {"f": "pjson"})
    if metadata.get("geometryType") != "esriGeometryPolygon":
        raise SystemExit("BLM layer is no longer a polygon feature layer")
    fields = {f.get("name") for f in metadata.get("fields", []) if isinstance(f, dict)}
    required_fields = {"OBJECTID", "adm_manage", "adm_name"}
    missing = sorted(required_fields - fields)
    if missing:
        raise SystemExit("BLM layer schema missing required fields: " + ", ".join(missing))
    object_id_field = metadata.get("objectIdField") or "OBJECTID"
    if object_id_field not in fields:
        raise SystemExit("BLM layer objectIdField is missing from fields")

    ids_payload = request_json(
        layer_url + "/query",
        {"where": "1=1", "returnIdsOnly": "true", "f": "json"},
        post=True,
    )
    raw_ids = ids_payload.get("objectIds")
    if not isinstance(raw_ids, list) or len(raw_ids) < 100:
        raise SystemExit("BLM returnIdsOnly query returned an implausibly small/invalid feature set")
    object_ids = sorted({int(v) for v in raw_ids})
    if len(object_ids) != len(raw_ids):
        raise SystemExit("BLM returnIdsOnly query contained duplicate OBJECTIDs")

    seen: dict[int, dict] = {}
    counts: Counter[str] = Counter()
    for start in range(0, len(object_ids), BATCH_SIZE):
        batch = object_ids[start:start + BATCH_SIZE]
        payload = request_json(
            layer_url + "/query",
            {
                "objectIds": ",".join(str(v) for v in batch),
                "outFields": f"{object_id_field},adm_manage,adm_name",
                "returnGeometry": "true",
                "outSR": "4326",
                "f": "geojson",
            },
            post=True,
        )
        if payload.get("type") != "FeatureCollection" or not isinstance(payload.get("features"), list):
            raise SystemExit("BLM GeoJSON batch was not a FeatureCollection")
        for feature in payload["features"]:
            oid, normalized = normalize_feature(feature, object_id_field)
            if oid in seen:
                raise SystemExit(f"BLM query returned duplicate OBJECTID {oid}")
            seen[oid] = normalized
            counts[normalized["properties"]["manager_code"]] += 1

    expected = set(object_ids)
    actual = set(seen)
    if actual != expected:
        missing_ids = sorted(expected - actual)[:20]
        extra_ids = sorted(actual - expected)[:20]
        raise SystemExit(
            f"BLM feature completeness check failed: missing={missing_ids} extra={extra_ids} "
            f"expected={len(expected)} actual={len(actual)}"
        )
    missing_codes = sorted(code for code in REQUIRED_CODES if counts[code] == 0)
    if missing_codes:
        raise SystemExit("BLM category sanity check failed; no features for: " + ", ".join(missing_codes))

    collection = {"type": "FeatureCollection", "features": [seen[oid] for oid in object_ids]}
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    encoded = json.dumps(collection, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    output_path.write_bytes(encoded)

    source_meta = {
        "source": layer_url,
        "source_name": metadata.get("name"),
        "copyright": metadata.get("copyrightText"),
        "fetched_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "object_id_field": object_id_field,
        "feature_count": len(object_ids),
        "object_id_min": object_ids[0],
        "object_id_max": object_ids[-1],
        "manager_code_counts": dict(sorted(counts.items())),
        "normalized_geojson_sha256": hashlib.sha256(encoded).hexdigest(),
        "normalized_properties": ["manager_code", "manager_name"],
        "service_current_version": metadata.get("currentVersion"),
        "service_item_id": metadata.get("serviceItemId"),
        "editing_info": metadata.get("editingInfo"),
    }
    metadata_path = Path(args.metadata)
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(json.dumps(source_meta, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Fetched and normalized {len(object_ids)} BLM Colorado SMA polygons")
    print("Manager counts: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))


if __name__ == "__main__":
    main()
