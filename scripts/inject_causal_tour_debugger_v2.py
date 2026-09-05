#!/usr/bin/env python3
"""Causal debugger v2 refinements: nearest-user context, geometry/state separation, callback lineage.

Diagnostic-only. This script executes after inject_causal_tour_debugger.py and must not alter
application behavior or UI state.
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
        "causal-v2-last-user-state",
        '''    private static StepState currentStep;
    private static long stepSerial;
    private static long lastFailureSnapshotElapsed;
''',
        '''    private static StepState currentStep;
    private static long stepSerial;
    private static long lastFailureSnapshotElapsed;
    private static String lastUserCauseId = "none";
    private static String lastUserTarget = "";
    private static long lastUserElapsed;
    // marker: causal-v2-last-user-state
''',
        "nearest-user causal state",
    )

    replace_once(
        path,
        "causal-v2-context-nearest-user",
        '''    public static String contextSummary() {
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
''',
        '''    public static String contextSummary() {
        Cause cause = CURRENT.get();
        String userId;
        String userTarget;
        long userAge;
        synchronized (LOCK) {
            userId = lastUserCauseId;
            userTarget = lastUserTarget;
            userAge = lastUserElapsed <= 0L ? -1L
                    : Math.max(0L, SystemClock.elapsedRealtime() - lastUserElapsed);
        }
        String nearestUser = " lastUser=" + empty(userId, "none")
                + " lastUserAgeMs=" + userAge
                + " lastUserTarget=" + clean(userTarget, 140);
        if (cause == null) {
            return "cause=none origin=" + ORIGIN_UNKNOWN + " parent=none action=none"
                    + nearestUser;
        }
        return "cause=" + cause.id
                + " origin=" + cause.origin
                + " parent=" + empty(cause.parent, "none")
                + " action=" + clean(cause.action, 120)
                + " target=" + clean(cause.target, 140)
                + nearestUser;
    } // marker: causal-v2-context-nearest-user
''',
        "nearest user in every causal context",
    )

    replace_once(
        path,
        "causal-v2-user-start-attributed",
        '''                gesture = new Gesture(user, rawX, rawY, SystemClock.elapsedRealtime(), hit);
                GESTURES.put(activity, gesture);
                TourDebugLog.causalEvent("USER_GESTURE_START",
                        "cause=" + user.id
                                + " activity=" + activityName(activity)
                                + " x=" + Math.round(rawX)
                                + " y=" + Math.round(rawY)
                                + " hit={" + clean(hit, 900) + "}");
''',
        '''                gesture = new Gesture(user, rawX, rawY, SystemClock.elapsedRealtime(), hit);
                GESTURES.put(activity, gesture);
                lastUserCauseId = user.id;
                lastUserTarget = hit;
                lastUserElapsed = SystemClock.elapsedRealtime();
                // Attribute the gesture-start record itself, not only code that executes inside
                // super.dispatchTouchEvent().
                CURRENT.set(user);
                TourDebugLog.causalEvent("USER_GESTURE_START",
                        "cause=" + user.id
                                + " activity=" + activityName(activity)
                                + " x=" + Math.round(rawX)
                                + " y=" + Math.round(rawY)
                                + " hit={" + clean(hit, 900) + "}");
                // marker: causal-v2-user-start-attributed
''',
        "attribute raw gesture start to USER",
    )


def patch_surface_audit(path: Path) -> None:
    replace_once(
        path,
        "causal-v2-geometry-not-state",
        '''        String beforeState = null;
        String beforeGeometry = null;
        String afterGeometry = geometry(view);
        boolean changed = false;
''',
        '''        String beforeState = null;
        String beforeGeometry = null;
        String afterGeometry = geometry(view);
        boolean stateChanged = false;
        boolean geometryChanged = false;
        // marker: causal-v2-geometry-not-state
''',
        "separate logical state from geometry",
    )

    replace_once(
        path,
        "causal-v2-geometry-change-flags",
        '''            entry.state = safeState;
            entry.lastGeometry = afterGeometry;
            changed = !safeState.equals(beforeState) || !afterGeometry.equals(beforeGeometry);
        }
        if (changed) {
            TourDebugCausality.stateMutation(activity, safeId,
                    clean(beforeState, 180), clean(safeState, 180),
                    "geometry=" + clean(beforeGeometry, 260) + "->" + clean(afterGeometry, 260));
        }
''',
        '''            entry.state = safeState;
            entry.lastGeometry = afterGeometry;
            stateChanged = !safeState.equals(beforeState);
            geometryChanged = !afterGeometry.equals(beforeGeometry);
        }
        if (stateChanged) {
            TourDebugCausality.stateMutation(activity, safeId,
                    clean(beforeState, 180), clean(safeState, 180),
                    "geometry=" + clean(beforeGeometry, 260) + "->" + clean(afterGeometry, 260));
        } else if (geometryChanged) {
            TourDebugLog.causalEvent("SURFACE_GEOMETRY_CHANGE",
                    "activity=" + activityName(activity)
                            + " surface=" + clean(safeId, 120)
                            + " state=" + clean(safeState, 180)
                            + " geometry=" + clean(beforeGeometry, 300)
                            + "->" + clean(afterGeometry, 300));
        }
        // marker: causal-v2-geometry-change-flags
''',
        "geometry changes stay informational",
    )


def patch_coach_wait_lineage(path: Path) -> None:
    # The older coach target-wait callback is still an app callback. Wrap the exact Runnable only;
    # delay, callback body, conditions and results are unchanged.
    replace_once(
        path,
        "causal-v2-coach-wait-callback-lineage",
        '''        scheduler.postDelayed(() -> {
            if (!isPendingRequestCurrent(activity, generation)) {
                TourDebugLog.coachSuperseded(activity, generation, step,
                        "inside wait callback", target);
                return;
            }
            if (activity.isFinishing() || activity.isDestroyed()) {
                TourDebugLog.coachAbort(activity, generation, step,
                        "activity ended inside wait callback", target);
                return;
            }
            View liveTarget = resolveEquivalentTarget(activity, target);
''',
        '''        scheduler.postDelayed(TourDebugCausality.wrapScheduled(activity,
                TourDebugCausality.ORIGIN_TOUR,
                "coach-target-wait-step-" + step,
                "coachGeneration=" + generation + ",attempt=" + (attempt + 1),
                () -> {
            if (!isPendingRequestCurrent(activity, generation)) {
                TourDebugLog.coachSuperseded(activity, generation, step,
                        "inside wait callback", target);
                return;
            }
            if (activity.isFinishing() || activity.isDestroyed()) {
                TourDebugLog.coachAbort(activity, generation, step,
                        "activity ended inside wait callback", target);
                return;
            }
            View liveTarget = resolveEquivalentTarget(activity, target);
            // marker: causal-v2-coach-wait-callback-lineage
''',
        "coach target-wait causal callback opening",
    )

    replace_once(
        path,
        "causal-v2-coach-wait-callback-close",
        '''                waitForTargetAndShow(activity, hostRoot, step, total, title, message,
                        requiredAction, liveTarget, backAction, primaryLabel, primaryAction,
                        skipAction, exitAction, generation, nextAttempt);
            }
        }, 40L);
''',
        '''                waitForTargetAndShow(activity, hostRoot, step, total, title, message,
                        requiredAction, liveTarget, backAction, primaryLabel, primaryAction,
                        skipAction, exitAction, generation, nextAttempt);
            }
        }), 40L); // marker: causal-v2-coach-wait-callback-close
''',
        "coach target-wait causal callback close",
    )


def main() -> int:
    causality = ROOT / "app/src/main/java/com/rockmap/app/TourDebugCausality.java"
    audit = ROOT / "app/src/main/java/com/rockmap/app/TourDebugSurfaceAudit.java"
    coach = ROOT / "app/src/main/java/com/rockmap/app/GuidedTourCoach.java"
    for path in (causality, audit, coach):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: text(path) for path in (causality, audit, coach)}
    try:
        patch_causality(causality)
        patch_surface_audit(audit)
        patch_coach_wait_lineage(coach)

        forbidden = [
            ".performClick(", ".setVisibility(", ".bringToFront(", ".requestLayout(",
            ".invalidate(", "GuidedTourState.setStep(", "FieldMapState.set",
            "MapHudCoordinator.beforeExpand("
        ]
        for path in (causality, audit):
            content = text(path)
            for token in forbidden:
                if token in content:
                    raise RuntimeError(
                        f"causal v2 scope guard failed: {path.relative_to(ROOT)} contains {token}"
                    )
        print("Causal debugger v2 refinement complete.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("Causal debugger v2 refinement rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
