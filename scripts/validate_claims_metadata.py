#!/usr/bin/env python3
import json
import sys
from pathlib import Path

REQUIRED_FIELDS = {
    "name", "serial", "legacy_serial", "type", "type_code", "disposition",
    "acres", "quality", "quality_description", "source",
}


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: validate_claims_metadata.py PMTILES_METADATA.json SOURCE_METADATA.json")
    pm = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    source = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
    layers = pm.get("vector_layers")
    if not isinstance(layers, list):
        raise SystemExit("Claims PMTiles metadata has no vector_layers list")
    claims = next((x for x in layers if isinstance(x, dict) and x.get("id") == "claims"), None)
    if claims is None:
        raise SystemExit("Claims PMTiles is missing required vector layer: claims")
    fields = claims.get("fields")
    if not isinstance(fields, dict):
        raise SystemExit("Claims PMTiles metadata has no fields map for layer 'claims'")
    missing = sorted(REQUIRED_FIELDS - set(fields))
    if missing:
        raise SystemExit("Claims PMTiles is missing normalized fields: " + ", ".join(missing))
    extra = sorted(set(fields) - REQUIRED_FIELDS)
    if extra:
        raise SystemExit("Claims PMTiles contains unexpected raw fields: " + ", ".join(extra))

    included = int(source.get("included_feature_count", 0))
    unique_cases = int(source.get("unique_case_serial_count", 0))
    if included < 100 or unique_cases < 100:
        raise SystemExit("Claims source metadata feature/case count is implausibly small")
    type_counts = source.get("claim_type_counts")
    if not isinstance(type_counts, dict) or int(type_counts.get("Lode Claim", 0)) <= 0 or int(type_counts.get("Placer Claim", 0)) <= 0:
        raise SystemExit("Claims source metadata is missing expected Lode/Placer categories")
    policy = str(source.get("quality_policy", ""))
    if "25" not in policy or "county" not in policy.lower():
        raise SystemExit("Claims source metadata does not document county-only quality filtering")
    print("Alpha 5 claims PMTiles schema check passed")


if __name__ == "__main__":
    main()
