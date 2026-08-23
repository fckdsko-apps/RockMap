package com.rockmap.app.research;

import android.app.Activity;
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
 * Persistent, map-supporting navigation for sibling Research views of one geographic area.
 * The map remains the primary surface; Geology, Mineral Evidence and Historic Mines stay one tap
 * apart without chaining modal prompts or losing the active area.
 */
public final class ResearchAreaPanelController {
    public interface Listener {
        void onGeology();
        void onMinerals();
        void onMines();
        void onClose();
    }

    public static final String VIEW_GEOLOGY = "geology";
    public static final String VIEW_MINERALS = "minerals";
    public static final String VIEW_MINES = "mines";

    private final Activity activity;
    private final FrameLayout root;
    private LinearLayout panel;
    private TextView title;
    private TextView status;
    private Button geology;
    private Button minerals;
    private Button mines;
    private Listener listener;
    private String activeView = "";

    public ResearchAreaPanelController(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
    }

    public void show(String areaLabel, String activeView, String statusText, Listener listener) {
        if (activity == null || root == null) return;
        this.listener = listener;
        this.activeView = activeView == null ? "" : activeView;
        ensurePanel();
        title.setText(areaLabel == null || areaLabel.trim().isEmpty()
                ? "Research Area" : "Research Area — " + areaLabel.trim());
        status.setText(statusText == null ? "" : statusText.trim());
        status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
        updateTabs();
        panel.setVisibility(View.VISIBLE);
        panel.bringToFront();
    }

    public void update(String activeView, String statusText) {
        if (activeView != null) this.activeView = activeView;
        ensurePanel();
        if (statusText != null) {
            status.setText(statusText.trim());
            status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
        }
        updateTabs();
        panel.setVisibility(View.VISIBLE);
        panel.bringToFront();
    }

    public boolean isVisible() {
        return panel != null && panel.getVisibility() == View.VISIBLE;
    }

    public void hide() {
        if (panel != null) panel.setVisibility(View.GONE);
    }

    private void ensurePanel() {
        if (panel != null) return;
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        panel.setElevation(dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(247, 255, 255, 255));
        background.setStroke(dp(1), Color.rgb(180, 180, 180));
        background.setCornerRadius(dp(10));
        panel.setBackground(background);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(activity);
        title.setTextSize(13f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(30, 30, 30));
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button close = tabButton("×");
        close.setContentDescription("Close Research Area panel");
        close.setOnClickListener(v -> {
            hide();
            if (listener != null) listener.onClose();
        });
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(header);

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        geology = tabButton("Geology");
        minerals = tabButton("Mineral Evidence");
        mines = tabButton("Historic Mines & Workings");
        geology.setOnClickListener(v -> { if (listener != null) listener.onGeology(); });
        minerals.setOnClickListener(v -> { if (listener != null) listener.onMinerals(); });
        mines.setOnClickListener(v -> { if (listener != null) listener.onMines(); });
        tabs.addView(geology, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tabs.addView(minerals, new LinearLayout.LayoutParams(0, dp(48), 1.15f));
        tabs.addView(mines, new LinearLayout.LayoutParams(0, dp(48), 1f));
        panel.addView(tabs);

        status = new TextView(activity);
        status.setTextSize(11.5f);
        status.setTextColor(Color.rgb(65, 65, 65));
        status.setPadding(dp(4), dp(5), dp(4), dp(2));
        panel.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.setMargins(dp(8), 0, dp(8), dp(126));
        root.addView(panel, params);
        panel.setVisibility(View.GONE);
    }

    private Button tabButton(String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(11f);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private void updateTabs() {
        updateTab(geology, VIEW_GEOLOGY.equals(activeView));
        updateTab(minerals, VIEW_MINERALS.equals(activeView));
        updateTab(mines, VIEW_MINES.equals(activeView));
    }

    private void updateTab(Button button, boolean selected) {
        if (button == null) return;
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setAlpha(selected ? 1f : 0.78f);
        button.setSelected(selected);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
