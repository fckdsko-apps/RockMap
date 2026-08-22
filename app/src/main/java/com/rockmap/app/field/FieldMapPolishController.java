package com.rockmap.app.field;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

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
 * Visual polish layered over the map-first Field controller.
 *
 * This class deliberately does not own toolbars or camera state. FieldMapController is the single
 * owner of persistent tool panels and camera commands. This class only keeps saved-location
 * visibility synchronized and renders geographic labels that must stay visually anchored to map
 * geometry. Context labels are suppressed when zoomed too far out or when they would collide with
 * important map UI.
 */
public final class FieldMapPolishController {
    private static final String SCREEN_LABELS_TAG = "rockmap-field-screen-labels";

    private static final String MAIN_WAYPOINT_LAYER = "rockmap-waypoint-layer";
    private static final String FIELD_WAYPOINT_MIRROR = "rockmap-waypoint-mirror-layer";
    private static final String FIELD_WAYPOINT_LABEL = "rockmap-waypoint-label-layer";
    private static final String MEASURE_LINE_LAYER = "rockmap-field-measure-line-layer";

    private static final String TRACK_ENDPOINT_SOURCE = "rockmap-field-track-endpoint-source";
    private static final String TRACK_ENDPOINT_LAYER = "rockmap-field-track-endpoint-layer";

    // Context labels should help at field scale, not clutter a statewide/regional overview.
    private static final double MEASUREMENT_LABEL_MIN_ZOOM = 12.0d;
    private static final double TRACK_ENDPOINT_LABEL_MIN_ZOOM = 13.0d;

    private final Activity activity;
    private final FieldDatabase db;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private FrameLayout root;
    private MapView mapView;
    private MapLibreMap map;
    private FrameLayout screenLabels;
    private boolean resumed;
    private boolean styleTickPending;

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
        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            applyPolish();
        });
    }

    public void onResume() {
        resumed = true;
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

    /** Keep Field's waypoint mirror and labels locked to Layers > Saved locations. */
    private void syncSavedLocationVisibility(Style style) {
        Layer mainSaved = style.getLayer(MAIN_WAYPOINT_LAYER);
        if (mainSaved == null) return;
        String value = mainSaved.getVisibility().getValue();
        boolean visible = !NONE.equals(value);
        setVisible(style, FIELD_WAYPOINT_MIRROR, visible);
        setVisible(style, FIELD_WAYPOINT_LABEL, visible && FieldMapState.labelsVisible(activity));
    }

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
        bringMapUiToFront();
    }

    private void renderMeasurementLabels() {
        if (currentZoom() < MEASUREMENT_LABEL_MIN_ZOOM) return;
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
        if (currentZoom() < TRACK_ENDPOINT_LABEL_MIN_ZOOM) return;
        if (cachedTrack == null || cachedTrackPoints.size() < 2
                || FieldMapState.selectedTrackDetail(activity) < 0L) return;

        addMapLabel("START", cachedTrackPoints.get(0),
                Color.rgb(20, 70, 135), Color.WHITE, 13f, true, -dp(25));
        addMapLabel("END", cachedTrackPoints.get(cachedTrackPoints.size() - 1),
                Color.rgb(20, 70, 135), Color.WHITE, 13f, true, dp(25));
    }

    private double currentZoom() {
        if (map == null || map.getCameraPosition() == null) return 0d;
        return map.getCameraPosition().zoom;
    }

    private void addMapLabel(String label, GeoMath.Point point, int background, int foreground,
                             float textSizeSp, boolean bold, int verticalOffsetPx) {
        if (screenLabels == null || map == null || point == null || mapView == null) return;
        PointF screen = map.getProjection().toScreenLocation(new LatLng(point.lat, point.lon));
        if (screen == null || !Float.isFinite(screen.x) || !Float.isFinite(screen.y)) return;

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
        Rect candidate = new Rect(left, top, left + width, top + height);

        // Do not detach a geographic label from its feature by clamping it to a screen edge. If it
        // cannot be shown cleanly in the usable map area, suppress it until pan/zoom makes it useful.
        if (candidate.left < dp(3) || candidate.right > mapView.getWidth() - dp(3)
                || candidate.top < statusBarHeight() + dp(3)
                || candidate.bottom > mapView.getHeight() - dp(3)
                || overlapsMapUi(candidate)) return;

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = left;
        params.topMargin = top;
        screenLabels.addView(view, params);
    }

    private boolean overlapsMapUi(Rect candidate) {
        if (root == null || mapView == null) return false;
        for (String tag : new String[]{
                FieldMapController.HUD_TAG,
                FieldMapController.COLLAPSED_TABS_TAG,
                FieldMapController.FIELD_BUTTON_TAG}) {
            View view = root.findViewWithTag(tag);
            if (view != null && view.getVisibility() == View.VISIBLE && view.getWidth() > 0 && view.getHeight() > 0) {
                Rect occupied = new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
                if (Rect.intersects(candidate, occupied)) return true;
            }
        }

        ViewGroup bottomControls = findBottomControls(root);
        if (bottomControls != null && bottomControls.getVisibility() == View.VISIBLE
                && bottomControls.getWidth() > 0 && bottomControls.getHeight() > 0) {
            Rect occupied = new Rect(bottomControls.getLeft(), bottomControls.getTop(),
                    bottomControls.getRight(), bottomControls.getBottom());
            if (Rect.intersects(candidate, occupied)) return true;
        }

        // MapLibre's compass lives inside MapView rather than as a root sibling, so reserve its
        // top-right footprint explicitly. FieldMapController moves it below an expanded HUD.
        int compassTop = statusBarHeight() + dp(8);
        View hud = root.findViewWithTag(FieldMapController.HUD_TAG);
        if (hud != null && hud.getVisibility() == View.VISIBLE && hud.getHeight() > 0) {
            compassTop = Math.max(compassTop, hud.getBottom() + dp(8));
        }
        Rect compass = new Rect(mapView.getWidth() - dp(72), compassTop,
                mapView.getWidth(), compassTop + dp(72));
        return Rect.intersects(candidate, compass);
    }

    private void updateTrackContext(Style style) {
        long selected = FieldMapState.selectedTrackDetail(activity);
        if (selected != cachedTrackId) {
            cachedTrackId = selected;
            cachedTrack = selected >= 0L ? db.getTrack(selected) : null;
            cachedTrackPoints = selected >= 0L ? db.getTrackPoints(selected) : new ArrayList<>();
        }

        if (selected < 0L || cachedTrack == null || cachedTrackPoints.size() < 2) {
            if (selected >= 0L && cachedTrack == null) FieldMapState.clearViewedMapContext(activity);
            cachedTrack = null;
            cachedTrackPoints = new ArrayList<>();
            setEndpointJson(style, emptyCollection(), false);
            return;
        }

        JSONArray endpointFeatures = new JSONArray();
        try {
            endpointFeatures.put(pointFeature(cachedTrackPoints.get(0)));
            endpointFeatures.put(pointFeature(cachedTrackPoints.get(cachedTrackPoints.size() - 1)));
        } catch (JSONException ignored) {
        }
        setEndpointJson(style, collection(endpointFeatures), true);
    }

    private void setEndpointJson(Style style, String json, boolean visible) {
        if (!json.equals(lastEndpointJson)) {
            GeoJsonSource source = style.getSourceAs(TRACK_ENDPOINT_SOURCE);
            if (source != null) source.setGeoJson(json);
            lastEndpointJson = json;
        }
        setVisible(style, TRACK_ENDPOINT_LAYER, visible);
    }

    private void bringMapUiToFront() {
        if (root == null) return;
        View hud = root.findViewWithTag(FieldMapController.HUD_TAG);
        if (hud != null && hud.getVisibility() == View.VISIBLE) hud.bringToFront();
        View tabs = root.findViewWithTag(FieldMapController.COLLAPSED_TABS_TAG);
        if (tabs != null && tabs.getVisibility() == View.VISIBLE) tabs.bringToFront();
        View field = root.findViewWithTag(FieldMapController.FIELD_BUTTON_TAG);
        if (field != null) field.bringToFront();
        ViewGroup bottomControls = findBottomControls(root);
        if (bottomControls != null) bottomControls.bringToFront();
        if (tabs != null && tabs.getVisibility() == View.VISIBLE) tabs.bringToFront();
        if (field != null) field.bringToFront();
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

    private ViewGroup findBottomControls(FrameLayout container) {
        if (container == null) return null;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!(child instanceof ViewGroup) || child == mapView || child == screenLabels) continue;
            Object tag = child.getTag();
            if (FieldMapController.HUD_TAG.equals(tag) || FieldMapController.COLLAPSED_TABS_TAG.equals(tag)) continue;
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
}
