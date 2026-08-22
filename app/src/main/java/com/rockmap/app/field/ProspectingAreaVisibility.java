package com.rockmap.app.field;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Explicit map-visibility state for saved Prospecting Areas.
 *
 * Closing a saved area on the map never deletes it. Hidden IDs remain hidden across unrelated
 * camera moves and Research operations until the user explicitly chooses Show on Map again.
 */
public final class ProspectingAreaVisibility {
    private static final String PREFS = "rockmap-prospecting-area-visibility";
    private static final String KEY_HIDDEN = "hidden-area-ids";

    private ProspectingAreaVisibility() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Set<Long> hiddenIds(Context context) {
        HashSet<Long> out = new HashSet<>();
        if (context == null) return out;
        Set<String> stored = prefs(context).getStringSet(KEY_HIDDEN, new HashSet<>());
        if (stored == null) return out;
        for (String value : stored) {
            try {
                long id = Long.parseLong(value);
                if (id > 0L) out.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static boolean isHidden(Context context, long areaId) {
        return areaId > 0L && hiddenIds(context).contains(areaId);
    }

    public static void hide(Context context, long areaId) {
        if (context == null || areaId <= 0L) return;
        Set<String> ids = new HashSet<>(prefs(context).getStringSet(KEY_HIDDEN, new HashSet<>()));
        ids.add(Long.toString(areaId));
        prefs(context).edit().putStringSet(KEY_HIDDEN, ids).apply();
    }

    public static void show(Context context, long areaId) {
        if (context == null || areaId <= 0L) return;
        Set<String> ids = new HashSet<>(prefs(context).getStringSet(KEY_HIDDEN, new HashSet<>()));
        if (ids.remove(Long.toString(areaId))) {
            prefs(context).edit().putStringSet(KEY_HIDDEN, ids).apply();
        }
    }

    public static void forget(Context context, long areaId) {
        show(context, areaId);
    }
}
