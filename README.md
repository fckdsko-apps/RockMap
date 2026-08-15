# RockMap

RockMap is an Android-first, offline-first field map project built through GitHub Actions. Android Studio is not required for the normal workflow.

## Current source build: 0.1.0-alpha5

Alpha 5 is a deliberately isolated **mining-claims test** on top of the working Alpha 4.1 checkpoint. It keeps the exact Colorado Protomaps/OpenStreetMap basemap, Alpha 3.1 offline labels, and Alpha 4 BLM Colorado Surface Management Agency land-status PMTiles, then adds a separate offline BLM MLRS Mining Claims — Not Closed PMTiles overlay.

Core functionality included:

- MapLibre Native renderer with local PMTiles support.
- Existing Alpha 2 Colorado basemap reused byte-for-byte; Alpha 5 does not rebuild or redownload it when the phone already has the verified file.
- Existing Alpha 3.1 offline city/town, road, water and terrain labels retained with the local Noto Sans/font-glyph paths.
- Existing Alpha 4 offline land-status overlay retained with normalized `manager_code` and `manager_name` fields only.
- New Alpha 5 BLM MLRS not-closed mining-claims overlay with normalized claim identity, type, disposition, acreage, and mapping-quality fields.
- BLM, US Forest Service, state, private and other SMA categories are rendered distinctly; private land is intentionally visible rather than left visually blank.
- Land-status and mining-claims layers can be toggled independently. Claims use a single magenta overlay so they are not confused with the land-status palette.
- Tapping the map can report the rendered surface manager and overlapping claim cases while explicitly treating missing features as unknown rather than proof of public/unclaimed land.
- Land and claim diagnostics report source features, normalized fields and rendered features for the current viewport.
- Foreground GPS/current-position display and Room-backed saved locations remain unchanged.
- HTTPS-only manifest/data update plumbing with exact SHA-256 and byte-count verification, atomic activation and rollback.
- Blank local safety style remains the fail-closed fallback.

## Critical safety state

**0.1.0-alpha5 remains a red `NOT VERIFIED FOR NAVIGATION` test build.** Alpha 5 must not turn the safety banner green.

The BLM Colorado SMA source remains surface-management/status mapping, not a parcel survey or legal boundary. The mining-claims overlay uses BLM MLRS records whose disposition is not closed; it is not a surveyed claim-boundary product. BLM states that some cases may lack geospatial representation when they cannot be geocoded. County-only quality-score 25 geometry is excluded from the footprint overlay.

A point displaying BLM or other public management does not by itself establish that rockhounding or access is lawful. A blank land-status result is reported as unknown, not as public land.

## Build/data setup

The normal signed APK workflow stays unchanged. Alpha 5 adds a separate GitHub Actions workflow that builds and publishes only the Colorado mining-claims component. Do not rerun or replace the Alpha 2 basemap or Alpha 4 land-status release.

The Alpha 5 manifest copies the exact immutable `style`, `base`, and `land` entries from the Alpha 4 manifest and adds one required `claims` file. The Android updater therefore skips the already-valid local basemap/style/land files and downloads only claims when those bytes are already present.

See `docs/MINING_CLAIMS_ALPHA5.md` for the Alpha 5 build/install/device test, `docs/LAND_STATUS_ALPHA4.md` for the land-status checkpoint, and `docs/DATA_CONTRACT.md` for the stable offline data contract.


## Alpha 5 mining claims

See `docs/MINING_CLAIMS_ALPHA5.md` for the source, quality filtering, incremental-update contract, and device acceptance test. The claims layer is BLM MLRS **not closed** data, not a surveyed claim-boundary or legal-access determination.
