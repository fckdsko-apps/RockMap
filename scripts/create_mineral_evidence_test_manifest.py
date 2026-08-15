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
    p.add_argument("--evidence", required=True)
    p.add_argument("--source-metadata", required=True)
    p.add_argument("--output", required=True)
    args = p.parse_args()

    baseline = json.loads(Path(args.baseline_manifest).read_text(encoding="utf-8"))
    files = baseline.get("files", [])
    by_id = {item.get("id"): item for item in files}
    expected = {"style", "base", "land", "claims", "minerals", "mineral_localities"}
    if set(by_id) != expected:
        raise SystemExit(f"Expected exact Alpha 6.2 baseline ids, got: {sorted(by_id)}")
    for required in expected:
        if not by_id[required].get("required", False):
            raise SystemExit(f"Baseline component unexpectedly optional: {required}")

    evidence_path = Path(args.evidence)
    metadata = json.loads(Path(args.source_metadata).read_text(encoding="utf-8"))
    count = int(metadata.get("record_count", 0))
    if count < 26000:
        raise SystemExit(f"Alpha 6.2.1 evidence set unexpectedly small: {count}")
    if evidence_path.stat().st_size > 15_000_000:
        raise SystemExit("Alpha 6.2.1 mineral evidence exceeds the 15 MB hard-data budget")

    now = datetime.now(timezone.utc)
    stamp = now.strftime("%Y%m%dT%H%M%S")
    url = f"https://github.com/{args.repo}/releases/download/{args.tag}/{evidence_path.name}"
    evidence_spec = {
        "id": "mineral_evidence",
        "kind": "index",
        "fileName": evidence_path.name,
        "url": url,
        "sha256": sha256(evidence_path),
        "bytes": evidence_path.stat().st_size,
        "schemaVersion": 1,
        "required": True,
    }

    manifest = dict(baseline)
    manifest["pack"] = "Colorado offline map + land + claims + MRDS + official localities + expanded USGS/CGS mineral evidence test"
    manifest["version"] = f"alpha6-2-1-mineral-evidence-{stamp}"
    manifest["publishedAt"] = now.isoformat().replace("+00:00", "Z")
    manifest["status"] = "basemap_test"
    manifest["message"] = (
        f"Alpha 6.2.1 reuses every Alpha 6.2 map/mineral asset exactly and adds {count} official-source evidence records "
        "from USGS MAS/MILS and Colorado Geological Survey datasets. Evidence classes remain distinct: occurrence, "
        "mine/property, abandoned-mine inventory, and broad historic district evidence are not treated as equivalent."
    )
    order = ["style", "base", "land", "claims", "minerals", "mineral_localities"]
    manifest["files"] = [by_id[item_id] for item_id in order] + [evidence_spec]
    Path(args.output).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote Alpha 6.2.1 manifest with {count} expanded evidence records")
    print("Alpha 6.2.1 reuses exact Alpha 6.2 files and adds only mineral_evidence")


if __name__ == "__main__":
    main()
