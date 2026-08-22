# RockMap P0–P3 — Commit 2: Spatial Research Query

**Commit:** `Add spatial geology research and area queries`

**Baseline:** `5491ca1acb67b0340e5e7d7c4b564e75a2b38123` (`Redesign Field export flow for clear selection`)

## Scope

This commit adds a Colorado-first, offline-after-install spatial geology research system while preserving the existing mineral, mine, land, claim, GPS, Field, Trip, Find, and export systems.

### Queryable geology

RockMap stores a Colorado-only local SQLite snapshot of polygon geology. The source is the USGS **State Geologic Map Compilation (SGMC)** data release, DOI `10.5066/F7WH2N65`, published through the ArcGIS FeatureServer layer used by the USGS/ArcGIS map product.

The app deliberately identifies this as the 2017 SGMC lineage. It does **not** label these records as the separate 2026 GeMS SGMC release.

The first install is user-triggered from Research. RockMap:

1. queries the source for the Colorado record count;
2. rejects counts outside a broad fail-closed sanity range;
3. downloads the Colorado source in bounded pages;
4. rejects any returned non-Colorado feature;
5. requires an object identifier and polygon geometry;
6. requires the number of records received to exactly match the count observed at the beginning of the download;
7. writes to a temporary SQLite database;
8. replaces the prior local geology database only after the complete replacement passes validation.

A failed refresh preserves the existing local snapshot.

Because the source is a live feature service rather than a RockMap release artifact, exact transfer bytes and a RockMap SHA-256 cannot be known before transfer. The app says this explicitly and reports local database size after activation. This is intentionally separate from the existing release-managed offline-pack updater, which retains its size/SHA behavior.

### Search and filters

Search covers the installed source's unit names, original/standardized labels, generalized/major/minor lithology, incidental/indeterminate lithology, and age fields. Suggestions are generated only from terms present in the installed local database.

Search can be statewide or limited to the map bounds that were visible when Research was opened. Optional lithology and age filters can be combined with the general text query.

### Spatial queries

Research supports:

- visible map rectangle;
- an actual saved Field prospecting polygon;
- a coordinate with a radius from 0 m through 100 km.

Saved-area geology is clipped logically against the saved polygon rather than merely treating the polygon's rectangle as the geology query. Existing mineral-area analysis currently accepts rectangular bounds, so the Research UI explicitly labels that mineral cross-reference as the saved polygon's **bounding rectangle** rather than pretending it is exact polygon clipping.

### Results and map behavior

Results include source polygon geometry and SGMC attributes needed for geology research and provenance. A selected result can be shown as a translucent temporary map overlay.

Research geology is deliberately inserted beneath land-status, mining-claim, and saved-location safety/user layers. It does not change their meaning or visibility logic.

The last Research result is staged as an app-private local GeoJSON file. Main/Field handoff sends only small metadata through Android Intents and reads the geometry from that local result file. This avoids Android Binder transaction failures from large polygon GeoJSON extras.

### Existing evidence cross-reference

Research reuses rather than duplicates:

- RockMap mineral/locality evidence;
- mineral-area analysis/heatmap behavior;
- historic mine/workings overlay.

### Field integration

- **Field Record → Research geology here:** starts a 1 km geology query around the Field Record.
- **Saved prospecting area → Analyze geology & evidence:** queries the actual saved polygon.
- **Field → Research geology:** opens the general Research workspace.
- **Field → Export data → Research result:** exports the most recent Research result as GeoJSON or CSV.

### Data retention / backup

The downloadable geology database is replaceable public reference data, so it is excluded from Android cloud backup/device transfer. The reproducible last Research-result GeoJSON/meta files are also excluded. Existing user-created waypoints, trips, Field Records, tracks, areas, notes, and related user data remain subject to the existing backup behavior.

## Source / commercial-use note

The USGS data-release page for `10.5066/F7WH2N65` currently marks the work **CC0 1.0 Universal**. The ArcGIS item metadata also contains standard language noting that some information products may contain copyrighted material as noted in source text. RockMap preserves source provenance. This source should remain on the pre-public-launch licensing audit rather than treating “publicly available” as a substitute for commercial-rights review.

## Deliberately out of this commit

- satellite imagery downloads;
- LiDAR / 3D terrain;
- private parcels;
- a major terrain/elevation/slope/aspect/hillshade package;
- full mining-claim history/change detection;
- migration of the geology source to the separate 2026 GeMS SGMC release.

## Regression guard

The Commit 2 patch was constructed from the current post-Commit-1 source. Static comparison confirmed that these existing methods were unchanged:

- Main `locate()`;
- Main fresh precise `centerOnGpsFix(...)` path;
- Main `saveLocation()`;
- Main location permission helper/result handling;
- Main location update callback;
- Field precise-location helper;
- Field track-start logic;
- Field track-command logic.

No new Android permission, Gradle dependency, signing change, or `.github` workflow file is included.
