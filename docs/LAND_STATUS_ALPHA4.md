# RockMap Alpha 4 — Colorado land-status test

Alpha 4 adds one thing: an offline Colorado surface-management/status overlay. The Alpha 2 basemap and Alpha 3.1 label stack are intentionally left alone. Mining claims are intentionally not included yet.

## Do not change

- Do not edit `.github/workflows/build-apk.yml`.
- Do not edit or rerun `.github/workflows/build-basemap-test.yml`.
- Do not replace or modify the immutable Alpha 2 basemap release.
- Do not change `ROCKMAP_SIGNING_BUNDLE_B64`.
- Do not uninstall RockMap from the phone. The existing Colorado basemap and saved locations must be preserved.

## Data source and normalization

The land workflow fetches the official BLM Colorado Surface Management Agency polygon layer. The source is checked for its expected polygon schema and coded manager categories. The build first obtains the complete OBJECTID inventory, downloads those objects in batches, requires every expected OBJECTID exactly once, validates Colorado-area geometry, then writes a normalized GeoJSON containing only `manager_code` and `manager_name`.

Tippecanoe converts those polygons to a vector-tile layer named `land`. The PMTiles metadata is checked before publication. The published Alpha 4 manifest copies the exact `style` and `base` entries from the immutable Alpha 2 manifest and adds only the new `land` file.

The source is management/status mapping, not a parcel survey or legal property-boundary system. Alpha 4 remains `NOT VERIFIED FOR NAVIGATION`.

## Repository/build order

1. Make sure no RockMap GitHub Actions job is running.
2. Upload every file inside `RockMap_ALPHA4_LAND_SOURCE_PATCH.zip` to the existing repository in one commit.
3. Commit to `main` with: `Add Alpha 4 land status test`.
4. Wait for the automatic **Build RockMap APK** workflow to finish successfully.
5. Create `.github/workflows/build-land-test.yml` and paste the complete contents of `RockMap_LAND_ALPHA4_WORKFLOW_COPY_PASTE.yml`.
6. Commit that workflow file with: `Add Alpha 4 land status test workflow`.
7. The workflow-file commit will also trigger the normal APK build. Let it finish; use the newest successful `0.1.0-alpha4` APK.
8. In Actions, select **Build Colorado Land Status Test Pack** and run it manually once.
9. Wait for a green check. The workflow publishes the immutable prerelease `rockmap-land-alpha4-20260814-test1`.
10. Do not run the old Colorado basemap workflow.

## Phone update

1. Install the newest Alpha 4 APK **over** the existing RockMap installation. Do not uninstall first.
2. Open RockMap and confirm the existing basemap/labels still render.
3. Open **Data** and tap **Check for update**. Alpha 4 is the first phase where this is expected after the APK install because the phone needs the new land file.
4. The manifest reuses the exact Alpha 2 base/style hashes. If those files are still valid locally, RockMap skips them and downloads only the land component.
5. After activation, the banner must remain red and the Data dialog should report:
   - `OFFLINE BASEMAP + LABELS + LAND STATUS: TEST — NOT VERIFIED FOR NAVIGATION`
   - Land status included offline (Alpha 4 BLM Colorado SMA test)
   - Mining claims unavailable
   - Alpha 3.1 labels retained
   - Label diagnostics
   - Land diagnostics

## Device acceptance test

Test several known management types, not just one place:

- a BLM-managed area;
- a US Forest Service area;
- a Colorado state-managed area;
- an obviously private/developed area.

At useful zooms, land polygons should be visibly colored and boundaries should remain below road/place labels. In **Layers**, Land status must be independently toggleable while Mining claims remains disabled.

Tap several areas. The location panel should show the rendered manager name/code where a land polygon exists. If no land feature is rendered at a tap, it must say the result is unknown; it must never infer public land from an empty result.

Open **Data** while zoomed into an area containing land polygons. The land diagnostics should show source features and normalized manager features greater than zero, and rendered land features should be greater than zero when a visible polygon is in the viewport.

Then prove offline operation:

1. Turn on airplane mode.
2. Fully force-close RockMap.
3. Reopen it.
4. Pan to other Colorado locations that were not just on screen.
5. Zoom through regional, city and close field-map levels.
6. Confirm basemap geometry, labels and land-status polygons still render.
7. Tap land polygons and confirm manager information still appears.

The red `NOT VERIFIED FOR NAVIGATION` state must remain throughout the entire Alpha 4 test. No mining-claim conclusion can be made in this phase.
