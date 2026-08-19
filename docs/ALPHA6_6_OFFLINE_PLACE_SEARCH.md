# RockMap Alpha 6.6 — on-device offline place search

Alpha 6.6 adds **Find** without adding a second Colorado map or geocoder dataset.

## Data source

The searchable names come from the same PMTiles basemap RockMap already renders and stores
in the app's private `files/maps` directory. Search does not contact Google, Nominatim,
Geofabrik, GNIS, or any other online geocoder/data service.

The APK build does not extract Colorado PMTiles and does not generate or bundle a Colorado
place index. The real index is built on the Android device from the installed basemap.

## Fresh install / update behavior

- When RockMap starts, it checks the active basemap SHA-256.
- If the matching local place index is missing, WorkManager queues a background,
  network-free index build.
- If the basemap has not finished installing yet, the worker retries later.
- Opening **Find** while an index is missing queues an immediate build and reports
  `Preparing offline search from the installed basemap…` rather than using stale data.
- When a different basemap SHA-256 becomes active, the old index is rejected and rebuilt.

There is no manual indexing step for a fresh device.

## What is indexed

To keep the one-time phone scan bounded, Alpha 6.6 scans only source zoom **13** and only the
existing Protomaps `places`, `pois`, and `water` layers. This covers the use case requested
for regional map lookup while avoiding an all-road/all-trail index.

Expected searchable classes include:

- cities, towns, villages, hamlets, and named localities;
- peaks such as Mount Antero, mountain passes, viewpoints, selected landmarks, parks,
  campgrounds/trailheads, and selected historic features;
- named lakes, reservoirs, rivers, streams/creeks, canals, and other named water features.

Roads and trails continue to render normally from the basemap but are not promised as
searchable in this Alpha 6.6 index.

## Matching

The existing PlaceSearchEngine remains responsible for forgiving matching: case and
punctuation normalization, common abbreviations such as `mt`/`mtn`, partial names, and
conservative typo tolerance. Exact matches still outrank fuzzy matches.

## Safety and failure behavior

The indexer validates PMTiles v3, MVT tile type, supported compression, archive bounds,
directory ranges, tile sizes, feature counts, output size, and known sanity records. It
fails closed rather than silently producing a partial index. The current immutable RockMap
basemap is PMTiles v3 with gzip internal/tile compression and max zoom 14.

A Find result is a locator only. It is not routing, trail-open status, road drivability,
land ownership, mining-claim status, or collecting permission.

## Device acceptance test

1. Install Alpha 6.6 over the existing app; do not uninstall first.
2. Leave the existing map/data pack in place.
3. Open Find shortly after launch. If indexing is still running, confirm the app reports
   that offline search is being prepared instead of failing or using the network.
4. Search `Mount Antero`, `mtn antr`, and `mount antro`.
5. Search `Buena Vista` and `Denver`.
6. Search a named lake/reservoir and a named river/creek visible on the basemap.
7. Switch to airplane mode and repeat the searches.
8. Confirm the result centers the map and leaves the temporary yellow target marker.
9. Confirm GPS, land status, claims, minerals/heatmaps, historic mines, and saved markers
   remain unchanged.
