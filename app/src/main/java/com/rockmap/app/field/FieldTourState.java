package com.rockmap.app.field;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent cross-screen state for the interactive Field Tools guided tours. */
public final class FieldTourState {
    private static final String PREFS = "rockmap_field_tool_tour";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_TOOL = "tool";
    private static final String KEY_STEP = "step";
    private static final String KEY_ENTITY_ID = "entity_id";
    private static final String KEY_AUX_ID = "aux_id";
    private static final String KEY_TEXT = "text";

    private FieldTourState() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void start(Context context, String tool) {
        if (context == null || tool == null) return;
        prefs(context).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putString(KEY_TOOL, tool)
                .putInt(KEY_STEP, 1)
                .remove(KEY_ENTITY_ID)
                .remove(KEY_AUX_ID)
                .remove(KEY_TEXT)
                .apply();
    }

    public static boolean active(Context context) {
        return context != null && prefs(context).getBoolean(KEY_ACTIVE, false);
    }

    public static boolean is(Context context, String tool) {
        return active(context) && tool != null && tool.equals(tool(context));
    }

    public static boolean is(Context context, String tool, int step) {
        return is(context, tool) && step(context) == step;
    }

    public static String tool(Context context) {
        return context == null ? "" : prefs(context).getString(KEY_TOOL, "");
    }

    public static int step(Context context) {
        return context == null ? 0 : prefs(context).getInt(KEY_STEP, 0);
    }

    public static void step(Context context, int step) {
        if (context == null) return;
        prefs(context).edit().putInt(KEY_STEP, Math.max(1, step)).apply();
    }

    public static void entityId(Context context, long id) {
        if (context == null) return;
        prefs(context).edit().putLong(KEY_ENTITY_ID, id).apply();
    }

    public static long entityId(Context context) {
        return context == null ? -1L : prefs(context).getLong(KEY_ENTITY_ID, -1L);
    }

    public static void auxId(Context context, long id) {
        if (context == null) return;
        prefs(context).edit().putLong(KEY_AUX_ID, id).apply();
    }

    public static long auxId(Context context) {
        return context == null ? -1L : prefs(context).getLong(KEY_AUX_ID, -1L);
    }

    public static void text(Context context, String text) {
        if (context == null) return;
        prefs(context).edit().putString(KEY_TEXT, text == null ? "" : text).apply();
    }

    public static String text(Context context) {
        return context == null ? "" : prefs(context).getString(KEY_TEXT, "");
    }

    public static void finish(Context context) {
        clear(context);
    }

    public static void clear(Context context) {
        if (context == null) return;
        prefs(context).edit().clear().apply();
    }
}
