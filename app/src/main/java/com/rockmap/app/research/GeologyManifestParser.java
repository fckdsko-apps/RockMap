package com.rockmap.app.research;

import com.rockmap.app.BuildConfig;
import com.rockmap.app.offline.DataValidators;

import org.json.JSONException;
import org.json.JSONObject;

public final class GeologyManifestParser {
    private static final int MAX_MANIFEST_CHARS = 1_000_000;
    private static final int MIN_RECORDS = 500;
    private static final int MAX_RECORDS = 100_000;
    private static final String REQUIRED_DOI = "10.5066/F7WH2N65";
    private static final String REQUIRED_WHERE = "STATE='CO'";

    private GeologyManifestParser() {}

    public static GeologyManifest parse(String json) throws JSONException {
        if (json == null || json.trim().isEmpty() || json.length() > MAX_MANIFEST_CHARS) {
            throw new JSONException("Geology manifest is empty or unreasonably large.");
        }
        JSONObject root = new JSONObject(json);
        int manifestVersion = root.getInt("manifestVersion");
        if (manifestVersion != BuildConfig.SUPPORTED_GEOLOGY_MANIFEST_VERSION) {
            throw new JSONException("Unsupported geology manifest version: " + manifestVersion);
        }

        String status = root.optString("status", "");
        String pack = root.optString("pack", "Colorado Geology");
        String message = root.optString("message", "");
        if (!GeologyManifest.STATUS_PUBLISHED.equals(status)) {
            return new GeologyManifest(manifestVersion, status, pack, "", "", 0,
                    message, null, null, null);
        }

        String version = root.getString("version").trim();
        String publishedAt = root.getString("publishedAt").trim();
        int minimumAppVersionCode = root.optInt("minimumAppVersionCode", 1);
        if (version.isEmpty() || publishedAt.isEmpty()) {
            throw new JSONException("Published geology manifest is missing version/date.");
        }
        if (minimumAppVersionCode > BuildConfig.VERSION_CODE) {
            throw new JSONException("Colorado geology pack requires a newer RockMap build.");
        }

        JSONObject sourceJson = root.getJSONObject("source");
        String sourceTitle = sourceJson.getString("title").trim();
        String sourceDoi = sourceJson.getString("doi").trim();
        String sourceScale = sourceJson.getString("scale").trim();
        String sourceService = sourceJson.getString("service").trim();
        String sourceWhere = sourceJson.getString("where").trim();
        int recordCount = sourceJson.getInt("recordCount");
        if (sourceTitle.isEmpty() || sourceScale.isEmpty()) {
            throw new JSONException("Geology source metadata is incomplete.");
        }
        if (!REQUIRED_DOI.equals(sourceDoi)) {
            throw new JSONException("Unexpected geology source DOI.");
        }
        if (!REQUIRED_WHERE.equals(sourceWhere)) {
            throw new JSONException("Geology manifest is not explicitly Colorado-only.");
        }
        if (!DataValidators.isSafeHttpsUrl(sourceService)) {
            throw new JSONException("Geology source service URL is unsafe.");
        }
        if (recordCount < MIN_RECORDS || recordCount > MAX_RECORDS) {
            throw new JSONException("Geology record count is outside the fail-closed range.");
        }

        JSONObject assetJson = root.getJSONObject("asset");
        String assetFile = assetJson.getString("fileName").trim();
        String assetUrl = assetJson.getString("url").trim();
        long assetBytes = assetJson.getLong("bytes");
        String assetSha = assetJson.getString("sha256").trim();
        if (!DataValidators.isSafeFileName(assetFile) || !assetFile.toLowerCase().endsWith(".db.gz")) {
            throw new JSONException("Unsafe geology download filename.");
        }
        if (!DataValidators.isSafeHttpsUrl(assetUrl)) {
            throw new JSONException("Geology asset URL must be HTTPS.");
        }
        if (!DataValidators.isSafeByteCount(assetBytes) || !DataValidators.isSha256(assetSha)) {
            throw new JSONException("Invalid geology asset size or SHA-256.");
        }

        JSONObject databaseJson = root.getJSONObject("database");
        String databaseFile = databaseJson.getString("fileName").trim();
        long databaseBytes = databaseJson.getLong("bytes");
        String databaseSha = databaseJson.getString("sha256").trim();
        int schemaVersion = databaseJson.getInt("schemaVersion");
        if (!DataValidators.isSafeFileName(databaseFile) || !databaseFile.toLowerCase().endsWith(".db")) {
            throw new JSONException("Unsafe geology database filename.");
        }
        if (!DataValidators.isSafeByteCount(databaseBytes) || !DataValidators.isSha256(databaseSha)) {
            throw new JSONException("Invalid geology database size or SHA-256.");
        }
        if (schemaVersion != BuildConfig.SUPPORTED_GEOLOGY_SCHEMA_VERSION) {
            throw new JSONException("Unsupported geology database schema: " + schemaVersion);
        }
        if (!assetFile.equals(databaseFile + ".gz")) {
            throw new JSONException("Geology asset/database filenames do not match.");
        }

        GeologyManifest.Source source = new GeologyManifest.Source(
                sourceTitle, sourceDoi, sourceScale, sourceService, sourceWhere, recordCount);
        GeologyManifest.Asset asset = new GeologyManifest.Asset(
                assetFile, assetUrl, assetBytes, assetSha);
        GeologyManifest.Database database = new GeologyManifest.Database(
                databaseFile, databaseBytes, databaseSha, schemaVersion);
        return new GeologyManifest(manifestVersion, status, pack, version, publishedAt,
                minimumAppVersionCode, message, source, asset, database);
    }
}
