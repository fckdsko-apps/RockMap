#!/usr/bin/env python3
import json
import sys
from pathlib import Path

REQUIRED_VECTOR_LAYERS = {"earth", "landcover", "landuse", "water", "roads", "buildings", "places", "pois"}


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: validate_basemap_metadata.py METADATA.json")
    path = Path(sys.argv[1])
    metadata = json.loads(path.read_text(encoding="utf-8"))
    vector_layers = metadata.get("vector_layers")
    if not isinstance(vector_layers, list):
        raise SystemExit("PMTiles metadata has no vector_layers list")
    ids = {entry.get("id") for entry in vector_layers if isinstance(entry, dict)}
    missing = sorted(REQUIRED_VECTOR_LAYERS - ids)
    if missing:
        raise SystemExit("PMTiles basemap is missing required vector layers: " + ", ".join(missing))
    print("PMTiles basemap schema check passed: " + ", ".join(sorted(REQUIRED_VECTOR_LAYERS)))


if __name__ == "__main__":
    main()
