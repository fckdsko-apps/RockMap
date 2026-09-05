#!/usr/bin/env python3
"""Current-test corrective pass: safe mapped controls, peer-HUD reflow, and Step-16 coach sync."""
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
    context = ROOT / "app/src/main/java/com/rockmap/app/map/MapContextCloseController.java"
    field = ROOT / "app/src/main/java/com/rockmap/app/field/FieldMapController.java"
    research = ROOT / "app/src/main/java/com/rockmap/app/research/ResearchAreaPanelController.java"
    main_activity = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    required = [context, field, research, main_activity]
    for path in required:
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: text(path) for path in required}
    try:
        replace_once(
            context,
            "v4-system-safe-bounds-helper",
            """    private void positionCollapsedTab(int left, int top) {\n""",
            """    private int safeTopInsetInRoot() {\n        if (root == null) return dp(6);\n        int safeTop = 0;\n        try {\n            android.graphics.Rect visible = new android.graphics.Rect();\n            root.getWindowVisibleDisplayFrame(visible);\n            int[] rootLoc = new int[2];\n            root.getLocationOnScreen(rootLoc);\n            safeTop = Math.max(0, visible.top - rootLoc[1]);\n        } catch (RuntimeException ignored) {}\n        int statusId = activity.getResources().getIdentifier(\n                \"status_bar_height\", \"dimen\", \"android\");\n        if (statusId > 0) {\n            safeTop = Math.max(safeTop,\n                    activity.getResources().getDimensionPixelSize(statusId));\n        }\n        return safeTop + dp(6);\n    }\n\n    private int safeBottomGuardInRoot() {\n        int guard = dp(118);\n        if (root == null) return guard;\n        try {\n            android.graphics.Rect visible = new android.graphics.Rect();\n            root.getWindowVisibleDisplayFrame(visible);\n            int[] rootLoc = new int[2];\n            root.getLocationOnScreen(rootLoc);\n            int rootBottomOnScreen = rootLoc[1] + root.getHeight();\n            int systemBottom = Math.max(0, rootBottomOnScreen - visible.bottom);\n            guard = Math.max(guard, systemBottom + dp(6));\n        } catch (RuntimeException ignored) {}\n        return guard;\n    }\n\n    public boolean isDisplayedInsideSystemSafeArea() {\n        View displayed = getDisplayedContainer();\n        if (displayed == null || root == null || !displayed.isShown()) return false;\n        android.graphics.Rect viewRect = new android.graphics.Rect();\n        android.graphics.Rect rootRect = new android.graphics.Rect();\n        if (!displayed.getGlobalVisibleRect(viewRect) || !root.getGlobalVisibleRect(rootRect)) return false;\n        int safeTop = rootRect.top + safeTopInsetInRoot();\n        int safeBottom = rootRect.bottom - safeBottomGuardInRoot();\n        return viewRect.top >= safeTop && viewRect.bottom <= safeBottom\n                && viewRect.left >= rootRect.left + dp(4)\n                && viewRect.right <= rootRect.right - dp(4);\n    } // marker: v4-system-safe-bounds-helper\n\n    private void positionCollapsedTab(int left, int top) {\n""",
            "mapped controls system-safe helper",
        )
        replace_once(
            context,
            "v4-collapsed-tab-safe-clamp",
            """        int margin = dp(4);\n        int bottomGuard = dp(118);\n        left = clamp(left, margin, Math.max(margin, rootWidth - width - margin));\n        top = clamp(top, margin, Math.max(margin, rootHeight - height - bottomGuard));\n""",
            """        int margin = dp(4);\n        int minTop = Math.max(margin, safeTopInsetInRoot());\n        int bottomGuard = safeBottomGuardInRoot();\n        left = clamp(left, margin, Math.max(margin, rootWidth - width - margin));\n        top = clamp(top, minTop, Math.max(minTop, rootHeight - height - bottomGuard));\n        // marker: v4-collapsed-tab-safe-clamp\n""",
            "collapsed mapped control safe clamp",
        )
        replace_once(
            context,
            "v4-expanded-menu-safe-clamp",
            """        int margin = dp(6);\n        int bottomGuard = dp(118);\n        left = clamp(left, margin, Math.max(margin, rootWidth - resolvedWidth - margin));\n        top = clamp(top, margin, Math.max(margin, rootHeight - resolvedHeight - bottomGuard));\n""",
            """        int margin = dp(6);\n        int minTop = Math.max(margin, safeTopInsetInRoot());\n        int bottomGuard = safeBottomGuardInRoot();\n        left = clamp(left, margin, Math.max(margin, rootWidth - resolvedWidth - margin));\n        top = clamp(top, minTop, Math.max(minTop, rootHeight - resolvedHeight - bottomGuard));\n        // marker: v4-expanded-menu-safe-clamp\n""",
            "user-positioned mapped menu safe clamp",
        )
        replace_once(
            context,
            "v4-anchored-menu-safe-clamp",
            """        int margin = dp(6);\n        int bottomGuard = dp(118);\n        left = clamp(left, margin, Math.max(margin, rootWidth - width - margin));\n        top = clamp(top, margin, Math.max(margin, rootHeight - height - bottomGuard));\n""",
            """        int margin = dp(6);\n        int minTop = Math.max(margin, safeTopInsetInRoot());\n        int bottomGuard = safeBottomGuardInRoot();\n        left = clamp(left, margin, Math.max(margin, rootWidth - width - margin));\n        top = clamp(top, minTop, Math.max(minTop, rootHeight - height - bottomGuard));\n        // marker: v4-anchored-menu-safe-clamp\n""",
            "geometry-anchored mapped menu safe clamp",
        )

        replace_once(
            field,
            "v4-peer-control-collision-api",
            """    public static View hudPresentationView(Activity activity) {\n        FieldMapController controller = liveController(activity);\n        return controller == null ? null : controller.hud;\n    } // marker: v2-field-low-level-arbitration\n\n""",
            """    public static View hudPresentationView(Activity activity) {\n        FieldMapController controller = liveController(activity);\n        return controller == null ? null : controller.hud;\n    } // marker: v2-field-low-level-arbitration\n\n    public static void avoidPeerControlCollision(Activity activity, View expandedHud) {\n        FieldMapController controller = liveController(activity);\n        if (controller != null) controller.avoidPeerControlCollision(expandedHud);\n    }\n\n    public static void clearPeerControlCollision(Activity activity) {\n        FieldMapController controller = liveController(activity);\n        if (controller == null) return;\n        if (controller.collapsedTabs != null) {\n            controller.collapsedTabs.setTranslationX(0f);\n            controller.collapsedTabs.setTranslationY(0f);\n        }\n        if (controller.fieldButton != null) {\n            controller.fieldButton.setTranslationX(0f);\n            controller.fieldButton.setTranslationY(0f);\n        }\n    }\n\n    private void avoidPeerControlCollision(View expandedHud) {\n        if (root == null || expandedHud == null || !expandedHud.isShown()) return;\n        clearPeerControlCollision(activity);\n        Rect target = new Rect();\n        if (!expandedHud.getGlobalVisibleRect(target)) return;\n        translatePeerOutside(collapsedTabs, target, true, \"collapsed-field-tabs\");\n        translatePeerOutside(fieldButton, target, false, \"field-entry\");\n        // marker: v4-peer-control-collision-api\n    }\n\n    private void translatePeerOutside(View peer, Rect target, boolean preferAbove, String label) {\n        if (peer == null || peer.getVisibility() != View.VISIBLE || !peer.isShown()) return;\n        Rect peerRect = new Rect();\n        if (!peer.getGlobalVisibleRect(peerRect) || !Rect.intersects(peerRect, target)) return;\n        Rect visible = new Rect();\n        root.getWindowVisibleDisplayFrame(visible);\n        int[] rootLoc = new int[2];\n        root.getLocationOnScreen(rootLoc);\n        int safeTop = Math.max(visible.top, rootLoc[1] + statusBarHeight()) + dp(6);\n        int safeBottom = visible.bottom - dp(6);\n        if (controls != null && controls.isShown()) {\n            Rect controlsRect = new Rect();\n            if (controls.getGlobalVisibleRect(controlsRect)) {\n                safeBottom = Math.min(safeBottom, controlsRect.top - dp(6));\n            }\n        }\n        int gap = dp(8);\n        int height = Math.max(dp(42), peerRect.height());\n        int aboveTop = target.top - gap - height;\n        int belowTop = target.bottom + gap;\n        boolean aboveFits = aboveTop >= safeTop;\n        boolean belowFits = belowTop + height <= safeBottom;\n        Integer chosenTop = null;\n        if (preferAbove && aboveFits) chosenTop = aboveTop;\n        else if (!preferAbove && belowFits) chosenTop = belowTop;\n        else if (aboveFits) chosenTop = aboveTop;\n        else if (belowFits) chosenTop = belowTop;\n        if (chosenTop == null) {\n            TourDebugLog.mapDiagnostic(\"HUD_PEER_COLLISION_UNRESOLVED\",\n                    \"peer=\" + label + \" target=\" + target.flattenToString()\n                            + \" peerRect=\" + peerRect.flattenToString());\n            return;\n        }\n        float dy = chosenTop - peerRect.top;\n        peer.setTranslationY(peer.getTranslationY() + dy);\n        peer.bringToFront();\n        TourDebugLog.mapDiagnostic(\"HUD_PEER_COLLISION_AVOIDED\",\n                \"peer=\" + label + \" dy=\" + Math.round(dy)\n                        + \" target=\" + target.flattenToString());\n    }\n\n""",
            "Field peer-control collision avoidance",
        )

        replace_once(
            research,
            "v4-clear-peer-collision-on-user-collapse",
            """    public void collapse() {\n        ensurePanel();\n        mode = MODE_COLLAPSED;\n        renderMode();\n        if (listener != null) listener.onPanelModeChanged(mode);\n    }\n""",
            """    public void collapse() {\n        ensurePanel();\n        mode = MODE_COLLAPSED;\n        renderMode();\n        FieldMapController.clearPeerControlCollision(activity);\n        if (listener != null) listener.onPanelModeChanged(mode);\n        // marker: v4-clear-peer-collision-on-user-collapse\n    }\n""",
            "clear peer reflow on Research collapse",
        )
        replace_once(
            research,
            "v4-clear-peer-collision-on-close",
            """    public void closePanel() {\n        ensurePanel();\n        mode = MODE_HIDDEN;\n        renderMode();\n        if (listener != null) {\n""",
            """    public void closePanel() {\n        ensurePanel();\n        mode = MODE_HIDDEN;\n        renderMode();\n        FieldMapController.clearPeerControlCollision(activity);\n        // marker: v4-clear-peer-collision-on-close\n        if (listener != null) {\n""",
            "clear peer reflow on Research close",
        )
        replace_once(
            research,
            "v4-clear-peer-collision-on-arbitration-collapse",
            """    public boolean collapsePresentationOnly() {\n        ensurePanel();\n        if (MODE_HIDDEN.equals(mode) || MODE_COLLAPSED.equals(mode)) return true;\n        mode = MODE_COLLAPSED;\n        renderMode();\n        return isCollapsed();\n    }\n""",
            """    public boolean collapsePresentationOnly() {\n        ensurePanel();\n        if (MODE_HIDDEN.equals(mode) || MODE_COLLAPSED.equals(mode)) {\n            FieldMapController.clearPeerControlCollision(activity);\n            return true;\n        }\n        mode = MODE_COLLAPSED;\n        renderMode();\n        FieldMapController.clearPeerControlCollision(activity);\n        // marker: v4-clear-peer-collision-on-arbitration-collapse\n        return isCollapsed();\n    }\n""",
            "clear peer reflow on arbitration collapse",
        )

        replace_once(
            main_activity,
            "v4-clear-peer-reflow-before-hud-switch",
            """    public long beforeMapHudExpansion(String surface) {\n        // Steps before the mapped-controls lesson need the Research workspace itself. Keep mapped\n""",
            """    public long beforeMapHudExpansion(String surface) {\n        if (!MapHudCoordinator.SURFACE_RESEARCH.equals(surface)) {\n            FieldMapController.clearPeerControlCollision(this);\n        }\n        // marker: v4-clear-peer-reflow-before-hud-switch\n        // Steps before the mapped-controls lesson need the Research workspace itself. Keep mapped\n""",
            "clear peer reflow before HUD switch",
        )
        replace_once(
            main_activity,
            "v4-auto-reflow-before-blocker-check",
            """    private View higherZHudBlocker(View target) {\n        if (target == null || !(target.getParent() instanceof ViewGroup)) return null;\n""",
            """    private View higherZHudBlocker(View target) {\n        if (target != null && researchAreaPanel != null\n                && target == researchAreaPanel.getPresentationView()) {\n            FieldMapController.avoidPeerControlCollision(this, target);\n        }\n        // marker: v4-auto-reflow-before-blocker-check\n        if (target == null || !(target.getParent() instanceof ViewGroup)) return null;\n""",
            "auto-reflow collapsed Field controls around Research",
        )

        replace_once(
            main_activity,
            "v4-clear-stale-coach-on-schedule",
            """    private void scheduleGuidedTourCoachForCurrentStep() {\n        if (!GuidedTourState.isActive(this) || getWindow() == null) return;\n        final int expectedStep = GuidedTourState.step(this);\n""",
            """    private void scheduleGuidedTourCoachForCurrentStep() {\n        if (!GuidedTourState.isActive(this) || getWindow() == null) return;\n        final int expectedStep = GuidedTourState.step(this);\n        if (lastPresentedGuidedTourStep > 0\n                && lastPresentedGuidedTourStep != expectedStep) {\n            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_COACH_INVALIDATED\",\n                    \"shownStep=\" + lastPresentedGuidedTourStep + \" currentStep=\" + expectedStep);\n            GuidedTourCoach.clear(this);\n            lastPresentedGuidedTourStep = -1;\n        }\n        // marker: v4-clear-stale-coach-on-schedule\n""",
            "clear stale coach immediately on model step change",
        )
        replace_once(
            main_activity,
            "v4-recover-stale-back-action",
            """            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STALE_ACTION\",\n                    \"action=back shownStep=\" + lastPresentedGuidedTourStep + \" currentStep=\" + step);\n            scheduleGuidedTourCoachForCurrentStep();\n            return;\n""",
            """            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STALE_ACTION\",\n                    \"action=back shownStep=\" + lastPresentedGuidedTourStep + \" currentStep=\" + step);\n            GuidedTourCoach.clear(this);\n            lastPresentedGuidedTourStep = -1;\n            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STALE_COACH_RECOVERED\",\n                    \"action=back currentStep=\" + step);\n            scheduleGuidedTourCoachForCurrentStep();\n            return;\n            // marker: v4-recover-stale-back-action\n""",
            "recover stale Back action",
        )
        replace_once(
            main_activity,
            "v4-recover-stale-skip-action",
            """            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STALE_ACTION\",\n                    \"action=skip shownStep=\" + lastPresentedGuidedTourStep + \" currentStep=\" + step);\n            scheduleGuidedTourCoachForCurrentStep();\n            return;\n""",
            """            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STALE_ACTION\",\n                    \"action=skip shownStep=\" + lastPresentedGuidedTourStep + \" currentStep=\" + step);\n            GuidedTourCoach.clear(this);\n            lastPresentedGuidedTourStep = -1;\n            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STALE_COACH_RECOVERED\",\n                    \"action=skip currentStep=\" + step);\n            scheduleGuidedTourCoachForCurrentStep();\n            return;\n            // marker: v4-recover-stale-skip-action\n""",
            "recover stale Skip action",
        )
        replace_once(
            main_activity,
            "v4-step16-presentation-normalizer",
            """    private void prepareMappedResearchTourPresentation(int step) {\n""",
            """    private void prepareResearchWorkspaceTourPresentation(int step) {\n        if (researchAreaPanel == null) return;\n        if (step == GuidedTourState.STEP_WORKSPACE_REOPEN) {\n            if (!researchAreaPanel.isCollapsed()) {\n                researchAreaPanel.collapsePresentationOnly();\n                saveResearchSession();\n                TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STEP16_NORMALIZED\",\n                        \"state=collapsed reopenTargetPending=true\");\n            }\n        } else if (step == GuidedTourState.STEP_WORKSPACE_COLLAPSE\n                && researchAreaPanel.isCollapsed()) {\n            researchAreaPanel.restoreMode(ResearchAreaPanelController.MODE_EXPANDED);\n            saveResearchSession();\n            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_STEP15_NORMALIZED\",\n                    \"state=expanded collapseTargetPending=true\");\n        }\n    } // marker: v4-step16-presentation-normalizer\n\n    private void prepareMappedResearchTourPresentation(int step) {\n""",
            "Step 15/16 presentation normalization helper",
        )
        replace_once(
            main_activity,
            "v4-run-step16-normalizer-before-readiness",
            """        // Presentation setup is explicit and idempotent. The readiness accessor below remains\n        // observational; if MapLibre was not ready when the step was first scheduled, this retry\n        // can establish the intended mapped-controls state once without waiting for camera idle.\n        prepareMappedResearchTourPresentation(expectedStep);\n""",
            """        // Presentation setup is explicit and idempotent. The readiness accessor below remains\n        // observational; if MapLibre was not ready when the step was first scheduled, this retry\n        // can establish the intended mapped-controls state once without waiting for camera idle.\n        prepareResearchWorkspaceTourPresentation(expectedStep);\n        prepareMappedResearchTourPresentation(expectedStep);\n        // marker: v4-run-step16-normalizer-before-readiness\n""",
            "run Step-16 normalization before coach readiness",
        )
        replace_once(
            main_activity,
            "v4-mapped-tour-safe-readiness",
            """        View displayed = context.getDisplayedContainer();\n        if (displayed == null || !tourTargetReady(displayed)) return false;\n""",
            """        View displayed = context.getDisplayedContainer();\n        if (displayed == null || !tourTargetReady(displayed)) return false;\n        if (!context.isDisplayedInsideSystemSafeArea()) {\n            TourDebugLog.mainTourAction(this, \"RESEARCH_TOUR_UNSAFE_MAPPED_POSITION\",\n                    \"step=\" + step + \" target=outside-system-safe-area\");\n            return false;\n        }\n        // marker: v4-mapped-tour-safe-readiness\n""",
            "mapped Research tour system-safe readiness",
        )

        print("HUD/tour corrective v4 injection complete.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("HUD/tour corrective v4 injection rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
