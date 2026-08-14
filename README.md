# RockMap

RockMap is an Android-first, offline-first field map project built through GitHub Actions. Android Studio is not required for the normal workflow.

## Current source build: 0.1.0-alpha2

Alpha 2 keeps the known-good Android build/signing workflow from Alpha 1 and adds support for a real Colorado **basemap test pack** distributed as immutable GitHub Release assets.

Core functionality included:

- MapLibre Native renderer with local PMTiles support.
- Blank local safety style when no usable offline data exists or an installed style fails its safety checks.
- Alpha 2 `basemap_test` state: renders a real local Colorado basemap while keeping the safety banner red.
- Land-status and mining-claim controls explicitly unavailable during the basemap-only test.
- Foreground GPS/current-position display.
- Room-backed saved locations with name, notes, timestamps, coordinates, reported GPS accuracy, and GeoJSON import/export.
- Stable future land-status and mining-claims source/layer contracts.
- HTTPS-only manifest/data update plumbing with declared byte counts and SHA-256 verification.
- Atomic activation plus previous-snapshot rollback plumbing.
- Downloaded maps excluded from Android backup while waypoint data remains backup-eligible.
- No background-location or broad-storage permission.

## Critical safety state

**0.1.0-alpha2 is still not a field-verified navigation build.** Its test pack contains a Protomaps/OpenStreetMap basemap only. It intentionally does not include offline labels, BLM land-status data, or mining-claim data.

While the test pack is active, RockMap must continue to report `NOT VERIFIED FOR NAVIGATION` in red. The test exists to validate PMTiles rendering, Colorado coverage, roads/paths/water, GPS/waypoint alignment, and airplane-mode behavior before additional layers are added.

RockMap never infers that collecting is legal merely because a point appears on public land or because no claim is rendered.

## Build setup

The existing successful APK workflow should remain unchanged. Alpha 2 source files are committed normally. A second, manual-only GitHub workflow is supplied separately to extract the pinned Colorado Protomaps test pack and publish it as a prerelease.

See `docs/BASEMAP_ALPHA2.md` for the device test and `docs/DATA_CONTRACT.md` for the stable map-data contract.
