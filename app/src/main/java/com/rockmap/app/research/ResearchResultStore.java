package com.rockmap.app.research;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Stores only the most recent research result so Field's existing export hub owns export UX. */
public final class ResearchResultStore {
    private static final String RESULT_FILE = "rockmap-research-last.geojson";
    private static final String META_FILE = "rockmap-research-last-meta.json";
    private static final long MAX_RESULT_BYTES = 64L * 1024L * 1024L;

    public static final class Summary {
        public final String title;
        public final int count;
        public final long savedAt;
        Summary(String title, int count, long savedAt) {
            this.title = title;
            this.count = count;
            this.savedAt = savedAt;
        }
    }

    private ResearchResultStore() {}

    public static void save(Context context, String title, String geoJson, int count) throws IOException {
        if (geoJson == null || geoJson.trim().isEmpty()) throw new IOException("Research result was empty.");
        if (geoJson.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw new IOException("Research result exceeds the 64 MB local result limit. Narrow the map area or search filters.");
        }
        File directory = directory(context);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("RockMap could not create the local Research directory.");
        }
        File result = new File(directory, RESULT_FILE);
        File meta = new File(directory, META_FILE);
        write(result, geoJson);
        try {
            JSONObject m = new JSONObject();
            m.put("title", title == null ? "Research result" : title.trim());
            m.put("count", Math.max(0, count));
            m.put("savedAt", System.currentTimeMillis());
            write(meta, m.toString());
        } catch (JSONException ex) {
            throw new IOException("Could not save research metadata.", ex);
        }
    }

    public static boolean exists(Context context) {
        File file = new File(directory(context), RESULT_FILE);
        return file.isFile() && file.length() > 20L;
    }

    public static Summary summary(Context context) {
        if (!exists(context)) return new Summary("No research result", 0, 0L);
        File meta = new File(directory(context), META_FILE);
        try {
            JSONObject m = new JSONObject(read(meta));
            return new Summary(m.optString("title", "Research result"),
                    m.optInt("count", 0), m.optLong("savedAt", 0L));
        } catch (Exception ex) {
            return new Summary("Research result", 0, 0L);
        }
    }

    public static String geoJson(Context context) throws IOException {
        File file = new File(directory(context), RESULT_FILE);
        if (!file.isFile()) throw new IOException("No saved research result is available.");
        return read(file);
    }

    public static String csv(Context context) throws IOException {
        try {
            JSONObject root = new JSONObject(geoJson(context));
            JSONArray features = root.optJSONArray("features");
            StringBuilder out = new StringBuilder();
            out.append("object_id,unit_name,sgmc_label,original_label,age_min,age_max,generalized_lithology,major1,major2,major3,reference_id,reference,digital_url,source_doi\n");
            if (features == null) return out.toString();
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.optJSONObject(i);
                JSONObject p = feature == null ? null : feature.optJSONObject("properties");
                if (p == null) continue;
                appendCsv(out, p.optString("OBJECTID"));
                appendCsv(out, p.optString("UNIT_NAME"));
                appendCsv(out, p.optString("SGMC_LABEL"));
                appendCsv(out, p.optString("ORIG_LABEL"));
                appendCsv(out, p.optString("AGE_MIN"));
                appendCsv(out, p.optString("AGE_MAX"));
                appendCsv(out, p.optString("GENERALIZED_LITH"));
                appendCsv(out, p.optString("MAJOR1"));
                appendCsv(out, p.optString("MAJOR2"));
                appendCsv(out, p.optString("MAJOR3"));
                appendCsv(out, p.optString("REF_ID"));
                appendCsv(out, p.optString("REFERENCE"));
                appendCsv(out, p.optString("DIGITAL_URL"));
                appendCsv(out, p.optString("rockmap_source_doi"));
                out.setLength(out.length() - 1);
                out.append('\n');
            }
            return out.toString();
        } catch (JSONException ex) {
            throw new IOException("Saved research GeoJSON is invalid.", ex);
        }
    }

    public static void clear(Context context) {
        new File(directory(context), RESULT_FILE).delete();
        new File(directory(context), META_FILE).delete();
    }

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "research");
    }

    private static void appendCsv(StringBuilder out, String value) {
        String text = value == null ? "" : value;
        out.append('"').append(text.replace("\"", "\"\"")).append("\",");
    }

    private static void write(File file, String content) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static String read(File file) throws IOException {
        if (!file.isFile()) throw new IOException("File not found.");
        if (file.length() > MAX_RESULT_BYTES) throw new IOException("Research result exceeds the safe local export size.");
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int n = input.read(bytes, offset, bytes.length - offset);
                if (n < 0) break;
                offset += n;
            }
            if (offset != bytes.length) throw new IOException("Research result could not be read completely.");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
