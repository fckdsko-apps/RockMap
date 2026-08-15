package com.rockmap.app.minerals;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.sources.GeoJsonSource;

import java.util.List;

import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;

public final class MineralOverlayController {
    public static final String SOURCE_ID = "rockmap-mineral-search-source";
    public static final String LAYER_ID = "rockmap-mineral-search-layer";
    private final MapView mapView;

    public MineralOverlayController(MapView mapView) {
        this.mapView = mapView;
    }

    public void show(List<MineralSearchEngine.Hit> hits) {
        String geoJson = toGeoJson(hits);
        mapView.getMapAsync(map -> map.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(SOURCE_ID);
            if (source == null) {
                source = new GeoJsonSource(SOURCE_ID, geoJson);
                style.addSource(source);
            } else {
                source.setGeoJson(geoJson);
            }
            if (style.getLayer(LAYER_ID) == null) {
                CircleLayer layer = new CircleLayer(LAYER_ID, SOURCE_ID);
                layer.setProperties(
                        circleColor(Color.rgb(0, 165, 175)),
                        circleRadius(6f),
                        circleStrokeColor(Color.WHITE),
                        circleStrokeWidth(2f));
                style.addLayer(layer);
            }
        }));
    }

    public void clear() {
        mapView.getMapAsync(map -> map.getStyle(style -> {
            GeoJsonSource source = style.getSourceAs(SOURCE_ID);
            if (source != null) source.setGeoJson(emptyCollection());
        }));
    }

    public void center(MineralRecord record) {
        mapView.getMapAsync(map -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(record.latitude, record.longitude), 14.0)));
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
