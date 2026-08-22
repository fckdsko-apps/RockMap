# RockMap P0–P3 — Commit 2: Spatial Research Query

**Original Commit 2:** `Add spatial geology research and area queries`

**UX cleanup commit:** `Improve Commit 2 research navigation and geology UX`

## What this cleanup fixes

The first Commit 2 build proved the geology/search engine worked, but phone testing exposed an information-architecture problem: mineral analysis was buried, Prospecting Areas were difficult to find, point/radius analysis required too much menu knowledge, and raw SGMC polygon output was too verbose and repetitive.

This cleanup keeps the underlying research/export data intact while changing the default experience around the user’s task.


## Entry point

The existing main-map **Minerals** control remains in place for this cleanup because it is part of the current RockMap preflight/regression contract. Tapping it now opens the redesigned **Research** hub, where Mineral Evidence, Geology, and Area Research are separate first-level sections. Field also has a direct **Research** entry. This patch does not weaken the existing preflight guard merely to rename a button.

## Research information architecture

Research is split into three explicit jobs:

### Mineral Evidence

- Search minerals / materials
- Analyze visible mineral evidence

These actions reuse the existing RockMap mineral/locality/evidence index and area analyzer. They are no longer hidden behind geology actions.

### Geology

- Search geology
- Explore visible-map geology
- Analyze around a point

Point analysis offers direct sources instead of a hidden coordinate/radius dialog:

- current map center;
- current precise GPS;
- saved Field Record;
- entered coordinates.

The radius is then chosen explicitly: at point, 250 m, 500 m, 1 km, 5 km, or custom.

### Area Research

- Prospecting Areas
- Analyze current map area

A saved Prospecting Area queries its actual polygon for geology. Mineral evidence and historic activity remain distinct source families and are exposed immediately as continuation actions rather than being mixed into a misleading single “probability” result.

## Prospecting Areas in Field

Field now has an explicit **Prospecting Areas** entry. It is no longer necessary to know that saved areas were previously buried under Measure.

The Prospecting Areas screen provides:

- Create area on map
- Saved Prospecting Areas
- Open an area
- Analyze this area
- Show on map
- Delete

The existing Field export hub remains the export owner for saved areas and Research results.

Field Records now expose **Research this location**. RockMap asks for a radius rather than silently assuming 1 km.

## Geology result presentation

The source data remains polygon-level and complete, but the normal UI no longer dumps one row for every source polygon.

RockMap now groups repeated polygons into user-facing geologic units. A result such as Sawatch Quartzite appears once with:

- concise rock/lithology;
- concise most-specific age;
- number of mapped source areas.

The underlying polygons remain separate in the map result and GeoJSON export.

Full SGMC hierarchy, labels, references, unit/source IDs and provenance are available only through **Technical & source details**.

Age paths are shortened for the normal UI (for example, `Pennsylvanian` instead of the complete hierarchy). Raw values remain unchanged in export.

## Complete result browsing

Search, visible-area, saved-polygon and point/radius geology queries no longer use the former 250/500/1000/3000 display/query caps. A zero limit means no artificial result limit in the local SQLite query. Repeated polygons are grouped for usability rather than silently discarded.

## Additional-resource download policy

The former Research-screen live FeatureServer install/refresh path is disabled.

RockMap must not begin an additional geology-resource download unless the resource has a known declared byte size that can be shown to the user before confirmation. New geology installs/updates are to be distributed as versioned RockMap offline data-pack assets with a declared byte count and SHA-256, using the same user-confirmed update model as the existing offline pack system.

An already installed local geology snapshot remains usable offline. Mineral Evidence remains usable even when geology is not installed.

## Source / scale

The currently installed Commit 2 geology snapshot retains its source identity:

- USGS State Geologic Map Compilation (SGMC)
- DOI `10.5066/F7WH2N65`
- Colorado source map scale: 1:500,000

The app does not relabel this source as the separate 2026 GeMS release.

Source scale and detailed provenance remain available in technical details/export so generalized statewide mapping is not presented as site-scale precision.

## Export

UI simplification does not simplify the exported dataset. The existing Field → Export data pipeline continues to export the complete underlying Research result, including source polygon geometry and source attributes.

## Regression boundaries

This cleanup does not change:

- `MainActivity.locate()`;
- `centerOnGpsFix(...)`;
- Save GPS behavior;
- track start/recording commands;
- Android permissions declared in the manifest;
- signing configuration;
- `.github` workflows.

A new additive precise-GPS Research action reuses `LocationRepository.requestFreshPrecise(...)`. Existing center/save permission actions retain their existing behavior; the permission handler adds a separate Research-GPS branch rather than replacing those flows.
