package com.rockmap.app.field;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Durable user-saved Research snapshots associated with one Prospecting Area. */
public final class ProspectingAreaResearchStore {
    private static final String PREFS = "rockmap-prospecting-area-research-v1";
    private static final String KEY_PREFIX = "area.";
    private static final int MAX_SNAPSHOTS_PER_AREA = 50;

    private ProspectingAreaResearchStore() {}

    public static final class Snapshot {
        public long savedAt;
        public String dataset = "";
        public String title = "";
        public String summary = "";
        public String mineral = "";
        public String source = "";
        public String version = "";
        public double south = Double.NaN;
        public double west = Double.NaN;
        public double north = Double.NaN;
        public double east = Double.NaN;

        public String compactLabel() {
            String label = dataset == null || dataset.trim().isEmpty() ? "Research" : dataset.trim();
            if (mineral != null && !mineral.trim().isEmpty()) label += " — " + mineral.trim();
            return label;
        }
    }

    public static void save(Context context, long areaId, Snapshot snapshot) {
        if (context == null || areaId <= 0L || snapshot == null) return;
        if (snapshot.savedAt <= 0L) snapshot.savedAt = System.currentTimeMillis();
        JSONArray array = readArray(context, areaId);
        try {
            array.put(toJson(snapshot));
            while (array.length() > MAX_SNAPSHOTS_PER_AREA) {
                JSONArray trimmed = new JSONArray();
                for (int i = 1; i < array.length(); i++) trimmed.put(array.opt(i));
                array = trimmed;
            }
            prefs(context).edit().putString(key(areaId), array.toString()).apply();
        } catch (JSONException ignored) {
            // Optional saved Research must never destabilize the field database or map.
        }
    }

    public static List<Snapshot> list(Context context, long areaId) {
        ArrayList<Snapshot> out = new ArrayList<>();
        if (context == null || areaId <= 0L) return out;
        JSONArray array = readArray(context, areaId);
        for (int i = array.length() - 1; i >= 0; i--) {
            JSONObject object = array.optJSONObject(i);
            Snapshot snapshot = fromJson(object);
            if (snapshot != null) out.add(snapshot);
        }
        return out;
    }

    public static void forget(Context context, long areaId) {
        if (context == null || areaId <= 0L) return;
        prefs(context).edit().remove(key(areaId)).apply();
    }

    private static JSONArray readArray(Context context, long areaId) {
        String raw = prefs(context).getString(key(areaId), "");
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try { return new JSONArray(raw); }
        catch (JSONException ex) { return new JSONArray(); }
    }

    private static JSONObject toJson(Snapshot s) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("savedAt", s.savedAt);
        object.put("dataset", clean(s.dataset));
        object.put("title", clean(s.title));
        object.put("summary", clean(s.summary));
        object.put("mineral", clean(s.mineral));
        object.put("source", clean(s.source));
        object.put("version", clean(s.version));
        if (Double.isFinite(s.south)) object.put("south", s.south);
        if (Double.isFinite(s.west)) object.put("west", s.west);
        if (Double.isFinite(s.north)) object.put("north", s.north);
        if (Double.isFinite(s.east)) object.put("east", s.east);
        return object;
    }

    private static Snapshot fromJson(JSONObject object) {
        if (object == null) return null;
        Snapshot s = new Snapshot();
        s.savedAt = object.optLong("savedAt", 0L);
        s.dataset = object.optString("dataset", "");
        s.title = object.optString("title", "");
        s.summary = object.optString("summary", "");
        s.mineral = object.optString("mineral", "");
        s.source = object.optString("source", "");
        s.version = object.optString("version", "");
        s.south = object.optDouble("south", Double.NaN);
        s.west = object.optDouble("west", Double.NaN);
        s.north = object.optDouble("north", Double.NaN);
        s.east = object.optDouble("east", Double.NaN);
        return s;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(long areaId) { return KEY_PREFIX + areaId; }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
