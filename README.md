# RockMap

RockMap is an Android-first, offline-first field map project built through GitHub Actions. Android Studio is not required for the normal workflow.

## Current source build: 0.1.0-alpha3

Alpha 3 keeps the known-good Android build/signing workflow and the exact Colorado PMTiles pack that passed Alpha 2 device testing. It adds **offline labels from APK-bundled style/glyph resources** without requiring another statewide basemap download.

Core functionality included:

- MapLibre Native renderer with local PMTiles support.
- Blank local safety style when no usable offline data exists or an installed style fails its safety checks.
- Alpha 3 `basemap_test` state: renders the tested Colorado basemap plus local labels while keeping the safety banner red.
- Offline labels for cities/towns, neighborhoods, roads/paths, waterways/lakes, state/region names, and peaks where present in the Protomaps source data.
- APK-bundled SDF glyph PBFs prepared during the GitHub build from immutable, pinned Protomaps font-resource blobs; the source patch contains no font binary and the runtime label style has no HTTP/HTTPS font dependency.
- Land-status and mining-claim controls explicitly unavailable during the basemap/label test.
- Foreground GPS/current-position display.
- Room-backed saved locations with name, notes, timestamps, coordinates, reported GPS accuracy, and GeoJSON import/export.
- Stable future land-status and mining-claims source/layer contracts.
- HTTPS-only manifest/data update plumbing with declared byte counts and SHA-256 verification.
- Atomic activation plus previous-snapshot rollback plumbing.
- Downloaded maps excluded from Android backup while waypoint data remains backup-eligible.
- No background-location or broad-storage permission.

## Critical safety state

**0.1.0-alpha3 is still not a field-verified navigation build.** Alpha 2 established that the Colorado PMTiles base renders statewide, aligns with GPS, and works after a cold reopen in airplane mode. Alpha 3 adds labels, but it still does not include BLM land-status data or mining-claim data.

While the test pack is active, RockMap must continue to report `NOT VERIFIED FOR NAVIGATION` in red. Alpha 3 exists to prove that useful labels and their glyphs are also fully local and reliable before land/claim overlays are added.

RockMap never infers that collecting is legal merely because a point appears on public land or because no claim is rendered.

## Build setup

The existing successful APK workflow remains unchanged. Alpha 3 is a normal source-only update. **Do not rebuild or rerun the Colorado basemap workflow for Alpha 3.** Install Alpha 3 over Alpha 2 so the already-downloaded PMTiles file and saved waypoints remain in app storage.

See `docs/BASEMAP_ALPHA3.md` for the device test and `docs/DATA_CONTRACT.md` for the stable map-data contract.
