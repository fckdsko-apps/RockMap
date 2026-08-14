#!/usr/bin/env python3
from pathlib import Path
import json
import re
import runpy
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []


def err(message):
    errors.append(message)


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


# Required project files. The workflow itself is deliberately NOT required here;
# source must be safe to commit independently of .github/workflows.
required_files = [
    "settings.gradle",
    "build.gradle",
    "gradle.properties",
    "app/build.gradle",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/assets/rockmap_safe_style.json",
    "app/src/main/assets/rockmap_basemap_label_style_alpha3.json",
    "app/src/main/assets/rockmap-glyphs/NOTICE.txt",
    "app/src/main/assets/rockmap-glyphs/OFL.txt",
    "app/src/main/res/xml/backup_rules.xml",
    "app/src/main/res/xml/data_extraction_rules.xml",
    "data/manifest.json",
    "data/basemap-test-style.json",
    "docs/DATA_CONTRACT.md",
    "docs/BASEMAP_ALPHA2.md",
    "docs/BASEMAP_ALPHA3.md",
    "scripts/create_basemap_test_manifest.py",
    "scripts/prepare_offline_glyphs.py",
    "scripts/validate_basemap_metadata.py",
    "signing-certificate.sha256",
    "app/src/test/java/com/rockmap/app/offline/DataValidatorsTest.java",
]
for rel in required_files:
    if not (ROOT / rel).is_file():
        err(f"Required project file missing: {rel}")

# Android manifest and permissions.
manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
try:
    ET.parse(manifest_path)
    manifest_xml = manifest_path.read_text(encoding="utf-8")
except Exception as exc:
    err(f"AndroidManifest.xml is invalid XML: {exc}")
    manifest_xml = ""

for forbidden in (
    "ACCESS_BACKGROUND_LOCATION",
    "MANAGE_EXTERNAL_STORAGE",
    "READ_EXTERNAL_STORAGE",
    "WRITE_EXTERNAL_STORAGE",
):
    if forbidden in manifest_xml:
        err(f"Forbidden/unneeded permission present: {forbidden}")

for required in (
    "ACCESS_FINE_LOCATION",
    "ACCESS_COARSE_LOCATION",
    "INTERNET",
    "ACCESS_NETWORK_STATE",
):
    if required not in manifest_xml:
        err(f"Required permission missing: {required}")

if 'android:usesCleartextTraffic="false"' not in manifest_xml:
    err("Cleartext traffic must remain disabled.")
if 'android:dataExtractionRules="@xml/data_extraction_rules"' not in manifest_xml:
    err("Android 12+ backup rules are not wired into the manifest.")
if 'android:fullBackupContent="@xml/backup_rules"' not in manifest_xml:
    err("Android 11-and-earlier backup rules are not wired into the manifest.")

# Backup rules: downloaded maps are replaceable; waypoint DB must remain backup-eligible.
for rel in (
    "app/src/main/res/xml/backup_rules.xml",
    "app/src/main/res/xml/data_extraction_rules.xml",
):
    try:
        ET.parse(ROOT / rel)
        text = read(rel)
        if 'path="maps/"' not in text or 'domain="file"' not in text:
            err(f"{rel} must exclude filesDir/maps from backup.")
        if re.search(r"<exclude[^>]+domain=\"database\"", text, re.I):
            err(f"{rel} must not exclude the waypoint database.")
    except Exception as exc:
        err(f"{rel} is invalid XML: {exc}")

# Safe fallback style is local, intentionally blank, and cannot silently fetch an online map.
try:
    style_text = read("app/src/main/assets/rockmap_safe_style.json")
    style = json.loads(style_text)
    if style.get("version") != 8:
        err("Safe style must use MapLibre style version 8.")
    if style.get("sources") != {}:
        err("Safe fallback style must contain no data sources.")
    lower = style_text.lower()
    if "http://" in lower or "https://" in lower:
        err("Safe fallback style must have no network dependency.")
except Exception as exc:
    err(f"Safe style invalid: {exc}")

# Alpha 2 release style remains the immutable no-label data-pack template. Alpha 3
# reuses the same PMTiles bytes but replaces this rendering template in-app.
try:
    basemap_style_text = read("data/basemap-test-style.json")
    basemap_style = json.loads(basemap_style_text)
    if basemap_style.get("version") != 8:
        err("Basemap test release style must use MapLibre style version 8.")
    sources = basemap_style.get("sources", {})
    base = sources.get("rockmap-base", {})
    if base.get("type") != "vector" or base.get("url") != "${ROCKMAP_BASE_URI}":
        err("Basemap test release style must expose rockmap-base at ${ROCKMAP_BASE_URI}.")
    if "${ROCKMAP_LAND_URI}" in basemap_style_text or "${ROCKMAP_CLAIMS_URI}" in basemap_style_text:
        err("Basemap test release style must not pretend land/claims data exist.")
    lower = basemap_style_text.lower()
    if "http://" in lower or "https://" in lower:
        err("Basemap test release style must have no runtime network dependency.")
    if '"glyphs"' in lower or '"sprite"' in lower:
        err("The pinned Alpha 2 release style must remain the original no-label template.")
    layer_ids = {layer.get("id") for layer in basemap_style.get("layers", []) if isinstance(layer, dict)}
    for layer_id in (
        "rockmap-water", "rockmap-streams", "rockmap-paths", "rockmap-minor-road",
        "rockmap-major-road", "rockmap-highway", "rockmap-buildings",
    ):
        if layer_id not in layer_ids:
            err(f"Basemap test release style missing test layer: {layer_id}")
except Exception as exc:
    err(f"Basemap test release style invalid: {exc}")

# Alpha 3 runtime label style must be wholly APK/local other than the local PMTiles placeholder.
try:
    label_style_text = read("app/src/main/assets/rockmap_basemap_label_style_alpha3.json")
    label_style = json.loads(label_style_text)
    if label_style.get("version") != 8:
        err("Alpha 3 label style must use MapLibre style version 8.")
    label_sources = label_style.get("sources", {})
    label_base = label_sources.get("rockmap-base", {})
    if label_base.get("type") != "vector" or label_base.get("url") != "${ROCKMAP_BASE_URI}":
        err("Alpha 3 label style must use the verified local rockmap-base PMTiles placeholder.")
    if label_style.get("glyphs") != "asset://rockmap-glyphs/{fontstack}/{range}.pbf":
        err("Alpha 3 glyph URL must point only to APK-bundled glyph PBFs.")
    if "sprite" in label_style:
        err("Alpha 3 label style must not depend on a sprite URL.")
    if "${ROCKMAP_LAND_URI}" in label_style_text or "${ROCKMAP_CLAIMS_URI}" in label_style_text:
        err("Alpha 3 label style must not pretend land/claims data exist.")
    lower = label_style_text.lower()
    if "http://" in lower or "https://" in lower:
        err("Alpha 3 runtime label style must have no HTTP/HTTPS dependency.")
    label_layers = [layer for layer in label_style.get("layers", []) if isinstance(layer, dict)]
    layer_ids = {layer.get("id") for layer in label_layers}
    for layer_id in (
        "rockmap-label-region",
        "rockmap-label-locality",
        "rockmap-label-neighbourhood",
        "rockmap-label-water",
        "rockmap-label-water-area",
        "rockmap-label-peak",
        "rockmap-label-road-major",
        "rockmap-label-road-minor",
    ):
        if layer_id not in layer_ids:
            err(f"Alpha 3 label style missing required label layer: {layer_id}")
    expected_sources = {
        "rockmap-label-region": "places",
        "rockmap-label-locality": "places",
        "rockmap-label-neighbourhood": "places",
        "rockmap-label-water": "water",
        "rockmap-label-water-area": "water",
        "rockmap-label-peak": "pois",
        "rockmap-label-road-major": "roads",
        "rockmap-label-road-minor": "roads",
    }
    for layer in label_layers:
        lid = layer.get("id")
        if lid in expected_sources and layer.get("source-layer") != expected_sources[lid]:
            err(f"Alpha 3 label layer {lid} uses unexpected source-layer {layer.get('source-layer')!r}.")
        if layer.get("type") == "symbol" and lid and lid.startswith("rockmap-label-"):
            if layer.get("layout", {}).get("text-font") != ["RockMapSans"]:
                err(f"Alpha 3 label layer {lid} must use only the bundled RockMapSans glyph stack.")
except Exception as exc:
    err(f"Alpha 3 label style invalid: {exc}")

# Alpha 3 does not track font/glyph binaries in source. The Android build fetches a
# small set of PBF ranges from one immutable upstream commit, verifies Git blob IDs,
# aliases the stack to RockMapSans, and writes only to Gradle's generated-assets tree.
try:
    glyph_script_path = ROOT / "scripts/prepare_offline_glyphs.py"
    glyph_module = runpy.run_path(str(glyph_script_path))
    expected_commit = "028c18f713baecad011301ff7a69acc39bcc2ae7"
    expected_blobs = {
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
    if glyph_module.get("UPSTREAM_COMMIT") != expected_commit:
        err("Alpha 3 glyph upstream commit unexpectedly changed.")
    if glyph_module.get("GLYPH_BLOBS") != expected_blobs:
        err("Alpha 3 glyph blob pins unexpectedly changed.")
    if glyph_module.get("OUTPUT_FONTSTACK") != "RockMapSans":
        err("Alpha 3 glyph generator must output the RockMapSans alias.")
    for glyph_range in expected_blobs:
        url = glyph_module["upstream_url"](glyph_range)
        expected_prefix = f"https://raw.githubusercontent.com/protomaps/basemaps-assets/{expected_commit}/fonts/"
        if not url.startswith(expected_prefix) or not url.endswith(f"/{glyph_range}.pbf"):
            err(f"Alpha 3 glyph URL is not pinned to the immutable upstream commit: {glyph_range}")

    # Mechanically test the protobuf name rewrite without downloading any font resource.
    ev = glyph_module["encode_varint"]
    original_name = b"Upstream Font Name"
    sample_range = b"0-255"
    stack = (ev((1 << 3) | 2) + ev(len(original_name)) + original_name
             + ev((2 << 3) | 2) + ev(len(sample_range)) + sample_range)
    top = ev((1 << 3) | 2) + ev(len(stack)) + stack
    rewritten = glyph_module["rewrite_fontstack"](top, "0-255")
    parsed_stack, _ = glyph_module["read_length_field"](rewritten, 0, 1)
    parsed_name, parsed_name_end = glyph_module["read_length_field"](parsed_stack, 0, 1)
    parsed_range, _ = glyph_module["read_length_field"](parsed_stack, parsed_name_end, 2)
    if parsed_name != b"RockMapSans" or parsed_range != b"0-255":
        err("Alpha 3 glyph protobuf rewrite self-test failed.")
except Exception as exc:
    err(f"Alpha 3 glyph preparation script validation failed: {exc}")

try:
    ofl = read("app/src/main/assets/rockmap-glyphs/OFL.txt")
    notice = read("app/src/main/assets/rockmap-glyphs/NOTICE.txt")
    if "SIL OPEN FONT LICENSE Version 1.1" not in ofl:
        err("Offline glyph resource license text is missing SIL OFL 1.1.")
    if "RockMapSans" not in notice or "does not include a TTF/OTF font file" not in notice:
        err("Offline glyph NOTICE must describe the generated alias and absence of source font binaries.")
except Exception as exc:
    err(f"Glyph license/notice validation failed: {exc}")

# The repository placeholder manifest remains unpublished. Alpha 3 still fetches its
# basemap-test manifest from the existing fixed Alpha 2 GitHub Release tag.
try:
    data_manifest = json.loads(read("data/manifest.json"))
    if data_manifest.get("manifestVersion") != 1:
        err("Repository data manifest must be schema version 1.")
    if data_manifest.get("status") != "not_published":
        err("The repository placeholder manifest must not claim a field-safe Colorado pack is published.")
    if "field-safe" not in data_manifest.get("message", ""):
        err("Unpublished manifest must carry the field-safety warning.")
except Exception as exc:
    err(f"data/manifest.json invalid: {exc}")

# Build/dependency pins verified against the known-good Aug 2026 CI/device chain.
all_gradle = read("build.gradle") + "\n" + read("app/build.gradle")
required_pins = {
    "AGP": "9.3.0",
    "MapLibre": "13.4.1",
    "Room": "2.8.4",
    "WorkManager": "2.11.2",
    "Build Tools": "36.0.0",
}
for label, version in required_pins.items():
    if version not in all_gradle:
        err(f"{label} expected pinned version {version} not found.")
if "compileSdk 37" not in all_gradle:
    err("compileSdk 37 is required by the known-good project configuration.")
if "targetSdk 36" not in all_gradle:
    err("targetSdk 36 unexpectedly changed.")
if "minSdk 26" not in all_gradle:
    err("minSdk 26 unexpectedly changed.")
if "rockmap-basemap-alpha2-20260722-z14" not in all_gradle or "/releases/download/" not in all_gradle:
    err("Alpha 3 must reuse the fixed Alpha 2 data release rather than rebuild the basemap.")
if "ROCKMAP_VERSION_NAME=0.1.0-alpha3" not in read("gradle.properties"):
    err("Alpha 3 version name is not pinned in gradle.properties.")
if re.search(r"(?:implementation|annotationProcessor|testImplementation)\s+['\"][^'\"]*\+", all_gradle):
    err("Dynamic dependency version detected.")
for required_gradle in (
    "generatedOfflineGlyphAssetsDir",
    "assets.srcDir generatedOfflineGlyphAssetsDir",
    "tasks.register('prepareOfflineGlyphs', Exec)",
    "scripts/prepare_offline_glyphs.py",
    "dependsOn tasks.named('prepareOfflineGlyphs')",
):
    if required_gradle not in all_gradle:
        err(f"Alpha 3 generated offline glyph build wiring missing: {required_gradle}")

# Java/source guardrails.
java_files = list((ROOT / "app/src/main/java").rglob("*.java"))
if not java_files:
    err("No application Java sources found.")
java_text = "\n".join(p.read_text(encoding="utf-8") for p in java_files)
for forbidden_source in (
    "fallbackToDestructiveMigration(",
    "java.nio.file.Files",
    ".isBlank()",
):
    if forbidden_source in java_text:
        err(f"Forbidden/risky source pattern present: {forbidden_source}")
if "@Override protected void onLowMemory()" in java_text:
    err("Activity.onLowMemory() must remain public on current Android APIs.")
if "@Override public void onLowMemory()" not in java_text:
    err("MainActivity must forward public onLowMemory() to MapView.")

for required_source in (
    "pmtiles://",
    "${ROCKMAP_BASE_URI}",
    "${ROCKMAP_LAND_URI}",
    "${ROCKMAP_CLAIMS_URI}",
    "rockmap_basemap_label_style_alpha3.json",
    "readAssetUtf8",
    "LABEL_LOCALITY",
    "LABEL_ROAD_MAJOR",
    "LABEL_WATER",
    "LABEL_PEAK",
    "revertToPreviousManifest",
    "STATUS_BASEMAP_TEST",
    "hasRenderableActivePack",
    "hasLandClaimsAvailable",
    "No claim feature was rendered",
    "Treat this as unknown, not as public land",
):
    if required_source not in java_text:
        err(f"Offline/safety implementation is missing: {required_source}")
if "labels are not included yet" in java_text:
    err("Alpha 3 source still reports labels as absent.")

# Stable data contract.
contract = read("docs/DATA_CONTRACT.md")
for required in (
    "alpha3",
    "basemap_test",
    "${ROCKMAP_BASE_URI}",
    "${ROCKMAP_LAND_URI}",
    "${ROCKMAP_CLAIMS_URI}",
    "rockmap-base",
    "rockmap-land",
    "rockmap-claims",
    "rockmap-land-fill",
    "rockmap-land-outline",
    "rockmap-claim-fill",
    "rockmap-claim-outline",
    "asset://rockmap-glyphs/{fontstack}/{range}.pbf",
    "rockmap-label-locality",
    "rockmap-label-road-major",
    "rockmap-label-water",
    "rockmap-label-peak",
):
    if required not in contract:
        err(f"Data contract missing required term: {required}")

alpha3_doc = read("docs/BASEMAP_ALPHA3.md") if (ROOT / "docs/BASEMAP_ALPHA3.md").is_file() else ""
for required in ("airplane mode", "Do not uninstall", "NOT VERIFIED FOR NAVIGATION", "land status", "mining claims"):
    if required not in alpha3_doc:
        err(f"Alpha 3 acceptance test missing required instruction: {required}")

# Public signing fingerprint is required; private signing material and original font files are forbidden.
fingerprint = ROOT / "signing-certificate.sha256"
if fingerprint.exists():
    value = fingerprint.read_text(encoding="utf-8").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", value):
        err("signing-certificate.sha256 must contain exactly one SHA-256 fingerprint.")
else:
    err("Public signing certificate fingerprint is missing.")

secret_tokens = (
    "storePassword=",
    "keyPassword=",
    "ROCKMAP_SIGNING_BUNDLE_B64=",
    "BEGIN PRIVATE KEY",
)
font_suffixes = {".ttf", ".otf", ".woff", ".woff2", ".ttc"}
file_count = 0
for path in ROOT.rglob("*"):
    if not path.is_file() or ".git" in path.parts:
        continue
    file_count += 1
    rel = path.relative_to(ROOT)
    if path.suffix.lower() in {".jks", ".keystore", ".p12", ".pfx"}:
        err(f"Private signing material must never be tracked: {rel}")
        continue
    if path.suffix.lower() in font_suffixes:
        err(f"Original font files must not be tracked or distributed in the RockMap patch: {rel}")
    if path.suffix.lower() == ".pbf" and "rockmap-glyphs" in rel.parts:
        err(f"Generated glyph PBF binaries must not be tracked in source: {rel}")
    size = path.stat().st_size
    if size > 25 * 1024 * 1024:
        err(f"Source file is too large for GitHub browser upload: {rel}")
    if size <= 2_000_000 and rel.as_posix() != "scripts/preflight.py":
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            text = ""
        for token in secret_tokens:
            if token in text:
                err(f"Potential private signing material is tracked in {rel}: {token}")

if file_count > 100:
    err(f"Source package has {file_count} files; keep it at or below 100 for one browser upload.")

if errors:
    print("PRE-FLIGHT FAILED")
    for item in errors:
        print(" -", item)
    sys.exit(1)

print(f"RockMap Alpha 3 source preflight passed ({file_count} files checked).")
