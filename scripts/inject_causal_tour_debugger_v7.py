#!/usr/bin/env python3
"""Causal debugger v7: preserve causal evidence while removing routine diagnostics noise.

Diagnostic-only. This pass runs after causal v6/v5 and changes only debugger bookkeeping and
logging policy. It never changes RockMap UI, tour state, map state, location state, or saved data.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def replace_once(path: Path, marker: str, old: str, new: str, label: str) -> None:
    current = text(path)
    if marker in current:
        print(f"{label}: already present")
        return
    count = current.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one match in {path.relative_to(ROOT)}, found {count}"
        )
    path.write_text(current.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: injected")


def patch_causality(path: Path) -> None:
    replace_once(
        path,
        "causal-v7-main-looper-import",
        '''import android.os.SystemClock;
''',
        '''import android.os.SystemClock;
import android.os.Looper; // marker: causal-v7-main-looper-import
''',
        "main-thread causal continuation import",
    )

    replace_once(
        path,
        "causal-v7-recent-user-cause-state",
        '''    private static String lastUserCauseId = "none";
    private static String lastUserTarget = "";
    private static long lastUserElapsed;
''',
        '''    private static String lastUserCauseId = "none";
    private static String lastUserTarget = "";
    private static long lastUserElapsed;
    private static Cause lastUserCause;
    private static WeakReference<Activity> lastUserActivity = new WeakReference<>(null);
    private static final long RECENT_USER_CONTINUATION_MS = 350L;
    // marker: causal-v7-recent-user-cause-state
''',
        "recent user cause state",
    )

    replace_once(
        path,
        "causal-v7-destroy-recent-user",
        '''            Activity last = lastResumedActivity.get();
            if (last == activity) lastResumedActivity = new WeakReference<>(null);
''',
        '''            Activity last = lastResumedActivity.get();
            if (last == activity) lastResumedActivity = new WeakReference<>(null);
            Activity userActivity = lastUserActivity.get();
            if (userActivity == activity) {
                lastUserCause = null;
                lastUserActivity = new WeakReference<>(null);
            }
            // marker: causal-v7-destroy-recent-user
''',
        "clear recent user cause with destroyed activity",
    )

    replace_once(
        path,
        "causal-v7-context-scope-api",
        '''    public static String currentCauseId() {
        Cause cause = CURRENT.get();
        return cause == null ? "none" : cause.id;
    }

    public static String currentOrigin() {
        Cause cause = CURRENT.get();
        return cause == null ? ORIGIN_UNKNOWN : cause.origin;
    }
''',
        '''    public static final class ContextScope implements AutoCloseable {
        private final Cause previous;
        private final boolean changed;
        private boolean closed;

        private ContextScope(Cause previous, boolean changed) {
            this.previous = previous;
            this.changed = changed;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (changed) CURRENT.set(previous);
        }
    }

    private static Cause recentUserCause(Activity activity) {
        if (activity == null || Looper.myLooper() != Looper.getMainLooper()) return null;
        synchronized (LOCK) {
            Activity owner = lastUserActivity.get();
            if (owner != activity || lastUserCause == null || lastUserElapsed <= 0L) return null;
            long age = Math.max(0L, SystemClock.elapsedRealtime() - lastUserElapsed);
            return age <= RECENT_USER_CONTINUATION_MS ? lastUserCause : null;
        }
    }

    /**
     * Re-enters the most recent USER cause only for immediate main-thread diagnostic work owned by
     * the same Activity. This closes the dispatchTouchEvent -> posted UI bookkeeping gap without
     * globally relabeling unrelated sensor, GPS, worker, or later lifecycle callbacks as USER.
     */
    public static ContextScope continueRecentUserContext(Activity activity) {
        Cause previous = CURRENT.get();
        if (previous != null) return new ContextScope(previous, false);
        Cause recent = recentUserCause(activity);
        if (recent == null) return new ContextScope(previous, false);
        CURRENT.set(recent);
        return new ContextScope(previous, true);
    } // marker: causal-v7-context-scope-api

    public static String currentCauseId() {
        Cause cause = CURRENT.get();
        return cause == null ? "none" : cause.id;
    }

    public static String currentOrigin() {
        Cause cause = CURRENT.get();
        return cause == null ? ORIGIN_UNKNOWN : cause.origin;
    }
''',
        "narrow recent-user causal continuation API",
    )

    replace_once(
        path,
        "causal-v7-capture-user-cause",
        '''                lastUserCauseId = user.id;
                lastUserTarget = hit;
                lastUserElapsed = SystemClock.elapsedRealtime();
''',
        '''                lastUserCauseId = user.id;
                lastUserTarget = hit;
                lastUserElapsed = SystemClock.elapsedRealtime();
                lastUserCause = user;
                lastUserActivity = new WeakReference<>(activity);
                // marker: causal-v7-capture-user-cause
''',
        "retain recent user cause object",
    )

    replace_once(
        path,
        "causal-v7-scheduled-parent-fallback",
        '''        final Cause schedulingCause = CURRENT.get();
        final String parentId = schedulingCause == null ? "" : schedulingCause.id;
''',
        '''        Cause directSchedulingCause = CURRENT.get();
        final Cause schedulingCause = directSchedulingCause != null
                ? directSchedulingCause : recentUserCause(activity);
        final String parentId = schedulingCause == null ? "" : schedulingCause.id;
        // marker: causal-v7-scheduled-parent-fallback
''',
        "carry recent user parent into immediate scheduled work",
    )

    replace_once(
        path,
        "causal-v7-state-mutation-continuation-open",
        '''    public static void stateMutation(Activity activity, String surface, String before,
                                     String after, String detail) {
        Cause cause = CURRENT.get();
        String event = cause == null ? "UNATTRIBUTED_STATE_CHANGE" : "STATE_MUTATION";
''',
        '''    public static void stateMutation(Activity activity, String surface, String before,
                                     String after, String detail) {
        try (ContextScope ignored = continueRecentUserContext(activity)) {
        Cause cause = CURRENT.get();
        String event = cause == null ? "UNATTRIBUTED_STATE_CHANGE" : "STATE_MUTATION";
        // marker: causal-v7-state-mutation-continuation-open
''',
        "attribute immediate post-dispatch state mutations",
    )

    replace_once(
        path,
        "causal-v7-state-mutation-continuation-close",
        '''            finding(activity, "WARNING", "UNATTRIBUTED_STATE_CHANGE",
                    "surface=" + clean(surface, 120)
                            + " before=" + clean(before, 120)
                            + " after=" + clean(after, 120));
        }
    }

    public static void onMainTourStepChanged''',
        '''            finding(activity, "WARNING", "UNATTRIBUTED_STATE_CHANGE",
                    "surface=" + clean(surface, 120)
                            + " before=" + clean(before, 120)
                            + " after=" + clean(after, 120));
        }
        }
    } // marker: causal-v7-state-mutation-continuation-close

    public static void onMainTourStepChanged''',
        "close immediate state-mutation causal scope",
    )

    replace_once(
        path,
        "causal-v7-step-entry-recent-user",
        '''            next = new StepState(++stepSerial, step, currentCauseId(), currentOrigin(), now);
            currentStep = next;
''',
        '''            Cause entryCause = CURRENT.get();
            if (entryCause == null) entryCause = recentUserCause(activity);
            next = new StepState(++stepSerial, step,
                    entryCause == null ? "none" : entryCause.id,
                    entryCause == null ? ORIGIN_UNKNOWN : entryCause.origin, now);
            currentStep = next;
            // marker: causal-v7-step-entry-recent-user
''',
        "retain recent user cause on asynchronous preference step entry",
    )

    replace_once(
        path,
        "causal-v7-step-no-progress-policy",
        '''                finding(lastResumedActivity(), "ERROR", "STEP_NO_PROGRESS",
                        "step=" + live.step + " elapsedMs="
                                + Math.max(0L, SystemClock.elapsedRealtime() - live.enteredElapsed));
            }
        }, 1200L, TimeUnit.MILLISECONDS);
''',
        '''                finding(lastResumedActivity(), "WARNING", "STEP_NO_PROGRESS",
                        "step=" + live.step
                                + " thresholdMs=3500 elapsedMs="
                                + Math.max(0L, SystemClock.elapsedRealtime() - live.enteredElapsed));
            }
        }, 3500L, TimeUnit.MILLISECONDS);
        // marker: causal-v7-step-no-progress-policy
''',
        "make no-progress watchdog a delayed warning",
    )


def patch_log(path: Path) -> None:
    replace_once(
        path,
        "causal-v7-noise-state",
        '''    private static final Object HUD_EVENT_LOCK = new Object();
    private static final Object GPS_EVENT_LOCK = new Object();
    private static Location lastGpsFix;
''',
        '''    private static final Object HUD_EVENT_LOCK = new Object();
    private static final Object GPS_EVENT_LOCK = new Object();
    private static final Object DIAGNOSTIC_NOISE_LOCK = new Object();
    private static Location lastGpsFix;
    private static String lastHeadingAccuracy = "";
    private static String lastHeadingSource = "";
    private static String lastHeadingPolicy = "";
    private static String lastCompassRender = "";
    // marker: causal-v7-noise-state
''',
        "low-noise diagnostic state",
    )

    replace_once(
        path,
        "causal-v7-periodic-hidden-hud-filter",
        '''        String type = clean(event, 60);
        // Frame/global-layout polling used to evict the user actions we actually needed to debug.
''',
        '''        String type = clean(event, 60);
        boolean periodicHiddenRefresh = "periodic_refresh".equals(reason)
                && (expandedTool == null || expandedTool.trim().isEmpty())
                && measurementCount == 0
                && (hud == null || !hud.isShown());
        if (periodicHiddenRefresh
                && ("HUD_RENDER_START".equals(type)
                    || "HUD_RENDER_BUILT".equals(type)
                    || "HUD_READY".equals(type))) {
            return;
        }
        // marker: causal-v7-periodic-hidden-hud-filter
        // Frame/global-layout polling used to evict the user actions we actually needed to debug.
''',
        "suppress unchanged hidden periodic HUD triplets",
    )

    replace_once(
        path,
        "causal-v7-heading-noise-filter",
        '''    public static void headingDiagnostic(Activity activity, String event, String detail) {
        record(clean(event, 60),
                "activity=" + activityName(activity) + " " + clean(detail, 1800));
    }

    /** Observational MapLibre compass/GPS-render diagnostics. */
    public static void mapDiagnostic(String event, String detail) {
        record(clean(event, 60), clean(detail, 1800));
    }
''',
        '''    public static void headingDiagnostic(Activity activity, String event, String detail) {
        String type = clean(event, 60);
        String safeDetail = clean(detail, 1800);

        // Raw samples/filtered outputs change continuously even when the heading system is healthy.
        // Source, policy, accuracy, start/stop and failure transitions retain the useful evidence.
        if ("HEADING_OUTPUT".equals(type) || "HEADING_SENSOR_SAMPLE".equals(type)) return;

        String signature = null;
        if ("HEADING_ACCURACY".equals(type)) {
            signature = diagnosticToken(safeDetail, "accuracy=")
                    + "|" + diagnosticToken(safeDetail, "unreliable=");
            synchronized (DIAGNOSTIC_NOISE_LOCK) {
                if (signature.equals(lastHeadingAccuracy)) return;
                lastHeadingAccuracy = signature;
            }
        } else if ("HEADING_SOURCE".equals(type)) {
            signature = diagnosticToken(safeDetail, "source=");
            synchronized (DIAGNOSTIC_NOISE_LOCK) {
                if (signature.equals(lastHeadingSource)) return;
                lastHeadingSource = signature;
            }
        } else if ("HEADING_POLICY_DECISION".equals(type)) {
            signature = diagnosticToken(safeDetail, "selected=")
                    + "|" + diagnosticToken(safeDetail, "courseReason=")
                    + "|" + diagnosticToken(safeDetail, "previousSource=");
            synchronized (DIAGNOSTIC_NOISE_LOCK) {
                if (signature.equals(lastHeadingPolicy)) return;
                lastHeadingPolicy = signature;
            }
        }

        record(type, "activity=" + activityName(activity) + " " + safeDetail);
    }

    /** Observational MapLibre compass/GPS-render diagnostics. */
    public static void mapDiagnostic(String event, String detail) {
        String type = clean(event, 60);
        String safeDetail = clean(detail, 1800);

        // Per-record camera probes were the largest remaining map-log flood. Keep the aggregate
        // FIELD_RECORD_ZOOM_RENDER event; individual records should only be logged by a future
        // anomaly path when a specific record fails an invariant.
        if ("FIELD_RECORD_ZOOM_ITEM".equals(type)) return;

        if ("COMPASS_RENDER".equals(type)) {
            String signature = diagnosticToken(safeDetail, "state=")
                    + "|" + diagnosticToken(safeDetail, "icon=");
            synchronized (DIAGNOSTIC_NOISE_LOCK) {
                if (signature.equals(lastCompassRender)) return;
                lastCompassRender = signature;
            }
        }

        record(type, safeDetail);
    } // marker: causal-v7-heading-noise-filter

    private static String diagnosticToken(String detail, String key) {
        if (detail == null || key == null || key.isEmpty()) return "";
        int start = detail.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = detail.indexOf(' ', start);
        if (end < 0) end = detail.length();
        return detail.substring(start, end);
    }
''',
        "suppress routine heading/compass/per-record render noise",
    )

    replace_once(
        path,
        "causal-v7-main-action-user-continuation",
        '''    public static void mainTourAction(Activity activity, String event, String detail) {
        recordImportant(clean(event, 60),
                "activity=" + activityName(activity)
                        + " detail=" + clean(detail, 600)
                        + " main={" + mainSnapshot() + "} field={" + fieldSnapshot() + "}");
    }
''',
        '''    public static void mainTourAction(Activity activity, String event, String detail) {
        try (TourDebugCausality.ContextScope ignored =
                     TourDebugCausality.continueRecentUserContext(activity)) {
            recordImportant(clean(event, 60),
                    "activity=" + activityName(activity)
                            + " detail=" + clean(detail, 600)
                            + " main={" + mainSnapshot() + "} field={" + fieldSnapshot() + "}");
        }
    } // marker: causal-v7-main-action-user-continuation
''',
        "continue recent user cause through immediate UI-invariant bookkeeping",
    )


def main() -> int:
    causality = ROOT / "app/src/main/java/com/rockmap/app/TourDebugCausality.java"
    log = ROOT / "app/src/main/java/com/rockmap/app/TourDebugLog.java"
    for path in (causality, log):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: text(path) for path in (causality, log)}
    try:
        patch_causality(causality)
        patch_log(log)

        # Diagnostic-only guard: this pass may change debugger context/bookkeeping and filtering,
        # but may not manipulate RockMap UI, tour/map state, or user data.
        for path in (causality, log):
            injected = text(path)
            for forbidden in (
                ".performClick(", ".setVisibility(", ".bringToFront(", ".requestLayout(",
                ".invalidate(", "GuidedTourState.setStep(", "GuidedTourState.advance(",
                "FieldMapState.set", "MapHudCoordinator.beforeExpand(", "deleteTrack(",
                "deleteArea(", "deleteFieldRecord("
            ):
                if injected.count(forbidden) > originals[path].count(forbidden):
                    raise RuntimeError(
                        f"causal v7 observational scope guard failed: "
                        f"{path.relative_to(ROOT)} introduced {forbidden}"
                    )

        print("Causal debugger v7 diagnostics cleanup complete.")
        print("Policy: short same-Activity USER continuation; no raw heading/per-record flood; "
              "hidden periodic HUD refresh suppressed; step no-progress is a 3.5s warning.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("Causal debugger v7 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
