# RockMap Alpha 6.2.1 — Expanded Colorado Mineral Evidence

Alpha 6.2.1 keeps the proven Alpha 6.2 basemap, BLM surface-management layer, MLRS claims,
USGS MRDS index, and three reviewed official gemstone/locality records unchanged. It adds one
new compact `mineral_evidence` JSON.GZ index. The new hard-data file has a strict **15 MB**
compressed ceiling.

## Evidence sources

RockMap keeps these evidence classes separate instead of treating every mine or district as a
mineral occurrence.

- **USGS MAS/MILS — OFR 03-090.** Colorado mine, prospect, occurrence, property, primary
  commodity, district, status, and revised coordinate data. The USGS metadata says 16,151
  Colorado MAS records were extracted for the report and warns that many fields and locations
  are old or inaccurate. In-app reliability: `Historic site data; location and status may be approximate or outdated.`
- **CGS ON-B-40D / B-40.** More than 2,000 digitized radioactive-mineral occurrence records
  derived from the 1978 Bulletin 40 compilation, including mineralogy, host rock, geology and
  references where present. In-app reliability: `Documented occurrence; mapped precision and 1978-era details may vary.`
- **CGS MS-17 (2022 update of IS-62).** The current CGS package rechecks the older statewide
  nonmetallic/industrial-mineral mine inventory against georeferenced source plates and includes
  updated GIS/spreadsheet data. In-app reliability: `Updated inventory; historic mine locations and activity may still be dated.`
- **CGS/USFS ON-008-04D.** Historic U.S. Forest Service abandoned-mine inventory. Approximately
  18,000 mine-related features were inventoried during the 1990s. These points are mine-feature
  evidence, not proof that a searched mineral occurs unless the source explicitly names it.
  In-app reliability: `Field inventory; locations vary and site conditions may have changed.`
- **CGS ON-007-08D Historic Metal Mining Districts.** Broad district polygons plus CGS district
  reviews. RockMap uses a representative display point and literal mineral/commodity terms from
  the CGS district review. District boundaries are subjective and intended for approximately
  1:150,000 use. In-app reliability: `District evidence; boundaries are subjective, approximate 1:150,000 areas.`

The existing **USGS MRDS** and official CGS/USGS locality records remain separate records with
their own source-specific reliability text.

## Source citation rule

Every mineral-evidence point carries its own source code/title, evidence type, location-precision
note, and **source-specific reliability**. The marker detail shows compact `Source:` and
`Reliability:` lines. A broad district result is never presented as a specimen-level point, and a
mine inventory point is never silently converted into a documented mineral occurrence.

## Mindat

**Mindat is not bundled in Alpha 6.2.1.** Its API requires approved account/API access and is
currently subject to CC BY-NC-SA 4.0 for noncommercial use. RockMap does not scrape Mindat.
A future Mindat connector would require an approved key and explicit license compatibility for
whatever distribution model RockMap ultimately uses.

## Saved mineral markers

A mineral result saved as a red RockMap marker preserves the full saved evidence block: searched
term, match reason, minerals/materials, commodities, district/model/rock context when available,
evidence type, precision, land-management snapshot, source citation, source-specific reliability,
and source note. Tapping the **saved red marker** on the map now opens that saved rich information
instead of falling through to the generic coordinate/land popup.

Existing ordinary GPS/manual markers remain unchanged. Existing previously-saved mineral markers
will reopen whatever detail was stored in their notes at the time they were saved; newly saved
Alpha 6.2.1 mineral markers contain the expanded provenance block.

## Safety / interpretation

- Search evidence is a research lead, not ownership, access, claim status, or collecting permission.
- BLM surface-management mapping is management/status context, not a parcel survey or legal boundary.
- Historic mine status and abandoned-mine conditions may be stale.
- District evidence applies to a broad approximate area, not the displayed point itself.
- **Do not uninstall RockMap** to update. Install the APK over the current app so saved locations and
  downloaded data remain in place.
