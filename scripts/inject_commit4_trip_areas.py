#!/usr/bin/env python3
"""Commit 4A: make Prospecting Areas first-class in Layers and Trips.

This pass intentionally reuses the existing Prospecting Area storage/visibility model and the
existing Trip item schema. A Trip stores an area reference (area:<id>) plus a display coordinate;
it does not duplicate polygon geometry or introduce a database migration. Opening that Trip stop
resolves the original saved area and focuses its full polygon on the map.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"


def read() -> str:
    return MAIN.read_text(encoding="utf-8")


def replace_once(marker: str, old: str, new: str, label: str) -> None:
    text = read()
    if marker in text:
        print(f"{label}: already present")
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one match in {MAIN.relative_to(ROOT)}, found {count}"
        )
    MAIN.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: injected")


def replace_in_region_once(marker: str, region_start: str, region_end: str,
                           old: str, new: str, label: str) -> None:
    text = read()
    if marker in text:
        print(f"{label}: already present")
        return
    start = text.find(region_start)
    if start < 0:
        raise RuntimeError(f"{label}: region start missing: {region_start.strip()}")
    end = text.find(region_end, start + len(region_start))
    if end < 0:
        raise RuntimeError(f"{label}: region end missing: {region_end.strip()}")
    region = text[start:end]
    count = region.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match inside region, found {count}")
    MAIN.write_text(text[:start] + region.replace(old, new, 1) + text[end:], encoding="utf-8")
    print(f"{label}: injected")


def main() -> int:
    if not MAIN.is_file():
        raise RuntimeError(f"required file missing: {MAIN.relative_to(ROOT)}")

    original = read()
    try:
        replace_once(
            "commit4-prospecting-area-visibility-import",
            '''import com.rockmap.app.field.ProspectingAreaCreator;\n''',
            '''import com.rockmap.app.field.ProspectingAreaCreator;\nimport com.rockmap.app.field.ProspectingAreaVisibility; // marker: commit4-prospecting-area-visibility-import\n''',
            "Prospecting Area visibility import",
        )

        replace_in_region_once(
            "commit4-layers-prospecting-area-checkbox",
            "    private void showLayers() {",
            "    private void addGeologyLegend(",
            '''        CheckBox saved = checkbox("Saved Locations", mapController.isWaypointsVisible());\n        CheckBox tracks = checkbox("Tracks", FieldMapState.tracksVisible(this));\n        CheckBox fieldRecords = checkbox("Field Records", FieldMapState.fieldRecordsVisible(this));\n''',
            '''        CheckBox saved = checkbox("Saved Locations", mapController.isWaypointsVisible());\n        CheckBox tracks = checkbox("Tracks", FieldMapState.tracksVisible(this));\n        CheckBox prospectingAreas = checkbox("Prospecting Areas", FieldMapState.areasVisible(this)); // marker: commit4-layers-prospecting-area-checkbox\n        CheckBox fieldRecords = checkbox("Field Records", FieldMapState.fieldRecordsVisible(this));\n''',
            "Prospecting Areas Layers checkbox",
        )

        replace_in_region_once(
            "commit4-layers-prospecting-area-row",
            "    private void showLayers() {",
            "    private void addGeologyLegend(",
            '''        box.addView(saved);\n        box.addView(tracks);\n        box.addView(fieldRecords);\n''',
            '''        box.addView(saved);\n        box.addView(tracks);\n        box.addView(prospectingAreas); // marker: commit4-layers-prospecting-area-row\n        box.addView(fieldRecords);\n''',
            "Prospecting Areas Layers placement",
        )

        replace_in_region_once(
            "commit4-layers-prospecting-area-apply",
            "    private void showLayers() {",
            "    private void addGeologyLegend(",
            '''                    FieldMapState.setTracksVisible(MainActivity.this, tracks.isChecked());\n                    FieldMapState.setFieldRecordsVisible(MainActivity.this, fieldRecords.isChecked());\n''',
            '''                    FieldMapState.setTracksVisible(MainActivity.this, tracks.isChecked());\n                    FieldMapState.setAreasVisible(MainActivity.this, prospectingAreas.isChecked()); // marker: commit4-layers-prospecting-area-apply\n                    FieldMapState.setFieldRecordsVisible(MainActivity.this, fieldRecords.isChecked());\n''',
            "Prospecting Areas Layers apply",
        )

        replace_in_region_once(
            "commit4-trip-add-prospecting-area",
            "    private void showTripDetail(TripEntity trip) {",
            "    private String tripItemLabel(",
            '''            LinearLayout addRow = new LinearLayout(this);\n            addRow.setOrientation(LinearLayout.HORIZONTAL);\n            Button addPlace = smallActionButton("Add place / GPS");\n            Button addMarker = smallActionButton("Add Saved Location");\n            addRow.addView(addPlace, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));\n            addRow.addView(addMarker, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));\n            box.addView(addRow);\n''',
            '''            LinearLayout addRow = new LinearLayout(this);\n            addRow.setOrientation(LinearLayout.HORIZONTAL);\n            Button addPlace = smallActionButton("Add place / GPS");\n            Button addMarker = smallActionButton("Add Saved Location");\n            addRow.addView(addPlace, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));\n            addRow.addView(addMarker, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));\n            box.addView(addRow);\n            Button addArea = smallActionButton("Add Prospecting Area"); // marker: commit4-trip-add-prospecting-area\n            box.addView(addArea, new LinearLayout.LayoutParams(\n                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n''',
            "Trip Add Prospecting Area control",
        )

        replace_in_region_once(
            "commit4-trip-add-prospecting-area-listener",
            "    private void showTripDetail(TripEntity trip) {",
            "    private String tripItemLabel(",
            '''            addMarker.setOnClickListener(v -> {\n                dialog.dismiss();\n                showAddSavedMarkerToTrip(trip);\n            });\n            export.setEnabled(!items.isEmpty());\n''',
            '''            addMarker.setOnClickListener(v -> {\n                dialog.dismiss();\n                showAddSavedMarkerToTrip(trip);\n            });\n            addArea.setOnClickListener(v -> {\n                dialog.dismiss();\n                showAddProspectingAreaToTrip(trip);\n            }); // marker: commit4-trip-add-prospecting-area-listener\n            export.setEnabled(!items.isEmpty());\n''',
            "Trip Add Prospecting Area listener",
        )

        replace_in_region_once(
            "commit4-trip-area-map-resolution",
            "    private void showTripItemDetail(TripEntity trip, TripItemEntity item,",
            "    private void showTripPickerForPlace(",
            '''        showMap.setOnClickListener(v -> {\n            itemDialog.dismiss();\n            if (parentDialog != null) parentDialog.dismiss();\n            Location target = new Location("trip-item");\n            target.setLatitude(item.latitude);\n            target.setLongitude(item.longitude);\n            mapController.centerOn(target);\n        });\n''',
            '''        showMap.setOnClickListener(v -> {\n            if ("prospecting_area".equals(item.sourceType)) {\n                long areaId = tripProspectingAreaId(item);\n                FieldDatabase.Area area = areaId > 0L ? FieldDatabase.get(this).getArea(areaId) : null;\n                itemDialog.dismiss();\n                if (parentDialog != null) parentDialog.dismiss();\n                if (area == null) {\n                    new AlertDialog.Builder(this)\n                            .setTitle("Prospecting Area not found")\n                            .setMessage("This trip still contains the saved stop, but the linked Prospecting Area no longer exists on this device.")\n                            .setPositiveButton("Trip", (d, w) -> showTripDetail(trip))\n                            .show();\n                    return;\n                }\n                ProspectingAreaVisibility.showOnly(this, area.id);\n                FieldMapState.setAreasVisible(this, true);\n                FieldMapState.clearViewedMapContext(this);\n                FieldMapState.Bounds bounds = FieldMapState.Bounds.fromPoints(area.points);\n                if (bounds != null) FieldMapState.requestFocusBounds(this, bounds);\n                FieldMapController.refreshLayerVisibility(this);\n                // marker: commit4-trip-area-map-resolution\n                return;\n            }\n            itemDialog.dismiss();\n            if (parentDialog != null) parentDialog.dismiss();\n            Location target = new Location("trip-item");\n            target.setLatitude(item.latitude);\n            target.setLongitude(item.longitude);\n            mapController.centerOn(target);\n        });\n''',
            "Trip Prospecting Area map resolution",
        )

        replace_once(
            "commit4-trip-area-picker-method",
            '''    private void showAddSavedMarkerToTrip(TripEntity trip) {\n''',
            '''    private long tripProspectingAreaId(TripItemEntity item) {\n        if (item == null || item.sourceRef == null\n                || !"prospecting_area".equals(item.sourceType)) return -1L;\n        String ref = item.sourceRef.trim();\n        if (!ref.startsWith("area:")) return -1L;\n        try {\n            long id = Long.parseLong(ref.substring("area:".length()));\n            return id > 0L ? id : -1L;\n        } catch (NumberFormatException ignored) {\n            return -1L;\n        }\n    }\n\n    private void showAddProspectingAreaToTrip(TripEntity trip) {\n        if (trip == null) return;\n        List<FieldDatabase.Area> areas = FieldDatabase.get(this).listAreas();\n        if (areas.isEmpty()) {\n            new AlertDialog.Builder(this)\n                    .setTitle("No Prospecting Areas")\n                    .setMessage("Create or import a Prospecting Area first, then add it to this trip.")\n                    .setPositiveButton("Trip", (d, w) -> showTripDetail(trip))\n                    .show();\n            return;\n        }\n\n        ArrayList<ActionListItem> rows = new ArrayList<>();\n        for (FieldDatabase.Area area : areas) {\n            rows.add(new ActionListItem(\n                    area.name,\n                    GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(area.points))\n                            + " · " + area.points.size() + " vertices",\n                    "ADD"));\n        }\n        showActionListDialog(\n                "Add Prospecting Area to " + trip.name,\n                "Tap a saved Prospecting Area to link it to this trip. The trip references the original saved polygon rather than copying its geometry.",\n                rows, null, null, null, null, "Trip", (d, w) -> showTripDetail(trip),\n                which -> {\n                    FieldDatabase.Area area = areas.get(which);\n                    FieldMapState.Bounds bounds = FieldMapState.Bounds.fromPoints(area.points);\n                    if (bounds == null) {\n                        showMessage("That Prospecting Area does not have usable polygon geometry.");\n                        showTripDetail(trip);\n                        return;\n                    }\n                    double latitude = (bounds.minLat + bounds.maxLat) / 2d;\n                    double longitude = (bounds.minLon + bounds.maxLon) / 2d;\n                    long now = System.currentTimeMillis();\n                    TripItemEntity item = new TripItemEntity(\n                            trip.id, boundedText(area.name, 500), "Prospecting Area",\n                            boundedText(GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(area.points))\n                                    + " · " + area.points.size() + " vertices", 2_000),\n                            latitude, longitude, "",\n                            "prospecting_area", "area:" + area.id, 0, now);\n                    tripRepository.addItem(item, added -> {\n                        showMessage(area.name + " added to " + trip.name + ".");\n                        showTripDetail(trip);\n                    });\n                });\n    } // marker: commit4-trip-area-picker-method\n\n    private void showAddSavedMarkerToTrip(TripEntity trip) {\n''',
            "Trip Prospecting Area picker",
        )

        replace_in_region_once(
            "commit4-trip-delete-copy",
            "    private void confirmDeleteTrip(TripEntity trip) {",
            "    private void showTripExportPicker(",
            '''                .setMessage(trip.name + " and its trip list will be deleted. Original Saved Locations are not deleted.")\n''',
            '''                .setMessage(trip.name + " and its trip list will be deleted. Original Saved Locations and Prospecting Areas are not deleted.") // marker: commit4-trip-delete-copy\n''',
            "Trip delete copy for linked areas",
        )

        final = read()
        required = (
            'checkbox("Prospecting Areas", FieldMapState.areasVisible(this))',
            'FieldMapState.setAreasVisible(MainActivity.this, prospectingAreas.isChecked())',
            'smallActionButton("Add Prospecting Area")',
            '"prospecting_area", "area:" + area.id',
            'ProspectingAreaVisibility.showOnly(this, area.id)',
            'FieldMapState.Bounds.fromPoints(area.points)',
            'tripProspectingAreaId(TripItemEntity item)',
        )
        missing = [token for token in required if token not in final]
        if missing:
            raise RuntimeError("Commit 4A postcondition missing: " + ", ".join(missing))

        # Guard the intended no-migration/no-duplication scope. MainActivity may reference an area,
        # but this pass must not alter Room/Field DB schemas or serialize polygon vertices into a
        # Trip item.
        forbidden = ("CREATE TABLE", "ALTER TABLE", "@Database(", "area_points")
        for token in forbidden:
            if final.count(token) != original.count(token):
                raise RuntimeError("Commit 4A unexpectedly changed schema/geometry-storage token: " + token)

        print("Commit 4A Trip + Prospecting Area integration complete.")
        print("Scope: Layers visibility + linked Trip areas; no database migration or polygon duplication.")
        return 0
    except Exception:
        MAIN.write_text(original, encoding="utf-8")
        print("Commit 4A integration rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
