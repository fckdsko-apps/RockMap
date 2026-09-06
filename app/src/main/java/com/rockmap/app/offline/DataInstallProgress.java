package com.rockmap.app.offline;

import androidx.work.Data;

/** Shared WorkManager progress contract for RockMap reference-data installs. */
public final class DataInstallProgress {
    public static final String KEY_PACKAGE = "rockmap_progress_package";
    public static final String KEY_PHASE = "rockmap_progress_phase";
    public static final String KEY_BYTES_DONE = "rockmap_progress_bytes_done";
    public static final String KEY_BYTES_TOTAL = "rockmap_progress_bytes_total";
    public static final String KEY_INDETERMINATE = "rockmap_progress_indeterminate";

    public static final String PACKAGE_CORE = "core";
    public static final String PACKAGE_GEOLOGY = "geology";

    private DataInstallProgress() {}

    public static Data value(String packageName, String phase,
                             long bytesDone, long bytesTotal, boolean indeterminate) {
        return new Data.Builder()
                .putString(KEY_PACKAGE, packageName == null ? "" : packageName)
                .putString(KEY_PHASE, phase == null ? "" : phase)
                .putLong(KEY_BYTES_DONE, Math.max(0L, bytesDone))
                .putLong(KEY_BYTES_TOTAL, Math.max(0L, bytesTotal))
                .putBoolean(KEY_INDETERMINATE, indeterminate)
                .build();
    }
}
