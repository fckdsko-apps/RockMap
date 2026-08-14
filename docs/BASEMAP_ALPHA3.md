# RockMap Alpha 3 — offline label acceptance test

Alpha 3 adds local labels to the same Colorado PMTiles base that passed the Alpha 2 device test. It deliberately does **not** rebuild or redownload the statewide basemap.

## Installation order

1. Confirm the existing Alpha 2 installation still has the Colorado basemap installed.
2. Build `0.1.0-alpha3` with the existing known-good APK workflow.
3. Install the Alpha 3 APK **over** Alpha 2. Do not uninstall RockMap first; uninstalling would remove the app-internal map files and saved locations.
4. Open RockMap. No new 246 MB basemap download should be required.

## Expected safety state

The banner must remain red and must state that the basemap + labels are a test and are **NOT VERIFIED FOR NAVIGATION**.

The Data status should state:

- land status unavailable;
- mining claims unavailable;
- labels included offline for the Alpha 3 test.

Land-status and mining-claim checkboxes remain unavailable.

## Label checks

Use locations that are easy to recognize. Exact density varies with source data and zoom, but the following categories should appear where Protomaps contains names:

- Colorado/state or region label at statewide zoom;
- Denver and other cities/towns;
- neighborhoods at closer city zooms;
- major road/highway names or route text;
- minor road and named path labels at close zoom;
- named rivers/streams;
- named lakes/water bodies;
- named peaks in mountain areas.

Labels should remain legible over the Alpha 2 geometry and should not cause the map to fall back to the blank safety style.

## Offline proof

1. While online, inspect a few labeled places but leave several parts of Colorado untouched.
2. Enable airplane mode.
3. Fully close RockMap.
4. Reopen RockMap.
5. Pan to a part of Colorado not inspected during the online portion.
6. Zoom through statewide, town, road, and close trail/stream levels.

Both the map geometry **and labels** must appear without a network connection. A missing-glyph error, blank map, or silent online dependency fails Alpha 3.

## Alpha 3 pass criteria

- Existing Alpha 2 PMTiles data survives the APK update.
- GPS/current location still aligns with mapped surroundings.
- City/town labels render.
- Road labels render.
- Water labels render where named source features exist.
- Peak labels render where named source POIs exist.
- Labels render after a cold reopen in airplane mode in an unseen area.
- Saved locations still render and remain editable/exportable.
- Safety banner remains red/unverified.
- Land status remains unavailable.
- Mining claims remain unavailable.

After these checks pass, the next data milestone is the Colorado land/surface-management overlay. The map must still remain unverified until the land and mining-claim stages are complete and jointly tested.
