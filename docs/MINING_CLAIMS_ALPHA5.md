# RockMap Alpha 5 — Colorado mining-claims test

Alpha 5 adds one thing to the proven Alpha 4.1 checkpoint: an offline BLM MLRS mining-claims overlay. The existing Colorado basemap, Alpha 3.1 offline labels, and Alpha 4 BLM Surface Management Agency land-status PMTiles are reused byte-for-byte. The test remains **NOT VERIFIED FOR NAVIGATION**.

## Source and meaning

Source: U.S. Department of the Interior, Bureau of Land Management (BLM), Mineral & Land Records System (MLRS), **Mining Claims — Not Closed** FeatureServer.

The source dataset contains claim cases whose disposition is anything other than closed. RockMap does not relabel those polygons as surveyed active-claim boundaries. BLM states that geometries are mainly geocoded from Legal Land Descriptions and PLSS data and that geospatial representations may be missing for cases that cannot be geocoded.

The build spatially selects claim geometries intersecting a buffered Colorado envelope. Every OBJECTID returned by the selection must be fetched exactly once. Unknown claim type codes fail the build.

## Mapping-quality policy

BLM documents mapping-quality groups. Alpha 5 retains direct PLSS, calculated PLSS, section-mapped, mixed mapped/unmapped, improved, and unknown-reported geometry with an on-tap quality note. It excludes quality score **25** (mapped only to county) because that is too coarse to display as a claim footprint. Scores 11/12/20/21/22 are documented as attribute-only/no-geometry and are also excluded if geometry unexpectedly appears.

A missing rendered claim is not proof that no claim exists.

## Incremental download contract

The Alpha 5 release manifest copies the exact `style`, `base`, and `land` entries from immutable release `rockmap-land-alpha4-20260814-test1`, then adds one required `claims` PMTiles entry. A phone that already has Alpha 4 should therefore download only the new claims file.

The Alpha 5 APK points to immutable test release `rockmap-claims-alpha5-20260815-test1`.

## Build order

1. Do not uninstall RockMap.
2. Add `.github/workflows/build-claims-test.yml` from the supplied copy/paste workflow before uploading the Alpha 5 source patch.
3. Upload the Alpha 5 source patch and commit exactly `Add Alpha 5 mining claims test`.
4. That commit should trigger both **Build RockMap APK** and **Build Colorado Mining Claims Test Pack**.
5. Do not install the APK yet. Wait until both workflows are green and verify the claims release exists.
6. Install the verified Alpha 5 APK over Alpha 4.1.
7. Open **Data → Check for update**. The phone should reuse base/style/land and download only claims.

## Device acceptance test

After activation, Data should report Alpha 5 and include claim diagnostics. At a useful Colorado zoom in a mining district, claim source features and rendered claim features should be nonzero. In Layers, **Mining claims — BLM MLRS not closed** must toggle independently of land status. Magenta claim polygons must disappear/reappear with the toggle. Tapping a magenta polygon must report claim name, type, serial, disposition, acreage when present, and BLM mapping-quality information.

Repeat with airplane mode enabled after force-closing the app. Basemap, labels, land status, claims, legend, and tap inspection must remain functional offline.

The red **NOT VERIFIED FOR NAVIGATION** state stays in place after Alpha 5. The next milestone is a combined verification/safety pass, not automatic promotion to a field-safe state.
