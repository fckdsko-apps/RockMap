# RockMap offline data contract — schema 1

RockMap deliberately separates downloadable map data from user waypoints. The Android app never consumes BLM or Protomaps field names directly. The GitHub data-building workflow will normalize upstream GIS data to this stable contract.

## Field-safety status for alpha3

`0.1.0-alpha3.1` reuses the real Colorado **basemap test pack** that passed Alpha 2 device testing while deliberately keeping the map in the red/unverified state. The existing manifest remains `status: basemap_test` and continues to describe the downloadable `style` + `base` snapshot. For Alpha 3.1, the APK deliberately overrides the test-pack style at runtime with a bundled label style and bundled glyph PBFs while reusing the same SHA-256-verified local `base` file. Land status and mining claims remain unavailable and the UI must say so.

A `basemap_test` pack is for validating Colorado coverage, PMTiles rendering, roads, paths, water, labels, GPS/waypoint alignment, and airplane-mode behavior. It is **not field-safe navigation data** and must never turn the safety banner green.

Alpha 3's runtime basemap-test style and glyph URL are APK-local (`asset://`) and may not contain runtime HTTP/HTTPS dependencies. The downloaded Alpha 2 style remains part of the immutable data snapshot for compatibility/rollback but is not the active Alpha 3 basemap-test rendering style.

Do not publish a manifest with `status: published` until the complete Colorado style/data passes the airplane-mode/device tests, including land status, claims, and offline labels/glyphs. Offline glyph handling must be proven on the phone before the map is called field-safe.

### Basemap-test manifest

A `basemap_test` manifest uses the same schema and verification rules as a published snapshot but requires only `style` and `base`. The downloaded style template must contain `${ROCKMAP_BASE_URI}` and must not contain `${ROCKMAP_LAND_URI}` or `${ROCKMAP_CLAIMS_URI}`. Alpha 3 may replace that test rendering template with a versioned APK-bundled style, but the bundled style must use the same `${ROCKMAP_BASE_URI}` contract, use only local resources, and remain explicitly unverified. RockMap renders it with a persistent red warning and disables land/claim controls.

## Published manifest

A published `data/manifest.json` must include a complete snapshot:

```json
{
  "manifestVersion": 1,
  "status": "published",
  "pack": "Colorado",
  "version": "2026-08-14.1",
  "publishedAt": "2026-08-14T00:00:00Z",
  "styleSchemaVersion": 1,
  "minimumAppVersionCode": 1,
  "files": [
    {
      "id": "style",
      "kind": "style",
      "fileName": "rockmap-style-20260814-a1b2c3.json",
      "url": "https://...",
      "sha256": "64 hexadecimal characters",
      "bytes": 12345,
      "schemaVersion": 1,
      "required": true
    },
    {
      "id": "base",
      "kind": "pmtiles",
      "fileName": "colorado-base-20260814-a1b2c3.pmtiles",
      "url": "https://...",
      "sha256": "...",
      "bytes": 12345,
      "schemaVersion": 1,
      "required": true
    },
    {
      "id": "land",
      "kind": "pmtiles",
      "fileName": "colorado-land-20260814-a1b2c3.pmtiles",
      "url": "https://...",
      "sha256": "...",
      "bytes": 12345,
      "schemaVersion": 1,
      "required": true
    },
    {
      "id": "claims",
      "kind": "pmtiles",
      "fileName": "colorado-claims-20260814-a1b2c3.pmtiles",
      "url": "https://...",
      "sha256": "...",
      "bytes": 12345,
      "schemaVersion": 1,
      "required": true
    }
  ]
}
```

The manifest is a snapshot. If only claims change, the next manifest may keep the exact same base/land/style filenames and hashes. The phone downloads only changed files, but it does not activate the new manifest until every required component in that snapshot is present and SHA-256 verified.

### Large-pack transport gate

The alpha includes a conservative WorkManager-based verified downloader because there is intentionally no published Colorado pack yet. Before any multi-hundred-megabyte or multi-gigabyte statewide base pack is marked `published`, its transfer path must be tested on the target Android version and device under interruption, backgrounding, low-storage, and restart conditions. Android 16 applies job-execution quota considerations to long-running WorkManager jobs; if the measured Colorado base transfer is large enough to make that relevant, RockMap will use Android's user-initiated large-transfer mechanism for that component rather than pretending the existing worker is sufficient. Small manifest/claims updates can remain on the simpler verified path when device testing supports it.

### Immutable filenames

A filename that is referenced by the active or rollback snapshot may not be reused for different bytes. New content gets a new filename. This is enforced on-device so a failed update cannot overwrite a file still needed by the known-good map.

## Style template

The downloaded style file is a **template**, not a device-specific style. A `basemap_test` style requires `${ROCKMAP_BASE_URI}` only. A `published` style must contain all three placeholders:

- `${ROCKMAP_BASE_URI}`
- `${ROCKMAP_LAND_URI}`
- `${ROCKMAP_CLAIMS_URI}`

At runtime RockMap substitutes those placeholders in memory with the verified local Android file paths, for example `pmtiles://file:///data/user/0/com.rockmap.app/files/maps/...`. Absolute Android paths must never be generated on GitHub.

A field-safe published style must not contain any `http://` or `https://` runtime resource dependency. The app rejects such a style. Fonts/glyphs, sprites if any, and all required rendering resources must therefore be available offline.

### Alpha 3 local-label contract

The Alpha 3 basemap-test style is bundled at `app/src/main/assets/rockmap_basemap_label_style_alpha3.json`. It uses:

- `${ROCKMAP_BASE_URI}` for the already-installed PMTiles file;
- `asset://rockmap-glyphs/{fontstack}/{range}.pbf` for glyph PBFs;
- the `RockMapSans` font-stack identifier;
- no sprite URL and no HTTP/HTTPS runtime dependency.

The label test requires stable Alpha 3 layer IDs `rockmap-label-locality`, `rockmap-label-road-major`, `rockmap-label-water`, and `rockmap-label-peak` in addition to the `rockmap-base` source. Missing required label layers cause the basemap test style to fail closed rather than silently appearing successful.

## Required stable source IDs

The style must expose:

- `rockmap-base`
- `rockmap-land`
- `rockmap-claims`

## Required stable layer IDs

The style must expose:

- `rockmap-land-fill`
- `rockmap-land-outline`
- `rockmap-claim-fill`
- `rockmap-claim-outline`

After MapLibre loads a newly activated snapshot, RockMap checks all required sources/layers. Missing contract elements cause runtime rollback to the previous complete snapshot when available; otherwise RockMap switches to the blank local safety style.

## Normalized land properties

- `manager_name`
- `manager_code`

The layer is surface-management/status information. It is not a parcel survey or a legal property-boundary system.

## Normalized claim properties

- `name`
- `serial`
- `type`
- `disposition`
- `acres`
- `quality`
- `quality_description`

The normal claim overlay must exclude geometries whose BLM mapping quality is too coarse to be represented responsibly as a claim footprint, such as county-only placement. Approximate/section-derived geometry that remains useful must retain its quality description for display.

## Safety rules

- No cleartext (`http://`) URLs.
- Published runtime map styles have no `https://` dependencies either.
- Manifest/file download redirects must remain HTTPS.
- Filenames may not contain path separators or `..`.
- Required file sizes must be positive and at most 2,000,000,000 bytes per file.
- Every file has an exact SHA-256 and declared byte count.
- Downloads abort if they exceed the declared size.
- A partial download is never activated.
- The previous complete snapshot is retained for runtime rollback.
- A replacement manifest becomes active only after every required file verifies.
- A future incompatible contract increments `manifestVersion` or `styleSchemaVersion`; older APKs reject it instead of guessing.
- RockMap distinguishes unavailable/disabled layers from “no feature shown.”
- The claims overlay represents BLM MLRS **not closed** records selected by the data pipeline. It is not labeled as surveyed active-claim boundaries.
- RockMap never infers that collecting is legal merely because a point is on public land or because no claim feature is rendered.

## Alpha 3.1 offline text

The `basemap_test` runtime style uses `asset://rockmap-fonts/NotoSans-Regular.ttf` through MapLibre Native `font-faces` and retains `asset://rockmap-glyphs/{fontstack}/{range}.pbf` as a local fallback. These assets are generated at build time from pinned upstream blobs; font binaries are not stored in the source patch.

Alpha 3.1 also includes schema-tolerant fallback symbol layers `rockmap-label-place-any`, `rockmap-label-water-any`, and `rockmap-label-road-any` so named features remain visible if a Protomaps kind classification differs from the primary styling rules.
