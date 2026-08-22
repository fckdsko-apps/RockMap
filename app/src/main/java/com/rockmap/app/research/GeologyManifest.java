package com.rockmap.app.research;

public final class GeologyManifest {
    public static final String STATUS_PUBLISHED = "published";

    public static final class Source {
        public final String title;
        public final String doi;
        public final String scale;
        public final String service;
        public final String where;
        public final int recordCount;

        Source(String title, String doi, String scale, String service, String where, int recordCount) {
            this.title = safe(title);
            this.doi = safe(doi);
            this.scale = safe(scale);
            this.service = safe(service);
            this.where = safe(where);
            this.recordCount = recordCount;
        }
    }

    public static final class Asset {
        public final String fileName;
        public final String url;
        public final long bytes;
        public final String sha256;

        Asset(String fileName, String url, long bytes, String sha256) {
            this.fileName = safe(fileName);
            this.url = safe(url);
            this.bytes = bytes;
            this.sha256 = safe(sha256);
        }
    }

    public static final class Database {
        public final String fileName;
        public final long bytes;
        public final String sha256;
        public final int schemaVersion;

        Database(String fileName, long bytes, String sha256, int schemaVersion) {
            this.fileName = safe(fileName);
            this.bytes = bytes;
            this.sha256 = safe(sha256);
            this.schemaVersion = schemaVersion;
        }
    }

    public final int manifestVersion;
    public final String status;
    public final String pack;
    public final String version;
    public final String publishedAt;
    public final int minimumAppVersionCode;
    public final String message;
    public final Source source;
    public final Asset asset;
    public final Database database;

    GeologyManifest(int manifestVersion, String status, String pack, String version,
                    String publishedAt, int minimumAppVersionCode, String message,
                    Source source, Asset asset, Database database) {
        this.manifestVersion = manifestVersion;
        this.status = safe(status);
        this.pack = safe(pack);
        this.version = safe(version);
        this.publishedAt = safe(publishedAt);
        this.minimumAppVersionCode = minimumAppVersionCode;
        this.message = safe(message);
        this.source = source;
        this.asset = asset;
        this.database = database;
    }

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status) && source != null && asset != null && database != null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
