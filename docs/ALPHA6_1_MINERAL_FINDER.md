# Alpha 6.1 — compact Colorado mineral finder

Alpha 6.1 adds an offline mineral-search research aid without turning RockMap into a permanently cluttered geology map.

## Data included

The phone pack contains only a compact Colorado subset of the U.S. Geological Survey Mineral Resources Data System (MRDS). The builder keeps search-relevant fields: occurrence/site name and coordinates, mineral/material names, commodities, district names already present in MRDS records, deposit-model names, compact rock names, development-status text, and grade where present.

The phone pack deliberately excludes production history, ownership, reserves/resources, references, long comments, and verbose geology text. Alpha 6.1 does not add CGS mining-district polygons, geochemistry, terrain/contours, imagery, magnetics, radiometrics, or another state.

The compressed mineral index has a hard 15 MB release-build limit and a 64 MB uncompressed parser limit.

## Search behavior

The normal map has no permanent mineral layer. The `Minerals` button searches the local index and displays at most 50 matching cyan occurrence points. Clearing the search removes them.

Search priority is:

1. mineral/material name
2. commodity
3. site name
4. district name
5. deposit model
6. rock/geologic context

Several familiar gemstone names have a conservative parent-mineral fallback only when there are no exact MRDS matches, for example aquamarine → beryl and amazonite → microcline. The app labels these results as geological leads rather than exact gemstone occurrences.

Search results can be saved into the existing Saved-locations database as normal orange markers with the MRDS source ID retained in the note.

## Bottom controls

Alpha 6.1 uses concise, differentiated labels:

`GPS | Save GPS | Coords | Minerals | Layers | Markers | Data`

- `GPS`: center on the device GPS fix.
- `Save GPS`: save a fresh precise device GPS fix.
- `Coords`: search typed latitude/longitude and optionally save a manual marker.
- `Minerals`: search the local MRDS mineral index.
- `Layers`: map-layer visibility and legends.
- `Markers`: saved locations, edit/import/export.
- `Data`: offline pack status, diagnostics, and update.

## Data/update contract

The Alpha 6.1 data release reuses the exact Alpha 5 style, basemap, BLM Colorado land-status, and BLM MLRS claims file entries. It adds one required gzip JSON index with id `minerals`, kind `index`.

The Android updater verifies the new index size and SHA-256 before activation. Existing files whose size and SHA-256 already match are reused rather than downloaded again.

## Safety semantics

MRDS is a mineral-resource research database, not a rockhounding-permission database. Human-activity information can be stale. A mapped mineral occurrence does not establish current ownership, legal access, mining-claim status, or permission to collect. Absence from the compact search index is not proof that a mineral is absent.
