package com.rockmap.app.field;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/**
 * Explicit map-visibility state for saved Prospecting Areas.
 *
 * Saved areas are not global always-on layers. Only IDs the user explicitly chose to show are
 * rendered. Closing one removes that ID from the shown set and it stays closed across camera and
 * Research operations until the user explicitly chooses Show on Map again.
 */
public final class ProspectingAreaVisibility {
    private static final String PREFS = "rockmap-prospecting-area-visibility";
    private static final String KEY_SHOWN = "shown-area-ids-v2";

    private ProspectingAreaVisibility() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Set<Long> shownIds(Context context) {
        HashSet<Long> out = new HashSet<>();
        if (context == null) return out;
        Set<String> stored = shownStrings(context);
        for (String value : stored) {
            try {
                long id = Long.parseLong(value);
                if (id > 0L) out.add(id);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static boolean isShown(Context context, long areaId) {
        return areaId > 0L && shownIds(context).contains(areaId);
    }

    public static boolean isHidden(Context context, long areaId) {
        return areaId > 0L && !isShown(context, areaId);
    }

    public static void hide(Context context, long areaId) {
        if (context == null || areaId <= 0L) return;
        Set<String> ids = shownStrings(context);
        if (ids.remove(Long.toString(areaId))) {
            prefs(context).edit().putStringSet(KEY_SHOWN, ids).apply();
        }
    }

    public static void show(Context context, long areaId) {
        if (context == null || areaId <= 0L) return;
        Set<String> ids = shownStrings(context);
        ids.add(Long.toString(areaId));
        prefs(context).edit().putStringSet(KEY_SHOWN, ids).apply();
    }

    /** Explicitly show one saved Prospecting Area and hide other saved areas. */
    public static void showOnly(Context context, long areaId) {
        if (context == null || areaId <= 0L) return;
        HashSet<String> ids = new HashSet<>();
        ids.add(Long.toString(areaId));
        prefs(context).edit().putStringSet(KEY_SHOWN, ids).apply();
    }

    public static void forget(Context context, long areaId) {
        hide(context, areaId);
    }
    /**
     * On first use of the new explicit-visibility model, preserve tracked imported polygon areas.
     * User-created Prospecting Areas intentionally start hidden until Show on Map is chosen.
     */
    private static Set<String> shownStrings(Context context) {
        HashSet<String> out = new HashSet<>();
        if (context == null) return out;
        SharedPreferences preferences = prefs(context);
        Set<String> stored = preferences.getStringSet(KEY_SHOWN, null);
        if (stored != null) {
            out.addAll(stored);
            return out;
        }
        try {
            FieldDatabase db = FieldDatabase.get(context);
            for (FieldDatabase.ImportBatch batch : db.listImportBatches()) {
                for (Long id : db.getImportItemIds(batch.id, FieldDatabase.IMPORT_AREA)) {
                    if (id != null && id > 0L) out.add(Long.toString(id));
                }
            }
        } catch (RuntimeException ignored) {
            // Visibility migration must never prevent the map from opening.
        }
        preferences.edit().putStringSet(KEY_SHOWN, new HashSet<>(out)).apply();
        return out;
    }

}
