# Alpha 6.1 / 6.1.1 — compact Colorado mineral finder

Alpha 6.1 adds an offline mineral-search research aid without turning RockMap into a permanently cluttered geology map. Alpha 6.1.1 refines how large search results are filtered, displayed, hidden, cleared, and saved; it does not change the mineral data pack.

## Data included

The phone pack contains only a compact Colorado subset of the U.S. Geological Survey (USGS) Mineral Resources Data System (MRDS). The builder keeps search-relevant fields: occurrence/site name and coordinates, mineral/material names, commodities, district names already present in MRDS records, deposit-model names, compact rock names, development-status text, and grade where present.

The phone pack deliberately excludes production history, ownership, reserves/resources, references, long comments, and verbose geology text. Alpha 6.1 does not add CGS mining-district polygons, geochemistry, terrain/contours, imagery, magnetics, radiometrics, or another state.

The compressed mineral index has a hard 15 MB release-build limit and a 64 MB uncompressed parser limit.

## Search behavior

The `Minerals` button searches the local index. Alpha 6.1.1 removes the permanent 50-result cap: every matching record in the selected search area remains available.

To keep broad searches usable, RockMap does not force thousands of individual dots onto the screen at once. All matching records are sent to a clustered offline MapLibre layer. Dense groups appear as numbered clusters and progressively separate into individual cyan mineral points as the user zooms in. Tapping a cluster zooms toward its contents.

The results list initially shows 100 records. `+100` progressively reveals more rows and `Show all` makes the complete result list available when desired. This list paging is only a presentation control; it never removes matches from the map or search result set.

Search area has two simple choices:

- `All Colorado`: searches the complete Colorado MRDS phone index.
- `Current map area`: searches only the geographic rectangle currently visible on the map. This avoids requiring the user to type a county, district, town, or database-specific region name.

Search priority is:

1. mineral/material name
2. commodity
3. site name
4. district name
5. deposit model
6. rock/geologic context

Several familiar gemstone names have a conservative parent-mineral fallback only when there are no exact MRDS matches in the selected area, for example aquamarine → beryl and amazonite → microcline. The app labels these results as geological leads rather than exact gemstone occurrences.

Tapping an individual mineral point on the map continues through RockMap's normal `Location information` popup, which now includes `Save marker`. That action saves the tapped latitude/longitude as a normal coordinate marker. Opening a mineral from the search-results list still saves it as a mineral-source marker with the MRDS source ID retained in the note.

## Mineral layer controls

After a search, `Layers` includes `Mineral results — N`. Turning that checkbox off hides the current mineral result layer without discarding the search. Turning it back on restores the same results.

`Clear minerals` is different from hiding the layer: it removes the active mineral search results from the map and memory. A new search replaces the previous mineral result set.

## Bottom controls

Alpha 6.1 uses concise, differentiated labels:

`GPS | Save GPS | Coords | Minerals | Layers | Markers | Data`

- `GPS`: center on the device GPS fix.
- `Save GPS`: save a fresh precise device GPS fix.
- `Coords`: search typed latitude/longitude and optionally save a manual marker.
- `Minerals`: search the local MRDS mineral index.
- `Layers`: map-layer visibility and legends, including current mineral results.
- `Markers`: saved locations, edit/import/export.
- `Data`: offline pack status, diagnostics, and update.

## Data/update contract

The Alpha 6.1 data release reuses the exact Alpha 5 style, basemap, BLM Colorado land-status, and BLM MLRS claims file entries. It adds one required gzip JSON index with id `minerals`, kind `index`.

Alpha 6.1.1 is APK-only. It continues to point to `rockmap-minerals-alpha6-1-20260815-test1` and must not publish or require a replacement mineral pack.

The Android updater verifies the index size and SHA-256 before activation. Existing files whose size and SHA-256 already match are reused rather than downloaded again.

## Safety semantics

MRDS is a mineral-resource research database, not a rockhounding-permission database. Human-activity information can be stale. A mapped mineral occurrence does not establish current ownership, legal access, mining-claim status, or permission to collect. Absence from the compact search index is not proof that a mineral is absent.
