#!/usr/bin/env python3
"""v12: require the shared clean-start contract before every pending Field tour.

The global Field-tour picker and inline Field help both queue through TourStartCoordinator.
Training-area tours (Measure, Field Records, Prospecting Areas) previously branched into
training-location setup before MainActivity called prepareWorkspaceForTourStart().  This pass
moves the shared cleanup gate ahead of that branch without changing the proven cleanup itself.
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


def main() -> int:
    main_activity = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    if not main_activity.is_file():
        raise RuntimeError(f"required file missing: {main_activity.relative_to(ROOT)}")

    original = text(main_activity)
    try:
        # v11/v10 must already have installed the proven true-close cleanup contract.  v12 only
        # changes pending Field-tour ordering; it must never become a second cleanup implementation.
        for required in (
            "v9-tour-start-full-close-applied",
            "v10-tour-start-hide-saved-field-overlays",
        ):
            if required not in original:
                raise RuntimeError(
                    f"Field-tour clean-first v12 requires prior cleanup marker: {required}"
                )

        old = '''        TourStartCoordinator.PendingFieldTour pending =
                TourStartCoordinator.consumePendingFieldTour(this);
        if (pending == null) return;
        if (pending.trainingArea) {
            pendingTrainingFieldTool = pending.tool;
            pendingTrainingMainTopic = "";
            beginPendingTrainingLocationSetup();
            return;
        }
        if (!prepareWorkspaceForTourStart("field:" + pending.tool)) {
            TourStartCoordinator.queueFieldTour(this, pending.tool, false, pending.legacyLocal);
            return;
        }
        if (pending.legacyLocal) {
'''
        new = '''        TourStartCoordinator.PendingFieldTour pending =
                TourStartCoordinator.consumePendingFieldTour(this);
        if (pending == null) return;

        // Every Field-tour entry path, including training-area tours started from an inline ?,
        // must pass the exact same full-close contract before any tour-specific setup can reopen UI.
        TourDebugLog.mainTourAction(this, "TOUR_PENDING_FIELD_CLEANUP_REQUIRED",
                "tool=" + pending.tool + " training=" + pending.trainingArea
                        + " legacy=" + pending.legacyLocal);
        if (!prepareWorkspaceForTourStart("field:" + pending.tool)) {
            TourDebugLog.mainTourAction(this, "TOUR_PENDING_FIELD_CLEANUP_DEFERRED",
                    "tool=" + pending.tool + " training=" + pending.trainingArea
                            + " legacy=" + pending.legacyLocal);
            TourStartCoordinator.queueFieldTour(
                    this, pending.tool, pending.trainingArea, pending.legacyLocal);
            return;
        }
        TourDebugLog.mainTourAction(this, "TOUR_PENDING_FIELD_AFTER_CLEANUP",
                "tool=" + pending.tool + " training=" + pending.trainingArea
                        + " legacy=" + pending.legacyLocal + " clean=true");

        if (pending.trainingArea) {
            TourDebugLog.mainTourAction(this, "TOUR_TRAINING_SETUP_AFTER_CLEANUP",
                    "tool=" + pending.tool + " clean=true");
            pendingTrainingFieldTool = pending.tool;
            pendingTrainingMainTopic = "";
            beginPendingTrainingLocationSetup();
            return;
        }
        // marker: v12-all-pending-field-tours-clean-first
        if (pending.legacyLocal) {
'''
        replace_once(
            main_activity,
            "v12-all-pending-field-tours-clean-first",
            old,
            new,
            "all pending Field tours clean before training/legacy dispatch",
        )

        updated = text(main_activity)
        start = updated.index("public void onMapWorkspaceResumedForTour()")
        end = updated.index("@Override", start)
        block = updated[start:end]

        cleanup_pos = block.index('prepareWorkspaceForTourStart("field:" + pending.tool)')
        training_pos = block.index("if (pending.trainingArea)")
        if cleanup_pos > training_pos:
            raise RuntimeError("v12 ordering guard failed: training branch still precedes cleanup")
        if "pending.tool, pending.trainingArea, pending.legacyLocal" not in block:
            raise RuntimeError("v12 requeue guard failed: pending tour flags are not preserved")
        if 'TOUR_PENDING_FIELD_AFTER_CLEANUP' not in block or 'TOUR_TRAINING_SETUP_AFTER_CLEANUP' not in block:
            raise RuntimeError("v12 diagnostic guard failed: clean-first causal markers missing")

        # This patch is routing-only. Saved entities and the known-good HUD lifecycle are forbidden
        # from being modified in this neighborhood.
        for forbidden in (
            "deleteTrack(", "deleteArea(", "deleteFieldRecord(", "deleteWaypoint(",
            "deleteTrip(", "collapsePresentationOnly(", "closePresentationForTourStart(",
            "MAPPED_HUD_FAST_REOPEN", "RESEARCH_TOUR_STEP17_HANDOFF",
        ):
            if forbidden in block:
                raise RuntimeError(f"v12 scope guard failed: unexpected token {forbidden}")

        print("Field-tour v12 clean-first routing complete.")
        print("Training-area setup now starts only after the shared full-close contract passes.")
        return 0
    except Exception:
        main_activity.write_text(original, encoding="utf-8")
        print("Field-tour v12 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
