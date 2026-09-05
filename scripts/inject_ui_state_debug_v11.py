#!/usr/bin/env python3
"""Compatibility wrapper for v9 cleanup on the post-v4/v7/v8 generated HUD source.

The v4 Research collapse method intentionally contains peer-control cleanup. v9's original anchor
predated that change. This wrapper preserves the v4 method byte-for-byte, adds the new tour-start
full-close API after it, then applies the rest of v9 unchanged.
"""
from pathlib import Path
import inject_ui_state_debug_v9 as v9

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


def patch_research_post_v4(research: Path) -> None:
    old = '''    public boolean collapsePresentationOnly() {
        ensurePanel();
        if (MODE_HIDDEN.equals(mode) || MODE_COLLAPSED.equals(mode)) {
            FieldMapController.clearPeerControlCollision(activity);
            return true;
        }
        mode = MODE_COLLAPSED;
        renderMode();
        FieldMapController.clearPeerControlCollision(activity);
        // marker: v4-clear-peer-collision-on-arbitration-collapse
        return isCollapsed();
    }
'''
    new = old + '''
    /** Tour-start cleanup is a true close, not a minimized workspace. No listener/tour callback. */
    public boolean closePresentationForTourStart() {
        ensurePanel();
        mode = MODE_HIDDEN;
        renderMode();
        FieldMapController.clearPeerControlCollision(activity);
        return panel == null || panel.getVisibility() != View.VISIBLE;
    } // marker: v9-research-full-close-for-tour
'''
    replace_once(
        research,
        "v9-research-full-close-for-tour",
        old,
        new,
        "Research full-close API preserving v4 arbitration cleanup",
    )


def main() -> int:
    coordinator = ROOT / "app/src/main/java/com/rockmap/app/TourStartCoordinator.java"
    main_activity = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    context = ROOT / "app/src/main/java/com/rockmap/app/map/MapContextCloseController.java"
    research = ROOT / "app/src/main/java/com/rockmap/app/research/ResearchAreaPanelController.java"
    prospecting = ROOT / "app/src/main/java/com/rockmap/app/field/ProspectingAreaCreator.java"
    field = ROOT / "app/src/main/java/com/rockmap/app/field/FieldMapController.java"
    required = (coordinator, main_activity, context, research, prospecting, field)
    for path in required:
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: text(path) for path in required}
    try:
        v9.patch_prompt(coordinator)
        patch_research_post_v4(research)
        v9.patch_mapped_context(context)
        v9.patch_prospecting_prompt(prospecting)
        v9.patch_field_visibility(field)
        v9.patch_main_activity(main_activity)

        # Guard the new tour-cleanup neighborhoods against saved-entity deletion.
        merged = "\n".join(text(path) for path in required)
        for marker in (
            "v9-research-full-close-for-tour",
            "v9-mapped-full-close-for-tour",
            "v9-tour-start-full-close-applied",
            "v9-tour-clean-invariant-expanded",
        ):
            pos = merged.find(marker)
            if pos < 0:
                raise RuntimeError(f"required v9 marker missing after compatibility injection: {marker}")
            window = merged[max(0, pos - 2200):pos + 2200]
            for forbidden in (
                "deleteTrack(", "deleteArea(", "deleteFieldRecord(",
                "deleteWaypoint(", "deleteTrip("
            ):
                if forbidden in window:
                    raise RuntimeError(f"v11 destructive scope guard failed near {marker}: {forbidden}")

        print("Tour-start v11 compatibility cleanup complete.")
        print("Preserved v4/v7/v8 HUD lifecycle behavior; added v9 full-close contract only.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("Tour-start v11 compatibility cleanup rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
