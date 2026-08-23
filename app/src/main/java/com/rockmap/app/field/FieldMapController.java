package com.rockmap.app.field;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PointF;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.MainActivity;
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
        hud.setBackgroundColor(Color.argb(238, 255, 255, 255));
        hud.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.setMargins(dp(8), statusBarHeight() + dp(8), dp(8), 0);
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
        collapsedTabs.setPadding(0, dp(2), 0, dp(2));
        collapsedTabs.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.END);
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
            if (hud != null && hud.getVisibility() == View.VISIBLE && hud.getHeight() > 0) {
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
            collapsedTabs.setVisibility(collapsedTabs.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        }
        bringFieldUiToFront();
        positionFieldButton();
        if (hud != null) hud.post(this::updateMapUiInsets);
    }

    private void addTrackHud(FieldDatabase.Track activeTrack, FieldDatabase.Track viewedTrack) {
        String title = FieldUiNames.TRACK;
        if (activeTrack != null && viewedTrack == null) title += " — " + activeTrack.name;
        else if (activeTrack == null && viewedTrack != null) title += " — " + viewedTrack.name;
        hud.addView(panelHeader(title, FieldUiNames.TRACK_SHORT,
                v -> setExpandedTool(null)));

        if (activeTrack != null) {
            List<GeoMath.Point> points = db.getTrackPoints(activeTrack.id);
            String prefix = viewedTrack == null ? "" : "Recording — " + activeTrack.name + "\n";
            hud.addView(hudText(prefix
                    + (FieldDatabase.TRACK_PAUSED.equals(activeTrack.status) ? "Paused" : "Recording")
                    + " · " + points.size() + " points · "
                    + GeoMath.distanceLabel(GeoMath.pathDistanceMeters(points))
                    + "\nThe line continues recording while this panel is collapsed or another tool is open."));
            LinearLayout row = buttonRow();
            if (FieldDatabase.TRACK_PAUSED.equals(activeTrack.status)) {
                row.addView(hudButton("Resume", v -> trackCommand(TrackRecordingService.ACTION_RESUME, activeTrack.id)), weight());
            } else {
                row.addView(hudButton("Pause", v -> trackCommand(TrackRecordingService.ACTION_PAUSE, activeTrack.id)), weight());
            }
            row.addView(hudButton("Stop", v -> confirmStopTrack(activeTrack)), weight());
            row.addView(hudButton("Tracks", v -> openFieldScreen("tracks")), weight());
            hud.addView(row);
        }

        if (viewedTrack != null) {
            if (activeTrack != null) hud.addView(divider());
            List<GeoMath.Point> points = db.getTrackPoints(viewedTrack.id);
            FieldDatabase.Track selected = viewedTrack;
            hud.addView(hudText("Viewing — " + selected.name + "\n"
                    + points.size() + " points · " + GeoMath.distanceLabel(GeoMath.pathDistanceMeters(points))
                    + "\nSTART and END appear once the map is zoomed in enough to use them."));

            LinearLayout first = buttonRow();
            first.addView(hudButton("Backtrack", v -> {
                if (points.size() < 2) return;
                FieldMapState.showTrack(activity, selected.id);
                FieldMapState.clearViewedMapContext(activity);
                startNavigation("Start of " + selected.name, points.get(0));
            }), weight());
            first.addView(hudButton("Hide", v -> {
                FieldMapState.hideTrack(activity, selected.id);
                FieldMapState.clearViewedMapContext(activity);
                refreshFieldSnapshot();
                applyCachedSources();
                renderHud();
                toast("Track hidden. Reopen it from Field > Tracks to show it again.");
            }), weight());
            first.addView(hudButton("Delete", v -> new AlertDialog.Builder(activity)
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
                    .show()), weight());
            hud.addView(first);

            LinearLayout second = buttonRow();
            second.addView(hudButton("All tracks", v -> {
                FieldMapState.clearViewedMapContext(activity);
                if (activeTrack == null) setExpandedTool(null);
                openFieldScreen("tracks");
            }), weight());
            second.addView(hudButton("Close map view", v -> {
                FieldMapState.clearViewedMapContext(activity);
                if (activeTrack == null) setExpandedTool(null);
                else renderHud();
                applyCachedSources();
            }), weight());
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
                    + "\nStraight-line guidance only; RockMap does not calculate a safe or legal route.";
        }
        hud.addView(hudText(status));
        LinearLayout row = buttonRow();
        row.addView(hudButton("Frame", v -> frameNavigation(target)), weight());
        row.addView(hudButton("Target", v -> centerExplicit(target.point, 16d)), weight());
        row.addView(hudButton("Stop", v -> stopNavigation()), weight());
        hud.addView(row);
    }

    private void addMeasureHud() {
        hud.addView(panelHeader(
                FieldUiNames.MEASURE + " — " + measurement.size() + " point" + (measurement.size() == 1 ? "" : "s"),
                FieldUiNames.MEASURE_SHORT,
                v -> {
                    removeTapCapture();
                    setExpandedTool(null);
                }));
        hud.addView(hudText(measurementSummary()));

        LinearLayout first = buttonRow();
        first.addView(hudButton(awaitingMapTap ? "Cancel tap" : "Tap map", v -> {
            if (awaitingMapTap) removeTapCapture(); else beginOneShotMapTap();
            renderHud();
        }), weight());
        Button addGps = hudButton("Add GPS", v -> addGpsMeasurement());
        addGps.setContentDescription("Add GPS. Get a fresh precise GPS fix and add your current location as the next measurement point.");
        first.addView(addGps, weight());
        first.addView(hudButton("Saved", v -> chooseSavedMeasurement()), weight());
        first.addView(hudButton("Field", v -> chooseFieldRecordMeasurement()), weight());
        hud.addView(first);

        LinearLayout second = buttonRow();
        second.addView(hudButton("Paste", v -> pasteMeasurement()), weight());
        second.addView(hudButton("Undo", v -> undoMeasurement()), weight());
        second.addView(hudButton("Done", v -> finishMeasurement()), weight());
        hud.addView(second);

        if (measurement.size() >= 3) {
            Button save = hudButton("Save as Prospecting Area", v -> saveMeasurementArea());
            save.setTextSize(13f);
            save.setContentDescription("Save this temporary measured polygon as a persistent Prospecting Area");
            hud.addView(save, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
            hud.addView(hudText("This measurement is temporary until you save it as a Prospecting Area."));
        } else {
            hud.addView(hudText("Add at least 3 points to save a Prospecting Area."));
        }
    }

    private void showFieldMenu() {
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = dialogBox();
        box.addView(dialogAction(FieldUiNames.TRACK, "Record a track and see it build live on this map.", v -> {
            holder[0].dismiss(); openFieldScreen("tracks");
        }));
        box.addView(dialogAction(FieldUiNames.NAVIGATE, "Choose a Saved Location, Field Record, or coordinate and follow a live map line.", v -> {
            holder[0].dismiss(); showNavigateMenu();
        }));
        box.addView(dialogAction(FieldUiNames.MEASURE, "Tap map points or use GPS/saved records; see distance and area directly here.", v -> {
            holder[0].dismiss(); startMeasurement();
        }));
        box.addView(dialogAction(FieldUiNames.FIELD_RECORDS, "Create, edit, photograph, navigate to, and research saved field observations.", v -> {
            holder[0].dismiss(); openFieldScreen("records");
        }));
        box.addView(dialogAction(FieldUiNames.PROSPECTING_AREAS, "Create, open, analyze, and manage saved prospecting areas.", v -> {
            holder[0].dismiss(); openFieldScreen("areas");
        }));
        box.addView(dialogAction(FieldUiNames.IMPORT, "Import GPX, KML, or GeoJSON files into RockMap.", v -> {
            holder[0].dismiss(); openFieldScreen("import");
        }));
        box.addView(dialogAction(FieldUiNames.IMPORTED_DATA, "Review imported files, show their contents on the map, or remove one import safely.", v -> {
            holder[0].dismiss(); openFieldScreen("imports");
        }));
        box.addView(dialogAction(FieldUiNames.EXPORT, "Export Saved Locations, Tracks, Field Records, Prospecting Areas, imported files, or combined field data.", v -> {
            holder[0].dismiss(); openFieldScreen("export");
        }));
        box.addView(dialogAction(FieldUiNames.COORDINATES, "Convert decimal, DDM, DMS, UTM, and MGRS coordinates.", v -> {
            holder[0].dismiss(); openFieldScreen("coordinates");
        }));
        box.addView(dialogAction(FieldUiNames.VISIBILITY, "Choose which Tracks, Prospecting Areas, Field Records, and Saved Location labels appear on the map.", v -> {
            holder[0].dismiss(); showVisibilityMenu();
        }));
        holder[0] = new AlertDialog.Builder(activity)
                .setTitle("Field tools")
                .setView(scrollDialog(box))
                .setNegativeButton("Close", null)
                .create();
        holder[0].show();
    }

    private void showNavigateMenu() {
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = dialogBox();
        if (FieldMapState.navigationTarget(activity) != null) {
            box.addView(dialogAction("Stop current navigation", "Remove the target and bearing line from the map.", v -> {
                holder[0].dismiss(); stopNavigation();
            }));
        }
        box.addView(dialogAction("Saved Location", "Choose one of your normal RockMap Saved Locations.", v -> {
            holder[0].dismiss(); chooseSavedNavigation();
        }));
        box.addView(dialogAction("Field Record", "Choose a field observation or sample record.", v -> {
            holder[0].dismiss(); chooseFieldRecordNavigation();
        }));
        box.addView(dialogAction("Enter coordinates", "Paste or type a latitude/longitude supported by RockMap.", v -> {
            holder[0].dismiss(); enterNavigationCoordinate();
        }));
        holder[0] = new AlertDialog.Builder(activity).setTitle("Navigate on map").setView(box)
                .setNegativeButton("Close", null).create();
        holder[0].show();
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
                renderHud();
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
            points.setProperties(circleColor(Color.rgb(10, 120, 105)), circleRadius(6f),
                    circleStrokeColor(Color.WHITE), circleStrokeWidth(2f));
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
        renderHud();
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
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                CoordinateParser.Result parsed = CoordinateParser.parse(input.getText().toString());
                dialog.dismiss();
                startNavigation("Coordinate target", new GeoMath.Point(parsed.latitude, parsed.longitude));
            } catch (IllegalArgumentException ex) {
                input.setError(ex.getMessage());
            }
        }));
        dialog.show();
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

    private void addGpsMeasurement() {
        locationRepository.requestFreshPrecise(location ->
                addMeasurementPoint(point(location), true), this::toast);
    }

    private void chooseSavedMeasurement() {
        waypointRepository.getAll(items -> {
            if (items == null || items.isEmpty()) { toast("No Saved Locations yet."); return; }
            String[] labels = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                String name = items.get(i).name;
                labels[i] = name == null || name.trim().isEmpty() ? "Saved Location" : name.trim();
            }
            new AlertDialog.Builder(activity).setTitle("Add Saved Location to Measurement")
                    .setItems(labels, (d, which) -> {
                        WaypointEntity w = items.get(which);
                        addMeasurementPoint(new GeoMath.Point(w.latitude, w.longitude), true);
                    }).setNegativeButton("Cancel", null).show();
        });
    }

    private void chooseFieldRecordMeasurement() {
        List<FieldDatabase.FieldRecord> items = db.listFieldRecords();
        if (items.isEmpty()) { toast("No Field Records yet."); return; }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
                String name = items.get(i).name;
                labels[i] = name == null || name.trim().isEmpty() ? "Saved Location" : name.trim();
            }
        new AlertDialog.Builder(activity).setTitle("Add Field Record to Measurement")
                .setItems(labels, (d, which) -> {
                    FieldDatabase.FieldRecord r = items.get(which);
                    addMeasurementPoint(new GeoMath.Point(r.lat, r.lon), true);
                }).setNegativeButton("Cancel", null).show();
    }

    private void pasteMeasurement() {
        EditText input = new EditText(activity);
        input.setHint("Latitude, longitude");
        input.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Add Coordinate")
                .setView(input).setPositiveButton("Add", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                CoordinateParser.Result p = CoordinateParser.parse(input.getText().toString());
                dialog.dismiss();
                addMeasurementPoint(new GeoMath.Point(p.latitude, p.longitude), true);
            } catch (IllegalArgumentException ex) { input.setError(ex.getMessage()); }
        }));
        dialog.show();
    }

    private void addMeasurementPoint(GeoMath.Point point, boolean centerIfFirst) {
        if (!measureActive) measureActive = true;
        measurement.add(point);
        if (measurement.size() > 2000) measurement.remove(measurement.size() - 1);
        FieldMapState.saveMeasurement(activity, measurement, true);
        if (centerIfFirst && measurement.size() == 1) centerExplicit(point, 16d);
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
        new AlertDialog.Builder(activity).setTitle("Save measured area as Prospecting Area")
                .setMessage(measurementSummary()
                        + "\n\nThis will turn the temporary measurement into a saved Prospecting Area. "
                        + "After saving, RockMap will offer to research the exact saved area.")
                .setView(name)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        ProspectingAreaCreator.saveNamedPolygonAndPrompt(
                                activity, name.getText().toString().trim(),
                                "Saved from map measurement", new ArrayList<>(measurement), false);
                        measureActive = false;
                        measurement.clear();
                        FieldMapState.clearMeasurement(activity);
                        if (FieldMapState.TOOL_MEASURE.equals(expandedTool)) setExpandedToolValue(null);
                        removeTapCapture();
                        refreshFieldSnapshot();
                        applyCachedSources();
                        renderHud();
                    } catch (RuntimeException ex) {
                        toast(ex.getMessage() == null ? "Could not save this Prospecting Area." : ex.getMessage());
                    }
                }).setNegativeButton("Cancel", null).show();
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
        new AlertDialog.Builder(activity).setTitle("Finish measurement?")
                .setMessage("The temporary measurement will be cleared. Save it as a prospecting area first if you want it to remain on the map.")
                .setPositiveButton("Clear & finish", (d, w) -> {
                    measurement.clear();
                    measureActive = false;
                    FieldMapState.clearMeasurement(activity);
                    if (FieldMapState.TOOL_MEASURE.equals(expandedTool)) setExpandedToolValue(null);
                    removeTapCapture();
                    applyCachedSources();
                    renderHud();
                })
                .setNegativeButton("Keep measuring", null).show();
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
        new AlertDialog.Builder(activity).setTitle("Stop track?")
                .setMessage("The recorded line will remain visible on the map until you hide or delete the track.")
                .setPositiveButton("Stop", (d, w) -> trackCommand(TrackRecordingService.ACTION_STOP, track.id))
                .setNegativeButton("Cancel", null).show();
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

        TextView title = hudTitle(titleText);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button arrow = new Button(activity);
        arrow.setText("›");
        arrow.setAllCaps(false);
        arrow.setTextSize(20f);
        arrow.setMinWidth(dp(44));
        arrow.setMinimumWidth(dp(44));
        arrow.setMinHeight(dp(40));
        arrow.setMinimumHeight(dp(40));
        arrow.setPadding(0, 0, 0, 0);
        arrow.setContentDescription("Collapse " + shortLabel + " toolbar to the right edge");
        arrow.setOnClickListener(collapse);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
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
        collapsedTabs.addView(tab, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        collapsedTabs.setVisibility(View.VISIBLE);
        bringFieldUiToFront();
    }

    private void removeCollapsedTab(String tag) {
        if (collapsedTabs == null) return;
        View tab = collapsedTabs.findViewWithTag(tag);
        if (tab != null) collapsedTabs.removeView(tab);
        collapsedTabs.setVisibility(collapsedTabs.getChildCount() > 0 ? View.VISIBLE : View.GONE);
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
