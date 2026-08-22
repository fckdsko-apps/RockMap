package com.rockmap.app.field;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.MainActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Geometry;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.Property.VISIBLE;
import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;

/**
 * Small additive map-polish controller for the Commit-1 field UX.
 *
 * The primary FieldMapController still owns track/area/measurement geometry. This controller
 * deliberately leaves MainActivity, MapController, LocationRepository and the known-good GPS
 * centering path untouched. It supplies the pieces that need to stay visually obvious above the
 * basemap: saved-location visibility synchronization, readable measurement labels, and
 * completed-track context controls/endpoints. Import management lives permanently in Field.
 */
public final class FieldMapPolishController {
    private static final String CONTEXT_HUD_TAG = "rockmap-track-context-hud";
    private static final String EXISTING_FIELD_HUD_TAG = "rockmap-field-map-hud";
    private static final String COLLAPSED_TABS_TAG = "rockmap-field-collapsed-tabs";
    private static final String TRACK_CONTEXT_TAB_TAG = "rockmap-collapsed-track-context";
    private static final String SCREEN_LABELS_TAG = "rockmap-field-screen-labels";
    private static final String FIELD_BUTTON_TAG = "rockmap-field-entry";

    private static final String MAIN_WAYPOINT_LAYER = "rockmap-waypoint-layer";
    private static final String FIELD_WAYPOINT_MIRROR = "rockmap-waypoint-mirror-layer";
    private static final String FIELD_WAYPOINT_LABEL = "rockmap-waypoint-label-layer";

    private static final String MEASURE_LINE_LAYER = "rockmap-field-measure-line-layer";

    private static final String TRACK_ENDPOINT_SOURCE = "rockmap-field-track-endpoint-source";
    private static final String TRACK_ENDPOINT_LAYER = "rockmap-field-track-endpoint-layer";

    private final Activity activity;
    private final FieldDatabase db;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private FrameLayout root;
    private MapView mapView;
    private MapLibreMap map;
    private LinearLayout contextHud;
    private LinearLayout collapsedTabs;
    private FrameLayout screenLabels;
    private boolean resumed;
    private boolean styleTickPending;
    private boolean contextHudDismissed;

    private long cachedTrackId = Long.MIN_VALUE;
    private FieldDatabase.Track cachedTrack;
    private List<GeoMath.Point> cachedTrackPoints = new ArrayList<>();
    private String lastEndpointJson = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            applyPolish();
            main.postDelayed(this, 300L);
        }
    };

    public FieldMapPolishController(Activity activity) {
        this.activity = activity;
        this.db = FieldDatabase.get(activity);
    }

    public void attach() {
        if (!(activity instanceof MainActivity) || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        mapView = findMapView(decor);
        if (mapView == null) return;
        root = findMapRoot(mapView);
        if (root == null) return;

        installScreenLabels();
        installContextHud();
        installCollapsedTabs();

        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            applyPolish();
        });
    }

    public void onResume() {
        resumed = true;
        // Returning from Field > Tracks is an explicit way of reopening a track map view.
        // Do not leave the toolbar hidden simply because the same track id was selected again.
        if (FieldMapState.selectedTrackDetail(activity) >= 0L) contextHudDismissed = false;
        attach();
        main.removeCallbacks(tick);
        main.post(tick);
    }

    public void onPause() {
        resumed = false;
        main.removeCallbacks(tick);
    }

    public void destroy() {
        onPause();
    }

    private void applyPolish() {
        if (map == null || styleTickPending) return;
        styleTickPending = true;
        map.getStyle(style -> {
            styleTickPending = false;
            ensureLayers(style);
            syncSavedLocationVisibility(style);
            updateTrackContext(style);
            renderScreenLabels();
        });
    }

    private void ensureLayers(Style style) {
        if (style.getSource(TRACK_ENDPOINT_SOURCE) == null) {
            style.addSource(new GeoJsonSource(TRACK_ENDPOINT_SOURCE, emptyCollection()));
            lastEndpointJson = null;
        }
        if (style.getLayer(TRACK_ENDPOINT_LAYER) == null) {
            CircleLayer points = new CircleLayer(TRACK_ENDPOINT_LAYER, TRACK_ENDPOINT_SOURCE);
            points.setProperties(
                    circleColor(Color.rgb(20, 70, 135)),
                    circleRadius(8f),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(3f));
            style.addLayer(points);
        }
    }

    /** Keep the Field mirror/labels locked to MainActivity's real Layers > Saved locations state. */
    private void syncSavedLocationVisibility(Style style) {
        Layer mainSaved = style.getLayer(MAIN_WAYPOINT_LAYER);
        if (mainSaved == null) return;
        String value = mainSaved.getVisibility().getValue();
        boolean visible = !NONE.equals(value);
        setVisible(style, FIELD_WAYPOINT_MIRROR, visible);
        setVisible(style, FIELD_WAYPOINT_LABEL, visible && FieldMapState.labelsVisible(activity));
    }

    /**
     * Use Android overlay text instead of relying on a MapLibre glyph/symbol layer. The previous
     * symbol labels could exist in the style yet still be effectively invisible. These labels are
     * positioned from the same geographic line every 300 ms, so the number is visibly attached to
     * the line while the user pans/zooms.
     */
    private void renderScreenLabels() {
        if (screenLabels == null || map == null || mapView == null) return;
        screenLabels.removeAllViews();

        renderMeasurementLabels();
        renderTrackEndpointLabels();

        if (screenLabels.getChildCount() == 0) {
            screenLabels.setVisibility(View.GONE);
        } else {
            screenLabels.setVisibility(View.VISIBLE);
            screenLabels.bringToFront();
        }
        bringOverlayControlsToFront();
        if (contextHud != null && contextHud.getVisibility() == View.VISIBLE) contextHud.bringToFront();
    }

    private void renderMeasurementLabels() {
        List<GeoMath.Point> points = renderedMeasurementPoints();
        if (points.size() < 2) return;

        int segmentCount = points.size() - 1;
        int step = Math.max(1, (int) Math.ceil(segmentCount / 30d));
        for (int i = 0; i < segmentCount; i += step) {
            GeoMath.Point a = points.get(i);
            GeoMath.Point b = points.get(i + 1);
            GeoMath.Point mid = new GeoMath.Point(
                    (a.lat + b.lat) / 2d,
                    midpointLongitude(a.lon, b.lon));
            addMapLabel(
                    GeoMath.distanceLabel(GeoMath.distanceMeters(a, b)),
                    mid,
                    Color.rgb(255, 224, 70),
                    Color.rgb(25, 25, 25),
                    13f,
                    true,
                    0);
        }

        if (points.size() >= 3) {
            GeoMath.Point center = averagePoint(points);
            addMapLabel(
                    "AREA  " + GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(points)),
                    center,
                    Color.rgb(185, 25, 110),
                    Color.WHITE,
                    13f,
                    true,
                    dp(24));
        }
    }

    private List<GeoMath.Point> renderedMeasurementPoints() {
        ArrayList<GeoMath.Point> out = new ArrayList<>();
        if (map == null || mapView == null || mapView.getWidth() <= 0 || mapView.getHeight() <= 0) return out;
        try {
            RectF viewport = new RectF(0f, 0f, mapView.getWidth(), mapView.getHeight());
            List<Feature> features = map.queryRenderedFeatures(viewport, new String[]{MEASURE_LINE_LAYER});
            for (Feature feature : features) {
                if (feature == null) continue;
                Geometry geometry = feature.geometry();
                if (!(geometry instanceof LineString)) continue;
                for (Point point : ((LineString) geometry).coordinates()) {
                    out.add(new GeoMath.Point(point.latitude(), point.longitude()));
                }
                if (!out.isEmpty()) break;
            }
        } catch (RuntimeException ignored) {
            // Map/style transitions are retried on the next tick.
        }
        return out;
    }

    private void renderTrackEndpointLabels() {
        if (cachedTrack == null || cachedTrackPoints.size() < 2
                || FieldMapState.selectedTrackDetail(activity) < 0L) return;

        addMapLabel("START", cachedTrackPoints.get(0),
                Color.rgb(20, 70, 135), Color.WHITE, 13f, true, -dp(25));
        addMapLabel("END", cachedTrackPoints.get(cachedTrackPoints.size() - 1),
                Color.rgb(20, 70, 135), Color.WHITE, 13f, true, dp(25));
    }

    private void addMapLabel(String label, GeoMath.Point point, int background, int foreground,
                             float textSizeSp, boolean bold, int verticalOffsetPx) {
        if (screenLabels == null || map == null || point == null) return;
        PointF screen = map.getProjection().toScreenLocation(new LatLng(point.lat, point.lon));
        if (screen == null || !Float.isFinite(screen.x) || !Float.isFinite(screen.y)) return;

        int safeBottom = Math.max(dp(80), mapView.getHeight() - dp(118));
        if (screen.x < -dp(60) || screen.x > mapView.getWidth() + dp(60)
                || screen.y < -dp(60) || screen.y > mapView.getHeight() + dp(60)) return;

        TextView view = new TextView(activity);
        view.setText(label);
        view.setTextSize(textSizeSp);
        view.setTextColor(foreground);
        view.setBackgroundColor(background);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(6), dp(3), dp(6), dp(3));
        view.setClickable(false);
        view.setFocusable(false);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        int width = Math.max(dp(38), view.getMeasuredWidth());
        int height = Math.max(dp(24), view.getMeasuredHeight());
        int left = Math.round(screen.x - width / 2f);
        int top = Math.round(screen.y - height / 2f) + verticalOffsetPx;
        left = Math.max(dp(3), Math.min(left, Math.max(dp(3), mapView.getWidth() - width - dp(3))));
        top = Math.max(statusBarHeight() + dp(3), Math.min(top, safeBottom - height));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        screenLabels.addView(view, params);
    }

    private void updateTrackContext(Style style) {
        long selected = FieldMapState.selectedTrackDetail(activity);
        if (selected != cachedTrackId) {
            cachedTrackId = selected;
            cachedTrack = selected >= 0L ? db.getTrack(selected) : null;
            cachedTrackPoints = selected >= 0L ? db.getTrackPoints(selected) : new ArrayList<>();
            contextHudDismissed = false;
        }

        if (selected < 0L || cachedTrack == null || cachedTrackPoints.size() < 2) {
            if (selected >= 0L && cachedTrack == null) FieldMapState.clearSelectedTrackDetail(activity);
            setEndpointJson(style, emptyCollection(), false);
            hideContextHud();
            updateTrackCollapsedTab(false);
            return;
        }

        JSONArray endpointFeatures = new JSONArray();
        try {
            endpointFeatures.put(pointFeature(cachedTrackPoints.get(0)));
            endpointFeatures.put(pointFeature(cachedTrackPoints.get(cachedTrackPoints.size() - 1)));
        } catch (JSONException ignored) {
        }
        setEndpointJson(style, collection(endpointFeatures), true);

        if (contextHudDismissed) {
            hideContextHud();
            updateTrackCollapsedTab(true);
        } else {
            updateTrackCollapsedTab(false);
            renderContextHud(cachedTrack, cachedTrackPoints);
        }
    }

    private void setEndpointJson(Style style, String json, boolean visible) {
        if (!json.equals(lastEndpointJson)) {
            GeoJsonSource source = style.getSourceAs(TRACK_ENDPOINT_SOURCE);
            if (source != null) source.setGeoJson(json);
            lastEndpointJson = json;
        }
        setVisible(style, TRACK_ENDPOINT_LAYER, visible);
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

    private void updateTrackCollapsedTab(boolean visible) {
        if (collapsedTabs == null) installCollapsedTabs();
        if (collapsedTabs == null) return;

        View existing = collapsedTabs.findViewWithTag(TRACK_CONTEXT_TAB_TAG);
        if (existing != null) collapsedTabs.removeView(existing);

        if (visible) {
            Button tab = new Button(activity);
            tab.setTag(TRACK_CONTEXT_TAB_TAG);
            tab.setText("‹ Track");
            tab.setAllCaps(false);
            tab.setTextSize(11f);
            tab.setMinWidth(dp(82));
            tab.setMinimumWidth(dp(82));
            tab.setMinHeight(dp(40));
            tab.setMinimumHeight(dp(40));
            tab.setPadding(dp(5), 0, dp(5), 0);
            tab.setContentDescription("Expand Track & backtrack map toolbar");
            tab.setOnClickListener(v -> reopenTrackToolbar());
            collapsedTabs.addView(tab, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        collapsedTabs.setVisibility(collapsedTabs.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (collapsedTabs.getVisibility() == View.VISIBLE) collapsedTabs.bringToFront();
    }

    private void bringOverlayControlsToFront() {
        if (collapsedTabs != null && collapsedTabs.getVisibility() == View.VISIBLE) collapsedTabs.bringToFront();
        if (root != null) {
            View field = root.findViewWithTag(FIELD_BUTTON_TAG);
            if (field != null) field.bringToFront();
        }
    }

    private void installContextHud() {
        if (root == null) return;
        View existing = root.findViewWithTag(CONTEXT_HUD_TAG);
        if (existing instanceof LinearLayout) {
            contextHud = (LinearLayout) existing;
            return;
        }
        contextHud = new LinearLayout(activity);
        contextHud.setTag(CONTEXT_HUD_TAG);
        contextHud.setOrientation(LinearLayout.VERTICAL);
        contextHud.setPadding(dp(10), dp(8), dp(10), dp(8));
        contextHud.setBackgroundColor(Color.argb(240, 255, 255, 255));
        contextHud.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.setMargins(dp(8), statusBarHeight() + dp(8), dp(8), 0);
        root.addView(contextHud, params);
    }

    private void installScreenLabels() {
        if (root == null) return;
        View existing = root.findViewWithTag(SCREEN_LABELS_TAG);
        if (existing instanceof FrameLayout) {
            screenLabels = (FrameLayout) existing;
            return;
        }
        screenLabels = new FrameLayout(activity);
        screenLabels.setTag(SCREEN_LABELS_TAG);
        screenLabels.setClickable(false);
        screenLabels.setFocusable(false);
        screenLabels.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(screenLabels, params);
    }

    private void renderContextHud(FieldDatabase.Track track, List<GeoMath.Point> points) {
        if (contextHud == null) return;

        if (FieldDatabase.TRACK_RECORDING.equals(track.status)
                || FieldDatabase.TRACK_PAUSED.equals(track.status)) {
            hideContextHud();
            return;
        }

        contextHud.removeAllViews();

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("Track & backtrack — " + track.name, 13.5f, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button collapse = button("›");
        collapse.setTextSize(20f);
        collapse.setContentDescription("Collapse Track & backtrack toolbar to the right edge");
        collapse.setOnClickListener(v -> {
            contextHudDismissed = true;
            hideContextHud();
            updateTrackCollapsedTab(true);
            renderScreenLabels();
        });
        header.addView(collapse, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        contextHud.addView(header);

        contextHud.addView(text(points.size() + " points · "
                        + GeoMath.distanceLabel(GeoMath.pathDistanceMeters(points))
                        + "\nSTART and END are labeled directly on the basemap.",
                11.5f, false));

        LinearLayout firstRow = new LinearLayout(activity);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);

        Button backtrack = button("Backtrack");
        backtrack.setOnClickListener(v -> {
            if (cachedTrackPoints.size() < 2 || cachedTrack == null) return;
            FieldMapState.showTrack(activity, cachedTrack.id);
            FieldMapState.requestTrackFocus(activity, cachedTrack.id);
            FieldMapState.startNavigation(activity, "Start of " + cachedTrack.name, cachedTrackPoints.get(0));
            FieldMapState.clearSelectedTrackDetail(activity);
            resetTrackContext();
            applyPolish();
        });
        firstRow.addView(backtrack, weight());

        Button hide = button("Hide");
        hide.setOnClickListener(v -> {
            if (cachedTrack == null) return;
            FieldMapState.hideTrack(activity, cachedTrack.id);
            resetTrackContext();
            toast("Track hidden. Reopen it from Field > Tracks to show it again.");
            applyPolish();
        });
        firstRow.addView(hide, weight());

        Button delete = button("Delete");
        delete.setOnClickListener(v -> {
            if (cachedTrack == null) return;
            FieldDatabase.Track trackToDelete = cachedTrack;
            new AlertDialog.Builder(activity)
                    .setTitle("Delete track?")
                    .setMessage("Permanently remove “" + trackToDelete.name + "” and all of its recorded points?")
                    .setPositiveButton("Delete", (d, w) -> {
                        db.deleteTrack(trackToDelete.id);
                        FieldMapState.clearSelectedTrackDetail(activity);
                        resetTrackContext();
                        toast("Track deleted.");
                        applyPolish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        firstRow.addView(delete, weight());
        contextHud.addView(firstRow);

        LinearLayout secondRow = new LinearLayout(activity);
        secondRow.setOrientation(LinearLayout.HORIZONTAL);

        Button tracks = button("All tracks");
        tracks.setOnClickListener(v -> {
            FieldMapState.clearSelectedTrackDetail(activity);
            resetTrackContext();
            IntentFactory.openTracks(activity);
        });
        secondRow.addView(tracks, weight());

        Button close = button("Close map view");
        close.setOnClickListener(v -> {
            FieldMapState.clearSelectedTrackDetail(activity);
            resetTrackContext();
            applyPolish();
        });
        secondRow.addView(close, weight());

        contextHud.addView(secondRow);
        positionContextHud();
        contextHud.setVisibility(View.VISIBLE);
        contextHud.bringToFront();
        bringOverlayControlsToFront();
    }

    private void reopenTrackToolbar() {
        if (FieldMapState.selectedTrackDetail(activity) < 0L) {
            updateTrackCollapsedTab(false);
            toast("No track map is currently selected.");
            return;
        }
        contextHudDismissed = false;
        updateTrackCollapsedTab(false);
        applyPolish();
    }

    private void resetTrackContext() {
        cachedTrackId = Long.MIN_VALUE;
        cachedTrack = null;
        cachedTrackPoints = new ArrayList<>();
        contextHudDismissed = false;
        hideContextHud();
        updateTrackCollapsedTab(false);
    }

    private void positionContextHud() {
        if (contextHud == null || root == null) return;
        int top = statusBarHeight() + dp(8);
        View primary = root.findViewWithTag(EXISTING_FIELD_HUD_TAG);
        if (primary != null && primary.getVisibility() == View.VISIBLE && primary.getHeight() > 0) {
            top += primary.getHeight() + dp(6);
        }
        ViewGroup.LayoutParams raw = contextHud.getLayoutParams();
        if (raw instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) raw;
            params.topMargin = top;
            contextHud.setLayoutParams(params);
        }
    }

    private void hideContextHud() {
        if (contextHud != null) contextHud.setVisibility(View.GONE);
    }

    private void setVisible(Style style, String id, boolean visible) {
        Layer layer = style.getLayer(id);
        if (layer != null) layer.setProperties(visibility(visible ? VISIBLE : NONE));
    }

    private static double midpointLongitude(double a, double b) {
        double delta = b - a;
        if (delta > 180d) delta -= 360d;
        if (delta < -180d) delta += 360d;
        double value = a + delta / 2d;
        if (value > 180d) value -= 360d;
        if (value < -180d) value += 360d;
        return value;
    }

    private static GeoMath.Point averagePoint(List<GeoMath.Point> points) {
        double lat = 0d;
        double x = 0d;
        double y = 0d;
        for (GeoMath.Point point : points) {
            lat += point.lat;
            double radians = Math.toRadians(point.lon);
            x += Math.cos(radians);
            y += Math.sin(radians);
        }
        double lon = Math.toDegrees(Math.atan2(y, x));
        return new GeoMath.Point(lat / points.size(), lon);
    }

    private static JSONObject pointFeature(GeoMath.Point point) throws JSONException {
        JSONArray coordinates = new JSONArray().put(point.lon).put(point.lat);
        JSONObject geometry = new JSONObject().put("type", "Point").put("coordinates", coordinates);
        return new JSONObject().put("type", "Feature")
                .put("properties", new JSONObject())
                .put("geometry", geometry);
    }

    private static String collection(JSONArray features) {
        try {
            return new JSONObject().put("type", "FeatureCollection").put("features", features).toString();
        } catch (JSONException ex) {
            return emptyCollection();
        }
    }

    private static String emptyCollection() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
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

    private TextView text(String value, float size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(45, 45, 45));
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setPadding(0, 0, 0, dp(4));
        return view;
    }

    private Button button(String value) {
        Button button = new Button(activity);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextSize(11f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setPadding(dp(2), 0, dp(2), 0);
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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

    private static final class IntentFactory {
        static void openTracks(Activity activity) {
            android.content.Intent intent = new android.content.Intent(activity, FieldActivity.class);
            intent.putExtra(FieldActivity.EXTRA_SCREEN, "tracks");
            activity.startActivity(intent);
        }

    }
}
