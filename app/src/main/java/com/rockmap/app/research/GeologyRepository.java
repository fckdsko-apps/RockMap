package com.rockmap.app.research;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline Colorado geology store used by Commit 2 research queries.
 *
 * The first install is intentionally user-triggered. RockMap downloads Colorado-only polygon
 * records from the USGS SGMC ArcGIS service, validates the schema/state, and writes a local SQLite
 * snapshot. Once that succeeds, search/spatial queries operate without a network connection.
 */
public final class GeologyRepository {
    public static final String SOURCE_TITLE = "USGS State Geologic Map Compilation (SGMC)";
    public static final String SOURCE_DOI = "10.5066/F7WH2N65";
    public static final String SOURCE_NOTE = "2017 USGS SGMC source polygons published through an ArcGIS FeatureServer; RockMap stores a Colorado-only local snapshot and reports the source exactly rather than relabeling it as the separate 2026 GeMS release.";
    public static final String SOURCE_SERVICE = "https://services.arcgis.com/v01gqwM5QqNysAAi/ArcGIS/rest/services/SGMC_featureservice/FeatureServer/0/query";

    private static final String DB_NAME = "rockmap-geology.db";
    private static final int PAGE_SIZE = 500;
    private static final int MIN_EXPECTED_COLORADO_RECORDS = 500;
    private static final int MAX_EXPECTED_COLORADO_RECORDS = 100000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    public interface ProgressCallback {
        void onProgress(int downloaded, int expected);
        void onComplete(int records, long bytes);
        void onError(String message);
    }

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
    private final Handler main = new Handler(Looper.getMainLooper());

    public GeologyRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    public File getDatabaseFile() {
        return context.getDatabasePath(DB_NAME);
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

    public void downloadColoradoSnapshot(ProgressCallback callback) {
        EXECUTOR.execute(() -> {
            File target = getDatabaseFile();
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                postError(callback, "RockMap could not create the geology data directory.");
                return;
            }
            File temp = new File(target.getAbsolutePath() + ".download");
            if (temp.exists() && !temp.delete()) {
                postError(callback, "RockMap could not replace an incomplete geology download.");
                return;
            }

            SQLiteDatabase db = null;
            try {
                int expected = fetchColoradoCount();
                if (expected < MIN_EXPECTED_COLORADO_RECORDS || expected > MAX_EXPECTED_COLORADO_RECORDS) {
                    throw new IOException("USGS returned an unexpected Colorado geology count (" + expected
                            + "). RockMap rejected the snapshot rather than trusting a changed/partial source.");
                }

                db = SQLiteDatabase.openOrCreateDatabase(temp, null);
                createSchema(db);
                int inserted = 0;
                int offset = 0;
                while (offset < expected) {
                    JSONObject page = fetchPage(offset, PAGE_SIZE);
                    JSONArray features = page.optJSONArray("features");
                    if (features == null) throw new IOException("USGS response did not contain GeoJSON features.");
                    if (features.length() == 0 && offset < expected) {
                        throw new IOException("USGS pagination ended early at " + inserted + " of " + expected + " records.");
                    }

                    db.beginTransaction();
                    try {
                        for (int i = 0; i < features.length(); i++) {
                            JSONObject feature = features.optJSONObject(i);
                            if (feature == null) continue;
                            insertFeature(db, feature);
                            inserted++;
                        }
                        db.setTransactionSuccessful();
                    } finally {
                        db.endTransaction();
                    }
                    offset += features.length();
                    int progress = inserted;
                    main.post(() -> callback.onProgress(progress, expected));
                    if (features.length() < PAGE_SIZE) break;
                }

                if (inserted != expected) {
                    throw new IOException("USGS count changed during download (expected " + expected
                            + ", received " + inserted + "). RockMap rejected the mixed snapshot.");
                }
                putMeta(db, "record_count", Integer.toString(inserted));
                putMeta(db, "source_title", SOURCE_TITLE);
                putMeta(db, "source_doi", SOURCE_DOI);
                putMeta(db, "source_service", SOURCE_SERVICE);
                putMeta(db, "source_note", SOURCE_NOTE);
                putMeta(db, "downloaded_at", Long.toString(System.currentTimeMillis()));
                db.execSQL("PRAGMA user_version=1");
                db.execSQL("ANALYZE");
                db.close();
                db = null;

                File backup = new File(target.getAbsolutePath() + ".bak");
                if (backup.exists()) backup.delete();
                if (target.exists() && !target.renameTo(backup)) {
                    throw new IOException("RockMap could not stage the existing geology database for replacement.");
                }
                if (!temp.renameTo(target)) {
                    if (backup.exists()) backup.renameTo(target);
                    throw new IOException("RockMap could not activate the completed geology snapshot.");
                }
                if (backup.exists()) backup.delete();
                long bytes = target.length();
                int finalInserted = inserted;
                main.post(() -> callback.onComplete(finalInserted, bytes));
            } catch (Exception ex) {
                if (db != null && db.isOpen()) db.close();
                temp.delete();
                postError(callback, ex.getMessage() == null ? "Colorado geology download failed safely." : ex.getMessage());
            }
        });
    }

    public List<GeologyUnit> search(Filter filter, Bounds bounds, int limit) {
        ensureReady();
        Filter actual = filter == null ? new Filter("", "", "") : filter;
        int safeLimit = limit <= 0 ? 500 : Math.min(limit, 2000);
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
                limit <= 0 ? 1000 : Math.min(limit, 3000));
    }

    public List<GeologyUnit> queryPolygon(List<Point> polygon, int limit) {
        if (polygon == null || polygon.size() < 3) throw new IllegalArgumentException("Area query needs at least 3 vertices.");
        Bounds bounds = boundsOfPoints(polygon);
        List<GeologyUnit> candidates = queryBounds(bounds, 3000);
        ArrayList<GeologyUnit> out = new ArrayList<>();
        int max = limit <= 0 ? 1000 : Math.min(limit, 3000);
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
        List<GeologyUnit> candidates = queryBounds(bounds, 3000);
        ArrayList<GeologyUnit> out = new ArrayList<>();
        int max = limit <= 0 ? 1000 : Math.min(limit, 3000);
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
        feature.put("properties", p);
        return feature;
    }

    private int fetchColoradoCount() throws IOException, JSONException {
        String url = SOURCE_SERVICE + "?where=" + enc("STATE='CO'")
                + "&returnCountOnly=true&f=json";
        JSONObject json = fetchJson(url);
        int count = json.optInt("count", -1);
        if (count < 0) throw new IOException("USGS did not return a Colorado geology record count.");
        return count;
    }

    private JSONObject fetchPage(int offset, int count) throws IOException, JSONException {
        String fields = "OBJECTID,STATE,ORIG_LABEL,SGMC_LABEL,UNIT_LINK,UNIT_NAME,AGE_MIN,AGE_MAX,"
                + "MAJOR1,MAJOR2,MAJOR3,MINOR1,MINOR2,MINOR3,MINOR4,MINOR5,INCIDENTAL,INDETERMINATE,"
                + "REF_ID,REFERENCE,GENERALIZED_LITH,DIGITAL_URL,NGMDB1,NGMDB2,NGMDB3,rgba";
        String url = SOURCE_SERVICE + "?where=" + enc("STATE='CO'")
                + "&outFields=" + enc(fields)
                + "&returnGeometry=true&outSR=4326&orderByFields=OBJECTID"
                + "&resultOffset=" + offset + "&resultRecordCount=" + count + "&f=geojson";
        return fetchJson(url);
    }

    private JSONObject fetchJson(String urlText) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Accept", "application/json, application/geo+json");
        connection.setRequestProperty("User-Agent", "RockMap-Android");
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IOException("USGS geology service returned HTTP " + code + ".");
        }
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[32768];
            int n;
            int total = 0;
            while ((n = input.read(buffer)) != -1) {
                total += n;
                if (total > 80 * 1024 * 1024) {
                    throw new IOException("A USGS geology page exceeded RockMap's safety size limit.");
                }
                out.write(buffer, 0, n);
            }
            JSONObject json = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            JSONObject error = json.optJSONObject("error");
            if (error != null) throw new IOException("USGS geology service error: " + error.optString("message", "unknown error"));
            return json;
        } finally {
            connection.disconnect();
        }
    }

    private void insertFeature(SQLiteDatabase db, JSONObject feature) throws JSONException, IOException {
        JSONObject p = feature.optJSONObject("properties");
        JSONObject geometry = feature.optJSONObject("geometry");
        if (p == null || geometry == null) throw new IOException("USGS geology feature is missing properties or geometry.");
        String state = p.optString("STATE", "").trim();
        if (!"CO".equalsIgnoreCase(state)) throw new IOException("USGS Colorado query returned a non-Colorado geology feature.");
        long objectId = p.optLong("OBJECTID", -1L);
        if (objectId <= 0L) throw new IOException("USGS geology feature is missing OBJECTID.");
        Bounds bbox = geometryBounds(geometry);

        ContentValues v = new ContentValues();
        v.put("object_id", objectId);
        v.put("state", state);
        put(v, "orig_label", p, "ORIG_LABEL");
        put(v, "sgmc_label", p, "SGMC_LABEL");
        put(v, "unit_link", p, "UNIT_LINK");
        put(v, "unit_name", p, "UNIT_NAME");
        put(v, "age_min", p, "AGE_MIN");
        put(v, "age_max", p, "AGE_MAX");
        put(v, "generalized_lith", p, "GENERALIZED_LITH");
        put(v, "major1", p, "MAJOR1");
        put(v, "major2", p, "MAJOR2");
        put(v, "major3", p, "MAJOR3");
        put(v, "minor1", p, "MINOR1");
        put(v, "minor2", p, "MINOR2");
        put(v, "minor3", p, "MINOR3");
        put(v, "minor4", p, "MINOR4");
        put(v, "minor5", p, "MINOR5");
        put(v, "incidental", p, "INCIDENTAL");
        put(v, "indeterminate", p, "INDETERMINATE");
        put(v, "ref_id", p, "REF_ID");
        put(v, "reference_text", p, "REFERENCE");
        put(v, "digital_url", p, "DIGITAL_URL");
        put(v, "ngmdb1", p, "NGMDB1");
        put(v, "ngmdb2", p, "NGMDB2");
        put(v, "ngmdb3", p, "NGMDB3");
        put(v, "rgba", p, "rgba");
        v.put("south", bbox.south);
        v.put("west", bbox.west);
        v.put("north", bbox.north);
        v.put("east", bbox.east);
        v.put("geometry_json", geometry.toString());

        String search = combine(
                p.optString("UNIT_NAME"), p.optString("ORIG_LABEL"), p.optString("SGMC_LABEL"),
                p.optString("GENERALIZED_LITH"), p.optString("MAJOR1"), p.optString("MAJOR2"), p.optString("MAJOR3"),
                p.optString("MINOR1"), p.optString("MINOR2"), p.optString("MINOR3"), p.optString("MINOR4"), p.optString("MINOR5"),
                p.optString("INCIDENTAL"), p.optString("INDETERMINATE"), p.optString("AGE_MIN"), p.optString("AGE_MAX"));
        String lith = combine(p.optString("GENERALIZED_LITH"), p.optString("MAJOR1"), p.optString("MAJOR2"), p.optString("MAJOR3"),
                p.optString("MINOR1"), p.optString("MINOR2"), p.optString("MINOR3"), p.optString("MINOR4"), p.optString("MINOR5"),
                p.optString("INCIDENTAL"), p.optString("INDETERMINATE"));
        String age = combine(p.optString("AGE_MIN"), p.optString("AGE_MAX"));
        v.put("search_text", search);
        v.put("lithology_text", lith);
        v.put("age_text", age);
        db.insertOrThrow("units", null, v);
    }

    private static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE units (object_id INTEGER PRIMARY KEY, state TEXT NOT NULL, "
                + "orig_label TEXT NOT NULL, sgmc_label TEXT NOT NULL, unit_link TEXT NOT NULL, unit_name TEXT NOT NULL, "
                + "age_min TEXT NOT NULL, age_max TEXT NOT NULL, generalized_lith TEXT NOT NULL, "
                + "major1 TEXT NOT NULL, major2 TEXT NOT NULL, major3 TEXT NOT NULL, "
                + "minor1 TEXT NOT NULL, minor2 TEXT NOT NULL, minor3 TEXT NOT NULL, minor4 TEXT NOT NULL, minor5 TEXT NOT NULL, "
                + "incidental TEXT NOT NULL, indeterminate TEXT NOT NULL, ref_id TEXT NOT NULL, reference_text TEXT NOT NULL, "
                + "digital_url TEXT NOT NULL, ngmdb1 TEXT NOT NULL, ngmdb2 TEXT NOT NULL, ngmdb3 TEXT NOT NULL, rgba TEXT NOT NULL, "
                + "south REAL NOT NULL, west REAL NOT NULL, north REAL NOT NULL, east REAL NOT NULL, geometry_json TEXT NOT NULL, "
                + "search_text TEXT NOT NULL, lithology_text TEXT NOT NULL, age_text TEXT NOT NULL)");
        db.execSQL("CREATE INDEX units_bounds_lat ON units(south,north)");
        db.execSQL("CREATE INDEX units_bounds_lon ON units(west,east)");
        db.execSQL("CREATE INDEX units_lith ON units(generalized_lith)");
        db.execSQL("CREATE INDEX units_age ON units(age_min,age_max)");
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    }

    private List<GeologyUnit> query(String where, String[] args, String order, int limit) {
        ArrayList<GeologyUnit> out = new ArrayList<>();
        try (SQLiteDatabase db = openRead();
             Cursor c = db.query("units", UNIT_COLUMNS, where, args, null, null, order, Integer.toString(limit))) {
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

    private static Bounds geometryBounds(JSONObject geometry) throws JSONException, IOException {
        JSONArray coordinates = geometry.optJSONArray("coordinates");
        if (coordinates == null) throw new IOException("Geology geometry has no coordinates.");
        double[] box = new double[]{90d, 180d, -90d, -180d};
        collectBounds(coordinates, box);
        if (box[2] < box[0] || box[3] < box[1]) throw new IOException("Geology geometry bounds are invalid.");
        return new Bounds(box[0], box[1], box[2], box[3]);
    }

    private static void collectBounds(JSONArray array, double[] box) throws JSONException {
        if (array.length() >= 2 && array.opt(0) instanceof Number && array.opt(1) instanceof Number) {
            double lon = array.getDouble(0);
            double lat = array.getDouble(1);
            box[0] = Math.min(box[0], lat);
            box[1] = Math.min(box[1], lon);
            box[2] = Math.max(box[2], lat);
            box[3] = Math.max(box[3], lon);
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONArray child = array.optJSONArray(i);
            if (child != null) collectBounds(child, box);
        }
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

    private static void put(ContentValues v, String column, JSONObject p, String key) {
        v.put(column, p.optString(key, "").trim());
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value == null ? "" : value);
        db.insertWithOnConflict("metadata", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static String enc(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
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

    private void postError(ProgressCallback callback, String message) {
        main.post(() -> callback.onError(message == null ? "Colorado geology download failed safely." : message));
    }
}
