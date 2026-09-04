#!/usr/bin/env python3
"""
Inject guided-tour diagnostics into the checked-out tour-debug build.

The tour-debug branch may now contain some diagnostics directly in source. This injector is
therefore deliberately idempotent: if a requested hook is already present, it leaves it alone;
otherwise it applies the known baseline replacement exactly once. All edits remain runner-only.
"""
from pathlib import Path
import sys

from inject_ui_state_debug import inject_ui_state_fixes

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/rockmap/app/RockMapApplication.java"
COACH = ROOT / "app/src/main/java/com/rockmap/app/GuidedTourCoach.java"


def ensure_replace(path: Path, marker: str, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"{label}: already present")
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one source match in {path.relative_to(ROOT)}, found {count}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: injected")


def main() -> int:
    ensure_replace(
        APP,
        "TourDebugLog.install(this);",
        """    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }
""",
        """    @Override public void onCreate() {
        super.onCreate();
        TourDebugLog.install(this);
        registerActivityLifecycleCallbacks(this);
    }
""",
        "install TourDebugLog",
    )

    ensure_replace(
        COACH,
        "TourDebugLog.coachClear(activity);",
        """    public static void clear(Activity activity) {
        cancelPendingRequest(activity);
""",
        """    public static void clear(Activity activity) {
        TourDebugLog.coachClear(activity);
        cancelPendingRequest(activity);
""",
        "log coach clear",
    )

    ensure_replace(
        COACH,
        "TourDebugLog.coachRequest(activity, requestGeneration, step, total",
        """        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        long requestGeneration = beginPendingRequest(activity);
""",
        """        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            TourDebugLog.coachAbort(activity, -1L, step, "invalid activity at show()", target);
            return;
        }
        long requestGeneration = beginPendingRequest(activity);
        TourDebugLog.coachRequest(activity, requestGeneration, step, total,
                title, requiredAction, target);
""",
        "log coach request",
    )

    ensure_replace(
        COACH,
        "TourDebugLog.coachWait(activity, requestGeneration, step, target);",
        """        if (target != null && !targetReady(target)) {
            requestTargetVisibility(target);
""",
        """        if (target != null && !targetReady(target)) {
            TourDebugLog.coachWait(activity, requestGeneration, step, target);
            requestTargetVisibility(target);
""",
        "log target wait start",
    )

    ensure_replace(
        COACH,
        '"claim-before-show"',
        """        if (!claimPendingRequest(activity, requestGeneration)) return;
""",
        """        if (!claimPendingRequest(activity, requestGeneration)) {
            TourDebugLog.coachSuperseded(activity, requestGeneration, step,
                    "claim-before-show", target);
            return;
        }
""",
        "log failed coach claim",
    )

    ensure_replace(
        COACH,
        '"activity content is not FrameLayout"',
        """            ViewGroup content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            root = (FrameLayout) content;
""",
        """            ViewGroup content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) {
                TourDebugLog.coachAbort(activity, requestGeneration, step,
                        "activity content is not FrameLayout", target);
                return;
            }
            root = (FrameLayout) content;
""",
        "log invalid coach root",
    )

    ensure_replace(
        COACH,
        "TourDebugLog.coachShown(activity, requestGeneration, step, total",
        """        card.placeForCurrentStep();
        highlight(target);
        if (target != null) {
""",
        """        card.placeForCurrentStep();
        highlight(target);
        TourDebugLog.coachShown(activity, requestGeneration, step, total,
                title, target, root instanceof DialogCoachHost);
        if (target != null) {
""",
        "log coach shown",
    )

    # Newer tour-debug source already has generation-aware wait termination. Preserve it instead
    # of forcing the older combined guard back into the file.
    coach_text = COACH.read_text(encoding="utf-8")
    if ("TourDebugLog.coachTimeout(activity, generation, step, target);" in coach_text
            and '"target-wait"' in coach_text):
        print("log wait termination: already present")
    else:
        ensure_replace(
            COACH,
            '"before wait schedule"',
            """        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || target == null || !isPendingRequestCurrent(activity, generation) || attempt >= 250) return;
        View scheduler = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
""",
            """        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            TourDebugLog.coachAbort(activity, generation, step,
                    "invalid activity while waiting", target);
            return;
        }
        if (target == null) {
            TourDebugLog.coachAbort(activity, generation, step,
                    "target became null while waiting", null);
            return;
        }
        if (!isPendingRequestCurrent(activity, generation)) {
            TourDebugLog.coachSuperseded(activity, generation, step,
                    "before wait schedule", target);
            return;
        }
        if (attempt >= 250) {
            TourDebugLog.coachTimeout(activity, generation, step, target);
            return;
        }
        View scheduler = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
""",
            "log wait termination",
        )

    ensure_replace(
        COACH,
        '"inside wait callback"',
        """        scheduler.postDelayed(() -> {
            if (!isPendingRequestCurrent(activity, generation)
                    || activity.isFinishing() || activity.isDestroyed()) return;
            View liveTarget = resolveEquivalentTarget(activity, target);
""",
        """        scheduler.postDelayed(() -> {
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
""",
        "log wait callback termination",
    )

    ensure_replace(
        COACH,
        "TourDebugLog.coachTargetReady(activity, generation, step",
        """            if (targetReady(liveTarget)) {
                show(activity, hostRoot, step, total, title, message, requiredAction, liveTarget,
""",
        """            if (targetReady(liveTarget)) {
                TourDebugLog.coachTargetReady(activity, generation, step,
                        attempt + 1, liveTarget);
                show(activity, hostRoot, step, total, title, message, requiredAction, liveTarget,
""",
        "log target ready",
    )

    coach_text = COACH.read_text(encoding="utf-8")
    if "TourDebugLog.coachWaitProgress(activity, generation, step" in coach_text:
        print("log target wait progress: already present")
    else:
        ensure_replace(
            COACH,
            "TourDebugLog.coachWaitProgress(activity, generation, step",
            """            } else {
                waitForTargetAndShow(activity, hostRoot, step, total, title, message,
                        requiredAction, liveTarget, backAction, primaryLabel, primaryAction,
                        skipAction, exitAction, generation, attempt + 1);
""",
            """            } else {
                int nextAttempt = attempt + 1;
                if (nextAttempt == 25 || nextAttempt == 100 || nextAttempt == 200) {
                    TourDebugLog.coachWaitProgress(activity, generation, step,
                            nextAttempt, liveTarget);
                }
                waitForTargetAndShow(activity, hostRoot, step, total, title, message,
                        requiredAction, liveTarget, backAction, primaryLabel, primaryAction,
                        skipAction, exitAction, generation, nextAttempt);
""",
            "log target wait progress",
        )

    # Commit-1 additions: HUD exclusivity, safe tour start/cleanup, Step-17 render barrier and
    # invariant/Track-pipeline instrumentation.  This remains runner-only because this script is
    # invoked only by the tour-debug workflow.
    inject_ui_state_fixes(ROOT)

    print("Tour debugger injection complete.")
    print("Runner-only instrumentation is compatible with source-level tour diagnostics.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Tour debugger injection failed: {exc}", file=sys.stderr)
        raise
