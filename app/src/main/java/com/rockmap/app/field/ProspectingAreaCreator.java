package com.rockmap.app.field;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.MainActivity;
import com.rockmap.app.map.MapContextCloseController;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared, plain-language Prospecting Area creation helpers used by Research and map details. */
public final class ProspectingAreaCreator {
    public interface SaveCallback {
        void onSaved(long areaId, String savedName);
    }

    private static final double EARTH_RADIUS_M = 6371008.8d;
    private static final int CIRCLE_SEGMENTS = 72;
    public static final String EXTRA_OPEN_RESEARCH_AREA_ID = "rockmap.openResearchAreaId";
    private static final String SAVED_PROMPT_TAG = "rockmap-prospecting-area-saved-prompt";

    private ProspectingAreaCreator() {}

    public static void chooseRadiusAndSave(Activity activity,
                                           double lat,
                                           double lon,
                                           String baseName,
                                           String sourceNote) {
        if (activity == null || !validCoordinate(lat, lon)) return;
        String[] labels = new String[]{"250 m", "500 m", "1 km", "2 km", "5 km", "Custom…"};
        double[] meters = new double[]{250d, 500d, 1000d, 2000d, 5000d};
        new AlertDialog.Builder(activity)
                .setTitle("Create Prospecting Area Around Here")
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < meters.length) {
                        saveCircle(activity, lat, lon, meters[which], baseName, sourceNote);
                    } else if (which == labels.length - 1) {
                        showCustomRadius(activity, lat, lon, baseName, sourceNote);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void showCustomRadius(Activity activity,
                                         double lat,
                                         double lon,
                                         String baseName,
                                         String sourceNote) {
        EditText input = new EditText(activity);
        input.setHint("Radius in meters (1–100000)");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Custom Radius")
                .setView(input)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    try {
                        double radius = Double.parseDouble(input.getText().toString().trim());
                        if (!Double.isFinite(radius) || radius < 1d || radius > 100000d) {
                            input.setError("Enter a radius from 1 to 100000 meters.");
                            return;
                        }
                        dialog.dismiss();
                        saveCircle(activity, lat, lon, radius, baseName, sourceNote);
                    } catch (NumberFormatException ex) {
                        input.setError("Enter a number in meters.");
                    }
                }));
        dialog.show();
    }

    private static void saveCircle(Activity activity,
                                   double lat,
                                   double lon,
                                   double radiusMeters,
                                   String baseName,
                                   String sourceNote) {
        List<GeoMath.Point> points = circlePoints(lat, lon, radiusMeters);
        String name = clean(baseName, "Prospecting Area") + " — " + radiusLabel(radiusMeters);
        String note = clean(sourceNote, "Created from a RockMap map point")
                + "\nCenter: " + String.format(Locale.US, "%.6f, %.6f", lat, lon)
                + "\nRadius: " + radiusLabel(radiusMeters);
        // A newly created point/radius area should exist visibly as soon as it is saved.
        // Researching the area is a separate optional next step, not the trigger that makes it appear.
        savePolygon(activity, name, note, points, false);
    }

    public static void savePolygon(Activity activity,
                                   String defaultName,
                                   String notes,
                                   List<GeoMath.Point> points,
                                   boolean keepHiddenUntilShown) {
        savePolygon(activity, defaultName, notes, points, keepHiddenUntilShown, null);
    }

    public static void savePolygon(Activity activity,
                                   String defaultName,
                                   String notes,
                                   List<GeoMath.Point> points,
                                   boolean keepHiddenUntilShown,
                                   SaveCallback callback) {
        if (activity == null) return;
        List<GeoMath.Point> normalized = normalizePolygon(points);
        if (normalized.size() < 3) {
            Toast.makeText(activity, "This shape cannot be saved as a Prospecting Area.", Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(clean(defaultName, "Prospecting Area"));
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 8));
        String areaSize = GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(normalized));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Save as Prospecting Area")
                .setMessage("You are saving this exact area (" + areaSize + ").\n\n"
                        + "Name it below. After saving, RockMap will confirm the save and offer to research this same area.")
                .setView(input)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        input.setError("Enter a name.");
                        return;
                    }
                    try {
                        saveNamedPolygonAndPrompt(activity, name, notes, normalized, keepHiddenUntilShown, callback);
                        dialog.dismiss();
                    } catch (RuntimeException ex) {
                        input.setError(ex.getMessage() == null ? "Could not save this area." : ex.getMessage());
                    }
                }));
        dialog.show();
        input.requestFocus();
    }

    /**
     * Save an already named polygon and show the same post-save Research Area shortcut used by
     * every other interactive Prospecting Area creation path.
     */
    public static long saveNamedPolygonAndPrompt(Activity activity,
                                                  String name,
                                                  String notes,
                                                  List<GeoMath.Point> points,
                                                  boolean keepHiddenUntilShown) {
        return saveNamedPolygonAndPrompt(activity, name, notes, points, keepHiddenUntilShown, null);
    }

    public static long saveNamedPolygonAndPrompt(Activity activity,
                                                  String name,
                                                  String notes,
                                                  List<GeoMath.Point> points,
                                                  boolean keepHiddenUntilShown,
                                                  SaveCallback callback) {
        if (activity == null) throw new IllegalArgumentException("Activity is required.");
        List<GeoMath.Point> normalized = normalizePolygon(points);
        if (normalized.size() < 3) throw new IllegalArgumentException("This shape cannot be saved as a Prospecting Area.");
        String savedName = clean(name, "Prospecting Area");
        long id = FieldDatabase.get(activity).insertArea(savedName, notes == null ? "" : notes, normalized);
        if (keepHiddenUntilShown) {
            ProspectingAreaVisibility.hide(activity, id);
        } else {
            // A map-measurement save replaces the temporary drawing with this one explicit saved area.
            ProspectingAreaVisibility.showOnly(activity, id);
            FieldMapState.setAreasVisible(activity, true);
        }
        MapContextCloseController.refreshFor(activity);
        showSavedResearchPrompt(activity, id, savedName);
        if (callback != null) callback.onSaved(id, savedName);
        return id;
    }

    /**
     * Lightweight, non-blocking save confirmation with the immediate next task kept one tap away.
     * Dismissing it is equivalent to Not Now; the saved area remains available in Field.
     */
    private static void showSavedResearchPrompt(Activity activity, long areaId, String name) {
        if (activity == null || areaId <= 0L) return;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) {
            Toast.makeText(activity, "Prospecting Area saved.", Toast.LENGTH_SHORT).show();
            return;
        }
        FrameLayout root = (FrameLayout) content;
        View previous = root.findViewWithTag(SAVED_PROMPT_TAG);
        if (previous != null) root.removeView(previous);

        LinearLayout bar = new LinearLayout(activity);
        bar.setTag(SAVED_PROMPT_TAG);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 6), dp(activity, 6));
        bar.setElevation(dp(activity, 10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(48, 48, 48));
        background.setCornerRadius(dp(activity, 8));
        bar.setBackground(background);

        TextView message = new TextView(activity);
        message.setText("Saved Prospecting Area" + (name == null || name.trim().isEmpty() ? "" : ": " + name.trim()));
        message.setTextColor(Color.WHITE);
        message.setTextSize(12f);
        message.setMaxLines(2);
        bar.addView(message, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button research = promptButton(activity, "Research Area");
        research.setOnClickListener(v -> {
            root.removeView(bar);
            Intent map = new Intent(activity, MainActivity.class);
            map.putExtra(EXTRA_OPEN_RESEARCH_AREA_ID, areaId);
            map.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(map);
        });
        bar.addView(research, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48)));

        Button notNow = promptButton(activity, "Not Now");
        notNow.setOnClickListener(v -> root.removeView(bar));
        bar.addView(notNow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int bottom = activity instanceof MainActivity ? dp(activity, 126) : dp(activity, 18);
        params.setMargins(dp(activity, 10), 0, dp(activity, 10), bottom);
        root.addView(bar, params);
        bar.bringToFront();
        bar.post(() -> {
            bar.bringToFront();
            FieldMapController.ensurePersistentEntry(activity);
        });
    }

    private static Button promptButton(Activity activity, String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11f);
        button.setMinHeight(dp(activity, 48));
        button.setMinimumHeight(dp(activity, 48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
        return button;
    }

    public static List<GeoMath.Point> polygonFromGeometryJson(String geometryJson) {
        return polygonFromGeometryJson(geometryJson, Double.NaN, Double.NaN);
    }

    /**
     * Returns one outer polygon ring. For multipart geology, prefer the part containing the tap;
     * otherwise use the largest outer ring so a single saved Prospecting Area remains unambiguous.
     */
    public static List<GeoMath.Point> polygonFromGeometryJson(String geometryJson,
                                                              double preferredLat,
                                                              double preferredLon) {
        ArrayList<List<GeoMath.Point>> rings = new ArrayList<>();
        if (geometryJson == null || geometryJson.trim().isEmpty()) return new ArrayList<>();
        try {
            JSONObject geometry = new JSONObject(geometryJson);
            String type = geometry.optString("type", "");
            JSONArray coordinates = geometry.optJSONArray("coordinates");
            if (coordinates == null) return new ArrayList<>();
            if ("Polygon".equalsIgnoreCase(type)) {
                if (coordinates.length() > 0) addRing(rings, coordinates.optJSONArray(0));
            } else if ("MultiPolygon".equalsIgnoreCase(type)) {
                for (int i = 0; i < coordinates.length(); i++) {
                    JSONArray polygon = coordinates.optJSONArray(i);
                    if (polygon != null && polygon.length() > 0) addRing(rings, polygon.optJSONArray(0));
                }
            }
        } catch (JSONException ignored) {
            return new ArrayList<>();
        }
        if (rings.isEmpty()) return new ArrayList<>();
        if (validCoordinate(preferredLat, preferredLon)) {
            for (List<GeoMath.Point> ring : rings) {
                if (contains(ring, preferredLat, preferredLon)) return normalizePolygon(ring);
            }
        }
        List<GeoMath.Point> best = rings.get(0);
        double bestArea = approximateArea(best);
        for (int i = 1; i < rings.size(); i++) {
            double area = approximateArea(rings.get(i));
            if (area > bestArea) {
                best = rings.get(i);
                bestArea = area;
            }
        }
        return normalizePolygon(best);
    }

    private static void addRing(List<List<GeoMath.Point>> out, JSONArray ring) {
        if (ring == null) return;
        ArrayList<GeoMath.Point> points = new ArrayList<>();
        for (int i = 0; i < ring.length(); i++) {
            JSONArray pair = ring.optJSONArray(i);
            if (pair == null || pair.length() < 2) continue;
            double lon = pair.optDouble(0, Double.NaN);
            double lat = pair.optDouble(1, Double.NaN);
            if (validCoordinate(lat, lon)) points.add(new GeoMath.Point(lat, lon));
        }
        points = new ArrayList<>(normalizePolygon(points));
        if (points.size() >= 3) out.add(points);
    }

    public static List<GeoMath.Point> circlePoints(double lat, double lon, double radiusMeters) {
        ArrayList<GeoMath.Point> out = new ArrayList<>();
        if (!validCoordinate(lat, lon) || !Double.isFinite(radiusMeters) || radiusMeters <= 0d) return out;
        double angularDistance = radiusMeters / EARTH_RADIUS_M;
        double lat1 = Math.toRadians(lat);
        double lon1 = Math.toRadians(lon);
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double bearing = (Math.PI * 2d * i) / CIRCLE_SEGMENTS;
            double lat2 = Math.asin(Math.sin(lat1) * Math.cos(angularDistance)
                    + Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearing));
            double lon2 = lon1 + Math.atan2(
                    Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat1),
                    Math.cos(angularDistance) - Math.sin(lat1) * Math.sin(lat2));
            double outLon = Math.toDegrees(lon2);
            while (outLon > 180d) outLon -= 360d;
            while (outLon < -180d) outLon += 360d;
            out.add(new GeoMath.Point(Math.toDegrees(lat2), outLon));
        }
        return out;
    }

    private static List<GeoMath.Point> normalizePolygon(List<GeoMath.Point> points) {
        ArrayList<GeoMath.Point> out = new ArrayList<>();
        if (points == null) return out;
        for (GeoMath.Point point : points) {
            if (point == null || !validCoordinate(point.lat, point.lon)) continue;
            if (!out.isEmpty()) {
                GeoMath.Point previous = out.get(out.size() - 1);
                if (same(previous, point)) continue;
            }
            out.add(new GeoMath.Point(point.lat, point.lon));
        }
        if (out.size() >= 2 && same(out.get(0), out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static boolean contains(List<GeoMath.Point> polygon, double lat, double lon) {
        if (polygon == null || polygon.size() < 3) return false;
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            GeoMath.Point a = polygon.get(i);
            GeoMath.Point b = polygon.get(j);
            boolean crosses = ((a.lat > lat) != (b.lat > lat))
                    && (lon < (b.lon - a.lon) * (lat - a.lat) / ((b.lat - a.lat) == 0d ? 1e-12d : (b.lat - a.lat)) + a.lon);
            if (crosses) inside = !inside;
        }
        return inside;
    }

    private static double approximateArea(List<GeoMath.Point> points) {
        if (points == null || points.size() < 3) return 0d;
        double area = 0d;
        for (int i = 0; i < points.size(); i++) {
            GeoMath.Point a = points.get(i);
            GeoMath.Point b = points.get((i + 1) % points.size());
            area += a.lon * b.lat - b.lon * a.lat;
        }
        return Math.abs(area) * 0.5d;
    }

    private static boolean same(GeoMath.Point a, GeoMath.Point b) {
        return a != null && b != null
                && Math.abs(a.lat - b.lat) < 1e-10d
                && Math.abs(a.lon - b.lon) < 1e-10d;
    }

    private static boolean validCoordinate(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90d && lat <= 90d && lon >= -180d && lon <= 180d;
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    public static String radiusLabel(double meters) {
        if (meters >= 1000d) {
            double km = meters / 1000d;
            return km == Math.rint(km)
                    ? String.format(Locale.US, "%.0f km", km)
                    : String.format(Locale.US, "%.1f km", km);
        }
        return String.format(Locale.US, "%.0f m", meters);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
