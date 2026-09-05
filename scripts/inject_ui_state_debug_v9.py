#!/usr/bin/env python3
"""v9: make the tour-start warning true — fully close/reset temporary workspace UI.

This pass deliberately runs after the v7/v8 HUD lifecycle stabilization. It does not alter the
stable mapped-header/fast-reopen implementation. It changes only the user-confirmed tour-start
cleanup contract and adds observational checks for that cleanup.

Saved user data is never deleted. Active Track recording remains a hard blocker. Temporary
Research/Find/Measure/Navigation work and open workspace presentation are reset before a tour starts.
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


def patch_prompt(coordinator: Path) -> None:
    replace_once(
        coordinator,
        "v9-tour-start-warning-copy",
        '''        message.append("This tour will close open tools and clear temporary work from the map so the tour can start with a clean screen.\\n\\n");
        int boldStart = message.length();
        message.append("Save any unsaved tracks, measurements, prospecting areas, or other work before continuing.");
''',
        '''        message.append("This tour will close all open tools and workspaces and clear temporary map work so the tour can start with a clean screen.\\n\\n");
        int boldStart = message.length();
        message.append("Save any unsaved tracks, measurements, Prospecting Areas, Research work, or other temporary work before continuing. Saved data will not be deleted.");
        // marker: v9-tour-start-warning-copy
''',
        "tour-start warning accurately describes full reset",
    )


def patch_research_panel(research: Path) -> None:
    replace_once(
        research,
        "v9-research-full-close-for-tour",
        '''    public boolean collapsePresentationOnly() {
        ensurePanel();
        if (MODE_HIDDEN.equals(mode) || MODE_COLLAPSED.equals(mode)) return true;
        mode = MODE_COLLAPSED;
        renderMode();
        return isCollapsed();
    }
''',
        '''    public boolean collapsePresentationOnly() {
        ensurePanel();
        if (MODE_HIDDEN.equals(mode) || MODE_COLLAPSED.equals(mode)) return true;
        mode = MODE_COLLAPSED;
        renderMode();
        return isCollapsed();
    }

    /** Tour-start cleanup is a true close, not a minimized workspace. No listener/tour callback. */
    public boolean closePresentationForTourStart() {
        ensurePanel();
        mode = MODE_HIDDEN;
        renderMode();
        return panel == null || panel.getVisibility() != View.VISIBLE;
    } // marker: v9-research-full-close-for-tour
''',
        "Research full-close API for tour start",
    )


def patch_mapped_context(context: Path) -> None:
    replace_once(
        context,
        "v9-tour-start-mapped-close-state",
        '''    private boolean refreshPosted;
    private boolean refreshRunning;
    private boolean refreshAgain;
    private LinearLayout menuRows; // marker: v7-mapped-refresh-state
''',
        '''    private boolean refreshPosted;
    private boolean refreshRunning;
    private boolean refreshAgain;
    private boolean tourStartPresentationClosed;
    private long tourStartPresentationClosedAt;
    private LinearLayout menuRows; // marker: v7-mapped-refresh-state
    // marker: v9-tour-start-mapped-close-state
''',
        "mapped-context tour-start close state",
    )

    replace_once(
        context,
        "v9-mapped-full-close-for-tour",
        '''    public boolean collapsePresentationOnly() {
        ensureViews();
        if (menuCollapsed) return true;
        menuCollapsed = true;
        if (menu != null) menu.setVisibility(View.GONE);
        if (collapsedTab != null) showCollapsedTab();
        return !isExpandedVisible();
    }
''',
        '''    public boolean collapsePresentationOnly() {
        ensureViews();
        if (menuCollapsed) return true;
        menuCollapsed = true;
        if (menu != null) menu.setVisibility(View.GONE);
        if (collapsedTab != null) showCollapsedTab();
        return !isExpandedVisible();
    }

    /** Fully hide both expanded and collapsed mapped controls for a freshly starting tour. */
    public boolean closePresentationForTourStart() {
        ensureViews();
        tourStartPresentationClosed = true;
        tourStartPresentationClosedAt = android.os.SystemClock.elapsedRealtime();
        menuCollapsed = false;
        transientlyHiddenForCamera = false;
        hideControls();
        return isPresentationClosedForTourStart();
    }

    public boolean isPresentationClosedForTourStart() {
        boolean menuHidden = menu == null || menu.getVisibility() != View.VISIBLE;
        boolean tabHidden = collapsedTab == null || collapsedTab.getVisibility() != View.VISIBLE;
        return menuHidden && tabHidden;
    } // marker: v9-mapped-full-close-for-tour
''',
        "mapped-context full-close API for tour start",
    )

    replace_once(
        context,
        "v9-mapped-close-survives-refresh",
        '''    private void rebuildControls() {
        if (root == null || map == null || menu == null) return;
        ArrayList<ContextItem> items = new ArrayList<>();
''',
        '''    private void rebuildControls() {
        if (root == null || map == null || menu == null) return;
        if (tourStartPresentationClosed) {
            boolean mainTourActive = com.rockmap.app.GuidedTourState.isActive(activity);
            boolean fieldTourActive = com.rockmap.app.field.FieldTourState.active(activity);
            boolean mappedLessonActive = mainTourActive
                    && com.rockmap.app.GuidedTourState.step(activity)
                    >= com.rockmap.app.GuidedTourState.STEP_CONTEXT_CONTROLS;
            long closedAge = Math.max(0L, android.os.SystemClock.elapsedRealtime()
                    - tourStartPresentationClosedAt);
            if (mappedLessonActive) {
                tourStartPresentationClosed = false;
            } else if (mainTourActive || fieldTourActive || closedAge < 1000L) {
                hideControls();
                return;
            } else {
                // A short tour that never reaches the mapped-controls lesson has ended. Normal
                // mapped chrome may resume on the next ordinary refresh.
                tourStartPresentationClosed = false;
            }
        }
        // marker: v9-mapped-close-survives-refresh
        ArrayList<ContextItem> items = new ArrayList<>();
''',
        "tour-start mapped close survives asynchronous refreshes",
    )

    replace_once(
        context,
        "v9-mapped-force-expand-releases-close",
        '''    public View forcePrepareExpandedControlsForTour() {
        ensureViews();
''',
        '''    public View forcePrepareExpandedControlsForTour() {
        tourStartPresentationClosed = false;
        ensureViews();
        // marker: v9-mapped-force-expand-releases-close
''',
        "Step 17 explicitly releases tour-start mapped close",
    )

    replace_once(
        context,
        "v9-mapped-force-collapse-releases-close",
        '''    public View forcePrepareCollapsedControlsForTour() {
        ensureViews();
''',
        '''    public View forcePrepareCollapsedControlsForTour() {
        tourStartPresentationClosed = false;
        ensureViews();
        // marker: v9-mapped-force-collapse-releases-close
''',
        "Step 19 explicitly releases tour-start mapped close",
    )


def patch_prospecting_prompt(prospecting: Path) -> None:
    replace_once(
        prospecting,
        "v9-prospecting-prompt-observer",
        '''    public static void dismissSavedResearchPrompt(Activity activity) {
        if (activity == null) return;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        View prompt = ((FrameLayout) content).findViewWithTag(SAVED_PROMPT_TAG);
        if (prompt != null) ((FrameLayout) content).removeView(prompt);
    }
''',
        '''    public static void dismissSavedResearchPrompt(Activity activity) {
        if (activity == null) return;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        View prompt = ((FrameLayout) content).findViewWithTag(SAVED_PROMPT_TAG);
        if (prompt != null) ((FrameLayout) content).removeView(prompt);
    }

    public static boolean isSavedResearchPromptVisible(Activity activity) {
        if (activity == null) return false;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return false;
        View prompt = ((FrameLayout) content).findViewWithTag(SAVED_PROMPT_TAG);
        return prompt != null && prompt.getVisibility() == View.VISIBLE && prompt.isShown();
    } // marker: v9-prospecting-prompt-observer
''',
        "Prospecting Area prompt visibility observer",
    )


def patch_field_visibility(field: Path) -> None:
    replace_once(
        field,
        "v9-field-tour-presentation-observer",
        '''    public static boolean isHudExpanded(Activity activity) {
        FieldMapController controller = liveController(activity);
        return controller != null && controller.expandedTool != null
                && controller.hud != null && controller.hud.getVisibility() == View.VISIBLE;
    }
''',
        '''    public static boolean isHudExpanded(Activity activity) {
        FieldMapController controller = liveController(activity);
        return controller != null && controller.expandedTool != null
                && controller.hud != null && controller.hud.getVisibility() == View.VISIBLE;
    }

    public static boolean isTourWorkspacePresentationVisible(Activity activity) {
        FieldMapController controller = liveController(activity);
        if (controller == null) return false;
        boolean hudVisible = controller.hud != null
                && controller.hud.getVisibility() == View.VISIBLE && controller.hud.isShown();
        boolean tabsVisible = controller.collapsedTabs != null
                && controller.collapsedTabs.getVisibility() == View.VISIBLE
                && controller.collapsedTabs.isShown();
        boolean tapCatcherVisible = controller.tapCapture != null
                && controller.tapCapture.getVisibility() == View.VISIBLE
                && controller.tapCapture.isShown();
        return hudVisible || tabsVisible || tapCatcherVisible || controller.awaitingMapTap;
    } // marker: v9-field-tour-presentation-observer
''',
        "Field full presentation visibility observer",
    )


def patch_main_activity(main: Path) -> None:
    replace_once(
        main,
        "v9-research-tour-start-reset-helper",
        '''    private void openTracksForTourStart() {
        Intent field = new Intent(this, FieldActivity.class);
        field.putExtra(FieldActivity.EXTRA_SCREEN, "tracks");
        startActivity(field);
    }

    private boolean prepareWorkspaceForTourStart(String origin) {
''',
        '''    private void openTracksForTourStart() {
        Intent field = new Intent(this, FieldActivity.class);
        field.putExtra(FieldActivity.EXTRA_SCREEN, "tracks");
        startActivity(field);
    }

    /** Clear only the live/temporary Research workspace. Saved Research exports are preserved. */
    private boolean resetResearchWorkspaceForTourStart(long transitionId) {
        boolean panelClosed = researchAreaPanel == null
                || researchAreaPanel.closePresentationForTourStart();

        pendingResearchLaunchIntent = null;
        activeMineralSearchResult = null;
        activeMineralAreaAnalysis = null;
        activeResearchBounds = null;
        activeResearchGeologyGeoJson = "";
        activeResearchGeologyTitle = "";
        activeResearchGeologyCount = 0;
        activeResearchGeologyBounds = null;
        historicMineContextBounds = null;
        activeResearchAreaLabel = "Selected Area";
        activeResearchAreaId = -1L;
        activeResearchView = ResearchAreaPanelController.VIEW_GEOLOGY;
        activeResearchStatus = "";
        activeResearchMineralKey = "";
        activeResearchMineralLabel = "";
        activeResearchMineralMessage = "";
        activeResearchMineralEvidencePoints = new ArrayList<>();
        historicMinesRequestedVisible = false;
        researchSessionContentRestorePending = false;
        researchSessionRestored = true;
        ResearchSessionState.clear(this);

        if (geologyOverlayController != null) geologyOverlayController.clear();
        if (mineralOverlayController != null) {
            mineralOverlayController.clear();
            mineralOverlayController.clearAreaAnalysis();
        }
        if (historicMineOverlayController != null) historicMineOverlayController.clear();

        boolean overlaysClosed = (geologyOverlayController == null || !geologyOverlayController.isVisible())
                && (mineralOverlayController == null
                    || (!mineralOverlayController.isVisible()
                        && !mineralOverlayController.isHeatmapVisible()
                        && !mineralOverlayController.isAreaAnalysisVisible()))
                && (historicMineOverlayController == null || !historicMineOverlayController.isVisible());
        boolean clean = panelClosed && overlaysClosed;
        UiInvariantMonitor.invariant(this, transitionId,
                "research_workspace_fully_closed_for_tour", clean,
                "panelClosed=" + panelClosed + " overlaysClosed=" + overlaysClosed);
        return clean;
    } // marker: v9-research-tour-start-reset-helper

    private boolean prepareWorkspaceForTourStart(String origin) {
''',
        "temporary Research reset helper for tour start",
    )

    replace_once(
        main,
        "v9-tour-start-full-close-applied",
        '''        ProspectingAreaCreator.dismissSavedResearchPrompt(this);
        if (researchAreaPanel != null && researchAreaPanel.isExpanded()) {
            researchAreaPanel.collapsePresentationOnly();
        }
        if (mapView != null) {
            MapContextCloseController context = MapContextCloseController.forMap(mapView);
            if (context.isExpandedVisible()) context.collapsePresentationOnly();
        }
        boolean fieldClean = FieldMapController.prepareTemporaryWorkspaceForTour(this, transition);
''',
        '''        ProspectingAreaCreator.dismissSavedResearchPrompt(this);
        boolean researchClean = resetResearchWorkspaceForTourStart(transition);
        clearPlaceSearchTarget();
        pendingOverlayTapLand.clear();
        pendingLocationAction = LOCATION_ACTION_NONE;

        boolean mappedClosed = true;
        if (mapView != null) {
            MapContextCloseController context = MapContextCloseController.forMap(mapView);
            mappedClosed = context.closePresentationForTourStart();
        }
        boolean fieldClean = FieldMapController.prepareTemporaryWorkspaceForTour(this, transition);
        // marker: v9-tour-start-full-close-applied
''',
        "tour start fully closes Research/mapped/temporary UI",
    )

    replace_once(
        main,
        "v9-tour-clean-invariant-expanded",
        '''        boolean clean = fieldClean
                && MapHudCoordinator.expandedCount(mapHudHost()) == 0
                && !FieldMapState.measurementActive(this)
                && FieldMapState.navigationTarget(this) == null
                && FieldDatabase.get(this).getActiveTrack() == null;
        UiInvariantMonitor.invariant(this, transition, "tour_starts_from_clean_working_state", clean,
                "hud=" + MapHudCoordinator.expandedSummary(mapHudHost())
                        + " measure=" + FieldMapState.measurementActive(this)
                        + " nav=" + (FieldMapState.navigationTarget(this) != null));
''',
        '''        boolean prospectingPromptClosed = !ProspectingAreaCreator.isSavedResearchPromptVisible(this);
        boolean researchPanelClosed = researchAreaPanel == null || !researchAreaPanel.isVisible();
        boolean mappedPresentationClosed = mapView == null
                || MapContextCloseController.forMap(mapView).isPresentationClosedForTourStart();
        boolean fieldPresentationClosed = !FieldMapController.isTourWorkspacePresentationVisible(this);
        boolean clean = fieldClean && researchClean && mappedClosed
                && prospectingPromptClosed && researchPanelClosed
                && mappedPresentationClosed && fieldPresentationClosed
                && MapHudCoordinator.expandedCount(mapHudHost()) == 0
                && !FieldMapState.measurementActive(this)
                && FieldMapState.navigationTarget(this) == null
                && FieldDatabase.get(this).getActiveTrack() == null;
        UiInvariantMonitor.invariant(this, transition, "tour_starts_from_clean_working_state", clean,
                "hud=" + MapHudCoordinator.expandedSummary(mapHudHost())
                        + " research=" + researchPanelClosed
                        + " mapped=" + mappedPresentationClosed
                        + " fieldPresentation=" + fieldPresentationClosed
                        + " prospectingPrompt=" + prospectingPromptClosed
                        + " measure=" + FieldMapState.measurementActive(this)
                        + " nav=" + (FieldMapState.navigationTarget(this) != null));
        TourDebugLog.mainTourAction(this, "TOUR_CLEAN_SURFACE_AUDIT",
                "research=" + researchPanelClosed
                        + " mapped=" + mappedPresentationClosed
                        + " field=" + fieldPresentationClosed
                        + " prospectingPrompt=" + prospectingPromptClosed
                        + " researchState=" + researchClean
                        + " fieldState=" + fieldClean);
        // marker: v9-tour-clean-invariant-expanded
''',
        "tour clean-start invariant covers all workspace surfaces",
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
        patch_prompt(coordinator)
        patch_research_panel(research)
        patch_mapped_context(context)
        patch_prospecting_prompt(prospecting)
        patch_field_visibility(field)
        patch_main_activity(main_activity)

        # Scope guards: tour cleanup may clear temporary state, but never saved entities/records.
        new_text = "\n".join(text(path) for path in required)
        for forbidden in ("deleteTrack(", "deleteArea(", "deleteTrip(", "deleteWaypoint("):
            # Baseline source may contain unrelated management methods; only reject if one appears
            # near a v9 marker, which would mean this patch accidentally became destructive.
            marker_positions = [i for i in range(len(new_text)) if new_text.startswith("v9-", i)]
            for pos in marker_positions:
                window = new_text[max(0, pos - 1800):pos + 1800]
                if forbidden in window:
                    raise RuntimeError(f"v9 scope guard failed near marker: {forbidden}")

        print("HUD/tour corrective v9 full-close tour-start cleanup complete.")
        print("Saved data preserved; active Track remains blocked; v7/v8 HUD lifecycle unchanged.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("HUD/tour corrective v9 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
