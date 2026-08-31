package com.rockmap.app.updates;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.rockmap.app.DataUpdateSettingsActivity;
import com.rockmap.app.TourDebugLog;
import com.rockmap.app.offline.DataManifest;
import com.rockmap.app.offline.DataUpdatePreviewer;
import com.rockmap.app.offline.OfflineDataManager;
import com.rockmap.app.research.GeologyDataManager;
import com.rockmap.app.research.GeologyDataPreviewer;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Owns RockMap's user-selected manifest-scan schedule and the persisted result of the last scan.
 *
 * Large data packs are never downloaded here. This class schedules/checks only small release
 * manifests and posts a notification only when an update is available and Android permits alerts.
 */
public final class DataUpdateScheduler {
    public static final String FREQUENCY_NEVER = "never";
    public static final String FREQUENCY_DAILY = "daily";
    public static final String FREQUENCY_WEEKLY = "weekly";
    public static final String FREQUENCY_MONTHLY = "monthly";

    private static final String SETTINGS_PREFS = "rockmap_data_update_settings";
    private static final String STATE_PREFS = "rockmap_data_update_state";
    private static final String KEY_FREQUENCY = "frequency";
    private static final String KEY_NOTIFICATION_REQUESTED = "notification_permission_requested";
    private static final String KEY_FIRST_RUN_ONBOARDING_SEEN = "first_run_onboarding_seen";

    private static final String KEY_LAST_CHECKED_AT = "last_checked_at";
    private static final String KEY_LAST_RESULT = "last_result";
    private static final String KEY_CORE_AVAILABLE = "core_available";
    private static final String KEY_CORE_VERSION = "core_version";
    private static final String KEY_CORE_BYTES = "core_bytes";
    private static final String KEY_CORE_ERROR = "core_error";
    private static final String KEY_GEOLOGY_AVAILABLE = "geology_available";
    private static final String KEY_GEOLOGY_VERSION = "geology_version";
    private static final String KEY_GEOLOGY_BYTES = "geology_bytes";
    private static final String KEY_GEOLOGY_ERROR = "geology_error";
    private static final String KEY_LAST_NOTIFIED_FINGERPRINT = "last_notified_fingerprint";

    private static final String PERIODIC_WORK = "rockmap-periodic-data-update-scan";
    private static final String CHANNEL_ID = "rockmap-data-updates";
    private static final int NOTIFICATION_ID = 7412;

    private DataUpdateScheduler() {}

    public static void ensureScheduled(Context context) {
        Context app = context.getApplicationContext();

        // Do not silently opt a brand-new installation into background manifest checks before
        // RockMap has shown the first-run frequency choice. The UI still recommends Weekly.
        if (shouldShowFirstRunOnboarding(app)) {
            WorkManager.getInstance(app).cancelUniqueWork(PERIODIC_WORK);
            TourDebugLog.mapDiagnostic("DATA_UPDATE_SCHEDULE",
                    "state=awaiting_onboarding");
            return;
        }

        setScheduleInternal(app, getFrequency(app), false);
    }

    public static void setFrequency(Context context, String frequency) {
        String normalized = normalizeFrequency(frequency);
        context.getApplicationContext()
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_FREQUENCY, normalized).apply();
        setScheduleInternal(context.getApplicationContext(), normalized, true);
    }

    public static String getFrequency(Context context) {
        String value = context.getApplicationContext()
                .getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_FREQUENCY, FREQUENCY_WEEKLY);
        return normalizeFrequency(value);
    }

    public static String frequencyLabel(String frequency) {
        switch (normalizeFrequency(frequency)) {
            case FREQUENCY_NEVER: return "Never";
            case FREQUENCY_DAILY: return "Daily";
            case FREQUENCY_MONTHLY: return "Monthly";
            case FREQUENCY_WEEKLY:
            default: return "Weekly";
        }
    }

    private static String normalizeFrequency(String value) {
        if (FREQUENCY_NEVER.equals(value)
                || FREQUENCY_DAILY.equals(value)
                || FREQUENCY_WEEKLY.equals(value)
                || FREQUENCY_MONTHLY.equals(value)) {
            return value;
        }
        return FREQUENCY_WEEKLY;
    }

    private static void setScheduleInternal(Context context, String frequency, boolean userChange) {
        WorkManager manager = WorkManager.getInstance(context);
        if (FREQUENCY_NEVER.equals(frequency)) {
            manager.cancelUniqueWork(PERIODIC_WORK);
            TourDebugLog.mapDiagnostic("DATA_UPDATE_SCHEDULE",
                    "state=disabled source=" + (userChange ? "user" : "startup"));
            return;
        }

        long hours = FREQUENCY_DAILY.equals(frequency) ? 24L
                : FREQUENCY_MONTHLY.equals(frequency) ? 24L * 30L
                : 24L * 7L;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                DataUpdateCheckWorker.class, hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(PERIODIC_WORK)
                .build();
        manager.enqueueUniquePeriodicWork(
                PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
        TourDebugLog.mapDiagnostic("DATA_UPDATE_SCHEDULE",
                "state=scheduled frequency=" + frequency
                        + " intervalHours=" + hours
                        + " source=" + (userChange ? "user" : "startup"));
    }

    public static State recordScan(Context context,
                                   DataUpdatePreviewer.Preview core,
                                   String coreError,
                                   GeologyDataPreviewer.Preview geology,
                                   String geologyError,
                                   String source) {
        Context app = context.getApplicationContext();
        reconcileInstalledState(app);
        SharedPreferences state = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = state.edit();

        OfflineDataManager coreManager = new OfflineDataManager(app);
        boolean coreInstalled = coreManager.hasRenderableActivePack();
        String installedCoreVersion = installedCoreVersion(app);
        if (core != null) {
            boolean available = coreInstalled && core.renderable
                    && (core.estimatedDownloadBytes > 0L
                    || (!core.version.isEmpty() && !core.version.equals(installedCoreVersion)));
            edit.putBoolean(KEY_CORE_AVAILABLE, available)
                    .putString(KEY_CORE_VERSION, core.version)
                    .putLong(KEY_CORE_BYTES, available ? Math.max(0L, core.estimatedDownloadBytes) : 0L)
                    .putString(KEY_CORE_ERROR, "");
        } else if (coreError != null && !coreError.trim().isEmpty()) {
            edit.putString(KEY_CORE_ERROR, clean(coreError));
        }

        if (geology != null) {
            String installedGeologyVersion =
                    clean(new GeologyDataManager(app).getInstalledVersion());
            boolean geologyInstalled = !installedGeologyVersion.isEmpty();
            boolean available = geologyInstalled && geology.published && geology.needsDownload;
            edit.putBoolean(KEY_GEOLOGY_AVAILABLE, available)
                    .putString(KEY_GEOLOGY_VERSION, geology.version)
                    .putLong(KEY_GEOLOGY_BYTES, available ? Math.max(0L, geology.downloadBytes) : 0L)
                    .putString(KEY_GEOLOGY_ERROR, "");
        } else if (geologyError != null && !geologyError.trim().isEmpty()) {
            edit.putString(KEY_GEOLOGY_ERROR, clean(geologyError));
        }

        long now = System.currentTimeMillis();
        edit.putLong(KEY_LAST_CHECKED_AT, now);
        edit.apply();

        State afterData = readState(app);
        String result = buildResult(afterData, coreError, geologyError);
        state.edit().putString(KEY_LAST_RESULT, result).apply();
        State finalState = readState(app);

        String scanState = finalState.hasUpdate() ? "update_found"
                : hasError(coreError) && hasError(geologyError) ? "failure"
                : hasError(coreError) || hasError(geologyError) ? "partial_failure"
                : "no_update";
        TourDebugLog.mapDiagnostic("DATA_UPDATE_SCAN",
                "type=" + clean(source)
                        + " state=" + scanState
                        + " core=" + finalState.coreAvailable
                        + " geology=" + finalState.geologyAvailable
                        + " bytes=" + finalState.totalDownloadBytes());
        return finalState;
    }

    public static State getState(Context context) {
        Context app = context.getApplicationContext();
        reconcileInstalledState(app);
        return readState(app);
    }

    private static State readState(Context context) {
        SharedPreferences p = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        return new State(
                getFrequency(context),
                p.getLong(KEY_LAST_CHECKED_AT, 0L),
                p.getString(KEY_LAST_RESULT, ""),
                p.getBoolean(KEY_CORE_AVAILABLE, false),
                p.getString(KEY_CORE_VERSION, ""),
                p.getLong(KEY_CORE_BYTES, 0L),
                p.getString(KEY_CORE_ERROR, ""),
                p.getBoolean(KEY_GEOLOGY_AVAILABLE, false),
                p.getString(KEY_GEOLOGY_VERSION, ""),
                p.getLong(KEY_GEOLOGY_BYTES, 0L),
                p.getString(KEY_GEOLOGY_ERROR, ""));
    }

    /**
     * If an update was installed from RockMap's older Offline Data screen, clear a stale
     * "available" badge when the installed versions now match the versions from the last scan.
     */
    private static void reconcileInstalledState(Context context) {
        SharedPreferences p = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = null;

        if (p.getBoolean(KEY_CORE_AVAILABLE, false)) {
            String remote = p.getString(KEY_CORE_VERSION, "");
            OfflineDataManager manager = new OfflineDataManager(context);
            DataManifest active = manager.getActiveManifest();
            String installed = active == null ? "" : clean(active.version);
            if (!remote.isEmpty() && remote.equals(installed) && manager.hasRenderableActivePack()) {
                edit = p.edit().putBoolean(KEY_CORE_AVAILABLE, false).putLong(KEY_CORE_BYTES, 0L);
            }
        }

        if (p.getBoolean(KEY_GEOLOGY_AVAILABLE, false)) {
            String remote = p.getString(KEY_GEOLOGY_VERSION, "");
            String installed = clean(new GeologyDataManager(context).getInstalledVersion());
            if (!remote.isEmpty() && remote.equals(installed)) {
                if (edit == null) edit = p.edit();
                edit.putBoolean(KEY_GEOLOGY_AVAILABLE, false).putLong(KEY_GEOLOGY_BYTES, 0L);
            }
        }

        if (edit != null) edit.apply();
    }

    public static void clearAvailability(Context context) {
        context.getApplicationContext().getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CORE_AVAILABLE, false)
                .putLong(KEY_CORE_BYTES, 0L)
                .putBoolean(KEY_GEOLOGY_AVAILABLE, false)
                .putLong(KEY_GEOLOGY_BYTES, 0L)
                .putString(KEY_LAST_NOTIFIED_FINGERPRINT, "")
                .apply();
    }

    public static void markCurrentResultSeen(Context context) {
        State state = getState(context);
        context.getApplicationContext().getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_NOTIFIED_FINGERPRINT,
                        state.hasUpdate() ? fingerprint(state) : "").apply();
    }

    public static boolean maybeNotify(Context context, State state) {
        if (state == null || !state.hasUpdate()) return false;
        Context app = context.getApplicationContext();
        ensureNotificationChannel(app);

        String fingerprint = fingerprint(state);
        String previous = app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_NOTIFIED_FINGERPRINT, "");
        if (!fingerprint.isEmpty() && fingerprint.equals(previous)) {
            TourDebugLog.mapDiagnostic("DATA_UPDATE_NOTIFICATION",
                    "state=suppressed_duplicate fingerprint=" + fingerprint);
            return false;
        }

        if (!areAlertsEnabled(app)) {
            TourDebugLog.mapDiagnostic("DATA_UPDATE_NOTIFICATION",
                    "state=blocked_permission");
            return false;
        }

        Intent open = new Intent(app, DataUpdateSettingsActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                app, 7412, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("RockMap data update available")
                .setContentText(notificationText(state))
                .setStyle(new Notification.BigTextStyle().bigText(notificationText(state)))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS);

        NotificationManager manager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, builder.build());
        app.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_NOTIFIED_FINGERPRINT, fingerprint).apply();
        TourDebugLog.mapDiagnostic("DATA_UPDATE_NOTIFICATION",
                "state=posted fingerprint=" + fingerprint);
        return true;
    }

    public static void cancelUpdateNotification(Context context) {
        NotificationManager manager = (NotificationManager)
                context.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(NOTIFICATION_ID);
    }

    public static void ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "RockMap data updates", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(
                "Notifies you when a new RockMap offline data or Colorado geology release is available.");
        manager.createNotificationChannel(channel);
    }

    public static boolean areAlertsEnabled(Context context) {
        Context app = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager =
                (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager.areNotificationsEnabled();
    }

    public static boolean wasNotificationPermissionRequested(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFICATION_REQUESTED, false);
    }

    public static void markNotificationPermissionRequested(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply();
    }

    public static boolean shouldShowFirstRunOnboarding(Context context) {
        return !context.getApplicationContext()
                .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FIRST_RUN_ONBOARDING_SEEN, false);
    }

    public static void markFirstRunOnboardingSeen(Context context) {
        context.getApplicationContext()
                .getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FIRST_RUN_ONBOARDING_SEEN, true).apply();
    }

    public static String installedCoreVersion(Context context) {
        DataManifest active = new OfflineDataManager(context).getActiveManifest();
        return active == null ? "" : clean(active.version);
    }

    private static String buildResult(State state, String coreError, String geologyError) {
        if (state.hasUpdate()) {
            String what = state.coreAvailable && state.geologyAvailable
                    ? "Core data and Colorado geology updates are available."
                    : state.coreAvailable
                    ? "A Core RockMap data update is available."
                    : "A Colorado geology update is available.";
            if (hasError(coreError) || hasError(geologyError)) {
                what += " One manifest could not be checked.";
            }
            return what;
        }
        if (hasError(coreError) && hasError(geologyError)) {
            return "RockMap could not check either update manifest.";
        }
        if (hasError(coreError) || hasError(geologyError)) {
            return "One update manifest could not be checked; the other is current.";
        }
        return "RockMap data is current.";
    }

    private static String notificationText(State state) {
        String what = state.coreAvailable && state.geologyAvailable
                ? "Core data and Colorado geology"
                : state.coreAvailable ? "Core RockMap data" : "Colorado geology";
        long bytes = state.totalDownloadBytes();
        return bytes > 0L
                ? what + " update available (" + formatBytes(bytes) + "). Tap to review and install."
                : what + " update available. No additional asset transfer is currently expected.";
    }

    private static String fingerprint(State state) {
        if (state == null || !state.hasUpdate()) return "";
        return (state.coreAvailable ? "c:" + clean(state.coreVersion) + ":" + state.coreDownloadBytes : "c:-")
                + "|" + (state.geologyAvailable
                ? "g:" + clean(state.geologyVersion) + ":" + state.geologyDownloadBytes : "g:-");
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 MB";
        double mb = bytes / (1024d * 1024d);
        if (mb < 0.1d) return String.format(Locale.US, "%.0f KB", bytes / 1024d);
        if (mb < 10d) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.0f MB", mb);
    }

    private static boolean hasError(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class State {
        public final String frequency;
        public final long lastCheckedAt;
        public final String lastResult;
        public final boolean coreAvailable;
        public final String coreVersion;
        public final long coreDownloadBytes;
        public final String coreError;
        public final boolean geologyAvailable;
        public final String geologyVersion;
        public final long geologyDownloadBytes;
        public final String geologyError;

        State(String frequency, long lastCheckedAt, String lastResult,
              boolean coreAvailable, String coreVersion, long coreDownloadBytes, String coreError,
              boolean geologyAvailable, String geologyVersion, long geologyDownloadBytes,
              String geologyError) {
            this.frequency = frequency;
            this.lastCheckedAt = lastCheckedAt;
            this.lastResult = clean(lastResult);
            this.coreAvailable = coreAvailable;
            this.coreVersion = clean(coreVersion);
            this.coreDownloadBytes = Math.max(0L, coreDownloadBytes);
            this.coreError = clean(coreError);
            this.geologyAvailable = geologyAvailable;
            this.geologyVersion = clean(geologyVersion);
            this.geologyDownloadBytes = Math.max(0L, geologyDownloadBytes);
            this.geologyError = clean(geologyError);
        }

        public boolean hasUpdate() {
            return coreAvailable || geologyAvailable;
        }

        public long totalDownloadBytes() {
            try {
                return Math.addExact(coreAvailable ? coreDownloadBytes : 0L,
                        geologyAvailable ? geologyDownloadBytes : 0L);
            } catch (ArithmeticException ex) {
                return Long.MAX_VALUE;
            }
        }
    }
}
