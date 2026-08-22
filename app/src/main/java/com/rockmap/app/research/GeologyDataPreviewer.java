package com.rockmap.app.research;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.rockmap.app.BuildConfig;
import com.rockmap.app.offline.DataValidators;

import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Fetches only geology-manifest.json so size is disclosed before the user authorizes the asset. */
public final class GeologyDataPreviewer {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_MANIFEST_BYTES = 1_000_000;

    private GeologyDataPreviewer() {}

    public interface Callback {
        void onPreview(Preview preview);
        void onError(String message);
    }

    public static final class Preview {
        public final String version;
        public final String message;
        public final long downloadBytes;
        public final long installedBytes;
        public final int recordCount;
        public final boolean published;
        public final boolean needsDownload;

        Preview(String version, String message, long downloadBytes, long installedBytes,
                int recordCount, boolean published, boolean needsDownload) {
            this.version = safe(version);
            this.message = safe(message);
            this.downloadBytes = downloadBytes;
            this.installedBytes = installedBytes;
            this.recordCount = recordCount;
            this.published = published;
            this.needsDownload = needsDownload;
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
            } catch (IOException | JSONException | NoSuchAlgorithmException | RuntimeException ex) {
                String message = ex.getMessage() == null
                        ? "Could not inspect the Colorado geology pack safely." : ex.getMessage();
                main.post(() -> callback.onError(message));
            }
        }, "rockmap-geology-preview");
        worker.start();
    }

    private static Preview load(Context context, String manifestUrl)
            throws IOException, JSONException, NoSuchAlgorithmException {
        if (!DataValidators.isSafeHttpsUrl(manifestUrl)) {
            throw new IOException("Geology manifest URL is not configured safely.");
        }
        String raw = downloadSmallText(manifestUrl);
        GeologyManifest manifest = GeologyManifestParser.parse(raw);
        if (!manifest.isPublished()) {
            return new Preview("", manifest.message, 0L, 0L, 0, false, false);
        }

        GeologyDataManager manager = new GeologyDataManager(context);
        GeologyManifest active = manager.getActiveManifest();
        File activeFile = manager.getActiveDatabaseFile();
        boolean current = active != null && activeFile != null
                && active.database.sha256.equalsIgnoreCase(manifest.database.sha256)
                && active.database.bytes == manifest.database.bytes
                && sha256(activeFile).equalsIgnoreCase(manifest.database.sha256);
        return new Preview(manifest.version, manifest.message,
                manifest.asset.bytes, manifest.database.bytes, manifest.source.recordCount,
                true, !current);
    }

    private static String downloadSmallText(String url) throws IOException {
        HttpURLConnection connection = openHttps(url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Geology metadata returned HTTP " + code + ".");
            }
            long length = connection.getContentLengthLong();
            if (length > MAX_MANIFEST_BYTES) throw new IOException("Geology metadata is too large.");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_MANIFEST_BYTES) throw new IOException("Geology metadata exceeded its size limit.");
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
                throw new IOException("Geology metadata redirected to an unsafe URL.");
            }
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("User-Agent", "RockMap/" + BuildConfig.VERSION_NAME);
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Geology metadata redirect had no destination.");
                }
                current = new URL(current, location);
                continue;
            }
            return connection;
        }
        throw new IOException("Geology metadata redirected too many times.");
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
