#!/usr/bin/env python3
import argparse
import gzip
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse

COLORADO_BBOX = (-109.0603, 36.9924, -102.0415, 41.0034)
EXPECTED_IDS = {
    "cgs-mt-antero-aquamarine",
    "cgs-mt-white-aquamarine",
    "usgs-crystal-peak-amazonite",
}
ALLOWED_SOURCE_HOSTS = {"coloradogeologicalsurvey.org", "www.usgs.gov"}


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def valid_source_url(value):
    try:
        parsed = urlparse(value)
        return parsed.scheme == "https" and parsed.hostname in ALLOWED_SOURCE_HOSTS
    except Exception:
        return False


def clean_strings(value, max_count=20, max_chars=160):
    if not isinstance(value, list):
        return []
    out = []
    seen = set()
    for item in value:
        text = str(item or "").strip()[:max_chars]
        key = text.lower()
        if not text or key in seen:
            continue
        seen.add(key)
        out.append(text)
        if len(out) >= max_count:
            break
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--source", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--metadata", required=True)
    args = p.parse_args()

    source_path = Path(args.source)
    root = json.loads(source_path.read_text(encoding="utf-8"))
    if root.get("schema") != 1:
        raise SystemExit("Unsupported curated-locality source schema")
    items = root.get("records")
    if not isinstance(items, list) or len(items) != len(EXPECTED_IDS):
        raise SystemExit("Alpha 6.2 locality source must contain exactly the reviewed locality set")

    records = []
    ids = set()
    sources = set()
    xmin, ymin, xmax, ymax = COLORADO_BBOX
    for raw in items:
        if not isinstance(raw, dict):
            raise SystemExit("Locality record must be an object")
        rec_id = str(raw.get("id") or "").strip()
        if rec_id not in EXPECTED_IDS or rec_id in ids:
            raise SystemExit(f"Unexpected or duplicate locality id: {rec_id}")
        ids.add(rec_id)
        try:
            lat = float(raw["lat"])
            lon = float(raw["lon"])
        except (KeyError, TypeError, ValueError):
            raise SystemExit(f"Invalid coordinates for {rec_id}")
        if not (math.isfinite(lat) and math.isfinite(lon) and ymin <= lat <= ymax and xmin <= lon <= xmax):
            raise SystemExit(f"Locality outside Colorado envelope: {rec_id}")
        source_url = str(raw.get("source_url") or "").strip()
        coordinate_source_url = str(raw.get("coordinate_source_url") or "").strip()
        if not valid_source_url(source_url) or not valid_source_url(coordinate_source_url):
            raise SystemExit(f"Locality uses an unapproved source domain: {rec_id}")
        materials = clean_strings(raw.get("materials"), 20, 100)
        if not materials:
            raise SystemExit(f"Locality has no searchable minerals/materials: {rec_id}")
        source_code = str(raw.get("source_code") or "").strip()[:60]
        evidence_type = str(raw.get("evidence_type") or "").strip()[:120]
        location_precision = str(raw.get("location_precision") or "").strip()[:180]
        source_title = str(raw.get("source_title") or "").strip()[:140]
        source_note = str(raw.get("source_note") or "").strip()[:280]
        if not all((source_code, evidence_type, location_precision, source_title, source_note)):
            raise SystemExit(f"Locality provenance is incomplete: {rec_id}")
        sources.add(source_url)
        records.append({
            "id": rec_id,
            "name": str(raw.get("name") or "").strip()[:180],
            "lat": round(lat, 6),
            "lon": round(lon, 6),
            "status": "",
            "grade": "",
            "materials": materials,
            "commodities": clean_strings(raw.get("commodities"), 20, 100),
            "districts": clean_strings(raw.get("districts"), 12, 140),
            "models": clean_strings(raw.get("models"), 12, 160),
            "rocks": clean_strings(raw.get("rocks"), 20, 140),
            "source_code": source_code,
            "evidence_type": evidence_type,
            "location_precision": location_precision,
            "source_title": source_title,
            "source_note": source_note,
        })

    if ids != EXPECTED_IDS:
        raise SystemExit(f"Reviewed locality set changed: {sorted(ids)}")
    records.sort(key=lambda r: r["id"])
    payload = {
        "schema": 1,
        "source": "Official Colorado gemstone/mineral locality supplement (CGS + USGS)",
        "generatedAt": utc_now(),
        "recordCount": len(records),
        "records": records,
    }
    raw_bytes = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw_out:
        with gzip.GzipFile(fileobj=raw_out, mode="wb", compresslevel=9, mtime=0) as gz:
            gz.write(raw_bytes)

    coverage = {}
    for term in ("amazonite", "microcline", "aquamarine", "beryl", "smoky quartz", "quartz"):
        coverage[term] = sum(1 for record in records
                             if term in " | ".join(record["materials"] + record["rocks"]).lower())
    metadata = {
        "built_at": utc_now(),
        "record_count": len(records),
        "record_ids": sorted(ids),
        "source_urls": sorted(sources),
        "compressed_index_bytes": output.stat().st_size,
        "uncompressed_json_bytes": len(raw_bytes),
        "coverage_record_counts": coverage,
        "purpose": "Reviewed official-source locality supplement for high-value gemstone search gaps in the MRDS-only Alpha 6.1 index.",
        "precision_note": "Coordinates are named-locality reference points, not specimen pockets or legal access points.",
    }
    Path(args.metadata).write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Alpha 6.2 official locality records: {len(records)}")
    print(f"Compressed locality index bytes: {output.stat().st_size}")
    print(json.dumps(coverage, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
