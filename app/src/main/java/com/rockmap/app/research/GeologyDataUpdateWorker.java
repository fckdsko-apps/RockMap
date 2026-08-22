package com.rockmap.app.research;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.StatFs;
import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.rockmap.app.BuildConfig;
import com.rockmap.app.offline.DataValidators;

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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Downloads one immutable geology asset only after Research has shown its manifest-declared size. */
public final class GeologyDataUpdateWorker extends Worker {
    private static final Object UPDATE_LOCK = new Object();
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_MANIFEST_BYTES = 1_000_000;
    private static final long STORAGE_MARGIN_BYTES = 64L * 1024L * 1024L;

    private static final Set<String> REQUIRED_UNIT_COLUMNS = new HashSet<>(Arrays.asList(
            "object_id", "state", "orig_label", "sgmc_label", "unit_link", "unit_name",
            "age_min", "age_max", "generalized_lith", "major1", "major2", "major3",
            "minor1", "minor2", "minor3", "minor4", "minor5", "incidental", "indeterminate",
            "ref_id", "reference_text", "digital_url", "ngmdb1", "ngmdb2", "ngmdb3", "rgba",
            "south", "west", "north", "east", "geometry_json", "search_text", "lithology_text",
            "age_text"));

    public GeologyDataUpdateWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
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
        GeologyDataManager manager = new GeologyDataManager(getApplicationContext());
        if (!DataValidators.isSafeHttpsUrl(BuildConfig.GEOLOGY_MANIFEST_URL)) {
            return fail(manager, "Colorado geology manifest URL is not configured safely.");
        }

        File assetPart = null;
        File databasePart = null;
        try {
            String rawManifest = downloadSmallText(BuildConfig.GEOLOGY_MANIFEST_URL);
            GeologyManifest incoming = GeologyManifestParser.parse(rawManifest);
            if (!incoming.isPublished()) {
                return fail(manager, incoming.message.isEmpty()
                        ? "No Colorado geology pack is currently published." : incoming.message);
            }

            GeologyManifest activeBefore = manager.getActiveManifest();
            GeologyManifest previousBefore = manager.getPreviousManifest();
            enforceImmutableDatabaseName(manager, incoming, activeBefore, previousBefore);

            File finalDatabase = new File(manager.getResearchDir(), incoming.database.fileName);
            if (isAlreadyValid(finalDatabase, incoming.database)) {
                validateDatabase(finalDatabase, incoming);
                activateManifest(manager, rawManifest, incoming, activeBefore);
                cleanupUnreferencedDatabases(manager);
                manager.setLastUpdateStatus("Colorado geology installed: " + incoming.version
                        + " (" + incoming.source.recordCount + " mapped areas)." );
                return Result.success();
            }

            long needed = Math.addExact(incoming.asset.bytes, incoming.database.bytes);
            ensureFreeSpace(manager.getResearchDir(), Math.addExact(needed, STORAGE_MARGIN_BYTES));

            assetPart = new File(manager.getResearchDir(), incoming.asset.fileName + ".part");
            databasePart = new File(manager.getResearchDir(), incoming.database.fileName + ".part");
            deleteStalePart(assetPart);
            deleteStalePart(databasePart);

            downloadAndVerify(incoming.asset, assetPart);
            gunzipAndVerify(assetPart, databasePart, incoming.database);
            validateDatabase(databasePart, incoming);
            moveReplaceAtomically(databasePart, finalDatabase);
            databasePart = null;

            // Recheck immutable bytes after filesystem activation, before switching the active manifest.
            if (!isAlreadyValid(finalDatabase, incoming.database)) {
                throw new IOException("Activated geology database failed final SHA-256 verification.");
            }
            validateDatabase(finalDatabase, incoming);
            activateManifest(manager, rawManifest, incoming, activeBefore);
            cleanupUnreferencedDatabases(manager);
            manager.setLastUpdateStatus("Colorado geology installed: " + incoming.version
                    + " (" + incoming.source.recordCount + " mapped areas)." );
            return Result.success();
        } catch (ArithmeticException ex) {
            return fail(manager, "Colorado geology download rejected because declared sizes overflowed safely.");
        } catch (JSONException ex) {
            return fail(manager, "Colorado geology manifest rejected: " + ex.getMessage());
        } catch (IOException | NoSuchAlgorithmException ex) {
            return fail(manager, "Colorado geology update failed; previous geology was kept: " + ex.getMessage());
        } catch (RuntimeException ex) {
            return fail(manager, "Colorado geology update aborted safely: " + ex.getMessage());
        } finally {
            if (assetPart != null) assetPart.delete();
            if (databasePart != null) databasePart.delete();
        }
    }

    private Result fail(GeologyDataManager manager, String message) {
        manager.setLastUpdateStatus(message);
        return Result.failure();
    }

    private void enforceImmutableDatabaseName(GeologyDataManager manager, GeologyManifest incoming,
                                              GeologyManifest active, GeologyManifest previous)
            throws IOException, NoSuchAlgorithmException {
        File target = new File(manager.getResearchDir(), incoming.database.fileName);
        if (!target.isFile() || isAlreadyValid(target, incoming.database)) return;
        if (sameFileName(active, incoming.database.fileName) || sameFileName(previous, incoming.database.fileName)) {
            throw new IOException("Geology manifest attempted to reuse a protected immutable filename with different bytes: "
                    + incoming.database.fileName);
        }
    }

    private static boolean sameFileName(GeologyManifest manifest, String fileName) {
        return manifest != null && manifest.database != null && manifest.database.fileName.equals(fileName);
    }

    private void downloadAndVerify(GeologyManifest.Asset asset, File target)
            throws IOException, NoSuchAlgorithmException {
        HttpURLConnection connection = openHttps(asset.url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " for Colorado geology asset.");
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > asset.bytes) {
                throw new IOException("Server sent oversized geology file header.");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0L;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream fileOutput = new FileOutputStream(target);
                 BufferedOutputStream output = new BufferedOutputStream(fileOutput)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (isStopped()) throw new IOException("Geology download was stopped before completion.");
                    total += read;
                    if (total > asset.bytes) throw new IOException("Geology download exceeded declared size.");
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
                output.flush();
                fileOutput.getFD().sync();
            }
            if (total != asset.bytes) throw new IOException("Downloaded geology size did not match its manifest.");
            if (!hex(digest.digest()).equalsIgnoreCase(asset.sha256)) {
                throw new IOException("Downloaded geology SHA-256 did not match its manifest.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private void gunzipAndVerify(File source, File target, GeologyManifest.Database database)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        try (InputStream raw = new BufferedInputStream(new FileInputStream(source));
             GZIPInputStream gzip = new GZIPInputStream(raw, 128 * 1024);
             FileOutputStream fileOutput = new FileOutputStream(target);
             BufferedOutputStream output = new BufferedOutputStream(fileOutput)) {
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                if (isStopped()) throw new IOException("Geology installation was stopped before completion.");
                total += read;
                if (total > database.bytes) throw new IOException("Unpacked geology exceeded declared installed size.");
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
            }
            output.flush();
            fileOutput.getFD().sync();
        }
        if (total != database.bytes) throw new IOException("Installed geology size did not match its manifest.");
        if (!hex(digest.digest()).equalsIgnoreCase(database.sha256)) {
            throw new IOException("Installed geology SHA-256 did not match its manifest.");
        }
    }

    private void validateDatabase(File file, GeologyManifest manifest) throws IOException {
        if (!file.isFile() || file.length() != manifest.database.bytes) {
            throw new IOException("Geology database is missing or has the wrong size.");
        }
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
            try (Cursor quick = db.rawQuery("PRAGMA quick_check", null)) {
                if (!quick.moveToFirst() || !"ok".equalsIgnoreCase(quick.getString(0))) {
                    throw new IOException("SQLite quick_check rejected the geology database.");
                }
            }
            Set<String> columns = new HashSet<>();
            try (Cursor schema = db.rawQuery("PRAGMA table_info(units)", null)) {
                while (schema.moveToNext()) columns.add(schema.getString(1));
            }
            if (!columns.containsAll(REQUIRED_UNIT_COLUMNS)) {
                Set<String> missing = new HashSet<>(REQUIRED_UNIT_COLUMNS);
                missing.removeAll(columns);
                throw new IOException("Geology database is missing required columns: " + missing);
            }
            String schemaVersion = metadata(db, "schema_version");
            if (!Integer.toString(manifest.database.schemaVersion).equals(schemaVersion)) {
                throw new IOException("Geology database schema metadata does not match its manifest.");
            }
            String recordCount = metadata(db, "record_count");
            if (!Integer.toString(manifest.source.recordCount).equals(recordCount)) {
                throw new IOException("Geology database record-count metadata does not match its manifest.");
            }
            if (!GeologyRepository.SOURCE_DOI.equals(metadata(db, "source_doi"))) {
                throw new IOException("Geology database source DOI is unexpected.");
            }
            long count = scalarLong(db, "SELECT COUNT(*) FROM units");
            if (count != manifest.source.recordCount) {
                throw new IOException("Geology database row count does not match its manifest.");
            }
            long wrongState = scalarLong(db, "SELECT COUNT(*) FROM units WHERE state IS NULL OR state <> 'CO'");
            if (wrongState != 0L) throw new IOException("Geology database contains non-Colorado records.");
            long missingGeometry = scalarLong(db,
                    "SELECT COUNT(*) FROM units WHERE geometry_json IS NULL OR length(trim(geometry_json)) < 10");
            if (missingGeometry != 0L) throw new IOException("Geology database contains missing polygon geometry.");
        } catch (RuntimeException ex) {
            throw new IOException("Geology SQLite validation failed: " + ex.getMessage(), ex);
        }
    }

    private static String metadata(SQLiteDatabase db, String key) throws IOException {
        try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE key=?", new String[]{key})) {
            if (!c.moveToFirst()) throw new IOException("Geology database metadata is missing: " + key);
            return c.getString(0);
        }
    }

    private static long scalarLong(SQLiteDatabase db, String sql) throws IOException {
        try (Cursor c = db.rawQuery(sql, null)) {
            if (!c.moveToFirst()) throw new IOException("Geology validation query returned no row.");
            return c.getLong(0);
        }
    }

    private void activateManifest(GeologyDataManager manager, String rawManifest,
                                  GeologyManifest incoming, GeologyManifest activeBefore) throws IOException {
        File activeFile = manager.getActiveManifestFile();
        boolean sameActive = activeBefore != null && activeBefore.database != null
                && activeBefore.database.sha256.equalsIgnoreCase(incoming.database.sha256)
                && activeBefore.database.bytes == incoming.database.bytes;
        if (!sameActive && activeFile.isFile()) {
            byte[] previous = GeologyDataManager.readBytes(activeFile, MAX_MANIFEST_BYTES);
            replaceFileAtomically(manager.getPreviousManifestFile(), previous);
        }
        replaceFileAtomically(activeFile, rawManifest.getBytes(StandardCharsets.UTF_8));
    }

    private void cleanupUnreferencedDatabases(GeologyDataManager manager) {
        Set<String> keep = new HashSet<>();
        keep.add(GeologyDataManager.LEGACY_DATABASE);
        GeologyManifest active = manager.getActiveManifest();
        GeologyManifest previous = manager.getPreviousManifest();
        if (active != null && active.database != null) keep.add(active.database.fileName);
        if (previous != null && previous.database != null) keep.add(previous.database.fileName);
        File[] files = manager.getResearchDir().listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith("colorado-geology-") && name.endsWith(".db")
                    && !keep.contains(name)) {
                file.delete();
            }
        }
    }

    private String downloadSmallText(String url) throws IOException {
        HttpURLConnection connection = openHttps(url);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code + " for geology manifest.");
            long length = connection.getContentLengthLong();
            if (length > MAX_MANIFEST_BYTES) throw new IOException("Geology manifest is too large.");
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1) {
                    if (isStopped()) throw new IOException("Geology update was stopped before completion.");
                    total += read;
                    if (total > MAX_MANIFEST_BYTES) throw new IOException("Geology manifest exceeded size limit.");
                    output.write(buffer, 0, read);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openHttps(String input) throws IOException {
        String current = input;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (!DataValidators.isSafeHttpsUrl(current)) throw new IOException("Non-HTTPS geology URL rejected.");
            URL url = new URL(current);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache, no-store");
            connection.setRequestProperty("User-Agent", "RockMap/" + BuildConfig.VERSION_NAME);
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Geology redirect had no Location header.");
                current = new URL(url, location).toString();
                continue;
            }
            return connection;
        }
        throw new IOException("Too many geology redirects.");
    }

    private boolean isAlreadyValid(File file, GeologyManifest.Database database)
            throws IOException, NoSuchAlgorithmException {
        return file.isFile() && file.length() == database.bytes
                && sha256(file).equalsIgnoreCase(database.sha256);
    }

    private static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private void ensureFreeSpace(File directory, long needed) throws IOException {
        StatFs stat = new StatFs(directory.getAbsolutePath());
        long free = stat.getAvailableBytes();
        if (needed < 0L || free < needed) {
            throw new IOException("Not enough internal storage for a safe Colorado geology install.");
        }
    }

    private static void deleteStalePart(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Cannot remove stale temporary geology file: " + file.getName());
        }
    }

    private static void replaceFileAtomically(File target, byte[] bytes) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        deleteStalePart(temp);
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        moveReplaceAtomically(temp, target);
    }

    private static void moveReplaceAtomically(File source, File target) throws IOException {
        try {
            Os.rename(source.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException ex) {
            throw new IOException("Atomic geology activation failed: " + ex.getMessage(), ex);
        }
    }
}
