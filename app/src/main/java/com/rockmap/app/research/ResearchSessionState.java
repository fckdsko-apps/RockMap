package com.rockmap.app.research;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small durable state for the current map/research workspace. This is session state, not user data.
 * It lets Android recreate RockMap without dumping the user back to the default map/research view.
 */
public final class ResearchSessionState {
    private static final String PREFS = "rockmap-research-session-v1";

    private static final String K_ACTIVE = "active";
    private static final String K_SOUTH = "south";
    private static final String K_WEST = "west";
    private static final String K_NORTH = "north";
    private static final String K_EAST = "east";
    private static final String K_LABEL = "label";
    private static final String K_VIEW = "view";
    private static final String K_PANEL = "panel";
    private static final String K_AREA_ID = "area_id";
    private static final String K_GEOLOGY_VISIBLE = "geology_visible";
    private static final String K_MINERAL_VISIBLE = "mineral_visible";
    private static final String K_MINES_VISIBLE = "mines_visible";
    private static final String K_MINERAL_KEY = "mineral_key";
    private static final String K_MINERAL_LABEL = "mineral_label";
    private static final String K_GEOLOGY_TITLE = "geology_title";
    private static final String K_GEOLOGY_COUNT = "geology_count";

    private static final String K_CAMERA = "camera";
    private static final String K_CAMERA_LAT = "camera_lat";
    private static final String K_CAMERA_LON = "camera_lon";
    private static final String K_CAMERA_ZOOM = "camera_zoom";
    private static final String K_CAMERA_BEARING = "camera_bearing";
    private static final String K_CAMERA_TILT = "camera_tilt";

    private ResearchSessionState() {}

    public static final class Snapshot {
        public boolean active;
        public double south;
        public double west;
        public double north;
        public double east;
        public String areaLabel = "Selected Area";
        public String activeView = ResearchAreaPanelController.VIEW_GEOLOGY;
        public String panelMode = ResearchAreaPanelController.MODE_EXPANDED;
        public long areaId = -1L;
        public boolean geologyVisible;
        public boolean mineralVisible;
        public boolean minesVisible;
        public String mineralKey = "";
        public String mineralLabel = "";
        public String geologyTitle = "";
        public int geologyCount;

        public boolean validBounds() {
            return Double.isFinite(south) && Double.isFinite(west)
                    && Double.isFinite(north) && Double.isFinite(east)
                    && south <= north && west <= east
                    && south >= -90d && north <= 90d && west >= -180d && east <= 180d;
        }
    }

    public static final class CameraSnapshot {
        public final double lat;
        public final double lon;
        public final double zoom;
        public final double bearing;
        public final double tilt;

        CameraSnapshot(double lat, double lon, double zoom, double bearing, double tilt) {
            this.lat = lat;
            this.lon = lon;
            this.zoom = zoom;
            this.bearing = bearing;
            this.tilt = tilt;
        }
    }

    public static void save(Context context, Snapshot snapshot) {
        if (context == null || snapshot == null || !snapshot.validBounds()) return;
        prefs(context).edit()
                .putBoolean(K_ACTIVE, snapshot.active)
                .putLong(K_SOUTH, Double.doubleToLongBits(snapshot.south))
                .putLong(K_WEST, Double.doubleToLongBits(snapshot.west))
                .putLong(K_NORTH, Double.doubleToLongBits(snapshot.north))
                .putLong(K_EAST, Double.doubleToLongBits(snapshot.east))
                .putString(K_LABEL, clean(snapshot.areaLabel, "Selected Area"))
                .putString(K_VIEW, clean(snapshot.activeView, ResearchAreaPanelController.VIEW_GEOLOGY))
                .putString(K_PANEL, clean(snapshot.panelMode, ResearchAreaPanelController.MODE_EXPANDED))
                .putLong(K_AREA_ID, snapshot.areaId)
                .putBoolean(K_GEOLOGY_VISIBLE, snapshot.geologyVisible)
                .putBoolean(K_MINERAL_VISIBLE, snapshot.mineralVisible)
                .putBoolean(K_MINES_VISIBLE, snapshot.minesVisible)
                .putString(K_MINERAL_KEY, clean(snapshot.mineralKey, ""))
                .putString(K_MINERAL_LABEL, clean(snapshot.mineralLabel, ""))
                .putString(K_GEOLOGY_TITLE, clean(snapshot.geologyTitle, ""))
                .putInt(K_GEOLOGY_COUNT, Math.max(0, snapshot.geologyCount))
                .apply();
    }

    public static Snapshot load(Context context) {
        if (context == null) return null;
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(K_ACTIVE, false)) return null;
        Snapshot s = new Snapshot();
        s.active = true;
        s.south = Double.longBitsToDouble(p.getLong(K_SOUTH, Double.doubleToLongBits(Double.NaN)));
        s.west = Double.longBitsToDouble(p.getLong(K_WEST, Double.doubleToLongBits(Double.NaN)));
        s.north = Double.longBitsToDouble(p.getLong(K_NORTH, Double.doubleToLongBits(Double.NaN)));
        s.east = Double.longBitsToDouble(p.getLong(K_EAST, Double.doubleToLongBits(Double.NaN)));
        s.areaLabel = clean(p.getString(K_LABEL, "Selected Area"), "Selected Area");
        s.activeView = clean(p.getString(K_VIEW, ResearchAreaPanelController.VIEW_GEOLOGY),
                ResearchAreaPanelController.VIEW_GEOLOGY);
        s.panelMode = clean(p.getString(K_PANEL, ResearchAreaPanelController.MODE_EXPANDED),
                ResearchAreaPanelController.MODE_EXPANDED);
        s.areaId = p.getLong(K_AREA_ID, -1L);
        s.geologyVisible = p.getBoolean(K_GEOLOGY_VISIBLE, false);
        s.mineralVisible = p.getBoolean(K_MINERAL_VISIBLE, false);
        s.minesVisible = p.getBoolean(K_MINES_VISIBLE, false);
        s.mineralKey = clean(p.getString(K_MINERAL_KEY, ""), "");
        s.mineralLabel = clean(p.getString(K_MINERAL_LABEL, ""), "");
        s.geologyTitle = clean(p.getString(K_GEOLOGY_TITLE, ""), "");
        s.geologyCount = p.getInt(K_GEOLOGY_COUNT, 0);
        return s.validBounds() ? s : null;
    }

    public static void clear(Context context) {
        if (context == null) return;
        SharedPreferences p = prefs(context);
        // Keep camera restoration independent from Research session restoration.
        boolean camera = p.getBoolean(K_CAMERA, false);
        long lat = p.getLong(K_CAMERA_LAT, 0L);
        long lon = p.getLong(K_CAMERA_LON, 0L);
        long zoom = p.getLong(K_CAMERA_ZOOM, 0L);
        long bearing = p.getLong(K_CAMERA_BEARING, 0L);
        long tilt = p.getLong(K_CAMERA_TILT, 0L);
        SharedPreferences.Editor e = p.edit().clear();
        if (camera) {
            e.putBoolean(K_CAMERA, true)
                    .putLong(K_CAMERA_LAT, lat)
                    .putLong(K_CAMERA_LON, lon)
                    .putLong(K_CAMERA_ZOOM, zoom)
                    .putLong(K_CAMERA_BEARING, bearing)
                    .putLong(K_CAMERA_TILT, tilt);
        }
        e.apply();
    }

    public static void saveCamera(Context context, double lat, double lon, double zoom,
                                  double bearing, double tilt) {
        if (context == null || !Double.isFinite(lat) || !Double.isFinite(lon)
                || !Double.isFinite(zoom) || lat < -90d || lat > 90d || lon < -180d || lon > 180d) return;
        prefs(context).edit()
                .putBoolean(K_CAMERA, true)
                .putLong(K_CAMERA_LAT, Double.doubleToLongBits(lat))
                .putLong(K_CAMERA_LON, Double.doubleToLongBits(lon))
                .putLong(K_CAMERA_ZOOM, Double.doubleToLongBits(zoom))
                .putLong(K_CAMERA_BEARING, Double.doubleToLongBits(Double.isFinite(bearing) ? bearing : 0d))
                .putLong(K_CAMERA_TILT, Double.doubleToLongBits(Double.isFinite(tilt) ? tilt : 0d))
                .apply();
    }

    public static CameraSnapshot loadCamera(Context context) {
        if (context == null) return null;
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(K_CAMERA, false)) return null;
        double lat = Double.longBitsToDouble(p.getLong(K_CAMERA_LAT, Double.doubleToLongBits(Double.NaN)));
        double lon = Double.longBitsToDouble(p.getLong(K_CAMERA_LON, Double.doubleToLongBits(Double.NaN)));
        double zoom = Double.longBitsToDouble(p.getLong(K_CAMERA_ZOOM, Double.doubleToLongBits(Double.NaN)));
        double bearing = Double.longBitsToDouble(p.getLong(K_CAMERA_BEARING, Double.doubleToLongBits(0d)));
        double tilt = Double.longBitsToDouble(p.getLong(K_CAMERA_TILT, Double.doubleToLongBits(0d)));
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || !Double.isFinite(zoom)) return null;
        return new CameraSnapshot(lat, lon, zoom, bearing, tilt);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String clean(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
