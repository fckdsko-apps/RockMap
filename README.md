# RockMap

RockMap is an Android-first, offline-first Colorado field-reference and research map built through GitHub Actions. Android Studio is not required for the normal build workflow.

## Current source build: 0.1.0-alpha6.6

Current RockMap functionality includes:

- offline MapLibre / PMTiles basemap rendering;
- foreground GPS/current-position display and precise GPS waypoint saving;
- Room-backed saved markers;
- offline Find for supported Colorado place/terrain/water names plus direct coordinate entry;
- BLM Colorado Surface Management Agency land-status context;
- BLM MLRS Mining Claims — Not Closed context;
- offline mineral and historic-mine evidence search using reviewed public USGS/CGS/USFS source families;
- area mineral-evidence summaries;
- Trips / Collections with ordered stops and CSV, RockMap XML, GPX, and GeoJSON export;
- SHA-256/size-verified offline-data updates with rollback protection;
- user-visible download-size confirmation before additional offline resources are downloaded;
- mandatory Safety & Data Limitations acknowledgment with a device-local acknowledgment token;
- in-app privacy policy and Google Play app-side release hardening.

## Safety and data limitations

**RockMap is an informational reference and planning tool, not a surveying, legal-boundary, mineral-deposit validation, precision plotting, or safety-critical navigation product.**

RockMap uses public or publicly distributed source information. RockMap does not independently verify the truth, age, completeness, legal status, field condition, or coordinate precision of those records. Source data can be incomplete, stale, generalized, approximate, mislocated, or wrong.

Do not use RockMap to establish property or claim boundaries, stake or file claims, determine legal access/collecting permission, prove a deposit or feature exists at a displayed point, or navigate where a mapping error could create a safety risk. Verify important information with current responsible agencies, landowners, official records, surveyed coordinates where precision matters, posted signs/closures, and direct field observation.

The in-app **Safety & Data Limitations** notice lists the current source families and must be acknowledged before the app can be used. A user may choose not to see the current disclosure version on later launches; a material disclosure update can force acknowledgment again.

## Major public source families

Depending on the installed data pack and feature, RockMap uses or derives information from:

- OpenStreetMap contributors via Protomaps-derived basemap data;
- USGS National Map / geographic-name and Gazetteer data;
- Colorado Department of Transportation state-highway data;
- BLM Colorado Surface Management Agency data;
- BLM Mineral & Land Records System (MLRS), including Mining Claims — Not Closed records;
- USGS MRDS and MAS/MILS;
- Colorado Geological Survey radioactive-mineral occurrence, nonmetallic/industrial-mineral mine, abandoned-mine, historic mining-district, and reviewed locality sources;
- U.S. Forest Service abandoned-mine inventory information distributed/reviewed through CGS source packages;
- reviewed official CGS/USGS gemstone and mineral-locality references included in RockMap's compact offline evidence sets.

See `docs/ALPHA6_2_1_MINERAL_EVIDENCE.md`, `docs/ALPHA6_6_OFFLINE_PLACE_SEARCH.md`, `docs/MINING_CLAIMS_ALPHA5.md`, `docs/LAND_STATUS_ALPHA4.md`, and `docs/DATA_CONTRACT.md` for detailed source/data contracts.

## Privacy

RockMap is designed to keep GPS positions, saved markers, trips, notes, and the safety acknowledgment token local to the device unless the user explicitly exports a file. RockMap does not request background location and the current release does not include advertising or analytics SDKs.

See [`PRIVACY.md`](PRIVACY.md). Before a production Google Play launch, the same privacy policy must also remain available at a stable public URL even if the source repository becomes private.

## Builds

`.github/workflows/build-apk.yml` remains unchanged in this app-side Play-readiness commit and continues to build the signed APK used for device testing. The Android project itself supports a release app bundle through Gradle `bundleRelease`; the GitHub Actions AAB publishing step can be added separately when preparing the first Play Console upload.

The permanent RockMap signing material remains in GitHub repository secrets; private signing files are not tracked in source.
