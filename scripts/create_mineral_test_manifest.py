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
    p.add_argument("--minerals", required=True)
    p.add_argument("--source-metadata", required=True)
    p.add_argument("--output", required=True)
    args = p.parse_args()

    baseline = json.loads(Path(args.baseline_manifest).read_text(encoding="utf-8"))
    files = baseline.get("files", [])
    by_id = {item.get("id"): item for item in files}
    if set(by_id) != {"style", "base", "land", "claims"}:
        raise SystemExit(f"Expected exact Alpha 5 baseline ids, got: {sorted(by_id)}")
    for required in ("style", "base", "land", "claims"):
        if not by_id[required].get("required", False):
            raise SystemExit(f"Baseline component unexpectedly optional: {required}")

    mineral_path = Path(args.minerals)
    metadata = json.loads(Path(args.source_metadata).read_text(encoding="utf-8"))
    count = int(metadata.get("normalized_unique_records", 0))
    if count < 100:
        raise SystemExit("Mineral metadata reports too few records")

    now = datetime.now(timezone.utc)
    stamp = now.strftime("%Y%m%dT%H%M%S")
    url = f"https://github.com/{args.repo}/releases/download/{args.tag}/{mineral_path.name}"
    mineral_spec = {
        "id": "minerals",
        "kind": "index",
        "fileName": mineral_path.name,
        "url": url,
        "sha256": sha256(mineral_path),
        "bytes": mineral_path.stat().st_size,
        "schemaVersion": 1,
        "required": True,
    }

    manifest = dict(baseline)
    manifest["pack"] = "Colorado Protomaps/OpenStreetMap + BLM land status + MLRS mining claims + USGS MRDS mineral finder test"
    manifest["version"] = f"alpha6-1-minerals-{stamp}"
    manifest["publishedAt"] = now.isoformat().replace("+00:00", "Z")
    manifest["status"] = "basemap_test"
    manifest["message"] = (
        f"Alpha 6.1 adds a compact offline USGS MRDS mineral-search index with {count} Colorado records. "
        "MRDS human-activity information can be outdated; occurrence points and geologic fields are research leads, "
        "not proof of access, ownership, claim status, or permission to collect."
    )
    manifest["files"] = [by_id["style"], by_id["base"], by_id["land"], by_id["claims"], mineral_spec]
    Path(args.output).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote Alpha 6.1 manifest with {count} compact MRDS records")
    print("Alpha 6.1 manifest reuses exact Alpha 5 style/base/land/claims entries and adds only the compressed mineral index")


if __name__ == "__main__":
    main()
