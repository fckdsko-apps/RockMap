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
import android.widget.ScrollView;
import android.widget.TextView;

import com.rockmap.app.field.FieldMapController;

/**
 * Map-supporting Research workspace for sibling views of one geographic area.
 *
 * Android interaction contract used here:
 * - Back changes the information subview; it never disables a mapped feature.
 * - Collapse leaves the whole Research session active while exposing the map.
 * - Close hides this panel only; mapped Research layers remain until their map-context × is used.
 * - Primary actions stay static while long result/detail content scrolls independently.
 */
public final class ResearchAreaPanelController {
    public interface Listener {
        void onGeology();
        void onMinerals();
        void onMines();
        void onSaveResearch();
        void onBack();
        void onClosePanel();
        void onPanelModeChanged(String mode);
    }

    public static final class ActionSpec {
        public final String label;
        public final String contentDescription;
        public final Runnable action;

        public ActionSpec(String label, String contentDescription, Runnable action) {
            this.label = label == null ? "" : label.trim();
            this.contentDescription = contentDescription == null ? this.label : contentDescription.trim();
            this.action = action;
        }
    }

    public static final String VIEW_GEOLOGY = "geology";
    public static final String VIEW_MINERALS = "minerals";
    public static final String VIEW_MINES = "mines";

    public static final String MODE_EXPANDED = "expanded";
    public static final String MODE_COLLAPSED = "collapsed";
    public static final String MODE_HIDDEN = "hidden";

    private final Activity activity;
    private final FrameLayout root;
    private LinearLayout panel;
    private LinearLayout expandedGroup;
    private LinearLayout collapsedBar;
    private TextView title;
    private TextView collapsedTitle;
    private TextView collapsedView;
    private TextView status;
    private LinearLayout primaryActions;
    private LinearLayout scrollContent;
    private ScrollView detailScroll;
    private Button geology;
    private Button minerals;
    private Button mines;
    private Listener listener;
    private String activeView = "";
    private String areaLabel = "Selected Area";
    private String mode = MODE_EXPANDED;

    public ResearchAreaPanelController(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
    }

    public void show(String areaLabel, String activeView, String statusText, Listener listener) {
        if (activity == null || root == null) return;
        this.listener = listener;
        this.areaLabel = clean(areaLabel, "Selected Area");
        this.activeView = clean(activeView, VIEW_GEOLOGY);
        ensurePanel();
        setStatus(statusText);
        // Do not reopen a panel the user explicitly closed just because an asynchronous
        // query finished. Explicit navigation/reopen actions call reopenExpanded().
        updateLabels();
        updateTabs();
        renderMode();
    }

    /** Update results/status without reopening a panel the user deliberately closed. */
    public void update(String activeView, String statusText) {
        if (activeView != null && !activeView.trim().isEmpty()) this.activeView = activeView.trim();
        ensurePanel();
        setStatus(statusText);
        updateLabels();
        updateTabs();
        renderMode();
    }

    /** Reopen from a map-context label and expose the working controls. */
    public void reopenExpanded() {
        ensurePanel();
        mode = MODE_EXPANDED;
        renderMode();
    }

    /** Mark the next explicit Research result to open expanded without flashing stale content now. */
    public void prepareForExplicitOpen() {
        mode = MODE_EXPANDED;
    }

    public void restoreMode(String restoredMode) {
        ensurePanel();
        if (MODE_COLLAPSED.equals(restoredMode) || MODE_HIDDEN.equals(restoredMode)) mode = restoredMode;
        else mode = MODE_EXPANDED;
        renderMode();
    }

    public String currentMode() { return mode; }
    public boolean isVisible() { return panel != null && !MODE_HIDDEN.equals(mode) && panel.getVisibility() == View.VISIBLE; }
    public boolean isCollapsed() { return MODE_COLLAPSED.equals(mode); }

    /** Close panel UI only. It intentionally does not clear mapped layers or the Research session. */
    public void closePanel() {
        ensurePanel();
        mode = MODE_HIDDEN;
        renderMode();
        if (listener != null) {
            listener.onPanelModeChanged(mode);
            listener.onClosePanel();
        }
    }

    public void collapse() {
        ensurePanel();
        mode = MODE_COLLAPSED;
        renderMode();
        if (listener != null) listener.onPanelModeChanged(mode);
    }

    public void expand() {
        ensurePanel();
        mode = MODE_EXPANDED;
        renderMode();
        if (listener != null) listener.onPanelModeChanged(mode);
    }

    /** Back is a subview/navigation action, never a layer visibility action. */
    public void back() {
        if (listener != null) listener.onBack();
    }

    public void setPrimaryActions(ActionSpec... actions) {
        ensurePanel();
        primaryActions.removeAllViews();
        int count = 0;
        if (actions != null) {
            for (ActionSpec spec : actions) {
                if (spec == null || spec.label.isEmpty()) continue;
                Button button = actionButton(spec.label);
                button.setContentDescription(spec.contentDescription);
                button.setOnClickListener(v -> { if (spec.action != null) spec.action.run(); });
                primaryActions.addView(button, new LinearLayout.LayoutParams(
                        0, dp(48), 1f));
                count++;
                if (count >= 3) break;
            }
        }
        primaryActions.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    /** Long details/results live here; the header/tabs/primary actions above never scroll away. */
    public void setScrollableContent(View content) {
        ensurePanel();
        scrollContent.removeAllViews();
        if (content == null) {
            detailScroll.setVisibility(View.GONE);
            return;
        }
        detach(content);
        scrollContent.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        detailScroll.setVisibility(View.VISIBLE);
        detailScroll.scrollTo(0, 0);
    }

    public void clearScrollableContent() { setScrollableContent(null); }

    private void ensurePanel() {
        if (panel != null) return;
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setElevation(dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(248, 255, 255, 255));
        background.setStroke(dp(1), Color.rgb(175, 175, 175));
        background.setCornerRadius(dp(10));
        panel.setBackground(background);

        expandedGroup = new LinearLayout(activity);
        expandedGroup.setOrientation(LinearLayout.VERTICAL);
        expandedGroup.setPadding(dp(8), dp(6), dp(8), dp(7));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button back = iconButton("‹", "Back without hiding the active Research map feature");
        back.setOnClickListener(v -> back());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        title = new TextView(activity);
        title.setTextSize(13f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(30, 30, 30));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button collapse = iconButton("⌄", "Collapse Research Area panel");
        collapse.setOnClickListener(v -> collapse());
        header.addView(collapse, new LinearLayout.LayoutParams(dp(48), dp(48)));

        Button close = iconButton("×", "Close Research Area panel without hiding mapped Research layers");
        close.setOnClickListener(v -> closePanel());
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        expandedGroup.addView(header);

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        geology = tabButton("Geology");
        minerals = tabButton("Mineral Evidence");
        mines = tabButton("Historic Mines");
        geology.setOnClickListener(v -> { if (listener != null) listener.onGeology(); });
        minerals.setOnClickListener(v -> { if (listener != null) listener.onMinerals(); });
        mines.setOnClickListener(v -> { if (listener != null) listener.onMines(); });
        tabs.addView(geology, new LinearLayout.LayoutParams(0, dp(48), 1f));
        tabs.addView(minerals, new LinearLayout.LayoutParams(0, dp(48), 1.15f));
        tabs.addView(mines, new LinearLayout.LayoutParams(0, dp(48), 1f));
        expandedGroup.addView(tabs);

        primaryActions = new LinearLayout(activity);
        primaryActions.setOrientation(LinearLayout.HORIZONTAL);
        primaryActions.setGravity(Gravity.CENTER_VERTICAL);
        primaryActions.setVisibility(View.GONE);
        expandedGroup.addView(primaryActions);

        status = new TextView(activity);
        status.setTextSize(11.5f);
        status.setTextColor(Color.rgb(65, 65, 65));
        status.setPadding(dp(6), dp(4), dp(6), dp(4));
        expandedGroup.addView(status);

        detailScroll = new ScrollView(activity);
        detailScroll.setFillViewport(false);
        scrollContent = new LinearLayout(activity);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.setPadding(dp(4), 0, dp(4), dp(2));
        detailScroll.addView(scrollContent);
        int detailHeight = Math.min(dp(156), Math.max(dp(96),
                Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.18f)));
        expandedGroup.addView(detailScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, detailHeight));
        detailScroll.setVisibility(View.GONE);
        panel.addView(expandedGroup);

        collapsedBar = new LinearLayout(activity);
        collapsedBar.setOrientation(LinearLayout.HORIZONTAL);
        collapsedBar.setGravity(Gravity.CENTER_VERTICAL);
        collapsedBar.setPadding(dp(10), dp(2), dp(4), dp(2));
        collapsedTitle = new TextView(activity);
        collapsedTitle.setTextSize(12.5f);
        collapsedTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        collapsedTitle.setTextColor(Color.rgb(35, 35, 35));
        collapsedTitle.setSingleLine(true);
        collapsedTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        collapsedBar.addView(collapsedTitle, new LinearLayout.LayoutParams(0, dp(52), 1f));
        collapsedView = new TextView(activity);
        collapsedView.setTextSize(11f);
        collapsedView.setTextColor(Color.rgb(85, 85, 85));
        collapsedView.setSingleLine(true);
        collapsedView.setGravity(Gravity.CENTER_VERTICAL);
        collapsedBar.addView(collapsedView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        Button expand = iconButton("⌃", "Expand Research Area panel");
        expand.setOnClickListener(v -> expand());
        collapsedBar.addView(expand, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button collapsedClose = iconButton("×", "Close Research Area panel without hiding mapped Research layers");
        collapsedClose.setOnClickListener(v -> closePanel());
        collapsedBar.addView(collapsedClose, new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(collapsedBar);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.setMargins(dp(8), 0, dp(8), dp(126));
        root.addView(panel, params);
        updateLabels();
        updateTabs();
        renderMode();
    }

    private void setStatus(String statusText) {
        if (status == null) return;
        status.setText(statusText == null ? "" : statusText.trim());
        status.setVisibility(status.getText().length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void updateLabels() {
        if (title != null) title.setText("Research Area — " + areaLabel);
        if (collapsedTitle != null) collapsedTitle.setText("Research — " + areaLabel);
        if (collapsedView != null) collapsedView.setText(viewLabel(activeView));
    }

    private void renderMode() {
        if (panel == null) return;
        if (MODE_HIDDEN.equals(mode)) {
            panel.setVisibility(View.GONE);
        } else {
            panel.setVisibility(View.VISIBLE);
            boolean collapsed = MODE_COLLAPSED.equals(mode);
            expandedGroup.setVisibility(collapsed ? View.GONE : View.VISIBLE);
            collapsedBar.setVisibility(collapsed ? View.VISIBLE : View.GONE);
            panel.bringToFront();
        }
        FieldMapController.ensurePersistentEntry(activity);
    }

    private void updateTabs() {
        updateTab(geology, VIEW_GEOLOGY.equals(activeView));
        updateTab(minerals, VIEW_MINERALS.equals(activeView));
        updateTab(mines, VIEW_MINES.equals(activeView));
        if (collapsedView != null) collapsedView.setText(viewLabel(activeView));
    }

    private void updateTab(Button button, boolean selected) {
        if (button == null) return;
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setAlpha(selected ? 1f : 0.76f);
        button.setSelected(selected);
    }

    private Button tabButton(String text) {
        Button button = actionButton(text);
        button.setContentDescription("Open " + text + " for the current Research Area");
        return button;
    }

    private Button actionButton(String text) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(11f);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(5), 0, dp(5), 0);
        return button;
    }

    private Button iconButton(String text, String description) {
        Button button = actionButton(text);
        button.setTextSize(19f);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(description);
        return button;
    }

    private String viewLabel(String view) {
        if (VIEW_MINERALS.equals(view)) return "Minerals";
        if (VIEW_MINES.equals(view)) return "Mines";
        return "Geology";
    }

    private static String clean(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static void detach(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
