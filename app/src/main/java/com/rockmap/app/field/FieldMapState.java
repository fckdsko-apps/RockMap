package com.rockmap.app.field;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Small persistent bridge between the Field activity and the map-integrated field UI. */
public final class FieldMapState {
    private static final String PREFS = "rockmap-field-map-state";
    private static final String KEY_NAV_ACTIVE = "navigation-active";
    private static final String KEY_NAV_NAME = "navigation-name";
    private static final String KEY_NAV_LAT = "navigation-lat";
    private static final String KEY_NAV_LON = "navigation-lon";
    private static final String KEY_HIDDEN_TRACKS = "hidden-tracks";
    private static final String KEY_TRACKS_VISIBLE = "tracks-visible";
    private static final String KEY_AREAS_VISIBLE = "areas-visible";
    private static final String KEY_LABELS_VISIBLE = "labels-visible";
    private static final String KEY_FIELD_RECORDS_VISIBLE = "field-records-visible";
    private static final String KEY_FOCUS_TRACK = "focus-track";
    private static final String KEY_MEASURE_REQUEST = "measure-request";
    private static final String KEY_FOCUS_PENDING = "focus-pending";
    private static final String KEY_FOCUS_MIN_LAT = "focus-min-lat";
    private static final String KEY_FOCUS_MIN_LON = "focus-min-lon";
    private static final String KEY_FOCUS_MAX_LAT = "focus-max-lat";
    private static final String KEY_FOCUS_MAX_LON = "focus-max-lon";

    private FieldMapState() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void startNavigation(Context context, String name, GeoMath.Point target) {
        if (target == null) return;
        prefs(context).edit()
                .putBoolean(KEY_NAV_ACTIVE, true)
                .putString(KEY_NAV_NAME, name == null || name.trim().isEmpty() ? "Target" : name.trim())
                .putLong(KEY_NAV_LAT, Double.doubleToRawLongBits(target.lat))
                .putLong(KEY_NAV_LON, Double.doubleToRawLongBits(target.lon))
                .apply();
    }

    public static void stopNavigation(Context context) {
        prefs(context).edit().putBoolean(KEY_NAV_ACTIVE, false).apply();
    }

    public static NavigationTarget navigationTarget(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_NAV_ACTIVE, false)) return null;
        double lat = Double.longBitsToDouble(p.getLong(KEY_NAV_LAT, Double.doubleToRawLongBits(Double.NaN)));
        double lon = Double.longBitsToDouble(p.getLong(KEY_NAV_LON, Double.doubleToRawLongBits(Double.NaN)));
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90d || lat > 90d || lon < -180d || lon > 180d) {
            stopNavigation(context);
            return null;
        }
        return new NavigationTarget(p.getString(KEY_NAV_NAME, "Target"), new GeoMath.Point(lat, lon));
    }

    public static boolean tracksVisible(Context context) {
        return prefs(context).getBoolean(KEY_TRACKS_VISIBLE, true);
    }

    public static void setTracksVisible(Context context, boolean visible) {
        prefs(context).edit().putBoolean(KEY_TRACKS_VISIBLE, visible).apply();
    }

    public static boolean areasVisible(Context context) {
        return prefs(context).getBoolean(KEY_AREAS_VISIBLE, true);
    }

    public static void setAreasVisible(Context context, boolean visible) {
        prefs(context).edit().putBoolean(KEY_AREAS_VISIBLE, visible).apply();
    }

    public static boolean labelsVisible(Context context) {
        return prefs(context).getBoolean(KEY_LABELS_VISIBLE, true);
    }

    public static void setLabelsVisible(Context context, boolean visible) {
        prefs(context).edit().putBoolean(KEY_LABELS_VISIBLE, visible).apply();
    }

    public static boolean fieldRecordsVisible(Context context) {
        return prefs(context).getBoolean(KEY_FIELD_RECORDS_VISIBLE, true);
    }

    public static void setFieldRecordsVisible(Context context, boolean visible) {
        prefs(context).edit().putBoolean(KEY_FIELD_RECORDS_VISIBLE, visible).apply();
    }

    public static boolean isTrackHidden(Context context, long trackId) {
        return hiddenTracks(context).contains(Long.toString(trackId));
    }

    public static void showTrack(Context context, long trackId) {
        Set<String> ids = hiddenTracks(context);
        ids.remove(Long.toString(trackId));
        prefs(context).edit().putStringSet(KEY_HIDDEN_TRACKS, ids).apply();
    }

    public static void hideTrack(Context context, long trackId) {
        Set<String> ids = hiddenTracks(context);
        ids.add(Long.toString(trackId));
        prefs(context).edit().putStringSet(KEY_HIDDEN_TRACKS, ids).apply();
    }

    public static Set<String> hiddenTracks(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_HIDDEN_TRACKS, new HashSet<>()));
    }

    public static void requestTrackFocus(Context context, long trackId) {
        prefs(context).edit().putLong(KEY_FOCUS_TRACK, trackId).apply();
    }

    public static long consumeTrackFocus(Context context) {
        SharedPreferences p = prefs(context);
        long id = p.getLong(KEY_FOCUS_TRACK, -1L);
        if (id >= 0L) p.edit().remove(KEY_FOCUS_TRACK).apply();
        return id;
    }

    public static void requestMeasurement(Context context) {
        prefs(context).edit().putBoolean(KEY_MEASURE_REQUEST, true).apply();
    }

    public static boolean consumeMeasurementRequest(Context context) {
        SharedPreferences p = prefs(context);
        boolean requested = p.getBoolean(KEY_MEASURE_REQUEST, false);
        if (requested) p.edit().putBoolean(KEY_MEASURE_REQUEST, false).apply();
        return requested;
    }

    public static void requestFocusBounds(Context context, Bounds bounds) {
        if (bounds == null || !bounds.isValid()) return;
        prefs(context).edit()
                .putBoolean(KEY_FOCUS_PENDING, true)
                .putLong(KEY_FOCUS_MIN_LAT, Double.doubleToRawLongBits(bounds.minLat))
                .putLong(KEY_FOCUS_MIN_LON, Double.doubleToRawLongBits(bounds.minLon))
                .putLong(KEY_FOCUS_MAX_LAT, Double.doubleToRawLongBits(bounds.maxLat))
                .putLong(KEY_FOCUS_MAX_LON, Double.doubleToRawLongBits(bounds.maxLon))
                .apply();
    }

    public static Bounds consumeFocusBounds(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_FOCUS_PENDING, false)) return null;
        Bounds bounds = new Bounds(
                Double.longBitsToDouble(p.getLong(KEY_FOCUS_MIN_LAT, Double.doubleToRawLongBits(Double.NaN))),
                Double.longBitsToDouble(p.getLong(KEY_FOCUS_MIN_LON, Double.doubleToRawLongBits(Double.NaN))),
                Double.longBitsToDouble(p.getLong(KEY_FOCUS_MAX_LAT, Double.doubleToRawLongBits(Double.NaN))),
                Double.longBitsToDouble(p.getLong(KEY_FOCUS_MAX_LON, Double.doubleToRawLongBits(Double.NaN))));
        p.edit().putBoolean(KEY_FOCUS_PENDING, false).apply();
        return bounds.isValid() ? bounds : null;
    }

    public static final class NavigationTarget {
        public final String name;
        public final GeoMath.Point point;
        NavigationTarget(String name, GeoMath.Point point) {
            this.name = name;
            this.point = point;
        }
    }

    public static final class Bounds {
        public final double minLat;
        public final double minLon;
        public final double maxLat;
        public final double maxLon;

        public Bounds(double minLat, double minLon, double maxLat, double maxLon) {
            this.minLat = Math.min(minLat, maxLat);
            this.minLon = Math.min(minLon, maxLon);
            this.maxLat = Math.max(minLat, maxLat);
            this.maxLon = Math.max(minLon, maxLon);
        }

        public boolean isValid() {
            return Double.isFinite(minLat) && Double.isFinite(minLon)
                    && Double.isFinite(maxLat) && Double.isFinite(maxLon)
                    && minLat >= -90d && maxLat <= 90d && minLon >= -180d && maxLon <= 180d;
        }

        public static Bounds fromPoints(Iterable<GeoMath.Point> points) {
            double minLat = Double.POSITIVE_INFINITY;
            double minLon = Double.POSITIVE_INFINITY;
            double maxLat = Double.NEGATIVE_INFINITY;
            double maxLon = Double.NEGATIVE_INFINITY;
            boolean any = false;
            if (points != null) {
                for (GeoMath.Point point : points) {
                    if (point == null || !Double.isFinite(point.lat) || !Double.isFinite(point.lon)) continue;
                    any = true;
                    minLat = Math.min(minLat, point.lat);
                    minLon = Math.min(minLon, point.lon);
                    maxLat = Math.max(maxLat, point.lat);
                    maxLon = Math.max(maxLon, point.lon);
                }
            }
            return any ? new Bounds(minLat, minLon, maxLat, maxLon) : null;
        }
    }
}
