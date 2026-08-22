package com.rockmap.app.field;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persistent bridge between Field screens and the map-integrated field UI. */
public final class FieldMapState {
    public static final String TOOL_TRACK = "track";
    public static final String TOOL_NAVIGATE = "navigate";
    public static final String TOOL_MEASURE = "measure";

    public static final String CAMERA_BOUNDS = "bounds";
    public static final String CAMERA_TRACK = "track";

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
    private static final String KEY_TRACK_DETAIL = "track-detail";
    private static final String KEY_MEASURE_REQUEST = "measure-request";
    private static final String KEY_MEASURE_ACTIVE = "measure-active";
    private static final String KEY_MEASURE_POINTS = "measure-points";
    private static final String KEY_EXPANDED_TOOL = "expanded-tool";

    // A single persisted camera-request channel means the newest explicit Show/Open request wins.
    private static final String KEY_CAMERA_SERIAL = "camera-request-serial";
    private static final String KEY_CAMERA_KIND = "camera-request-kind";
    private static final String KEY_CAMERA_TRACK = "camera-request-track";
    private static final String KEY_FOCUS_TRACK = "focus-track"; // legacy compatibility
    private static final String KEY_FOCUS_PENDING = "focus-pending"; // legacy compatibility
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
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90d || lat > 90d || lon < -180d || lon > 180d) {
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
        if (selectedTrackDetail(context) == trackId) clearViewedMapContext(context);
    }

    public static Set<String> hiddenTracks(Context context) {
        return new HashSet<>(prefs(context).getStringSet(KEY_HIDDEN_TRACKS, new HashSet<>()));
    }

    /** Newest explicit cross-screen camera request replaces any older pending request. */
    public static synchronized void requestTrackFocus(Context context, long trackId) {
        SharedPreferences p = prefs(context);
        long serial = p.getLong(KEY_CAMERA_SERIAL, 0L) + 1L;
        p.edit()
                .putLong(KEY_CAMERA_SERIAL, serial)
                .putString(KEY_CAMERA_KIND, CAMERA_TRACK)
                .putLong(KEY_CAMERA_TRACK, trackId)
                .putLong(KEY_FOCUS_TRACK, trackId)
                .putBoolean(KEY_FOCUS_PENDING, false)
                .commit();
    }

    public static synchronized void requestFocusBounds(Context context, Bounds bounds) {
        if (bounds == null || !bounds.isValid()) return;
        SharedPreferences p = prefs(context);
        long serial = p.getLong(KEY_CAMERA_SERIAL, 0L) + 1L;
        p.edit()
                .putLong(KEY_CAMERA_SERIAL, serial)
                .putString(KEY_CAMERA_KIND, CAMERA_BOUNDS)
                .putLong(KEY_FOCUS_MIN_LAT, Double.doubleToRawLongBits(bounds.minLat))
                .putLong(KEY_FOCUS_MIN_LON, Double.doubleToRawLongBits(bounds.minLon))
                .putLong(KEY_FOCUS_MAX_LAT, Double.doubleToRawLongBits(bounds.maxLat))
                .putLong(KEY_FOCUS_MAX_LON, Double.doubleToRawLongBits(bounds.maxLon))
                .putBoolean(KEY_FOCUS_PENDING, true)
                .remove(KEY_FOCUS_TRACK)
                .commit();
    }

    public static synchronized CameraRequest consumeCameraRequest(Context context) {
        SharedPreferences p = prefs(context);
        String kind = p.getString(KEY_CAMERA_KIND, "");
        if (kind == null || kind.isEmpty()) return null;
        long serial = p.getLong(KEY_CAMERA_SERIAL, 0L);
        CameraRequest request = null;
        if (CAMERA_TRACK.equals(kind)) {
            long trackId = p.getLong(KEY_CAMERA_TRACK, p.getLong(KEY_FOCUS_TRACK, -1L));
            if (trackId >= 0L) request = CameraRequest.forTrack(serial, trackId);
        } else if (CAMERA_BOUNDS.equals(kind)) {
            Bounds bounds = readBounds(p);
            if (bounds != null && bounds.isValid()) request = CameraRequest.forBounds(serial, bounds);
        }
        p.edit()
                .remove(KEY_CAMERA_KIND)
                .remove(KEY_CAMERA_TRACK)
                .remove(KEY_FOCUS_TRACK)
                .putBoolean(KEY_FOCUS_PENDING, false)
                .commit();
        return request;
    }

    /** Legacy reader retained for older callers; new code should use consumeCameraRequest(). */
    public static long consumeTrackFocus(Context context) {
        SharedPreferences p = prefs(context);
        long id = p.getLong(KEY_FOCUS_TRACK, -1L);
        if (id >= 0L) p.edit().remove(KEY_FOCUS_TRACK).apply();
        return id;
    }

    /** Legacy reader retained for older callers; new code should use consumeCameraRequest(). */
    public static Bounds consumeFocusBounds(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_FOCUS_PENDING, false)) return null;
        Bounds bounds = readBounds(p);
        p.edit().putBoolean(KEY_FOCUS_PENDING, false).apply();
        return bounds != null && bounds.isValid() ? bounds : null;
    }

    private static Bounds readBounds(SharedPreferences p) {
        Bounds bounds = new Bounds(
                Double.longBitsToDouble(p.getLong(KEY_FOCUS_MIN_LAT, Double.doubleToRawLongBits(Double.NaN))),
                Double.longBitsToDouble(p.getLong(KEY_FOCUS_MIN_LON, Double.doubleToRawLongBits(Double.NaN))),
                Double.longBitsToDouble(p.getLong(KEY_FOCUS_MAX_LAT, Double.doubleToRawLongBits(Double.NaN))),
                Double.longBitsToDouble(p.getLong(KEY_FOCUS_MAX_LON, Double.doubleToRawLongBits(Double.NaN))));
        return bounds.isValid() ? bounds : null;
    }

    /** Keeps a completed track's inspected-object context visible until another object replaces it. */
    public static void selectTrackDetail(Context context, long trackId) {
        prefs(context).edit().putLong(KEY_TRACK_DETAIL, trackId).apply();
    }

    public static long selectedTrackDetail(Context context) {
        return prefs(context).getLong(KEY_TRACK_DETAIL, -1L);
    }

    public static void clearSelectedTrackDetail(Context context) {
        clearViewedMapContext(context);
    }

    public static void clearViewedMapContext(Context context) {
        prefs(context).edit().remove(KEY_TRACK_DETAIL).apply();
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

    /** Persist temporary measurement geometry so internal activity recreation cannot erase it. */
    public static void saveMeasurement(Context context, List<GeoMath.Point> points, boolean active) {
        StringBuilder encoded = new StringBuilder();
        if (points != null) {
            int count = 0;
            for (GeoMath.Point point : points) {
                if (point == null || !Double.isFinite(point.lat) || !Double.isFinite(point.lon)) continue;
                if (count++ > 0) encoded.append(';');
                encoded.append(Double.toString(point.lat)).append(',').append(Double.toString(point.lon));
                if (count >= 2000) break;
            }
        }
        prefs(context).edit()
                .putBoolean(KEY_MEASURE_ACTIVE, active)
                .putString(KEY_MEASURE_POINTS, encoded.toString())
                .apply();
    }

    public static boolean measurementActive(Context context) {
        return prefs(context).getBoolean(KEY_MEASURE_ACTIVE, false);
    }

    public static List<GeoMath.Point> measurementPoints(Context context) {
        ArrayList<GeoMath.Point> points = new ArrayList<>();
        String encoded = prefs(context).getString(KEY_MEASURE_POINTS, "");
        if (encoded == null || encoded.isEmpty()) return points;
        String[] pairs = encoded.split(";");
        for (String pair : pairs) {
            if (points.size() >= 2000) break;
            String[] values = pair.split(",", -1);
            if (values.length != 2) continue;
            try {
                double lat = Double.parseDouble(values[0]);
                double lon = Double.parseDouble(values[1]);
                if (Double.isFinite(lat) && Double.isFinite(lon)
                        && lat >= -90d && lat <= 90d && lon >= -180d && lon <= 180d) {
                    points.add(new GeoMath.Point(lat, lon));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return points;
    }

    public static void clearMeasurement(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_MEASURE_ACTIVE, false)
                .remove(KEY_MEASURE_POINTS)
                .apply();
    }

    /** Only one contextual map panel may be expanded at a time. Null means all active tools are tabs. */
    public static void setExpandedTool(Context context, String tool) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (TOOL_TRACK.equals(tool) || TOOL_NAVIGATE.equals(tool) || TOOL_MEASURE.equals(tool)) {
            editor.putString(KEY_EXPANDED_TOOL, tool);
        } else {
            editor.remove(KEY_EXPANDED_TOOL);
        }
        editor.apply();
    }

    public static String expandedTool(Context context) {
        String value = prefs(context).getString(KEY_EXPANDED_TOOL, "");
        if (TOOL_TRACK.equals(value) || TOOL_NAVIGATE.equals(value) || TOOL_MEASURE.equals(value)) return value;
        return null;
    }

    public static final class CameraRequest {
        public final long serial;
        public final String kind;
        public final long trackId;
        public final Bounds bounds;

        private CameraRequest(long serial, String kind, long trackId, Bounds bounds) {
            this.serial = serial;
            this.kind = kind;
            this.trackId = trackId;
            this.bounds = bounds;
        }

        static CameraRequest forTrack(long serial, long trackId) {
            return new CameraRequest(serial, CAMERA_TRACK, trackId, null);
        }

        static CameraRequest forBounds(long serial, Bounds bounds) {
            return new CameraRequest(serial, CAMERA_BOUNDS, -1L, bounds);
        }
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
