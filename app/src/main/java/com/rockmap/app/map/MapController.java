package com.rockmap.app.map;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.location.Location;

import com.rockmap.app.offline.OfflineDataManager;
import com.rockmap.app.waypoints.WaypointEntity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.VectorSource;
import org.maplibre.geojson.Feature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.maplibre.android.style.layers.Property.NONE;
import static org.maplibre.android.style.layers.Property.VISIBLE;
import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.fillColor;
import static org.maplibre.android.style.layers.PropertyFactory.fillOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap;
import static org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement;
import static org.maplibre.android.style.layers.PropertyFactory.iconImage;
import static org.maplibre.android.style.layers.PropertyFactory.iconRotate;
import static org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment;
import static org.maplibre.android.style.layers.PropertyFactory.visibility;

public final class MapController {
    public interface Listener {
        void onMapSafetyState(boolean verified, String message);
        boolean onWaypointTapped(WaypointEntity waypoint);
        boolean onMapOverlayTapped(LatLng coordinate, List<Feature> landAtCoordinate);
        void onMapFeaturesTapped(LatLng coordinate, List<Feature> land, List<Feature> claims);
    }

    public static final String BASE_SOURCE = "rockmap-base";
    public static final String LAND_SOURCE = "rockmap-land";
    public static final String CLAIM_SOURCE = "rockmap-claims";
    public static final String LAND_FILL = "rockmap-land-fill";
    public static final String LAND_OUTLINE = "rockmap-land-outline";
    public static final String LAND_QUERY_LAYER = "rockmap-land-hit-test";
    public static final String CLAIM_FILL = "rockmap-claim-fill";
    public static final String CLAIM_OUTLINE = "rockmap-claim-outline";
    public static final String CURRENT_SOURCE = "rockmap-current-location-source";
    public static final String CURRENT_HEADING_LAYER = "rockmap-current-heading-layer";
    private static final String CURRENT_HEADING_ICON = "rockmap-current-heading-icon";
    public static final String CURRENT_LAYER = "rockmap-current-location-layer";
    public static final String WAYPOINT_SOURCE = "rockmap-waypoint-source";
    public static final String WAYPOINT_LAYER = "rockmap-waypoint-layer";
    public static final String LABEL_LOCALITY = "rockmap-label-locality";
    public static final String LABEL_ROAD_MAJOR = "rockmap-label-road-major";
    public static final String LABEL_WATER = "rockmap-label-water";
    public static final String LABEL_PEAK = "rockmap-label-peak";
    public static final String LABEL_PLACE_ANY = "rockmap-label-place-any";
    public static final String LABEL_WATER_ANY = "rockmap-label-water-any";
    public static final String LABEL_ROAD_ANY = "rockmap-label-road-any";

    private final MapView mapView;
    private final OfflineDataManager dataManager;
    private final Listener listener;
    private MapLibreMap map;
    private Style style;
    private Location currentLocation;
    private Float currentHeadingDegrees;
    private List<WaypointEntity> waypoints = new ArrayList<>();
    private boolean landVisible = true;
    private boolean claimsVisible = true;
    private boolean waypointsVisible = true;
    private boolean verifiedStyleActive;
    private boolean landStatusAvailable;
    private boolean claimsAvailable;
    private boolean offlineStyleActive;
    private boolean attemptingOfflineStyle;
    private boolean fallbackLoading;
    private boolean rollbackAttempted;
    private int glyphFallbackRequests;
    private int glyphFallbackLoads;
    private int glyphFallbackErrors;

    public MapController(MapView mapView, OfflineDataManager dataManager, Listener listener) {
        this.mapView = mapView;
        this.dataManager = dataManager;
        this.listener = listener;
    }

    public void initialize() {
        mapView.addOnDidFailLoadingMapListener(errorMessage -> {
            if (offlineStyleActive || attemptingOfflineStyle) {
                failOfflineStyle("MapLibre failed to load the offline map: " + errorMessage);
            }
        });
        mapView.addOnGlyphsRequestedListener((fontStack, rangeStart, rangeEnd) -> glyphFallbackRequests++);
        mapView.addOnGlyphsLoadedListener((fontStack, rangeStart, rangeEnd) -> glyphFallbackLoads++);
        mapView.addOnGlyphsErrorListener((fontStack, rangeStart, rangeEnd) -> {
            glyphFallbackErrors++;
            if (offlineStyleActive || attemptingOfflineStyle) {
                failOfflineStyle("Required offline map labels failed to load.");
            }
        });

        mapView.getMapAsync(mapLibreMap -> {
            map = mapLibreMap;
            map.addOnMapClickListener(this::handleTap);
            reloadStyle();
        });
    }

    public void reloadStyle() {
        rollbackAttempted = false;
        loadActiveOrSafe();
    }

    private void loadActiveOrSafe() {
        if (map == null) return;
        if (!dataManager.hasRenderableActivePack()) {
            loadSafeStyle("MAP DATA NOT INSTALLED / VERIFIED — DO NOT USE THIS SCREEN FOR NAVIGATION.\n"
                    + dataManager.describeStatus());
            return;
        }

        final boolean expectedVerified = dataManager.hasVerifiedActivePack();
        final boolean expectedClaimsTest = dataManager.hasClaimsTestPack();
        final boolean expectedLandTest = dataManager.hasLandStatusTestPack();
        final boolean claimsWereAvailable = claimsAvailable;
        try {
            String styleJson = dataManager.buildActiveStyleJson();
            attemptingOfflineStyle = true;
            offlineStyleActive = false;
            verifiedStyleActive = false;
            landStatusAvailable = false;
            claimsAvailable = false;
            fallbackLoading = false;
            map.setStyle(new Style.Builder().fromJson(styleJson), loaded -> {
                style = loaded;
                boolean contractValid = expectedVerified
                        ? verifyRequiredDataContract(loaded)
                        : expectedClaimsTest
                            ? verifyClaimsTestContract(loaded)
                            : expectedLandTest
                                ? verifyLandStatusTestContract(loaded)
                                : verifyBasemapTestContract(loaded);
                if (!contractValid) {
                    failOfflineStyle(expectedVerified
                            ? "Published files loaded, but the map style is missing required RockMap sources/layers."
                            : expectedClaimsTest
                                ? "Alpha 5 claims test loaded, but required base/label/land/claims sources or layers are missing."
                                : expectedLandTest
                                    ? "Alpha 4 land-status test loaded, but required base/label/land sources or layers are missing."
                                    : "Alpha 3.1 basemap files loaded, but required offline label sources/layers are missing.");
                    return;
                }
                attemptingOfflineStyle = false;
                offlineStyleActive = true;
                verifiedStyleActive = expectedVerified;
                landStatusAvailable = expectedVerified || expectedLandTest || expectedClaimsTest;
                claimsAvailable = expectedVerified || expectedClaimsTest;
                if (claimsAvailable && !claimsWereAvailable) claimsVisible = true;
                if (!claimsAvailable) claimsVisible = false;
                installLocalOverlayLayers();
                renderCurrentLocation();
                renderWaypoints();
                applyVisibility();
                listener.onMapSafetyState(expectedVerified, dataManager.describeStatus());
            });
        } catch (IOException | RuntimeException ex) {
            failOfflineStyle("Offline map snapshot was rejected before rendering: " + ex.getMessage());
        }
    }

    private boolean verifyBasemapTestContract(Style loaded) {
        return loaded.getSource(BASE_SOURCE) != null
                && loaded.getLayer(LABEL_LOCALITY) != null
                && loaded.getLayer(LABEL_ROAD_MAJOR) != null
                && loaded.getLayer(LABEL_WATER) != null
                && loaded.getLayer(LABEL_PEAK) != null
                && loaded.getLayer(LABEL_PLACE_ANY) != null
                && loaded.getLayer(LABEL_WATER_ANY) != null
                && loaded.getLayer(LABEL_ROAD_ANY) != null;
    }

    private boolean verifyLandStatusTestContract(Style loaded) {
        return verifyBasemapTestContract(loaded)
                && loaded.getSource(LAND_SOURCE) != null
                && loaded.getLayer(LAND_FILL) != null
                && loaded.getLayer(LAND_OUTLINE) != null;
    }

    private boolean verifyClaimsTestContract(Style loaded) {
        return verifyLandStatusTestContract(loaded)
                && loaded.getSource(CLAIM_SOURCE) != null
                && loaded.getLayer(CLAIM_FILL) != null
                && loaded.getLayer(CLAIM_OUTLINE) != null;
    }

    private boolean verifyRequiredDataContract(Style loaded) {
        return loaded.getSource(BASE_SOURCE) != null
                && loaded.getSource(LAND_SOURCE) != null
                && loaded.getSource(CLAIM_SOURCE) != null
                && loaded.getLayer(LAND_FILL) != null
                && loaded.getLayer(LAND_OUTLINE) != null
                && loaded.getLayer(CLAIM_FILL) != null
                && loaded.getLayer(CLAIM_OUTLINE) != null;
    }

    private void failOfflineStyle(String reason) {
        if (fallbackLoading) return;
        attemptingOfflineStyle = false;
        offlineStyleActive = false;
        verifiedStyleActive = false;
        landStatusAvailable = false;
        claimsAvailable = false;
        style = null;

        if (!rollbackAttempted && dataManager.revertToPreviousManifest(reason)) {
            rollbackAttempted = true;
            loadActiveOrSafe();
            return;
        }
        loadSafeStyle("OFFLINE MAP FAILED SAFETY CHECK — DO NOT USE THIS SCREEN FOR NAVIGATION.\n" + reason
                + "\n" + dataManager.describeStatus());
    }

    private void loadSafeStyle(String reason) {
        if (map == null || fallbackLoading) return;
        fallbackLoading = true;
        attemptingOfflineStyle = false;
        offlineStyleActive = false;
        verifiedStyleActive = false;
        landStatusAvailable = false;
        claimsAvailable = false;
        map.setStyle(new Style.Builder().fromUri("asset://rockmap_safe_style.json"), loaded -> {
            style = loaded;
            fallbackLoading = false;
            installLocalOverlayLayers();
            renderCurrentLocation();
            renderWaypoints();
            applyVisibility();
            listener.onMapSafetyState(false, reason);
        });
    }

    public String describeLabelDiagnostics() {
        if (!offlineStyleActive || style == null || map == null) {
            return "Label diagnostics: offline style is not active.";
        }

        int namedSourceFeatures = 0;
        VectorSource base = null;
        try {
            base = style.getSourceAs(BASE_SOURCE);
        } catch (RuntimeException ignored) {
            // Diagnostics must never destabilize the field map.
        }
        if (base != null) {
            for (String sourceLayer : new String[]{"places", "roads", "water", "pois"}) {
                try {
                    List<Feature> features = base.querySourceFeatures(new String[]{sourceLayer}, null);
                    for (Feature feature : features) {
                        if (feature != null && (feature.hasProperty("name") || feature.hasProperty("name:en"))) {
                            namedSourceFeatures++;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // Diagnostics must never destabilize the field map.
                }
            }
        }

        int renderedLabels = 0;
        if (mapView.getWidth() > 0 && mapView.getHeight() > 0) {
            try {
                RectF viewport = new RectF(0f, 0f, mapView.getWidth(), mapView.getHeight());
                renderedLabels = map.queryRenderedFeatures(viewport, new String[]{
                        LABEL_LOCALITY, LABEL_ROAD_MAJOR, LABEL_WATER, LABEL_PEAK,
                        LABEL_PLACE_ANY, LABEL_WATER_ANY, LABEL_ROAD_ANY
                }).size();
            } catch (RuntimeException ignored) {
                // Report zero rather than allowing a diagnostics query to crash the app.
            }
        }

        return "Label diagnostics (current viewport):"
                + "\nNamed source features loaded: " + namedSourceFeatures
                + "\nRendered label features: " + renderedLabels
                + "\nPBF fallback requested/loaded/errors: "
                + glyphFallbackRequests + "/" + glyphFallbackLoads + "/" + glyphFallbackErrors;
    }

    public String describeLandDiagnostics() {
        if (!landStatusAvailable || !offlineStyleActive || style == null || map == null) {
            return "Land diagnostics: land-status test is not active.";
        }

        int sourceFeatures = 0;
        int namedFeatures = 0;
        VectorSource land = null;
        try {
            land = style.getSourceAs(LAND_SOURCE);
        } catch (RuntimeException ignored) {
        }
        if (land != null) {
            try {
                List<Feature> features = land.querySourceFeatures(new String[]{"land"}, null);
                sourceFeatures = features.size();
                for (Feature feature : features) {
                    if (feature != null && (feature.hasProperty("manager_code") || feature.hasProperty("manager_name"))) {
                        namedFeatures++;
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        int renderedFeatures = 0;
        if (mapView.getWidth() > 0 && mapView.getHeight() > 0) {
            try {
                RectF viewport = new RectF(0f, 0f, mapView.getWidth(), mapView.getHeight());
                renderedFeatures = map.queryRenderedFeatures(viewport, new String[]{LAND_FILL}).size();
            } catch (RuntimeException ignored) {
            }
        }
        return "Land diagnostics (current viewport):"
                + "\nLand source features loaded: " + sourceFeatures
                + "\nFeatures with normalized manager fields: " + namedFeatures
                + "\nRendered land features: " + renderedFeatures;
    }

    public String describeClaimsDiagnostics() {
        if (!claimsAvailable || !offlineStyleActive || style == null || map == null) {
            return "Claims diagnostics: mining-claims test is not active.";
        }

        int sourceFeatures = 0;
        int normalizedFeatures = 0;
        VectorSource claims = null;
        try {
            claims = style.getSourceAs(CLAIM_SOURCE);
        } catch (RuntimeException ignored) {
        }
        if (claims != null) {
            try {
                List<Feature> features = claims.querySourceFeatures(new String[]{"claims"}, null);
                sourceFeatures = features.size();
                for (Feature feature : features) {
                    if (feature != null
                            && feature.hasProperty("serial")
                            && feature.hasProperty("type")
                            && feature.hasProperty("disposition")
                            && feature.hasProperty("quality_description")) {
                        normalizedFeatures++;
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }

        int renderedFeatures = 0;
        if (mapView.getWidth() > 0 && mapView.getHeight() > 0) {
            try {
                RectF viewport = new RectF(0f, 0f, mapView.getWidth(), mapView.getHeight());
                renderedFeatures = map.queryRenderedFeatures(viewport, new String[]{CLAIM_FILL}).size();
            } catch (RuntimeException ignored) {
            }
        }
        return "Claims diagnostics (current viewport):"
                + "\nClaim source features loaded: " + sourceFeatures
                + "\nFeatures with normalized claim fields: " + normalizedFeatures
                + "\nRendered claim features: " + renderedFeatures;
    }

    public void updateCurrentLocation(Location location) {
        currentLocation = location;
        renderCurrentLocation();
    }

    public void updateCurrentHeading(Float headingDegrees) {
        currentHeadingDegrees = headingDegrees == null ? null : normalizeHeading(headingDegrees);
        renderCurrentHeading();
    }

    public void setWaypoints(List<WaypointEntity> items) {
        waypoints = items == null ? new ArrayList<>() : new ArrayList<>(items);
        renderWaypoints();
    }

    public void centerOn(Location location) {
        if (map == null || location == null) return;
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(location.getLatitude(), location.getLongitude()), 16.0));
    }

    public void centerOn(WaypointEntity waypoint) {
        if (map == null || waypoint == null) return;
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                new LatLng(waypoint.latitude, waypoint.longitude), 16.0));
    }

    public void setLandVisible(boolean visible) {
        landVisible = landStatusAvailable && visible;
        applyVisibility();
    }

    public void setClaimsVisible(boolean visible) {
        claimsVisible = claimsAvailable && visible;
        applyVisibility();
    }

    public void setWaypointsVisible(boolean visible) {
        waypointsVisible = visible;
        applyVisibility();
    }

    public boolean isLandVisible() { return landStatusAvailable && landVisible; }
    public boolean isClaimsVisible() { return claimsAvailable && claimsVisible; }
    public boolean isWaypointsVisible() { return waypointsVisible; }
    public boolean isVerifiedStyleActive() { return verifiedStyleActive; }
    public boolean hasLandStatusAvailable() { return landStatusAvailable; }
    public boolean hasClaimsAvailable() { return claimsAvailable; }
    public boolean hasLandClaimsAvailable() { return landStatusAvailable && claimsAvailable; }

    private void installLocalOverlayLayers() {
        if (style == null) return;
        if (style.getSource(CURRENT_SOURCE) == null) style.addSource(new GeoJsonSource(CURRENT_SOURCE, emptyCollection()));
        if (style.getLayer(CURRENT_LAYER) == null) {
            CircleLayer current = new CircleLayer(CURRENT_LAYER, CURRENT_SOURCE);
            current.setProperties(
                    circleColor(Color.rgb(20, 90, 230)),
                    circleRadius(8f),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(3f));
            style.addLayer(current);
        }
        if (style.getImage(CURRENT_HEADING_ICON) == null) {
            style.addImage(CURRENT_HEADING_ICON, createCurrentHeadingIcon());
        }
        if (style.getLayer(CURRENT_HEADING_LAYER) == null) {
            SymbolLayer heading = new SymbolLayer(CURRENT_HEADING_LAYER, CURRENT_SOURCE);
            heading.setProperties(
                    iconImage(CURRENT_HEADING_ICON),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),
                    iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    iconRotate(currentHeadingDegrees == null ? 0f : currentHeadingDegrees),
                    visibility(currentHeadingDegrees == null ? NONE : VISIBLE));
            style.addLayerBelow(heading, CURRENT_LAYER);
        }
        renderCurrentHeading();
        // Keep a nearly invisible land-status hit-test layer independent of the visual land toggle.
        // It lets a mineral marker report mapped management at its coordinate even when the user
        // has hidden the colored land layer. This remains management/status mapping, not parcel data.
        if (landStatusAvailable && style.getSource(LAND_SOURCE) != null && style.getLayer(LAND_QUERY_LAYER) == null) {
            FillLayer landHitTest = new FillLayer(LAND_QUERY_LAYER, LAND_SOURCE);
            landHitTest.setSourceLayer("land");
            landHitTest.setMinZoom(5f);
            landHitTest.setProperties(
                    fillColor(Color.WHITE),
                    fillOpacity(0.001f));
            style.addLayer(landHitTest);
        }

        if (style.getSource(WAYPOINT_SOURCE) == null) style.addSource(new GeoJsonSource(WAYPOINT_SOURCE, emptyCollection()));
        if (style.getLayer(WAYPOINT_LAYER) == null) {
            CircleLayer saved = new CircleLayer(WAYPOINT_LAYER, WAYPOINT_SOURCE);
            saved.setProperties(
                    circleColor(Color.rgb(215, 80, 20)),
                    circleRadius(7f),
                    circleStrokeColor(Color.WHITE),
                    circleStrokeWidth(2f));
            style.addLayer(saved);
        }
    }

    private void renderCurrentHeading() {
        if (style == null) return;
        Layer layer = style.getLayer(CURRENT_HEADING_LAYER);
        if (!(layer instanceof SymbolLayer)) return;
        SymbolLayer heading = (SymbolLayer) layer;
        if (currentHeadingDegrees == null) {
            heading.setProperties(visibility(NONE));
            return;
        }
        heading.setProperties(
                iconRotate(currentHeadingDegrees),
                visibility(VISIBLE));
    }

    private Bitmap createCurrentHeadingIcon() {
        final int size = 48;
        final float center = size / 2f;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Path arrow = new Path();
        arrow.moveTo(center, 2f);
        arrow.lineTo(center + 11f, center + 1f);
        arrow.lineTo(center - 11f, center + 1f);
        arrow.close();

        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeJoin(Paint.Join.ROUND);
        outline.setStrokeWidth(4f);
        outline.setColor(Color.WHITE);
        canvas.drawPath(arrow, outline);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Color.rgb(20, 90, 230));
        canvas.drawPath(arrow, fill);
        return bitmap;
    }

    private static float normalizeHeading(float headingDegrees) {
        float normalized = headingDegrees % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

    private void renderCurrentLocation() {
        if (style == null) return;
        GeoJsonSource source = style.getSourceAs(CURRENT_SOURCE);
        if (source == null) return;
        if (currentLocation == null) {
            source.setGeoJson(emptyCollection());
            return;
        }
        try {
            JSONObject geometry = new JSONObject();
            geometry.put("type", "Point");
            JSONArray coords = new JSONArray();
            coords.put(currentLocation.getLongitude());
            coords.put(currentLocation.getLatitude());
            geometry.put("coordinates", coords);
            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            feature.put("geometry", geometry);
            JSONObject props = new JSONObject();
            props.put("accuracy_m", currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : JSONObject.NULL);
            feature.put("properties", props);
            JSONObject collection = new JSONObject();
            collection.put("type", "FeatureCollection");
            collection.put("features", new JSONArray().put(feature));
            source.setGeoJson(collection.toString());
        } catch (JSONException ignored) {
        }
    }

    private void renderWaypoints() {
        if (style == null) return;
        GeoJsonSource source = style.getSourceAs(WAYPOINT_SOURCE);
        if (source == null) return;
        try {
            JSONArray features = new JSONArray();
            for (WaypointEntity waypoint : waypoints) {
                JSONObject geometry = new JSONObject();
                geometry.put("type", "Point");
                geometry.put("coordinates", new JSONArray().put(waypoint.longitude).put(waypoint.latitude));
                JSONObject props = new JSONObject();
                props.put("id", waypoint.id);
                props.put("name", waypoint.name);
                props.put("accuracy_m", waypoint.accuracyMeters);
                JSONObject feature = new JSONObject();
                feature.put("type", "Feature");
                feature.put("geometry", geometry);
                feature.put("properties", props);
                features.put(feature);
            }
            JSONObject collection = new JSONObject();
            collection.put("type", "FeatureCollection");
            collection.put("features", features);
            source.setGeoJson(collection.toString());
        } catch (JSONException ignored) {
        }
    }

    private String emptyCollection() {
        return "{\"type\":\"FeatureCollection\",\"features\":[]}";
    }

    private boolean handleTap(LatLng coordinate) {
        if (map == null || style == null) return false;
        PointF point = map.getProjection().toScreenLocation(coordinate);

        // Saved red markers are persistent user objects and take precedence over temporary
        // mineral-search dots beneath them. Resolve the rendered waypoint back to its Room row
        // so mineral-sourced waypoints can reopen the complete saved provenance/detail text.
        if (waypointsVisible) {
            WaypointEntity waypoint = findRenderedWaypoint(point);
            if (waypoint != null && listener.onWaypointTapped(waypoint)) return true;
        }

        List<Feature> overlayLand = landStatusAvailable
                ? dedupe(query(point, LAND_QUERY_LAYER), "manager_name", "manager_code") : new ArrayList<>();
        if (listener.onMapOverlayTapped(coordinate, overlayLand)) return true;
        if (!landStatusAvailable && !claimsAvailable) return false;
        List<Feature> land = (landStatusAvailable && landVisible)
                ? dedupe(query(point, LAND_FILL), "manager_name", "manager_code") : new ArrayList<>();
        List<Feature> claims = (claimsAvailable && claimsVisible)
                ? dedupe(query(point, CLAIM_FILL), "serial", "name") : new ArrayList<>();
        // Empty rendered results are never interpreted as public/unclaimed land. Alpha 5
        // adds claims for testing while the entire snapshot remains explicitly unverified.
        listener.onMapFeaturesTapped(coordinate, land, claims);
        return true;
    }

    private WaypointEntity findRenderedWaypoint(PointF point) {
        List<Feature> features = query(point, WAYPOINT_LAYER);
        for (Feature feature : features) {
            if (feature == null || !feature.hasProperty("id")) continue;
            try {
                Number number = feature.getNumberProperty("id");
                if (number == null) continue;
                long id = number.longValue();
                for (WaypointEntity waypoint : waypoints) {
                    if (waypoint != null && waypoint.id == id) return waypoint;
                }
            } catch (RuntimeException ignored) {
                // A malformed overlay feature must never destabilize map taps.
            }
        }
        return null;
    }

    private List<Feature> query(PointF point, String layerId) {
        if (style == null || style.getLayer(layerId) == null) return new ArrayList<>();
        return map.queryRenderedFeatures(point, new String[]{layerId});
    }

    private List<Feature> dedupe(List<Feature> input, String primaryProperty, String fallbackProperty) {
        Map<String, Feature> unique = new LinkedHashMap<>();
        int anonymous = 0;
        for (Feature feature : input) {
            String key = null;
            if (feature != null && feature.hasProperty(primaryProperty)) {
                key = feature.getStringProperty(primaryProperty);
            }
            if ((key == null || key.trim().isEmpty()) && feature != null && feature.hasProperty(fallbackProperty)) {
                key = feature.getStringProperty(fallbackProperty);
            }
            if (key == null || key.trim().isEmpty()) key = "__feature_" + (anonymous++);
            if (!unique.containsKey(key)) unique.put(key, feature);
        }
        return new ArrayList<>(unique.values());
    }

    private void applyVisibility() {
        if (style == null) return;
        setLayerVisible(LAND_FILL, landVisible);
        setLayerVisible(LAND_OUTLINE, landVisible);
        setLayerVisible(CLAIM_FILL, claimsVisible);
        setLayerVisible(CLAIM_OUTLINE, claimsVisible);
        setLayerVisible(WAYPOINT_LAYER, waypointsVisible);
    }

    private void setLayerVisible(String id, boolean visible) {
        Layer layer = style.getLayer(id);
        if (layer != null) layer.setProperties(visibility(visible ? VISIBLE : NONE));
    }
}
