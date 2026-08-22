package com.rockmap.app.research;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Offline Colorado geology store used by Commit 2 research queries.
 *
 * Geology queries read a local Colorado SQLite snapshot. Unknown-size live-service installation is
 * intentionally disabled: new snapshots must be distributed as versioned RockMap offline resources
 * with a disclosed byte size/checksum before download. Existing installed snapshots remain usable.
 */
public final class GeologyRepository {
    public static final String SOURCE_TITLE = "USGS State Geologic Map Compilation (SGMC)";
    public static final String SOURCE_DOI = "10.5066/F7WH2N65";
    public static final String SOURCE_SCALE = "1:500,000 Colorado source map";
    public static final String SOURCE_NOTE = "2017 USGS SGMC source polygons published through an ArcGIS FeatureServer; RockMap stores a Colorado-only local snapshot and reports the source exactly rather than relabeling it as the separate 2026 GeMS release.";
    public static final String SOURCE_SERVICE = "https://services.arcgis.com/v01gqwM5QqNysAAi/ArcGIS/rest/services/SGMC_featureservice/FeatureServer/0/query";

    private static final String DB_NAME = "rockmap-geology.db";
    private static final int MIN_EXPECTED_COLORADO_RECORDS = 500;
    private static final int MAX_EXPECTED_COLORADO_RECORDS = 100000;

    public static final class Bounds {
        public final double south;
        public final double west;
        public final double north;
        public final double east;

        public Bounds(double south, double west, double north, double east) {
            if (!Double.isFinite(south) || !Double.isFinite(west)
                    || !Double.isFinite(north) || !Double.isFinite(east)
                    || south > north || west > east
                    || south < -90d || north > 90d || west < -180d || east > 180d) {
                throw new IllegalArgumentException("Invalid geology bounds.");
            }
            this.south = south;
            this.west = west;
            this.north = north;
            this.east = east;
        }
    }

    public static final class Point {
        public final double lat;
        public final double lon;
        public Point(double lat, double lon) {
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || lat < -90d || lat > 90d || lon < -180d || lon > 180d) {
                throw new IllegalArgumentException("Invalid point.");
            }
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static final class Filter {
        public final String text;
        public final String lithology;
        public final String age;
        public Filter(String text, String lithology, String age) {
            this.text = normalize(text);
            this.lithology = normalize(lithology);
            this.age = normalize(age);
        }
    }

    private final Context context;

    public GeologyRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public File getDatabaseFile() {
        // Keep replaceable geology outside Android's database backup domain. User-created
        // RockMap databases remain backup-eligible; the whole files/research directory is
        // explicitly excluded by the existing file-domain backup rules.
        return new File(new File(context.getFilesDir(), "research"), DB_NAME);
    }

    public boolean isReady() {
        File file = getDatabaseFile();
        if (!file.isFile() || file.length() < 4096L) return false;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
            try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE key='record_count'", null)) {
                if (!c.moveToFirst()) return false;
                int count = Integer.parseInt(c.getString(0));
                return count >= MIN_EXPECTED_COLORADO_RECORDS && count <= MAX_EXPECTED_COLORADO_RECORDS;
            }
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public int getRecordCount() {
        if (!isReady()) return 0;
        try (SQLiteDatabase db = openRead();
             Cursor c = db.rawQuery("SELECT value FROM metadata WHERE key='record_count'", null)) {
            return c.moveToFirst() ? Integer.parseInt(c.getString(0)) : 0;
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    public long getDatabaseBytes() {
        File file = getDatabaseFile();
        return file.isFile() ? file.length() : 0L;
    }

    public String getDownloadedAt() {
        if (!isReady()) return "";
        try (SQLiteDatabase db = openRead();
             Cursor c = db.rawQuery("SELECT value FROM metadata WHERE key='downloaded_at'", null)) {
            return c.moveToFirst() ? c.getString(0) : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    public List<GeologyUnit> search(Filter filter, Bounds bounds, int limit) {
        ensureReady();
        Filter actual = filter == null ? new Filter("", "", "") : filter;
        int safeLimit = limit <= 0 ? 0 : Math.min(limit, 100000);
        ArrayList<String> clauses = new ArrayList<>();
        ArrayList<String> args = new ArrayList<>();
        if (!actual.text.isEmpty()) {
            clauses.add("search_text LIKE ?");
            args.add("%" + actual.text + "%");
        }
        if (!actual.lithology.isEmpty()) {
            clauses.add("lithology_text LIKE ?");
            args.add("%" + actual.lithology + "%");
        }
        if (!actual.age.isEmpty()) {
            clauses.add("age_text LIKE ?");
            args.add("%" + actual.age + "%");
        }
        appendBounds(clauses, args, bounds);
        String where = clauses.isEmpty() ? null : join(clauses, " AND ");
        return query(where, args.toArray(new String[0]), "unit_name COLLATE NOCASE ASC", safeLimit);
    }

    public List<GeologyUnit> queryBounds(Bounds bounds, int limit) {
        ensureReady();
        ArrayList<String> clauses = new ArrayList<>();
        ArrayList<String> args = new ArrayList<>();
        appendBounds(clauses, args, bounds);
        return query(join(clauses, " AND "), args.toArray(new String[0]), "object_id ASC",
                limit <= 0 ? 0 : Math.min(limit, 100000));
    }

    public List<GeologyUnit> queryPolygon(List<Point> polygon, int limit) {
        if (polygon == null || polygon.size() < 3) throw new IllegalArgumentException("Area query needs at least 3 vertices.");
        Bounds bounds = boundsOfPoints(polygon);
        List<GeologyUnit> candidates = queryBounds(bounds, 0);
        ArrayList<GeologyUnit> out = new ArrayList<>();
        int max = limit <= 0 ? Integer.MAX_VALUE : Math.min(limit, 100000);
        for (GeologyUnit unit : candidates) {
            if (geometryIntersectsPolygon(unit.geometryJson, polygon)) {
                out.add(unit);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    public List<GeologyUnit> queryPointRadius(Point point, double radiusMeters, int limit) {
        if (point == null) throw new IllegalArgumentException("Point is required.");
        if (!Double.isFinite(radiusMeters) || radiusMeters < 0d || radiusMeters > 100000d) {
            throw new IllegalArgumentException("Radius must be between 0 and 100 km.");
        }
        double latDelta = Math.max(0.00001d, radiusMeters / 111320d);
        double lonScale = Math.max(0.1d, Math.cos(Math.toRadians(point.lat)));
        double lonDelta = Math.max(0.00001d, radiusMeters / (111320d * lonScale));
        Bounds bounds = new Bounds(point.lat - latDelta, point.lon - lonDelta,
                point.lat + latDelta, point.lon + lonDelta);
        List<GeologyUnit> candidates = queryBounds(bounds, 0);
        ArrayList<GeologyUnit> out = new ArrayList<>();
        int max = limit <= 0 ? Integer.MAX_VALUE : Math.min(limit, 100000);
        for (GeologyUnit unit : candidates) {
            if (geometryWithinRadius(unit.geometryJson, point, radiusMeters)) {
                out.add(unit);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    public List<String> suggestions(String prefix, int limit) {
        ensureReady();
        String needle = normalize(prefix);
        int max = limit <= 0 ? 20 : Math.min(limit, 50);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try (SQLiteDatabase db = openRead()) {
            String where = needle.isEmpty() ? null : "search_text LIKE ?";
            String[] args = needle.isEmpty() ? null : new String[]{"%" + needle + "%"};
            try (Cursor c = db.query("units",
                    new String[]{"generalized_lith","major1","major2","major3","unit_name"},
                    where, args, null, null, "generalized_lith COLLATE NOCASE ASC", "250")) {
                while (c.moveToNext() && out.size() < max) {
                    addSuggestion(out, c.getString(0), needle, max);
                    addSuggestion(out, c.getString(1), needle, max);
                    addSuggestion(out, c.getString(2), needle, max);
                    addSuggestion(out, c.getString(3), needle, max);
                    addSuggestion(out, c.getString(4), needle, max);
                }
            }
        }
        return new ArrayList<>(out);
    }

    public String toGeoJson(List<GeologyUnit> units) {
        try {
            JSONObject root = new JSONObject();
            root.put("type", "FeatureCollection");
            root.put("rockmapResearchSchema", 1);
            root.put("source", SOURCE_TITLE);
            root.put("sourceDOI", SOURCE_DOI);
            root.put("sourceScale", SOURCE_SCALE);
            root.put("sourceNote", SOURCE_NOTE);
            JSONArray features = new JSONArray();
            if (units != null) {
                for (GeologyUnit unit : units) features.put(toFeature(unit));
            }
            root.put("features", features);
            return root.toString();
        } catch (JSONException ex) {
            throw new IllegalStateException("Could not encode geology result.", ex);
        }
    }

    public JSONObject toFeature(GeologyUnit unit) throws JSONException {
        JSONObject feature = new JSONObject();
        feature.put("type", "Feature");
        feature.put("id", unit.objectId);
        feature.put("geometry", new JSONObject(unit.geometryJson));
        JSONObject p = new JSONObject();
        p.put("OBJECTID", unit.objectId);
        p.put("STATE", unit.state);
        p.put("ORIG_LABEL", unit.originalLabel);
        p.put("SGMC_LABEL", unit.sgmcLabel);
        p.put("UNIT_LINK", unit.unitLink);
        p.put("UNIT_NAME", unit.unitName);
        p.put("AGE_MIN", unit.ageMin);
        p.put("AGE_MAX", unit.ageMax);
        p.put("GENERALIZED_LITH", unit.generalizedLithology);
        p.put("MAJOR1", unit.major1);
        p.put("MAJOR2", unit.major2);
        p.put("MAJOR3", unit.major3);
        p.put("MINOR1", unit.minor1);
        p.put("MINOR2", unit.minor2);
        p.put("MINOR3", unit.minor3);
        p.put("MINOR4", unit.minor4);
        p.put("MINOR5", unit.minor5);
        p.put("INCIDENTAL", unit.incidental);
        p.put("INDETERMINATE", unit.indeterminate);
        p.put("REF_ID", unit.referenceId);
        p.put("REFERENCE", unit.reference);
        p.put("DIGITAL_URL", unit.digitalUrl);
        p.put("NGMDB1", unit.ngmdb1);
        p.put("NGMDB2", unit.ngmdb2);
        p.put("NGMDB3", unit.ngmdb3);
        p.put("rgba", unit.rgba);
        p.put("rockmap_source", SOURCE_TITLE);
        p.put("rockmap_source_doi", SOURCE_DOI);
        p.put("rockmap_source_scale", SOURCE_SCALE);
        feature.put("properties", p);
        return feature;
    }

    private List<GeologyUnit> query(String where, String[] args, String order, int limit) {
        ArrayList<GeologyUnit> out = new ArrayList<>();
        try (SQLiteDatabase db = openRead();
             Cursor c = db.query("units", UNIT_COLUMNS, where, args, null, null, order,
                     limit <= 0 ? null : Integer.toString(limit))) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    private SQLiteDatabase openRead() {
        return SQLiteDatabase.openDatabase(getDatabaseFile().getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    private void ensureReady() {
        if (!isReady()) throw new IllegalStateException("Colorado geology is not installed yet.");
    }

    private static final String[] UNIT_COLUMNS = new String[]{
            "object_id","state","orig_label","sgmc_label","unit_link","unit_name","age_min","age_max","generalized_lith",
            "major1","major2","major3","minor1","minor2","minor3","minor4","minor5","incidental","indeterminate",
            "ref_id","reference_text","digital_url","ngmdb1","ngmdb2","ngmdb3","rgba","south","west","north","east","geometry_json"
    };

    private static GeologyUnit fromCursor(Cursor c) {
        return new GeologyUnit(
                c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                c.getString(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11),
                c.getString(12), c.getString(13), c.getString(14), c.getString(15), c.getString(16), c.getString(17), c.getString(18),
                c.getString(19), c.getString(20), c.getString(21), c.getString(22), c.getString(23), c.getString(24), c.getString(25),
                c.getDouble(26), c.getDouble(27), c.getDouble(28), c.getDouble(29), c.getString(30));
    }

    private static void appendBounds(List<String> clauses, List<String> args, Bounds bounds) {
        if (bounds == null) return;
        clauses.add("south<=? AND north>=? AND west<=? AND east>=?");
        args.add(Double.toString(bounds.north));
        args.add(Double.toString(bounds.south));
        args.add(Double.toString(bounds.east));
        args.add(Double.toString(bounds.west));
    }

    private static Bounds boundsOfPoints(List<Point> points) {
        double south = 90d, west = 180d, north = -90d, east = -180d;
        for (Point point : points) {
            south = Math.min(south, point.lat);
            north = Math.max(north, point.lat);
            west = Math.min(west, point.lon);
            east = Math.max(east, point.lon);
        }
        return new Bounds(south, west, north, east);
    }

    private static boolean geometryIntersectsPolygon(String geometryJson, List<Point> polygon) {
        try {
            JSONObject geometry = new JSONObject(geometryJson);
            String type = geometry.optString("type", "");
            JSONArray coords = geometry.getJSONArray("coordinates");
            if ("Polygon".equals(type)) return polygonCoordinatesIntersect(coords, polygon);
            if ("MultiPolygon".equals(type)) {
                for (int i = 0; i < coords.length(); i++) {
                    JSONArray poly = coords.optJSONArray(i);
                    if (poly != null && polygonCoordinatesIntersect(poly, polygon)) return true;
                }
            }
        } catch (JSONException ignored) {}
        return false;
    }

    private static boolean polygonCoordinatesIntersect(JSONArray polygonCoords, List<Point> query) throws JSONException {
        JSONArray outer = polygonCoords.optJSONArray(0);
        if (outer == null || outer.length() < 3) return false;
        ArrayList<Point> geologyOuter = ringPoints(outer);
        for (Point p : geologyOuter) if (pointInPolygon(p, query)) return true;
        for (Point p : query) if (pointInPolygonCoordinates(p, polygonCoords)) return true;
        if (ringsCross(geologyOuter, query)) return true;
        for (int i = 1; i < polygonCoords.length(); i++) {
            JSONArray hole = polygonCoords.optJSONArray(i);
            if (hole != null && hole.length() >= 3 && ringsCross(ringPoints(hole), query)) return true;
        }
        return false;
    }

    private static boolean geometryWithinRadius(String geometryJson, Point center, double radiusMeters) {
        try {
            JSONObject geometry = new JSONObject(geometryJson);
            String type = geometry.optString("type", "");
            JSONArray coords = geometry.getJSONArray("coordinates");
            if ("Polygon".equals(type)) return polygonWithinRadius(coords, center, radiusMeters);
            if ("MultiPolygon".equals(type)) {
                for (int i = 0; i < coords.length(); i++) {
                    JSONArray poly = coords.optJSONArray(i);
                    if (poly != null && polygonWithinRadius(poly, center, radiusMeters)) return true;
                }
            }
        } catch (JSONException ignored) {}
        return false;
    }

    private static boolean polygonWithinRadius(JSONArray polygonCoords, Point center, double radiusMeters) throws JSONException {
        JSONArray outer = polygonCoords.optJSONArray(0);
        if (outer == null || outer.length() < 3) return false;
        if (pointInPolygonCoordinates(center, polygonCoords)) return true;
        double max = Math.max(0d, radiusMeters);
        for (int ringIndex = 0; ringIndex < polygonCoords.length(); ringIndex++) {
            JSONArray rawRing = polygonCoords.optJSONArray(ringIndex);
            if (rawRing == null || rawRing.length() < 2) continue;
            ArrayList<Point> ring = ringPoints(rawRing);
            for (int i = 0; i < ring.size(); i++) {
                Point a = ring.get(i);
                Point b = ring.get((i + 1) % ring.size());
                if (distanceMeters(center, a) <= max || segmentDistanceMeters(center, a, b) <= max) return true;
            }
        }
        return false;
    }

    private static boolean pointInPolygonCoordinates(Point point, JSONArray polygonCoords) throws JSONException {
        JSONArray outer = polygonCoords.optJSONArray(0);
        if (outer == null || !pointInPolygon(point, ringPoints(outer))) return false;
        for (int i = 1; i < polygonCoords.length(); i++) {
            JSONArray hole = polygonCoords.optJSONArray(i);
            if (hole != null && hole.length() >= 3 && pointInPolygon(point, ringPoints(hole))) return false;
        }
        return true;
    }

    private static ArrayList<Point> ringPoints(JSONArray ring) throws JSONException {
        ArrayList<Point> out = new ArrayList<>();
        for (int i = 0; i < ring.length(); i++) {
            JSONArray xy = ring.optJSONArray(i);
            if (xy != null && xy.length() >= 2) out.add(new Point(xy.getDouble(1), xy.getDouble(0)));
        }
        return out;
    }

    private static boolean pointInPolygon(Point p, List<Point> polygon) {
        if (polygon == null || polygon.size() < 3) return false;
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Point a = polygon.get(i);
            Point b = polygon.get(j);
            boolean intersect = ((a.lat > p.lat) != (b.lat > p.lat))
                    && (p.lon < (b.lon - a.lon) * (p.lat - a.lat) / ((b.lat - a.lat) == 0d ? 1e-12d : (b.lat - a.lat)) + a.lon);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    private static boolean ringsCross(List<Point> a, List<Point> b) {
        for (int i = 0; i < a.size(); i++) {
            Point a1 = a.get(i), a2 = a.get((i + 1) % a.size());
            for (int j = 0; j < b.size(); j++) {
                Point b1 = b.get(j), b2 = b.get((j + 1) % b.size());
                if (segmentsIntersect(a1, a2, b1, b2)) return true;
            }
        }
        return false;
    }

    private static boolean segmentsIntersect(Point a, Point b, Point c, Point d) {
        double d1 = direction(c, d, a), d2 = direction(c, d, b);
        double d3 = direction(a, b, c), d4 = direction(a, b, d);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double direction(Point a, Point b, Point c) {
        return (c.lon - a.lon) * (b.lat - a.lat) - (b.lon - a.lon) * (c.lat - a.lat);
    }

    private static double distanceMeters(Point a, Point b) {
        double p1 = Math.toRadians(a.lat), p2 = Math.toRadians(b.lat);
        double dp = p2 - p1, dl = Math.toRadians(b.lon - a.lon);
        double h = Math.sin(dp / 2d) * Math.sin(dp / 2d)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2d) * Math.sin(dl / 2d);
        return 6371008.8d * 2d * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0d, 1d - h)));
    }

    private static double segmentDistanceMeters(Point p, Point a, Point b) {
        double lat0 = Math.toRadians(p.lat);
        double scaleX = 111320d * Math.max(0.1d, Math.cos(lat0));
        double scaleY = 111320d;
        double ax = (a.lon - p.lon) * scaleX, ay = (a.lat - p.lat) * scaleY;
        double bx = (b.lon - p.lon) * scaleX, by = (b.lat - p.lat) * scaleY;
        double dx = bx - ax, dy = by - ay;
        double denom = dx * dx + dy * dy;
        double t = denom <= 0d ? 0d : -(ax * dx + ay * dy) / denom;
        t = Math.max(0d, Math.min(1d, t));
        double x = ax + t * dx, y = ay + t * dy;
        return Math.sqrt(x * x + y * y);
    }

    private static void addSuggestion(Set<String> out, String raw, String needle, int max) {
        if (out.size() >= max || raw == null) return;
        String value = raw.trim();
        if (value.length() < 2 || value.length() > 120) return;
        if (!needle.isEmpty() && !value.toLowerCase(Locale.US).contains(needle)) return;
        out.add(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }

    private static String combine(String... values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(value.trim().toLowerCase(Locale.US));
        }
        return out.toString();
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(delimiter);
            out.append(value);
        }
        return out.toString();
    }

}
