package com.rockmap.app;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in production diagnostics for RockMap.
 *
 * The historical class name is retained so existing observational call sites do not need to be
 * rewritten. Diagnostics are OFF by default. When enabled, records are kept only in a bounded
 * app-private log until the user explicitly exports them from Data settings.
 *
 * This class never advances tours, changes UI, filters GPS, invokes app actions, or repairs state.
 */
public final class TourDebugLog {
    private static final String MAIN_PREFS = "rockmap_guided_tour";
    private static final String FIELD_PREFS = "rockmap_field_tool_tour";
    private static final String DIAGNOSTICS_PREFS = "rockmap_diagnostics";
    private static final String KEY_ENABLED = "enabled";
    private static final String INTERNAL_FILE_NAME = "rockmap-diagnostics.log";

    private static final long MAX_INTERNAL_BYTES = 2L * 1024L * 1024L;
    private static final int KEEP_INTERNAL_BYTES = 1536 * 1024;

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final Object FILE_LOCK = new Object();
    private static final Object HUD_EVENT_LOCK = new Object();
    private static final Object GPS_EVENT_LOCK = new Object();

    private static final String DEBUG_SCHEMA = TourDebugCausality.SCHEMA;
    private static final String SESSION_ID = "S" + Long.toHexString(System.currentTimeMillis())
            + "-" + android.os.Process.myPid();

    /*
     * Injector compatibility markers. Source owns these diagnostics hooks now, so the existing
     * fail-closed tour-debug injector must treat them as already present.
     * causal-debug-session-fields
     * causal-debug-pref-listeners
     * causal-debug-lifecycle-resumed
     * causal-debug-lifecycle-destroyed
     * causal-debug-process-identity
     * causal-debug-coach-request-hook
     * causal-debug-coach-shown-hook
     * causal-debug-public-event-api
     * causal-debug-line-prefix
     */

    private static Location lastGpsFix;
    private static long lastHudFrameGeneration = Long.MIN_VALUE;
    private static long lastHudWaitGeneration = Long.MIN_VALUE;

    private static final ScheduledExecutorService IO =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "rockmap-diagnostics");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private static Context app;
    private static File internalLog;
    private static SharedPreferences mainPrefs;
    private static SharedPreferences fieldPrefs;
    private static SharedPreferences diagnosticsPrefs;
    private static SharedPreferences.OnSharedPreferenceChangeListener mainListener;
    private static SharedPreferences.OnSharedPreferenceChangeListener fieldListener;

    private TourDebugLog() {}

    /** Initializes diagnostics without enabling them or creating any diagnostic file. */
    public static void install(Context context) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        if (application == null) application = context;

        if (INITIALIZED.compareAndSet(false, true)) {
            app = application;
            internalLog = new File(app.getFilesDir(), INTERNAL_FILE_NAME);
            mainPrefs = app.getSharedPreferences(MAIN_PREFS, Context.MODE_PRIVATE);
            fieldPrefs = app.getSharedPreferences(FIELD_PREFS, Context.MODE_PRIVATE);
            diagnosticsPrefs = app.getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE);
        }

        if (enabled()) installHooksIfNeeded();
    }

    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        return context.getApplicationContext()
                .getSharedPreferences(DIAGNOSTICS_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean value) {
        install(context);
        if (diagnosticsPrefs == null) return;
        boolean current = diagnosticsPrefs.getBoolean(KEY_ENABLED, false);
        if (current == value) {
            if (value) installHooksIfNeeded();
            return;
        }

        if (!value && current) {
            recordImportant("DIAGNOSTICS", "state=disabled by_user=true");
        }
        diagnosticsPrefs.edit().putBoolean(KEY_ENABLED, value).apply();
        if (value) {
            installHooksIfNeeded();
            recordImportant("DIAGNOSTICS", "state=enabled by_user=true");
            recordProcessStart();
        }
    }

    public static boolean hasDiagnostics(Context context) {
        install(context);
        synchronized (FILE_LOCK) {
            return internalLog != null && internalLog.isFile() && internalLog.length() > 0L;
        }
    }

    public static long diagnosticBytes(Context context) {
        install(context);
        synchronized (FILE_LOCK) {
            return internalLog != null && internalLog.isFile() ? internalLog.length() : 0L;
        }
    }

    public static String suggestedExportFileName() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return "RockMap-Diagnostics-" + stamp + ".txt";
    }

    /** Writes diagnostics only to a URI explicitly selected by the user. */
    public static boolean exportTo(Context context, Uri uri) {
        if (context == null || uri == null) return false;
        install(context);
        try {
            byte[] bytes;
            synchronized (FILE_LOCK) {
                if (internalLog == null || !internalLog.isFile() || internalLog.length() <= 0L) {
                    return false;
                }
                bytes = readFile(internalLog);
            }
            ContentResolver resolver = context.getApplicationContext().getContentResolver();
            try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                if (output == null) return false;
                output.write(bytes);
                output.flush();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean clear(Context context) {
        install(context);
        synchronized (FILE_LOCK) {
            if (internalLog == null || !internalLog.exists()) return true;
            return internalLog.delete();
        }
    }

    private static boolean enabled() {
        return diagnosticsPrefs != null && diagnosticsPrefs.getBoolean(KEY_ENABLED, false);
    }

    private static void installHooksIfNeeded() {
        if (!enabled() || app == null || !HOOKS_INSTALLED.compareAndSet(false, true)) return;

        mainListener = (prefs, key) -> {
            record("MAIN_STATE", "changed=" + clean(key, 80) + " " + mainSnapshot());
            if ("tour_step".equals(key) || "tour_state".equals(key)) {
                TourDebugCausality.onMainTourStepChanged(
                        mainPrefs.getInt("tour_step", 1),
                        mainPrefs.getString("tour_state", "not_offered"));
            }
        };
        fieldListener = (prefs, key) ->
                record("FIELD_STATE", "changed=" + clean(key, 80) + " " + fieldSnapshot());

        mainPrefs.registerOnSharedPreferenceChangeListener(mainListener);
        fieldPrefs.registerOnSharedPreferenceChangeListener(fieldListener);

        if (app instanceof Application) {
            ((Application) app).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityCreated(Activity activity, android.os.Bundle state) {
                            record("ACTIVITY", activityName(activity) + " created savedState=" + (state != null));
                        }

                        @Override public void onActivityStarted(Activity activity) {
                            record("ACTIVITY", activityName(activity) + " started");
                        }

                        @Override public void onActivityResumed(Activity activity) {
                            TourDebugCausality.onActivityResumed(activity);
                            recordImportant("ACTIVITY",
                                    activityName(activity) + " resumed focus=" + activity.hasWindowFocus()
                                            + " main={" + mainSnapshot() + "} field={" + fieldSnapshot() + "}");
                        }

                        @Override public void onActivityPaused(Activity activity) {
                            recordImportant("ACTIVITY",
                                    activityName(activity) + " paused focus=" + activity.hasWindowFocus());
                        }

                        @Override public void onActivityStopped(Activity activity) {
                            record("ACTIVITY", activityName(activity) + " stopped");
                        }

                        @Override public void onActivitySaveInstanceState(
                                Activity activity, android.os.Bundle state) {
                            record("ACTIVITY", activityName(activity) + " saveInstanceState");
                        }

                        @Override public void onActivityDestroyed(Activity activity) {
                            TourDebugCausality.onActivityDestroyed(activity);
                            recordImportant("ACTIVITY",
                                    activityName(activity) + " destroyed finishing=" + activity.isFinishing());
                        }
                    });
        }
    }

    private static void recordProcessStart() {
        recordImportant("PROCESS_START",
                "debugSchema=" + DEBUG_SCHEMA
                        + " session=" + SESSION_ID
                        + " version=" + safeVersion()
                        + " code=" + safeVersionCode()
                        + " sdk=" + Build.VERSION.SDK_INT
                        + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
                        + " pid=" + android.os.Process.myPid()
                        + " main={" + mainSnapshot() + "}"
                        + " field={" + fieldSnapshot() + "}");
    }

    public static void coachRequest(Activity activity, long generation, int step, int total,
                                    String title, String requiredAction, View target) {
        TourDebugCausality.onCoachRequest(activity, step, generation);
        recordImportant("COACH_REQUEST",
                "gen=" + generation
                        + " activity=" + activityName(activity)
                        + " step=" + step + "/" + total
                        + " title=" + clean(title, 180)
                        + " action=" + clean(requiredAction, 220)
                        + " target={" + targetSummary(target) + "}");
    }

    public static void coachWait(Activity activity, long generation, int step, View target) {
        record("TARGET_WAIT_START",
                "gen=" + generation + " activity=" + activityName(activity)
                        + " step=" + step + " target={" + targetSummary(target) + "}");
    }

    public static void coachWaitProgress(Activity activity, long generation, int step,
                                         int attempt, View target) {
        record("TARGET_WAIT_PROGRESS",
                "gen=" + generation + " activity=" + activityName(activity)
                        + " step=" + step + " attempt=" + attempt
                        + " elapsedApproxMs=" + (attempt * 40L)
                        + " target={" + targetSummary(target) + "}");
    }

    public static void coachTargetReady(Activity activity, long generation, int step,
                                        int attempt, View target) {
        recordImportant("TARGET_READY",
                "gen=" + generation + " activity=" + activityName(activity)
                        + " step=" + step + " attempt=" + attempt
                        + " elapsedApproxMs=" + (attempt * 40L)
                        + " target={" + targetSummary(target) + "}");
    }

    public static void coachSuperseded(Activity activity, long generation, int step,
                                       String stage, View target) {
        recordImportant("REQUEST_SUPERSEDED",
                "gen=" + generation + " activity=" + activityName(activity)
                        + " step=" + step + " stage=" + clean(stage, 80)
                        + " target={" + targetSummary(target) + "}");
    }

    public static void coachTimeout(Activity activity, long generation, int step, View target) {
        recordImportant("TARGET_WAIT_TIMEOUT",
                "gen=" + generation + " activity=" + activityName(activity)
                        + " step=" + step + " attempts=250 elapsedApproxMs=10000"
                        + " target={" + targetSummary(target) + "}");
    }

    public static void coachShown(Activity activity, long generation, int step, int total,
                                  String title, View target, boolean dialogHost) {
        TourDebugCausality.onCoachShown(activity, step, generation);
        recordImportant("COACH_SHOWN",
                "gen=" + generation + " activity=" + activityName(activity)
                        + " step=" + step + "/" + total
                        + " title=" + clean(title, 180)
                        + " host=" + (dialogHost ? "dialog-popup" : "activity-content")
                        + " target={" + targetSummary(target) + "}");
    }

    public static void coachClear(Activity activity) {
        record("COACH_CLEAR", "activity=" + activityName(activity));
    }

    public static void coachAbort(Activity activity, long generation, int step,
                                  String reason, View target) {
        recordImportant("COACH_ABORT",
                "gen=" + generation + " activity=" + activityName(activity)
                        + " step=" + step + " reason=" + clean(reason, 140)
                        + " target={" + targetSummary(target) + "}");
    }

    public static void hudLifecycle(Activity activity, String event, long generation,
                                    String reason, String expandedTool, int measurementCount,
                                    View hud, View requiredTarget, long startedElapsed) {
        String type = clean(event, 60);
        synchronized (HUD_EVENT_LOCK) {
            if ("HUD_FRAME_RECHECK".equals(type)) {
                if (lastHudFrameGeneration == generation) return;
                lastHudFrameGeneration = generation;
            } else if ("HUD_LAYOUT_WAIT".equals(type)) {
                if (lastHudWaitGeneration == generation) return;
                lastHudWaitGeneration = generation;
            }
        }
        long elapsedMs = startedElapsed <= 0L ? -1L
                : Math.max(0L, SystemClock.elapsedRealtime() - startedElapsed);
        String detail = "gen=" + generation
                + " activity=" + activityName(activity)
                + " reason=" + clean(reason, 120)
                + " expandedTool=" + clean(expandedTool, 80)
                + " measurementCount=" + measurementCount
                + " elapsedMs=" + elapsedMs
                + " main={" + mainSnapshot() + "}"
                + " field={" + fieldSnapshot() + "}"
                + " hud={" + targetSummary(hud) + "}"
                + " requiredTarget={" + targetSummary(requiredTarget) + "}";
        if ("HUD_READY".equals(type) || "HUD_SUPERSEDED".equals(type)
                || "HUD_RECOVERY".equals(type) || "HUD_PASSIVE_IGNORED".equals(type)) {
            recordImportant(type, detail);
        } else {
            record(type, detail);
        }
    }

    public static void measurementPoint(Activity activity, String event,
                                        int beforeCount, int afterCount,
                                        String beforeTool, int beforeStep, String beforePhase,
                                        String afterTool, int afterStep, String afterPhase,
                                        double latitude, double longitude) {
        recordImportant(clean(event, 60),
                "activity=" + activityName(activity)
                        + " count=" + beforeCount + "->" + afterCount
                        + " tour=" + clean(beforeTool, 60) + ":" + beforeStep
                        + "/" + clean(beforePhase, 60)
                        + "->" + clean(afterTool, 60) + ":" + afterStep
                        + "/" + clean(afterPhase, 60)
                        + " point=" + String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
                        + " main={" + mainSnapshot() + "} field={" + fieldSnapshot() + "}");
    }

    public static void mainTourAction(Activity activity, String event, String detail) {
        recordImportant(clean(event, 60),
                "activity=" + activityName(activity)
                        + " detail=" + clean(detail, 600)
                        + " main={" + mainSnapshot() + "} field={" + fieldSnapshot() + "}");
    }

    /** Structured application/causal event. Diagnostic-only. */
    public static void causalEvent(String event, String detail) {
        recordImportant(clean(event, 60), clean(detail, 5000));
    }

    public static void appDiagnostic(String event, String detail) {
        record(clean(event, 60), clean(detail, 3000));
    }

    public static void headingDiagnostic(Activity activity, String event, String detail) {
        record(clean(event, 60),
                "activity=" + activityName(activity) + " " + clean(detail, 1800));
    }

    public static void mapDiagnostic(String event, String detail) {
        appDiagnostic(event, detail);
    }

    public static void gpsFix(Activity activity, Location location) {
        if (!enabled()) return;
        if (location == null) {
            recordImportant("GPS_FIX_NULL", "activity=" + activityName(activity));
            return;
        }

        Location previous;
        synchronized (GPS_EVENT_LOCK) {
            previous = lastGpsFix == null ? null : new Location(lastGpsFix);
            lastGpsFix = new Location(location);
        }

        long ageMs = -1L;
        long elapsedNanos = location.getElapsedRealtimeNanos();
        if (elapsedNanos > 0L) {
            long ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedNanos;
            if (ageNanos >= 0L) ageMs = ageNanos / 1_000_000L;
        }

        float moveMeters = -1f;
        long intervalMs = -1L;
        float impliedSpeedMps = -1f;
        float previousAccuracy = -1f;
        if (previous != null) {
            try {
                moveMeters = previous.distanceTo(location);
            } catch (RuntimeException ignored) {
                moveMeters = -1f;
            }
            long previousElapsed = previous.getElapsedRealtimeNanos();
            if (elapsedNanos > 0L && previousElapsed > 0L && elapsedNanos >= previousElapsed) {
                intervalMs = (elapsedNanos - previousElapsed) / 1_000_000L;
                if (intervalMs > 0L && moveMeters >= 0f) {
                    impliedSpeedMps = moveMeters / (intervalMs / 1000f);
                }
            }
            if (previous.hasAccuracy()) previousAccuracy = previous.getAccuracy();
        }

        float accuracy = location.hasAccuracy() ? location.getAccuracy() : -1f;
        float accuracyBudget = accuracy >= 0f && previousAccuracy >= 0f
                ? accuracy + previousAccuracy : -1f;
        boolean outsideAccuracyBudget = moveMeters >= 0f && accuracyBudget >= 0f
                && intervalMs > 0L && intervalMs <= 10_000L
                && moveMeters > Math.max(10f, accuracyBudget);

        String detail = String.format(Locale.US,
                "activity=%s provider=%s lat=%.6f lon=%.6f accuracyM=%s ageMs=%d intervalMs=%d "
                        + "moveM=%s impliedSpeedMps=%s reportedSpeedMps=%s speedAccuracyMps=%s "
                        + "bearingDeg=%s bearingAccuracyDeg=%s altitudeM=%s previousAccuracyM=%s "
                        + "accuracyBudgetM=%s outsideAccuracyBudget=%s mock=%s",
                activityName(activity),
                clean(location.getProvider(), 80),
                location.getLatitude(), location.getLongitude(),
                numberOrNa(accuracy), ageMs, intervalMs,
                numberOrNa(moveMeters), numberOrNa(impliedSpeedMps),
                numberOrNa(location.hasSpeed() ? location.getSpeed() : -1f),
                numberOrNa(location.hasSpeedAccuracy() ? location.getSpeedAccuracyMetersPerSecond() : -1f),
                numberOrNa(location.hasBearing() ? location.getBearing() : -1f),
                numberOrNa(location.hasBearingAccuracy() ? location.getBearingAccuracyDegrees() : -1f),
                location.hasAltitude() ? String.format(Locale.US, "%.1f", location.getAltitude()) : "n/a",
                numberOrNa(previousAccuracy), numberOrNa(accuracyBudget),
                outsideAccuracyBudget, location.isFromMockProvider());
        if (outsideAccuracyBudget) recordImportant("GPS_FIX_JUMP", detail);
        else record("GPS_FIX", detail);
    }

    public static void mineralSortDiagnostic(Activity activity, String event, String detail) {
        recordImportant(clean(event, 60),
                "activity=" + activityName(activity)
                        + " " + clean(detail, 320)
                        + " main={" + mainSnapshot() + "}");
    }

    public static void researchPresentation(Activity activity, String event, String state,
                                            View workspace, View mappedPanel, View dragControl,
                                            View collapseControl, View collapsedReopen) {
        recordImportant(clean(event, 60),
                "activity=" + activityName(activity)
                        + " state=" + clean(state, 160)
                        + " main={" + mainSnapshot() + "}"
                        + " workspace={" + targetSummary(workspace) + "}"
                        + " mappedPanel={" + targetSummary(mappedPanel) + "}"
                        + " drag={" + targetSummary(dragControl) + "}"
                        + " collapse={" + targetSummary(collapseControl) + "}"
                        + " collapsedReopen={" + targetSummary(collapsedReopen) + "}");
    }

    private static String numberOrNa(float value) {
        return Float.isFinite(value) && value >= 0f
                ? String.format(Locale.US, "%.2f", value) : "n/a";
    }

    private static String mainSnapshot() {
        if (mainPrefs == null) return "unavailable";
        return "state=" + mainPrefs.getString("tour_state", "not_offered")
                + ",topic=" + mainPrefs.getString("tour_topic", "full")
                + ",step=" + mainPrefs.getInt("tour_step", 1)
                + ",start=" + mainPrefs.getInt("tour_start_step", 1)
                + ",end=" + mainPrefs.getInt("tour_end_step", 19);
    }

    private static String fieldSnapshot() {
        if (fieldPrefs == null) return "unavailable";
        return "active=" + fieldPrefs.getBoolean("active", false)
                + ",tool=" + fieldPrefs.getString("tool", "")
                + ",step=" + fieldPrefs.getInt("step", 0)
                + ",entity=" + fieldPrefs.getLong("entity_id", -1L)
                + ",aux=" + fieldPrefs.getLong("aux_id", -1L);
    }

    private static String targetSummary(View target) {
        if (target == null) return "null";
        try {
            String id = "none";
            int viewId = target.getId();
            if (viewId != View.NO_ID) {
                try {
                    id = target.getResources().getResourceEntryName(viewId);
                } catch (Throwable ignored) {
                    id = Integer.toString(viewId);
                }
            }

            Object tag = target.getTag();
            CharSequence description = target.getContentDescription();
            Rect visible = new Rect();
            boolean globalVisible = false;
            boolean logicallyVisible = target.isAttachedToWindow()
                    && target.getVisibility() == View.VISIBLE
                    && target.isShown() && target.getAlpha() >= 0.05f;
            if (logicallyVisible) {
                try {
                    globalVisible = target.getGlobalVisibleRect(visible)
                            && visible.width() > 0 && visible.height() > 0;
                } catch (Throwable ignored) {
                    visible.setEmpty();
                }
            } else {
                visible.setEmpty();
            }

            return "class=" + target.getClass().getSimpleName()
                    + ",id=" + clean(id, 100)
                    + ",tag=" + clean(tag == null ? "" : String.valueOf(tag), 180)
                    + ",desc=" + clean(description == null ? "" : description.toString(), 220)
                    + ",attached=" + target.isAttachedToWindow()
                    + ",shown=" + target.isShown()
                    + ",visibility=" + target.getVisibility()
                    + ",alpha=" + target.getAlpha()
                    + ",size=" + target.getWidth() + "x" + target.getHeight()
                    + ",globalVisible=" + globalVisible
                    + ",rect=" + visible.left + "," + visible.top + ","
                    + visible.right + "," + visible.bottom;
        } catch (Throwable error) {
            return "summary-error=" + error.getClass().getSimpleName();
        }
    }

    private static String activityName(Activity activity) {
        return activity == null ? "null" : activity.getClass().getSimpleName();
    }

    private static String safeVersion() {
        try {
            return BuildConfig.VERSION_NAME;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static long safeVersionCode() {
        try {
            return BuildConfig.VERSION_CODE;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static void record(String type, String detail) {
        enqueue(type, detail);
    }

    private static void recordImportant(String type, String detail) {
        enqueue(type, detail);
    }

    private static void enqueue(String type, String detail) {
        if (!enabled() || app == null || internalLog == null) return;

        long sequence = SEQUENCE.incrementAndGet();
        long elapsed = SystemClock.elapsedRealtime();
        String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());

        String context;
        try {
            context = TourDebugCausality.contextSummary();
        } catch (Throwable ignored) {
            context = "cause=unavailable";
        }

        String line = timestamp
                + " | +" + elapsed + "ms"
                + " | #" + sequence
                + " | session=" + SESSION_ID
                + " | schema=" + DEBUG_SCHEMA
                + " | " + clean(type, 60)
                + " | " + clean(context, 700)
                + " | " + clean(detail, 5000)
                + "\n";

        IO.execute(() -> appendLine(line));
    }

    private static void appendLine(String line) {
        synchronized (FILE_LOCK) {
            try {
                trimIfNeeded();
                try (FileOutputStream output = new FileOutputStream(internalLog, true)) {
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (IOException ignored) {
                // Diagnostics must never interfere with RockMap.
            }
        }
    }

    private static void trimIfNeeded() throws IOException {
        if (internalLog == null || !internalLog.isFile()
                || internalLog.length() <= MAX_INTERNAL_BYTES) {
            return;
        }

        byte[] bytes = readFile(internalLog);
        int start = Math.max(0, bytes.length - KEEP_INTERNAL_BYTES);
        while (start < bytes.length && bytes[start] != '\n') start++;
        if (start < bytes.length) start++;

        try (FileOutputStream output = new FileOutputStream(internalLog, false)) {
            output.write("[older RockMap diagnostics entries trimmed]\n"
                    .getBytes(StandardCharsets.UTF_8));
            output.write(bytes, Math.min(start, bytes.length),
                    bytes.length - Math.min(start, bytes.length));
        }
    }

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return compact.length() <= max
                ? compact
                : compact.substring(0, max) + "…";
    }
}
