# RockMap offline data contract — schema 1

RockMap deliberately separates downloadable map data from user waypoints. The Android app never consumes BLM or Protomaps field names directly. The GitHub data-building workflow will normalize upstream GIS data to this stable contract.

## Field-safety status for alpha5

`0.1.0-alpha5` remains a `status: basemap_test` build so adding mining claims cannot accidentally make the app field-verified. It reuses the exact Alpha 4 `style` + `base` + `land` manifest entries and the Alpha 3.1 APK-local label renderer, then adds one required `claims` PMTiles component.

The Alpha 4 land source is the official **BLM Colorado Surface Management Agency (SMA)** polygon feature service. The GitHub build fetches the current source, requires complete OBJECTID coverage, fails closed if the coded manager schema changes unexpectedly, and normalizes every polygon to RockMap's two stable fields: `manager_code` and `manager_name`. Raw BLM fields are not shipped in the PMTiles file.

A `basemap_test` snapshot may contain `style` + `base` (Alpha 2/3.1), `style` + `base` + required `land` (Alpha 4), or `style` + `base` + `land` + required `claims` (Alpha 5). In every test state the safety banner remains red and the snapshot is not field-safe. Alpha 5 must copy the Alpha 4 `style`, `base`, and `land` entries exactly so phones reuse those local files rather than redownload them.

Alpha 4 builds land data separately from the basemap. Alpha 5 adds claims separately again. The app injects `rockmap-land` and `rockmap-claims` sources/layers into the proven Alpha 3.1 local style at runtime and substitutes `${ROCKMAP_LAND_URI}` / `${ROCKMAP_CLAIMS_URI}` with verified local PMTiles paths. Labels remain above both overlays. No runtime HTTP/HTTPS dependency is permitted.

The SMA layer is surface-management/status mapping. It is not a parcel survey, surveyed legal property boundary, title record, or legal determination that collecting/access is permitted. A tap with no rendered land feature is explicitly treated as **unknown**, never as public land.

Do not publish a manifest with `status: published` until the complete Colorado style/data passes the device/airplane-mode tests with land status, mining claims, offline labels and all safety wording.

### Basemap-test manifest

A `basemap_test` manifest uses the same file-integrity rules as a published snapshot. The immutable Alpha 2 release template still contains `${ROCKMAP_BASE_URI}` only. Alpha 3.1/4/5 may replace that rendering template in-app with the APK-bundled local label style. Alpha 4 adds the required `land` PMTiles source. Alpha 5 retains that exact land entry, adds required `claims`, inserts `${ROCKMAP_LAND_URI}` and `${ROCKMAP_CLAIMS_URI}` into the in-memory style, and resolves both to verified local files.

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

The downloaded style file is a **template**, not a device-specific style. The immutable downloaded `basemap_test` style requires `${ROCKMAP_BASE_URI}` only. Alpha 4's in-memory test style may additionally contain `${ROCKMAP_LAND_URI}` after the app injects the land source/layers. A `published` style must contain all three placeholders:

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

### Alpha 4 land-status contract

The land-data builder reads the official BLM Colorado SMA feature layer and emits a vector layer named `land`. It retains only:

- `manager_code`
- `manager_name`

The build must verify that the PMTiles metadata contains the `land` layer and no unexpected raw attributes, and must reject an implausibly small source set or missing expected BLM/private/state/USFS categories. The land release is immutable once published.

Alpha 4 requires the runtime source/layers:

- `rockmap-land`
- `rockmap-land-fill`
- `rockmap-land-outline`

Land controls may be enabled while claim controls remain disabled. This is intentionally different from the future `published` state, where both land and claim contracts are required.

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

The layer is surface-management/status information. It is not a parcel survey, surveyed legal property boundary, title record, or legal access/collecting determination. RockMap must preserve that distinction in the UI.

## Normalized claim properties

- `name`
- `serial`
- `legacy_serial`
- `type`
- `type_code`
- `disposition`
- `acres`
- `quality`
- `quality_description`
- `source`

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


## Alpha 5 test release

The Alpha 5 APK points to immutable prerelease `rockmap-claims-alpha5-20260815-test1`. That manifest remains `basemap_test`, copies the exact Alpha 4 style/base/land entries, and adds only the claims PMTiles. The normal claim display uses a single magenta treatment so it cannot be confused with land-status ownership/management colors; claim type and disposition are reported on tap.

## Alpha 6.1 / 6.2 mineral-search indexes

Alpha 6.1 adds required index id `minerals`, a gzip JSON schema-1 compact USGS MRDS search index. Alpha 6.2 reuses that exact immutable file and adds required index id `mineral_localities`, a separate tiny reviewed official CGS/USGS locality supplement. Both use kind `index` and `.json.gz` filenames.

Alpha 6.2 manifests must contain the cumulative required chain `style`, `base`, `land`, `claims`, `minerals`, `mineral_localities`. The first five entries must be byte-for-byte manifest entries copied from the immutable Alpha 6.1 baseline; only `mineral_localities` is new.

Locality records carry source/evidence/precision metadata and are area reference points, not specimen pockets. Mineral-search results do not establish ownership, access, claim status, or collecting legality.

The runtime land hit-test layer `rockmap-land-hit-test` is an informational query layer over `rockmap-land`. It remains queryable when the colored land-status display is hidden so a tapped mineral point can report mapped manager code (for example `PRI`, `BLM`, `USFS`, or `NPS`). It does not change the legal meaning or precision of the BLM SMA land dataset.

## Alpha 6.2.1 expanded mineral-evidence index

Alpha 6.2.1 adds one required compact index id `mineral_evidence`. The cumulative `basemap_test` manifest chain becomes `style`, `base`, `land`, `claims`, `minerals`, `mineral_localities`, `mineral_evidence`. The first six entries are copied exactly from the immutable Alpha 6.2 baseline so existing phones reuse the already-verified basemap, land, claims, MRDS, and official-locality files. Only `mineral_evidence` is newly downloaded.

The expanded evidence index normalizes separate official-source evidence classes instead of pretending every point means the same thing:

- `USGS_MAS` — USGS MAS/MILS OFR 03-090 historic mine/mineral properties.
- `CGS_B40` — CGS ON-B-40D / B-40 documented radioactive-mineral occurrences.
- `CGS_MS17` — CGS MS-17 (2022 update of IS-62) industrial/nonmetallic mineral mine inventory.
- `CGS_USFS_AML` — CGS/USFS ON-008-04D abandoned-mine inventory features.
- `CGS_DISTRICTS` — CGS ON-007-08D Historic Metal Mining Districts broad-area mineral evidence.

Every normalized record must carry its own `source_code`, `source_title`, `source_reliability`, `evidence_type`, and `location_precision`. RockMap displays the publication/database identifier and a short source-specific reliability statement with the record. Source provenance is never inferred from the mineral name.

The data builder may retain source-specific identifiers and concise source notes needed to audit a result. It must not merge unlike sources into a single stronger-looking observation unless every contributing source remains separately cited. Duplicate coordinates from different source families therefore remain independently attributable evidence.

USFS abandoned-mine features are not automatically treated as mineral occurrences. They become searchable by mineral/commodity only when an explicit source field provides that association; otherwise they remain mine-feature/site-name evidence. Historic-district results are explicitly broad-area evidence. A representative district point is for display/search navigation only and is not a specimen location.

Mindat is not bundled in Alpha 6.2.1. Its current API requires approved access and carries license restrictions that are not assumed compatible with RockMap distribution. No Mindat scraping, repackaging, or derived locality dump is permitted without a separately reviewed authorization/license path.

The compressed `mineral_evidence` file is capped at 15,000,000 bytes for this alpha. The build fails closed on implausibly small source counts, insufficient searchable records, missing source metadata, schema drift, or oversized output. The release also records SHA-256 hashes of the downloaded upstream source packages so the exact source inputs are auditable.

### Saved mineral waypoint provenance

Mineral-search waypoints continue using the existing waypoint database and the mineral-source sentinel; no destructive migration is required. Newly saved mineral waypoints persist the rich result text, including searched term, match reason, recorded minerals/materials, commodities, district/model/rock context when present, evidence class, source record ID, source citation, compact reliability statement, precision statement, and the land-management snapshot shown when saved.

The red saved-waypoint layer must be hit-tested before temporary mineral results and generic map features. Tapping a saved mineral red dot reopens the saved rich mineral information rather than falling through to the generic coordinate/land/claims popup. Older saved mineral waypoints remain readable with whatever provenance text was stored when they were originally saved.

