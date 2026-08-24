package com.rockmap.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small, touch-isolated tour card. It never installs a full-screen touch interceptor or scrim;
 * the real map and controls remain interactive outside this card.
 */
public final class GuidedTourCoach {
    private static final String TAG = "rockmap-guided-tour-coach";
    private GuidedTourCoach() {}

    public static void clear(Activity activity) {
        if (activity == null) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        View old = root.findViewWithTag(TAG);
        if (old != null) root.removeView(old);
    }

    public static void show(Activity activity, int step, int total, String title, String message,
                            String primaryLabel, Runnable primaryAction, boolean placeAtTop,
                            Runnable onStateChanged) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        clear(activity);
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) content;

        LinearLayout card = new LinearLayout(activity);
        card.setTag(TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10));
        card.setElevation(dp(activity, 10));
        card.setClickable(true);
        card.setFocusable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(248, 255, 255, 255));
        background.setStroke(dp(activity, 1), Color.rgb(171, 193, 195));
        background.setCornerRadius(dp(activity, 12));
        card.setBackground(background);

        TextView progress = new TextView(activity);
        progress.setText("GUIDED TOUR · " + Math.max(1, step) + " OF " + Math.max(step, total));
        progress.setTextSize(10.5f);
        progress.setTextColor(Color.rgb(0, 112, 121));
        progress.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(progress);

        TextView heading = new TextView(activity);
        heading.setText(title == null ? "" : title);
        heading.setTextSize(16f);
        heading.setTextColor(Color.rgb(30, 36, 38));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(activity, 2), 0, dp(activity, 2));
        card.addView(heading);

        TextView body = new TextView(activity);
        body.setText(message == null ? "" : message);
        body.setTextSize(12.5f);
        body.setTextColor(Color.rgb(66, 75, 77));
        body.setLineSpacing(0f, 1.08f);
        card.addView(body);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(activity, 6), 0, 0);

        if (primaryLabel != null && !primaryLabel.trim().isEmpty()) {
            Button primary = button(activity, primaryLabel.trim());
            primary.setOnClickListener(v -> {
                if (primaryAction != null) primaryAction.run();
            });
            actions.addView(primary);
        }

        Button end = button(activity, "Tour options");
        end.setOnClickListener(v -> new AlertDialog.Builder(activity)
                .setTitle("Guided tour")
                .setMessage("Pause the tour, or turn automatic tour prompts off permanently. You can always restart it manually from RockMap help.")
                .setPositiveButton("Keep going", null)
                .setNeutralButton("Pause tour", (d, w) -> {
                    GuidedTourState.defer(activity);
                    clear(activity);
                    if (onStateChanged != null) onStateChanged.run();
                })
                .setNegativeButton("Never show again", (d, w) -> {
                    GuidedTourState.disable(activity);
                    clear(activity);
                    if (onStateChanged != null) onStateChanged.run();
                })
                .show());
        LinearLayout.LayoutParams endParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        endParams.setMargins(dp(activity, 6), 0, 0, 0);
        actions.addView(end, endParams);
        card.addView(actions);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                (placeAtTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL);
        int vertical = dp(activity, placeAtTop ? 104 : 126);
        params.setMargins(dp(activity, 10), placeAtTop ? vertical : 0,
                dp(activity, 10), placeAtTop ? 0 : vertical);
        root.addView(card, params);
        card.bringToFront();
    }

    private static Button button(Activity activity, String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(11.5f);
        button.setMinHeight(dp(activity, 44));
        button.setMinimumHeight(dp(activity, 44));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(activity, 9), 0, dp(activity, 9), 0);
        return button;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
