# RockMap Privacy Policy

**Last updated: August 22, 2026**

RockMap is an offline-first Android field-map and research application. This policy explains how RockMap accesses, uses, stores, and shares user data.

## Developer and privacy contact

RockMap is developed and distributed as the **RockMap** project by **fckdsko-apps**. Privacy questions or requests can be submitted through the RockMap repository inquiry mechanism at:

https://github.com/fckdsko-apps/RockMap/issues

Do not include sensitive personal information in a public issue. If RockMap is distributed through Google Play, the developer contact shown on the RockMap Google Play listing may also be used for privacy inquiries.

## Location data

RockMap can access approximate or precise device location when the user invokes GPS features.

- Approximate location can be used to show the user's current position on the map.
- Precise location is used when the user chooses to save a field waypoint or richer field record from device GPS.
- If the user explicitly starts **Track Recording**, RockMap runs a visible Android location foreground service so the track can continue while the user returns to the RockMap map or temporarily places the app in the background. Android displays a persistent track-recording notification until the user stops the track.
- RockMap does **not** request Android `ACCESS_BACKGROUND_LOCATION` permission.
- RockMap does **not** send the user's GPS position, recorded tracks, saved waypoints, field records, trip coordinates, sample notes, or measurements to RockMap-operated servers.

Location access is optional. Offline maps and non-GPS features remain available when location permission is denied.

## Saved markers, field records, tracks, areas, trips, notes, photos, and imported files

Saved locations, field records, recorded track points, saved polygon areas, sample identifiers, trip plans, trip stops, names, notes, and related coordinates are stored locally in RockMap's private app storage. RockMap does not operate an account system or RockMap cloud-sync service for this information.

If the user attaches a photo to a field record, RockMap stores the Android document URI selected by the user and retains read access when the selected provider permits it. The original photo remains managed by the storage/photo provider selected by the user; RockMap does not automatically upload it.

When the user explicitly exports data, Android's system document picker lets the user choose where to save the file. When the user explicitly imports a supported **GPX, KML, GeoJSON, or RockMap file**, RockMap reads the selected file to add supported locations, tracks, or polygon areas to local app storage. Imports are additive and do not automatically delete existing RockMap data. RockMap does not automatically upload exported or imported content to RockMap-operated servers.

If the user chooses a cloud-storage or sharing provider in Android's document/share UI, that provider receives or serves the selected file under the provider's own terms and privacy practices.

## Local safety acknowledgment token

Before RockMap can be used, the current release requires acknowledgment of RockMap's Safety & Data Limitations notice unless the user previously chose not to show the current disclosure version again.

RockMap stores a randomly generated local acknowledgment token together with the disclosure version, last acknowledgment time, acknowledgment count, and reminder preference. This token is stored only in RockMap's private app preferences. It is not an account token, authentication credential, analytics identifier, advertising identifier, or cross-app tracking identifier, and RockMap does not send it to RockMap-operated servers. The safety preferences are excluded from Android backup/device transfer. The token is not a server-verified, tamper-proof, or legal-signature record and can be removed by clearing RockMap app data.

## Android backup and device transfer

RockMap allows Android's standard backup and device-transfer systems to include eligible local databases containing saved markers, trips, and field data. Depending on the user's device, account, and backup settings, this may cause that data to be stored or transferred by the Android platform or backup provider.

Downloadable offline map files, downloaded Colorado geology snapshots, reproducible Research-result files, and the local safety-acknowledgment preferences are excluded from Android backup/device transfer. These resources can be regenerated or downloaded again and are not treated as user-created field records. RockMap does not receive or control copies held by the Android backup provider.

## Offline-data updates and network data

RockMap is designed to work offline, but the user can choose to check for and download updated offline map or research data.

For release-managed RockMap data, the app retrieves a small update manifest first. The manifest declares the resource filename, exact byte count, and SHA-256 checksum. RockMap displays the relevant download size before the user authorizes the additional resource download. Downloaded files are not activated unless their byte counts and SHA-256 values match the manifest.

Queryable Colorado geology is distributed as a versioned RockMap SQLite snapshot built from the U.S. Geological Survey State Geologic Map Compilation (SGMC) Colorado polygons. The Android app does not perform an unknown-size statewide SGMC scrape when the user installs geology. Instead, the RockMap data-build process obtains and validates the Colorado source records, creates a fixed geology database, and publishes an immutable compressed asset together with its exact compressed download size, installed size, and SHA-256 values. The app shows those sizes before the user chooses **Download**, verifies both the compressed asset and the installed SQLite database, validates the expected Colorado record count/schema, and only then activates the new snapshot. If a geology update fails verification, an existing working snapshot remains available.

After the Colorado geology snapshot is installed, RockMap geology search and spatial queries operate on the local database. GPS positions, saved Prospecting Areas, Field Record coordinates, search terms, tracks, notes, photos, and other user-created data are not sent to USGS as part of those local geology queries.

Update requests use HTTPS. The update host and its infrastructure, including GitHub when GitHub-hosted releases are used, may receive ordinary network information such as IP address, request time, and standard HTTP request metadata under that provider's own privacy practices. The external USGS service is contacted by the RockMap data-build process when a new geology snapshot is produced; normal app geology queries do not contact USGS after the fixed snapshot is installed.

RockMap does not include advertising SDKs or analytics SDKs in the current release and does not use update requests to transmit saved markers, field records, tracks, trips, notes, acknowledgment tokens, photos, or GPS coordinates.

## Public map and research sources

RockMap uses public or publicly distributed geographic and research datasets, including OpenStreetMap/Protomaps-derived basemap data and selected data from USGS, BLM, Colorado Geological Survey, Colorado Department of Transportation, and U.S. Forest Service sources. Queryable Colorado geology uses the USGS State Geologic Map Compilation (SGMC), DOI `10.5066/F7WH2N65`, as a Colorado-only local snapshot. The Safety & Data Limitations notice inside the app lists the source families in more detail.

These datasets describe places and resources; RockMap does not upload user location to those source agencies when the user views or queries installed offline data.

## Data sharing and sale

RockMap does not sell user data. RockMap does not share saved location, field, track, sample, or trip data with third parties except when the user explicitly exports/shares a file, selects a file/photo through an external provider, or when Android's backup/device-transfer functionality processes eligible local app data according to the user's device and account settings. Network providers may process ordinary connection metadata during user-requested update checks/downloads.

## Data security

RockMap stores working data in Android app-private storage. Network update URLs must use HTTPS, cleartext traffic is disabled, and release-managed downloaded data are checked against declared size and SHA-256 values before activation. Colorado geology additionally undergoes SQLite integrity, schema, Colorado-state, geometry, and record-count validation before activation. No storage or transmission method can be guaranteed completely secure.

## Data retention and deletion

Saved markers, field records, tracks, polygon areas, and trips remain on the device until the user deletes them in RockMap, clears RockMap app data, or uninstalls the app. Downloaded Colorado geology remains until it is replaced by a successfully verified newer snapshot, app data is cleared, or RockMap is uninstalled. The most recent Research geology result may be replaced or cleared and is excluded from Android backup/device transfer. Attached-photo references remain until the associated field record is changed/deleted or app data is cleared; the original selected photo is controlled by its storage provider. The local safety acknowledgment remains until RockMap app data is cleared/uninstalled or a later disclosure version replaces it. Exported files remain wherever the user chose to save them and must be deleted from that location. Android backups are subject to the backup provider's own retention/deletion rules.

RockMap has no server-side user accounts and therefore no RockMap account data to delete from a RockMap server.

## Children

RockMap is a general-purpose field-map and research utility and is not designed specifically for children. RockMap does not knowingly operate a service that collects children's personal information on RockMap servers.

## Changes to this policy

This policy may be updated when RockMap's features, data handling, third-party services, or distribution requirements change. The current policy identifies its latest revision date.
