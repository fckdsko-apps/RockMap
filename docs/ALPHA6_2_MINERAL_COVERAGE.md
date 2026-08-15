# Alpha 6.2 — mineral coverage expansion

Alpha 6.2 keeps the proven Alpha 6.1 USGS MRDS index unchanged and adds a separate, tiny official-source Colorado locality supplement for gemstone searches that MRDS underrepresents. It also reports mapped land-management type when an individual mineral marker is tapped.

## Search data

The primary mineral-search database remains the U.S. Geological Survey Mineral Resources Data System (MRDS). Alpha 6.2 adds exactly three reviewed locality references rather than importing a broad, redundant dataset:

- Mount Antero Aquamarine Locality — Colorado Geological Survey (CGS)
- Mount White Aquamarine Locality — Colorado Geological Survey (CGS)
- Crystal Peak–Lake George Amazonite Locality — U.S. Geological Survey publication

The supplement is intended to fill high-value exact-search gaps, especially amazonite and aquamarine. It is not a comprehensive rockhounding-locality catalog.

The locality coordinates are named-area reference points. They are not specimen pockets, surveyed property boundaries, access points, or collecting-permission determinations. Search results label their evidence type, source, and location precision.

## Land status in mineral details

When an individual mineral result is tapped on the map, RockMap queries the already-installed BLM Colorado Surface Management Agency layer at that coordinate and reports a compact line such as:

`Land: BLM — Bureau of Land Management`

or

`Land: PRI — Private`

The informational land lookup remains available even when the colored land-status layer is visually hidden. This is management/status mapping only; it is not a parcel survey or legal boundary and does not establish collecting permission.

## Data-update behavior

Alpha 6.2 uses the immutable release tag `rockmap-minerals-alpha6-2-20260815-test1`.

The release manifest must reuse the exact Alpha 6.1 `style`, `base`, `land`, `claims`, and `minerals` entries and append only the required `mineral_localities` gzip JSON index. Therefore an existing Alpha 6.1 installation should download only the tiny new locality file plus the new manifest. The old MRDS file must not be rebuilt or renamed.

## Search behavior

The same **Minerals** control searches MRDS and the official locality supplement together. Exact records win before gemstone parent-mineral fallback. Therefore an `amazonite` search can return the official Crystal Peak locality directly instead of only falling back to microcline. All matches remain accessible; dense map results stay clustered and the results list remains progressively revealed.

## Safety

Mineral/locality records are research leads. They do not establish current ownership, public access, claim validity, exact specimen position, or permission to collect. Mining-claim and land-status caveats from earlier RockMap test releases remain in force.

Do not uninstall RockMap before installing the Alpha 6.2 APK; uninstalling can remove internal offline data and saved state.
