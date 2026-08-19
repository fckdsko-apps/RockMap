package com.rockmap.app.places;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.File;

/**
 * Compatibility shell for the abandoned Alpha 6.6 on-device statewide scanner.
 *
 * Keeping this class lets WorkManager safely resolve a persisted job after an APK upgrade,
 * while doWork() exits immediately. New code never schedules this worker.
 */
public final class PlaceIndexWorker extends Worker {
    private static final String LEGACY_UNIQUE_WORK = "rockmap-local-place-index-v2";

    public PlaceIndexWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    public static void cancelLegacy(Context context) {
        Context app = context.getApplicationContext();
        WorkManager.getInstance(app).cancelUniqueWork(LEGACY_UNIQUE_WORK);
        File legacyDir = new File(app.getFilesDir(), "place-search");
        File[] files = legacyDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) file.delete();
            }
        }
        legacyDir.delete();
    }

    @NonNull
    @Override
    public Result doWork() {
        return Result.success();
    }
}
