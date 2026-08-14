package com.rockmap.app.offline;

import com.rockmap.app.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DataManifestParser {
    private static final int MAX_MANIFEST_CHARS = 1_000_000;

    private DataManifestParser() {}

    public static DataManifest parse(String json) throws JSONException {
        if (json == null || json.trim().isEmpty() || json.length() > MAX_MANIFEST_CHARS) {
            throw new JSONException("Manifest is empty or unreasonably large");
        }

        JSONObject root = new JSONObject(json);
        int manifestVersion = root.getInt("manifestVersion");
        String status = root.optString("status", "");
        String pack = root.optString("pack", "");
        String message = root.optString("message", "");

        if (manifestVersion != BuildConfig.SUPPORTED_MANIFEST_VERSION) {
            throw new JSONException("Unsupported manifest version: " + manifestVersion);
        }

        if (!"published".equals(status)) {
            return new DataManifest(manifestVersion, 0, 0, pack, "", "", status,
                    message, new ArrayList<>());
        }

        int styleSchemaVersion = root.getInt("styleSchemaVersion");
        int minimumAppVersionCode = root.optInt("minimumAppVersionCode", 1);
        String version = root.getString("version");
        String publishedAt = root.getString("publishedAt");
        JSONArray fileArray = root.getJSONArray("files");

        if (styleSchemaVersion != BuildConfig.SUPPORTED_STYLE_SCHEMA_VERSION) {
            throw new JSONException("Unsupported style/data contract version: " + styleSchemaVersion);
        }
        if (minimumAppVersionCode > BuildConfig.VERSION_CODE) {
            throw new JSONException("Data pack requires a newer RockMap build");
        }
        if (version.trim().isEmpty() || publishedAt.trim().isEmpty()) {
            throw new JSONException("Published manifest is missing version/date");
        }
        if (fileArray.length() == 0 || fileArray.length() > 32) {
            throw new JSONException("Invalid number of files in manifest");
        }

        List<DataFileSpec> files = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < fileArray.length(); i++) {
            JSONObject item = fileArray.getJSONObject(i);
            String id = item.getString("id");
            String kind = item.getString("kind");
            String fileName = item.getString("fileName");
            String url = item.getString("url");
            String sha256 = item.getString("sha256");
            long bytes = item.getLong("bytes");
            int schemaVersion = item.optInt("schemaVersion", 1);
            boolean required = item.optBoolean("required", true);

            if (!DataValidators.isSafeId(id) || !ids.add(id)) {
                throw new JSONException("Unsafe or duplicate file id: " + id);
            }
            if (!DataValidators.isSafeFileName(fileName) || !names.add(fileName)) {
                throw new JSONException("Unsafe or duplicate file name: " + fileName);
            }
            if (!DataValidators.isSupportedKind(kind, fileName)) {
                throw new JSONException("Unsupported file kind/name: " + id);
            }
            if (!DataValidators.isSafeHttpsUrl(url)) {
                throw new JSONException("File URL must be HTTPS without embedded credentials: " + id);
            }
            if (!DataValidators.isSha256(sha256)) {
                throw new JSONException("Invalid SHA-256: " + id);
            }
            if (!DataValidators.isSafeByteCount(bytes)) {
                throw new JSONException("Invalid file size: " + id);
            }
            if (schemaVersion < 1 || schemaVersion > 1000) {
                throw new JSONException("Invalid schema version: " + id);
            }
            files.add(new DataFileSpec(id, kind, fileName, url, sha256, bytes, schemaVersion, required));
        }

        require(files, "style", "style");
        require(files, "base", "pmtiles");
        require(files, "land", "pmtiles");
        require(files, "claims", "pmtiles");

        return new DataManifest(manifestVersion, styleSchemaVersion, minimumAppVersionCode,
                pack, version, publishedAt, status, message, files);
    }

    private static void require(List<DataFileSpec> files, String id, String kind) throws JSONException {
        for (DataFileSpec file : files) {
            if (id.equals(file.id) && kind.equals(file.kind) && file.required) return;
        }
        throw new JSONException("Published manifest is missing required component: " + id);
    }
}
