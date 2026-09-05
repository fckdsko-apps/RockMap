package com.rockmap.app;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Causal attribution for the tour-debug build.
 *
 * This class is deliberately observational. It never clicks a View, changes tour state, moves UI,
 * retries an application action, or suppresses an application callback. It only attaches causal
 * metadata to work that would have happened anyway and emits watchdog findings when expected
 * diagnostic milestones are missing.
 */
public final class TourDebugCausality {
    public static final String SCHEMA = "causal-v2";

    public static final String ORIGIN_USER = "USER";
    public static final String ORIGIN_TOUR = "TOUR_ENGINE";
    public static final String ORIGIN_FRAME = "FRAME_CALLBACK";
    public static final String ORIGIN_MAP = "MAP_CALLBACK";
    public static final String ORIGIN_AUTO = "AUTO_REFRESH";
    public static final String ORIGIN_RESTORE = "STATE_RESTORE";
    public static final String ORIGIN_SYSTEM = "SYSTEM";
    public static final String ORIGIN_CALLBACK = "ASYNC_CALLBACK";
    public static final String ORIGIN_UNKNOWN = "UNATTRIBUTED";

    private static final AtomicLong NEXT_CAUSE = new AtomicLong(1L);
    private static final AtomicLong NEXT_CALLBACK = new AtomicLong(1L);
    private static final Object LOCK = new Object();
    private static final ThreadLocal<Cause> CURRENT = new ThreadLocal<>();
    private static final WeakHashMap<Activity, Gesture> GESTURES = new WeakHashMap<>();
    private static WeakReference<Activity> lastResumedActivity = new WeakReference<>(null);
    private static StepState currentStep;
    private static long stepSerial;
    private static long lastFailureSnapshotElapsed;

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rockmap-tour-causal-watchdog");
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    private TourDebugCausality() {}

    private static final class Cause {
        final String id;
        final String origin;
        final String action;
        final String target;
        final String parent;
        final long startedElapsed;

        Cause(String id, String origin, String action, String target,
              String parent, long startedElapsed) {
            this.id = id;
            this.origin = origin;
            this.action = action;
            this.target = target;
            this.parent = parent;
            this.startedElapsed = startedElapsed;
        }
    }

    private static final class Gesture {
        final Cause cause;
        final float startX;
        final float startY;
        final long startedElapsed;
        final String initialHit;
        float lastX;
        float lastY;
        boolean moved;

        Gesture(Cause cause, float startX, float startY, long startedElapsed, String initialHit) {
            this.cause = cause;
            this.startX = startX;
            this.startY = startY;
            this.lastX = startX;
            this.lastY = startY;
            this.startedElapsed = startedElapsed;
            this.initialHit = initialHit;
        }
    }

    private static final class StepState {
        final long serial;
        final int step;
        final String causeId;
        final String origin;
        final long enteredElapsed;
        volatile long preparedElapsed;
        volatile long targetVerifiedElapsed;
        volatile long coachRequestedElapsed;
        volatile long coachShownElapsed;
        volatile String preparation = "";
        volatile String targetLabel = "";

        StepState(long serial, int step, String causeId, String origin, long enteredElapsed) {
            this.serial = serial;
            this.step = step;
            this.causeId = causeId;
            this.origin = origin;
            this.enteredElapsed = enteredElapsed;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final Cause previous;
        private final Cause active;
        private boolean closed;

        private Scope(Cause previous, Cause active) {
            this.previous = previous;
            this.active = active;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - active.startedElapsed);
            TourDebugLog.causalEvent("CAUSE_END",
                    "cause=" + active.id + " origin=" + active.origin
                            + " action=" + clean(active.action, 160)
                            + " elapsedMs=" + elapsed);
            CURRENT.set(previous);
        }
    }

    public static final class DispatchToken {
        private final Cause previous;
        private final Gesture gesture;
        private final int action;
        private DispatchToken(Cause previous, Gesture gesture, int action) {
            this.previous = previous;
            this.gesture = gesture;
            this.action = action;
        }
    }

    public static void onActivityResumed(Activity activity) {
        if (activity == null) return;
        synchronized (LOCK) {
            lastResumedActivity = new WeakReference<>(activity);
        }
    }

    public static void onActivityDestroyed(Activity activity) {
        if (activity == null) return;
        synchronized (LOCK) {
            GESTURES.remove(activity);
            Activity last = lastResumedActivity.get();
            if (last == activity) lastResumedActivity = new WeakReference<>(null);
        }
    }

    public static Activity lastResumedActivity() {
        synchronized (LOCK) {
            return lastResumedActivity.get();
        }
    }

    public static String currentCauseId() {
        Cause cause = CURRENT.get();
        return cause == null ? "none" : cause.id;
    }

    public static String currentOrigin() {
        Cause cause = CURRENT.get();
        return cause == null ? ORIGIN_UNKNOWN : cause.origin;
    }

    public static String contextSummary() {
        Cause cause = CURRENT.get();
        if (cause == null) {
            return "cause=none origin=" + ORIGIN_UNKNOWN + " parent=none action=none";
        }
        return "cause=" + cause.id
                + " origin=" + cause.origin
                + " parent=" + empty(cause.parent, "none")
                + " action=" + clean(cause.action, 120)
                + " target=" + clean(cause.target, 140);
    }

    public static Scope begin(Activity activity, String origin, String action, String target) {
        Cause previous = CURRENT.get();
        return beginWithParent(activity, origin, action, target,
                previous == null ? "" : previous.id, previous);
    }

    private static Scope beginWithParent(Activity activity, String origin, String action,
                                         String target, String parentId, Cause previous) {
        String safeOrigin = empty(origin, ORIGIN_UNKNOWN);
        Cause cause = new Cause(nextCauseId(safeOrigin), safeOrigin,
                empty(action, "unspecified"), empty(target, ""), empty(parentId, ""),
                SystemClock.elapsedRealtime());
        CURRENT.set(cause);
        TourDebugLog.causalEvent("CAUSE_BEGIN",
                "cause=" + cause.id
                        + " origin=" + cause.origin
                        + " parent=" + empty(cause.parent, "none")
                        + " activity=" + activityName(activity)
                        + " action=" + clean(cause.action, 180)
                        + " target=" + clean(cause.target, 220));
        return new Scope(previous, cause);
    }

    public static Runnable wrapScheduled(Activity activity, String origin, String action,
                                         String target, Runnable delegate) {
        final Cause schedulingCause = CURRENT.get();
        final String parentId = schedulingCause == null ? "" : schedulingCause.id;
        final String callbackId = "CB" + NEXT_CALLBACK.getAndIncrement();
        final long scheduledAt = SystemClock.elapsedRealtime();
        TourDebugLog.causalEvent("CALLBACK_SCHEDULED",
                "callback=" + callbackId
                        + " origin=" + empty(origin, ORIGIN_CALLBACK)
                        + " parent=" + empty(parentId, "none")
                        + " activity=" + activityName(activity)
                        + " action=" + clean(action, 180)
                        + " target=" + clean(target, 180));
        return () -> {
            Cause previous = CURRENT.get();
            try (Scope ignored = beginWithParent(activity, empty(origin, ORIGIN_CALLBACK),
                    "callback:" + callbackId + ":" + empty(action, "unspecified"),
                    target, parentId, previous)) {
                TourDebugLog.causalEvent("CALLBACK_START",
                        "callback=" + callbackId
                                + " ageMs=" + Math.max(0L, SystemClock.elapsedRealtime() - scheduledAt)
                                + " activity=" + activityName(activity));
                if (delegate != null) delegate.run();
                TourDebugLog.causalEvent("CALLBACK_COMPLETE",
                        "callback=" + callbackId + " activity=" + activityName(activity));
            } catch (Throwable error) {
                TourDebugLog.causalEvent("CALLBACK_THROW",
                        "callback=" + callbackId
                                + " error=" + error.getClass().getSimpleName()
                                + " message=" + clean(error.getMessage(), 240));
                throw error;
            }
        };
    }

    /**
     * Called by transparent dispatchTouchEvent overrides. The original event is never modified,
     * cancelled, consumed, delayed, or synthesized.
     */
    public static DispatchToken beforeDispatchTouch(Activity activity, MotionEvent event) {
        if (activity == null || event == null) return new DispatchToken(CURRENT.get(), null, -1);
        int action = event.getActionMasked();
        Cause previous = CURRENT.get();
        Gesture gesture;
        synchronized (LOCK) {
            gesture = GESTURES.get(activity);
            if (action == MotionEvent.ACTION_DOWN || gesture == null) {
                float rawX = event.getRawX();
                float rawY = event.getRawY();
                String hit = TourDebugSurfaceAudit.hitSummary(activity, rawX, rawY);
                Cause user = new Cause(nextCauseId(ORIGIN_USER), ORIGIN_USER,
                        action == MotionEvent.ACTION_DOWN ? "touch-gesture" : "orphan-touch",
                        hit, "", SystemClock.elapsedRealtime());
                gesture = new Gesture(user, rawX, rawY, SystemClock.elapsedRealtime(), hit);
                GESTURES.put(activity, gesture);
                TourDebugLog.causalEvent("USER_GESTURE_START",
                        "cause=" + user.id
                                + " activity=" + activityName(activity)
                                + " x=" + Math.round(rawX)
                                + " y=" + Math.round(rawY)
                                + " hit={" + clean(hit, 900) + "}");
            }
            gesture.lastX = event.getRawX();
            gesture.lastY = event.getRawY();
            if (Math.hypot(gesture.lastX - gesture.startX,
                    gesture.lastY - gesture.startY) >= 12.0) gesture.moved = true;
        }
        CURRENT.set(gesture.cause);
        return new DispatchToken(previous, gesture, action);
    }

    public static void afterDispatchTouch(Activity activity, MotionEvent event, DispatchToken token) {
        if (token == null) return;
        try {
            if (token.gesture != null
                    && (token.action == MotionEvent.ACTION_UP
                    || token.action == MotionEvent.ACTION_CANCEL)) {
                Gesture g = token.gesture;
                String endHit = event == null ? ""
                        : TourDebugSurfaceAudit.hitSummary(activity, event.getRawX(), event.getRawY());
                TourDebugLog.causalEvent("USER_GESTURE_END",
                        "cause=" + g.cause.id
                                + " activity=" + activityName(activity)
                                + " kind=" + (g.moved ? "drag-or-swipe" : "tap")
                                + " durationMs=" + Math.max(0L,
                                SystemClock.elapsedRealtime() - g.startedElapsed)
                                + " start=" + Math.round(g.startX) + "," + Math.round(g.startY)
                                + " end=" + Math.round(g.lastX) + "," + Math.round(g.lastY)
                                + " initialHit={" + clean(g.initialHit, 650) + "}"
                                + " endHit={" + clean(endHit, 650) + "}");
                synchronized (LOCK) {
                    Gesture live = GESTURES.get(activity);
                    if (live == g) GESTURES.remove(activity);
                }
            }
        } finally {
            CURRENT.set(token.previous);
        }
    }

    public static void stateMutation(Activity activity, String surface, String before,
                                     String after, String detail) {
        Cause cause = CURRENT.get();
        String event = cause == null ? "UNATTRIBUTED_STATE_CHANGE" : "STATE_MUTATION";
        TourDebugLog.causalEvent(event,
                "activity=" + activityName(activity)
                        + " surface=" + clean(surface, 120)
                        + " before=" + clean(before, 180)
                        + " after=" + clean(after, 180)
                        + " " + contextSummary()
                        + " detail=" + clean(detail, 800));
        if (cause == null) {
            finding(activity, "WARNING", "UNATTRIBUTED_STATE_CHANGE",
                    "surface=" + clean(surface, 120)
                            + " before=" + clean(before, 120)
                            + " after=" + clean(after, 120));
        }
    }

    public static void onMainTourStepChanged(int step, String tourState) {
        Activity activity = lastResumedActivity();
        final long now = SystemClock.elapsedRealtime();
        final StepState next;
        final StepState previous;
        synchronized (LOCK) {
            previous = currentStep;
            if (previous != null && previous.step == step
                    && "in_progress".equals(tourState)) return;
            if (!"in_progress".equals(tourState)) {
                currentStep = null;
                if (previous != null) {
                    TourDebugLog.causalEvent("STEP_EXIT",
                            "step=" + previous.step
                                    + " reason=tour-state-" + clean(tourState, 60)
                                    + " durationMs=" + Math.max(0L, now - previous.enteredElapsed));
                }
                return;
            }
            if (previous != null) {
                TourDebugLog.causalEvent("STEP_EXIT",
                        "step=" + previous.step
                                + " reason=step-changed"
                                + " durationMs=" + Math.max(0L, now - previous.enteredElapsed)
                                + " coachRequested=" + (previous.coachRequestedElapsed > 0L)
                                + " coachShown=" + (previous.coachShownElapsed > 0L)
                                + " targetVerified=" + (previous.targetVerifiedElapsed > 0L));
            }
            next = new StepState(++stepSerial, step, currentCauseId(), currentOrigin(), now);
            currentStep = next;
        }
        TourDebugLog.causalEvent("STEP_ENTER",
                "serial=" + next.serial
                        + " step=" + step
                        + " enteredByCause=" + next.causeId
                        + " enteredByOrigin=" + next.origin);

        WATCHDOG.schedule(() -> {
            StepState live;
            synchronized (LOCK) { live = currentStep; }
            if (live == null || live.serial != next.serial) return;
            if (live.preparedElapsed == 0L && live.targetVerifiedElapsed == 0L
                    && live.coachRequestedElapsed == 0L) {
                finding(lastResumedActivity(), "ERROR", "STEP_NO_PROGRESS",
                        "step=" + live.step + " elapsedMs="
                                + Math.max(0L, SystemClock.elapsedRealtime() - live.enteredElapsed));
            }
        }, 1200L, TimeUnit.MILLISECONDS);

        WATCHDOG.schedule(() -> {
            StepState live;
            synchronized (LOCK) { live = currentStep; }
            if (live == null || live.serial != next.serial) return;
            if (live.coachShownElapsed == 0L) {
                finding(lastResumedActivity(), "ERROR", "STEP_COACH_NOT_SHOWN",
                        "step=" + live.step
                                + " prepared=" + (live.preparedElapsed > 0L)
                                + " targetVerified=" + (live.targetVerifiedElapsed > 0L)
                                + " coachRequested=" + (live.coachRequestedElapsed > 0L)
                                + " elapsedMs=" + Math.max(0L,
                                SystemClock.elapsedRealtime() - live.enteredElapsed));
            }
        }, 10000L, TimeUnit.MILLISECONDS);
    }

    public static void stepPreparation(Activity activity, int step, String preparation) {
        StepState live;
        synchronized (LOCK) {
            live = currentStep;
            if (live == null || live.step != step) return;
            live.preparedElapsed = SystemClock.elapsedRealtime();
            live.preparation = empty(preparation, "");
        }
        TourDebugLog.causalEvent("STEP_PREPARE",
                "step=" + step
                        + " preparation=" + clean(preparation, 180)
                        + " " + contextSummary());
    }

    public static void stepGate(Activity activity, int step, int attempt, String stage) {
        TourDebugLog.causalEvent("STEP_GATE",
                "activity=" + activityName(activity)
                        + " step=" + step
                        + " attempt=" + attempt
                        + " stage=" + clean(stage, 120)
                        + " " + contextSummary());
    }

    public static void onFrameResult(Activity activity, String label, boolean visible,
                                     long transitionId) {
        String safeLabel = empty(label, "");
        TourDebugLog.causalEvent("FRAME_RESULT",
                "activity=" + activityName(activity)
                        + " transition=" + transitionId
                        + " label=" + clean(safeLabel, 180)
                        + " visible=" + visible
                        + " " + contextSummary());
        int step = parseTrailingStep(safeLabel, "mapped-research-step-");
        if (!visible || step <= 0) return;
        final StepState matched;
        synchronized (LOCK) {
            StepState live = currentStep;
            if (live == null || live.step != step) return;
            live.targetVerifiedElapsed = SystemClock.elapsedRealtime();
            live.targetLabel = safeLabel;
            matched = live;
        }
        WATCHDOG.schedule(() -> {
            StepState live;
            synchronized (LOCK) { live = currentStep; }
            if (live == null || live.serial != matched.serial) return;
            if (live.coachRequestedElapsed == 0L) {
                finding(lastResumedActivity(), "ERROR", "VERIFIED_TARGET_WITHOUT_COACH",
                        "step=" + live.step
                                + " label=" + clean(live.targetLabel, 180)
                                + " verifiedAgoMs=" + Math.max(0L,
                                SystemClock.elapsedRealtime() - live.targetVerifiedElapsed));
            }
        }, 300L, TimeUnit.MILLISECONDS);
    }

    public static void onCoachRequest(Activity activity, int step, long generation) {
        StepState live;
        synchronized (LOCK) {
            live = currentStep;
            if (live != null && live.step == step) {
                live.coachRequestedElapsed = SystemClock.elapsedRealtime();
            }
        }
        TourDebugLog.causalEvent("STEP_COACH_REQUESTED",
                "activity=" + activityName(activity)
                        + " step=" + step + " coachGen=" + generation
                        + " " + contextSummary());
    }

    public static void onCoachShown(Activity activity, int step, long generation) {
        StepState live;
        synchronized (LOCK) {
            live = currentStep;
            if (live != null && live.step == step) {
                live.coachShownElapsed = SystemClock.elapsedRealtime();
            }
        }
        TourDebugLog.causalEvent("STEP_COACH_SHOWN",
                "activity=" + activityName(activity)
                        + " step=" + step + " coachGen=" + generation
                        + " " + contextSummary());
    }

    public static void finding(Activity activity, String severity, String code, String detail) {
        TourDebugLog.causalEvent("DEBUG_FINDING",
                "severity=" + clean(severity, 20)
                        + " code=" + clean(code, 100)
                        + " activity=" + activityName(activity)
                        + " " + contextSummary()
                        + " detail=" + clean(detail, 1200));
        requestFailureSnapshot(activity, code);
    }

    private static void requestFailureSnapshot(Activity activity, String reason) {
        final long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (now - lastFailureSnapshotElapsed < 500L) return;
            lastFailureSnapshotElapsed = now;
        }
        Activity target = activity != null ? activity : lastResumedActivity();
        if (target == null || target.isFinishing() || target.isDestroyed()) return;
        target.runOnUiThread(() -> TourDebugSurfaceAudit.snapshot(target,
                "finding:" + clean(reason, 100)));
    }

    private static int parseTrailingStep(String value, String prefix) {
        if (value == null || prefix == null || !value.startsWith(prefix)) return -1;
        try {
            return Integer.parseInt(value.substring(prefix.length()).trim());
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static String nextCauseId(String origin) {
        String prefix;
        if (ORIGIN_USER.equals(origin)) prefix = "U";
        else if (ORIGIN_TOUR.equals(origin)) prefix = "T";
        else if (ORIGIN_FRAME.equals(origin)) prefix = "F";
        else if (ORIGIN_MAP.equals(origin)) prefix = "M";
        else if (ORIGIN_AUTO.equals(origin)) prefix = "A";
        else if (ORIGIN_RESTORE.equals(origin)) prefix = "R";
        else if (ORIGIN_SYSTEM.equals(origin)) prefix = "S";
        else if (ORIGIN_CALLBACK.equals(origin)) prefix = "C";
        else prefix = "X";
        return prefix + NEXT_CAUSE.getAndIncrement();
    }

    private static String activityName(Activity activity) {
        return activity == null ? "null" : activity.getClass().getSimpleName();
    }

    private static String empty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "…";
    }
}