package com.rockmap.app.research;

import android.graphics.Color;
import android.graphics.PointF;

import com.rockmap.app.map.MapController;

import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;

import java.util.List;

import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.Property.VISIBLE;
import static org.maplibre.android.style.layers.PropertyFactory.fillColor;
import static org.maplibre.android.style.layers.PropertyFactory.fillOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;

/** Temporary map overlay for the currently selected research result. */
public final class GeologyOverlayController {
    public interface Listener {
        void onGeologyTapped(Feature feature, LatLng coordinate);
    }

    public static final String SOURCE_ID = "rockmap-geology-research-source";
    public static final String FILL_ID = "rockmap-geology-research-fill";
    public static final String LINE_ID = "rockmap-geology-research-outline";

    private final MapView mapView;
    private final Listener listener;
    private MapLibreMap map;
    private String geoJson = emptyCollection();
    private String label = "";
    private int count;
    private boolean visible;

    public GeologyOverlayController(MapView mapView, Listener listener) {
        this.mapView = mapView;
        this.listener = listener;
    }

    public void initialize() {
        mapView.getMapAsync(mapLibreMap -> map = mapLibreMap);
    }

    public void show(String geoJson, String label, int count) {
        this.geoJson = geoJson == null || geoJson.trim().isEmpty() ? emptyCollection() : geoJson;
        this.label = label == null ? "" : label.trim();
        this.count = Math.max(0, count);
        this.visible = this.count > 0;
        render();
    }

    public void clear() {
        geoJson = emptyCollection();
        label = "";
        count = 0;
        visible = false;
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(SOURCE_ID);
            if (source != null) source.setGeoJson(geoJson);
            applyVisibility(style);
        }));
    }

    public void refreshStyle() {
        if (count > 0) render();
    }

    public boolean hasResults() { return count > 0; }
    public int getCount() { return count; }
    public String getLabel() { return label; }
    public boolean isVisible() { return hasResults() && visible; }

    public void setVisible(boolean show) {
        visible = show && hasResults();
        if (visible) render();
        else mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(this::applyVisibility));
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
                        fillColor(Color.rgb(224, 151, 42)),
                        fillOpacity(0.28f));
                addBelowSafety(style, fill);
            }
            if (style.getLayer(LINE_ID) == null) {
                LineLayer line = new LineLayer(LINE_ID, SOURCE_ID);
                line.setProperties(
                        lineColor(Color.rgb(120, 74, 18)),
                        lineWidth(1.4f));
                addBelowSafety(style, line);
            }
            applyVisibility(style);
        }));
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
        String state = visible && count > 0 ? VISIBLE : NONE;
        if (fill != null) fill.setProperties(visibility(state));
        if (line != null) line.setProperties(visibility(state));
    }

    private static String emptyCollection() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }
}
