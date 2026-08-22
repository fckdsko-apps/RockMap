package com.rockmap.app.field;

import com.rockmap.app.waypoints.WaypointEntity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Portable exports for user-created Field data. These exports are deliberately generated from
 * local data only; no network access is involved. GeoJSON exports preserve RockMap metadata in
 * feature properties while remaining usable in ordinary GIS software.
 */
public final class FieldExport {
    public static final class TrackData {
        public final FieldDatabase.Track track;
        public final List<GeoMath.Point> points;

        public TrackData(FieldDatabase.Track track, List<GeoMath.Point> points) {
            this.track = track;
            this.points = points == null ? new ArrayList<>() : points;
        }
    }

    private FieldExport() {}

    public static String savedLocationsGeoJson(List<WaypointEntity> waypoints) throws JSONException {
        JSONObject root = baseCollection("savedLocations");
        // Keep schema 1 compatible with MainActivity's existing Saved locations backup importer.
        root.put("rockmapSchema", 1);
        JSONArray features = new JSONArray();
        if (waypoints != null) {
            for (WaypointEntity waypoint : waypoints) {
                if (waypoint != null) features.put(savedLocationFeature(waypoint));
            }
        }
        root.put("features", features);
        return root.toString(2);
    }

    public static String savedLocationsGpx(List<WaypointEntity> waypoints) {
        StringBuilder out = new StringBuilder();
        gpxStart(out);
        if (waypoints != null) {
            for (WaypointEntity waypoint : waypoints) {
                if (waypoint == null) continue;
                out.append("  <wpt lat=\"").append(coord(waypoint.latitude))
                        .append("\" lon=\"").append(coord(waypoint.longitude)).append("\">\n");
                appendXmlElement(out, "name", waypoint.name, 4);
                if (waypoint.notes != null && !waypoint.notes.isEmpty()) {
                    appendXmlElement(out, "desc", waypoint.notes, 4);
                }
                if (waypoint.capturedAt > 0L) appendXmlElement(out, "time", iso(waypoint.capturedAt), 4);
                out.append("  </wpt>\n");
            }
        }
        gpxEnd(out);
        return out.toString();
    }

    public static String tracksGeoJson(List<TrackData> tracks) throws JSONException {
        JSONObject root = baseCollection("tracks");
        JSONArray features = new JSONArray();
        if (tracks != null) {
            for (TrackData data : tracks) {
                if (data != null && data.track != null && data.points.size() >= 2) {
                    features.put(trackFeature(data));
                }
            }
        }
        root.put("features", features);
        return root.toString(2);
    }

    public static String tracksGpx(List<TrackData> tracks) {
        StringBuilder out = new StringBuilder();
        gpxStart(out);
        if (tracks != null) {
            for (TrackData data : tracks) {
                if (data == null || data.track == null || data.points.size() < 2) continue;
                out.append("  <trk>\n");
                appendXmlElement(out, "name", data.track.name, 4);
                out.append("    <trkseg>\n");
                for (GeoMath.Point point : data.points) {
                    out.append("      <trkpt lat=\"").append(coord(point.lat))
                            .append("\" lon=\"").append(coord(point.lon)).append("\">\n");
                    if (Double.isFinite(point.alt)) appendXmlElement(out, "ele", number(point.alt), 8);
                    if (point.time > 0L) appendXmlElement(out, "time", iso(point.time), 8);
                    out.append("      </trkpt>\n");
                }
                out.append("    </trkseg>\n");
                out.append("  </trk>\n");
            }
        }
        gpxEnd(out);
        return out.toString();
    }

    public static String fieldRecordsGeoJson(List<FieldDatabase.FieldRecord> records) throws JSONException {
        JSONObject root = baseCollection("fieldRecords");
        JSONArray features = new JSONArray();
        if (records != null) {
            for (FieldDatabase.FieldRecord record : records) {
                if (record != null) features.put(fieldRecordFeature(record));
            }
        }
        root.put("features", features);
        return root.toString(2);
    }

    public static String fieldRecordsCsv(List<FieldDatabase.FieldRecord> records) {
        StringBuilder out = new StringBuilder();
        out.append("name,category,mineral_material,sample_id,notes,latitude,longitude,altitude_m,accuracy_m,photo_reference,created_at_utc,updated_at_utc\n");
        if (records != null) {
            for (FieldDatabase.FieldRecord record : records) {
                if (record == null) continue;
                csv(out, record.name); out.append(',');
                csv(out, record.category); out.append(',');
                csv(out, record.mineral); out.append(',');
                csv(out, record.sampleId); out.append(',');
                csv(out, record.notes); out.append(',');
                out.append(coord(record.lat)).append(',').append(coord(record.lon)).append(',');
                if (Double.isFinite(record.altitude)) out.append(number(record.altitude));
                out.append(',');
                if (Float.isFinite(record.accuracy) && record.accuracy >= 0f) out.append(number(record.accuracy));
                out.append(',');
                csv(out, record.photoUri); out.append(',');
                csv(out, record.createdAt > 0L ? iso(record.createdAt) : ""); out.append(',');
                csv(out, record.updatedAt > 0L ? iso(record.updatedAt) : "");
                out.append('\n');
            }
        }
        return out.toString();
    }

    public static String areasGeoJson(List<FieldDatabase.Area> areas) throws JSONException {
        JSONObject root = baseCollection("prospectingAreas");
        JSONArray features = new JSONArray();
        if (areas != null) {
            for (FieldDatabase.Area area : areas) {
                if (area != null && area.points != null && area.points.size() >= 3) {
                    features.put(areaFeature(area));
                }
            }
        }
        root.put("features", features);
        return root.toString(2);
    }

    public static String areasKml(List<FieldDatabase.Area> areas) {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
                .append("  <Document>\n")
                .append("    <name>RockMap prospecting areas</name>\n");
        if (areas != null) {
            for (FieldDatabase.Area area : areas) {
                if (area == null || area.points == null || area.points.size() < 3) continue;
                out.append("    <Placemark>\n");
                appendXmlElement(out, "name", area.name, 6);
                if (area.notes != null && !area.notes.isEmpty()) appendXmlElement(out, "description", area.notes, 6);
                out.append("      <Polygon><outerBoundaryIs><LinearRing><coordinates>\n        ");
                for (GeoMath.Point point : area.points) {
                    out.append(coord(point.lon)).append(',').append(coord(point.lat)).append(",0 ");
                }
                GeoMath.Point first = area.points.get(0);
                GeoMath.Point last = area.points.get(area.points.size() - 1);
                if (!samePoint(first, last)) {
                    out.append(coord(first.lon)).append(',').append(coord(first.lat)).append(",0 ");
                }
                out.append("\n      </coordinates></LinearRing></outerBoundaryIs></Polygon>\n")
                        .append("    </Placemark>\n");
            }
        }
        out.append("  </Document>\n</kml>\n");
        return out.toString();
    }

    public static String importBatchGeoJson(FieldDatabase.ImportBatch batch,
                                            List<WaypointEntity> waypoints,
                                            List<TrackData> tracks,
                                            List<FieldDatabase.Area> areas) throws JSONException {
        JSONObject root = baseCollection("importBatch");
        if (batch != null) {
            root.put("sourceName", safe(batch.sourceName));
            root.put("sourceSha256", safe(batch.sha256));
            root.put("importedAt", batch.importedAt);
            root.put("originalWaypointCount", batch.waypointCount);
            root.put("originalTrackCount", batch.trackCount);
            root.put("originalAreaCount", batch.areaCount);
        }
        JSONArray features = new JSONArray();
        if (waypoints != null) {
            for (WaypointEntity waypoint : waypoints) {
                if (waypoint != null) features.put(savedLocationFeature(waypoint));
            }
        }
        if (tracks != null) {
            for (TrackData data : tracks) {
                if (data != null && data.track != null && data.points.size() >= 2) features.put(trackFeature(data));
            }
        }
        if (areas != null) {
            for (FieldDatabase.Area area : areas) {
                if (area != null && area.points != null && area.points.size() >= 3) features.put(areaFeature(area));
            }
        }
        root.put("features", features);
        return root.toString(2);
    }

    public static String allFieldGeoJson(List<WaypointEntity> waypoints,
                                         List<TrackData> tracks,
                                         List<FieldDatabase.FieldRecord> records,
                                         List<FieldDatabase.Area> areas) throws JSONException {
        JSONObject root = baseCollection("allFieldSpatialData");
        root.put("restoreBackup", false);
        JSONArray features = new JSONArray();
        if (waypoints != null) for (WaypointEntity waypoint : waypoints) if (waypoint != null) features.put(savedLocationFeature(waypoint));
        if (tracks != null) for (TrackData data : tracks) if (data != null && data.track != null && data.points.size() >= 2) features.put(trackFeature(data));
        if (records != null) for (FieldDatabase.FieldRecord record : records) if (record != null) features.put(fieldRecordFeature(record));
        if (areas != null) for (FieldDatabase.Area area : areas) if (area != null && area.points != null && area.points.size() >= 3) features.put(areaFeature(area));
        root.put("features", features);
        return root.toString(2);
    }

    private static JSONObject baseCollection(String exportType) throws JSONException {
        return new JSONObject()
                .put("type", "FeatureCollection")
                .put("rockmapExport", exportType)
                .put("exportedAt", System.currentTimeMillis());
    }

    private static JSONObject savedLocationFeature(WaypointEntity waypoint) throws JSONException {
        JSONObject props = new JSONObject()
                .put("rockmapType", "savedLocation")
                .put("name", safe(waypoint.name))
                .put("notes", safe(waypoint.notes))
                .put("accuracyMeters", waypoint.accuracyMeters)
                .put("capturedAt", waypoint.capturedAt)
                .put("createdAt", waypoint.createdAt)
                .put("updatedAt", waypoint.updatedAt);
        return pointFeature(waypoint.latitude, waypoint.longitude, props);
    }

    private static JSONObject fieldRecordFeature(FieldDatabase.FieldRecord record) throws JSONException {
        JSONObject props = new JSONObject()
                .put("rockmapType", "fieldRecord")
                .put("name", safe(record.name))
                .put("category", safe(record.category))
                .put("mineral", safe(record.mineral))
                .put("sampleId", safe(record.sampleId))
                .put("notes", safe(record.notes))
                .put("photoReference", safe(record.photoUri))
                .put("createdAt", record.createdAt)
                .put("updatedAt", record.updatedAt);
        if (Double.isFinite(record.altitude)) props.put("altitudeMeters", record.altitude);
        if (Float.isFinite(record.accuracy) && record.accuracy >= 0f) props.put("accuracyMeters", record.accuracy);
        return pointFeature(record.lat, record.lon, props);
    }

    private static JSONObject trackFeature(TrackData data) throws JSONException {
        JSONArray coordinates = new JSONArray();
        JSONArray times = new JSONArray();
        JSONArray altitudes = new JSONArray();
        JSONArray accuracies = new JSONArray();
        for (GeoMath.Point point : data.points) {
            coordinates.put(new JSONArray().put(point.lon).put(point.lat));
            times.put(point.time > 0L ? point.time : JSONObject.NULL);
            altitudes.put(Double.isFinite(point.alt) ? point.alt : JSONObject.NULL);
            accuracies.put(Float.isFinite(point.accuracy) && point.accuracy >= 0f ? point.accuracy : JSONObject.NULL);
        }
        JSONObject props = new JSONObject()
                .put("rockmapType", "track")
                .put("name", safe(data.track.name))
                .put("status", safe(data.track.status))
                .put("startedAt", data.track.startedAt)
                .put("endedAt", data.track.endedAt)
                .put("pointCount", data.points.size())
                .put("pointTimes", times)
                .put("altitudesMeters", altitudes)
                .put("accuraciesMeters", accuracies);
        JSONObject geometry = new JSONObject().put("type", "LineString").put("coordinates", coordinates);
        return new JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry);
    }

    private static JSONObject areaFeature(FieldDatabase.Area area) throws JSONException {
        JSONArray ring = new JSONArray();
        for (GeoMath.Point point : area.points) ring.put(new JSONArray().put(point.lon).put(point.lat));
        GeoMath.Point first = area.points.get(0);
        GeoMath.Point last = area.points.get(area.points.size() - 1);
        if (!samePoint(first, last)) ring.put(new JSONArray().put(first.lon).put(first.lat));
        JSONObject props = new JSONObject()
                .put("rockmapType", "prospectingArea")
                .put("name", safe(area.name))
                .put("notes", safe(area.notes))
                .put("createdAt", area.createdAt)
                .put("vertexCount", area.points.size());
        JSONObject geometry = new JSONObject().put("type", "Polygon")
                .put("coordinates", new JSONArray().put(ring));
        return new JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry);
    }

    private static JSONObject pointFeature(double lat, double lon, JSONObject properties) throws JSONException {
        JSONObject geometry = new JSONObject().put("type", "Point")
                .put("coordinates", new JSONArray().put(lon).put(lat));
        return new JSONObject().put("type", "Feature").put("properties", properties).put("geometry", geometry);
    }

    private static void gpxStart(StringBuilder out) {
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"RockMap\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
    }

    private static void gpxEnd(StringBuilder out) {
        out.append("</gpx>\n");
    }

    private static void appendXmlElement(StringBuilder out, String tag, String value, int spaces) {
        if (value == null) return;
        for (int i = 0; i < spaces; i++) out.append(' ');
        out.append('<').append(tag).append('>').append(xml(value)).append("</").append(tag).append(">\n");
    }

    private static String xml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static void csv(StringBuilder out, String value) {
        String text = value == null ? "" : value;
        out.append('"').append(text.replace("\"", "\"\"")).append('"');
    }

    private static String coord(double value) {
        return String.format(Locale.US, "%.8f", value);
    }

    private static String number(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    private static boolean samePoint(GeoMath.Point a, GeoMath.Point b) {
        return a != null && b != null
                && Math.abs(a.lat - b.lat) < 1e-10
                && Math.abs(a.lon - b.lon) < 1e-10;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
