package com.rockmap.app;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic-only observer for guided-tour state/UI drift.
 *
 * This class does not advance, repair, reopen, hide, enable, disable, or otherwise mutate tour UI.
 * It observes persisted state, Activity lifecycle, and the live View hierarchy, then writes a
 * compact audit trail to internal storage and mirrors it to Downloads/RockMap/RockMap-Tour-Audit.txt.
 */
public final class TourAudit {
    private static final String BASE_HEAD = "53e28ddcc45ed9ef0cd99eadc277b65718febdc0";
    private static final String TOUR_PREFS = "rockmap_guided_tour";
    private static final String RESEARCH_PREFS = "rockmap-research-session-v1";
    private static final String FIELD_TOUR_PREFS = "rockmap_field_tool_tour";
    private static final String FIELD_RUNTIME_PREFS = "rockmap_field_tour_runtime";
    private static final String AUDIT_PREFS = "rockmap_tour_audit_storage";
    private static final String AUDIT_URI = "downloads_uri";
    private static final String FILE_NAME = "RockMap-Tour-Audit.txt";
    private static final long MAX_INTERNAL_BYTES = 640L * 1024L;
    private static final int KEEP_INTERNAL_BYTES = 480 * 1024;
    private static final long SAMPLE_MS = 400L;
    private static final long INVARIANT_GRACE_MS = 1200L;
    private static final long PUBLIC_MIRROR_INTERVAL_MS = 2000L;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean MIRROR_SCHEDULED = new AtomicBoolean();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rockmap-tour-audit");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object FILE_LOCK = new Object();

    private static Context app;
    private static File internalLog;
    private static SharedPreferences tourPrefs;
    private static SharedPreferences researchPrefs;
    private static SharedPreferences fieldTourPrefs;
    private static SharedPreferences fieldRuntimePrefs;
    private static SharedPreferences auditPrefs;
    private static SharedPreferences.OnSharedPreferenceChangeListener tourListener;
    private static SharedPreferences.OnSharedPreferenceChangeListener researchListener;
    private static SharedPreferences.OnSharedPreferenceChangeListener fieldTourListener;
    private static SharedPreferences.OnSharedPreferenceChangeListener fieldRuntimeListener;
    private static WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private static String lastUiSignature = "";
    private static int lastObservedStep = -1;
    private static long observedStepSinceMs;
    private static String activeInvariantFailure = "";
    private static int consecutiveInvariantFailures;
    private static long lastPublicMirrorElapsed;

    private static final Runnable SAMPLER = new Runnable() {
        @Override public void run() {
            Activity activity = resumedActivity.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            sample(activity, "periodic");
            MAIN.postDelayed(this, SAMPLE_MS);
        }
    };

    private TourAudit() {}

    public static void install(Context context) {
        if (context == null || !INSTALLED.compareAndSet(false, true)) return;
        app = context.getApplicationContext();
        internalLog = new File(app.getFilesDir(), "rockmap-tour-audit.log");
        auditPrefs = app.getSharedPreferences(AUDIT_PREFS, Context.MODE_PRIVATE);
        tourPrefs = app.getSharedPreferences(TOUR_PREFS, Context.MODE_PRIVATE);
        researchPrefs = app.getSharedPreferences(RESEARCH_PREFS, Context.MODE_PRIVATE);
        fieldTourPrefs = app.getSharedPreferences(FIELD_TOUR_PREFS, Context.MODE_PRIVATE);
        fieldRuntimePrefs = app.getSharedPreferences(FIELD_RUNTIME_PREFS, Context.MODE_PRIVATE);

        tourListener = (prefs, key) -> {
            if (!isTourKey(key)) return;
            record("TOUR_PREF", key + "=" + prefValue(prefs, key) + " caller=" + appStack());
            if ("tour_step".equals(key)) {
                lastObservedStep = prefs.getInt("tour_step", 1);
                observedStepSinceMs = SystemClock.elapsedRealtime();
                consecutiveInvariantFailures = 0;
                activeInvariantFailure = "";
            }
            requestImmediateSample();
        };
        researchListener = (prefs, key) -> {
            if (!isResearchKey(key)) return;
            record("RESEARCH_PREF", key + "=" + prefValue(prefs, key) + " caller=" + appStack());
            requestImmediateSample();
        };
        fieldTourListener = (prefs, key) -> {
            record("FIELD_TOUR_PREF", key + "=" + prefValue(prefs, key) + " caller=" + appStack());
            requestImmediateSample();
        };
        fieldRuntimeListener = (prefs, key) -> {
            record("FIELD_RUNTIME_PREF", key + "=" + prefValue(prefs, key) + " caller=" + appStack());
            requestImmediateSample();
        };
        tourPrefs.registerOnSharedPreferenceChangeListener(tourListener);
        researchPrefs.registerOnSharedPreferenceChangeListener(researchListener);
        fieldTourPrefs.registerOnSharedPreferenceChangeListener(fieldTourListener);
        fieldRuntimePrefs.registerOnSharedPreferenceChangeListener(fieldRuntimeListener);

        Context applicationContext = app;
        if (applicationContext instanceof Application) {
            ((Application) applicationContext).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity activity, Bundle state) {
                    record("LIFECYCLE", activityName(activity) + " created savedState=" + (state != null));
                }
                @Override public void onActivityStarted(Activity activity) {
                    record("LIFECYCLE", activityName(activity) + " started");
                }
                @Override public void onActivityResumed(Activity activity) {
                    resumedActivity = new WeakReference<>(activity);
                    record("LIFECYCLE", activityName(activity) + " resumed focus=" + activity.hasWindowFocus());
                    MAIN.removeCallbacks(SAMPLER);
                    sample(activity, "resume");
                    MAIN.postDelayed(SAMPLER, SAMPLE_MS);
                }
                @Override public void onActivityPaused(Activity activity) {
                    sample(activity, "pause");
                    recordImportant("LIFECYCLE", activityName(activity) + " paused focus=" + activity.hasWindowFocus());
                    Activity current = resumedActivity.get();
                    if (current == activity) {
                        resumedActivity = new WeakReference<>(null);
                        MAIN.removeCallbacks(SAMPLER);
                    }
                }
                @Override public void onActivityStopped(Activity activity) {
                    recordImportant("LIFECYCLE", activityName(activity) + " stopped");
                }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
                    record("LIFECYCLE", activityName(activity) + " saveInstanceState");
                }
                @Override public void onActivityDestroyed(Activity activity) {
                    recordImportant("LIFECYCLE", activityName(activity) + " destroyed finishing=" + activity.isFinishing());
                }
            });
        }

        lastObservedStep = tourPrefs.getInt("tour_step", 1);
        observedStepSinceMs = SystemClock.elapsedRealtime();
        recordImportant("PROCESS_START",
                "baseHead=" + BASE_HEAD
                        + " version=" + safeVersion()
                        + " sdk=" + Build.VERSION.SDK_INT
                        + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
                        + " pid=" + android.os.Process.myPid());
    }

    private static void requestImmediateSample() {
        Activity activity = resumedActivity.get();
        if (activity == null) return;
        MAIN.post(() -> {
            Activity current = resumedActivity.get();
            if (current != null && !current.isFinishing() && !current.isDestroyed()) sample(current, "state-change");
        });
    }

    private static void sample(Activity activity, String reason) {
        if (activity == null || tourPrefs == null) return;
        TourState tour = TourState.read();
        FieldTourStateSnapshot fieldTour = FieldTourStateSnapshot.read();
        boolean mainTourActive = "in_progress".equals(tour.state);
        if (!mainTourActive && !fieldTour.active) {
            if (!lastUiSignature.isEmpty()) {
                record("UI_STATE", "no active tour activity=" + activityName(activity));
                lastUiSignature = "";
            }
            activeInvariantFailure = "";
            consecutiveInvariantFailures = 0;
            return;
        }

        if (mainTourActive && tour.step != lastObservedStep) {
            lastObservedStep = tour.step;
            observedStepSinceMs = SystemClock.elapsedRealtime();
            consecutiveInvariantFailures = 0;
            activeInvariantFailure = "";
            record("TOUR_STEP_OBSERVED",
                    "step=" + tour.step + "(" + stepName(tour.step) + ") display=" + tour.displayStep
                            + " topic=" + tour.topic + " reason=" + reason);
        }

        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        ProbeSet probes = ProbeSet.scan(root);
        ResearchState research = ResearchState.read();
        String signature = "activity=" + activityName(activity)
                + " focus=" + activity.hasWindowFocus()
                + " tour=" + tour.compact()
                + " field=" + fieldTour.compact()
                + " research=" + research.compact()
                + " probes=" + probes.signature();
        if (!signature.equals(lastUiSignature)) {
            record("UI_STATE", signature);
            lastUiSignature = signature;
        }

        if (mainTourActive) {
            String violation = invariantViolation(tour, research, probes, activity);
            if (violation == null && probes.coachDisplayStep > 0
                    && probes.coachDisplayStep != tour.displayStep) {
                violation = "coach displays step " + probes.coachDisplayStep
                        + " while persisted display step is " + tour.displayStep;
            }
            handleInvariant(violation, tour, research, probes, activity);
        }
    }

    private static String invariantViolation(TourState tour, ResearchState research,
                                             ProbeSet p, Activity activity) {
        Probe required = null;
        String requirement = null;
        if (tour.step != 9 && tour.step != 10 && !"MainActivity".equals(activityName(activity))) {
            return "step requires MainActivity but current Activity is " + activityName(activity);
        }
        switch (tour.step) {
            case 1: required = p.centerGps; requirement = "Center GPS main control"; break;
            case 2: required = p.saveGps; requirement = "Save GPS main control"; break;
            case 3: required = p.savedLocations; requirement = "Saved Locations main control"; break;
            case 4: required = p.trips; requirement = "Trips main control"; break;
            case 5: required = p.offlineData; requirement = "Offline Data main control"; break;
            case 6: required = p.layers; requirement = "Layers main control"; break;
            case 7: required = p.find; requirement = "Find main control"; break;
            case 8: required = p.researchMain; requirement = "Research main control"; break;
            case 9:
            case 10:
                return null; // Cross-Activity analysis/result steps intentionally have no stable target.
            case 11:
                if (!p.researchCollapse.ready()) return "Research workspace is not expanded for Mineral Evidence";
                required = p.mineralTab; requirement = "Mineral Evidence Research tab"; break;
            case 12:
                if (!p.researchCollapse.ready()) return "Research workspace is not expanded for Choose Mineral";
                if (!p.showEvidence.ready() && !p.mineralChoice.ready()) {
                    return "Choose Mineral has neither a selectable mineral row nor Show Evidence on Map";
                }
                return null;
            case 13:
                if (research.mineralLabel.isEmpty()) return "Layers Reveal has no persisted selected mineral label";
                required = p.layers; requirement = "Layers main control"; break;
            case 14:
                if (!p.researchCollapse.ready()) return "Research workspace is not expanded for Historic Mines";
                required = p.historicTab; requirement = "Historic Mines Research tab"; break;
            case 15: required = p.researchCollapse; requirement = "Research collapse control"; break;
            case 16: required = p.researchExpand; requirement = "collapsed Research expand control"; break;
            case 17:
                if (p.mappedCollapsed.ready()) return "Mapped Research controls are collapsed during Context Controls";
                if (!p.mappedDrag.ready() && !p.mappedCollapse.ready()) {
                    return "Mapped Research controls are not visible for Context Controls";
                }
                return null;
            case 18: required = p.mappedCollapse; requirement = "mapped Research collapse control"; break;
            case 19: required = p.mappedCollapsed; requirement = "collapsed mapped Research control"; break;
            case 20: required = p.helpTours; requirement = "Help & Tours control"; break;
            default: return "unknown guided-tour step " + tour.step;
        }
        if (required == null || !required.ready()) {
            return requirement + " is not ready: " + (required == null ? "missing probe" : required.detail);
        }
        return null;
    }

    private static void handleInvariant(String violation, TourState tour, ResearchState research,
                                        ProbeSet probes, Activity activity) {
        if (violation == null || violation.isEmpty()) {
            if (!activeInvariantFailure.isEmpty()) {
                recordImportant("INVARIANT_RECOVERED",
                        "previous=" + activeInvariantFailure + " step=" + tour.step + " activity=" + activityName(activity));
            }
            activeInvariantFailure = "";
            consecutiveInvariantFailures = 0;
            return;
        }
        if (SystemClock.elapsedRealtime() - observedStepSinceMs < INVARIANT_GRACE_MS) return;
        consecutiveInvariantFailures++;
        if (violation.equals(activeInvariantFailure) || consecutiveInvariantFailures < 2) return;
        activeInvariantFailure = violation;
        recordImportant("INVARIANT_FAIL",
                "step=" + tour.step + "(" + stepName(tour.step) + ") display=" + tour.displayStep
                        + " topic=" + tour.topic
                        + " activity=" + activityName(activity)
                        + " reason=" + violation
                        + " research=" + research.compact()
                        + " probes=" + probes.full());
    }

    private static boolean isTourKey(String key) {
        return "tour_state".equals(key) || "tour_step".equals(key)
                || "tour_start_step".equals(key) || "tour_end_step".equals(key)
                || "tour_topic".equals(key) || "tour_version".equals(key);
    }

    private static boolean isResearchKey(String key) {
        return "active".equals(key) || "view".equals(key) || "panel".equals(key)
                || "area_id".equals(key) || "geology_visible".equals(key)
                || "mineral_visible".equals(key) || "mines_visible".equals(key)
                || "mineral_key".equals(key) || "mineral_label".equals(key)
                || "geology_title".equals(key) || "geology_count".equals(key);
    }

    private static String prefString(SharedPreferences prefs, String key, String fallback) {
        if (prefs == null) return fallback;
        String value = prefs.getString(key, fallback);
        return value == null ? fallback : value;
    }

    private static String prefValue(SharedPreferences prefs, String key) {
        if (prefs == null || key == null) return "null";
        Map<String, ?> all = prefs.getAll();
        Object value = all.get(key);
        return value == null ? "<removed>" : clean(String.valueOf(value), 180);
    }

    private static String appStack() {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String cls = frame.getClassName();
            if (!cls.startsWith("com.rockmap.app.")) continue;
            if (cls.equals(TourAudit.class.getName()) || cls.equals(TourAuditProvider.class.getName())) continue;
            if (out.length() > 0) out.append(" <- ");
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            out.append(simple).append('#').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            if (out.length() > 260) break;
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }

    private static String safeVersion() {
        try { return BuildConfig.VERSION_NAME; }
        catch (Throwable ignored) { return "unknown"; }
    }

    private static String activityName(Activity activity) {
        return activity == null ? "null" : activity.getClass().getSimpleName();
    }

    private static String stepName(int step) {
        switch (step) {
            case 1: return "CENTER_GPS";
            case 2: return "SAVE_GPS";
            case 3: return "SAVED_LOCATIONS";
            case 4: return "TRIPS";
            case 5: return "OFFLINE_DATA";
            case 6: return "LAYERS_BASICS";
            case 7: return "FIND_MOUNT_ANTERO";
            case 8: return "OPEN_RESEARCH";
            case 9: return "COMBINED_ANALYSIS";
            case 10: return "SHOW_GEOLOGY";
            case 11: return "MINERAL_EVIDENCE";
            case 12: return "CHOOSE_MINERAL";
            case 13: return "LAYERS_REVEAL";
            case 14: return "HISTORIC_MINES";
            case 15: return "WORKSPACE_COLLAPSE";
            case 16: return "WORKSPACE_REOPEN";
            case 17: return "CONTEXT_CONTROLS";
            case 18: return "CONTEXT_COLLAPSE";
            case 19: return "CONTEXT_REOPEN";
            case 20: return "COMPLETE";
            default: return "STEP_" + step;
        }
    }

    private static void record(String type, String detail) { enqueue(type, detail, false); }
    private static void recordImportant(String type, String detail) { enqueue(type, detail, true); }

    private static void enqueue(String type, String detail, boolean forceMirror) {
        if (app == null || internalLog == null) return;
        long seq = SEQUENCE.incrementAndGet();
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(wall));
        String line = stamp + " | +" + elapsed + "ms | #" + seq + " | "
                + clean(type, 60) + " | " + clean(detail, 5000) + "\n";
        IO.execute(() -> appendLine(line, forceMirror));
    }

    private static void appendLine(String line, boolean forceMirror) {
        synchronized (FILE_LOCK) {
            try {
                trimIfNeeded();
                try (FileOutputStream output = new FileOutputStream(internalLog, true)) {
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
            } catch (IOException ignored) {
                return;
            }
        }
        requestPublicMirror(forceMirror);
    }


    private static void requestPublicMirror(boolean immediate) {
        long now = SystemClock.elapsedRealtime();
        if (immediate) {
            mirrorToDownloads();
            lastPublicMirrorElapsed = SystemClock.elapsedRealtime();
            return;
        }
        long remaining = Math.max(0L, PUBLIC_MIRROR_INTERVAL_MS - (now - lastPublicMirrorElapsed));
        if (!MIRROR_SCHEDULED.compareAndSet(false, true)) return;
        IO.schedule(() -> {
            MIRROR_SCHEDULED.set(false);
            mirrorToDownloads();
            lastPublicMirrorElapsed = SystemClock.elapsedRealtime();
        }, remaining, TimeUnit.MILLISECONDS);
    }

    private static void trimIfNeeded() throws IOException {
        if (internalLog == null || !internalLog.isFile() || internalLog.length() <= MAX_INTERNAL_BYTES) return;
        byte[] all = readFile(internalLog);
        int start = Math.max(0, all.length - KEEP_INTERNAL_BYTES);
        while (start < all.length && all[start] != '\n') start++;
        if (start < all.length) start++;
        try (FileOutputStream output = new FileOutputStream(internalLog, false)) {
            output.write("[older Tour Audit entries trimmed]\n".getBytes(StandardCharsets.UTF_8));
            output.write(all, Math.min(start, all.length), all.length - Math.min(start, all.length));
        }
    }

    private static byte[] readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static void mirrorToDownloads() {
        if (app == null || internalLog == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        try {
            byte[] bytes;
            synchronized (FILE_LOCK) {
                if (!internalLog.isFile()) return;
                bytes = readFile(internalLog);
            }
            ContentResolver resolver = app.getContentResolver();
            Uri uri = storedAuditUri();
            if (uri != null && !rewrite(resolver, uri, bytes)) {
                auditPrefs.edit().remove(AUDIT_URI).apply();
                uri = null;
            }
            if (uri == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RockMap");
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    auditPrefs.edit().putString(AUDIT_URI, uri.toString()).apply();
                    rewrite(resolver, uri, bytes);
                }
            }
        } catch (Throwable ignored) {
            // Diagnostics must never interfere with RockMap if public-file mirroring fails.
        }
    }

    private static Uri storedAuditUri() {
        if (auditPrefs == null) return null;
        String raw = auditPrefs.getString(AUDIT_URI, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return Uri.parse(raw); }
        catch (Throwable ignored) { return null; }
    }

    private static boolean rewrite(ContentResolver resolver, Uri uri, byte[] bytes) {
        try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
            if (output == null) return false;
            output.write(bytes);
            output.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max) + "…";
    }

    private static final class TourState {
        final String state;
        final String topic;
        final int step;
        final int start;
        final int end;
        final int displayStep;
        final int displayTotal;

        TourState(String state, String topic, int step, int start, int end) {
            this.state = state;
            this.topic = topic;
            this.step = step;
            this.start = start;
            this.end = end;
            this.displayStep = step == 20 ? Math.max(1, end - start + 2) : Math.max(1, step - start + 1);
            this.displayTotal = Math.max(2, end - start + 2);
        }

        static TourState read() {
            String state = prefString(tourPrefs, "tour_state", "not_offered");
            String topic = prefString(tourPrefs, "tour_topic", "full");
            int step = Math.max(1, tourPrefs.getInt("tour_step", 1));
            int start = Math.max(1, tourPrefs.getInt("tour_start_step", 1));
            int end = Math.max(start, tourPrefs.getInt("tour_end_step", 19));
            return new TourState(state, topic, step, start, end);
        }

        String compact() {
            return state + "/" + topic + "/" + step + "(" + stepName(step) + ") display="
                    + displayStep + "/" + displayTotal;
        }
    }

    private static final class ResearchState {
        final boolean active;
        final String view;
        final String panel;
        final boolean geologyVisible;
        final boolean mineralVisible;
        final boolean minesVisible;
        final String mineralKey;
        final String mineralLabel;
        final int geologyCount;

        ResearchState(boolean active, String view, String panel, boolean geologyVisible,
                      boolean mineralVisible, boolean minesVisible, String mineralKey,
                      String mineralLabel, int geologyCount) {
            this.active = active;
            this.view = view;
            this.panel = panel;
            this.geologyVisible = geologyVisible;
            this.mineralVisible = mineralVisible;
            this.minesVisible = minesVisible;
            this.mineralKey = mineralKey;
            this.mineralLabel = mineralLabel;
            this.geologyCount = geologyCount;
        }

        static ResearchState read() {
            return new ResearchState(
                    researchPrefs.getBoolean("active", false),
                    prefString(researchPrefs, "view", ""),
                    prefString(researchPrefs, "panel", ""),
                    researchPrefs.getBoolean("geology_visible", false),
                    researchPrefs.getBoolean("mineral_visible", false),
                    researchPrefs.getBoolean("mines_visible", false),
                    prefString(researchPrefs, "mineral_key", ""),
                    prefString(researchPrefs, "mineral_label", ""),
                    researchPrefs.getInt("geology_count", 0));
        }

        String compact() {
            return "active=" + active + ",view=" + view + ",panel=" + panel
                    + ",geo=" + geologyVisible + ",min=" + mineralVisible + ",mines=" + minesVisible
                    + ",mineral=" + clean(mineralLabel, 60) + ",key=" + clean(mineralKey, 40)
                    + ",geologyCount=" + geologyCount;
        }
    }

    private static final class FieldTourStateSnapshot {
        final boolean active;
        final String tool;
        final int step;
        final long entityId;
        final long auxId;
        final boolean navigationPractice;

        FieldTourStateSnapshot(boolean active, String tool, int step, long entityId,
                               long auxId, boolean navigationPractice) {
            this.active = active;
            this.tool = tool;
            this.step = step;
            this.entityId = entityId;
            this.auxId = auxId;
            this.navigationPractice = navigationPractice;
        }

        static FieldTourStateSnapshot read() {
            return new FieldTourStateSnapshot(
                    fieldTourPrefs.getBoolean("active", false),
                    prefString(fieldTourPrefs, "tool", ""),
                    fieldTourPrefs.getInt("step", 0),
                    fieldTourPrefs.getLong("entity_id", -1L),
                    fieldTourPrefs.getLong("aux_id", -1L),
                    fieldRuntimePrefs.getBoolean("nav_practice", false));
        }

        String compact() {
            return "active=" + active + ",tool=" + clean(tool, 40) + ",step=" + step
                    + ",entity=" + entityId + ",aux=" + auxId + ",navPractice=" + navigationPractice;
        }
    }

    private static final class Probe {
        final String key;
        View view;
        String detail = "missing";
        boolean ready;

        Probe(String key) { this.key = key; }

        void consider(View candidate) {
            if (candidate == null) return;
            boolean candidateReady = TourAudit.ready(candidate);
            if (view == null || (candidateReady && !ready)) {
                view = candidate;
                ready = candidateReady;
                detail = describe(candidate);
            }
        }

        boolean ready() { return view != null && ready; }

        String compact() {
            if (view == null) return key + "=missing";
            return key + "=" + (ready ? "READY" : "NOT_READY")
                    + "[obj=" + Integer.toHexString(System.identityHashCode(view))
                    + ",vis=" + visibilityName(view.getVisibility())
                    + ",shown=" + view.isShown()
                    + ",enabled=" + view.isEnabled()
                    + ",alpha=" + String.format(Locale.US, "%.2f", effectiveAlpha(view)) + "]";
        }
    }

    private static final class ProbeSet {
        final Probe centerGps = new Probe("centerGps");
        final Probe saveGps = new Probe("saveGps");
        final Probe savedLocations = new Probe("savedLocations");
        final Probe trips = new Probe("trips");
        final Probe offlineData = new Probe("offlineData");
        final Probe layers = new Probe("layers");
        final Probe find = new Probe("find");
        final Probe researchMain = new Probe("researchMain");
        final Probe helpTours = new Probe("helpTours");
        final Probe coach = new Probe("coach");
        final Probe researchCollapse = new Probe("researchCollapse");
        final Probe researchExpand = new Probe("researchExpand");
        final Probe mineralTab = new Probe("mineralTab");
        final Probe historicTab = new Probe("historicTab");
        final Probe mineralChoice = new Probe("mineralChoice");
        final Probe showEvidence = new Probe("showEvidence");
        final Probe mappedDrag = new Probe("mappedDrag");
        final Probe mappedCollapse = new Probe("mappedCollapse");
        final Probe mappedCollapsed = new Probe("mappedCollapsed");
        int coachDisplayStep;
        int visited;

        static ProbeSet scan(View root) {
            ProbeSet set = new ProbeSet();
            if (root != null) set.walk(root, 0);
            return set;
        }

        private void walk(View view, int depth) {
            if (view == null || depth > 40 || visited++ > 700) return;
            Object tag = view.getTag();
            String tagText = tag == null ? "" : String.valueOf(tag);
            CharSequence content = view.getContentDescription();
            String desc = content == null ? "" : content.toString();
            String text = view instanceof TextView && ((TextView) view).getText() != null
                    ? ((TextView) view).getText().toString() : "";

            if ("rockmap-main-gps".equals(tagText)) centerGps.consider(view);
            else if ("rockmap-main-save-gps".equals(tagText)) saveGps.consider(view);
            else if ("rockmap-main-markers".equals(tagText)) savedLocations.consider(view);
            else if ("rockmap-main-trips".equals(tagText)) trips.consider(view);
            else if ("rockmap-main-data".equals(tagText)) offlineData.consider(view);
            else if ("rockmap-main-layers".equals(tagText)) layers.consider(view);
            else if ("rockmap-main-find".equals(tagText)) find.consider(view);
            else if ("rockmap-main-minerals".equals(tagText)) researchMain.consider(view);
            else if ("rockmap-help-tours".equals(tagText)) helpTours.consider(view);
            else if ("rockmap-guided-tour-coach".equals(tagText)) coach.consider(view);

            if ("Collapse Research workspace".equals(desc)) researchCollapse.consider(view);
            if ("Expand Research workspace".equals(desc)) researchExpand.consider(view);
            if ("Collapse mapped research controls".equals(desc)) mappedCollapse.consider(view);
            if (desc.startsWith("Open mapped research controls")) mappedCollapsed.consider(view);
            if (desc.startsWith("Drag mapped research controls")) mappedDrag.consider(view);
            if ("Mineral Evidence".equals(text) && view instanceof android.widget.Button) mineralTab.consider(view);
            if ("Historic Mines".equals(text) && view instanceof android.widget.Button) historicTab.consider(view);
            if ("Show Evidence on Map".equals(text)) showEvidence.consider(view);
            if ((desc.startsWith("Select ") || desc.startsWith("Selected "))
                    && desc.contains("Mineral Evidence")) mineralChoice.consider(view);

            if (view.isShown()) {
                int parsed = parseCoachStep(text);
                if (parsed > 0) coachDisplayStep = parsed;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), depth + 1);
            }
        }

        String signature() {
            return centerGps.compact() + ',' + saveGps.compact() + ',' + savedLocations.compact()
                    + ',' + trips.compact() + ',' + offlineData.compact() + ',' + layers.compact()
                    + ',' + find.compact() + ',' + researchMain.compact() + ',' + helpTours.compact()
                    + ',' + coach.compact() + ",coachStep=" + coachDisplayStep
                    + ',' + researchCollapse.compact() + ',' + researchExpand.compact()
                    + ',' + mineralTab.compact() + ',' + historicTab.compact()
                    + ',' + mineralChoice.compact() + ',' + showEvidence.compact()
                    + ',' + mappedDrag.compact() + ',' + mappedCollapse.compact() + ',' + mappedCollapsed.compact();
        }

        String full() {
            return signature()
                    + " | researchCollapse{" + researchCollapse.detail + "}"
                    + " researchExpand{" + researchExpand.detail + "}"
                    + " mineralTab{" + mineralTab.detail + "}"
                    + " historicTab{" + historicTab.detail + "}"
                    + " mineralChoice{" + mineralChoice.detail + "}"
                    + " showEvidence{" + showEvidence.detail + "}"
                    + " layers{" + layers.detail + "}"
                    + " mappedCollapse{" + mappedCollapse.detail + "}"
                    + " mappedCollapsed{" + mappedCollapsed.detail + "}"
                    + " coach{" + coach.detail + "}";
        }
    }

    private static int parseCoachStep(String text) {
        if (text == null) return 0;
        String value = text.trim();
        String prefix = "GUIDED TOUR · ";
        if (value.startsWith(prefix)) {
            int of = value.indexOf(" OF ", prefix.length());
            if (of > prefix.length()) {
                try { return Integer.parseInt(value.substring(prefix.length(), of).trim()); }
                catch (NumberFormatException ignored) { return 0; }
            }
        }
        if (value.matches("\\d+/\\d+")) {
            try { return Integer.parseInt(value.substring(0, value.indexOf('/'))); }
            catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    private static boolean ready(View view) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown() || !view.isEnabled()) return false;
        if (effectiveAlpha(view) <= 0.10f || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        Rect rect = new Rect();
        return view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0;
    }

    private static float effectiveAlpha(View view) {
        float alpha = 1f;
        View current = view;
        int guard = 0;
        while (current != null && guard++ < 30) {
            alpha *= current.getAlpha();
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return alpha;
    }

    private static String describe(View view) {
        if (view == null) return "missing";
        Rect rect = new Rect();
        boolean global = view.getGlobalVisibleRect(rect);
        String text = view instanceof TextView && ((TextView) view).getText() != null
                ? clean(((TextView) view).getText().toString(), 90) : "";
        CharSequence rawDesc = view.getContentDescription();
        String desc = rawDesc == null ? "" : clean(rawDesc.toString(), 120);
        Object tag = view.getTag();
        return "class=" + view.getClass().getSimpleName()
                + ",text=" + text + ",desc=" + desc + ",tag=" + clean(String.valueOf(tag), 80)
                + ",vis=" + visibilityName(view.getVisibility()) + ",shown=" + view.isShown()
                + ",enabled=" + view.isEnabled() + ",alpha=" + String.format(Locale.US, "%.2f", effectiveAlpha(view))
                + ",attached=" + view.isAttachedToWindow() + ",size=" + view.getWidth() + "x" + view.getHeight()
                + ",global=" + global + ",rect=" + rect.left + "," + rect.top + "-" + rect.right + "," + rect.bottom;
    }

    private static String visibilityName(int visibility) {
        if (visibility == View.VISIBLE) return "VISIBLE";
        if (visibility == View.INVISIBLE) return "INVISIBLE";
        if (visibility == View.GONE) return "GONE";
        return String.valueOf(visibility);
    }
}
