#!/usr/bin/env python3
import json
import sys
from pathlib import Path

REQUIRED_FIELDS = {"manager_code", "manager_name"}


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: validate_land_metadata.py PMTILES_METADATA.json SOURCE_METADATA.json")
    pm = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    source = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    layers = pm.get("vector_layers")
    if not isinstance(layers, list):
        raise SystemExit("Land PMTiles metadata has no vector_layers list")
    land = next((x for x in layers if isinstance(x, dict) and x.get("id") == "land"), None)
    if land is None:
        raise SystemExit("Land PMTiles is missing required vector layer: land")
    fields = land.get("fields")
    if not isinstance(fields, dict):
        raise SystemExit("Land PMTiles metadata has no fields map for layer 'land'")
    missing = sorted(REQUIRED_FIELDS - set(fields))
    if missing:
        raise SystemExit("Land PMTiles is missing normalized fields: " + ", ".join(missing))
    if set(fields) - REQUIRED_FIELDS:
        raise SystemExit("Land PMTiles contains unexpected raw fields: " + ", ".join(sorted(set(fields) - REQUIRED_FIELDS)))
    if int(source.get("feature_count", 0)) < 100:
        raise SystemExit("Source metadata feature count is implausibly small")
    counts = source.get("manager_code_counts")
    if not isinstance(counts, dict) or not all(int(counts.get(k, 0)) > 0 for k in ("BLM", "PRI", "STA", "USFS")):
        raise SystemExit("Source metadata is missing expected BLM/PRI/STA/USFS categories")
    print("Alpha 4 land PMTiles schema check passed")


if __name__ == "__main__":
    main()
