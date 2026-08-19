package com.rockmap.app.places;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads the compact search index bundled in the APK; no statewide device scan is performed.
 *
 * The build generator writes rockmap_place_index.tsv.gz. Android's resource packager treats
 * .gz assets specially: it expands the gzip payload and stores it in the APK under the base
 * filename, so the runtime asset is rockmap_place_index.tsv and is already plain UTF-8 text.
 */
public final class PlaceIndexRepository implements AutoCloseable {
    public interface Callback {
        void onResult(List<PlaceSearchEngine.Match> matches);
        void onError(String message);
    }

    private static final String ASSET = "rockmap_place_index.tsv";
    static final String INDEX_HEADER = "# RockMap place index v3";
    private static final int MAX_RECORDS = 100_000;
    private static final long MAX_DECOMPRESSED_CHARS = 40_000_000L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile PlaceSearchEngine engine;
    private volatile String loadError;
    private volatile int recordCount;

    public PlaceIndexRepository(Context context) {
        this.context = context.getApplicationContext();
        // Upgrades from the first Alpha 6.6 attempt may still have a long-running unique
        // WorkManager job recorded. Cancel it immediately; this APK never needs that scan.
        PlaceIndexWorker.cancelLegacy(this.context);
        // Warm the small bundled index off the UI thread so Find is normally instant even
        // on the first tap after a fresh install or APK upgrade.
        executor.execute(this::ensureLoaded);
    }

    public void search(String query, int limit, Callback callback) {
        executor.execute(() -> {
            try {
                ensureLoaded();
                if (engine == null) {
                    postError(callback, loadError == null
                            ? "Bundled offline place index is unavailable." : loadError);
                    return;
                }
                List<PlaceSearchEngine.Match> matches = engine.search(query, limit);
                mainHandler.post(() -> callback.onResult(matches));
            } catch (RuntimeException ex) {
                postError(callback, "Offline place search failed safely: " + safeMessage(ex));
            }
        });
    }

    public int getRecordCount() {
        return recordCount;
    }

    public boolean isReady() {
        if (engine != null) return true;
        if (loadError != null) return false;
        try (InputStream ignored = context.getAssets().open(ASSET)) {
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private void ensureLoaded() {
        if (engine != null || loadError != null) return;
        synchronized (this) {
            if (engine != null || loadError != null) return;
            try {
                ArrayList<PlaceRecord> records = loadAsset();
                engine = new PlaceSearchEngine(records);
                recordCount = records.size();
            } catch (IOException | RuntimeException ex) {
                loadError = "Bundled offline place index could not be loaded: " + safeMessage(ex);
            }
        }
    }

    private ArrayList<PlaceRecord> loadAsset() throws IOException {
        ArrayList<PlaceRecord> records = new ArrayList<>();
        long chars = 0L;
        try (InputStream raw = context.getAssets().open(ASSET);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(raw, StandardCharsets.UTF_8), 64 * 1024)) {
            String header = reader.readLine();
            if (!INDEX_HEADER.equals(header)) {
                throw new IOException("unsupported place-index header");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                chars += line.length() + 1L;
                if (chars > MAX_DECOMPRESSED_CHARS) {
                    throw new IOException("place index exceeds safe decompressed size");
                }
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                if (records.size() >= MAX_RECORDS) {
                    throw new IOException("place index exceeds safe record count");
                }
                PlaceRecord record = parseLine(line);
                if (record != null) records.add(record);
            }
        }
        if (records.size() < 2_000) {
            throw new IOException("place index is unexpectedly small");
        }
        return records;
    }

    private PlaceRecord parseLine(String line) throws IOException {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 7) throw new IOException("malformed place-index row");
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
            throw new IOException("invalid place-index row", ex);
        }
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
