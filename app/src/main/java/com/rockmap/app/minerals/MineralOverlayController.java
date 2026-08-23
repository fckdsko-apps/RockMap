package com.rockmap.app.minerals;

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
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.HeatmapLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonOptions;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;

import java.util.ArrayList;
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
import static org.maplibre.android.style.layers.PropertyFactory.heatmapColor;
import static org.maplibre.android.style.layers.PropertyFactory.heatmapIntensity;
import static org.maplibre.android.style.layers.PropertyFactory.heatmapOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.heatmapRadius;
import static org.maplibre.android.style.layers.PropertyFactory.heatmapWeight;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;
import static org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.textColor;
import static org.maplibre.android.style.layers.PropertyFactory.textField;
import static org.maplibre.android.style.layers.PropertyFactory.textFont;
import static org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.textSize;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;

public final class MineralOverlayController {
    public interface Listener {
        void onMineralTapped(MineralSearchEngine.Hit hit, boolean fromAreaHeatmap);
    }

    public interface BoundsCallback {
        void onBounds(MineralSearchEngine.Bounds bounds);
    }

    public static final String SOURCE_ID = "rockmap-mineral-search-source";
    public static final String LAYER_ID = "rockmap-mineral-search-layer";
    public static final String CLUSTER_LAYER_ID = "rockmap-mineral-cluster-layer";
    public static final String CLUSTER_COUNT_LAYER_ID = "rockmap-mineral-cluster-count-layer";

    public static final String HEATMAP_SOURCE_ID = "rockmap-mineral-heatmap-source";
    public static final String HEATMAP_LAYER_ID = "rockmap-mineral-heatmap-layer";
    public static final String HEATMAP_POINT_LAYER_ID = "rockmap-mineral-heatmap-point-layer";
    public static final String AREA_SOURCE_ID = "rockmap-mineral-analysis-area-source";
    public static final String AREA_FILL_LAYER_ID = "rockmap-mineral-analysis-area-fill";
    public static final String AREA_OUTLINE_LAYER_ID = "rockmap-mineral-analysis-area-outline";

    private final MapView mapView;
    private final Listener listener;
    private MapLibreMap map;
    private MapContextCloseController closeController;
    private List<MineralSearchEngine.Hit> activeHits = new ArrayList<>();
    private List<MineralAreaAnalyzer.EvidencePoint> activeHeatmapPoints = new ArrayList<>();
    private MineralSearchEngine.Bounds activeAreaBounds;
    private String activeHeatmapLabel = "";
    private boolean searchVisible = true;
    private boolean heatmapVisible;
    private boolean areaVisible;

    public MineralOverlayController(MapView mapView, Listener listener) {
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

    public void show(List<MineralSearchEngine.Hit> hits) {
        activeHits = hits == null ? new ArrayList<>() : new ArrayList<>(hits);
        searchVisible = true;
        if (hasHeatmap()) heatmapVisible = false;
        areaVisible = false;
        syncAreaCloseTarget();
        renderSearch();
        applyCurrentVisibility();
    }

    public void showAnalysisBounds(MineralSearchEngine.Bounds bounds) {
        activeAreaBounds = bounds;
        areaVisible = bounds != null;
        if (hasResults()) searchVisible = false;
        syncAreaCloseTarget();
        renderAreaBounds();
        applyCurrentVisibility();
    }

    public void showHeatmap(List<MineralAreaAnalyzer.EvidencePoint> points,
                            MineralSearchEngine.Bounds bounds,
                            String mineralLabel) {
        activeHeatmapPoints = points == null ? new ArrayList<>() : new ArrayList<>(points);
        activeAreaBounds = bounds;
        activeHeatmapLabel = mineralLabel == null ? "" : mineralLabel.trim();
        heatmapVisible = !activeHeatmapPoints.isEmpty();
        areaVisible = bounds != null;
        if (heatmapVisible && hasResults()) searchVisible = false;
        syncAreaCloseTarget();
        renderHeatmap();
        renderAreaBounds();
        applyCurrentVisibility();
    }

    /** Recreate dynamic sources/layers after MapLibre reloads the base style. */
    public void refreshStyle() {
        if (hasResults()) renderSearch();
        if (hasHeatmap()) renderHeatmap();
        if (activeAreaBounds != null) renderAreaBounds();
        syncAreaCloseTarget();
        applyCurrentVisibility();
    }

    /** Clears normal mineral-search results but preserves any selected-area analysis. */
    public void clear() {
        activeHits = new ArrayList<>();
        searchVisible = true;
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(SOURCE_ID);
            if (source != null) source.setGeoJson(emptyCollection());
            applyVisibility(style);
        }));
    }

    public void clearAreaAnalysis() {
        activeHeatmapPoints = new ArrayList<>();
        activeAreaBounds = null;
        activeHeatmapLabel = "";
        heatmapVisible = false;
        areaVisible = false;
        if (closeController != null) closeController.clearMineralTarget();
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(style -> {
            GeoJsonSource heatmapSource = style.getSourceAs(HEATMAP_SOURCE_ID);
            if (heatmapSource != null) heatmapSource.setGeoJson(emptyCollection());
            GeoJsonSource areaSource = style.getSourceAs(AREA_SOURCE_ID);
            if (areaSource != null) areaSource.setGeoJson(emptyCollection());
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
        return hasResults() && searchVisible;
    }

    public void setVisible(boolean show) {
        searchVisible = show && hasResults();
        if (searchVisible) renderSearch();
        applyCurrentVisibility();
    }

    public boolean hasHeatmap() {
        return !activeHeatmapPoints.isEmpty();
    }

    public int getHeatmapPointCount() {
        return activeHeatmapPoints.size();
    }

    public String getHeatmapLabel() {
        return activeHeatmapLabel;
    }

    public boolean isHeatmapVisible() {
        return hasHeatmap() && heatmapVisible;
    }

    public boolean isAreaAnalysisVisible() {
        return areaVisible && activeAreaBounds != null;
    }

    public void setHeatmapVisible(boolean show) {
        heatmapVisible = show && hasHeatmap();
        areaVisible = heatmapVisible && activeAreaBounds != null;
        if (heatmapVisible) {
            renderHeatmap();
            renderAreaBounds();
        }
        syncAreaCloseTarget();
        applyCurrentVisibility();
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

    public boolean handleTap(LatLng coordinate) {
        if (map == null) return false;
        Style currentStyle = map.getStyle();
        if (currentStyle == null) return false;
        PointF point = map.getProjection().toScreenLocation(coordinate);

        if (heatmapVisible && hasHeatmap() && currentStyle.getLayer(HEATMAP_POINT_LAYER_ID) != null) {
            List<Feature> heatmapPoints = map.queryRenderedFeatures(point, new String[]{HEATMAP_POINT_LAYER_ID});
            for (Feature feature : heatmapPoints) {
                MineralSearchEngine.Hit hit = findHeatmapHit(feature);
                if (hit != null) {
                    if (listener != null) listener.onMineralTapped(hit, true);
                    return true;
                }
            }
        }

        if (!searchVisible || activeHits.isEmpty()
                || currentStyle.getLayer(CLUSTER_LAYER_ID) == null
                || currentStyle.getLayer(LAYER_ID) == null) return false;

        List<Feature> clusters = map.queryRenderedFeatures(point, new String[]{CLUSTER_LAYER_ID});
        if (!clusters.isEmpty()) {
            try {
                GeoJsonSource source = currentStyle.getSourceAs(SOURCE_ID);
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

        List<Feature> points = map.queryRenderedFeatures(point, new String[]{LAYER_ID});
        for (Feature feature : points) {
            MineralSearchEngine.Hit hit = findSearchHit(feature);
            if (hit != null) {
                if (listener != null) listener.onMineralTapped(hit, false);
                return true;
            }
        }
        return false;
    }

    private void syncAreaCloseTarget() {
        if (closeController == null) closeController = MapContextCloseController.forMap(mapView);
        if (closeController == null) return;
        if (areaVisible && activeAreaBounds != null) {
            String closeLabel = heatmapVisible && !activeHeatmapLabel.isEmpty()
                    ? activeHeatmapLabel + " heatmap" : "Mineral Evidence";
            int closeColor = heatmapVisible ? Color.rgb(205, 35, 25) : Color.rgb(235, 115, 20);
            closeController.setMineralTarget(
                    activeAreaBounds.south, activeAreaBounds.west,
                    activeAreaBounds.north, activeAreaBounds.east,
                    closeLabel, closeColor, this::clearAreaAnalysis);
        } else {
            closeController.clearMineralTarget();
        }
    }

    private MineralSearchEngine.Hit findSearchHit(Feature feature) {
        String key = featureKey(feature);
        if (key.isEmpty()) return null;
        for (MineralSearchEngine.Hit hit : activeHits) {
            if (hit != null && hit.record != null
                    && key.equals(MineralAreaAnalyzer.recordKey(hit.record))) return hit;
        }
        return null;
    }

    private MineralSearchEngine.Hit findHeatmapHit(Feature feature) {
        String key = featureKey(feature);
        if (key.isEmpty()) return null;
        for (MineralAreaAnalyzer.EvidencePoint point : activeHeatmapPoints) {
            if (point != null && point.record != null
                    && key.equals(MineralAreaAnalyzer.recordKey(point.record))) {
                return new MineralSearchEngine.Hit(
                        point.record,
                        point.reason,
                        Math.round(point.weight * 100f));
            }
        }
        return null;
    }

    private String featureKey(Feature feature) {
        if (feature == null || !feature.hasProperty("record_key")) return "";
        String key = feature.getStringProperty("record_key");
        return key == null ? "" : key;
    }

    private void renderSearch() {
        String geoJson = searchGeoJson(activeHits);
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
                addBelowSafetyLayers(style, points);
            }

            if (style.getLayer(CLUSTER_LAYER_ID) == null) {
                CircleLayer clusters = new CircleLayer(CLUSTER_LAYER_ID, SOURCE_ID);
                clusters.setProperties(
                        circleColor(Color.rgb(0, 125, 145)),
                        circleRadius(18f),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(2f));
                clusters.setFilter(Expression.has("point_count"));
                addBelowSafetyLayers(style, clusters);
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
                addBelowSafetyLayers(style, counts);
            }
            applyVisibility(style);
        }));
    }

    private void renderHeatmap() {
        String geoJson = heatmapGeoJson(activeHeatmapPoints);
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(HEATMAP_SOURCE_ID);
            if (source == null) {
                source = new GeoJsonSource(HEATMAP_SOURCE_ID, geoJson);
                style.addSource(source);
            } else {
                source.setGeoJson(geoJson);
            }

            if (style.getLayer(HEATMAP_LAYER_ID) == null) {
                HeatmapLayer heatmap = new HeatmapLayer(HEATMAP_LAYER_ID, HEATMAP_SOURCE_ID);
                heatmap.setMaxZoom(15f);
                heatmap.setProperties(
                        heatmapWeight(Expression.get("weight")),
                        heatmapIntensity(1.0f),
                        heatmapRadius(30f),
                        heatmapOpacity(0.68f),
                        heatmapColor(Expression.interpolate(
                                Expression.linear(), Expression.heatmapDensity(),
                                Expression.literal(0.0f), Expression.rgba(35, 80, 255, 0.0f),
                                Expression.literal(0.15f), Expression.rgba(35, 80, 255, 0.65f),
                                Expression.literal(0.35f), Expression.rgba(0, 190, 225, 0.72f),
                                Expression.literal(0.55f), Expression.rgba(255, 225, 70, 0.78f),
                                Expression.literal(0.75f), Expression.rgba(255, 130, 30, 0.85f),
                                Expression.literal(1.0f), Expression.rgba(205, 35, 25, 0.92f))));
                addBelowSafetyLayers(style, heatmap);
            }

            if (style.getLayer(HEATMAP_POINT_LAYER_ID) == null) {
                CircleLayer points = new CircleLayer(HEATMAP_POINT_LAYER_ID, HEATMAP_SOURCE_ID);
                points.setMinZoom(10f);
                points.setProperties(
                        circleColor(Color.rgb(35, 35, 35)),
                        circleRadius(3.7f),
                        circleOpacity(0.82f),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(1.25f));
                addBelowSafetyLayers(style, points);
            }
            applyVisibility(style);
        }));
    }

    private void renderAreaBounds() {
        String geoJson = boundsGeoJson(activeAreaBounds);
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(AREA_SOURCE_ID);
            if (source == null) {
                source = new GeoJsonSource(AREA_SOURCE_ID, geoJson);
                style.addSource(source);
            } else {
                source.setGeoJson(geoJson);
            }

            if (style.getLayer(AREA_FILL_LAYER_ID) == null) {
                FillLayer fill = new FillLayer(AREA_FILL_LAYER_ID, AREA_SOURCE_ID);
                fill.setProperties(
                        fillColor(Color.rgb(255, 160, 25)),
                        fillOpacity(0.055f));
                addBelowSafetyLayers(style, fill);
            }
            if (style.getLayer(AREA_OUTLINE_LAYER_ID) == null) {
                LineLayer outline = new LineLayer(AREA_OUTLINE_LAYER_ID, AREA_SOURCE_ID);
                outline.setProperties(
                        lineColor(Color.rgb(235, 115, 20)),
                        lineWidth(2.4f));
                addBelowSafetyLayers(style, outline);
            }
            applyVisibility(style);
        }));
    }

    private void addBelowSafetyLayers(Style style, Layer layer) {
        String[] anchors = new String[]{
                MapController.CLAIM_FILL,
                MapController.WAYPOINT_LAYER,
                MapController.CURRENT_LAYER
        };
        for (String anchor : anchors) {
            if (style.getLayer(anchor) != null) {
                style.addLayerBelow(layer, anchor);
                return;
            }
        }
        style.addLayer(layer);
    }

    private void applyCurrentVisibility() {
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(this::applyVisibility));
    }

    private void applyVisibility(Style style) {
        boolean showSearch = searchVisible && hasResults();
        setLayerVisible(style.getLayer(LAYER_ID), showSearch);
        setLayerVisible(style.getLayer(CLUSTER_LAYER_ID), showSearch);
        setLayerVisible(style.getLayer(CLUSTER_COUNT_LAYER_ID), showSearch);

        boolean showHeatmap = heatmapVisible && hasHeatmap();
        setLayerVisible(style.getLayer(HEATMAP_LAYER_ID), showHeatmap);
        setLayerVisible(style.getLayer(HEATMAP_POINT_LAYER_ID), showHeatmap);

        boolean showArea = areaVisible && activeAreaBounds != null;
        setLayerVisible(style.getLayer(AREA_FILL_LAYER_ID), showArea);
        setLayerVisible(style.getLayer(AREA_OUTLINE_LAYER_ID), showArea);
    }

    private void setLayerVisible(Layer layer, boolean show) {
        if (layer != null) layer.setProperties(visibility(show ? VISIBLE : NONE));
    }

    private String searchGeoJson(List<MineralSearchEngine.Hit> hits) {
        try {
            JSONArray features = new JSONArray();
            if (hits != null) {
                for (MineralSearchEngine.Hit hit : hits) {
                    if (hit == null || hit.record == null) continue;
                    MineralRecord record = hit.record;
                    JSONObject properties = baseProperties(record);
                    properties.put("reason", hit.reason);
                    features.put(pointFeature(record, properties));
                }
            }
            return featureCollection(features);
        } catch (JSONException ex) {
            return emptyCollection();
        }
    }

    private String heatmapGeoJson(List<MineralAreaAnalyzer.EvidencePoint> points) {
        try {
            JSONArray features = new JSONArray();
            if (points != null) {
                for (MineralAreaAnalyzer.EvidencePoint point : points) {
                    if (point == null || point.record == null) continue;
                    JSONObject properties = baseProperties(point.record);
                    properties.put("reason", point.reason);
                    properties.put("weight", point.weight);
                    properties.put("mineral", point.displayName);
                    features.put(pointFeature(point.record, properties));
                }
            }
            return featureCollection(features);
        } catch (JSONException ex) {
            return emptyCollection();
        }
    }

    private JSONObject baseProperties(MineralRecord record) throws JSONException {
        JSONObject properties = new JSONObject();
        properties.put("record_key", MineralAreaAnalyzer.recordKey(record));
        properties.put("id", record.id);
        properties.put("source_code", record.sourceCode);
        properties.put("name", record.name);
        return properties;
    }

    private JSONObject pointFeature(MineralRecord record, JSONObject properties) throws JSONException {
        JSONObject geometry = new JSONObject();
        geometry.put("type", "Point");
        geometry.put("coordinates", new JSONArray().put(record.longitude).put(record.latitude));
        JSONObject feature = new JSONObject();
        feature.put("type", "Feature");
        feature.put("geometry", geometry);
        feature.put("properties", properties);
        return feature;
    }

    private String boundsGeoJson(MineralSearchEngine.Bounds bounds) {
        if (bounds == null) return emptyCollection();
        try {
            JSONArray ring = new JSONArray();
            ring.put(new JSONArray().put(bounds.west).put(bounds.south));
            ring.put(new JSONArray().put(bounds.east).put(bounds.south));
            ring.put(new JSONArray().put(bounds.east).put(bounds.north));
            ring.put(new JSONArray().put(bounds.west).put(bounds.north));
            ring.put(new JSONArray().put(bounds.west).put(bounds.south));
            JSONObject geometry = new JSONObject();
            geometry.put("type", "Polygon");
            geometry.put("coordinates", new JSONArray().put(ring));
            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            feature.put("geometry", geometry);
            feature.put("properties", new JSONObject().put("kind", "mineral-analysis-area"));
            return featureCollection(new JSONArray().put(feature));
        } catch (JSONException ex) {
            return emptyCollection();
        }
    }

    private String featureCollection(JSONArray features) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("type", "FeatureCollection");
        root.put("features", features);
        return root.toString();
    }

    private static String emptyCollection() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }
}
