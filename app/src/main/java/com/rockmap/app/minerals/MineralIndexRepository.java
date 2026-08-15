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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

public final class MineralIndexRepository {
    private static final int MAX_UNCOMPRESSED_BYTES = 64 * 1024 * 1024;
    private static final String MRDS_ID = "minerals";
    private static final String LOCALITY_ID = "mineral_localities";

    public interface Callback {
        void onResult(MineralSearchEngine.SearchResult result);
        void onError(String message);
    }

    private final OfflineDataManager dataManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile List<MineralRecord> cachedRecords;
    private volatile String cachedPath = "";
    private volatile int cachedRecordCount;
    private volatile int cachedLocalityCount;

    public MineralIndexRepository(Context context, OfflineDataManager dataManager) {
        this.dataManager = dataManager;
    }

    public boolean isAvailable() {
        return dataManager.getActiveFile(MRDS_ID) != null;
    }

    public boolean hasOfficialLocalitySupplement() {
        return dataManager.getActiveFile(LOCALITY_ID) != null;
    }

    public int getCachedRecordCount() {
        return cachedRecordCount;
    }

    public int getCachedLocalityCount() {
        return cachedLocalityCount;
    }

    public void clearCache() {
        cachedRecords = null;
        cachedPath = "";
        cachedRecordCount = 0;
        cachedLocalityCount = 0;
    }

    public void search(String query, Callback callback) {
        search(query, null, callback);
    }

    public void search(String query, MineralSearchEngine.Bounds bounds, Callback callback) {
        new Thread(() -> {
            try {
                List<MineralRecord> records = loadRecords();
                MineralSearchEngine.SearchResult result = MineralSearchEngine.search(
                        records, query, MineralSearchEngine.DEFAULT_LIMIT, bounds);
                mainHandler.post(() -> callback.onResult(result));
            } catch (IllegalArgumentException | IOException | JSONException ex) {
                mainHandler.post(() -> callback.onError(ex.getMessage()));
            } catch (RuntimeException ex) {
                mainHandler.post(() -> callback.onError("Mineral search failed safely."));
            }
        }, "rockmap-mineral-search").start();
    }

    private synchronized List<MineralRecord> loadRecords() throws IOException, JSONException {
        File mrdsFile = dataManager.getActiveFile(MRDS_ID);
        if (mrdsFile == null) throw new IOException("Mineral index is not installed. Open Data and check for update.");
        File localityFile = dataManager.getActiveFile(LOCALITY_ID);
        String pathKey = fileKey(mrdsFile) + "|" + fileKey(localityFile);
        if (cachedRecords != null && pathKey.equals(cachedPath)) return cachedRecords;

        ArrayList<MineralRecord> records = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        loadFile(mrdsFile, false, records, identities);
        int baseCount = records.size();
        if (localityFile != null) loadFile(localityFile, true, records, identities);
        if (baseCount == 0) throw new JSONException("Mineral index contains no usable Colorado records.");

        cachedRecords = records;
        cachedRecordCount = records.size();
        cachedLocalityCount = Math.max(0, records.size() - baseCount);
        cachedPath = pathKey;
        return records;
    }

    private void loadFile(File file, boolean officialLocalities, List<MineralRecord> output,
                          Set<String> identities) throws IOException, JSONException {
        byte[] bytes = readIndexBytes(file);
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (root.optInt("schema", 0) != 1) throw new JSONException("Unsupported mineral index schema.");
        JSONArray items = root.getJSONArray("records");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            double lat = item.getDouble("lat");
            double lon = item.getDouble("lon");
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;

            String id = item.optString("id", "").trim();
            String sourceCode = item.optString("source_code", officialLocalities ? "OFFICIAL_LOCALITY" : MineralRecord.SOURCE_MRDS).trim();
            String identity = sourceCode + ":" + id;
            if (id.isEmpty() || !identities.add(identity)) continue;

            output.add(new MineralRecord(
                    id, item.optString("name", ""), lat, lon,
                    item.optString("status", ""), item.optString("grade", ""),
                    strings(item.optJSONArray("materials")),
                    strings(item.optJSONArray("commodities")),
                    strings(item.optJSONArray("districts")),
                    strings(item.optJSONArray("models")),
                    strings(item.optJSONArray("rocks")),
                    sourceCode,
                    item.optString("evidence_type", officialLocalities
                            ? "Official gemstone/mineral locality" : "Documented mineral occurrence"),
                    item.optString("location_precision", officialLocalities
                            ? "Named locality reference point; not specimen-level" : "MRDS record point; precision varies by record"),
                    item.optString("source_title", officialLocalities
                            ? root.optString("source", "Official mineral-locality reference")
                            : "USGS Mineral Resources Data System (MRDS)"),
                    item.optString("source_note", "")));
        }
    }

    private byte[] readIndexBytes(File file) throws IOException {
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
            return output.toByteArray();
        }
    }

    private static String fileKey(File file) {
        return file == null ? "none" : file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
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
