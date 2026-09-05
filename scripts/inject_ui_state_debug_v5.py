#!/usr/bin/env python3
"""Current-test v5: explicit HUD ownership, stable mapped-control placement, and rail separation."""
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


def replace_count(path: Path, marker: str, old: str, new: str, expected: int, label: str) -> None:
    current = text(path)
    if marker in current:
        print(f"{label}: already present")
        return
    count = current.count(old)
    if count != expected:
        raise RuntimeError(
            f"{label}: expected {expected} matches in {path.relative_to(ROOT)}, found {count}"
        )
    path.write_text(current.replace(old, new), encoding="utf-8")
    print(f"{label}: injected ({count})")


def main() -> int:
    main_activity = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    context = ROOT / "app/src/main/java/com/rockmap/app/map/MapContextCloseController.java"
    field = ROOT / "app/src/main/java/com/rockmap/app/field/FieldMapController.java"
    required = [main_activity, context, field]
    for path in required:
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: text(path) for path in required}
    try:
        # Explicit Field ownership wins over background Step-17 mapped prep. Reservation starts
        # before prepareForUpcoming() collapses the old HUD, closing the zero-owner callback race.
        replace_once(
            main_activity,
            "v5-explicit-hud-reservation-field",
            """    private int mappedResearchFrameRecoveryCount;\n\n    @Override\n""",
            """    private int mappedResearchFrameRecoveryCount;\n    private String pendingExplicitHudSurface = \"\"; // marker: v5-explicit-hud-reservation-field\n\n    @Override\n""",
            "explicit HUD reservation field",
        )

        replace_once(
            main_activity,
            "v5-explicit-hud-reservation-api",
            """    public boolean prepareForUpcomingMapHudExpansion(String surface) {\n        return MapHudCoordinator.prepareForUpcoming(this, surface, mapHudHost());\n    }\n\n""",
            """    private void releaseExplicitHudReservation(String surface, String outcome) {\n        String requested = surface == null ? \"\" : surface;\n        if (requested.isEmpty() || !requested.equals(pendingExplicitHudSurface)) return;\n        pendingExplicitHudSurface = \"\";\n        TourDebugLog.mainTourAction(this, \"HUD_EXPLICIT_OWNERSHIP_RELEASED\",\n                \"surface=\" + requested + \" outcome=\" + (outcome == null ? \"\" : outcome));\n    }\n\n    public boolean prepareForUpcomingMapHudExpansion(String surface) {\n        String requested = surface == null ? \"\" : surface;\n        if (requested.isEmpty()) return false;\n        pendingExplicitHudSurface = requested;\n        TourDebugLog.mainTourAction(this, \"HUD_EXPLICIT_OWNERSHIP_RESERVED\",\n                \"surface=\" + requested + \" occupied=\" + MapHudCoordinator.expandedSummary(mapHudHost()));\n        boolean prepared = MapHudCoordinator.prepareForUpcoming(this, requested, mapHudHost());\n        if (!prepared) releaseExplicitHudReservation(requested, \"prepare-failed\");\n        return prepared;\n    } // marker: v5-explicit-hud-reservation-api\n\n""",
            "explicit HUD reservation API",
        )

        replace_once(
            main_activity,
            "v5-explicit-owner-supersedes-stale-reservation",
            """        // marker: v4-clear-peer-reflow-before-hud-switch\n        // Steps before the mapped-controls lesson need the Research workspace itself. Keep mapped\n""",
            """        // marker: v4-clear-peer-reflow-before-hud-switch\n        if (pendingExplicitHudSurface != null && !pendingExplicitHudSurface.isEmpty()\n                && surface != null && !pendingExplicitHudSurface.equals(surface)) {\n            releaseExplicitHudReservation(pendingExplicitHudSurface,\n                    \"superseded-by-explicit-\" + surface);\n        }\n        // marker: v5-explicit-owner-supersedes-stale-reservation\n        // Steps before the mapped-controls lesson need the Research workspace itself. Keep mapped\n""",
            "explicit HUD supersedes stale reservation",
        )

        replace_once(
            main_activity,
            "v5-implicit-never-steals-explicit-owner",
            """    public long beforeImplicitMapHudExpansion(String surface) {\n        // Implicit chrome never steals an explicitly occupied HUD slot. It participates in the\n        // coordinator by yielding to the current surface. Step 17+ is an explicit tour-directed\n        // presentation and therefore uses the normal arbitration rule.\n        if (!(GuidedTourState.isActive(this)\n                && GuidedTourState.step(this) >= GuidedTourState.STEP_CONTEXT_CONTROLS)\n                && MapHudCoordinator.expandedCount(mapHudHost()) > 0) {\n            TourDebugLog.mainTourAction(this, \"HUD_IMPLICIT_EXPANSION_DEFERRED\",\n                    \"surface=\" + surface + \" occupied=\"\n                            + MapHudCoordinator.expandedSummary(mapHudHost()));\n            return -1L;\n        }\n        return beforeMapHudExpansion(surface);\n    }\n""",
            """    public long beforeImplicitMapHudExpansion(String surface) {\n        String requested = surface == null ? \"\" : surface;\n        String pending = pendingExplicitHudSurface == null ? \"\" : pendingExplicitHudSurface;\n        String occupied = MapHudCoordinator.expandedSummary(mapHudHost());\n\n        // Background chrome/tour preparation may fill an empty slot, but it may never interrupt\n        // an explicit user-requested HUD. This reservation begins before Field collapses the old\n        // mapped HUD, so the collapse callback cannot exploit the few-ms zero-owner window.\n        if (!pending.isEmpty() && !pending.equals(requested)) {\n            TourDebugLog.mainTourAction(this, \"HUD_IMPLICIT_EXPANSION_DEFERRED\",\n                    \"surface=\" + requested + \" reason=pending-explicit pending=\" + pending\n                            + \" occupied=\" + occupied);\n            return -1L;\n        }\n        if (MapHudCoordinator.expandedCount(mapHudHost()) > 0\n                && !mapHudHost().isExpanded(requested)) {\n            TourDebugLog.mainTourAction(this, \"HUD_IMPLICIT_EXPANSION_DEFERRED\",\n                    \"surface=\" + requested + \" reason=explicit-owner occupied=\" + occupied\n                            + \" pending=\" + pending);\n            return -1L;\n        }\n        return beforeMapHudExpansion(requested);\n    } // marker: v5-implicit-never-steals-explicit-owner\n""",
            "implicit HUD ownership policy",
        )

        replace_once(
            main_activity,
            "v5-release-reservation-after-real-frame",
            """    public void verifyMapHudPresentation(String surface, View target, String detail) {\n        final String safeDetail = detail == null ? \"\" : detail;\n        long transitionId = UiInvariantMonitor.begin(this, \"hud-render-verify\",\n                \"surface=\" + surface + \" \" + safeDetail);\n        UiInvariantMonitor.verifyNextFrame(this, transitionId,\n                \"hud-render-\" + surface, target,\n                () -> mapHudHost().isExpanded(surface),\n                () -> {\n                    View blocker = higherZHudBlocker(target);\n                    boolean unobscured = blocker == null;\n                    UiInvariantMonitor.invariant(MainActivity.this, transitionId,\n                            \"expanded_hud_not_blocked_by_higher_z_control\", unobscured,\n                            \"surface=\" + surface + \" blocker=\" + hudBlockerSummary(blocker)\n                                    + \" \" + safeDetail);\n                    UiInvariantMonitor.state(MainActivity.this, transitionId,\n                            unobscured ? \"HUD_RENDER_COMMITTED\" : \"HUD_RENDER_BLOCKED\",\n                            \"surface=\" + surface + \" blocker=\" + hudBlockerSummary(blocker)\n                                    + \" \" + safeDetail);\n                },\n                () -> UiInvariantMonitor.state(MainActivity.this, transitionId,\n                        \"HUD_RENDER_FAILED\", \"surface=\" + surface + \" \" + safeDetail));\n    }\n""",
            """    public void verifyMapHudPresentation(String surface, View target, String detail) {\n        final String safeDetail = detail == null ? \"\" : detail;\n        long transitionId = UiInvariantMonitor.begin(this, \"hud-render-verify\",\n                \"surface=\" + surface + \" \" + safeDetail);\n        UiInvariantMonitor.verifyNextFrame(this, transitionId,\n                \"hud-render-\" + surface, target,\n                () -> mapHudHost().isExpanded(surface),\n                () -> {\n                    View blocker = higherZHudBlocker(target);\n                    boolean unobscured = blocker == null;\n                    UiInvariantMonitor.invariant(MainActivity.this, transitionId,\n                            \"expanded_hud_not_blocked_by_higher_z_control\", unobscured,\n                            \"surface=\" + surface + \" blocker=\" + hudBlockerSummary(blocker)\n                                    + \" \" + safeDetail);\n                    UiInvariantMonitor.state(MainActivity.this, transitionId,\n                            unobscured ? \"HUD_RENDER_COMMITTED\" : \"HUD_RENDER_BLOCKED\",\n                            \"surface=\" + surface + \" blocker=\" + hudBlockerSummary(blocker)\n                                    + \" \" + safeDetail);\n                    releaseExplicitHudReservation(surface,\n                            unobscured ? \"frame-committed\" : \"frame-blocked\");\n                },\n                () -> {\n                    UiInvariantMonitor.state(MainActivity.this, transitionId,\n                            \"HUD_RENDER_FAILED\", \"surface=\" + surface + \" \" + safeDetail);\n                    releaseExplicitHudReservation(surface, \"frame-failed\");\n                });\n    } // marker: v5-release-reservation-after-real-frame\n""",
            "release explicit reservation after real frame",
        )

        replace_once(
            main_activity,
            "v5-observational-blocker-check",
            """    private View higherZHudBlocker(View target) {\n        if (target != null && researchAreaPanel != null\n                && target == researchAreaPanel.getPresentationView()) {\n            FieldMapController.avoidPeerControlCollision(this, target);\n        }\n        // marker: v4-auto-reflow-before-blocker-check\n        if (target == null || !(target.getParent() instanceof ViewGroup)) return null;\n""",
            """    private View higherZHudBlocker(View target) {\n        // Diagnostics are observational. Layout code owns placement; invariant checks never move UI.\n        // marker: v4-auto-reflow-before-blocker-check\n        // marker: v5-observational-blocker-check\n        if (target == null || !(target.getParent() instanceof ViewGroup)) return null;\n""",
            "observational blocker check",
        )

        # Mapped Research gets a deterministic middle-right default and preserves real user drags.
        replace_once(
            context,
            "v5-menu-manual-position-state",
            """    private boolean explicitExpansionInProgress; // marker: v2-explicit-mapped-expansion-flag\n    private boolean menuUserPositioned;\n""",
            """    private boolean explicitExpansionInProgress; // marker: v2-explicit-mapped-expansion-flag\n    private boolean menuManuallyMoved; // marker: v5-menu-manual-position-state\n    private boolean menuUserPositioned;\n""",
            "mapped manual-position state",
        )

        replace_once(
            context,
            "v5-menu-drag-marks-manual",
            """                if (!menuDragging && Math.hypot(dx, dy) >= Math.max(1, menuTouchSlop)) {\n                    menuDragging = true;\n                    touchedRow.setPressed(false);\n                }\n""",
            """                if (!menuDragging && Math.hypot(dx, dy) >= Math.max(1, menuTouchSlop)) {\n                    menuDragging = true;\n                    menuManuallyMoved = true;\n                    touchedRow.setPressed(false);\n                    // marker: v5-menu-drag-marks-manual\n                }\n""",
            "mapped menu manual drag state",
        )

        replace_once(
            context,
            "v5-middle-right-default-helper",
            """    private void showCollapsedTab() {\n        if (collapsedTab == null || root == null) return;\n""",
            """    private int defaultMiddleRightTop(int viewHeight) {\n        int rootHeight = Math.max(root == null ? 0 : root.getHeight(), mapView.getHeight());\n        if (rootHeight <= 0) rootHeight = activity.getResources().getDisplayMetrics().heightPixels;\n        int height = Math.max(dp(42), viewHeight);\n        int safeTop = Math.max(dp(48), safeTopInsetInRoot() + dp(16));\n        int safeBottom = Math.max(safeTop + height,\n                rootHeight - safeBottomGuardInRoot() - dp(12));\n        int travel = Math.max(0, safeBottom - safeTop - height);\n        return safeTop + travel / 2;\n    }\n\n    private int defaultMiddleRightLeft(int viewWidth) {\n        int rootWidth = Math.max(root == null ? 0 : root.getWidth(), mapView.getWidth());\n        if (rootWidth <= 0) rootWidth = activity.getResources().getDisplayMetrics().widthPixels;\n        return Math.max(dp(6), rootWidth - Math.max(dp(82), viewWidth) - dp(8));\n    }\n\n    private void showCollapsedTab() {\n        if (collapsedTab == null || root == null) return;\n        // marker: v5-middle-right-default-helper\n""",
            "middle-right mapped default helper",
        )

        replace_once(
            context,
            "v5-middle-right-collapsed-default",
            """        int rootWidth = Math.max(root.getWidth(), mapView.getWidth());\n        int width = dp(82);\n        int margin = dp(4);\n        int left = collapsedOnLeft ? margin : Math.max(margin, rootWidth - width - margin);\n        positionCollapsedTab(left, collapsedTop);\n""",
            """        int rootWidth = Math.max(root.getWidth(), mapView.getWidth());\n        int width = dp(82);\n        int margin = dp(4);\n        if (!collapsedMoved && !menuManuallyMoved) {\n            collapsedOnLeft = false;\n            collapsedTop = defaultMiddleRightTop(\n                    collapsedTab.getHeight() > 0 ? collapsedTab.getHeight() : dp(42));\n        }\n        int left = collapsedOnLeft ? margin : Math.max(margin, rootWidth - width - dp(8));\n        positionCollapsedTab(left, collapsedTop);\n        // marker: v5-middle-right-collapsed-default\n""",
            "middle-right collapsed mapped control",
        )

        replace_once(
            context,
            "v5-middle-right-expanded-default",
            """        if (menuUserPositioned) {\n            positionInRoot(menu, width, estimatedHeight, menuUserLeft, menuUserTop, true);\n        } else {\n            ContextItem anchorItem = items.get(0);\n            PointF anchor = project(anchorItem.anchor != null ? anchorItem.anchor : northEast(anchorItem.bounds));\n            if (anchor == null) {\n                menu.setVisibility(View.GONE);\n                if (hudTransition > 0L && activity instanceof MainActivity) {\n                    ((MainActivity) activity).afterMapHudExpansion(\n                            MapHudCoordinator.SURFACE_MAPPED_CONTEXT, hudTransition);\n                }\n                // marker: mapped-context-anchor-null-clears-transition\n                return;\n            }\n            // Before the user moves it, keep the menu associated with the mapped geometry.\n            int left = Math.round(anchor.x + dp(7));\n            int top = Math.round(anchor.y - estimatedHeight - dp(7));\n            if (left + width > Math.max(root.getWidth(), mapView.getWidth()) - dp(6)) {\n                left = Math.round(anchor.x - width - dp(7));\n            }\n            position(menu, width, estimatedHeight, left, top);\n        }\n""",
            """        if (menuUserPositioned && menuManuallyMoved) {\n            positionInRoot(menu, width, estimatedHeight, menuUserLeft, menuUserTop, true);\n        } else {\n            // Auto-created mapped controls use a predictable right-middle home. They no longer\n            // inherit a geometry anchor or stale collapse coordinate near the Android status bar.\n            menuUserPositioned = false;\n            int left = defaultMiddleRightLeft(width);\n            int top = defaultMiddleRightTop(estimatedHeight);\n            positionInRoot(menu, width, estimatedHeight, left, top, false);\n        }\n        // marker: v5-middle-right-expanded-default\n""",
            "middle-right expanded mapped control",
        )

        replace_once(
            context,
            "v5-collapsed-drag-promotes-manual-position",
            """        if (collapsedMoved && root != null) {\n            int rootWidth = Math.max(root.getWidth(), mapView.getWidth());\n            int width = dp(180);\n            int margin = dp(6);\n            menuUserPositioned = true;\n            menuUserLeft = collapsedOnLeft\n                    ? margin\n                    : Math.max(margin, rootWidth - width - margin);\n            menuUserTop = collapsedTop;\n        }\n""",
            """        if (collapsedMoved && root != null) {\n            int rootWidth = Math.max(root.getWidth(), mapView.getWidth());\n            int width = dp(180);\n            int margin = dp(6);\n            menuUserPositioned = true;\n            menuManuallyMoved = true;\n            menuUserLeft = collapsedOnLeft\n                    ? margin\n                    : Math.max(margin, rootWidth - width - margin);\n            menuUserTop = collapsedTop;\n        } else if (!menuManuallyMoved) {\n            menuUserPositioned = false;\n        }\n        // marker: v5-collapsed-drag-promotes-manual-position\n""",
            "collapsed mapped drag ownership",
        )

        replace_count(
            context,
            "v5-conservative-top-clamp",
            """        int minTop = Math.max(margin, safeTopInsetInRoot());\n""",
            """        int minTop = Math.max(dp(48), Math.max(margin, safeTopInsetInRoot()));\n        // marker: v5-conservative-top-clamp\n""",
            3,
            "conservative mapped top clamps",
        )

        replace_once(
            context,
            "v5-safe-area-check-conservative-top",
            """        int safeTop = rootRect.top + safeTopInsetInRoot();\n""",
            """        int safeTop = rootRect.top + Math.max(dp(48), safeTopInsetInRoot());\n        // marker: v5-safe-area-check-conservative-top\n""",
            "conservative mapped safe-area invariant",
        )

        # Default collapsed Field rail moves to left/lower-middle. User-dragged positions remain.
        replace_once(
            field,
            "v5-field-collapsed-rail-default",
            """            int rootWidth = root.getWidth() > 0 ? root.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;\n            int rootHeight = root.getHeight() > 0 ? root.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;\n            int width = collapsedTabs.getWidth() > 0 ? collapsedTabs.getWidth() : dp(94);\n            int height = collapsedTabs.getHeight() > 0 ? collapsedTabs.getHeight() : dp(90);\n            positionFloatingView(collapsedTabs, rootWidth - width - dp(6),\n                    Math.max(statusBarHeight() + dp(70), (rootHeight - height) / 2), false);\n""",
            """            int rootWidth = root.getWidth() > 0 ? root.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;\n            int rootHeight = root.getHeight() > 0 ? root.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;\n            int width = collapsedTabs.getWidth() > 0 ? collapsedTabs.getWidth() : dp(94);\n            int height = collapsedTabs.getHeight() > 0 ? collapsedTabs.getHeight() : dp(90);\n            int safeTop = Math.max(dp(96), statusBarHeight() + dp(70));\n            int safeBottom = Math.max(safeTop + height, rootHeight - dp(118));\n            if (controls != null && controls.isShown() && controls.getTop() > 0) {\n                safeBottom = Math.min(safeBottom, controls.getTop() - dp(8));\n            }\n            int travel = Math.max(0, safeBottom - safeTop - height);\n            int top = safeTop + Math.round(travel * 0.68f);\n            positionFloatingView(collapsedTabs, dp(6), top, false);\n            // marker: v5-field-collapsed-rail-default\n""",
            "Field collapsed rail left/lower default",
        )

        print("HUD/tour corrective v5 injection complete.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("HUD/tour corrective v5 injection rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
