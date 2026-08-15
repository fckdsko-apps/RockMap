package com.rockmap.app.minerals;

import android.graphics.Color;
import android.graphics.PointF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonOptions;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;

import java.util.ArrayList;
import java.util.List;

import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.Property.VISIBLE;
import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.textColor;
import static org.maplibre.android.style.layers.PropertyFactory.textField;
import static org.maplibre.android.style.layers.PropertyFactory.textFont;
import static org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.textSize;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;

public final class MineralOverlayController {
    public interface BoundsCallback {
        void onBounds(MineralSearchEngine.Bounds bounds);
    }

    public static final String SOURCE_ID = "rockmap-mineral-search-source";
    public static final String LAYER_ID = "rockmap-mineral-search-layer";
    public static final String CLUSTER_LAYER_ID = "rockmap-mineral-cluster-layer";
    public static final String CLUSTER_COUNT_LAYER_ID = "rockmap-mineral-cluster-count-layer";

    private final MapView mapView;
    private MapLibreMap map;
    private List<MineralSearchEngine.Hit> activeHits = new ArrayList<>();
    private boolean visible = true;

    public MineralOverlayController(MapView mapView) {
        this.mapView = mapView;
    }

    public void initialize() {
        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            map.addOnMapClickListener(this::handleTap);
        });
    }

    public void show(List<MineralSearchEngine.Hit> hits) {
        activeHits = hits == null ? new ArrayList<>() : new ArrayList<>(hits);
        visible = true;
        String geoJson = toGeoJson(activeHits);
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(SOURCE_ID);
            if (source == null) {
                source = new GeoJsonSource(
                        SOURCE_ID,
                        geoJson,
                        new GeoJsonOptions()
                                .withCluster(true)
                                .withClusterMaxZoom(13)
                                .withClusterRadius(55));
                style.addSource(source);
            } else {
                source.setGeoJson(geoJson);
            }

            if (style.getLayer(LAYER_ID) == null) {
                CircleLayer points = new CircleLayer(LAYER_ID, SOURCE_ID);
                points.setProperties(
                        circleColor(Color.rgb(0, 165, 175)),
                        circleRadius(6f),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(2f));
                points.setFilter(Expression.neq(Expression.get("cluster"), Expression.literal(true)));
                style.addLayer(points);
            }

            if (style.getLayer(CLUSTER_LAYER_ID) == null) {
                CircleLayer clusters = new CircleLayer(CLUSTER_LAYER_ID, SOURCE_ID);
                clusters.setProperties(
                        circleColor(Color.rgb(0, 125, 145)),
                        circleRadius(18f),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(2f));
                clusters.setFilter(Expression.has("point_count"));
                style.addLayer(clusters);
            }

            if (style.getLayer(CLUSTER_COUNT_LAYER_ID) == null) {
                SymbolLayer counts = new SymbolLayer(CLUSTER_COUNT_LAYER_ID, SOURCE_ID);
                counts.setProperties(
                        textField(Expression.toString(Expression.get("point_count"))),
                        textFont(new String[]{"RockMapSans"}),
                        textSize(11f),
                        textColor(Color.WHITE),
                        textIgnorePlacement(true),
                        textAllowOverlap(true));
                counts.setFilter(Expression.has("point_count"));
                style.addLayer(counts);
            }
            applyVisibility(style);
        }));
    }

    public void clear() {
        activeHits = new ArrayList<>();
        visible = true;
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(SOURCE_ID);
            if (source != null) source.setGeoJson(emptyCollection());
            applyVisibility(style);
        }));
    }

    public boolean hasResults() {
        return !activeHits.isEmpty();
    }

    public int getResultCount() {
        return activeHits.size();
    }

    public boolean isVisible() {
        return hasResults() && visible;
    }

    public void setVisible(boolean show) {
        if (show && hasResults()) {
            visible = true;
            // Re-running show is cheap for the active result set and also recreates the
            // dynamic source/layers if MapLibre reloaded the base style in the meantime.
            show(activeHits);
            return;
        }
        visible = false;
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(this::applyVisibility));
    }

    public void center(MineralRecord record) {
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(record.latitude, record.longitude), 14.0)));
    }

    public void getVisibleBounds(BoundsCallback callback) {
        mapView.getMapAsync(mapLibreMap -> {
            LatLngBounds bounds = mapLibreMap.getProjection().getVisibleRegion().latLngBounds;
            callback.onBounds(new MineralSearchEngine.Bounds(
                    bounds.getLatNorth(), bounds.getLonEast(), bounds.getLatSouth(), bounds.getLonWest()));
        });
    }

    private boolean handleTap(LatLng coordinate) {
        if (map == null || !visible || activeHits.isEmpty()) return false;
        PointF point = map.getProjection().toScreenLocation(coordinate);

        List<Feature> clusters = map.queryRenderedFeatures(point, new String[]{CLUSTER_LAYER_ID});
        if (!clusters.isEmpty()) {
            try {
                GeoJsonSource source = map.getStyle() == null ? null : map.getStyle().getSourceAs(SOURCE_ID);
                int expansionZoom = source == null
                        ? (int) Math.ceil(map.getCameraPosition().zoom + 2.0)
                        : source.getClusterExpansionZoom(clusters.get(0));
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        coordinate, Math.max(map.getCameraPosition().zoom + 1.0, expansionZoom)));
            } catch (RuntimeException ignored) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        coordinate, map.getCameraPosition().zoom + 2.0));
            }
            return true;
        }

        // Individual mineral points deliberately pass through to RockMap's normal
        // Location information popup. Alpha 6.1.1 adds Save marker there, so a map-tapped
        // mineral point can be saved as a coordinate marker without opening the result list.
        return false;
    }

    private void applyVisibility(org.maplibre.android.maps.Style style) {
        boolean show = visible && hasResults();
        setLayerVisible(style.getLayer(LAYER_ID), show);
        setLayerVisible(style.getLayer(CLUSTER_LAYER_ID), show);
        setLayerVisible(style.getLayer(CLUSTER_COUNT_LAYER_ID), show);
    }

    private void setLayerVisible(Layer layer, boolean show) {
        if (layer != null) layer.setProperties(visibility(show ? VISIBLE : NONE));
    }

    private String toGeoJson(List<MineralSearchEngine.Hit> hits) {
        try {
            JSONArray features = new JSONArray();
            if (hits != null) {
                for (MineralSearchEngine.Hit hit : hits) {
                    MineralRecord record = hit.record;
                    JSONObject geometry = new JSONObject();
                    geometry.put("type", "Point");
                    geometry.put("coordinates", new JSONArray().put(record.longitude).put(record.latitude));
                    JSONObject properties = new JSONObject();
                    properties.put("id", record.id);
                    properties.put("name", record.name);
                    properties.put("reason", hit.reason);
                    JSONObject feature = new JSONObject();
                    feature.put("type", "Feature");
                    feature.put("geometry", geometry);
                    feature.put("properties", properties);
                    features.put(feature);
                }
            }
            JSONObject root = new JSONObject();
            root.put("type", "FeatureCollection");
            root.put("features", features);
            return root.toString();
        } catch (JSONException ex) {
            return emptyCollection();
        }
    }

    private static String emptyCollection() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }
}
