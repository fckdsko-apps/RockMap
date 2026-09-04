#!/usr/bin/env python3
"""Commit-1 runner-only HUD/tour fixes and invariant instrumentation for tour-debug."""
from pathlib import Path


def _text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def ensure_replace(root: Path, path: Path, marker: str, old: str, new: str, label: str) -> None:
    text = _text(path)
    if marker in text:
        print(f"{label}: already present")
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one source match in {path.relative_to(root)}, found {count}"
        )
    _write(path, text.replace(old, new, 1))
    print(f"{label}: injected")


def ensure_import(root: Path, path: Path, import_line: str, after_line: str, label: str) -> None:
    text = _text(path)
    if import_line in text:
        print(f"{label}: already present")
        return
    if text.count(after_line) != 1:
        raise RuntimeError(
            f"{label}: expected one import anchor in {path.relative_to(root)}, found {text.count(after_line)}"
        )
    _write(path, text.replace(after_line, after_line + import_line, 1))
    print(f"{label}: injected")


def require_file(root: Path, rel: str) -> Path:
    path = root / rel
    if not path.is_file():
        raise RuntimeError(f"required file missing: {rel}")
    return path


def _inject_ui_state_fixes_mutating(root: Path) -> None:
    app = require_file(root, "app/src/main/java/com/rockmap/app/RockMapApplication.java")
    main = require_file(root, "app/src/main/java/com/rockmap/app/MainActivity.java")
    coach = require_file(root, "app/src/main/java/com/rockmap/app/GuidedTourCoach.java")
    research = require_file(root, "app/src/main/java/com/rockmap/app/research/ResearchAreaPanelController.java")
    field = require_file(root, "app/src/main/java/com/rockmap/app/field/FieldMapController.java")
    field_activity = require_file(root, "app/src/main/java/com/rockmap/app/field/FieldActivity.java")
    field_db = require_file(root, "app/src/main/java/com/rockmap/app/field/FieldDatabase.java")
    track_service = require_file(root, "app/src/main/java/com/rockmap/app/field/TrackRecordingService.java")
    context = require_file(root, "app/src/main/java/com/rockmap/app/map/MapContextCloseController.java")

    # The three helper classes are source files in this commit. Fail before touching existing Java
    # if an upload omitted one of them.
    for rel in (
        "app/src/main/java/com/rockmap/app/MapHudCoordinator.java",
        "app/src/main/java/com/rockmap/app/TourStartCoordinator.java",
        "app/src/main/java/com/rockmap/app/UiInvariantMonitor.java",
    ):
        require_file(root, rel)

    # -------------------------------------------------------------------------
    # Lifecycle: invariant monitor follows Activity state; Field controller is fully resumed
    # before MainActivity consumes any pending cross-screen tour request.
    # -------------------------------------------------------------------------
    ensure_replace(
        root, app, "UiInvariantMonitor.onActivityResumed(activity);",
        """    @Override public void onActivityResumed(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
""",
        """    @Override public void onActivityResumed(Activity activity) {
        UiInvariantMonitor.onActivityResumed(activity);
        if (!(activity instanceof MainActivity)) return;
""",
        "lifecycle resumed diagnostics",
    )
    ensure_replace(
        root, app, "onMapWorkspaceResumedForTour();",
        """            TransientMapAttributionController attribution = attribution(activity);
            if (attribution != null) attribution.attach();
        });
""",
        """            TransientMapAttributionController attribution = attribution(activity);
            if (attribution != null) attribution.attach();
            ((MainActivity) activity).onMapWorkspaceResumedForTour();
        });
""",
        "consume pending tour after map workspace resume",
    )
    ensure_replace(
        root, app, "UiInvariantMonitor.onActivityPaused(activity);",
        """    @Override public void onActivityPaused(Activity activity) {
        FieldMapController controller = controllers.get(activity);
""",
        """    @Override public void onActivityPaused(Activity activity) {
        UiInvariantMonitor.onActivityPaused(activity);
        FieldMapController controller = controllers.get(activity);
""",
        "lifecycle paused diagnostics",
    )
    ensure_replace(
        root, app, "UiInvariantMonitor.onActivityDestroyed(activity);",
        """    @Override public void onActivityDestroyed(Activity activity) {
        FieldMapController controller = controllers.remove(activity);
""",
        """    @Override public void onActivityDestroyed(Activity activity) {
        UiInvariantMonitor.onActivityDestroyed(activity);
        FieldMapController controller = controllers.remove(activity);
""",
        "lifecycle destroyed diagnostics",
    )

    # -------------------------------------------------------------------------
    # Research workspace: presentation-only collapse cannot trigger listener/tour semantics.
    # Intentional expansion asks MainActivity for the one-HUD slot; restoreMode remains untouched.
    # -------------------------------------------------------------------------
    ensure_import(root, research, "import com.rockmap.app.MainActivity;\n",
                  "import com.rockmap.app.RockMapDragHandle;\n", "research MainActivity import")
    ensure_import(root, research, "import com.rockmap.app.MapHudCoordinator;\n",
                  "import com.rockmap.app.MainActivity;\n", "research HUD import")

    ensure_replace(
        root, research, "collapsePresentationOnly()",
        """    public String currentMode() { return mode; }
    public boolean isVisible() {
        return panel != null && !MODE_HIDDEN.equals(mode) && panel.getVisibility() == View.VISIBLE;
    }
    public boolean isCollapsed() { return MODE_COLLAPSED.equals(mode); }
""",
        """    public String currentMode() { return mode; }
    public boolean isVisible() {
        return panel != null && !MODE_HIDDEN.equals(mode) && panel.getVisibility() == View.VISIBLE;
    }
    public boolean isCollapsed() { return MODE_COLLAPSED.equals(mode); }
    public boolean isExpanded() { return isVisible() && !isCollapsed(); }

    /** Presentation-only arbitration: no listener callback, no tour advancement, no data change. */
    public boolean collapsePresentationOnly() {
        ensurePanel();
        if (MODE_HIDDEN.equals(mode) || MODE_COLLAPSED.equals(mode)) return true;
        mode = MODE_COLLAPSED;
        renderMode();
        return isCollapsed();
    }
""",
        "research presentation-only collapse",
    )

    ensure_replace(
        root, research, "beforeMapHudExpansion(MapHudCoordinator.SURFACE_RESEARCH)",
        """    /** Reopen from a map-context label or explicit Research action. */
    public void reopenExpanded() {
        ensurePanel();
        mode = MODE_EXPANDED;
        renderMode();
    }
""",
        """    /** Reopen from a map-context label or explicit Research action. */
    public void reopenExpanded() {
        ensurePanel();
        long hudTransition = activity instanceof MainActivity
                ? ((MainActivity) activity).beforeMapHudExpansion(MapHudCoordinator.SURFACE_RESEARCH)
                : 0L;
        if (hudTransition < 0L) return;
        mode = MODE_EXPANDED;
        renderMode();
        if (hudTransition > 0L) {
            ((MainActivity) activity).afterMapHudExpansion(
                    MapHudCoordinator.SURFACE_RESEARCH, hudTransition);
        }
    }
""",
        "research reopen arbitration",
    )

    ensure_replace(
        root, research, "research-user-expand-arbitrated",
        """    public void expand() {
        ensurePanel();
        mode = MODE_EXPANDED;
        renderMode();
        if (listener != null) listener.onPanelModeChanged(mode);
    }
""",
        """    public void expand() {
        ensurePanel();
        long hudTransition = activity instanceof MainActivity
                ? ((MainActivity) activity).beforeMapHudExpansion(MapHudCoordinator.SURFACE_RESEARCH)
                : 0L;
        if (hudTransition < 0L) return;
        mode = MODE_EXPANDED;
        renderMode();
        if (listener != null) listener.onPanelModeChanged(mode);
        if (hudTransition > 0L) {
            ((MainActivity) activity).afterMapHudExpansion(
                    MapHudCoordinator.SURFACE_RESEARCH, hudTransition);
        }
        // marker: research-user-expand-arbitrated
    }
""",
        "research user expand arbitration",
    )

    # -------------------------------------------------------------------------
    # Field HUD family: Track/Navigate/Measure already have internal exclusivity.  Expose only
    # presentation state to MainActivity and keep active state unchanged on arbitration collapse.
    # -------------------------------------------------------------------------
    ensure_import(root, field, "import com.rockmap.app.MapHudCoordinator;\n",
                  "import com.rockmap.app.MainActivity;\n", "field HUD import")
    ensure_import(root, field, "import com.rockmap.app.UiInvariantMonitor;\n",
                  "import com.rockmap.app.MapHudCoordinator;\n", "field invariant import")

    ensure_replace(
        root, field, "prepareTemporaryWorkspaceForTour(Activity activity, long transitionId)",
        """    }
    static final String HUD_TAG = "rockmap-field-map-hud";
""",
        """    }

    private static FieldMapController liveController(Activity activity) {
        if (activity == null) return null;
        WeakReference<FieldMapController> ref;
        synchronized (INSTANCES) { ref = INSTANCES.get(activity); }
        return ref == null ? null : ref.get();
    }

    public static boolean isHudExpanded(Activity activity) {
        FieldMapController controller = liveController(activity);
        return controller != null && controller.expandedTool != null
                && controller.hud != null && controller.hud.getVisibility() == View.VISIBLE;
    }

    /** Collapse Field presentation only; Track/Nav/Measure active state must remain identical. */
    public static boolean collapseHudForArbitration(Activity activity, long transitionId) {
        FieldMapController controller = liveController(activity);
        if (controller == null) return true;
        FieldDatabase.Track beforeTrack = controller.db.getActiveTrack();
        FieldMapState.NavigationTarget beforeNav = FieldMapState.navigationTarget(activity);
        boolean beforeMeasure = controller.measureActive;
        int beforeMeasurePoints = controller.measurement.size();
        controller.setExpandedToolValue(null);
        controller.renderHud("hud_arbitration");
        FieldDatabase.Track afterTrack = controller.db.getActiveTrack();
        FieldMapState.NavigationTarget afterNav = FieldMapState.navigationTarget(activity);
        boolean functionalStateStable = sameTrack(beforeTrack, afterTrack)
                && (beforeNav != null) == (afterNav != null)
                && beforeMeasure == controller.measureActive
                && beforeMeasurePoints == controller.measurement.size();
        UiInvariantMonitor.invariant(activity, transitionId,
                "hud_collapse_preserves_field_active_state", functionalStateStable,
                "track=" + trackState(beforeTrack) + "->" + trackState(afterTrack)
                        + " nav=" + (beforeNav != null) + "->" + (afterNav != null)
                        + " measure=" + beforeMeasure + "/" + beforeMeasurePoints
                        + "->" + controller.measureActive + "/" + controller.measurement.size());
        return !isHudExpanded(activity) && functionalStateStable;
    }

    /** User-confirmed destructive cleanup of temporary work; never stops/deletes a Track. */
    public static boolean prepareTemporaryWorkspaceForTour(Activity activity, long transitionId) {
        FieldMapController controller = liveController(activity);
        if (controller == null) {
            FieldMapState.stopNavigation(activity);
            FieldMapState.consumeMeasurementRequest(activity);
            FieldMapState.clearMeasurement(activity);
            FieldMapState.clearViewedMapContext(activity);
            FieldMapState.setExpandedTool(activity, null);
            return true;
        }
        if (controller.db.getActiveTrack() != null) return false;
        controller.removeTapCapture();
        controller.awaitingMapTap = false;
        controller.draggingMeasurementIndex = -1;
        controller.measurement.clear();
        controller.measureActive = false;
        FieldMapState.consumeMeasurementRequest(activity);
        FieldMapState.clearMeasurement(activity);
        FieldMapState.stopNavigation(activity);
        FieldMapState.clearViewedMapContext(activity);
        controller.latestNavigationLocation = null;
        controller.locationRepository.stop();
        controller.navigationUpdatesStarted = false;
        controller.setExpandedToolValue(null);
        controller.renderHud("tour_clean_start");
        controller.applyCachedSources();
        boolean clean = !FieldMapState.measurementActive(activity)
                && FieldMapState.navigationTarget(activity) == null
                && !isHudExpanded(activity);
        UiInvariantMonitor.invariant(activity, transitionId,
                "field_temporary_workspace_cleared", clean,
                "measure=" + FieldMapState.measurementActive(activity)
                        + " nav=" + (FieldMapState.navigationTarget(activity) != null)
                        + " hud=" + isHudExpanded(activity));
        return clean;
    }

    private static boolean sameTrack(FieldDatabase.Track a, FieldDatabase.Track b) {
        if (a == null || b == null) return a == b;
        return a.id == b.id && String.valueOf(a.status).equals(String.valueOf(b.status));
    }

    private static String trackState(FieldDatabase.Track track) {
        return track == null ? "none" : track.id + ":" + track.status;
    }

    static final String HUD_TAG = "rockmap-field-map-hud";
""",
        "field presentation bridge",
    )

    ensure_replace(
        root, field, "field-hud-expansion-arbitrated",
        """    private void setExpandedTool(String tool) {
        setExpandedToolValue(tool);
        renderHud("tool_expanded");
    }
""",
        """    private void setExpandedTool(String tool) {
        long hudTransition = 0L;
        if (tool != null && activity instanceof MainActivity) {
            hudTransition = ((MainActivity) activity).beforeMapHudExpansion(
                    MapHudCoordinator.SURFACE_FIELD);
            if (hudTransition < 0L) return;
        }
        setExpandedToolValue(tool);
        renderHud("tool_expanded");
        if (hudTransition > 0L) {
            ((MainActivity) activity).afterMapHudExpansion(
                    MapHudCoordinator.SURFACE_FIELD, hudTransition);
        }
        // marker: field-hud-expansion-arbitrated
    }
""",
        "field intentional expansion arbitration",
    )

    ensure_replace(
        root, field, "field-tour-forced-hud-arbitrated",
        """        String tourExpandedTool = requiredExpandedToolForActiveTour();
        if (tourExpandedTool != null && !tourExpandedTool.equals(expandedTool)) {
            expandedTool = tourExpandedTool;
            FieldMapState.setExpandedTool(activity, tourExpandedTool);
        }
""",
        """        String tourExpandedTool = requiredExpandedToolForActiveTour();
        if (tourExpandedTool != null && !tourExpandedTool.equals(expandedTool)) {
            long hudTransition = activity instanceof MainActivity
                    ? ((MainActivity) activity).beforeMapHudExpansion(MapHudCoordinator.SURFACE_FIELD)
                    : 0L;
            if (hudTransition >= 0L) {
                expandedTool = tourExpandedTool;
                FieldMapState.setExpandedTool(activity, tourExpandedTool);
                if (hudTransition > 0L) {
                    ((MainActivity) activity).afterMapHudExpansion(
                            MapHudCoordinator.SURFACE_FIELD, hudTransition);
                }
            }
            // marker: field-tour-forced-hud-arbitrated
        }
""",
        "field tour-forced expansion arbitration",
    )

    # Track render pipeline snapshots.  COUNT(*) avoids materializing the growing Track just for
    # diagnostics; log only when the signature changes or every five seconds.
    ensure_replace(
        root, field, "lastTrackPipelineSignature",
        """    private String trackJson = emptyCollection();
    private String areaJson = emptyCollection();
""",
        """    private String trackJson = emptyCollection();
    private volatile long lastActiveTrackDiagnosticId = -1L;
    private volatile int lastActiveTrackDiagnosticPoints;
    private volatile boolean lastActiveTrackDiagnosticFeaturePresent;
    private volatile boolean lastActiveTrackDiagnosticHidden;
    private volatile int lastTrackDiagnosticFeatures;
    private String lastTrackPipelineSignature = "";
    private long lastTrackPipelineLogElapsedMs;
    private String areaJson = emptyCollection();
""",
        "track pipeline fields",
    )
    ensure_replace(
        root, field, "TRACK_GEOJSON_ACTIVE_PROBE",
        """    private String buildTrackJson(Set<String> hidden) {
        JSONArray features = new JSONArray();
        try {
            for (FieldDatabase.Track track : db.listTracks(0)) {
""",
        """    private String buildTrackJson(Set<String> hidden) {
        JSONArray features = new JSONArray();
        FieldDatabase.Track activeTrackForDiagnostics = db.getActiveTrack();
        lastActiveTrackDiagnosticId = activeTrackForDiagnostics == null
                ? -1L : activeTrackForDiagnostics.id;
        lastActiveTrackDiagnosticPoints = activeTrackForDiagnostics == null
                ? 0 : db.getTrackPointCount(activeTrackForDiagnostics.id);
        lastActiveTrackDiagnosticHidden = lastActiveTrackDiagnosticId > 0L
                && hidden.contains(Long.toString(lastActiveTrackDiagnosticId));
        lastActiveTrackDiagnosticFeaturePresent = false;
        // marker: TRACK_GEOJSON_ACTIVE_PROBE
        try {
            for (FieldDatabase.Track track : db.listTracks(0)) {
""",
        "track active GeoJSON probe",
    )
    ensure_replace(
        root, field, "TRACK_GEOJSON_BUILD",
        """                props.put("status", track.status);
                features.put(lineFeature(points, props));
            }
        } catch (JSONException ignored) {}
        return collection(features);
    }

    private String buildAreaJson() {
""",
        """                props.put("status", track.status);
                features.put(lineFeature(points, props));
                if (track.id == lastActiveTrackDiagnosticId) {
                    lastActiveTrackDiagnosticFeaturePresent = true;
                }
            }
        } catch (JSONException ignored) {}
        lastTrackDiagnosticFeatures = features.length();
        logTrackPipelineSnapshot("TRACK_GEOJSON_BUILD");
        return collection(features);
    }

    private String buildAreaJson() {
""",
        "track GeoJSON diagnostics",
    )
    ensure_replace(
        root, field, "private void logTrackPipelineSnapshot(String event)",
        """    private String buildAreaJson() {
""",
        """    private void logTrackPipelineSnapshot(String event) {
        long now = SystemClock.elapsedRealtime();
        String signature = lastActiveTrackDiagnosticId + ":" + lastActiveTrackDiagnosticPoints
                + ":" + lastActiveTrackDiagnosticFeaturePresent + ":" + lastActiveTrackDiagnosticHidden
                + ":" + lastTrackDiagnosticFeatures + ":" + FieldMapState.tracksVisible(activity);
        if (signature.equals(lastTrackPipelineSignature)
                && now - lastTrackPipelineLogElapsedMs < 5000L) return;
        lastTrackPipelineSignature = signature;
        lastTrackPipelineLogElapsedMs = now;
        UiInvariantMonitor.track(event,
                "activeTrackId=" + lastActiveTrackDiagnosticId
                        + " activePoints=" + lastActiveTrackDiagnosticPoints
                        + " activeFeature=" + lastActiveTrackDiagnosticFeaturePresent
                        + " activeHidden=" + lastActiveTrackDiagnosticHidden
                        + " geoJsonFeatures=" + lastTrackDiagnosticFeatures
                        + " tracksVisible=" + FieldMapState.tracksVisible(activity));
    }

    private String buildAreaJson() {
""",
        "track pipeline rate limiter",
    )
    ensure_replace(
        root, field, "TRACK_MAP_RENDER_STATE",
        """            setSource(style, TRACK_SOURCE, trackJson);
            setSource(style, AREA_SOURCE, areaJson);
            // Field Record layers stay layout-visible even when the user hides them; opacity
""",
        """            setSource(style, TRACK_SOURCE, trackJson);
            UiInvariantMonitor.track("TRACK_MAP_RENDER_STATE",
                    "source=" + (style.getSource(TRACK_SOURCE) != null)
                            + " layer=" + (style.getLayer(TRACK_LAYER) != null)
                            + " activeTrackId=" + lastActiveTrackDiagnosticId
                            + " activePoints=" + lastActiveTrackDiagnosticPoints
                            + " geoJsonFeatures=" + lastTrackDiagnosticFeatures
                            + " tracksVisible=" + FieldMapState.tracksVisible(activity)
                            + " activeFeature=" + lastActiveTrackDiagnosticFeaturePresent
                            + " activeHidden=" + lastActiveTrackDiagnosticHidden);
            if (lastActiveTrackDiagnosticPoints >= 2 && FieldMapState.tracksVisible(activity)
                    && !lastActiveTrackDiagnosticHidden) {
                boolean renderPipelineReady = style.getSource(TRACK_SOURCE) != null
                        && style.getLayer(TRACK_LAYER) != null
                        && lastActiveTrackDiagnosticFeaturePresent;
                UiInvariantMonitor.track(
                        renderPipelineReady
                                ? "TRACK_RENDER_INVARIANT_OK" : "TRACK_RENDER_INVARIANT_FAIL",
                        "activePoints=" + lastActiveTrackDiagnosticPoints
                                + " activeFeature=" + lastActiveTrackDiagnosticFeaturePresent
                                + " allFeatures=" + lastTrackDiagnosticFeatures
                                + " source=" + (style.getSource(TRACK_SOURCE) != null)
                                + " layer=" + (style.getLayer(TRACK_LAYER) != null));
            }
            setSource(style, AREA_SOURCE, areaJson);
            // Field Record layers stay layout-visible even when the user hides them; opacity
""",
        "track MapLibre diagnostics",
    )

    # -------------------------------------------------------------------------
    # Field DB/service Track pipeline. No behavioral changes.
    # -------------------------------------------------------------------------
    ensure_import(root, field_db, "import com.rockmap.app.TourDebugLog;\n",
                  "import android.database.sqlite.SQLiteOpenHelper;\n\n", "field DB debug import")
    ensure_replace(
        root, field_db, "TRACK_DB_INSERT",
        """        values.put("captured_at", point.time > 0L ? point.time : System.currentTimeMillis());
        db.insertOrThrow("track_points", null, values);
    }

    public synchronized List<Track> listTracks(int limit) {
""",
        """        values.put("captured_at", point.time > 0L ? point.time : System.currentTimeMillis());
        long rowId = db.insertOrThrow("track_points", null, values);
        TourDebugLog.mapDiagnostic("TRACK_DB_INSERT",
                "trackId=" + trackId + " sortOrder=" + order + " rowId=" + rowId
                        + " capturedAt=" + values.getAsLong("captured_at"));
    }

    public synchronized int getTrackPointCount(long trackId) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM track_points WHERE track_id=?",
                new String[]{Long.toString(trackId)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public synchronized List<Track> listTracks(int limit) {
""",
        "track DB insert/count diagnostics",
    )

    ensure_import(root, track_service, "import com.rockmap.app.UiInvariantMonitor;\n",
                  "import android.os.IBinder;\n\n", "track service invariant import")
    ensure_replace(
        root, track_service, "TRACK_POINT_ACCEPTED",
        """        long time = location.getTime() > 0L ? location.getTime() : System.currentTimeMillis();
        database.addTrackPoint(trackId, new GeoMath.Point(lat, lon, altitude, accuracy, time));
""",
        """        long time = location.getTime() > 0L ? location.getTime() : System.currentTimeMillis();
        UiInvariantMonitor.track("TRACK_POINT_ACCEPTED",
                "trackId=" + trackId + " accuracyM=" + accuracy
                        + " speedMps=" + (location.hasSpeed() ? location.getSpeed() : -1f)
                        + " hasBearing=" + location.hasBearing() + " capturedAt=" + time);
        database.addTrackPoint(trackId, new GeoMath.Point(lat, lon, altitude, accuracy, time));
""",
        "track accepted-point diagnostics",
    )

    # -------------------------------------------------------------------------
    # Mapped-context presentation: distinguish camera-hide/redraw from a real expansion. Camera
    # idle must never arbitrate HUDs. Tour step 17 gets a force-render method with no isShown()
    # short circuit.
    # -------------------------------------------------------------------------
    ensure_import(root, context, "import com.rockmap.app.MainActivity;\n",
                  "import com.rockmap.app.RockMapDragHandle;\n", "mapped context MainActivity import")
    ensure_import(root, context, "import com.rockmap.app.MapHudCoordinator;\n",
                  "import com.rockmap.app.MainActivity;\n", "mapped context HUD import")
    ensure_replace(
        root, context, "transientlyHiddenForCamera",
        """    private boolean listenersInstalled;
    private boolean menuUserPositioned;
""",
        """    private boolean listenersInstalled;
    private boolean transientlyHiddenForCamera;
    private boolean menuUserPositioned;
""",
        "mapped context camera-hide flag",
    )
    ensure_replace(
        root, context, "hideControlsForCamera",
        """                map.addOnCameraMoveStartedListener(reason -> hideControls());
                map.addOnCameraIdleListener(this::refreshNow);
""",
        """                map.addOnCameraMoveStartedListener(reason -> hideControlsForCamera());
                map.addOnCameraIdleListener(this::refreshNow);
""",
        "mapped context camera redraw isolation",
    )
    ensure_replace(
        root, context, "public boolean isExpandedVisible()",
        """    public boolean isCollapsed() { return menuCollapsed; }

    /** Observational tour accessors: never build, refresh, or replace the View being inspected. */
""",
        """    public boolean isCollapsed() { return menuCollapsed; }
    public boolean isExpandedVisible() {
        return !menuCollapsed && menu != null && menu.getVisibility() == View.VISIBLE;
    }

    /** Presentation-only collapse: no callback and therefore no tour/user-action semantics. */
    public boolean collapsePresentationOnly() {
        ensureViews();
        if (menuCollapsed) return true;
        menuCollapsed = true;
        if (menu != null) menu.setVisibility(View.GONE);
        if (collapsedTab != null) showCollapsedTab();
        return !isExpandedVisible();
    }

    /** Step-17/18 establishment: always rebuild/layout, even if Android currently says shown. */
    public View forcePrepareExpandedControlsForTour() {
        ensureViews();
        menuCollapsed = false;
        transientlyHiddenForCamera = false;
        if (collapsedTab != null) collapsedTab.setVisibility(View.GONE);
        if (map != null) refreshNow();
        else ensureViews();
        if (menu != null) {
            menu.requestLayout();
            menu.invalidate();
            menu.bringToFront();
        }
        if (root != null) {
            root.requestLayout();
            root.invalidate();
        }
        return menu;
    }

    public View forcePrepareCollapsedControlsForTour() {
        ensureViews();
        menuCollapsed = true;
        transientlyHiddenForCamera = false;
        if (menu != null) menu.setVisibility(View.GONE);
        if (map != null) refreshNow();
        else if (collapsedTab != null) showCollapsedTab();
        if (collapsedTab != null) {
            collapsedTab.requestLayout();
            collapsedTab.invalidate();
            collapsedTab.bringToFront();
        }
        if (root != null) {
            root.requestLayout();
            root.invalidate();
        }
        return collapsedTab;
    }

    /** Observational tour accessors: never build, refresh, or replace the View being inspected. */
""",
        "mapped context presentation API",
    )

    # Insert camera-only hide adjacent to the normal hide implementation without depending on its body.
    ensure_replace(
        root, context, "mapped-context-empty-resets-camera-flag",
        """        if (items.isEmpty()) {
            hideControls();
            return;
        }
""",
        """        if (items.isEmpty()) {
            transientlyHiddenForCamera = false;
            hideControls();
            // marker: mapped-context-empty-resets-camera-flag
            return;
        }
""",
        "mapped context empty-state camera flag reset",
    )

    ensure_replace(
        root, context, "private void hideControlsForCamera()",
        """    private void showMenu(List<ContextItem> items) {
""",
        """    private void hideControlsForCamera() {
        transientlyHiddenForCamera = true;
        hideControls();
    }

    private void showMenu(List<ContextItem> items) {
""",
        "mapped context camera hide helper",
    )

    # Arbitrate only when an expanded menu is newly appearing for a reason other than camera redraw.
    ensure_replace(
        root, context, "mapped-context-expansion-arbitrated",
        """        if (menuCollapsed) {
            menu.setVisibility(View.GONE);
            showCollapsedTab();
            return;
        }
        if (collapsedTab != null) collapsedTab.setVisibility(View.GONE);

        if (menuUserPositioned) {
""",
        """        if (menuCollapsed) {
            menu.setVisibility(View.GONE);
            showCollapsedTab();
            transientlyHiddenForCamera = false;
            return;
        }
        long hudTransition = 0L;
        boolean needsArbitration = !transientlyHiddenForCamera
                && (menu == null || menu.getVisibility() != View.VISIBLE);
        if (needsArbitration && activity instanceof MainActivity) {
            hudTransition = ((MainActivity) activity).beforeImplicitMapHudExpansion(
                    MapHudCoordinator.SURFACE_MAPPED_CONTEXT);
            if (hudTransition < 0L) {
                menuCollapsed = true;
                if (menu != null) menu.setVisibility(View.GONE);
                showCollapsedTab();
                transientlyHiddenForCamera = false;
                return;
            }
        }
        transientlyHiddenForCamera = false;
        if (collapsedTab != null) collapsedTab.setVisibility(View.GONE);

        if (menuUserPositioned) {
""",
        "mapped context new-expansion arbitration",
    )
    ensure_replace(
        root, context, "mapped-context-anchor-null-clears-transition",
        """            if (anchor == null) {
                menu.setVisibility(View.GONE);
                return;
            }
""",
        """            if (anchor == null) {
                menu.setVisibility(View.GONE);
                if (hudTransition > 0L && activity instanceof MainActivity) {
                    ((MainActivity) activity).afterMapHudExpansion(
                            MapHudCoordinator.SURFACE_MAPPED_CONTEXT, hudTransition);
                }
                // marker: mapped-context-anchor-null-clears-transition
                return;
            }
""",
        "mapped context failed-position transition cleanup",
    )

    ensure_replace(
        root, context, "mapped-context-expansion-commit",
        """        menu.setVisibility(View.VISIBLE);
        menu.requestLayout();
        menu.invalidate();
        root.invalidate();
        menu.bringToFront();
    }

    /** One header language everywhere: boxed collapse arrow left, four-way drag handle right. */
""",
        """        menu.setVisibility(View.VISIBLE);
        menu.requestLayout();
        menu.invalidate();
        root.invalidate();
        menu.bringToFront();
        if (hudTransition > 0L && activity instanceof MainActivity) {
            ((MainActivity) activity).afterMapHudExpansion(
                    MapHudCoordinator.SURFACE_MAPPED_CONTEXT, hudTransition);
        }
        // marker: mapped-context-expansion-commit
    }

    /** One header language everywhere: boxed collapse arrow left, four-way drag handle right. */
""",
        "mapped context expansion commit",
    )
    ensure_replace(
        root, context, "mapped-context-user-expand-arbitrated",
        """    private void expandMenu() {
        if (!menuCollapsed) return;
        menuCollapsed = false;
        if (collapsedTab != null) collapsedTab.setVisibility(View.GONE);
""",
        """    private void expandMenu() {
        if (!menuCollapsed) return;
        long hudTransition = activity instanceof MainActivity
                ? ((MainActivity) activity).beforeMapHudExpansion(
                        MapHudCoordinator.SURFACE_MAPPED_CONTEXT)
                : 0L;
        if (hudTransition < 0L) return;
        menuCollapsed = false;
        if (collapsedTab != null) collapsedTab.setVisibility(View.GONE);
        // marker: mapped-context-user-expand-arbitrated
""",
        "mapped context user expansion arbitration",
    )
    ensure_replace(
        root, context, "mapped-context-user-expand-commit",
        """        if (map != null) refreshNow();
        else ensureViews();
        notifyPresentationReady(menu);
    }

    private void notifyPresentationReady(View anchor) {
""",
        """        if (map != null) refreshNow();
        else ensureViews();
        notifyPresentationReady(menu);
        if (hudTransition > 0L && activity instanceof MainActivity) {
            ((MainActivity) activity).afterMapHudExpansion(
                    MapHudCoordinator.SURFACE_MAPPED_CONTEXT, hudTransition);
        }
        // marker: mapped-context-user-expand-commit
    }

    private void notifyPresentationReady(View anchor) {
""",
        "mapped context user expansion commit",
    )

    # -------------------------------------------------------------------------
    # FieldActivity: every tour start is confirmed first.  Cross-screen requests are queued in
    # SharedPreferences and consumed only after MainActivity + FieldMapController resume.
    # -------------------------------------------------------------------------
    ensure_import(root, field_activity, "import com.rockmap.app.TourStartCoordinator;\n",
                  "import com.rockmap.app.MainActivity;\n", "FieldActivity tour-start import")
    ensure_replace(
        root, field_activity, "EXTRA_START_APPROVED_HELP_TOOL",
        """    public static final String EXTRA_START_HELP_TOOL = "rockmap.field.start_help_tool";
    public static final String EXTRA_START_CONTEXTUAL_RESEARCH = "rockmap.field.start_contextual_research";
""",
        """    public static final String EXTRA_START_HELP_TOOL = "rockmap.field.start_help_tool";
    public static final String EXTRA_START_APPROVED_HELP_TOOL = "rockmap.field.start_approved_help_tool";
    public static final String EXTRA_START_CONTEXTUAL_RESEARCH = "rockmap.field.start_contextual_research";
""",
        "approved legacy field-tour extra",
    )
    ensure_replace(
        root, field_activity, "approvedToolTour",
        """            String requestedToolTour = getIntent() == null ? null
                    : getIntent().getStringExtra(EXTRA_START_HELP_TOOL);
            if (requestedToolTour != null && !requestedToolTour.trim().isEmpty()) {
""",
        """            String approvedToolTour = getIntent() == null ? null
                    : getIntent().getStringExtra(EXTRA_START_APPROVED_HELP_TOOL);
            if (approvedToolTour != null && !approvedToolTour.trim().isEmpty()) {
                getIntent().removeExtra(EXTRA_START_APPROVED_HELP_TOOL);
                final String tool = approvedToolTour.trim();
                getWindow().getDecorView().post(() -> startApprovedLegacyTour(tool));
                return;
            }
            String requestedToolTour = getIntent() == null ? null
                    : getIntent().getStringExtra(EXTRA_START_HELP_TOOL);
            if (requestedToolTour != null && !requestedToolTour.trim().isEmpty()) {
""",
        "approved legacy field-tour dispatch",
    )

    ensure_replace(
        root, field_activity, "TourStartCoordinator.queueFieldTour(FieldActivity.this, tool, true, false);",
        """                .setPositiveButton("Continue", (d, w) -> {
                    GuidedTourCoach.clear(FieldActivity.this);
                    Intent intent = new Intent(FieldActivity.this, MainActivity.class);
                    intent.putExtra(MainActivity.EXTRA_START_TRAINING_FIELD_TOUR, tool);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                })
""",
        """                .setPositiveButton("Continue", (d, w) -> {
                    GuidedTourCoach.clear(FieldActivity.this);
                    TourStartCoordinator.queueFieldTour(FieldActivity.this, tool, true, false);
                    returnToMap();
                })
""",
        "queue training field tour safely",
    )

    # Replace the two public entry paths with one confirmation gate and approved dispatcher.
    ensure_replace(
        root, field_activity, "private void beginApprovedFieldTour(String tool)",
        """    private void startFieldTourByName(String tool) {
        if (tool == null || tool.trim().isEmpty()) return;
        if (FieldUiNames.SAVED_LOCATIONS.equals(tool)) {
            startLegacyTwoStepTour(tool,
                    "Saved Locations are quick saved points. Open one to map it, navigate to it, edit it, or copy it into a richer Field Record.",
                    this::showLegacyWaypoints, true);
            return;
        }
        if ("Research".equals(tool)) {
            startLegacyTwoStepTour(tool,
                    "Research connects field locations and areas to geology, Mineral Evidence, historic activity, and spatial analysis.",
                    () -> startResearch(new Intent(this, ResearchActivity.class)), false);
            return;
        }
        if (usesTrainingArea(tool)) {
            confirmTrainingAreaAndReturnToMap(tool);
            return;
        }
        FieldTourState.start(this, tool);
        GuidedTourCoach.clear(this);
        returnToMap();
    }
""",
        """    private void startFieldTourByName(String tool) {
        if (tool == null || tool.trim().isEmpty()) return;
        final String requested = tool.trim();
        TourStartCoordinator.confirm(this, "Field Tools — " + requested,
                () -> beginApprovedFieldTour(requested), this::showTracks);
    }

    private void beginApprovedFieldTour(String tool) {
        if (FieldUiNames.SAVED_LOCATIONS.equals(tool) || "Research".equals(tool)) {
            TourStartCoordinator.queueFieldTour(this, tool, false, true);
            returnToMap();
            return;
        }
        if (usesTrainingArea(tool)) {
            confirmTrainingAreaAndReturnToMap(tool);
            return;
        }
        TourStartCoordinator.queueFieldTour(this, tool, false, false);
        returnToMap();
    }

    private void startApprovedLegacyTour(String tool) {
        if (FieldUiNames.SAVED_LOCATIONS.equals(tool)) {
            startLegacyTwoStepTour(tool,
                    "Saved Locations are quick saved points. Open one to map it, navigate to it, edit it, or copy it into a richer Field Record.",
                    this::showLegacyWaypoints, true);
        } else if ("Research".equals(tool)) {
            startLegacyTwoStepTour(tool,
                    "Research connects field locations and areas to geology, Mineral Evidence, historic activity, and spatial analysis.",
                    () -> startResearch(new Intent(this, ResearchActivity.class)), false);
        }
    }
""",
        "global field-tour start gate",
    )
    ensure_replace(
        root, field_activity, "field-help-routes-through-global-tour-gate",
        """    private void startFieldToolTour(String tool, String explainer,
                                    Runnable openAction, boolean staysInField) {
        // All actual Field Tools now enter the same map-first interactive tour engine used by the
        // main Field menu. Saved Locations and Research retain their small local walkthroughs.
        if (!FieldUiNames.SAVED_LOCATIONS.equals(tool) && !"Research".equals(tool)) {
            if (usesTrainingArea(tool)) {
                confirmTrainingAreaAndReturnToMap(tool);
                return;
            }
            FieldTourState.start(this, tool);
            GuidedTourCoach.clear(this);
            returnToMap();
            return;
        }
        startLegacyTwoStepTour(tool, explainer, openAction, staysInField);
    }
""",
        """    private void startFieldToolTour(String tool, String explainer,
                                    Runnable openAction, boolean staysInField) {
        // All entry points use the same warning/clean-start gate. The approved legacy path rebuilds
        // its local explainer after MainActivity has prepared the map workspace.
        startFieldTourByName(tool);
        // marker: field-help-routes-through-global-tour-gate
    }
""",
        "field help tour gate",
    )

    # -------------------------------------------------------------------------
    # MainActivity: scoped HUD host, pending Field-tour consumer, user-confirmed cleanup,
    # main-tour gate, and Step-17 next-frame verification with one bounded recovery.
    # -------------------------------------------------------------------------
    ensure_replace(
        root, main, "mappedResearchFrameGeneration",
        """    private boolean researchTourEmptyRecoveryVisible;

    @Override
""",
        """    private boolean researchTourEmptyRecoveryVisible;
    private long mappedResearchFrameGeneration = -1L;
    private int mappedResearchFrameStep = -1;
    private boolean mappedResearchFrameCheckArmed;
    private boolean mappedResearchFrameVerified;
    private int mappedResearchFrameRecoveryCount;

    @Override
""",
        "Step-17 frame state",
    )

    # Main helper block inserted before onCreate.
    ensure_replace(
        root, main, "private MapHudCoordinator.Host mapHudHost()",
        """    @Override
    protected void onCreate(Bundle savedInstanceState) {
""",
        """    private MapHudCoordinator.Host mapHudHost() {
        return new MapHudCoordinator.Host() {
            @Override public boolean isExpanded(String surface) {
                if (MapHudCoordinator.SURFACE_FIELD.equals(surface)) {
                    return FieldMapController.isHudExpanded(MainActivity.this);
                }
                if (MapHudCoordinator.SURFACE_RESEARCH.equals(surface)) {
                    return researchAreaPanel != null && researchAreaPanel.isExpanded();
                }
                if (MapHudCoordinator.SURFACE_MAPPED_CONTEXT.equals(surface)) {
                    return mapView != null
                            && MapContextCloseController.forMap(mapView).isExpandedVisible();
                }
                return false;
            }

            @Override public boolean collapsePresentationOnly(String surface, long transitionId) {
                if (MapHudCoordinator.SURFACE_FIELD.equals(surface)) {
                    return FieldMapController.collapseHudForArbitration(
                            MainActivity.this, transitionId);
                }
                if (MapHudCoordinator.SURFACE_RESEARCH.equals(surface)) {
                    boolean collapsed = researchAreaPanel == null
                            || researchAreaPanel.collapsePresentationOnly();
                    if (collapsed) saveResearchSession();
                    return collapsed;
                }
                if (MapHudCoordinator.SURFACE_MAPPED_CONTEXT.equals(surface)) {
                    boolean collapsed = mapView == null
                            || MapContextCloseController.forMap(mapView).collapsePresentationOnly();
                    if (collapsed) saveResearchSession();
                    return collapsed;
                }
                return false;
            }
        };
    }

    public long beforeMapHudExpansion(String surface) {
        // Steps before the mapped-controls lesson need the Research workspace itself. Keep mapped
        // controls collapsed instead of allowing their automatic appearance to steal the HUD slot.
        if (MapHudCoordinator.SURFACE_MAPPED_CONTEXT.equals(surface)
                && GuidedTourState.isActive(this)
                && GuidedTourState.step(this) < GuidedTourState.STEP_CONTEXT_CONTROLS) {
            TourDebugLog.mainTourAction(this, "HUD_EXPANSION_DEFERRED_FOR_TOUR",
                    "surface=" + surface + " step=" + GuidedTourState.step(this));
            return -1L;
        }
        return MapHudCoordinator.beforeExpand(this, surface, mapHudHost());
    }

    public long beforeImplicitMapHudExpansion(String surface) {
        // Implicit chrome never steals an explicitly occupied HUD slot. It participates in the
        // coordinator by yielding to the current surface. Step 17+ is an explicit tour-directed
        // presentation and therefore uses the normal arbitration rule.
        if (!(GuidedTourState.isActive(this)
                && GuidedTourState.step(this) >= GuidedTourState.STEP_CONTEXT_CONTROLS)
                && MapHudCoordinator.expandedCount(mapHudHost()) > 0) {
            TourDebugLog.mainTourAction(this, "HUD_IMPLICIT_EXPANSION_DEFERRED",
                    "surface=" + surface + " occupied="
                            + MapHudCoordinator.expandedSummary(mapHudHost()));
            return -1L;
        }
        return beforeMapHudExpansion(surface);
    }

    public void afterMapHudExpansion(String surface, long transitionId) {
        MapHudCoordinator.afterExpand(this, surface, transitionId, mapHudHost());
    }

    private void prepareMapHudForUpcoming(String surface) {
        MapHudCoordinator.prepareForUpcoming(this, surface, mapHudHost());
    }

    private void openTracksForTourStart() {
        Intent field = new Intent(this, FieldActivity.class);
        field.putExtra(FieldActivity.EXTRA_SCREEN, "tracks");
        startActivity(field);
    }

    private boolean prepareWorkspaceForTourStart(String origin) {
        if (!TourStartCoordinator.ensureNoActiveTrack(this, origin, this::openTracksForTourStart)) {
            return false;
        }
        long transition = UiInvariantMonitor.begin(this, "tour-clean-start",
                "origin=" + origin + " hudBefore=" + MapHudCoordinator.expandedSummary(mapHudHost())
                        + " measure=" + FieldMapState.measurementActive(this)
                        + " nav=" + (FieldMapState.navigationTarget(this) != null));
        UiInvariantMonitor.state(this, transition, "TOUR_PREPARE_START",
                "origin=" + origin + " oldMainTour=" + GuidedTourState.isActive(this)
                        + " oldFieldTour=" + FieldTourState.active(this));
        GuidedTourCoach.clear(this);
        if (FieldTourState.active(this)) FieldTourState.clear(this);
        if (GuidedTourState.isActive(this)) GuidedTourState.exit(this);
        ProspectingAreaCreator.dismissSavedResearchPrompt(this);
        if (researchAreaPanel != null && researchAreaPanel.isExpanded()) {
            researchAreaPanel.collapsePresentationOnly();
        }
        if (mapView != null) {
            MapContextCloseController context = MapContextCloseController.forMap(mapView);
            if (context.isExpandedVisible()) context.collapsePresentationOnly();
        }
        boolean fieldClean = FieldMapController.prepareTemporaryWorkspaceForTour(this, transition);
        if (!fieldClean && FieldDatabase.get(this).getActiveTrack() != null) {
            UiInvariantMonitor.state(this, transition, "TOUR_PREPARE_ABORTED",
                    "origin=" + origin + " reason=active_track_started_during_gate");
            TourStartCoordinator.ensureNoActiveTrack(this, origin, this::openTracksForTourStart);
            return false;
        }
        MapHudCoordinator.reconcile(this, mapHudHost());
        boolean clean = fieldClean
                && MapHudCoordinator.expandedCount(mapHudHost()) == 0
                && !FieldMapState.measurementActive(this)
                && FieldMapState.navigationTarget(this) == null
                && FieldDatabase.get(this).getActiveTrack() == null;
        UiInvariantMonitor.invariant(this, transition, "tour_starts_from_clean_working_state", clean,
                "hud=" + MapHudCoordinator.expandedSummary(mapHudHost())
                        + " measure=" + FieldMapState.measurementActive(this)
                        + " nav=" + (FieldMapState.navigationTarget(this) != null));
        UiInvariantMonitor.state(this, transition, "TOUR_PREPARE_COMPLETE",
                "origin=" + origin + " clean=" + clean);
        return clean;
    }

    /** Called only after RockMapApplication has resumed/attached the live map Field controller. */
    public void onMapWorkspaceResumedForTour() {
        if (isFinishing() || isDestroyed()) return;
        MapHudCoordinator.reconcile(this, mapHudHost());
        TourStartCoordinator.PendingFieldTour pending =
                TourStartCoordinator.consumePendingFieldTour(this);
        if (pending == null) return;
        if (pending.trainingArea) {
            pendingTrainingFieldTool = pending.tool;
            pendingTrainingMainTopic = "";
            beginPendingTrainingLocationSetup();
            return;
        }
        if (!prepareWorkspaceForTourStart("field:" + pending.tool)) {
            TourStartCoordinator.queueFieldTour(this, pending.tool, false, pending.legacyLocal);
            return;
        }
        if (pending.legacyLocal) {
            Intent field = new Intent(this, FieldActivity.class);
            field.putExtra(FieldActivity.EXTRA_START_APPROVED_HELP_TOOL, pending.tool);
            startActivity(field);
            return;
        }
        FieldTourState.start(this, pending.tool);
        GuidedTourCoach.clear(this);
        FieldMapController.ensurePersistentEntry(this);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
""",
        "MainActivity HUD/tour coordination block",
    )

    # Research button explicitly prepares its future HUD slot; this is intentional action, not restore.
    ensure_replace(
        root, main, "research-upcoming-hud-prepared",
        """    private void showResearch() {
        if (GuidedTourState.isActive(this)
""",
        """    private void showResearch() {
        prepareMapHudForUpcoming(MapHudCoordinator.SURFACE_RESEARCH);
        // marker: research-upcoming-hud-prepared
        if (GuidedTourState.isActive(this)
""",
        "top-level Research HUD preparation",
    )
    ensure_replace(
        root, main, "research-panel-post-render-reconciled",
        """        if (configureView) configureResearchPanelForView(activeResearchView);
        saveResearchSession();
        FieldMapController.ensurePersistentEntry(this);
""",
        """        if (configureView) configureResearchPanelForView(activeResearchView);
        MapHudCoordinator.reconcile(this, mapHudHost());
        // marker: research-panel-post-render-reconciled
        saveResearchSession();
        FieldMapController.ensurePersistentEntry(this);
""",
        "Research post-render HUD reconciliation",
    )

    ensure_replace(
        root, main, "pending-research-result-hud-prepared",
        """    private void consumePendingResearchLaunch() {
        Intent pending = pendingResearchLaunchIntent;
        if (pending == null) return;
        pendingResearchLaunchIntent = null;
        handleResearchResult(pending);
    }
""",
        """    private void consumePendingResearchLaunch() {
        Intent pending = pendingResearchLaunchIntent;
        if (pending == null) return;
        pendingResearchLaunchIntent = null;
        prepareMapHudForUpcoming(MapHudCoordinator.SURFACE_RESEARCH);
        handleResearchResult(pending);
        // marker: pending-research-result-hud-prepared
    }
""",
        "cross-screen Research result HUD preparation",
    )

    ensure_replace(
        root, main, "saved-area-research-hud-prepared",
        """    private void openSavedProspectingAreaResearch(long areaId) {
        if (areaId <= 0L) return;
        if (researchAreaPanel != null) researchAreaPanel.prepareForExplicitOpen();
""",
        """    private void openSavedProspectingAreaResearch(long areaId) {
        if (areaId <= 0L) return;
        prepareMapHudForUpcoming(MapHudCoordinator.SURFACE_RESEARCH);
        if (researchAreaPanel != null) researchAreaPanel.prepareForExplicitOpen();
        // marker: saved-area-research-hud-prepared
""",
        "saved-area Research HUD preparation",
    )

    # Main tour entry: warning once; recursive training-location continuation uses two-arg method
    # and therefore does not warn a second time.
    ensure_replace(
        root, main, "main-tour-start-confirmation-gate",
        """    private void startTourTopic(String topic) {
        startTourTopic(topic, false);
    }
""",
        """    private void startTourTopic(String topic) {
        final String requestedTopic = topic == null ? GuidedTourState.TOPIC_FULL : topic;
        TourStartCoordinator.confirm(this, "Guided tour — " + requestedTopic,
                () -> startTourTopic(requestedTopic, false), this::openTracksForTourStart);
        // marker: main-tour-start-confirmation-gate
    }
""",
        "main tour warning gate",
    )
    ensure_replace(
        root, main, "main-tour-clean-start-applied",
        """        if (researchAreaPanel != null && researchAreaPanel.isVisible()) researchAreaPanel.closePanel();
        if (start <= GuidedTourState.STEP_FIND_MOUNT_ANTERO
""",
        """        if (!prepareWorkspaceForTourStart("main:" + topic)) return;
        // marker: main-tour-clean-start-applied
        if (researchAreaPanel != null && researchAreaPanel.isVisible()) researchAreaPanel.closePanel();
        if (start <= GuidedTourState.STEP_FIND_MOUNT_ANTERO
""",
        "main tour clean-start execution",
    )

    # Training Field tour cleanup occurs only after the training location has been selected.
    ensure_replace(
        root, main, "training-field-tour-clean-start-applied",
        """        if (!pendingTrainingFieldTool.isEmpty()) {
            String tool = pendingTrainingFieldTool;
            pendingTrainingFieldTool = "";
            pendingTrainingMainTopic = "";
            FieldTourState.start(this, tool);
            GuidedTourCoach.clear(this);
            FieldMapController.ensurePersistentEntry(this);
            return;
        }
""",
        """        if (!pendingTrainingFieldTool.isEmpty()) {
            String tool = pendingTrainingFieldTool;
            if (!prepareWorkspaceForTourStart("field:" + tool)) {
                pendingTrainingFieldTool = "";
                pendingTrainingMainTopic = "";
                TourStartCoordinator.queueFieldTour(this, tool, false, false);
                return;
            }
            pendingTrainingFieldTool = "";
            pendingTrainingMainTopic = "";
            FieldTourState.start(this, tool);
            GuidedTourCoach.clear(this);
            FieldMapController.ensurePersistentEntry(this);
            // marker: training-field-tour-clean-start-applied
            return;
        }
""",
        "training Field tour clean start",
    )

    # Step 17-19 force-render replaces the old logical-isShown short-circuit path.
    ensure_replace(
        root, main, "forcePrepareExpandedControlsForTour",
        """        if (step == GuidedTourState.STEP_CONTEXT_CONTROLS
                || step == GuidedTourState.STEP_CONTEXT_COLLAPSE) {
            context.prepareExpandedControls();
        } else if (step == GuidedTourState.STEP_CONTEXT_REOPEN) {
            context.prepareCollapsedControls();
        }
""",
        """        if (step == GuidedTourState.STEP_CONTEXT_CONTROLS
                || step == GuidedTourState.STEP_CONTEXT_COLLAPSE) {
            context.forcePrepareExpandedControlsForTour();
        } else if (step == GuidedTourState.STEP_CONTEXT_REOPEN) {
            context.forcePrepareCollapsedControlsForTour();
        }
""",
        "Step-17 mapped controls force render",
    )

    # Frame verifier helper inserted before mapped readiness method.
    ensure_replace(
        root, main, "private boolean ensureMappedResearchTourFrameVerified",
        """    private boolean mappedResearchPresentationReadyForTour(int step) {
""",
        """    private boolean ensureMappedResearchTourFrameVerified(int step,
                                                                long generation,
                                                                int attempt) {
        if (step != GuidedTourState.STEP_CONTEXT_CONTROLS
                && step != GuidedTourState.STEP_CONTEXT_COLLAPSE
                && step != GuidedTourState.STEP_CONTEXT_REOPEN) return true;
        if (mappedResearchFrameGeneration != generation || mappedResearchFrameStep != step) {
            mappedResearchFrameGeneration = generation;
            mappedResearchFrameStep = step;
            mappedResearchFrameCheckArmed = false;
            mappedResearchFrameVerified = false;
            mappedResearchFrameRecoveryCount = 0;
        }
        if (mappedResearchFrameVerified) return true;
        if (mappedResearchFrameCheckArmed || mapView == null) return false;

        MapContextCloseController context = MapContextCloseController.forMap(mapView);
        View displayed = context.getDisplayedContainer();
        if (displayed == null) return false;
        mappedResearchFrameCheckArmed = true;
        long transition = UiInvariantMonitor.begin(this, "mapped-research-frame",
                "step=" + step + " tourGeneration=" + generation
                        + " recovery=" + mappedResearchFrameRecoveryCount);
        UiInvariantMonitor.verifyNextFrame(this, transition,
                "mapped-research-step-" + step, displayed,
                () -> generation == guidedTourScheduleGeneration
                        && GuidedTourState.isActive(MainActivity.this)
                        && GuidedTourState.step(MainActivity.this) == step,
                () -> {
                    mappedResearchFrameCheckArmed = false;
                    mappedResearchFrameVerified = true;
                    showGuidedTourCoachWhenReady(step, generation, attempt);
                },
                () -> {
                    mappedResearchFrameCheckArmed = false;
                    if (generation != guidedTourScheduleGeneration
                            || !GuidedTourState.isActive(MainActivity.this)
                            || GuidedTourState.step(MainActivity.this) != step) return;
                    if (mappedResearchFrameRecoveryCount < 1) {
                        mappedResearchFrameRecoveryCount++;
                        UiInvariantMonitor.state(MainActivity.this, transition,
                                "UI_RECOVERY_ATTEMPT",
                                "step=" + step + " recovery=" + mappedResearchFrameRecoveryCount);
                        if (step == GuidedTourState.STEP_CONTEXT_REOPEN) {
                            context.forcePrepareCollapsedControlsForTour();
                        } else {
                            context.forcePrepareExpandedControlsForTour();
                        }
                        showGuidedTourCoachWhenReady(step, generation, attempt);
                    } else {
                        UiInvariantMonitor.state(MainActivity.this, transition,
                                "RESEARCH_TOUR_RENDER_UNRESOLVED",
                                "step=" + step + " recoveryExhausted=true");
                    }
                });
        return false;
    }

    private boolean mappedResearchPresentationReadyForTour(int step) {
""",
        "mapped Research frame verifier",
    )
    ensure_replace(
        root, main, "ensureMappedResearchTourFrameVerified(expectedStep, generation, attempt)",
        """        if (!mappedResearchPresentationReadyForTour(expectedStep)) {
            retryGuidedTourCoach(expectedStep, generation, attempt);
            return;
        }
        View requiredTarget = guidedTourReadinessTarget(expectedStep);
""",
        """        if (!mappedResearchPresentationReadyForTour(expectedStep)) {
            retryGuidedTourCoach(expectedStep, generation, attempt);
            return;
        }
        if (!ensureMappedResearchTourFrameVerified(expectedStep, generation, attempt)) return;
        View requiredTarget = guidedTourReadinessTarget(expectedStep);
""",
        "mapped Research frame gate",
    )

    # Exit invalidates pending render verification immediately.
    ensure_replace(
        root, main, "mappedResearchFrameVerified = false; // exit-tour-render-cancel",
        """    private void exitTour() {
        GuidedTourState.exit(this);
        lastPresentedGuidedTourStep = -1;
        GuidedTourCoach.clear(this);
    }
""",
        """    private void exitTour() {
        guidedTourScheduleGeneration++;
        mappedResearchFrameCheckArmed = false;
        mappedResearchFrameVerified = false; // exit-tour-render-cancel
        GuidedTourState.exit(this);
        lastPresentedGuidedTourStep = -1;
        GuidedTourCoach.clear(this);
    }
""",
        "tour exit invalidates render callbacks",
    )

    # Generic coach target next-frame observation. This never retries or mutates the target.
    ensure_replace(
        root, coach, "COACH_TARGET_FRAME_OBSERVED",
        """        TourDebugLog.coachShown(activity, requestGeneration, step, total, title, target,
                root instanceof DialogCoachHost);
        highlight(target);
        if (target != null) {
""",
        """        TourDebugLog.coachShown(activity, requestGeneration, step, total, title, target,
                root instanceof DialogCoachHost);
        highlight(target);
        if (target != null) {
            final View observedTarget = target;
            final long observedRequest = requestGeneration;
            long frameTransition = UiInvariantMonitor.begin(activity, "coach-target-frame",
                    "coachRequest=" + observedRequest + " step=" + step);
            UiInvariantMonitor.verifyNextFrame(activity, frameTransition,
                    "coach-step-" + step, observedTarget,
                    () -> isPendingRequestCurrent(activity, observedRequest),
                    () -> TourDebugLog.mapDiagnostic("COACH_TARGET_FRAME_OBSERVED",
                            "request=" + observedRequest + " step=" + step + " visible=true"),
                    () -> TourDebugLog.mapDiagnostic("COACH_TARGET_FRAME_MISSED",
                            "request=" + observedRequest + " step=" + step));
""",
        "generic coach target frame observation",
    )

    # Final static guardrails: the new helpers must remain presentation/debug scoped and must not
    # introduce dangerous APIs into this commit.
    forbidden = {
        "app/src/main/java/com/rockmap/app/MapHudCoordinator.java": (
            "setTrackStatus(", "deleteTrack(", "deleteArea(", "stopNavigation(", "clearMeasurement("
        ),
        "app/src/main/java/com/rockmap/app/UiInvariantMonitor.java": (
            "FieldDatabase", "FieldMapState", "ResearchSessionState", "MapContextCloseController"
        ),
    }
    for rel, tokens in forbidden.items():
        content = _text(root / rel)
        for token in tokens:
            if token in content:
                raise RuntimeError(f"scope guard failed: {rel} unexpectedly contains {token}")

    print("Commit-1 UI state/tour fixes injected successfully.")


def inject_ui_state_fixes(root: Path) -> None:
    # Transactional runner patch: a late anchor mismatch restores the exact post-baseline source
    # state before propagating the error. No half-injected APK can proceed to tests/build.
    mutable = [
        root / "app/src/main/java/com/rockmap/app/RockMapApplication.java",
        root / "app/src/main/java/com/rockmap/app/MainActivity.java",
        root / "app/src/main/java/com/rockmap/app/GuidedTourCoach.java",
        root / "app/src/main/java/com/rockmap/app/research/ResearchAreaPanelController.java",
        root / "app/src/main/java/com/rockmap/app/field/FieldMapController.java",
        root / "app/src/main/java/com/rockmap/app/field/FieldActivity.java",
        root / "app/src/main/java/com/rockmap/app/field/FieldDatabase.java",
        root / "app/src/main/java/com/rockmap/app/field/TrackRecordingService.java",
        root / "app/src/main/java/com/rockmap/app/map/MapContextCloseController.java",
    ]
    originals = {path: path.read_text(encoding="utf-8") for path in mutable if path.is_file()}
    try:
        _inject_ui_state_fixes_mutating(root)
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("Commit-1 UI injection rolled back after failure.")
        raise


if __name__ == "__main__":
    inject_ui_state_fixes(Path(__file__).resolve().parents[1])
