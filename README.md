# RockMap

RockMap is an Android-first, offline-first field map project built through GitHub Actions. Android Studio is not required for the normal workflow.

## Current source build: 0.1.0-alpha4

Alpha 4 is a deliberately isolated **land-status test**. It keeps the exact Colorado Protomaps/OpenStreetMap PMTiles basemap already installed for Alpha 2 and the Alpha 3.1 offline-label renderer that passed the phone/airplane-mode test. It adds a separate offline PMTiles overlay generated from the official BLM Colorado Surface Management Agency (SMA) polygon service.

Core functionality included:

- MapLibre Native renderer with local PMTiles support.
- Existing Alpha 2 Colorado basemap reused byte-for-byte; Alpha 4 does not rebuild or redownload it when the phone already has the verified file.
- Existing Alpha 3.1 offline city/town, road, water and terrain labels retained with the local Noto Sans/font-glyph paths.
- New Alpha 4 offline land-status overlay with normalized `manager_code` and `manager_name` fields only.
- BLM, US Forest Service, state, private and other SMA categories are rendered distinctly; private land is intentionally visible rather than left visually blank.
- Land-status layer can be toggled independently. Mining claims remain unavailable in Alpha 4.
- Tapping the map can report the rendered surface manager while explicitly treating a missing feature as unknown rather than public land.
- Land diagnostics report source features, normalized manager fields and rendered features for the current viewport.
- Foreground GPS/current-position display and Room-backed saved locations remain unchanged.
- HTTPS-only manifest/data update plumbing with exact SHA-256 and byte-count verification, atomic activation and rollback.
- Blank local safety style remains the fail-closed fallback.

## Critical safety state

**0.1.0-alpha4 remains a red `NOT VERIFIED FOR NAVIGATION` test build.** Alpha 4 must not turn the safety banner green.

The BLM Colorado SMA source is surface-management/status mapping assembled from multiple source materials and updated over time. RockMap treats it as a useful management overlay, not as a parcel survey, surveyed property boundary, title record, or determination that collecting is legal. Mining-claim data is not present in this phase.

A point displaying BLM or other public management does not by itself establish that rockhounding or access is lawful. A blank land-status result is reported as unknown, not as public land.

## Build/data setup

The normal signed APK workflow stays unchanged. Alpha 4 adds a separate manual GitHub Actions workflow that builds and publishes only the Colorado land-status data component. Do not rerun or replace the Alpha 2 basemap release.

The Alpha 4 manifest copies the exact immutable `style` and `base` entries from the Alpha 2 baseline manifest and adds one required `land` file. The Android updater therefore skips the already-valid local basemap/style files and downloads only the new land PMTiles file when those baseline bytes are already present.

See `docs/LAND_STATUS_ALPHA4.md` for the exact build/install/device test and `docs/DATA_CONTRACT.md` for the stable offline data contract.
