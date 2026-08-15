# RockMap Alpha 6.4 — GPS / field validation

Alpha 6.4 stops adding geology datasets and focuses on validating the phone GPS path RockMap will use in the field. It also fixes the Alpha 6.3 historic-mine display order so a saved mine waypoint remains visibly red/orange above the brown source mine point.

## Data and storage

Alpha 6.4 does not publish or download a new geology pack. The APK deliberately reuses `rockmap-minerals-alpha6-2-1-20260815-test1` and all already-installed basemap, land, claim, mineral, locality, and expanded-evidence files. Do not uninstall RockMap; install the Alpha 6.4 APK over the existing app so saved locations and installed map data remain in place.

## GPS validator

The APK adds a temporary launcher entry named **RockMap GPS Validate**. It is foreground-only and uses Android `LocationManager.GPS_PROVIDER`, the same provider used by RockMap's normal GPS and Save GPS code. It requests no background-location permission.

The validator reports current coordinates, monotonic fix age, Android's horizontal accuracy estimate, GPS altitude, vertical accuracy when Android supplies it, satellite status, stationary repeatability statistics, and optional error against a user-entered known coordinate. GPS altitude is labeled as **WGS84 ellipsoid GPS altitude**; it is not surveyed elevation or a topo/DEM height.

Android's reported horizontal/vertical accuracy values are uncertainty estimates, not guarantees. Stationary scatter measures repeatability, not absolute accuracy. Absolute validation requires a known reference coordinate.

## First validation pass

1. Install over Alpha 6.3. Do not uninstall and do not run a data update unless RockMap itself reports missing data.
2. In normal RockMap, enable **Historic mines / workings — USGS / CGS**, save a mine, and verify the persistent saved marker is visibly red/orange above the brown/gray mine-source point. Tap the saved marker and verify its rich saved details still reopen.
3. Launch **RockMap GPS Validate**, grant Precise location, and test outdoors with clear sky. Stand still and collect at least 20 fixes.
4. If a trustworthy known coordinate is available for the exact point where the phone is placed, enter it as the reference. Use **Copy report** and preserve the resulting diagnostics for review.
5. Repeat with airplane mode enabled. GPS must continue to acquire fixes without network service; this test validates the device/location path, while earlier RockMap tests already validated offline map/data reopening.
6. After the stationary results are reviewed, perform a walking test on a clearly mapped trail/road and compare the blue RockMap position to the physical route.
7. Reboot the phone, enable airplane mode, launch RockMap/GPS Validate, and confirm a fresh GPS fix can still be obtained.
8. Later field checks should include screen off/on, background/foreground recovery, tree cover or canyon conditions when practical, saved-waypoint coordinate persistence, and battery behavior during a realistic outing.

No Alpha 6.4 diagnostic result by itself changes land ownership, mining-claim, access, or collecting-legality semantics.
