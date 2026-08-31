package com.rockmap.app.updates;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.rockmap.app.BuildConfig;
import com.rockmap.app.TourDebugLog;
import com.rockmap.app.offline.DataUpdatePreviewer;
import com.rockmap.app.offline.OfflineDataManager;
import com.rockmap.app.research.GeologyDataPreviewer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduled manifest-only update scan.
 *
 * It deliberately reuses the same previewers as RockMap's user-facing size checks and never
 * queues DataUpdateWorker/GeologyDataUpdateWorker. A large download requires user action.
 */
public final class DataUpdateCheckWorker extends Worker {
    private static final long WAIT_SECONDS = 75L;

    public DataUpdateCheckWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();

        // A fresh installation is handled by InitialDataSetupActivity, not by an "update" alert.
        if (!new OfflineDataManager(app).hasRenderableActivePack()) {
            TourDebugLog.mapDiagnostic("DATA_UPDATE_SCAN",
                    "type=scheduled state=skipped reason=initial_core_data_not_ready");
            return Result.success();
        }

        TourDebugLog.mapDiagnostic("DATA_UPDATE_SCAN", "type=scheduled state=start");

        AtomicReference<DataUpdatePreviewer.Preview> core = new AtomicReference<>();
        AtomicReference<GeologyDataPreviewer.Preview> geology = new AtomicReference<>();
        AtomicReference<String> coreError = new AtomicReference<>("");
        AtomicReference<String> geologyError = new AtomicReference<>("");
        CountDownLatch latch = new CountDownLatch(2);

        if (BuildConfig.DATA_MANIFEST_URL == null || BuildConfig.DATA_MANIFEST_URL.trim().isEmpty()) {
            coreError.set("Core data manifest is not configured.");
            latch.countDown();
        } else {
            DataUpdatePreviewer.preview(app, BuildConfig.DATA_MANIFEST_URL,
                    new DataUpdatePreviewer.Callback() {
                @Override public void onPreview(DataUpdatePreviewer.Preview preview) {
                    core.set(preview);
                    latch.countDown();
                }

                @Override public void onError(String message) {
                    coreError.set(message == null ? "Core update check failed." : message);
                    latch.countDown();
                }
            });
        }

        if (BuildConfig.GEOLOGY_MANIFEST_URL == null
                || BuildConfig.GEOLOGY_MANIFEST_URL.trim().isEmpty()) {
            geologyError.set("Colorado geology manifest is not configured.");
            latch.countDown();
        } else {
            GeologyDataPreviewer.preview(app, BuildConfig.GEOLOGY_MANIFEST_URL,
                    new GeologyDataPreviewer.Callback() {
                @Override public void onPreview(GeologyDataPreviewer.Preview preview) {
                    geology.set(preview);
                    latch.countDown();
                }

                @Override public void onError(String message) {
                    geologyError.set(message == null ? "Geology update check failed." : message);
                    latch.countDown();
                }
            });
        }

        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                if (core.get() == null && coreError.get().isEmpty()) {
                    coreError.set("Core update check timed out.");
                }
                if (geology.get() == null && geologyError.get().isEmpty()) {
                    geologyError.set("Geology update check timed out.");
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }

        DataUpdateScheduler.State state = DataUpdateScheduler.recordScan(
                app, core.get(), coreError.get(), geology.get(), geologyError.get(), "scheduled");

        if (state.hasUpdate()) {
            DataUpdateScheduler.maybeNotify(app, state);
        } else if (coreError.get().isEmpty() && geologyError.get().isEmpty()) {
            DataUpdateScheduler.cancelUpdateNotification(app);
        }

        if (core.get() == null && geology.get() == null) {
            return Result.retry();
        }
        return Result.success();
    }
}
