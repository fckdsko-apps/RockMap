#!/usr/bin/env python3
"""Current-test v6: make Step 17 explicitly hand Research presentation to mapped controls."""
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
        replace_once(
            main_activity,
            "v6-step17-research-to-mapped-handoff",
            """    private void prepareResearchWorkspaceTourPresentation(int step) {\n        if (researchAreaPanel == null) return;\n        if (step == GuidedTourState.STEP_WORKSPACE_REOPEN) {\n            if (!researchAreaPanel.isCollapsed()) {\n                researchAreaPanel.collapsePresentationOnly();\n                saveResearchSession();\n                TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STEP16_NORMALIZED\",\n                        \"state=collapsed reopenTargetPending=true\");\n            }\n        } else if (step == GuidedTourState.STEP_WORKSPACE_COLLAPSE\n                && researchAreaPanel.isCollapsed()) {\n            researchAreaPanel.restoreMode(ResearchAreaPanelController.MODE_EXPANDED);\n            saveResearchSession();\n            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STEP15_NORMALIZED\",\n                    \"state=expanded collapseTargetPending=true\");\n        }\n    } // marker: v4-step16-presentation-normalizer\n""",
            """    private void prepareResearchWorkspaceTourPresentation(int step) {\n        if (researchAreaPanel == null) return;\n        if (step == GuidedTourState.STEP_WORKSPACE_REOPEN) {\n            if (!researchAreaPanel.isCollapsed()) {\n                researchAreaPanel.collapsePresentationOnly();\n                saveResearchSession();\n                TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STEP16_NORMALIZED\",\n                        \"state=collapsed reopenTargetPending=true\");\n            }\n        } else if (step == GuidedTourState.STEP_WORKSPACE_COLLAPSE\n                && researchAreaPanel.isCollapsed()) {\n            researchAreaPanel.restoreMode(ResearchAreaPanelController.MODE_EXPANDED);\n            saveResearchSession();\n            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STEP15_NORMALIZED\",\n                    \"state=expanded collapseTargetPending=true\");\n        } else if (step == GuidedTourState.STEP_CONTEXT_CONTROLS\n                && researchAreaPanel.isExpanded()) {\n            // Step 16 deliberately teaches reopening Research. Step 17 immediately teaches the\n            // separate mapped-controls HUD, so hand presentation ownership over without closing\n            // the Research session or changing any mapped data/map position.\n            boolean collapsed = researchAreaPanel.collapsePresentationOnly();\n            saveResearchSession();\n            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STEP17_HANDOFF\",\n                    \"researchCollapsed=\" + collapsed + \" nextOwner=mapped-context\");\n        }\n    } // marker: v4-step16-presentation-normalizer\n      // marker: v6-step17-research-to-mapped-handoff\n""",
            "Step 17 Research-to-mapped HUD handoff",
        )

        updated = text(main_activity)
        forbidden = (
            "GuidedTourState.setStep(",
            "FieldMapState.set",
            "deleteTrack(",
            "deleteArea(",
            "stopNavigation(",
            "clearMeasurement("
        )
        inserted = updated[updated.index("private void prepareResearchWorkspaceTourPresentation"):
                           updated.index("private void prepareMappedResearchTourPresentation")]
        for token in forbidden:
            if token in inserted:
                raise RuntimeError(f"scope guard failed: Step 17 handoff unexpectedly contains {token}")

        print("HUD/tour corrective v6 Step-17 handoff injection complete.")
        return 0
    except Exception:
        main_activity.write_text(original, encoding="utf-8")
        print("HUD/tour corrective v6 injection rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
