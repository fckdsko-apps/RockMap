package com.rockmap.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/** Adds opt-in production diagnostics controls to RockMap's Data settings. */
public final class DiagnosticsPanel {
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
                        + "Nothing is exported unless you tap Export diagnostics.");
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
            Uri destination = createExportDestination(activity);
            if (destination == null || !TourDebugLog.exportTo(activity, destination)) {
                deleteFailedDestination(activity, destination);
                Toast.makeText(activity, "Diagnostics could not be exported.", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(activity,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? "Diagnostics exported to Downloads/RockMap."
                            : "Diagnostics exported to RockMap's app documents folder.",
                    Toast.LENGTH_LONG).show();
            refresh.run();
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

    private static Uri createExportDestination(Activity activity) {
        String name = TourDebugLog.suggestedExportFileName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/RockMap");
                return activity.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        File base = activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (base == null) return null;
        File dir = new File(base, "RockMap");
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        return Uri.fromFile(new File(dir, name));
    }

    private static void deleteFailedDestination(Activity activity, Uri uri) {
        if (uri == null) return;
        try {
            if ("content".equalsIgnoreCase(uri.getScheme())) {
                ContentResolver resolver = activity.getContentResolver();
                resolver.delete(uri, null, null);
            } else if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                new File(uri.getPath()).delete();
            }
        } catch (RuntimeException ignored) {
            // Export failure must never interfere with RockMap.
        }
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
