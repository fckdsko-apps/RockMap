package com.rockmap.app.offline;

public final class DataFileSpec {
    public final String id;
    public final String kind;
    public final String fileName;
    public final String url;
    public final String sha256;
    public final long bytes;
    public final int schemaVersion;
    public final boolean required;

    public DataFileSpec(String id, String kind, String fileName, String url,
                        String sha256, long bytes, int schemaVersion, boolean required) {
        this.id = id;
        this.kind = kind;
        this.fileName = fileName;
        this.url = url;
        this.sha256 = sha256;
        this.bytes = bytes;
        this.schemaVersion = schemaVersion;
        this.required = required;
    }
}
