package com.rockmap.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent user-controlled state for RockMap guided tours and first-run data setup. */
public final class GuidedTourState {
    private static final String PREFS = "rockmap_guided_tour";
    private static final String KEY_STATE = "tour_state";
    private static final String KEY_STEP = "tour_step";
    private static final String KEY_START_STEP = "tour_start_step";
    private static final String KEY_END_STEP = "tour_end_step";
    private static final String KEY_TOPIC = "tour_topic";
    private static final String KEY_VERSION = "tour_version";
    private static final String KEY_AUTO_SUPPRESSED = "tour_auto_suppressed";
    private static final String KEY_DATA_SETUP_SEEN = "initial_offline_data_setup_seen";
    private static final String KEY_COACH_X = "coach_x_fraction";
    private static final String KEY_COACH_Y = "coach_y_fraction";
    private static final String KEY_COACH_POSITION = "coach_position_valid";

    public static final int TOUR_VERSION = 2;

    public static final String NOT_OFFERED = "not_offered";
    public static final String DEFERRED = "deferred";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";
    public static final String DISABLED = "disabled";

    public static final String TOPIC_FULL = "full";
    public static final String TOPIC_MAP_BASICS = "map_basics";
    public static final String TOPIC_FIND = "find";
    public static final String TOPIC_LAYERS = "layers";
    public static final String TOPIC_RESEARCH = "research";
    public static final String TOPIC_GEOLOGY = "geology";
    public static final String TOPIC_MINERAL_EVIDENCE = "mineral_evidence";
    public static final String TOPIC_HISTORIC_MINES = "historic_mines";
    public static final String TOPIC_RESEARCH_WORKSPACE = "research_workspace";
    public static final String TOPIC_OFFLINE_DATA = "offline_data";

    public static final int STEP_CENTER_GPS = 1;
    public static final int STEP_SAVE_GPS = 2;
    public static final int STEP_SAVED_LOCATIONS = 3;
    public static final int STEP_TRIPS = 4;
    public static final int STEP_OFFLINE_DATA = 5;
    public static final int STEP_LAYERS_BASICS = 6;
    public static final int STEP_FIND_MOUNT_ANTERO = 7;
    public static final int STEP_OPEN_RESEARCH = 8;
    public static final int STEP_COMBINED_ANALYSIS = 9;
    public static final int STEP_SHOW_GEOLOGY = 10;
    public static final int STEP_MINERAL_EVIDENCE = 11;
    public static final int STEP_CHOOSE_MINERAL = 12;
    public static final int STEP_LAYERS_REVEAL = 13;
    public static final int STEP_HISTORIC_MINES = 14;
    public static final int STEP_WORKSPACE_COLLAPSE = 15;
    public static final int STEP_WORKSPACE_REOPEN = 16;
    public static final int STEP_CONTEXT_CONTROLS = 17;
    public static final int STEP_COMPLETE = 18;

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

    private static boolean legacySuppressed(Context context) {
        String value = state(context);
        return DISABLED.equals(value) || COMPLETED.equals(value);
    }

    public static boolean automaticPromptsSuppressed(Context context) {
        SharedPreferences p = prefs(context);
        return p.contains(KEY_AUTO_SUPPRESSED)
                ? p.getBoolean(KEY_AUTO_SUPPRESSED, false)
                : legacySuppressed(context);
    }

    public static boolean canAutoOffer(Context context) {
        if (automaticPromptsSuppressed(context)) return false;
        String value = state(context);
        return NOT_OFFERED.equals(value) || DEFERRED.equals(value);
    }

    public static boolean isPermanentlySuppressed(Context context) {
        return automaticPromptsSuppressed(context);
    }

    public static int step(Context context) {
        return Math.max(STEP_CENTER_GPS, prefs(context).getInt(KEY_STEP, STEP_CENTER_GPS));
    }

    public static int startStep(Context context) {
        return Math.max(STEP_CENTER_GPS,
                prefs(context).getInt(KEY_START_STEP, STEP_CENTER_GPS));
    }

    public static int endStep(Context context) {
        return Math.max(STEP_CENTER_GPS,
                prefs(context).getInt(KEY_END_STEP, STEP_CONTEXT_CONTROLS));
    }

    public static int displayStep(Context context) {
        int step = step(context);
        int start = startStep(context);
        int end = endStep(context);
        if (step == STEP_COMPLETE) return Math.max(1, end - start + 2);
        return Math.max(1, step - start + 1);
    }

    public static int displayTotal(Context context) {
        return Math.max(2, endStep(context) - startStep(context) + 2);
    }

    public static String topic(Context context) {
        return prefs(context).getString(KEY_TOPIC, TOPIC_FULL);
    }

    public static void start(Context context) {
        startFull(context);
    }

    public static void startFull(Context context) {
        startTopic(context, TOPIC_FULL, STEP_CENTER_GPS, STEP_CONTEXT_CONTROLS);
    }

    public static void startTopic(Context context, String topic, int startStep, int endStep) {
        int start = Math.max(STEP_CENTER_GPS, Math.min(STEP_CONTEXT_CONTROLS, startStep));
        int end = Math.max(start, Math.min(STEP_CONTEXT_CONTROLS, endStep));
        prefs(context).edit()
                .putString(KEY_STATE, IN_PROGRESS)
                .putString(KEY_TOPIC, topic == null ? TOPIC_FULL : topic)
                .putInt(KEY_START_STEP, start)
                .putInt(KEY_STEP, start)
                .putInt(KEY_END_STEP, end)
                .putInt(KEY_VERSION, TOUR_VERSION)
                .apply();
    }

    public static void setStep(Context context, int step) {
        int next = Math.max(STEP_CENTER_GPS, step);
        if (next > endStep(context)) next = STEP_COMPLETE;
        prefs(context).edit()
                .putString(KEY_STATE, IN_PROGRESS)
                .putInt(KEY_STEP, next)
                .putInt(KEY_VERSION, TOUR_VERSION)
                .apply();
    }

    public static void advance(Context context, int requestedNextStep) {
        setStep(context, requestedNextStep);
    }

    /** Exit the active tour while preserving the user's permanent automatic-prompt preference. */
    public static void exit(Context context) {
        prefs(context).edit()
                .putString(KEY_STATE, automaticPromptsSuppressed(context) ? DISABLED : DEFERRED)
                .apply();
    }

    public static void defer(Context context) {
        prefs(context).edit().putString(KEY_STATE, DEFERRED).apply();
    }

    /** Never auto-show again. Manual Help & Tours launches remain available. */
    public static void disable(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_AUTO_SUPPRESSED, true)
                .putString(KEY_STATE, DISABLED)
                .putInt(KEY_STEP, STEP_CENTER_GPS)
                .apply();
    }

    public static void complete(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_AUTO_SUPPRESSED, true)
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

    public static void saveCoachPosition(Context context, float xFraction, float yFraction) {
        prefs(context).edit()
                .putBoolean(KEY_COACH_POSITION, true)
                .putFloat(KEY_COACH_X, clampFraction(xFraction))
                .putFloat(KEY_COACH_Y, clampFraction(yFraction))
                .apply();
    }

    public static boolean hasCoachPosition(Context context) {
        return prefs(context).getBoolean(KEY_COACH_POSITION, false);
    }

    public static float coachX(Context context) {
        return clampFraction(prefs(context).getFloat(KEY_COACH_X, 0.5f));
    }

    public static float coachY(Context context) {
        return clampFraction(prefs(context).getFloat(KEY_COACH_Y, 0.78f));
    }

    private static float clampFraction(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
