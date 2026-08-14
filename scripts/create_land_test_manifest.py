#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--repo", required=True)
    p.add_argument("--tag", required=True)
    p.add_argument("--baseline-manifest", required=True)
    p.add_argument("--land", required=True)
    p.add_argument("--source-metadata", required=True)
    p.add_argument("--output", required=True)
    args = p.parse_args()

    baseline = json.loads(Path(args.baseline_manifest).read_text(encoding="utf-8"))
    if baseline.get("manifestVersion") != 1 or baseline.get("styleSchemaVersion") != 1:
        raise SystemExit("Baseline manifest contract is not schema 1")
    if baseline.get("status") != "basemap_test":
        raise SystemExit("Baseline manifest is not the known basemap_test snapshot")
    baseline_files = {x.get("id"): x for x in baseline.get("files", []) if isinstance(x, dict)}
    if set(baseline_files) != {"style", "base"}:
        raise SystemExit("Baseline manifest no longer contains exactly style+base")
    for key in ("style", "base"):
        item = baseline_files[key]
        if not item.get("required") or len(str(item.get("sha256", ""))) != 64 or int(item.get("bytes", 0)) <= 0:
            raise SystemExit(f"Baseline {key} entry failed integrity metadata checks")

    source = json.loads(Path(args.source_metadata).read_text(encoding="utf-8"))
    if int(source.get("feature_count", 0)) < 100:
        raise SystemExit("Refusing to publish land test with implausibly small source feature count")

    land = Path(args.land)
    if not land.is_file() or land.stat().st_size <= 0:
        raise SystemExit("Land PMTiles file is missing/empty")
    if land.stat().st_size > 2_000_000_000:
        raise SystemExit("Land PMTiles exceeds RockMap per-file safety limit")

    # Keep the exact immutable Alpha 2 style/base file entries. The phone therefore reuses
    # its already-verified local files and downloads only the new land component.
    files = [baseline_files["style"], baseline_files["base"]]
    files.append({
        "id": "land",
        "kind": "pmtiles",
        "fileName": land.name,
        "url": f"https://github.com/{args.repo}/releases/download/{args.tag}/{land.name}",
        "sha256": sha256(land),
        "bytes": land.stat().st_size,
        "schemaVersion": 1,
        "required": True,
    })

    fetched = str(source.get("fetched_at", "")).replace(":", "").replace("-", "")
    short_stamp = fetched[:15] if fetched else "unknown"
    manifest = {
        "manifestVersion": 1,
        "status": "basemap_test",
        "pack": "Colorado Protomaps/OpenStreetMap + BLM land-status test",
        "version": f"alpha4-land-{short_stamp}",
        "publishedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "styleSchemaVersion": 1,
        "minimumAppVersionCode": 1,
        "message": "UNVERIFIED TEST DATA — BLM Colorado Surface Management Agency land status is included; mining claims are not included.",
        "files": files,
    }
    Path(args.output).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Wrote Alpha 4 land test manifest with {source['feature_count']} source polygons")


if __name__ == "__main__":
    main()
