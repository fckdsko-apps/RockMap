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
    p.add_argument("--claims", required=True)
    p.add_argument("--source-metadata", required=True)
    p.add_argument("--output", required=True)
    args = p.parse_args()

    baseline = json.loads(Path(args.baseline_manifest).read_text(encoding="utf-8"))
    if baseline.get("manifestVersion") != 1 or baseline.get("styleSchemaVersion") != 1:
        raise SystemExit("Alpha 4 baseline manifest contract is not schema 1")
    if baseline.get("status") != "basemap_test":
        raise SystemExit("Alpha 4 baseline manifest is not the known basemap_test snapshot")
    baseline_files = {x.get("id"): x for x in baseline.get("files", []) if isinstance(x, dict)}
    if set(baseline_files) != {"style", "base", "land"}:
        raise SystemExit("Alpha 4 baseline no longer contains exactly style+base+land")
    for key in ("style", "base", "land"):
        item = baseline_files[key]
        if not item.get("required") or len(str(item.get("sha256", ""))) != 64 or int(item.get("bytes", 0)) <= 0:
            raise SystemExit(f"Alpha 4 baseline {key} entry failed integrity metadata checks")

    source = json.loads(Path(args.source_metadata).read_text(encoding="utf-8"))
    if int(source.get("included_feature_count", 0)) < 100 or int(source.get("unique_case_serial_count", 0)) < 100:
        raise SystemExit("Refusing to publish Alpha 5 with implausibly small claims source counts")

    claims = Path(args.claims)
    if not claims.is_file() or claims.stat().st_size <= 0:
        raise SystemExit("Claims PMTiles file is missing/empty")
    if claims.stat().st_size > 2_000_000_000:
        raise SystemExit("Claims PMTiles exceeds RockMap per-file safety limit")

    # Copy the exact immutable Alpha 4 style/base/land entries so phones that already have
    # Alpha 4 reuse all three files byte-for-byte and download only the new claims PMTiles.
    files = [baseline_files["style"], baseline_files["base"], baseline_files["land"]]
    files.append({
        "id": "claims",
        "kind": "pmtiles",
        "fileName": claims.name,
        "url": f"https://github.com/{args.repo}/releases/download/{args.tag}/{claims.name}",
        "sha256": sha256(claims),
        "bytes": claims.stat().st_size,
        "schemaVersion": 1,
        "required": True,
    })

    fetched = str(source.get("fetched_at", "")).replace(":", "").replace("-", "")
    short_stamp = fetched[:15] if fetched else "unknown"
    manifest = {
        "manifestVersion": 1,
        "status": "basemap_test",
        "pack": "Colorado Protomaps/OpenStreetMap + BLM land status + MLRS mining claims test",
        "version": f"alpha5-claims-{short_stamp}",
        "publishedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "styleSchemaVersion": 1,
        "minimumAppVersionCode": 1,
        "message": "UNVERIFIED TEST DATA — BLM Colorado SMA land status and BLM MLRS mining-claim cases with disposition not closed are included. Absence of a rendered claim is not proof that no claim exists.",
        "files": files,
    }
    Path(args.output).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        f"Wrote Alpha 5 claims test manifest with {source['included_feature_count']} claim geometries "
        f"and {source['unique_case_serial_count']} unique cases"
    )


if __name__ == "__main__":
    main()
