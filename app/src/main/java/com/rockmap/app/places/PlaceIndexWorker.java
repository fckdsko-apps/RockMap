package com.rockmap.app.places;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.rockmap.app.offline.DataFileSpec;
import com.rockmap.app.offline.DataManifest;
import com.rockmap.app.offline.OfflineDataManager;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Background, network-free generation of the local Find index from the installed basemap. */
public final class PlaceIndexWorker extends Worker {
    private static final String UNIQUE_WORK = "rockmap-local-place-index-v2";

    public PlaceIndexWorker(@NonNull Context appContext, @NonNull WorkerParameters params) {
        super(appContext, params);
    }

    /**
     * App-start behavior. If no basemap is installed yet, the worker safely retries later;
     * opening Find also queues an immediate run after the basemap becomes available.
     */
    public static void enqueueIfNeeded(Context context) {
        Context app = context.getApplicationContext();
        OfflineDataManager manager = new OfflineDataManager(app);
        String baseSha = activeBaseSha(manager);
        if (baseSha != null && PlaceIndexRepository.isCurrentIndex(app, baseSha)) return;
        enqueueInternal(app, ExistingWorkPolicy.KEEP);
    }

    /** Find/app use: keep an already-running build instead of repeatedly cancelling it. */
    public static void enqueue(Context context) {
        enqueueInternal(context.getApplicationContext(), ExistingWorkPolicy.KEEP);
    }

    /** Basemap activation: replace any delayed worker that was waiting for map data. */
    public static void enqueueReplacing(Context context) {
        enqueueInternal(context.getApplicationContext(), ExistingWorkPolicy.REPLACE);
    }

    private static void enqueueInternal(Context context, ExistingWorkPolicy policy) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(PlaceIndexWorker.class)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, policy, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        OfflineDataManager manager = new OfflineDataManager(context);
        DataManifest manifest = manager.getActiveManifest();
        DataFileSpec baseSpec = manifest == null ? null : manifest.find("base");
        File baseFile = manager.getActiveFile("base");

        if (baseSpec == null || baseFile == null) {
            // Fresh installs can reach this worker before the normal map download finishes.
            // Retry for a bounded window; Find itself can always queue an immediate run later.
            return getRunAttemptCount() < 12 ? Result.retry() : Result.success();
        }
        if (baseSpec.sha256 == null || !baseSpec.sha256.matches("(?i)[0-9a-f]{64}")) {
            return Result.failure();
        }
        if (PlaceIndexRepository.isCurrentIndex(context, baseSpec.sha256)) {
            return Result.success();
        }

        File index = PlaceIndexRepository.indexFile(context);
        File temp = new File(index.getParentFile(),
                PlaceIndexRepository.INDEX_FILE + "." + UUID.randomUUID() + ".part");
        if (temp.exists() && !temp.delete()) return Result.failure();

        try {
            PmtilesPlaceIndexer.build(baseFile, temp, baseSpec.sha256, this::isStopped);
            if (isStopped()) {
                temp.delete();
                return Result.retry();
            }
            activate(temp, index);
            if (!PlaceIndexRepository.isCurrentIndex(context, baseSpec.sha256)) {
                index.delete();
                return Result.failure();
            }
            return Result.success();
        } catch (IOException | RuntimeException ex) {
            temp.delete();
            return Result.failure();
        }
    }

    private static String activeBaseSha(OfflineDataManager manager) {
        DataManifest manifest = manager.getActiveManifest();
        if (manifest == null || manager.getActiveFile("base") == null) return null;
        DataFileSpec spec = manifest.find("base");
        if (spec == null || spec.sha256 == null || !spec.sha256.matches("(?i)[0-9a-f]{64}")) return null;
        return spec.sha256;
    }

    private static void activate(File temp, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("cannot create local place-index directory");
        }
        try {
            Os.rename(temp.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException ex) {
            throw new IOException("could not activate local place index: " + ex.getMessage(), ex);
        }
    }
}
