#!/usr/bin/env python3
"""Causal debugger v8: identify the cleaned schema and make step watchdog callback-aware.

Diagnostic-only. Runs after v7 and changes only causal bookkeeping/watchdog evidence.
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
        "causal-v8-schema",
        '''    public static final String SCHEMA = "causal-v5"; // marker: causal-v4-schema
      // marker: causal-v5-schema
''',
        '''    public static final String SCHEMA = "causal-v8"; // marker: causal-v4-schema
      // marker: causal-v5-schema
      // marker: causal-v8-schema
''',
        "causal debugger schema v8",
    )

    replace_once(
        path,
        "causal-v8-pending-tour-callback-state",
        '''        volatile String preparation = "";
        volatile String targetLabel = "";
''',
        '''        volatile String preparation = "";
        volatile String targetLabel = "";
        volatile int pendingTourCallbacks;
        // marker: causal-v8-pending-tour-callback-state
''',
        "track pending tour callbacks per step",
    )

    replace_once(
        path,
        "causal-v8-pending-tour-callback-schedule",
        '''        final String parentId = schedulingCause == null ? "" : schedulingCause.id;
        // marker: causal-v7-scheduled-parent-fallback
        final String callbackId = "CB" + NEXT_CALLBACK.getAndIncrement();
''',
        '''        final String parentId = schedulingCause == null ? "" : schedulingCause.id;
        final long pendingStepSerial;
        synchronized (LOCK) {
            StepState live = currentStep;
            if (ORIGIN_TOUR.equals(empty(origin, ORIGIN_CALLBACK)) && live != null) {
                live.pendingTourCallbacks++;
                pendingStepSerial = live.serial;
            } else {
                pendingStepSerial = -1L;
            }
        }
        // marker: causal-v7-scheduled-parent-fallback
        // marker: causal-v8-pending-tour-callback-schedule
        final String callbackId = "CB" + NEXT_CALLBACK.getAndIncrement();
''',
        "mark scheduled tour callbacks pending",
    )

    replace_once(
        path,
        "causal-v8-pending-tour-callback-start",
        '''        return () -> {
            Cause previous = CURRENT.get();
''',
        '''        return () -> {
            if (pendingStepSerial > 0L) {
                synchronized (LOCK) {
                    StepState live = currentStep;
                    if (live != null && live.serial == pendingStepSerial
                            && live.pendingTourCallbacks > 0) {
                        live.pendingTourCallbacks--;
                    }
                }
            }
            // marker: causal-v8-pending-tour-callback-start
            Cause previous = CURRENT.get();
''',
        "clear pending callback when tour callback begins",
    )

    replace_once(
        path,
        "causal-v8-step-no-progress-pending-aware",
        '''            if (live.preparedElapsed == 0L && live.targetVerifiedElapsed == 0L
                    && live.coachRequestedElapsed == 0L) {
                finding(lastResumedActivity(), "WARNING", "STEP_NO_PROGRESS",
''',
        '''            if (live.preparedElapsed == 0L && live.targetVerifiedElapsed == 0L
                    && live.coachRequestedElapsed == 0L
                    && live.pendingTourCallbacks == 0) {
                // marker: causal-v8-step-no-progress-pending-aware
                finding(lastResumedActivity(), "WARNING", "STEP_NO_PROGRESS",
''',
        "suppress no-progress warning while a tour callback is pending",
    )

    replace_once(
        path,
        "causal-v8-step-no-progress-pending-detail",
        '''                        "step=" + live.step
                                + " thresholdMs=3500 elapsedMs="
''',
        '''                        "step=" + live.step
                                + " pendingTourCallbacks=" + live.pendingTourCallbacks
                                + " thresholdMs=3500 elapsedMs="
                                // marker: causal-v8-step-no-progress-pending-detail
''',
        "include callback state in no-progress warning",
    )

    replace_once(
        path,
        "causal-v8-hard-watchdog-pending-detail",
        '''                                + " coachRequested=" + (live.coachRequestedElapsed > 0L)
                                + " elapsedMs=" + Math.max(0L,
''',
        '''                                + " coachRequested=" + (live.coachRequestedElapsed > 0L)
                                + " pendingTourCallbacks=" + live.pendingTourCallbacks
                                + " elapsedMs=" + Math.max(0L,
                                // marker: causal-v8-hard-watchdog-pending-detail
''',
        "include pending callback count in hard watchdog",
    )


def main() -> int:
    causality = ROOT / "app/src/main/java/com/rockmap/app/TourDebugCausality.java"
    if not causality.is_file():
        raise RuntimeError(f"required file missing: {causality.relative_to(ROOT)}")

    original = text(causality)
    try:
        patch_causality(causality)
        injected = text(causality)
        for forbidden in (
            ".performClick(", ".setVisibility(", ".bringToFront(", ".requestLayout(",
            ".invalidate(", "GuidedTourState.setStep(", "GuidedTourState.advance(",
            "FieldMapState.set", "MapHudCoordinator.beforeExpand("
        ):
            if injected.count(forbidden) > original.count(forbidden):
                raise RuntimeError(f"causal v8 scope guard failed: introduced {forbidden}")
        print("Causal debugger v8 callback-aware watchdog complete.")
        return 0
    except Exception:
        causality.write_text(original, encoding="utf-8")
        print("Causal debugger v8 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
