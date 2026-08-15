# RockMap Alpha 5.1 UI cleanup

Alpha 5.1 is an APK-only UI checkpoint on top of the existing Alpha 5 data pack.

- The persistent red banner is now one compact line: `TEST DATA — NOT VERIFIED FOR NAVIGATION`.
- Full pack, source, version, boundary warnings, and diagnostics remain in **Data**.
- Mining-claim tap cards show claim name, type, status, serials, area, and a compact normalized mapping-quality label.
- Verbose raw BLM `QLTY` processing notes remain in the offline data source but are not dumped into the normal field popup.
- No basemap, label, land-status, or claims PMTiles are rebuilt or replaced.
- The APK still points to `rockmap-claims-alpha5-20260815-test1`.

Do not uninstall the existing app. Install the Alpha 5.1 APK over it; existing offline map data should remain in place.
