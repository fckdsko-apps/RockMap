#!/usr/bin/env python3
"""Generate RockMap's Alpha 6.6 offline Find index during the normal Gradle build.

This runs from the normal Gradle build and derives a compact search index
from RockMap's own immutable Colorado PMTiles basemap by range-extracting only zoom 9-12,
decoding named features, and passing them to build_place_index.py.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import stat
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

PMTILES_VERSION = "1.31.2"
PMTILES_ARCHIVE_SHA256 = "3ed7dbf4ec2e6dfe5e25b6f70d1ffc932729f93c86db353bf514dd71010a312f"
TIPPECANOE_COMMIT = "68ab8dcc229f95b8b25877697d5e8d66783af503"
BASE_RELEASE_TAG = "rockmap-basemap-alpha2-20260722-z14"
BASE_NAME = "colorado-base-protomaps-20260722-z14.pmtiles"
COLORADO_BBOX = "-109.10,36.95,-102.00,41.05"
SEARCH_MINZOOM = 9
SEARCH_MAXZOOM = 12
MAX_SUBSET_BYTES = 120_000_000
MIN_INDEX_BYTES = 10_000
MAX_INDEX_BYTES = 12 * 1024 * 1024

ROOT = Path(__file__).resolve().parents[1]


def run(args: list[str], *, cwd: Path | None = None, stdout=None) -> None:
    shown = " ".join(str(x) for x in args)
    print(f"+ {shown}", flush=True)
    subprocess.run(args, cwd=cwd, check=True, stdout=stdout)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.is_file() and output.stat().st_size > 0:
        return
    print(f"Downloading {url}", flush=True)
    request = urllib.request.Request(url, headers={"User-Agent": "RockMap-Alpha6.6-build"})
    with urllib.request.urlopen(request, timeout=120) as response, output.open("wb") as handle:
        shutil.copyfileobj(response, handle, length=1024 * 1024)


def find_executable(root: Path, name: str) -> Path | None:
    for path in root.rglob(name):
        if path.is_file():
            path.chmod(path.stat().st_mode | stat.S_IXUSR)
            return path
    return None


def prepare_pmtiles(work: Path) -> Path:
    tool_dir = work / "pmtiles-tool"
    existing = find_executable(tool_dir, "pmtiles") if tool_dir.exists() else None
    if existing:
        return existing

    archive = work / "pmtiles-tool.tar.gz"
    url = (
        f"https://github.com/protomaps/go-pmtiles/releases/download/v{PMTILES_VERSION}/"
        f"go-pmtiles_{PMTILES_VERSION}_Linux_x86_64.tar.gz"
    )
    download(url, archive)
    actual = sha256(archive)
    if actual != PMTILES_ARCHIVE_SHA256:
        raise SystemExit(
            f"Pinned PMTiles CLI checksum mismatch: expected {PMTILES_ARCHIVE_SHA256}, got {actual}"
        )
    tool_dir.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, "r:gz") as tar:
        tar.extractall(tool_dir)
    binary = find_executable(tool_dir, "pmtiles")
    if binary is None:
        raise SystemExit("Pinned PMTiles CLI archive did not contain pmtiles")
    run([str(binary), "version"])
    return binary


def checkout_tippecanoe(work: Path) -> Path:
    source = work / "tippecanoe"
    binary = source / "tippecanoe-decode"
    if binary.is_file():
        binary.chmod(binary.stat().st_mode | stat.S_IXUSR)
        return binary

    if source.exists():
        shutil.rmtree(source)
    source.mkdir(parents=True)
    run(["git", "init", "-q", str(source)])
    run(["git", "-C", str(source), "remote", "add", "origin", "https://github.com/felt/tippecanoe.git"])
    run(["git", "-C", str(source), "fetch", "-q", "--depth", "1", "origin", TIPPECANOE_COMMIT])
    run(["git", "-C", str(source), "checkout", "-q", "--detach", "FETCH_HEAD"])
    actual = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if actual != TIPPECANOE_COMMIT:
        raise SystemExit(f"Pinned tippecanoe checkout mismatch: expected {TIPPECANOE_COMMIT}, got {actual}")

    try:
        run(["make", "-C", str(source), "-j2", "tippecanoe-decode"])
    except subprocess.CalledProcessError:
        if os.environ.get("GITHUB_ACTIONS", "").lower() != "true" or shutil.which("sudo") is None:
            raise
        print("tippecanoe-decode prerequisites missing; installing minimal Ubuntu build packages", flush=True)
        run(["sudo", "apt-get", "update", "-qq"])
        run([
            "sudo", "apt-get", "install", "-y", "--no-install-recommends",
            "make", "g++", "libsqlite3-dev", "zlib1g-dev"
        ])
        run(["make", "-C", str(source), "-j2", "tippecanoe-decode"])

    if not binary.is_file():
        raise SystemExit("tippecanoe-decode was not produced")
    binary.chmod(binary.stat().st_mode | stat.S_IXUSR)
    return binary


def validate_existing(output: Path) -> bool:
    if not output.is_file():
        return False
    size = output.stat().st_size
    return MIN_INDEX_BYTES <= size <= MAX_INDEX_BYTES


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()

    output_dir = args.output_dir.resolve()
    output = output_dir / "rockmap_place_index.tsv.gz"
    if validate_existing(output):
        print(f"Reusing generated RockMap place index: {output} ({output.stat().st_size} bytes)")
        return 0

    output_dir.mkdir(parents=True, exist_ok=True)
    work = ROOT / "app" / "build" / "rockmapPlaceIndexWork"
    work.mkdir(parents=True, exist_ok=True)

    pmtiles = prepare_pmtiles(work)
    decoder = checkout_tippecanoe(work)

    repository = os.environ.get("GITHUB_REPOSITORY", "fckdsko-apps/RockMap").strip() or "fckdsko-apps/RockMap"
    if repository != "fckdsko-apps/RockMap":
        # Fork builds should still consume the immutable canonical RockMap basemap rather than
        # assuming the fork copied GitHub Release assets.
        repository = "fckdsko-apps/RockMap"
    base_url = f"https://github.com/{repository}/releases/download/{BASE_RELEASE_TAG}/{BASE_NAME}"

    subset = work / f"rockmap-search-z{SEARCH_MINZOOM}-z{SEARCH_MAXZOOM}.pmtiles"
    decoded = work / "rockmap-search-features.ndjson"

    if not subset.is_file() or subset.stat().st_size == 0:
        subset.unlink(missing_ok=True)
        run([str(pmtiles), "show", base_url])
        run([
            str(pmtiles), "extract", base_url, str(subset),
            f"--bbox={COLORADO_BBOX}",
            f"--minzoom={SEARCH_MINZOOM}",
            f"--maxzoom={SEARCH_MAXZOOM}",
            "--download-threads=8",
            "--overfetch=0.05",
        ])
    if not subset.is_file() or subset.stat().st_size == 0:
        raise SystemExit("RockMap PMTiles overview subset was not produced")
    if subset.stat().st_size > MAX_SUBSET_BYTES:
        raise SystemExit(
            f"RockMap search overview subset exceeds {MAX_SUBSET_BYTES} bytes: {subset.stat().st_size}"
        )
    print(f"RockMap PMTiles search subset: {subset.stat().st_size} bytes", flush=True)

    with decoded.open("wb") as handle:
        run([
            str(decoder), "-c", "-Z", str(SEARCH_MINZOOM), "-z", str(SEARCH_MAXZOOM),
            "-l", "places", "-l", "pois", "-l", "water", "-l", "roads",
            "-y", "name", "-y", "name:en", "-y", "kind", "-y", "kind_detail",
            "-y", "ref", "-y", "shield_text", "-y", "min_zoom", "-y", "sort_rank",
            "-y", "population_rank", "-y", "ele", "-y", "reservoir",
            str(subset),
        ], stdout=handle)
    if not decoded.is_file() or decoded.stat().st_size == 0:
        raise SystemExit("RockMap PMTiles decode produced no feature stream")

    builder = ROOT / "scripts" / "build_place_index.py"
    run([
        sys.executable, str(builder),
        "--input", str(decoded),
        "--output", str(output),
        "--source",
        f"RockMap immutable Colorado Protomaps basemap 2026-07-22; overview tiles z{SEARCH_MINZOOM}-z{SEARCH_MAXZOOM}",
    ])

    if not validate_existing(output):
        size = output.stat().st_size if output.exists() else 0
        raise SystemExit(f"Generated RockMap place index failed final size guard: {size} bytes")
    print(f"Prepared RockMap offline Find asset: {output} ({output.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
