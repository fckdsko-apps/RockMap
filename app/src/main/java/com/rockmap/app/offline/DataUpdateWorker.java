package com.rockmap.app.offline;

import android.content.Context;
import android.os.StatFs;
import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.rockmap.app.BuildConfig;

import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class DataUpdateWorker extends Worker {
    private static final Object UPDATE_LOCK = new Object();
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_MANIFEST_BYTES = 1_000_000;
    private static final long STORAGE_MARGIN_BYTES = 64L * 1024L * 1024L;

    public DataUpdateWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        synchronized (UPDATE_LOCK) {
            return doWorkLocked();
        }
    }

    private Result doWorkLocked() {
        OfflineDataManager manager = new OfflineDataManager(getApplicationContext());
        if (!DataValidators.isSafeHttpsUrl(BuildConfig.DATA_MANIFEST_URL)) {
            return fail(manager, "Data manifest URL is not configured for this GitHub repository.");
        }

        try {
            String rawManifest = downloadSmallText(BuildConfig.DATA_MANIFEST_URL, MAX_MANIFEST_BYTES);
            DataManifest manifest = DataManifestParser.parse(rawManifest);
            if (!manifest.isRenderable()) {
                String message = manifest.message == null || manifest.message.trim().isEmpty()
                        ? "No RockMap offline data pack has been published yet." : manifest.message;
                return fail(manager, message);
            }

            DataManifest activeBeforeUpdate = manager.getActiveManifest();
            DataManifest previousBeforeUpdate = manager.getPreviousManifest();

            // Immutable filenames protect the currently active and rollback snapshots.
            // A manifest may reuse a filename only when its hash/size describe identical content.
            enforceImmutableReferencedFiles(manager, manifest, activeBeforeUpdate, previousBeforeUpdate);

            long bytesNeeded = 0;
            for (DataFileSpec spec : manifest.files) {
                File target = manager.resolve(spec.fileName);
                if (!isAlreadyValid(target, spec)) bytesNeeded = Math.addExact(bytesNeeded, spec.bytes);
            }
            ensureFreeSpace(manager.getMapsDir(), Math.addExact(bytesNeeded, STORAGE_MARGIN_BYTES));

            for (DataFileSpec spec : manifest.files) {
                File target = manager.resolve(spec.fileName);
                if (isAlreadyValid(target, spec)) continue;
                downloadAndVerify(spec, target);
            }

            // Recheck the entire target snapshot before switching the active manifest.
            for (DataFileSpec spec : manifest.files) {
                if (!spec.required) continue;
                File target = manager.resolve(spec.fileName);
                if (!isAlreadyValid(target, spec)) {
                    throw new IOException("Required file failed final verification: " + spec.id);
                }
            }

            // Preserve the last active manifest for runtime rollback before activating the new one.
            File active = manager.getActiveManifestFile();
            if (active.isFile()) {
                byte[] previousBytes = OfflineDataManager.readBytes(active, MAX_MANIFEST_BYTES);
                replaceFileAtomically(manager.getPreviousManifestFile(), previousBytes);
            }

            replaceFileAtomically(active, rawManifest.getBytes(StandardCharsets.UTF_8));

            // Keep everything referenced by the active and previous snapshots. This ensures a
            // semantically valid-but-unrenderable new style can be rolled back on the device.
            cleanupUnreferenced(manager, manifest, manager.getPreviousManifest());
            if (manifest.isBasemapTest()) {
                manager.setLastUpdateStatus("Basemap test pack downloaded and activated: " + manifest.version
                        + ". NOT VERIFIED FOR NAVIGATION; Alpha 3.1 supplies offline labels from the APK, but land status and mining claims are not included.");
            } else {
                manager.setLastUpdateStatus("Verified map snapshot downloaded and activated: " + manifest.version);
            }
            return Result.success();
        } catch (ArithmeticException ex) {
            return fail(manager, "Map update rejected because declared file sizes overflowed safely.");
        } catch (JSONException ex) {
            return fail(manager, "Map manifest rejected: " + ex.getMessage());
        } catch (IOException | NoSuchAlgorithmException ex) {
            return fail(manager, "Map update failed; previous offline data was kept: " + ex.getMessage());
        } catch (RuntimeException ex) {
            return fail(manager, "Map update aborted safely: " + ex.getMessage());
        }
    }

    private Result fail(OfflineDataManager manager, String message) {
        manager.setLastUpdateStatus(message);
        return Result.failure();
    }

    private void enforceImmutableReferencedFiles(OfflineDataManager manager, DataManifest incoming,
                                                  DataManifest active, DataManifest previous)
            throws IOException, NoSuchAlgorithmException {
        for (DataFileSpec spec : incoming.files) {
            File target = manager.resolve(spec.fileName);
            if (!target.isFile() || isAlreadyValid(target, spec)) continue;
            DataFileSpec activeSpec = active == null ? null : active.findByFileName(spec.fileName);
            DataFileSpec previousSpec = previous == null ? null : previous.findByFileName(spec.fileName);
            if (activeSpec != null || previousSpec != null) {
                throw new IOException("Manifest attempted to reuse protected filename with different content: "
                        + spec.fileName);
            }
        }
    }

    private String downloadSmallText(String url, int maxBytes) throws IOException {
        HttpURLConnection connection = openHttps(url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " for manifest");
            long length = connection.getContentLengthLong();
            if (length > maxBytes) throw new IOException("Manifest is too large");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1) {
                    if (isStopped()) throw new IOException("Update was stopped before completion");
                    total += read;
                    if (total > maxBytes) throw new IOException("Manifest exceeded size limit");
                    output.write(buffer, 0, read);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void downloadAndVerify(DataFileSpec spec, File target)
            throws IOException, NoSuchAlgorithmException {
        File part = new File(target.getParentFile(), spec.fileName + ".part");
        if (part.exists() && !part.delete()) throw new IOException("Cannot remove stale partial file: " + spec.id);

        HttpURLConnection connection = openHttps(spec.url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " for " + spec.id);
            long contentLength = connection.getContentLengthLong();
            if (contentLength > spec.bytes) throw new IOException("Server sent oversized file header for " + spec.id);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fileOutput = new FileOutputStream(part);
                 BufferedOutputStream output = new BufferedOutputStream(fileOutput)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (isStopped()) throw new IOException("Update was stopped before completion");
                    total += read;
                    if (total > spec.bytes) throw new IOException("Download exceeded declared size for " + spec.id);
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
                output.flush();
                fileOutput.getFD().sync();
            } catch (IOException ex) {
                part.delete();
                throw ex;
            }

            if (total != spec.bytes) {
                part.delete();
                throw new IOException("Downloaded size mismatch for " + spec.id);
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(spec.sha256)) {
                part.delete();
                throw new IOException("SHA-256 mismatch for " + spec.id);
            }

            moveReplaceAtomically(part, target);
        } finally {
            connection.disconnect();
        }
    }

    private boolean isAlreadyValid(File file, DataFileSpec spec)
            throws IOException, NoSuchAlgorithmException {
        if (!file.isFile() || file.length() != spec.bytes) return false;
        return sha256(file).equalsIgnoreCase(spec.sha256);
    }

    private String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private HttpURLConnection openHttps(String input) throws IOException {
        String current = input;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!DataValidators.isSafeHttpsUrl(current)) throw new IOException("Non-HTTPS URL rejected");
            URL url = new URL(current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("User-Agent", "RockMap/" + BuildConfig.VERSION_NAME);
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect without Location header");
                current = new URL(url, location).toString();
                continue;
            }
            return connection;
        }
        throw new IOException("Too many redirects");
    }

    private void ensureFreeSpace(File directory, long needed) throws IOException {
        StatFs stat = new StatFs(directory.getAbsolutePath());
        long free = stat.getAvailableBytes();
        if (needed < 0 || free < needed) {
            throw new IOException("Not enough internal storage for a safe map update");
        }
    }

    public static void replaceFileAtomically(File target, byte[] bytes) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        if (temp.exists() && !temp.delete()) throw new IOException("Cannot remove stale temporary file");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.getFD().sync();
        }
        moveReplaceAtomically(temp, target);
    }

    private static void moveReplaceAtomically(File source, File target) throws IOException {
        try {
            // POSIX rename is atomic within this app-internal filesystem and replaces target.
            Os.rename(source.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException ex) {
            throw new IOException("Atomic file activation failed: " + ex.getMessage(), ex);
        }
    }

    private void cleanupUnreferenced(OfflineDataManager manager, DataManifest active, DataManifest previous) {
        Set<String> keep = new HashSet<>();
        keep.add(OfflineDataManager.ACTIVE_MANIFEST);
        keep.add(OfflineDataManager.PREVIOUS_MANIFEST);
        if (active != null) for (DataFileSpec spec : active.files) keep.add(spec.fileName);
        if (previous != null) for (DataFileSpec spec : previous.files) keep.add(spec.fileName);
        File[] files = manager.getMapsDir().listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isFile() && !keep.contains(file.getName())) file.delete();
        }
    }
}
