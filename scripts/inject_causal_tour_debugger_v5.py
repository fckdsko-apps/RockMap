#!/usr/bin/env python3
"""Causal debugger v5: prove tour-start cleanup and saved-data preservation.

Diagnostic-only. Runs after behavioral v9/v10 cleanup. It adds observers, saved-state signatures,
pre/immediate/post-frame audits, and stale-reappearance checks. It never closes, moves, retries,
or otherwise repairs application UI/state.
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


def patch_database(field_db: Path) -> None:
    replace_once(
        field_db,
        "causal-v5-saved-data-signature",
        '''    public synchronized Track getActiveTrack() {
        try (Cursor c = getReadableDatabase().query("tracks",
                new String[]{"id","name","started_at","ended_at","status"},
                "status IN (?,?)", new String[]{TRACK_RECORDING, TRACK_PAUSED},
                null, null, "started_at DESC", "1")) {
            return c.moveToFirst() ? trackFrom(c) : null;
        }
    }
''',
        '''    public synchronized Track getActiveTrack() {
        try (Cursor c = getReadableDatabase().query("tracks",
                new String[]{"id","name","started_at","ended_at","status"},
                "status IN (?,?)", new String[]{TRACK_RECORDING, TRACK_PAUSED},
                null, null, "started_at DESC", "1")) {
            return c.moveToFirst() ? trackFrom(c) : null;
        }
    }

    /** Debug-build observer: compact identity/count signature; never mutates the database. */
    public synchronized String debugSavedStateSignature() {
        SQLiteDatabase db = getReadableDatabase();
        return "tracks=" + debugCountMax(db, "tracks")
                + ";trackPoints=" + debugCountMax(db, "track_points")
                + ";fieldRecords=" + debugCountMax(db, "field_records")
                + ";areas=" + debugCountMax(db, "areas")
                + ";areaPoints=" + debugCountMax(db, "area_points")
                + ";importBatches=" + debugCountMax(db, "import_batches")
                + ";importItems=" + debugCountMax(db, "import_items");
    }

    private static String debugCountMax(SQLiteDatabase db, String table) {
        long count = -1L;
        long maxId = -1L;
        try (Cursor c = db.rawQuery("SELECT COUNT(*), COALESCE(MAX(id),0) FROM " + table, null)) {
            if (c.moveToFirst()) {
                count = c.getLong(0);
                maxId = c.getLong(1);
            }
        }
        return count + ":" + maxId;
    } // marker: causal-v5-saved-data-signature
''',
        "saved Field data diagnostic signature",
    )


def patch_coach(coach: Path) -> None:
    replace_once(
        coach,
        "causal-v5-coach-observers",
        '''    private static void clearVisibleCoach() {
        clearHighlight();
''',
        '''    public static boolean debugCoachVisible(Activity activity) {
        Activity owner = activeCoachOwner.get();
        ViewGroup root = activeCoachRoot.get();
        if (activity == null || owner != activity || root == null) return false;
        View coach = root.findViewWithTag(TAG);
        return coach != null && coach.getVisibility() == View.VISIBLE && coach.isShown();
    }

    public static boolean debugHighlightVisible(Activity activity) {
        Activity owner = activeCoachOwner.get();
        View highlighted = highlightedView.get();
        return activity != null && owner == activity && highlighted != null
                && highlighted.getVisibility() == View.VISIBLE && highlighted.isShown();
    }

    public static synchronized boolean debugPendingRequest(Activity activity) {
        return activity != null && requestGenerations.containsKey(activity);
    } // marker: causal-v5-coach-observers

    private static void clearVisibleCoach() {
        clearHighlight();
''',
        "coach/highlight/pending observers",
    )


def patch_main(main: Path) -> None:
    replace_once(
        main,
        "causal-v5-tour-cleanup-audit-helper",
        '''    private boolean prepareWorkspaceForTourStart(String origin) {
''',
        '''    private String debugTourCleanupSurfaceSnapshot() {
        boolean researchVisible = researchAreaPanel != null && researchAreaPanel.isVisible();
        String researchMode = researchAreaPanel == null ? "none" : researchAreaPanel.currentMode();
        boolean mappedClosed = mapView == null
                || MapContextCloseController.forMap(mapView).isPresentationClosedForTourStart();
        boolean fieldPresentation = FieldMapController.isTourWorkspacePresentationVisible(this);
        boolean prompt = ProspectingAreaCreator.isSavedResearchPromptVisible(this);
        boolean geology = geologyOverlayController != null && geologyOverlayController.isVisible();
        boolean mineral = mineralOverlayController != null && mineralOverlayController.isVisible();
        boolean heatmap = mineralOverlayController != null && mineralOverlayController.isHeatmapVisible();
        boolean mineralArea = mineralOverlayController != null && mineralOverlayController.isAreaAnalysisVisible();
        boolean mines = historicMineOverlayController != null && historicMineOverlayController.isVisible();
        return "research=" + researchVisible + "/" + researchMode
                + " mappedClosed=" + mappedClosed
                + " fieldPresentation=" + fieldPresentation
                + " prospectingPrompt=" + prompt
                + " measure=" + FieldMapState.measurementActive(this)
                + " nav=" + (FieldMapState.navigationTarget(this) != null)
                + " activeTrack=" + (FieldDatabase.get(this).getActiveTrack() != null)
                + " tracksVisible=" + FieldMapState.tracksVisible(this)
                + " areasVisible=" + FieldMapState.areasVisible(this)
                + " recordsVisible=" + FieldMapState.fieldRecordsVisible(this)
                + " labelsVisible=" + FieldMapState.labelsVisible(this)
                + " geology=" + geology
                + " mineral=" + mineral
                + " heatmap=" + heatmap
                + " mineralArea=" + mineralArea
                + " mines=" + mines
                + " researchSession=" + (ResearchSessionState.load(this) != null)
                + " pendingResearch=" + (pendingResearchLaunchIntent != null)
                + " findPin=" + (activePlaceTarget != null)
                + " pendingMapTapResults=" + pendingOverlayTapLand.size()
                + " coach=" + GuidedTourCoach.debugCoachVisible(this)
                + " highlight=" + GuidedTourCoach.debugHighlightVisible(this)
                + " coachPending=" + GuidedTourCoach.debugPendingRequest(this);
    }

    private void debugAuditTourCleanup(long transitionId, String phase,
                                       String savedBaseline, boolean savedResearchBaseline,
                                       boolean requirePreTourEmpty) {
        String savedNow = FieldDatabase.get(this).debugSavedStateSignature();
        boolean savedFieldPreserved = savedBaseline.equals(savedNow);
        boolean savedResearchPreserved = ResearchResultStore.exists(this) == savedResearchBaseline;

        boolean mainTour = GuidedTourState.isActive(this);
        int step = mainTour ? GuidedTourState.step(this) : -1;
        boolean fieldTour = FieldTourState.active(this);
        boolean researchAllowed = !requirePreTourEmpty && mainTour
                && guidedTourStepNeedsResearchSession(step);
        boolean mappedAllowed = !requirePreTourEmpty && mainTour
                && step >= GuidedTourState.STEP_CONTEXT_CONTROLS;
        boolean fieldAllowed = !requirePreTourEmpty && fieldTour;

        boolean researchClosed = researchAllowed
                || researchAreaPanel == null || !researchAreaPanel.isVisible();
        boolean mappedClosed = mappedAllowed || mapView == null
                || MapContextCloseController.forMap(mapView).isPresentationClosedForTourStart();
        boolean fieldClosed = fieldAllowed
                || !FieldMapController.isTourWorkspacePresentationVisible(this);
        boolean promptClosed = !ProspectingAreaCreator.isSavedResearchPromptVisible(this);

        boolean temporaryStateClean = (!fieldAllowed
                ? !FieldMapState.measurementActive(this)
                    && FieldMapState.navigationTarget(this) == null
                : true)
                && FieldDatabase.get(this).getActiveTrack() == null;

        boolean overlaysClean = researchAllowed || (
                (geologyOverlayController == null || !geologyOverlayController.isVisible())
                && (mineralOverlayController == null
                    || (!mineralOverlayController.isVisible()
                        && !mineralOverlayController.isHeatmapVisible()
                        && !mineralOverlayController.isAreaAnalysisVisible()))
                && (historicMineOverlayController == null || !historicMineOverlayController.isVisible()));

        boolean fieldLayersClean = fieldAllowed || (!FieldMapState.tracksVisible(this)
                && !FieldMapState.areasVisible(this)
                && !FieldMapState.fieldRecordsVisible(this)
                && !FieldMapState.labelsVisible(this));

        boolean oldCoachGone = !requirePreTourEmpty
                || (!GuidedTourCoach.debugCoachVisible(this)
                    && !GuidedTourCoach.debugHighlightVisible(this)
                    && !GuidedTourCoach.debugPendingRequest(this));
        boolean pendingWorkGone = !requirePreTourEmpty || (
                ResearchSessionState.load(this) == null
                && pendingResearchLaunchIntent == null
                && activePlaceTarget == null
                && pendingOverlayTapLand.isEmpty());

        boolean uiClean = researchClosed && mappedClosed && fieldClosed && promptClosed
                && temporaryStateClean && overlaysClean && fieldLayersClean
                && oldCoachGone && pendingWorkGone;

        String detail = "phase=" + phase
                + " mainTour=" + mainTour + " step=" + step + " fieldTour=" + fieldTour
                + " savedField=" + savedFieldPreserved
                + " savedResearch=" + savedResearchPreserved
                + " uiClean=" + uiClean
                + " baseline=" + savedBaseline + " current=" + savedNow
                + " surfaces={" + debugTourCleanupSurfaceSnapshot() + "}";
        TourDebugLog.causalEvent("TOUR_CLEANUP_AUDIT", detail);
        UiInvariantMonitor.invariant(this, transitionId,
                "tour_cleanup_saved_data_preserved_" + phase,
                savedFieldPreserved && savedResearchPreserved, detail);
        UiInvariantMonitor.invariant(this, transitionId,
                "tour_cleanup_surfaces_clean_" + phase, uiClean, detail);
        if (!uiClean) {
            TourDebugLog.causalEvent("DEBUG_FINDING",
                    "severity=ERROR code=TOUR_CLEANUP_SURFACE_LEAK " + detail);
        }
        if (!savedFieldPreserved || !savedResearchPreserved) {
            TourDebugLog.causalEvent("DEBUG_FINDING",
                    "severity=CRITICAL code=TOUR_CLEANUP_SAVED_DATA_CHANGED " + detail);
        }
    }

    private void debugSchedulePostCleanupAudits(long transitionId,
                                                String savedBaseline,
                                                boolean savedResearchBaseline) {
        View scheduler = getWindow() == null ? null : getWindow().getDecorView();
        if (scheduler == null) return;
        scheduler.post(() -> debugAuditTourCleanup(
                transitionId, "next-frame", savedBaseline, savedResearchBaseline, false));
        scheduler.postDelayed(() -> debugAuditTourCleanup(
                transitionId, "100ms", savedBaseline, savedResearchBaseline, false), 100L);
        scheduler.postDelayed(() -> debugAuditTourCleanup(
                transitionId, "500ms", savedBaseline, savedResearchBaseline, false), 500L);
    } // marker: causal-v5-tour-cleanup-audit-helper

    private boolean prepareWorkspaceForTourStart(String origin) {
''',
        "full tour cleanup diagnostic helper",
    )

    replace_once(
        main,
        "causal-v5-tour-cleanup-before",
        '''        UiInvariantMonitor.state(this, transition, "TOUR_PREPARE_START",
                "origin=" + origin + " oldMainTour=" + GuidedTourState.isActive(this)
                        + " oldFieldTour=" + FieldTourState.active(this));
        GuidedTourCoach.clear(this);
''',
        '''        UiInvariantMonitor.state(this, transition, "TOUR_PREPARE_START",
                "origin=" + origin + " oldMainTour=" + GuidedTourState.isActive(this)
                        + " oldFieldTour=" + FieldTourState.active(this));
        final String cleanupSavedBaseline = FieldDatabase.get(this).debugSavedStateSignature();
        final boolean cleanupSavedResearchBaseline = ResearchResultStore.exists(this);
        TourDebugLog.causalEvent("TOUR_CLEANUP_BEFORE",
                "origin=" + origin + " saved=" + cleanupSavedBaseline
                        + " savedResearch=" + cleanupSavedResearchBaseline
                        + " surfaces={" + debugTourCleanupSurfaceSnapshot() + "}");
        // marker: causal-v5-tour-cleanup-before
        GuidedTourCoach.clear(this);
''',
        "capture pre-cleanup state and saved-data baseline",
    )

    replace_once(
        main,
        "causal-v5-tour-cleanup-after",
        '''        UiInvariantMonitor.state(this, transition, "TOUR_PREPARE_COMPLETE",
                "origin=" + origin + " clean=" + clean);
        return clean;
''',
        '''        UiInvariantMonitor.state(this, transition, "TOUR_PREPARE_COMPLETE",
                "origin=" + origin + " clean=" + clean);
        debugAuditTourCleanup(transition, "immediate", cleanupSavedBaseline,
                cleanupSavedResearchBaseline, true);
        debugSchedulePostCleanupAudits(transition, cleanupSavedBaseline,
                cleanupSavedResearchBaseline);
        // marker: causal-v5-tour-cleanup-after
        return clean;
''',
        "immediate and delayed post-cleanup audits",
    )


def patch_schema(causality: Path) -> None:
    replace_once(
        causality,
        "causal-v5-schema",
        '''    public static final String SCHEMA = "causal-v4"; // marker: causal-v4-schema
''',
        '''    public static final String SCHEMA = "causal-v5"; // marker: causal-v4-schema
      // marker: causal-v5-schema
''',
        "causal debugger schema v5",
    )


def main() -> int:
    main = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
    field_db = ROOT / "app/src/main/java/com/rockmap/app/field/FieldDatabase.java"
    coach = ROOT / "app/src/main/java/com/rockmap/app/GuidedTourCoach.java"
    causality = ROOT / "app/src/main/java/com/rockmap/app/TourDebugCausality.java"
    required = (main, field_db, coach, causality)
    for path in required:
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: text(path) for path in required}
    try:
        patch_database(field_db)
        patch_coach(coach)
        patch_main(main)
        patch_schema(causality)

        # Observational-only guard. DB SELECTs and scheduling diagnostic callbacks are allowed;
        # debugger code must never repair or alter the UI/application state it is inspecting.
        main_text = text(main)
        helper_start = main_text.index("private String debugTourCleanupSurfaceSnapshot()")
        helper_end = main_text.index("private boolean prepareWorkspaceForTourStart", helper_start)
        helper = main_text[helper_start:helper_end]
        for forbidden in (
            ".setVisibility(", ".bringToFront(", ".requestLayout(", ".invalidate(",
            ".performClick(", "GuidedTourState.setStep(", "GuidedTourState.advance(",
            "FieldMapState.setTracksVisible(", "FieldMapState.setAreasVisible(",
            "FieldMapState.clearMeasurement(", "FieldMapState.stopNavigation(",
            "deleteTrack(", "deleteArea(", "deleteFieldRecord("
        ):
            if forbidden in helper:
                raise RuntimeError(f"causal v5 observational scope guard failed: {forbidden}")

        print("Causal debugger v5 full cleanup audits complete.")
        print("Audits: before/immediate/next-frame/100ms/500ms + saved-data signature preservation.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("Causal debugger v5 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
