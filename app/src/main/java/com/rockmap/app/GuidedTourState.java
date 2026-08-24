package com.rockmap.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent user-controlled state for RockMap's optional guided tour and first-run data setup. */
public final class GuidedTourState {
    private static final String PREFS = "rockmap_guided_tour";
    private static final String KEY_STATE = "tour_state";
    private static final String KEY_STEP = "tour_step";
    private static final String KEY_VERSION = "tour_version";
    private static final String KEY_DATA_SETUP_SEEN = "initial_offline_data_setup_seen";

    public static final int TOUR_VERSION = 1;

    public static final String NOT_OFFERED = "not_offered";
    public static final String DEFERRED = "deferred";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String DISABLED = "disabled";

    public static final int STEP_MOUNT_ANTERO = 1;
    public static final int STEP_OPEN_RESEARCH = 2;
    public static final int STEP_COMBINED_ANALYSIS = 3;
    public static final int STEP_SHOW_GEOLOGY = 4;
    public static final int STEP_MINERAL_EVIDENCE = 5;
    public static final int STEP_CHOOSE_MINERAL = 6;
    public static final int STEP_HISTORIC_MINES = 7;
    public static final int STEP_LAYERS = 8;
    public static final int STEP_COMPLETE = 9;

    private GuidedTourState() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String state(Context context) {
        return prefs(context).getString(KEY_STATE, NOT_OFFERED);
    }

    public static boolean isActive(Context context) {
        return IN_PROGRESS.equals(state(context));
    }

    public static boolean canAutoOffer(Context context) {
        String state = state(context);
        return NOT_OFFERED.equals(state) || DEFERRED.equals(state);
    }

    public static boolean isPermanentlySuppressed(Context context) {
        String state = state(context);
        return DISABLED.equals(state) || COMPLETED.equals(state);
    }

    public static int step(Context context) {
        return Math.max(STEP_MOUNT_ANTERO,
                prefs(context).getInt(KEY_STEP, STEP_MOUNT_ANTERO));
    }

    public static void start(Context context) {
        prefs(context).edit()
                .putString(KEY_STATE, IN_PROGRESS)
                .putInt(KEY_STEP, STEP_MOUNT_ANTERO)
                .putInt(KEY_VERSION, TOUR_VERSION)
                .apply();
    }

    public static void setStep(Context context, int step) {
        prefs(context).edit()
                .putString(KEY_STATE, IN_PROGRESS)
                .putInt(KEY_STEP, Math.max(STEP_MOUNT_ANTERO, step))
                .putInt(KEY_VERSION, TOUR_VERSION)
                .apply();
    }

    public static void defer(Context context) {
        prefs(context).edit().putString(KEY_STATE, DEFERRED).apply();
    }

    /** Never auto-show again. Only an explicit manual start() changes this state. */
    public static void disable(Context context) {
        prefs(context).edit()
                .putString(KEY_STATE, DISABLED)
                .putInt(KEY_STEP, STEP_MOUNT_ANTERO)
                .apply();
    }

    public static void complete(Context context) {
        prefs(context).edit()
                .putString(KEY_STATE, COMPLETED)
                .putInt(KEY_STEP, STEP_COMPLETE)
                .putInt(KEY_VERSION, TOUR_VERSION)
                .apply();
    }

    public static boolean initialDataSetupSeen(Context context) {
        return prefs(context).getBoolean(KEY_DATA_SETUP_SEEN, false);
    }

    public static void markInitialDataSetupSeen(Context context) {
        prefs(context).edit().putBoolean(KEY_DATA_SETUP_SEEN, true).apply();
    }
}
