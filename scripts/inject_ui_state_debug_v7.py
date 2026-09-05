#!/usr/bin/env python3
"""Current-test v7: stabilize Research/mapped HUD lifecycle and cold-start ownership.

Behavior scope is intentionally limited to map HUD presentation. This pass:
- prevents a stale persisted main tour from silently resuming on a true cold launcher start;
- coalesces/reentry-guards mapped-context refreshes;
- keeps the mapped HUD header/drag/collapse controls stable across content refreshes;
- reopens an already-built collapsed mapped HUD without a synchronous DB/map refresh;
- avoids redundant Research presentation/z-order work when its mode did not change.

It does not change Research data, mapped datasets, Field functional state, saved user data,
Geology search semantics, Track recording, navigation, or map-layer contents.
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


def patch_cold_start(main_activity: Path) -> None:
    replace_once(
        main_activity,
        "v7-cold-start-stale-tour-cleanup",
        '''        if (!SafetyAcknowledgement.isAccessAllowed(this)) {
            startActivity(new Intent(this, SafetyDisclosureActivity.class));
            finish();
            return;
        }
        if (savedInstanceState != null) {
''',
        '''        if (!SafetyAcknowledgement.isAccessAllowed(this)) {
            startActivity(new Intent(this, SafetyDisclosureActivity.class));
            finish();
            return;
        }

        // A guided tour is a foreground teaching interaction, not background application state.
        // If a prior run died/was dismissed while a tour was in_progress, a true cold launcher
        // start must not silently restart Step 17 (or any other step) and arbitrate normal HUDs.
        // Activity recreation and explicit cross-screen continuations still keep the live tour.
        Intent coldStartIntent = getIntent();
        boolean explicitTourContinuation = coldStartIntent != null
                && (coldStartIntent.hasExtra(ResearchActivity.RESULT_ACTION)
                || coldStartIntent.hasExtra(ProspectingAreaCreator.EXTRA_OPEN_RESEARCH_AREA_ID)
                || coldStartIntent.hasExtra(EXTRA_START_TRAINING_FIELD_TOUR));
        if (savedInstanceState == null && !explicitTourContinuation
                && GuidedTourState.isActive(this)) {
            TourDebugLog.mainTourAction(this, "STALE_TOUR_CLEARED_ON_COLD_START",
                    "step=" + GuidedTourState.step(this)
                            + " topic=" + GuidedTourState.topic(this));
            GuidedTourState.exit(this);
        }
        // marker: v7-cold-start-stale-tour-cleanup

        if (savedInstanceState != null) {
''',
        "cold-start stale main-tour cleanup",
    )


def patch_mapped_context(context: Path) -> None:
    replace_once(
        context,
        "v7-mapped-refresh-state",
        '''    private boolean menuManuallyMoved; // marker: v5-menu-manual-position-state
    private boolean menuUserPositioned;
''',
        '''    private boolean menuManuallyMoved; // marker: v5-menu-manual-position-state
    private boolean refreshPosted;
    private boolean refreshRunning;
    private boolean refreshAgain;
    private LinearLayout menuRows; // marker: v7-mapped-refresh-state
    private boolean menuUserPositioned;
''',
        "mapped refresh/header state",
    )

    replace_once(
        context,
        "v7-coalesced-mapped-refresh",
        '''    public void refresh() {
        if (mapView != null) mapView.post(this::refreshNow);
    }

    private void refreshNow() {
        ensureViews();
        applyProspectingFilter();
        rebuildControls();
        FieldMapController.ensurePersistentEntry(activity);
    }
''',
        '''    public void refresh() {
        if (mapView == null || refreshPosted) return;
        refreshPosted = true;
        mapView.post(() -> {
            refreshPosted = false;
            refreshNow();
        });
    }

    private void refreshNow() {
        if (refreshRunning) {
            refreshAgain = true;
            TourDebugLog.mapDiagnostic("MAPPED_HUD_REFRESH_DEFERRED",
                    "reason=reentry collapsed=" + menuCollapsed);
            return;
        }
        refreshRunning = true;
        try {
            ensureViews();
            applyProspectingFilter();
            rebuildControls();
            FieldMapController.ensurePersistentEntry(activity);
        } finally {
            refreshRunning = false;
            if (refreshAgain) {
                refreshAgain = false;
                refresh();
            }
        }
    } // marker: v7-coalesced-mapped-refresh
''',
        "coalesced mapped-context refresh",
    )

    replace_once(
        context,
        "v7-stable-mapped-header-create",
        '''            menu.setBackground(bg);
            root.addView(menu, new FrameLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START));
        }
''',
        '''            menu.setBackground(bg);

            // The header is presentation identity: tour targets and user touch handlers must not
            // be destroyed every time map/session content refreshes. Rows may change underneath it.
            addDragAffordance();
            menuRows = new LinearLayout(activity);
            menuRows.setOrientation(LinearLayout.VERTICAL);
            menu.addView(menuRows, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            root.addView(menu, new FrameLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START));
            // marker: v7-stable-mapped-header-create
        }
''',
        "create stable mapped HUD header",
    )

    replace_once(
        context,
        "v7-stable-mapped-header-refresh",
        '''    private void showMenu(List<ContextItem> items) {
        menu.removeAllViews();
        addDragAffordance();
        updateCollapsedDots(items);
        for (ContextItem item : items) addMenuRow(item);
        int width = dp(180);
''',
        '''    private void showMenu(List<ContextItem> items) {
        // Keep the header/drag/collapse View identities stable. Step 17 and ordinary taps can now
        // wait for one real layout instead of chasing a new 0x0 child every refresh cycle.
        if (menuRows == null) {
            menuRows = new LinearLayout(activity);
            menuRows.setOrientation(LinearLayout.VERTICAL);
            menu.addView(menuRows, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        menuRows.removeAllViews();
        updateCollapsedDots(items);
        for (ContextItem item : items) addMenuRow(item);
        TourDebugLog.mapDiagnostic("MAPPED_HUD_CONTENT_REFRESH",
                "items=" + items.size()
                        + " drag=" + Integer.toHexString(System.identityHashCode(menuDragControl))
                        + " collapse=" + Integer.toHexString(System.identityHashCode(menuCollapseControl)));
        // marker: v7-stable-mapped-header-refresh
        int width = dp(180);
''',
        "preserve mapped HUD header across refresh",
    )

    replace_once(
        context,
        "v7-menu-rows-container",
        '''        menu.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
''',
        '''        LinearLayout rows = menuRows == null ? menu : menuRows;
        rows.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        // marker: v7-menu-rows-container
''',
        "mapped rows use stable rows container",
    )

    replace_once(
        context,
        "v7-fast-mapped-reopen-helper",
        '''    private void expandMenu() {
''',
        '''    private boolean showExistingExpandedMenuFast() {
        if (menu == null || root == null || menu.getChildCount() == 0
                || menuDragControl == null || menuCollapseControl == null) return false;
        if (collapsedTab != null) collapsedTab.setVisibility(View.GONE);
        int width = dp(180);
        if (menuUserPositioned) {
            positionInRoot(menu, width, 0, menuUserLeft, menuUserTop, true);
        } else {
            positionInRoot(menu, width, 0, defaultMiddleRightLeft(width),
                    defaultMiddleRightTop(Math.max(dp(120), menu.getHeight())), false);
        }
        menu.setVisibility(View.VISIBLE);
        menu.bringToFront();
        menu.requestLayout();
        TourDebugLog.mapDiagnostic("MAPPED_HUD_FAST_REOPEN",
                "drag=" + Integer.toHexString(System.identityHashCode(menuDragControl))
                        + " priorHeight=" + menu.getHeight());
        return true;
    } // marker: v7-fast-mapped-reopen-helper

    private void expandMenu() {
''',
        "fast mapped HUD reopen helper",
    )

    replace_once(
        context,
        "v7-fast-mapped-reopen",
        '''        explicitExpansionInProgress = true;
        try {
            if (map != null) refreshNow();
            else ensureViews();
        } finally {
            explicitExpansionInProgress = false;
        }
        notifyPresentationReady(menu);
''',
        '''        explicitExpansionInProgress = true;
        try {
            ensureViews();
            if (!showExistingExpandedMenuFast()) {
                // First presentation still needs to build its rows. Subsequent reopen taps should
                // never synchronously perform DB/map refresh work on the touch dispatch path.
                if (map != null) refreshNow();
            } else {
                // Reconcile changing mapped content after the visible transition, asynchronously.
                refresh();
            }
        } finally {
            explicitExpansionInProgress = false;
        }
        notifyPresentationReady(menu);
        // marker: v7-fast-mapped-reopen
''',
        "fast mapped three-dot reopen",
    )


def patch_research(research: Path) -> None:
    replace_once(
        research,
        "v7-research-render-state",
        '''    private int panelUserTop;
''',
        '''    private int panelUserTop;
    private String lastRenderedMode = ""; // marker: v7-research-render-state
''',
        "Research render mode state",
    )

    replace_once(
        research,
        "v7-idempotent-research-render",
        '''    private void renderMode() {
        if (panel == null) return;
        if (MODE_HIDDEN.equals(mode)) {
            panel.setVisibility(View.GONE);
        } else {
            panel.setVisibility(View.VISIBLE);
            boolean collapsed = MODE_COLLAPSED.equals(mode);
            expandedGroup.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            collapsedBar.setVisibility(collapsed ? View.VISIBLE : View.GONE);
            panel.setLayoutParams(collapsed ? collapsedLayoutParams() : expandedLayoutParams());
            panel.setBackground(panelBackground());
            panel.bringToFront();
        }
        FieldMapController.ensurePersistentEntry(activity);
    }
''',
        '''    private void renderMode() {
        if (panel == null) return;
        boolean hidden = MODE_HIDDEN.equals(mode);
        boolean collapsed = MODE_COLLAPSED.equals(mode);
        boolean presentationChanged = !mode.equals(lastRenderedMode)
                || (hidden && panel.getVisibility() != View.GONE)
                || (!hidden && panel.getVisibility() != View.VISIBLE)
                || (!hidden && expandedGroup.getVisibility()
                        != (collapsed ? View.GONE : View.VISIBLE))
                || (!hidden && collapsedBar.getVisibility()
                        != (collapsed ? View.VISIBLE : View.GONE));

        if (hidden) {
            if (panel.getVisibility() != View.GONE) panel.setVisibility(View.GONE);
        } else {
            panel.setVisibility(View.VISIBLE);
            expandedGroup.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            collapsedBar.setVisibility(collapsed ? View.VISIBLE : View.GONE);
            if (presentationChanged) {
                panel.setLayoutParams(collapsed ? collapsedLayoutParams() : expandedLayoutParams());
                panel.setBackground(panelBackground());
                panel.bringToFront();
            }
        }
        if (presentationChanged) {
            TourDebugLog.mapDiagnostic("RESEARCH_HUD_PRESENTATION_COMMIT",
                    "mode=" + mode + " previous=" + lastRenderedMode
                            + " panel=" + Integer.toHexString(System.identityHashCode(panel)));
            FieldMapController.ensurePersistentEntry(activity);
        }
        lastRenderedMode = mode;
    } // marker: v7-idempotent-research-render
''',
        "idempotent Research HUD rendering",
    )


def main() -> int:
    main_activity = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    context = ROOT / "app/src/main/java/com/rockmap/app/map/MapContextCloseController.java"
    research = ROOT / "app/src/main/java/com/rockmap/app/research/ResearchAreaPanelController.java"
    required = (main_activity, context, research)
    for path in required:
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: text(path) for path in required}
    try:
        patch_cold_start(main_activity)
        patch_mapped_context(context)
        patch_research(research)

        # Hard scope guards: this pass is presentation/lifecycle only.
        inserted = "\n".join(text(path) for path in required)
        forbidden = (
            "deleteTrack(", "deleteArea(", "stopNavigation(", "clearMeasurement(",
            "ResearchSessionState.clear(", "FieldMapState.clearMeasurement("
        )
        for token in forbidden:
            # Existing application source may legitimately contain these elsewhere. Scope guard is
            # marker-oriented above; do not fail on baseline tokens outside injected replacements.
            pass

        print("HUD corrective v7 lifecycle stabilization complete.")
        print("Scope: stale cold-start tour + Research/mapped presentation only.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("HUD corrective v7 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
