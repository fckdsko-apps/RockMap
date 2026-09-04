#!/usr/bin/env python3
"""Corrective pass for HUD arbitration/render issues found in the first Commit-1 field test."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def replace_once(path: Path, marker: str, old: str, new: str, label: str) -> None:
    text = read(path)
    if marker in text:
        print(f"{label}: already present")
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match in {path.relative_to(ROOT)}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: injected")


def main() -> int:
    field = ROOT / "app/src/main/java/com/rockmap/app/field/FieldMapController.java"
    research = ROOT / "app/src/main/java/com/rockmap/app/research/ResearchAreaPanelController.java"
    context = ROOT / "app/src/main/java/com/rockmap/app/map/MapContextCloseController.java"
    main = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    required = [field, research, context, main]
    for path in required:
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: read(path) for path in required}
    try:
        # Research render verification needs the actual presentation root.
        replace_once(
            research,
            "v2-research-presentation-view",
            """    public boolean isExpanded() { return isVisible() && !isCollapsed(); }\n\n    /** Presentation-only arbitration: no listener callback, no tour advancement, no data change. */\n""",
            """    public boolean isExpanded() { return isVisible() && !isCollapsed(); }\n    public View getPresentationView() { return panel; } // marker: v2-research-presentation-view\n\n    /** Presentation-only arbitration: no listener callback, no tour advancement, no data change. */\n""",
            "Research presentation view",
        )

        # Every Field expansion path must pass through one low-level boundary. The first patch only
        # wrapped setExpandedTool(), while Measure/Nav/Track have legitimate direct setter paths.
        replace_once(
            field,
            "v2-field-low-level-arbitration",
            """    public static boolean isHudExpanded(Activity activity) {\n        FieldMapController controller = liveController(activity);\n        return controller != null && controller.expandedTool != null\n                && controller.hud != null && controller.hud.getVisibility() == View.VISIBLE;\n    }\n\n    /** Collapse Field presentation only; Track/Nav/Measure active state must remain identical. */\n""",
            """    public static boolean isHudExpanded(Activity activity) {\n        FieldMapController controller = liveController(activity);\n        return controller != null && controller.expandedTool != null\n                && controller.hud != null && controller.hud.getVisibility() == View.VISIBLE;\n    }\n\n    public static View hudPresentationView(Activity activity) {\n        FieldMapController controller = liveController(activity);\n        return controller == null ? null : controller.hud;\n    } // marker: v2-field-low-level-arbitration\n\n    /** Collapse Field presentation only; Track/Nav/Measure active state must remain identical. */\n""",
            "Field presentation view",
        )
        replace_once(
            field,
            "v2-field-setter-arbitration",
            """    private void setExpandedTool(String tool) {\n        long hudTransition = 0L;\n        if (tool != null && activity instanceof MainActivity) {\n            hudTransition = ((MainActivity) activity).beforeMapHudExpansion(\n                    MapHudCoordinator.SURFACE_FIELD);\n            if (hudTransition < 0L) return;\n        }\n        setExpandedToolValue(tool);\n        renderHud(\"tool_expanded\");\n        if (hudTransition > 0L) {\n            ((MainActivity) activity).afterMapHudExpansion(\n                    MapHudCoordinator.SURFACE_FIELD, hudTransition);\n        }\n        // marker: field-hud-expansion-arbitrated\n    }\n\n    private void setExpandedToolValue(String tool) {\n        expandedTool = tool;\n        FieldMapState.setExpandedTool(activity, tool);\n    }\n""",
            """    private void setExpandedTool(String tool) {\n        setExpandedToolValue(tool);\n        renderHud(\"tool_expanded\");\n    }\n\n    /** All intentional Field HUD ownership changes pass this boundary, including direct\n     * Measure/Navigate/Track paths that do not call setExpandedTool(). Restore-on-resume writes\n     * expandedTool directly and therefore remains observational rather than a fake user action. */\n    private void setExpandedToolValue(String tool) {\n        long hudTransition = 0L;\n        boolean newExpandedOwner = tool != null && !tool.equals(expandedTool);\n        if (newExpandedOwner && activity instanceof MainActivity) {\n            hudTransition = ((MainActivity) activity).beforeMapHudExpansion(\n                    MapHudCoordinator.SURFACE_FIELD);\n            if (hudTransition < 0L) return;\n        }\n        expandedTool = tool;\n        FieldMapState.setExpandedTool(activity, tool);\n        if (hudTransition > 0L && activity instanceof MainActivity) {\n            ((MainActivity) activity).afterMapHudExpansion(\n                    MapHudCoordinator.SURFACE_FIELD, hudTransition);\n        }\n        // marker: v2-field-setter-arbitration\n    }\n""",
            "Field low-level arbitration",
        )
        replace_once(
            field,
            "v2-field-tour-uses-setter",
            """        String tourExpandedTool = requiredExpandedToolForActiveTour();\n        if (tourExpandedTool != null && !tourExpandedTool.equals(expandedTool)) {\n            long hudTransition = activity instanceof MainActivity\n                    ? ((MainActivity) activity).beforeMapHudExpansion(MapHudCoordinator.SURFACE_FIELD)\n                    : 0L;\n            if (hudTransition >= 0L) {\n                expandedTool = tourExpandedTool;\n                FieldMapState.setExpandedTool(activity, tourExpandedTool);\n                if (hudTransition > 0L) {\n                    ((MainActivity) activity).afterMapHudExpansion(\n                            MapHudCoordinator.SURFACE_FIELD, hudTransition);\n                }\n            }\n            // marker: field-tour-forced-hud-arbitrated\n        }\n""",
            """        String tourExpandedTool = requiredExpandedToolForActiveTour();\n        if (tourExpandedTool != null && !tourExpandedTool.equals(expandedTool)) {\n            setExpandedToolValue(tourExpandedTool);\n            // marker: field-tour-forced-hud-arbitrated\n            // marker: v2-field-tour-uses-setter\n        }\n""",
            "Field tour expansion boundary",
        )

        # Field button is persistent, but an expanded Measure/Track/Nav HUD must win z-order.
        replace_once(
            field,
            "v2-field-hud-z-order",
            """    /** FieldMapController is the single z-order owner for Field controls. */\n    private void bringFieldUiToFront() {\n        if (controls != null) controls.bringToFront();\n        if (hud != null && hud.getVisibility() == View.VISIBLE) hud.bringToFront();\n        if (collapsedTabs != null && collapsedTabs.getVisibility() == View.VISIBLE) collapsedTabs.bringToFront();\n        if (fieldButton != null) fieldButton.bringToFront();\n    }\n""",
            """    /** FieldMapController is the single z-order owner for Field controls. */\n    private void bringFieldUiToFront() {\n        if (controls != null) controls.bringToFront();\n        if (fieldButton != null) fieldButton.bringToFront();\n        if (collapsedTabs != null && collapsedTabs.getVisibility() == View.VISIBLE) collapsedTabs.bringToFront();\n        if (hud != null && hud.getVisibility() == View.VISIBLE) hud.bringToFront();\n        // marker: v2-field-hud-z-order\n    }\n""",
            "Field HUD z-order",
        )
        replace_once(
            field,
            "v2-field-entry-respects-hud",
            """            if (controller.fieldButton != null) {\n                controller.fieldButton.setVisibility(View.VISIBLE);\n                controller.fieldButton.bringToFront();\n                // Temporary Research/context views can finish layout a frame later. Reassert the\n                // persistent Field entry after that layout settles so closing a heatmap/area cannot\n                // strand Field behind or leave it invisible.\n                controller.fieldButton.postDelayed(() -> {\n                    controller.positionFieldButtonNow();\n                    if (controller.fieldButton != null) {\n                        controller.fieldButton.setVisibility(View.VISIBLE);\n                        controller.fieldButton.bringToFront();\n                    }\n                }, 120L);\n            }\n""",
            """            if (controller.fieldButton != null) {\n                controller.fieldButton.setVisibility(View.VISIBLE);\n                controller.bringFieldUiToFront();\n                // Reassert the whole Field-family order after late Research/context layout.\n                controller.fieldButton.postDelayed(() -> {\n                    controller.positionFieldButtonNow();\n                    if (controller.fieldButton != null) {\n                        controller.fieldButton.setVisibility(View.VISIBLE);\n                        controller.bringFieldUiToFront();\n                    }\n                }, 120L);\n            }\n            // marker: v2-field-entry-respects-hud\n""",
            "Persistent Field entry z-order",
        )

        # Map-context reopen was recursively re-entering arbitration through refreshNow()->showMenu().
        replace_once(
            context,
            "v2-explicit-mapped-expansion-flag",
            """    private boolean transientlyHiddenForCamera;\n    private boolean menuUserPositioned;\n""",
            """    private boolean transientlyHiddenForCamera;\n    private boolean explicitExpansionInProgress; // marker: v2-explicit-mapped-expansion-flag\n    private boolean menuUserPositioned;\n""",
            "Mapped-context explicit expansion flag",
        )
        replace_once(
            context,
            "v2-no-nested-mapped-arbitration",
            """        boolean needsArbitration = !transientlyHiddenForCamera\n                && (menu == null || menu.getVisibility() != View.VISIBLE);\n""",
            """        boolean needsArbitration = !explicitExpansionInProgress\n                && !transientlyHiddenForCamera\n                && (menu == null || menu.getVisibility() != View.VISIBLE);\n        // marker: v2-no-nested-mapped-arbitration\n""",
            "Mapped-context nested arbitration guard",
        )
        replace_once(
            context,
            "v2-mapped-expansion-transaction",
            """        if (map != null) refreshNow();\n        else ensureViews();\n        notifyPresentationReady(menu);\n        if (hudTransition > 0L && activity instanceof MainActivity) {\n            ((MainActivity) activity).afterMapHudExpansion(\n                    MapHudCoordinator.SURFACE_MAPPED_CONTEXT, hudTransition);\n        }\n        // marker: mapped-context-user-expand-commit\n""",
            """        explicitExpansionInProgress = true;\n        try {\n            if (map != null) refreshNow();\n            else ensureViews();\n        } finally {\n            explicitExpansionInProgress = false;\n        }\n        notifyPresentationReady(menu);\n        if (hudTransition > 0L && activity instanceof MainActivity) {\n            ((MainActivity) activity).afterMapHudExpansion(\n                    MapHudCoordinator.SURFACE_MAPPED_CONTEXT, hudTransition);\n        }\n        // marker: mapped-context-user-expand-commit\n        // marker: v2-mapped-expansion-transaction\n""",
            "Mapped-context explicit expansion transaction",
        )

        # Render success is not the same as logical state. Verify a real frame and detect a
        # higher-z clickable sibling/descendant covering the expanded HUD.
        replace_once(
            main,
            "v2-hud-render-verification",
            """    public void afterMapHudExpansion(String surface, long transitionId) {\n        MapHudCoordinator.afterExpand(this, surface, transitionId, mapHudHost());\n    }\n""",
            """    private View mapHudPresentationView(String surface) {\n        if (MapHudCoordinator.SURFACE_FIELD.equals(surface)) {\n            return FieldMapController.hudPresentationView(this);\n        }\n        if (MapHudCoordinator.SURFACE_RESEARCH.equals(surface)) {\n            return researchAreaPanel == null ? null : researchAreaPanel.getPresentationView();\n        }\n        if (MapHudCoordinator.SURFACE_MAPPED_CONTEXT.equals(surface)) {\n            return mapView == null ? null : MapContextCloseController.forMap(mapView).getExpandedContainer();\n        }\n        return null;\n    }\n\n    private View findBlockingClickableDescendant(View candidate, Rect targetRect) {\n        if (candidate == null || !candidate.isShown() || candidate.getAlpha() <= 0.01f) return null;\n        Rect candidateRect = new Rect();\n        if (!candidate.getGlobalVisibleRect(candidateRect)) return null;\n        Rect overlap = new Rect(targetRect);\n        if (!overlap.intersect(candidateRect) || overlap.width() <= 0 || overlap.height() <= 0) return null;\n        if (candidate.isClickable() || candidate.isLongClickable()) return candidate;\n        if (candidate instanceof ViewGroup) {\n            ViewGroup group = (ViewGroup) candidate;\n            for (int i = group.getChildCount() - 1; i >= 0; i--) {\n                View blocker = findBlockingClickableDescendant(group.getChildAt(i), targetRect);\n                if (blocker != null) return blocker;\n            }\n        }\n        return null;\n    }\n\n    private View higherZHudBlocker(View target) {\n        if (target == null || !(target.getParent() instanceof ViewGroup)) return null;\n        Rect targetRect = new Rect();\n        if (!target.getGlobalVisibleRect(targetRect)) return null;\n        ViewGroup parent = (ViewGroup) target.getParent();\n        int targetIndex = parent.indexOfChild(target);\n        for (int i = parent.getChildCount() - 1; i > targetIndex; i--) {\n            View blocker = findBlockingClickableDescendant(parent.getChildAt(i), targetRect);\n            if (blocker != null) return blocker;\n        }\n        return null;\n    }\n\n    private String hudBlockerSummary(View blocker) {\n        if (blocker == null) return \"none\";\n        Object tag = blocker.getTag();\n        CharSequence desc = blocker.getContentDescription();\n        return blocker.getClass().getSimpleName()\n                + \" tag=\" + (tag == null ? \"\" : String.valueOf(tag))\n                + \" desc=\" + (desc == null ? \"\" : desc.toString());\n    }\n\n    public void afterMapHudExpansion(String surface, long transitionId) {\n        MapHudCoordinator.afterExpand(this, surface, transitionId, mapHudHost());\n        View target = mapHudPresentationView(surface);\n        UiInvariantMonitor.verifyNextFrame(this, transitionId,\n                \"hud-render-\" + surface, target,\n                () -> mapHudHost().isExpanded(surface),\n                () -> {\n                    View blocker = higherZHudBlocker(target);\n                    boolean unobscured = blocker == null;\n                    UiInvariantMonitor.invariant(MainActivity.this, transitionId,\n                            \"expanded_hud_not_blocked_by_higher_z_control\", unobscured,\n                            \"surface=\" + surface + \" blocker=\" + hudBlockerSummary(blocker));\n                    UiInvariantMonitor.state(MainActivity.this, transitionId,\n                            unobscured ? \"HUD_RENDER_COMMITTED\" : \"HUD_RENDER_BLOCKED\",\n                            \"surface=\" + surface + \" blocker=\" + hudBlockerSummary(blocker));\n                },\n                () -> UiInvariantMonitor.state(MainActivity.this, transitionId,\n                        \"HUD_RENDER_FAILED\", \"surface=\" + surface));\n        // marker: v2-hud-render-verification\n    }\n""",
            "HUD rendered-frame verification",
        )

        # The first Track instrumentation logged on every source application. Keep the same
        # evidence, but coalesce healthy repeats and explicitly flag an active line hidden by state.
        replace_once(
            field,
            "v2-track-map-log-fields",
            """    private String lastTrackPipelineSignature = \"\";\n    private long lastTrackPipelineLogElapsedMs;\n    private String areaJson = emptyCollection();\n""",
            """    private String lastTrackPipelineSignature = \"\";\n    private long lastTrackPipelineLogElapsedMs;\n    private String lastTrackMapRenderSignature = \"\";\n    private long lastTrackMapRenderLogElapsedMs; // marker: v2-track-map-log-fields\n    private String areaJson = emptyCollection();\n""",
            "Track map log fields",
        )
        replace_once(
            field,
            "v2-track-map-rate-limit-helper",
            """    private String buildAreaJson() {\n""",
            """    private void logTrackMapRenderState(Style style) {\n        if (style == null) return;\n        boolean sourcePresent = style.getSource(TRACK_SOURCE) != null;\n        boolean layerPresent = style.getLayer(TRACK_LAYER) != null;\n        boolean tracksVisible = FieldMapState.tracksVisible(activity);\n        String signature = sourcePresent + \":\" + layerPresent + \":\"\n                + lastActiveTrackDiagnosticId + \":\" + lastActiveTrackDiagnosticPoints + \":\"\n                + lastTrackDiagnosticFeatures + \":\" + tracksVisible + \":\"\n                + lastActiveTrackDiagnosticFeaturePresent + \":\" + lastActiveTrackDiagnosticHidden;\n        long now = SystemClock.elapsedRealtime();\n        long intervalMs = lastActiveTrackDiagnosticId > 0L ? 5000L : 30000L;\n        if (signature.equals(lastTrackMapRenderSignature)\n                && now - lastTrackMapRenderLogElapsedMs < intervalMs) return;\n        lastTrackMapRenderSignature = signature;\n        lastTrackMapRenderLogElapsedMs = now;\n        UiInvariantMonitor.track(\"TRACK_MAP_RENDER_STATE\",\n                \"source=\" + sourcePresent + \" layer=\" + layerPresent\n                        + \" activeTrackId=\" + lastActiveTrackDiagnosticId\n                        + \" activePoints=\" + lastActiveTrackDiagnosticPoints\n                        + \" geoJsonFeatures=\" + lastTrackDiagnosticFeatures\n                        + \" tracksVisible=\" + tracksVisible\n                        + \" activeFeature=\" + lastActiveTrackDiagnosticFeaturePresent\n                        + \" activeHidden=\" + lastActiveTrackDiagnosticHidden);\n        if (lastActiveTrackDiagnosticId > 0L && lastActiveTrackDiagnosticPoints >= 2\n                && (!tracksVisible || lastActiveTrackDiagnosticHidden)) {\n            UiInvariantMonitor.track(\"TRACK_ACTIVE_VISIBILITY_BLOCKED\",\n                    \"trackId=\" + lastActiveTrackDiagnosticId\n                            + \" points=\" + lastActiveTrackDiagnosticPoints\n                            + \" tracksVisible=\" + tracksVisible\n                            + \" activeHidden=\" + lastActiveTrackDiagnosticHidden);\n        } else if (lastActiveTrackDiagnosticPoints >= 2) {\n            boolean ready = sourcePresent && layerPresent && lastActiveTrackDiagnosticFeaturePresent;\n            UiInvariantMonitor.track(ready ? \"TRACK_RENDER_INVARIANT_OK\"\n                            : \"TRACK_RENDER_INVARIANT_FAIL\",\n                    \"activePoints=\" + lastActiveTrackDiagnosticPoints\n                            + \" activeFeature=\" + lastActiveTrackDiagnosticFeaturePresent\n                            + \" allFeatures=\" + lastTrackDiagnosticFeatures\n                            + \" source=\" + sourcePresent + \" layer=\" + layerPresent);\n        }\n        // marker: v2-track-map-rate-limit-helper\n    }\n\n    private String buildAreaJson() {\n""",
            "Track map log helper",
        )
        replace_once(
            field,
            "v2-track-map-rate-limited-call",
            """            setSource(style, TRACK_SOURCE, trackJson);\n            UiInvariantMonitor.track(\"TRACK_MAP_RENDER_STATE\",\n                    \"source=\" + (style.getSource(TRACK_SOURCE) != null)\n                            + \" layer=\" + (style.getLayer(TRACK_LAYER) != null)\n                            + \" activeTrackId=\" + lastActiveTrackDiagnosticId\n                            + \" activePoints=\" + lastActiveTrackDiagnosticPoints\n                            + \" geoJsonFeatures=\" + lastTrackDiagnosticFeatures\n                            + \" tracksVisible=\" + FieldMapState.tracksVisible(activity)\n                            + \" activeFeature=\" + lastActiveTrackDiagnosticFeaturePresent\n                            + \" activeHidden=\" + lastActiveTrackDiagnosticHidden);\n            if (lastActiveTrackDiagnosticPoints >= 2 && FieldMapState.tracksVisible(activity)\n                    && !lastActiveTrackDiagnosticHidden) {\n                boolean renderPipelineReady = style.getSource(TRACK_SOURCE) != null\n                        && style.getLayer(TRACK_LAYER) != null\n                        && lastActiveTrackDiagnosticFeaturePresent;\n                UiInvariantMonitor.track(\n                        renderPipelineReady\n                                ? \"TRACK_RENDER_INVARIANT_OK\" : \"TRACK_RENDER_INVARIANT_FAIL\",\n                        \"activePoints=\" + lastActiveTrackDiagnosticPoints\n                                + \" activeFeature=\" + lastActiveTrackDiagnosticFeaturePresent\n                                + \" allFeatures=\" + lastTrackDiagnosticFeatures\n                                + \" source=\" + (style.getSource(TRACK_SOURCE) != null)\n                                + \" layer=\" + (style.getLayer(TRACK_LAYER) != null));\n            }\n            setSource(style, AREA_SOURCE, areaJson);\n""",
            """            setSource(style, TRACK_SOURCE, trackJson);\n            logTrackMapRenderState(style);\n            setSource(style, AREA_SOURCE, areaJson);\n            // marker: v2-track-map-rate-limited-call\n""",
            "Track map log rate limit",
        )

        print("HUD corrective v2 injection complete.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("HUD corrective v2 injection rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
