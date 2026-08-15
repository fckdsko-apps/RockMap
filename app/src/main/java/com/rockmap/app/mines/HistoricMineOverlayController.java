package com.rockmap.app.mines;

import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.RectF;

import com.rockmap.app.minerals.MineralRecord;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

public final class HistoricMineOverlayController {
    public interface Listener {
        void onHistoricMinesTapped(List<MineralRecord> records);
    }

    public static final String SOURCE_ID = "rockmap-historic-mine-source";
    public static final String PROPERTY_LAYER_ID = "rockmap-historic-mine-property-layer";
    public static final String OPENING_LAYER_ID = "rockmap-historic-mine-opening-layer";
    public static final String CLUSTER_LAYER_ID = "rockmap-historic-mine-cluster-layer";
    public static final String CLUSTER_COUNT_LAYER_ID = "rockmap-historic-mine-cluster-count-layer";

    private final MapView mapView;
    private final Listener listener;
    private final Map<String, MineralRecord> recordsByIdentity = new HashMap<>();
    private MapLibreMap map;
    private List<MineralRecord> activeRecords = new ArrayList<>();
    private boolean visible;

    public HistoricMineOverlayController(MapView mapView, Listener listener) {
        this.mapView = mapView;
        this.listener = listener;
    }

    public void initialize() {
        mapView.getMapAsync(mapLibreMap -> map = mapLibreMap);
    }

    public void load(List<MineralRecord> records) {
        ArrayList<MineralRecord> filtered = new ArrayList<>();
        recordsByIdentity.clear();
        if (records != null) {
            for (MineralRecord record : records) {
                if (!HistoricMineCatalog.isMineRecord(record)) continue;
                String identity = HistoricMineCatalog.identity(record);
                if (identity.isEmpty() || recordsByIdentity.containsKey(identity)) continue;
                recordsByIdentity.put(identity, record);
                filtered.add(record);
            }
        }
        activeRecords = filtered;
        if (visible) installOrUpdate();
    }

    public void clear() {
        activeRecords = new ArrayList<>();
        recordsByIdentity.clear();
        visible = false;
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(SOURCE_ID);
            if (source != null) source.setGeoJson(emptyCollection());
            applyVisibility(style);
        }));
    }

    public boolean isLoaded() {
        return !activeRecords.isEmpty();
    }

    public int getRecordCount() {
        return activeRecords.size();
    }

    public boolean isVisible() {
        return isLoaded() && visible;
    }

    public void setVisible(boolean show) {
        visible = show && isLoaded();
        if (visible) {
            installOrUpdate();
        } else {
            mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(this::applyVisibility));
        }
    }

    public void refreshStyle() {
        if (isLoaded()) installOrUpdate();
    }

    public void center(MineralRecord record) {
        if (record == null) return;
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(record.latitude, record.longitude), 15.0)));
    }

    public boolean handleTap(LatLng coordinate) {
        if (map == null || !visible || activeRecords.isEmpty()) return false;
        org.maplibre.android.maps.Style style = map.getStyle();
        if (style == null || style.getLayer(CLUSTER_LAYER_ID) == null) return false;

        PointF point = map.getProjection().toScreenLocation(coordinate);
        float radius = 16f * mapView.getResources().getDisplayMetrics().density;
        RectF hitBox = new RectF(point.x - radius, point.y - radius,
                point.x + radius, point.y + radius);

        List<Feature> clusters = map.queryRenderedFeatures(hitBox, new String[]{CLUSTER_LAYER_ID});
        if (!clusters.isEmpty()) {
            try {
                GeoJsonSource source = style.getSourceAs(SOURCE_ID);
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

        List<Feature> points = map.queryRenderedFeatures(
                hitBox, new String[]{PROPERTY_LAYER_ID, OPENING_LAYER_ID});
        LinkedHashMap<String, MineralRecord> matches = new LinkedHashMap<>();
        for (Feature feature : points) {
            if (feature == null || !feature.hasProperty("identity")) continue;
            String identity;
            try {
                identity = feature.getStringProperty("identity");
            } catch (RuntimeException ex) {
                continue;
            }
            MineralRecord record = recordsByIdentity.get(identity);
            if (record != null) matches.put(identity, record);
        }
        if (matches.isEmpty()) return false;
        if (listener != null) listener.onHistoricMinesTapped(new ArrayList<>(matches.values()));
        return true;
    }

    private void installOrUpdate() {
        String geoJson = toGeoJson(activeRecords);
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

            if (style.getLayer(PROPERTY_LAYER_ID) == null) {
                CircleLayer properties = new CircleLayer(PROPERTY_LAYER_ID, SOURCE_ID);
                properties.setProperties(
                        circleColor(Color.rgb(139, 92, 47)),
                        circleRadius(6f),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(1.5f));
                properties.setFilter(Expression.eq(
                        Expression.get("kind"), Expression.literal("property")));
                style.addLayer(properties);
            }

            if (style.getLayer(OPENING_LAYER_ID) == null) {
                CircleLayer openings = new CircleLayer(OPENING_LAYER_ID, SOURCE_ID);
                openings.setProperties(
                        circleColor(Color.rgb(70, 70, 70)),
                        circleRadius(5.5f),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(1.5f));
                openings.setFilter(Expression.eq(
                        Expression.get("kind"), Expression.literal("opening")));
                style.addLayer(openings);
            }

            if (style.getLayer(CLUSTER_LAYER_ID) == null) {
                CircleLayer clusters = new CircleLayer(CLUSTER_LAYER_ID, SOURCE_ID);
                clusters.setProperties(
                        circleColor(Color.rgb(112, 78, 50)),
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

    private void applyVisibility(org.maplibre.android.maps.Style style) {
        boolean show = visible && isLoaded();
        setLayerVisible(style.getLayer(PROPERTY_LAYER_ID), show);
        setLayerVisible(style.getLayer(OPENING_LAYER_ID), show);
        setLayerVisible(style.getLayer(CLUSTER_LAYER_ID), show);
        setLayerVisible(style.getLayer(CLUSTER_COUNT_LAYER_ID), show);
    }

    private void setLayerVisible(Layer layer, boolean show) {
        if (layer != null) layer.setProperties(visibility(show ? VISIBLE : NONE));
    }

    private String toGeoJson(List<MineralRecord> records) {
        try {
            JSONArray features = new JSONArray();
            if (records != null) {
                for (MineralRecord record : records) {
                    if (record == null) continue;
                    JSONObject geometry = new JSONObject();
                    geometry.put("type", "Point");
                    geometry.put("coordinates",
                            new JSONArray().put(record.longitude).put(record.latitude));

                    JSONObject properties = new JSONObject();
                    properties.put("identity", HistoricMineCatalog.identity(record));
                    properties.put("kind", HistoricMineCatalog.isOpening(record)
                            ? "opening" : "property");
                    properties.put("name", HistoricMineCatalog.displayName(record));

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
