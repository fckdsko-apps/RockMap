package com.rockmap.app.map;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.field.FieldDatabase;
import com.rockmap.app.field.FieldMapState;
import com.rockmap.app.field.GeoMath;
import com.rockmap.app.field.ProspectingAreaVisibility;

import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Compact, predictable top-right controls for temporary map contexts.
 *
 * Each logical context gets one row and one close action. Rows never float over map geometry, so
 * close controls cannot overlap or require hunting. The row color matches the mapped context, and
 * tapping the row label focuses that context. Prospecting Areas keep an explicit hidden state until
 * the user chooses Show on Map again.
 */
public final class MapContextCloseController {
    private static final double MIN_CONTEXT_ZOOM = 7.0d;
    private static final String FIELD_AREA_FILL = "rockmap-field-area-fill";
    private static final String FIELD_AREA_LINE = "rockmap-field-area-line";
    private static final int PROSPECTING_COLOR = Color.rgb(190, 105, 10);

    private static final WeakHashMap<MapView, WeakReference<MapContextCloseController>> INSTANCES =
            new WeakHashMap<>();

    public static synchronized MapContextCloseController forMap(MapView mapView) {
        WeakReference<MapContextCloseController> reference = INSTANCES.get(mapView);
        MapContextCloseController existing = reference == null ? null : reference.get();
        if (existing != null) return existing;
        MapContextCloseController created = new MapContextCloseController(mapView);
        INSTANCES.put(mapView, new WeakReference<>(created));
        return created;
    }

    /** Refresh every active RockMap map owned by this Activity after shared visibility state changes. */
    public static synchronized void refreshFor(Activity activity) {
        if (activity == null) return;
        for (WeakReference<MapContextCloseController> reference : INSTANCES.values()) {
            MapContextCloseController controller = reference == null ? null : reference.get();
            if (controller != null && controller.activity == activity) controller.refresh();
        }
    }

    private final MapView mapView;
    private final Activity activity;
    private final FieldDatabase fieldDb;
    private FrameLayout root;
    private LinearLayout stack;
    private MapLibreMap map;
    private Target geology;
    private Target mineral;
    private Target historic;
    private long selectedProspectingAreaId = -1L;
    private boolean listenersInstalled;

    private MapContextCloseController(MapView mapView) {
        this.mapView = mapView;
        this.activity = mapView != null && mapView.getContext() instanceof Activity
                ? (Activity) mapView.getContext() : null;
        this.fieldDb = activity == null ? null : FieldDatabase.get(activity);
        initialize();
    }

    private void initialize() {
        if (mapView == null || activity == null) return;
        mapView.post(this::ensureRootAndStack);
        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            if (!listenersInstalled) {
                map.addOnCameraMoveStartedListener(reason -> hideStack());
                map.addOnCameraIdleListener(this::refreshNow);
                listenersInstalled = true;
            }
            refreshNow();
        });
    }

    public void setGeologyTarget(double south, double west, double north, double east,
                                 String label, int color, Runnable closeAction) {
        geology = Target.of(cleanLabel(label, "Geology"), color,
                Bounds.validated(south, west, north, east), closeAction);
        refresh();
    }

    public void setGeologyTarget(double south, double west, double north, double east,
                                 Runnable closeAction) {
        setGeologyTarget(south, west, north, east, "Geology", Color.rgb(235, 115, 20), closeAction);
    }

    public void clearGeologyTarget() {
        geology = null;
        refresh();
    }

    public void setMineralTarget(double south, double west, double north, double east,
                                 String label, int color, Runnable closeAction) {
        mineral = Target.of(cleanLabel(label, "Mineral Evidence"), color,
                Bounds.validated(south, west, north, east), closeAction);
        refresh();
    }

    public void setMineralTarget(double south, double west, double north, double east,
                                 Runnable closeAction) {
        setMineralTarget(south, west, north, east, "Mineral Evidence",
                Color.rgb(235, 115, 20), closeAction);
    }

    public void clearMineralTarget() {
        mineral = null;
        refresh();
    }

    public void setHistoricTarget(String label, int color, Bounds bounds, Runnable closeAction) {
        historic = Target.of(cleanLabel(label, "Historic Mines"), color, bounds, closeAction);
        refresh();
    }

    public void setHistoricTarget(String label, int color,
                                  double south, double west, double north, double east,
                                  Runnable closeAction) {
        setHistoricTarget(label, color, Bounds.validated(south, west, north, east), closeAction);
    }

    public void setHistoricTarget(String label, int color, Runnable closeAction) {
        setHistoricTarget(label, color, (Bounds) null, closeAction);
    }

    public void clearHistoricTarget() {
        historic = null;
        refresh();
    }

    public void refresh() {
        if (mapView == null) return;
        mapView.post(this::refreshNow);
    }

    private void refreshNow() {
        ensureRootAndStack();
        applyProspectingFilter();
        rebuildStack();
    }

    private void ensureRootAndStack() {
        if (root == null) {
            View parent = mapView;
            while (parent != null && parent.getParent() instanceof View) {
                parent = (View) parent.getParent();
                if (parent instanceof FrameLayout) {
                    root = (FrameLayout) parent;
                    break;
                }
            }
        }
        if (root == null || stack != null) return;

        stack = new LinearLayout(activity);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.END);
        stack.setPadding(0, 0, 0, 0);
        stack.setVisibility(View.GONE);
        stack.setContentDescription("Active map views");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.setMargins(dp(8), dp(76), dp(8), 0);
        root.addView(stack, params);
    }

    private void rebuildStack() {
        if (stack == null || map == null) return;
        stack.removeAllViews();
        double zoom = map.getCameraPosition().zoom;
        if (zoom < MIN_CONTEXT_ZOOM) {
            stack.setVisibility(View.GONE);
            return;
        }

        if (geology != null) addTargetRow(geology);
        if (mineral != null) addTargetRow(mineral);
        if (historic != null) addTargetRow(historic);

        List<AreaTarget> prospecting = visibleProspectingTargets();
        if (!prospecting.isEmpty()) {
            AreaTarget selected = selectProspectingTarget(prospecting);
            if (selected != null) addProspectingRow(selected, prospecting);
        } else {
            selectedProspectingAreaId = -1L;
        }

        stack.setVisibility(stack.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        if (stack.getVisibility() == View.VISIBLE) stack.bringToFront();
    }

    private void addTargetRow(Target target) {
        if (target == null) return;
        LinearLayout row = createRow(target.color);
        TextView label = createLabel(target.label, target.color);
        label.setOnClickListener(v -> {
            if (target.bounds != null) focus(target.bounds);
        });
        if (target.bounds == null) label.setClickable(false);
        row.addView(label, new LinearLayout.LayoutParams(dp(142), dp(34)));
        TextView close = createClose(target.color, "Close " + target.label, () -> {
            Runnable action = target.closeAction;
            if (action != null) action.run();
            if (target == geology) geology = null;
            if (target == mineral) mineral = null;
            if (target == historic) historic = null;
            refresh();
        });
        row.addView(close, new LinearLayout.LayoutParams(dp(36), dp(34)));
        stack.addView(row, rowParams());
    }

    private void addProspectingRow(AreaTarget selected, List<AreaTarget> candidates) {
        LinearLayout row = createRow(PROSPECTING_COLOR);
        String name = selected.name;
        if (candidates.size() > 1) name += " (" + candidates.size() + ")";
        TextView label = createLabel("Prospecting Area — " + name, PROSPECTING_COLOR);
        label.setContentDescription(candidates.size() > 1
                ? "Choose one of " + candidates.size() + " visible Prospecting Areas"
                : "Focus Prospecting Area " + selected.name);
        label.setOnClickListener(v -> {
            if (candidates.size() > 1) showProspectingChooser(candidates);
            else focus(selected.bounds);
        });
        row.addView(label, new LinearLayout.LayoutParams(dp(142), dp(34)));
        TextView close = createClose(PROSPECTING_COLOR,
                "Hide Prospecting Area " + selected.name,
                () -> hideProspectingArea(selected));
        row.addView(close, new LinearLayout.LayoutParams(dp(36), dp(34)));
        stack.addView(row, rowParams());
    }

    private LinearLayout createRow(int color) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(238, 255, 255, 255));
        background.setStroke(dp(1), Color.argb(210, Color.red(color), Color.green(color), Color.blue(color)));
        background.setCornerRadius(dp(6));
        row.setBackground(background);
        row.setElevation(dp(4));
        return row;
    }

    private TextView createLabel(String text, int color) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(11.5f);
        label.setTextColor(Color.rgb(35, 35, 35));
        label.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(9), 0, dp(4), 0);
        label.setClickable(true);
        label.setFocusable(true);
        label.setContentDescription("Focus " + text);
        return label;
    }

    private TextView createClose(int color, String description, Runnable action) {
        TextView close = new TextView(activity);
        close.setText("×");
        close.setTextSize(22f);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(color);
        close.setContentDescription(description);
        close.setClickable(true);
        close.setFocusable(true);
        close.setPadding(0, 0, 0, dp(2));
        close.setOnClickListener(v -> action.run());
        return close;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        params.setMargins(0, 0, 0, dp(4));
        return params;
    }

    private void hideStack() {
        if (stack != null) stack.setVisibility(View.GONE);
    }

    private List<AreaTarget> visibleProspectingTargets() {
        ArrayList<AreaTarget> out = new ArrayList<>();
        if (fieldDb == null || map == null || !FieldMapState.areasVisible(activity)) return out;
        List<FieldDatabase.Area> areas = fieldDb.listAreas();
        if (areas == null || areas.isEmpty()) return out;
        Set<Long> hidden = ProspectingAreaVisibility.hiddenIds(activity);
        LatLngBounds visible = map.getProjection().getVisibleRegion().latLngBounds;
        double south = visible.getLatSouth();
        double west = visible.getLonWest();
        double north = visible.getLatNorth();
        double east = visible.getLonEast();
        for (FieldDatabase.Area area : areas) {
            if (area == null || area.points == null || area.points.size() < 3 || hidden.contains(area.id)) continue;
            Bounds bounds = boundsOf(area.points);
            if (bounds == null || !bounds.intersects(south, west, north, east)) continue;
            out.add(new AreaTarget(area.id, cleanLabel(area.name, "Prospecting Area"), bounds));
        }
        return out;
    }

    private AreaTarget selectProspectingTarget(List<AreaTarget> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        for (AreaTarget candidate : candidates) {
            if (candidate.id == selectedProspectingAreaId) return candidate;
        }
        LatLng center = map == null ? null : map.getCameraPosition().target;
        AreaTarget best = candidates.get(0);
        double bestScore = Double.POSITIVE_INFINITY;
        if (center != null) {
            for (AreaTarget candidate : candidates) {
                double lat = (candidate.bounds.south + candidate.bounds.north) / 2d;
                double lon = (candidate.bounds.west + candidate.bounds.east) / 2d;
                double dy = lat - center.getLatitude();
                double dx = (lon - center.getLongitude()) * Math.cos(Math.toRadians(lat));
                double score = dx * dx + dy * dy;
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        selectedProspectingAreaId = best.id;
        return best;
    }

    private void showProspectingChooser(List<AreaTarget> candidates) {
        if (activity == null || candidates == null || candidates.isEmpty()) return;
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) labels[i] = candidates.get(i).name;
        new AlertDialog.Builder(activity)
                .setTitle("Choose Prospecting Area")
                .setItems(labels, (dialog, which) -> {
                    if (which < 0 || which >= candidates.size()) return;
                    AreaTarget selected = candidates.get(which);
                    selectedProspectingAreaId = selected.id;
                    focus(selected.bounds);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void hideProspectingArea(AreaTarget area) {
        if (area == null || activity == null) return;
        ProspectingAreaVisibility.hide(activity, area.id);
        selectedProspectingAreaId = -1L;
        applyProspectingFilter();
        refresh();
        Toast.makeText(activity,
                "Prospecting Area hidden. Use Field → Prospecting Areas → Show on Map to display it again.",
                Toast.LENGTH_SHORT).show();
    }

    private void applyProspectingFilter() {
        if (map == null || activity == null) return;
        Set<Long> hidden = ProspectingAreaVisibility.hiddenIds(activity);
        map.getStyle(style -> {
            Layer fillLayer = style.getLayer(FIELD_AREA_FILL);
            Layer lineLayer = style.getLayer(FIELD_AREA_LINE);
            Expression filter = prospectingFilter(hidden);
            if (fillLayer instanceof FillLayer) ((FillLayer) fillLayer).setFilter(filter);
            if (lineLayer instanceof LineLayer) ((LineLayer) lineLayer).setFilter(filter);
        });
    }

    private Expression prospectingFilter(Set<Long> hidden) {
        if (hidden == null || hidden.isEmpty()) return Expression.literal(true);
        Expression[] filters = new Expression[hidden.size()];
        int i = 0;
        for (Long id : hidden) {
            filters[i++] = Expression.neq(Expression.get("id"), id.doubleValue());
        }
        return filters.length == 1 ? filters[0] : Expression.all(filters);
    }

    private void focus(Bounds bounds) {
        if (map == null || bounds == null) return;
        try {
            LatLngBounds cameraBounds = new LatLngBounds.Builder()
                    .include(new LatLng(bounds.south, bounds.west))
                    .include(new LatLng(bounds.north, bounds.east))
                    .build();
            int horizontal = dp(48);
            int top = dp(82);
            int bottom = dp(152);
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(
                    cameraBounds, horizontal, top, horizontal, bottom));
        } catch (RuntimeException ignored) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng((bounds.south + bounds.north) / 2d,
                            (bounds.west + bounds.east) / 2d), 12d));
        }
    }

    private static Bounds boundsOf(List<GeoMath.Point> points) {
        double south = Double.POSITIVE_INFINITY;
        double west = Double.POSITIVE_INFINITY;
        double north = Double.NEGATIVE_INFINITY;
        double east = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (GeoMath.Point point : points) {
            if (point == null || !Double.isFinite(point.lat) || !Double.isFinite(point.lon)) continue;
            any = true;
            south = Math.min(south, point.lat);
            west = Math.min(west, point.lon);
            north = Math.max(north, point.lat);
            east = Math.max(east, point.lon);
        }
        return any ? Bounds.validated(south, west, north, east) : null;
    }

    private static String cleanLabel(String label, String fallback) {
        if (label == null || label.trim().isEmpty()) return fallback;
        String value = label.trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    public static final class Bounds {
        public final double south;
        public final double west;
        public final double north;
        public final double east;

        private Bounds(double south, double west, double north, double east) {
            this.south = south;
            this.west = west;
            this.north = north;
            this.east = east;
        }

        public static Bounds validated(double south, double west, double north, double east) {
            if (!Double.isFinite(south) || !Double.isFinite(west)
                    || !Double.isFinite(north) || !Double.isFinite(east)) return null;
            double s = Math.min(south, north);
            double n = Math.max(south, north);
            double w = Math.min(west, east);
            double e = Math.max(west, east);
            if (s < -90d || n > 90d || w < -180d || e > 180d) return null;
            return new Bounds(s, w, n, e);
        }

        boolean intersects(double south, double west, double north, double east) {
            return this.south <= north && this.north >= south
                    && this.west <= east && this.east >= west;
        }
    }

    private static final class Target {
        final String label;
        final int color;
        final Bounds bounds;
        final Runnable closeAction;

        private Target(String label, int color, Bounds bounds, Runnable closeAction) {
            this.label = label;
            this.color = color;
            this.bounds = bounds;
            this.closeAction = closeAction;
        }

        static Target of(String label, int color, Bounds bounds, Runnable closeAction) {
            return new Target(label, color, bounds, closeAction);
        }
    }

    private static final class AreaTarget {
        final long id;
        final String name;
        final Bounds bounds;

        AreaTarget(long id, String name, Bounds bounds) {
            this.id = id;
            this.name = name;
            this.bounds = bounds;
        }
    }
}
