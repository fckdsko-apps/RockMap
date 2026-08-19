package com.rockmap.app.places;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.rockmap.app.offline.DataFileSpec;
import com.rockmap.app.offline.DataManifest;
import com.rockmap.app.offline.OfflineDataManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

public final class PlaceIndexRepository implements AutoCloseable {
    public interface Callback {
        void onResult(List<PlaceSearchEngine.Match> matches);
        void onError(String message);
    }

    static final String INDEX_FILE = "rockmap_place_index.tsv.gz";
    private static final String INDEX_DIR = "place-search";
    private static final int MAX_RECORDS = 80_000;
    private static final long MAX_DECOMPRESSED_CHARS = 30_000_000L;

    private final Context context;
    private final OfflineDataManager offlineDataManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile PlaceSearchEngine engine;
    private volatile String loadedBaseSha;
    private volatile int recordCount;

    public PlaceIndexRepository(Context context) {
        this.context = context.getApplicationContext();
        this.offlineDataManager = new OfflineDataManager(this.context);
        // Fresh install / app restart: build the index automatically if the installed
        // basemap is present and the matching local index does not exist yet.
        PlaceIndexWorker.enqueueIfNeeded(this.context);
    }

    public void search(String query, int limit, Callback callback) {
        executor.execute(() -> {
            try {
                String activeSha = activeBaseSha();
                if (activeSha == null) {
                    postError(callback, "Offline basemap data is not installed yet. Open Data and install/check the current pack first.");
                    return;
                }
                if (!isCurrentIndex(context, activeSha)) {
                    clearLoaded();
                    PlaceIndexWorker.enqueue(context);
                    postError(callback, "Preparing offline search from the installed basemap… Try again shortly.");
                    return;
                }
                ensureLoaded(activeSha);
                List<PlaceSearchEngine.Match> matches = engine.search(query, limit);
                mainHandler.post(() -> callback.onResult(matches));
            } catch (IOException | RuntimeException ex) {
                clearLoaded();
                PlaceIndexWorker.enqueue(context);
                postError(callback, "Offline place index needs to be rebuilt: " + safeMessage(ex));
            }
        });
    }

    public int getRecordCount() {
        return recordCount;
    }

    public boolean isReady() {
        String activeSha = activeBaseSha();
        return activeSha != null && isCurrentIndex(context, activeSha);
    }

    private void ensureLoaded(String activeSha) throws IOException {
        if (engine != null && activeSha.equalsIgnoreCase(loadedBaseSha)) return;
        synchronized (this) {
            if (engine != null && activeSha.equalsIgnoreCase(loadedBaseSha)) return;
            ArrayList<PlaceRecord> records = loadFile(indexFile(context), activeSha);
            engine = new PlaceSearchEngine(records);
            loadedBaseSha = activeSha.toLowerCase(Locale.US);
            recordCount = records.size();
        }
    }

    private ArrayList<PlaceRecord> loadFile(File file, String expectedBaseSha) throws IOException {
        ArrayList<PlaceRecord> records = new ArrayList<>();
        long chars = 0L;
        try (FileInputStream raw = new FileInputStream(file);
             GZIPInputStream gzip = new GZIPInputStream(raw, 64 * 1024);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gzip, StandardCharsets.UTF_8), 64 * 1024)) {
            String header = reader.readLine();
            if (!PmtilesPlaceIndexer.INDEX_HEADER.equals(header)) {
                throw new IOException("unsupported local place-index header");
            }
            String baseLine = reader.readLine();
            String expected = PmtilesPlaceIndexer.BASE_SHA_PREFIX + expectedBaseSha.toLowerCase(Locale.US);
            if (baseLine == null || !expected.equals(baseLine.toLowerCase(Locale.US))) {
                throw new IOException("local place index belongs to a different basemap");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                chars += line.length() + 1L;
                if (chars > MAX_DECOMPRESSED_CHARS) {
                    throw new IOException("local place index exceeds safe decompressed size");
                }
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                if (records.size() >= MAX_RECORDS) {
                    throw new IOException("local place index exceeds safe record count");
                }
                PlaceRecord record = parseLine(line);
                if (record != null) records.add(record);
            }
        }
        if (records.size() < 500) throw new IOException("local place index is unexpectedly small");
        return records;
    }

    private PlaceRecord parseLine(String line) throws IOException {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 7) throw new IOException("malformed local place-index row");
        try {
            String name = fields[0];
            String kind = fields[1];
            String context = fields[2];
            double latitude = Double.parseDouble(fields[3]);
            double longitude = Double.parseDouble(fields[4]);
            List<String> aliases = fields[5].isEmpty()
                    ? Collections.emptyList() : Arrays.asList(fields[5].split("\\|"));
            int importance = Integer.parseInt(fields[6]);
            return new PlaceRecord(name, kind, context, latitude, longitude, aliases, importance);
        } catch (IllegalArgumentException ex) {
            throw new IOException("invalid local place-index row", ex);
        }
    }

    private String activeBaseSha() {
        DataManifest manifest = offlineDataManager.getActiveManifest();
        if (manifest == null || offlineDataManager.getActiveFile("base") == null) return null;
        DataFileSpec spec = manifest.find("base");
        if (spec == null || spec.sha256 == null || !spec.sha256.matches("(?i)[0-9a-f]{64}")) return null;
        return spec.sha256.toLowerCase(Locale.US);
    }

    static File indexFile(Context context) {
        return new File(indexDirectory(context), INDEX_FILE);
    }

    private static File indexDirectory(Context context) {
        File directory = new File(context.getApplicationContext().getFilesDir(), INDEX_DIR);
        if (!directory.isDirectory()) directory.mkdirs();
        return directory;
    }

    static boolean isCurrentIndex(Context context, String expectedBaseSha) {
        if (expectedBaseSha == null || !expectedBaseSha.matches("(?i)[0-9a-f]{64}")) return false;
        File file = indexFile(context);
        if (!file.isFile() || file.length() <= 0 || file.length() > 8L * 1024L * 1024L) return false;
        try (FileInputStream raw = new FileInputStream(file);
             GZIPInputStream gzip = new GZIPInputStream(raw, 16 * 1024);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gzip, StandardCharsets.UTF_8), 16 * 1024)) {
            String header = reader.readLine();
            String base = reader.readLine();
            return PmtilesPlaceIndexer.INDEX_HEADER.equals(header)
                    && (PmtilesPlaceIndexer.BASE_SHA_PREFIX + expectedBaseSha.toLowerCase(Locale.US))
                    .equals(base == null ? "" : base.toLowerCase(Locale.US));
        } catch (IOException ex) {
            return false;
        }
    }

    private synchronized void clearLoaded() {
        engine = null;
        loadedBaseSha = null;
        recordCount = 0;
    }

    private void postError(Callback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.trim().isEmpty() ? ex.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
