# RockMap

RockMap is an Android-first, offline-first field map project built through GitHub Actions. Android Studio is not required for the normal workflow.

## Current source build: 0.1.0-alpha1

This source package contains the Android application only. It deliberately contains **no `.github` directory and no workflow file** so the project can be uploaded to a brand-new repository before any Action is capable of running.

Core alpha functionality included:

- MapLibre Native renderer and local PMTiles style contract.
- Blank local safety style whenever verified map data is unavailable or fails to render.
- Foreground GPS/current-position display.
- Precise-location requirement for saving field waypoints.
- Room-backed saved locations with name, notes, timestamps, coordinates, and reported GPS accuracy.
- Saved-location edit/delete/display and GeoJSON export/import.
- Stable land-status and mining-claims source/layer contracts.
- Overlapping-claim tap handling.
- HTTPS-only manifest/data update plumbing with byte-count and SHA-256 verification.
- Atomic activation plus previous-snapshot rollback plumbing.
- Downloaded maps excluded from Android backup while waypoint data remains backup-eligible.
- No background-location or broad-storage permission.

## Critical safety state

**0.1.0-alpha1 does not contain a field-verified Colorado basemap, surface-management pack, or mining-claims pack.** `data/manifest.json` is intentionally `not_published`.

Until the app itself reports `OFFLINE MAP: VERIFIED`, it must not be used for navigation or to decide whether land is public, unclaimed, or legal to collect on. The app intentionally displays a blank local map and a warning rather than silently substituting an online or incomplete map.

The real Colorado data pack will be published only after device/airplane-mode validation of geographic alignment, offline labels, land and claim geometry, overlap handling, archive integrity, and update/rollback behavior.

## Build setup

The workflow is created manually in GitHub **after this entire source package has already been committed**. This ordering prevents Actions from ever running against a half-uploaded repository.

The build workflow is supplied separately as `RockMap_WORKFLOW_COPY_PASTE.yml` and is not part of this source upload package.

## Stable map-data contract

See `docs/DATA_CONTRACT.md`. Android consumes only RockMap's normalized fields and stable source/layer IDs; upstream BLM/Protomaps processing details belong in the later data-build pipeline.
