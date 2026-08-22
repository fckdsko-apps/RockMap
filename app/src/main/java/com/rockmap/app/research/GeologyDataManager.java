package com.rockmap.app.research;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONException;

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

/** Owns RockMap's replaceable, release-managed Colorado geology snapshots. */
public final class GeologyDataManager {
    public static final String PREFS = "rockmap_geology_data";
    public static final String KEY_LAST_UPDATE_STATUS = "last_update_status";
    public static final String ACTIVE_MANIFEST = "geology-active-manifest.json";
    public static final String PREVIOUS_MANIFEST = "geology-previous-manifest.json";
    public static final String LEGACY_DATABASE = "rockmap-geology.db";
    private static final int MAX_MANIFEST_BYTES = 1_000_000;

    private final Context context;
    private final File researchDir;

    public GeologyDataManager(Context context) {
        this.context = context.getApplicationContext();
        this.researchDir = new File(this.context.getFilesDir(), "research");
        if (!researchDir.exists() && !researchDir.mkdirs()) {
            setLastUpdateStatus("Cannot create RockMap Research data directory.");
        }
    }

    public File getResearchDir() {
        return researchDir;
    }

    public File getActiveManifestFile() {
        return new File(researchDir, ACTIVE_MANIFEST);
    }

    public File getPreviousManifestFile() {
        return new File(researchDir, PREVIOUS_MANIFEST);
    }

    public File getLegacyDatabaseFile() {
        return new File(researchDir, LEGACY_DATABASE);
    }

    public GeologyManifest getActiveManifest() {
        return readPublishedManifest(getActiveManifestFile());
    }

    public GeologyManifest getPreviousManifest() {
        return readPublishedManifest(getPreviousManifestFile());
    }

    private GeologyManifest readPublishedManifest(File file) {
        if (!file.isFile()) return null;
        try {
            GeologyManifest manifest = GeologyManifestParser.parse(readUtf8(file, MAX_MANIFEST_BYTES));
            return manifest.isPublished() ? manifest : null;
        } catch (IOException | JSONException | RuntimeException ex) {
            return null;
        }
    }

    public File resolveDatabase(GeologyManifest manifest) {
        if (manifest == null || !manifest.isPublished() || manifest.database == null) return null;
        File file = new File(researchDir, manifest.database.fileName);
        return file.isFile() && file.length() == manifest.database.bytes ? file : null;
    }

    public File getActiveDatabaseFile() {
        return resolveDatabase(getActiveManifest());
    }

    public File getPreviousDatabaseFile() {
        return resolveDatabase(getPreviousManifest());
    }

    /** Ordered runtime fallbacks: current verified snapshot, rollback snapshot, then old Commit-2 local DB. */
    public List<File> getDatabaseCandidates() {
        ArrayList<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addCandidate(out, seen, getActiveDatabaseFile());
        addCandidate(out, seen, getPreviousDatabaseFile());
        File legacy = getLegacyDatabaseFile();
        if (legacy.isFile()) addCandidate(out, seen, legacy);
        return out;
    }

    private static void addCandidate(List<File> out, Set<String> seen, File file) {
        if (file == null) return;
        String path = file.getAbsolutePath();
        if (seen.add(path)) out.add(file);
    }

    public String getInstalledVersion() {
        GeologyManifest active = getActiveManifest();
        if (active != null && resolveDatabase(active) != null) return active.version;
        if (getLegacyDatabaseFile().isFile()) return "legacy local snapshot";
        return "";
    }

    public OneTimeWorkRequest queueUpdate() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(GeologyDataUpdateWorker.class)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context).enqueue(request);
        return request;
    }

    public void setLastUpdateStatus(String value) {
        prefs().edit().putString(KEY_LAST_UPDATE_STATUS, value == null ? "" : value).apply();
    }

    public String getLastUpdateStatus() {
        return prefs().getString(KEY_LAST_UPDATE_STATUS, "");
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String readUtf8(File file, int maxBytes) throws IOException {
        return new String(readBytes(file, maxBytes), StandardCharsets.UTF_8);
    }

    public static byte[] readBytes(File file, int maxBytes) throws IOException {
        if (!file.isFile()) throw new IOException("File does not exist: " + file.getName());
        if (file.length() > maxBytes) throw new IOException("File is larger than allowed: " + file.getName());
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("File exceeded size limit: " + file.getName());
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
