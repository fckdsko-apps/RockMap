# RockMap Alpha 6.0 — Coordinate search and manual markers

Alpha 6.0 is APK/UI-only. It deliberately keeps the Alpha 5 Colorado basemap, labels,
land-status, and BLM MLRS claims data contract unchanged.

## Added

- A sixth bottom control: **Search**.
- Fully offline latitude/longitude parsing.
- Supported coordinate input includes:
  - Decimal degrees: `39.290719, -106.212474`
  - Decimal degrees with hemispheres: `39.290719 N, 106.212474 W`
  - Degrees/minutes/seconds: `39°17'26.6"N 106°12'44.9"W`
  - Degrees/decimal-minutes: `39°17.443′ N, 106°12.748′ W`
- Search centers the map on the parsed coordinate without requesting location permission or network access.
- The result can be saved as a persistent orange RockMap marker with a user-entered name and notes.
- Manual coordinate markers use the existing Room waypoint database and existing GeoJSON export/import path.
- Export/import preserves whether a waypoint originated from manually entered coordinates without changing the Room schema.

## Intentionally unchanged

- No new map-data download.
- No change to the Alpha 5 claims release manifest.
- No basemap, land, claims, label, style, or PMTiles rebuild.
- No new Android permissions.
- Existing GPS `Locate` and GPS `Save` behavior remains separate from manual coordinate search.

## Alpha 6.0 device checks

1. App reports `RockMap 0.1.0-alpha6.0` under Data.
2. Existing basemap, labels, land status, and claims still render immediately after installing over Alpha 5.1.
3. Search `39.290719, -106.212474`; map centers there.
4. Search `39°17'26.6"N 106°12'44.9"W`; it resolves to the same location within rounding.
5. Invalid coordinates such as `95, -106` are rejected rather than silently moved.
6. Save a coordinate marker with a name and notes; the orange Saved-locations marker appears.
7. Open the saved marker; it says `Source: manually entered coordinates` and retains the note.
8. Force-close/reopen the app; the marker remains.
9. Airplane mode: coordinate search and saved-marker display still work.
10. Existing GPS Save still records reported GPS accuracy instead of being labeled as manually entered.
