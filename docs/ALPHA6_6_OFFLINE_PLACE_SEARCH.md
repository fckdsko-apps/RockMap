# RockMap Alpha 6.6 — offline basemap search

Alpha 6.6 adds an offline **Find** tool without adding a second Colorado map dataset.
GPS, land status, mining claims, mineral evidence, historic mines, saved markers, and
coordinate search remain separate and unchanged. The Alpha 6.2.1 cumulative map/mineral
data release remains unchanged.

## What Find searches

The searchable index is generated from the same immutable Colorado Protomaps PMTiles
basemap RockMap already renders (`rockmap-basemap-alpha2-20260722-z14`). RockMap extracts
only named features from the basemap's overview tiles and stores a compact text index in
the APK. The phone does not contact an online geocoder when Find is used.

The compact index is intentionally not a complete copy of every vector feature at every
zoom. It targets overview tiles through zoom 11 and therefore prioritizes:

- cities, towns, villages, and named localities;
- prominent peaks, passes, viewpoints, parks, campgrounds, and selected landmarks;
- named lakes and reservoirs;
- named rivers, streams, and creeks present in overview tiles;
- highways and major named roads;
- named paths/trails only when they are already present in those overview tiles.

A tiny forest road or local trail that only appears in RockMap at deep zoom may still be
visible on the map but absent from Find. That is an intentional size/build-time tradeoff,
not evidence that the feature is absent from the underlying PMTiles.

Basemap attribution remains Protomaps © OpenStreetMap contributors. OpenStreetMap data is
available under the ODbL. The search index is derived from those existing RockMap bytes;
no Geofabrik, Google, Nominatim, or other second statewide map/geocoder dataset is bundled.

## Forgiving matching

Find is not an exact database lookup. It supports:

- case, punctuation, and accent normalization;
- partial names and prefixes;
- common abbreviations such as `mt`, `mtn`, `rd`, `hwy`, and `trl`;
- road-reference aliases when those references exist in the basemap;
- small spelling mistakes using conservative edit-distance matching.

Exact matches rank above partial and fuzzy matches. RockMap shows a ranked result list
before moving the map; fuzzy search never silently chooses a destination.

## Map behavior

Tap **Find**, enter a name such as `Mount Antero`, `mtn antr`, `Buena Vista`, `Twin Lakes`,
or `US 24`, then tap a result. RockMap centers the map and places a temporary yellow
search marker at the representative coordinate stored in the index. The marker is not a
saved waypoint and can be cleared from Find. Latitude/longitude input still works through
the same Find box.

Long roads and waterways are tiled geometries rather than single points. The builder
deduplicates repeated tile fragments and keeps a small number of representative search
targets for long linear features instead of preserving every fragment.

Alpha 6.6 also carries forward the pending Alpha 6.5 peak-label polish: the existing
offline peak-label layer is exposed from zoom 6.5 so prominent mountain names become
visible at a more useful regional view.

## Build-time index contract

The existing APK workflow does not need to change. During the normal Gradle `preBuild`,
`prepareOfflinePlaceIndex` range-reads RockMap's own immutable PMTiles release, extracts only
Colorado overview tiles from zoom 9 through zoom 11, and decodes only the `places`, `pois`,
`water`, and `roads` source layers. Within one workflow run the generated file is reused by
subsequent Gradle tasks from the normal `app/build` directory.

The Gradle-side generator rejects the PMTiles overview subset if it exceeds 80 MB and rejects
the final index if it is malformed, has fewer than 500 records, exceeds 12 MiB compressed,
is missing Denver/Buena Vista/Mount Antero, or lacks peak, water, or named-road coverage.
No `.github` workflow replacement is required.

The generated binary is not committed to source.

## Acceptance test

1. **Do not uninstall** the existing RockMap app; install Alpha 6.6 over it.
2. Confirm existing saved locations and installed offline map data remain present.
3. In airplane mode, tap **Find** and search `Mount Antero`; choose the Peak result and
   confirm the map jumps there with a temporary yellow marker.
4. Search `mtn antr` and `mount antro`; confirm Mount Antero still ranks at or near the top.
5. Search `Buena Vista`; confirm the town/locality result centers correctly.
6. Search a named lake/reservoir or river visible in RockMap and confirm it is selectable.
7. Search a major named road/reference visible at regional zoom and confirm it is selectable.
8. If a tiny trail visible only at deep zoom is not searchable, treat that as the intended
   compact-index limitation rather than a failed map-data load.
9. Enter a latitude/longitude pair in Find and confirm the existing coordinate workflow
   still centers correctly.
10. Confirm GPS, land status, claims, mineral area analysis/heatmaps, historic mines,
    Layers, and saved markers still behave as before.

A search result is a navigation/reference aid, not evidence that a road or trail is open,
drivable, legal to access, or safe. Land/claim/collecting legality remains separate.
