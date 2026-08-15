#!/usr/bin/env python3
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--repo", required=True)
    p.add_argument("--tag", required=True)
    p.add_argument("--baseline-manifest", required=True)
    p.add_argument("--localities", required=True)
    p.add_argument("--source-metadata", required=True)
    p.add_argument("--output", required=True)
    args = p.parse_args()

    baseline = json.loads(Path(args.baseline_manifest).read_text(encoding="utf-8"))
    files = baseline.get("files", [])
    by_id = {item.get("id"): item for item in files}
    expected = {"style", "base", "land", "claims", "minerals"}
    if set(by_id) != expected:
        raise SystemExit(f"Expected exact Alpha 6.1 baseline ids, got: {sorted(by_id)}")
    for required in expected:
        if not by_id[required].get("required", False):
            raise SystemExit(f"Baseline component unexpectedly optional: {required}")

    locality_path = Path(args.localities)
    metadata = json.loads(Path(args.source_metadata).read_text(encoding="utf-8"))
    count = int(metadata.get("record_count", 0))
    if count != 3:
        raise SystemExit(f"Alpha 6.2 reviewed locality metadata count changed: {count}")

    now = datetime.now(timezone.utc)
    stamp = now.strftime("%Y%m%dT%H%M%S")
    url = f"https://github.com/{args.repo}/releases/download/{args.tag}/{locality_path.name}"
    locality_spec = {
        "id": "mineral_localities",
        "kind": "index",
        "fileName": locality_path.name,
        "url": url,
        "sha256": sha256(locality_path),
        "bytes": locality_path.stat().st_size,
        "schemaVersion": 1,
        "required": True,
    }

    manifest = dict(baseline)
    manifest["pack"] = "Colorado Protomaps/OpenStreetMap + BLM land status + MLRS claims + USGS MRDS + official CGS/USGS mineral localities test"
    manifest["version"] = f"alpha6-2-mineral-coverage-{stamp}"
    manifest["publishedAt"] = now.isoformat().replace("+00:00", "Z")
    manifest["status"] = "basemap_test"
    manifest["message"] = (
        f"Alpha 6.2 reuses the exact Alpha 6.1 MRDS mineral index and adds {count} reviewed official-source "
        "Colorado gemstone/mineral locality references for important MRDS gaps. Locality points represent named areas, "
        "not specimen pockets, ownership, access, claim status, or collecting permission."
    )
    manifest["files"] = [
        by_id["style"], by_id["base"], by_id["land"], by_id["claims"], by_id["minerals"], locality_spec
    ]
    Path(args.output).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote Alpha 6.2 manifest with {count} reviewed locality records")
    print("Alpha 6.2 reuses exact Alpha 6.1 style/base/land/claims/minerals entries and adds only mineral_localities")


if __name__ == "__main__":
    main()
