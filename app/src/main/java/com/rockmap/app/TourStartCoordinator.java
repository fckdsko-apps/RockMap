package com.rockmap.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import com.rockmap.app.field.FieldDatabase;

/**
 * User-confirmed gate for all guided-tour starts.
 *
 * This class owns only confirmation and cross-screen pending intent.  Actual map cleanup belongs
 * to the resumed MainActivity so a paused Activity is never mutated from another screen.
 */
public final class TourStartCoordinator {
    private static final String PREFS = "rockmap-tour-start-coordinator";
    private static final String KEY_TOOL = "pending-field-tool";
    private static final String KEY_TRAINING = "pending-field-training";
    private static final String KEY_LEGACY = "pending-field-legacy";
    private static final String KEY_QUEUED_AT = "pending-field-queued-at";
    private static final long PENDING_MAX_AGE_MS = 10L * 60L * 1000L;

    private TourStartCoordinator() {}

    public static void confirm(Activity activity,
                               String tourLabel,
                               Runnable approved,
                               Runnable openTracks) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        SpannableStringBuilder message = new SpannableStringBuilder();
        message.append("This tour will close open tools and clear temporary work from the map so the tour can start with a clean screen.\n\n");
        int boldStart = message.length();
        message.append("Save any unsaved tracks, measurements, prospecting areas, or other work before continuing.");
        message.setSpan(new StyleSpan(Typeface.BOLD), boldStart, message.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        long transition = UiInvariantMonitor.begin(activity, "tour-start-confirmation",
                "tour=" + safe(tourLabel));
        UiInvariantMonitor.state(activity, transition, "TOUR_START_WARNING_SHOWN",
                "tour=" + safe(tourLabel));

        new AlertDialog.Builder(activity)
                .setTitle("Before starting this tour")
                .setMessage(message)
                .setPositiveButton("Start Tour", (d, w) -> {
                    UiInvariantMonitor.state(activity, transition, "TOUR_START_WARNING_ACCEPTED",
                            "tour=" + safe(tourLabel));
                    if (!ensureNoActiveTrack(activity, tourLabel, openTracks)) return;
                    if (approved != null) approved.run();
                })
                .setNegativeButton("Cancel", (d, w) ->
                        UiInvariantMonitor.state(activity, transition, "TOUR_START_CANCELLED",
                                "tour=" + safe(tourLabel)))
                .show();
    }

    public static boolean ensureNoActiveTrack(Activity activity,
                                              String tourLabel,
                                              Runnable openTracks) {
        if (activity == null) return false;
        FieldDatabase.Track track = FieldDatabase.get(activity).getActiveTrack();
        if (track == null) return true;
        long transition = UiInvariantMonitor.begin(activity, "tour-start-blocked",
                "tour=" + safe(tourLabel) + " activeTrackId=" + track.id
                        + " status=" + safe(track.status));
        UiInvariantMonitor.invariant(activity, transition,
                "active_track_must_not_be_silently_discarded", true,
                "blocked=true trackId=" + track.id + " status=" + safe(track.status));
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle("Finish your active Track first")
                .setMessage("RockMap will not stop or discard an active Track to start a tour. Open Tracks, then stop/save the recording before starting the tour.")
                .setNegativeButton("Cancel", null);
        if (openTracks != null) {
            dialog.setPositiveButton("Open Tracks", (d, w) -> openTracks.run());
        }
        dialog.show();
        return false;
    }

    public static void queueFieldTour(Context context, String tool,
                                      boolean trainingArea, boolean legacyLocal) {
        if (context == null || tool == null || tool.trim().isEmpty()) return;
        prefs(context).edit()
                .putString(KEY_TOOL, tool.trim())
                .putBoolean(KEY_TRAINING, trainingArea)
                .putBoolean(KEY_LEGACY, legacyLocal)
                .putLong(KEY_QUEUED_AT, System.currentTimeMillis())
                .commit();
        TourDebugLog.mapDiagnostic("TOUR_PENDING_FIELD_QUEUED",
                "tool=" + safe(tool) + " training=" + trainingArea + " legacy=" + legacyLocal);
    }

    public static PendingFieldTour consumePendingFieldTour(Context context) {
        if (context == null) return null;
        SharedPreferences p = prefs(context);
        String tool = p.getString(KEY_TOOL, "");
        if (tool == null || tool.trim().isEmpty()) return null;
        long queuedAt = p.getLong(KEY_QUEUED_AT, 0L);
        PendingFieldTour out = new PendingFieldTour(tool.trim(),
                p.getBoolean(KEY_TRAINING, false),
                p.getBoolean(KEY_LEGACY, false));
        p.edit().remove(KEY_TOOL).remove(KEY_TRAINING).remove(KEY_LEGACY)
                .remove(KEY_QUEUED_AT).commit();
        if (queuedAt <= 0L || System.currentTimeMillis() - queuedAt > PENDING_MAX_AGE_MS) {
            TourDebugLog.mapDiagnostic("TOUR_PENDING_FIELD_EXPIRED",
                    "tool=" + safe(out.tool) + " queuedAt=" + queuedAt);
            return null;
        }
        TourDebugLog.mapDiagnostic("TOUR_PENDING_FIELD_CONSUMED",
                "tool=" + safe(out.tool) + " training=" + out.trainingArea
                        + " legacy=" + out.legacyLocal);
        return out;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String safe(String value) {
        if (value == null) return "";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 160 ? clean : clean.substring(0, 160);
    }

    public static final class PendingFieldTour {
        public final String tool;
        public final boolean trainingArea;
        public final boolean legacyLocal;

        PendingFieldTour(String tool, boolean trainingArea, boolean legacyLocal) {
            this.tool = tool;
            this.trainingArea = trainingArea;
            this.legacyLocal = legacyLocal;
        }
    }
}
