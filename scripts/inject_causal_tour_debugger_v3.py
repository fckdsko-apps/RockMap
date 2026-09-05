#!/usr/bin/env python3
"""Causal debugger v3: explicit readiness decisions and severity-aware failure snapshots.

Diagnostic-only. Executes after causal debugger v1/v2 and does not change tour, HUD, map,
Research, Field, or application state. Any source-shape mismatch fails the tour-debug build.
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


def patch_main_readiness(path: Path) -> None:
    replace_once(
        path,
        "causal-v3-readiness-signature-field",
        '''    private String lastMappedResearchPrepLogKey = "";
''',
        '''    private String lastMappedResearchPrepLogKey = "";
    private String lastTourReadinessDebugSignature = ""; // marker: causal-v3-readiness-signature-field
''',
        "readiness decision signature state",
    )

    replace_once(
        path,
        "causal-v3-readiness-helper",
        '''    private void retryGuidedTourCoach(int expectedStep, long generation, int attempt) {
''',
        '''    private String tourDebugViewIdentity(View view) {
        if (view == null) return "none";
        return TourDebugSurfaceAudit.stableViewId(view) + "@"
                + Integer.toHexString(System.identityHashCode(view));
    }

    private void debugTourReadinessDecision(int expectedStep, int attempt,
                                            String decision, View requiredTarget) {
        MapContextCloseController context = mapView == null
                ? null : MapContextCloseController.forMap(mapView);
        View displayed = context == null ? null : context.getDisplayedContainer();
        View drag = context == null ? null : context.getDragControl();
        View collapse = context == null ? null : context.getCollapseControl();
        View collapsed = context == null ? null : context.getCollapsedControl();
        boolean targetRequired = guidedTourStepNeedsReadyTarget(expectedStep);
        boolean targetReady = tourTargetReady(requiredTarget);
        boolean mappedReady = mappedResearchPresentationReadyForTour(expectedStep);
        String signature = expectedStep + "|" + decision
                + "|target=" + tourDebugViewIdentity(requiredTarget)
                + "|targetReady=" + targetReady
                + "|displayed=" + tourDebugViewIdentity(displayed)
                + "|drag=" + tourDebugViewIdentity(drag)
                + "|collapse=" + tourDebugViewIdentity(collapse)
                + "|collapsed=" + tourDebugViewIdentity(collapsed)
                + "|mappedCollapsed=" + (context != null && context.isCollapsed())
                + "|mappedReady=" + mappedReady
                + "|frameVerified=" + mappedResearchFrameVerified
                + "|restore=" + researchSessionRestored
                + "|contentPending=" + researchSessionContentRestorePending;
        boolean milestone = attempt <= 3 || attempt % 25 == 0;
        if (signature.equals(lastTourReadinessDebugSignature) && !milestone) return;
        lastTourReadinessDebugSignature = signature;
        TourDebugLog.causalEvent("STEP_READINESS_DECISION",
                "step=" + expectedStep
                        + " attempt=" + attempt
                        + " decision=" + decision
                        + " targetRequired=" + targetRequired
                        + " targetReady=" + targetReady
                        + " mappedReady=" + mappedReady
                        + " mappedCollapsed=" + (context != null && context.isCollapsed())
                        + " frameArmed=" + mappedResearchFrameCheckArmed
                        + " frameVerified=" + mappedResearchFrameVerified
                        + " restoreReady=" + researchSessionRestored
                        + " skipRestoreOnce=" + skipSessionRestoreOnce
                        + " contentRestorePending=" + researchSessionContentRestorePending
                        + " requiredTargetId=" + tourDebugViewIdentity(requiredTarget)
                        + " displayedId=" + tourDebugViewIdentity(displayed)
                        + " dragId=" + tourDebugViewIdentity(drag)
                        + " collapseId=" + tourDebugViewIdentity(collapse)
                        + " collapsedId=" + tourDebugViewIdentity(collapsed)
                        + " requiredTarget={" + TourDebugSurfaceAudit.summary(requiredTarget) + "}"
                        + " displayed={" + TourDebugSurfaceAudit.summary(displayed) + "}"
                        + " drag={" + TourDebugSurfaceAudit.summary(drag) + "}"
                        + " collapse={" + TourDebugSurfaceAudit.summary(collapse) + "}"
                        + " collapsed={" + TourDebugSurfaceAudit.summary(collapsed) + "}");
    } // marker: causal-v3-readiness-helper

    private void retryGuidedTourCoach(int expectedStep, long generation, int attempt) {
''',
        "explicit readiness diagnostic helper",
    )

    replace_once(
        path,
        "causal-v3-restore-block-reason",
        '''        if (guidedTourStepNeedsResearchSessionRestore(expectedStep)
                && !researchSessionRestored && !skipSessionRestoreOnce) {
            retryGuidedTourCoach(expectedStep, generation, attempt);
            return;
        }
''',
        '''        if (guidedTourStepNeedsResearchSessionRestore(expectedStep)
                && !researchSessionRestored && !skipSessionRestoreOnce) {
            debugTourReadinessDecision(expectedStep, attempt,
                    "blocked:research-session-restore", null);
            // marker: causal-v3-restore-block-reason
            retryGuidedTourCoach(expectedStep, generation, attempt);
            return;
        }
''',
        "research restore readiness reason",
    )

    replace_once(
        path,
        "causal-v3-content-block-reason",
        '''        if (guidedTourStepNeedsRestoredResearchContent(expectedStep)
                && researchSessionContentRestorePending) {
            retryGuidedTourCoach(expectedStep, generation, attempt);
            return;
        }
''',
        '''        if (guidedTourStepNeedsRestoredResearchContent(expectedStep)
                && researchSessionContentRestorePending) {
            debugTourReadinessDecision(expectedStep, attempt,
                    "blocked:research-content-restore", null);
            // marker: causal-v3-content-block-reason
            retryGuidedTourCoach(expectedStep, generation, attempt);
            return;
        }
''',
        "research content readiness reason",
    )

    replace_once(
        path,
        "causal-v3-mapped-presentation-block-reason",
        '''        if (!mappedResearchPresentationReadyForTour(expectedStep)) {
            retryGuidedTourCoach(expectedStep, generation, attempt);
            return;
        }
        if (!ensureMappedResearchTourFrameVerified(expectedStep, generation, attempt)) return;
        View requiredTarget = guidedTourReadinessTarget(expectedStep);
''',
        '''        if (!mappedResearchPresentationReadyForTour(expectedStep)) {
            debugTourReadinessDecision(expectedStep, attempt,
                    "blocked:mapped-presentation", null);
            // marker: causal-v3-mapped-presentation-block-reason
            retryGuidedTourCoach(expectedStep, generation, attempt);
            return;
        }
        if (!ensureMappedResearchTourFrameVerified(expectedStep, generation, attempt)) {
            debugTourReadinessDecision(expectedStep, attempt,
                    "blocked:mapped-frame-verification", null);
            return;
        }
        View requiredTarget = guidedTourReadinessTarget(expectedStep);
''',
        "mapped presentation/frame readiness reasons",
    )

    replace_once(
        path,
        "causal-v3-required-target-decision",
        '''        View requiredTarget = guidedTourReadinessTarget(expectedStep);
        if (!guidedTourStepNeedsReadyTarget(expectedStep) || tourTargetReady(requiredTarget)) {
            showGuidedTourCoachForCurrentStep();
            return;
        }

        if (attempt >= 250) {
''',
        '''        View requiredTarget = guidedTourReadinessTarget(expectedStep);
        boolean requiredTargetNeeded = guidedTourStepNeedsReadyTarget(expectedStep);
        boolean requiredTargetReady = tourTargetReady(requiredTarget);
        if (!requiredTargetNeeded || requiredTargetReady) {
            debugTourReadinessDecision(expectedStep, attempt,
                    "ready:show-coach", requiredTarget);
            // marker: causal-v3-required-target-decision
            showGuidedTourCoachForCurrentStep();
            return;
        }
        debugTourReadinessDecision(expectedStep, attempt,
                "blocked:required-target", requiredTarget);

        if (attempt >= 250) {
''',
        "required-target readiness decision",
    )

    replace_once(
        path,
        "causal-v3-retry-exhausted-reason",
        '''        if (attempt >= 250) {
            // Keep the tour state intact rather than silently clearing it. Reassert permanent map
            // chrome; a real Research/session callback can schedule this same step again later.
            restorePermanentMapChrome();
            return;
        }
''',
        '''        if (attempt >= 250) {
            debugTourReadinessDecision(expectedStep, attempt,
                    "blocked:retry-exhausted", requiredTarget);
            // marker: causal-v3-retry-exhausted-reason
            // Keep the tour state intact rather than silently clearing it. Reassert permanent map
            // chrome; a real Research/session callback can schedule this same step again later.
            restorePermanentMapChrome();
            return;
        }
''',
        "retry exhaustion readiness reason",
    )


def patch_snapshot_priority(path: Path) -> None:
    replace_once(
        path,
        "causal-v3-error-snapshot-state",
        '''    private static long lastFailureSnapshotElapsed;
    private static String lastUserCauseId = "none";
''',
        '''    private static long lastFailureSnapshotElapsed;
    private static long lastErrorSnapshotElapsed; // marker: causal-v3-error-snapshot-state
    private static String lastUserCauseId = "none";
''',
        "severity-aware snapshot state",
    )

    replace_once(
        path,
        "causal-v3-severity-aware-snapshot-call",
        '''        requestFailureSnapshot(activity, code);
    }

    private static void requestFailureSnapshot(Activity activity, String reason) {
        final long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (now - lastFailureSnapshotElapsed < 500L) return;
            lastFailureSnapshotElapsed = now;
        }
''',
        '''        requestFailureSnapshot(activity, severity, code);
    }

    private static void requestFailureSnapshot(Activity activity, String severity, String reason) {
        final long now = SystemClock.elapsedRealtime();
        final boolean error = "ERROR".equalsIgnoreCase(empty(severity, ""));
        synchronized (LOCK) {
            if (error) {
                // A recent WARNING must never suppress the first snapshot for a real ERROR.
                if (now - lastErrorSnapshotElapsed < 500L) return;
                lastErrorSnapshotElapsed = now;
                lastFailureSnapshotElapsed = now;
            } else {
                if (now - lastFailureSnapshotElapsed < 500L) return;
                lastFailureSnapshotElapsed = now;
            }
        }
        // marker: causal-v3-severity-aware-snapshot-call
''',
        "severity-aware failure snapshot cooldown",
    )


def patch_retry_noise(path: Path) -> None:
    replace_once(
        path,
        "causal-v3-step-gate-milestones",
        '''    public static void stepGate(Activity activity, int step, int attempt, String stage) {
        TourDebugLog.causalEvent("STEP_GATE",
''',
        '''    public static void stepGate(Activity activity, int step, int attempt, String stage) {
        if (attempt > 3 && attempt % 25 != 0) return;
        // marker: causal-v3-step-gate-milestones
        TourDebugLog.causalEvent("STEP_GATE",
''',
        "retry step-gate milestone logging",
    )

    replace_once(
        path,
        "causal-v3-step-preparation-once",
        '''    public static void stepPreparation(Activity activity, int step, String preparation) {
        StepState live;
        synchronized (LOCK) {
            live = currentStep;
            if (live == null || live.step != step) return;
            live.preparedElapsed = SystemClock.elapsedRealtime();
            live.preparation = empty(preparation, "");
        }
        TourDebugLog.causalEvent("STEP_PREPARE",
''',
        '''    public static void stepPreparation(Activity activity, int step, String preparation) {
        StepState live;
        boolean shouldLog;
        synchronized (LOCK) {
            live = currentStep;
            if (live == null || live.step != step) return;
            String nextPreparation = empty(preparation, "");
            shouldLog = live.preparedElapsed == 0L || !nextPreparation.equals(live.preparation);
            if (live.preparedElapsed == 0L) live.preparedElapsed = SystemClock.elapsedRealtime();
            live.preparation = nextPreparation;
        }
        if (!shouldLog) return;
        // marker: causal-v3-step-preparation-once
        TourDebugLog.causalEvent("STEP_PREPARE",
''',
        "deduplicate repeated step-preparation logs",
    )


def main() -> int:
    main_activity = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    causality = ROOT / "app/src/main/java/com/rockmap/app/TourDebugCausality.java"
    for path in (main_activity, causality):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: text(path) for path in (main_activity, causality)}
    try:
        patch_main_readiness(main_activity)
        patch_snapshot_priority(causality)
        patch_retry_noise(causality)

        # This pass may keep private debugger bookkeeping, but may not manipulate application UI,
        # guided-tour state, HUD ownership, map state, or Field/Research data.
        injected_debugger = text(causality)
        forbidden_debugger = [
            ".performClick(", ".setVisibility(", ".bringToFront(", ".requestLayout(",
            ".invalidate(", "GuidedTourState.setStep(", "GuidedTourState.advance(",
            "FieldMapState.set", "MapHudCoordinator.beforeExpand("
        ]
        for token in forbidden_debugger:
            if token in injected_debugger:
                raise RuntimeError(f"causal v3 scope guard failed: debugger contains {token}")

        print("Causal debugger v3 readiness diagnostics complete.")
        print("Scope: decision logging/snapshots only; no application behavior repair.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("Causal debugger v3 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
