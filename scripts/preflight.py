#!/usr/bin/env python3
from pathlib import Path
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []


def err(message):
    errors.append(message)


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


# Required project files. The workflow itself is deliberately NOT required here;
# source must be safe to commit before .github/workflows exists.
required_files = [
    "settings.gradle",
    "build.gradle",
    "gradle.properties",
    "app/build.gradle",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/assets/rockmap_safe_style.json",
    "app/src/main/res/xml/backup_rules.xml",
    "app/src/main/res/xml/data_extraction_rules.xml",
    "data/manifest.json",
    "data/basemap-test-style.json",
    "docs/DATA_CONTRACT.md",
    "docs/BASEMAP_ALPHA2.md",
    "scripts/create_basemap_test_manifest.py",
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

# Alpha 2 basemap-test style: real local vector map, but intentionally no labels/overlays.
try:
    basemap_style_text = read("data/basemap-test-style.json")
    basemap_style = json.loads(basemap_style_text)
    if basemap_style.get("version") != 8:
        err("Basemap test style must use MapLibre style version 8.")
    sources = basemap_style.get("sources", {})
    base = sources.get("rockmap-base", {})
    if base.get("type") != "vector" or base.get("url") != "${ROCKMAP_BASE_URI}":
        err("Basemap test style must expose rockmap-base at ${ROCKMAP_BASE_URI}.")
    if "${ROCKMAP_LAND_URI}" in basemap_style_text or "${ROCKMAP_CLAIMS_URI}" in basemap_style_text:
        err("Basemap test style must not pretend land/claims data exist.")
    lower = basemap_style_text.lower()
    if "http://" in lower or "https://" in lower:
        err("Basemap test style must have no runtime network dependency.")
    if '"glyphs"' in lower or '"sprite"' in lower:
        err("Alpha 2 basemap test must not depend on glyphs or sprites yet.")
    layer_ids = {layer.get("id") for layer in basemap_style.get("layers", []) if isinstance(layer, dict)}
    for layer_id in ("rockmap-water", "rockmap-streams", "rockmap-paths", "rockmap-minor-road", "rockmap-major-road", "rockmap-highway", "rockmap-buildings"):
        if layer_id not in layer_ids:
            err(f"Basemap test style missing test layer: {layer_id}")
except Exception as exc:
    err(f"Basemap test style invalid: {exc}")

# The repository placeholder manifest remains unpublished. Alpha 2 fetches its test
# manifest from the dedicated GitHub Release tag, not from this source-tree file.
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

# Build/dependency pins verified against the Aug 2026 project configuration.
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
    err("compileSdk 37 is required by the pinned project configuration.")
if "targetSdk 36" not in all_gradle:
    err("targetSdk 36 unexpectedly changed.")
if "minSdk 26" not in all_gradle:
    err("minSdk 26 unexpectedly changed.")
if "rockmap-basemap-alpha2" not in all_gradle or "/releases/download/" not in all_gradle:
    err("Alpha 2 must fetch its manifest from the fixed GitHub Release tag.")
if "ROCKMAP_VERSION_NAME=0.1.0-alpha2" not in read("gradle.properties"):
    err("Alpha 2 version name is not pinned in gradle.properties.")
if re.search(r"(?:implementation|annotationProcessor|testImplementation)\s+['\"][^'\"]*\+", all_gradle):
    err("Dynamic dependency version detected.")

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
    "revertToPreviousManifest",
    "STATUS_BASEMAP_TEST",
    "hasRenderableActivePack",
    "hasLandClaimsAvailable",
    "No claim feature was rendered",
    "Treat this as unknown, not as public land",
):
    if required_source not in java_text:
        err(f"Offline/safety implementation is missing: {required_source}")

# Stable data contract.
contract = read("docs/DATA_CONTRACT.md")
for required in (
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
    "offline labels/glyphs",
):
    if required not in contract:
        err(f"Data contract missing required term: {required}")

# Public signing fingerprint is required; private signing material is forbidden in the repo.
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
file_count = 0
for path in ROOT.rglob("*"):
    if not path.is_file() or ".git" in path.parts:
        continue
    file_count += 1
    rel = path.relative_to(ROOT)
    if path.suffix.lower() in {".jks", ".keystore", ".p12", ".pfx"}:
        err(f"Private signing material must never be tracked: {rel}")
        continue
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

print(f"RockMap source preflight passed ({file_count} files checked).")
