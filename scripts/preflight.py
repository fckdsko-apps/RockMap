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
    "docs/LAND_STATUS_ALPHA4.md",
    "docs/MINING_CLAIMS_ALPHA5.md",
    "scripts/create_basemap_test_manifest.py",
    "scripts/create_land_test_manifest.py",
    "scripts/create_claims_test_manifest.py",
    "scripts/fetch_blm_land_status.py",
    "scripts/fetch_blm_mining_claims.py",
    "scripts/prepare_offline_glyphs.py",
    "scripts/validate_basemap_metadata.py",
    "scripts/validate_land_metadata.py",
    "scripts/validate_claims_metadata.py",
    "signing-certificate.sha256",
    "app/src/test/java/com/rockmap/app/offline/DataValidatorsTest.java",
    "app/src/main/java/com/rockmap/app/coordinates/CoordinateParser.java",
    "app/src/test/java/com/rockmap/app/coordinates/CoordinateParserTest.java",
    "app/src/main/java/com/rockmap/app/minerals/MineralRecord.java",
    "app/src/main/java/com/rockmap/app/minerals/MineralSearchEngine.java",
    "app/src/main/java/com/rockmap/app/minerals/MineralIndexRepository.java",
    "app/src/main/java/com/rockmap/app/minerals/MineralOverlayController.java",
    "app/src/test/java/com/rockmap/app/minerals/MineralSearchEngineTest.java",
    "scripts/fetch_usgs_mrds_minerals.py",
    "scripts/create_mineral_test_manifest.py",
    "docs/ALPHA6_1_MINERAL_FINDER.md",
    "data/official-mineral-localities-alpha6-2.json",
    "scripts/build_official_mineral_localities.py",
    "scripts/create_mineral_coverage_test_manifest.py",
    "docs/ALPHA6_2_MINERAL_COVERAGE.md",
]
for rel in required_files:
    if not (ROOT / rel).is_file():
        err(f"Required project file missing: {rel}")


# Label-bearing Protomaps layers are part of the basemap contract from Alpha 3.1 onward.
validator_text = read("scripts/validate_basemap_metadata.py")
for layer_name in ("places", "pois", "roads", "water"):
    if f'"{layer_name}"' not in validator_text:
        err(f"Basemap metadata validator must require label-bearing layer: {layer_name}")

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

# Alpha 3.1 runtime label style must be wholly APK/local other than the local PMTiles placeholder.
try:
    label_style_text = read("app/src/main/assets/rockmap_basemap_label_style_alpha3.json")
    label_style = json.loads(label_style_text)
    if label_style.get("version") != 8:
        err("Alpha 3.1 label style must use MapLibre style version 8.")
    label_sources = label_style.get("sources", {})
    label_base = label_sources.get("rockmap-base", {})
    if label_base.get("type") != "vector" or label_base.get("url") != "${ROCKMAP_BASE_URI}":
        err("Alpha 3.1 label style must use the verified local rockmap-base PMTiles placeholder.")
    if label_style.get("glyphs") != "asset://rockmap-glyphs/{fontstack}/{range}.pbf":
        err("Alpha 3.1 glyph URL must point only to APK-bundled glyph PBFs.")
    font_faces = label_style.get("font-faces", {})
    expected_face = [{
        "url": "asset://rockmap-fonts/NotoSans-Regular.ttf",
        "unicode-range": ["U+0000-024F", "U+1E00-1EFF", "U+2000-206F"],
    }]
    if font_faces.get("RockMapSans") != expected_face:
        err("Alpha 3.1 must provide the pinned local RockMapSans font-faces fallback.")
    if "sprite" in label_style:
        err("Alpha 3.1 label style must not depend on a sprite URL.")
    if "${ROCKMAP_LAND_URI}" in label_style_text or "${ROCKMAP_CLAIMS_URI}" in label_style_text:
        err("Alpha 3.1 label style must not pretend land/claims data exist.")
    lower = label_style_text.lower()
    if "http://" in lower or "https://" in lower:
        err("Alpha 3.1 runtime label style must have no HTTP/HTTPS dependency.")
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
        "rockmap-label-place-any",
        "rockmap-label-water-any",
        "rockmap-label-road-any",
    ):
        if layer_id not in layer_ids:
            err(f"Alpha 3.1 label style missing required label layer: {layer_id}")
    expected_sources = {
        "rockmap-label-region": "places",
        "rockmap-label-locality": "places",
        "rockmap-label-neighbourhood": "places",
        "rockmap-label-water": "water",
        "rockmap-label-water-area": "water",
        "rockmap-label-peak": "pois",
        "rockmap-label-road-major": "roads",
        "rockmap-label-road-minor": "roads",
        "rockmap-label-place-any": "places",
        "rockmap-label-water-any": "water",
        "rockmap-label-road-any": "roads",
    }
    for layer in label_layers:
        lid = layer.get("id")
        if lid in expected_sources and layer.get("source-layer") != expected_sources[lid]:
            err(f"Alpha 3 label layer {lid} uses unexpected source-layer {layer.get('source-layer')!r}.")
        if layer.get("type") == "symbol" and lid and lid.startswith("rockmap-label-"):
            if layer.get("layout", {}).get("text-font") != ["RockMapSans"]:
                err(f"Alpha 3 label layer {lid} must use only the bundled RockMapSans glyph stack.")
except Exception as exc:
    err(f"Alpha 3.1 label style invalid: {exc}")

# Alpha 3.1 does not track font/glyph binaries in source. The Android build fetches a
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
        err("Alpha 3.1 glyph upstream commit unexpectedly changed.")
    if glyph_module.get("GLYPH_BLOBS") != expected_blobs:
        err("Alpha 3.1 glyph blob pins unexpectedly changed.")
    if glyph_module.get("OUTPUT_FONTSTACK") != "RockMapSans":
        err("Alpha 3.1 glyph generator must output the RockMapSans alias.")
    if glyph_module.get("NOTO_COMMIT") != "445abfe2d405cb658a9d825ab056e2004fb60627":
        err("Alpha 3.1 Noto font commit unexpectedly changed.")
    if glyph_module.get("NOTO_TTF_BLOB") != "f27f4ff59562d58480f1cb94194393484b8da9e9":
        err("Alpha 3.1 Noto font Git blob unexpectedly changed.")
    if glyph_module.get("NOTO_TTF_OUTPUT") != "rockmap-fonts/NotoSans-Regular.ttf":
        err("Alpha 3.1 Noto font output path unexpectedly changed.")
    for glyph_range in expected_blobs:
        url = glyph_module["upstream_url"](glyph_range)
        expected_prefix = f"https://raw.githubusercontent.com/protomaps/basemaps-assets/{expected_commit}/fonts/"
        if not url.startswith(expected_prefix) or not url.endswith(f"/{glyph_range}.pbf"):
            err(f"Alpha 3.1 glyph URL is not pinned to the immutable upstream commit: {glyph_range}")

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
        err("Alpha 3.1 glyph protobuf rewrite self-test failed.")
except Exception as exc:
    err(f"Alpha 3.1 glyph preparation script validation failed: {exc}")

try:
    ofl = read("app/src/main/assets/rockmap-glyphs/OFL.txt")
    notice = read("app/src/main/assets/rockmap-glyphs/NOTICE.txt")
    if "SIL OPEN FONT LICENSE Version 1.1" not in ofl:
        err("Offline glyph resource license text is missing SIL OFL 1.1.")
    if ("RockMapSans" not in notice or "source patch itself does not" not in notice
            or "TTF" not in notice or "glyph PBF" not in notice):
        err("Offline text NOTICE must describe both generated local font paths and absence of source binaries.")
except Exception as exc:
    err(f"Glyph license/notice validation failed: {exc}")

# Alpha 4 land builder: exact official BLM source, fail-closed manager normalization,
# and a manifest that copies the immutable Alpha 2 style/base entries before adding land.
try:
    fetch_module = runpy.run_path(str(ROOT / "scripts/fetch_blm_land_status.py"))
    expected_blm = (
        "https://gis.blm.gov/coarcgis/rest/services/lands/"
        "BLM_Colorado_Surface_Management_Agency/FeatureServer/1"
    )
    if fetch_module.get("DEFAULT_URL") != expected_blm:
        err("Alpha 4 BLM source URL unexpectedly changed.")
    expected_codes = {
        "BLM", "BOR", "BIA", "DOD", "USFS_NG", "NPS", "OTHER", "PRI",
        "STA", "LOCAL", "BLM_LU", "USFS_LU", "USFW", "USFS",
    }
    if set(fetch_module.get("MANAGER_NAMES", {})) != expected_codes:
        err("Alpha 4 BLM manager-code whitelist unexpectedly changed.")
    if fetch_module.get("REQUIRED_CODES") != {"BLM", "PRI", "STA", "USFS"}:
        err("Alpha 4 land category sanity gate unexpectedly changed.")
    if not (1 <= int(fetch_module.get("BATCH_SIZE", 0)) <= 500):
        err("Alpha 4 BLM query batch size is outside the conservative range.")
except Exception as exc:
    err(f"Alpha 4 BLM fetch script validation failed: {exc}")

land_fetch_text = read("scripts/fetch_blm_land_status.py")
for required_land_fetch in (
    "returnIdsOnly",
    "objectIds",
    '"outSR": "4326"',
    '"manager_code"',
    '"manager_name"',
    "feature completeness check failed",
    "schema drift",
):
    if required_land_fetch not in land_fetch_text:
        err(f"Alpha 4 BLM builder is missing fail-closed behavior: {required_land_fetch}")

land_manifest_text = read("scripts/create_land_test_manifest.py")
for required_manifest_text in (
    'baseline.get("status") != "basemap_test"',
    'set(baseline_files) != {"style", "base"}',
    '"id": "land"',
    '"status": "basemap_test"',
    'files = [baseline_files["style"], baseline_files["base"]]',
):
    if required_manifest_text not in land_manifest_text:
        err(f"Alpha 4 manifest builder is missing immutable-baseline behavior: {required_manifest_text}")

land_validator_text = read("scripts/validate_land_metadata.py")
for required_validator_text in ("manager_code", "manager_name", 'x.get("id") == "land"', "unexpected raw fields"):
    if required_validator_text not in land_validator_text:
        err(f"Alpha 4 land metadata validator is missing: {required_validator_text}")

# Alpha 5 claims builder: official MLRS not-closed source, conservative Colorado spatial
# selection, exact OBJECTID completeness, type-code whitelist, and coarse-quality filtering.
try:
    claim_module = runpy.run_path(str(ROOT / "scripts/fetch_blm_mining_claims.py"))
    expected_claims_url = (
        "https://gis.blm.gov/nlsdb/rest/services/HUB/"
        "BLM_Natl_MLRS_Mining_Claims_Not_Closed/FeatureServer/0"
    )
    if claim_module.get("DEFAULT_URL") != expected_claims_url:
        err("Alpha 5 BLM MLRS claims source URL unexpectedly changed.")
    expected_type_codes = {
        "384101", "384103", "384201", "384203",
        "384301", "384303", "384401", "384403",
    }
    if set(claim_module.get("TYPE_NAMES", {})) != expected_type_codes:
        err("Alpha 5 mining-claim type-code whitelist unexpectedly changed.")
    if claim_module.get("NO_FOOTPRINT_QUALITY_SCORES") != {"11", "12", "20", "21", "22", "25"}:
        err("Alpha 5 coarse/no-footprint quality exclusion set unexpectedly changed.")
    if not (1 <= int(claim_module.get("BATCH_SIZE", 0)) <= 500):
        err("Alpha 5 BLM claim query batch size is outside the conservative range.")
except Exception as exc:
    err(f"Alpha 5 BLM claims fetch script validation failed: {exc}")

claims_fetch_text = read("scripts/fetch_blm_mining_claims.py")
for required_claim_fetch in (
    "Mining Claims- Not Closed",
    "returnIdsOnly",
    "esriGeometryEnvelope",
    "esriSpatialRelIntersects",
    "objectIds",
    '"outSR": "4326"',
    '"serial"',
    '"quality_description"',
    "claim completeness check failed",
    "Mapped to county",
    "schema drift",
):
    if required_claim_fetch not in claims_fetch_text:
        err(f"Alpha 5 claims builder is missing fail-closed behavior: {required_claim_fetch}")

claims_manifest_text = read("scripts/create_claims_test_manifest.py")
for required_claim_manifest in (
    'baseline.get("status") != "basemap_test"',
    'set(baseline_files) != {"style", "base", "land"}',
    'files = [baseline_files["style"], baseline_files["base"], baseline_files["land"]]',
    '"id": "claims"',
    '"status": "basemap_test"',
):
    if required_claim_manifest not in claims_manifest_text:
        err(f"Alpha 5 claims manifest builder is missing immutable Alpha 4 baseline behavior: {required_claim_manifest}")

claims_validator_text = read("scripts/validate_claims_metadata.py")
for required_claim_validator in (
    "legacy_serial", "type_code", "quality_description", 'x.get("id") == "claims"', "unexpected raw fields"
):
    if required_claim_validator not in claims_validator_text:
        err(f"Alpha 5 claims metadata validator is missing: {required_claim_validator}")

manifest_parser_text = read("app/src/main/java/com/rockmap/app/offline/DataManifestParser.java")
for required_parser_guard in (
    'if (hasId(files, "mineral_localities"))',
    'else if (hasId(files, "minerals"))',
    'require(files, "land", "pmtiles", status);',
    'require(files, "claims", "pmtiles", status);',
    'require(files, "minerals", "index", status);',
    'require(files, "mineral_localities", "index", status);',
):
    if required_parser_guard not in manifest_parser_text:
        err(f"Alpha 6.2 manifest parser is missing cumulative dependency guard: {required_parser_guard}")

# The repository placeholder manifest remains unpublished. Alpha 5 points the APK updater
# at a separate land-test release whose manifest reuses the immutable Alpha 2 base/style.
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
if "rockmap-minerals-alpha6-2-20260815-test1" not in all_gradle or "/releases/download/" not in all_gradle:
    err("Alpha 6.2 APK must point only to the immutable Alpha 6.2 cumulative mineral-coverage release manifest.")
if "ROCKMAP_VERSION_NAME=0.1.0-alpha6.2" not in read("gradle.properties"):
    err("Alpha 6.2 version name is not pinned in gradle.properties.")
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
        err(f"Alpha 5 must retain the proven Alpha 3.1 generated offline text wiring: {required_gradle}")

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

if java_text.count("setId(View.generateViewId())") < 2 or "scope.check(allColorado.getId())" not in java_text:
    err("Alpha 6.1.2 mineral search area choices must be one mutually-exclusive RadioGroup selection.")
if "listener.onMapOverlayTapped(coordinate, overlayLand)" not in java_text or "listener.onMineralTapped(hit)" not in java_text:
    err("Alpha 6.2 mineral map taps must route rich mineral details with land context before generic location info.")

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
    "describeLabelDiagnostics",
    "LABEL_PLACE_ANY",
    "LABEL_ROAD_ANY",
    "revertToPreviousManifest",
    "STATUS_BASEMAP_TEST",
    "hasRenderableActivePack",
    "hasLandStatusTestPack",
    "hasClaimsTestPack",
    "hasLandStatusAvailable",
    "hasClaimsAvailable",
    "describeLandDiagnostics",
    "describeClaimsDiagnostics",
    "rockmap-land-fill",
    "rockmap-land-outline",
    "rockmap-claim-fill",
    "rockmap-claim-outline",
    "Mining claims legend",
    "MiningClaimCatalog",
    "BLM MLRS NOT CLOSED",
    "This is not proof that no mining claim exists",
    "Treat this as unknown, not as public land",
    "Land status legend",
    "LandStatusCatalog",
    "Tap a colored polygon for its category and source manager/name",
    "TEST DATA — NOT VERIFIED FOR NAVIGATION",
    "compactClaimQuality",
    "Mapping: ",
    "CoordinateParser",
    "addControl(controls, \"GPS\"",
    "addControl(controls, \"Save GPS\"",
    "addControl(controls, \"Coords\"",
    "addControl(controls, \"Minerals\"",
    "addControl(controls, \"Markers\"",
    "Save coordinate marker",
    "MANUAL_COORDINATE_ACCURACY",
    "Source: manually entered coordinates",
    "MineralIndexRepository",
    "MineralOverlayController",
    "rockmap-mineral-search-layer",
    "Source: saved mineral-search point",
    "withCluster(true)",
    "Current map area",
    "Mineral results — ",
    "Clear minerals",
    "MINERAL_LIST_PAGE",
    "getVisibleBounds",
    "CLUSTER_LAYER_ID",
    "View.generateViewId()",
    "onMapOverlayTapped",
    "onMineralTapped",
    "Searched for: ",
    "All recorded minerals/materials: ",
    "Source: USGS Mineral Resources Data System (MRDS)",
    "Reliability: Documented mineral and geologic records; location precision and historical mine information may vary.",
    "Source: Colorado Geological Survey (CGS)",
    "Source: U.S. Geological Survey publication",
    "Land: Unknown — no mapped management feature at this point.",
    "LAND_QUERY_LAYER",
    "rockmap-land-hit-test",
    "mineral_localities",
    "hasOfficialLocalitySupplement",
):
    if required_source not in java_text:
        err(f"Offline/safety implementation is missing: {required_source}")

# Alpha 6.1 adds one compact gzip JSON mineral index to the signed data contract.
validators_text = read("app/src/main/java/com/rockmap/app/offline/DataValidators.java")
if '"index".equals(kind)' not in validators_text or '.json.gz' not in validators_text:
    err("Alpha 6.1 DataValidators must accept only the compact JSON/JSON.GZ index kind in addition to existing styles/PMTiles.")

mineral_doc = read("docs/ALPHA6_1_MINERAL_FINDER.md")
for required in (
    "15 MB",
    "U.S. Geological Survey",
    "MRDS",
    "GPS | Save GPS | Coords | Minerals | Layers | Markers | Data",
    "does not establish current ownership",
):
    if required not in mineral_doc:
        err(f"Alpha 6.1 mineral-finder documentation missing required safety/size instruction: {required}")

fetch_minerals = read("scripts/fetch_usgs_mrds_minerals.py")
for required in (
    "Mineral Resources Data System",
    "materials",
    "commodities",
    "districts",
    "models",
    "rocks",
    "production",
    "ownership",
):
    if required not in fetch_minerals:
        err(f"Alpha 6.1 compact MRDS builder missing expected contract text: {required}")

# Alpha 6.2 keeps the MRDS file immutable and adds only a tiny reviewed official-source
# locality index for high-value gemstone gaps. The repository source is intentionally small
# enough to audit by eye and fail closed on any source/domain/coordinate drift.
try:
    locality_source = json.loads(read("data/official-mineral-localities-alpha6-2.json"))
    locality_records = locality_source.get("records", [])
    expected_locality_ids = {
        "cgs-mt-antero-aquamarine",
        "cgs-mt-white-aquamarine",
        "usgs-crystal-peak-amazonite",
    }
    ids = {str(item.get("id", "")) for item in locality_records if isinstance(item, dict)}
    if locality_source.get("schema") != 1 or ids != expected_locality_ids or len(locality_records) != 3:
        err(f"Alpha 6.2 reviewed locality set changed unexpectedly: {sorted(ids)}")
    allowed_hosts = {"coloradogeologicalsurvey.org", "www.usgs.gov"}
    from urllib.parse import urlparse
    for item in locality_records:
        if not isinstance(item, dict):
            err("Alpha 6.2 locality entry is not an object.")
            continue
        try:
            lat, lon = float(item["lat"]), float(item["lon"])
        except Exception:
            err(f"Alpha 6.2 locality has invalid coordinates: {item.get('id')}")
            continue
        if not (36.9924 <= lat <= 41.0034 and -109.0603 <= lon <= -102.0415):
            err(f"Alpha 6.2 locality lies outside the Colorado envelope: {item.get('id')}")
        if not item.get("materials") or not item.get("source_code") or not item.get("evidence_type") or not item.get("location_precision"):
            err(f"Alpha 6.2 locality is missing search/provenance fields: {item.get('id')}")
        for key in ("source_url", "coordinate_source_url"):
            parsed = urlparse(str(item.get(key, "")))
            if parsed.scheme != "https" or parsed.hostname not in allowed_hosts:
                err(f"Alpha 6.2 locality uses an unapproved source URL: {item.get('id')} {key}")
except Exception as exc:
    err(f"Alpha 6.2 locality source is invalid: {exc}")

locality_builder_text = read("scripts/build_official_mineral_localities.py")
for required in ("EXPECTED_IDS", "ALLOWED_SOURCE_HOSTS", "precision_note", "gzip.GzipFile", "mtime=0"):
    if required not in locality_builder_text:
        err(f"Alpha 6.2 locality builder is missing fail-closed behavior: {required}")

coverage_manifest_text = read("scripts/create_mineral_coverage_test_manifest.py")
for required in (
    'expected = {"style", "base", "land", "claims", "minerals"}',
    '"id": "mineral_localities"',
    'by_id["minerals"]',
    '"status"] = "basemap_test"',
):
    if required not in coverage_manifest_text:
        err(f"Alpha 6.2 manifest builder is missing immutable Alpha 6.1 baseline behavior: {required}")

alpha62_doc = read("docs/ALPHA6_2_MINERAL_COVERAGE.md")
for required in (
    "Mount Antero Aquamarine Locality",
    "Mount White Aquamarine Locality",
    "Crystal Peak–Lake George Amazonite Locality",
    "mineral_localities",
    "Land: BLM",
    "not a parcel survey or legal boundary",
    "Do not uninstall",
):
    if required not in alpha62_doc:
        err(f"Alpha 6.2 mineral-coverage documentation missing required term: {required}")

if "labels are not included yet" in java_text:
    err("Alpha 5 source still reports the already-proven labels as absent.")
if "OFFLINE BASEMAP + LABELS + LAND STATUS + MINING CLAIMS: TEST" not in java_text:
    err("Alpha 5 status text does not expose the combined claims-test state.")
if "management/status mapping only; not a parcel survey or legal boundary" not in java_text:
    err("Alpha 5 must retain the Alpha 4 land-status safety wording.")
if "some MLRS cases may lack geospatial representation" not in java_text:
    err("Alpha 5 MLRS incompleteness warning is missing from OfflineDataManager.")

# Stable data contract.
contract = read("docs/DATA_CONTRACT.md")
for required in (
    "Alpha 4",
    "alpha5",
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
    "asset://rockmap-fonts/NotoSans-Regular.ttf",
    "rockmap-label-locality",
    "rockmap-label-road-major",
    "rockmap-label-water",
    "rockmap-label-peak",
    "rockmap-label-place-any",
    "rockmap-label-road-any",
    "manager_code",
    "manager_name",
    "legacy_serial",
    "type_code",
    "quality_description",
    "BLM Colorado Surface Management Agency",
    "not a parcel survey",
    "not closed",
):
    if required not in contract:
        err(f"Data contract missing required term: {required}")

alpha3_doc = read("docs/BASEMAP_ALPHA3.md") if (ROOT / "docs/BASEMAP_ALPHA3.md").is_file() else ""
for required in ("airplane mode", "Do not uninstall", "NOT VERIFIED FOR NAVIGATION", "land status", "mining claims", "font-faces"):
    if required not in alpha3_doc:
        err(f"Alpha 3 acceptance test missing required instruction: {required}")

alpha4_doc = read("docs/LAND_STATUS_ALPHA4.md") if (ROOT / "docs/LAND_STATUS_ALPHA4.md").is_file() else ""
for required in (
    "Do not uninstall",
    "Build Colorado Land Status Test Pack",
    "Check for update",
    "Mining claims",
    "airplane mode",
    "unknown",
    "not a parcel survey",
    "rockmap-land-alpha4-20260814-test1",
):
    if required not in alpha4_doc:
        err(f"Alpha 4 land-status acceptance test missing required instruction: {required}")

alpha5_doc = read("docs/MINING_CLAIMS_ALPHA5.md") if (ROOT / "docs/MINING_CLAIMS_ALPHA5.md").is_file() else ""
for required in (
    "Do not uninstall",
    "Build Colorado Mining Claims Test Pack",
    "Add Alpha 5 mining claims test",
    "Check for update",
    "airplane mode",
    "quality score **25**",
    "not proof that no claim exists",
    "rockmap-claims-alpha5-20260815-test1",
):
    if required not in alpha5_doc:
        err(f"Alpha 5 mining-claims acceptance test missing required instruction: {required}")

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

print(f"RockMap Alpha 6.2 mineral-coverage source preflight passed ({file_count} files checked).")
