# RockMap Alpha 2 — Colorado basemap test

Alpha 2 is deliberately an **unverified basemap test**, not a navigation release.

## What this pack contains

- Colorado-focused Protomaps vector basemap extracted from a pinned OpenStreetMap-derived daily build.
- Zoom levels through 14 so the test can exercise roads, paths/trails, rivers, streams, buildings, and landcover where the upstream tiles contain them.
- A local-only MapLibre style with no glyph, sprite, HTTP, or HTTPS dependency after installation.
- SHA-256 and byte-count verification before activation.

## What it does not contain

- Offline labels/place names.
- BLM surface-management/land-status polygons.
- BLM MLRS mining-claim data.
- Terrain/elevation.
- Any conclusion that a location is legal to collect from.

The app must keep the safety banner red and explicitly mark land status and claims as unavailable while this pack is active.

## Source and attribution

The test workflow uses the Protomaps Version 4 daily basemap channel, derived principally from OpenStreetMap. The generated release manifest records the exact source date, Colorado extraction bounds, maximum zoom, byte sizes, and SHA-256 digests. RockMap visibly attributes `© OpenStreetMap contributors · Protomaps` while the pack is displayed.

## Device acceptance test

After the pack installs, check all of the following before moving to labels or overlays:

1. Map renders Colorado at statewide and local scales without a network fallback.
2. Denver, Front Range, mountain, Western Slope, and state-edge locations appear geographically aligned with GPS.
3. Major/minor roads render; paths/trails appear when zoomed in enough where present in OSM.
4. Rivers and streams render where present in the source data.
5. Saved RockMap waypoints remain aligned with the basemap.
6. Airplane mode: close/reopen RockMap and pan/zoom around previously unvisited Colorado areas. The map must still render.
7. Layers dialog marks land status and mining claims unavailable.
8. Safety banner remains red and says the basemap is not verified for navigation.
9. No place/road labels are expected in Alpha 2.

Only after this passes should offline labels be added, followed by land status, then mining claims, and finally a full field-safety verification pass.
