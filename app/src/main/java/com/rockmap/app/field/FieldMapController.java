package com.rockmap.app.field;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.MainActivity;
import com.rockmap.app.GuidedTourCoach;
import com.rockmap.app.RockMapDragHandle;
import com.rockmap.app.coordinates.CoordinateParser;
import com.rockmap.app.location.LocationRepository;
import com.rockmap.app.waypoints.WaypointEntity;
import com.rockmap.app.waypoints.WaypointRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.maplibre.android.style.expressions.Expression.get;
import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.Property.VISIBLE;
import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.fillColor;
import static org.maplibre.android.style.layers.PropertyFactory.fillOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;
import static org.maplibre.android.style.layers.PropertyFactory.textColor;
import static org.maplibre.android.style.layers.PropertyFactory.textField;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloColor;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloWidth;
import static org.maplibre.android.style.layers.PropertyFactory.textOffset;
import static org.maplibre.android.style.layers.PropertyFactory.textSize;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;

/**
 * Map-first field UX layered on top of the existing MainActivity without replacing its
 * verified GPS-centering implementation. Tracks, areas, marker labels, navigation and
 * measurement are rendered as local GeoJSON sources in the active MapLibre style.
 */
public final class FieldMapController implements LocationRepository.Listener {
    public static final String FIELD_BUTTON_TAG = "rockmap-field-entry";
    private static final WeakHashMap<Activity, WeakReference<FieldMapController>> INSTANCES = new WeakHashMap<>();

    /** Keep the persistent Field entry above temporary Research/context UI without changing Field state. */
    public static void ensurePersistentEntry(Activity activity) {
        if (activity == null) return;
        WeakReference<FieldMapController> ref;
        synchronized (INSTANCES) { ref = INSTANCES.get(activity); }
        FieldMapController controller = ref == null ? null : ref.get();
        if (controller == null) return;
        activity.runOnUiThread(() -> {
            controller.attach();
            controller.positionFieldButton();
            if (FieldTourState.active(activity)) {
                // MainActivity's normal resume path calls ensurePersistentEntry(). A Field tour
                // may have been started from FieldActivity immediately before returning here, so
                // resume the pending tour after the map controls have reattached.
                controller.main.post(controller::resumeActiveFieldTour);
            }
            if (controller.fieldButton != null) {
                controller.fieldButton.setVisibility(View.VISIBLE);
                controller.fieldButton.bringToFront();
                // Temporary Research/context views can finish layout a frame later. Reassert the
                // persistent entry after that layout settles so closing a heatmap/area cannot
                // strand Field behind or leave it invisible.
                controller.fieldButton.postDelayed(() -> {
                    controller.positionFieldButtonNow();
                    if (controller.fieldButton != null) {
                        controller.fieldButton.setVisibility(View.VISIBLE);
                        controller.fieldButton.bringToFront();
                    }
                }, 120L);
            }
        });
    }
    static final String HUD_TAG = "rockmap-field-map-hud";
    static final String COLLAPSED_TABS_TAG = "rockmap-field-collapsed-tabs";
    private static final String TRACK_TAB_TAG = "rockmap-collapsed-active-track";
    private static final String NAV_TAB_TAG = "rockmap-collapsed-navigation";
    private static final String MEASURE_TAB_TAG = "rockmap-collapsed-measure";
    private static final String COLLAPSED_DRAG_TAG = "rockmap-collapsed-drag-handle";
    private static final String TAP_CAPTURE_TAG = "rockmap-field-tap-capture";

    private static final String TRACK_SOURCE = "rockmap-field-track-source";
    private static final String TRACK_LAYER = "rockmap-field-track-layer";
    private static final String AREA_SOURCE = "rockmap-field-area-source";
    private static final String AREA_FILL = "rockmap-field-area-fill";
    private static final String AREA_LINE = "rockmap-field-area-line";
    private static final String FIELD_RECORD_SOURCE = "rockmap-field-record-source";
    private static final String FIELD_RECORD_LAYER = "rockmap-field-record-layer";
    private static final String FIELD_RECORD_LABEL = "rockmap-field-record-label";
    private static final String WAYPOINT_LABEL_SOURCE = "rockmap-waypoint-label-source";
    private static final String WAYPOINT_LABEL_LAYER = "rockmap-waypoint-label-layer";
    private static final String WAYPOINT_MIRROR_LAYER = "rockmap-waypoint-mirror-layer";
    private static final String NAV_TARGET_SOURCE = "rockmap-field-nav-target-source";
    private static final String NAV_TARGET_LAYER = "rockmap-field-nav-target-layer";
    private static final String NAV_TARGET_LABEL = "rockmap-field-nav-target-label";
    private static final String NAV_LINE_SOURCE = "rockmap-field-nav-line-source";
    private static final String NAV_LINE_LAYER = "rockmap-field-nav-line-layer";
    private static final String MEASURE_POINT_SOURCE = "rockmap-field-measure-point-source";
    private static final String MEASURE_POINT_LAYER = "rockmap-field-measure-point-layer";
    private static final String MEASURE_LINE_SOURCE = "rockmap-field-measure-line-source";
    private static final String MEASURE_LINE_LAYER = "rockmap-field-measure-line-layer";
    private static final String MEASURE_FILL_SOURCE = "rockmap-field-measure-fill-source";
    private static final String MEASURE_FILL_LAYER = "rockmap-field-measure-fill-layer";

    private static final String EXISTING_WAYPOINT_LAYER = "rockmap-waypoint-layer";

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final FieldDatabase db;
    private final WaypointRepository waypointRepository;
    private final LocationRepository locationRepository;
    private final ArrayList<GeoMath.Point> measurement = new ArrayList<>();

    private FrameLayout root;
    private ViewGroup controls;
    private MapView mapView;
    private MapLibreMap map;
    private Button fieldButton;
    private LinearLayout hud;
    private LinearLayout collapsedTabs;
    private boolean resumed;
    private boolean refreshRunning;
    private boolean measureActive;
    private String expandedTool;
    private boolean awaitingMapTap;
    private View tapCapture;
    private boolean measurementDragHandlerInstalled;
    private int draggingMeasurementIndex = -1;
    private Location latestNavigationLocation;
    private boolean navigationUpdatesStarted;
    private boolean cameraMoveListenerInstalled;
    private View.OnLayoutChangeListener fieldLayoutListener;
    private long cameraCommandGeneration;
    private long lastWaypointRefresh;
    private String trackJson = emptyCollection();
    private String areaJson = emptyCollection();
    private String fieldRecordJson = emptyCollection();
    private String waypointLabelJson = emptyCollection();
    private final int floatingTouchSlop;
    private float floatingDragDownRawX;
    private float floatingDragDownRawY;
    private int floatingDragStartLeft;
    private int floatingDragStartTop;
    private boolean floatingDragging;
    private boolean hudUserPositioned;
    private int hudUserLeft;
    private int hudUserTop;
    private boolean collapsedTabsUserPositioned;
    private int collapsedTabsUserLeft;
    private int collapsedTabsUserTop;
    private String lastFieldTourCoachKey = "";
    private AlertDialog activeFieldMenuDialog;

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshFieldSnapshot();
            main.postDelayed(this, db.getActiveTrack() == null ? 3500L : 1200L);
        }
    };

    public FieldMapController(Activity activity) {
        this.activity = activity;
        synchronized (INSTANCES) { INSTANCES.put(activity, new WeakReference<>(this)); }
        this.db = FieldDatabase.get(activity);
        this.waypointRepository = new WaypointRepository(activity);
        this.locationRepository = new LocationRepository(activity, this);
        this.floatingTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        this.expandedTool = FieldMapState.expandedTool(activity);
        this.measureActive = FieldMapState.measurementActive(activity);
        if (measureActive) measurement.addAll(FieldMapState.measurementPoints(activity));
    }

    public void attach() {
        if (!(activity instanceof MainActivity) || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        mapView = findMapView(decor);
        if (mapView == null) return;
        root = findMapRoot(mapView);
        if (root == null) return;
        controls = findBottomControls(root);
        clarifyMainGpsControl();

        fieldButton = (Button) root.findViewWithTag(FIELD_BUTTON_TAG);
        if (fieldButton == null) {
            fieldButton = new Button(activity);
            fieldButton.setTag(FIELD_BUTTON_TAG);
            fieldButton.setText("Field");
            fieldButton.setAllCaps(false);
            fieldButton.setTextSize(12f);
            fieldButton.setMinWidth(dp(82));
            fieldButton.setMinimumWidth(dp(82));
            fieldButton.setMinHeight(dp(48));
            fieldButton.setMinimumHeight(dp(48));
            fieldButton.setContentDescription("Open map-based field tools");
            // Do not flash the button at a guessed position before the bottom tray is measured.
            fieldButton.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    dp(92), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            params.setMargins(0, 0, dp(10), dp(112));
            root.addView(fieldButton, params);
        }
        fieldButton.setOnClickListener(v -> showFieldMenu());
        installHud();
        installCollapsedTabs();
        installMeasurementDragHandler();
        installFieldButtonLayoutTracking();
        positionFieldButton();
        bringFieldUiToFront();

        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            if (!cameraMoveListenerInstalled) {
                map.addOnCameraMoveStartedListener(reason -> {
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        beginCameraCommand();
                    }
                });
                cameraMoveListenerInstalled = true;
            }
            configureMapUi();
            applyCachedSources();
            consumeMapRequests();
        });
    }

    public void onResume() {
        resumed = true;
        expandedTool = FieldMapState.expandedTool(activity);
        if (!measureActive && FieldMapState.measurementActive(activity)) {
            measurement.clear();
            measurement.addAll(FieldMapState.measurementPoints(activity));
            measureActive = true;
        }
        attach();
        refreshNavigationState();
        main.removeCallbacks(refreshLoop);
        main.post(refreshLoop);
        main.postDelayed(this::resumeActiveFieldTour, 260L);
    }

    public void onPause() {
        resumed = false;
        main.removeCallbacks(refreshLoop);
        removeTapCapture();
        if (measureActive) FieldMapState.saveMeasurement(activity, measurement, true);
        locationRepository.stop();
        navigationUpdatesStarted = false;
    }

    public void destroy() {
        synchronized (INSTANCES) {
            WeakReference<FieldMapController> ref = INSTANCES.get(activity);
            if (ref != null && ref.get() == this) INSTANCES.remove(activity);
        }
        onPause();
        if (fieldLayoutListener != null) {
            if (root != null) root.removeOnLayoutChangeListener(fieldLayoutListener);
            if (controls != null) controls.removeOnLayoutChangeListener(fieldLayoutListener);
            fieldLayoutListener = null;
        }
        if (mapView != null && measurementDragHandlerInstalled) {
            mapView.setOnTouchListener(null);
            measurementDragHandlerInstalled = false;
        }
        worker.shutdownNow();
        waypointRepository.close();
    }

    /**
     * Keep the Field entry anchored above MainActivity's real bottom tray. The tray height changes
     * after window insets/layout settle, so a one-time guessed margin can briefly overlap a button
     * or leave Field underneath the tray. Reposition whenever either container changes size.
     */
    private void installFieldButtonLayoutTracking() {
        if (fieldLayoutListener != null || root == null) return;
        fieldLayoutListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            positionFieldButtonNow();
            updateMapUiInsets();
        };
        root.addOnLayoutChangeListener(fieldLayoutListener);
        if (controls != null) controls.addOnLayoutChangeListener(fieldLayoutListener);
    }

    private void positionFieldButton() {
        if (fieldButton == null) return;
        if (controls != null) controls.post(this::positionFieldButtonNow);
        else fieldButton.post(this::positionFieldButtonNow);
    }

    private void positionFieldButtonNow() {
        if (fieldButton == null) return;
        ViewGroup.LayoutParams raw = fieldButton.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams positioned = (FrameLayout.LayoutParams) raw;

        if (controls != null && (root == null || root.getHeight() <= 0
                || controls.getHeight() <= 0 || controls.getTop() <= 0)) {
            // The real tray geometry is not ready yet. Keep Field hidden for this frame rather than
            // showing it on top of another map button; the layout listener will place it next.
            fieldButton.setVisibility(View.INVISIBLE);
            return;
        }

        int bottomMargin = dp(112);
        if (root != null && controls != null) {
            bottomMargin = Math.max(dp(8), root.getHeight() - controls.getTop() + dp(8));
        }
        int rightMargin = dp(10);
        if (positioned.bottomMargin != bottomMargin || positioned.rightMargin != rightMargin) {
            positioned.bottomMargin = bottomMargin;
            positioned.rightMargin = rightMargin;
            fieldButton.setLayoutParams(positioned);
        }
        fieldButton.setVisibility(View.VISIBLE);
        bringFieldUiToFront();
    }

    /** FieldMapController is the single z-order owner for Field controls. */
    private void bringFieldUiToFront() {
        if (controls != null) controls.bringToFront();
        if (hud != null && hud.getVisibility() == View.VISIBLE) hud.bringToFront();
        if (collapsedTabs != null && collapsedTabs.getVisibility() == View.VISIBLE) collapsedTabs.bringToFront();
        if (fieldButton != null) fieldButton.bringToFront();
    }

    /**
     * Clarify the existing MainActivity GPS action without replacing its listener or precise-fix
     * implementation. This is presentation only: the button still calls MainActivity.locate().
     */
    private void clarifyMainGpsControl() {
        Button gps = findButtonByContentDescription(controls, "GPS");
        if (gps == null) return;
        gps.setText("Center GPS");
        gps.setTextSize(10.5f);
        gps.setContentDescription("Center GPS. Get a fresh precise GPS fix and center the map on your current location.");
    }

    private Button findButtonByContentDescription(View view, String description) {
        if (view == null || description == null) return null;
        CharSequence contentDescription = view.getContentDescription();
        if (view instanceof Button && contentDescription != null
                && description.contentEquals(contentDescription)) {
            return (Button) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButtonByContentDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void installHud() {
        if (root == null) return;
        View existing = root.findViewWithTag(HUD_TAG);
        if (existing instanceof LinearLayout) {
            hud = (LinearLayout) existing;
            return;
        }
        hud = new LinearLayout(activity);
        hud.setTag(HUD_TAG);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable hudBg = new GradientDrawable();
        hudBg.setColor(Color.argb(244, 255, 255, 255));
        hudBg.setStroke(dp(1), Color.rgb(165, 175, 180));
        hudBg.setCornerRadius(dp(8));
        hud.setBackground(hudBg);
        hud.setVisibility(View.GONE);
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(dp(280), Math.min(dp(520), screenWidth - dp(24)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        params.leftMargin = Math.max(dp(8), (screenWidth - width) / 2);
        params.topMargin = statusBarHeight() + dp(8);
        root.addView(hud, params);
        bringFieldUiToFront();
    }

    private void installCollapsedTabs() {
        if (root == null) return;
        View existing = root.findViewWithTag(COLLAPSED_TABS_TAG);
        if (existing instanceof LinearLayout) {
            collapsedTabs = (LinearLayout) existing;
            return;
        }
        collapsedTabs = new LinearLayout(activity);
        collapsedTabs.setTag(COLLAPSED_TABS_TAG);
        collapsedTabs.setOrientation(LinearLayout.VERTICAL);
        collapsedTabs.setGravity(Gravity.END);
        collapsedTabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        collapsedTabs.setVisibility(View.GONE);
        GradientDrawable tabsBg = new GradientDrawable();
        tabsBg.setColor(Color.argb(244, 255, 255, 255));
        tabsBg.setStroke(dp(1), Color.rgb(165, 175, 180));
        tabsBg.setCornerRadius(dp(8));
        collapsedTabs.setBackground(tabsBg);

        View dragHandle = RockMapDragHandle.labeled(activity, Color.rgb(82, 88, 90),
                (v, event) -> handleFloatingDrag(collapsedTabs, v, event),
                "Drag collapsed Field tool controls");
        dragHandle.setTag(COLLAPSED_DRAG_TAG);
        collapsedTabs.addView(dragHandle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        params.leftMargin = Math.max(dp(6), activity.getResources().getDisplayMetrics().widthPixels - dp(104));
        params.topMargin = Math.max(statusBarHeight() + dp(70),
                activity.getResources().getDisplayMetrics().heightPixels / 2 - dp(90));
        root.addView(collapsedTabs, params);
    }

    private void configureMapUi() {
        if (map == null) return;
        try {
            map.getUiSettings().setCompassEnabled(true);
            map.getUiSettings().setCompassFadeFacingNorth(false);
            map.getUiSettings().setCompassGravity(Gravity.TOP | Gravity.END);
            updateMapUiInsets();
        } catch (RuntimeException ignored) {
            // A map UI decoration must never destabilize the field map.
        }
    }

    private void updateMapUiInsets() {
        if (map == null) return;
        try {
            int top = statusBarHeight() + dp(8);
            if (hud != null && hud.getVisibility() == View.VISIBLE && hud.getHeight() > 0
                    && hud.getTop() <= top + dp(24)) {
                top = Math.max(top, hud.getBottom() + dp(8));
            }
            map.getUiSettings().setCompassMargins(dp(8), top, dp(8), dp(8));
        } catch (RuntimeException ignored) {
            // Keep the map usable even if MapLibre is between UI/style states.
        }
    }

    private void setExpandedTool(String tool) {
        setExpandedToolValue(tool);
        renderHud();
    }

    private void setExpandedToolValue(String tool) {
        expandedTool = tool;
        FieldMapState.setExpandedTool(activity, tool);
    }

    private void renderHud() {
        if (hud == null) return;
        installCollapsedTabs();
        hud.removeAllViews();
        removeCollapsedTab(TRACK_TAB_TAG);
        removeCollapsedTab(NAV_TAB_TAG);
        removeCollapsedTab(MEASURE_TAB_TAG);

        FieldDatabase.Track activeTrack = db.getActiveTrack();
        FieldDatabase.Track viewedTrack = null;
        long viewedTrackId = FieldMapState.selectedTrackDetail(activity);
        if (viewedTrackId >= 0L) {
            viewedTrack = db.getTrack(viewedTrackId);
            if (viewedTrack == null) FieldMapState.clearViewedMapContext(activity);
            if (activeTrack != null && viewedTrack != null && activeTrack.id == viewedTrack.id) viewedTrack = null;
        }
        FieldMapState.NavigationTarget target = FieldMapState.navigationTarget(activity);

        boolean trackActive = activeTrack != null || viewedTrack != null;
        boolean navigationActive = target != null;
        boolean measurementActive = measureActive;
        if ((FieldMapState.TOOL_TRACK.equals(expandedTool) && !trackActive)
                || (FieldMapState.TOOL_NAVIGATE.equals(expandedTool) && !navigationActive)
                || (FieldMapState.TOOL_MEASURE.equals(expandedTool) && !measurementActive)) {
            expandedTool = null;
            FieldMapState.setExpandedTool(activity, null);
        }

        boolean expanded = false;
        if (trackActive) {
            if (FieldMapState.TOOL_TRACK.equals(expandedTool)) {
                addTrackHud(activeTrack, viewedTrack);
                expanded = true;
            } else {
                addCollapsedTab(TRACK_TAB_TAG, FieldUiNames.TRACK_SHORT,
                        v -> setExpandedTool(FieldMapState.TOOL_TRACK));
            }
        }

        if (navigationActive) {
            if (FieldMapState.TOOL_NAVIGATE.equals(expandedTool)) {
                addNavigationHud(target);
                expanded = true;
            } else {
                addCollapsedTab(NAV_TAB_TAG, FieldUiNames.NAVIGATE_SHORT,
                        v -> setExpandedTool(FieldMapState.TOOL_NAVIGATE));
            }
        }

        if (measurementActive) {
            if (FieldMapState.TOOL_MEASURE.equals(expandedTool)) {
                addMeasureHud();
                expanded = true;
            } else {
                addCollapsedTab(MEASURE_TAB_TAG, FieldUiNames.MEASURE_SHORT,
                        v -> setExpandedTool(FieldMapState.TOOL_MEASURE));
            }
        }

        hud.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (collapsedTabs != null) {
            collapsedTabs.setVisibility(collapsedToolCount() > 0 ? View.VISIBLE : View.GONE);
            if (collapsedTabs.getVisibility() == View.VISIBLE) positionCollapsedTabsIfNeeded();
        }
        bringFieldUiToFront();
        positionFieldButton();
        if (hud != null) {
            hud.post(this::updateMapUiInsets);
            hud.post(this::showActiveMapFieldTourCoach);
        }
    }

    private void addTrackHud(FieldDatabase.Track activeTrack, FieldDatabase.Track viewedTrack) {
        String title = FieldUiNames.TRACK;
        if (activeTrack != null && viewedTrack == null) title += " — " + activeTrack.name;
        else if (activeTrack == null && viewedTrack != null) title += " — " + viewedTrack.name;
        View header = panelHeader(title, FieldUiNames.TRACK_SHORT,
                v -> setExpandedTool(null));
        header.setTag("rockmap-track-header");
        hud.addView(header);

        if (activeTrack != null) {
            List<GeoMath.Point> points = db.getTrackPoints(activeTrack.id);
            String prefix = viewedTrack == null ? "" : "Recording — " + activeTrack.name + "\n";
            TextView status = hudText(prefix
                    + (FieldDatabase.TRACK_PAUSED.equals(activeTrack.status) ? "Paused" : "Recording")
                    + " · " + points.size() + " points · "
                    + GeoMath.distanceLabel(GeoMath.pathDistanceMeters(points))
                    + "\nThe line continues recording while this panel is collapsed or another tool is open.");
            status.setTag("rockmap-track-status");
            hud.addView(status);
            LinearLayout row = buttonRow();
            if (FieldDatabase.TRACK_PAUSED.equals(activeTrack.status)) {
                Button resume = hudButton("Resume", v -> {
                    if (FieldTourState.is(activity, FieldUiNames.TRACK, 7)) {
                        FieldTourState.step(activity, 8);
                        lastFieldTourCoachKey = "";
                    }
                    trackCommand(TrackRecordingService.ACTION_RESUME, activeTrack.id);
                    main.postDelayed(this::renderHud, 520L);
                });
                resume.setTag("rockmap-track-resume");
                row.addView(resume, weight());
            } else {
                Button pause = hudButton("Pause", v -> {
                    if (FieldTourState.is(activity, FieldUiNames.TRACK, 6)) {
                        FieldTourState.step(activity, 7);
                        lastFieldTourCoachKey = "";
                    }
                    trackCommand(TrackRecordingService.ACTION_PAUSE, activeTrack.id);
                    main.postDelayed(this::renderHud, 520L);
                });
                pause.setTag("rockmap-track-pause");
                row.addView(pause, weight());
            }
            Button stop = hudButton("Stop", v -> {
                if (FieldTourState.is(activity, FieldUiNames.TRACK, 10)) {
                    FieldTourState.step(activity, 11);
                    lastFieldTourCoachKey = "";
                }
                confirmStopTrack(activeTrack);
            });
            stop.setTag("rockmap-track-stop");
            row.addView(stop, weight());
            Button tracks = hudButton("Tracks", v -> openFieldScreen("tracks"));
            tracks.setTag("rockmap-track-list");
            row.addView(tracks, weight());
            hud.addView(row);
        }

        if (viewedTrack != null) {
            if (activeTrack != null) hud.addView(divider());
            List<GeoMath.Point> points = db.getTrackPoints(viewedTrack.id);
            FieldDatabase.Track selected = viewedTrack;
            TextView viewedStatus = hudText("Viewing — " + selected.name + "\n"
                    + points.size() + " points · " + GeoMath.distanceLabel(GeoMath.pathDistanceMeters(points))
                    + "\nSTART and END appear once the map is zoomed in enough to use them.");
            viewedStatus.setTag("rockmap-track-viewed-status");
            hud.addView(viewedStatus);

            LinearLayout first = buttonRow();
            Button backtrack = hudButton("Backtrack", v -> {
                if (points.size() < 2) return;
                FieldMapState.showTrack(activity, selected.id);
                FieldMapState.clearViewedMapContext(activity);
                startNavigation("Start of " + selected.name, points.get(0));
            });
            backtrack.setTag("rockmap-track-backtrack");
            first.addView(backtrack, weight());
            Button hide = hudButton("Hide", v -> {
                FieldMapState.hideTrack(activity, selected.id);
                FieldMapState.clearViewedMapContext(activity);
                refreshFieldSnapshot();
                applyCachedSources();
                renderHud();
                toast("Track hidden. Reopen it from Field > Tracks to show it again.");
            });
            hide.setTag("rockmap-track-hide");
            first.addView(hide, weight());
            Button delete = hudButton("Delete", v -> new AlertDialog.Builder(activity)
                    .setTitle("Delete track?")
                    .setMessage("Permanently remove “" + selected.name + "” and all of its recorded points?")
                    .setPositiveButton("Delete", (d, w) -> {
                        db.deleteTrack(selected.id);
                        FieldMapState.clearViewedMapContext(activity);
                        refreshFieldSnapshot();
                        applyCachedSources();
                        renderHud();
                        toast("Track deleted.");
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            delete.setTag("rockmap-track-delete");
            first.addView(delete, weight());
            hud.addView(first);

            LinearLayout second = buttonRow();
            Button allTracks = hudButton("All tracks", v -> {
                FieldMapState.clearViewedMapContext(activity);
                if (activeTrack == null) setExpandedTool(null);
                openFieldScreen("tracks");
            });
            allTracks.setTag("rockmap-track-all");
            second.addView(allTracks, weight());
            Button close = hudButton("Close map view", v -> {
                FieldMapState.clearViewedMapContext(activity);
                if (activeTrack == null) setExpandedTool(null);
                else renderHud();
                applyCachedSources();
            });
            close.setTag("rockmap-track-close-view");
            second.addView(close, weight());
            hud.addView(second);
        }
    }

    private void addNavigationHud(FieldMapState.NavigationTarget target) {
        hud.addView(panelHeader(FieldUiNames.NAVIGATE + " — " + target.name,
                FieldUiNames.NAVIGATE_SHORT, v -> setExpandedTool(null)));
        String status;
        if (latestNavigationLocation == null) {
            status = "Getting a GPS fix…\nTarget: " + target.point.decimal();
        } else {
            GeoMath.Point current = point(latestNavigationLocation);
            double distance = GeoMath.distanceMeters(current, target.point);
            double bearing = GeoMath.initialBearingDegrees(current, target.point);
            status = "Distance: " + GeoMath.distanceLabel(distance)
                    + " · Bearing: " + String.format(Locale.US, "%.0f° %s", bearing, GeoMath.cardinal(bearing))
                    + "\nDirectional guidance only; RockMap does not calculate a safe, legal, or practical route.";
        }
        TextView statusView = hudText(status);
        statusView.setTag("rockmap-nav-status");
        hud.addView(statusView);
        LinearLayout row = buttonRow();
        Button frame = hudButton("Frame", v -> {
            frameNavigation(target);
            if (FieldTourState.is(activity, FieldUiNames.NAVIGATE, 8)) {
                FieldTourState.step(activity, 9);
                lastFieldTourCoachKey = "";
                renderHud();
            }
        });
        frame.setTag("rockmap-nav-frame");
        Button targetButton = hudButton("Target", v -> {
            centerExplicit(target.point, 16d);
            if (FieldTourState.is(activity, FieldUiNames.NAVIGATE, 9)) {
                FieldTourState.step(activity, 10);
                lastFieldTourCoachKey = "";
                renderHud();
            }
        });
        targetButton.setTag("rockmap-nav-target");
        Button stop = hudButton("Stop", v -> {
            boolean touring = FieldTourState.is(activity, FieldUiNames.NAVIGATE, 10);
            stopNavigation();
            if (touring) finishActiveFieldTour();
        });
        stop.setTag("rockmap-nav-stop");
        row.addView(frame, weight());
        row.addView(targetButton, weight());
        row.addView(stop, weight());
        hud.addView(row);
    }

    private void addMeasureHud() {
        View header = panelHeader(
                FieldUiNames.MEASURE + " — " + measurement.size() + " point" + (measurement.size() == 1 ? "" : "s"),
                FieldUiNames.MEASURE_SHORT,
                v -> {
                    removeTapCapture();
                    setExpandedTool(null);
                });
        header.setTag("rockmap-measure-header");
        hud.addView(header);
        TextView summary = hudText(measurementSummary());
        summary.setTag("rockmap-measure-summary");
        hud.addView(summary);
        if (!measurement.isEmpty()) {
            TextView dragNote = hudText("Drag any measurement point on the map to reshape the line or polygon.");
            dragNote.setTag("rockmap-measure-drag-note");
            hud.addView(dragNote);
        }

        LinearLayout first = buttonRow();
        Button tapMap = hudButton(awaitingMapTap ? "Cancel tap" : "Tap map", v -> handleTourAwareTapMap());
        tapMap.setTag(awaitingMapTap ? "rockmap-measure-cancel-tap" : "rockmap-measure-tap-map");
        first.addView(tapMap, weight());
        Button addGps = hudButton("Add GPS", v -> addGpsMeasurement());
        addGps.setTag("rockmap-measure-add-gps");
        addGps.setContentDescription("Add GPS. Get a fresh precise GPS fix and add your current location as the next measurement point.");
        first.addView(addGps, weight());
        Button saved = hudButton("Saved", v -> chooseSavedMeasurement());
        saved.setTag("rockmap-measure-saved");
        first.addView(saved, weight());
        Button field = hudButton("Field", v -> chooseFieldRecordMeasurement());
        field.setTag("rockmap-measure-field");
        first.addView(field, weight());
        hud.addView(first);

        LinearLayout second = buttonRow();
        Button paste = hudButton("Paste", v -> {
            if (FieldTourState.is(activity, FieldUiNames.MEASURE, 11)) {
                FieldTourState.step(activity, 12);
                lastFieldTourCoachKey = "";
            }
            pasteMeasurement();
        });
        paste.setTag("rockmap-measure-paste");
        second.addView(paste, weight());
        Button undo = hudButton("Undo", v -> {
            undoMeasurement();
            advanceMeasurementTourAfterUndo();
        });
        undo.setTag("rockmap-measure-undo");
        second.addView(undo, weight());
        Button done = hudButton("Done", v -> finishMeasurement());
        done.setTag("rockmap-measure-done");
        second.addView(done, weight());
        hud.addView(second);

        if (measurement.size() >= 3) {
            Button save = hudButton("Save as Prospecting Area", v -> {
                if (FieldTourState.is(activity, FieldUiNames.MEASURE, 19)) {
                    FieldTourState.step(activity, 20);
                    FieldTourState.text(activity, "name");
                    lastFieldTourCoachKey = "";
                } else if (FieldTourState.is(activity, FieldUiNames.PROSPECTING_AREAS, 10)) {
                    FieldTourState.step(activity, 11);
                    FieldTourState.text(activity, "name");
                    lastFieldTourCoachKey = "";
                }
                saveMeasurementArea();
            });
            save.setTag("rockmap-measure-save-area");
            save.setTextSize(13f);
            save.setContentDescription("Save this temporary measured polygon as a persistent Prospecting Area");
            hud.addView(save, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
            hud.addView(hudText("This measurement is temporary until you save it as a Prospecting Area."));
        } else {
            hud.addView(hudText("Add at least 3 points to save a Prospecting Area."));
        }
    }

    private void handleTourAwareTapMap() {
        if (awaitingMapTap) {
            removeTapCapture();
            if (FieldTourState.is(activity, FieldUiNames.MEASURE, 3)) {
                FieldTourState.step(activity, 4);
                FieldTourState.text(activity, "");
                lastFieldTourCoachKey = "";
            }
            renderHud();
            return;
        }

        if (FieldTourState.is(activity, FieldUiNames.MEASURE, 2)) {
            FieldTourState.step(activity, 3);
            lastFieldTourCoachKey = "";
        } else if (FieldTourState.is(activity, FieldUiNames.MEASURE, 4)) {
            FieldTourState.step(activity, 5);
            FieldTourState.text(activity, "map");
            lastFieldTourCoachKey = "";
        } else if (FieldTourState.is(activity, FieldUiNames.MEASURE)
                && (FieldTourState.step(activity) == 14
                || FieldTourState.step(activity) == 15
                || FieldTourState.step(activity) == 16)) {
            FieldTourState.text(activity, "map");
            lastFieldTourCoachKey = "";
        } else if (FieldTourState.is(activity, FieldUiNames.PROSPECTING_AREAS)
                && (FieldTourState.step(activity) == 4
                || FieldTourState.step(activity) == 5
                || FieldTourState.step(activity) == 6
                || FieldTourState.step(activity) == 9)) {
            FieldTourState.text(activity, "map");
            lastFieldTourCoachKey = "";
        }
        beginOneShotMapTap();
        renderHud();
    }

    private void advanceMeasurementTourAfterUndo() {
        if (!FieldTourState.active(activity)) return;
        if (FieldTourState.is(activity, FieldUiNames.MEASURE)) {
            int step = FieldTourState.step(activity);
            String phase = FieldTourState.text(activity);
            if (step == 7) {
                FieldTourState.step(activity, 8);
            } else if ("undo".equals(phase) && step >= 8 && step <= 10) {
                FieldTourState.text(activity, "");
                FieldTourState.step(activity, step + 1);
            } else if (step == 13 && "undo".equals(phase)) {
                FieldTourState.text(activity, "");
                FieldTourState.step(activity, 14);
            }
            lastFieldTourCoachKey = "";
            renderHud();
        } else if (FieldTourState.is(activity, FieldUiNames.PROSPECTING_AREAS, 8)) {
            FieldTourState.step(activity, 9);
            FieldTourState.text(activity, "");
            lastFieldTourCoachKey = "";
            renderHud();
        }
    }

    private void showFieldMenu() {
        if (activeFieldMenuDialog != null && activeFieldMenuDialog.isShowing()) {
            if (FieldTourState.active(activity) && FieldTourState.step(activity) == 1) {
                String tourTool = FieldTourState.tool(activity);
                View target = activeFieldMenuDialog.getWindow() == null ? null
                        : activeFieldMenuDialog.getWindow().getDecorView()
                        .findViewWithTag(fieldMenuTourTag(tourTool));
                if (target != null) {
                    final AlertDialog existingDialog = activeFieldMenuDialog;
                    target.post(() -> showUnifiedFieldMenuTourCoach(
                            existingDialog, target, tourTool));
                }
            }
            return;
        }

        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = dialogBox();
        box.addView(dialogActionWithHelp(
                FieldUiNames.TRACK,
                "Record a track and see it build live on this map.",
                "Tracks record a live GPS line. Start, pause, resume, stop, reopen, and export recorded tracks. The line remains available after recording stops until you hide or delete the track.",
                FieldUiNames.TRACK,
                holder,
                v -> { holder[0].dismiss(); openFieldScreen("tracks"); }));
        box.addView(dialogActionWithHelp(
                FieldUiNames.NAVIGATE,
                "Choose a Saved Location, Field Record, or coordinate and follow a live map line.",
                "Navigate provides directional guidance from your GPS position to a Saved Location, Field Record, or entered coordinate. RockMap does not calculate a safe, legal, or practical route. The guidance line does not account for terrain, roads, private property, closures, hazards, or whether a route is passable.",
                FieldUiNames.NAVIGATE,
                holder,
                v -> {
                    holder[0].dismiss();
                    showNavigateMenu();
                }));
        box.addView(dialogActionWithHelp(
                FieldUiNames.MEASURE,
                "Tap map points or use GPS/saved records; see distance and area directly here.",
                "Measure is a temporary map tool. Add map, GPS, Saved Location, or Field Record points to measure distance and area. Save a polygon as a Prospecting Area when you want to keep it.",
                FieldUiNames.MEASURE,
                holder,
                v -> { holder[0].dismiss(); startMeasurement(); }));
        box.addView(dialogActionWithHelp(
                FieldUiNames.FIELD_RECORDS,
                "Create, edit, photograph, navigate to, and research saved field observations.",
                "Field Records are saved observations that can include category, mineral, sample ID, notes, photo, GPS accuracy, elevation, map actions, and location-based Research.",
                FieldUiNames.FIELD_RECORDS,
                holder,
                v -> { holder[0].dismiss(); openFieldScreen("records"); }));
        box.addView(dialogActionWithHelp(
                FieldUiNames.PROSPECTING_AREAS,
                "Create, open, analyze, and manage saved prospecting areas.",
                "Prospecting Areas are saved polygons. Open them on the map, manage their visibility, and analyze their geography with Research. Spatial overlap is research context, not a prediction or permission to collect.",
                FieldUiNames.PROSPECTING_AREAS,
                holder,
                v -> { holder[0].dismiss(); openFieldScreen("areas"); }));
        box.addView(dialogActionWithHelp(
                FieldUiNames.IMPORT,
                "Import GPX, KML, or GeoJSON files into RockMap.",
                "Import accepts GPX, KML, and GeoJSON. Imported objects remain associated with their source import so you can review or remove that batch without deleting unrelated RockMap data.",
                FieldUiNames.IMPORT,
                holder,
                v -> { holder[0].dismiss(); openFieldScreen("import"); }));
        box.addView(dialogActionWithHelp(
                FieldUiNames.IMPORTED_DATA,
                "Review imported files, show their contents on the map, or remove one import safely.",
                "Imported Data lists the files RockMap has imported. Open a batch to inspect its Saved Locations, Tracks, and Prospecting Areas, show them on the map, or remove only that import.",
                FieldUiNames.IMPORTED_DATA,
                holder,
                v -> { holder[0].dismiss(); openFieldScreen("imports"); }));
        box.addView(dialogActionWithHelp(
                FieldUiNames.EXPORT,
                "Export Saved Locations, Tracks, Field Records, Prospecting Areas, imported files, or combined field data.",
                "Export Data lets you choose the records and output format deliberately. Exporting creates a copy for sharing or use elsewhere and does not delete the original RockMap records.",
                FieldUiNames.EXPORT,
                holder,
                v -> { holder[0].dismiss(); openFieldScreen("export"); }));
        box.addView(dialogActionWithHelp(
                FieldUiNames.COORDINATES,
                "Convert decimal, DDM, DMS, UTM, and MGRS coordinates.",
                "Coordinates converts one location among decimal degrees, DDM, DMS, WGS84 UTM, and MGRS. It is a conversion and reference tool; it does not change the accuracy of a GPS fix.",
                FieldUiNames.COORDINATES,
                holder,
                v -> { holder[0].dismiss(); openFieldScreen("coordinates"); }));
        box.addView(dialogActionWithHelp(
                FieldUiNames.VISIBILITY,
                "Choose which Tracks, Prospecting Areas, Field Records, and Saved Location labels appear on the map.",
                "Field Visibility controls which Field objects are drawn on the map. Hiding an item type changes display only; it does not delete the underlying saved records.",
                null,
                holder,
                v -> {
                    holder[0].dismiss();
                    showVisibilityMenu();
                }));
        holder[0] = new AlertDialog.Builder(activity)
                .setTitle("Field tools")
                .setView(scrollDialog(box))
                .setNegativeButton("Close", null)
                .create();
        activeFieldMenuDialog = holder[0];
        holder[0].setOnDismissListener(d -> {
            if (activeFieldMenuDialog == holder[0]) activeFieldMenuDialog = null;
            if (FieldTourState.active(activity) && FieldTourState.step(activity) == 1) {
                // Closing the Field menu while its first tour step is active ends only this tour.
                FieldTourState.finish(activity);
                lastFieldTourCoachKey = "";
                GuidedTourCoach.clear(activity);
            }
        });
        holder[0].show();
        if (FieldTourState.active(activity) && FieldTourState.step(activity) == 1) {
            String tourTool = FieldTourState.tool(activity);
            View target = box.findViewWithTag(fieldMenuTourTag(tourTool));
            if (target != null) {
                target.post(() -> {
                    target.requestFocus();
                    showUnifiedFieldMenuTourCoach(holder[0], target, tourTool);
                });
            }
        }
    }

    private String fieldMenuTourTag(String tool) {
        return "rockmap-field-menu-tour:" + (tool == null ? "" : tool);
    }

    private View dialogActionWithHelp(String title, String detail, String helpText,
                                      String guidedTourTool, AlertDialog[] fieldDialog,
                                      View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(8), dp(8), dp(10));
        card.setMinimumHeight(dp(68));

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);

        TextView h = new TextView(activity);
        h.setText(title + "  ›");
        h.setTextSize(15.5f);
        h.setTextColor(Color.rgb(30, 85, 145));
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.setSingleLine(true);
        h.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(h, new LinearLayout.LayoutParams(0, dp(40), 1f));

        View help = compactHelpButton("Help for " + title, v ->
                showFieldMenuHelp(title, helpText, guidedTourTool, fieldDialog));
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        helpParams.setMargins(dp(5), 0, 0, 0);
        heading.addView(help, helpParams);
        card.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

        TextView d = new TextView(activity);
        d.setText(detail);
        d.setTextSize(12.5f);
        d.setTextColor(Color.rgb(80, 80, 80));
        d.setPadding(0, dp(1), dp(4), 0);
        card.addView(d);
        card.setClickable(true);
        card.setFocusable(true);
        card.setTag(fieldMenuTourTag(title));
        card.setOnClickListener(v -> {
            if (FieldTourState.is(activity, title, 1)) {
                FieldTourState.step(activity, 2);
                lastFieldTourCoachKey = "";
                GuidedTourCoach.clear(activity);
            }
            if (listener != null) listener.onClick(v);
        });
        return card;
    }

    private View compactHelpButton(String description, View.OnClickListener listener) {
        FrameLayout touch = new FrameLayout(activity);
        touch.setClickable(true);
        touch.setFocusable(true);
        touch.setContentDescription(description);
        touch.setOnClickListener(listener);

        TextView icon = new TextView(activity);
        icon.setText("?");
        icon.setTextSize(14f);
        icon.setTextColor(Color.rgb(30, 85, 145));
        icon.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.rgb(238, 244, 251));
        background.setStroke(dp(1), Color.rgb(126, 166, 207));
        icon.setBackground(background);
        touch.addView(icon, new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER));
        return touch;
    }

    private void showFieldMenuHelp(String tool, String message, String guidedTourTool,
                                   AlertDialog[] fieldDialog) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(tool + " help")
                .setMessage(message)
                .setPositiveButton("Close", null);
        if (guidedTourTool != null && !guidedTourTool.trim().isEmpty()) {
            builder.setNeutralButton("Start guided tour", (d, w) -> {
                // Keep the existing Field menu alive underneath this help dialog. Dismissing and
                // immediately reopening it caused the old menu's asynchronous onDismiss callback
                // to cancel the newly started tour before its first coach could render.
                FieldTourState.start(activity, guidedTourTool);
                lastFieldTourCoachKey = "";
                GuidedTourCoach.clear(activity);
                final AlertDialog field = fieldDialog != null && fieldDialog.length > 0
                        ? fieldDialog[0] : null;
                main.post(() -> {
                    if (field != null && field.isShowing() && field.getWindow() != null) {
                        View target = field.getWindow().getDecorView()
                                .findViewWithTag(fieldMenuTourTag(guidedTourTool));
                        if (target != null) {
                            showUnifiedFieldMenuTourCoach(field, target, guidedTourTool);
                            return;
                        }
                    }
                    showFieldMenu();
                });
            });
        }
        builder.show();
    }

    private void showNavigateMenu() {
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = dialogBox();
        if (FieldMapState.navigationTarget(activity) != null) {
            box.addView(dialogAction("Stop current navigation", "Remove the target and bearing line from the map.", v -> {
                holder[0].dismiss(); stopNavigation();
            }));
        }
        View savedAction = dialogAction("Saved Location", "Choose one of your normal RockMap Saved Locations.", v -> {
            holder[0].dismiss(); chooseSavedNavigation();
        });
        savedAction.setTag("rockmap-navigate-saved");
        box.addView(savedAction);
        View fieldAction = dialogAction("Field Record", "Choose a field observation or sample record.", v -> {
            holder[0].dismiss(); chooseFieldRecordNavigation();
        });
        fieldAction.setTag("rockmap-navigate-field");
        box.addView(fieldAction);
        View coordinateAction = dialogAction("Enter coordinates",
                "Paste or type a latitude/longitude supported by RockMap.", v -> {
                    if (FieldTourState.is(activity, FieldUiNames.NAVIGATE, 4)) {
                        FieldTourState.step(activity, 5);
                        lastFieldTourCoachKey = "";
                        GuidedTourCoach.clear(activity);
                    }
                    holder[0].dismiss();
                    enterNavigationCoordinate();
                });
        coordinateAction.setTag("rockmap-navigate-coordinates");
        box.addView(coordinateAction);
        holder[0] = new AlertDialog.Builder(activity).setTitle("Navigate on map").setView(box)
                .setNegativeButton("Close", null).create();
        holder[0].show();
        if (FieldTourState.is(activity, FieldUiNames.NAVIGATE)
                && FieldTourState.step(activity) >= 2 && FieldTourState.step(activity) <= 4) {
            holder[0].setOnDismissListener(d -> {
                int step = FieldTourState.step(activity);
                if (FieldTourState.is(activity, FieldUiNames.NAVIGATE)
                        && step >= 2 && step <= 4) {
                    finishActiveFieldTour();
                }
            });
            showNavigateMenuTourCoach(holder[0], savedAction, fieldAction, coordinateAction);
        }
    }

    private void showNavigateMenuTourCoach(AlertDialog dialog, View saved, View field, View coordinates) {
        if (dialog == null || !FieldTourState.is(activity, FieldUiNames.NAVIGATE)) return;
        int step = FieldTourState.step(activity);
        if (step < 2 || step > 4) return;
        FrameLayout host = dialogTourRoot(dialog);
        if (host == null) return;
        View target;
        String title;
        String body;
        String action;
        String primary = "Continue";
        Runnable next;
        if (step == 2) {
            target = saved;
            title = "Saved Location target";
            body = "Choose Saved Location when the destination is one of your normal saved map points.";
            action = "Review “Saved Location”.";
            next = () -> { FieldTourState.step(activity, 3); showNavigateMenuTourCoach(dialog, saved, field, coordinates); };
        } else if (step == 3) {
            target = field;
            title = "Field Record target";
            body = "Choose Field Record when the destination is a saved field observation or sample location.";
            action = "Review “Field Record”.";
            next = () -> { FieldTourState.step(activity, 4); showNavigateMenuTourCoach(dialog, saved, field, coordinates); };
        } else {
            target = coordinates;
            title = "Coordinate target";
            body = "Enter coordinates lets you navigate to a location without first saving it. RockMap provides directional guidance only; it does not calculate a safe, legal, or practical route.";
            action = "Tap “Enter coordinates”.";
            primary = null;
            next = null;
        }
        Runnable back = () -> {
            if (step == 2) {
                dialog.setOnDismissListener(null);
                dialog.dismiss();
                FieldTourState.step(activity, 1);
                showFieldMenu();
            } else {
                FieldTourState.step(activity, step - 1);
                showNavigateMenuTourCoach(dialog, saved, field, coordinates);
            }
        };
        Runnable skip = step == 4 ? coordinates::performClick : next;
        GuidedTourCoach.show(activity, host, step, fieldTourTotal(FieldUiNames.NAVIGATE), title,
                body, action, target, back, primary, next, skip,
                () -> {
                    dialog.setOnDismissListener(null);
                    dialog.dismiss();
                    finishActiveFieldTour();
                });
    }

    private void showVisibilityMenu() {
        LinearLayout box = dialogBox();
        CheckBox tracks = check(FieldUiNames.TRACK, FieldMapState.tracksVisible(activity),
                "Recorded and imported tracks stay visible until you hide them or delete them.");
        CheckBox areas = check(FieldUiNames.PROSPECTING_AREAS, FieldMapState.areasVisible(activity),
                "Saved prospecting polygons and imported polygon areas.");
        CheckBox records = check(FieldUiNames.FIELD_RECORDS, FieldMapState.fieldRecordsVisible(activity),
                "Mapped field observations and samples.");
        CheckBox labels = check("Saved Location Labels", FieldMapState.labelsVisible(activity),
                "Names for saved/imported RockMap markers. Labels also hide when the normal marker layer is hidden.");
        box.addView(tracks); box.addView(areas); box.addView(records); box.addView(labels);
        new AlertDialog.Builder(activity)
                .setTitle(FieldUiNames.VISIBILITY)
                .setView(box)
                .setPositiveButton("Apply", (d, w) -> {
                    FieldMapState.setTracksVisible(activity, tracks.isChecked());
                    FieldMapState.setAreasVisible(activity, areas.isChecked());
                    FieldMapState.setFieldRecordsVisible(activity, records.isChecked());
                    FieldMapState.setLabelsVisible(activity, labels.isChecked());
                    applyCachedSources();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int fieldTourTotal(String tool) {
        if (FieldUiNames.TRACK.equals(tool)) return 17;
        if (FieldUiNames.NAVIGATE.equals(tool)) return 10;
        if (FieldUiNames.MEASURE.equals(tool)) return 20;
        if (FieldUiNames.FIELD_RECORDS.equals(tool)) return 17;
        if (FieldUiNames.PROSPECTING_AREAS.equals(tool)) return 15;
        if (FieldUiNames.IMPORT.equals(tool)) return 3;
        if (FieldUiNames.IMPORTED_DATA.equals(tool)) return 8;
        if (FieldUiNames.EXPORT.equals(tool)) return 3;
        if (FieldUiNames.COORDINATES.equals(tool)) return 5;
        return 2;
    }

    private String fieldTourPurpose(String tool) {
        if (FieldUiNames.TRACK.equals(tool)) {
            return "Tracks record your movement as a GPS line that you can pause, resume, reopen, navigate, and export later.";
        }
        if (FieldUiNames.NAVIGATE.equals(tool)) {
            return "Navigate provides directional guidance from your GPS position to a selected point. RockMap does not calculate a safe, legal, or practical route. It does not account for terrain, roads, private property, closures, hazards, or whether a route is passable.";
        }
        if (FieldUiNames.MEASURE.equals(tool)) {
            return "Measure builds a temporary line or polygon from points you add. It calculates distance and area, and a polygon can be saved as a Prospecting Area.";
        }
        if (FieldUiNames.FIELD_RECORDS.equals(tool)) {
            return "Field Records save detailed observations at a location, including notes, mineral or material information, sample IDs, photos, GPS accuracy, and elevation.";
        }
        if (FieldUiNames.PROSPECTING_AREAS.equals(tool)) {
            return "Prospecting Areas are saved polygons you can keep on the map and analyze with Research.";
        }
        if (FieldUiNames.IMPORT.equals(tool)) {
            return "Import reads GPX, KML, or GeoJSON files. Point geometry may become Saved Locations, line geometry may become Tracks, and polygon geometry may become Prospecting Areas.";
        }
        if (FieldUiNames.IMPORTED_DATA.equals(tool)) {
            return "Imported Data lets you select a previously imported file, inspect the objects created from it, show them on the map, or remove only that import.";
        }
        if (FieldUiNames.EXPORT.equals(tool)) {
            return "Export Data creates copies of selected RockMap data for use elsewhere without deleting the originals.";
        }
        if (FieldUiNames.COORDINATES.equals(tool)) {
            return "Coordinates converts one location among decimal, DDM, DMS, WGS84 UTM, and MGRS formats.";
        }
        return "Open this Field tool to learn its controls.";
    }

    private void showUnifiedFieldMenuTourCoach(AlertDialog dialog, View target, String tool) {
        if (dialog == null || target == null || tool == null
                || !FieldTourState.is(activity, tool, 1)) return;
        FrameLayout host = dialogTourRoot(dialog);
        if (host == null) return;
        GuidedTourCoach.show(activity, host, 1, fieldTourTotal(tool), tool,
                fieldTourPurpose(tool), "Tap “" + tool + "”.", target,
                null, null, null,
                () -> target.performClick(),
                () -> {
                    FieldTourState.finish(activity);
                    lastFieldTourCoachKey = "";
                    dialog.dismiss();
                    GuidedTourCoach.clear(activity);
                });
    }

    private boolean fieldTourOwnsHud() {
        if (!FieldTourState.active(activity)) return false;
        String tool = FieldTourState.tool(activity);
        int step = FieldTourState.step(activity);
        if (FieldUiNames.TRACK.equals(tool)) return (step >= 5 && step <= 11) || (step >= 13 && step <= 17);
        if (FieldUiNames.NAVIGATE.equals(tool)) return step >= 7 && step <= 10;
        if (FieldUiNames.MEASURE.equals(tool)) return step >= 2 && step <= 20;
        return FieldUiNames.PROSPECTING_AREAS.equals(tool) && step >= 3 && step <= 11;
    }

    private void resumeActiveFieldTour() {
        if (!FieldTourState.active(activity)) return;
        int step = FieldTourState.step(activity);
        if (step == 1) {
            showFieldMenu();
            return;
        }
        if (fieldTourOwnsHud()) {
            renderHud();
            return;
        }
    }

    private void finishActiveFieldTour() {
        FieldTourState.finish(activity);
        lastFieldTourCoachKey = "";
        GuidedTourCoach.clear(activity);
    }

    private View hudTourTarget(String tag) {
        return hud == null || tag == null ? null : hud.findViewWithTag(tag);
    }

    private void setMapTourStep(int step) {
        FieldTourState.step(activity, step);
        FieldTourState.text(activity, "");
        lastFieldTourCoachKey = "";
        renderHud();
    }

    private void addMapCenterMeasurementPointForTour() {
        if (map == null || map.getCameraPosition() == null || map.getCameraPosition().target == null) return;
        LatLng center = map.getCameraPosition().target;
        int index = measurement.size();
        double delta = 0.00055d * Math.max(1, index);
        double lat = center.getLatitude();
        double lon = center.getLongitude();
        if (index % 3 == 1) lon += delta;
        else if (index % 3 == 2) lat += delta;
        else if (index > 0) lon -= delta;
        addMeasurementPoint(new GeoMath.Point(lat, lon), false);
    }

    /**
     * Continue the approved Field-tool walkthrough on the live map. Every actionable step targets
     * the real control; informational steps use Continue while leaving the live feature usable.
     * GuidedTourCoach owns target highlighting, collision avoidance, and four-way finger dragging.
     */
    private void showActiveMapFieldTourCoach() {
        if (!FieldTourState.active(activity) || hud == null || hud.getVisibility() != View.VISIBLE) return;
        final String tool = FieldTourState.tool(activity);
        final int step = FieldTourState.step(activity);
        final int total = fieldTourTotal(tool);
        View target = null;
        String title = tool;
        String body = "";
        String action = "";
        String primary = null;
        Runnable primaryAction = null;
        Runnable skip = null;
        Runnable back = null;

        if (FieldUiNames.TRACK.equals(tool)) {
            if (step == 5) {
                target = hudTourTarget("rockmap-track-status");
                title = "Track recording";
                body = "This panel shows whether the track is recording, how many GPS points have been collected, and the distance recorded so far. Recording continues while this panel is collapsed or while you use other RockMap tools.";
                action = "Review the live recording status.";
                primary = "Continue";
                primaryAction = () -> setMapTourStep(6);
                skip = primaryAction;
            } else if (step == 6) {
                target = hudTourTarget("rockmap-track-pause");
                title = "Pause recording";
                body = "Pause keeps this track but temporarily stops adding GPS points.";
                action = "Tap “Pause”.";
                skip = () -> setMapTourStep(8);
                back = () -> setMapTourStep(5);
            } else if (step == 7) {
                target = hudTourTarget("rockmap-track-resume");
                title = "Resume recording";
                body = "Resume continues recording into the same track.";
                action = "Tap “Resume”.";
                final View resume = target;
                skip = () -> { if (resume != null) resume.performClick(); else setMapTourStep(8); };
                back = () -> setMapTourStep(5);
            } else if (step == 8) {
                target = hudTourTarget("rockmap-hud-drag:" + FieldUiNames.TRACK_SHORT);
                title = "Move the Track panel";
                body = "Move the Track panel anywhere convenient. Collapsing or moving the panel changes only the controls on screen; it does not stop recording.";
                action = "Try dragging the Track panel.";
                primary = "Continue";
                primaryAction = () -> setMapTourStep(9);
                skip = primaryAction;
                back = () -> setMapTourStep(5);
            } else if (step == 9) {
                target = hudTourTarget("rockmap-track-list");
                title = "Open Tracks";
                body = "Tracks opens the list of the active recording and earlier recorded tracks. You can keep recording while you use the map.";
                action = "Review the “Tracks” button.";
                primary = "Continue";
                primaryAction = () -> setMapTourStep(10);
                skip = primaryAction;
                back = () -> setMapTourStep(8);
            } else if (step == 10) {
                target = hudTourTarget("rockmap-track-stop");
                title = "Stop recording";
                body = "Stop ends GPS recording. The recorded track remains saved on this device.";
                action = "Tap “Stop”.";
                final FieldDatabase.Track active = db.getActiveTrack();
                skip = () -> {
                    if (active != null) FieldTourState.entityId(activity, active.id);
                    FieldTourState.step(activity, 12);
                    FieldTourState.text(activity, "");
                    GuidedTourCoach.clear(activity);
                    openFieldScreen("tracks");
                };
                back = () -> setMapTourStep(9);
            } else if (step >= 13 && step <= 17) {
                String tag;
                if (step == 13) {
                    tag = "rockmap-track-backtrack";
                    title = "Backtrack";
                    body = "Backtrack starts directional guidance toward the beginning of this saved track. It does not calculate a safe, legal, or practical route.";
                    action = "Review “Backtrack”.";
                } else if (step == 14) {
                    tag = "rockmap-track-hide";
                    title = "Hide";
                    body = "Hide removes this track line from the map without deleting the saved track.";
                    action = "Review “Hide”.";
                } else if (step == 15) {
                    tag = "rockmap-track-delete";
                    title = "Delete";
                    body = "Delete permanently removes the track and its recorded points. The tour will not ask you to delete anything.";
                    action = "Review “Delete”.";
                } else if (step == 16) {
                    tag = "rockmap-track-all";
                    title = "All tracks";
                    body = "All tracks returns to the Tracks list so you can open another recording.";
                    action = "Review “All tracks”.";
                } else {
                    tag = "rockmap-track-close-view";
                    title = "Close map view";
                    body = "Close map view stops inspecting this saved track here. It does not hide or delete the track.";
                    action = "Review “Close map view”.";
                }
                target = hudTourTarget(tag);
                back = () -> setMapTourStep(Math.max(13, step - 1));
                if (step < 17) {
                    primary = "Continue";
                    primaryAction = () -> setMapTourStep(step + 1);
                    skip = primaryAction;
                } else {
                    primary = "Finish";
                    primaryAction = this::finishActiveFieldTour;
                    skip = primaryAction;
                }
            } else return;
        } else if (FieldUiNames.NAVIGATE.equals(tool)) {
            if (step == 7) {
                target = hudTourTarget("rockmap-nav-status");
                title = "Directional guidance";
                body = "The line shows direction and distance only. RockMap does not calculate a safe, legal, or practical route. It does not account for terrain, roads, private property, closures, hazards, or whether the route is passable.";
                action = "Review the distance, bearing, and guidance line.";
                primary = "Continue";
                primaryAction = () -> setMapTourStep(8);
                skip = primaryAction;
            } else if (step == 8) {
                target = hudTourTarget("rockmap-nav-frame");
                title = "Frame the navigation";
                body = "Frame adjusts the map so your GPS position and the destination can be viewed together.";
                action = "Tap “Frame”.";
                final View frame = target;
                skip = () -> { if (frame != null) frame.performClick(); else setMapTourStep(9); };
                back = () -> setMapTourStep(7);
            } else if (step == 9) {
                target = hudTourTarget("rockmap-nav-target");
                title = "Center the target";
                body = "Target centers the map directly on the destination.";
                action = "Tap “Target”.";
                final View targetButton = target;
                skip = () -> { if (targetButton != null) targetButton.performClick(); else setMapTourStep(10); };
                back = () -> setMapTourStep(8);
            } else if (step == 10) {
                target = hudTourTarget("rockmap-nav-stop");
                title = "Stop navigation";
                body = "Stop removes the active destination and its directional guidance line.";
                action = "Tap “Stop”.";
                skip = this::finishActiveFieldTour;
                back = () -> setMapTourStep(9);
            } else return;
        } else if (FieldUiNames.MEASURE.equals(tool)) {
            String phase = FieldTourState.text(activity);
            if (step == 2) {
                target = hudTourTarget("rockmap-measure-tap-map");
                title = "Tap map";
                body = "Tap map lets you place the next measurement point directly on the map.";
                action = "Tap “Tap map”.";
                final View t = target; skip = () -> { if (t != null) t.performClick(); else setMapTourStep(3); };
            } else if (step == 3) {
                target = hudTourTarget("rockmap-measure-cancel-tap");
                title = "Cancel a map tap";
                body = "Cancel tap leaves the measurement unchanged when you decide not to place a point.";
                action = "Tap “Cancel tap”.";
                final View t = target; skip = () -> { if (t != null) t.performClick(); else setMapTourStep(4); };
                back = () -> setMapTourStep(2);
            } else if (step == 4) {
                target = hudTourTarget("rockmap-measure-tap-map");
                title = "Place a map point";
                body = "Turn map tapping on again. The next tap on the map will add the first measurement point.";
                action = "Tap “Tap map”.";
                final View t = target; skip = () -> { if (t != null) t.performClick(); else setMapTourStep(5); };
                back = () -> setMapTourStep(3);
            } else if (step == 5) {
                title = "Place the first point";
                body = "The map is ready for a measurement point. The guide stays out of the interaction area while you choose the location.";
                action = "Tap a location on the map.";
                skip = () -> { removeTapCapture(); addMapCenterMeasurementPointForTour(); };
                back = () -> { removeTapCapture(); setMapTourStep(4); };
            } else if (step == 6) {
                target = hudTourTarget("rockmap-measure-drag-note");
                title = "Move a measurement point";
                body = "Measurement points are direct handles. Drag a point on the map to reshape the line or polygon.";
                action = "Try dragging the measurement point.";
                primary = "Continue"; primaryAction = () -> setMapTourStep(7); skip = primaryAction;
                back = () -> setMapTourStep(5);
            } else if (step == 7) {
                target = hudTourTarget("rockmap-measure-undo");
                title = "Undo";
                body = "Undo removes the most recently added measurement point.";
                action = "Tap “Undo”.";
                final View t = target; skip = () -> { if (t != null) t.performClick(); else setMapTourStep(8); };
                back = () -> setMapTourStep(6);
            } else if (step == 8) {
                if ("undo".equals(phase)) {
                    target = hudTourTarget("rockmap-measure-undo");
                    title = "Remove the GPS example";
                    body = "The GPS position was added as a real measurement point. Undo removes this example before the next point source.";
                    action = "Tap “Undo”.";
                    final View t = target; skip = () -> { if (t != null) t.performClick(); else setMapTourStep(9); };
                } else {
                    target = hudTourTarget("rockmap-measure-add-gps");
                    title = "Add GPS";
                    body = "Add GPS requests a fresh precise GPS fix and uses your current position as the next measurement point.";
                    action = "Tap “Add GPS”.";
                    skip = () -> setMapTourStep(9);
                }
                back = () -> setMapTourStep(7);
            } else if (step == 9) {
                if ("undo".equals(phase)) {
                    target = hudTourTarget("rockmap-measure-undo");
                    title = "Remove the Saved Location example";
                    body = "The selected Saved Location was added as a measurement point. Undo removes this example before continuing.";
                    action = "Tap “Undo”.";
                    final View t = target; skip = () -> { if (t != null) t.performClick(); else setMapTourStep(10); };
                } else {
                    target = hudTourTarget("rockmap-measure-saved");
                    title = "Saved Location point";
                    body = "Saved adds one of your existing Saved Locations as a measurement point. If you do not have one yet, skip this step and continue.";
                    action = "Tap “Saved”, or skip if none exist.";
                    skip = () -> setMapTourStep(10);
                }
                back = () -> setMapTourStep(8);
            } else if (step == 10) {
                if ("undo".equals(phase)) {
                    target = hudTourTarget("rockmap-measure-undo");
                    title = "Remove the Field Record example";
                    body = "The selected Field Record was added as a measurement point. Undo removes this example before continuing.";
                    action = "Tap “Undo”.";
                    final View t = target; skip = () -> { if (t != null) t.performClick(); else setMapTourStep(11); };
                } else {
                    target = hudTourTarget("rockmap-measure-field");
                    title = "Field Record point";
                    body = "Field adds the location of an existing Field Record as a measurement point. If no Field Records exist yet, skip this step.";
                    action = "Tap “Field”, or skip if none exist.";
                    skip = () -> setMapTourStep(11);
                }
                back = () -> setMapTourStep(9);
            } else if (step == 11) {
                target = hudTourTarget("rockmap-measure-paste");
                title = "Paste coordinates";
                body = "Paste lets you type or paste coordinates and add that exact location as a measurement point.";
                action = "Tap “Paste”.";
                final View t = target; skip = () -> { if (t != null) t.performClick(); else setMapTourStep(12); };
                back = () -> setMapTourStep(10);
            } else if (step == 13 && "undo".equals(phase)) {
                target = hudTourTarget("rockmap-measure-undo");
                title = "Remove the coordinate example";
                body = "Undo removes the coordinate point so you can build a simple polygon next.";
                action = "Tap “Undo”.";
                final View t = target; skip = () -> { if (t != null) t.performClick(); else setMapTourStep(14); };
                back = () -> setMapTourStep(11);
            } else if (step >= 14 && step <= 16) {
                if ("map".equals(phase) || awaitingMapTap) {
                    title = "Place polygon point " + (step - 13);
                    body = "Choose the next boundary point on the map. Three points are enough to calculate an area and enable saving a Prospecting Area.";
                    action = "Tap a location on the map.";
                    skip = () -> { removeTapCapture(); addMapCenterMeasurementPointForTour(); };
                } else {
                    target = hudTourTarget("rockmap-measure-tap-map");
                    title = "Polygon point " + (step - 13);
                    body = "Use Tap map for the next polygon vertex.";
                    action = "Tap “Tap map”.";
                    final View t = target; skip = () -> { if (t != null) t.performClick(); else { FieldTourState.text(activity, "map"); beginOneShotMapTap(); } };
                }
                back = () -> setMapTourStep(Math.max(14, step - 1));
            } else if (step == 17) {
                target = hudTourTarget("rockmap-measure-summary");
                title = "Distance and area";
                body = "With two or more points RockMap measures the path. With three or more points it also calculates polygon area. Drag any vertex to adjust the shape.";
                action = "Review the measurements and try moving a vertex.";
                primary = "Continue"; primaryAction = () -> setMapTourStep(18); skip = primaryAction;
                back = () -> setMapTourStep(16);
            } else if (step == 18) {
                target = hudTourTarget("rockmap-measure-done");
                title = "Done";
                body = "Done finishes a temporary measurement. If it has not been saved as a Prospecting Area, finishing clears the temporary shape.";
                action = "Tap “Done”.";
                skip = () -> setMapTourStep(19);
                back = () -> setMapTourStep(17);
            } else if (step == 19) {
                target = hudTourTarget("rockmap-measure-save-area");
                title = "Save as Prospecting Area";
                body = "Save as Prospecting Area turns the temporary polygon into a saved area that can be reopened and analyzed later.";
                action = "Tap “Save as Prospecting Area”.";
                final View t = target; skip = () -> { if (t != null) t.performClick(); else finishActiveFieldTour(); };
                back = () -> setMapTourStep(18);
            } else return;
        } else if (FieldUiNames.PROSPECTING_AREAS.equals(tool)) {
            String phase = FieldTourState.text(activity);
            if (step == 3) {
                if (phase == null || phase.isEmpty()) {
                    target = hudTourTarget("rockmap-measure-header");
                    title = "Define the area with Measure";
                    body = "Creating a Prospecting Area uses the Measure panel to define its boundary. The same point-source controls are available here so you can build the polygon from the map or from locations you already know.";
                    action = "Review the Measure panel.";
                    primary = "Continue";
                    primaryAction = () -> { FieldTourState.text(activity, "area-gps"); renderHud(); };
                    skip = primaryAction;
                } else if ("area-gps".equals(phase)) {
                    target = hudTourTarget("rockmap-measure-add-gps");
                    title = "Add GPS";
                    body = "Add GPS uses a fresh current GPS fix as the next Prospecting Area boundary point.";
                    action = "Review “Add GPS”.";
                    primary = "Continue";
                    primaryAction = () -> { FieldTourState.text(activity, "area-saved"); renderHud(); };
                    skip = primaryAction;
                } else if ("area-saved".equals(phase)) {
                    target = hudTourTarget("rockmap-measure-saved");
                    title = "Saved Location";
                    body = "Saved uses one of your existing Saved Locations as the next boundary point.";
                    action = "Review “Saved”.";
                    primary = "Continue";
                    primaryAction = () -> { FieldTourState.text(activity, "area-field"); renderHud(); };
                    skip = primaryAction;
                } else if ("area-field".equals(phase)) {
                    target = hudTourTarget("rockmap-measure-field");
                    title = "Field Record";
                    body = "Field uses the location of an existing Field Record as the next boundary point.";
                    action = "Review “Field”.";
                    primary = "Continue";
                    primaryAction = () -> { FieldTourState.text(activity, "area-paste"); renderHud(); };
                    skip = primaryAction;
                } else if ("area-paste".equals(phase)) {
                    target = hudTourTarget("rockmap-measure-paste");
                    title = "Paste coordinates";
                    body = "Paste lets you enter coordinates and use that exact location as the next boundary point.";
                    action = "Review “Paste”.";
                    primary = "Continue";
                    primaryAction = () -> { FieldTourState.text(activity, "area-undo"); renderHud(); };
                    skip = primaryAction;
                } else if ("area-undo".equals(phase)) {
                    target = hudTourTarget("rockmap-measure-undo");
                    title = "Undo";
                    body = "Undo removes the most recently added boundary point without clearing the rest of the polygon.";
                    action = "Review “Undo”.";
                    primary = "Continue";
                    primaryAction = () -> { FieldTourState.text(activity, "area-done"); renderHud(); };
                    skip = primaryAction;
                } else {
                    target = hudTourTarget("rockmap-measure-done");
                    title = "Done";
                    body = "Done ends the temporary measurement. For a Prospecting Area, use Save as Prospecting Area once the polygon has at least three points instead of clearing the measurement.";
                    action = "Review “Done”.";
                    primary = "Continue";
                    primaryAction = () -> { FieldTourState.text(activity, ""); setMapTourStep(4); };
                    skip = primaryAction;
                }
            } else if ((step >= 4 && step <= 6) || step == 9) {
                int ordinal = step == 9 ? 3 : step - 3;
                if ("map".equals(phase) || awaitingMapTap) {
                    title = step == 9 ? "Replace the third boundary point" : "Boundary point " + ordinal;
                    body = "Tap the map to place this Prospecting Area boundary point.";
                    action = "Tap a location on the map.";
                    skip = () -> { removeTapCapture(); addMapCenterMeasurementPointForTour(); };
                } else {
                    target = hudTourTarget("rockmap-measure-tap-map");
                    title = step == 9 ? "Replace the third boundary point" : "Boundary point " + ordinal;
                    body = "Use Tap map to place the next boundary vertex.";
                    action = "Tap “Tap map”.";
                    final View t = target; skip = () -> { if (t != null) t.performClick(); else { FieldTourState.text(activity, "map"); beginOneShotMapTap(); } };
                }
                back = () -> setMapTourStep(step == 4 ? 3 : step - 1);
            } else if (step == 7) {
                target = hudTourTarget("rockmap-measure-drag-note");
                title = "Adjust the boundary";
                body = "Each polygon point is draggable. Move a vertex to adjust the Prospecting Area boundary before saving it.";
                action = "Try dragging a boundary point.";
                primary = "Continue"; primaryAction = () -> setMapTourStep(8); skip = primaryAction;
                back = () -> setMapTourStep(6);
            } else if (step == 8) {
                target = hudTourTarget("rockmap-measure-undo");
                title = "Undo a boundary point";
                body = "Undo removes the most recently added boundary point.";
                action = "Tap “Undo”.";
                final View t = target; skip = () -> { if (t != null) t.performClick(); else setMapTourStep(9); };
                back = () -> setMapTourStep(7);
            } else if (step == 10) {
                target = hudTourTarget("rockmap-measure-save-area");
                title = "Save the Prospecting Area";
                body = "Once the polygon has at least three points, save it as a Prospecting Area so it can be reopened, mapped, exported, and analyzed with Research.";
                action = "Tap “Save as Prospecting Area”.";
                final View t = target; skip = () -> { if (t != null) t.performClick(); else finishActiveFieldTour(); };
                back = () -> setMapTourStep(9);
            } else return;
        } else return;

        if (back == null && step > 1) back = () -> setMapTourStep(Math.max(1, step - 1));
        GuidedTourCoach.show(activity, step, total, title, body, action, target,
                back, primary, primaryAction, skip == null ? primaryAction : skip,
                this::finishActiveFieldTour);
    }

    private FrameLayout dialogTourRoot(AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) return null;
        View content = dialog.getWindow().findViewById(android.R.id.content);
        if (content instanceof FrameLayout) return (FrameLayout) content;
        View decor = dialog.getWindow().getDecorView();
        return findFirstFrameLayout(decor);
    }

    private FrameLayout findFirstFrameLayout(View view) {
        if (view instanceof FrameLayout) return (FrameLayout) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                FrameLayout found = findFirstFrameLayout(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void openFieldScreen(String screen) {
        Intent intent = new Intent(activity, FieldActivity.class);
        intent.putExtra(FieldActivity.EXTRA_SCREEN, screen);
        activity.startActivity(intent);
    }

    private void refreshFieldSnapshot() {
        if (refreshRunning) return;
        refreshRunning = true;
        final Set<String> hidden = FieldMapState.hiddenTracks(activity);
        worker.execute(() -> {
            String newTracks = buildTrackJson(hidden);
            String newAreas = buildAreaJson();
            String newRecords = buildFieldRecordJson();
            main.post(() -> {
                trackJson = newTracks;
                areaJson = newAreas;
                fieldRecordJson = newRecords;
                refreshRunning = false;
                long now = System.currentTimeMillis();
                if (now - lastWaypointRefresh > 3000L) {
                    lastWaypointRefresh = now;
                    refreshWaypointLabels();
                }
                applyCachedSources();
                if (!fieldTourOwnsHud()) renderHud();
                consumeMapRequests();
            });
        });
    }

    private void refreshWaypointLabels() {
        waypointRepository.getAll(items -> {
            waypointLabelJson = buildWaypointJson(items);
            applyCachedSources();
        });
    }

    private String buildTrackJson(Set<String> hidden) {
        JSONArray features = new JSONArray();
        try {
            for (FieldDatabase.Track track : db.listTracks(0)) {
                if (hidden.contains(Long.toString(track.id))) continue;
                List<GeoMath.Point> points = db.getTrackPoints(track.id);
                if (points.size() < 2) continue;
                JSONObject props = new JSONObject();
                props.put("id", track.id);
                props.put("name", track.name);
                props.put("status", track.status);
                features.put(lineFeature(points, props));
            }
        } catch (JSONException ignored) {}
        return collection(features);
    }

    private String buildAreaJson() {
        JSONArray features = new JSONArray();
        try {
            for (FieldDatabase.Area area : db.listAreas()) {
                if (area.points.size() < 3) continue;
                JSONObject props = new JSONObject();
                props.put("id", area.id);
                props.put("name", area.name);
                features.put(polygonFeature(area.points, props));
            }
        } catch (JSONException ignored) {}
        return collection(features);
    }

    private String buildFieldRecordJson() {
        JSONArray features = new JSONArray();
        try {
            for (FieldDatabase.FieldRecord record : db.listFieldRecords()) {
                JSONObject props = new JSONObject();
                props.put("id", record.id);
                props.put("name", record.name == null ? "Field record" : record.name);
                props.put("category", record.category == null ? "" : record.category);
                features.put(pointFeature(new GeoMath.Point(record.lat, record.lon), props));
            }
        } catch (JSONException | IllegalArgumentException ignored) {}
        return collection(features);
    }

    private String buildWaypointJson(List<WaypointEntity> items) {
        JSONArray features = new JSONArray();
        try {
            if (items != null) {
                for (WaypointEntity waypoint : items) {
                    JSONObject props = new JSONObject();
                    props.put("id", waypoint.id);
                    props.put("name", waypoint.name == null || waypoint.name.trim().isEmpty() ? "Saved Location" : waypoint.name.trim());
                    features.put(pointFeature(new GeoMath.Point(waypoint.latitude, waypoint.longitude), props));
                }
            }
        } catch (JSONException | IllegalArgumentException ignored) {}
        return collection(features);
    }

    private void applyCachedSources() {
        if (map == null) return;
        map.getStyle(style -> {
            ensureLayers(style);
            setSource(style, TRACK_SOURCE, trackJson);
            setSource(style, AREA_SOURCE, areaJson);
            setSource(style, FIELD_RECORD_SOURCE, fieldRecordJson);
            setSource(style, WAYPOINT_LABEL_SOURCE, waypointLabelJson);
            updateNavigationSources(style);
            updateMeasurementSources(style);
            syncVisibility(style);
        });
    }

    private void ensureLayers(Style style) {
        if (style.getSource(TRACK_SOURCE) == null) style.addSource(new GeoJsonSource(TRACK_SOURCE, emptyCollection()));
        if (style.getLayer(TRACK_LAYER) == null) {
            LineLayer layer = new LineLayer(TRACK_LAYER, TRACK_SOURCE);
            layer.setProperties(lineColor(Color.rgb(20, 105, 210)), lineWidth(4f), lineOpacity(0.92f));
            style.addLayer(layer);
        }

        if (style.getSource(AREA_SOURCE) == null) style.addSource(new GeoJsonSource(AREA_SOURCE, emptyCollection()));
        if (style.getLayer(AREA_FILL) == null) {
            FillLayer fill = new FillLayer(AREA_FILL, AREA_SOURCE);
            fill.setProperties(fillColor(Color.rgb(245, 170, 30)), fillOpacity(0.16f));
            style.addLayer(fill);
        }
        if (style.getLayer(AREA_LINE) == null) {
            LineLayer line = new LineLayer(AREA_LINE, AREA_SOURCE);
            line.setProperties(lineColor(Color.rgb(190, 105, 10)), lineWidth(3f), lineOpacity(0.9f));
            style.addLayer(line);
        }

        if (style.getSource(FIELD_RECORD_SOURCE) == null) style.addSource(new GeoJsonSource(FIELD_RECORD_SOURCE, emptyCollection()));
        if (style.getLayer(FIELD_RECORD_LAYER) == null) {
            CircleLayer circle = new CircleLayer(FIELD_RECORD_LAYER, FIELD_RECORD_SOURCE);
            circle.setProperties(circleColor(Color.rgb(120, 70, 160)), circleRadius(6f),
                    circleStrokeColor(Color.WHITE), circleStrokeWidth(2f));
            style.addLayer(circle);
        }
        if (style.getLayer(FIELD_RECORD_LABEL) == null) {
            SymbolLayer labels = labelLayer(FIELD_RECORD_LABEL, FIELD_RECORD_SOURCE, 10.5f);
            style.addLayer(labels);
        }

        if (style.getSource(WAYPOINT_LABEL_SOURCE) == null) style.addSource(new GeoJsonSource(WAYPOINT_LABEL_SOURCE, emptyCollection()));
        // Mirror the normal saved-marker circles from the current waypoint database. This makes
        // newly imported waypoints visible immediately, even before MainActivity is recreated and
        // reloads its own waypoint source. The matching style makes existing points visually merge.
        if (style.getLayer(WAYPOINT_MIRROR_LAYER) == null) {
            CircleLayer mirror = new CircleLayer(WAYPOINT_MIRROR_LAYER, WAYPOINT_LABEL_SOURCE);
            mirror.setProperties(circleColor(Color.rgb(215, 80, 20)), circleRadius(7f),
                    circleStrokeColor(Color.WHITE), circleStrokeWidth(2f));
            style.addLayer(mirror);
        }
        if (style.getLayer(WAYPOINT_LABEL_LAYER) == null) {
            SymbolLayer labels = labelLayer(WAYPOINT_LABEL_LAYER, WAYPOINT_LABEL_SOURCE, 10.5f);
            style.addLayer(labels);
        }

        if (style.getSource(NAV_TARGET_SOURCE) == null) style.addSource(new GeoJsonSource(NAV_TARGET_SOURCE, emptyCollection()));
        if (style.getLayer(NAV_TARGET_LAYER) == null) {
            CircleLayer target = new CircleLayer(NAV_TARGET_LAYER, NAV_TARGET_SOURCE);
            target.setProperties(circleColor(Color.rgb(205, 35, 45)), circleRadius(8f),
                    circleStrokeColor(Color.WHITE), circleStrokeWidth(3f));
            style.addLayer(target);
        }
        if (style.getLayer(NAV_TARGET_LABEL) == null) {
            SymbolLayer label = labelLayer(NAV_TARGET_LABEL, NAV_TARGET_SOURCE, 0f);
            label.setProperties(textSize(13f), textHaloWidth(2f));
            style.addLayer(label);
        }
        if (style.getSource(NAV_LINE_SOURCE) == null) style.addSource(new GeoJsonSource(NAV_LINE_SOURCE, emptyCollection()));
        if (style.getLayer(NAV_LINE_LAYER) == null) {
            LineLayer line = new LineLayer(NAV_LINE_LAYER, NAV_LINE_SOURCE);
            line.setProperties(lineColor(Color.rgb(205, 35, 45)), lineWidth(3f), lineOpacity(0.9f));
            style.addLayer(line);
        }

        if (style.getSource(MEASURE_FILL_SOURCE) == null) style.addSource(new GeoJsonSource(MEASURE_FILL_SOURCE, emptyCollection()));
        if (style.getLayer(MEASURE_FILL_LAYER) == null) {
            FillLayer fill = new FillLayer(MEASURE_FILL_LAYER, MEASURE_FILL_SOURCE);
            fill.setProperties(fillColor(Color.rgb(25, 145, 125)), fillOpacity(0.17f));
            style.addLayer(fill);
        }
        if (style.getSource(MEASURE_LINE_SOURCE) == null) style.addSource(new GeoJsonSource(MEASURE_LINE_SOURCE, emptyCollection()));
        if (style.getLayer(MEASURE_LINE_LAYER) == null) {
            LineLayer line = new LineLayer(MEASURE_LINE_LAYER, MEASURE_LINE_SOURCE);
            line.setProperties(lineColor(Color.rgb(10, 120, 105)), lineWidth(4f), lineOpacity(0.95f));
            style.addLayer(line);
        }
        if (style.getSource(MEASURE_POINT_SOURCE) == null) style.addSource(new GeoJsonSource(MEASURE_POINT_SOURCE, emptyCollection()));
        if (style.getLayer(MEASURE_POINT_LAYER) == null) {
            CircleLayer points = new CircleLayer(MEASURE_POINT_LAYER, MEASURE_POINT_SOURCE);
            points.setProperties(circleColor(Color.rgb(10, 120, 105)), circleRadius(8f),
                    circleStrokeColor(Color.WHITE), circleStrokeWidth(2.5f));
            style.addLayer(points);
        }
    }

    private SymbolLayer labelLayer(String id, String source, float minZoom) {
        SymbolLayer labels = new SymbolLayer(id, source);
        labels.setProperties(
                textField(get("name")),
                textSize(12f),
                textColor(Color.rgb(25, 25, 25)),
                textHaloColor(Color.WHITE),
                textHaloWidth(1.5f),
                textOffset(new Float[]{0f, 1.35f}));
        labels.setMinZoom(minZoom);
        return labels;
    }

    private void syncVisibility(Style style) {
        setLayerVisible(style, TRACK_LAYER, FieldMapState.tracksVisible(activity));
        setLayerVisible(style, AREA_FILL, FieldMapState.areasVisible(activity));
        setLayerVisible(style, AREA_LINE, FieldMapState.areasVisible(activity));
        setLayerVisible(style, FIELD_RECORD_LAYER, FieldMapState.fieldRecordsVisible(activity));
        setLayerVisible(style, FIELD_RECORD_LABEL, FieldMapState.fieldRecordsVisible(activity));

        boolean normalMarkersVisible = true;
        Layer existingMarkers = style.getLayer(EXISTING_WAYPOINT_LAYER);
        if (existingMarkers != null) {
            String value = existingMarkers.getVisibility().getValue();
            normalMarkersVisible = !NONE.equals(value);
        }
        setLayerVisible(style, WAYPOINT_MIRROR_LAYER, normalMarkersVisible);
        setLayerVisible(style, WAYPOINT_LABEL_LAYER,
                FieldMapState.labelsVisible(activity) && normalMarkersVisible);

        boolean nav = FieldMapState.navigationTarget(activity) != null;
        setLayerVisible(style, NAV_TARGET_LAYER, nav);
        setLayerVisible(style, NAV_TARGET_LABEL, nav);
        setLayerVisible(style, NAV_LINE_LAYER, nav && latestNavigationLocation != null);

        setLayerVisible(style, MEASURE_POINT_LAYER, measureActive && !measurement.isEmpty());
        setLayerVisible(style, MEASURE_LINE_LAYER, measureActive && measurement.size() >= 2);
        setLayerVisible(style, MEASURE_FILL_LAYER, measureActive && measurement.size() >= 3);
    }

    private void setLayerVisible(Style style, String id, boolean visible) {
        Layer layer = style.getLayer(id);
        if (layer != null) layer.setProperties(visibility(visible ? VISIBLE : NONE));
    }

    private void updateNavigationSources(Style style) {
        FieldMapState.NavigationTarget target = FieldMapState.navigationTarget(activity);
        if (target == null) {
            setSource(style, NAV_TARGET_SOURCE, emptyCollection());
            setSource(style, NAV_LINE_SOURCE, emptyCollection());
            return;
        }
        try {
            JSONObject props = new JSONObject().put("name", target.name);
            JSONArray targetFeatures = new JSONArray().put(pointFeature(target.point, props));
            setSource(style, NAV_TARGET_SOURCE, collection(targetFeatures));
            if (latestNavigationLocation != null) {
                ArrayList<GeoMath.Point> points = new ArrayList<>();
                points.add(point(latestNavigationLocation));
                points.add(target.point);
                JSONArray lineFeatures = new JSONArray().put(lineFeature(points, new JSONObject()));
                setSource(style, NAV_LINE_SOURCE, collection(lineFeatures));
            } else {
                setSource(style, NAV_LINE_SOURCE, emptyCollection());
            }
        } catch (JSONException ignored) {}
    }

    private void updateMeasurementSources(Style style) {
        if (!measureActive || measurement.isEmpty()) {
            setSource(style, MEASURE_POINT_SOURCE, emptyCollection());
            setSource(style, MEASURE_LINE_SOURCE, emptyCollection());
            setSource(style, MEASURE_FILL_SOURCE, emptyCollection());
            return;
        }
        try {
            JSONArray points = new JSONArray();
            for (int i = 0; i < measurement.size(); i++) {
                points.put(pointFeature(measurement.get(i), new JSONObject().put("index", i + 1)));
            }
            setSource(style, MEASURE_POINT_SOURCE, collection(points));
            if (measurement.size() >= 2) {
                setSource(style, MEASURE_LINE_SOURCE,
                        collection(new JSONArray().put(lineFeature(measurement, new JSONObject()))));
            } else setSource(style, MEASURE_LINE_SOURCE, emptyCollection());
            if (measurement.size() >= 3) {
                setSource(style, MEASURE_FILL_SOURCE,
                        collection(new JSONArray().put(polygonFeature(measurement, new JSONObject()))));
            } else setSource(style, MEASURE_FILL_SOURCE, emptyCollection());
        } catch (JSONException ignored) {}
    }

    private void setSource(Style style, String id, String json) {
        GeoJsonSource source = style.getSourceAs(id);
        if (source != null) source.setGeoJson(json == null ? emptyCollection() : json);
    }

    private void refreshNavigationState() {
        FieldMapState.NavigationTarget target = FieldMapState.navigationTarget(activity);
        if (target == null) {
            latestNavigationLocation = null;
            locationRepository.stop();
            navigationUpdatesStarted = false;
            if (FieldMapState.TOOL_NAVIGATE.equals(expandedTool)) setExpandedTool(null);
            else {
                renderHud();
                applyCachedSources();
            }
            return;
        }

        // Navigation remains logically active while the user opens Field screens or inspects other
        // map objects. Resuming only restarts location updates; it never takes camera ownership.
        ensureNavigationUpdates();
        if (latestNavigationLocation == null) {
            locationRepository.requestFreshPrecise(location -> {
                latestNavigationLocation = location;
                renderHud();
                applyCachedSources();
            }, message -> {
                renderHud();
                if (message != null) toast(message);
            });
        }
        renderHud();
        applyCachedSources();
    }

    private void startNavigation(String name, GeoMath.Point target) {
        if (target == null) return;
        // A new navigation target becomes the current map context. Ongoing Track/Measure modes
        // remain active, but stale completed-track START/END context should not follow it.
        FieldMapState.clearViewedMapContext(activity);
        FieldMapState.startNavigation(activity, name, target);
        setExpandedToolValue(FieldMapState.TOOL_NAVIGATE);
        latestNavigationLocation = null;
        locationRepository.stop();
        navigationUpdatesStarted = false;
        ensureNavigationUpdates();

        // Initial navigation may frame once. Any newer explicit map action invalidates this token,
        // so a delayed GPS fix can never steal the camera from Show batch/area/track/etc.
        long cameraToken = beginCameraCommand();
        centerInternal(target, 16d);
        locationRepository.requestFreshPrecise(location -> {
            latestNavigationLocation = location;
            renderHud();
            applyCachedSources();
            if (isCameraCommandCurrent(cameraToken)) frameNavigationInternal(FieldMapState.navigationTarget(activity));
        }, message -> {
            renderHud();
            applyCachedSources();
            toast(message == null ? "Navigation target mapped; GPS fix is not available yet." : message);
        });
        renderHud();
        applyCachedSources();
    }

    private void stopNavigation() {
        if (FieldMapState.TOOL_NAVIGATE.equals(expandedTool)) setExpandedToolValue(null);
        FieldMapState.stopNavigation(activity);
        latestNavigationLocation = null;
        locationRepository.stop();
        navigationUpdatesStarted = false;
        applyCachedSources();
        renderHud();
        toast("Navigation stopped.");
    }

    @Override public void onLocation(Location location) {
        if (location == null || FieldMapState.navigationTarget(activity) == null) return;
        latestNavigationLocation = location;
        if (!fieldTourOwnsHud()) renderHud();
        applyCachedSources();
    }

    @Override public void onLocationError(String message) {
        if (FieldMapState.navigationTarget(activity) != null && message != null) toast(message);
    }

    private void frameNavigation(FieldMapState.NavigationTarget target) {
        beginCameraCommand();
        frameNavigationInternal(target);
    }

    private void frameNavigationInternal(FieldMapState.NavigationTarget target) {
        if (target == null) return;
        if (latestNavigationLocation == null) {
            centerInternal(target.point, 16d);
            return;
        }
        GeoMath.Point current = point(latestNavigationLocation);
        focusBoundsInternal(new FieldMapState.Bounds(
                Math.min(current.lat, target.point.lat), Math.min(current.lon, target.point.lon),
                Math.max(current.lat, target.point.lat), Math.max(current.lon, target.point.lon)));
    }

    private void chooseSavedNavigation() {
        waypointRepository.getAll(items -> {
            if (items == null || items.isEmpty()) {
                toast("No Saved Locations yet.");
                return;
            }
            String[] labels = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                WaypointEntity w = items.get(i);
                labels[i] = (w.name == null || w.name.trim().isEmpty() ? "Saved Location" : w.name.trim())
                        + "\n" + String.format(Locale.US, "%.6f, %.6f", w.latitude, w.longitude);
            }
            new AlertDialog.Builder(activity).setTitle("Navigate to Saved Location")
                    .setItems(labels, (d, which) -> {
                        WaypointEntity w = items.get(which);
                        startNavigation(w.name, new GeoMath.Point(w.latitude, w.longitude));
                    })
                    .setNegativeButton("Cancel", null).show();
        });
    }

    private void chooseFieldRecordNavigation() {
        List<FieldDatabase.FieldRecord> items = db.listFieldRecords();
        if (items.isEmpty()) { toast("No Field Records yet."); return; }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            FieldDatabase.FieldRecord r = items.get(i);
            labels[i] = r.name + "\n" + String.format(Locale.US, "%.6f, %.6f", r.lat, r.lon);
        }
        new AlertDialog.Builder(activity).setTitle("Navigate to Field Record")
                .setItems(labels, (d, which) -> {
                    FieldDatabase.FieldRecord r = items.get(which);
                    startNavigation(r.name, new GeoMath.Point(r.lat, r.lon));
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void enterNavigationCoordinate() {
        EditText input = new EditText(activity);
        input.setHint("Latitude, longitude");
        input.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Navigate to coordinates")
                .setView(input).setPositiveButton("Map target", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(x -> {
            Button mapTarget = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            mapTarget.setOnClickListener(v -> {
                try {
                    CoordinateParser.Result parsed = CoordinateParser.parse(input.getText().toString());
                    if (FieldTourState.is(activity, FieldUiNames.NAVIGATE, 6)) {
                        FieldTourState.step(activity, 7);
                        lastFieldTourCoachKey = "";
                    }
                    dialog.setOnDismissListener(null);
                    dialog.dismiss();
                    GuidedTourCoach.clear(activity);
                    startNavigation("Coordinate target", new GeoMath.Point(parsed.latitude, parsed.longitude));
                } catch (IllegalArgumentException ex) {
                    input.setError(ex.getMessage());
                }
            });
            if (FieldTourState.is(activity, FieldUiNames.NAVIGATE, 5)) {
                showNavigationCoordinateTour(dialog, input, mapTarget, 5);
            } else if (FieldTourState.is(activity, FieldUiNames.NAVIGATE, 6)) {
                showNavigationCoordinateTour(dialog, input, mapTarget, 6);
            }
        });
        dialog.setOnDismissListener(d -> {
            if (FieldTourState.is(activity, FieldUiNames.NAVIGATE)
                    && FieldTourState.step(activity) >= 5 && FieldTourState.step(activity) <= 6) {
                finishActiveFieldTour();
            }
        });
        dialog.show();
    }

    private void showNavigationCoordinateTour(AlertDialog dialog, EditText input,
                                              Button mapTarget, int step) {
        FrameLayout host = dialogTourRoot(dialog);
        if (host == null || !FieldTourState.is(activity, FieldUiNames.NAVIGATE, step)) return;
        if (step == 5) {
            GuidedTourCoach.show(activity, host, 5, fieldTourTotal(FieldUiNames.NAVIGATE),
                    "Enter the destination",
                    "Enter the latitude and longitude of the point you want to use as the navigation target.",
                    "Enter the destination coordinates.", input,
                    () -> {
                        dialog.setOnDismissListener(null);
                        dialog.dismiss();
                        FieldTourState.step(activity, 4);
                        showNavigateMenu();
                    },
                    "Continue", () -> {
                        FieldTourState.step(activity, 6);
                        showNavigationCoordinateTour(dialog, input, mapTarget, 6);
                    },
                    () -> {
                        FieldTourState.step(activity, 6);
                        showNavigationCoordinateTour(dialog, input, mapTarget, 6);
                    },
                    () -> {
                        dialog.setOnDismissListener(null);
                        dialog.dismiss();
                        finishActiveFieldTour();
                    });
        } else {
            Runnable mapTargetAction = () -> {
                if (input.getText().toString().trim().isEmpty() && map != null
                        && map.getCameraPosition() != null && map.getCameraPosition().target != null) {
                    LatLng center = map.getCameraPosition().target;
                    input.setText(String.format(Locale.US, "%.6f, %.6f",
                            center.getLatitude(), center.getLongitude()));
                }
                mapTarget.performClick();
            };
            GuidedTourCoach.show(activity, host, 6, fieldTourTotal(FieldUiNames.NAVIGATE),
                    "Map the target",
                    "Map target starts directional guidance to the coordinates you entered. RockMap does not determine whether traveling straight toward that point is safe, legal, practical, or passable.",
                    "Tap “Map target”.", mapTarget,
                    () -> {
                        FieldTourState.step(activity, 5);
                        showNavigationCoordinateTour(dialog, input, mapTarget, 5);
                    },
                    null, null, mapTargetAction,
                    () -> {
                        dialog.setOnDismissListener(null);
                        dialog.dismiss();
                        finishActiveFieldTour();
                    });
        }
    }

    private void startMeasurement() {
        if (!measureActive) measurement.clear();
        measureActive = true;
        FieldMapState.saveMeasurement(activity, measurement, true);
        setExpandedToolValue(FieldMapState.TOOL_MEASURE);
        removeTapCapture();
        renderHud();
        applyCachedSources();
        toast("Measure is active on the map. Add points with Tap map, GPS, Saved, Field, or Paste.");
    }

    private void beginOneShotMapTap() {
        if (!measureActive || root == null || mapView == null || map == null) return;
        removeTapCapture();
        FrameLayout catcher = new FrameLayout(activity);
        catcher.setTag(TAP_CAPTURE_TAG);
        catcher.setBackgroundColor(Color.TRANSPARENT);
        final float[] down = new float[2];
        catcher.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                down[0] = event.getRawX(); down[1] = event.getRawY(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                v.performClick();
                float dx = event.getRawX() - down[0];
                float dy = event.getRawY() - down[1];
                removeTapCapture();
                if (Math.hypot(dx, dy) > dp(18)) {
                    toast("No point added. Pan the map normally, then tap “Tap map” again.");
                    return true;
                }
                int[] mapLocation = new int[2];
                mapView.getLocationOnScreen(mapLocation);
                PointF screen = new PointF(event.getRawX() - mapLocation[0], event.getRawY() - mapLocation[1]);
                LatLng latLng = map.getProjection().fromScreenLocation(screen);
                addMeasurementPoint(new GeoMath.Point(latLng.getLatitude(), latLng.getLongitude()), true);
                return true;
            }
            return true;
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(catcher, params);
        tapCapture = catcher;
        awaitingMapTap = true;
        bringFieldUiToFront();
        renderHud();
        toast("Tap once on the map to add the next measurement point. Dragging cancels the tap so you can pan normally afterward.");
    }

    private void removeTapCapture() {
        awaitingMapTap = false;
        if (tapCapture != null && tapCapture.getParent() instanceof ViewGroup) {
            ((ViewGroup) tapCapture.getParent()).removeView(tapCapture);
        }
        tapCapture = null;
    }

    /**
     * Measurement vertices are direct-manipulation handles. Touches that start away from a vertex
     * continue to MapLibre unchanged, so normal pan/zoom gestures remain intact. A drag that starts
     * on a vertex is consumed until release and updates the connected line/polygon in place.
     */
    private void installMeasurementDragHandler() {
        if (mapView == null || measurementDragHandlerInstalled) return;
        measurementDragHandlerInstalled = true;
        mapView.setOnTouchListener((view, event) -> {
            if (!measureActive || measurement.isEmpty() || awaitingMapTap || map == null) {
                draggingMeasurementIndex = -1;
                return false;
            }

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                draggingMeasurementIndex = nearestMeasurementVertex(event.getX(), event.getY());
                if (draggingMeasurementIndex < 0) return false;
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }

            if (draggingMeasurementIndex < 0) return false;

            if (action == MotionEvent.ACTION_MOVE) {
                PointF screen = new PointF(event.getX(), event.getY());
                LatLng moved = map.getProjection().fromScreenLocation(screen);
                GeoMath.Point candidatePoint = new GeoMath.Point(moved.getLatitude(), moved.getLongitude());
                ArrayList<GeoMath.Point> candidate = new ArrayList<>(measurement);
                candidate.set(draggingMeasurementIndex, candidatePoint);
                if (isSensibleMeasurementShape(candidate)) {
                    measurement.set(draggingMeasurementIndex, candidatePoint);
                    refreshMeasurementGeometryOnly();
                }
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                FieldMapState.saveMeasurement(activity, measurement, true);
                draggingMeasurementIndex = -1;
                if (view.getParent() != null) view.getParent().requestDisallowInterceptTouchEvent(false);
                refreshMeasurementGeometryOnly();
                renderHud();
                return true;
            }
            return true;
        });
    }

    private int nearestMeasurementVertex(float x, float y) {
        if (map == null || measurement.isEmpty()) return -1;
        float radius = dp(30);
        float best = radius * radius;
        int bestIndex = -1;
        for (int i = 0; i < measurement.size(); i++) {
            GeoMath.Point point = measurement.get(i);
            PointF screen = map.getProjection().toScreenLocation(new LatLng(point.lat, point.lon));
            float dx = screen.x - x;
            float dy = screen.y - y;
            float d2 = dx * dx + dy * dy;
            if (d2 <= best) {
                best = d2;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private void refreshMeasurementGeometryOnly() {
        if (map == null) return;
        map.getStyle(style -> {
            updateMeasurementSources(style);
            syncVisibility(style);
        });
    }

    /** Keep user-created measurement polygons simple: no duplicate vertices or crossed edges. */
    private boolean isSensibleMeasurementShape(List<GeoMath.Point> points) {
        if (points == null || points.isEmpty()) return true;
        for (int i = 0; i < points.size(); i++) {
            GeoMath.Point a = points.get(i);
            if (a == null || !Double.isFinite(a.lat) || !Double.isFinite(a.lon)) return false;
            for (int j = i + 1; j < points.size(); j++) {
                GeoMath.Point b = points.get(j);
                if (b == null) return false;
                if (GeoMath.distanceMeters(a, b) < 0.25d) return false;
            }
        }
        int n = points.size();
        if (n < 4) return true;

        for (int i = 0; i < n; i++) {
            GeoMath.Point a1 = points.get(i);
            GeoMath.Point a2 = points.get((i + 1) % n);
            for (int j = i + 1; j < n; j++) {
                // Adjacent polygon edges intentionally meet at their shared vertex.
                if (j == i || j == (i + 1) % n || (j + 1) % n == i) continue;
                GeoMath.Point b1 = points.get(j);
                GeoMath.Point b2 = points.get((j + 1) % n);
                if (segmentsIntersect(a1, a2, b1, b2)) return false;
            }
        }
        return true;
    }

    private boolean segmentsIntersect(GeoMath.Point a, GeoMath.Point b,
                                      GeoMath.Point c, GeoMath.Point d) {
        double o1 = orientation(a, b, c);
        double o2 = orientation(a, b, d);
        double o3 = orientation(c, d, a);
        double o4 = orientation(c, d, b);
        double eps = 1e-12d;

        if (((o1 > eps && o2 < -eps) || (o1 < -eps && o2 > eps))
                && ((o3 > eps && o4 < -eps) || (o3 < -eps && o4 > eps))) return true;
        if (Math.abs(o1) <= eps && onSegment(a, b, c)) return true;
        if (Math.abs(o2) <= eps && onSegment(a, b, d)) return true;
        if (Math.abs(o3) <= eps && onSegment(c, d, a)) return true;
        return Math.abs(o4) <= eps && onSegment(c, d, b);
    }

    private double orientation(GeoMath.Point a, GeoMath.Point b, GeoMath.Point c) {
        return (b.lon - a.lon) * (c.lat - a.lat) - (b.lat - a.lat) * (c.lon - a.lon);
    }

    private boolean onSegment(GeoMath.Point a, GeoMath.Point b, GeoMath.Point p) {
        double eps = 1e-12d;
        return p.lon >= Math.min(a.lon, b.lon) - eps && p.lon <= Math.max(a.lon, b.lon) + eps
                && p.lat >= Math.min(a.lat, b.lat) - eps && p.lat <= Math.max(a.lat, b.lat) + eps;
    }

    private void addGpsMeasurement() {
        locationRepository.requestFreshPrecise(location -> {
            if (FieldTourState.is(activity, FieldUiNames.MEASURE, 8)) {
                FieldTourState.text(activity, "undo");
                lastFieldTourCoachKey = "";
            }
            addMeasurementPoint(point(location), true);
        }, this::toast);
    }

    private void chooseSavedMeasurement() {
        waypointRepository.getAll(items -> {
            if (items == null || items.isEmpty()) {
                toast("No Saved Locations yet.");
                if (FieldTourState.is(activity, FieldUiNames.MEASURE, 9)) {
                    lastFieldTourCoachKey = "";
                    renderHud();
                }
                return;
            }
            String[] labels = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                String name = items.get(i).name;
                labels[i] = name == null || name.trim().isEmpty() ? "Saved Location" : name.trim();
            }
            AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Add Saved Location to Measurement")
                    .setItems(labels, (d, which) -> {
                        WaypointEntity w = items.get(which);
                        if (FieldTourState.is(activity, FieldUiNames.MEASURE, 9)) {
                            FieldTourState.text(activity, "undo");
                            lastFieldTourCoachKey = "";
                        }
                        addMeasurementPoint(new GeoMath.Point(w.latitude, w.longitude), true);
                    }).setNegativeButton("Cancel", null).create();
            dialog.show();
            if (FieldTourState.is(activity, FieldUiNames.MEASURE, 9)) {
                dialog.getListView().post(() -> {
                    View target = dialog.getListView().getChildAt(0);
                    if (target == null) target = dialog.getListView();
                    FrameLayout host = dialogTourRoot(dialog);
                    if (host != null) {
                        GuidedTourCoach.show(activity, host, 9, fieldTourTotal(FieldUiNames.MEASURE),
                                "Choose a Saved Location",
                                "Saved adds one of your existing Saved Locations as the next measurement point.",
                                "Select a Saved Location.", target,
                                () -> { dialog.dismiss(); renderHud(); },
                                null, null,
                                () -> { dialog.dismiss(); FieldTourState.step(activity, 10); FieldTourState.text(activity, ""); renderHud(); },
                                () -> { dialog.dismiss(); finishActiveFieldTour(); });
                    }
                });
            }
        });
    }

    private void chooseFieldRecordMeasurement() {
        List<FieldDatabase.FieldRecord> items = db.listFieldRecords();
        if (items.isEmpty()) {
            toast("No Field Records yet.");
            if (FieldTourState.is(activity, FieldUiNames.MEASURE, 10)) {
                lastFieldTourCoachKey = "";
                renderHud();
            }
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            String name = items.get(i).name;
            labels[i] = name == null || name.trim().isEmpty() ? "Field Record" : name.trim();
        }
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Add Field Record to Measurement")
                .setItems(labels, (d, which) -> {
                    FieldDatabase.FieldRecord r = items.get(which);
                    if (FieldTourState.is(activity, FieldUiNames.MEASURE, 10)) {
                        FieldTourState.text(activity, "undo");
                        lastFieldTourCoachKey = "";
                    }
                    addMeasurementPoint(new GeoMath.Point(r.lat, r.lon), true);
                }).setNegativeButton("Cancel", null).create();
        dialog.show();
        if (FieldTourState.is(activity, FieldUiNames.MEASURE, 10)) {
            dialog.getListView().post(() -> {
                View target = dialog.getListView().getChildAt(0);
                if (target == null) target = dialog.getListView();
                FrameLayout host = dialogTourRoot(dialog);
                if (host != null) {
                    GuidedTourCoach.show(activity, host, 10, fieldTourTotal(FieldUiNames.MEASURE),
                            "Choose a Field Record",
                            "Field adds the location of an existing Field Record as the next measurement point.",
                            "Select a Field Record.", target,
                            () -> { dialog.dismiss(); renderHud(); },
                            null, null,
                            () -> { dialog.dismiss(); FieldTourState.step(activity, 11); FieldTourState.text(activity, ""); renderHud(); },
                            () -> { dialog.dismiss(); finishActiveFieldTour(); });
                }
            });
        }
    }

    private void pasteMeasurement() {
        EditText input = new EditText(activity);
        input.setHint("Latitude, longitude");
        input.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Add Coordinate")
                .setView(input).setPositiveButton("Add", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(x -> {
            Button add = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            add.setOnClickListener(v -> {
                try {
                    CoordinateParser.Result p = CoordinateParser.parse(input.getText().toString());
                    if (FieldTourState.is(activity, FieldUiNames.MEASURE, 13)) {
                        FieldTourState.text(activity, "undo");
                        lastFieldTourCoachKey = "";
                    }
                    dialog.dismiss();
                    addMeasurementPoint(new GeoMath.Point(p.latitude, p.longitude), true);
                } catch (IllegalArgumentException ex) { input.setError(ex.getMessage()); }
            });
            if (FieldTourState.is(activity, FieldUiNames.MEASURE, 12)) {
                showMeasureCoordinateTour(dialog, input, add, 12);
            } else if (FieldTourState.is(activity, FieldUiNames.MEASURE, 13)) {
                showMeasureCoordinateTour(dialog, input, add, 13);
            }
        });
        dialog.show();
    }

    private void showMeasureCoordinateTour(AlertDialog dialog, EditText input, Button add, int step) {
        FrameLayout host = dialogTourRoot(dialog);
        if (host == null) return;
        if (step == 12) {
            GuidedTourCoach.show(activity, host, 12, fieldTourTotal(FieldUiNames.MEASURE),
                    "Paste coordinates",
                    "Paste lets you type or paste coordinates and use that exact location as a measurement point.",
                    "Enter latitude and longitude.", input,
                    () -> { dialog.dismiss(); FieldTourState.step(activity, 11); renderHud(); },
                    "Continue", () -> {
                        FieldTourState.step(activity, 13);
                        showMeasureCoordinateTour(dialog, input, add, 13);
                    },
                    () -> {
                        FieldTourState.step(activity, 13);
                        showMeasureCoordinateTour(dialog, input, add, 13);
                    },
                    () -> { dialog.dismiss(); finishActiveFieldTour(); });
        } else {
            GuidedTourCoach.show(activity, host, 13, fieldTourTotal(FieldUiNames.MEASURE),
                    "Add the coordinate",
                    "Add converts the entered coordinate into the next measurement point. Afterward, Undo will remove this example point before you build a polygon.",
                    "Tap “Add”.", add,
                    () -> {
                        FieldTourState.step(activity, 12);
                        showMeasureCoordinateTour(dialog, input, add, 12);
                    },
                    null, null,
                    () -> {
                        dialog.dismiss();
                        FieldTourState.text(activity, "");
                        FieldTourState.step(activity, 14);
                        renderHud();
                    },
                    () -> { dialog.dismiss(); finishActiveFieldTour(); });
        }
    }

    private void addMeasurementPoint(GeoMath.Point point, boolean centerIfFirst) {
        if (point == null) return;
        if (!measureActive) measureActive = true;
        ArrayList<GeoMath.Point> candidate = new ArrayList<>(measurement);
        candidate.add(point);
        if (candidate.size() > 2000) {
            toast("A measurement can contain at most 2,000 points.");
            return;
        }
        if (!isSensibleMeasurementShape(candidate)) {
            toast("That point would cross or overlap the measurement shape. Place it so the outline stays simple.");
            return;
        }
        measurement.add(point);
        FieldMapState.saveMeasurement(activity, measurement, true);
        if (centerIfFirst && measurement.size() == 1) centerExplicit(point, 16d);

        if (FieldTourState.is(activity, FieldUiNames.MEASURE)) {
            int step = FieldTourState.step(activity);
            if (step == 5 && "map".equals(FieldTourState.text(activity))) {
                FieldTourState.text(activity, "");
                FieldTourState.step(activity, 6);
                lastFieldTourCoachKey = "";
            } else if ((step == 14 || step == 15 || step == 16)
                    && "map".equals(FieldTourState.text(activity))) {
                FieldTourState.text(activity, "");
                FieldTourState.step(activity, step + 1);
                lastFieldTourCoachKey = "";
            }
        } else if (FieldTourState.is(activity, FieldUiNames.PROSPECTING_AREAS)) {
            int step = FieldTourState.step(activity);
            if ((step == 4 || step == 5 || step == 6)
                    && "map".equals(FieldTourState.text(activity))) {
                FieldTourState.text(activity, "");
                FieldTourState.step(activity, step + 1);
                lastFieldTourCoachKey = "";
            } else if (step == 9 && "map".equals(FieldTourState.text(activity))) {
                FieldTourState.text(activity, "");
                FieldTourState.step(activity, 10);
                lastFieldTourCoachKey = "";
            }
        }
        applyCachedSources();
        renderHud();
    }

    private void undoMeasurement() {
        if (measurement.isEmpty()) { toast("There is no measurement point to undo."); return; }
        measurement.remove(measurement.size() - 1);
        FieldMapState.saveMeasurement(activity, measurement, true);
        applyCachedSources();
        renderHud();
    }

    private void saveMeasurementArea() {
        if (measurement.size() < 3) { toast("Add at least 3 points before saving an area."); return; }
        EditText name = new EditText(activity);
        name.setHint("Area name");
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Save measured area as Prospecting Area")
                .setMessage(measurementSummary()
                        + "\n\nThis turns the temporary measurement into a saved Prospecting Area that can be reopened and analyzed later.")
                .setView(name)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                try {
                    final boolean measureTour = FieldTourState.is(activity, FieldUiNames.MEASURE, 20);
                    final boolean areaTour = FieldTourState.is(activity, FieldUiNames.PROSPECTING_AREAS, 11);
                    ArrayList<GeoMath.Point> pointsToSave = new ArrayList<>(measurement);
                    long areaId = ProspectingAreaCreator.saveNamedPolygonAndPrompt(
                            activity, name.getText().toString().trim(),
                            "Saved from map measurement", pointsToSave, false,
                            (savedId, savedName) -> {
                                if (areaTour) {
                                    FieldTourState.entityId(activity, savedId);
                                    FieldTourState.step(activity, 12);
                                    lastFieldTourCoachKey = "";
                                    main.postDelayed(() -> openFieldArea(savedId), 220L);
                                } else if (measureTour) {
                                    finishActiveFieldTour();
                                }
                            });
                    if (areaTour) FieldTourState.entityId(activity, areaId);
                    measureActive = false;
                    measurement.clear();
                    FieldMapState.clearMeasurement(activity);
                    if (FieldMapState.TOOL_MEASURE.equals(expandedTool)) setExpandedToolValue(null);
                    removeTapCapture();
                    refreshFieldSnapshot();
                    applyCachedSources();
                    renderHud();
                    dialog.dismiss();
                } catch (RuntimeException ex) {
                    name.setError(ex.getMessage() == null ? "Could not save this Prospecting Area." : ex.getMessage());
                }
            });

            if (FieldTourState.is(activity, FieldUiNames.MEASURE, 20)
                    || FieldTourState.is(activity, FieldUiNames.PROSPECTING_AREAS, 11)) {
                showSaveAreaTour(dialog, name, save);
            }
        });
        dialog.show();
    }

    private void showSaveAreaTour(AlertDialog dialog, EditText name, Button save) {
        String tool = FieldTourState.tool(activity);
        int step = FieldTourState.step(activity);
        String phase = FieldTourState.text(activity);
        FrameLayout host = dialogTourRoot(dialog);
        if (host == null) return;
        int total = fieldTourTotal(tool);
        if (!"save".equals(phase)) {
            GuidedTourCoach.show(activity, host, step, total,
                    "Name the Prospecting Area",
                    "Give the saved polygon a name you will recognize later in Prospecting Areas.",
                    "Enter an area name.", name,
                    () -> {
                        dialog.dismiss();
                        if (FieldUiNames.MEASURE.equals(tool)) {
                            FieldTourState.step(activity, 19);
                        } else {
                            FieldTourState.step(activity, 10);
                        }
                        FieldTourState.text(activity, "");
                        renderHud();
                    },
                    "Continue", () -> {
                        FieldTourState.text(activity, "save");
                        showSaveAreaTour(dialog, name, save);
                    },
                    () -> {
                        dialog.dismiss();
                        FieldTourState.text(activity, "");
                        finishActiveFieldTour();
                        renderHud();
                    },
                    () -> {
                        dialog.dismiss();
                        finishActiveFieldTour();
                    });
        } else {
            GuidedTourCoach.show(activity, host, step, total,
                    "Save the Prospecting Area",
                    "Save keeps this polygon on the device so it can be reopened, mapped, exported, and analyzed with Research.",
                    "Tap “Save”.", save,
                    () -> {
                        FieldTourState.text(activity, "");
                        showSaveAreaTour(dialog, name, save);
                    },
                    null, null,
                    () -> {
                        dialog.dismiss();
                        FieldTourState.text(activity, "");
                        finishActiveFieldTour();
                        renderHud();
                    },
                    () -> {
                        dialog.dismiss();
                        finishActiveFieldTour();
                    });
        }
    }

    private void finishMeasurement() {
        if (measurement.isEmpty()) {
            measureActive = false;
            FieldMapState.clearMeasurement(activity);
            if (FieldMapState.TOOL_MEASURE.equals(expandedTool)) setExpandedToolValue(null);
            removeTapCapture();
            applyCachedSources();
            renderHud();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Finish measurement?")
                .setMessage("The temporary measurement will be cleared. Save it as a Prospecting Area first if you want it to remain on the map.")
                .setPositiveButton("Clear & finish", null)
                .setNegativeButton("Keep measuring", null).create();
        dialog.setOnShowListener(ignored -> {
            Button clear = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button keep = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            clear.setOnClickListener(v -> {
                measurement.clear();
                measureActive = false;
                FieldMapState.clearMeasurement(activity);
                if (FieldMapState.TOOL_MEASURE.equals(expandedTool)) setExpandedToolValue(null);
                removeTapCapture();
                applyCachedSources();
                renderHud();
                dialog.dismiss();
                if (FieldTourState.is(activity, FieldUiNames.MEASURE, 18)) finishActiveFieldTour();
            });
            keep.setOnClickListener(v -> {
                dialog.dismiss();
                if (FieldTourState.is(activity, FieldUiNames.MEASURE, 18)) {
                    FieldTourState.step(activity, 19);
                    lastFieldTourCoachKey = "";
                    renderHud();
                }
            });
            if (FieldTourState.is(activity, FieldUiNames.MEASURE, 18)) {
                FrameLayout host = dialogTourRoot(dialog);
                if (host != null) {
                    GuidedTourCoach.show(activity, host, 18, fieldTourTotal(FieldUiNames.MEASURE),
                            "Done",
                            "Done finishes a temporary measurement. Clearing it removes the temporary shape, so keep measuring for this walkthrough and save it as a Prospecting Area next.",
                            "Tap “Keep measuring”.", keep,
                            () -> { dialog.dismiss(); FieldTourState.step(activity, 17); renderHud(); },
                            null, null, keep::performClick,
                            () -> { dialog.dismiss(); finishActiveFieldTour(); });
                }
            }
        });
        dialog.show();
    }

    private void openFieldArea(long areaId) {
        Intent field = new Intent(activity, FieldActivity.class);
        field.putExtra(FieldActivity.EXTRA_SCREEN, "areas");
        field.putExtra(FieldActivity.EXTRA_AREA_ID, areaId);
        activity.startActivity(field);
    }

    private String measurementSummary() {
        if (measurement.isEmpty()) return "No points yet. Tap “Tap map” or choose another point source.";
        if (measurement.size() == 1) return "First point: " + measurement.get(0).decimal();
        double distance = GeoMath.pathDistanceMeters(measurement);
        double bearing = GeoMath.initialBearingDegrees(measurement.get(0), measurement.get(measurement.size() - 1));
        String result = "Path: " + GeoMath.distanceLabel(distance)
                + " · First→last: " + String.format(Locale.US, "%.0f° %s", bearing, GeoMath.cardinal(bearing));
        if (measurement.size() >= 3) result += " · Area: " + GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(measurement));
        return result;
    }

    private void trackCommand(String action, long id) {
        Intent service = new Intent(activity, TrackRecordingService.class)
                .setAction(action).putExtra(TrackRecordingService.EXTRA_TRACK_ID, id);
        activity.startService(service);
        main.postDelayed(this::refreshFieldSnapshot, 300L);
    }

    private void confirmStopTrack(FieldDatabase.Track track) {
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Stop track?")
                .setMessage("The recorded line will remain visible on the map until you hide or delete the track.")
                .setPositiveButton("Stop", null)
                .setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> {
            Button stop = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            stop.setOnClickListener(v -> {
                boolean touring = FieldTourState.is(activity, FieldUiNames.TRACK, 11);
                if (touring) {
                    FieldTourState.entityId(activity, track.id);
                    FieldTourState.step(activity, 12);
                }
                dialog.setOnDismissListener(null);
                dialog.dismiss();
                GuidedTourCoach.clear(activity);
                trackCommand(TrackRecordingService.ACTION_STOP, track.id);
                if (touring) {
                    main.postDelayed(() -> openStoppedTrackListWhenReady(track.id, 0), 260L);
                }
            });
            if (FieldTourState.is(activity, FieldUiNames.TRACK, 11)) {
                FrameLayout host = dialogTourRoot(dialog);
                if (host != null) {
                    GuidedTourCoach.show(activity, host, 11, fieldTourTotal(FieldUiNames.TRACK),
                            "Stop recording",
                            "Stop ends GPS recording. The recorded line stays saved on this device and remains available in Tracks.",
                            "Tap “Stop”.", stop,
                            () -> {
                                dialog.setOnDismissListener(null);
                                dialog.dismiss();
                                FieldTourState.step(activity, 10);
                                renderHud();
                            },
                            null, null, stop::performClick,
                            () -> {
                                dialog.setOnDismissListener(null);
                                dialog.dismiss();
                                finishActiveFieldTour();
                            });
                }
            }
        });
        dialog.setOnDismissListener(d -> {
            if (FieldTourState.is(activity, FieldUiNames.TRACK, 11)) finishActiveFieldTour();
        });
        dialog.show();
    }

    private void openStoppedTrackListWhenReady(long trackId, int attempt) {
        FieldDatabase.Track saved = db.getTrack(trackId);
        boolean complete = saved != null && FieldDatabase.TRACK_COMPLETE.equals(saved.status);
        if (complete || attempt >= 8) {
            openFieldScreen("tracks");
            return;
        }
        main.postDelayed(() -> openStoppedTrackListWhenReady(trackId, attempt + 1), 220L);
    }

    private void consumeMapRequests() {
        if (map == null) return;
        if (FieldMapState.consumeMeasurementRequest(activity)) startMeasurement();

        FieldMapState.CameraRequest cameraRequest = FieldMapState.consumeCameraRequest(activity);
        if (cameraRequest != null) {
            long cameraToken = beginCameraCommand();
            if (FieldMapState.CAMERA_BOUNDS.equals(cameraRequest.kind) && cameraRequest.bounds != null) {
                focusBoundsInternal(cameraRequest.bounds);
            } else if (FieldMapState.CAMERA_TRACK.equals(cameraRequest.kind) && cameraRequest.trackId >= 0L) {
                long trackId = cameraRequest.trackId;
                worker.execute(() -> {
                    List<GeoMath.Point> points = db.getTrackPoints(trackId);
                    main.post(() -> {
                        if (!isCameraCommandCurrent(cameraToken)) return;
                        FieldMapState.Bounds trackBounds = FieldMapState.Bounds.fromPoints(points);
                        if (trackBounds != null) focusBoundsInternal(trackBounds);
                    });
                });
            }
        }
        refreshNavigationStateIfNeeded();
    }

    private void refreshNavigationStateIfNeeded() {
        FieldMapState.NavigationTarget target = FieldMapState.navigationTarget(activity);
        if (target != null && resumed) ensureNavigationUpdates();
    }

    private void ensureNavigationUpdates() {
        if (navigationUpdatesStarted) return;
        navigationUpdatesStarted = true;
        locationRepository.start();
    }

    private long beginCameraCommand() {
        return ++cameraCommandGeneration;
    }

    private boolean isCameraCommandCurrent(long token) {
        return token == cameraCommandGeneration;
    }

    private void focusBoundsInternal(FieldMapState.Bounds bounds) {
        if (map == null || bounds == null || !bounds.isValid()) return;
        GeoMath.Point a = new GeoMath.Point(bounds.minLat, bounds.minLon);
        GeoMath.Point b = new GeoMath.Point(bounds.maxLat, bounds.maxLon);
        double diagonal = GeoMath.distanceMeters(a, b);
        double zoom;
        if (diagonal < 150d) zoom = 17d;
        else if (diagonal < 500d) zoom = 16d;
        else if (diagonal < 1500d) zoom = 14.8d;
        else if (diagonal < 5000d) zoom = 13.2d;
        else if (diagonal < 20000d) zoom = 11.5d;
        else if (diagonal < 100000d) zoom = 9.3d;
        else zoom = 7.2d;
        LatLng mid = new LatLng((bounds.minLat + bounds.maxLat) / 2d, (bounds.minLon + bounds.maxLon) / 2d);
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(mid, zoom));
    }

    private void centerExplicit(GeoMath.Point point, double zoom) {
        beginCameraCommand();
        centerInternal(point, zoom);
    }

    private void centerInternal(GeoMath.Point point, double zoom) {
        if (map == null || point == null) return;
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(point.lat, point.lon), zoom));
    }

    private JSONObject pointFeature(GeoMath.Point point, JSONObject properties) throws JSONException {
        JSONArray coordinates = new JSONArray().put(point.lon).put(point.lat);
        JSONObject geometry = new JSONObject().put("type", "Point").put("coordinates", coordinates);
        return new JSONObject().put("type", "Feature").put("properties", properties).put("geometry", geometry);
    }

    private JSONObject lineFeature(List<GeoMath.Point> points, JSONObject properties) throws JSONException {
        JSONArray coordinates = new JSONArray();
        for (GeoMath.Point point : points) coordinates.put(new JSONArray().put(point.lon).put(point.lat));
        JSONObject geometry = new JSONObject().put("type", "LineString").put("coordinates", coordinates);
        return new JSONObject().put("type", "Feature").put("properties", properties).put("geometry", geometry);
    }

    private JSONObject polygonFeature(List<GeoMath.Point> points, JSONObject properties) throws JSONException {
        JSONArray ring = new JSONArray();
        for (GeoMath.Point point : points) ring.put(new JSONArray().put(point.lon).put(point.lat));
        GeoMath.Point first = points.get(0);
        GeoMath.Point last = points.get(points.size() - 1);
        if (Math.abs(first.lat - last.lat) > 1e-10 || Math.abs(first.lon - last.lon) > 1e-10) {
            ring.put(new JSONArray().put(first.lon).put(first.lat));
        }
        JSONObject geometry = new JSONObject().put("type", "Polygon").put("coordinates", new JSONArray().put(ring));
        return new JSONObject().put("type", "Feature").put("properties", properties).put("geometry", geometry);
    }

    private static String collection(JSONArray features) {
        try { return new JSONObject().put("type", "FeatureCollection").put("features", features).toString(); }
        catch (JSONException ex) { return emptyCollection(); }
    }

    private static String emptyCollection() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }

    private CheckBox check(String title, boolean checked, String detail) {
        CheckBox box = new CheckBox(activity);
        box.setText(title + "\n" + detail);
        box.setChecked(checked);
        box.setTextSize(13f);
        box.setPadding(dp(4), dp(7), dp(4), dp(7));
        return box;
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(4), dp(12), dp(8));
        return box;
    }

    private View scrollDialog(View content) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        LinearLayout holder = new LinearLayout(activity);
        holder.setOrientation(LinearLayout.VERTICAL);
        int height = Math.min(dp(520), Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.58f));
        holder.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        return holder;
    }

    private View dialogAction(String title, String detail, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setMinimumHeight(dp(68));
        TextView h = new TextView(activity);
        h.setText(title + "  ›");
        h.setTextSize(15.5f);
        h.setTextColor(Color.rgb(30, 85, 145));
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(h);
        TextView d = new TextView(activity);
        d.setText(detail);
        d.setTextSize(12.5f);
        d.setTextColor(Color.rgb(80, 80, 80));
        d.setPadding(0, dp(3), 0, 0);
        card.addView(d);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(listener);
        return card;
    }

    private TextView hudTitle(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(13.5f);
        view.setTextColor(Color.rgb(25, 25, 25));
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private TextView hudText(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(11.5f);
        view.setTextColor(Color.rgb(65, 65, 65));
        view.setPadding(0, dp(2), 0, dp(4));
        return view;
    }

    private View panelHeader(String titleText, String shortLabel, View.OnClickListener collapse) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOnTouchListener((v, event) -> handleFloatingDrag(hud, v, event));

        Button arrow = new Button(activity);
        arrow.setText("›");
        arrow.setAllCaps(false);
        arrow.setTextSize(20f);
        arrow.setMinWidth(dp(44));
        arrow.setMinimumWidth(dp(44));
        arrow.setMinHeight(dp(40));
        arrow.setMinimumHeight(dp(40));
        arrow.setPadding(0, 0, 0, 0);
        arrow.setTextColor(Color.rgb(30, 85, 145));
        GradientDrawable collapseBg = new GradientDrawable();
        collapseBg.setColor(Color.rgb(255, 255, 255));
        collapseBg.setStroke(dp(1), Color.rgb(165, 175, 180));
        collapseBg.setCornerRadius(dp(7));
        arrow.setBackground(collapseBg);
        arrow.setContentDescription("Collapse " + shortLabel + " toolbar");
        arrow.setTag("rockmap-hud-collapse:" + shortLabel);
        arrow.setOnClickListener(collapse);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(44), dp(40)));

        TextView title = hudTitle(titleText);
        title.setPadding(dp(6), 0, dp(4), 0);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View dragHandle = RockMapDragHandle.labeled(activity, Color.rgb(82, 88, 90),
                (v, event) -> handleFloatingDrag(hud, v, event),
                "Drag " + shortLabel + " toolbar");
        dragHandle.setTag("rockmap-hud-drag:" + shortLabel);
        row.addView(dragHandle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        return row;
    }

    private void addCollapsedTab(String tag, String label, View.OnClickListener expand) {
        if (collapsedTabs == null) return;
        removeCollapsedTab(tag);
        Button tab = new Button(activity);
        tab.setTag(tag);
        tab.setText("‹ " + label);
        tab.setAllCaps(false);
        tab.setTextSize(11f);
        tab.setMinWidth(dp(82));
        tab.setMinimumWidth(dp(82));
        tab.setMinHeight(dp(40));
        tab.setMinimumHeight(dp(40));
        tab.setPadding(dp(5), 0, dp(5), 0);
        tab.setContentDescription("Expand " + label + " map toolbar");
        tab.setOnClickListener(expand);
        GradientDrawable tabBg = new GradientDrawable();
        tabBg.setColor(Color.rgb(255, 255, 255));
        tabBg.setStroke(dp(1), Color.rgb(165, 175, 180));
        tabBg.setCornerRadius(dp(7));
        tab.setBackground(tabBg);
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        tabParams.setMargins(0, dp(2), 0, 0);
        collapsedTabs.addView(tab, tabParams);
        collapsedTabs.setVisibility(View.VISIBLE);
        positionCollapsedTabsIfNeeded();
        bringFieldUiToFront();
    }

    private void removeCollapsedTab(String tag) {
        if (collapsedTabs == null) return;
        View tab = collapsedTabs.findViewWithTag(tag);
        if (tab != null) collapsedTabs.removeView(tab);
        collapsedTabs.setVisibility(collapsedToolCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private int collapsedToolCount() {
        if (collapsedTabs == null) return 0;
        int count = 0;
        for (int i = 0; i < collapsedTabs.getChildCount(); i++) {
            View child = collapsedTabs.getChildAt(i);
            if (child != null && child.getTag() != null
                    && !COLLAPSED_DRAG_TAG.equals(child.getTag())) count++;
        }
        return count;
    }

    private boolean handleFloatingDrag(View movingView, View touched, MotionEvent event) {
        if (movingView == null || root == null || event == null
                || !(movingView.getLayoutParams() instanceof FrameLayout.LayoutParams)) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                FrameLayout.LayoutParams start = (FrameLayout.LayoutParams) movingView.getLayoutParams();
                floatingDragDownRawX = event.getRawX();
                floatingDragDownRawY = event.getRawY();
                floatingDragStartLeft = start.leftMargin;
                floatingDragStartTop = start.topMargin;
                floatingDragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - floatingDragDownRawX;
                float dy = event.getRawY() - floatingDragDownRawY;
                if (!floatingDragging && Math.hypot(dx, dy) >= Math.max(1, floatingTouchSlop)) {
                    floatingDragging = true;
                    touched.setPressed(false);
                    ViewParent parent = touched.getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                }
                if (floatingDragging) {
                    positionFloatingView(movingView, floatingDragStartLeft + Math.round(dx),
                            floatingDragStartTop + Math.round(dy), true);
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (floatingDragging) {
                    float upDx = event.getRawX() - floatingDragDownRawX;
                    float upDy = event.getRawY() - floatingDragDownRawY;
                    positionFloatingView(movingView, floatingDragStartLeft + Math.round(upDx),
                            floatingDragStartTop + Math.round(upDy), true);
                    touched.setPressed(false);
                    floatingDragging = false;
                    return true;
                }
                return false;
            default:
                return floatingDragging;
        }
    }

    private void positionFloatingView(View view, int left, int top, boolean remember) {
        if (view == null || root == null || !(view.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        int rootWidth = root.getWidth() > 0 ? root.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        int rootHeight = root.getHeight() > 0 ? root.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
        int width = view.getWidth() > 0 ? view.getWidth() : Math.max(dp(82), ((FrameLayout.LayoutParams) view.getLayoutParams()).width);
        int height = view.getHeight() > 0 ? view.getHeight() : dp(80);
        int margin = dp(6);
        int bottomGuard = dp(118);
        left = Math.max(margin, Math.min(left, Math.max(margin, rootWidth - width - margin)));
        int minTop = Math.max(margin, statusBarHeight() + dp(2));
        top = Math.max(minTop, Math.min(top, Math.max(minTop, rootHeight - height - bottomGuard)));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = 0;
        params.bottomMargin = 0;
        view.setLayoutParams(params);
        if (view == hud) updateMapUiInsets();
        if (remember) {
            if (view == hud) {
                hudUserPositioned = true; hudUserLeft = left; hudUserTop = top;
            } else if (view == collapsedTabs) {
                collapsedTabsUserPositioned = true; collapsedTabsUserLeft = left; collapsedTabsUserTop = top;
            }
        }
    }

    private void positionCollapsedTabsIfNeeded() {
        if (collapsedTabs == null || root == null) return;
        collapsedTabs.post(() -> {
            if (collapsedTabs == null || collapsedTabs.getVisibility() != View.VISIBLE) return;
            if (collapsedTabsUserPositioned) {
                positionFloatingView(collapsedTabs, collapsedTabsUserLeft, collapsedTabsUserTop, false);
                return;
            }
            int rootWidth = root.getWidth() > 0 ? root.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
            int rootHeight = root.getHeight() > 0 ? root.getHeight() : activity.getResources().getDisplayMetrics().heightPixels;
            int width = collapsedTabs.getWidth() > 0 ? collapsedTabs.getWidth() : dp(94);
            int height = collapsedTabs.getHeight() > 0 ? collapsedTabs.getHeight() : dp(90);
            positionFloatingView(collapsedTabs, rootWidth - width - dp(6),
                    Math.max(statusBarHeight() + dp(70), (rootHeight - height) / 2), false);
        });
    }

    private Button hudButton(String text, View.OnClickListener listener) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(11.5f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(3), 0, dp(3), 0);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View divider() {
        View line = new View(activity);
        line.setBackgroundColor(Color.rgb(220, 220, 220));
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        return line;
    }

    private GeoMath.Point point(Location location) {
        return new GeoMath.Point(location.getLatitude(), location.getLongitude(),
                location.hasAltitude() ? location.getAltitude() : Double.NaN,
                location.hasAccuracy() ? location.getAccuracy() : -1f,
                location.getTime() > 0L ? location.getTime() : System.currentTimeMillis());
    }

    private FrameLayout findMapRoot(MapView target) {
        View current = target;
        while (current.getParent() instanceof View) {
            View parent = (View) current.getParent();
            if (parent instanceof FrameLayout) return (FrameLayout) parent;
            current = parent;
        }
        return null;
    }

    /** Locate MainActivity's bottom action tray by layout role, never by visible button text. */
    private ViewGroup findBottomControls(FrameLayout container) {
        if (container == null) return null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!(child instanceof ViewGroup) || child == mapView) continue;
            Object tag = child.getTag();
            if (HUD_TAG.equals(tag) || COLLAPSED_TABS_TAG.equals(tag)) continue;
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            if (!(raw instanceof FrameLayout.LayoutParams)) continue;
            int gravity = ((FrameLayout.LayoutParams) raw).gravity;
            if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) return (ViewGroup) child;
        }
        return null;
    }

    private MapView findMapView(View view) {
        if (view instanceof MapView) return (MapView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                MapView found = findMapView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private int statusBarHeight() {
        int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? activity.getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(activity, message == null ? "" : message, Toast.LENGTH_LONG).show();
    }
}
