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
import java.lang.reflect.Field;
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
 * Diagnostic-only universal guided-tour observer.
 *
 * Revision 3 deliberately observes the shared GuidedTourCoach boundary instead of maintaining a
 * separate hard-coded UI scanner for every tour. That gives the same diagnostics to main-map,
 * Research, FieldTourState-backed, dialog-hosted, and legacy coach-only tours. This class never
 * advances, repairs, reopens, clicks, hides, enables, or otherwise mutates RockMap tour UI.
 */
public final class TourAuditV2 {
    private static final String BASE_HEAD = "b6406a1905108a004a281cda08cfe6ab25ce9a9e";
    private static final String AUDIT_VERSION = "3-universal";
    private static final String TOUR_PREFS = "rockmap_guided_tour";
    private static final String RESEARCH_PREFS = "rockmap-research-session-v1";
    private static final String FIELD_TOUR_PREFS = "rockmap_field_tool_tour";
    private static final String FIELD_RUNTIME_PREFS = "rockmap_field_tour_runtime";
    private static final String AUDIT_PREFS = "rockmap_tour_audit_storage";
    private static final String AUDIT_URI = "downloads_uri";
    private static final String FILE_NAME = "RockMap-Tour-Audit.txt";
    private static final String COACH_TAG = "rockmap-guided-tour-coach";

    private static final long SAMPLE_MS = 200L;
    private static final long STATE_COACH_GRACE_MS = 1200L;
    private static final long COACH_MISSING_GRACE_MS = 2500L;
    private static final long REQUEST_STALL_MS = 1800L;
    private static final long TARGET_LOST_MS = 750L;
    private static final long FOCUS_LOST_MS = 800L;
    private static final long PUBLIC_MIRROR_INTERVAL_MS = 1500L;
    private static final long MAX_INTERNAL_BYTES = 1024L * 1024L;
    private static final int KEEP_INTERNAL_BYTES = 800 * 1024;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean MIRROR_SCHEDULED = new AtomicBoolean();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "rockmap-tour-audit-v3");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
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

    private static Field coachRootField;
    private static Field coachOwnerField;
    private static Field highlightedViewField;
    private static Field requestSequenceField;
    private static Field requestGenerationsField;
    private static boolean reflectionReady;
    private static boolean reflectionFailureReported;

    private static String lastMainStateKey = "";
    private static long mainStateSinceMs;
    private static String lastFieldStateKey = "";
    private static long fieldStateSinceMs;
    private static String lastSnapshot = "";
    private static String lastCoachSignature = "";
    private static boolean lastCoachPresent;
    private static String lastTargetSignature = "";
    private static long targetLostSinceMs;
    private static boolean targetLostReported;
    private static long lastRequestSequence = -1L;
    private static long requestObservedAtMs;
    private static String coachSignatureAtRequest = "";
    private static boolean requestResolved;
    private static boolean requestStallReported;
    private static String activeMismatch = "";
    private static long focusLostSinceMs;
    private static String focusLostStateKey = "";
    private static boolean focusLostReported;
    private static long lastPublicMirrorElapsed;

    private static final Runnable SAMPLER = new Runnable() {
        @Override public void run() {
            Activity activity = resumedActivity.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            sample(activity, "periodic");
            MAIN.postDelayed(this, SAMPLE_MS);
        }
    };

    private TourAuditV2() {}

    public static void install(Context context) {
        if (context == null || !INSTALLED.compareAndSet(false, true)) return;
        app = context.getApplicationContext();
        internalLog = new File(app.getFilesDir(), "rockmap-tour-audit.log");
        auditPrefs = app.getSharedPreferences(AUDIT_PREFS, Context.MODE_PRIVATE);
        tourPrefs = app.getSharedPreferences(TOUR_PREFS, Context.MODE_PRIVATE);
        researchPrefs = app.getSharedPreferences(RESEARCH_PREFS, Context.MODE_PRIVATE);
        fieldTourPrefs = app.getSharedPreferences(FIELD_TOUR_PREFS, Context.MODE_PRIVATE);
        fieldRuntimePrefs = app.getSharedPreferences(FIELD_RUNTIME_PREFS, Context.MODE_PRIVATE);
        initCoachReflection();

        tourListener = (prefs, key) -> {
            if (!isTourKey(key)) return;
            record("TOUR_PREF", key + "=" + prefValue(prefs, key) + " caller=" + appStack());
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

        if (app instanceof Application) {
            ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
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
                    recordImportant("LIFECYCLE", activityName(activity)
                            + " destroyed finishing=" + activity.isFinishing());
                }
            });
        }

        MainState main = MainState.read();
        FieldState field = FieldState.read();
        lastMainStateKey = main.key();
        lastFieldStateKey = field.key();
        long now = SystemClock.elapsedRealtime();
        mainStateSinceMs = now;
        fieldStateSinceMs = now;
        recordImportant("PROCESS_START",
                "auditVersion=" + AUDIT_VERSION
                        + " baseHead=" + BASE_HEAD
                        + " version=" + safeVersion()
                        + " sdk=" + Build.VERSION.SDK_INT
                        + " device=" + Build.MANUFACTURER + "/" + Build.MODEL
                        + " pid=" + android.os.Process.myPid()
                        + " coachReflection=" + reflectionReady);
    }

    private static void initCoachReflection() {
        try {
            coachRootField = GuidedTourCoach.class.getDeclaredField("activeCoachRoot");
            coachOwnerField = GuidedTourCoach.class.getDeclaredField("activeCoachOwner");
            highlightedViewField = GuidedTourCoach.class.getDeclaredField("highlightedView");
            requestSequenceField = GuidedTourCoach.class.getDeclaredField("requestSequence");
            requestGenerationsField = GuidedTourCoach.class.getDeclaredField("requestGenerations");
            coachRootField.setAccessible(true);
            coachOwnerField.setAccessible(true);
            highlightedViewField.setAccessible(true);
            requestSequenceField.setAccessible(true);
            requestGenerationsField.setAccessible(true);
            reflectionReady = true;
        } catch (Throwable ex) {
            reflectionReady = false;
            reflectionFailureReported = true;
            recordImportant("AUDIT_REFLECTION_ERROR",
                    clean(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 300));
        }
    }

    private static void requestImmediateSample() {
        Activity activity = resumedActivity.get();
        if (activity == null) return;
        MAIN.post(() -> {
            Activity current = resumedActivity.get();
            if (current != null && !current.isFinishing() && !current.isDestroyed()) {
                sample(current, "state-change");
            }
        });
    }

    private static void sample(Activity activity, String reason) {
        if (activity == null || tourPrefs == null || fieldTourPrefs == null) return;
        MainState main = MainState.read();
        FieldState field = FieldState.read();
        long now = SystemClock.elapsedRealtime();

        if (!main.key().equals(lastMainStateKey)) {
            record("MAIN_STATE", "from=" + lastMainStateKey + " to=" + main.key() + " source=" + reason);
            lastMainStateKey = main.key();
            mainStateSinceMs = now;
            activeMismatch = "";
        }
        if (!field.key().equals(lastFieldStateKey)) {
            record("FIELD_STATE", "from=" + lastFieldStateKey + " to=" + field.key() + " source=" + reason);
            lastFieldStateKey = field.key();
            fieldStateSinceMs = now;
            activeMismatch = "";
        }

        CoachRuntime coach = CoachRuntime.read(activity);
        observeRequest(activity, main, field, coach, now);
        observeCoach(activity, main, field, coach, now);
        observeTarget(activity, main, field, coach, now);
        observeStateCoachMismatch(activity, main, field, coach, now);
        observeFocus(activity, main, field, coach, now);

        String snapshot = "activity=" + activityName(activity)
                + ",focus=" + activity.hasWindowFocus()
                + ",main=" + main.compact()
                + ",field=" + field.compact()
                + ",coach=" + coach.compact();
        if (!snapshot.equals(lastSnapshot)) {
            record("STATE_SNAPSHOT", snapshot);
            lastSnapshot = snapshot;
        }
    }

    private static void observeRequest(Activity activity, MainState main, FieldState field,
                                       CoachRuntime coach, long now) {
        if (lastRequestSequence < 0L) {
            lastRequestSequence = coach.requestSequence;
            return;
        }
        if (coach.requestSequence != lastRequestSequence) {
            lastRequestSequence = coach.requestSequence;
            requestObservedAtMs = now;
            coachSignatureAtRequest = lastCoachSignature;
            requestResolved = coach.present && !coach.signature().equals(coachSignatureAtRequest);
            requestStallReported = false;
            record("COACH_REQUEST_OBSERVED",
                    "sequence=" + coach.requestSequence
                            + " activityToken=" + coach.activityRequestToken
                            + " activity=" + activityName(activity)
                            + " main=" + main.compact()
                            + " field=" + field.compact()
                            + " previousCoach=" + clean(coachSignatureAtRequest, 500));
        }
        if (!requestResolved && coach.present && !coach.signature().equals(coachSignatureAtRequest)) {
            requestResolved = true;
            record("COACH_REQUEST_RESOLVED",
                    "sequence=" + coach.requestSequence
                            + " delayMs=" + Math.max(0L, now - requestObservedAtMs)
                            + " coach=" + coach.compact());
        }
        if (!requestResolved && !requestStallReported && requestObservedAtMs > 0L
                && now - requestObservedAtMs >= REQUEST_STALL_MS) {
            requestStallReported = true;
            recordImportant("COACH_REQUEST_STALLED",
                    "sequence=" + coach.requestSequence
                            + " ageMs=" + (now - requestObservedAtMs)
                            + " activityToken=" + coach.activityRequestToken
                            + " activity=" + activityName(activity)
                            + " main=" + main.compact()
                            + " field=" + field.compact()
                            + " visibleCoach=" + coach.compact());
        }
    }

    private static void observeCoach(Activity activity, MainState main, FieldState field,
                                     CoachRuntime coach, long now) {
        String signature = coach.signature();
        if (coach.present && (!lastCoachPresent || !signature.equals(lastCoachSignature))) {
            recordImportant("COACH_DISPLAYED",
                    "activity=" + activityName(activity)
                            + " main=" + main.compact()
                            + " field=" + field.compact()
                            + " coach=" + coach.full());
            lastCoachSignature = signature;
            lastCoachPresent = true;
            if (requestObservedAtMs > 0L && !signature.equals(coachSignatureAtRequest)) {
                requestResolved = true;
            }
            return;
        }
        if (!coach.present && lastCoachPresent) {
            record("COACH_CLEARED",
                    "activity=" + activityName(activity)
                            + " main=" + main.compact()
                            + " field=" + field.compact()
                            + " previous=" + clean(lastCoachSignature, 800));
            lastCoachPresent = false;
            lastCoachSignature = "";
        }
    }

    private static void observeTarget(Activity activity, MainState main, FieldState field,
                                      CoachRuntime coach, long now) {
        String signature = coach.targetSignature();
        if (!signature.equals(lastTargetSignature)) {
            if (!lastTargetSignature.isEmpty() || !signature.isEmpty()) {
                record("TARGET_STATE",
                        "activity=" + activityName(activity)
                                + " main=" + main.compact()
                                + " field=" + field.compact()
                                + " target=" + coach.targetDetail());
            }
            lastTargetSignature = signature;
            targetLostSinceMs = 0L;
            targetLostReported = false;
        }
        if (!coach.present || coach.target == null || coach.targetReady) {
            if (targetLostReported) {
                recordImportant("TARGET_RECOVERED",
                        "activity=" + activityName(activity)
                                + " target=" + coach.targetDetail());
            }
            targetLostSinceMs = 0L;
            targetLostReported = false;
            return;
        }
        if (targetLostSinceMs == 0L) targetLostSinceMs = now;
        if (!targetLostReported && now - targetLostSinceMs >= TARGET_LOST_MS) {
            targetLostReported = true;
            recordImportant("TARGET_LOST",
                    "durationMs=" + (now - targetLostSinceMs)
                            + " activity=" + activityName(activity)
                            + " main=" + main.compact()
                            + " field=" + field.compact()
                            + " coach=" + coach.compact()
                            + " target=" + coach.targetDetail());
        }
    }

    private static void observeStateCoachMismatch(Activity activity, MainState main, FieldState field,
                                                   CoachRuntime coach, long now) {
        String violation = null;
        if (field.active) {
            long age = now - fieldStateSinceMs;
            if (!coach.present && age >= COACH_MISSING_GRACE_MS) {
                violation = "Field state expects step " + field.step + " but no coach is visible";
            } else if (coach.present && coach.step > 0 && coach.step != field.step
                    && age >= STATE_COACH_GRACE_MS) {
                violation = "Field state is step " + field.step + " but displayed coach is " + coach.step;
            }
        } else if (main.active) {
            long age = now - mainStateSinceMs;
            boolean transitionalResearchStep = main.step == 9 || main.step == 10;
            if (!coach.present && !transitionalResearchStep && age >= COACH_MISSING_GRACE_MS) {
                violation = "Main tour expects display step " + main.displayStep + " but no coach is visible";
            } else if (coach.present && coach.step > 0 && coach.step != main.displayStep
                    && age >= STATE_COACH_GRACE_MS) {
                violation = "Main persisted display step is " + main.displayStep
                        + " but displayed coach is " + coach.step;
            }
        }

        if (violation == null) {
            if (!activeMismatch.isEmpty()) {
                recordImportant("STATE_COACH_RECOVERED",
                        "previous=" + activeMismatch
                                + " activity=" + activityName(activity)
                                + " coach=" + coach.compact());
            }
            activeMismatch = "";
            return;
        }
        if (!violation.equals(activeMismatch)) {
            activeMismatch = violation;
            recordImportant("STATE_COACH_MISMATCH",
                    "reason=" + violation
                            + " activity=" + activityName(activity)
                            + " main=" + main.compact()
                            + " field=" + field.compact()
                            + " coach=" + coach.full());
        }
    }

    private static void observeFocus(Activity activity, MainState main, FieldState field,
                                     CoachRuntime coach, long now) {
        String stateKey = field.active ? "field:" + field.key()
                : main.active ? "main:" + main.key()
                : coach.present ? "coach:" + coach.step + "/" + coach.total
                : "idle";
        boolean dialogCoach = "dialog".equals(coach.hostKind);
        if (activity.hasWindowFocus() || dialogCoach) {
            if (focusLostReported && focusLostSinceMs > 0L) {
                recordImportant("FOCUS_RESTORED",
                        "state=" + focusLostStateKey
                                + " durationMs=" + (now - focusLostSinceMs)
                                + " activity=" + activityName(activity));
            }
            focusLostSinceMs = 0L;
            focusLostStateKey = "";
            focusLostReported = false;
            return;
        }
        if (focusLostSinceMs == 0L || !stateKey.equals(focusLostStateKey)) {
            focusLostSinceMs = now;
            focusLostStateKey = stateKey;
            focusLostReported = false;
            return;
        }
        if (!focusLostReported && now - focusLostSinceMs >= FOCUS_LOST_MS) {
            focusLostReported = true;
            recordImportant("FOCUS_INTERRUPTION",
                    "state=" + stateKey
                            + " durationMs=" + (now - focusLostSinceMs)
                            + " activity=" + activityName(activity)
                            + " coach=" + coach.compact());
        }
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
            if (cls.equals(TourAuditV2.class.getName())
                    || cls.equals(TourAudit.class.getName())
                    || cls.equals(TourAuditProvider.class.getName())) continue;
            if (out.length() > 0) out.append(" <- ");
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            out.append(simple).append('#').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (out.length() > 300) break;
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

    private static final class MainState {
        final boolean active;
        final String state;
        final String topic;
        final int step;
        final int start;
        final int end;
        final int displayStep;
        final int displayTotal;

        MainState(String state, String topic, int step, int start, int end) {
            this.state = state;
            this.active = "in_progress".equals(state);
            this.topic = topic;
            this.step = step;
            this.start = start;
            this.end = end;
            this.displayStep = step == 20 ? Math.max(1, end - start + 2)
                    : Math.max(1, step - start + 1);
            this.displayTotal = Math.max(2, end - start + 2);
        }

        static MainState read() {
            String state = prefString(tourPrefs, "tour_state", "not_offered");
            String topic = prefString(tourPrefs, "tour_topic", "full");
            int step = Math.max(1, tourPrefs.getInt("tour_step", 1));
            int start = Math.max(1, tourPrefs.getInt("tour_start_step", 1));
            int end = Math.max(start, tourPrefs.getInt("tour_end_step", 19));
            return new MainState(state, topic, step, start, end);
        }

        String key() {
            return state + "/" + topic + "/" + step + "/" + start + "/" + end;
        }

        String compact() {
            return active ? topic + ":step=" + step + ":display=" + displayStep + "/" + displayTotal
                    : state + ":step=" + step;
        }
    }

    private static final class FieldState {
        final boolean active;
        final String tool;
        final int step;
        final String phase;
        final long entityId;
        final long auxId;
        final boolean navPractice;

        FieldState(boolean active, String tool, int step, String phase,
                   long entityId, long auxId, boolean navPractice) {
            this.active = active;
            this.tool = tool;
            this.step = step;
            this.phase = phase;
            this.entityId = entityId;
            this.auxId = auxId;
            this.navPractice = navPractice;
        }

        static FieldState read() {
            return new FieldState(
                    fieldTourPrefs.getBoolean("active", false),
                    prefString(fieldTourPrefs, "tool", ""),
                    fieldTourPrefs.getInt("step", 0),
                    prefString(fieldTourPrefs, "text", ""),
                    fieldTourPrefs.getLong("entity_id", -1L),
                    fieldTourPrefs.getLong("aux_id", -1L),
                    fieldRuntimePrefs.getBoolean("nav_practice", false));
        }

        String key() {
            return active + "/" + tool + "/" + step + "/" + phase + "/" + entityId + "/" + auxId;
        }

        String compact() {
            return active ? clean(tool, 35) + ":step=" + step + ":phase=" + clean(phase, 35)
                    + ":entity=" + entityId + ":aux=" + auxId + ":navPractice=" + navPractice
                    : "inactive";
        }
    }

    private static final class CoachRuntime {
        final boolean present;
        final int step;
        final int total;
        final String hostKind;
        final String owner;
        final String cardText;
        final int coachIdentity;
        final View target;
        final boolean targetReady;
        final long requestSequence;
        final long activityRequestToken;
        final String detail;

        CoachRuntime(boolean present, int step, int total, String hostKind, String owner,
                     String cardText, int coachIdentity, View target, boolean targetReady,
                     long requestSequence, long activityRequestToken, String detail) {
            this.present = present;
            this.step = step;
            this.total = total;
            this.hostKind = hostKind;
            this.owner = owner;
            this.cardText = cardText;
            this.coachIdentity = coachIdentity;
            this.target = target;
            this.targetReady = targetReady;
            this.requestSequence = requestSequence;
            this.activityRequestToken = activityRequestToken;
            this.detail = detail;
        }

        static CoachRuntime read(Activity currentActivity) {
            if (!reflectionReady) {
                if (!reflectionFailureReported) {
                    reflectionFailureReported = true;
                    recordImportant("AUDIT_REFLECTION_ERROR", "GuidedTourCoach reflection is unavailable");
                }
                return new CoachRuntime(false, 0, 0, "reflection_error", "", "", 0,
                        null, false, -1L, -1L, "reflection unavailable");
            }
            try {
                ViewGroup root = dereferenceViewGroup(coachRootField.get(null));
                Activity owner = dereferenceActivity(coachOwnerField.get(null));
                View target = dereferenceView(highlightedViewField.get(null));
                long requestSequence = requestSequenceField.getLong(null);
                long activityToken = requestTokenForActivity(requestGenerationsField.get(null), currentActivity);
                View coach = root == null ? null : root.findViewWithTag(COACH_TAG);
                boolean present = coach != null && coach.isAttachedToWindow();
                int[] progress = present ? parseCoachProgress(coach) : new int[]{0, 0};
                String text = present ? summarizeCoachText(coach) : "";
                String host = root == null ? "none" : hostKind(root);
                String ownerName = activityName(owner);
                String detail = present ? describe(coach) : "coach not present in active root";
                int coachIdentity = coach == null ? 0 : System.identityHashCode(coach);
                return new CoachRuntime(present, progress[0], progress[1], host, ownerName,
                        text, coachIdentity, target, ready(target), requestSequence, activityToken, detail);
            } catch (Throwable ex) {
                return new CoachRuntime(false, 0, 0, "reflection_error", "", "", 0,
                        null, false, -1L, -1L,
                        clean(ex.getClass().getSimpleName() + ": " + ex.getMessage(), 300));
            }
        }

        String signature() {
            if (!present) return "missing";
            return step + "/" + total + "/" + hostKind + "/" + owner + "/"
                    + Integer.toHexString(coachIdentity) + "/"
                    + Integer.toHexString(cardText.hashCode()) + "/"
                    + (target == null ? "none" : Integer.toHexString(System.identityHashCode(target)));
        }

        String targetSignature() {
            if (target == null) return "";
            return Integer.toHexString(System.identityHashCode(target)) + "/" + targetReady + "/"
                    + target.getVisibility() + "/" + target.isShown() + "/"
                    + String.format(Locale.US, "%.2f", effectiveAlpha(target));
        }

        String targetDetail() {
            return target == null ? "none" : describe(target);
        }

        String compact() {
            return (present ? "VISIBLE" : "missing")
                    + "[step=" + step + "/" + total
                    + ",host=" + hostKind
                    + ",owner=" + owner
                    + ",requestSeq=" + requestSequence
                    + ",activityToken=" + activityRequestToken
                    + ",target=" + (target == null ? "none" : (targetReady ? "READY" : "NOT_READY")) + "]";
        }

        String full() {
            return compact() + " text={" + clean(cardText, 900) + "}"
                    + " card={" + detail + "}"
                    + " target={" + targetDetail() + "}";
        }
    }

    private static ViewGroup dereferenceViewGroup(Object ref) {
        if (!(ref instanceof WeakReference)) return null;
        Object value = ((WeakReference<?>) ref).get();
        return value instanceof ViewGroup ? (ViewGroup) value : null;
    }

    private static Activity dereferenceActivity(Object ref) {
        if (!(ref instanceof WeakReference)) return null;
        Object value = ((WeakReference<?>) ref).get();
        return value instanceof Activity ? (Activity) value : null;
    }

    private static View dereferenceView(Object ref) {
        if (!(ref instanceof WeakReference)) return null;
        Object value = ((WeakReference<?>) ref).get();
        return value instanceof View ? (View) value : null;
    }

    private static long requestTokenForActivity(Object rawMap, Activity activity) {
        if (!(rawMap instanceof Map) || activity == null) return -1L;
        try {
            Object value = ((Map<?, ?>) rawMap).get(activity);
            return value instanceof Number ? ((Number) value).longValue() : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String hostKind(ViewGroup root) {
        if (root == null) return "none";
        String name = root.getClass().getSimpleName();
        return name != null && name.contains("DialogCoachHost") ? "dialog" : "activity";
    }

    private static int[] parseCoachProgress(View root) {
        if (root == null) return new int[]{0, 0};
        if (root instanceof TextView) {
            CharSequence raw = ((TextView) root).getText();
            String text = raw == null ? "" : raw.toString().trim();
            String prefix = "GUIDED TOUR · ";
            if (text.startsWith(prefix)) {
                int of = text.indexOf(" OF ", prefix.length());
                if (of > prefix.length()) {
                    try {
                        int step = Integer.parseInt(text.substring(prefix.length(), of).trim());
                        int total = Integer.parseInt(text.substring(of + 4).trim());
                        return new int[]{step, total};
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (text.matches("\\d+/\\d+")) {
                int slash = text.indexOf('/');
                try {
                    return new int[]{Integer.parseInt(text.substring(0, slash)),
                            Integer.parseInt(text.substring(slash + 1))};
                } catch (NumberFormatException ignored) {}
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                int[] parsed = parseCoachProgress(group.getChildAt(i));
                if (parsed[0] > 0) return parsed;
            }
        }
        return new int[]{0, 0};
    }

    private static String summarizeCoachText(View root) {
        StringBuilder out = new StringBuilder();
        appendCoachText(root, out, 0);
        return clean(out.toString(), 1200);
    }

    private static void appendCoachText(View view, StringBuilder out, int depth) {
        if (view == null || out.length() >= 1200 || depth > 30) return;
        if (view instanceof TextView) {
            CharSequence raw = ((TextView) view).getText();
            String text = raw == null ? "" : clean(raw.toString(), 240);
            if (!text.isEmpty()) {
                if (out.length() > 0) out.append(" | ");
                out.append(text);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && out.length() < 1200; i++) {
                appendCoachText(group.getChildAt(i), out, depth + 1);
            }
        }
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
        while (current != null && guard++ < 40) {
            alpha *= current.getAlpha();
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
        return alpha;
    }

    private static String describe(View view) {
        if (view == null) return "none";
        Rect rect = new Rect();
        boolean global = view.getGlobalVisibleRect(rect);
        String text = view instanceof TextView && ((TextView) view).getText() != null
                ? clean(((TextView) view).getText().toString(), 140) : "";
        CharSequence rawDesc = view.getContentDescription();
        String desc = rawDesc == null ? "" : clean(rawDesc.toString(), 180);
        Object tag = view.getTag();
        return "class=" + view.getClass().getSimpleName()
                + ",obj=" + Integer.toHexString(System.identityHashCode(view))
                + ",text=" + text
                + ",desc=" + desc
                + ",tag=" + clean(String.valueOf(tag), 120)
                + ",vis=" + visibilityName(view.getVisibility())
                + ",shown=" + view.isShown()
                + ",enabled=" + view.isEnabled()
                + ",alpha=" + String.format(Locale.US, "%.2f", effectiveAlpha(view))
                + ",attached=" + view.isAttachedToWindow()
                + ",size=" + view.getWidth() + "x" + view.getHeight()
                + ",global=" + global
                + ",rect=" + rect.left + "," + rect.top + "-" + rect.right + "," + rect.bottom;
    }

    private static String visibilityName(int visibility) {
        if (visibility == View.VISIBLE) return "VISIBLE";
        if (visibility == View.INVISIBLE) return "INVISIBLE";
        if (visibility == View.GONE) return "GONE";
        return String.valueOf(visibility);
    }

    private static void record(String type, String detail) {
        enqueue(type, detail, false);
    }

    private static void recordImportant(String type, String detail) {
        enqueue(type, detail, true);
    }

    private static void enqueue(String type, String detail, boolean forceMirror) {
        if (app == null || internalLog == null) return;
        long seq = SEQUENCE.incrementAndGet();
        long wall = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(wall));
        String line = stamp + " | +" + elapsed + "ms | #" + seq + " | "
                + clean(type, 80) + " | " + clean(detail, 9000) + "\n";
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
        long remaining = Math.max(0L,
                PUBLIC_MIRROR_INTERVAL_MS - (now - lastPublicMirrorElapsed));
        if (!MIRROR_SCHEDULED.compareAndSet(false, true)) return;
        IO.schedule(() -> {
            MIRROR_SCHEDULED.set(false);
            mirrorToDownloads();
            lastPublicMirrorElapsed = SystemClock.elapsedRealtime();
        }, remaining, TimeUnit.MILLISECONDS);
    }

    private static void trimIfNeeded() throws IOException {
        if (internalLog == null || !internalLog.isFile()
                || internalLog.length() <= MAX_INTERNAL_BYTES) return;
        byte[] all = readFile(internalLog);
        int start = Math.max(0, all.length - KEEP_INTERNAL_BYTES);
        while (start < all.length && all[start] != '\n') start++;
        if (start < all.length) start++;
        try (FileOutputStream output = new FileOutputStream(internalLog, false)) {
            output.write("[older Tour Audit entries trimmed]\n".getBytes(StandardCharsets.UTF_8));
            output.write(all, Math.min(start, all.length),
                    all.length - Math.min(start, all.length));
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
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/RockMap");
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
        String compact = value.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max) + "…";
    }
}
