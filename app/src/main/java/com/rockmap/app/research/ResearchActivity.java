package com.rockmap.app.research;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.WorkInfo;

import com.rockmap.app.BuildConfig;
import com.rockmap.app.field.FieldDatabase;
import com.rockmap.app.field.GeoMath;
import com.rockmap.app.waypoints.WaypointEntity;
import com.rockmap.app.waypoints.WaypointRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ResearchActivity extends Activity {
    public static final String EXTRA_SOUTH = "rockmap.research.south";
    public static final String EXTRA_WEST = "rockmap.research.west";
    public static final String EXTRA_NORTH = "rockmap.research.north";
    public static final String EXTRA_EAST = "rockmap.research.east";
    public static final String EXTRA_AREA_ID = "rockmap.research.area_id";
    public static final String EXTRA_POINT_LAT = "rockmap.research.point_lat";
    public static final String EXTRA_POINT_LON = "rockmap.research.point_lon";
    public static final String EXTRA_RADIUS_M = "rockmap.research.radius_m";
    public static final String EXTRA_POINT_LABEL = "rockmap.research.point_label";

    public static final String RESULT_ACTION = "rockmap.research.result_action";
    public static final String RESULT_TITLE = "rockmap.research.title";
    public static final String RESULT_COUNT = "rockmap.research.count";
    public static final String ACTION_GEOLOGY = "geology";
    public static final String ACTION_MINERALS = "minerals";
    public static final String ACTION_MINERALS_AREA = "minerals_area";
    public static final String ACTION_HISTORIC_MINES = "historic_mines";
    public static final String ACTION_DATA = "data";
    public static final String ACTION_GPS_POINT = "gps_point";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private GeologyRepository geology;
    private GeologyDataManager geologyDataManager;
    private FieldDatabase fieldDb;
    private WaypointRepository waypointRepository;
    private GeologyRepository.Bounds visibleBounds;
    private List<GeologyUnit> currentResults = new ArrayList<>();
    private String currentResultTitle = "Analysis";
    private GeologyRepository.Bounds currentResultBounds;
    private LiveData<WorkInfo> geologyUpdateLiveData;
    private Observer<WorkInfo> geologyUpdateObserver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        geology = new GeologyRepository(this);
        geologyDataManager = new GeologyDataManager(this);
        fieldDb = FieldDatabase.get(this);
        waypointRepository = new WaypointRepository(this);
        visibleBounds = readBounds(getIntent());
        setTitle("RockMap Research");

        if (!resumeRequestedScope()) showHub();
    }

    private boolean resumeRequestedScope() {
        Intent intent = getIntent();
        long areaId = intent == null ? -1L : intent.getLongExtra(EXTRA_AREA_ID, -1L);
        if (areaId > 0L) {
            intent.removeExtra(EXTRA_AREA_ID);
            if (!geology.isReady()) showInstall();
            else analyzeArea(areaId);
            return true;
        }
        if (intent != null && intent.hasExtra(EXTRA_POINT_LAT) && intent.hasExtra(EXTRA_POINT_LON)) {
            if (!geology.isReady()) {
                intent.removeExtra(EXTRA_POINT_LAT);
                intent.removeExtra(EXTRA_POINT_LON);
                intent.removeExtra(EXTRA_POINT_LABEL);
                intent.removeExtra(EXTRA_RADIUS_M);
                showInstall();
                return true;
            }
            double lat = intent.getDoubleExtra(EXTRA_POINT_LAT, Double.NaN);
            double lon = intent.getDoubleExtra(EXTRA_POINT_LON, Double.NaN);
            String label = intent.getStringExtra(EXTRA_POINT_LABEL);
            boolean hasRadius = intent.hasExtra(EXTRA_RADIUS_M);
            double radius = intent.getDoubleExtra(EXTRA_RADIUS_M, 1000d);
            intent.removeExtra(EXTRA_POINT_LAT);
            intent.removeExtra(EXTRA_POINT_LON);
            intent.removeExtra(EXTRA_POINT_LABEL);
            intent.removeExtra(EXTRA_RADIUS_M);
            if (hasRadius) runPointRadius(lat, lon, radius, blank(label, "Selected point"));
            else showRadiusPicker(lat, lon, blank(label, "Selected point"));
            return true;
        }
        return false;
    }

    private void showInstall() {
        LinearLayout root = page();
        root.addView(title("Research"));
        root.addView(section("Colorado geology is not installed"));
        root.addView(help("RockMap does not download geology from the live USGS service on this device. It first checks a small RockMap geology manifest so the exact download size can be shown before you decide whether to install it."));
        root.addView(help("After a verified pack is installed, geology searches and spatial queries use the local database and work offline."));
        root.addView(action("Check Colorado Geology Pack",
                "Check the published version and exact download/install sizes. Checking the small manifest does not download the geology database.",
                v -> startGeologyDataUpdate()));
        root.addView(action("Search Mineral Evidence",
                "Mineral Evidence does not depend on the geology pack.",
                v -> returnAction(ACTION_MINERALS, null)));
        root.addView(nav("Back", v -> finish()));
        setContentView(scroll(root));
    }

    private void showHub() {
        LinearLayout root = page();
        root.addView(title("Research"));
        root.addView(help("Choose what you want to investigate. Mineral Evidence and Geology stay distinct until you deliberately combine them in an area analysis."));

        root.addView(section("Mineral Evidence"));
        root.addView(action("Search Mineral Evidence",
                "Search installed mineral, locality, and evidence records across Colorado or the Visible Area.",
                v -> returnAction(ACTION_MINERALS, null)));
        if (visibleBounds != null) {
            root.addView(action("Analyze Visible Area",
                    "Summarize mineral evidence in the area currently visible on the map.",
                    v -> returnBoundsAction(ACTION_MINERALS_AREA, visibleBounds)));
        }

        root.addView(section("Geology"));
        if (geology.isReady()) {
            root.addView(action("Search Geology",
                    "Search formation and unit names, rock types, and geologic ages.",
                    v -> showSearch()));
            if (visibleBounds != null) {
                root.addView(action("Analyze Visible Area",
                        "See the mapped geologic units intersecting the area currently visible on the map.",
                        v -> runBoundsQuery(visibleBounds, "Geology — Visible Area")));
            }
            root.addView(action("Analyze Around a Point",
                    "Choose Map Center, Current GPS, Saved Location, Field Record, or entered coordinates, then choose a radius.",
                    v -> showPointSourcePicker()));

            root.addView(section("Combined Area Analysis"));
            root.addView(action("Prospecting Areas",
                    "Choose a saved Prospecting Area and analyze its geology, then continue into Mineral Evidence and historic activity.",
                    v -> showAreaPicker()));
            if (visibleBounds != null) {
                root.addView(action("Analyze Visible Area",
                        "Start with geology for the Visible Area, then continue into Mineral Evidence and Historic Mines & Workings.",
                        v -> runBoundsQuery(visibleBounds, "Geology — Visible Area")));
            }
        } else {
            root.addView(help("Colorado geology is not installed. Mineral Evidence remains available above."));
            root.addView(action("Install Colorado Geology",
                    "Check the current fixed RockMap geology pack and see its exact download size before anything is downloaded.",
                    v -> startGeologyDataUpdate()));
        }

        if (geology.isReady()) {
            GeologyManifest active = geologyDataManager.getActiveManifest();
            String version = active == null || active.version.isEmpty() ? "installed snapshot" : active.version;
            root.addView(help(geology.getRecordCount() + " mapped geology areas installed · "
                    + formatBytes(geology.getDatabaseBytes()) + " local database · " + version + "."));
            root.addView(action("Check Geology Update",
                    "Check for a newer fixed Colorado geology pack. RockMap shows the exact download and installed sizes before you confirm.",
                    v -> startGeologyDataUpdate()));
        }
        if (ResearchResultStore.exists(this)) {
            ResearchResultStore.Summary r = ResearchResultStore.summary(this);
            root.addView(action("Last Analysis",
                    r.title + " · " + r.count + " mapped area" + (r.count == 1 ? "" : "s") + " · export from Field → Export Data.",
                    v -> showStoredResult()));
        }
        root.addView(nav("Back", v -> finish()));
        setContentView(scroll(root));
    }

    private void startGeologyDataUpdate() {
        if (BuildConfig.GEOLOGY_MANIFEST_URL == null || BuildConfig.GEOLOGY_MANIFEST_URL.trim().isEmpty()) {
            toast("This APK was not built from a configured public RockMap repository.");
            return;
        }
        toast("Checking Colorado geology pack size…");
        GeologyDataPreviewer.preview(this, BuildConfig.GEOLOGY_MANIFEST_URL,
                new GeologyDataPreviewer.Callback() {
                    @Override public void onPreview(GeologyDataPreviewer.Preview preview) {
                        if (isFinishing() || isDestroyed()) return;
                        if (!preview.published) {
                            new AlertDialog.Builder(ResearchActivity.this)
                                    .setTitle("Colorado Geology")
                                    .setMessage(preview.message.isEmpty()
                                            ? "No fixed Colorado geology pack is currently published. Nothing was downloaded."
                                            : preview.message)
                                    .setPositiveButton("OK", null)
                                    .show();
                            return;
                        }
                        if (!preview.needsDownload) {
                            new AlertDialog.Builder(ResearchActivity.this)
                                    .setTitle("Colorado Geology is current")
                                    .setMessage(preview.recordCount + " mapped geology areas are already installed.\n\nVersion: "
                                            + preview.version + "\nInstalled size: " + formatBytes(preview.installedBytes)
                                            + "\n\nNothing will be downloaded.")
                                    .setPositiveButton("OK", (d, w) -> showHub())
                                    .show();
                            return;
                        }

                        String message = "USGS SGMC geology for offline RockMap research."
                                + "\n\nDownload size: " + formatBytes(preview.downloadBytes)
                                + "\nInstalled size: " + formatBytes(preview.installedBytes)
                                + "\nMapped Colorado areas: " + preview.recordCount
                                + "\nVersion: " + preview.version
                                + "\n\nStored on this device and available offline after installation. "
                                + "RockMap verifies both the downloaded asset and installed SQLite database before activation. "
                                + "If verification fails, the working geology snapshot is kept.";
                        new AlertDialog.Builder(ResearchActivity.this)
                                .setTitle("Colorado Geology")
                                .setMessage(message)
                                .setPositiveButton("Download", (d, w) -> queueConfirmedGeologyUpdate())
                                .setNegativeButton("Cancel", null)
                                .show();
                    }

                    @Override public void onError(String message) {
                        if (isFinishing() || isDestroyed()) return;
                        new AlertDialog.Builder(ResearchActivity.this)
                                .setTitle("Could not check Colorado Geology")
                                .setMessage(message == null ? "The geology manifest could not be checked safely." : message)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
    }

    private void queueConfirmedGeologyUpdate() {
        androidx.work.OneTimeWorkRequest request = geologyDataManager.queueUpdate();
        toast("Downloading and verifying Colorado geology…");
        clearGeologyUpdateObserver();
        geologyUpdateLiveData = androidx.work.WorkManager.getInstance(this)
                .getWorkInfoByIdLiveData(request.getId());
        geologyUpdateObserver = new Observer<WorkInfo>() {
            @Override public void onChanged(WorkInfo info) {
                if (info == null || !info.getState().isFinished()) return;
                clearGeologyUpdateObserver();
                if (info.getState() == WorkInfo.State.SUCCEEDED) {
                    // A result generated from an older geology snapshot should not silently look current.
                    // This clears only the reproducible Research result, not user-created Field data.
                    ResearchResultStore.clear(ResearchActivity.this);
                    new AlertDialog.Builder(ResearchActivity.this)
                            .setTitle("Colorado Geology installed")
                            .setMessage(geologyDataManager.getLastUpdateStatus()
                                    + "\n\nGeology searches and area queries now use the verified local database and work offline."
                                    + "\n\nIf an older geology result is still visible on the map behind Research, run a new geology analysis before using that overlay; the saved prior Research result was cleared when the new snapshot activated.")
                            .setPositiveButton("Research", (d, w) -> showHub())
                            .show();
                } else {
                    new AlertDialog.Builder(ResearchActivity.this)
                            .setTitle("Colorado Geology was not changed")
                            .setMessage(geologyDataManager.getLastUpdateStatus())
                            .setPositiveButton("OK", null)
                            .show();
                }
            }
        };
        geologyUpdateLiveData.observeForever(geologyUpdateObserver);
    }

    private void clearGeologyUpdateObserver() {
        if (geologyUpdateLiveData != null && geologyUpdateObserver != null) {
            geologyUpdateLiveData.removeObserver(geologyUpdateObserver);
        }
        geologyUpdateLiveData = null;
        geologyUpdateObserver = null;
    }

    private void showSearch() {
        LinearLayout box = page();
        EditText text = input("Unit, label, rock, or geology term", "");
        EditText lith = input("Lithology filter (optional)", "");
        EditText age = input("Age filter (optional)", "");
        CheckBox visible = new CheckBox(this);
        visible.setText("Search Visible Area Only");
        visible.setChecked(visibleBounds != null);
        visible.setEnabled(visibleBounds != null);
        visible.setMinHeight(dp(48));
        box.addView(text);
        box.addView(lith);
        box.addView(age);
        box.addView(visible);

        TextView suggestions = help(suggestionText(""));
        box.addView(suggestions);
        text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                suggestions.setText(suggestionText(s == null ? "" : s.toString()));
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Search Geology")
                .setView(scroll(box))
                .setPositiveButton("Search", null)
                .setNegativeButton("Cancel", (d, w) -> showHub())
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            GeologyRepository.Filter filter = new GeologyRepository.Filter(
                    text.getText().toString(), lith.getText().toString(), age.getText().toString());
            if (filter.text.isEmpty() && filter.lithology.isEmpty() && filter.age.isEmpty()) {
                text.setError("Enter a search or filter term.");
                return;
            }
            GeologyRepository.Bounds bounds = visible.isChecked() ? visibleBounds : null;
            dialog.dismiss();
            runAsync("Searching geology…", () -> geology.search(filter, bounds, 0),
                    results -> showResults(results,
                            "Geology Search" + (filter.text.isEmpty() ? "" : ": " + text.getText().toString().trim()),
                            bounds));
        }));
        dialog.show();
        text.requestFocus();
    }

    private String suggestionText(String prefix) {
        try {
            List<String> suggestions = geology.suggestions(prefix, 8);
            if (suggestions.isEmpty()) return "Try a formation name, rock type, or geologic age.";
            return "Try: " + String.join(" · ", suggestions);
        } catch (RuntimeException ex) {
            return "Suggestions unavailable.";
        }
    }

    private void runBoundsQuery(GeologyRepository.Bounds bounds, String title) {
        runAsync("Analyzing map area…", () -> geology.queryBounds(bounds, 0),
                results -> showResults(results, title, bounds));
    }

    private void showAreaPicker() {
        List<FieldDatabase.Area> areas = fieldDb.listAreas();
        if (areas.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No Prospecting Areas yet")
                    .setMessage("Open Field → Prospecting Areas → Create Prospecting Area. Saved areas then appear here for one-tap analysis.")
                    .setPositiveButton("Back", (d, w) -> showHub())
                    .show();
            return;
        }
        String[] labels = new String[areas.size()];
        for (int i = 0; i < areas.size(); i++) {
            FieldDatabase.Area a = areas.get(i);
            labels[i] = a.name + "\n" + a.points.size() + " vertices";
        }
        new AlertDialog.Builder(this)
                .setTitle("Prospecting Areas")
                .setMessage("Choose a saved area to analyze.")
                .setItems(labels, (d, which) -> analyzeArea(areas.get(which).id))
                .setNegativeButton("Cancel", (d, w) -> showHub())
                .show();
    }

    private void analyzeArea(long areaId) {
        FieldDatabase.Area area = fieldDb.getArea(areaId);
        if (area == null || area.points == null || area.points.size() < 3) {
            toast("Saved area could not be read.");
            showHub();
            return;
        }
        ArrayList<GeologyRepository.Point> polygon = new ArrayList<>();
        for (GeoMath.Point p : area.points) polygon.add(new GeologyRepository.Point(p.lat, p.lon));
        GeologyRepository.Bounds bounds = boundsOf(polygon);
        runAsync("Analyzing " + area.name + "…", () -> geology.queryPolygon(polygon, 0),
                results -> showResults(results, "Geology — Prospecting Area: " + area.name, bounds));
    }

    private void showPointSourcePicker() {
        LinearLayout root = page();
        root.addView(title("Analyze Around a Point"));
        root.addView(help("Choose the location first, then choose how far around it to analyze."));
        if (visibleBounds != null) {
            final double lat = (visibleBounds.south + visibleBounds.north) / 2d;
            final double lon = (visibleBounds.west + visibleBounds.east) / 2d;
            root.addView(action("Map Center",
                    String.format(Locale.US, "%.5f, %.5f · center of the map you were viewing", lat, lon),
                    v -> showRadiusPicker(lat, lon, "Map Center")));
        }
        root.addView(action("Current GPS",
                "Use a fresh precise GPS fix. If precise permission is not enabled, RockMap will tell you rather than silently using an approximate point.",
                v -> showGpsRadiusPicker()));
        root.addView(action("Saved Location",
                "Choose one of your normal RockMap Saved Locations.",
                v -> showSavedLocationPointPicker()));
        root.addView(action("Field Record",
                "Choose one of your saved Field Records.",
                v -> showFieldRecordPointPicker()));
        root.addView(action("Enter Coordinates",
                "Paste or type latitude, longitude.",
                v -> showCoordinatePointEntry()));
        root.addView(nav("Back to Research", v -> showHub()));
        setContentView(scroll(root));
    }

    private void showGpsRadiusPicker() {
        showRadiusChoices("Current GPS", meters -> {
            Intent result = new Intent();
            result.putExtra(RESULT_ACTION, ACTION_GPS_POINT);
            result.putExtra(EXTRA_RADIUS_M, meters);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private void showSavedLocationPointPicker() {
        waypointRepository.getAll(items -> {
            if (items == null || items.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("No Saved Locations")
                        .setMessage("Save a location first, or choose another point source.")
                        .setPositiveButton("Back", (d, w) -> showPointSourcePicker())
                        .show();
                return;
            }
            String[] labels = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                WaypointEntity item = items.get(i);
                String name = item.name == null || item.name.trim().isEmpty() ? "Saved Location" : item.name.trim();
                labels[i] = name + String.format(Locale.US, "\n%.5f, %.5f", item.latitude, item.longitude);
            }
            new AlertDialog.Builder(this)
                    .setTitle("Choose Saved Location")
                    .setItems(labels, (d, which) -> {
                        WaypointEntity item = items.get(which);
                        String name = item.name == null || item.name.trim().isEmpty() ? "Saved Location" : item.name.trim();
                        showRadiusPicker(item.latitude, item.longitude, name);
                    })
                    .setNegativeButton("Cancel", (d, w) -> showPointSourcePicker())
                    .show();
        });
    }

    private void showFieldRecordPointPicker() {
        List<FieldDatabase.FieldRecord> records = fieldDb.listFieldRecords();
        if (records.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No Field Records")
                    .setMessage("Create a Field Record first, or choose another point source.")
                    .setPositiveButton("Back", (d, w) -> showPointSourcePicker())
                    .show();
            return;
        }
        String[] labels = new String[records.size()];
        for (int i = 0; i < records.size(); i++) {
            FieldDatabase.FieldRecord r = records.get(i);
            labels[i] = r.name + String.format(Locale.US, "\n%.5f, %.5f", r.lat, r.lon);
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose Field Record")
                .setItems(labels, (d, which) -> {
                    FieldDatabase.FieldRecord r = records.get(which);
                    showRadiusPicker(r.lat, r.lon, r.name);
                })
                .setNegativeButton("Cancel", (d, w) -> showPointSourcePicker())
                .show();
    }

    private void showCoordinatePointEntry() {
        EditText coords = input("Latitude, longitude", "");
        new AlertDialog.Builder(this)
                .setTitle("Enter Coordinates")
                .setView(coords)
                .setPositiveButton("Next", (d, w) -> {
                    try {
                        double[] ll = parseLatLon(coords.getText().toString());
                        showRadiusPicker(ll[0], ll[1], "Entered Coordinate");
                    } catch (RuntimeException ex) {
                        toast(ex.getMessage() == null ? "Invalid coordinate." : ex.getMessage());
                        showCoordinatePointEntry();
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> showPointSourcePicker())
                .show();
    }

    private void showRadiusPicker(double lat, double lon, String label) {
        showRadiusChoices(label, meters -> runPointRadius(lat, lon, meters, label));
    }

    private interface RadiusChoice { void choose(double meters); }

    private void showRadiusChoices(String label, RadiusChoice choice) {
        String[] rows = {"Exact Point", "250 m", "500 m", "1 km", "5 km", "Custom…"};
        new AlertDialog.Builder(this)
                .setTitle("Choose Radius — " + blank(label, "Selected Point"))
                .setItems(rows, (d, which) -> {
                    if (which == 0) choice.choose(0d);
                    else if (which == 1) choice.choose(250d);
                    else if (which == 2) choice.choose(500d);
                    else if (which == 3) choice.choose(1000d);
                    else if (which == 4) choice.choose(5000d);
                    else showCustomRadius(label, choice);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCustomRadius(String label, RadiusChoice choice) {
        EditText input = input("Radius in meters (0–100000)", "1000");
        new AlertDialog.Builder(this)
                .setTitle("Custom Radius")
                .setView(input)
                .setPositiveButton("Analyze", (d, w) -> {
                    try {
                        double meters = Double.parseDouble(input.getText().toString().trim());
                        if (!Double.isFinite(meters) || meters < 0d || meters > 100000d) {
                            throw new IllegalArgumentException("Radius must be between 0 and 100 km.");
                        }
                        choice.choose(meters);
                    } catch (RuntimeException ex) {
                        toast(ex.getMessage() == null ? "Invalid radius." : ex.getMessage());
                        showCustomRadius(label, choice);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runPointRadius(double lat, double lon, double radiusMeters, String pointLabel) {
        try {
            GeologyRepository.Point point = new GeologyRepository.Point(lat, lon);
            double latDelta = Math.max(0.00001d, Math.max(0d, radiusMeters) / 111320d);
            double lonDelta = Math.max(0.00001d, Math.max(0d, radiusMeters)
                    / (111320d * Math.max(0.1d, Math.cos(Math.toRadians(lat)))));
            GeologyRepository.Bounds bounds = new GeologyRepository.Bounds(
                    lat - latDelta, lon - lonDelta, lat + latDelta, lon + lonDelta);
            String distance = radiusMeters <= 0d ? "Exact Point" : radiusLabel(radiusMeters);
            String pointName = blank(pointLabel, "Selected Point");
            String resultTitle = radiusMeters <= 0d
                    ? "Geology — Exact Point at " + pointName
                    : "Geology — " + distance + " Around " + pointName;
            runAsync("Querying geology " + distance + "…", () -> geology.queryPointRadius(point, radiusMeters, 0),
                    results -> showResults(results, resultTitle, bounds));
        } catch (RuntimeException ex) {
            toast(ex.getMessage());
            showHub();
        }
    }

    private void showResults(List<GeologyUnit> results, String resultTitle,
                             GeologyRepository.Bounds queryBounds) {
        List<GeologyUnit> safe = results == null ? new ArrayList<>() : results;
        currentResults = new ArrayList<>(safe);
        currentResultTitle = resultTitle;
        currentResultBounds = queryBounds;
        String geoJson = geology.toGeoJson(safe);
        try {
            ResearchResultStore.save(this, resultTitle, geoJson, safe.size());
        } catch (IOException ex) {
            toast("Results are usable, but the export copy could not be saved: " + ex.getMessage());
        }

        List<UnitGroup> groups = groupUnits(safe);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0xfffafafa);
        screen.setOnApplyWindowInsetsListener((v, i) -> {
            v.setPadding(i.getSystemWindowInsetLeft(), i.getSystemWindowInsetTop(),
                    i.getSystemWindowInsetRight(), i.getSystemWindowInsetBottom());
            return i;
        });

        LinearLayout top = page();
        top.setPadding(dp(18), dp(12), dp(18), dp(4));
        top.addView(title(resultTitle));
        top.addView(help(compactSummary(safe, groups)));
        if (!safe.isEmpty()) {
            Button showMap = button("Show Geology on Map");
            showMap.setOnClickListener(v -> returnGeology(
                    geoJson, resultTitle, safe.size(), boundsOfUnits(safe)));
            top.addView(showMap);
        }
        top.addView(section("Geologic Units"));
        screen.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout unitList = new LinearLayout(this);
        unitList.setOrientation(LinearLayout.VERTICAL);
        unitList.setPadding(dp(8), dp(6), dp(8), dp(6));
        if (safe.isEmpty()) {
            unitList.addView(help("No installed mapped geology matched this query. This is absence of matching mapped evidence, not proof that a rock, mineral, or unit is absent on the ground."));
        } else {
            unitList.addView(help("Repeated mapped areas are grouped by geologic unit. Full source geometry remains available on the map and in export."));
            for (UnitGroup group : groups) {
                unitList.addView(action(group.name,
                        group.detailLine(),
                        v -> showUnitGroup(group, resultTitle)));
            }
        }

        ScrollView unitScroll = new ScrollView(this);
        unitScroll.setFillViewport(true);
        unitScroll.addView(unitList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout frame = new LinearLayout(this);
        frame.setOrientation(LinearLayout.VERTICAL);
        frame.setPadding(dp(2), dp(2), dp(2), dp(2));
        GradientDrawable frameBackground = new GradientDrawable();
        frameBackground.setColor(Color.WHITE);
        frameBackground.setStroke(dp(1), 0xffd2d2d2);
        frameBackground.setCornerRadius(dp(8));
        frame.setBackground(frameBackground);
        frame.addView(unitScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        frameParams.setMargins(dp(18), 0, dp(18), dp(6));
        screen.addView(frame, frameParams);

        LinearLayout bottom = page();
        bottom.setPadding(dp(18), 0, dp(18), dp(10));
        if (queryBounds != null) {
            bottom.addView(section("Analyze This Area Further"));
            Button minerals = button("Mineral Evidence");
            minerals.setOnClickListener(v -> returnBoundsAction(ACTION_MINERALS_AREA, queryBounds));
            bottom.addView(minerals);
            Button mines = button("Historic Mines & Workings");
            mines.setOnClickListener(v -> returnBoundsAction(ACTION_HISTORIC_MINES, queryBounds));
            bottom.addView(mines);
        }
        bottom.addView(nav("Back to Research", v -> showHub()));
        screen.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(screen);
        screen.requestApplyInsets();
    }

    private void showUnitGroup(UnitGroup group, String resultTitle) {
        LinearLayout root = page();
        root.addView(title(group.name));
        root.addView(help(group.detailLine()));
        root.addView(action("Show Unit on Map",
                group.units.size() + " mapped area" + (group.units.size() == 1 ? "" : "s") + " for this unit.",
                v -> returnGeology(geology.toGeoJson(group.units), group.name, group.units.size(), boundsOfUnits(group.units))));
        root.addView(action("Source & Technical Details",
                "View raw SGMC labels, full age hierarchy, references and source identifiers.",
                v -> showTechnicalDetails(group.representative())));
        if (group.units.size() > 1) {
            root.addView(help(group.units.size() + " separate mapped areas are represented by this grouped unit. Their individual source geometry remains available on the map and in GeoJSON export."));
        }
        root.addView(nav("Back to Results", v -> showResults(currentResults, currentResultTitle, currentResultBounds)));
        setContentView(scroll(root));
    }

    private String compactSummary(List<GeologyUnit> polygons, List<UnitGroup> groups) {
        if (polygons == null || polygons.isEmpty()) return "0 mapped geology areas in this result.";
        LinkedHashMap<String, Integer> lith = new LinkedHashMap<>();
        for (UnitGroup group : groups) addCount(lith, group.lithology);
        StringBuilder out = new StringBuilder();
        out.append(groups.size()).append(" geologic unit").append(groups.size() == 1 ? "" : "s")
                .append(" · ").append(polygons.size()).append(" mapped area").append(polygons.size() == 1 ? "" : "s").append('.');
        String rocks = topNames(lith, 3);
        if (!rocks.isEmpty()) out.append("\nCommon rock types: ").append(rocks).append('.');
        out.append("\nMapped-area counts do not indicate land coverage.");
        return out.toString();
    }

    private static List<UnitGroup> groupUnits(List<GeologyUnit> units) {
        LinkedHashMap<String, UnitGroup> grouped = new LinkedHashMap<>();
        if (units != null) {
            for (GeologyUnit unit : units) {
                if (unit == null) continue;
                String key = unit.resultGroupKey();
                UnitGroup group = grouped.get(key);
                if (group == null) {
                    group = new UnitGroup(unit.displayName(), unit.compactLithologyLabel(), unit.compactAgeLabel());
                    grouped.put(key, group);
                }
                group.units.add(unit);
            }
        }
        return new ArrayList<>(grouped.values());
    }

    private static final class UnitGroup {
        final String name;
        final String lithology;
        final String age;
        final ArrayList<GeologyUnit> units = new ArrayList<>();
        UnitGroup(String name, String lithology, String age) {
            this.name = name == null || name.trim().isEmpty() ? "Unnamed geologic unit" : name.trim();
            this.lithology = lithology == null ? "" : lithology.trim();
            this.age = age == null ? "" : age.trim();
        }
        GeologyUnit representative() { return units.get(0); }
        String detailLine() {
            StringBuilder out = new StringBuilder();
            if (!lithology.isEmpty()) out.append(lithology);
            if (!age.isEmpty()) {
                if (out.length() > 0) out.append(" · ");
                out.append(age);
            }
            if (out.length() > 0) out.append("\n");
            out.append(units.size()).append(" mapped area").append(units.size() == 1 ? "" : "s");
            return out.toString();
        }
    }

    private static String topNames(Map<String, Integer> map, int limit) {
        ArrayList<Map.Entry<String, Integer>> rows = new ArrayList<>(map.entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.size() && i < limit; i++) {
            String name = rows.get(i).getKey();
            if (name == null || name.trim().isEmpty() || "Not reported".equals(name)) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(name);
        }
        return out.toString();
    }

    private void showTechnicalDetails(GeologyUnit u) {
        StringBuilder text = new StringBuilder();
        text.append("Unit: ").append(u.displayName());
        append(text, "SGMC label", u.sgmcLabel);
        append(text, "Original label", u.originalLabel);
        append(text, "Full age range", u.ageLabel());
        append(text, "Generalized lithology", u.generalizedLithology);
        append(text, "Major lithology", joinNonblank(u.major1, u.major2, u.major3));
        append(text, "Minor lithology", joinNonblank(u.minor1, u.minor2, u.minor3, u.minor4, u.minor5));
        append(text, "Incidental", u.incidental);
        append(text, "Indeterminate", u.indeterminate);
        append(text, "Unit link", u.unitLink);
        append(text, "Reference ID", u.referenceId);
        append(text, "Reference", u.reference);
        append(text, "Digital source", u.digitalUrl);
        append(text, "NGMDB", joinNonblank(u.ngmdb1, u.ngmdb2, u.ngmdb3));
        text.append("\nSource polygon ID: ").append(u.objectId);
        text.append("\n\nSource: ").append(GeologyRepository.SOURCE_TITLE)
                .append("\nDOI: ").append(GeologyRepository.SOURCE_DOI)
                .append("\nScale: ").append(GeologyRepository.SOURCE_SCALE)
                .append("\n").append(GeologyRepository.SOURCE_NOTE)
                .append("\n\nMapped geology is interpretive source data. It does not determine land ownership, access, mining-claim status, hazards, or collecting permission.");
        new AlertDialog.Builder(this)
                .setTitle("Source & Technical Details")
                .setMessage(text.toString())
                .setPositiveButton("Close", null)
                .show();
    }

    private void showStoredResult() {
        try {
            ResearchResultStore.Summary s = ResearchResultStore.summary(this);
            String geoJson = ResearchResultStore.geoJson(this);
            new AlertDialog.Builder(this)
                    .setTitle(s.title)
                    .setMessage(s.count + " mapped geology area" + (s.count == 1 ? "" : "s")
                            + " saved as the current analysis. Field → Export Data can save the complete underlying records as GeoJSON or CSV.")
                    .setPositiveButton("Show on Map", (d, w) -> returnGeology(geoJson, s.title, s.count, null))
                    .setNeutralButton("Clear", (d, w) -> {
                        ResearchResultStore.clear(this);
                        showHub();
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (IOException ex) {
            toast("Stored analysis could not be read.");
            showHub();
        }
    }

    private <T> void runAsync(String message, Work<T> work, Result<T> result) {
        LinearLayout root = page();
        root.addView(title("Research"));
        root.addView(help(message));
        setContentView(scroll(root));
        executor.execute(() -> {
            try {
                T value = work.run();
                main.post(() -> result.accept(value));
            } catch (Exception ex) {
                main.post(() -> {
                    toast(ex.getMessage() == null ? "Research query failed safely." : ex.getMessage());
                    showHub();
                });
            }
        });
    }

    private interface Work<T> { T run() throws Exception; }
    private interface Result<T> { void accept(T value); }

    private void returnGeology(String geoJson, String title, int count, GeologyRepository.Bounds bounds) {
        try {
            ResearchResultStore.save(this, title, geoJson, count);
        } catch (IOException ex) {
            toast("Could not stage the analysis for the map: " + ex.getMessage());
            return;
        }
        Intent result = new Intent();
        result.putExtra(RESULT_ACTION, ACTION_GEOLOGY);
        result.putExtra(RESULT_TITLE, title);
        result.putExtra(RESULT_COUNT, count);
        putBounds(result, bounds);
        setResult(RESULT_OK, result);
        finish();
    }

    private void returnAction(String action, GeologyRepository.Bounds bounds) {
        Intent result = new Intent();
        result.putExtra(RESULT_ACTION, action);
        putBounds(result, bounds);
        setResult(RESULT_OK, result);
        finish();
    }

    private void returnBoundsAction(String action, GeologyRepository.Bounds bounds) {
        returnAction(action, bounds);
    }

    private static void putBounds(Intent intent, GeologyRepository.Bounds bounds) {
        if (bounds == null) return;
        intent.putExtra(EXTRA_SOUTH, bounds.south);
        intent.putExtra(EXTRA_WEST, bounds.west);
        intent.putExtra(EXTRA_NORTH, bounds.north);
        intent.putExtra(EXTRA_EAST, bounds.east);
    }

    private static GeologyRepository.Bounds readBounds(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_SOUTH) || !intent.hasExtra(EXTRA_WEST)
                || !intent.hasExtra(EXTRA_NORTH) || !intent.hasExtra(EXTRA_EAST)) return null;
        try {
            return new GeologyRepository.Bounds(
                    intent.getDoubleExtra(EXTRA_SOUTH, Double.NaN),
                    intent.getDoubleExtra(EXTRA_WEST, Double.NaN),
                    intent.getDoubleExtra(EXTRA_NORTH, Double.NaN),
                    intent.getDoubleExtra(EXTRA_EAST, Double.NaN));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static GeologyRepository.Bounds boundsOf(List<GeologyRepository.Point> points) {
        double south = 90d, west = 180d, north = -90d, east = -180d;
        for (GeologyRepository.Point p : points) {
            south = Math.min(south, p.lat); west = Math.min(west, p.lon);
            north = Math.max(north, p.lat); east = Math.max(east, p.lon);
        }
        return new GeologyRepository.Bounds(south, west, north, east);
    }

    private static GeologyRepository.Bounds boundsOfUnits(List<GeologyUnit> units) {
        if (units == null || units.isEmpty()) return null;
        double south = 90d, west = 180d, north = -90d, east = -180d;
        for (GeologyUnit u : units) {
            south = Math.min(south, u.south); west = Math.min(west, u.west);
            north = Math.max(north, u.north); east = Math.max(east, u.east);
        }
        return new GeologyRepository.Bounds(south, west, north, east);
    }

    private static double[] parseLatLon(String raw) {
        if (raw == null) throw new IllegalArgumentException("Enter latitude, longitude.");
        String[] parts = raw.trim().split("[,\\s]+", 2);
        if (parts.length != 2) throw new IllegalArgumentException("Use latitude, longitude.");
        double lat = Double.parseDouble(parts[0]);
        double lon = Double.parseDouble(parts[1]);
        new GeologyRepository.Point(lat, lon);
        return new double[]{lat, lon};
    }

    private static String radiusLabel(double meters) {
        if (meters >= 1000d) {
            double km = meters / 1000d;
            return km == Math.rint(km) ? String.format(Locale.US, "%.0f km", km)
                    : String.format(Locale.US, "%.1f km", km);
        }
        return String.format(Locale.US, "%.0f m", meters);
    }

    private static void addCount(Map<String, Integer> map, String raw) {
        String key = raw == null || raw.trim().isEmpty() ? "Not reported" : raw.trim();
        map.put(key, map.getOrDefault(key, 0) + 1);
    }

    private static void append(StringBuilder out, String label, String value) {
        if (value != null && !value.trim().isEmpty()) out.append("\n").append(label).append(": ").append(value.trim());
    }

    private static String joinNonblank(String... values) {
        StringBuilder out = new StringBuilder();
        for (String v : values) {
            if (v == null || v.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(v.trim());
        }
        return out.toString();
    }

    private static String blank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private LinearLayout page() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(14), dp(18), dp(24));
        l.setBackgroundColor(0xfffafafa);
        return l;
    }

    private ScrollView scroll(View content) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(content);
        s.setOnApplyWindowInsetsListener((v, i) -> {
            v.setPadding(i.getSystemWindowInsetLeft(), i.getSystemWindowInsetTop(),
                    i.getSystemWindowInsetRight(), i.getSystemWindowInsetBottom());
            return i;
        });
        s.requestApplyInsets();
        return s;
    }

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(24f);
        t.setTextColor(0xff202020);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16f);
        t.setTextColor(0xff303030);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView help(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(0xff555555);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setMinHeight(dp(50));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        return b;
    }

    private View action(String title, String detail, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackgroundColor(Color.WHITE);
        TextView h = new TextView(this);
        h.setText(title + "  ›");
        h.setTextSize(16f);
        h.setTextColor(0xff205b93);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(h);
        TextView d = help(detail);
        d.setPadding(0, dp(3), 0, 0);
        card.addView(d);
        card.setClickable(true);
        card.setFocusable(true);
        card.setMinimumHeight(dp(68));
        card.setOnClickListener(listener);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(4), 0, dp(4));
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private Button nav(String text, View.OnClickListener listener) {
        Button b = button(text);
        b.setOnClickListener(listener);
        return b;
    }

    private EditText input(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setSingleLine(true);
        e.setTextSize(14f);
        e.setPadding(dp(8), dp(8), dp(8), dp(8));
        return e;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message == null ? "" : message, Toast.LENGTH_LONG).show();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 MB";
        double mb = bytes / (1024d * 1024d);
        if (mb < 0.1d) return String.format(Locale.US, "%.0f KB", bytes / 1024d);
        return mb < 10d ? String.format(Locale.US, "%.1f MB", mb) : String.format(Locale.US, "%.0f MB", mb);
    }

    @Override protected void onDestroy() {
        clearGeologyUpdateObserver();
        executor.shutdownNow();
        if (waypointRepository != null) waypointRepository.close();
        super.onDestroy();
    }
}
