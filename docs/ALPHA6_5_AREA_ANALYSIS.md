# RockMap Alpha 6.5 — selected-area mineral evidence heatmaps

Alpha 6.5 is an APK-only field-use feature. It does not publish, replace, or rebuild any
geology/map data. It deliberately reuses the already-verified immutable Alpha 6.2.1 cumulative
data release `rockmap-minerals-alpha6-2-1-20260815-test1`.

## What it adds

From **Minerals**, choose **Analyze visible area** after panning/zooming the map to the area you
want to inspect. RockMap captures the current visible map rectangle, outlines it in orange, and
analyzes the installed offline mineral records inside that rectangle.

The result list inventories explicit mineral/material and commodity terms from the already-
installed MRDS, reviewed locality, and expanded USGS/CGS evidence records. It does not infer a
mineral merely because a site name, district, rock name, or unrelated mine record contains similar
text.

Tap a mineral/material in the area list to display its **Mineral evidence heatmap**. The heatmap
uses the mapped evidence points already stored on the phone and remains fully offline.

At closer zoom levels, small evidence dots appear. Tapping one opens the normal RockMap mineral
record detail with coordinates, mineral/material fields, evidence type, source, source-specific
reliability, land-status context, and Save marker behavior.

## Heatmap semantics

The heatmap is **documented evidence density**, not a probability or prospectivity model.

Point contribution is conservative and source-aware:

- direct USGS MRDS and CGS B-40 occurrence evidence: strongest;
- reviewed official mineral/locality references: very strong;
- CGS MS-17 inventory and USGS MAS/MILS explicit mineral/commodity records: moderate/strong;
- abandoned-mine evidence: weak when an explicit selected mineral/commodity term is actually present;
- broad historic mining-district evidence: weakest.

An explicit mineral/material field contributes more than a commodity-only match from the same
source class. A source record contributes at most once to a particular analyzed mineral term even
if the same term appears in both fields.

Hotter map areas therefore mean denser and/or stronger **installed source evidence nearby**. They
do not mean a numerical chance of finding a specimen, and the heatmap does not predict mineral
continuity between source records.

## Layer and safety behavior

The normal mineral-search clusters and the Alpha 6.5 heatmap are independent overlays. Layers can
show/hide them separately after they have been loaded. Starting a normal search hides an active
heatmap by default; selecting a heatmap hides normal mineral search results by default, avoiding
unreadable overlap. The user can re-enable either in Layers.

BLM surface-management mapping, BLM MLRS claim mapping, historic mines, GPS/current location, and
saved waypoints remain separate systems. The new dynamic layers are inserted below the critical
claim/current-location/saved-location drawing order rather than replacing those layers.

The orange rectangle records which viewport was analyzed. Clearing mineral overlays removes normal
mineral results, the selected-area rectangle, and the heatmap.

## Data and update contract

No new network permission, background location permission, storage permission, data schema, or
download is required. Alpha 6.5 continues to use the Alpha 6.2.1 data manifest and installed files.
Install the Alpha 6.5 APK **over** the existing RockMap installation; do not uninstall first, so the
verified offline map pack and saved locations remain present.

## Interpretation warning

A heatmap hotspot is a research lead only. It is not proof of mineral presence at an unrecorded
point, current land ownership, legal access, claim validity, claim boundaries, or permission to
collect. Lack of heatmap evidence is not proof that a mineral is absent.
