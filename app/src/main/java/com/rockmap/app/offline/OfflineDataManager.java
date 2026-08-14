package com.rockmap.app.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

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

public final class OfflineDataManager {
    public static final String PREFS = "rockmap_offline";
    public static final String KEY_LAST_UPDATE_STATUS = "last_update_status";
    public static final String ACTIVE_MANIFEST = "active-manifest.json";
    public static final String PREVIOUS_MANIFEST = "previous-manifest.json";

    private static final String BASE_PLACEHOLDER = "${ROCKMAP_BASE_URI}";
    private static final String LAND_PLACEHOLDER = "${ROCKMAP_LAND_URI}";
    private static final String CLAIMS_PLACEHOLDER = "${ROCKMAP_CLAIMS_URI}";

    private final Context context;
    private final File mapsDir;

    public OfflineDataManager(Context context) {
        this.context = context.getApplicationContext();
        this.mapsDir = new File(this.context.getFilesDir(), "maps");
        if (!mapsDir.exists() && !mapsDir.mkdirs()) {
            setLastUpdateStatus("Cannot create internal map directory.");
        }
    }

    public File getMapsDir() {
        return mapsDir;
    }

    public File getActiveManifestFile() {
        return new File(mapsDir, ACTIVE_MANIFEST);
    }

    public File getPreviousManifestFile() {
        return new File(mapsDir, PREVIOUS_MANIFEST);
    }

    public DataManifest getActiveManifest() {
        return readRenderableManifest(getActiveManifestFile());
    }

    public DataManifest getPreviousManifest() {
        return readRenderableManifest(getPreviousManifestFile());
    }

    private DataManifest readRenderableManifest(File manifestFile) {
        if (!manifestFile.isFile()) return null;
        try {
            String json = readUtf8(manifestFile, 1_000_000);
            DataManifest parsed = DataManifestParser.parse(json);
            return parsed.isRenderable() ? parsed : null;
        } catch (IOException | JSONException ex) {
            return null;
        }
    }

    public boolean hasRenderableActivePack() {
        return hasCompleteSnapshotBySize(getActiveManifest());
    }

    public boolean hasVerifiedActivePack() {
        DataManifest active = getActiveManifest();
        return active != null && active.isPublished() && hasCompleteSnapshotBySize(active);
    }

    public boolean hasBasemapTestPack() {
        DataManifest active = getActiveManifest();
        return active != null && active.isBasemapTest() && hasCompleteSnapshotBySize(active);
    }

    public boolean hasCompleteSnapshotBySize(DataManifest manifest) {
        if (manifest == null) return false;
        for (DataFileSpec spec : manifest.files) {
            if (!spec.required) continue;
            File file = resolve(spec.fileName);
            if (!file.isFile() || file.length() != spec.bytes) return false;
        }
        return true;
    }

    public File getActiveFile(String id) {
        DataManifest manifest = getActiveManifest();
        if (manifest == null) return null;
        DataFileSpec spec = manifest.find(id);
        if (spec == null || !DataValidators.isSafeFileName(spec.fileName)) return null;
        File file = resolve(spec.fileName);
        return file.isFile() && file.length() == spec.bytes ? file : null;
    }

    /**
     * Builds the runtime style from a locally stored, size-verified style template.
     * The template must never contain absolute device paths; those are substituted here.
     */
    public String buildActiveStyleJson() throws IOException {
        DataManifest manifest = getActiveManifest();
        if (!hasCompleteSnapshotBySize(manifest)) {
            throw new IOException("Active map snapshot is incomplete.");
        }

        File styleFile = getActiveFile("style");
        File baseFile = getActiveFile("base");
        if (styleFile == null || baseFile == null) {
            throw new IOException("Active map snapshot is missing its style or basemap.");
        }

        String template = readUtf8(styleFile, 8_000_000);
        requirePlaceholder(template, BASE_PLACEHOLDER);
        String rendered = template.replace(BASE_PLACEHOLDER, pmtilesUri(baseFile));

        if (manifest.isPublished()) {
            File landFile = getActiveFile("land");
            File claimsFile = getActiveFile("claims");
            if (landFile == null || claimsFile == null) {
                throw new IOException("Published map snapshot is missing land or claims data.");
            }
            requirePlaceholder(rendered, LAND_PLACEHOLDER);
            requirePlaceholder(rendered, CLAIMS_PLACEHOLDER);
            rendered = rendered
                    .replace(LAND_PLACEHOLDER, pmtilesUri(landFile))
                    .replace(CLAIMS_PLACEHOLDER, pmtilesUri(claimsFile));
        }

        if (rendered.contains("${ROCKMAP_")) {
            throw new IOException("Map style contains an unresolved RockMap placeholder.");
        }
        // Any installed RockMap pack must be able to render without a network fallback.
        String lower = rendered.toLowerCase(java.util.Locale.US);
        if (lower.contains("http://") || lower.contains("https://")) {
            throw new IOException("Installed offline style contains a runtime network dependency.");
        }
        return rendered;
    }

    private void requirePlaceholder(String template, String placeholder) throws IOException {
        if (!template.contains(placeholder)) {
            throw new IOException("Map style is missing required placeholder " + placeholder);
        }
    }

    private String pmtilesUri(File file) {
        return "pmtiles://" + Uri.fromFile(file).toString();
    }

    public boolean revertToPreviousManifest(String reason) {
        DataManifest previous = getPreviousManifest();
        if (!hasCompleteSnapshotBySize(previous)) return false;
        try {
            byte[] bytes = readBytes(getPreviousManifestFile(), 1_000_000);
            DataUpdateWorker.replaceFileAtomically(getActiveManifestFile(), bytes);
            setLastUpdateStatus("A newly activated map failed to render safely. RockMap restored the previous offline snapshot. "
                    + safeMessage(reason));
            return true;
        } catch (IOException ex) {
            setLastUpdateStatus("Map render failed and the previous snapshot could not be restored: " + ex.getMessage());
            return false;
        }
    }

    public String describeStatus() {
        DataManifest active = getActiveManifest();
        if (active == null || !hasCompleteSnapshotBySize(active)) {
            String last = getLastUpdateStatus();
            if (last == null || last.trim().isEmpty()) last = "No offline map pack is installed.";
            return "OFFLINE MAP: NOT VERIFIED\n" + last;
        }

        if (active.isBasemapTest()) {
            return "OFFLINE BASEMAP: TEST PACK — NOT VERIFIED FOR NAVIGATION"
                    + "\nLand status: unavailable"
                    + "\nMining claims: unavailable"
                    + "\nLabels: not included yet"
                    + "\nPack: " + active.pack
                    + "\nVersion: " + active.version
                    + "\nPublished: " + active.publishedAt
                    + "\nMap data: © OpenStreetMap contributors · Protomaps";
        }

        return "OFFLINE MAP: VERIFIED"
                + "\nPack: " + active.pack
                + "\nVersion: " + active.version
                + "\nPublished: " + active.publishedAt
                + "\nMap data: © OpenStreetMap contributors · Protomaps";
    }

    public OneTimeWorkRequest queueUpdate() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DataUpdateWorker.class)
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

    public File resolve(String safeFileName) {
        if (!DataValidators.isSafeFileName(safeFileName)) {
            throw new IllegalArgumentException("Unsafe map filename");
        }
        return new File(mapsDir, safeFileName);
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

    private String safeMessage(String message) {
        return message == null ? "" : message;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
