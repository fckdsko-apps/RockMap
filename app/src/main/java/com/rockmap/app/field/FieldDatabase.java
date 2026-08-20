package com.rockmap.app.field;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class FieldDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "rockmap-field.db";
    private static final int DB_VERSION = 1;
    private static volatile FieldDatabase instance;

    public static final String TRACK_RECORDING = "recording";
    public static final String TRACK_PAUSED = "paused";
    public static final String TRACK_COMPLETE = "complete";
    public static final String TRACK_INTERRUPTED = "interrupted";

    public static FieldDatabase get(Context context) {
        FieldDatabase local = instance;
        if (local == null) {
            synchronized (FieldDatabase.class) {
                local = instance;
                if (local == null) {
                    local = new FieldDatabase(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private FieldDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE tracks (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL)");
        db.execSQL("CREATE TABLE track_points (id INTEGER PRIMARY KEY AUTOINCREMENT, track_id INTEGER NOT NULL, "
                + "sort_order INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, altitude REAL, "
                + "accuracy REAL, captured_at INTEGER NOT NULL, "
                + "FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX track_points_track_order ON track_points(track_id, sort_order)");

        db.execSQL("CREATE TABLE field_records (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, "
                + "category TEXT NOT NULL, mineral TEXT NOT NULL, sample_id TEXT NOT NULL, notes TEXT NOT NULL, "
                + "lat REAL NOT NULL, lon REAL NOT NULL, altitude REAL, accuracy REAL, photo_uri TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX field_records_location ON field_records(lat, lon)");

        db.execSQL("CREATE TABLE areas (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, notes TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE area_points (id INTEGER PRIMARY KEY AUTOINCREMENT, area_id INTEGER NOT NULL, "
                + "sort_order INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, "
                + "FOREIGN KEY(area_id) REFERENCES areas(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX area_points_area_order ON area_points(area_id, sort_order)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported RockMap field database upgrade path.");
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    public synchronized long createTrack(String name, long startedAt) {
        ContentValues values = new ContentValues();
        values.put("name", safe(name, "Recorded track"));
        values.put("started_at", startedAt);
        values.put("ended_at", 0L);
        values.put("status", TRACK_RECORDING);
        return getWritableDatabase().insertOrThrow("tracks", null, values);
    }

    public synchronized void setTrackStatus(long trackId, String status, long endedAt) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("ended_at", endedAt);
        getWritableDatabase().update("tracks", values, "id=?", new String[]{Long.toString(trackId)});
    }

    public synchronized void addTrackPoint(long trackId, GeoMath.Point point) {
        SQLiteDatabase db = getWritableDatabase();
        int order = 0;
        try (Cursor cursor = db.rawQuery("SELECT COALESCE(MAX(sort_order), -1)+1 FROM track_points WHERE track_id=?",
                new String[]{Long.toString(trackId)})) {
            if (cursor.moveToFirst()) order = cursor.getInt(0);
        }
        ContentValues values = new ContentValues();
        values.put("track_id", trackId);
        values.put("sort_order", order);
        values.put("lat", point.lat);
        values.put("lon", point.lon);
        if (Double.isFinite(point.alt)) values.put("altitude", point.alt);
        if (Float.isFinite(point.accuracy) && point.accuracy >= 0f) values.put("accuracy", point.accuracy);
        values.put("captured_at", point.time > 0L ? point.time : System.currentTimeMillis());
        db.insertOrThrow("track_points", null, values);
    }

    public synchronized List<Track> listTracks(int limit) {
        ArrayList<Track> out = new ArrayList<>();
        String limitText = limit <= 0 ? null : Integer.toString(limit);
        try (Cursor c = getReadableDatabase().query("tracks",
                new String[]{"id","name","started_at","ended_at","status"},
                null, null, null, null, "started_at DESC", limitText)) {
            while (c.moveToNext()) out.add(trackFrom(c));
        }
        return out;
    }

    public synchronized Track getTrack(long id) {
        try (Cursor c = getReadableDatabase().query("tracks",
                new String[]{"id","name","started_at","ended_at","status"},
                "id=?", new String[]{Long.toString(id)}, null, null, null)) {
            return c.moveToFirst() ? trackFrom(c) : null;
        }
    }

    public synchronized Track getActiveTrack() {
        try (Cursor c = getReadableDatabase().query("tracks",
                new String[]{"id","name","started_at","ended_at","status"},
                "status IN (?,?)", new String[]{TRACK_RECORDING, TRACK_PAUSED},
                null, null, "started_at DESC", "1")) {
            return c.moveToFirst() ? trackFrom(c) : null;
        }
    }

    public synchronized List<GeoMath.Point> getTrackPoints(long trackId) {
        ArrayList<GeoMath.Point> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("track_points",
                new String[]{"lat","lon","altitude","accuracy","captured_at"},
                "track_id=?", new String[]{Long.toString(trackId)}, null, null, "sort_order ASC")) {
            while (c.moveToNext()) {
                double alt = c.isNull(2) ? Double.NaN : c.getDouble(2);
                float accuracy = c.isNull(3) ? -1f : c.getFloat(3);
                out.add(new GeoMath.Point(c.getDouble(0), c.getDouble(1), alt, accuracy, c.getLong(4)));
            }
        }
        return out;
    }

    public synchronized void deleteTrack(long trackId) {
        getWritableDatabase().delete("tracks", "id=?", new String[]{Long.toString(trackId)});
    }

    public synchronized long insertFieldRecord(FieldRecord record) {
        ContentValues v = fieldValues(record);
        return getWritableDatabase().insertOrThrow("field_records", null, v);
    }

    public synchronized void updateFieldRecord(FieldRecord record) {
        ContentValues v = fieldValues(record);
        getWritableDatabase().update("field_records", v, "id=?", new String[]{Long.toString(record.id)});
    }

    public synchronized List<FieldRecord> listFieldRecords() {
        ArrayList<FieldRecord> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("field_records",
                new String[]{"id","name","category","mineral","sample_id","notes","lat","lon","altitude","accuracy","photo_uri","created_at","updated_at"},
                null, null, null, null, "updated_at DESC")) {
            while (c.moveToNext()) out.add(fieldFrom(c));
        }
        return out;
    }

    public synchronized FieldRecord getFieldRecord(long id) {
        try (Cursor c = getReadableDatabase().query("field_records",
                new String[]{"id","name","category","mineral","sample_id","notes","lat","lon","altitude","accuracy","photo_uri","created_at","updated_at"},
                "id=?", new String[]{Long.toString(id)}, null, null, null)) {
            return c.moveToFirst() ? fieldFrom(c) : null;
        }
    }

    public synchronized void deleteFieldRecord(long id) {
        getWritableDatabase().delete("field_records", "id=?", new String[]{Long.toString(id)});
    }

    public synchronized long insertArea(String name, String notes, List<GeoMath.Point> points) {
        if (points == null || points.size() < 3) throw new IllegalArgumentException("An area needs at least 3 points.");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues area = new ContentValues();
            area.put("name", safe(name, "Saved prospecting area"));
            area.put("notes", notes == null ? "" : notes);
            area.put("created_at", System.currentTimeMillis());
            long id = db.insertOrThrow("areas", null, area);
            for (int i = 0; i < points.size(); i++) {
                ContentValues p = new ContentValues();
                p.put("area_id", id);
                p.put("sort_order", i);
                p.put("lat", points.get(i).lat);
                p.put("lon", points.get(i).lon);
                db.insertOrThrow("area_points", null, p);
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<Area> listAreas() {
        ArrayList<Area> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("areas",
                new String[]{"id","name","notes","created_at"}, null, null, null, null, "created_at DESC")) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                out.add(new Area(id, c.getString(1), c.getString(2), c.getLong(3), getAreaPoints(id)));
            }
        }
        return out;
    }

    public synchronized List<GeoMath.Point> getAreaPoints(long areaId) {
        ArrayList<GeoMath.Point> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("area_points", new String[]{"lat","lon"},
                "area_id=?", new String[]{Long.toString(areaId)}, null, null, "sort_order ASC")) {
            while (c.moveToNext()) out.add(new GeoMath.Point(c.getDouble(0), c.getDouble(1)));
        }
        return out;
    }

    public synchronized void deleteArea(long id) {
        getWritableDatabase().delete("areas", "id=?", new String[]{Long.toString(id)});
    }

    private static Track trackFrom(Cursor c) {
        return new Track(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3), c.getString(4));
    }

    private static ContentValues fieldValues(FieldRecord record) {
        ContentValues v = new ContentValues();
        v.put("name", safe(record.name, "Field record"));
        v.put("category", record.category == null ? "" : record.category);
        v.put("mineral", record.mineral == null ? "" : record.mineral);
        v.put("sample_id", record.sampleId == null ? "" : record.sampleId);
        v.put("notes", record.notes == null ? "" : record.notes);
        v.put("lat", record.lat);
        v.put("lon", record.lon);
        if (Double.isFinite(record.altitude)) v.put("altitude", record.altitude); else v.putNull("altitude");
        if (Float.isFinite(record.accuracy) && record.accuracy >= 0f) v.put("accuracy", record.accuracy); else v.putNull("accuracy");
        v.put("photo_uri", record.photoUri == null ? "" : record.photoUri);
        v.put("created_at", record.createdAt);
        v.put("updated_at", record.updatedAt);
        return v;
    }

    private static FieldRecord fieldFrom(Cursor c) {
        return new FieldRecord(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5),
                c.getDouble(6), c.getDouble(7), c.isNull(8) ? Double.NaN : c.getDouble(8),
                c.isNull(9) ? -1f : c.getFloat(9), c.getString(10), c.getLong(11), c.getLong(12));
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    public static final class Track {
        public final long id;
        public final String name;
        public final long startedAt;
        public final long endedAt;
        public final String status;
        Track(long id, String name, long startedAt, long endedAt, String status) {
            this.id = id; this.name = name; this.startedAt = startedAt; this.endedAt = endedAt; this.status = status;
        }
    }

    public static final class FieldRecord {
        public long id;
        public String name;
        public String category;
        public String mineral;
        public String sampleId;
        public String notes;
        public double lat;
        public double lon;
        public double altitude;
        public float accuracy;
        public String photoUri;
        public long createdAt;
        public long updatedAt;

        public FieldRecord(long id, String name, String category, String mineral, String sampleId, String notes,
                           double lat, double lon, double altitude, float accuracy, String photoUri,
                           long createdAt, long updatedAt) {
            this.id=id; this.name=name; this.category=category; this.mineral=mineral; this.sampleId=sampleId; this.notes=notes;
            this.lat=lat; this.lon=lon; this.altitude=altitude; this.accuracy=accuracy; this.photoUri=photoUri;
            this.createdAt=createdAt; this.updatedAt=updatedAt;
        }
    }

    public static final class Area {
        public final long id;
        public final String name;
        public final String notes;
        public final long createdAt;
        public final List<GeoMath.Point> points;
        Area(long id, String name, String notes, long createdAt, List<GeoMath.Point> points) {
            this.id=id; this.name=name; this.notes=notes; this.createdAt=createdAt; this.points=points;
        }
    }
}
