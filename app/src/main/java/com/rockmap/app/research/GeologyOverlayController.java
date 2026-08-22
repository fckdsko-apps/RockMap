package com.rockmap.app.research;

import android.graphics.Color;
import android.graphics.PointF;

import com.rockmap.app.map.MapController;
import com.rockmap.app.map.MapContextCloseController;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;

import java.util.List;

import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.Property.VISIBLE;
import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.fillColor;
import static org.maplibre.android.style.layers.PropertyFactory.fillOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;

/** Temporary map overlay for the currently selected research result and its analyzed area. */
public final class GeologyOverlayController {
    public interface Listener {
        void onGeologyTapped(Feature feature, LatLng coordinate);
    }

    public static final String SOURCE_ID = "rockmap-geology-research-source";
    public static final String FILL_ID = "rockmap-geology-research-fill";
    public static final String LINE_ID = "rockmap-geology-research-outline";
    public static final String QUERY_SOURCE_ID = "rockmap-geology-query-source";
    public static final String QUERY_FILL_ID = "rockmap-geology-query-fill";
    public static final String QUERY_LINE_ID = "rockmap-geology-query-outline";
    public static final String QUERY_CENTER_ID = "rockmap-geology-query-center";

    public static final int FILL_COLOR = Color.rgb(127, 140, 115);
    public static final int OUTLINE_COLOR = Color.rgb(79, 94, 72);
    public static final int QUERY_COLOR = Color.rgb(235, 115, 20);

    private final MapView mapView;
    private final Listener listener;
    private MapLibreMap map;
    private MapContextCloseController closeController;
    private String geoJson = emptyCollection();
    private String queryGeoJson = emptyCollection();
    private String label = "";
    private int count;
    private boolean visible;
    private boolean hasQueryGeometry;
    private GeologyRepository.Bounds queryBounds;
    private String queryCloseLabel = "Geology";
    private boolean fitQueryOnNextRender;

    public GeologyOverlayController(MapView mapView, Listener listener) {
        this.mapView = mapView;
        this.listener = listener;
    }

    public void initialize() {
        closeController = MapContextCloseController.forMap(mapView);
        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            if (closeController != null) closeController.refresh();
        });
    }

    public void show(String geoJson, String label, int count) {
        this.geoJson = geoJson == null || geoJson.trim().isEmpty() ? emptyCollection() : geoJson;
        this.queryGeoJson = extractQueryGeoJson(this.geoJson);
        this.hasQueryGeometry = !emptyCollection().equals(this.queryGeoJson);
        this.queryBounds = extractQueryBounds(this.geoJson);
        this.queryCloseLabel = extractQueryCloseLabel(this.geoJson);
        this.fitQueryOnNextRender = this.hasQueryGeometry && this.queryBounds != null;
        this.label = label == null ? "" : label.trim();
        this.count = Math.max(0, count);
        this.visible = this.count > 0;
        syncCloseTarget();
        render();
    }

    public void clear() {
        geoJson = emptyCollection();
        queryGeoJson = emptyCollection();
        hasQueryGeometry = false;
        queryBounds = null;
        queryCloseLabel = "Geology";
        fitQueryOnNextRender = false;
        label = "";
        count = 0;
        visible = false;
        if (closeController != null) closeController.clearGeologyTarget();
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(SOURCE_ID);
            if (source != null) source.setGeoJson(geoJson);
            GeoJsonSource querySource = style.getSourceAs(QUERY_SOURCE_ID);
            if (querySource != null) querySource.setGeoJson(queryGeoJson);
            applyVisibility(style);
        }));
    }

    public void refreshStyle() {
        if (count > 0) render();
        if (closeController != null) closeController.refresh();
    }

    public boolean hasResults() { return count > 0; }
    public int getCount() { return count; }
    public String getLabel() { return label; }
    public boolean isVisible() { return hasResults() && visible; }
    public boolean hasQueryGeometry() { return hasQueryGeometry; }

    public void setVisible(boolean show) {
        visible = show && hasResults();
        if (visible) {
            syncCloseTarget();
            render();
        } else {
            if (closeController != null) closeController.clearGeologyTarget();
            mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(this::applyVisibility));
        }
    }


    private void syncCloseTarget() {
        if (closeController == null) closeController = MapContextCloseController.forMap(mapView);
        if (closeController == null) return;
        if (visible && count > 0 && hasQueryGeometry && queryBounds != null) {
            closeController.setGeologyTarget(
                    queryBounds.south, queryBounds.west, queryBounds.north, queryBounds.east,
                    queryCloseLabel, QUERY_COLOR, this::clear);
        } else {
            closeController.clearGeologyTarget();
        }
    }

    public boolean handleTap(LatLng coordinate) {
        if (!visible || count <= 0 || map == null || coordinate == null) return false;
        Style style = map.getStyle();
        if (style == null || style.getLayer(FILL_ID) == null) return false;
        PointF point = map.getProjection().toScreenLocation(coordinate);
        List<Feature> features = map.queryRenderedFeatures(point, new String[]{FILL_ID});
        if (features == null || features.isEmpty()) return false;
        if (listener != null) listener.onGeologyTapped(features.get(0), coordinate);
        return true;
    }

    private void render() {
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(SOURCE_ID);
            if (source == null) {
                source = new GeoJsonSource(SOURCE_ID, geoJson);
                style.addSource(source);
            } else {
                source.setGeoJson(geoJson);
            }

            if (style.getLayer(FILL_ID) == null) {
                FillLayer fill = new FillLayer(FILL_ID, SOURCE_ID);
                fill.setProperties(
                        fillColor(FILL_COLOR),
                        fillOpacity(0.28f));
                addBelowSafety(style, fill);
            }
            if (style.getLayer(LINE_ID) == null) {
                LineLayer line = new LineLayer(LINE_ID, SOURCE_ID);
                line.setProperties(
                        lineColor(OUTLINE_COLOR),
                        lineWidth(1.4f));
                addBelowSafety(style, line);
            }

            GeoJsonSource querySource = style.getSourceAs(QUERY_SOURCE_ID);
            if (querySource == null) {
                querySource = new GeoJsonSource(QUERY_SOURCE_ID, queryGeoJson);
                style.addSource(querySource);
            } else {
                querySource.setGeoJson(queryGeoJson);
            }
            if (style.getLayer(QUERY_FILL_ID) == null) {
                FillLayer queryFill = new FillLayer(QUERY_FILL_ID, QUERY_SOURCE_ID);
                queryFill.setProperties(
                        fillColor(QUERY_COLOR),
                        fillOpacity(0.055f));
                style.addLayer(queryFill);
            }
            if (style.getLayer(QUERY_LINE_ID) == null) {
                LineLayer queryLine = new LineLayer(QUERY_LINE_ID, QUERY_SOURCE_ID);
                queryLine.setProperties(
                        lineColor(QUERY_COLOR),
                        lineWidth(2.8f));
                style.addLayer(queryLine);
            }
            if (style.getLayer(QUERY_CENTER_ID) == null) {
                CircleLayer center = new CircleLayer(QUERY_CENTER_ID, QUERY_SOURCE_ID);
                center.setProperties(
                        circleRadius(6f),
                        circleColor(QUERY_COLOR),
                        circleOpacity(1f),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(2f));
                style.addLayer(center);
            }
            applyVisibility(style);
            if (fitQueryOnNextRender && queryBounds != null) {
                fitQueryOnNextRender = false;
                mapView.post(() -> fitQueryBounds(mapLibreMap, queryBounds));
            }
        }));
    }

    private void fitQueryBounds(MapLibreMap mapLibreMap, GeologyRepository.Bounds bounds) {
        if (mapLibreMap == null || bounds == null) return;
        double span = Math.max(bounds.north - bounds.south, bounds.east - bounds.west);
        double lat = (bounds.south + bounds.north) / 2d;
        double lon = (bounds.west + bounds.east) / 2d;
        if (span <= 0.0001d) {
            mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 15.5));
            return;
        }
        try {
            LatLngBounds cameraBounds = new LatLngBounds.Builder()
                    .include(new LatLng(bounds.south, bounds.west))
                    .include(new LatLng(bounds.north, bounds.east))
                    .build();
            float density = mapView.getResources().getDisplayMetrics().density;
            int horizontal = Math.max(36, Math.round(44f * density));
            int top = Math.max(48, Math.round(64f * density));
            int bottom = Math.max(96, Math.round(152f * density));
            mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngBounds(
                    cameraBounds, horizontal, top, horizontal, bottom));
        } catch (RuntimeException ignored) {
            mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), 12.0));
        }
    }

    private void addBelowSafety(Style style, org.maplibre.android.style.layers.Layer layer) {
        if (style.getLayer(MapController.LAND_FILL) != null) {
            style.addLayerBelow(layer, MapController.LAND_FILL);
        } else if (style.getLayer(MapController.CLAIM_FILL) != null) {
            style.addLayerBelow(layer, MapController.CLAIM_FILL);
        } else if (style.getLayer(MapController.WAYPOINT_LAYER) != null) {
            style.addLayerBelow(layer, MapController.WAYPOINT_LAYER);
        } else {
            style.addLayer(layer);
        }
    }

    private void applyVisibility(Style style) {
        if (style == null) return;
        org.maplibre.android.style.layers.Layer fill = style.getLayer(FILL_ID);
        org.maplibre.android.style.layers.Layer line = style.getLayer(LINE_ID);
        org.maplibre.android.style.layers.Layer queryFill = style.getLayer(QUERY_FILL_ID);
        org.maplibre.android.style.layers.Layer queryLine = style.getLayer(QUERY_LINE_ID);
        org.maplibre.android.style.layers.Layer queryCenter = style.getLayer(QUERY_CENTER_ID);
        String state = visible && count > 0 ? VISIBLE : NONE;
        if (fill != null) fill.setProperties(visibility(state));
        if (line != null) line.setProperties(visibility(state));
        String queryState = visible && count > 0 && hasQueryGeometry ? VISIBLE : NONE;
        if (queryFill != null) queryFill.setProperties(visibility(queryState));
        if (queryLine != null) queryLine.setProperties(visibility(queryState));
        if (queryCenter != null) queryCenter.setProperties(visibility(queryState));
    }

    /**
     * Research GeoJSON may contain the RFC 7946-compatible foreign member rockmapQuery.
     * It carries only the user-selected analysis boundary/center, never user notes or other data.
     */
    private static String extractQueryGeoJson(String researchGeoJson) {
        try {
            JSONObject root = new JSONObject(researchGeoJson);
            JSONObject query = root.optJSONObject("rockmapQuery");
            if (query == null) return emptyCollection();
            JSONObject geometry = query.optJSONObject("geometry");
            boolean hasCenter = query.has("centerLat") && query.has("centerLon");
            if (geometry == null && !hasCenter) return emptyCollection();

            JSONArray features = new JSONArray();
            if (geometry != null) {
                JSONObject feature = new JSONObject();
                feature.put("type", "Feature");
                feature.put("geometry", geometry);
                JSONObject properties = new JSONObject();
                properties.put("rockmapQueryRole", "area");
                feature.put("properties", properties);
                features.put(feature);
            }
            if (hasCenter) {
                double lat = query.optDouble("centerLat", Double.NaN);
                double lon = query.optDouble("centerLon", Double.NaN);
                if (Double.isFinite(lat) && Double.isFinite(lon)) {
                    JSONObject point = new JSONObject();
                    point.put("type", "Point");
                    JSONArray coordinates = new JSONArray();
                    coordinates.put(lon);
                    coordinates.put(lat);
                    point.put("coordinates", coordinates);
                    JSONObject feature = new JSONObject();
                    feature.put("type", "Feature");
                    feature.put("geometry", point);
                    JSONObject properties = new JSONObject();
                    properties.put("rockmapQueryRole", "center");
                    feature.put("properties", properties);
                    features.put(feature);
                }
            }
            if (features.length() == 0) return emptyCollection();
            JSONObject collection = new JSONObject();
            collection.put("type", "FeatureCollection");
            collection.put("features", features);
            return collection.toString();
        } catch (JSONException ex) {
            return emptyCollection();
        }
    }


    private static String extractQueryCloseLabel(String researchGeoJson) {
        if (researchGeoJson == null || researchGeoJson.trim().isEmpty()) return "Geology";
        try {
            JSONObject root = new JSONObject(researchGeoJson);
            JSONObject query = root.optJSONObject("rockmapQuery");
            if (query == null) return "Geology";
            String type = query.optString("type", "");
            if ("point_radius".equals(type)) {
                double radius = query.optDouble("radiusMeters", 0d);
                if (radius >= 1000d) {
                    double km = radius / 1000d;
                    String amount = km == Math.rint(km)
                            ? String.format(java.util.Locale.US, "%.0f km", km)
                            : String.format(java.util.Locale.US, "%.1f km", km);
                    return "Geology — " + amount + " radius";
                }
                if (radius > 0d) return "Geology — " + Math.round(radius) + " m radius";
            }
            if ("point".equals(type)) return "Geology — Exact Point";
            if ("bounds".equals(type)) return "Geology — Visible Area";
            if ("polygon".equals(type)) return "Geology — Prospecting Area";
            return "Geology";
        } catch (JSONException ex) {
            return "Geology";
        }
    }

    private static GeologyRepository.Bounds extractQueryBounds(String researchGeoJson) {
        try {
            JSONObject root = new JSONObject(researchGeoJson);
            JSONObject query = root.optJSONObject("rockmapQuery");
            if (query == null) return null;
            return new GeologyRepository.Bounds(
                    query.optDouble("south", Double.NaN),
                    query.optDouble("west", Double.NaN),
                    query.optDouble("north", Double.NaN),
                    query.optDouble("east", Double.NaN));
        } catch (RuntimeException | JSONException ex) {
            return null;
        }
    }

    private static String emptyCollection() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }
}
