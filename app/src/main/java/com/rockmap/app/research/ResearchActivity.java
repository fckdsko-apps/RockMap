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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
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

    public static final String RESULT_ACTION = "rockmap.research.result_action";
    public static final String RESULT_TITLE = "rockmap.research.title";
    public static final String RESULT_COUNT = "rockmap.research.count";
    public static final String ACTION_GEOLOGY = "geology";
    public static final String ACTION_MINERALS = "minerals";
    public static final String ACTION_MINERALS_AREA = "minerals_area";
    public static final String ACTION_HISTORIC_MINES = "historic_mines";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private GeologyRepository geology;
    private FieldDatabase fieldDb;
    private GeologyRepository.Bounds visibleBounds;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        geology = new GeologyRepository(this);
        fieldDb = FieldDatabase.get(this);
        visibleBounds = readBounds(getIntent());
        setTitle("RockMap Research");

        if (!geology.isReady()) {
            showInstall();
            return;
        }

        if (!resumeRequestedScope()) showHub();
    }

    private boolean resumeRequestedScope() {
        Intent intent = getIntent();
        long areaId = intent == null ? -1L : intent.getLongExtra(EXTRA_AREA_ID, -1L);
        if (areaId > 0L) {
            intent.removeExtra(EXTRA_AREA_ID);
            analyzeArea(areaId);
            return true;
        }
        if (intent != null && intent.hasExtra(EXTRA_POINT_LAT) && intent.hasExtra(EXTRA_POINT_LON)) {
            double lat = intent.getDoubleExtra(EXTRA_POINT_LAT, Double.NaN);
            double lon = intent.getDoubleExtra(EXTRA_POINT_LON, Double.NaN);
            double radius = intent.getDoubleExtra(EXTRA_RADIUS_M, 1000d);
            intent.removeExtra(EXTRA_POINT_LAT);
            intent.removeExtra(EXTRA_POINT_LON);
            intent.removeExtra(EXTRA_RADIUS_M);
            runPointRadius(lat, lon, radius);
            return true;
        }
        return false;
    }

    private void showInstall() {
        LinearLayout root = page();
        root.addView(title("Research"));
        root.addView(help("RockMap Research uses a local, queryable Colorado geology snapshot. The first install needs a connection; after it succeeds, geology search and spatial queries work offline. The source is a live feature service, so the exact transfer size is not known in advance; RockMap reports the local database size after validation and activation."));
        root.addView(section("Colorado geology not installed"));
        root.addView(help("Source: USGS State Geologic Map Compilation (SGMC), Colorado-only polygons. RockMap verifies the returned state, record count and required geometry before replacing any existing local snapshot. The source is identified as the SGMC service dataset and is not mislabeled as the separate 2026 GeMS release."));

        TextView status = help("No geology download is running.");
        root.addView(status);
        Button download = button("Download Colorado geology");
        root.addView(download);
        download.setOnClickListener(v -> {
            download.setEnabled(false);
            status.setText("Checking the USGS Colorado record count…");
            geology.downloadColoradoSnapshot(new GeologyRepository.ProgressCallback() {
                @Override public void onProgress(int downloaded, int expected) {
                    status.setText("Downloading and indexing Colorado geology: " + downloaded + " / " + expected + " units…");
                }

                @Override public void onComplete(int records, long bytes) {
                    status.setText(records + " Colorado geology units installed · " + formatBytes(bytes) + " local database.");
                    download.setText("Continue");
                    download.setEnabled(true);
                    download.setOnClickListener(x -> {
                        if (!resumeRequestedScope()) showHub();
                    });
                }

                @Override public void onError(String message) {
                    status.setText("Download rejected: " + message);
                    download.setEnabled(true);
                }
            });
        });
        root.addView(nav("Back", v -> finish()));
        setContentView(scroll(root));
    }

    private void showHub() {
        LinearLayout root = page();
        root.addView(title("Research"));
        root.addView(help(geology.getRecordCount() + " Colorado geology units ready offline · "
                + formatBytes(geology.getDatabaseBytes()) + ". Research results are leads for interpretation, not ownership, access, hazard, claim-validity, or collecting-permission determinations."));

        root.addView(action("Search geology",
                "Search unit names, labels, age and lithology. Add lithology/age filters and optionally limit the search to the current map view.",
                v -> showSearch()));
        if (visibleBounds != null) {
            root.addView(action("Analyze visible map",
                    "Summarize geologic units intersecting the map area you were viewing.",
                    v -> runBoundsQuery(visibleBounds, "Visible-map geology")));
        }
        root.addView(action("Analyze saved prospecting area",
                "Use the actual saved polygon, not only its rectangular map bounds.",
                v -> showAreaPicker()));
        root.addView(action("Query point / radius",
                "Find geologic units at or within a chosen radius of a coordinate.",
                v -> showPointRadius()));
        root.addView(action("Mineral evidence",
                "Open the existing RockMap mineral-evidence search without duplicating that dataset.",
                v -> returnAction(ACTION_MINERALS, null)));

        root.addView(section("Data"));
        root.addView(action("Refresh Colorado geology",
                "Replace the local snapshot only after the USGS source passes the same state/count/geometry checks.",
                v -> confirmRefresh()));
        if (ResearchResultStore.exists(this)) {
            ResearchResultStore.Summary s = ResearchResultStore.summary(this);
            root.addView(action("Last research result",
                    s.title + " · " + s.count + " mapped unit" + (s.count == 1 ? "" : "s")
                            + " · export from Field → Export data.",
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
            runAsync("Searching geology…", () -> geology.search(filter, bounds, 250),
                    results -> showResults(results,
                            "Geology search" + (filter.text.isEmpty() ? "" : ": " + text.getText().toString().trim()),
                            bounds, results.size() >= 250));
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
        runAsync("Analyzing map area…", () -> geology.queryBounds(bounds, 500),
                results -> showResults(results, title, bounds, results.size() >= 500));
    }

    private void showAreaPicker() {
        List<FieldDatabase.Area> areas = fieldDb.listAreas();
        if (areas.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No saved prospecting areas")
                    .setMessage("Create a polygon with Field → Measure, save it as a prospecting area, then Research can query the actual polygon.")
                    .setPositiveButton("OK", (d, w) -> showHub())
                    .show();
            return;
        }
        String[] labels = new String[areas.size()];
        for (int i = 0; i < areas.size(); i++) {
            FieldDatabase.Area a = areas.get(i);
            labels[i] = a.name + "\n" + a.points.size() + " vertices";
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose prospecting area")
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
        runAsync("Analyzing " + area.name + "…", () -> geology.queryPolygon(polygon, 500),
                results -> showResults(results, "Area: " + area.name, bounds, results.size() >= 500));
    }

    private void showPointRadius() {
        LinearLayout box = page();
        EditText coords = input("Latitude, longitude", "");
        EditText radius = input("Radius in meters (0 = unit at point)", "1000");
        box.addView(coords);
        box.addView(radius);
        new AlertDialog.Builder(this)
                .setTitle("Geology near a point")
                .setView(box)
                .setPositiveButton("Query", (d, w) -> {
                    try {
                        double[] ll = parseLatLon(coords.getText().toString());
                        double meters = Double.parseDouble(radius.getText().toString().trim());
                        runPointRadius(ll[0], ll[1], meters);
                    } catch (RuntimeException ex) {
                        toast(ex.getMessage() == null ? "Invalid point/radius." : ex.getMessage());
                        showPointRadius();
                    }
                })
                .setNegativeButton("Cancel", (d, w) -> showHub())
                .show();
    }

    private void runPointRadius(double lat, double lon, double radiusMeters) {
        try {
            GeologyRepository.Point point = new GeologyRepository.Point(lat, lon);
            double latDelta = Math.max(0.00001d, Math.max(0d, radiusMeters) / 111320d);
            double lonDelta = Math.max(0.00001d, Math.max(0d, radiusMeters)
                    / (111320d * Math.max(0.1d, Math.cos(Math.toRadians(lat)))));
            GeologyRepository.Bounds bounds = new GeologyRepository.Bounds(
                    lat - latDelta, lon - lonDelta, lat + latDelta, lon + lonDelta);
            runAsync("Querying geology near point…", () -> geology.queryPointRadius(point, radiusMeters, 500),
                    results -> showResults(results,
                            String.format(Locale.US, "Geology within %.0f m of %.5f, %.5f", radiusMeters, lat, lon),
                            bounds, results.size() >= 500));
        } catch (RuntimeException ex) {
            toast(ex.getMessage());
            showHub();
        }
    }

    private void showResults(List<GeologyUnit> results, String title,
                             GeologyRepository.Bounds queryBounds, boolean capped) {
        List<GeologyUnit> safe = results == null ? new ArrayList<>() : results;
        String geoJson = geology.toGeoJson(safe);
        try {
            ResearchResultStore.save(this, title, geoJson, safe.size());
        } catch (IOException ex) {
            toast("Results are usable, but the export copy could not be saved: " + ex.getMessage());
        }

        LinearLayout root = page();
        root.addView(title(title));
        root.addView(help(summaryText(safe, capped)));

        if (safe.isEmpty()) {
            root.addView(help("No installed geologic polygon matched this query. This does not establish that a material, unit, or mineral is absent from the ground."));
        } else {
            Button mapAll = button("Show result on map");
            mapAll.setOnClickListener(v -> returnGeology(geoJson, title, safe.size(), boundsOfUnits(safe)));
            root.addView(mapAll);

            String[] labels = new String[safe.size()];
            for (int i = 0; i < safe.size(); i++) {
                GeologyUnit unit = safe.get(i);
                labels[i] = unit.displayName() + "\n" + unit.lithologyLabel()
                        + (unit.ageLabel().isEmpty() ? "" : " · " + unit.ageLabel());
            }
            ListView list = new ListView(this);
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
            list.setOnItemClickListener((parent, view, position, id) -> showUnitDetail(safe.get(position), title));
            root.addView(list, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(420), Math.max(dp(120), safe.size() * dp(54)))));
        }

        if (queryBounds != null) {
            root.addView(section("Cross-reference existing RockMap evidence"));
            root.addView(action("Mineral evidence in these bounds",
                    "Use the existing installed mineral/locality evidence index for the same geographic bounds. For saved polygons this is explicitly the polygon's bounding rectangle, not a claim of exact polygon clipping.",
                    v -> returnBoundsAction(ACTION_MINERALS_AREA, queryBounds)));
            root.addView(action("Historic mines / workings in these bounds",
                    "Return to the map, zoom to these bounds, and turn on the existing historic-mine overlay.",
                    v -> returnBoundsAction(ACTION_HISTORIC_MINES, queryBounds)));
        }
        root.addView(nav("Back to Research", v -> showHub()));
        setContentView(scroll(root));
    }

    private String summaryText(List<GeologyUnit> units, boolean capped) {
        if (units == null || units.isEmpty()) return "0 geology units in this result.";
        LinkedHashMap<String, Integer> lith = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> ages = new LinkedHashMap<>();
        for (GeologyUnit unit : units) {
            addCount(lith, unit.lithologyLabel());
            String age = unit.ageLabel();
            if (!age.isEmpty()) addCount(ages, age);
        }
        StringBuilder out = new StringBuilder();
        out.append(units.size()).append(" mapped geologic unit").append(units.size() == 1 ? "" : "s");
        if (capped) out.append(" shown (result safety cap reached)");
        out.append(".\n\nMost common lithology labels: ").append(topCounts(lith, 6));
        if (!ages.isEmpty()) out.append(".\nCommon age labels: ").append(topCounts(ages, 5));
        out.append(".\n\nCounts describe mapped source polygons, not percentage of ground area. Polygon size varies, so a unit count is not an areal-abundance estimate.");
        return out.toString();
    }

    private void showUnitDetail(GeologyUnit u, String resultTitle) {
        StringBuilder text = new StringBuilder();
        text.append("SGMC label: ").append(blank(u.sgmcLabel, "not reported"));
        text.append("\nOriginal label: ").append(blank(u.originalLabel, "not reported"));
        text.append("\nAge: ").append(blank(u.ageLabel(), "not reported"));
        text.append("\nGeneralized lithology: ").append(blank(u.generalizedLithology, "not reported"));
        append(text, "Major lithology", joinNonblank(u.major1, u.major2, u.major3));
        append(text, "Minor lithology", joinNonblank(u.minor1, u.minor2, u.minor3, u.minor4, u.minor5));
        append(text, "Incidental", u.incidental);
        append(text, "Indeterminate", u.indeterminate);
        append(text, "Reference ID", u.referenceId);
        append(text, "Reference", u.reference);
        append(text, "Digital source", u.digitalUrl);
        append(text, "NGMDB", joinNonblank(u.ngmdb1, u.ngmdb2, u.ngmdb3));
        text.append("\n\nSource: ").append(GeologyRepository.SOURCE_TITLE)
                .append("\nDOI: ").append(GeologyRepository.SOURCE_DOI)
                .append("\n").append(GeologyRepository.SOURCE_NOTE)
                .append("\n\nMapped geology is interpretive source data. It does not determine land ownership, access, mining-claim status, hazards, or collecting permission.");

        new AlertDialog.Builder(this)
                .setTitle(u.displayName())
                .setMessage(text.toString())
                .setPositiveButton("Show on map", (d, w) -> {
                    try {
                        ArrayList<GeologyUnit> one = new ArrayList<>();
                        one.add(u);
                        returnGeology(geology.toGeoJson(one), u.displayName(), 1,
                                new GeologyRepository.Bounds(u.south, u.west, u.north, u.east));
                    } catch (RuntimeException ex) {
                        toast("Could not map that geology unit.");
                    }
                })
                .setNeutralButton("Results", (d, w) -> showStoredResult())
                .setNegativeButton("Close", null)
                .show();
    }

    private void showStoredResult() {
        try {
            ResearchResultStore.Summary s = ResearchResultStore.summary(this);
            String geoJson = ResearchResultStore.geoJson(this);
            new AlertDialog.Builder(this)
                    .setTitle(s.title)
                    .setMessage(s.count + " geology unit" + (s.count == 1 ? "" : "s")
                            + " saved as the current Research export. Field → Export data can save it as GeoJSON or CSV.")
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
                .setTitle("Refresh Colorado geology?")
                .setMessage("RockMap will download a new Colorado-only SGMC service snapshot. The current offline database stays active unless the replacement passes validation completely.")
                .setPositiveButton("Refresh", (d, w) -> showRefreshProgress())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRefreshProgress() {
        LinearLayout root = page();
        root.addView(title("Refreshing geology"));
        TextView status = help("Checking source…");
        root.addView(status);
        root.addView(help("Do not uninstall RockMap. The existing geology snapshot remains available until a complete replacement is activated."));
        setContentView(scroll(root));
        geology.downloadColoradoSnapshot(new GeologyRepository.ProgressCallback() {
            @Override public void onProgress(int downloaded, int expected) {
                status.setText("Downloading and indexing: " + downloaded + " / " + expected + " units…");
            }
            @Override public void onComplete(int records, long bytes) {
                status.setText("Refresh complete: " + records + " units · " + formatBytes(bytes) + ".");
                getWindow().getDecorView().postDelayed(ResearchActivity.this::showHub, 600);
            }
            @Override public void onError(String message) {
                status.setText("Refresh rejected: " + message + "\n\nThe previous offline geology database was preserved.");
                Button back = button("Back to Research");
                back.setOnClickListener(v -> showHub());
                root.addView(back);
            }
        });
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
