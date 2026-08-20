# RockMap P0-P3 batch — Commit 1: Field tools

Commit name: `Add RockMap field tools and advanced saved records`

Baseline: commit `8af38645e8700fe843a9bdd7c61a4f3626e9ca0e` (`Restore precise GPS centering`).

This patch intentionally leaves `MainActivity.java`, `LocationRepository.java`, the current basemap/data manifests, data builders, signing configuration, and GitHub Actions workflows unchanged.

## What this commit adds

- Map-first Field workspace without expanding the existing two-row map toolbar. A compact `Field` button is added above the existing two-row map toolbar at runtime by `RockMapApplication`; the existing `Markers` button and its behavior remain unchanged, and the original `MainActivity` source remains untouched.
- Foreground GPS track recording with pause/resume/stop.
- Persistent breadcrumb points and track stats.
- Backtrack screen with breadcrumb shape plus live distance/bearing to the recorded start.
- Separate additive `rockmap-field.db` so current RockMap waypoints/trips are not migrated or rewritten.
- Rich field records with name, category, mineral/material, sample ID, notes, coordinates, GPS accuracy, elevation and an optional user-selected photo reference.
- Existing RockMap saved locations remain intact and can be copied into richer Field Records.
- Straight-line navigate-to-point guidance for Field Records and existing saved locations.
- Distance/path measurement, first-to-last bearing and polygon area calculation.
- Saved prospecting polygons.
- Additive GPX import: waypoints, routes and tracks.
- Additive KML import: points, lines and polygons.
- Additive GeoJSON import: points, LineStrings and Polygons.
- Coordinate output in decimal degrees, DDM, DMS, WGS84 UTM and MGRS.
- Privacy-policy update for explicit foreground track recording and local field data.

## Data/storage behavior

No new geology, claims, satellite, LiDAR, parcel, basemap or other bulk dataset is bundled or downloaded by this commit.

Track points are tiny local database records. Recording uses Android's precise GPS provider. RockMap does **not** request `ACCESS_BACKGROUND_LOCATION`; an explicitly started track uses a visible location foreground service so recording can continue while the user returns to the RockMap map or locks the screen.

Imported files are capped at 10 MB and 20,000 geometry points per import. Imports add data; they do not replace existing RockMap markers, trips, tracks or field records.

## Safety / regression constraints

- Do not uninstall RockMap before installing the resulting APK.
- Existing offline data must remain installed.
- Existing GPS centering behavior is not modified by this patch.
- No data-builder workflow should be manually run for this commit.
- The normal APK build is the only expected build for the source commit.
- Do not treat breadcrumb backtrack as routing. It does not evaluate roads, terrain, hazards, access or legality.

## Device test checklist after the APK is inspected

1. Open RockMap and confirm the existing two-row toolbar is unchanged and a compact `Field` button appears just above it.
2. Tap `GPS`; confirm precise centering still behaves exactly like the verified baseline.
3. Open `Field` → `Track recording & backtrack` → start a track outdoors.
4. Walk at least 50–100 m, return to the normal map, then reopen Field and confirm the track continued recording.
5. Pause, walk briefly, resume, then stop. Confirm track points/stats remain and no points were intentionally added while paused.
6. Open Backtrack and confirm live distance/bearing to the recorded start responds to movement.
7. Create a Field Record at GPS; add category/mineral/sample ID/notes and optionally attach a photo.
8. Open existing Saved locations and copy one to a Field Record. Confirm the original red marker remains.
9. Measure 2+ entered coordinates; with 3+ points save a prospecting area.
10. Import a small known GPX, KML and/or GeoJSON. Confirm import is additive.
11. Convert a known Colorado coordinate and confirm UTM zone 13 and plausible output.
12. Reboot or fully close/reopen RockMap. Confirm saved field records, tracks and areas persist.
13. Confirm Trips, Minerals, Layers, Data, Find, Save GPS and normal saved-marker map rendering still work.
