#!/usr/bin/env python3
import argparse
import gzip
import json
import math
import re
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

COLORADO_BBOX = (-109.0603, 36.9924, -102.0415, 41.0034)
PAGE_SIZE = 500
MAX_PAGES = 200
MAX_RECORDS = 100_000
EXPECTED_FIELDS = {"gid", "dep_id", "site_name", "dev_stat", "code_list", "grade", "json"}
COVERAGE_TERMS = [
    "amazonite", "microcline", "aquamarine", "beryl", "topaz", "rhodochrosite",
    "fluorite", "quartz", "chalcedony", "amethyst", "tourmaline", "telluride",
    "tellurium", "pegmatite", "garnet", "zircon", "corundum", "olivine",
    "gold", "silver", "copper", "uranium",
]


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def normalized(value):
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9+.-]+", " ", str(value or "").lower())).strip()


def as_list(value):
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def clipped(value, limit):
    text = str(value or "").strip()
    return text[:limit]


def unique_strings(items, limit_count=30, limit_chars=140):
    seen = set()
    out = []
    for item in items:
        text = clipped(item, limit_chars)
        if not text:
            continue
        key = normalized(text)
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(text)
        if len(out) >= limit_count:
            break
    return out


def nested_values(props, key, field):
    values = []
    for item in as_list(props.get(key)):
        if isinstance(item, dict):
            value = item.get(field)
            if value is not None:
                values.append(value)
    return values


def state_values(props):
    return unique_strings(nested_values(props, "location", "state_prov"), 8, 80)


def fetch_json(url, params=None, attempts=4):
    target = url
    if params:
        target += ("&" if "?" in target else "?") + urllib.parse.urlencode(params)
    headers = {"User-Agent": "RockMap/alpha6.1 mineral-pack builder"}
    last = None
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(target, headers=headers)
            with urllib.request.urlopen(req, timeout=120) as response:
                if response.status != 200:
                    raise RuntimeError(f"HTTP {response.status} from USGS")
                data = response.read()
            parsed = json.loads(data.decode("utf-8"))
            if isinstance(parsed, dict) and parsed.get("error"):
                raise RuntimeError(f"USGS ArcGIS error: {parsed['error']}")
            return parsed
        except Exception as exc:
            last = exc
            if attempt + 1 < attempts:
                time.sleep(2 ** attempt)
    raise RuntimeError(f"USGS request failed after {attempts} attempts: {last}")


def validate_layer(layer):
    if layer.get("geometryType") != "esriGeometryPoint":
        raise RuntimeError(f"Unexpected MRDS geometry type: {layer.get('geometryType')}")
    fields = {field.get("name") for field in layer.get("fields", []) if isinstance(field, dict)}
    missing = EXPECTED_FIELDS - fields
    if missing:
        raise RuntimeError(f"MRDS service is missing expected fields: {sorted(missing)}")
    name = str(layer.get("name", "")).lower()
    if "mrds" not in name:
        raise RuntimeError(f"Unexpected MRDS layer name: {layer.get('name')}")


def parse_record(feature):
    attrs = feature.get("attributes") or {}
    geometry = feature.get("geometry") or {}
    try:
        lon = float(geometry.get("x"))
        lat = float(geometry.get("y"))
    except (TypeError, ValueError):
        return None
    xmin, ymin, xmax, ymax = COLORADO_BBOX
    if not (math.isfinite(lat) and math.isfinite(lon) and ymin <= lat <= ymax and xmin <= lon <= xmax):
        return None

    raw = attrs.get("json")
    if not raw:
        return None
    try:
        embedded = json.loads(raw)
    except (TypeError, json.JSONDecodeError):
        return None
    props = embedded.get("properties") or {}
    explicit_states = state_values(props)
    if explicit_states and not any(normalized(state) == "colorado" for state in explicit_states):
        return None

    dep_id = clipped(attrs.get("dep_id") or (props.get("deposits") or {}).get("dep_id"), 40)
    if not dep_id:
        dep_id = f"gid-{attrs.get('gid')}"
    name = clipped(attrs.get("site_name"), 180) or "Unnamed MRDS occurrence"
    status = clipped(attrs.get("dev_stat"), 60)
    grade = clipped(attrs.get("grade"), 12)

    materials = unique_strings(nested_values(props, "material", "material"), 40, 100)
    commodities = unique_strings(nested_values(props, "commodity", "commod"), 30, 100)
    districts = unique_strings(nested_values(props, "districts", "district"), 12, 120)
    models = unique_strings(nested_values(props, "model_type", "model_name"), 12, 160)

    rock_names = []
    for rock in as_list(props.get("rock")):
        if not isinstance(rock, dict):
            continue
        for field in ("low_name", "second_ord_nm", "first_ord_nm"):
            value = rock.get(field)
            if value:
                rock_names.append(value)
                break
    rocks = unique_strings(rock_names, 24, 120)

    # Keep only compact, search-relevant geology/mineral fields. MRDS comments, ownership,
    # production, references, and verbose free text are intentionally excluded from the phone pack.
    if not any((materials, commodities, districts, models, rocks)):
        return None

    return {
        "id": dep_id,
        "name": name,
        "lat": round(lat, 6),
        "lon": round(lon, 6),
        "status": status,
        "grade": grade,
        "materials": materials,
        "commodities": commodities,
        "districts": districts,
        "models": models,
        "rocks": rocks,
    }


def record_haystack(record):
    parts = [record["name"]]
    for key in ("materials", "commodities", "districts", "models", "rocks"):
        parts.extend(record[key])
    return " | ".join(normalized(value) for value in parts)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True, help="USGS MRDS FeatureServer layer URL")
    parser.add_argument("--output", required=True)
    parser.add_argument("--metadata", required=True)
    args = parser.parse_args()

    layer_url = args.url.rstrip("/")
    layer = fetch_json(layer_url, {"f": "json"})
    validate_layer(layer)

    geometry = ",".join(str(v) for v in COLORADO_BBOX)
    count_response = fetch_json(layer_url + "/query", {
        "f": "json",
        "where": "1=1",
        "geometry": geometry,
        "geometryType": "esriGeometryEnvelope",
        "inSR": "4326",
        "spatialRel": "esriSpatialRelIntersects",
        "returnCountOnly": "true",
    })
    expected_envelope_count = int(count_response.get("count", 0))
    if expected_envelope_count <= 0 or expected_envelope_count > MAX_RECORDS:
        raise RuntimeError(f"Unexpected Colorado-envelope MRDS count: {expected_envelope_count}")

    records = []
    seen = set()
    raw_seen = 0
    excluded_wrong_state = 0
    offset = 0
    for page in range(MAX_PAGES):
        response = fetch_json(layer_url + "/query", {
            "f": "json",
            "where": "1=1",
            "geometry": geometry,
            "geometryType": "esriGeometryEnvelope",
            "inSR": "4326",
            "spatialRel": "esriSpatialRelIntersects",
            "outFields": "gid,dep_id,site_name,dev_stat,code_list,url,grade,json",
            "returnGeometry": "true",
            "outSR": "4326",
            "orderByFields": "gid ASC",
            "resultOffset": str(offset),
            "resultRecordCount": str(PAGE_SIZE),
        })
        features = response.get("features") or []
        if not features:
            break
        raw_seen += len(features)
        for feature in features:
            attrs = feature.get("attributes") or {}
            raw = attrs.get("json")
            if raw:
                try:
                    props = (json.loads(raw).get("properties") or {})
                    states = state_values(props)
                    if states and not any(normalized(state) == "colorado" for state in states):
                        excluded_wrong_state += 1
                except Exception:
                    pass
            record = parse_record(feature)
            if record is None or record["id"] in seen:
                continue
            seen.add(record["id"])
            records.append(record)
        offset += len(features)
        if len(features) < PAGE_SIZE:
            break
    else:
        raise RuntimeError("MRDS pagination exceeded safe page limit")

    if raw_seen < expected_envelope_count:
        raise RuntimeError(f"MRDS pagination incomplete: expected {expected_envelope_count}, received {raw_seen}")
    if len(records) < 100:
        raise RuntimeError(f"Too few usable Colorado MRDS records after normalization: {len(records)}")

    records.sort(key=lambda r: (normalized(r["name"]), r["id"]))
    payload = {
        "schema": 1,
        "source": "U.S. Geological Survey Mineral Resources Data System (MRDS)",
        "sourceUrl": layer_url,
        "generatedAt": utc_now(),
        "recordCount": len(records),
        "records": records,
    }
    raw_bytes = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("wb") as raw_out:
        with gzip.GzipFile(fileobj=raw_out, mode="wb", compresslevel=9, mtime=0) as gz:
            gz.write(raw_bytes)

    coverage = {}
    haystacks = [record_haystack(record) for record in records]
    for term in COVERAGE_TERMS:
        needle = normalized(term)
        coverage[term] = sum(1 for text in haystacks if needle in text)

    metadata = {
        "source_url": layer_url,
        "source_layer_name": layer.get("name"),
        "service_geometry_type": layer.get("geometryType"),
        "fetched_at": utc_now(),
        "colorado_envelope": COLORADO_BBOX,
        "service_envelope_count": expected_envelope_count,
        "features_received": raw_seen,
        "explicit_non_colorado_records_excluded": excluded_wrong_state,
        "normalized_unique_records": len(records),
        "uncompressed_json_bytes": len(raw_bytes),
        "compressed_index_bytes": output_path.stat().st_size,
        "coverage_record_counts": coverage,
        "phone_fields": ["id", "name", "lat", "lon", "status", "grade", "materials", "commodities", "districts", "models", "rocks"],
        "excluded_categories": ["production", "ownership", "reserves", "references", "comments", "verbose geology text"],
        "note": "MRDS human-activity information can be stale. RockMap retains compact mineral/geologic fields for research leads only.",
    }
    Path(args.metadata).write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"USGS MRDS Colorado envelope features received: {raw_seen}")
    print(f"RockMap compact Colorado mineral records: {len(records)}")
    print(f"Compressed mineral index bytes: {output_path.stat().st_size}")
    print("Coverage counts:")
    for term in COVERAGE_TERMS:
        print(f"  {term}: {coverage[term]}")


if __name__ == "__main__":
    main()
