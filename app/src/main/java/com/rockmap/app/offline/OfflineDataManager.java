package com.rockmap.app.offline;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.rockmap.app.map.LandStatusCatalog;
import com.rockmap.app.map.MiningClaimCatalog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
    private static final String BASEMAP_LABEL_STYLE_ASSET = "rockmap_basemap_label_style_alpha3.json";

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

    /** Alpha 4/5 keep status=basemap_test so the red safety state cannot be bypassed. */
    public boolean hasLandStatusTestPack() {
        DataManifest active = getActiveManifest();
        if (active == null || !active.isBasemapTest() || !hasCompleteSnapshotBySize(active)) return false;
        DataFileSpec land = active.find("land");
        return land != null && land.required && getActiveFile("land") != null;
    }

    /** Alpha 5 test: land + BLM MLRS not-closed claims are present, but still not field-verified. */
    public boolean hasClaimsTestPack() {
        DataManifest active = getActiveManifest();
        if (active == null || !active.isBasemapTest() || !hasCompleteSnapshotBySize(active)) return false;
        DataFileSpec land = active.find("land");
        DataFileSpec claims = active.find("claims");
        return land != null && land.required && getActiveFile("land") != null
                && claims != null && claims.required && getActiveFile("claims") != null;
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

        File baseFile = getActiveFile("base");
        if (baseFile == null) {
            throw new IOException("Active map snapshot is missing its basemap.");
        }

        // Alpha 5 still reuses the exact Alpha 2 basemap bytes, Alpha 3.1 local labels,
        // and the already-tested Alpha 4 land PMTiles. A basemap_test manifest may add land
        // and then claims; both sources/layers are injected into the known-good local style
        // in memory without mutating the immutable baseline files.
        String template;
        if (manifest.isBasemapTest()) {
            template = readAssetUtf8(BASEMAP_LABEL_STYLE_ASSET, 8_000_000);
            if (manifest.find("land") != null) {
                File landFile = getActiveFile("land");
                if (landFile == null) throw new IOException("Land-status test snapshot is missing its land PMTiles file.");
                template = addLandStatusTestLayers(template);
                requirePlaceholder(template, LAND_PLACEHOLDER);
                template = template.replace(LAND_PLACEHOLDER, pmtilesUri(landFile));
            }
            if (manifest.find("claims") != null) {
                File claimsFile = getActiveFile("claims");
                if (claimsFile == null) throw new IOException("Claims test snapshot is missing its claims PMTiles file.");
                template = addClaimsTestLayers(template);
                requirePlaceholder(template, CLAIMS_PLACEHOLDER);
                template = template.replace(CLAIMS_PLACEHOLDER, pmtilesUri(claimsFile));
            }
        } else {
            File styleFile = getActiveFile("style");
            if (styleFile == null) {
                throw new IOException("Published map snapshot is missing its style.");
            }
            template = readUtf8(styleFile, 8_000_000);
        }

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


    private String addLandStatusTestLayers(String template) throws IOException {
        try {
            JSONObject root = new JSONObject(template);
            JSONObject sources = root.getJSONObject("sources");
            if (sources.has("rockmap-land")) throw new IOException("Alpha 4 style already contains rockmap-land unexpectedly.");

            JSONObject landSource = new JSONObject();
            landSource.put("type", "vector");
            landSource.put("url", LAND_PLACEHOLDER);
            landSource.put("attribution", "Bureau of Land Management, Colorado");
            landSource.put("maxzoom", 14);
            sources.put("rockmap-land", landSource);

            JSONArray layers = root.getJSONArray("layers");
            for (int i = 0; i < layers.length(); i++) {
                JSONObject layer = layers.optJSONObject(i);
                if (layer == null) continue;
                String id = layer.optString("id", "");
                if ("rockmap-land-fill".equals(id) || "rockmap-land-outline".equals(id)) {
                    throw new IOException("Alpha 4 style already contains land-status layers unexpectedly.");
                }
            }

            JSONArray fillColor = new JSONArray();
            fillColor.put("match");
            fillColor.put(new JSONArray().put("get").put("manager_code"));
            for (LandStatusCatalog.Entry entry : LandStatusCatalog.entries()) {
                fillColor.put(entry.code);
                fillColor.put(entry.colorHex);
            }
            // Unknown values should never reach a published Alpha 4 pack because the data
            // builder fails closed on category drift, but keep a visible fallback anyway.
            fillColor.put(LandStatusCatalog.DEFAULT_COLOR_HEX);

            JSONObject fill = new JSONObject();
            fill.put("id", "rockmap-land-fill");
            fill.put("type", "fill");
            fill.put("source", "rockmap-land");
            fill.put("source-layer", "land");
            fill.put("minzoom", 5);
            JSONObject fillPaint = new JSONObject();
            fillPaint.put("fill-color", fillColor);
            fillPaint.put("fill-opacity", 0.32);
            fill.put("paint", fillPaint);

            JSONObject outline = new JSONObject();
            outline.put("id", "rockmap-land-outline");
            outline.put("type", "line");
            outline.put("source", "rockmap-land");
            outline.put("source-layer", "land");
            outline.put("minzoom", 7);
            JSONObject outlinePaint = new JSONObject();
            outlinePaint.put("line-color", "#5b5650");
            outlinePaint.put("line-opacity", 0.72);
            outlinePaint.put("line-width", new JSONArray()
                    .put("interpolate")
                    .put(new JSONArray().put("linear"))
                    .put(new JSONArray().put("zoom"))
                    .put(7).put(0.35)
                    .put(12).put(0.8)
                    .put(16).put(1.4));
            outline.put("paint", outlinePaint);

            // Put land polygons above the basemap geometry but below every text label.
            JSONArray rebuilt = new JSONArray();
            boolean inserted = false;
            for (int i = 0; i < layers.length(); i++) {
                JSONObject layer = layers.getJSONObject(i);
                if (!inserted && "symbol".equals(layer.optString("type"))) {
                    rebuilt.put(fill);
                    rebuilt.put(outline);
                    inserted = true;
                }
                rebuilt.put(layer);
            }
            if (!inserted) {
                rebuilt.put(fill);
                rebuilt.put(outline);
            }
            root.put("layers", rebuilt);
            JSONObject metadata = root.optJSONObject("metadata");
            if (metadata == null) {
                metadata = new JSONObject();
                root.put("metadata", metadata);
            }
            metadata.put("rockmap:land-status", "alpha4-blm-colorado-sma-test");
            metadata.put("rockmap:warning", "NOT VERIFIED FOR NAVIGATION. BLM Colorado SMA is management/status mapping, not a parcel survey; mining claims are not included.");
            return root.toString();
        } catch (JSONException ex) {
            throw new IOException("Could not construct Alpha 4 land-status style: " + ex.getMessage(), ex);
        }
    }


    private String addClaimsTestLayers(String template) throws IOException {
        try {
            JSONObject root = new JSONObject(template);
            JSONObject sources = root.getJSONObject("sources");
            if (sources.has("rockmap-claims")) throw new IOException("Alpha 5 style already contains rockmap-claims unexpectedly.");

            JSONObject claimSource = new JSONObject();
            claimSource.put("type", "vector");
            claimSource.put("url", CLAIMS_PLACEHOLDER);
            claimSource.put("attribution", "U.S. Department of the Interior, Bureau of Land Management (BLM), MLRS");
            claimSource.put("maxzoom", 14);
            sources.put("rockmap-claims", claimSource);

            JSONArray layers = root.getJSONArray("layers");
            for (int i = 0; i < layers.length(); i++) {
                JSONObject layer = layers.optJSONObject(i);
                if (layer == null) continue;
                String id = layer.optString("id", "");
                if ("rockmap-claim-fill".equals(id) || "rockmap-claim-outline".equals(id)) {
                    throw new IOException("Alpha 5 style already contains mining-claim layers unexpectedly.");
                }
            }

            JSONObject fill = new JSONObject();
            fill.put("id", "rockmap-claim-fill");
            fill.put("type", "fill");
            fill.put("source", "rockmap-claims");
            fill.put("source-layer", "claims");
            fill.put("minzoom", 7);
            JSONObject fillPaint = new JSONObject();
            fillPaint.put("fill-color", MiningClaimCatalog.COLOR_HEX);
            fillPaint.put("fill-opacity", 0.18);
            fill.put("paint", fillPaint);

            JSONObject outline = new JSONObject();
            outline.put("id", "rockmap-claim-outline");
            outline.put("type", "line");
            outline.put("source", "rockmap-claims");
            outline.put("source-layer", "claims");
            outline.put("minzoom", 7);
            JSONObject outlinePaint = new JSONObject();
            outlinePaint.put("line-color", MiningClaimCatalog.COLOR_HEX);
            outlinePaint.put("line-opacity", 0.95);
            outlinePaint.put("line-width", new JSONArray()
                    .put("interpolate")
                    .put(new JSONArray().put("linear"))
                    .put(new JSONArray().put("zoom"))
                    .put(7).put(0.8)
                    .put(12).put(1.4)
                    .put(16).put(2.2));
            outline.put("paint", outlinePaint);

            // Claims sit above land-status fills/outlines but below road/place labels.
            JSONArray rebuilt = new JSONArray();
            boolean inserted = false;
            for (int i = 0; i < layers.length(); i++) {
                JSONObject layer = layers.getJSONObject(i);
                if (!inserted && "symbol".equals(layer.optString("type"))) {
                    rebuilt.put(fill);
                    rebuilt.put(outline);
                    inserted = true;
                }
                rebuilt.put(layer);
            }
            if (!inserted) {
                rebuilt.put(fill);
                rebuilt.put(outline);
            }
            root.put("layers", rebuilt);
            JSONObject metadata = root.optJSONObject("metadata");
            if (metadata == null) {
                metadata = new JSONObject();
                root.put("metadata", metadata);
            }
            metadata.put("rockmap:mining-claims", "alpha5-blm-mlrs-not-closed-test");
            metadata.put("rockmap:warning", "NOT VERIFIED FOR NAVIGATION. Claims are BLM MLRS records whose disposition is not closed, not surveyed claim boundaries; some MLRS cases may lack geospatial representation.");
            return root.toString();
        } catch (JSONException ex) {
            throw new IOException("Could not construct Alpha 5 mining-claims style: " + ex.getMessage(), ex);
        }
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
            if (active.find("claims") != null && getActiveFile("claims") != null
                    && active.find("land") != null && getActiveFile("land") != null) {
                return "OFFLINE BASEMAP + LABELS + LAND STATUS + MINING CLAIMS: TEST — NOT VERIFIED FOR NAVIGATION"
                        + "\nLand status: included offline (Alpha 4 BLM Colorado SMA test)"
                        + "\nMining claims: included offline (Alpha 5 BLM MLRS not-closed test)"
                        + "\nLabels: included offline (Alpha 3.1 dual-font path retained)"
                        + "\nPack: " + active.pack
                        + "\nVersion: " + active.version
                        + "\nPublished: " + active.publishedAt
                        + "\nMap data: © OpenStreetMap contributors · Protomaps"
                        + "\nLand data: Bureau of Land Management, Colorado Surface Management Agency"
                        + "\nClaim data: Bureau of Land Management, Mineral & Land Records System (MLRS), Mining Claims — Not Closed"
                        + "\nBoundary note: land status is management/status mapping, not a parcel survey or legal boundary."
                        + "\nClaim note: BLM says some MLRS cases may lack geospatial representation. No rendered claim is not proof that no claim exists.";
            }
            if (active.find("land") != null && getActiveFile("land") != null) {
                return "OFFLINE BASEMAP + LABELS + LAND STATUS: TEST — NOT VERIFIED FOR NAVIGATION"
                        + "\nLand status: included offline (Alpha 4 BLM Colorado SMA test)"
                        + "\nMining claims: unavailable"
                        + "\nLabels: included offline (Alpha 3.1 dual-font path retained)"
                        + "\nPack: " + active.pack
                        + "\nVersion: " + active.version
                        + "\nPublished: " + active.publishedAt
                        + "\nMap data: © OpenStreetMap contributors · Protomaps"
                        + "\nLand data: Bureau of Land Management, Colorado Surface Management Agency"
                        + "\nBoundary note: management/status mapping only; not a parcel survey or legal boundary.";
            }
            return "OFFLINE BASEMAP + LABELS: TEST — NOT VERIFIED FOR NAVIGATION"
                    + "\nLand status: unavailable"
                    + "\nMining claims: unavailable"
                    + "\nLabels: included offline (Alpha 3.1 dual-font test)"
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

    private String readAssetUtf8(String assetName, int maxBytes) throws IOException {
        try (InputStream input = context.getAssets().open(assetName);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Bundled map style exceeded size limit.");
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
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
