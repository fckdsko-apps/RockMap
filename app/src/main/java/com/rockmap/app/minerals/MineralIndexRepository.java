package com.rockmap.app.minerals;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.rockmap.app.mines.HistoricMineCatalog;
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
    private static final int MAX_UNCOMPRESSED_BYTES = 96 * 1024 * 1024;
    private static final String MRDS_ID = "minerals";
    private static final String LOCALITY_ID = "mineral_localities";
    private static final String EVIDENCE_ID = "mineral_evidence";

    public interface Callback {
        void onResult(MineralSearchEngine.SearchResult result);
        void onError(String message);
    }

    public interface RecordListCallback {
        void onResult(List<MineralRecord> records);
        void onError(String message);
    }

    public interface NearbyEvidenceCallback {
        void onResult(List<HistoricMineCatalog.NearbyEvidence> evidence);
        void onError(String message);
    }

    public interface AreaAnalysisCallback {
        void onResult(MineralAreaAnalyzer.AnalysisResult result);
        void onError(String message);
    }

    public interface AreaEvidenceCallback {
        void onResult(List<MineralAreaAnalyzer.EvidencePoint> evidence);
        void onError(String message);
    }

    private final OfflineDataManager dataManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile List<MineralRecord> cachedRecords;
    private volatile String cachedPath = "";
    private volatile int cachedRecordCount;
    private volatile int cachedLocalityCount;
    private volatile int cachedEvidenceCount;

    public MineralIndexRepository(Context context, OfflineDataManager dataManager) {
        this.dataManager = dataManager;
    }

    public boolean isAvailable() {
        return dataManager.getActiveFile(MRDS_ID) != null;
    }

    public boolean hasOfficialLocalitySupplement() {
        return dataManager.getActiveFile(LOCALITY_ID) != null;
    }

    public boolean hasExpandedEvidence() {
        return dataManager.getActiveFile(EVIDENCE_ID) != null;
    }

    public int getCachedRecordCount() {
        return cachedRecordCount;
    }

    public int getCachedLocalityCount() {
        return cachedLocalityCount;
    }

    public int getCachedEvidenceCount() {
        return cachedEvidenceCount;
    }

    public void clearCache() {
        cachedRecords = null;
        cachedPath = "";
        cachedRecordCount = 0;
        cachedLocalityCount = 0;
        cachedEvidenceCount = 0;
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

    public void analyzeArea(MineralSearchEngine.Bounds bounds, AreaAnalysisCallback callback) {
        new Thread(() -> {
            try {
                MineralAreaAnalyzer.AnalysisResult result =
                        MineralAreaAnalyzer.analyze(loadRecords(), bounds);
                mainHandler.post(() -> callback.onResult(result));
            } catch (IllegalArgumentException | IOException | JSONException ex) {
                mainHandler.post(() -> callback.onError(ex.getMessage()));
            } catch (RuntimeException ex) {
                mainHandler.post(() -> callback.onError("Selected-area mineral analysis failed safely."));
            }
        }, "rockmap-mineral-area-analysis").start();
    }

    public void loadAreaEvidence(MineralSearchEngine.Bounds bounds, String mineralKey,
                                 AreaEvidenceCallback callback) {
        new Thread(() -> {
            try {
                List<MineralAreaAnalyzer.EvidencePoint> evidence =
                        MineralAreaAnalyzer.evidenceFor(loadRecords(), bounds, mineralKey);
                mainHandler.post(() -> callback.onResult(evidence));
            } catch (IllegalArgumentException | IOException | JSONException ex) {
                mainHandler.post(() -> callback.onError(ex.getMessage()));
            } catch (RuntimeException ex) {
                mainHandler.post(() -> callback.onError("Mineral heatmap evidence lookup failed safely."));
            }
        }, "rockmap-mineral-area-heatmap").start();
    }

    public void loadHistoricMines(RecordListCallback callback) {
        new Thread(() -> {
            try {
                List<MineralRecord> records = loadRecords();
                ArrayList<MineralRecord> mines = new ArrayList<>();
                for (MineralRecord record : records) {
                    if (HistoricMineCatalog.isMineRecord(record)) mines.add(record);
                }
                mainHandler.post(() -> callback.onResult(mines));
            } catch (IOException | JSONException ex) {
                mainHandler.post(() -> callback.onError(ex.getMessage()));
            } catch (RuntimeException ex) {
                mainHandler.post(() -> callback.onError("Historic mine overlay failed safely."));
            }
        }, "rockmap-historic-mine-load").start();
    }

    public void findNearbyHistoricMineEvidence(MineralRecord origin, double maxMeters, int maxResults,
                                               NearbyEvidenceCallback callback) {
        new Thread(() -> {
            try {
                List<HistoricMineCatalog.NearbyEvidence> evidence =
                        HistoricMineCatalog.nearbyEvidence(loadRecords(), origin, maxMeters, maxResults);
                mainHandler.post(() -> callback.onResult(evidence));
            } catch (IOException | JSONException ex) {
                mainHandler.post(() -> callback.onError(ex.getMessage()));
            } catch (RuntimeException ex) {
                mainHandler.post(() -> callback.onError("Nearby mineral evidence lookup failed safely."));
            }
        }, "rockmap-historic-mine-nearby").start();
    }

    private synchronized List<MineralRecord> loadRecords() throws IOException, JSONException {
        File mrdsFile = dataManager.getActiveFile(MRDS_ID);
        if (mrdsFile == null) throw new IOException("Mineral index is not installed. Open Data and check for update.");
        File localityFile = dataManager.getActiveFile(LOCALITY_ID);
        File evidenceFile = dataManager.getActiveFile(EVIDENCE_ID);
        String pathKey = fileKey(mrdsFile) + "|" + fileKey(localityFile) + "|" + fileKey(evidenceFile);
        if (cachedRecords != null && pathKey.equals(cachedPath)) return cachedRecords;

        ArrayList<MineralRecord> records = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        loadFile(mrdsFile, "mrds", records, identities);
        int baseCount = records.size();
        if (localityFile != null) loadFile(localityFile, "locality", records, identities);
        int afterLocalities = records.size();
        if (evidenceFile != null) loadFile(evidenceFile, "evidence", records, identities);
        if (baseCount == 0) throw new JSONException("Mineral index contains no usable Colorado records.");

        cachedRecords = records;
        cachedRecordCount = records.size();
        cachedLocalityCount = Math.max(0, afterLocalities - baseCount);
        cachedEvidenceCount = Math.max(0, records.size() - afterLocalities);
        cachedPath = pathKey;
        return records;
    }

    private void loadFile(File file, String kind, List<MineralRecord> output,
                          Set<String> identities) throws IOException, JSONException {
        byte[] bytes = readIndexBytes(file);
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (root.optInt("schema", 0) != 1) throw new JSONException("Unsupported mineral index schema.");
        JSONArray items = root.getJSONArray("records");
        boolean officialLocalities = "locality".equals(kind);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            double lat = item.getDouble("lat");
            double lon = item.getDouble("lon");
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;

            String id = item.optString("id", "").trim();
            String sourceCode = item.optString("source_code",
                    officialLocalities ? "OFFICIAL_LOCALITY" : MineralRecord.SOURCE_MRDS).trim();
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
                            ? "Named locality reference point; not specimen-level" : "Record point; precision varies by source"),
                    compactSourceTitle(sourceCode, item.optString("source_title", officialLocalities
                            ? root.optString("source", "Official mineral-locality reference")
                            : "USGS Mineral Resources Data System (MRDS)")),
                    item.optString("source_reliability", ""),
                    item.optString("source_note", "")));
        }
    }

    private static String compactSourceTitle(String sourceCode, String fallback) {
        String code = sourceCode == null ? "" : sourceCode.trim().toUpperCase(java.util.Locale.US);
        if ("CGS_GEMSTONES".equals(code)) return "CGS Gemstones of Colorado";
        if ("CGS_TEACHERS".equals(code)) return "CGS aquamarine locality reference";
        if ("USGS_PUB_70021621".equals(code)) return "USGS publication 70021621";
        return fallback == null ? "" : fallback.trim();
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
