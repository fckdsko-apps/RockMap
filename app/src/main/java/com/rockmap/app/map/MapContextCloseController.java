package com.rockmap.app.map;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.field.FieldDatabase;
import com.rockmap.app.field.FieldMapState;
import com.rockmap.app.field.GeoMath;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;

/**
 * Tiny map-attached close controls for temporary spatial context. The controls are intentionally
 * hidden at broad zoom levels and do not occupy a permanent toolbar slot.
 */
public final class MapContextCloseController {
    private static final double ANALYSIS_MIN_ZOOM = 10.5d;
    private static final double PROSPECTING_MIN_ZOOM = 11.5d;
    private static final String FIELD_AREA_FILL = "rockmap-field-area-fill";
    private static final String FIELD_AREA_LINE = "rockmap-field-area-line";

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

    private final MapView mapView;
    private final Activity activity;
    private final FieldDatabase fieldDb;
    private FrameLayout root;
    private MapLibreMap map;
    private TextView geologyClose;
    private TextView mineralClose;
    private TextView prospectingClose;
    private Bounds geologyBounds;
    private Bounds mineralBounds;
    private Runnable geologyAction;
    private Runnable mineralAction;
    private final Set<Long> hiddenProspectingAreaIds = new HashSet<>();
    private long prospectingAreaId = -1L;
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
        mapView.post(this::ensureRootAndButtons);
        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            if (!listenersInstalled) {
                map.addOnCameraMoveStartedListener(reason -> {
                    hideAll();
                    if (reason != MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
                            && !hiddenProspectingAreaIds.isEmpty()) {
                        hiddenProspectingAreaIds.clear();
                        applyProspectingFilter();
                    }
                });
                map.addOnCameraIdleListener(this::updatePositions);
                listenersInstalled = true;
            }
            updatePositions();
        });
    }

    public void setGeologyTarget(double south, double west, double north, double east, Runnable closeAction) {
        geologyBounds = Bounds.validated(south, west, north, east);
        geologyAction = closeAction;
        refresh();
    }

    public void clearGeologyTarget() {
        geologyBounds = null;
        geologyAction = null;
        refresh();
    }

    public void setMineralTarget(double south, double west, double north, double east, Runnable closeAction) {
        mineralBounds = Bounds.validated(south, west, north, east);
        mineralAction = closeAction;
        refresh();
    }

    public void clearMineralTarget() {
        mineralBounds = null;
        mineralAction = null;
        refresh();
    }

    public void refresh() {
        if (mapView == null) return;
        mapView.post(() -> {
            ensureRootAndButtons();
            updatePositions();
        });
    }

    private void ensureRootAndButtons() {
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
        if (geologyClose == null) {
            geologyClose = createClose("Close geology analysis", () -> {
                Runnable action = geologyAction;
                if (action != null) action.run();
                clearGeologyTarget();
            });
            root.addView(geologyClose, closeParams());
        }
        if (mineralClose == null) {
            mineralClose = createClose("Close mineral area analysis", () -> {
                Runnable action = mineralAction;
                if (action != null) action.run();
                clearMineralTarget();
            });
            root.addView(mineralClose, closeParams());
        }
        if (prospectingClose == null) {
            prospectingClose = createClose("Close this Prospecting Area from the map", this::hideProspectingArea);
            root.addView(prospectingClose, closeParams());
        }
    }

    private FrameLayout.LayoutParams closeParams() {
        return new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.START);
    }

    private TextView createClose(String description, Runnable action) {
        TextView close = new TextView(activity);
        close.setText("×");
        close.setTextSize(22f);
        close.setGravity(Gravity.CENTER);
        close.setTextColor(Color.rgb(45, 45, 45));
        close.setContentDescription(description);
        close.setClickable(true);
        close.setFocusable(true);
        close.setElevation(dp(4));
        close.setPadding(0, 0, 0, dp(2));
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(232, 255, 255, 255));
        background.setStroke(dp(1), Color.argb(180, 70, 70, 70));
        close.setBackground(background);
        close.setVisibility(View.GONE);
        close.setOnClickListener(v -> action.run());
        return close;
    }

    private void hideAll() {
        if (geologyClose != null) geologyClose.setVisibility(View.GONE);
        if (mineralClose != null) mineralClose.setVisibility(View.GONE);
        if (prospectingClose != null) prospectingClose.setVisibility(View.GONE);
    }

    private void updatePositions() {
        if (map == null || root == null) return;
        double zoom = map.getCameraPosition().zoom;
        position(geologyClose, geologyBounds, zoom >= ANALYSIS_MIN_ZOOM, Anchor.TOP_RIGHT);
        position(mineralClose, mineralBounds, zoom >= ANALYSIS_MIN_ZOOM, Anchor.BOTTOM_RIGHT);

        applyProspectingFilter();
        AreaTarget prospecting = zoom >= PROSPECTING_MIN_ZOOM ? relevantProspectingTarget() : null;
        prospectingAreaId = prospecting == null ? -1L : prospecting.id;
        position(prospectingClose, prospecting == null ? null : prospecting.bounds,
                prospecting != null && FieldMapState.areasVisible(activity), Anchor.TOP_LEFT);
        bringToFront();
    }

    private void position(TextView view, Bounds bounds, boolean allowed, Anchor anchor) {
        if (view == null) return;
        if (!allowed || bounds == null || map == null || root == null) {
            view.setVisibility(View.GONE);
            return;
        }

        PointF southWest = map.getProjection().toScreenLocation(new LatLng(bounds.south, bounds.west));
        PointF northEast = map.getProjection().toScreenLocation(new LatLng(bounds.north, bounds.east));
        double pixelWidth = Math.abs(northEast.x - southWest.x);
        double pixelHeight = Math.abs(northEast.y - southWest.y);
        if (Math.max(pixelWidth, pixelHeight) < dp(72)) {
            view.setVisibility(View.GONE);
            return;
        }

        double latSpan = Math.max(0d, bounds.north - bounds.south);
        double lonSpan = Math.max(0d, bounds.east - bounds.west);
        double lat;
        double lon;
        switch (anchor) {
            case TOP_LEFT:
                lat = bounds.north - latSpan * 0.18d;
                lon = bounds.west + lonSpan * 0.18d;
                break;
            case BOTTOM_RIGHT:
                lat = bounds.south + latSpan * 0.18d;
                lon = bounds.east - lonSpan * 0.18d;
                break;
            case TOP_RIGHT:
            default:
                lat = bounds.north - latSpan * 0.18d;
                lon = bounds.east - lonSpan * 0.18d;
                break;
        }

        if (latSpan < 1e-7d && lonSpan < 1e-7d) {
            lat = bounds.north;
            lon = bounds.east;
        }
        PointF point = map.getProjection().toScreenLocation(new LatLng(lat, lon));
        int size = dp(36);
        float x = mapView.getLeft() + point.x - size / 2f;
        float y = mapView.getTop() + point.y - size / 2f;

        int topGuard = dp(44);
        int bottomGuard = dp(112);
        if (!Float.isFinite(x) || !Float.isFinite(y)
                || x < -size || x > root.getWidth()
                || y < topGuard || y > root.getHeight() - bottomGuard) {
            view.setVisibility(View.GONE);
            return;
        }

        view.setX(Math.max(dp(4), Math.min(x, root.getWidth() - size - dp(4))));
        view.setY(Math.max(topGuard, Math.min(y, root.getHeight() - bottomGuard - size)));
        view.setVisibility(View.VISIBLE);
    }

    private AreaTarget relevantProspectingTarget() {
        if (fieldDb == null || map == null || !FieldMapState.areasVisible(activity)) return null;
        List<FieldDatabase.Area> areas = fieldDb.listAreas();
        if (areas == null || areas.isEmpty()) return null;

        LatLngBounds visible = map.getProjection().getVisibleRegion().latLngBounds;
        double south = visible.getLatSouth();
        double west = visible.getLonWest();
        double north = visible.getLatNorth();
        double east = visible.getLonEast();
        LatLng center = map.getCameraPosition().target;
        double bestDistance = Double.POSITIVE_INFINITY;
        AreaTarget best = null;

        for (FieldDatabase.Area area : areas) {
            if (area == null || area.points == null || area.points.size() < 3
                    || hiddenProspectingAreaIds.contains(area.id)) continue;
            Bounds bounds = boundsOf(area.points);
            if (bounds == null || !bounds.intersects(south, west, north, east)) continue;
            double centerLat = (bounds.south + bounds.north) / 2d;
            double centerLon = (bounds.west + bounds.east) / 2d;
            double dy = centerLat - center.getLatitude();
            double dx = (centerLon - center.getLongitude()) * Math.cos(Math.toRadians(centerLat));
            double score = dx * dx + dy * dy;
            if (score < bestDistance) {
                bestDistance = score;
                best = new AreaTarget(area.id, bounds);
            }
        }
        return best;
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

    private void hideProspectingArea() {
        if (activity == null || prospectingAreaId <= 0L) return;
        hiddenProspectingAreaIds.add(prospectingAreaId);
        prospectingAreaId = -1L;
        applyProspectingFilter();
        updatePositions();
        Toast.makeText(activity,
                "Prospecting Area closed from this map view. Reopening an area from Field shows it again.",
                Toast.LENGTH_SHORT).show();
    }

    private void applyProspectingFilter() {
        if (map == null) return;
        map.getStyle(style -> {
            Layer fillLayer = style.getLayer(FIELD_AREA_FILL);
            Layer lineLayer = style.getLayer(FIELD_AREA_LINE);
            Expression filter = prospectingFilter();
            if (fillLayer instanceof FillLayer) ((FillLayer) fillLayer).setFilter(filter);
            if (lineLayer instanceof LineLayer) ((LineLayer) lineLayer).setFilter(filter);
        });
    }

    private Expression prospectingFilter() {
        if (hiddenProspectingAreaIds.isEmpty()) return Expression.literal(true);
        Expression[] filters = new Expression[hiddenProspectingAreaIds.size()];
        int i = 0;
        for (Long id : hiddenProspectingAreaIds) {
            filters[i++] = Expression.neq(Expression.get("id"), id.doubleValue());
        }
        return filters.length == 1 ? filters[0] : Expression.all(filters);
    }

    private void bringToFront() {
        if (geologyClose != null && geologyClose.getVisibility() == View.VISIBLE) geologyClose.bringToFront();
        if (mineralClose != null && mineralClose.getVisibility() == View.VISIBLE) mineralClose.bringToFront();
        if (prospectingClose != null && prospectingClose.getVisibility() == View.VISIBLE) prospectingClose.bringToFront();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private enum Anchor { TOP_RIGHT, BOTTOM_RIGHT, TOP_LEFT }

    private static final class AreaTarget {
        final long id;
        final Bounds bounds;

        AreaTarget(long id, Bounds bounds) {
            this.id = id;
            this.bounds = bounds;
        }
    }

    private static final class Bounds {
        final double south;
        final double west;
        final double north;
        final double east;

        private Bounds(double south, double west, double north, double east) {
            this.south = south;
            this.west = west;
            this.north = north;
            this.east = east;
        }

        static Bounds validated(double south, double west, double north, double east) {
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
}
