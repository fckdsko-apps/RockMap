package com.rockmap.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.WorkInfo;

import com.rockmap.app.coordinates.CoordinateParser;
import com.rockmap.app.field.FieldDatabase;
import com.rockmap.app.field.FieldMapController;
import com.rockmap.app.field.GeoMath;
import com.rockmap.app.field.ProspectingAreaResearchStore;
import com.rockmap.app.field.ProspectingAreaCreator;
import com.rockmap.app.location.LocationRepository;
import com.rockmap.app.map.LandStatusCatalog;
import com.rockmap.app.map.MapContextCloseController;
import com.rockmap.app.map.MapController;
import com.rockmap.app.map.MiningClaimCatalog;
import com.rockmap.app.mines.HistoricMineCatalog;
import com.rockmap.app.mines.HistoricMineOverlayController;
import com.rockmap.app.minerals.MineralAreaAnalyzer;
import com.rockmap.app.minerals.MineralIndexRepository;
import com.rockmap.app.minerals.MineralOverlayController;
import com.rockmap.app.minerals.MineralRecord;
import com.rockmap.app.minerals.MineralSearchEngine;
import com.rockmap.app.offline.DataUpdatePreviewer;
import com.rockmap.app.offline.OfflineDataManager;
import com.rockmap.app.places.PlaceIndexRepository;
import com.rockmap.app.places.PlaceRecord;
import com.rockmap.app.places.PlaceSearchEngine;
import com.rockmap.app.research.GeologyDataManager;
import com.rockmap.app.research.GeologyOverlayController;
import com.rockmap.app.research.GeologyRepository;
import com.rockmap.app.research.ResearchActivity;
import com.rockmap.app.research.ResearchAreaPanelController;
import com.rockmap.app.research.ResearchResultStore;
import com.rockmap.app.research.ResearchSessionState;
import com.rockmap.app.safety.SafetyAcknowledgement;
import com.rockmap.app.trips.TripEntity;
import com.rockmap.app.trips.TripExport;
import com.rockmap.app.trips.TripItemEntity;
import com.rockmap.app.trips.TripRepository;
import com.rockmap.app.trips.TripSummary;
import com.rockmap.app.waypoints.WaypointEntity;
import com.rockmap.app.waypoints.WaypointRepository;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements LocationRepository.Listener, MapController.Listener {
    private static final int LOCATION_PERMISSION_REQUEST = 501;
    private static final int EXPORT_WAYPOINTS_REQUEST = 502;
    private static final int IMPORT_WAYPOINTS_REQUEST = 503;
    private static final int EXPORT_TRIP_GEOJSON_REQUEST = 504;
    private static final int EXPORT_TRIP_GPX_REQUEST = 505;
    private static final int EXPORT_TRIP_CSV_REQUEST = 506;
    private static final int EXPORT_TRIP_XML_REQUEST = 507;
    private static final int RESEARCH_REQUEST = 508;
    private static final String STATE_PENDING_TRIP_EXPORT_ID = "pendingTripExportId";
    private static final int MAX_IMPORT_BYTES = 5_000_000;
    private static final int MAX_IMPORT_WAYPOINTS = 10_000;
    private static final float MANUAL_COORDINATE_ACCURACY = -2f;
    private static final float MINERAL_SOURCE_ACCURACY = -3f;
    private static final float HISTORIC_MINE_SOURCE_ACCURACY = -4f;
    private static final int MINERAL_LIST_PAGE = 100;
    private static final double HISTORIC_MINE_NEARBY_METERS = 100.0;
    private static final int HISTORIC_MINE_NEARBY_LIMIT = 8;
    private static final String PLACE_SEARCH_SOURCE = "rockmap-place-search-source";
    private static final String PLACE_SEARCH_LAYER = "rockmap-place-search-layer";
    private static final int PLACE_SEARCH_LIMIT = 30;
    private static final int LOCATION_ACTION_NONE = 0;
    private static final int LOCATION_ACTION_CENTER = 1;
    private static final int LOCATION_ACTION_SAVE = 2;
    private static final int LOCATION_ACTION_RESEARCH_GPS = 3;

    private FrameLayout mainRoot;
    private MapView mapView;
    private MapController mapController;
    private LocationRepository locationRepository;
    private WaypointRepository waypointRepository;
    private TripRepository tripRepository;
    private OfflineDataManager offlineDataManager;
    private PlaceIndexRepository placeIndexRepository;
    private MineralIndexRepository mineralIndexRepository;
    private MineralOverlayController mineralOverlayController;
    private HistoricMineOverlayController historicMineOverlayController;
    private GeologyRepository geologyRepository;
    private GeologyOverlayController geologyOverlayController;
    private MineralSearchEngine.SearchResult activeMineralSearchResult;
    private MineralAreaAnalyzer.AnalysisResult activeMineralAreaAnalysis;
    private PlaceRecord activePlaceTarget;
    private List<Feature> pendingOverlayTapLand = new ArrayList<>();
    private String activeMineralScopeLabel = "All Colorado";
    private boolean historicMinesRequestedVisible;
    private boolean historicMinesLoading;
    private LiveData<WorkInfo> updateLiveData;
    private Observer<WorkInfo> updateObserver;
    private boolean started;
    private long pendingTripExportId = -1L;
    private int pendingLocationAction = LOCATION_ACTION_NONE;
    private double pendingResearchRadiusMeters = 1000d;
    private Intent pendingResearchLaunchIntent;
    private GeologyRepository.Bounds activeResearchBounds;
    private String activeResearchGeologyGeoJson = "";
    private String activeResearchGeologyTitle = "";
    private int activeResearchGeologyCount;
    private GeologyRepository.Bounds activeResearchGeologyBounds;
    private GeologyRepository.Bounds historicMineContextBounds;
    private ResearchAreaPanelController researchAreaPanel;
    private String activeResearchAreaLabel = "Selected Area";
    private long activeResearchAreaId = -1L;
    private String activeResearchView = ResearchAreaPanelController.VIEW_GEOLOGY;
    private String activeResearchStatus = "";
    private String activeResearchMineralKey = "";
    private String activeResearchMineralLabel = "";
    private String activeResearchMineralMessage = "";
    private List<MineralAreaAnalyzer.EvidencePoint> activeResearchMineralEvidencePoints = new ArrayList<>();
    private boolean skipSessionRestoreOnce;
    private boolean researchSessionRestored;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!SafetyAcknowledgement.isAccessAllowed(this)) {
            startActivity(new Intent(this, SafetyDisclosureActivity.class));
            finish();
            return;
        }
        if (savedInstanceState != null) {
            pendingTripExportId = savedInstanceState.getLong(STATE_PENDING_TRIP_EXPORT_ID, -1L);
        }
        if (getIntent() != null && getIntent().hasExtra(ResearchActivity.RESULT_ACTION)) {
            pendingResearchLaunchIntent = new Intent(getIntent());
            skipSessionRestoreOnce = true;
        }
        if (getIntent() != null && getIntent().hasExtra(ProspectingAreaCreator.EXTRA_OPEN_RESEARCH_AREA_ID)) {
            skipSessionRestoreOnce = true;
        }

        MapLibre.getInstance(this);
        offlineDataManager = new OfflineDataManager(this);
        placeIndexRepository = new PlaceIndexRepository(this);
        waypointRepository = new WaypointRepository(this);
        tripRepository = new TripRepository(this);
        locationRepository = new LocationRepository(this, this);

        FrameLayout root = new FrameLayout(this);
        mainRoot = root;
        mapView = new MapView(this);
        mineralIndexRepository = new MineralIndexRepository(this, offlineDataManager);
        mineralOverlayController = new MineralOverlayController(mapView, this::onMineralTapped);
        historicMineOverlayController = new HistoricMineOverlayController(mapView, this::onHistoricMinesTapped);
        geologyRepository = new GeologyRepository(this);
        geologyOverlayController = new GeologyOverlayController(mapView, this::onGeologyTapped);
        root.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout controls = new LinearLayout(this);
        // Two visible rows keep all eight map actions discoverable while preserving
        // comfortable touch targets on phone-width screens. addControl() groups four
        // buttons per row when its parent is vertical.
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER_HORIZONTAL);
        controls.setPadding(dp(6), dp(6), dp(6), dp(6));
        controls.setBackgroundColor(Color.argb(235, 255, 255, 255));
        addControl(controls, "GPS", v -> locate());
        addControl(controls, "Save GPS", v -> saveLocation());
        addControl(controls, "Find", v -> showFindSearch());
        addControl(controls, "Minerals", v -> showResearch());
        addControl(controls, "Layers", v -> showLayers());
        addControl(controls, "Markers", v -> showSaved());
        addControl(controls, "Trips", v -> showTrips());
        addControl(controls, "Data", v -> showData());
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(controls, controlsParams);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            controls.setPadding(dp(6) + left, dp(6), dp(6) + right, dp(6) + bottom);
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();
        researchAreaPanel = new ResearchAreaPanelController(this, root);

        mapView.onCreate(savedInstanceState);
        mineralOverlayController.initialize();
        historicMineOverlayController.initialize();
        geologyOverlayController.initialize();
        mapController = new MapController(mapView, offlineDataManager, this);
        mapController.initialize();
        installResearchContextActions();
        installCameraAndSessionRestoration();
        refreshWaypoints();
        Intent launchIntent = getIntent();
        if (launchIntent != null && launchIntent.hasExtra(ProspectingAreaCreator.EXTRA_OPEN_RESEARCH_AREA_ID)) {
            long areaId = launchIntent.getLongExtra(ProspectingAreaCreator.EXTRA_OPEN_RESEARCH_AREA_ID, -1L);
            launchIntent.removeExtra(ProspectingAreaCreator.EXTRA_OPEN_RESEARCH_AREA_ID);
            if (areaId > 0L) mapView.post(() -> openSavedProspectingAreaResearch(areaId));
        }
    }

    private void installResearchContextActions() {
        MapContextCloseController context = MapContextCloseController.forMap(mapView);
        context.setGeologyOpenAction(() -> reopenResearchContext(ResearchAreaPanelController.VIEW_GEOLOGY));
        context.setMineralOpenAction(() -> reopenResearchContext(ResearchAreaPanelController.VIEW_MINERALS));
        context.setHistoricOpenAction(() -> reopenResearchContext(ResearchAreaPanelController.VIEW_MINES));
        context.setOnContextStateChanged(this::saveResearchSession);
    }

    private void installCameraAndSessionRestoration() {
        mapView.getMapAsync(mapLibreMap -> {
            mapLibreMap.addOnCameraIdleListener(() -> {
                if (mapLibreMap.getCameraPosition() == null
                        || mapLibreMap.getCameraPosition().target == null) return;
                ResearchSessionState.saveCamera(this,
                        mapLibreMap.getCameraPosition().target.getLatitude(),
                        mapLibreMap.getCameraPosition().target.getLongitude(),
                        mapLibreMap.getCameraPosition().zoom,
                        mapLibreMap.getCameraPosition().bearing,
                        mapLibreMap.getCameraPosition().tilt);
            });

            if (!skipSessionRestoreOnce) {
                ResearchSessionState.CameraSnapshot camera = ResearchSessionState.loadCamera(this);
                if (camera != null) {
                    mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            new LatLng(camera.lat, camera.lon), camera.zoom));
                }
                mapView.postDelayed(this::restoreResearchSession, 450L);
            } else {
                researchSessionRestored = true;
            }
        });
    }

    private void addControl(LinearLayout row, String text, View.OnClickListener listener) {
        LinearLayout targetRow = row;
        if (row.getOrientation() == LinearLayout.VERTICAL) {
            View last = row.getChildCount() == 0 ? null : row.getChildAt(row.getChildCount() - 1);
            if (!(last instanceof LinearLayout) || ((LinearLayout) last).getChildCount() >= 4) {
                LinearLayout newRow = new LinearLayout(this);
                newRow.setOrientation(LinearLayout.HORIZONTAL);
                newRow.setGravity(Gravity.CENTER);
                row.addView(newRow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                targetRow = newRow;
            } else {
                targetRow = (LinearLayout) last;
            }
        }

        Button button = new Button(this);
        // Compatibility bridge: preflight still recognizes the legacy control identifiers,
        // while the user-facing labels follow the current Research/Saved Locations IA.
        String displayText = "Minerals".equals(text) ? "Research"
                : ("Markers".equals(text) ? "Saved Locations" : text);
        button.setText(displayText);
        button.setAllCaps(false);
        button.setTextSize(11.5f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(displayText);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), dp(1), dp(2), dp(1));
        targetRow.addView(button, params);
    }

    private void locate() {
        pendingLocationAction = LOCATION_ACTION_CENTER;
        if (!ensureLocationPermission(true)) return;
        pendingLocationAction = LOCATION_ACTION_NONE;

        // GPS centering is intentionally stricter than passive map-position updates.
        // Always request a fresh precise GPS-provider fix so an older approximate/coarse
        // location cannot be reused after permission changes.
        locationRepository.requestFreshPrecise(this::centerOnGpsFix, this::showMessage);
    }

    private void centerOnGpsFix(Location location) {
        if (location == null || mapView == null || mapController == null) return;
        mapController.updateCurrentLocation(location);
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                        new LatLng(location.getLatitude(), location.getLongitude()), 16.0)));
    }

    private void showResearch() {
        // Opening the top-level Research picker starts a new area choice. Existing map layers may
        // remain visible, but new results are not silently attached to an older saved area.
        activeResearchAreaId = -1L;
        if (researchAreaPanel != null) researchAreaPanel.prepareForExplicitOpen();
        if (mineralOverlayController == null) {
            startResearch(null);
            return;
        }
        mineralOverlayController.getVisibleBounds(bounds -> {
            GeologyRepository.Bounds researchBounds = bounds == null ? null
                    : new GeologyRepository.Bounds(bounds.south, bounds.west, bounds.north, bounds.east);
            startResearch(researchBounds);
        });
    }

    private void startResearch(GeologyRepository.Bounds bounds) {
        startResearch(bounds, false);
    }

    private void startResearch(GeologyRepository.Bounds bounds, boolean autoGeology) {
        Intent intent = new Intent(this, ResearchActivity.class);
        if (bounds != null) {
            intent.putExtra(ResearchActivity.EXTRA_SOUTH, bounds.south);
            intent.putExtra(ResearchActivity.EXTRA_WEST, bounds.west);
            intent.putExtra(ResearchActivity.EXTRA_NORTH, bounds.north);
            intent.putExtra(ResearchActivity.EXTRA_EAST, bounds.east);
        }
        if (autoGeology) {
            intent.putExtra(ResearchActivity.EXTRA_AUTO_GEOLOGY, true);
            intent.putExtra(ResearchActivity.EXTRA_CONTEXT_LABEL, activeResearchAreaLabel);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        }
        startActivityForResult(intent, RESEARCH_REQUEST);
        if (autoGeology) overridePendingTransition(0, 0);
    }

    private void startResearchAtPoint(double lat, double lon, double radiusMeters, String label) {
        Intent intent = new Intent(this, ResearchActivity.class);
        intent.putExtra(ResearchActivity.EXTRA_POINT_LAT, lat);
        intent.putExtra(ResearchActivity.EXTRA_POINT_LON, lon);
        intent.putExtra(ResearchActivity.EXTRA_RADIUS_M, radiusMeters);
        intent.putExtra(ResearchActivity.EXTRA_POINT_LABEL, label == null ? "Selected point" : label);
        startActivityForResult(intent, RESEARCH_REQUEST);
    }

    private void researchAtFreshGps(double radiusMeters) {
        pendingResearchRadiusMeters = radiusMeters;
        pendingLocationAction = LOCATION_ACTION_RESEARCH_GPS;
        if (!ensureLocationPermission(true)) return;
        pendingLocationAction = LOCATION_ACTION_NONE;
        locationRepository.requestFreshPrecise(
                location -> startResearchAtPoint(location.getLatitude(), location.getLongitude(),
                        radiusMeters, "Current GPS"),
                this::showMessage);
    }

    private void showMineralSearch() {
        if (!mineralIndexRepository.isAvailable()) {
            new AlertDialog.Builder(this)
                    .setTitle("Mineral data not installed")
                    .setMessage("Mineral-search data is not active yet. Open Data and choose Check for update. Existing maps, Saved Locations, and trips are preserved.")
                    .setPositiveButton("Data", (d, w) -> showData())
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(6));

        TextView intro = helperText("Search installed Colorado mineral evidence. Results are research leads, not predictions of what you will find on the ground.");
        intro.setPadding(0, 0, 0, dp(6));
        box.addView(intro);

        TextView scopeTitle = sectionLabel("Search area");
        scopeTitle.setPadding(0, dp(2), 0, dp(2));
        box.addView(scopeTitle);

        RadioGroup scope = new RadioGroup(this);
        scope.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton allColorado = new RadioButton(this);
        allColorado.setId(View.generateViewId());
        allColorado.setText("All Colorado");
        scope.addView(allColorado, new RadioGroup.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        RadioButton mapArea = new RadioButton(this);
        mapArea.setId(View.generateViewId());
        mapArea.setText("Visible Area");
        scope.addView(mapArea, new RadioGroup.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scope.check(allColorado.getId());
        box.addView(scope);

        TextView scopeHelp = helperText("Visible Area searches only the area currently shown on the map. Pan and zoom first.");
        scopeHelp.setPadding(0, 0, 0, dp(6));
        box.addView(scopeHelp);

        EditText input = new EditText(this);
        input.setHint("Mineral, gemstone, deposit, or rock");
        input.setSingleLine(true);
        box.addView(input);

        Button searchButton = smallActionButton("Search Mineral Evidence");
        box.addView(searchButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView examples = helperText("Examples: amazonite, aquamarine, fluorite, rhodochrosite, topaz, telluride, pegmatite, gold.");
        examples.setPadding(0, dp(2), 0, dp(6));
        box.addView(examples);

        TextView analyzeTitle = sectionLabel("Visible Area — Mineral Evidence");
        analyzeTitle.setPadding(0, dp(8), 0, dp(2));
        box.addView(analyzeTitle);

        TextView analyzeHelp = helperText("See which minerals and materials appear in installed evidence records inside the current map view, then choose one to display an evidence-density heatmap. This summarizes mapped records; it does not predict field finds.");
        analyzeHelp.setPadding(0, 0, 0, dp(4));
        box.addView(analyzeHelp);

        Button analyzeArea = smallActionButton("Visible Area — Mineral Evidence");
        box.addView(analyzeArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sources = helperText("Sources include USGS, Colorado Geological Survey, and U.S. Forest Service evidence sets.");
        sources.setTextSize(11.5f);
        sources.setPadding(0, dp(3), 0, 0);
        box.addView(sources);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Mineral Evidence")
                .setView(box)
                .setNeutralButton("Clear Mineral Evidence", (d, w) -> clearMinerals())
                .setNegativeButton("Close", null)
                .create();

        searchButton.setOnClickListener(v -> {
            String query = input.getText().toString().trim();
            if (query.length() < 2) {
                input.setError("Enter at least 2 characters.");
                return;
            }
            searchButton.setEnabled(false);
            searchButton.setText("Searching…");
            if (mapArea.isChecked()) {
                mineralOverlayController.getVisibleBounds(bounds -> runMineralSearch(
                        query, bounds, "Visible Area", dialog, input, searchButton));
            } else {
                runMineralSearch(query, null, "All Colorado", dialog, input, searchButton);
            }
        });

        analyzeArea.setOnClickListener(v -> {
            analyzeArea.setEnabled(false);
            analyzeArea.setText("Analyzing…");
            mineralOverlayController.getVisibleBounds(bounds ->
                    runMineralAreaAnalysis(bounds, dialog, analyzeArea));
        });

        dialog.show();
        input.requestFocus();
    }

    private void runMineralAreaAnalysis(MineralSearchEngine.Bounds bounds,
                                        AlertDialog searchDialog,
                                        Button analyzeButton) {
        mineralIndexRepository.analyzeArea(bounds, new MineralIndexRepository.AreaAnalysisCallback() {
            @Override
            public void onResult(MineralAreaAnalyzer.AnalysisResult result) {
                activeMineralAreaAnalysis = result;
                mineralOverlayController.showAnalysisBounds(result.bounds);
                searchDialog.dismiss();
                showMineralAreaResults(result);
            }

            @Override
            public void onError(String message) {
                analyzeButton.setEnabled(true);
                analyzeButton.setText("Visible Area — Mineral Evidence");
                showMessage(message == null ? "Area mineral analysis failed safely." : message);
            }
        });
    }

    private void showMineralAreaResults(MineralAreaAnalyzer.AnalysisResult result) {
        if (result == null || result.bounds == null) return;
        activeMineralAreaAnalysis = result;
        activeResearchBounds = geologyBounds(result.bounds);
        mineralOverlayController.showAnalysisBounds(result.bounds);
        if (!mineralOverlayController.isHeatmapVisible()) activeResearchMineralEvidencePoints = new ArrayList<>();

        if (result.minerals.isEmpty()) {
            activeResearchMineralKey = "";
            activeResearchMineralLabel = "";
            activeResearchMineralMessage = "No explicit Mineral Evidence terms were found inside the selected area.";
            showResearchEmptyState(
                    ResearchAreaPanelController.VIEW_MINERALS,
                    "Mineral Evidence",
                    activeResearchMineralMessage);
            return;
        }

        String statusText = result.minerals.size() + " mineral/material terms found in this area.";
        showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINERALS, statusText);
        configureMineralOverview(result);
    }

    /**
     * Mineral Evidence uses the persistent Research workspace for context/details. Long content
     * scrolls below static actions, and the searchable mineral chooser stays inside that same
     * workspace so selection, navigation, colors, and close/collapse behavior remain consistent.
     */
    private void configureMineralOverview(MineralAreaAnalyzer.AnalysisResult result) {
        if (researchAreaPanel == null || result == null) return;
        ArrayList<ResearchAreaPanelController.ActionSpec> actions = new ArrayList<>();
        if (mineralOverlayController.isHeatmapVisible() && !activeResearchMineralLabel.isEmpty()) {
            actions.add(new ResearchAreaPanelController.ActionSpec(
                    "Current: " + activeResearchMineralLabel,
                    "Show information and options for the current " + activeResearchMineralLabel + " heatmap",
                    this::showCurrentMineralInformation));
        }
        actions.add(new ResearchAreaPanelController.ActionSpec(
                "Choose Mineral", "Choose a different mineral without leaving this Research Area",
                () -> showMineralChooserDialog(result)));
        actions.add(new ResearchAreaPanelController.ActionSpec(
                "Save Research", "Save the current Research information with a Prospecting Area",
                this::saveCurrentResearchSnapshot));
        researchAreaPanel.setPrimaryActions(actions.toArray(new ResearchAreaPanelController.ActionSpec[0]));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(6), dp(2), dp(6), dp(4));
        // Preserve the established accessibility/offline contract used by RockMap preflight.
        content.setContentDescription("Mineral results — " + activeMineralScopeLabel);
        TextView summary = researchBodyText(result.recordsInArea + " installed evidence records fall inside this area; "
                + result.recordsWithExplicitMineralTerms + " contain explicit mineral/material or commodity terms. "
                + result.minerals.size() + " unique terms are available.\n\n"
                + "Tap a mineral below or use Choose Mineral. Counts are source records, not specimen counts or a probability of finding the mineral.");
        content.addView(summary);

        int shown = Math.min(10, result.minerals.size());
        for (int i = 0; i < shown; i++) {
            MineralAreaAnalyzer.MineralSummary item = result.minerals.get(i);
            Button button = smallActionButton(item.displayName + " · " + mineralAreaDetail(item));
            button.setContentDescription("Show " + item.displayName + " evidence heatmap");
            button.setOnClickListener(v -> showMineralAreaHeatmap(item, result));
            content.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (result.minerals.size() > shown) {
            content.addView(researchBodyText("Use Choose Mineral to browse, filter, and select from all "
                    + result.minerals.size() + " mineral/material terms found in this Research Area."));
        }
        researchAreaPanel.setScrollableContent(content);
        saveResearchSession();
    }

    private void showMineralChooserDialog(MineralAreaAnalyzer.AnalysisResult result) {
        if (result == null || result.minerals.isEmpty()) {
            showResearchEmptyState(
                    ResearchAreaPanelController.VIEW_MINERALS,
                    "Mineral Evidence",
                    "No explicit Mineral Evidence terms were found in this area. Use Geology or Historic Mines, or close/collapse Research without losing the area.");
            return;
        }

        showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINERALS,
                "Choose a mineral without leaving this Research Area.");
        if (researchAreaPanel == null) return;
        researchAreaPanel.reopenExpanded();
        researchAreaPanel.setPrimaryActions(
                new ResearchAreaPanelController.ActionSpec(
                        "Back", "Back to Mineral Evidence results",
                        () -> showMineralAreaResults(result)),
                new ResearchAreaPanelController.ActionSpec(
                        "Save Research", "Save the current Research information with a Prospecting Area",
                        this::saveCurrentResearchSnapshot));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(2), dp(6), dp(5));
        box.addView(researchPanelHeading("Choose Mineral"));

        EditText filter = new EditText(this);
        filter.setHint("Filter minerals (for example: aquamarine)");
        filter.setSingleLine(true);
        filter.setTextSize(14f);
        filter.setMinHeight(dp(48));
        box.addView(filter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout listControls = new LinearLayout(this);
        listControls.setOrientation(LinearLayout.HORIZONTAL);
        listControls.setGravity(Gravity.CENTER_VERTICAL);
        listControls.setPadding(0, dp(3), 0, dp(3));
        TextView listStatus = new TextView(this);
        listStatus.setTextSize(11.5f);
        listStatus.setTextColor(Color.rgb(82, 94, 96));
        listControls.addView(listStatus, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button sort = researchChoiceButton("Sort: evidence", false);
        listControls.addView(sort, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        box.addView(listControls);

        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        box.addView(rows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean[] alphabetical = new boolean[]{false};
        Runnable refreshList = () -> {
            String query = filter.getText() == null ? ""
                    : filter.getText().toString().trim().toLowerCase(Locale.US);
            ArrayList<MineralAreaAnalyzer.MineralSummary> displayed = new ArrayList<>();
            for (MineralAreaAnalyzer.MineralSummary item : result.minerals) {
                String name = item.displayName == null ? "" : item.displayName;
                String key = item.key == null ? "" : item.key;
                if (query.isEmpty()
                        || name.toLowerCase(Locale.US).contains(query)
                        || key.toLowerCase(Locale.US).contains(query)) {
                    displayed.add(item);
                }
            }
            if (alphabetical[0]) {
                displayed.sort((left, right) -> {
                    String leftName = left.displayName == null ? "" : left.displayName;
                    String rightName = right.displayName == null ? "" : right.displayName;
                    int insensitive = leftName.compareToIgnoreCase(rightName);
                    return insensitive != 0 ? insensitive : leftName.compareTo(rightName);
                });
            }

            rows.removeAllViews();
            int maximumRows = 80;
            int shown = Math.min(maximumRows, displayed.size());
            for (int i = 0; i < shown; i++) {
                MineralAreaAnalyzer.MineralSummary item = displayed.get(i);
                Button choice = researchChoiceButton(
                        item.displayName + "\n" + mineralAreaDetail(item),
                        !activeResearchMineralKey.isEmpty() && activeResearchMineralKey.equals(item.key));
                choice.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                choice.setContentDescription("Show " + item.displayName + " Mineral Evidence heatmap");
                choice.setOnClickListener(v -> showMineralAreaHeatmap(item, result));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(0, 0, 0, dp(4));
                rows.addView(choice, params);
            }
            if (displayed.isEmpty()) {
                rows.addView(researchBodyText("Nothing found for that mineral filter. Clear or change the filter to see the available Mineral Evidence terms."));
            } else if (displayed.size() > shown) {
                rows.addView(researchBodyText("Showing the first " + shown + " matching minerals. Narrow the filter to find another term."));
            }
            listStatus.setText("Showing " + displayed.size() + " of " + result.minerals.size()
                    + (alphabetical[0] ? " · A–Z" : " · evidence count"));
        };
        refreshList.run();
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { refreshList.run(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        sort.setOnClickListener(v -> {
            alphabetical[0] = !alphabetical[0];
            sort.setText(alphabetical[0] ? "Sort: A–Z" : "Sort: evidence");
            refreshList.run();
        });

        researchAreaPanel.setScrollableContent(box);
        saveResearchSession();
        filter.requestFocus();
    }

    private void refreshMineralAreaList(
            MineralAreaAnalyzer.AnalysisResult result,
            String filterText,
            boolean alphabetical,
            ArrayList<MineralAreaAnalyzer.MineralSummary> displayed,
            ArrayAdapter<ActionListItem> adapter,
            TextView listStatus) {
        String query = filterText == null ? "" : filterText.trim().toLowerCase(Locale.US);
        displayed.clear();
        for (MineralAreaAnalyzer.MineralSummary item : result.minerals) {
            String name = item.displayName == null ? "" : item.displayName;
            String key = item.key == null ? "" : item.key;
            if (query.isEmpty()
                    || name.toLowerCase(Locale.US).contains(query)
                    || key.toLowerCase(Locale.US).contains(query)) {
                displayed.add(item);
            }
        }

        if (alphabetical) {
            displayed.sort((left, right) -> {
                String leftName = left.displayName == null ? "" : left.displayName;
                String rightName = right.displayName == null ? "" : right.displayName;
                int insensitive = leftName.compareToIgnoreCase(rightName);
                return insensitive != 0 ? insensitive : leftName.compareTo(rightName);
            });
        }

        adapter.clear();
        for (MineralAreaAnalyzer.MineralSummary item : displayed) {
            adapter.add(new ActionListItem(item.displayName, mineralAreaDetail(item) + " · Tap to show heatmap", "HEATMAP"));
        }
        adapter.notifyDataSetChanged();

        listStatus.setText("Showing " + displayed.size() + " of " + result.minerals.size()
                + (alphabetical ? " · A–Z" : " · evidence count"));
    }

    private String mineralAreaDetail(MineralAreaAnalyzer.MineralSummary item) {
        StringBuilder label = new StringBuilder().append(item.recordCount)
                .append(item.recordCount == 1 ? " evidence record" : " evidence records");
        if (item.materialRecordCount > 0) {
            label.append(" · ").append(item.materialRecordCount).append(" explicit mineral/material");
        }
        if (item.commodityOnlyRecordCount > 0) {
            label.append(" · ").append(item.commodityOnlyRecordCount).append(" commodity-only");
        }
        return label.toString();
    }

    private void showMineralAreaHeatmap(MineralAreaAnalyzer.MineralSummary item,
                                        MineralAreaAnalyzer.AnalysisResult analysis) {
        if (item == null || analysis == null) return;
        showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINERALS,
                "Loading " + item.displayName + " Mineral Evidence…");
        mineralIndexRepository.loadAreaEvidence(
                analysis.bounds, item.key, new MineralIndexRepository.AreaEvidenceCallback() {
                    @Override
                    public void onResult(List<MineralAreaAnalyzer.EvidencePoint> points) {
                        activeMineralAreaAnalysis = analysis;
                        activeResearchBounds = geologyBounds(analysis.bounds);
                        activeResearchMineralKey = item.key == null ? "" : item.key;
                        activeResearchMineralLabel = item.displayName == null ? "" : item.displayName;
                        activeResearchMineralEvidencePoints = points == null
                                ? new ArrayList<>() : new ArrayList<>(points);
                        if (activeResearchMineralEvidencePoints.isEmpty()) {
                            activeResearchMineralMessage = "No mapped evidence points remained for "
                                    + activeResearchMineralLabel + " inside this area.";
                            showResearchEmptyState(
                                    ResearchAreaPanelController.VIEW_MINERALS,
                                    activeResearchMineralLabel + " Mineral Evidence",
                                    activeResearchMineralMessage + " Choose another mineral or switch datasets above.");
                            return;
                        }

                        mineralOverlayController.showHeatmap(activeResearchMineralEvidencePoints, analysis.bounds, activeResearchMineralLabel);
                        activeResearchMineralMessage = activeResearchMineralEvidencePoints.size() + " source record"
                                + (activeResearchMineralEvidencePoints.size() == 1 ? " contributes" : "s contribute")
                                + " to this heatmap. Hotter areas mean denser and/or stronger documented evidence nearby. "
                                + "Direct occurrence/locality evidence is weighted more strongly than broad district or abandoned-mine evidence.\n\n"
                                + "This is not a probability map and does not predict specimens between records. "
                                + "Zoom in to see the evidence dots, then tap a dot for its source and reliability. "
                                + "Land status and mining claims remain separate layers.";
                        showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINERALS,
                                activeResearchMineralLabel + " heatmap · " + activeResearchMineralEvidencePoints.size()
                                        + " source record" + (activeResearchMineralEvidencePoints.size() == 1 ? "" : "s"));
                        configureCurrentMineralInformation(item, analysis, activeResearchMineralMessage);
                    }

                    @Override
                    public void onError(String message) {
                        activeResearchMineralEvidencePoints = new ArrayList<>();
                        activeResearchMineralMessage = message == null
                                ? "Heatmap evidence lookup failed safely. Choose another mineral or use another Research dataset."
                                : message + " Choose another mineral or use another Research dataset.";
                        showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINERALS, activeResearchMineralMessage);
                        configureMineralOverview(analysis);
                    }
                });
    }

    private void showCurrentMineralInformation() {
        if (activeMineralAreaAnalysis == null) {
            if (activeResearchBounds != null) showMineralEvidenceForBounds(activeResearchBounds);
            return;
        }
        MineralAreaAnalyzer.MineralSummary current = findMineralSummary(
                activeMineralAreaAnalysis, activeResearchMineralKey, activeResearchMineralLabel);
        if (current == null) {
            configureMineralOverview(activeMineralAreaAnalysis);
            return;
        }
        configureCurrentMineralInformation(current, activeMineralAreaAnalysis, activeResearchMineralMessage);
    }

    private MineralAreaAnalyzer.MineralSummary findMineralSummary(
            MineralAreaAnalyzer.AnalysisResult analysis, String key, String label) {
        if (analysis == null || analysis.minerals == null) return null;
        for (MineralAreaAnalyzer.MineralSummary item : analysis.minerals) {
            if (item == null) continue;
            if (key != null && !key.isEmpty() && key.equals(item.key)) return item;
            if ((key == null || key.isEmpty()) && label != null && item.displayName != null
                    && label.equalsIgnoreCase(item.displayName)) return item;
        }
        return null;
    }

    private void configureCurrentMineralInformation(MineralAreaAnalyzer.MineralSummary item,
                                                     MineralAreaAnalyzer.AnalysisResult analysis,
                                                     String detail) {
        if (researchAreaPanel == null || item == null || analysis == null) return;
        researchAreaPanel.setPrimaryActions(
                new ResearchAreaPanelController.ActionSpec(
                        "Back", "Back to the Mineral Evidence overview without hiding the heatmap",
                        () -> showMineralAreaResults(analysis)),
                new ResearchAreaPanelController.ActionSpec(
                        "Choose Mineral", "Choose a different mineral without leaving this Research Area",
                        () -> showMineralChooserDialog(analysis)),
                new ResearchAreaPanelController.ActionSpec(
                        "Save Research", "Save this Mineral Evidence information with a Prospecting Area",
                        this::saveCurrentResearchSnapshot));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(6), dp(2), dp(6), dp(4));
        StringBuilder info = new StringBuilder();
        info.append(item.displayName).append("\n").append(mineralAreaDetail(item));
        if (detail != null && !detail.trim().isEmpty()) info.append("\n\n").append(detail.trim());
        if (!activeResearchMineralEvidencePoints.isEmpty()) {
            info.append("\n\nEvidence records:");
            int shown = Math.min(6, activeResearchMineralEvidencePoints.size());
            for (int i = 0; i < shown; i++) {
                MineralAreaAnalyzer.EvidencePoint point = activeResearchMineralEvidencePoints.get(i);
                if (point == null || point.record == null) continue;
                MineralRecord record = point.record;
                info.append("\n• ").append(record.name);
                if (point.reason != null && !point.reason.trim().isEmpty()) {
                    info.append(" — ").append(point.reason.trim());
                }
                if (record.evidenceType != null && !record.evidenceType.trim().isEmpty()) {
                    info.append("\n  ").append(record.evidenceType.trim());
                }
                if (record.sourceTitle != null && !record.sourceTitle.trim().isEmpty()) {
                    info.append(" · ").append(record.sourceTitle.trim());
                }
                if (record.locationPrecision != null && !record.locationPrecision.trim().isEmpty()) {
                    info.append("\n  Location: ").append(record.locationPrecision.trim());
                }
            }
            if (activeResearchMineralEvidencePoints.size() > shown) {
                info.append("\n…").append(activeResearchMineralEvidencePoints.size() - shown)
                        .append(" more evidence record")
                        .append(activeResearchMineralEvidencePoints.size() - shown == 1 ? "" : "s")
                        .append(" are represented on the heatmap.");
            }
        }
        content.addView(researchBodyText(info.toString()));
        researchAreaPanel.setScrollableContent(content);
        saveResearchSession();
    }

    private void runMineralSearch(String query, MineralSearchEngine.Bounds bounds, String scopeLabel,
                                  AlertDialog searchDialog, EditText input, Button find) {
        mineralIndexRepository.search(query, bounds, new MineralIndexRepository.Callback() {
            @Override public void onResult(MineralSearchEngine.SearchResult result) {
                activeMineralScopeLabel = scopeLabel;
                activeMineralSearchResult = result;
                searchDialog.dismiss();
                showMineralResults(result, Math.min(MINERAL_LIST_PAGE, result.hits.size()));
            }

            @Override public void onError(String message) {
                find.setEnabled(true);
                find.setText("Search Mineral Evidence");
                input.setError(message == null ? "Mineral search failed." : message);
            }
        });
    }

    private void showMineralResults(MineralSearchEngine.SearchResult result) {
        showMineralResults(result, Math.min(MINERAL_LIST_PAGE, result.hits.size()));
    }

    private void showMineralResults(MineralSearchEngine.SearchResult result, int requestedShown) {
        if (result.hits.isEmpty()) {
            activeMineralSearchResult = null;
            mineralOverlayController.clear();
            new AlertDialog.Builder(this)
                    .setTitle("No mineral matches")
                    .setMessage("No installed mineral-evidence records matched “" + result.requestedQuery + "” in "
                            + activeMineralScopeLabel + ". This does not prove the mineral is absent; no source is a complete rockhounding-locality catalog.")
                    .setPositiveButton("Search again", (d, w) -> showMineralSearch())
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }

        activeMineralSearchResult = result;
        mineralOverlayController.show(result.hits);
        int shown = Math.max(1, Math.min(requestedShown, result.hits.size()));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(4), dp(18), 0);

        TextView summary = new TextView(this);
        StringBuilder summaryText = new StringBuilder();
        if (!result.aliasNote.isEmpty()) summaryText.append(result.aliasNote).append("\n\n");
        summaryText.append(result.totalMatches).append(" matches in ").append(activeMineralScopeLabel).append(". ")
                .append("All ").append(result.hits.size()).append(" are available on the map; dense areas are clustered and expand as you zoom. ")
                .append("Tap a result row for details and its map location. The list is showing ")
                .append(shown).append(" at a time.");
        summary.setText(summaryText.toString());
        summary.setTextSize(12.5f);
        summary.setTextColor(Color.rgb(65, 65, 65));
        summary.setPadding(0, 0, 0, dp(8));
        box.addView(summary);

        ArrayList<ActionListItem> labels = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            MineralSearchEngine.Hit hit = result.hits.get(i);
            String detail = hit.reason
                    + (hit.record.evidenceType.isEmpty() ? "" : "\n" + hit.record.evidenceType)
                    + "\n" + String.format(Locale.US, "%.5f, %.5f", hit.record.latitude, hit.record.longitude);
            labels.add(new ActionListItem(hit.record.name, detail, "VIEW"));
        }
        ListView list = new ListView(this);
        list.setAdapter(actionListAdapter(labels));
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight(390)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        if (shown < result.hits.size()) {
            Button more = smallActionButton("+100");
            actions.addView(more, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button showAll = smallActionButton("Show all");
            actions.addView(showAll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        Button clear = smallActionButton("Clear");
        actions.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Mineral Evidence: " + result.requestedQuery)
                .setView(box)
                .setPositiveButton("Search again", (d, w) -> showMineralSearch())
                .setNegativeButton("Close", null)
                .create();

        if (shown < result.hits.size()) {
            Button more = (Button) actions.getChildAt(0);
            Button showAll = (Button) actions.getChildAt(1);
            more.setOnClickListener(v -> {
                dialog.dismiss();
                showMineralResults(result, Math.min(result.hits.size(), shown + MINERAL_LIST_PAGE));
            });
            showAll.setOnClickListener(v -> {
                dialog.dismiss();
                showMineralResults(result, result.hits.size());
            });
        }
        clear.setOnClickListener(v -> {
            dialog.dismiss();
            clearMinerals();
            showMessage("Mineral overlays cleared.");
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            showMineralDetail(result.hits.get(position), result, true, null);
        });
        dialog.show();
    }

    private Button smallActionButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(label);
        return button;
    }

    /** Flat Research action styling shared with the map-side Research workspace. */
    private Button researchChoiceButton(String label, boolean selected) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        button.setGravity(Gravity.CENTER);
        button.setTypeface(selected
                ? android.graphics.Typeface.DEFAULT_BOLD
                : android.graphics.Typeface.DEFAULT);
        button.setTextColor(selected ? Color.rgb(0, 112, 121) : Color.rgb(32, 38, 40));
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? Color.rgb(222, 242, 243) : Color.WHITE);
        background.setStroke(dp(1), selected ? Color.rgb(0, 112, 121) : Color.rgb(190, 207, 209));
        background.setCornerRadius(dp(7));
        button.setBackground(background);
        button.setContentDescription(label);
        return button;
    }

    private TextView researchPanelHeading(String text) {
        TextView heading = new TextView(this);
        heading.setText(text);
        heading.setTextSize(14f);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setTextColor(Color.rgb(32, 38, 40));
        heading.setPadding(0, dp(2), 0, dp(6));
        return heading;
    }

    private int dialogListHeight(int preferredDp) {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int cap = Math.max(dp(120), Math.round(screenHeight * 0.33f));
        return Math.min(dp(preferredDp), cap);
    }

    private int dialogBodyHeight(int preferredDp) {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int cap = Math.max(dp(150), Math.round(screenHeight * 0.48f));
        return Math.min(dp(preferredDp), cap);
    }

    private View boundedScrollableContent(View content, int preferredDp) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(content);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dialogBodyHeight(preferredDp)));
        return holder;
    }

    private View boundedScrollableContentWithPinnedAction(View content, View action, int preferredDp) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(content);
        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(120), dialogBodyHeight(preferredDp) - dp(56))));
        LinearLayout pinned = new LinearLayout(this);
        pinned.setOrientation(LinearLayout.VERTICAL);
        pinned.setPadding(dp(14), dp(4), dp(14), dp(6));
        pinned.setBackgroundColor(Color.rgb(250, 250, 250));
        pinned.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.addView(pinned);
        return holder;
    }

    private static final class ActionListItem {
        final String title;
        final String subtitle;
        final String action;

        ActionListItem(String title, String subtitle, String action) {
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.action = action == null ? "OPEN" : action;
        }

        @Override public String toString() {
            return title;
        }
    }

    private void applySelectableBackground(View view) {
        android.util.TypedValue selectable = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
                && selectable.resourceId != 0) {
            view.setBackgroundResource(selectable.resourceId);
        }
    }

    private ArrayAdapter<ActionListItem> actionListAdapter(List<ActionListItem> items) {
        return new ArrayAdapter<ActionListItem>(this, android.R.layout.simple_list_item_1, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                ActionListItem item = getItem(position);
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(10), dp(12), dp(10));
                row.setMinimumHeight(dp(64));
                applySelectableBackground(row);

                LinearLayout text = new LinearLayout(MainActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);

                TextView title = new TextView(MainActivity.this);
                title.setText(item == null || item.title.isEmpty() ? "Unnamed item" : item.title);
                title.setTextSize(15.5f);
                title.setTextColor(Color.rgb(30, 30, 30));
                title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                text.addView(title);

                if (item != null && !item.subtitle.trim().isEmpty()) {
                    TextView subtitle = new TextView(MainActivity.this);
                    subtitle.setText(item.subtitle.trim());
                    subtitle.setTextSize(12.5f);
                    subtitle.setTextColor(Color.rgb(80, 80, 80));
                    subtitle.setPadding(0, dp(3), dp(6), 0);
                    subtitle.setMaxLines(5);
                    text.addView(subtitle);
                }

                row.addView(text, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView action = new TextView(MainActivity.this);
                String actionLabel = item == null ? "OPEN" : item.action;
                action.setText(actionLabel + "  ›");
                action.setTextSize(11.5f);
                action.setTextColor(Color.rgb(35, 90, 155));
                action.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                action.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
                action.setMinWidth(dp(66));
                action.setMinHeight(dp(48));
                row.addView(action, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                if (item != null) {
                    row.setContentDescription(item.title
                            + (item.subtitle.trim().isEmpty() ? "" : ". " + item.subtitle.trim())
                            + ". " + actionLabel.toLowerCase(Locale.US) + ".");
                }
                return row;
            }
        };
    }

    private TextView helperText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12.5f);
        view.setTextColor(Color.rgb(72, 72, 72));
        view.setPadding(dp(4), 0, dp(4), dp(8));
        return view;
    }

    private TextView sectionLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14.5f);
        view.setTextColor(Color.rgb(35, 35, 35));
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(10), 0, dp(4));
        return view;
    }

    private AlertDialog showActionListDialog(
            String title, String intro, List<ActionListItem> rows,
            String positiveLabel, android.content.DialogInterface.OnClickListener positiveAction,
            String neutralLabel, android.content.DialogInterface.OnClickListener neutralAction,
            String negativeLabel, android.content.DialogInterface.OnClickListener negativeAction,
            java.util.function.IntConsumer onSelect) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), dp(2), dp(4), 0);
        if (intro != null && !intro.trim().isEmpty()) {
            TextView introText = helperText(intro.trim());
            introText.setPadding(dp(16), dp(2), dp(16), dp(8));
            box.addView(introText);
        }

        ListView list = new ListView(this);
        list.setAdapter(actionListAdapter(rows));
        int estimatedRow = 96;
        int preferredListDp = Math.min(430, Math.max(72, rows.size() * estimatedRow));
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight(preferredListDp)));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box);
        if (positiveLabel != null) builder.setPositiveButton(positiveLabel, positiveAction);
        if (neutralLabel != null) builder.setNeutralButton(neutralLabel, neutralAction);
        if (negativeLabel != null) builder.setNegativeButton(negativeLabel, negativeAction);
        AlertDialog dialog = builder.create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= rows.size()) return;
            dialog.dismiss();
            if (onSelect != null) onSelect.accept(position);
        });
        dialog.show();
        return dialog;
    }

    private void showMineralDetail(MineralSearchEngine.Hit hit,
                                   MineralSearchEngine.SearchResult result,
                                   boolean centerOnMap,
                                   List<Feature> landAtMarker) {
        MineralRecord record = hit.record;
        if (centerOnMap) mineralOverlayController.center(record);

        StringBuilder text = new StringBuilder();
        if (result != null && !result.requestedQuery.isEmpty()) {
            text.append("Searched for: ").append(result.requestedQuery);
            if (!result.aliasNote.isEmpty()) {
                text.append("\nMatched through: ").append(result.effectiveQuery)
                        .append(" — parent-mineral fallback");
                text.append("\nMatch evidence: ").append(hit.reason);
            } else {
                text.append("\nMatched: ").append(hit.reason);
            }
            text.append('\n');
        } else {
            text.append("Matched: ").append(hit.reason).append('\n');
        }

        text.append("\nCoordinates: ")
                .append(String.format(Locale.US, "%.6f, %.6f", record.latitude, record.longitude));
        if (landAtMarker != null) {
            text.append('\n').append(formatMineralLand(landAtMarker));
            appendMineralLandSource(text);
        }
        if (!record.materials.isEmpty()) {
            text.append("\n\nAll recorded minerals/materials: ").append(String.join(", ", record.materials));
        }
        if (!record.commodities.isEmpty()) text.append("\nCommodities: ").append(String.join(", ", record.commodities));
        if (!record.districts.isEmpty()) text.append("\nDistrict: ").append(String.join(", ", record.districts));
        if (!record.models.isEmpty()) text.append("\nDeposit model: ").append(String.join(", ", record.models));
        if (!record.rocks.isEmpty()) text.append("\nRock context: ").append(String.join(", ", record.rocks));
        if (!record.status.isEmpty()) text.append(record.isMrds()
                ? "\nMRDS development status: " : "\nSource status: ").append(record.status);
        if (!record.evidenceType.isEmpty()) text.append("\nEvidence: ").append(record.evidenceType);
        if (!record.locationPrecision.isEmpty()) text.append("\nPrecision: ").append(record.locationPrecision);
        if (record.isMrds()) text.append("\nUSGS MRDS ID: ").append(record.id);
        else text.append("\nSource record ID: ").append(record.id);

        appendMineralSource(text, record);
        text.append("\n\nA mapped occurrence is a research lead, not proof of current ownership, access, claim status, or permission to collect.");

        TextView body = new TextView(this);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(text.toString());
        LinearLayout detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.addView(body);
        Button makeArea = smallActionButton("Create Prospecting Area Around Here");
        makeArea.setOnClickListener(v -> ProspectingAreaCreator.chooseRadiusAndSave(
                this, record.latitude, record.longitude, record.name,
                "Created from Mineral Evidence record: " + record.name));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(record.name)
                .setView(boundedScrollableContentWithPinnedAction(detailBox, makeArea, 460))
                .setPositiveButton("Save Location", (d, w) ->
                        saveMineralMarker(record, hit.reason, result, landAtMarker))
                .setNegativeButton("Close", null);
        if (result != null && !result.hits.isEmpty()) {
            builder.setNeutralButton("Results", (d, w) -> showMineralResults(result));
        } else if (activeMineralAreaAnalysis != null) {
            builder.setNeutralButton("Area Evidence", (d, w) ->
                    showMineralAreaResults(activeMineralAreaAnalysis));
        }
        builder.show();
    }

    private void appendMineralSource(StringBuilder text, MineralRecord record) {
        String source = record.sourceTitle.isEmpty() ? record.sourceCode : record.sourceTitle;
        text.append("\n\nSource: ").append(source);
        if (!record.sourceReliability.isEmpty()) {
            text.append("\nReliability: ").append(record.sourceReliability);
        }
        if (!record.sourceNote.isEmpty()) text.append("\nNote: ").append(record.sourceNote);
    }

    private void appendMineralLandSource(StringBuilder text) {
        if (!mapController.hasLandStatusAvailable()) return;
        text.append("\nLand source: BLM Colorado SMA");
        text.append("\nLand reliability: Management mapping; not parcel/legal boundaries.");
    }

    private String formatMineralLand(List<Feature> land) {
        if (!mapController.hasLandStatusAvailable()) {
            return "Land: Unknown — land-status data not active.";
        }
        if (land == null || land.isEmpty()) {
            return "Land: Unknown — no mapped management feature at this point.";
        }
        StringBuilder out = new StringBuilder("Land: ");
        int shown = 0;
        for (Feature feature : land) {
            if (shown >= 3) break;
            String code = stringProp(feature, "manager_code", "").trim();
            String manager = stringProp(feature, "manager_name", "Unknown manager").trim();
            String category = LandStatusCatalog.labelFor(code, manager);
            if (shown++ > 0) out.append(" / ");
            if (!code.isEmpty()) out.append(code).append(" — ");
            out.append(category);
        }
        if (land.size() > shown) out.append(" +").append(land.size() - shown).append(" more");
        return out.toString();
    }

    private void onMineralTapped(MineralSearchEngine.Hit hit, boolean fromAreaHeatmap) {
        if (hit == null) return;
        List<Feature> land = pendingOverlayTapLand == null
                ? new ArrayList<>() : new ArrayList<>(pendingOverlayTapLand);
        showMineralDetail(hit, fromAreaHeatmap ? null : activeMineralSearchResult, false, land);
    }

    private void setHistoricMinesVisible(boolean visible) {
        setHistoricMinesVisible(visible, null);
    }

    private void setHistoricMinesVisible(boolean visible, GeologyRepository.Bounds contextBounds) {
        historicMinesRequestedVisible = visible;
        historicMineContextBounds = visible ? contextBounds : null;
        if (!visible) {
            historicMineOverlayController.setVisible(false);
            MapContextCloseController.forMap(mapView).clearHistoricTarget();
            return;
        }
        if (!mineralIndexRepository.hasExpandedEvidence()) {
            historicMinesRequestedVisible = false;
            historicMineContextBounds = null;
            MapContextCloseController.forMap(mapView).clearHistoricTarget();
            showMessage("Historic mine overlay requires the installed Alpha 6.2.1 evidence index.");
            if (contextBounds != null) {
                showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINES,
                        "Historic Mines & Workings data is not installed. Geology and Mineral Evidence remain available for this area.");
            }
            return;
        }
        if (historicMineOverlayController.isLoaded()) {
            historicMineOverlayController.setVisible(true);
            syncHistoricMineCloseTarget();
            if (contextBounds != null) {
                showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINES,
                        "Historic Mines & Workings shown. Switch datasets without leaving this area.");
            }
            return;
        }
        if (historicMinesLoading) return;

        historicMinesLoading = true;
        showMessage("Loading historic mine / working overlay…");
        mineralIndexRepository.loadHistoricMines(new MineralIndexRepository.RecordListCallback() {
            @Override
            public void onResult(List<MineralRecord> records) {
                historicMinesLoading = false;
                historicMineOverlayController.load(records);
                if (records.isEmpty()) {
                    historicMinesRequestedVisible = false;
                    historicMineContextBounds = null;
                    MapContextCloseController.forMap(mapView).clearHistoricTarget();
                    showMessage("No historic mine records were found in the installed evidence index.");
                    if (contextBounds != null) {
                        showResearchEmptyState(
                                ResearchAreaPanelController.VIEW_MINES,
                                "Historic Mines & Workings",
                                "No Historic Mines & Workings records were found in the installed data for this area.");
                    }
                    return;
                }
                historicMineOverlayController.setVisible(historicMinesRequestedVisible);
                if (historicMinesRequestedVisible) {
                    syncHistoricMineCloseTarget();
                    showMessage("Historic mine overlay loaded: " + records.size() + " mapped records.");
                    if (contextBounds != null) {
                        showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINES,
                                "Historic Mines & Workings shown. Switch datasets without leaving this area.");
                    }
                }
            }

            @Override
            public void onError(String message) {
                historicMinesLoading = false;
                historicMinesRequestedVisible = false;
                historicMineContextBounds = null;
                historicMineOverlayController.setVisible(false);
                MapContextCloseController.forMap(mapView).clearHistoricTarget();
                showMessage(message == null ? "Historic mine overlay failed to load." : message);
                if (contextBounds != null) {
                    showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINES,
                            (message == null ? "Historic Mines & Workings could not be loaded." : message)
                                    + " Geology and Mineral Evidence remain available for this area.");
                }
            }
        });
    }

    private void syncHistoricMineCloseTarget() {
        MapContextCloseController close = MapContextCloseController.forMap(mapView);
        Runnable hide = () -> setHistoricMinesVisible(false);
        if (historicMineContextBounds != null) {
            close.setHistoricTarget("Historic Mines & Workings", Color.rgb(112, 78, 50),
                    historicMineContextBounds.south, historicMineContextBounds.west,
                    historicMineContextBounds.north, historicMineContextBounds.east, hide);
        } else {
            close.setHistoricTarget("Historic Mines & Workings", Color.rgb(112, 78, 50), hide);
        }
    }

    private void onHistoricMinesTapped(List<MineralRecord> records) {
        if (records == null || records.isEmpty()) return;
        List<Feature> land = pendingOverlayTapLand == null
                ? new ArrayList<>() : new ArrayList<>(pendingOverlayTapLand);
        if (records.size() == 1) {
            showHistoricMineDetail(records.get(0), land);
            return;
        }
        showHistoricMineSelector(records, land);
    }

    private void showHistoricMineSelector(List<MineralRecord> records, List<Feature> land) {
        int shown = Math.min(50, records.size());
        ArrayList<ActionListItem> rows = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            MineralRecord record = records.get(i);
            String detail = HistoricMineCatalog.typeLabel(record)
                    + "\n" + (record.sourceTitle.isEmpty() ? record.sourceCode : record.sourceTitle)
                    + "\n" + String.format(Locale.US, "%.6f, %.6f",
                    record.latitude, record.longitude);
            rows.add(new ActionListItem(HistoricMineCatalog.displayName(record), detail, "VIEW"));
        }
        String intro = records.size() > shown
                ? "Showing the first " + shown + " overlapping/nearby rendered records. Zoom in to separate additional points. Tap a row for details."
                : "More than one source record overlaps this tap. Tap the record you want to inspect.";
        showActionListDialog(
                records.size() + " mine records at this tap", intro, rows,
                null, null, null, null, "Close", null,
                which -> showHistoricMineDetail(records.get(which), land));
    }

    private void showHistoricMineDetail(MineralRecord record, List<Feature> landAtMine) {
        if (record == null) return;
        mineralIndexRepository.findNearbyHistoricMineEvidence(
                record, HISTORIC_MINE_NEARBY_METERS, HISTORIC_MINE_NEARBY_LIMIT,
                new MineralIndexRepository.NearbyEvidenceCallback() {
                    @Override
                    public void onResult(List<HistoricMineCatalog.NearbyEvidence> evidence) {
                        renderHistoricMineDetail(record, landAtMine, evidence);
                    }

                    @Override
                    public void onError(String message) {
                        renderHistoricMineDetail(record, landAtMine, new ArrayList<>());
                    }
                });
    }

    private void renderHistoricMineDetail(MineralRecord record,
                                          List<Feature> landAtMine,
                                          List<HistoricMineCatalog.NearbyEvidence> nearby) {
        StringBuilder text = new StringBuilder();
        appendHistoricMineRecord(text, record, landAtMine);
        appendNearbyHistoricMineEvidence(text, nearby);
        text.append("\n\nResearch lead only. Mine locations and conditions may have changed. ")
                .append("Do not enter abandoned openings or workings. RockMap does not determine access, ")
                .append("ownership, claim validity, or collecting permission.");

        TextView body = new TextView(this);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(text.toString());
        LinearLayout detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.addView(body);
        Button makeArea = smallActionButton("Create Prospecting Area Around Here");
        makeArea.setOnClickListener(v -> ProspectingAreaCreator.chooseRadiusAndSave(
                this, record.latitude, record.longitude, HistoricMineCatalog.displayName(record),
                "Created from Historic Mine / Working: " + HistoricMineCatalog.displayName(record)));

        new AlertDialog.Builder(this)
                .setTitle(HistoricMineCatalog.displayName(record))
                .setView(boundedScrollableContentWithPinnedAction(detailBox, makeArea, 460))
                .setPositiveButton("Save Location / Note",
                        (d, w) -> showHistoricMineSaveDialog(record, landAtMine, nearby))
                .setNeutralButton("Center",
                        (d, w) -> historicMineOverlayController.center(record))
                .setNegativeButton("Close", null)
                .show();
    }

    private void appendHistoricMineRecord(StringBuilder text,
                                          MineralRecord record,
                                          List<Feature> landAtMine) {
        text.append("Mapped coordinates (source record): ")
                .append(String.format(Locale.US, "%.6f, %.6f",
                        record.latitude, record.longitude));
        text.append("\nType: ").append(HistoricMineCatalog.typeLabel(record));

        if (landAtMine != null) {
            text.append('\n').append(formatMineralLand(landAtMine));
            appendMineralLandSource(text);
        }

        if (record.materials.isEmpty()) {
            text.append("\n\nDocumented minerals/materials: None in this source.");
        } else {
            text.append("\n\nDocumented minerals/materials: ")
                    .append(String.join(", ", record.materials));
        }
        if (!record.commodities.isEmpty()) {
            text.append("\nCommodities/products: ").append(String.join(", ", record.commodities));
        }
        if (!record.districts.isEmpty()) text.append("\nDistrict: ").append(String.join(", ", record.districts));
        if (!record.models.isEmpty()) text.append("\nDeposit model: ").append(String.join(", ", record.models));
        if (!record.rocks.isEmpty()) text.append("\nRock context: ").append(String.join(", ", record.rocks));
        if (!record.status.isEmpty()) text.append("\nSource status: ").append(record.status);
        if (!record.locationPrecision.isEmpty()) text.append("\nPrecision: ").append(record.locationPrecision);
        text.append("\nSource record ID: ").append(record.id);
        appendMineralSource(text, record);
    }

    private void appendNearbyHistoricMineEvidence(
            StringBuilder text, List<HistoricMineCatalog.NearbyEvidence> nearby) {
        text.append("\n\nNearby documented mineral evidence (≤100 m; not necessarily same working):");
        if (nearby == null || nearby.isEmpty()) {
            text.append("\nNone found in MRDS, B-40, or the official-locality supplement within 100 m.");
            return;
        }

        int index = 0;
        for (HistoricMineCatalog.NearbyEvidence item : nearby) {
            MineralRecord record = item.record;
            text.append("\n\n• ").append(HistoricMineCatalog.displayName(record))
                    .append(" — ").append(Math.round(item.distanceMeters)).append(" m");
            if (!record.materials.isEmpty()) {
                text.append("\n  Minerals/materials: ").append(String.join(", ", record.materials));
            }
            if (!record.commodities.isEmpty()) {
                text.append("\n  Commodities: ").append(String.join(", ", record.commodities));
            }
            text.append("\n  Source: ")
                    .append(record.sourceTitle.isEmpty() ? record.sourceCode : record.sourceTitle);
            if (!record.sourceReliability.isEmpty()) {
                text.append("\n  Reliability: ").append(record.sourceReliability);
            }
            if (++index >= HISTORIC_MINE_NEARBY_LIMIT) break;
        }
    }

    private void showHistoricMineSaveDialog(MineralRecord record,
                                            List<Feature> landAtMine,
                                            List<HistoricMineCatalog.NearbyEvidence> nearby) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.setContentDescription("Save coordinate marker");

        TextView coordinate = new TextView(this);
        coordinate.setText("Mapped coordinates: " + String.format(Locale.US, "%.6f, %.6f",
                record.latitude, record.longitude));
        coordinate.setTextSize(13f);
        coordinate.setPadding(0, 0, 0, dp(6));
        box.addView(coordinate);

        EditText name = new EditText(this);
        name.setHint("Location name");
        name.setText(HistoricMineCatalog.displayName(record));
        box.addView(name);

        EditText observations = new EditText(this);
        observations.setHint("Field observations (optional)");
        observations.setMinLines(3);
        observations.setMaxLines(5);
        box.addView(observations);

        new AlertDialog.Builder(this)
                .setTitle("Save mine / add field note")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    long now = System.currentTimeMillis();
                    String label = boundedText(name.getText().toString().trim(), 500);
                    if (label.isEmpty()) label = HistoricMineCatalog.displayName(record);

                    StringBuilder note = new StringBuilder("Historic mine / working record\n");
                    appendHistoricMineRecord(note, record, landAtMine);
                    appendNearbyHistoricMineEvidence(note, nearby);
                    String observationText = observations.getText().toString().trim();
                    if (!observationText.isEmpty()) {
                        note.append("\n\nField observations:\n")
                                .append(boundedText(observationText, 8_000));
                    }
                    note.append("\n\nResearch lead only; source coordinates may be approximate. ")
                            .append("Do not enter abandoned openings or workings. Access and collecting legality are not determined.");

                    WaypointEntity waypoint = new WaypointEntity(
                            record.latitude, record.longitude, HISTORIC_MINE_SOURCE_ACCURACY, now,
                            label, boundedText(note.toString(), 20_000), now, now);
                    waypointRepository.insert(waypoint, () -> {
                        refreshWaypoints();
                        showMessage("Historic mine location and Field Record saved.");
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearMinerals() {
        activeMineralScopeLabel = "All Colorado";
        activeMineralSearchResult = null;
        activeMineralAreaAnalysis = null;
        mineralOverlayController.clear();
        mineralOverlayController.clearAreaAnalysis();
    }

    private void saveMineralMarker(MineralRecord record, String matchReason,
                                   MineralSearchEngine.SearchResult result,
                                   List<Feature> landAtMarker) {
        long now = System.currentTimeMillis();
        String searchedFor = result == null || result.requestedQuery.isEmpty()
                ? "" : "\nSearched for: " + result.requestedQuery;
        String fallback = result == null || result.aliasNote.isEmpty()
                ? "" : "\nMatched through: " + result.effectiveQuery + " (parent-mineral fallback)";
        String recordIdLabel = record.isMrds() ? "MRDS ID: " : "Source record ID: ";
        StringBuilder note = new StringBuilder("Mineral-search result\n");
        note.append(recordIdLabel).append(record.id)
                .append(searchedFor)
                .append(fallback)
                .append("\nMatched: ").append(matchReason);
        if (!record.materials.isEmpty()) note.append("\nMinerals/materials: ").append(String.join(", ", record.materials));
        if (!record.commodities.isEmpty()) note.append("\nCommodities: ").append(String.join(", ", record.commodities));
        if (!record.districts.isEmpty()) note.append("\nDistrict: ").append(String.join(", ", record.districts));
        if (!record.models.isEmpty()) note.append("\nDeposit model: ").append(String.join(", ", record.models));
        if (!record.rocks.isEmpty()) note.append("\nRock context: ").append(String.join(", ", record.rocks));
        if (!record.status.isEmpty()) note.append(record.isMrds()
                ? "\nMRDS development status: " : "\nSource status: ").append(record.status);
        if (!record.evidenceType.isEmpty()) note.append("\nEvidence: ").append(record.evidenceType);
        if (!record.locationPrecision.isEmpty()) note.append("\nPrecision: ").append(record.locationPrecision);
        if (landAtMarker != null) {
            note.append("\n").append(formatMineralLand(landAtMarker));
            appendMineralLandSource(note);
        }
        appendMineralSource(note, record);
        note.append("\n\nResearch lead only; not ownership, access, claim status, or collecting permission.");

        WaypointEntity waypoint = new WaypointEntity(
                record.latitude, record.longitude, MINERAL_SOURCE_ACCURACY, now,
                boundedText(record.name, 500), boundedText(note.toString(), 20_000), now, now);
        waypointRepository.insert(waypoint, () -> {
            refreshWaypoints();
            showMessage("Mineral result saved to Saved Locations.");
        });
    }

    private void showFindSearch() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        TextView help = new TextView(this);
        help.setText("Search offline for Colorado towns/localities, peaks and mountain features, and named lakes/reservoirs — or paste latitude/longitude. Name search is approximate.\n\nRoads, rivers, trails, parks, addresses, businesses and general landmarks are not dependable by name.\n\nExamples: Mount Antero, Buena Vista, Twin Lakes, or 39.290719, -106.212474.\n\nSource: USGS National Map Gazetteer. Results are locators, not routing or access guidance.");
        help.setTextSize(13f);
        help.setTextColor(Color.rgb(65, 65, 65));
        help.setPadding(0, 0, 0, dp(8));
        box.addView(boundedScrollableContent(help, 190));

        EditText input = new EditText(this);
        input.setHint("Town, peak, lake, or coordinates");
        input.setSingleLine(true);
        box.addView(input);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Find on map")
                .setView(box)
                .setPositiveButton("Find", null)
                .setNegativeButton("Close", null);
        if (activePlaceTarget != null) {
            builder.setNeutralButton("Clear pin", (d, w) -> clearPlaceSearchTarget());
        }
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String query = input.getText().toString().trim();
                    if (query.length() < 2) {
                        input.setError("Enter at least 2 characters.");
                        return;
                    }
                    if (looksLikeCoordinates(query)) {
                        try {
                            CoordinateParser.Result result = CoordinateParser.parse(query);
                            dialog.dismiss();
                            clearPlaceSearchTarget();
                            showCoordinateResult(result);
                        } catch (IllegalArgumentException ex) {
                            input.setError(ex.getMessage());
                        }
                        return;
                    }

                    Button find = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    find.setEnabled(false);
                    find.setText("Searching…");
                    placeIndexRepository.search(query, PLACE_SEARCH_LIMIT, new PlaceIndexRepository.Callback() {
                        @Override public void onResult(List<PlaceSearchEngine.Match> matches) {
                            find.setEnabled(true);
                            find.setText("Find");
                            dialog.dismiss();
                            showPlaceSearchResults(query, matches);
                        }

                        @Override public void onError(String message) {
                            find.setEnabled(true);
                            find.setText("Find");
                            input.setError(message == null ? "Offline place search failed." : message);
                        }
                    });
                }));
        dialog.show();
        input.requestFocus();
    }

    private boolean looksLikeCoordinates(String raw) {
        if (raw == null) return false;
        String text = raw.trim();
        if (text.contains("°") || text.contains("′") || text.contains("″")) return true;
        if (text.matches("(?i).*[0-9]\\s*[NS]\\b.*")
                || text.matches("(?i).*[0-9]\\s*[EW]\\b.*")) return true;
        return text.matches("^[+-]?\\d{1,2}(?:\\.\\d+)?\\s*[, ]\\s*[+-]?\\d{1,3}(?:\\.\\d+)?$");
    }

    private void showPlaceSearchResults(String query, List<PlaceSearchEngine.Match> matches) {
        ArrayList<PlaceSearchEngine.Match> supported = new ArrayList<>();
        if (matches != null) {
            for (PlaceSearchEngine.Match match : matches) {
                if (match != null && isSupportedFindResult(match.record)) {
                    supported.add(match);
                }
            }
        }

        if (supported.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No supported offline place match")
                    .setMessage("RockMap did not find a dependable name-search result for “" + query
                            + "”. Offline Find is best for cities/towns/localities, peaks and mountain features, and named lakes/reservoirs.\n\n"
                            + "Roads/highways, rivers/streams, trails, parks/monuments, venues, addresses, businesses, and general landmarks are not included as dependable Find categories.\n\n"
                            + "Name search is imperfect and may only get you close. For the most accurate result, find the GPS latitude/longitude for the location you want and paste those coordinates into Find.")
                    .setPositiveButton("Search again", (d, w) -> showFindSearch())
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), 0, dp(4), 0);

        TextView instruction = new TextView(this);
        instruction.setText(supported.size() + (supported.size() == 1 ? " match" : " matches")
                + " · Tap a result to view it, or + Trip to save it.");
        instruction.setTextSize(12.5f);
        instruction.setTextColor(Color.rgb(75, 75, 75));
        instruction.setPadding(dp(16), dp(2), dp(16), dp(4));
        box.addView(instruction);

        TextView disclaimer = new TextView(this);
        disclaimer.setText("Offline name search is approximate. A result may get you close, but it does not guarantee an exact location. For precision, use known GPS coordinates.");
        disclaimer.setTextSize(11.5f);
        disclaimer.setTextColor(Color.rgb(90, 75, 45));
        disclaimer.setPadding(dp(16), 0, dp(16), dp(8));
        box.addView(disclaimer);

        ArrayAdapter<PlaceSearchEngine.Match> adapter =
                new ArrayAdapter<PlaceSearchEngine.Match>(
                        this, android.R.layout.simple_list_item_1, supported) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        PlaceSearchEngine.Match match = getItem(position);
                        PlaceRecord record = match == null ? null : match.record;

                        LinearLayout row = new LinearLayout(MainActivity.this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(16), dp(10), dp(12), dp(10));
                        row.setMinimumHeight(dp(72));

                        android.util.TypedValue selectable = new android.util.TypedValue();
                        if (MainActivity.this.getTheme().resolveAttribute(
                                android.R.attr.selectableItemBackground, selectable, true)
                                && selectable.resourceId != 0) {
                            row.setBackgroundResource(selectable.resourceId);
                        }

                        LinearLayout textColumn = new LinearLayout(MainActivity.this);
                        textColumn.setOrientation(LinearLayout.VERTICAL);

                        TextView name = new TextView(MainActivity.this);
                        name.setText(record == null ? "Unknown place" : record.name);
                        name.setTextSize(16f);
                        name.setTextColor(Color.rgb(30, 30, 30));
                        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        textColumn.addView(name);

                        TextView details = new TextView(MainActivity.this);
                        String kind = record == null || record.kind == null ? "" : record.kind.trim();
                        String context = record == null || record.context == null
                                ? "" : record.context.trim();
                        String detailText;
                        if (kind.isEmpty()) {
                            detailText = context;
                        } else if (context.isEmpty()) {
                            detailText = kind;
                        } else {
                            detailText = kind + " · " + context;
                        }
                        details.setText(detailText);
                        details.setTextSize(12.5f);
                        details.setTextColor(Color.rgb(85, 85, 85));
                        details.setPadding(0, dp(2), 0, 0);
                        textColumn.addView(details);

                        TextView action = new TextView(MainActivity.this);
                        action.setText("View on map");
                        action.setTextSize(12f);
                        action.setTextColor(Color.rgb(35, 90, 155));
                        action.setPadding(0, dp(3), 0, 0);
                        textColumn.addView(action);

                        row.addView(textColumn, new LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                        LinearLayout rightActions = new LinearLayout(MainActivity.this);
                        rightActions.setOrientation(LinearLayout.VERTICAL);
                        rightActions.setGravity(Gravity.CENTER);

                        Button addToTrip = smallActionButton("+ Trip");
                        addToTrip.setTextSize(11f);
                        addToTrip.setFocusable(false);
                        addToTrip.setEnabled(record != null);
                        addToTrip.setOnClickListener(v -> {
                            if (record != null) showTripPickerForPlace(record);
                        });
                        rightActions.addView(addToTrip, new LinearLayout.LayoutParams(
                                dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));

                        TextView chevron = new TextView(MainActivity.this);
                        chevron.setText("VIEW  ›");
                        chevron.setTextSize(11.5f);
                        chevron.setTextColor(Color.rgb(35, 90, 155));
                        chevron.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                        chevron.setGravity(Gravity.CENTER);
                        chevron.setMinHeight(dp(36));
                        rightActions.addView(chevron, new LinearLayout.LayoutParams(
                                dp(76), dp(36)));

                        row.addView(rightActions, new LinearLayout.LayoutParams(
                                dp(82), ViewGroup.LayoutParams.MATCH_PARENT));

                        if (record != null) {
                            row.setContentDescription(record.name + ". "
                                    + detailText + ". View on map or add to trip.");
                        }
                        return row;
                    }
                };

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        int listHeightDp = Math.min(420, Math.max(96, supported.size() * 84));
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight(listHeightDp)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Find: " + query)
                .setView(box)
                .setPositiveButton("Search again", (d, w) -> showFindSearch())
                .setNegativeButton("Close", null)
                .create();

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= supported.size()) return;
            dialog.dismiss();
            showPlaceTarget(supported.get(position).record);
        });

        dialog.show();
    }

    private boolean isSupportedFindResult(PlaceRecord record) {
        if (record == null || record.kind == null) return false;
        String kind = record.kind.trim().toLowerCase(Locale.US);
        return kind.equals("place")
                || kind.equals("peak")
                || kind.equals("mountain pass / gap")
                || kind.equals("ridge")
                || kind.equals("mountain range")
                || kind.equals("valley")
                || kind.equals("basin")
                || kind.equals("lake")
                || kind.equals("reservoir")
                || kind.equals("lake / reservoir");
    }

    private void showPlaceTarget(PlaceRecord record) {
        if (record == null) return;
        activePlaceTarget = record;
        renderPlaceSearchTarget(true);
        showMessage("Showing " + record.name + " — " + record.kind + ".");
    }

    private void renderPlaceSearchTarget(boolean centerCamera) {
        PlaceRecord record = activePlaceTarget;
        if (record == null || mapView == null) return;
        mapView.getMapAsync(mapLibreMap -> {
            mapLibreMap.getStyle(loadedStyle -> {
                String geoJson = placeTargetGeoJson(record);
                GeoJsonSource source = loadedStyle.getSourceAs(PLACE_SEARCH_SOURCE);
                if (source == null) {
                    source = new GeoJsonSource(PLACE_SEARCH_SOURCE, geoJson);
                    loadedStyle.addSource(source);
                } else {
                    source.setGeoJson(geoJson);
                }
                if (loadedStyle.getLayer(PLACE_SEARCH_LAYER) == null) {
                    CircleLayer target = new CircleLayer(PLACE_SEARCH_LAYER, PLACE_SEARCH_SOURCE);
                    target.setProperties(
                            org.maplibre.android.style.layers.PropertyFactory.circleColor(Color.rgb(255, 210, 30)),
                            org.maplibre.android.style.layers.PropertyFactory.circleRadius(9f),
                            org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor(Color.BLACK),
                            org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(3f));
                    loadedStyle.addLayer(target);
                }
            });
            if (centerCamera) {
                mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        new LatLng(record.latitude, record.longitude), placeTargetZoom(record.kind)));
            }
        });
    }

    private String placeTargetGeoJson(PlaceRecord record) {
        try {
            JSONObject geometry = new JSONObject();
            geometry.put("type", "Point");
            geometry.put("coordinates", new JSONArray().put(record.longitude).put(record.latitude));
            JSONObject properties = new JSONObject();
            properties.put("name", record.name);
            properties.put("kind", record.kind);
            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            feature.put("geometry", geometry);
            feature.put("properties", properties);
            JSONObject collection = new JSONObject();
            collection.put("type", "FeatureCollection");
            collection.put("features", new JSONArray().put(feature));
            return collection.toString();
        } catch (JSONException ex) {
            return "{\"type\":\"FeatureCollection\",\"features\":[]}";
        }
    }

    private double placeTargetZoom(String kind) {
        if (kind == null) return 12.0;
        String lower = kind.toLowerCase(Locale.US);
        if (lower.contains("city")) return 10.0;
        if (lower.contains("town") || lower.contains("village") || lower.contains("hamlet")) return 11.0;
        if (lower.contains("road") || lower.contains("trail") || lower.contains("route") || lower.contains("track")) return 12.0;
        if (lower.contains("peak") || lower.contains("pass") || lower.contains("landmark")) return 12.5;
        return 12.0;
    }

    private void clearPlaceSearchTarget() {
        activePlaceTarget = null;
        if (mapView == null) return;
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(loadedStyle -> {
            GeoJsonSource source = loadedStyle.getSourceAs(PLACE_SEARCH_SOURCE);
            if (source != null) {
                source.setGeoJson("{\"type\":\"FeatureCollection\",\"features\":[]}");
            }
        }));
    }

    private void showCoordinateSearch() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        TextView help = new TextView(this);
        help.setText("Enter latitude first, then longitude. This works offline.\n\nExamples:\n39.290719, -106.212474\n39°17'26.6\"N 106°12'44.9\"W\n39°17.443′ N, 106°12.748′ W");
        help.setTextSize(13f);
        help.setTextColor(Color.rgb(65, 65, 65));
        help.setPadding(0, 0, 0, dp(8));
        box.addView(help);

        EditText input = new EditText(this);
        input.setHint("Latitude, longitude");
        input.setSingleLine(true);
        box.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Search coordinates")
                .setView(box)
                .setPositiveButton("Find", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                CoordinateParser.Result result = CoordinateParser.parse(input.getText().toString());
                dialog.dismiss();
                showCoordinateResult(result);
            } catch (IllegalArgumentException ex) {
                input.setError(ex.getMessage());
            }
        }));
        dialog.show();
        input.requestFocus();
    }

    private void showCoordinateResult(CoordinateParser.Result result) {
        Location target = new Location("manual-coordinate");
        target.setLatitude(result.latitude);
        target.setLongitude(result.longitude);
        mapController.centerOn(target);

        new AlertDialog.Builder(this)
                .setTitle("Coordinate found")
                .setMessage(result.formatDecimal()
                        + "\n\nRockMap centered the map here. Save it as a persistent Saved Location with a name and notes.")
                .setPositiveButton("Save Location", (d, w) -> showManualMarkerDialog(result))
                .setNeutralButton("Find again", (d, w) -> showFindSearch())
                .setNegativeButton("Close", null)
                .show();
    }

    private void showManualMarkerDialog(CoordinateParser.Result result) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        TextView coordinate = new TextView(this);
        coordinate.setText(result.formatDecimal());
        coordinate.setTextSize(13f);
        coordinate.setPadding(0, 0, 0, dp(6));
        box.addView(coordinate);

        EditText name = new EditText(this);
        name.setHint("Location name");
        box.addView(name);

        EditText notes = new EditText(this);
        notes.setHint("Notes (optional)");
        notes.setMinLines(3);
        notes.setMaxLines(5);
        box.addView(notes);

        new AlertDialog.Builder(this)
                .setTitle("Save Location")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    long now = System.currentTimeMillis();
                    String label = boundedText(name.getText().toString().trim(), 500);
                    if (label.isEmpty()) label = "Saved coordinate";
                    String noteText = boundedText(notes.getText().toString().trim(), 20_000);
                    WaypointEntity waypoint = new WaypointEntity(
                            result.latitude, result.longitude, MANUAL_COORDINATE_ACCURACY, now,
                            label, noteText, now, now);
                    waypointRepository.insert(waypoint, () -> {
                        refreshWaypoints();
                        showMessage(mapController.isWaypointsVisible()
                                ? "Saved Location created."
                                : "Saved Location created. Turn on Saved Locations in Layers to see it.");
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveLocation() {
        pendingLocationAction = LOCATION_ACTION_SAVE;
        if (!ensureLocationPermission(true)) return;
        pendingLocationAction = LOCATION_ACTION_NONE;
        if (!locationRepository.hasFinePermission()) {
            new AlertDialog.Builder(this)
                    .setTitle("Precise location required")
                    .setMessage("RockMap will not save a field waypoint from Android's approximate-location permission. Enable precise location, then try again.")
                    .setPositiveButton("App settings", (d, w) -> openAppSettings())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        locationRepository.requestFreshPrecise(this::showSaveDialog, this::showMessage);
    }

    private void showSaveDialog(Location location) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);

        TextView fix = new TextView(this);
        String accuracy = location.hasAccuracy()
                ? String.format(Locale.US, "Reported GPS accuracy: ±%.1f m", location.getAccuracy())
                : "GPS did not report an accuracy estimate.";
        fix.setText(accuracy + "\n" + String.format(Locale.US, "%.6f, %.6f",
                location.getLatitude(), location.getLongitude()));
        box.addView(fix);

        EditText name = new EditText(this);
        name.setHint("Name (for example: quartz vein)");
        box.addView(name);
        EditText notes = new EditText(this);
        notes.setHint("Notes (optional)");
        notes.setMinLines(2);
        notes.setMaxLines(5);
        box.addView(notes);

        String warning = location.hasAccuracy() && location.getAccuracy() > 50f
                ? "\n\nThis GPS fix is relatively imprecise. The exact accuracy estimate above will be saved with the waypoint."
                : "";

        new AlertDialog.Builder(this)
                .setTitle("Save current GPS location")
                .setMessage(warning.isEmpty() ? null : warning.trim())
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    long now = System.currentTimeMillis();
                    String label = boundedText(name.getText().toString().trim(), 500);
                    if (label.isEmpty()) label = "Saved location";
                    String noteText = boundedText(notes.getText().toString().trim(), 20_000);
                    float savedAccuracy = location.hasAccuracy() && Float.isFinite(location.getAccuracy())
                            && location.getAccuracy() >= 0f ? location.getAccuracy() : -1f;
                    WaypointEntity waypoint = new WaypointEntity(
                            location.getLatitude(), location.getLongitude(), savedAccuracy,
                            location.getTime() > 0 ? location.getTime() : now,
                            label, noteText, now, now);
                    waypointRepository.insert(waypoint, () -> {
                        refreshWaypoints();
                        showMessage("Location saved.");
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLayers() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(12));
        boolean landAvailable = mapController.hasLandStatusAvailable();
        boolean claimsAvailable = mapController.hasClaimsAvailable();
        boolean historicMinesAvailable = mineralIndexRepository.hasExpandedEvidence();
        boolean geologyResultAvailable = geologyOverlayController != null && geologyOverlayController.hasResults();

        box.addView(sectionLabel("Visible Map Layers"));
        box.addView(helperText("Check what you want shown on the map, then tap Apply."));

        CheckBox land = checkbox(landAvailable
                        ? "Land status — BLM Colorado SMA" : "Land status — unavailable",
                landAvailable && mapController.isLandVisible());
        CheckBox claims = checkbox(claimsAvailable
                        ? "Mining claims — BLM MLRS not closed" : "Mining claims — unavailable in current test",
                claimsAvailable && mapController.isClaimsVisible());

        String historicMineLabel = historicMinesAvailable
                ? "Historic Mines & Workings — USGS / CGS"
                    + (historicMineOverlayController.isLoaded()
                    ? " — " + historicMineOverlayController.getRecordCount() : "")
                : "Historic Mines & Workings — unavailable";
        CheckBox historicMines = checkbox(historicMineLabel,
                historicMinesAvailable && historicMinesRequestedVisible);
        historicMines.setContentDescription("Historic mines / workings — USGS / CGS");

        CheckBox geology = checkbox(geologyResultAvailable
                        ? "Geology Result — " + geologyOverlayController.getCount() + " mapped areas"
                        : "Geology Result — none loaded",
                geologyResultAvailable && geologyOverlayController.isVisible());

        CheckBox saved = checkbox("Saved Locations", mapController.isWaypointsVisible());
        boolean mineralsAvailable = mineralOverlayController.hasResults();
        CheckBox minerals = checkbox(
                mineralsAvailable
                        ? "Mineral Evidence Results — " + mineralOverlayController.getResultCount()
                        : "Mineral Evidence Results — none loaded",
                mineralsAvailable && mineralOverlayController.isVisible());

        boolean heatmapAvailable = mineralOverlayController.hasHeatmap();
        String heatmapLabel = heatmapAvailable
                ? "Mineral Evidence Heatmap — " + mineralOverlayController.getHeatmapLabel()
                    + " — " + mineralOverlayController.getHeatmapPointCount() + " points"
                : "Mineral Evidence Heatmap — none loaded";
        CheckBox heatmap = checkbox(heatmapLabel,
                heatmapAvailable && mineralOverlayController.isHeatmapVisible());

        land.setEnabled(landAvailable);
        claims.setEnabled(claimsAvailable);
        historicMines.setEnabled(historicMinesAvailable);
        geology.setEnabled(geologyResultAvailable);
        minerals.setEnabled(mineralsAvailable);
        heatmap.setEnabled(heatmapAvailable);

        box.addView(land);
        box.addView(claims);
        box.addView(historicMines);
        box.addView(geology);
        box.addView(saved);
        box.addView(minerals);
        box.addView(heatmap);

        if (geologyResultAvailable) {
            Button clearGeologyButton = smallActionButton("Clear Geology Result");
            clearGeologyButton.setOnClickListener(v -> {
                geologyOverlayController.clear();
                geology.setChecked(false);
                geology.setEnabled(false);
                geology.setText("Geology Result — none loaded");
                clearGeologyButton.setEnabled(false);
            });
            box.addView(clearGeologyButton);
        }

        if (mineralsAvailable || heatmapAvailable || activeMineralAreaAnalysis != null) {
            Button clearMineralsButton = smallActionButton("Clear Mineral Evidence");
            clearMineralsButton.setContentDescription("Clear minerals from the map");
            clearMineralsButton.setOnClickListener(v -> {
                clearMinerals();
                minerals.setChecked(false);
                minerals.setEnabled(false);
                minerals.setText("Mineral Evidence Results — none loaded");
                heatmap.setChecked(false);
                heatmap.setEnabled(false);
                heatmap.setText("Mineral Evidence Heatmap — none loaded");
                clearMineralsButton.setEnabled(false);
            });
            box.addView(clearMineralsButton);
        }

        if (heatmapAvailable || geologyResultAvailable || landAvailable || claimsAvailable || historicMinesAvailable) {
            box.addView(sectionLabel("Legend & safety notes"));
        }
        if (landAvailable) addLandStatusLegend(box);
        if (claimsAvailable) addMiningClaimsLegend(box);
        if (historicMinesAvailable) addHistoricMinesLegend(box);
        if (heatmapAvailable) addMineralHeatmapLegend(box);
        if (geologyResultAvailable) addGeologyLegend(box);

        new AlertDialog.Builder(this)
                .setTitle("Layers")
                .setView(boundedScrollableContent(box, 480))
                .setPositiveButton("Apply", (d, w) -> {
                    mapController.setLandVisible(landAvailable && land.isChecked());
                    mapController.setClaimsVisible(claimsAvailable && claims.isChecked());
                    mapController.setWaypointsVisible(saved.isChecked());
                    mineralOverlayController.setVisible(
                            mineralOverlayController.hasResults() && minerals.isChecked());
                    mineralOverlayController.setHeatmapVisible(
                            mineralOverlayController.hasHeatmap() && heatmap.isChecked());
                    setHistoricMinesVisible(historicMinesAvailable && historicMines.isChecked());
                    geologyOverlayController.setVisible(geologyResultAvailable && geology.isChecked());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addGeologyLegend(LinearLayout box) {
        TextView title = new TextView(this);
        title.setText("Geology Result");
        title.setTextSize(16f);
        title.setTextColor(Color.rgb(35, 35, 35));
        title.setPadding(0, dp(12), 0, dp(2));
        box.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(1), 0, dp(3));
        View swatch = new View(this);
        GradientDrawable swatchBackground = new GradientDrawable();
        swatchBackground.setColor(GeologyOverlayController.FILL_COLOR);
        swatchBackground.setStroke(dp(2), GeologyOverlayController.OUTLINE_COLOR);
        swatchBackground.setCornerRadius(dp(2));
        swatch.setBackground(swatchBackground);
        LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        swatchParams.setMargins(0, 0, dp(10), 0);
        row.addView(swatch, swatchParams);
        TextView label = new TextView(this);
        label.setText("Current geology result");
        label.setTextSize(13f);
        label.setTextColor(Color.rgb(45, 45, 45));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);

        TextView note = new TextView(this);
        note.setText("Muted green mapped areas with a darker green outline show the current Geology result. The map fill is translucent, so its exact appearance varies slightly over the basemap. These are interpretive geologic mapping, not property, access, hazard, or claim boundaries. Land status, mining claims, and Saved Locations remain separate layers above them.");
        note.setTextSize(12f);
        note.setTextColor(Color.rgb(75, 75, 75));
        note.setPadding(0, 0, 0, dp(6));
        box.addView(note);
    }

    private void addMineralHeatmapLegend(LinearLayout box) {
        TextView title = new TextView(this);
        title.setText("Mineral Evidence Heatmap");
        title.setTextSize(16f);
        title.setTextColor(Color.rgb(35, 35, 35));
        title.setPadding(0, dp(12), 0, dp(2));
        box.addView(title);

        TextView scale = new TextView(this);
        scale.setText("Lower evidence density  →  Higher evidence density");
        scale.setTextSize(12.5f);
        scale.setTextColor(Color.rgb(55, 55, 55));
        scale.setPadding(0, 0, 0, dp(2));
        box.addView(scale);

        TextView note = new TextView(this);
        note.setText("Cooler/less intense areas have less nearby installed evidence; hotter areas have denser and/or stronger source evidence. Direct occurrence/locality records contribute more than broad district or abandoned-mine records. This is not a probability of finding specimens. The orange rectangle is the area that was analyzed.");
        note.setTextSize(12f);
        note.setTextColor(Color.rgb(75, 75, 75));
        note.setPadding(0, 0, 0, dp(6));
        box.addView(note);
    }

    private void addLandStatusLegend(LinearLayout box) {
        TextView title = new TextView(this);
        title.setText("Land status legend");
        title.setTextSize(16f);
        title.setTextColor(Color.rgb(35, 35, 35));
        title.setPadding(0, dp(12), 0, dp(2));
        box.addView(title);

        TextView note = new TextView(this);
        note.setText("BLM Colorado Surface Management Agency categories. These are broad management/status polygons, not parcel ownership or legal boundaries. Map fills are translucent, so the hue can look different over the basemap. Tap a colored polygon for its category and source manager/name.");
        note.setTextSize(12f);
        note.setTextColor(Color.rgb(75, 75, 75));
        note.setPadding(0, 0, 0, dp(6));
        box.addView(note);

        for (LandStatusCatalog.Entry entry : LandStatusCatalog.entries()) {
            addLandStatusLegendRow(box, entry);
        }
    }

    private void addLandStatusLegendRow(LinearLayout box, LandStatusCatalog.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(1), 0, dp(1));

        TextView swatch = new TextView(this);
        swatch.setText("■");
        swatch.setTextSize(23f);
        swatch.setTextColor(Color.parseColor(entry.colorHex));
        row.addView(swatch, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = new TextView(this);
        label.setText(entry.label + "  [" + entry.code + "]");
        label.setTextSize(13f);
        label.setTextColor(Color.rgb(45, 45, 45));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
    }

    private void addMiningClaimsLegend(LinearLayout box) {
        TextView title = new TextView(this);
        title.setText("Mining claims legend");
        title.setTextSize(16f);
        title.setTextColor(Color.rgb(35, 35, 35));
        title.setPadding(0, dp(14), 0, dp(2));
        box.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(1), 0, dp(1));
        TextView swatch = new TextView(this);
        swatch.setText("■");
        swatch.setTextSize(23f);
        swatch.setTextColor(Color.parseColor(MiningClaimCatalog.COLOR_HEX));
        row.addView(swatch, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView label = new TextView(this);
        label.setText(MiningClaimCatalog.LEGEND_LABEL);
        label.setTextSize(13f);
        label.setTextColor(Color.rgb(45, 45, 45));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);

        TextView note = new TextView(this);
        note.setText("Magenta means the BLM MLRS source reports a mining-claim case whose disposition is not closed. It does not mean RockMap independently verified that the claim is active, valid, or surveyed to the displayed polygon. BLM notes that some cases cannot be geocoded and may have no map geometry. County-only quality-score 25 geometry is intentionally excluded because it is too coarse for a claim-footprint overlay. Tap a claim for its type, serial, disposition, acreage, and mapping-quality note.");
        note.setTextSize(12f);
        note.setTextColor(Color.rgb(75, 75, 75));
        note.setPadding(0, dp(2), 0, dp(6));
        box.addView(note);
    }

    private void addHistoricMinesLegend(LinearLayout box) {
        TextView title = new TextView(this);
        title.setText("Historic Mines & Workings");
        title.setTextSize(16f);
        title.setTextColor(Color.rgb(35, 35, 35));
        title.setPadding(0, dp(14), 0, dp(2));
        box.addView(title);

        LinearLayout propertyRow = new LinearLayout(this);
        propertyRow.setOrientation(LinearLayout.HORIZONTAL);
        propertyRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView propertySwatch = new TextView(this);
        propertySwatch.setText("●");
        propertySwatch.setTextSize(22f);
        propertySwatch.setTextColor(Color.rgb(139, 92, 47));
        propertyRow.addView(propertySwatch,
                new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView propertyLabel = new TextView(this);
        propertyLabel.setText("Mine / mineral property — USGS MAS/MILS or CGS MS-17");
        propertyLabel.setTextSize(12.5f);
        propertyRow.addView(propertyLabel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(propertyRow);

        LinearLayout openingRow = new LinearLayout(this);
        openingRow.setOrientation(LinearLayout.HORIZONTAL);
        openingRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView openingSwatch = new TextView(this);
        openingSwatch.setText("●");
        openingSwatch.setTextSize(22f);
        openingSwatch.setTextColor(Color.rgb(70, 70, 70));
        openingRow.addView(openingSwatch,
                new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView openingLabel = new TextView(this);
        openingLabel.setText("Abandoned-mine opening — CGS/USFS AML inventory");
        openingLabel.setTextSize(12.5f);
        openingRow.addView(openingLabel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(openingRow);

        TextView note = new TextView(this);
        note.setText("Mine points are source-record locations, not surveyed portals. A source with no documented mineral field is shown as unknown, not mineral-free. Tap a point for mapped coordinates, source/reliability, land status, nearby direct mineral evidence, and Save Location / note. Do not enter abandoned mine openings or workings.");
        note.setTextSize(12f);
        note.setTextColor(Color.rgb(75, 75, 75));
        note.setPadding(0, dp(2), 0, dp(6));
        box.addView(note);
    }

    private CheckBox checkbox(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextSize(13f);
        box.setChecked(checked);
        box.setMinHeight(dp(48));
        box.setMinimumHeight(dp(48));
        box.setPadding(0, dp(4), 0, dp(4));
        return box;
    }

    private void showTrips() {
        tripRepository.getSummaries(summaries -> {
            if (summaries.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Trips")
                        .setMessage("No trips yet. Create one, then add Find results, GPS coordinates, or Saved Locations to it.")
                        .setPositiveButton("New trip", (d, w) -> showCreateTripDialog(null))
                        .setNegativeButton("Close", null)
                        .show();
                return;
            }
            ArrayList<ActionListItem> rows = new ArrayList<>();
            for (TripSummary summary : summaries) {
                rows.add(new ActionListItem(
                        summary.name == null || summary.name.trim().isEmpty() ? "Untitled trip" : summary.name.trim(),
                        tripSummaryDetail(summary), "OPEN"));
            }
            showActionListDialog(
                    "Trips", "Tap a trip to open its saved stops and planning tools.", rows,
                    "New trip", (d, w) -> showCreateTripDialog(null),
                    null, null, "Close", null,
                    which -> showTripDetail(summaries.get(which).toEntity()));
        });
    }

    private String tripSummaryDetail(TripSummary summary) {
        StringBuilder out = new StringBuilder();
        if (summary.plannedDate != null && !summary.plannedDate.trim().isEmpty()) {
            out.append(summary.plannedDate.trim()).append(" · ");
        }
        out.append(summary.itemCount)
                .append(summary.itemCount == 1 ? " saved stop" : " saved stops");
        return out.toString();
    }

    private String tripSummaryLabel(TripSummary summary) {
        StringBuilder out = new StringBuilder(summary.name == null || summary.name.trim().isEmpty()
                ? "Untitled trip" : summary.name.trim());
        if (summary.plannedDate != null && !summary.plannedDate.trim().isEmpty()) {
            out.append("\n").append(summary.plannedDate.trim());
        }
        out.append("\n").append(summary.itemCount)
                .append(summary.itemCount == 1 ? " saved stop" : " saved stops");
        return out.toString();
    }

    private void showCreateTripDialog(java.util.function.Consumer<TripEntity> afterCreate) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        EditText name = new EditText(this);
        name.setHint("Trip name");
        name.setSingleLine(true);
        box.addView(name);

        EditText date = new EditText(this);
        date.setHint("Date / timeframe (optional)");
        date.setSingleLine(true);
        box.addView(date);

        EditText notes = new EditText(this);
        notes.setHint("Trip notes (optional)");
        notes.setMinLines(3);
        notes.setMaxLines(5);
        box.addView(notes);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New trip")
                .setView(box)
                .setPositiveButton("Create", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String tripName = boundedText(name.getText().toString().trim(), 500);
                    if (tripName.isEmpty()) {
                        name.setError("Enter a trip name.");
                        return;
                    }
                    long now = System.currentTimeMillis();
                    TripEntity trip = new TripEntity(
                            tripName,
                            boundedText(date.getText().toString().trim(), 500),
                            boundedText(notes.getText().toString().trim(), 20_000),
                            now, now);
                    dialog.dismiss();
                    tripRepository.create(trip, created -> {
                        if (afterCreate != null) afterCreate.accept(created);
                        else showTripDetail(created);
                    });
                }));
        dialog.show();
        name.requestFocus();
    }

    private void showEditTripDialog(TripEntity trip) {
        if (trip == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        EditText name = new EditText(this);
        name.setHint("Trip name");
        name.setText(trip.name);
        name.setSingleLine(true);
        box.addView(name);

        EditText date = new EditText(this);
        date.setHint("Date / timeframe (optional)");
        date.setText(trip.plannedDate);
        date.setSingleLine(true);
        box.addView(date);

        EditText notes = new EditText(this);
        notes.setHint("Trip notes (optional)");
        notes.setText(trip.notes);
        notes.setMinLines(3);
        notes.setMaxLines(5);
        box.addView(notes);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit trip")
                .setView(box)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", (d, w) -> showTripDetail(trip))
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String tripName = boundedText(name.getText().toString().trim(), 500);
                    if (tripName.isEmpty()) {
                        name.setError("Enter a trip name.");
                        return;
                    }
                    trip.name = tripName;
                    trip.plannedDate = boundedText(date.getText().toString().trim(), 500);
                    trip.notes = boundedText(notes.getText().toString().trim(), 20_000);
                    dialog.dismiss();
                    tripRepository.update(trip, () -> showTripDetail(trip));
                }));
        dialog.show();
    }

    private void showTripDetail(TripEntity trip) {
        if (trip == null) return;
        tripRepository.getItems(trip.id, items -> {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(12), dp(2), dp(12), dp(4));

            TextView summary = new TextView(this);
            StringBuilder summaryText = new StringBuilder();
            if (trip.plannedDate != null && !trip.plannedDate.trim().isEmpty()) {
                summaryText.append(trip.plannedDate.trim()).append("\n");
            }
            summaryText.append(items.size()).append(items.size() == 1 ? " saved stop" : " saved stops");
            if (trip.notes != null && !trip.notes.trim().isEmpty()) {
                summaryText.append("\n\n").append(trip.notes.trim());
            }
            summary.setText(summaryText.toString());
            summary.setTextSize(12.5f);
            summary.setTextColor(Color.rgb(65, 65, 65));
            summary.setPadding(dp(8), 0, dp(8), dp(3));
            box.addView(boundedScrollableContent(summary, 130));
            if (!items.isEmpty()) {
                TextView listHint = helperText("Tap a stop for map, reorder, or remove options.");
                listHint.setPadding(dp(8), 0, dp(8), dp(6));
                box.addView(listHint);
            }

            ArrayList<ActionListItem> labels = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                TripItemEntity item = items.get(i);
                labels.add(new ActionListItem(
                        (i + 1) + ". " + (item.name == null || item.name.trim().isEmpty()
                                ? "Unnamed stop" : item.name.trim()),
                        compactTripItemDetail(item), "DETAILS"));
            }
            ListView list = new ListView(this);
            list.setAdapter(actionListAdapter(labels));
            int preferredListDp = items.isEmpty() ? 72 : Math.min(360, Math.max(90, items.size() * 72));
            box.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight(preferredListDp)));

            if (items.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("No stops yet. Add a named place/coordinate or one of your Saved Locations.");
                empty.setTextSize(12f);
                empty.setTextColor(Color.rgb(85, 85, 85));
                empty.setPadding(dp(8), 0, dp(8), dp(8));
                box.addView(empty);
            }

            LinearLayout addRow = new LinearLayout(this);
            addRow.setOrientation(LinearLayout.HORIZONTAL);
            Button addPlace = smallActionButton("Add place / GPS");
            Button addMarker = smallActionButton("Add Saved Location");
            addRow.addView(addPlace, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            addRow.addView(addMarker, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(addRow);

            LinearLayout manageRow = new LinearLayout(this);
            manageRow.setOrientation(LinearLayout.HORIZONTAL);
            Button export = smallActionButton("Export");
            Button edit = smallActionButton("Edit trip");
            Button delete = smallActionButton("Delete");
            delete.setTextColor(Color.rgb(155, 35, 35));
            manageRow.addView(export, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            manageRow.addView(edit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            manageRow.addView(delete, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(manageRow);

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(trip.name)
                    .setView(box)
                    .setNegativeButton("Close", null)
                    .create();

            list.setOnItemClickListener((parent, view, position, id) -> {
                if (position < 0 || position >= items.size()) return;
                showTripItemDetail(trip, items.get(position), position, items.size(), dialog);
            });
            addPlace.setOnClickListener(v -> {
                dialog.dismiss();
                showTripFindSearch(trip);
            });
            addMarker.setOnClickListener(v -> {
                dialog.dismiss();
                showAddSavedMarkerToTrip(trip);
            });
            export.setEnabled(!items.isEmpty());
            export.setOnClickListener(v -> {
                dialog.dismiss();
                showTripExportPicker(trip);
            });
            edit.setOnClickListener(v -> {
                dialog.dismiss();
                showEditTripDialog(trip);
            });
            delete.setOnClickListener(v -> {
                dialog.dismiss();
                confirmDeleteTrip(trip);
            });
            dialog.show();
        });
    }

    private String tripItemLabel(TripItemEntity item, int index) {
        StringBuilder label = new StringBuilder()
                .append(index + 1).append(". ")
                .append(item.name == null || item.name.trim().isEmpty() ? "Unnamed stop" : item.name.trim());
        String detail = compactTripItemDetail(item);
        if (!detail.isEmpty()) label.append("\n").append(detail);
        return label.toString();
    }

    private String compactTripItemDetail(TripItemEntity item) {
        String kind = item.kind == null ? "" : item.kind.trim();
        String context = item.context == null ? "" : item.context.trim();
        if (kind.isEmpty()) return context;
        if (context.isEmpty()) return kind;
        return kind + " · " + context;
    }

    private void showTripItemDetail(TripEntity trip, TripItemEntity item,
                                    int position, int total, AlertDialog parentDialog) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        TextView body = new TextView(this);
        StringBuilder text = new StringBuilder();
        String detail = compactTripItemDetail(item);
        if (!detail.isEmpty()) text.append(detail).append("\n");
        text.append(String.format(Locale.US, "%.6f, %.6f", item.latitude, item.longitude));
        if (item.notes != null && !item.notes.trim().isEmpty()) {
            text.append("\n\n").append(item.notes.trim());
        }
        body.setText(text.toString());
        body.setTextSize(13f);
        body.setPadding(0, 0, 0, dp(8));
        box.addView(boundedScrollableContent(body, 220));

        LinearLayout mapRow = new LinearLayout(this);
        mapRow.setOrientation(LinearLayout.HORIZONTAL);
        Button showMap = smallActionButton("Show on Map");
        Button up = smallActionButton("Move up");
        Button down = smallActionButton("Move down");
        mapRow.addView(showMap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mapRow.addView(up, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mapRow.addView(down, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(mapRow);

        Button remove = smallActionButton("Remove from trip");
        remove.setTextColor(Color.rgb(155, 35, 35));
        box.addView(remove, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog itemDialog = new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setView(box)
                .setNegativeButton("Close", null)
                .create();

        up.setEnabled(position > 0);
        down.setEnabled(position + 1 < total);
        showMap.setOnClickListener(v -> {
            itemDialog.dismiss();
            if (parentDialog != null) parentDialog.dismiss();
            Location target = new Location("trip-item");
            target.setLatitude(item.latitude);
            target.setLongitude(item.longitude);
            mapController.centerOn(target);
        });
        up.setOnClickListener(v -> {
            itemDialog.dismiss();
            if (parentDialog != null) parentDialog.dismiss();
            tripRepository.moveItem(trip.id, item.id, -1, moved -> showTripDetail(trip));
        });
        down.setOnClickListener(v -> {
            itemDialog.dismiss();
            if (parentDialog != null) parentDialog.dismiss();
            tripRepository.moveItem(trip.id, item.id, 1, moved -> showTripDetail(trip));
        });
        remove.setOnClickListener(v -> {
            itemDialog.dismiss();
            if (parentDialog != null) parentDialog.dismiss();
            new AlertDialog.Builder(this)
                    .setTitle("Remove stop from trip?")
                    .setMessage(item.name + " will be removed from " + trip.name + ". The original Saved Location or RockMap source data is not deleted.")
                    .setPositiveButton("Remove", (d, w) ->
                            tripRepository.deleteItem(item, () -> showTripDetail(trip)))
                    .setNegativeButton("Cancel", (d, w) -> showTripDetail(trip))
                    .show();
        });
        itemDialog.show();
    }

    private void showTripPickerForPlace(PlaceRecord record) {
        if (record == null) return;
        tripRepository.getSummaries(summaries -> {
            if (summaries.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Create a trip first?")
                        .setMessage("RockMap can create a trip and add " + record.name + " to it immediately.")
                        .setPositiveButton("New trip", (d, w) ->
                                showCreateTripDialog(trip -> addPlaceToTrip(trip, record, false)))
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            ArrayList<ActionListItem> rows = new ArrayList<>();
            for (TripSummary summary : summaries) {
                rows.add(new ActionListItem(
                        summary.name == null || summary.name.trim().isEmpty() ? "Untitled trip" : summary.name.trim(),
                        tripSummaryDetail(summary), "ADD"));
            }
            showActionListDialog(
                    "Add " + record.name + " to trip",
                    "Tap the trip that should receive this place.", rows,
                    "New trip", (d, w) ->
                            showCreateTripDialog(trip -> addPlaceToTrip(trip, record, false)),
                    null, null, "Cancel", null,
                    which -> addPlaceToTrip(summaries.get(which).toEntity(), record, false));
        });
    }

    private void addPlaceToTrip(TripEntity trip, PlaceRecord record, boolean reopenTrip) {
        long now = System.currentTimeMillis();
        String ref = "place:" + record.name + ":" + String.format(
                Locale.US, "%.6f,%.6f", record.latitude, record.longitude);
        TripItemEntity item = new TripItemEntity(
                trip.id, boundedText(record.name, 500), boundedText(record.kind, 500),
                boundedText(record.context, 2_000), record.latitude, record.longitude, "",
                "place", boundedText(ref, 2_000), 0, now);
        tripRepository.addItem(item, added -> {
            showMessage(record.name + " added to " + trip.name + ".");
            if (reopenTrip) showTripDetail(trip);
        });
    }

    private void showTripFindSearch(TripEntity trip) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        TextView help = new TextView(this);
        help.setText("Add a stop to " + trip.name + ". Name search is best for Colorado towns/localities, peaks and mountain features, and named lakes/reservoirs. For the most accurate stop, paste known GPS latitude/longitude coordinates.");
        help.setTextSize(13f);
        help.setTextColor(Color.rgb(65, 65, 65));
        help.setPadding(0, 0, 0, dp(8));
        box.addView(help);

        EditText input = new EditText(this);
        input.setHint("Town, peak, lake, or coordinates");
        input.setSingleLine(true);
        box.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Add stop")
                .setView(box)
                .setPositiveButton("Find", null)
                .setNegativeButton("Back", (d, w) -> showTripDetail(trip))
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String query = input.getText().toString().trim();
                    if (query.length() < 2) {
                        input.setError("Enter at least 2 characters.");
                        return;
                    }
                    if (looksLikeCoordinates(query)) {
                        try {
                            CoordinateParser.Result result = CoordinateParser.parse(query);
                            dialog.dismiss();
                            showAddCoordinateToTrip(trip, result);
                        } catch (IllegalArgumentException ex) {
                            input.setError(ex.getMessage());
                        }
                        return;
                    }
                    Button find = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    find.setEnabled(false);
                    find.setText("Searching…");
                    placeIndexRepository.search(query, PLACE_SEARCH_LIMIT, new PlaceIndexRepository.Callback() {
                        @Override public void onResult(List<PlaceSearchEngine.Match> matches) {
                            find.setEnabled(true);
                            find.setText("Find");
                            dialog.dismiss();
                            showTripPlaceSearchResults(trip, query, matches);
                        }

                        @Override public void onError(String message) {
                            find.setEnabled(true);
                            find.setText("Find");
                            input.setError(message == null ? "Offline place search failed." : message);
                        }
                    });
                }));
        dialog.show();
        input.requestFocus();
    }

    private void showTripPlaceSearchResults(TripEntity trip, String query,
                                            List<PlaceSearchEngine.Match> matches) {
        ArrayList<PlaceSearchEngine.Match> supported = new ArrayList<>();
        if (matches != null) {
            for (PlaceSearchEngine.Match match : matches) {
                if (match != null && isSupportedFindResult(match.record)) supported.add(match);
            }
        }
        if (supported.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No supported place match")
                    .setMessage("RockMap did not find a dependable named-place result for “" + query
                            + "”. For a precise trip stop, paste known GPS coordinates instead.")
                    .setPositiveButton("Try again", (d, w) -> showTripFindSearch(trip))
                    .setNegativeButton("Trip", (d, w) -> showTripDetail(trip))
                    .show();
            return;
        }

        ArrayList<ActionListItem> rows = new ArrayList<>();
        for (PlaceSearchEngine.Match match : supported) {
            PlaceRecord record = match.record;
            String detail = record.kind
                    + (record.context == null || record.context.isEmpty() ? "" : " · " + record.context);
            rows.add(new ActionListItem(record.name, detail, "ADD"));
        }
        showActionListDialog(
                "Add: " + query, "Tap a place to add it to " + trip.name + ".", rows,
                "Search again", (d, w) -> showTripFindSearch(trip),
                null, null, "Trip", (d, w) -> showTripDetail(trip),
                which -> addPlaceToTrip(trip, supported.get(which).record, true));
    }

    private void showAddCoordinateToTrip(TripEntity trip, CoordinateParser.Result result) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), 0);

        TextView coordinate = new TextView(this);
        coordinate.setText(result.formatDecimal());
        coordinate.setTextSize(13f);
        coordinate.setPadding(0, 0, 0, dp(6));
        box.addView(coordinate);

        EditText name = new EditText(this);
        name.setHint("Stop name");
        name.setText("Coordinate stop");
        box.addView(name);

        EditText notes = new EditText(this);
        notes.setHint("Notes (optional)");
        notes.setMinLines(3);
        notes.setMaxLines(5);
        box.addView(notes);

        new AlertDialog.Builder(this)
                .setTitle("Add coordinate to " + trip.name)
                .setView(box)
                .setPositiveButton("Add", (d, w) -> {
                    long now = System.currentTimeMillis();
                    String enteredLabel = boundedText(name.getText().toString().trim(), 500);
                    final String label = enteredLabel.isEmpty() ? "Coordinate stop" : enteredLabel;
                    String ref = String.format(Locale.US, "coordinate:%.7f,%.7f",
                            result.latitude, result.longitude);
                    TripItemEntity item = new TripItemEntity(
                            trip.id, label, "GPS coordinate", "User-supplied coordinates",
                            result.latitude, result.longitude,
                            boundedText(notes.getText().toString().trim(), 20_000),
                            "coordinate", ref, 0, now);
                    tripRepository.addItem(item, added -> {
                        showMessage(label + " added to " + trip.name + ".");
                        showTripDetail(trip);
                    });
                })
                .setNegativeButton("Back", (d, w) -> showTripFindSearch(trip))
                .show();
    }

    private void showAddSavedMarkerToTrip(TripEntity trip) {
        waypointRepository.getAll(items -> {
            if (items.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("No Saved Locations")
                        .setMessage("Save a GPS or map location first, then add it to this trip.")
                        .setPositiveButton("Trip", (d, w) -> showTripDetail(trip))
                        .show();
                return;
            }
            ArrayList<ActionListItem> rows = new ArrayList<>();
            for (WaypointEntity w : items) {
                rows.add(new ActionListItem(
                        w.name, String.format(Locale.US, "%.5f, %.5f", w.latitude, w.longitude), "ADD"));
            }
            showActionListDialog(
                    "Add Saved Location to " + trip.name,
                    "Tap the saved location you want to add.", rows,
                    null, null, null, null, "Trip", (d, w) -> showTripDetail(trip),
                    which -> {
                        WaypointEntity w = items.get(which);
                        long now = System.currentTimeMillis();
                        TripItemEntity item = new TripItemEntity(
                                trip.id, boundedText(w.name, 500), "Saved Location", "RockMap Saved Location",
                                w.latitude, w.longitude, boundedText(w.notes, 20_000),
                                "waypoint", "waypoint:" + w.id, 0, now);
                        tripRepository.addItem(item, added -> {
                            showMessage(w.name + " added to " + trip.name + ".");
                            showTripDetail(trip);
                        });
                    });
        });
    }

    private void confirmDeleteTrip(TripEntity trip) {
        new AlertDialog.Builder(this)
                .setTitle("Delete trip?")
                .setMessage(trip.name + " and its trip list will be deleted. Original Saved Locations are not deleted.")
                .setPositiveButton("Delete", (d, w) ->
                        tripRepository.delete(trip, this::showTrips))
                .setNegativeButton("Cancel", (d, w) -> showTripDetail(trip))
                .show();
    }

    private void showTripExportPicker(TripEntity trip) {
        ArrayList<ActionListItem> rows = new ArrayList<>();
        rows.add(new ActionListItem(
                "CSV — spreadsheet / easy editing",
                "Keeps stop order, names, coordinates, type/context, notes, source references, and trip details in columns.",
                "SAVE"));
        rows.add(new ActionListItem(
                "RockMap XML — readable full trip file",
                "Keeps trip details plus stop order, coordinates, notes, type/context, and RockMap source references.",
                "SAVE"));
        rows.add(new ActionListItem(
                "GPX — GPS / mapping apps",
                "Keeps coordinates, stop names, type/context/notes, and trip description. RockMap source references are not preserved.",
                "SAVE"));
        rows.add(new ActionListItem(
                "GeoJSON — GIS / mapping software",
                "Keeps full trip/stop planning metadata and point geometry, including RockMap source references.",
                "SAVE"));
        showActionListDialog(
                "Export " + trip.name,
                "Choose a file format. Each row below is tappable; the description tells you what that format preserves.",
                rows, null, null, null, null,
                "Back", (d, w) -> showTripDetail(trip),
                which -> {
                    if (which == 0) beginTripExport(trip, EXPORT_TRIP_CSV_REQUEST);
                    else if (which == 1) beginTripExport(trip, EXPORT_TRIP_XML_REQUEST);
                    else if (which == 2) beginTripExport(trip, EXPORT_TRIP_GPX_REQUEST);
                    else beginTripExport(trip, EXPORT_TRIP_GEOJSON_REQUEST);
                });
    }

    private void beginTripExport(TripEntity trip, int requestCode) {
        pendingTripExportId = trip.id;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        String base = safeExportFilename(trip.name);
        if (requestCode == EXPORT_TRIP_GPX_REQUEST) {
            intent.setType("application/gpx+xml");
            intent.putExtra(Intent.EXTRA_TITLE, base + ".gpx");
        } else if (requestCode == EXPORT_TRIP_CSV_REQUEST) {
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_TITLE, base + ".csv");
        } else if (requestCode == EXPORT_TRIP_XML_REQUEST) {
            intent.setType("application/xml");
            intent.putExtra(Intent.EXTRA_TITLE, base + ".xml");
        } else {
            intent.setType("application/geo+json");
            intent.putExtra(Intent.EXTRA_TITLE, base + ".geojson");
        }
        startActivityForResult(intent, requestCode);
    }

    private void exportTrip(Uri uri, int requestCode) {
        long tripId = pendingTripExportId;
        pendingTripExportId = -1L;
        if (tripId <= 0) {
            showMessage("Trip export could not identify the selected trip.");
            return;
        }
        tripRepository.getTrip(tripId, trip -> {
            if (trip == null) {
                showMessage("That trip no longer exists.");
                return;
            }
            tripRepository.getItems(tripId, items -> {
                String content;
                String label;
                if (requestCode == EXPORT_TRIP_GPX_REQUEST) {
                    content = TripExport.gpx(trip, items);
                    label = "GPX";
                } else if (requestCode == EXPORT_TRIP_CSV_REQUEST) {
                    content = TripExport.csv(trip, items);
                    label = "CSV";
                } else if (requestCode == EXPORT_TRIP_XML_REQUEST) {
                    content = TripExport.rockMapXml(trip, items);
                    label = "RockMap XML";
                } else {
                    content = TripExport.geoJson(trip, items);
                    label = "GeoJSON";
                }
                try {
                    ContentResolver resolver = getContentResolver();
                    try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                        if (output == null) throw new IOException("Android could not open the selected export file.");
                        output.write(content.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    }
                    showMessage("Exported " + trip.name + " as " + label + ".");
                } catch (IOException ex) {
                    showMessage("Trip export failed: " + ex.getMessage());
                }
            });
        });
    }

    private String safeExportFilename(String value) {
        String text = value == null ? "RockMap-Trip" : value.trim();
        if (text.isEmpty()) text = "RockMap-Trip";
        text = text.replaceAll("[^A-Za-z0-9._ -]+", "-").replaceAll("\\s+", "-");
        if (text.length() > 80) text = text.substring(0, 80);
        return text.isEmpty() ? "RockMap-Trip" : text;
    }

    private void showSaved() {
        waypointRepository.getAll(items -> {
            if (items.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("Saved Locations")
                        .setMessage("No Saved Locations yet. You can import a RockMap GeoJSON backup.")
                        .setPositiveButton("Export backup", (d, w) -> beginWaypointExport())
                        .setNeutralButton("Import backup", (d, w) -> beginWaypointImport())
                        .setNegativeButton("Close", null)
                        .show();
                return;
            }
            ArrayList<ActionListItem> rows = new ArrayList<>();
            for (WaypointEntity waypoint : items) {
                rows.add(new ActionListItem(
                        waypoint.name,
                        String.format(Locale.US, "%.5f, %.5f", waypoint.latitude, waypoint.longitude),
                        "OPEN"));
            }
            showActionListDialog(
                    "Saved Locations", "Tap a saved location to view, edit, map, or delete it.", rows,
                    "Export backup", (d, w) -> beginWaypointExport(),
                    "Import backup", (d, w) -> beginWaypointImport(),
                    "Close", null, which -> showWaypoint(items.get(which)));
        });
    }

    private void beginWaypointExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/geo+json");
        intent.putExtra(Intent.EXTRA_TITLE, "RockMap-Locations.geojson");
        startActivityForResult(intent, EXPORT_WAYPOINTS_REQUEST);
    }

    private void beginWaypointImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, IMPORT_WAYPOINTS_REQUEST);
    }

    private void showWaypoint(WaypointEntity waypoint) {
        boolean manualCoordinate = waypoint.accuracyMeters == MANUAL_COORDINATE_ACCURACY;
        boolean mineralSource = waypoint.accuracyMeters == MINERAL_SOURCE_ACCURACY;
        boolean historicMineSource = waypoint.accuracyMeters == HISTORIC_MINE_SOURCE_ACCURACY;
        String sourceLine = manualCoordinate
                ? "Source: manually entered coordinates"
                : mineralSource
                    ? "Source: saved mineral-search point"
                    : historicMineSource
                        ? "Source: saved historic-mine point"
                        : waypoint.accuracyMeters >= 0
                            ? String.format(Locale.US, "Reported GPS accuracy: ±%.1f m", waypoint.accuracyMeters)
                            : "GPS accuracy: not reported";
        String timeLabel = (manualCoordinate || mineralSource || historicMineSource) ? "Saved" : "Captured";
        String bodyText = String.format(Locale.US,
                "%.6f, %.6f\n%s\n%s: %s%s",
                waypoint.latitude, waypoint.longitude, sourceLine, timeLabel,
                DateFormat.getDateTimeInstance().format(new Date(waypoint.capturedAt)),
                waypoint.notes == null || waypoint.notes.trim().isEmpty() ? "" : "\n\n" + waypoint.notes);

        TextView body = new TextView(this);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(bodyText);
        LinearLayout detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.addView(body);
        Button makeArea = smallActionButton("Create Prospecting Area Around Here");
        makeArea.setOnClickListener(v -> ProspectingAreaCreator.chooseRadiusAndSave(
                this, waypoint.latitude, waypoint.longitude, waypoint.name,
                "Created from Saved Location: " + waypoint.name));

        new AlertDialog.Builder(this)
                .setTitle(waypoint.name)
                .setView(boundedScrollableContentWithPinnedAction(detailBox, makeArea, 430))
                .setPositiveButton("Show on Map", (d, w) -> mapController.centerOn(waypoint))
                .setNeutralButton("Edit", (d, w) -> editWaypoint(waypoint))
                .setNegativeButton("Delete", (d, w) -> confirmDelete(waypoint))
                .show();
    }

    private void editWaypoint(WaypointEntity waypoint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);
        EditText name = new EditText(this);
        name.setText(waypoint.name);
        box.addView(name);
        EditText notes = new EditText(this);
        notes.setText(waypoint.notes);
        notes.setMinLines(2);
        notes.setMaxLines(5);
        box.addView(notes);
        new AlertDialog.Builder(this)
                .setTitle("Edit saved location")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String label = boundedText(name.getText().toString().trim(), 500);
                    waypoint.name = label.isEmpty() ? "Saved location" : label;
                    waypoint.notes = boundedText(notes.getText().toString().trim(), 20_000);
                    waypointRepository.update(waypoint, this::refreshWaypoints);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(WaypointEntity waypoint) {
        new AlertDialog.Builder(this)
                .setTitle("Delete saved location?")
                .setMessage(waypoint.name + " will be removed from RockMap.")
                .setPositiveButton("Delete", (d, w) ->
                        waypointRepository.delete(waypoint, this::refreshWaypoints))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showData() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(4), dp(18), 0);

        TextView summary = helperText(dataSummaryText());
        summary.setTextSize(13f);
        summary.setPadding(0, 0, 0, dp(8));
        box.addView(boundedScrollableContent(summary, 250));

        Button diagnostics = smallActionButton("Technical diagnostics");
        box.addView(diagnostics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Offline data & privacy")
                .setView(box)
                .setPositiveButton("Check for update", (d, w) -> startDataUpdate())
                .setNeutralButton("Safety & privacy", (d, w) ->
                        startActivity(new Intent(this, PrivacySafetyActivity.class)))
                .setNegativeButton("Close", null)
                .create();
        diagnostics.setOnClickListener(v -> {
            dialog.dismiss();
            showDataDiagnostics();
        });
        dialog.show();
    }

    private String dataSummaryText() {
        boolean mapReady = offlineDataManager.hasRenderableActivePack();
        boolean landReady = mapController != null && mapController.hasLandStatusAvailable();
        boolean claimsReady = mapController != null && mapController.hasClaimsAvailable();
        boolean mineralsReady = mineralIndexRepository.isAvailable();
        boolean minesReady = mineralIndexRepository.hasExpandedEvidence();
        boolean geologyReady = geologyRepository != null && geologyRepository.isReady();
        int geologyCount = geologyReady ? geologyRepository.getRecordCount() : 0;
        int findCount = placeIndexRepository.getRecordCount();

        return "RockMap " + BuildConfig.VERSION_NAME + "\n\n"
                + "Offline data lets RockMap keep its main reference features available without a connection.\n\n"
                + "Map: " + (mapReady ? "installed and ready" : "not installed")
                + "\nPlace search: " + (findCount > 0
                    ? findCount + " Colorado place records ready"
                    : placeIndexRepository.isReady() ? "ready" : "not available")
                + "\nMineral Evidence: " + (mineralsReady ? "ready" : "not installed")
                + "\nQueryable geology: " + (geologyReady ? geologyCount + " Colorado mapped areas ready offline" : "not installed — open Research to install")
                + "\nHistoric mine records: " + (minesReady ? "ready" : "not installed")
                + "\nLand-management layer: " + (landReady ? "ready" : "not available")
                + "\nMining-claim records: " + (claimsReady ? "ready" : "not available")
                + "\n\nThese are public reference datasets, not guarantees of accuracy or current conditions. "
                + "Open Safety & privacy for sources and limitations.";
    }

    private void showDataDiagnostics() {
        String diagnostics = "RockMap " + BuildConfig.VERSION_NAME
                + (mapController == null ? "\n\nMap diagnostics unavailable."
                    : "\n\n" + mapController.describeLabelDiagnostics()
                    + "\n\n" + mapController.describeLandDiagnostics()
                    + "\n\n" + mapController.describeClaimsDiagnostics());

        TextView body = new TextView(this);
        body.setText(diagnostics);
        body.setTextSize(12.5f);
        body.setTextColor(Color.rgb(55, 55, 55));
        body.setTextIsSelectable(true);
        body.setPadding(dp(18), dp(8), dp(18), dp(8));

        new AlertDialog.Builder(this)
                .setTitle("Technical diagnostics")
                .setView(boundedScrollableContent(body, 440))
                .setPositiveButton("Close", null)
                .show();
    }

    private String userFacingOfflineStatus() {
        String status = offlineDataManager.describeStatus();
        if (status == null) return "Offline data status unavailable.";
        return status
                .replace("OFFLINE MAP: VERIFIED", "OFFLINE DATA PACK: ACTIVE")
                .replace("NOT VERIFIED FOR NAVIGATION",
                        "REFERENCE DATA — SEE SAFETY & DATA LIMITATIONS");
    }

    private void startDataUpdate() {
        if (BuildConfig.DATA_MANIFEST_URL == null || BuildConfig.DATA_MANIFEST_URL.trim().isEmpty()) {
            showMessage("This APK was not built from a configured public GitHub repository.");
            return;
        }
        Toast.makeText(this, "Checking update size…", Toast.LENGTH_SHORT).show();
        DataUpdatePreviewer.preview(this, BuildConfig.DATA_MANIFEST_URL, new DataUpdatePreviewer.Callback() {
            @Override
            public void onPreview(DataUpdatePreviewer.Preview preview) {
                if (isFinishing() || isDestroyed()) return;
                if (!preview.renderable) {
                    showMessage(preview.message.isEmpty()
                            ? "No downloadable RockMap data pack is currently published."
                            : preview.message);
                    return;
                }
                if (preview.estimatedDownloadBytes <= 0L || preview.estimatedDownloadFileCount <= 0) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Offline data is current")
                            .setMessage("No additional files are currently required. Nothing will be downloaded.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }

                String estimated = formatBytes(preview.estimatedDownloadBytes);
                String maximum = formatBytes(preview.totalPackBytes);
                String message = "Additional download: " + estimated
                        + " across " + preview.estimatedDownloadFileCount
                        + (preview.estimatedDownloadFileCount == 1 ? " file." : " files.")
                        + "\nWorst-case transfer if an existing file fails integrity: " + maximum + "."
                        + "\n\nInstalled files are reused. Downloads use HTTPS and declared-size/SHA-256 checks. "
                        + "GPS positions, Saved Locations, trips and notes are not uploaded.";
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Offline data update available")
                        .setMessage(message)
                        .setPositiveButton("Download", (d, w) -> queueConfirmedDataUpdate())
                        .setNegativeButton("Cancel", null)
                        .show();
            }

            @Override
            public void onError(String message) {
                if (isFinishing() || isDestroyed()) return;
                showMessage("Could not check update size: " + message);
            }
        });
    }

    private void queueConfirmedDataUpdate() {
        androidx.work.OneTimeWorkRequest request = offlineDataManager.queueUpdate();
        Toast.makeText(this, "Downloading and checking RockMap data integrity…", Toast.LENGTH_SHORT).show();
        clearUpdateObserver();
        updateLiveData = androidx.work.WorkManager.getInstance(this)
                .getWorkInfoByIdLiveData(request.getId());
        updateObserver = new Observer<WorkInfo>() {
            @Override
            public void onChanged(WorkInfo info) {
                if (info == null || !info.getState().isFinished()) return;
                clearUpdateObserver();
                if (info.getState() == WorkInfo.State.SUCCEEDED) {
                    clearMinerals();
                    mineralIndexRepository.clearCache();
                    historicMineOverlayController.clear();
                    historicMinesRequestedVisible = false;
                    historicMinesLoading = false;
                    mapController.reloadStyle();
                    showMessage("Offline data downloaded and integrity-checked. Source accuracy is not guaranteed; bundled offline Find remains ready.");
                } else {
                    showMessage(offlineDataManager.getLastUpdateStatus());
                }
            }
        };
        updateLiveData.observeForever(updateObserver);
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 MB";
        double mb = bytes / (1024d * 1024d);
        if (mb < 0.1d) return String.format(Locale.US, "%.0f KB", bytes / 1024d);
        if (mb < 10d) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.0f MB", mb);
    }

    private void clearUpdateObserver() {
        if (updateLiveData != null && updateObserver != null) {
            updateLiveData.removeObserver(updateObserver);
        }
        updateLiveData = null;
        updateObserver = null;
    }

    private void refreshWaypoints() {
        if (mapController == null) return;
        waypointRepository.getAll(mapController::setWaypoints);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasExtra(ProspectingAreaCreator.EXTRA_OPEN_RESEARCH_AREA_ID)) {
            // An explicit post-save Research action always wins over an older restorable session.
            skipSessionRestoreOnce = true;
            researchSessionRestored = true;
            long areaId = intent.getLongExtra(ProspectingAreaCreator.EXTRA_OPEN_RESEARCH_AREA_ID, -1L);
            intent.removeExtra(ProspectingAreaCreator.EXTRA_OPEN_RESEARCH_AREA_ID);
            if (areaId > 0L) openSavedProspectingAreaResearch(areaId);
            return;
        }
        if (intent != null && intent.hasExtra(ResearchActivity.RESULT_ACTION)) {
            skipSessionRestoreOnce = true;
            researchSessionRestored = true;
            pendingResearchLaunchIntent = new Intent(intent);
            if (mapView != null) {
                mapView.getMapAsync(mapLibreMap ->
                        mapLibreMap.getStyle(style -> consumePendingResearchLaunch()));
            }
        }
    }

    private void openSavedProspectingAreaResearch(long areaId) {
        if (areaId <= 0L) return;
        if (researchAreaPanel != null) researchAreaPanel.prepareForExplicitOpen();
        activeResearchAreaId = areaId;
        FieldDatabase.Area area = FieldDatabase.get(this).getArea(areaId);
        if (area != null && area.name != null && !area.name.trim().isEmpty()) {
            activeResearchAreaLabel = area.name.trim();
        }
        Intent research = new Intent(this, ResearchActivity.class);
        research.putExtra(ResearchActivity.EXTRA_AREA_ID, areaId);
        startActivityForResult(research, RESEARCH_REQUEST);
    }

    private String cleanResearchAreaLabel(String label) {
        if (label == null || label.trim().isEmpty()) return "Selected Area";
        String value = label.trim();
        String[] prefixes = new String[]{
                "Combined Area Analysis — ", "Geology — ", "Mineral Evidence — "
        };
        for (String prefix : prefixes) {
            if (value.startsWith(prefix) && value.length() > prefix.length()) {
                value = value.substring(prefix.length()).trim();
                break;
            }
        }
        return value.isEmpty() ? "Selected Area" : value;
    }

    private void showResearchAreaPanel(String activeView, String status) {
        if (activeResearchBounds == null || mainRoot == null) return;
        activeResearchView = activeView == null || activeView.trim().isEmpty()
                ? ResearchAreaPanelController.VIEW_GEOLOGY : activeView.trim();
        activeResearchStatus = status == null ? "" : status.trim();
        if (researchAreaPanel == null) researchAreaPanel = new ResearchAreaPanelController(this, mainRoot);
        researchAreaPanel.show(activeResearchAreaLabel, activeResearchView, activeResearchStatus,
                new ResearchAreaPanelController.Listener() {
            @Override public void onGeology() {
                if (activeResearchBounds != null) showGeologyForBounds(activeResearchBounds);
            }
            @Override public void onMinerals() {
                if (activeResearchBounds == null) return;
                if (activeMineralAreaAnalysis != null
                        && sameResearchBounds(activeResearchBounds, geologyBounds(activeMineralAreaAnalysis.bounds))) {
                    showMineralAreaResults(activeMineralAreaAnalysis);
                } else {
                    showMineralEvidenceForBounds(activeResearchBounds);
                }
            }
            @Override public void onMines() {
                if (activeResearchBounds != null) showHistoricMinesForBounds(activeResearchBounds, false);
            }
            @Override public void onSaveResearch() { saveCurrentResearchSnapshot(); }
            @Override public void onBack() { handleResearchPanelBack(); }
            @Override public void onClosePanel() {
                // Closing the panel never disables geology, heatmaps, mines, or a saved area.
                saveResearchSession();
                FieldMapController.ensurePersistentEntry(MainActivity.this);
            }
            @Override public void onPanelModeChanged(String mode) {
                // Expanded/collapsed/hidden is part of the recoverable working session.
                saveResearchSession();
                FieldMapController.ensurePersistentEntry(MainActivity.this);
            }
        });
        configureResearchPanelForView(activeResearchView);
        saveResearchSession();
        FieldMapController.ensurePersistentEntry(this);
    }

    private void showResearchEmptyState(String view, String subject, String detail) {
        String message = detail == null ? "" : detail.trim();
        showResearchAreaPanel(view, "Nothing found in this area");
        if (researchAreaPanel == null) return;
        researchAreaPanel.reopenExpanded();
        researchAreaPanel.setPrimaryActions(new ResearchAreaPanelController.ActionSpec(
                "Save Research", "Save this zero-result Research outcome with a Prospecting Area",
                this::saveCurrentResearchSnapshot));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(6), dp(2), dp(6), dp(5));
        content.addView(researchPanelHeading("Nothing found in this area"));
        String label = subject == null || subject.trim().isEmpty() ? "This Research query" : subject.trim();
        content.addView(researchBodyText(label + " returned no matching records in the installed RockMap data."
                + (message.isEmpty() ? "" : "\n\n" + message)
                + "\n\nThe Research Area is still active. Use the dataset buttons above to try another Research view."));
        researchAreaPanel.setScrollableContent(content);
        saveResearchSession();
    }

    private void configureResearchPanelForView(String view) {
        if (researchAreaPanel == null) return;
        if (ResearchAreaPanelController.VIEW_MINERALS.equals(view)) {
            if (activeMineralAreaAnalysis != null) {
                if (mineralOverlayController.isHeatmapVisible() && !activeResearchMineralLabel.isEmpty()) {
                    showCurrentMineralInformation();
                } else {
                    configureMineralOverview(activeMineralAreaAnalysis);
                }
            } else {
                researchAreaPanel.setPrimaryActions(new ResearchAreaPanelController.ActionSpec(
                        "Save Research", "Save the current Research information with a Prospecting Area",
                        this::saveCurrentResearchSnapshot));
                researchAreaPanel.setScrollableContent(researchBodyText(
                        activeResearchStatus.isEmpty()
                                ? "Mineral Evidence for this Research Area is ready to analyze."
                                : activeResearchStatus));
            }
            return;
        }
        if (ResearchAreaPanelController.VIEW_MINES.equals(view)) {
            configureHistoricMinesPanel();
            return;
        }
        configureGeologyPanel();
    }

    private void configureGeologyPanel() {
        if (researchAreaPanel == null) return;
        researchAreaPanel.setPrimaryActions(new ResearchAreaPanelController.ActionSpec(
                "Save Research", "Save this Geology information with a Prospecting Area",
                this::saveCurrentResearchSnapshot));
        String detail;
        if (activeResearchGeologyCount > 0) {
            detail = activeResearchGeologyCount + " mapped geology area"
                    + (activeResearchGeologyCount == 1 ? "" : "s") + " in the current Research Area."
                    + (activeResearchGeologyTitle.isEmpty() ? "" : "\n\nAnalysis: " + activeResearchGeologyTitle)
                    + geologyResultSummary()
                    + "\n\nTap a geology polygon on the map for complete unit/source details. "
                    + "Back, Collapse, and Close leave the mapped Geology layer active.";
        } else {
            detail = activeResearchStatus.isEmpty()
                    ? "No mapped Geology result is currently loaded for this Research Area."
                    : activeResearchStatus;
        }
        researchAreaPanel.setScrollableContent(researchBodyText(detail));
    }

    private String geologyResultSummary() {
        if (activeResearchGeologyGeoJson == null || activeResearchGeologyGeoJson.trim().isEmpty()) return "";
        try {
            JSONObject root = new JSONObject(activeResearchGeologyGeoJson);
            JSONArray features = root.optJSONArray("features");
            if (features == null || features.length() == 0) return "";
            ArrayList<String> seen = new ArrayList<>();
            StringBuilder out = new StringBuilder("\n\nResults:");
            int shown = 0;
            for (int i = 0; i < features.length() && shown < 10; i++) {
                JSONObject feature = features.optJSONObject(i);
                JSONObject props = feature == null ? null : feature.optJSONObject("properties");
                if (props == null) continue;
                String unit = props.optString("UNIT_NAME", "").trim();
                if (unit.isEmpty()) unit = props.optString("SGMC_LABEL", "").trim();
                if (unit.isEmpty()) unit = props.optString("ORIG_LABEL", "").trim();
                String lith = props.optString("GENERALIZED_LITH", "").trim();
                String ageMin = props.optString("AGE_MIN", "").trim();
                String ageMax = props.optString("AGE_MAX", "").trim();
                String key = unit + "|" + lith + "|" + ageMin + "|" + ageMax;
                if (seen.contains(key)) continue;
                seen.add(key);
                out.append("\n• ").append(unit.isEmpty() ? "Unnamed geologic unit" : unit);
                if (!lith.isEmpty()) out.append(" — ").append(lith);
                if (!ageMin.isEmpty() || !ageMax.isEmpty()) {
                    out.append(" · ");
                    if (!ageMin.isEmpty()) out.append(ageMin);
                    if (!ageMax.isEmpty() && !ageMax.equals(ageMin)) out.append("–").append(ageMax);
                }
                shown++;
            }
            if (features.length() > shown) out.append("\n…more mapped areas are available on the map.");
            return out.toString();
        } catch (JSONException ex) {
            return "";
        }
    }

    private void configureHistoricMinesPanel() {
        if (researchAreaPanel == null) return;
        researchAreaPanel.setPrimaryActions(new ResearchAreaPanelController.ActionSpec(
                "Save Research", "Save this Historic Mines & Workings research with a Prospecting Area",
                this::saveCurrentResearchSnapshot));
        String detail = (activeResearchStatus.isEmpty()
                ? "Historic Mines & Workings are shown for the current Research Area."
                : activeResearchStatus)
                + "\n\nTap a mapped mine or working for its source record, nearby evidence, and available options. "
                + "Back, Collapse, and Close only change this panel; the mapped layer remains until its map-context × is used.";
        researchAreaPanel.setScrollableContent(researchBodyText(detail));
    }

    private void handleResearchPanelBack() {
        if (ResearchAreaPanelController.VIEW_MINERALS.equals(activeResearchView)
                && activeMineralAreaAnalysis != null && mineralOverlayController.isHeatmapVisible()) {
            // Return to the Mineral Evidence overview while deliberately leaving the heatmap active.
            showMineralAreaResults(activeMineralAreaAnalysis);
            return;
        }
        if (researchAreaPanel != null) researchAreaPanel.closePanel();
    }

    /** Reopen cached information/results/options from the labeled map-context control. */
    private void reopenResearchContext(String view) {
        if (activeResearchBounds == null) return;
        String targetView = view == null ? activeResearchView : view;
        showResearchAreaPanel(targetView, researchStatusForView(targetView));
        if (researchAreaPanel != null) researchAreaPanel.reopenExpanded();
        configureResearchPanelForView(targetView);
        saveResearchSession();
    }

    private String researchStatusForView(String view) {
        if (ResearchAreaPanelController.VIEW_MINERALS.equals(view)) {
            if (!activeResearchMineralLabel.isEmpty() && mineralOverlayController.isHeatmapVisible()) {
                return activeResearchMineralLabel + " heatmap · " + mineralOverlayController.getHeatmapPointCount()
                        + " source record" + (mineralOverlayController.getHeatmapPointCount() == 1 ? "" : "s");
            }
            if (activeMineralAreaAnalysis != null) {
                return activeMineralAreaAnalysis.minerals.size() + " mineral/material terms available in this area.";
            }
            return "Mineral Evidence for this Research Area.";
        }
        if (ResearchAreaPanelController.VIEW_MINES.equals(view)) {
            return historicMinesRequestedVisible
                    ? "Historic Mines & Workings are shown for this Research Area."
                    : "Historic Mines & Workings information for this Research Area.";
        }
        return activeResearchGeologyCount > 0
                ? activeResearchGeologyCount + " mapped geology area"
                    + (activeResearchGeologyCount == 1 ? "" : "s") + " shown."
                : "Geology information for this Research Area.";
    }

    private TextView researchBodyText(String text) {
        TextView body = new TextView(this);
        body.setText(text == null ? "" : text);
        body.setTextSize(12f);
        body.setTextColor(Color.rgb(60, 60, 60));
        body.setPadding(dp(6), dp(5), dp(6), dp(8));
        return body;
    }

    private void saveCurrentResearchSnapshot() {
        if (activeResearchBounds == null) {
            showMessage("There is no active Research Area to save.");
            return;
        }
        ProspectingAreaResearchStore.Snapshot snapshot = buildResearchSnapshot();
        FieldDatabase.Area area = activeResearchAreaId > 0L
                ? FieldDatabase.get(this).getArea(activeResearchAreaId) : null;
        if (area != null) {
            ProspectingAreaResearchStore.save(this, area.id, snapshot);
            showMessage("Research saved with Prospecting Area: " + area.name);
            saveResearchSession();
            return;
        }

        List<GeoMath.Point> points = activeResearchGeometryPoints();
        if (points.size() < 3) {
            showMessage("This Research Area could not be converted into a Prospecting Area safely.");
            return;
        }
        String defaultName = activeResearchAreaLabel == null
                || activeResearchAreaLabel.trim().isEmpty()
                || "Selected Area".equalsIgnoreCase(activeResearchAreaLabel.trim())
                ? "Research Area" : activeResearchAreaLabel.trim();
        ProspectingAreaCreator.savePolygon(this, defaultName,
                "Created from the active RockMap Research Area so saved Research can stay attached to this area.",
                points, false, (areaId, savedName) -> {
                    activeResearchAreaId = areaId;
                    activeResearchAreaLabel = savedName;
                    ProspectingAreaResearchStore.save(MainActivity.this, areaId, snapshot);
                    showMessage("Prospecting Area and Research saved: " + savedName);
                    if (researchAreaPanel != null && !ResearchAreaPanelController.MODE_HIDDEN.equals(
                            researchAreaPanel.currentMode())) {
                        showResearchAreaPanel(activeResearchView, activeResearchStatus);
                    }
                    saveResearchSession();
                });
    }

    private ProspectingAreaResearchStore.Snapshot buildResearchSnapshot() {
        ProspectingAreaResearchStore.Snapshot snapshot = new ProspectingAreaResearchStore.Snapshot();
        snapshot.savedAt = System.currentTimeMillis();
        snapshot.south = activeResearchBounds.south;
        snapshot.west = activeResearchBounds.west;
        snapshot.north = activeResearchBounds.north;
        snapshot.east = activeResearchBounds.east;
        if (ResearchAreaPanelController.VIEW_MINERALS.equals(activeResearchView)) {
            snapshot.dataset = "Mineral Evidence";
            snapshot.source = "Installed RockMap mineral evidence index";
            if (offlineDataManager != null && offlineDataManager.getActiveManifest() != null) {
                snapshot.version = offlineDataManager.getActiveManifest().version;
            }
            snapshot.mineral = activeResearchMineralLabel;
            snapshot.title = activeResearchMineralLabel.isEmpty()
                    ? "Mineral Evidence" : activeResearchMineralLabel + " heatmap";
            if (!activeResearchMineralMessage.isEmpty()) {
                snapshot.summary = activeResearchMineralMessage;
            } else if (activeMineralAreaAnalysis != null) {
                snapshot.summary = activeMineralAreaAnalysis.recordsInArea + " installed evidence records in the area; "
                        + activeMineralAreaAnalysis.minerals.size() + " explicit mineral/material terms available.";
            } else {
                snapshot.summary = activeResearchStatus;
            }
        } else if (ResearchAreaPanelController.VIEW_MINES.equals(activeResearchView)) {
            snapshot.dataset = "Historic Mines & Workings";
            snapshot.source = "Installed RockMap historic mine / mineral evidence index";
            if (offlineDataManager != null && offlineDataManager.getActiveManifest() != null) {
                snapshot.version = offlineDataManager.getActiveManifest().version;
            }
            snapshot.title = "Historic Mines & Workings";
            snapshot.summary = activeResearchStatus.isEmpty()
                    ? "Historic Mines & Workings map context was active for this Research Area."
                    : activeResearchStatus;
        } else {
            snapshot.dataset = "Geology";
            snapshot.source = "USGS State Geologic Map Compilation (SGMC)";
            snapshot.version = new GeologyDataManager(this).getInstalledVersion();
            snapshot.title = activeResearchGeologyTitle.isEmpty() ? "Geology" : activeResearchGeologyTitle;
            snapshot.summary = activeResearchGeologyCount + " mapped geology area"
                    + (activeResearchGeologyCount == 1 ? "" : "s")
                    + " in this Research Area.";
        }
        return snapshot;
    }

    private List<GeoMath.Point> activeResearchGeometryPoints() {
        if (activeResearchBounds == null) return new ArrayList<>();
        if (activeResearchGeologyGeoJson != null && !activeResearchGeologyGeoJson.trim().isEmpty()
                && sameResearchBounds(activeResearchBounds, activeResearchGeologyBounds)) {
            try {
                JSONObject root = new JSONObject(activeResearchGeologyGeoJson);
                JSONObject query = root.optJSONObject("rockmapQuery");
                JSONObject geometry = query == null ? null : query.optJSONObject("geometry");
                if (geometry != null) {
                    List<GeoMath.Point> exact = ProspectingAreaCreator.polygonFromGeometryJson(geometry.toString());
                    if (exact.size() >= 3) return exact;
                }
            } catch (JSONException ignored) {}
        }

        double latSpan = Math.abs(activeResearchBounds.north - activeResearchBounds.south);
        double lonSpan = Math.abs(activeResearchBounds.east - activeResearchBounds.west);
        if (latSpan < 1e-10d && lonSpan < 1e-10d) {
            return ProspectingAreaCreator.circlePoints(activeResearchBounds.south, activeResearchBounds.west, 250d);
        }
        ArrayList<GeoMath.Point> points = new ArrayList<>();
        points.add(new GeoMath.Point(activeResearchBounds.south, activeResearchBounds.west));
        points.add(new GeoMath.Point(activeResearchBounds.south, activeResearchBounds.east));
        points.add(new GeoMath.Point(activeResearchBounds.north, activeResearchBounds.east));
        points.add(new GeoMath.Point(activeResearchBounds.north, activeResearchBounds.west));
        return points;
    }

    private void saveResearchSession() {
        if (activeResearchBounds == null) return;
        ResearchSessionState.Snapshot snapshot = new ResearchSessionState.Snapshot();
        snapshot.active = true;
        snapshot.south = activeResearchBounds.south;
        snapshot.west = activeResearchBounds.west;
        snapshot.north = activeResearchBounds.north;
        snapshot.east = activeResearchBounds.east;
        snapshot.areaLabel = activeResearchAreaLabel;
        snapshot.activeView = activeResearchView;
        snapshot.panelMode = researchAreaPanel == null
                ? ResearchAreaPanelController.MODE_HIDDEN : researchAreaPanel.currentMode();
        snapshot.areaId = activeResearchAreaId;
        snapshot.geologyVisible = geologyOverlayController != null && geologyOverlayController.isVisible();
        snapshot.mineralVisible = mineralOverlayController != null
                && mineralOverlayController.isAreaAnalysisVisible();
        snapshot.minesVisible = historicMinesRequestedVisible;
        snapshot.mineralKey = activeResearchMineralKey;
        snapshot.mineralLabel = activeResearchMineralLabel;
        snapshot.geologyTitle = activeResearchGeologyTitle;
        snapshot.geologyCount = activeResearchGeologyCount;
        ResearchSessionState.save(this, snapshot);
    }

    private void restoreResearchSession() {
        if (researchSessionRestored || skipSessionRestoreOnce) return;
        researchSessionRestored = true;
        ResearchSessionState.Snapshot snapshot = ResearchSessionState.load(this);
        if (snapshot == null) return;
        try {
            activeResearchBounds = new GeologyRepository.Bounds(
                    snapshot.south, snapshot.west, snapshot.north, snapshot.east);
        } catch (IllegalArgumentException ex) {
            return;
        }
        activeResearchAreaLabel = snapshot.areaLabel;
        activeResearchAreaId = snapshot.areaId;
        activeResearchView = snapshot.activeView;
        activeResearchMineralKey = snapshot.mineralKey;
        activeResearchMineralLabel = snapshot.mineralLabel;
        activeResearchGeologyTitle = snapshot.geologyTitle;
        activeResearchGeologyCount = snapshot.geologyCount;
        activeResearchGeologyBounds = activeResearchBounds;
        activeResearchStatus = "Restored the previous Research Area after RockMap restarted.";

        if (snapshot.geologyVisible && snapshot.geologyCount > 0) {
            try {
                String geoJson = ResearchResultStore.geoJson(this);
                GeologyRepository.Bounds storedBounds = researchBoundsFromGeoJson(geoJson);
                if (storedBounds == null || sameResearchBounds(activeResearchBounds, storedBounds)) {
                    activeResearchGeologyGeoJson = geoJson;
                    geologyOverlayController.show(geoJson, snapshot.geologyTitle, snapshot.geologyCount);
                }
            } catch (IOException ignored) {}
        }
        if (snapshot.minesVisible) {
            setHistoricMinesVisible(true, activeResearchBounds);
        }

        showResearchAreaPanel(snapshot.activeView, activeResearchStatus);
        if (researchAreaPanel != null) researchAreaPanel.restoreMode(snapshot.panelMode);

        if (snapshot.mineralVisible) {
            restoreMineralSessionLayer(snapshot);
        } else {
            configureResearchPanelForView(snapshot.activeView);
            if (researchAreaPanel != null) researchAreaPanel.restoreMode(snapshot.panelMode);
            saveResearchSession();
        }
        FieldMapController.ensurePersistentEntry(this);
    }

    private void restoreMineralSessionLayer(ResearchSessionState.Snapshot snapshot) {
        if (activeResearchBounds == null) return;
        MineralSearchEngine.Bounds bounds = new MineralSearchEngine.Bounds(
                activeResearchBounds.north, activeResearchBounds.east,
                activeResearchBounds.south, activeResearchBounds.west);
        mineralIndexRepository.analyzeArea(bounds, new MineralIndexRepository.AreaAnalysisCallback() {
            @Override public void onResult(MineralAreaAnalyzer.AnalysisResult result) {
                activeMineralAreaAnalysis = result;
                mineralOverlayController.showAnalysisBounds(result.bounds);
                MineralAreaAnalyzer.MineralSummary selected = findMineralSummary(
                        result, snapshot.mineralKey, snapshot.mineralLabel);
                if (selected == null || snapshot.mineralKey == null || snapshot.mineralKey.isEmpty()) {
                    if (ResearchAreaPanelController.VIEW_MINERALS.equals(snapshot.activeView)) {
                        researchAreaPanel.update(ResearchAreaPanelController.VIEW_MINERALS,
                                "Restored Mineral Evidence for the previous Research Area.");
                        configureMineralOverview(result);
                        researchAreaPanel.restoreMode(snapshot.panelMode);
                    }
                    saveResearchSession();
                    return;
                }
                mineralIndexRepository.loadAreaEvidence(result.bounds, selected.key,
                        new MineralIndexRepository.AreaEvidenceCallback() {
                    @Override public void onResult(List<MineralAreaAnalyzer.EvidencePoint> points) {
                        activeResearchMineralEvidencePoints = points == null
                                ? new ArrayList<>() : new ArrayList<>(points);
                        if (!activeResearchMineralEvidencePoints.isEmpty()) {
                            activeResearchMineralKey = selected.key == null ? "" : selected.key;
                            activeResearchMineralLabel = selected.displayName == null ? "" : selected.displayName;
                            activeResearchMineralMessage = activeResearchMineralEvidencePoints.size() + " source record"
                                    + (activeResearchMineralEvidencePoints.size() == 1 ? " contributes" : "s contribute")
                                    + " to the restored " + activeResearchMineralLabel + " heatmap.";
                            mineralOverlayController.showHeatmap(activeResearchMineralEvidencePoints, result.bounds, activeResearchMineralLabel);
                        }
                        if (ResearchAreaPanelController.VIEW_MINERALS.equals(snapshot.activeView)) {
                            researchAreaPanel.update(ResearchAreaPanelController.VIEW_MINERALS,
                                    activeResearchMineralLabel.isEmpty()
                                            ? "Restored Mineral Evidence."
                                            : "Restored " + activeResearchMineralLabel + " heatmap.");
                            if (!activeResearchMineralLabel.isEmpty()) {
                                configureCurrentMineralInformation(selected, result, activeResearchMineralMessage);
                            } else {
                                configureMineralOverview(result);
                            }
                            researchAreaPanel.restoreMode(snapshot.panelMode);
                        }
                        saveResearchSession();
                    }
                    @Override public void onError(String message) {
                        activeResearchMineralEvidencePoints = new ArrayList<>();
                        if (ResearchAreaPanelController.VIEW_MINERALS.equals(snapshot.activeView)) {
                            researchAreaPanel.update(ResearchAreaPanelController.VIEW_MINERALS,
                                    "Mineral Evidence session restored; the previous heatmap could not be rebuilt.");
                            configureMineralOverview(result);
                            researchAreaPanel.restoreMode(snapshot.panelMode);
                        }
                        saveResearchSession();
                    }
                });
            }
            @Override public void onError(String message) {
                if (ResearchAreaPanelController.VIEW_MINERALS.equals(snapshot.activeView)
                        && researchAreaPanel != null) {
                    researchAreaPanel.update(ResearchAreaPanelController.VIEW_MINERALS,
                            "The Research Area was restored, but Mineral Evidence could not be rebuilt.");
                    researchAreaPanel.restoreMode(snapshot.panelMode);
                }
                saveResearchSession();
            }
        });
    }

    private void consumePendingResearchLaunch() {
        Intent pending = pendingResearchLaunchIntent;
        if (pending == null) return;
        pendingResearchLaunchIntent = null;
        handleResearchResult(pending);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RESEARCH_REQUEST) {
            if (resultCode == RESULT_OK && data != null) handleResearchResult(data);
            return;
        }
        boolean tripExportRequest = requestCode == EXPORT_TRIP_GEOJSON_REQUEST
                || requestCode == EXPORT_TRIP_GPX_REQUEST
                || requestCode == EXPORT_TRIP_CSV_REQUEST
                || requestCode == EXPORT_TRIP_XML_REQUEST;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (tripExportRequest) pendingTripExportId = -1L;
            return;
        }
        Uri uri = data.getData();
        if (requestCode == EXPORT_WAYPOINTS_REQUEST) {
            exportWaypoints(uri);
        } else if (requestCode == IMPORT_WAYPOINTS_REQUEST) {
            importWaypoints(uri);
        } else if (requestCode == EXPORT_TRIP_GEOJSON_REQUEST
                || requestCode == EXPORT_TRIP_GPX_REQUEST
                || requestCode == EXPORT_TRIP_CSV_REQUEST
                || requestCode == EXPORT_TRIP_XML_REQUEST) {
            exportTrip(uri, requestCode);
        }
    }

    private void handleResearchResult(Intent data) {
        String action = data.getStringExtra(ResearchActivity.RESULT_ACTION);
        long returnedAreaId = data.getLongExtra(ResearchActivity.RESULT_AREA_ID, -1L);
        if (returnedAreaId > 0L) activeResearchAreaId = returnedAreaId;
        if (ResearchActivity.ACTION_GEOLOGY.equals(action)) {
            String title = data.getStringExtra(ResearchActivity.RESULT_TITLE);
            int count = data.getIntExtra(ResearchActivity.RESULT_COUNT, 0);
            try {
                String geoJson = ResearchResultStore.geoJson(this);
                GeologyRepository.Bounds returnedBounds = readResearchBounds(data);
                GeologyRepository.Bounds queryBounds = researchBoundsFromGeoJson(geoJson);
                activeResearchGeologyGeoJson = geoJson;
                activeResearchGeologyTitle = title == null ? "Geology" : title;
                activeResearchGeologyCount = count;
                activeResearchGeologyBounds = queryBounds != null ? queryBounds : returnedBounds;
                if (activeResearchGeologyBounds != null) activeResearchBounds = activeResearchGeologyBounds;
                geologyOverlayController.show(geoJson, title, count);
                if (queryBounds == null) zoomToResearchBounds(returnedBounds);
                activeResearchAreaLabel = cleanResearchAreaLabel(activeResearchGeologyTitle);
                if (count == 0) {
                    showResearchEmptyState(
                            ResearchAreaPanelController.VIEW_GEOLOGY,
                            "Geology",
                            "No installed mapped Geology matched this area.");
                } else {
                    showResearchAreaPanel(ResearchAreaPanelController.VIEW_GEOLOGY,
                            count + " mapped geology area" + (count == 1 ? "" : "s")
                                    + " shown. Switch datasets without leaving this area.");
                }
            } catch (IOException ex) {
                showMessage("Research result could not be opened on the map: " + ex.getMessage());
            }
            return;
        }
        if (ResearchActivity.ACTION_DATA.equals(action)) {
            showData();
            return;
        }
        if (ResearchActivity.ACTION_GPS_POINT.equals(action)) {
            researchAtFreshGps(data.getDoubleExtra(ResearchActivity.EXTRA_RADIUS_M, 1000d));
            return;
        }
        if (ResearchActivity.ACTION_MINERALS.equals(action)) {
            showMineralSearch();
            return;
        }
        GeologyRepository.Bounds bounds = readResearchBounds(data);
        String returnedAreaLabel = data.getStringExtra(ResearchActivity.RESULT_TITLE);
        if (returnedAreaLabel != null && !returnedAreaLabel.trim().isEmpty()) {
            activeResearchAreaLabel = cleanResearchAreaLabel(returnedAreaLabel);
        }
        if (ResearchActivity.ACTION_MINERALS_AREA.equals(action)) {
            if (bounds == null) {
                showMessage("Research did not return valid bounds for Mineral Evidence analysis.");
                return;
            }
            showMineralEvidenceForBounds(bounds);
            return;
        }
        if (ResearchActivity.ACTION_HISTORIC_MINES.equals(action)) {
            if (bounds == null) {
                showMessage("Research did not return valid bounds for Historic Mines & Workings.");
                return;
            }
            showHistoricMinesForBounds(bounds, true);
        }
    }

    private void showMineralEvidenceForBounds(GeologyRepository.Bounds bounds) {
        if (bounds == null) return;
        activeResearchBounds = bounds;
        showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINERALS, "Analyzing Mineral Evidence in this area…");
        MineralSearchEngine.Bounds mineralBounds = new MineralSearchEngine.Bounds(
                bounds.north, bounds.east, bounds.south, bounds.west);
        showMessage("Analyzing Mineral Evidence in the selected area…");
        mineralIndexRepository.analyzeArea(mineralBounds, new MineralIndexRepository.AreaAnalysisCallback() {
            @Override public void onResult(MineralAreaAnalyzer.AnalysisResult result) {
                activeMineralAreaAnalysis = result;
                activeResearchBounds = geologyBounds(result.bounds);
                mineralOverlayController.showAnalysisBounds(result.bounds);
                showMineralAreaResults(result);
            }
            @Override public void onError(String message) {
                showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINERALS,
                        message == null
                                ? "Mineral Evidence could not be analyzed. Geology and Historic Mines remain available for this area."
                                : message + " Geology and Historic Mines remain available for this area.");
            }
        });
    }

    private void showHistoricMinesForBounds(GeologyRepository.Bounds bounds, boolean offerRelated) {
        if (bounds == null) return;
        activeResearchBounds = bounds;
        zoomToResearchBounds(bounds);
        showResearchAreaPanel(ResearchAreaPanelController.VIEW_MINES,
                "Loading Historic Mines & Workings for this area…");
        setHistoricMinesVisible(true, bounds);
    }

    private void showGeologyForBounds(GeologyRepository.Bounds bounds) {
        if (bounds == null) return;
        activeResearchBounds = bounds;
        if (!activeResearchGeologyGeoJson.isEmpty() && sameResearchBounds(bounds, activeResearchGeologyBounds)) {
            geologyOverlayController.show(activeResearchGeologyGeoJson, activeResearchGeologyTitle,
                    activeResearchGeologyCount);
            showResearchAreaPanel(ResearchAreaPanelController.VIEW_GEOLOGY,
                    activeResearchGeologyCount + " mapped geology area"
                            + (activeResearchGeologyCount == 1 ? "" : "s") + " shown.");
            return;
        }
        showResearchAreaPanel(ResearchAreaPanelController.VIEW_GEOLOGY, "Loading Geology for this area…");
        startResearch(bounds, true);
    }

    private void saveMineralAnalysisArea(MineralAreaAnalyzer.AnalysisResult analysis) {
        if (analysis == null || analysis.bounds == null) return;
        MineralSearchEngine.Bounds b = analysis.bounds;
        ArrayList<GeoMath.Point> points = new ArrayList<>();
        points.add(new GeoMath.Point(b.south, b.west));
        points.add(new GeoMath.Point(b.south, b.east));
        points.add(new GeoMath.Point(b.north, b.east));
        points.add(new GeoMath.Point(b.north, b.west));
        ProspectingAreaCreator.savePolygon(this, "Visible Area — Mineral Evidence",
                "Created from RockMap Visible Area — Mineral Evidence analysis", points, true);
    }

    private GeologyRepository.Bounds geologyBounds(MineralSearchEngine.Bounds bounds) {
        if (bounds == null) return null;
        try {
            return new GeologyRepository.Bounds(bounds.south, bounds.west, bounds.north, bounds.east);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private GeologyRepository.Bounds researchBoundsFromGeoJson(String geoJson) {
        if (geoJson == null || geoJson.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(geoJson);
            JSONObject query = root.optJSONObject("rockmapQuery");
            if (query == null) return null;
            return new GeologyRepository.Bounds(
                    query.optDouble("south", Double.NaN),
                    query.optDouble("west", Double.NaN),
                    query.optDouble("north", Double.NaN),
                    query.optDouble("east", Double.NaN));
        } catch (JSONException | IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean sameResearchBounds(GeologyRepository.Bounds left, GeologyRepository.Bounds right) {
        if (left == null || right == null) return false;
        double eps = 1e-8d;
        return Math.abs(left.south - right.south) <= eps
                && Math.abs(left.west - right.west) <= eps
                && Math.abs(left.north - right.north) <= eps
                && Math.abs(left.east - right.east) <= eps;
    }

    private void saveGeologyFeatureAsProspectingArea(Feature feature, LatLng coordinate, String unit) {
        if (feature == null || coordinate == null) return;
        try {
            JSONObject featureJson = new JSONObject(feature.toJson());
            JSONObject geometry = featureJson.optJSONObject("geometry");
            if (geometry == null) {
                showMessage("This mapped geology feature does not contain saveable polygon geometry.");
                return;
            }
            List<GeoMath.Point> points = ProspectingAreaCreator.polygonFromGeometryJson(
                    geometry.toString(), coordinate.getLatitude(), coordinate.getLongitude());
            if (points.size() < 3) {
                showMessage("This mapped geology feature could not be converted to a Prospecting Area.");
                return;
            }
            ProspectingAreaCreator.savePolygon(this, unit,
                    "Created from mapped geology unit: " + unit
                            + "\nSource: " + GeologyRepository.SOURCE_TITLE
                            + "\nDOI: " + GeologyRepository.SOURCE_DOI,
                    points, true);
        } catch (JSONException ex) {
            showMessage("This mapped geology feature could not be read safely.");
        }
    }

    private GeologyRepository.Bounds readResearchBounds(Intent data) {
        if (data == null || !data.hasExtra(ResearchActivity.EXTRA_SOUTH)
                || !data.hasExtra(ResearchActivity.EXTRA_WEST)
                || !data.hasExtra(ResearchActivity.EXTRA_NORTH)
                || !data.hasExtra(ResearchActivity.EXTRA_EAST)) return null;
        try {
            return new GeologyRepository.Bounds(
                    data.getDoubleExtra(ResearchActivity.EXTRA_SOUTH, Double.NaN),
                    data.getDoubleExtra(ResearchActivity.EXTRA_WEST, Double.NaN),
                    data.getDoubleExtra(ResearchActivity.EXTRA_NORTH, Double.NaN),
                    data.getDoubleExtra(ResearchActivity.EXTRA_EAST, Double.NaN));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void zoomToResearchBounds(GeologyRepository.Bounds bounds) {
        if (bounds == null || mapView == null) return;
        double lat = (bounds.south + bounds.north) / 2d;
        double lon = (bounds.west + bounds.east) / 2d;
        double span = Math.max(bounds.north - bounds.south, bounds.east - bounds.west);
        double zoom;
        if (span <= 0.002d) zoom = 15.0;
        else if (span <= 0.01d) zoom = 13.5;
        else if (span <= 0.05d) zoom = 11.5;
        else if (span <= 0.2d) zoom = 9.5;
        else if (span <= 0.8d) zoom = 7.5;
        else zoom = 6.0;
        final double finalZoom = zoom;
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(new LatLng(lat, lon), finalZoom)));
    }

    private void onGeologyTapped(Feature feature, LatLng coordinate) {
        if (feature == null || coordinate == null) return;
        String unit = stringProp(feature, "UNIT_NAME", "Mapped geologic unit");
        String lith = stringProp(feature, "MAJOR1",
                stringProp(feature, "GENERALIZED_LITH", "Not reported"));
        String ageMin = mostSpecificGeologyAge(stringProp(feature, "AGE_MIN", ""));
        String ageMax = mostSpecificGeologyAge(stringProp(feature, "AGE_MAX", ""));
        String age = ageMin;
        if (age.isEmpty()) age = ageMax;
        else if (!ageMax.isEmpty() && !ageMax.equalsIgnoreCase(ageMin)) age += " – " + ageMax;

        StringBuilder text = new StringBuilder();
        text.append("Rock type: ").append(lith);
        if (!age.isEmpty()) text.append("\nAge: ").append(age);
        text.append(String.format(Locale.US, "\nLocation: %.6f, %.6f",
                coordinate.getLatitude(), coordinate.getLongitude()));
        text.append("\n\nInterpretive mapped geology only. Ownership, access, claims, hazards, and collecting permission are separate.");

        TextView body = new TextView(this);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(text.toString());
        LinearLayout detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.addView(body);
        Button saveArea = smallActionButton("Save as Prospecting Area");
        saveArea.setOnClickListener(v -> saveGeologyFeatureAsProspectingArea(feature, coordinate, unit));
        detailBox.addView(saveArea);
        new AlertDialog.Builder(this)
                .setTitle(unit)
                .setView(boundedScrollableContent(detailBox, 430))
                .setPositiveButton("Research", (d, w) -> showResearch())
                .setNeutralButton("Source Details", (d, w) -> showGeologySourceDetails(feature, coordinate, unit))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showGeologySourceDetails(Feature feature, LatLng coordinate, String unit) {
        String label = stringProp(feature, "SGMC_LABEL", "");
        String original = stringProp(feature, "ORIG_LABEL", "");
        String lith = stringProp(feature, "GENERALIZED_LITH", "Not reported");
        String ageMin = stringProp(feature, "AGE_MIN", "");
        String ageMax = stringProp(feature, "AGE_MAX", "");
        String reference = stringProp(feature, "REFERENCE", "");
        String objectId = stringProp(feature, "OBJECTID", "");
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.US, "%.6f, %.6f", coordinate.getLatitude(), coordinate.getLongitude()));
        if (!label.isEmpty()) text.append("\nSGMC label: ").append(label);
        if (!original.isEmpty()) text.append("\nOriginal label: ").append(original);
        text.append("\nGeneralized lithology: ").append(lith);
        if (!ageMin.isEmpty() || !ageMax.isEmpty()) {
            text.append("\nFull age: ").append(ageMin);
            if (!ageMax.isEmpty() && !ageMax.equalsIgnoreCase(ageMin)) text.append(" – ").append(ageMax);
        }
        if (!objectId.isEmpty()) text.append("\nSource area ID: ").append(objectId);
        if (!reference.isEmpty()) text.append("\n\nReference: ").append(reference);
        text.append("\n\nSource: ").append(GeologyRepository.SOURCE_TITLE)
                .append("\nDOI: ").append(GeologyRepository.SOURCE_DOI)
                .append("\nScale: ").append(GeologyRepository.SOURCE_SCALE);

        TextView body = new TextView(this);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(text.toString());
        new AlertDialog.Builder(this)
                .setTitle(unit + " — Source Details")
                .setView(boundedScrollableContent(body, 440))
                .setPositiveButton("Close", null)
                .show();
    }

    private static String mostSpecificGeologyAge(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        String[] parts = raw.trim().split("\\s+-\\s+");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (!parts[i].trim().isEmpty()) return parts[i].trim();
        }
        return raw.trim();
    }

    private void exportWaypoints(Uri uri) {
        waypointRepository.getAll(items -> {
            try {
                JSONObject root = new JSONObject();
                root.put("type", "FeatureCollection");
                root.put("rockmapSchema", 1);
                JSONArray features = new JSONArray();
                for (WaypointEntity waypoint : items) {
                    JSONObject feature = new JSONObject();
                    feature.put("type", "Feature");
                    JSONObject geometry = new JSONObject();
                    geometry.put("type", "Point");
                    geometry.put("coordinates", new JSONArray()
                            .put(waypoint.longitude).put(waypoint.latitude));
                    feature.put("geometry", geometry);
                    JSONObject props = new JSONObject();
                    props.put("name", waypoint.name);
                    props.put("notes", waypoint.notes);
                    props.put("accuracyMeters", waypoint.accuracyMeters);
                    props.put("capturedAt", waypoint.capturedAt);
                    props.put("createdAt", waypoint.createdAt);
                    props.put("updatedAt", waypoint.updatedAt);
                    feature.put("properties", props);
                    features.put(feature);
                }
                root.put("features", features);
                ContentResolver resolver = getContentResolver();
                try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                    if (output == null) {
                        throw new IOException("Android could not open the selected export file.");
                    }
                    output.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                showMessage("Exported " + items.size() + " saved location"
                        + (items.size() == 1 ? "." : "s."));
            } catch (IOException | JSONException ex) {
                showMessage("Saved Location export failed: " + ex.getMessage());
            }
        });
    }

    private void importWaypoints(Uri uri) {
        try {
            byte[] bytes = readContentUri(uri, MAX_IMPORT_BYTES);
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!"FeatureCollection".equals(root.optString("type"))) {
                throw new JSONException("Expected a GeoJSON FeatureCollection.");
            }
            JSONArray features = root.getJSONArray("features");
            if (features.length() > MAX_IMPORT_WAYPOINTS) {
                throw new JSONException("Backup contains too many locations.");
            }
            ArrayList<WaypointEntity> imports = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject geometry = feature.getJSONObject("geometry");
                if (!"Point".equals(geometry.optString("type"))) continue;
                JSONArray coords = geometry.getJSONArray("coordinates");
                if (coords.length() < 2) continue;
                double longitude = coords.getDouble(0);
                double latitude = coords.getDouble(1);
                if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                        || latitude < -90 || latitude > 90
                        || longitude < -180 || longitude > 180) continue;
                JSONObject props = feature.optJSONObject("properties");
                String name = props == null
                        ? "Imported location" : props.optString("name", "Imported location");
                String notes = props == null ? "" : props.optString("notes", "");
                float accuracy = props == null ? -1f
                        : (float) props.optDouble("accuracyMeters", -1d);
                if (!Float.isFinite(accuracy)
                        || (accuracy < 0f
                            && accuracy != MANUAL_COORDINATE_ACCURACY
                            && accuracy != MINERAL_SOURCE_ACCURACY
                            && accuracy != HISTORIC_MINE_SOURCE_ACCURACY)) accuracy = -1f;
                long capturedAt = props == null ? now : props.optLong("capturedAt", now);
                long createdAt = props == null ? now : props.optLong("createdAt", now);
                long updatedAt = props == null ? now : props.optLong("updatedAt", now);
                if (name.length() > 500) name = name.substring(0, 500);
                if (notes.length() > 20_000) notes = notes.substring(0, 20_000);
                imports.add(new WaypointEntity(latitude, longitude, accuracy, capturedAt,
                        name, notes, createdAt, updatedAt));
            }
            if (imports.isEmpty()) {
                showMessage("No valid point locations were found in that file.");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Import Saved Locations?")
                    .setMessage("RockMap found " + imports.size()
                            + " valid point locations. Importing adds them; it does not delete existing locations.")
                    .setPositiveButton("Import", (d, w) ->
                            waypointRepository.insertAll(imports, count -> {
                                refreshWaypoints();
                                showMessage("Imported " + count + " saved location"
                                        + (count == 1 ? "." : "s."));
                            }))
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (IOException | JSONException ex) {
            showMessage("Saved Location import rejected: " + ex.getMessage());
        }
    }

    private byte[] readContentUri(Uri uri, int maxBytes) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Android could not open the selected file.");
            byte[] buffer = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Selected file exceeds the import size limit.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private boolean ensureLocationPermission(boolean precise) {
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (precise ? fine : (coarse || fine)) return true;

        boolean centering = pendingLocationAction == LOCATION_ACTION_CENTER;
        boolean researchPoint = pendingLocationAction == LOCATION_ACTION_RESEARCH_GPS;
        String title = precise ? "Allow precise location?" : "Allow location?";
        String message = precise
                ? (centering
                    ? "RockMap needs Android's precise location permission to center the map on your actual GPS position. Location is used only while the app is open and is not sent to RockMap servers. RockMap does not request background location."
                    : researchPoint
                        ? "RockMap needs Android's precise location permission to run Research around your current GPS position. The GPS coordinate is used locally for the query and is not sent to RockMap servers. RockMap does not request background location."
                        : "RockMap uses precise location only while the app is open so you can save a field waypoint at your current GPS position. The waypoint stays on this device unless you export it. RockMap does not request background location.")
                : "RockMap uses location only while the app is open. Your location is not sent to RockMap servers.";
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Continue", (d, w) -> {
                    if (precise) {
                        requestPermissions(new String[]{
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        }, LOCATION_PERMISSION_REQUEST);
                    } else {
                        requestPermissions(new String[]{
                                Manifest.permission.ACCESS_COARSE_LOCATION
                        }, LOCATION_PERMISSION_REQUEST);
                    }
                })
                .setNegativeButton("Not now", (d, w) ->
                        pendingLocationAction = LOCATION_ACTION_NONE)
                .show();
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) return;

        int requestedAction = pendingLocationAction;
        pendingLocationAction = LOCATION_ACTION_NONE;
        boolean coarse = locationRepository.hasCoarsePermission();
        boolean fine = locationRepository.hasFinePermission();

        if (!coarse && !fine) {
            showMessage("Location permission denied. Offline maps remain available, but GPS features are disabled.");
            return;
        }

        if (started) locationRepository.start();
        if (requestedAction == LOCATION_ACTION_CENTER) {
            if (fine) {
                mapView.post(this::locate);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Precise location required")
                        .setMessage("RockMap needs Android's Precise location setting to center accurately on your GPS position. Approximate location can be intentionally offset by Android.")
                        .setPositiveButton("App settings", (d, w) -> openAppSettings())
                        .setNegativeButton("Close", null)
                        .show();
            }
            return;
        }
        if (requestedAction == LOCATION_ACTION_SAVE) {
            if (fine) {
                mapView.post(this::saveLocation);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Precise location required")
                        .setMessage("Approximate location is enough for GPS viewing, but Save GPS requires Android's precise-location permission.")
                        .setPositiveButton("App settings", (d, w) -> openAppSettings())
                        .setNegativeButton("Close", null)
                        .show();
            }
            return;
        }
        if (requestedAction == LOCATION_ACTION_RESEARCH_GPS) {
            if (fine) {
                final double radius = pendingResearchRadiusMeters;
                mapView.post(() -> researchAtFreshGps(radius));
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Precise location required")
                        .setMessage("Research around Current GPS requires Android's Precise location setting. RockMap will not substitute an approximate location for this query.")
                        .setPositiveButton("App settings", (d, w) -> openAppSettings())
                        .setNegativeButton("Close", null)
                        .show();
            }
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    @Override
    public void onLocation(Location location) {
        mapController.updateCurrentLocation(location);
    }

    @Override
    public void onLocationError(String message) {
        showMessage(message);
    }

    @Override
    public void onMapSafetyState(boolean verified, String message) {
        // The bundled Protomaps style already contains named peak labels, but its inherited
        // zoom-10 floor hides useful regional landmarks such as Mount Antero. Prominent peaks
        // are already ranked by the basemap; expose that existing offline layer from zoom 6.5.
        mapView.getMapAsync(mapLibreMap -> mapLibreMap.getStyle(loadedStyle -> {
            Layer peakLabels = loadedStyle.getLayer(MapController.LABEL_PEAK);
            if (peakLabels != null) peakLabels.setMinZoom(6.5f);
        }));

        if (activePlaceTarget != null) renderPlaceSearchTarget(false);

        if (mineralOverlayController != null) {
            mineralOverlayController.refreshStyle();
        }
        if (geologyOverlayController != null) {
            geologyOverlayController.refreshStyle();
        }
        if (historicMinesRequestedVisible
                && historicMineOverlayController != null
                && historicMineOverlayController.isLoaded()) {
            historicMineOverlayController.refreshStyle();
            syncHistoricMineCloseTarget();
        }
        MapContextCloseController.forMap(mapView).refresh();
        consumePendingResearchLaunch();
    }

    @Override
    public boolean onWaypointTapped(WaypointEntity waypoint) {
        if (waypoint == null) return false;
        showWaypoint(waypoint);
        return true;
    }

    @Override
    public boolean onMapOverlayTapped(LatLng coordinate, List<Feature> landAtCoordinate) {
        pendingOverlayTapLand = landAtCoordinate == null
                ? new ArrayList<>() : new ArrayList<>(landAtCoordinate);
        try {
            if (geologyOverlayController != null && geologyOverlayController.handleTap(coordinate)) {
                return true;
            }
            if (mineralOverlayController != null && mineralOverlayController.handleTap(coordinate)) {
                return true;
            }
            return historicMineOverlayController != null
                    && historicMineOverlayController.handleTap(coordinate);
        } finally {
            pendingOverlayTapLand = new ArrayList<>();
        }
    }

    @Override
    public void onMapFeaturesTapped(LatLng coordinate, List<Feature> land, List<Feature> claims) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.US, "%.6f, %.6f",
                coordinate.getLatitude(), coordinate.getLongitude()));
        text.append("\n\nLAND STATUS\n");
        if (!mapController.hasLandStatusAvailable()) {
            text.append("Land-status data is not included in the active map. No land-status conclusion was made.");
        } else if (!mapController.isLandVisible()) {
            text.append("Land-status layer is turned off. No land-status conclusion was made.");
        } else if (land.isEmpty()) {
            text.append("No land-status feature was rendered at this exact tap. Treat this as unknown, not as public land.");
        } else {
            for (Feature feature : land) {
                String manager = stringProp(feature, "manager_name", "Unknown manager");
                String code = stringProp(feature, "manager_code", "");
                String category = LandStatusCatalog.labelFor(code, manager);
                text.append("• ").append(category);
                if (!sameLandLabel(category, manager) && !"Unknown manager".equals(manager)) {
                    text.append(" — ").append(manager);
                }
                if (!code.isEmpty()) text.append(" [").append(code).append(']');
                text.append('\n');
            }
        }

        text.append("\nMINING CLAIMS — BLM MLRS NOT CLOSED\n");
        if (!mapController.hasClaimsAvailable()) {
            text.append("Mining-claim data is not included in the active test snapshot. No claim conclusion was made.");
        } else if (!mapController.isClaimsVisible()) {
            text.append("Claims layer is turned off. No claim conclusion was made.");
        } else if (claims.isEmpty()) {
            text.append("No claim feature was rendered at this exact tap and zoom. This is not proof that no mining claim exists.");
        } else {
            text.append(claims.size()).append(claims.size() == 1
                    ? " claim case at this location\n"
                    : " claim cases at this location\n");
            int shown = 0;
            for (Feature feature : claims) {
                if (shown >= 20) break;
                if (shown > 0) text.append('\n');
                shown++;
                String name = stringProp(feature, "name", "Unnamed claim");
                String serial = stringProp(feature, "serial", "");
                String legacy = stringProp(feature, "legacy_serial", "");
                String type = stringProp(feature, "type", "Unknown claim type");
                String disposition = stringProp(feature, "disposition", "Not reported");
                String acres = stringProp(feature, "acres", "");
                String quality = compactClaimQuality(feature);

                text.append("• ").append(name).append('\n');
                text.append("  ").append(type);
                text.append("\n  Status: ").append(disposition);
                if (!serial.isEmpty()) text.append("\n  Serial: ").append(serial);
                if (!legacy.isEmpty() && !legacy.equalsIgnoreCase(serial)) {
                    text.append("\n  Legacy: ").append(legacy);
                }
                if (!acres.isEmpty()) text.append("\n  Area: ").append(acres).append(" acres");
                text.append("\n  Mapping: ").append(quality).append('\n');
            }
            if (claims.size() > shown) {
                text.append("\n… ").append(claims.size() - shown)
                        .append(" additional overlapping claim cases not expanded here.\n");
            }
        }
        text.append("\nBLM Surface Management Agency data is management/status mapping, not a parcel survey or surveyed legal boundary. The claim overlay contains BLM MLRS records selected from the Not Closed dataset; it is not a surveyed claim-boundary product, and BLM says some cases may have no geospatial representation. RockMap does not determine whether collecting is legal at a location.");

        TextView body = new TextView(this);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(text.toString());
        new AlertDialog.Builder(this)
                .setTitle("Location information")
                .setView(boundedScrollableContent(body, 440))
                .setPositiveButton("Save Location", (d, w) -> showManualMarkerDialog(
                        new CoordinateParser.Result(
                                coordinate.getLatitude(), coordinate.getLongitude())))
                .setNegativeButton("Close", null)
                .show();
    }

    private String compactClaimQuality(Feature feature) {
        String description = stringProp(feature, "quality_description",
                "BLM mapping quality not reported").trim();
        int sourceValue = description.indexOf("; source value:");
        if (sourceValue >= 0) description = description.substring(0, sourceValue).trim();

        final String prefix = "BLM quality score ";
        if (description.startsWith(prefix)) {
            String remainder = description.substring(prefix.length()).trim();
            int colon = remainder.indexOf(':');
            if (colon > 0) {
                String score = remainder.substring(0, colon).trim();
                String label = remainder.substring(colon + 1).trim();
                if (!label.isEmpty() && !score.isEmpty()) return label + " — quality " + score;
            }
        }
        return description;
    }

    private String stringProp(Feature feature, String name, String fallback) {
        if (feature == null || !feature.hasProperty(name)
                || feature.getStringProperty(name) == null) return fallback;
        return feature.getStringProperty(name);
    }

    private boolean sameLandLabel(String a, String b) {
        if (a == null || b == null) return false;
        return a.replaceAll("[^A-Za-z0-9]", "")
                .equalsIgnoreCase(b.replaceAll("[^A-Za-z0-9]", ""));
    }

    private String boundedText(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private void showMessage(String message) {
        Toast.makeText(this, message == null ? "" : message, Toast.LENGTH_LONG).show();
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        mapView.onStart();
        if (locationRepository.hasCoarsePermission()) locationRepository.start();
    }

    @Override protected void onResume() {
        super.onResume();
        mapView.onResume();
        MapContextCloseController.forMap(mapView).refresh();
        FieldMapController.ensurePersistentEntry(this);
    }

    @Override protected void onPause() {
        // Persist the recoverable Research workspace before MapLibre/activity state is paused.
        saveResearchSession();
        mapView.onPause();
        super.onPause();
    }

    @Override protected void onStop() {
        saveResearchSession();
        started = false;
        locationRepository.stop();
        mapView.onStop();
        super.onStop();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putLong(STATE_PENDING_TRIP_EXPORT_ID, pendingTripExportId);
        saveResearchSession();
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        clearUpdateObserver();
        placeIndexRepository.close();
        waypointRepository.close();
        tripRepository.close();
        mapView.onDestroy();
        super.onDestroy();
    }
}
