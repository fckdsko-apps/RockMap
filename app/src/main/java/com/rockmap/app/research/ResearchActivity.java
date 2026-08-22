package com.rockmap.app.research;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
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

import com.rockmap.app.field.FieldDatabase;
import com.rockmap.app.field.GeoMath;

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
    private FieldDatabase fieldDb;
    private GeologyRepository.Bounds visibleBounds;
    private List<GeologyUnit> currentResults = new ArrayList<>();
    private String currentResultTitle = "Research result";
    private GeologyRepository.Bounds currentResultBounds;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        geology = new GeologyRepository(this);
        fieldDb = FieldDatabase.get(this);
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
        root.addView(help("RockMap will not start an additional geology-data download unless the download size is known and shown before you confirm it. Unknown-size live-service downloads are disabled in this build."));
        root.addView(help("Existing RockMap maps, mineral evidence, saved data and Field tools remain usable. Colorado geology will be distributed as a versioned offline data pack with a declared byte size and checksum before download."));
        root.addView(action("Mineral Evidence",
                "Mineral search does not depend on the geology pack.",
                v -> returnAction(ACTION_MINERALS, null)));
        root.addView(action("Open Data",
                "Check the currently published RockMap offline-data pack and its disclosed download size.",
                v -> returnAction(ACTION_DATA, null)));
        root.addView(nav("Back", v -> finish()));
        setContentView(scroll(root));
    }

    private void showHub() {
        LinearLayout root = page();
        root.addView(title("Research"));
        root.addView(help("Choose what you want to investigate. Geology and mineral evidence stay separate until you deliberately combine them in an area analysis."));

        root.addView(section("Mineral Evidence"));
        root.addView(action("Search minerals / materials",
                "Search the existing installed mineral, locality and evidence records.",
                v -> returnAction(ACTION_MINERALS, null)));
        if (visibleBounds != null) {
            root.addView(action("Analyze visible mineral evidence",
                    "Analyze the mineral evidence in the map area you were just viewing.",
                    v -> returnBoundsAction(ACTION_MINERALS_AREA, visibleBounds)));
        }

        root.addView(section("Geology"));
        if (geology.isReady()) {
            root.addView(action("Search geology",
                    "Search formation/unit names, rock types and ages in the installed Colorado geology.",
                    v -> showSearch()));
            if (visibleBounds != null) {
                root.addView(action("Explore visible-map geology",
                        "See the mapped geologic units intersecting the area currently on screen.",
                        v -> runBoundsQuery(visibleBounds, "Visible-map geology")));
            }
            root.addView(action("Analyze around a point",
                    "Choose the map center, current GPS, a Field Record, or entered coordinates, then choose a radius.",
                    v -> showPointSourcePicker()));

            root.addView(section("Area Research"));
            root.addView(action("Prospecting Areas",
                    "Choose a saved prospecting polygon and analyze the actual polygon against geology, then cross-reference mineral evidence and historic activity.",
                    v -> showAreaPicker()));
            if (visibleBounds != null) {
                root.addView(action("Analyze current map area",
                        "Start with geology for the visible area, with mineral evidence and historic activity immediately available from the result.",
                        v -> runBoundsQuery(visibleBounds, "Current map area")));
            }
        } else {
            root.addView(help("Colorado geology is not installed. Mineral Evidence remains available above."));
            root.addView(action("Geology data",
                    "Open the data screen. RockMap will not begin a geology download unless its size is disclosed before confirmation.",
                    v -> returnAction(ACTION_DATA, null)));
        }

        root.addView(section("Offline data"));
        if (geology.isReady()) {
            root.addView(help(geology.getRecordCount() + " mapped geology polygons installed · "
                    + formatBytes(geology.getDatabaseBytes()) + " local database. Detailed source fields remain available on demand and in export."));
        }
        root.addView(action("Data & geology pack",
                "Manage RockMap offline data. Any additional geology pack must disclose its download size before download begins.",
                v -> returnAction(ACTION_DATA, null)));
        if (ResearchResultStore.exists(this)) {
            ResearchResultStore.Summary r = ResearchResultStore.summary(this);
            root.addView(action("Last Research result",
                    r.title + " · " + r.count + " mapped polygon" + (r.count == 1 ? "" : "s") + " · export from Field → Export data.",
                    v -> showStoredResult()));
        }
        root.addView(nav("Back", v -> finish()));
        setContentView(scroll(root));
    }

    private void showSearch() {
        LinearLayout box = page();
        EditText text = input("Unit, label, rock, or geology term", "");
        EditText lith = input("Lithology filter (optional)", "");
        EditText age = input("Age filter (optional)", "");
        CheckBox visible = new CheckBox(this);
        visible.setText("Limit to current visible map");
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
                .setTitle("Search Colorado geology")
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
                            "Geology search" + (filter.text.isEmpty() ? "" : ": " + text.getText().toString().trim()),
                            bounds));
        }));
        dialog.show();
        text.requestFocus();
    }

    private String suggestionText(String prefix) {
        try {
            List<String> suggestions = geology.suggestions(prefix, 8);
            if (suggestions.isEmpty()) return "Suggestions are generated from terms present in the installed geology snapshot.";
            return "Installed-data suggestions: " + String.join(" · ", suggestions);
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
                    .setMessage("Open Field → Prospecting Areas → Create area on map. Saved prospecting polygons then appear here for one-tap analysis.")
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
                .setMessage("Choose an area to analyze its saved polygon.")
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
                results -> showResults(results, "Prospecting Area: " + area.name, bounds));
    }

    private void showPointSourcePicker() {
        LinearLayout root = page();
        root.addView(title("Analyze around a point"));
        root.addView(help("Choose the point first. RockMap asks for the radius next—no nested geology menus."));
        if (visibleBounds != null) {
            final double lat = (visibleBounds.south + visibleBounds.north) / 2d;
            final double lon = (visibleBounds.west + visibleBounds.east) / 2d;
            root.addView(action("Current map center",
                    String.format(Locale.US, "%.5f, %.5f · pan the map first if you want a different center", lat, lon),
                    v -> showRadiusPicker(lat, lon, "Map center")));
        }
        root.addView(action("Current GPS",
                "Use a fresh precise GPS fix. If precise permission is not enabled, RockMap will tell you rather than silently using an approximate point.",
                v -> showGpsRadiusPicker()));
        root.addView(action("Field Record",
                "Choose one of your saved Field Records.",
                v -> showFieldRecordPointPicker()));
        root.addView(action("Enter coordinates",
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
                .setTitle("Enter point")
                .setView(coords)
                .setPositiveButton("Next", (d, w) -> {
                    try {
                        double[] ll = parseLatLon(coords.getText().toString());
                        showRadiusPicker(ll[0], ll[1], "Entered coordinate");
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
        String[] rows = {"At point", "250 m", "500 m", "1 km", "5 km", "Custom…"};
        new AlertDialog.Builder(this)
                .setTitle("Radius · " + blank(label, "Selected point"))
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
                .setTitle("Custom radius")
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
            String distance = radiusMeters <= 0d ? "at point" : radiusLabel(radiusMeters);
            runAsync("Querying geology " + distance + "…", () -> geology.queryPointRadius(point, radiusMeters, 0),
                    results -> showResults(results,
                            "Geology " + distance + " · " + blank(pointLabel, "Selected point"),
                            bounds));
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
        LinearLayout root = page();
        root.addView(title(resultTitle));
        root.addView(help(compactSummary(safe, groups)));

        if (safe.isEmpty()) {
            root.addView(help("No installed mapped geology matched this query. This is absence of matching mapped evidence, not proof that a rock, mineral or unit is absent on the ground."));
        } else {
            root.addView(action("Show all geology on map",
                    safe.size() + " mapped polygon" + (safe.size() == 1 ? "" : "s") + " · grouped below into "
                            + groups.size() + " geologic unit" + (groups.size() == 1 ? "" : "s"),
                    v -> returnGeology(geoJson, resultTitle, safe.size(), boundsOfUnits(safe))));

            root.addView(section("Geologic units"));
            root.addView(help("Repeated source polygons are grouped here. Every underlying polygon remains in the map result and GeoJSON export."));
            for (UnitGroup group : groups) {
                root.addView(action(group.name,
                        group.detailLine(),
                        v -> showUnitGroup(group, resultTitle)));
            }
        }

        if (queryBounds != null) {
            root.addView(section("Continue this area analysis"));
            root.addView(action("Mineral Evidence",
                    "Analyze the existing installed mineral/locality evidence in these geographic bounds.",
                    v -> returnBoundsAction(ACTION_MINERALS_AREA, queryBounds)));
            root.addView(action("Historic mines / workings",
                    "Show the existing historic mine/workings evidence for this area on the map.",
                    v -> returnBoundsAction(ACTION_HISTORIC_MINES, queryBounds)));
        }
        root.addView(nav("Back to Research", v -> showHub()));
        setContentView(scroll(root));
    }

    private void showUnitGroup(UnitGroup group, String resultTitle) {
        LinearLayout root = page();
        root.addView(title(group.name));
        root.addView(help(group.detailLine()));
        root.addView(action("Show mapped areas on map",
                group.units.size() + " source polygon" + (group.units.size() == 1 ? "" : "s") + " for this unit.",
                v -> returnGeology(geology.toGeoJson(group.units), group.name, group.units.size(), boundsOfUnits(group.units))));
        root.addView(action("Technical & source details",
                "View raw SGMC labels, full age hierarchy, references and source identifiers.",
                v -> showTechnicalDetails(group.representative())));
        if (group.units.size() > 1) {
            root.addView(help(group.units.size() + " separate mapped source areas are represented by this grouped unit. Individual polygons stay available on the map and in GeoJSON export; they are not repeated here."));
        }
        root.addView(nav("Back to results", v -> showResults(currentResults, currentResultTitle, currentResultBounds)));
        setContentView(scroll(root));
    }

    private String compactSummary(List<GeologyUnit> polygons, List<UnitGroup> groups) {
        if (polygons == null || polygons.isEmpty()) return "0 mapped geology polygons in this result.";
        LinkedHashMap<String, Integer> lith = new LinkedHashMap<>();
        for (UnitGroup group : groups) addCount(lith, group.lithology);
        StringBuilder out = new StringBuilder();
        out.append(groups.size()).append(" geologic unit").append(groups.size() == 1 ? "" : "s")
                .append(" · ").append(polygons.size()).append(" mapped polygon").append(polygons.size() == 1 ? "" : "s").append('.');
        String rocks = topNames(lith, 3);
        if (!rocks.isEmpty()) out.append("\nCommon rock types in the grouped results: ").append(rocks).append('.');
        out.append("\nPolygon counts are not percentages of ground area.");
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

    private static String compactUnitLine(GeologyUnit unit) {
        String age = unit.compactAgeLabel();
        String lith = unit.compactLithologyLabel();
        if (age.isEmpty()) return lith;
        return lith + " · " + age;
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
                .setTitle("Technical geology details")
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
                    .setMessage(s.count + " mapped geology polygon" + (s.count == 1 ? "" : "s")
                            + " saved as the current Research export. Field → Export data can save the complete underlying records as GeoJSON or CSV.")
                    .setPositiveButton("Show on map", (d, w) -> returnGeology(geoJson, s.title, s.count, null))
                    .setNeutralButton("Clear", (d, w) -> {
                        ResearchResultStore.clear(this);
                        showHub();
                    })
                    .setNegativeButton("Close", null)
                    .show();
        } catch (IOException ex) {
            toast("Stored research result could not be read.");
            showHub();
        }
    }

    private void confirmRefresh() {
        new AlertDialog.Builder(this)
                .setTitle("Geology updates")
                .setMessage("RockMap no longer starts an unknown-size live geology download. Geology updates must be published as a versioned pack whose download size is shown before confirmation.")
                .setPositiveButton("Data", (d, w) -> returnAction(ACTION_DATA, null))
                .setNegativeButton("Close", null)
                .show();
    }

    private void showRefreshProgress() {
        confirmRefresh();
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
            toast("Could not stage the Research result for the map: " + ex.getMessage());
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

    private static String topCounts(Map<String, Integer> map, int limit) {
        ArrayList<Map.Entry<String, Integer>> rows = new ArrayList<>(map.entrySet());
        rows.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.size() && i < limit; i++) {
            if (out.length() > 0) out.append(" · ");
            out.append(rows.get(i).getKey()).append(" (").append(rows.get(i).getValue()).append(')');
        }
        return out.toString();
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
        return mb < 10d ? String.format(Locale.US, "%.1f MB", mb) : String.format(Locale.US, "%.0f MB", mb);
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
