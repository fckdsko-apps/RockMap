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
 * Diagnostic-only guided-tour audit, revision 2.
 *
 * This observer never advances or repairs a tour. It records persisted state, complete target
 * readiness, the actual GuidedTourCoach owner/root (including dialog PopupWindow hosts), Activity
 * lifecycle/focus, and per-step invariants for the main Research tour plus the map-owned Tracks,
 * Navigate, Measure, and Prospecting Areas tours.
 */
public final class TourAuditV2 {
    private static final String BASE_HEAD = "b071240e2dbbebcb581d3223ed97e713cb3f42d3";
    private static final String AUDIT_VERSION = "2";
    private static final String TOUR_PREFS = "rockmap_guided_tour";
    private static final String RESEARCH_PREFS = "rockmap-research-session-v1";
    private static final String FIELD_TOUR_PREFS = "rockmap_field_tool_tour";
    private static final String FIELD_RUNTIME_PREFS = "rockmap_field_tour_runtime";
    private static final String AUDIT_PREFS = "rockmap_tour_audit_storage";
    private static final String AUDIT_URI = "downloads_uri";
    private static final String FILE_NAME = "RockMap-Tour-Audit.txt";
    private static final String COACH_TAG = "rockmap-guided-tour-coach";

    private static final long MAX_INTERNAL_BYTES = 900L * 1024L;
    private static final int KEEP_INTERNAL_BYTES = 700 * 1024;
    private static final long SAMPLE_MS = 250L;
    private static final long MAIN_INVARIANT_GRACE_MS = 1200L;
    private static final long FIELD_TARGET_GRACE_MS = 1200L;
    private static final long COACH_MISMATCH_GRACE_MS = 1200L;
    private static final long COACH_MISSING_GRACE_MS = 2500L;
    private static final long FOCUS_LOSS_GRACE_MS = 800L;
    private static final long PUBLIC_MIRROR_INTERVAL_MS = 1500L;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean MIRROR_SCHEDULED = new AtomicBoolean();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "rockmap-tour-audit-v2");
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
    private static String lastUiSignature = "";

    private static int lastMainStep = -1;
    private static long mainStepSinceMs;
    private static String activeMainFailure = "";
    private static int consecutiveMainFailures;

    private static String lastFieldTool = "";
    private static int lastFieldStep = -1;
    private static String lastFieldPhase = "";
    private static long fieldStepSinceMs;
    private static String activeFieldFailure = "";
    private static int consecutiveFieldFailures;

    private static String lastTargetKey = "";
    private static int lastTargetIdentity;
    private static long focusLossSinceMs;
    private static String focusLossStateKey = "";
    private static boolean focusLossReported;
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

        tourListener = (prefs, key) -> {
            if (!isTourKey(key)) return;
            record("TOUR_PREF", key + "=" + prefValue(prefs, key) + " caller=" + appStack());
            if ("tour_step".equals(key)) {
                lastMainStep = prefs.getInt("tour_step", 1);
                mainStepSinceMs = SystemClock.elapsedRealtime();
                activeMainFailure = "";
                consecutiveMainFailures = 0;
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
            if ("step".equals(key) || "tool".equals(key) || "text".equals(key) || "active".equals(key)) {
                FieldTourStateSnapshot field = FieldTourStateSnapshot.read();
                observeFieldTransition(field, "pref:" + key);
            }
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

        TourState main = TourState.read();
        FieldTourStateSnapshot field = FieldTourStateSnapshot.read();
        lastMainStep = main.step;
        mainStepSinceMs = SystemClock.elapsedRealtime();
        lastFieldTool = field.tool;
        lastFieldStep = field.step;
        lastFieldPhase = field.phase;
        fieldStepSinceMs = SystemClock.elapsedRealtime();

        recordImportant("PROCESS_START",
                "auditVersion=" + AUDIT_VERSION
                        + " baseHead=" + BASE_HEAD
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
            if (current != null && !current.isFinishing() && !current.isDestroyed()) {
                sample(current, "state-change");
            }
        });
    }

    private static void observeFieldTransition(FieldTourStateSnapshot field, String source) {
        if (field == null) return;
        if (!field.tool.equals(lastFieldTool) || field.step != lastFieldStep
                || !field.phase.equals(lastFieldPhase)) {
            String before = clean(lastFieldTool, 30) + "/" + lastFieldStep + "/" + clean(lastFieldPhase, 30);
            String after = clean(field.tool, 30) + "/" + field.step + "/" + clean(field.phase, 30);
            if (!field.tool.equals(lastFieldTool) || field.step != lastFieldStep) {
                fieldStepSinceMs = SystemClock.elapsedRealtime();
                activeFieldFailure = "";
                consecutiveFieldFailures = 0;
                lastTargetKey = "";
                lastTargetIdentity = 0;
            }
            record("FIELD_STEP_OBSERVED", "from=" + before + " to=" + after + " source=" + source);
            lastFieldTool = field.tool;
            lastFieldStep = field.step;
            lastFieldPhase = field.phase;
        }
    }

    private static void sample(Activity activity, String reason) {
        if (activity == null || tourPrefs == null || fieldTourPrefs == null) return;

        TourState main = TourState.read();
        FieldTourStateSnapshot field = FieldTourStateSnapshot.read();
        boolean mainActive = "in_progress".equals(main.state);
        boolean fieldActive = field.active;

        if (!mainActive && !fieldActive) {
            if (!lastUiSignature.isEmpty()) {
                record("UI_STATE", "no active tour activity=" + activityName(activity));
                lastUiSignature = "";
            }
            resetTransientFailures();
            return;
        }

        if (mainActive && main.step != lastMainStep) {
            lastMainStep = main.step;
            mainStepSinceMs = SystemClock.elapsedRealtime();
            activeMainFailure = "";
            consecutiveMainFailures = 0;
            record("TOUR_STEP_OBSERVED",
                    "step=" + main.step + "(" + stepName(main.step) + ") display=" + main.displayStep
                            + " topic=" + main.topic + " reason=" + reason);
        }
        if (fieldActive) observeFieldTransition(field, "sample:" + reason);

        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        MainProbes probes = MainProbes.scan(decor);
        ResearchState research = ResearchState.read();
        CoachSnapshot coach = CoachSnapshot.read();
        FieldExpectation fieldExpectation = fieldActive
                ? FieldExpectation.forState(field, activity, decor) : FieldExpectation.none();

        String signature = "activity=" + activityName(activity)
                + " focus=" + activity.hasWindowFocus()
                + " tour=" + main.compact()
                + " field=" + field.compact()
                + " research=" + research.compact()
                + " coach=" + coach.compact()
                + " fieldExpected=" + fieldExpectation.compact()
                + " probes=" + probes.signature();
        if (!signature.equals(lastUiSignature)) {
            record("UI_STATE", signature);
            lastUiSignature = signature;
        }

        trackTargetReplacement(mainActive, main, fieldActive, field, probes, fieldExpectation);
        trackFocusInterruption(activity, mainActive, main, fieldActive, field, coach);

        if (mainActive) {
            String violation = mainInvariantViolation(main, research, probes, activity);
            String coachViolation = coachViolation("main", main.displayStep, coach, mainStepSinceMs);
            if (violation == null) violation = coachViolation;
            handleMainInvariant(violation, main, research, probes, coach, activity);
        } else {
            activeMainFailure = "";
            consecutiveMainFailures = 0;
        }

        if (fieldActive) {
            String violation = fieldInvariantViolation(field, fieldExpectation, coach, activity);
            handleFieldInvariant(violation, field, fieldExpectation, coach, activity);
        } else {
            activeFieldFailure = "";
            consecutiveFieldFailures = 0;
        }
    }

    private static void resetTransientFailures() {
        activeMainFailure = "";
        consecutiveMainFailures = 0;
        activeFieldFailure = "";
        consecutiveFieldFailures = 0;
        focusLossSinceMs = 0L;
        focusLossStateKey = "";
        focusLossReported = false;
        lastTargetKey = "";
        lastTargetIdentity = 0;
    }

    private static void trackFocusInterruption(Activity activity, boolean mainActive, TourState main,
                                               boolean fieldActive, FieldTourStateSnapshot field,
                                               CoachSnapshot coach) {
        long now = SystemClock.elapsedRealtime();
        String stateKey = fieldActive
                ? "field:" + field.tool + ":" + field.step + ":" + field.phase
                : "main:" + main.topic + ":" + main.step;

        boolean dialogCoach = "dialog".equals(coach.hostKind);
        if (activity.hasWindowFocus() || dialogCoach) {
            if (focusLossReported && focusLossSinceMs > 0L) {
                recordImportant("FOCUS_RESTORED",
                        "state=" + focusLossStateKey + " durationMs=" + (now - focusLossSinceMs)
                                + " activity=" + activityName(activity));
            }
            focusLossSinceMs = 0L;
            focusLossStateKey = "";
            focusLossReported = false;
            return;
        }

        if (focusLossSinceMs == 0L || !stateKey.equals(focusLossStateKey)) {
            focusLossSinceMs = now;
            focusLossStateKey = stateKey;
            focusLossReported = false;
            return;
        }
        if (!focusLossReported && now - focusLossSinceMs >= FOCUS_LOSS_GRACE_MS) {
            focusLossReported = true;
            recordImportant("FOCUS_INTERRUPTION",
                    "state=" + stateKey + " durationMs=" + (now - focusLossSinceMs)
                            + " activity=" + activityName(activity)
                            + " coach=" + coach.compact()
                            + " reason=Activity lost window focus while active tour had no dialog-hosted coach");
        }
    }

    private static void trackTargetReplacement(boolean mainActive, TourState main,
                                               boolean fieldActive, FieldTourStateSnapshot field,
                                               MainProbes probes, FieldExpectation expectation) {
        String key = "";
        View target = null;
        if (fieldActive && expectation.targetTag != null) {
            key = "field:" + field.tool + ":" + field.step + ":" + expectation.targetTag;
            target = expectation.target.view;
        } else if (mainActive) {
            Probe mainTarget = probes.targetForMainStep(main.step);
            if (mainTarget != null && mainTarget.view != null) {
                key = "main:" + main.step + ":" + mainTarget.key;
                target = mainTarget.view;
            }
        }
        if (key.isEmpty() || target == null) {
            if (!key.equals(lastTargetKey)) {
                lastTargetKey = key;
                lastTargetIdentity = 0;
            }
            return;
        }
        int identity = System.identityHashCode(target);
        if (key.equals(lastTargetKey) && lastTargetIdentity != 0 && identity != lastTargetIdentity) {
            record("TARGET_REPLACED",
                    "key=" + key + " oldObj=" + Integer.toHexString(lastTargetIdentity)
                            + " newObj=" + Integer.toHexString(identity)
                            + " new=" + describe(target));
        }
        lastTargetKey = key;
        lastTargetIdentity = identity;
    }

    private static String coachViolation(String domain, int expectedStep,
                                         CoachSnapshot coach, long stepSinceMs) {
        long age = SystemClock.elapsedRealtime() - stepSinceMs;
        if (!coach.present) {
            if (age >= COACH_MISSING_GRACE_MS) {
                return domain + " coach missing for " + age + "ms";
            }
            return null;
        }
        if (coach.step > 0 && coach.step != expectedStep && age >= COACH_MISMATCH_GRACE_MS) {
            return domain + " coach displays step " + coach.step
                    + " while persisted step is " + expectedStep
                    + " for " + age + "ms";
        }
        return null;
    }

    private static String mainInvariantViolation(TourState tour, ResearchState research,
                                                 MainProbes p, Activity activity) {
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
                return null;
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

    private static String fieldInvariantViolation(FieldTourStateSnapshot field,
                                                  FieldExpectation expectation,
                                                  CoachSnapshot coach,
                                                  Activity activity) {
        long age = SystemClock.elapsedRealtime() - fieldStepSinceMs;

        String coachProblem = coachViolation("field " + field.tool, field.step, coach, fieldStepSinceMs);
        if (coachProblem != null) return coachProblem;

        if (expectation.expectedDialog && age >= FIELD_TARGET_GRACE_MS) {
            if (!coach.present || !"dialog".equals(coach.hostKind)) {
                return "step expects dialog-hosted coach but coach host is " + coach.hostKind;
            }
        }

        if (expectation.targetTag != null && age >= FIELD_TARGET_GRACE_MS
                && !expectation.target.ready()) {
            return "expected target " + expectation.targetTag
                    + " is not ready: " + expectation.target.detail;
        }

        if (expectation.requiresMapPhase && age >= FIELD_TARGET_GRACE_MS
                && !"map".equals(field.phase)) {
            return "map-placement step is not armed; phase=" + clean(field.phase, 60);
        }

        return null;
    }

    private static void handleMainInvariant(String violation, TourState tour, ResearchState research,
                                            MainProbes probes, CoachSnapshot coach, Activity activity) {
        if (violation == null || violation.isEmpty()) {
            if (!activeMainFailure.isEmpty()) {
                recordImportant("INVARIANT_RECOVERED",
                        "domain=main previous=" + activeMainFailure
                                + " step=" + tour.step + " activity=" + activityName(activity));
            }
            activeMainFailure = "";
            consecutiveMainFailures = 0;
            return;
        }
        if (SystemClock.elapsedRealtime() - mainStepSinceMs < MAIN_INVARIANT_GRACE_MS) return;
        consecutiveMainFailures++;
        if (violation.equals(activeMainFailure) || consecutiveMainFailures < 2) return;
        activeMainFailure = violation;
        recordImportant("INVARIANT_FAIL",
                "domain=main step=" + tour.step + "(" + stepName(tour.step) + ")"
                        + " display=" + tour.displayStep + " topic=" + tour.topic
                        + " activity=" + activityName(activity)
                        + " reason=" + violation
                        + " research=" + research.compact()
                        + " coach=" + coach.full()
                        + " probes=" + probes.full());
    }

    private static void handleFieldInvariant(String violation, FieldTourStateSnapshot field,
                                             FieldExpectation expectation, CoachSnapshot coach,
                                             Activity activity) {
        if (violation == null || violation.isEmpty()) {
            if (!activeFieldFailure.isEmpty()) {
                recordImportant("FIELD_INVARIANT_RECOVERED",
                        "previous=" + activeFieldFailure
                                + " tool=" + field.tool + " step=" + field.step
                                + " phase=" + field.phase + " activity=" + activityName(activity));
            }
            activeFieldFailure = "";
            consecutiveFieldFailures = 0;
            return;
        }
        if (SystemClock.elapsedRealtime() - fieldStepSinceMs < FIELD_TARGET_GRACE_MS) return;
        consecutiveFieldFailures++;
        if (violation.equals(activeFieldFailure) || consecutiveFieldFailures < 2) return;
        activeFieldFailure = violation;
        recordImportant("FIELD_INVARIANT_FAIL",
                "tool=" + field.tool + " step=" + field.step + "/" + field.total()
                        + " phase=" + clean(field.phase, 80)
                        + " activity=" + activityName(activity)
                        + " reason=" + violation
                        + " expectation=" + expectation.full()
                        + " coach=" + coach.full());
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
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(wall));
        String line = stamp + " | +" + elapsed + "ms | #" + seq + " | "
                + clean(type, 60) + " | " + clean(detail, 7000) + "\n";
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
            output.write("[older Tour Audit entries trimmed]\n"
                    .getBytes(StandardCharsets.UTF_8));
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
        if (app == null || internalLog == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
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
            this.displayStep = step == 20
                    ? Math.max(1, end - start + 2) : Math.max(1, step - start + 1);
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
                    + ",geo=" + geologyVisible + ",min=" + mineralVisible
                    + ",mines=" + minesVisible + ",mineral=" + clean(mineralLabel, 60)
                    + ",key=" + clean(mineralKey, 40) + ",geologyCount=" + geologyCount;
        }
    }

    private static final class FieldTourStateSnapshot {
        final boolean active;
        final String tool;
        final int step;
        final String phase;
        final long entityId;
        final long auxId;
        final boolean navigationPractice;

        FieldTourStateSnapshot(boolean active, String tool, int step, String phase,
                               long entityId, long auxId, boolean navigationPractice) {
            this.active = active;
            this.tool = tool;
            this.step = step;
            this.phase = phase;
            this.entityId = entityId;
            this.auxId = auxId;
            this.navigationPractice = navigationPractice;
        }

        static FieldTourStateSnapshot read() {
            return new FieldTourStateSnapshot(
                    fieldTourPrefs.getBoolean("active", false),
                    prefString(fieldTourPrefs, "tool", ""),
                    fieldTourPrefs.getInt("step", 0),
                    prefString(fieldTourPrefs, "text", ""),
                    fieldTourPrefs.getLong("entity_id", -1L),
                    fieldTourPrefs.getLong("aux_id", -1L),
                    fieldRuntimePrefs.getBoolean("nav_practice", false));
        }

        int total() {
            if ("Tracks".equals(tool)) return 17;
            if ("Navigate".equals(tool)) return 9;
            if ("Measure".equals(tool)) return 17;
            if ("Field Records".equals(tool)) return 15;
            if ("Prospecting Areas".equals(tool)) return 19;
            if ("Import Files".equals(tool)) return 3;
            if ("Manage Imports".equals(tool)) return 8;
            if ("Export Data".equals(tool)) return 3;
            if ("Coordinates".equals(tool)) return 5;
            return 2;
        }

        String compact() {
            return "active=" + active + ",tool=" + clean(tool, 40) + ",step=" + step
                    + "/" + total() + ",phase=" + clean(phase, 40)
                    + ",entity=" + entityId + ",aux=" + auxId
                    + ",navPractice=" + navigationPractice;
        }
    }

    private static final class Probe {
        final String key;
        View view;
        String detail = "missing";

        Probe(String key) { this.key = key; }

        void set(View candidate) {
            view = candidate;
            detail = candidate == null ? "missing" : describe(candidate);
        }

        boolean ready() { return view != null && TourAuditV2.ready(view); }

        String compact() {
            if (view == null) return key + "=missing";
            return key + "=" + (ready() ? "READY" : "NOT_READY")
                    + "[obj=" + Integer.toHexString(System.identityHashCode(view))
                    + ",vis=" + visibilityName(view.getVisibility())
                    + ",shown=" + view.isShown()
                    + ",enabled=" + view.isEnabled()
                    + ",alpha=" + String.format(Locale.US, "%.2f", effectiveAlpha(view)) + "]";
        }
    }

    private static final class MainProbes {
        final Probe centerGps = new Probe("centerGps");
        final Probe saveGps = new Probe("saveGps");
        final Probe savedLocations = new Probe("savedLocations");
        final Probe trips = new Probe("trips");
        final Probe offlineData = new Probe("offlineData");
        final Probe layers = new Probe("layers");
        final Probe find = new Probe("find");
        final Probe researchMain = new Probe("researchMain");
        final Probe helpTours = new Probe("helpTours");
        final Probe researchCollapse = new Probe("researchCollapse");
        final Probe researchExpand = new Probe("researchExpand");
        final Probe mineralTab = new Probe("mineralTab");
        final Probe historicTab = new Probe("historicTab");
        final Probe mineralChoice = new Probe("mineralChoice");
        final Probe showEvidence = new Probe("showEvidence");
        final Probe mappedDrag = new Probe("mappedDrag");
        final Probe mappedCollapse = new Probe("mappedCollapse");
        final Probe mappedCollapsed = new Probe("mappedCollapsed");

        static MainProbes scan(View root) {
            MainProbes p = new MainProbes();
            p.centerGps.set(findByTag(root, "rockmap-main-gps"));
            p.saveGps.set(findByTag(root, "rockmap-main-save-gps"));
            p.savedLocations.set(findByTag(root, "rockmap-main-markers"));
            p.trips.set(findByTag(root, "rockmap-main-trips"));
            p.offlineData.set(findByTag(root, "rockmap-main-data"));
            p.layers.set(findByTag(root, "rockmap-main-layers"));
            p.find.set(findByTag(root, "rockmap-main-find"));
            p.researchMain.set(findByTag(root, "rockmap-main-minerals"));
            p.helpTours.set(findByTag(root, "rockmap-help-tours"));
            p.researchCollapse.set(findByContentDescription(root, "Collapse Research workspace", false));
            p.researchExpand.set(findByContentDescription(root, "Expand Research workspace", false));
            p.mappedCollapse.set(findByContentDescription(root, "Collapse mapped research controls", false));
            p.mappedCollapsed.set(findByContentDescription(root, "Open mapped research controls", true));
            p.mappedDrag.set(findByContentDescription(root, "Drag mapped research controls", true));
            p.mineralTab.set(findByButtonText(root, "Mineral Evidence"));
            p.historicTab.set(findByButtonText(root, "Historic Mines"));
            p.showEvidence.set(findByText(root, "Show Evidence on Map", false));
            p.mineralChoice.set(findMineralChoice(root));
            return p;
        }

        Probe targetForMainStep(int step) {
            switch (step) {
                case 1: return centerGps;
                case 2: return saveGps;
                case 3: return savedLocations;
                case 4: return trips;
                case 5: return offlineData;
                case 6:
                case 13: return layers;
                case 7: return find;
                case 8: return researchMain;
                case 11: return mineralTab;
                case 12: return showEvidence.ready() ? showEvidence : mineralChoice;
                case 14: return historicTab;
                case 15: return researchCollapse;
                case 16: return researchExpand;
                case 17: return mappedDrag.ready() ? mappedDrag : mappedCollapse;
                case 18: return mappedCollapse;
                case 19: return mappedCollapsed;
                case 20: return helpTours;
                default: return null;
            }
        }

        String signature() {
            return centerGps.compact() + ',' + saveGps.compact() + ',' + savedLocations.compact()
                    + ',' + trips.compact() + ',' + offlineData.compact() + ',' + layers.compact()
                    + ',' + find.compact() + ',' + researchMain.compact() + ',' + helpTours.compact()
                    + ',' + researchCollapse.compact() + ',' + researchExpand.compact()
                    + ',' + mineralTab.compact() + ',' + historicTab.compact()
                    + ',' + mineralChoice.compact() + ',' + showEvidence.compact()
                    + ',' + mappedDrag.compact() + ',' + mappedCollapse.compact()
                    + ',' + mappedCollapsed.compact();
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
                    + " mappedDrag{" + mappedDrag.detail + "}"
                    + " mappedCollapse{" + mappedCollapse.detail + "}"
                    + " mappedCollapsed{" + mappedCollapsed.detail + "}";
        }
    }

    private static final class FieldExpectation {
        final String targetTag;
        final Probe target;
        final boolean expectedDialog;
        final boolean requiresMapPhase;
        final String note;

        FieldExpectation(String targetTag, View target, boolean expectedDialog,
                         boolean requiresMapPhase, String note) {
            this.targetTag = targetTag;
            this.target = new Probe("fieldTarget");
            this.target.set(target);
            this.expectedDialog = expectedDialog;
            this.requiresMapPhase = requiresMapPhase;
            this.note = note == null ? "" : note;
        }

        static FieldExpectation none() {
            return new FieldExpectation(null, null, false, false, "none");
        }

        static FieldExpectation forState(FieldTourStateSnapshot f, Activity activity, View root) {
            String tag = null;
            boolean dialog = false;
            boolean mapPhase = false;
            String note = "coach-sync-only";

            if ("Tracks".equals(f.tool)) {
                switch (f.step) {
                    case 5: tag = "rockmap-track-status"; break;
                    case 6: tag = "rockmap-track-pause"; break;
                    case 7: tag = "rockmap-track-resume"; break;
                    case 8: tag = "rockmap-hud-drag:Track"; break;
                    case 9: tag = "rockmap-track-list"; break;
                    case 11: tag = "rockmap-track-stop"; break;
                    case 13: tag = "rockmap-track-backtrack"; break;
                    case 14: tag = "rockmap-track-hide"; break;
                    case 15: tag = "rockmap-track-delete"; break;
                    case 16: tag = "rockmap-track-all"; break;
                    case 17: tag = "rockmap-track-close-view"; break;
                    default: note = "cross-screen/dialog step; coach-sync invariant only"; break;
                }
            } else if ("Navigate".equals(f.tool)) {
                switch (f.step) {
                    case 5:
                    case 6: tag = "rockmap-nav-status"; break;
                    case 7: tag = "rockmap-nav-frame"; break;
                    case 8: tag = "rockmap-nav-target"; break;
                    case 9: tag = "rockmap-nav-stop"; break;
                    default: note = "setup/dialog step; coach-sync invariant only"; break;
                }
            } else if ("Measure".equals(f.tool)) {
                switch (f.step) {
                    case 1:
                        dialog = true;
                        note = "Field menu dialog";
                        break;
                    case 2: tag = "rockmap-measure-tap-map"; break;
                    case 3: tag = "rockmap-measure-cancel-tap"; break;
                    case 4: tag = "rockmap-measure-tap-map"; break;
                    case 5:
                        mapPhase = true;
                        note = "live map tap";
                        break;
                    case 6: tag = "rockmap-measure-drag-note"; break;
                    case 7: tag = "rockmap-measure-undo"; break;
                    case 8:
                        tag = "undo".equals(f.phase)
                                ? "rockmap-measure-undo" : "rockmap-measure-add-gps";
                        break;
                    case 9: tag = "rockmap-measure-saved"; break;
                    case 10: tag = "rockmap-measure-field"; break;
                    case 11:
                    case 12:
                    case 13:
                        if ("map".equals(f.phase)) {
                            mapPhase = true;
                            note = "live polygon map tap";
                        } else {
                            tag = "rockmap-measure-tap-map";
                        }
                        break;
                    case 14: tag = "rockmap-measure-summary"; break;
                    case 15: tag = "rockmap-measure-done"; break;
                    case 16: tag = "rockmap-measure-save-area"; break;
                    case 17:
                        dialog = true;
                        note = "Save measured area dialog";
                        break;
                    default: note = "unknown Measure step"; break;
                }
            } else if ("Prospecting Areas".equals(f.tool)) {
                switch (f.step) {
                    case 1:
                        dialog = true;
                        note = "Field menu dialog";
                        break;
                    case 3: tag = "rockmap-measure-header"; break;
                    case 4: tag = "rockmap-measure-add-gps"; break;
                    case 5: tag = "rockmap-measure-saved"; break;
                    case 6: tag = "rockmap-measure-field"; break;
                    case 7: tag = "rockmap-measure-paste"; break;
                    case 8: tag = "rockmap-measure-undo"; break;
                    case 9: tag = "rockmap-measure-done"; break;
                    case 10:
                    case 11:
                    case 12:
                        if ("map".equals(f.phase)) {
                            mapPhase = true;
                            note = "live boundary map tap";
                        } else {
                            tag = "rockmap-measure-tap-map";
                        }
                        break;
                    case 13: tag = "rockmap-measure-drag-note"; break;
                    case 14: tag = "rockmap-measure-save-area"; break;
                    case 15:
                        dialog = true;
                        note = "Save Prospecting Area dialog";
                        break;
                    default:
                        note = "saved-area/Research handoff step; coach-sync invariant only";
                        break;
                }
            } else {
                note = "tool has generic coach-sync invariant; no map-target contract in audit v2";
                if (f.step == 1) dialog = true;
            }

            View target = tag == null ? null : findByTag(root, tag);
            return new FieldExpectation(tag, target, dialog, mapPhase, note);
        }

        String compact() {
            return "tag=" + (targetTag == null ? "-" : targetTag)
                    + ",target=" + target.compact()
                    + ",dialog=" + expectedDialog
                    + ",mapPhase=" + requiresMapPhase
                    + ",note=" + clean(note, 80);
        }

        String full() {
            return compact() + " targetDetail={" + target.detail + "}";
        }
    }

    private static final class CoachSnapshot {
        final boolean present;
        final int step;
        final String hostKind;
        final String owner;
        final String title;
        final String detail;

        CoachSnapshot(boolean present, int step, String hostKind,
                      String owner, String title, String detail) {
            this.present = present;
            this.step = step;
            this.hostKind = hostKind;
            this.owner = owner;
            this.title = title;
            this.detail = detail;
        }

        static CoachSnapshot read() {
            try {
                Field rootField = GuidedTourCoach.class.getDeclaredField("activeCoachRoot");
                Field ownerField = GuidedTourCoach.class.getDeclaredField("activeCoachOwner");
                rootField.setAccessible(true);
                ownerField.setAccessible(true);

                Object rootRefObject = rootField.get(null);
                Object ownerRefObject = ownerField.get(null);
                ViewGroup root = null;
                Activity owner = null;
                if (rootRefObject instanceof WeakReference) {
                    Object value = ((WeakReference<?>) rootRefObject).get();
                    if (value instanceof ViewGroup) root = (ViewGroup) value;
                }
                if (ownerRefObject instanceof WeakReference) {
                    Object value = ((WeakReference<?>) ownerRefObject).get();
                    if (value instanceof Activity) owner = (Activity) value;
                }
                if (root == null) {
                    return new CoachSnapshot(false, 0, "none",
                            activityName(owner), "", "activeCoachRoot=null");
                }
                View coach = root.findViewWithTag(COACH_TAG);
                if (coach == null) {
                    return new CoachSnapshot(false, 0, hostKind(root),
                            activityName(owner), "", "coach tag missing from active root");
                }
                int step = parseCoachStepFromTree(coach);
                String title = findCoachTitle(coach);
                return new CoachSnapshot(true, step, hostKind(root),
                        activityName(owner), title, describe(coach));
            } catch (Throwable ex) {
                return new CoachSnapshot(false, 0, "reflection_error", "",
                        "", clean(ex.getClass().getSimpleName() + ":" + ex.getMessage(), 160));
            }
        }

        static String hostKind(ViewGroup root) {
            if (root == null) return "none";
            String name = root.getClass().getSimpleName();
            return name != null && name.contains("DialogCoachHost") ? "dialog" : "activity";
        }

        String compact() {
            return (present ? "READY" : "missing")
                    + "[step=" + step + ",host=" + hostKind + ",owner=" + owner
                    + ",title=" + clean(title, 50) + "]";
        }

        String full() {
            return compact() + " detail={" + detail + "}";
        }
    }

    private static View findByTag(View root, String tag) {
        if (root == null || tag == null) return null;
        if (tag.equals(String.valueOf(root.getTag()))) return root;
        if (root instanceof ViewGroup) {
            View tagged = ((ViewGroup) root).findViewWithTag(tag);
            if (tagged != null) return tagged;
        }
        return null;
    }

    private static View findByContentDescription(View root, String value, boolean prefix) {
        if (root == null || value == null) return null;
        CharSequence raw = root.getContentDescription();
        String desc = raw == null ? "" : raw.toString();
        if ((prefix && desc.startsWith(value)) || (!prefix && value.equals(desc))) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByContentDescription(group.getChildAt(i), value, prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findByText(View root, String value, boolean prefix) {
        if (root == null || value == null) return null;
        if (root instanceof TextView) {
            CharSequence raw = ((TextView) root).getText();
            String text = raw == null ? "" : raw.toString();
            if ((prefix && text.startsWith(value)) || (!prefix && value.equals(text))) return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByText(group.getChildAt(i), value, prefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findByButtonText(View root, String value) {
        if (root == null || value == null) return null;
        if (root instanceof android.widget.Button) {
            CharSequence raw = ((android.widget.Button) root).getText();
            if (raw != null && value.equals(raw.toString())) return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByButtonText(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findMineralChoice(View root) {
        if (root == null) return null;
        CharSequence raw = root.getContentDescription();
        String desc = raw == null ? "" : raw.toString();
        if ((desc.startsWith("Select ") || desc.startsWith("Selected "))
                && desc.contains("Mineral Evidence")) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findMineralChoice(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int parseCoachStepFromTree(View root) {
        if (root == null) return 0;
        if (root instanceof TextView) {
            CharSequence raw = ((TextView) root).getText();
            int parsed = parseCoachStep(raw == null ? "" : raw.toString());
            if (parsed > 0) return parsed;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                int parsed = parseCoachStepFromTree(group.getChildAt(i));
                if (parsed > 0) return parsed;
            }
        }
        return 0;
    }

    private static String findCoachTitle(View root) {
        if (root == null) return "";
        if (root instanceof TextView) {
            CharSequence raw = ((TextView) root).getText();
            String text = raw == null ? "" : raw.toString().trim();
            if (!text.isEmpty()
                    && !text.startsWith("GUIDED TOUR · ")
                    && !text.matches("\\d+/\\d+")
                    && !text.startsWith("ACTION: ")
                    && text.length() <= 100) {
                return text;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                String title = findCoachTitle(group.getChildAt(i));
                if (!title.isEmpty()) return title;
            }
        }
        return "";
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
        if (view == null || !view.isAttachedToWindow()
                || !view.isShown() || !view.isEnabled()) return false;
        if (effectiveAlpha(view) <= 0.10f
                || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        Rect rect = new Rect();
        return view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0;
    }

    private static float effectiveAlpha(View view) {
        float alpha = 1f;
        View current = view;
        int guard = 0;
        while (current != null && guard++ < 100) {
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
                ? clean(((TextView) view).getText().toString(), 100) : "";
        CharSequence rawDesc = view.getContentDescription();
        String desc = rawDesc == null ? "" : clean(rawDesc.toString(), 140);
        Object tag = view.getTag();
        return "class=" + view.getClass().getSimpleName()
                + ",text=" + text
                + ",desc=" + desc
                + ",tag=" + clean(String.valueOf(tag), 100)
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
}
