#!/usr/bin/env python3
"""v10: complete the clean-map part of tour startup without deleting saved Field data.

Runs after v9. Saved Tracks, Prospecting Areas, Field Records and imports remain in the database;
only their map presentation flags are turned off before the first tour step.
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
    main = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    if not main.is_file():
        raise RuntimeError(f"required file missing: {main.relative_to(ROOT)}")
    original = text(main)
    try:
        replace_once(
            main,
            "v10-tour-start-hide-saved-field-overlays",
            '''        boolean fieldClean = FieldMapController.prepareTemporaryWorkspaceForTour(this, transition);
        // marker: v9-tour-start-full-close-applied
''',
            '''        // Saved Field objects survive, but a tour starts from a visually clean base map.
        // These are presentation flags only; no rows or geometry are deleted.
        FieldMapState.setTracksVisible(this, false);
        FieldMapState.setAreasVisible(this, false);
        FieldMapState.setFieldRecordsVisible(this, false);
        FieldMapState.setLabelsVisible(this, false);
        boolean fieldClean = FieldMapController.prepareTemporaryWorkspaceForTour(this, transition);
        // marker: v9-tour-start-full-close-applied
        // marker: v10-tour-start-hide-saved-field-overlays
''',
            "hide saved Field overlays for clean tour start",
        )

        replace_once(
            main,
            "v10-tour-clean-overlay-visibility-audit",
            '''                && FieldDatabase.get(this).getActiveTrack() == null;
        UiInvariantMonitor.invariant(this, transition, "tour_starts_from_clean_working_state", clean,
''',
            '''                && FieldDatabase.get(this).getActiveTrack() == null
                && !FieldMapState.tracksVisible(this)
                && !FieldMapState.areasVisible(this)
                && !FieldMapState.fieldRecordsVisible(this)
                && !FieldMapState.labelsVisible(this);
        // marker: v10-tour-clean-overlay-visibility-audit
        UiInvariantMonitor.invariant(this, transition, "tour_starts_from_clean_working_state", clean,
''',
            "audit saved Field overlay presentation is hidden",
        )

        replace_once(
            main,
            "v10-tour-clean-overlay-log-detail",
            '''                        + " nav=" + (FieldMapState.navigationTarget(this) != null));
        TourDebugLog.mainTourAction(this, "TOUR_CLEAN_SURFACE_AUDIT",
''',
            '''                        + " nav=" + (FieldMapState.navigationTarget(this) != null)
                        + " tracksVisible=" + FieldMapState.tracksVisible(this)
                        + " areasVisible=" + FieldMapState.areasVisible(this)
                        + " fieldRecordsVisible=" + FieldMapState.fieldRecordsVisible(this)
                        + " labelsVisible=" + FieldMapState.labelsVisible(this));
        // marker: v10-tour-clean-overlay-log-detail
        TourDebugLog.mainTourAction(this, "TOUR_CLEAN_SURFACE_AUDIT",
''',
            "log Field overlay visibility in clean-start audit",
        )

        # Strict destructive-operation scope guard around all v10 markers.
        current = text(main)
        for marker in (
            "v10-tour-start-hide-saved-field-overlays",
            "v10-tour-clean-overlay-visibility-audit",
            "v10-tour-clean-overlay-log-detail",
        ):
            pos = current.index(marker)
            window = current[max(0, pos - 1800):pos + 1800]
            for forbidden in ("deleteTrack(", "deleteArea(", "deleteFieldRecord(", "deleteWaypoint(", "deleteTrip("):
                if forbidden in window:
                    raise RuntimeError(f"v10 destructive scope guard failed near {marker}: {forbidden}")

        print("Tour-start v10 saved-overlay presentation reset complete.")
        return 0
    except Exception:
        main.write_text(original, encoding="utf-8")
        print("Tour-start v10 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
