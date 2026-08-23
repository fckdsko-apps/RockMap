package com.rockmap.app.map;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.field.FieldActivity;
import com.rockmap.app.field.FieldDatabase;
import com.rockmap.app.field.FieldMapController;
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
 * Contextual close controls attached to the map geometry they control.
 *
 * One active object gets one compact × at the top-right edge of the actual geometry (with a full
 * 48dp hit target). When several related map contexts are active, the individual × controls are
 * replaced by one color-coded layer menu placed near that same geometry so controls never overlap.
 */
public final class MapContextCloseController {
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
    private TextView singleClose;
    private LinearLayout menu;
    private MapLibreMap map;
    private Target geology;
    private Target mineral;
    private Target historic;
    private Runnable geologyOpenAction;
    private Runnable mineralOpenAction;
    private Runnable historicOpenAction;
    private Runnable contextStateChangedAction;
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
        mapView.post(this::ensureViews);
        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            if (!listenersInstalled) {
                map.addOnCameraMoveStartedListener(reason -> hideControls());
                map.addOnCameraIdleListener(this::refreshNow);
                listenersInstalled = true;
            }
            refreshNow();
        });
    }

    public void setGeologyTarget(double south, double west, double north, double east,
                                 String label, int color, Runnable closeAction) {
        Bounds bounds = Bounds.validated(south, west, north, east);
        LatLng anchor = bounds == null ? null : new LatLng(bounds.north, bounds.east);
        geology = Target.of(cleanLabel(label, "Geology"), color, bounds, anchor, closeAction);
        refresh();
    }

    public void setGeologyTarget(double south, double west, double north, double east,
                                 double anchorLat, double anchorLon,
                                 String label, int color, Runnable closeAction) {
        geology = Target.of(cleanLabel(label, "Geology"), color,
                Bounds.validated(south, west, north, east), validLatLng(anchorLat, anchorLon), closeAction);
        refresh();
    }

    public void setGeologyTarget(double south, double west, double north, double east,
                                 Runnable closeAction) {
        setGeologyTarget(south, west, north, east, "Geology", Color.rgb(235, 115, 20), closeAction);
    }

    public void setGeologyOpenAction(Runnable openAction) {
        geologyOpenAction = openAction;
        refresh();
    }

    public void clearGeologyTarget() {
        geology = null;
        refresh();
    }

    public void setMineralTarget(double south, double west, double north, double east,
                                 String label, int color, Runnable closeAction) {
        Bounds bounds = Bounds.validated(south, west, north, east);
        LatLng anchor = bounds == null ? null : new LatLng(bounds.north, bounds.east);
        mineral = Target.of(cleanLabel(label, "Mineral Evidence"), color, bounds, anchor, closeAction);
        refresh();
    }

    public void setMineralTarget(double south, double west, double north, double east,
                                 Runnable closeAction) {
        setMineralTarget(south, west, north, east, "Mineral Evidence",
                Color.rgb(235, 115, 20), closeAction);
    }

    public void setMineralOpenAction(Runnable openAction) {
        mineralOpenAction = openAction;
        refresh();
    }

    public void clearMineralTarget() {
        mineral = null;
        refresh();
    }

    public void setHistoricTarget(String label, int color, Bounds bounds, Runnable closeAction) {
        LatLng anchor = bounds == null ? null : new LatLng(bounds.north, bounds.east);
        historic = Target.of(cleanLabel(label, "Historic Mines"), color, bounds, anchor, closeAction);
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

    public void setHistoricOpenAction(Runnable openAction) {
        historicOpenAction = openAction;
        refresh();
    }

    public void setOnContextStateChanged(Runnable action) {
        contextStateChangedAction = action;
    }

    public void clearHistoricTarget() {
        historic = null;
        refresh();
    }

    public void refresh() {
        if (mapView != null) mapView.post(this::refreshNow);
    }

    private void refreshNow() {
        ensureViews();
        applyProspectingFilter();
        rebuildControls();
        FieldMapController.ensurePersistentEntry(activity);
    }

    private void ensureViews() {
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
        if (root == null) return;
        if (singleClose == null) {
            singleClose = new TextView(activity);
            singleClose.setText("×");
            singleClose.setTextSize(22f);
            singleClose.setGravity(Gravity.CENTER);
            singleClose.setClickable(true);
            singleClose.setFocusable(true);
            singleClose.setElevation(dp(6));
            singleClose.setVisibility(View.GONE);
            root.addView(singleClose, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.START));
        }
        if (menu == null) {
            menu = new LinearLayout(activity);
            menu.setOrientation(LinearLayout.VERTICAL);
            menu.setPadding(dp(3), dp(3), dp(3), dp(3));
            menu.setElevation(dp(7));
            menu.setVisibility(View.GONE);
            menu.setContentDescription("Active map layers for this area");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.argb(238, 255, 255, 255));
            bg.setStroke(dp(1), Color.rgb(165, 165, 165));
            bg.setCornerRadius(dp(8));
            menu.setBackground(bg);
            root.addView(menu, new FrameLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START));
        }
    }

    private void rebuildControls() {
        if (root == null || map == null || singleClose == null || menu == null) return;
        ArrayList<ContextItem> items = new ArrayList<>();
        if (geology != null) items.add(ContextItem.fromTarget(geology, geologyOpenAction, () -> closeTarget(geology)));
        if (mineral != null) items.add(ContextItem.fromTarget(mineral, mineralOpenAction, () -> closeTarget(mineral)));
        if (historic != null) items.add(ContextItem.fromTarget(historic, historicOpenAction, () -> closeTarget(historic)));
        for (AreaTarget area : visibleProspectingTargets()) {
            items.add(new ContextItem("Prospecting Area — " + area.name, PROSPECTING_COLOR,
                    area.bounds, area.anchor, () -> openProspectingArea(area), () -> hideProspectingArea(area)));
        }

        if (items.isEmpty()) {
            hideControls();
            return;
        }
        if (items.size() == 1) {
            showSingle(items.get(0));
        } else {
            showMenu(items);
        }
    }

    private void closeTarget(Target target) {
        if (target == null) return;
        Runnable action = target.closeAction;
        if (action != null) action.run();
        if (target == geology) geology = null;
        if (target == mineral) mineral = null;
        if (target == historic) historic = null;
        refresh();
        if (contextStateChangedAction != null) contextStateChangedAction.run();
    }

    private void showSingle(ContextItem item) {
        menu.setVisibility(View.GONE);
        singleClose.setTextColor(item.color);
        singleClose.setContentDescription("Close " + item.label);
        singleClose.setOnClickListener(v -> item.close.run());
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(238, 255, 255, 255));
        bg.setStroke(dp(2), item.color);
        singleClose.setBackground(new InsetDrawable(bg, dp(9)));
        PointF anchor = project(item.anchor != null ? item.anchor : northEast(item.bounds));
        if (anchor == null) {
            singleClose.setVisibility(View.GONE);
            return;
        }
        position(singleClose, dp(48), dp(48), Math.round(anchor.x - dp(24)), Math.round(anchor.y - dp(24)));
        singleClose.setVisibility(View.VISIBLE);
        singleClose.bringToFront();
    }

    private void showMenu(List<ContextItem> items) {
        singleClose.setVisibility(View.GONE);
        menu.removeAllViews();
        for (ContextItem item : items) addMenuRow(item);
        ContextItem anchorItem = items.get(0);
        PointF anchor = project(anchorItem.anchor != null ? anchorItem.anchor : northEast(anchorItem.bounds));
        if (anchor == null) {
            menu.setVisibility(View.GONE);
            return;
        }
        int width = dp(180);
        int estimatedHeight = dp(6 + 48 * items.size());
        // Prefer just outside the north-east edge so the controls remain associated with the
        // geometry without covering the center of the area being inspected. Clamping handles
        // screen edges and falls back beside the visible geometry.
        int left = Math.round(anchor.x + dp(7));
        int top = Math.round(anchor.y - estimatedHeight - dp(7));
        if (left + width > Math.max(root.getWidth(), mapView.getWidth()) - dp(6)) {
            left = Math.round(anchor.x - width - dp(7));
        }
        position(menu, width, estimatedHeight, left, top);
        menu.setVisibility(View.VISIBLE);
        menu.bringToFront();
    }

    private void addMenuRow(ContextItem item) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("Open " + item.label + " information, results, and options");
        row.setOnClickListener(v -> {
            if (item.open != null) item.open.run();
            else focus(item.bounds);
        });

        View swatch = new View(activity);
        GradientDrawable swatchBg = new GradientDrawable();
        swatchBg.setShape(GradientDrawable.OVAL);
        swatchBg.setColor(item.color);
        swatch.setBackground(swatchBg);
        LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        swatchParams.setMargins(dp(4), 0, dp(6), 0);
        row.addView(swatch, swatchParams);

        TextView label = new TextView(activity);
        label.setText(item.label);
        label.setTextSize(10.5f);
        label.setTextColor(Color.rgb(35, 35, 35));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView close = new TextView(activity);
        close.setText("×");
        close.setTextSize(22f);
        close.setTextColor(item.color);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close " + item.label);
        close.setOnClickListener(v -> item.close.run());
        row.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        menu.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private void position(View view, int width, int height, int left, int top) {
        if (root == null || view == null) return;
        int rootWidth = Math.max(root.getWidth(), mapView.getWidth());
        int rootHeight = Math.max(root.getHeight(), mapView.getHeight());
        int[] mapLoc = new int[2];
        int[] rootLoc = new int[2];
        mapView.getLocationOnScreen(mapLoc);
        root.getLocationOnScreen(rootLoc);
        left += mapLoc[0] - rootLoc[0];
        top += mapLoc[1] - rootLoc[1];
        int margin = dp(6);
        int bottomGuard = dp(118);
        left = clamp(left, margin, Math.max(margin, rootWidth - width - margin));
        top = clamp(top, margin, Math.max(margin, rootHeight - height - bottomGuard));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = left;
        params.topMargin = top;
        if (width > 0) params.width = width;
        view.setLayoutParams(params);
    }

    private PointF project(LatLng point) {
        if (map == null || point == null) return null;
        try {
            return map.getProjection().toScreenLocation(point);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void hideControls() {
        if (singleClose != null) singleClose.setVisibility(View.GONE);
        if (menu != null) menu.setVisibility(View.GONE);
    }

    private List<AreaTarget> visibleProspectingTargets() {
        ArrayList<AreaTarget> out = new ArrayList<>();
        if (fieldDb == null || map == null || !FieldMapState.areasVisible(activity)) return out;
        Set<Long> shown = ProspectingAreaVisibility.shownIds(activity);
        if (shown.isEmpty()) return out;
        LatLngBounds visible = map.getProjection().getVisibleRegion().latLngBounds;
        double south = visible.getLatSouth();
        double west = visible.getLonWest();
        double north = visible.getLatNorth();
        double east = visible.getLonEast();
        for (FieldDatabase.Area area : fieldDb.listAreas()) {
            if (area == null || !shown.contains(area.id) || area.points == null || area.points.size() < 3) continue;
            Bounds bounds = boundsOf(area.points);
            if (bounds == null || !bounds.intersects(south, west, north, east)) continue;
            out.add(new AreaTarget(area.id, cleanLabel(area.name, "Prospecting Area"), bounds,
                    topRightAnchor(area.points, bounds)));
        }
        return out;
    }

    private void openProspectingArea(AreaTarget area) {
        if (area == null || activity == null) return;
        Intent intent = new Intent(activity, FieldActivity.class);
        intent.putExtra(FieldActivity.EXTRA_SCREEN, "areas");
        intent.putExtra(FieldActivity.EXTRA_AREA_ID, area.id);
        activity.startActivity(intent);
    }

    private void hideProspectingArea(AreaTarget area) {
        if (area == null || activity == null) return;
        ProspectingAreaVisibility.hide(activity, area.id);
        applyProspectingFilter();
        refresh();
        if (contextStateChangedAction != null) contextStateChangedAction.run();
        Toast.makeText(activity,
                "Prospecting Area hidden. Use Field → Prospecting Areas → Show on Map to display it again.",
                Toast.LENGTH_SHORT).show();
    }

    private void applyProspectingFilter() {
        if (map == null || activity == null) return;
        Set<Long> shown = ProspectingAreaVisibility.shownIds(activity);
        map.getStyle(style -> {
            Layer fillLayer = style.getLayer(FIELD_AREA_FILL);
            Layer lineLayer = style.getLayer(FIELD_AREA_LINE);
            Expression filter = prospectingFilter(shown);
            if (fillLayer instanceof FillLayer) ((FillLayer) fillLayer).setFilter(filter);
            if (lineLayer instanceof LineLayer) ((LineLayer) lineLayer).setFilter(filter);
        });
    }

    private Expression prospectingFilter(Set<Long> shown) {
        if (shown == null || shown.isEmpty()) return Expression.literal(false);
        Expression[] filters = new Expression[shown.size()];
        int i = 0;
        for (Long id : shown) filters[i++] = Expression.eq(Expression.get("id"), id.doubleValue());
        return filters.length == 1 ? filters[0] : Expression.any(filters);
    }

    private void focus(Bounds bounds) {
        if (map == null || bounds == null) return;
        try {
            LatLngBounds cameraBounds = new LatLngBounds.Builder()
                    .include(new LatLng(bounds.south, bounds.west))
                    .include(new LatLng(bounds.north, bounds.east))
                    .build();
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(
                    cameraBounds, dp(48), dp(82), dp(48), dp(152)));
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

    /** Select a real polygon vertex nearest the visual north-east corner, not the bounds corner. */
    private static LatLng topRightAnchor(List<GeoMath.Point> points, Bounds bounds) {
        if (points == null || points.isEmpty() || bounds == null) return northEast(bounds);
        double latSpan = Math.max(1e-9d, bounds.north - bounds.south);
        double lonSpan = Math.max(1e-9d, bounds.east - bounds.west);
        GeoMath.Point best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (GeoMath.Point p : points) {
            if (p == null) continue;
            double northness = (p.lat - bounds.south) / latSpan;
            double eastness = (p.lon - bounds.west) / lonSpan;
            double score = northness + eastness;
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best == null ? northEast(bounds) : new LatLng(best.lat, best.lon);
    }

    private static LatLng northEast(Bounds bounds) {
        return bounds == null ? null : new LatLng(bounds.north, bounds.east);
    }

    private static LatLng validLatLng(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90d && lat <= 90d && lon >= -180d && lon <= 180d
                ? new LatLng(lat, lon) : null;
    }

    private static String cleanLabel(String label, String fallback) {
        if (label == null || label.trim().isEmpty()) return fallback;
        String value = label.trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
        final LatLng anchor;
        final Runnable closeAction;

        private Target(String label, int color, Bounds bounds, LatLng anchor, Runnable closeAction) {
            this.label = label;
            this.color = color;
            this.bounds = bounds;
            this.anchor = anchor;
            this.closeAction = closeAction;
        }

        static Target of(String label, int color, Bounds bounds, LatLng anchor, Runnable closeAction) {
            return new Target(label, color, bounds, anchor, closeAction);
        }
    }

    private static final class AreaTarget {
        final long id;
        final String name;
        final Bounds bounds;
        final LatLng anchor;

        AreaTarget(long id, String name, Bounds bounds, LatLng anchor) {
            this.id = id;
            this.name = name;
            this.bounds = bounds;
            this.anchor = anchor;
        }
    }

    private static final class ContextItem {
        final String label;
        final int color;
        final Bounds bounds;
        final LatLng anchor;
        final Runnable open;
        final Runnable close;

        ContextItem(String label, int color, Bounds bounds, LatLng anchor, Runnable open, Runnable close) {
            this.label = label;
            this.color = color;
            this.bounds = bounds;
            this.anchor = anchor;
            this.open = open;
            this.close = close;
        }

        static ContextItem fromTarget(Target target, Runnable open, Runnable close) {
            return new ContextItem(target.label, target.color, target.bounds, target.anchor, open, close);
        }
    }
}
