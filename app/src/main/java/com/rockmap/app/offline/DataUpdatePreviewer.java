package com.rockmap.app.offline;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.rockmap.app.BuildConfig;

import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Fetches only the small RockMap manifest so the app can disclose offline-data download size
 * before the user authorizes the actual asset download.
 */
public final class DataUpdatePreviewer {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_MANIFEST_BYTES = 1_000_000;

    private DataUpdatePreviewer() {}

    public interface Callback {
        void onPreview(Preview preview);
        void onError(String message);
    }

    public static final class Preview {
        public final String pack;
        public final String version;
        public final String status;
        public final String message;
        public final int fileCount;
        public final int estimatedDownloadFileCount;
        public final long totalPackBytes;
        public final long estimatedDownloadBytes;
        public final boolean renderable;

        Preview(String pack, String version, String status, String message,
                int fileCount, int estimatedDownloadFileCount,
                long totalPackBytes, long estimatedDownloadBytes, boolean renderable) {
            this.pack = safe(pack);
            this.version = safe(version);
            this.status = safe(status);
            this.message = safe(message);
            this.fileCount = fileCount;
            this.estimatedDownloadFileCount = estimatedDownloadFileCount;
            this.totalPackBytes = totalPackBytes;
            this.estimatedDownloadBytes = estimatedDownloadBytes;
            this.renderable = renderable;
        }

        private static String safe(String value) {
            return value == null ? "" : value;
        }
    }

    public static void preview(Context context, String manifestUrl, Callback callback) {
        Context appContext = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Thread worker = new Thread(() -> {
            try {
                Preview preview = load(appContext, manifestUrl);
                main.post(() -> callback.onPreview(preview));
            } catch (IOException | JSONException | ArithmeticException ex) {
                String message = ex.getMessage() == null ? "Could not inspect the update." : ex.getMessage();
                main.post(() -> callback.onError(message));
            } catch (RuntimeException ex) {
                String message = ex.getMessage() == null ? "Could not inspect the update safely." : ex.getMessage();
                main.post(() -> callback.onError(message));
            }
        }, "rockmap-update-preview");
        worker.start();
    }

    private static Preview load(Context context, String manifestUrl)
            throws IOException, JSONException {
        if (!DataValidators.isSafeHttpsUrl(manifestUrl)) {
            throw new IOException("Data manifest URL is not configured safely.");
        }
        String raw = downloadSmallText(manifestUrl);
        DataManifest manifest = DataManifestParser.parse(raw);
        if (!manifest.isRenderable()) {
            return new Preview(manifest.pack, manifest.version, manifest.status, manifest.message,
                    0, 0, 0L, 0L, false);
        }

        File mapsDir = new File(context.getFilesDir(), "maps");
        long totalBytes = 0L;
        long estimatedBytes = 0L;
        int estimatedFiles = 0;
        for (DataFileSpec spec : manifest.files) {
            totalBytes = Math.addExact(totalBytes, spec.bytes);
            File local = new File(mapsDir, spec.fileName);
            if (!local.isFile() || local.length() != spec.bytes) {
                estimatedBytes = Math.addExact(estimatedBytes, spec.bytes);
                estimatedFiles++;
            }
        }
        return new Preview(manifest.pack, manifest.version, manifest.status, manifest.message,
                manifest.files.size(), estimatedFiles, totalBytes, estimatedBytes, true);
    }

    private static String downloadSmallText(String url) throws IOException {
        HttpURLConnection connection = openHttps(url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Update metadata returned HTTP " + code + ".");
            }
            long length = connection.getContentLengthLong();
            if (length > MAX_MANIFEST_BYTES) throw new IOException("Update metadata is too large.");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_MANIFEST_BYTES) throw new IOException("Update metadata exceeded its size limit.");
                    output.write(buffer, 0, read);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openHttps(String url) throws IOException {
        URL current = new URL(url);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!DataValidators.isSafeHttpsUrl(current.toString())) {
                throw new IOException("Update metadata redirected to an unsafe URL.");
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("User-Agent", "RockMap/" + BuildConfig.VERSION_NAME);
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Update metadata redirect had no destination.");
                }
                current = new URL(current, location);
                continue;
            }
            return connection;
        }
        throw new IOException("Update metadata redirected too many times.");
    }
}
