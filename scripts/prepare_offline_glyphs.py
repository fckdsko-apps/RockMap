#!/usr/bin/env python3
"""Prepare RockMap's offline MapLibre glyph assets during the Android build.

The source repository intentionally does not track font binaries or glyph PBFs.
This script prepares two independent local text paths for MapLibre Native Android:
verified SDF glyph PBF ranges and a verified Noto Sans TTF font face. Both are
fetched from immutable upstream commits and written only to Gradle generated assets.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import tempfile
from urllib.parse import quote
from urllib.request import Request, urlopen

UPSTREAM_COMMIT = "028c18f713baecad011301ff7a69acc39bcc2ae7"
UPSTREAM_FONT_DIR = "Noto Sans Regular"
OUTPUT_FONTSTACK = "RockMapSans"
MAX_FILE_BYTES = 500_000

NOTO_COMMIT = "445abfe2d405cb658a9d825ab056e2004fb60627"
NOTO_TTF_PATH = "fonts/NotoSans/hinted/ttf/NotoSans-Regular.ttf"
NOTO_TTF_BLOB = "f27f4ff59562d58480f1cb94194393484b8da9e9"
NOTO_TTF_OUTPUT = "rockmap-fonts/NotoSans-Regular.ttf"
MAX_TTF_BYTES = 2_000_000

# Git blob SHA-1 values are content identities from the pinned upstream commit.
GLYPH_BLOBS = {
    "0-255": "7f65901599b368dc8c1d70d5fed9642148db9836",
    "256-511": "f0302889321b2fb9f83e13b5df1a9a6b0b10e6f3",
    "512-767": "5cde89d339b3cf0be2039f376fe20cadc75e533e",
    "768-1023": "a1d0bd9140db88a231ba88991e3d3e2191387448",
    "1024-1279": "d2ae7ab5bc345cbffad661e017e9444a27f78444",
    "1280-1535": "a4a018a3ec5a9a9d0ebe75c33929fdaa2d9e5e1c",
    "1536-1791": "49ab28abcdccf2cb0be4dbc3d06ef052dc502696",
    "1792-2047": "0db3589c68277a99bc2979fd333104423ed7bda3",
    "8192-8447": "9d7bcf0e89f0d1605c5b5cc75960f6deac70d18e",
}


def encode_varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("varint cannot be negative")
    out = bytearray()
    while True:
        b = value & 0x7F
        value >>= 7
        if value:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def decode_varint(data: bytes, pos: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while pos < len(data) and shift <= 63:
        b = data[pos]
        pos += 1
        value |= (b & 0x7F) << shift
        if not (b & 0x80):
            return value, pos
        shift += 7
    raise ValueError("invalid protobuf varint")


def read_length_field(data: bytes, pos: int, expected_field: int) -> tuple[bytes, int]:
    key, pos = decode_varint(data, pos)
    if key != (expected_field << 3) | 2:
        raise ValueError(f"expected length-delimited protobuf field {expected_field}")
    length, pos = decode_varint(data, pos)
    end = pos + length
    if end > len(data):
        raise ValueError("protobuf field exceeds payload")
    return data[pos:end], end


def rewrite_fontstack(raw: bytes, expected_range: str) -> bytes:
    # Glyph PBF format: top-level glyphs.stacks field 1 -> fontstack message.
    stack, top_end = read_length_field(raw, 0, 1)
    old_name, name_end = read_length_field(stack, 0, 1)
    range_bytes, range_end = read_length_field(stack, name_end, 2)
    if range_bytes.decode("utf-8") != expected_range:
        raise ValueError(f"glyph range metadata mismatch: expected {expected_range}")
    if not old_name:
        raise ValueError("upstream font-stack name is empty")

    replacement = OUTPUT_FONTSTACK.encode("utf-8")
    new_stack = (
        encode_varint((1 << 3) | 2)
        + encode_varint(len(replacement))
        + replacement
        + stack[name_end:]
    )
    rewritten = (
        encode_varint((1 << 3) | 2)
        + encode_varint(len(new_stack))
        + new_stack
        + raw[top_end:]
    )

    check_stack, _ = read_length_field(rewritten, 0, 1)
    check_name, check_name_end = read_length_field(check_stack, 0, 1)
    check_range, _ = read_length_field(check_stack, check_name_end, 2)
    if check_name.decode("utf-8") != OUTPUT_FONTSTACK:
        raise ValueError("font-stack alias rewrite failed")
    if check_range.decode("utf-8") != expected_range:
        raise ValueError("range changed during font-stack rewrite")
    return rewritten


def git_blob_sha1(data: bytes) -> str:
    header = f"blob {len(data)}\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def upstream_url(glyph_range: str) -> str:
    encoded_dir = quote(UPSTREAM_FONT_DIR, safe="")
    return (
        "https://raw.githubusercontent.com/protomaps/basemaps-assets/"
        f"{UPSTREAM_COMMIT}/fonts/{encoded_dir}/{glyph_range}.pbf"
    )


def download(glyph_range: str, expected_blob: str) -> bytes:
    url = upstream_url(glyph_range)
    request = Request(url, headers={"User-Agent": "RockMap-GitHub-Actions/alpha3.1"})
    with urlopen(request, timeout=30) as response:
        final_url = response.geturl()
        if not final_url.startswith("https://raw.githubusercontent.com/"):
            raise RuntimeError(f"unexpected glyph download redirect: {final_url}")
        declared = response.headers.get("Content-Length")
        if declared is not None and int(declared) > MAX_FILE_BYTES:
            raise RuntimeError(f"glyph response too large for {glyph_range}")
        data = response.read(MAX_FILE_BYTES + 1)
    if not data or len(data) > MAX_FILE_BYTES:
        raise RuntimeError(f"glyph payload size invalid for {glyph_range}")
    actual_blob = git_blob_sha1(data)
    if actual_blob != expected_blob:
        raise RuntimeError(
            f"glyph Git blob mismatch for {glyph_range}: expected {expected_blob}, got {actual_blob}"
        )
    return data



def download_noto_ttf() -> bytes:
    url = (
        "https://raw.githubusercontent.com/notofonts/notofonts.github.io/"
        f"{NOTO_COMMIT}/{NOTO_TTF_PATH}"
    )
    request = Request(url, headers={"User-Agent": "RockMap-GitHub-Actions/alpha3.1"})
    with urlopen(request, timeout=30) as response:
        final_url = response.geturl()
        if not final_url.startswith("https://raw.githubusercontent.com/"):
            raise RuntimeError(f"unexpected font download redirect: {final_url}")
        declared = response.headers.get("Content-Length")
        if declared is not None and int(declared) > MAX_TTF_BYTES:
            raise RuntimeError("Noto Sans font response is unexpectedly large")
        data = response.read(MAX_TTF_BYTES + 1)
    if not data or len(data) > MAX_TTF_BYTES:
        raise RuntimeError("Noto Sans font payload size is invalid")
    actual_blob = git_blob_sha1(data)
    if actual_blob != NOTO_TTF_BLOB:
        raise RuntimeError(
            f"Noto Sans Git blob mismatch: expected {NOTO_TTF_BLOB}, got {actual_blob}"
        )
    return data


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    final_root = args.output.resolve()
    final_stack = final_root / "rockmap-glyphs" / OUTPUT_FONTSTACK
    temp_parent = final_root.parent
    temp_parent.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="rockmap-glyphs-", dir=temp_parent) as temp_name:
        temp_root = Path(temp_name)
        staging_root = temp_root / "assets"
        temp_stack = staging_root / "rockmap-glyphs" / OUTPUT_FONTSTACK
        temp_stack.mkdir(parents=True, exist_ok=True)

        for glyph_range, expected_blob in GLYPH_BLOBS.items():
            raw = download(glyph_range, expected_blob)
            rewritten = rewrite_fontstack(raw, glyph_range)
            out = temp_stack / f"{glyph_range}.pbf"
            out.write_bytes(rewritten)
            print(f"Prepared {out.name}: {len(rewritten)} bytes")

        # Alpha 3.1 adds MapLibre Native's font-faces path as an independent
        # renderer fallback. The source repository still contains no font binary.
        font_out = staging_root / NOTO_TTF_OUTPUT
        font_out.parent.mkdir(parents=True, exist_ok=True)
        font_bytes = download_noto_ttf()
        font_out.write_bytes(font_bytes)
        print(f"Prepared {font_out.name}: {len(font_bytes)} bytes")

        # Replace the generated asset set at directory granularity. The staging child is
        # moved out; TemporaryDirectory itself remains in place for clean context cleanup.
        if final_root.exists():
            shutil.rmtree(final_root)
        os.replace(staging_root, final_root)

    if not final_stack.is_dir():
        raise SystemExit("offline glyph generation failed")
    final_font = final_root / NOTO_TTF_OUTPUT
    if not final_font.is_file():
        raise SystemExit("offline font-face generation failed")
    print(f"Offline text assets ready: {final_stack}; {final_font}")


if __name__ == "__main__":
    main()
