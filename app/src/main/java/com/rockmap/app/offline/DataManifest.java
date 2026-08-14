package com.rockmap.app.offline;

import java.util.Collections;
import java.util.List;

public final class DataManifest {
    public final int manifestVersion;
    public final int styleSchemaVersion;
    public final int minimumAppVersionCode;
    public final String pack;
    public final String version;
    public final String publishedAt;
    public final String status;
    public final String message;
    public final List<DataFileSpec> files;

    public DataManifest(int manifestVersion, int styleSchemaVersion, int minimumAppVersionCode,
                        String pack, String version, String publishedAt, String status,
                        String message, List<DataFileSpec> files) {
        this.manifestVersion = manifestVersion;
        this.styleSchemaVersion = styleSchemaVersion;
        this.minimumAppVersionCode = minimumAppVersionCode;
        this.pack = pack;
        this.version = version;
        this.publishedAt = publishedAt;
        this.status = status;
        this.message = message;
        this.files = Collections.unmodifiableList(files);
    }

    public DataFileSpec find(String id) {
        for (DataFileSpec file : files) {
            if (file.id.equals(id)) return file;
        }
        return null;
    }

    public DataFileSpec findByFileName(String fileName) {
        for (DataFileSpec file : files) {
            if (file.fileName.equals(fileName)) return file;
        }
        return null;
    }
}
