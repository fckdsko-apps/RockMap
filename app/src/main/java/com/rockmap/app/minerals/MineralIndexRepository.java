package com.rockmap.app.minerals;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.rockmap.app.offline.OfflineDataManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

public final class MineralIndexRepository {
    private static final int MAX_UNCOMPRESSED_BYTES = 64 * 1024 * 1024;

    public interface Callback {
        void onResult(MineralSearchEngine.SearchResult result);
        void onError(String message);
    }

    private final OfflineDataManager dataManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile List<MineralRecord> cachedRecords;
    private volatile String cachedPath = "";
    private volatile int cachedRecordCount;

    public MineralIndexRepository(Context context, OfflineDataManager dataManager) {
        this.dataManager = dataManager;
    }

    public boolean isAvailable() {
        return dataManager.getActiveFile("minerals") != null;
    }

    public int getCachedRecordCount() {
        return cachedRecordCount;
    }

    public void clearCache() {
        cachedRecords = null;
        cachedPath = "";
        cachedRecordCount = 0;
    }

    public void search(String query, Callback callback) {
        new Thread(() -> {
            try {
                List<MineralRecord> records = loadRecords();
                MineralSearchEngine.SearchResult result = MineralSearchEngine.search(
                        records, query, MineralSearchEngine.DEFAULT_LIMIT);
                mainHandler.post(() -> callback.onResult(result));
            } catch (IllegalArgumentException | IOException | JSONException ex) {
                mainHandler.post(() -> callback.onError(ex.getMessage()));
            } catch (RuntimeException ex) {
                mainHandler.post(() -> callback.onError("Mineral search failed safely."));
            }
        }, "rockmap-mineral-search").start();
    }

    private synchronized List<MineralRecord> loadRecords() throws IOException, JSONException {
        File file = dataManager.getActiveFile("minerals");
        if (file == null) throw new IOException("Mineral index is not installed. Open Data and check for update.");
        String pathKey = file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
        if (cachedRecords != null && pathKey.equals(cachedPath)) return cachedRecords;

        byte[] bytes;
        try (InputStream fileInput = new FileInputStream(file);
             InputStream input = file.getName().endsWith(".gz") ? new GZIPInputStream(fileInput) : fileInput;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[64 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_UNCOMPRESSED_BYTES) throw new IOException("Mineral index exceeds the safe in-app size limit.");
                output.write(buffer, 0, read);
            }
            bytes = output.toByteArray();
        }

        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (root.optInt("schema", 0) != 1) throw new JSONException("Unsupported mineral index schema.");
        JSONArray items = root.getJSONArray("records");
        ArrayList<MineralRecord> records = new ArrayList<>(items.length());
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            double lat = item.getDouble("lat");
            double lon = item.getDouble("lon");
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;
            records.add(new MineralRecord(
                    item.optString("id", ""), item.optString("name", ""), lat, lon,
                    item.optString("status", ""), item.optString("grade", ""),
                    strings(item.optJSONArray("materials")),
                    strings(item.optJSONArray("commodities")),
                    strings(item.optJSONArray("districts")),
                    strings(item.optJSONArray("models")),
                    strings(item.optJSONArray("rocks"))));
        }
        if (records.isEmpty()) throw new JSONException("Mineral index contains no usable Colorado records.");
        cachedRecords = records;
        cachedRecordCount = records.size();
        cachedPath = pathKey;
        return records;
    }

    private static List<String> strings(JSONArray array) {
        ArrayList<String> out = new ArrayList<>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }
}
