package com.rockmap.app.field;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import com.rockmap.app.map.MapContextCloseController;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared, plain-language Prospecting Area creation helpers used by Research and map details. */
public final class ProspectingAreaCreator {
    private static final double EARTH_RADIUS_M = 6371008.8d;
    private static final int CIRCLE_SEGMENTS = 72;

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
        savePolygon(activity, name, note, points, true);
    }

    public static void savePolygon(Activity activity,
                                   String defaultName,
                                   String notes,
                                   List<GeoMath.Point> points,
                                   boolean keepHiddenUntilShown) {
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
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Save as Prospecting Area")
                .setMessage("Name this area. You can reopen, analyze, export, or delete it from Field → Prospecting Areas.")
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
                        long id = FieldDatabase.get(activity).insertArea(name, notes == null ? "" : notes, normalized);
                        if (keepHiddenUntilShown) {
                            ProspectingAreaVisibility.hide(activity, id);
                            MapContextCloseController.refreshFor(activity);
                        }
                        dialog.dismiss();
                        Toast.makeText(activity,
                                keepHiddenUntilShown
                                        ? "Saved to Prospecting Areas. Use Show on Map when you want to display it."
                                        : "Prospecting Area saved.",
                                Toast.LENGTH_LONG).show();
                    } catch (RuntimeException ex) {
                        input.setError(ex.getMessage() == null ? "Could not save this area." : ex.getMessage());
                    }
                }));
        dialog.show();
        input.requestFocus();
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
