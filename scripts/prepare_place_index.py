#!/usr/bin/env python3
"""Legacy Alpha 6.6 helper retained only to fail safely if invoked manually.

RockMap place search is generated on-device from the installed PMTiles basemap. The APK
build has no network place-index step and this helper does not create a Colorado index.
"""
from __future__ import annotations

import argparse
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path)
    parser.parse_args()
    print("RockMap place search is generated on-device from the installed basemap; no build-time place index is required.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
