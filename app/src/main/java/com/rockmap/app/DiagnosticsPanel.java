package com.rockmap.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Adds opt-in production diagnostics controls to RockMap's Data settings. */
public final class DiagnosticsPanel {
    private static final int EXPORT_DIAGNOSTICS_REQUEST = 9151;

    private DiagnosticsPanel() {}

    public static void addTo(Activity activity, LinearLayout parent) {
        if (activity == null || parent == null) return;

        TextView heading = new TextView(activity);
        heading.setText("Diagnostics");
        heading.setTextSize(17f);
        heading.setTextColor(Color.rgb(30, 30, 30));
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(activity, 14), 0, dp(activity, 3));
        parent.addView(heading);

        TextView explanation = body(activity,
                "Off by default. When enabled, RockMap keeps a bounded diagnostic log on this device. "
                        + "It can include current coordinates, feature state, errors, and app events needed to diagnose problems. "
                        + "Nothing is exported unless you tap Export diagnostics and choose where to save it.");
        parent.addView(explanation);

        TextView status = body(activity, "");
        parent.addView(status);

        Button toggle = button(activity, "");
        parent.addView(toggle, fullButtonParams(activity));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button export = button(activity, "Export diagnostics");
        Button clear = button(activity, "Clear diagnostics");
        row.addView(export, weightedButtonParams(activity, false));
        row.addView(clear, weightedButtonParams(activity, true));
        parent.addView(row);

        Runnable refresh = () -> {
            boolean enabled = TourDebugLog.isEnabled(activity);
            long bytes = TourDebugLog.diagnosticBytes(activity);
            toggle.setText(enabled ? "Diagnostics: on — tap to turn off" : "Diagnostics: off — tap to enable");
            status.setText((enabled ? "Logging is enabled." : "Logging is disabled.")
                    + (bytes > 0L ? " Stored log: " + formatBytes(bytes) + "." : " No diagnostic log is stored."));
            export.setEnabled(bytes > 0L);
            clear.setEnabled(bytes > 0L);
        };

        toggle.setOnClickListener(v -> {
            boolean next = !TourDebugLog.isEnabled(activity);
            TourDebugLog.setEnabled(activity, next);
            Toast.makeText(activity,
                    next ? "RockMap diagnostics enabled." : "RockMap diagnostics disabled.",
                    Toast.LENGTH_SHORT).show();
            refresh.run();
        });

        export.setOnClickListener(v -> {
            if (!TourDebugLog.hasDiagnostics(activity)) {
                Toast.makeText(activity, "There are no diagnostics to export.", Toast.LENGTH_SHORT).show();
                refresh.run();
                return;
            }
            beginExport(activity);
        });

        clear.setOnClickListener(v -> new AlertDialog.Builder(activity)
                .setTitle("Clear diagnostics?")
                .setMessage("This permanently deletes RockMap's stored diagnostic log. Diagnostics can remain enabled and will start a new log afterward.")
                .setPositiveButton("Clear", (dialog, which) -> {
                    boolean cleared = TourDebugLog.clear(activity);
                    Toast.makeText(activity,
                            cleared ? "Diagnostics cleared." : "Diagnostics could not be cleared.",
                            cleared ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                    refresh.run();
                })
                .setNegativeButton("Cancel", null)
                .show());

        refresh.run();
    }

    /** Opens Android's document picker so RockMap never chooses the export folder for the user. */
    private static void beginExport(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, TourDebugLog.suggestedExportFileName());
        try {
            activity.startActivityForResult(intent, EXPORT_DIAGNOSTICS_REQUEST);
        } catch (RuntimeException ex) {
            Toast.makeText(activity,
                    "Android could not open a location picker for diagnostics.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Returns true when this result belongs to the diagnostics save picker. */
    public static boolean handleActivityResult(Activity activity, int requestCode,
                                               int resultCode, Intent data) {
        if (requestCode != EXPORT_DIAGNOSTICS_REQUEST) return false;
        if (activity == null) return true;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }

        boolean exported = TourDebugLog.exportTo(activity, data.getData());
        Toast.makeText(activity,
                exported ? "Diagnostics exported." : "Diagnostics could not be exported.",
                exported ? Toast.LENGTH_LONG : Toast.LENGTH_LONG).show();
        return true;
    }

    private static TextView body(Activity activity, String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(13.2f);
        view.setTextColor(Color.rgb(55, 55, 55));
        view.setTextIsSelectable(true);
        view.setPadding(0, 0, 0, dp(activity, 7));
        return view;
    }

    private static Button button(Activity activity, String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(11.5f);
        button.setMinHeight(dp(activity, 48));
        button.setMinimumHeight(dp(activity, 48));
        return button;
    }

    private static LinearLayout.LayoutParams weightedButtonParams(Activity activity, boolean right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (right) params.setMargins(dp(activity, 3), dp(activity, 2), 0, dp(activity, 2));
        else params.setMargins(0, dp(activity, 2), dp(activity, 3), dp(activity, 2));
        return params;
    }

    private static LinearLayout.LayoutParams fullButtonParams(Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(activity, 2), 0, dp(activity, 2));
        return params;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024.0) return String.format(Locale.US, "%.1f KB", value);
        value /= 1024.0;
        if (value < 1024.0) return String.format(Locale.US, "%.1f MB", value);
        return String.format(Locale.US, "%.2f GB", value / 1024.0);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
