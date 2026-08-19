# RockMap Alpha 6.6 — bundled offline Find

Alpha 6.6 adds a fast offline **Find** tool without making the phone scan the statewide
PMTiles archive and without bundling a second map dataset.

## Runtime design

The APK contains a compact gzip text index generated before packaging. On a fresh install,
Find is ready immediately; Android does not enumerate Colorado tiles, decompress thousands
of vector tiles, or contact an online geocoder.

Upgrades from the abandoned on-device-index prototype cancel its old WorkManager job. The
legacy worker class remains only as a compatibility shell and exits successfully without
reading the PMTiles basemap.

## Search sources

The build-time generator uses:

- U.S. Geological Survey National Map Gazetteer layers for Colorado administrative names,
  transportation names exposed by that service, landforms, named hydro lines, and named
  hydro points;
- Colorado Department of Transportation's state-highway route layer for Interstates,
  U.S. Highways, and State Highways.

The generated APK asset contains only names, feature categories, representative coordinates,
aliases, and ranking/context fields. Source GIS geometries are temporary build inputs and
are not included in the APK.

This search catalog is intentionally separate from RockMap's rendered Protomaps/OpenStreetMap
basemap. A name can therefore differ from a label in the basemap or exist in only one source.
This is preferable to a slow first-run statewide tile scan and does not alter the map itself.

## Matching

Find retains Alpha 6.6's forgiving local matching:

- case, punctuation, and accent normalization;
- partial names and prefixes;
- common abbreviations such as `mt`, `mtn`, `rd`, `hwy`, and `trl`;
- Mount aliases generated for names such as Mount Antero;
- highway aliases such as `I-70`, `Interstate 70`, `US Highway 24`, `SH 82`, and
  `State Highway 82`;
- conservative typo tolerance.

RockMap always shows a ranked result list before recentering. It never silently chooses a
fuzzy destination.

## Long features

Roads and other linear features are represented by one search target derived from their
source geometry. This is only a locator. It is not a route, trailhead, access point, or
statement that any part of the feature is open, legal, passable, or safe.

## Build contract

The existing GitHub APK workflow does not change. Normal Gradle `preBuild` runs
`prepareOfflinePlaceIndex`, which creates `rockmap_place_index.tsv.gz` in Gradle's generated
assets directory. The final compressed index must be between 20 KB and 8 MiB, contain at
least 2,000 records, include Denver, Buena Vista, Mount Antero, and US 24, and contain peak, water,
and CDOT highway coverage.

No `.github` workflow replacement is required. No PMTiles extraction, tippecanoe build, or
statewide device indexing is part of Alpha 6.6.

## Acceptance test

1. Install the new Alpha 6.6 APK **over** the existing RockMap app; do not uninstall first.
2. Open **Find** immediately. It must not display a long-running "Preparing offline search"
   state.
3. In airplane mode search `Mount Antero`, `mtn antr`, and `mount antro`.
4. Search `Buena Vista` and `Twin Lakes`.
5. Search `US 24` and another major Colorado highway.
6. Search a named transportation/trail feature if it is represented by the USGS Gazetteer.
7. Enter `38.6741, -106.2462` and confirm coordinate Find still works.
8. Select a result and confirm RockMap recenters and displays the temporary search marker.
9. Confirm GPS, saved markers, land status, mining claims, Minerals, mineral-area analysis,
   historic mines, Layers, and Data still behave as before.

Find is not routing/navigation guidance and does not determine ownership, access, road or
trail condition, claim status, or collecting legality.
