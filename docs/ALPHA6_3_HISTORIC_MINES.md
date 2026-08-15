# RockMap Alpha 6.3 — Historic mines / workings

Alpha 6.3 is the final geology/UI feature pass before Alpha 6.4 GPS and field-readiness validation.
It does not publish another geology data pack. The APK reuses the already-verified Alpha 6.2.1 cumulative release `rockmap-minerals-alpha6-2-1-20260815-test1` exactly.

## Historic mines / workings overlay

Layers gains one toggle: **Historic mines / workings — USGS / CGS**.

The overlay is built offline from mine/property records already stored in the Alpha 6.2.1 `mineral_evidence` index:

- **USGS MAS/MILS OFR 03-090** — historic mine/mineral-property records and documented commodities.
- **CGS MS-17** — industrial/nonmetallic mine or permit locations and documented materials/products.
- **CGS/USFS ON-008-04D** — abandoned-mine/opening inventory. These points locate mine features; they do not automatically prove a mineral occurs there.

MRDS, CGS B-40, and the reviewed official-locality supplement are not drawn as mine points merely because they contain mineral evidence. They can instead appear as nearby documented mineral evidence when a mine is inspected.

The overlay clusters dense areas until the user zooms in. Property/mine records and USFS abandoned openings use different point styling, but remain under one toggle.

## Mine tap behavior

Tapping a mine opens its source record. If several rendered mine records overlap the tap, RockMap shows a selector instead of silently choosing one.

Each mine detail includes, when the source provides it:

- **Mapped coordinates (source record)** — latitude/longitude from the USGS/CGS record. These are not a phone GPS fix.
- Mine/working type.
- Documented minerals/materials, or the explicit statement **None in this source**.
- Commodities/products.
- District, model, rock context, and source status when present.
- Mapped land management from BLM Colorado Surface Management Agency, with the land-source reliability warning.
- Source record ID.
- Exact source/database title and a compact source-specific reliability statement.
- Source notes retained by Alpha 6.2.1.

RockMap also checks for **Nearby documented mineral evidence** within 100 m from MRDS, CGS B-40, and reviewed official-locality records. Nearby evidence is explicitly labelled **not necessarily the same working**; proximity is not treated as proof that the mineral came from that mine.

## Saving and field notes

**Save / note** creates a persistent saved red marker at the source-record coordinate and allows **Field observations** to be added immediately.
The saved marker retains the mine's mapped coordinates, source ID, source/reliability, land status, documented materials/commodities, nearby direct mineral evidence, and the user's observations. Tapping the saved red marker later reopens that information. Existing GeoJSON export continues to preserve the waypoint coordinate and saved notes.

## Safety semantics

Historic and abandoned-mine coordinates can be approximate and site conditions can change. **Do not enter abandoned openings or workings.** A mine point is a research lead, not a statement about safe access, ownership, current claim validity, or collecting permission. Missing mineral information means only that the selected source did not document it.

BLM Surface Management Agency data remains management/status mapping, not parcel or legal-boundary surveying.

## Installation / acceptance

**Do not uninstall RockMap.** Install Alpha 6.3 over Alpha 6.2.1 so the existing offline map data and saved locations remain intact.

Because Alpha 6.3 reuses the exact Alpha 6.2.1 data release, no new geology download is required after installation.

Focused acceptance checks:

1. Turn on **Historic mines / workings — USGS / CGS** in Layers and confirm clustered mine points appear.
2. Tap a cluster and confirm it expands.
3. Tap a MAS/MILS, MS-17, and USFS AML point where available. Confirm mapped coordinates, source, reliability, land, and honest mineral/material wording.
4. In a dense location, confirm a tap with multiple mine records gives a selector rather than an arbitrary record.
5. Save a mine with a field observation, turn the mine overlay off, and tap the saved red marker. Confirm the mine details and observation remain.

After Alpha 6.3 is accepted, feature development pauses and **Alpha 6.4** begins GPS/offline field-readiness validation before RockMap is relied on in the field.
