#!/usr/bin/env python3
"""Post-v2 timing fix: Field arbitration is asynchronous, so verify only after HUD_READY."""
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
    field = ROOT / "app/src/main/java/com/rockmap/app/field/FieldMapController.java"
    main_activity = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    for path in (field, main_activity):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {field: text(field), main_activity: text(main_activity)}
    try:
        replace_once(
            field,
            "v3-field-pending-render-state",
            """    private long lastTrackMapRenderLogElapsedMs; // marker: v2-track-map-log-fields
    private String areaJson = emptyCollection();
""",
            """    private long lastTrackMapRenderLogElapsedMs; // marker: v2-track-map-log-fields
    private boolean pendingFieldHudRenderVerification;
    private String pendingFieldHudRenderTool = ""; // marker: v3-field-pending-render-state
    private String areaJson = emptyCollection();
""",
            "Field pending render state",
        )

        replace_once(
            field,
            "v3-field-async-arbitration",
            """    private void setExpandedTool(String tool) {
        setExpandedToolValue(tool);
        renderHud("tool_expanded");
    }

    /** All intentional Field HUD ownership changes pass this boundary, including direct
     * Measure/Navigate/Track paths that do not call setExpandedTool(). Restore-on-resume writes
     * expandedTool directly and therefore remains observational rather than a fake user action. */
    private void setExpandedToolValue(String tool) {
        long hudTransition = 0L;
        boolean newExpandedOwner = tool != null && !tool.equals(expandedTool);
        if (newExpandedOwner && activity instanceof MainActivity) {
            hudTransition = ((MainActivity) activity).beforeMapHudExpansion(
                    MapHudCoordinator.SURFACE_FIELD);
            if (hudTransition < 0L) return;
        }
        expandedTool = tool;
        FieldMapState.setExpandedTool(activity, tool);
        if (hudTransition > 0L && activity instanceof MainActivity) {
            ((MainActivity) activity).afterMapHudExpansion(
                    MapHudCoordinator.SURFACE_FIELD, hudTransition);
        }
        // marker: v2-field-setter-arbitration
    }
""",
            """    private void setExpandedTool(String tool) {
        setExpandedToolValue(tool);
        renderHud("tool_expanded");
    }

    /** All intentional Field HUD ownership changes pass this boundary. Field rendering is
     * asynchronous, so reserve the slot without retaining a coordinator transition across layout
     * or GPS callbacks; verify the real presentation only after HUD_READY. */
    private void setExpandedToolValue(String tool) {
        boolean newExpandedOwner = tool != null && !tool.equals(expandedTool);
        if (newExpandedOwner && activity instanceof MainActivity) {
            boolean prepared = ((MainActivity) activity).prepareForUpcomingMapHudExpansion(
                    MapHudCoordinator.SURFACE_FIELD);
            if (!prepared) return;
            pendingFieldHudRenderVerification = true;
            pendingFieldHudRenderTool = tool;
        }
        expandedTool = tool;
        FieldMapState.setExpandedTool(activity, tool);
        // marker: v2-field-setter-arbitration
        // marker: v3-field-async-arbitration
    }
""",
            "Field asynchronous arbitration",
        )

        replace_once(
            field,
            "v3-field-render-verify-at-ready",
            """        TourDebugLog.hudLifecycle(activity, "HUD_READY", renderGeneration, reason,
                expandedTool, measurement.size(), hud, requiredTarget, renderStarted);
        showActiveMapFieldTourCoach();
""",
            """        TourDebugLog.hudLifecycle(activity, "HUD_READY", renderGeneration, reason,
                expandedTool, measurement.size(), hud, requiredTarget, renderStarted);
        if (pendingFieldHudRenderVerification && activity instanceof MainActivity
                && expandedTool != null && hud != null && hud.getVisibility() == View.VISIBLE) {
            String verifiedTool = pendingFieldHudRenderTool;
            pendingFieldHudRenderVerification = false;
            pendingFieldHudRenderTool = "";
            ((MainActivity) activity).verifyMapHudPresentation(
                    MapHudCoordinator.SURFACE_FIELD, hud, "tool=" + verifiedTool);
        }
        // marker: v3-field-render-verify-at-ready
        showActiveMapFieldTourCoach();
""",
            "Field HUD_READY verification",
        )

        replace_once(
            main_activity,
            "v3-field-render-api",
            """    public void afterMapHudExpansion(String surface, long transitionId) {
        MapHudCoordinator.afterExpand(this, surface, transitionId, mapHudHost());
        View target = mapHudPresentationView(surface);
""",
            """    public boolean prepareForUpcomingMapHudExpansion(String surface) {
        return MapHudCoordinator.prepareForUpcoming(this, surface, mapHudHost());
    }

    public void verifyMapHudPresentation(String surface, View target, String detail) {
        final String safeDetail = detail == null ? "" : detail;
        long transitionId = UiInvariantMonitor.begin(this, "hud-render-verify",
                "surface=" + surface + " " + safeDetail);
        UiInvariantMonitor.verifyNextFrame(this, transitionId,
                "hud-render-" + surface, target,
                () -> mapHudHost().isExpanded(surface),
                () -> {
                    View blocker = higherZHudBlocker(target);
                    boolean unobscured = blocker == null;
                    UiInvariantMonitor.invariant(MainActivity.this, transitionId,
                            "expanded_hud_not_blocked_by_higher_z_control", unobscured,
                            "surface=" + surface + " blocker=" + hudBlockerSummary(blocker)
                                    + " " + safeDetail);
                    UiInvariantMonitor.state(MainActivity.this, transitionId,
                            unobscured ? "HUD_RENDER_COMMITTED" : "HUD_RENDER_BLOCKED",
                            "surface=" + surface + " blocker=" + hudBlockerSummary(blocker)
                                    + " " + safeDetail);
                },
                () -> UiInvariantMonitor.state(MainActivity.this, transitionId,
                        "HUD_RENDER_FAILED", "surface=" + surface + " " + safeDetail));
    }

    // marker: v3-field-render-api
    public void afterMapHudExpansion(String surface, long transitionId) {
        MapHudCoordinator.afterExpand(this, surface, transitionId, mapHudHost());
        View target = mapHudPresentationView(surface);
""",
            "MainActivity Field render API",
        )

        print("HUD corrective v3 timing injection complete.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("HUD corrective v3 injection rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
