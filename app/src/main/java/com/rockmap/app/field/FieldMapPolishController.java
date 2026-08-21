package com.rockmap.app.field;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
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
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.Geometry;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;

import static org.maplibre.android.style.expressions.Expression.get;
import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.Property.VISIBLE;
import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.textColor;
import static org.maplibre.android.style.layers.PropertyFactory.textField;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloColor;
import static org.maplibre.android.style.layers.PropertyFactory.textHaloWidth;
import static org.maplibre.android.style.layers.PropertyFactory.textOffset;
import static org.maplibre.android.style.layers.PropertyFactory.textSize;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;

/**
 * Small additive map-polish controller for Commit-1 field UX.
 *
 * It deliberately does not replace MainActivity, MapController, LocationRepository, or the
 * primary FieldMapController. It adds three narrowly-scoped behaviors:
 *  1) measurement values directly on measurement geometry,
 *  2) immediate synchronization of Field waypoint mirror/labels with Layers > Saved locations,
 *  3) completed-track context HUD plus START/END markers on the real basemap.
 */
public final class FieldMapPolishController {
    private static final String CONTEXT_HUD_TAG = "rockmap-track-context-hud";
    private static final String EXISTING_FIELD_HUD_TAG = "rockmap-field-map-hud";
    private static final String IMPORTS_BUTTON_TAG = "rockmap-imports-entry";

    private static final String MAIN_WAYPOINT_LAYER = "rockmap-waypoint-layer";
    private static final String FIELD_WAYPOINT_MIRROR = "rockmap-waypoint-mirror-layer";
    private static final String FIELD_WAYPOINT_LABEL = "rockmap-waypoint-label-layer";

    private static final String MEASURE_LINE_SOURCE = "rockmap-field-measure-line-source";
    private static final String MEASURE_LABEL_SOURCE = "rockmap-field-measure-label-source";
    private static final String MEASURE_LABEL_LAYER = "rockmap-field-measure-label-layer";

    private static final String TRACK_ENDPOINT_SOURCE = "rockmap-field-track-endpoint-source";
    private static final String TRACK_ENDPOINT_LAYER = "rockmap-field-track-endpoint-layer";
    private static final String TRACK_ENDPOINT_LABEL = "rockmap-field-track-endpoint-label";

    private final Activity activity;
    private final FieldDatabase db;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    private FrameLayout root;
    private MapView mapView;
    private MapLibreMap map;
    private LinearLayout contextHud;
    private Button importsButton;
    private boolean resumed;
    private boolean styleTickPending;

    private long cachedTrackId = Long.MIN_VALUE;
    private FieldDatabase.Track cachedTrack;
    private List<GeoMath.Point> cachedTrackPoints = new ArrayList<>();
    private String lastMeasureLabelJson = "";
    private String lastEndpointJson = "";
    private long lastImportsCheck;
    private boolean cachedHasImports;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            applyPolish();
            main.postDelayed(this, 350L);
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
        installContextHud();
        installImportsButton();
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
            updateImportsButton();
            syncSavedLocationVisibility(style);
            updateMeasurementLabels(style);
            updateTrackContext(style);
        });
    }

    private void ensureLayers(Style style) {
        if (style.getSource(MEASURE_LABEL_SOURCE) == null) {
            style.addSource(new GeoJsonSource(MEASURE_LABEL_SOURCE, emptyCollection()));
            lastMeasureLabelJson = null;
        }
        if (style.getLayer(MEASURE_LABEL_LAYER) == null) {
            SymbolLayer labels = new SymbolLayer(MEASURE_LABEL_LAYER, MEASURE_LABEL_SOURCE);
            labels.setProperties(
                    textField(get("name")),
                    textSize(13f),
                    textColor(Color.rgb(185, 25, 110)),
                    textHaloColor(Color.WHITE),
                    textHaloWidth(2.2f),
                    textAllowOverlap(true));
            style.addLayer(labels);
        }

        if (style.getSource(TRACK_ENDPOINT_SOURCE) == null) {
            style.addSource(new GeoJsonSource(TRACK_ENDPOINT_SOURCE, emptyCollection()));
            lastEndpointJson = null;
        }
        if (style.getLayer(TRACK_ENDPOINT_LAYER) == null) {
            CircleLayer points = new CircleLayer(TRACK_ENDPOINT_LAYER, TRACK_ENDPOINT_SOURCE);
            points.setProperties(
                    circleColor(Color.rgb(20, 70, 135)),
                    circleRadius(7f),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(2.5f));
            style.addLayer(points);
        }
        if (style.getLayer(TRACK_ENDPOINT_LABEL) == null) {
            SymbolLayer labels = new SymbolLayer(TRACK_ENDPOINT_LABEL, TRACK_ENDPOINT_SOURCE);
            labels.setProperties(
                    textField(get("name")),
                    textSize(12f),
                    textColor(Color.rgb(15, 45, 90)),
                    textHaloColor(Color.WHITE),
                    textHaloWidth(2f),
                    textOffset(new Float[]{0f, 1.35f}),
                    textAllowOverlap(true));
            style.addLayer(labels);
        }
    }

    /**
     * MainActivity owns Layers > Saved locations. The previous map integration mirrored those
     * circles for immediate import visibility, so this tiny loop follows the real layer's
     * visibility within a fraction of a second rather than waiting for the heavier field refresh.
     */
    private void syncSavedLocationVisibility(Style style) {
        Layer mainSaved = style.getLayer(MAIN_WAYPOINT_LAYER);
        if (mainSaved == null) return;
        String value = mainSaved.getVisibility().getValue();
        boolean visible = !NONE.equals(value);
        setVisible(style, FIELD_WAYPOINT_MIRROR, visible);
        setVisible(style, FIELD_WAYPOINT_LABEL, visible && FieldMapState.labelsVisible(activity));
    }

    private void updateMeasurementLabels(Style style) {
        GeoJsonSource lineSource = style.getSourceAs(MEASURE_LINE_SOURCE);
        if (lineSource == null) {
            setMeasureLabels(style, emptyCollection(), false);
            return;
        }

        List<GeoMath.Point> points = new ArrayList<>();
        try {
            List<Feature> features = lineSource.querySourceFeatures(null);
            for (Feature feature : features) {
                if (feature == null) continue;
                Geometry geometry = feature.geometry();
                if (!(geometry instanceof LineString)) continue;
                for (Point point : ((LineString) geometry).coordinates()) {
                    points.add(new GeoMath.Point(point.latitude(), point.longitude()));
                }
                if (!points.isEmpty()) break;
            }
        } catch (RuntimeException ignored) {
            // A style can be in the middle of reloading. The next 350 ms tick retries safely.
        }

        if (points.size() < 2) {
            setMeasureLabels(style, emptyCollection(), false);
            return;
        }

        JSONArray features = new JSONArray();
        try {
            int segmentCount = points.size() - 1;
            int step = Math.max(1, (int) Math.ceil(segmentCount / 40d));
            for (int i = 0; i < segmentCount; i += step) {
                GeoMath.Point a = points.get(i);
                GeoMath.Point b = points.get(i + 1);
                GeoMath.Point mid = new GeoMath.Point((a.lat + b.lat) / 2d, midpointLongitude(a.lon, b.lon));
                String label = GeoMath.distanceLabel(GeoMath.distanceMeters(a, b));
                features.put(pointFeature(mid, label));
            }

            if (points.size() >= 3) {
                GeoMath.Point center = averagePoint(points);
                String area = "AREA  " + GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(points));
                features.put(pointFeature(center, area));
            }
        } catch (JSONException ignored) {
            setMeasureLabels(style, emptyCollection(), false);
            return;
        }
        setMeasureLabels(style, collection(features), true);
    }

    private void setMeasureLabels(Style style, String json, boolean visible) {
        if (!json.equals(lastMeasureLabelJson)) {
            GeoJsonSource source = style.getSourceAs(MEASURE_LABEL_SOURCE);
            if (source != null) source.setGeoJson(json);
            lastMeasureLabelJson = json;
        }
        setVisible(style, MEASURE_LABEL_LAYER, visible);
    }

    private void updateTrackContext(Style style) {
        long selected = FieldMapState.selectedTrackDetail(activity);
        if (selected != cachedTrackId) {
            cachedTrackId = selected;
            cachedTrack = selected >= 0L ? db.getTrack(selected) : null;
            cachedTrackPoints = selected >= 0L ? db.getTrackPoints(selected) : new ArrayList<>();
        }

        if (selected < 0L || cachedTrack == null || cachedTrackPoints.size() < 2) {
            if (selected >= 0L && cachedTrack == null) FieldMapState.clearSelectedTrackDetail(activity);
            setEndpointJson(style, emptyCollection(), false);
            hideContextHud();
            return;
        }

        JSONArray endpointFeatures = new JSONArray();
        try {
            endpointFeatures.put(pointFeature(cachedTrackPoints.get(0), "START"));
            endpointFeatures.put(pointFeature(cachedTrackPoints.get(cachedTrackPoints.size() - 1), "END"));
        } catch (JSONException ignored) {}
        setEndpointJson(style, collection(endpointFeatures), true);
        renderContextHud(cachedTrack, cachedTrackPoints);
    }

    private void setEndpointJson(Style style, String json, boolean visible) {
        if (!json.equals(lastEndpointJson)) {
            GeoJsonSource source = style.getSourceAs(TRACK_ENDPOINT_SOURCE);
            if (source != null) source.setGeoJson(json);
            lastEndpointJson = json;
        }
        setVisible(style, TRACK_ENDPOINT_LAYER, visible);
        setVisible(style, TRACK_ENDPOINT_LABEL, visible);
    }


    private void installImportsButton() {
        if (root == null) return;
        View existing = root.findViewWithTag(IMPORTS_BUTTON_TAG);
        if (existing instanceof Button) {
            importsButton = (Button) existing;
            return;
        }
        importsButton = new Button(activity);
        importsButton.setTag(IMPORTS_BUTTON_TAG);
        importsButton.setText("Imports");
        importsButton.setAllCaps(false);
        importsButton.setTextSize(11.5f);
        importsButton.setMinWidth(dp(88));
        importsButton.setMinimumWidth(dp(88));
        importsButton.setMinHeight(dp(48));
        importsButton.setMinimumHeight(dp(48));
        importsButton.setContentDescription("Manage imported GPX, KML and GeoJSON data");
        importsButton.setOnClickListener(v -> IntentFactory.openImports(activity));
        importsButton.setVisibility(View.GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(96), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        params.setMargins(0, 0, dp(108), dp(112));
        root.addView(importsButton, params);

        View field = root.findViewWithTag("rockmap-field-entry");
        if (field != null) {
            field.post(() -> {
                ViewGroup.LayoutParams raw = field.getLayoutParams();
                ViewGroup.LayoutParams importRaw = importsButton.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams && importRaw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams fieldParams = (FrameLayout.LayoutParams) raw;
                    FrameLayout.LayoutParams importParams = (FrameLayout.LayoutParams) importRaw;
                    importParams.bottomMargin = fieldParams.bottomMargin;
                    importParams.rightMargin = fieldParams.rightMargin + dp(100);
                    importsButton.setLayoutParams(importParams);
                }
            });
        }
    }

    private void updateImportsButton() {
        if (importsButton == null || root == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastImportsCheck > 2000L) {
            cachedHasImports = !db.listImportBatches().isEmpty();
            lastImportsCheck = now;
        }
        importsButton.setVisibility(cachedHasImports ? View.VISIBLE : View.GONE);
        if (!cachedHasImports) return;

        View field = root.findViewWithTag("rockmap-field-entry");
        if (field != null) {
            ViewGroup.LayoutParams raw = field.getLayoutParams();
            ViewGroup.LayoutParams importRaw = importsButton.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams && importRaw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams fieldParams = (FrameLayout.LayoutParams) raw;
                FrameLayout.LayoutParams importParams = (FrameLayout.LayoutParams) importRaw;
                importParams.bottomMargin = fieldParams.bottomMargin;
                importParams.rightMargin = fieldParams.rightMargin + dp(100);
                importsButton.setLayoutParams(importParams);
            }
        }
        importsButton.bringToFront();
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

    private void renderContextHud(FieldDatabase.Track track, List<GeoMath.Point> points) {
        if (contextHud == null) return;

        // Active recording already has the primary FieldMapController HUD.
        if (FieldDatabase.TRACK_RECORDING.equals(track.status)
                || FieldDatabase.TRACK_PAUSED.equals(track.status)) {
            hideContextHud();
            return;
        }

        contextHud.removeAllViews();
        TextView title = text("TRACK MAP — " + track.name, 13.5f, true);
        contextHud.addView(title);
        contextHud.addView(text(points.size() + " points · "
                + GeoMath.distanceLabel(GeoMath.pathDistanceMeters(points))
                + "\nThe basemap is the track preview. START and END are labeled at their actual positions.",
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
            cachedTrackId = Long.MIN_VALUE;
            applyPolish();
        });
        firstRow.addView(backtrack, weight());

        Button hide = button("Hide");
        hide.setOnClickListener(v -> {
            if (cachedTrack == null) return;
            FieldMapState.hideTrack(activity, cachedTrack.id);
            FieldMapState.clearSelectedTrackDetail(activity);
            cachedTrackId = Long.MIN_VALUE;
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
                        cachedTrackId = Long.MIN_VALUE;
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
            IntentFactory.openTracks(activity);
        });
        secondRow.addView(tracks, weight());

        Button close = button("Close map view");
        close.setOnClickListener(v -> {
            FieldMapState.clearSelectedTrackDetail(activity);
            cachedTrackId = Long.MIN_VALUE;
            applyPolish();
        });
        secondRow.addView(close, weight());

        contextHud.addView(secondRow);
        positionContextHud();
        contextHud.setVisibility(View.VISIBLE);
        contextHud.bringToFront();
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

    private static JSONObject pointFeature(GeoMath.Point point, String label) throws JSONException {
        JSONObject props = new JSONObject().put("name", label);
        JSONArray coordinates = new JSONArray().put(point.lon).put(point.lat);
        JSONObject geometry = new JSONObject().put("type", "Point").put("coordinates", coordinates);
        return new JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry);
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

    /**
     * Keeps Intent construction isolated so this controller remains focused on map rendering.
     */
    private static final class IntentFactory {
        static void openTracks(Activity activity) {
            android.content.Intent intent = new android.content.Intent(activity, FieldActivity.class);
            intent.putExtra(FieldActivity.EXTRA_SCREEN, "tracks");
            activity.startActivity(intent);
        }

        static void openImports(Activity activity) {
            android.content.Intent intent = new android.content.Intent(activity, FieldActivity.class);
            intent.putExtra(FieldActivity.EXTRA_SCREEN, "imports");
            activity.startActivity(intent);
        }
    }
}
