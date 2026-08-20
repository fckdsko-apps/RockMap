# RockMap Privacy Policy

**Last updated: August 19, 2026**

RockMap is an offline-first Android field-map and research application. This policy explains how RockMap accesses, uses, stores, and shares user data.

## Developer and privacy contact

RockMap is developed and distributed as the **RockMap** project by **fckdsko-apps**. Privacy questions or requests can be submitted through the RockMap repository inquiry mechanism at:

https://github.com/fckdsko-apps/RockMap/issues

Do not include sensitive personal information in a public issue. If RockMap is distributed through Google Play, the developer contact shown on the RockMap Google Play listing may also be used for privacy inquiries.

## Location data

RockMap can access approximate or precise device location while the app is in use.

- Approximate location can be used to show the user's current position on the map.
- Precise location is used when the user chooses to save a field waypoint from device GPS.
- RockMap does **not** request background location permission.
- RockMap does **not** send the user's GPS position, saved waypoints, trip coordinates, or notes to RockMap-operated servers.

Location access is optional. Offline maps and non-GPS features remain available when location permission is denied.

## Saved markers, trips, notes, and imported files

Saved locations, trip plans, trip stops, names, notes, and related coordinates are stored locally in RockMap's private app storage. RockMap does not operate an account system or RockMap cloud-sync service for this information.

When the user explicitly exports data, Android's system document picker lets the user choose where to save the file. When the user explicitly imports a supported file, RockMap reads the selected file to add locations to local app storage. RockMap does not automatically upload exported or imported content to RockMap-operated servers.

If the user chooses a cloud-storage or sharing provider in Android's document/share UI, that provider receives the selected file under the provider's own terms and privacy practices.

## Local safety acknowledgment token

Before RockMap can be used, the current release requires acknowledgment of RockMap's Safety & Data Limitations notice unless the user previously chose not to show the current disclosure version again.

RockMap stores a randomly generated local acknowledgment token together with the disclosure version, last acknowledgment time, acknowledgment count, and reminder preference. This token:

- is stored in RockMap's private app preferences on the device;
- is not an account token or authentication credential;
- is not sent to RockMap-operated servers;
- is not used for advertising, analytics, profiling, or cross-app tracking; and
- is excluded from Android cloud backup and device transfer so it remains local to the current app installation/device context; and
- is not a server-verified, tamper-proof, or legal-signature record and can be removed by clearing RockMap app data.

If the safety disclosure materially changes, RockMap can increment the disclosure version and require a new acknowledgment.

## Android backup and device transfer

RockMap allows Android's standard backup and device-transfer systems to include the local database containing saved markers and trips. Depending on the user's device, account, and backup settings, this may cause that data to be stored or transferred by the Android platform or backup provider. Downloadable offline map files and the local safety-acknowledgment preferences are excluded from Android backup/device transfer.

RockMap does not receive or control copies held by the Android backup provider.

## Offline-data updates and network data

RockMap is designed to work offline, but the user can choose to check for and download updated offline map or research data. RockMap may retrieve a small update manifest first so it can calculate and display the expected resource size. Before additional data assets are downloaded, RockMap displays the estimated additional download and the maximum possible pack transfer and requires user confirmation.

Update requests use HTTPS. RockMap verifies downloaded data before activation. The update host and its infrastructure, including GitHub when GitHub-hosted releases are used, may receive ordinary network information such as IP address, request time, and standard HTTP request metadata under that provider's own privacy practices.

RockMap does not include advertising SDKs or analytics SDKs in the current release and does not use update requests to transmit saved markers, trips, notes, acknowledgment tokens, or GPS coordinates.

## Public map and research sources

RockMap uses public or publicly distributed geographic and research datasets, including OpenStreetMap/Protomaps-derived basemap data and selected data from USGS, BLM, Colorado Geological Survey, Colorado Department of Transportation, and U.S. Forest Service sources. The Safety & Data Limitations notice inside the app lists the source families in more detail.

These datasets describe places and resources; RockMap does not upload user location to those source agencies when the user views the offline data.

## Data sharing and sale

RockMap does not sell user data. RockMap does not share saved location or trip data with third parties except when the user explicitly exports/shares a file or when Android's backup/device-transfer functionality processes eligible local app data according to the user's device and account settings. Network providers may process ordinary connection metadata during user-requested update checks/downloads.

## Data security

RockMap stores working data in Android app-private storage. Network update URLs must use HTTPS, cleartext traffic is disabled, and downloaded offline-data files are checked against declared size and SHA-256 values before activation. No storage or transmission method can be guaranteed completely secure.

## Data retention and deletion

Saved markers and trips remain on the device until the user deletes them in RockMap, clears RockMap app data, or uninstalls the app. The local safety acknowledgment remains until RockMap app data is cleared/uninstalled or a later disclosure version replaces it. Exported files remain wherever the user chose to save them and must be deleted from that location. Android backups are subject to the backup provider's own retention/deletion rules.

RockMap has no server-side user accounts and therefore no RockMap account data to delete from a RockMap server.

## Children

RockMap is a general-purpose field-map and research utility and is not designed specifically for children. RockMap does not knowingly operate a service that collects children's personal information on RockMap servers.

## Changes to this policy

This policy may be updated when RockMap's features, data handling, third-party services, or distribution requirements change. The current policy identifies its latest revision date.
