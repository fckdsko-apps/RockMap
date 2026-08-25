package com.rockmap.app.field;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small persistent scratch state for guided-tour exercises that must temporarily borrow live map
 * state without changing the user's saved Field data.
 */
public final class FieldTourRuntimeState {
    private static final String PREFS = "rockmap_field_tour_runtime";
    private static final String KEY_NAV_PRACTICE = "nav_practice";
    private static final String KEY_NAV_HAD_PREVIOUS = "nav_had_previous";
    private static final String KEY_NAV_PREVIOUS_NAME = "nav_previous_name";
    private static final String KEY_NAV_PREVIOUS_LAT = "nav_previous_lat";
    private static final String KEY_NAV_PREVIOUS_LON = "nav_previous_lon";
    private static final String KEY_PREVIOUS_EXPANDED_TOOL = "previous_expanded_tool";

    private FieldTourRuntimeState() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void beginNavigationPractice(Context context,
                                               FieldMapState.NavigationTarget previous,
                                               String previousExpandedTool) {
        if (context == null) return;
        SharedPreferences.Editor edit = prefs(context).edit()
                .putBoolean(KEY_NAV_PRACTICE, true)
                .putBoolean(KEY_NAV_HAD_PREVIOUS, previous != null);
        if (previousExpandedTool != null && !previousExpandedTool.trim().isEmpty()) {
            edit.putString(KEY_PREVIOUS_EXPANDED_TOOL, previousExpandedTool);
        } else {
            edit.remove(KEY_PREVIOUS_EXPANDED_TOOL);
        }
        if (previous != null && previous.point != null) {
            edit.putString(KEY_NAV_PREVIOUS_NAME,
                            previous.name == null ? "Target" : previous.name)
                    .putLong(KEY_NAV_PREVIOUS_LAT,
                            Double.doubleToRawLongBits(previous.point.lat))
                    .putLong(KEY_NAV_PREVIOUS_LON,
                            Double.doubleToRawLongBits(previous.point.lon));
        } else {
            edit.remove(KEY_NAV_PREVIOUS_NAME)
                    .remove(KEY_NAV_PREVIOUS_LAT)
                    .remove(KEY_NAV_PREVIOUS_LON);
        }
        edit.apply();
    }

    public static boolean navigationPracticeActive(Context context) {
        return context != null && prefs(context).getBoolean(KEY_NAV_PRACTICE, false);
    }

    public static FieldMapState.NavigationTarget previousNavigation(Context context) {
        if (context == null) return null;
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_NAV_HAD_PREVIOUS, false)) return null;
        double lat = Double.longBitsToDouble(p.getLong(KEY_NAV_PREVIOUS_LAT,
                Double.doubleToRawLongBits(Double.NaN)));
        double lon = Double.longBitsToDouble(p.getLong(KEY_NAV_PREVIOUS_LON,
                Double.doubleToRawLongBits(Double.NaN)));
        if (!Double.isFinite(lat) || !Double.isFinite(lon)
                || lat < -90d || lat > 90d || lon < -180d || lon > 180d) return null;
        String name = p.getString(KEY_NAV_PREVIOUS_NAME, "Target");
        return new FieldMapState.NavigationTarget(name, new GeoMath.Point(lat, lon));
    }


    public static String previousExpandedTool(Context context) {
        if (context == null) return null;
        String value = prefs(context).getString(KEY_PREVIOUS_EXPANDED_TOOL, null);
        if (FieldMapState.TOOL_TRACK.equals(value)
                || FieldMapState.TOOL_NAVIGATE.equals(value)
                || FieldMapState.TOOL_MEASURE.equals(value)) return value;
        return null;
    }

    public static void clearNavigationPractice(Context context) {
        if (context == null) return;
        prefs(context).edit()
                .remove(KEY_NAV_PRACTICE)
                .remove(KEY_NAV_HAD_PREVIOUS)
                .remove(KEY_NAV_PREVIOUS_NAME)
                .remove(KEY_NAV_PREVIOUS_LAT)
                .remove(KEY_NAV_PREVIOUS_LON)
                .remove(KEY_PREVIOUS_EXPANDED_TOOL)
                .apply();
    }
}
