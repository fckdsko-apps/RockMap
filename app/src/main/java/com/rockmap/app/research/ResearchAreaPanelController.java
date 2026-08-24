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
 * Map-first Research workspace for sibling views of one geographic area.
 *
 * Interaction contract:
 * - Back changes the Research information subview; it never disables a mapped feature.
 * - Collapse docks Research to the right edge and leaves the Research session active.
 * - Close hides this workspace only; mapped layers remain until their map-context × is used.
 * - Primary actions stay static while long results/details scroll independently.
 *
 * The expanded workspace intentionally follows RockMap's Track/Measure pattern: it lives at the
 * top of the map, away from the permanent bottom navigation, and can collapse to a compact side tab.
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

    // One Research palette: teal identifies Research interaction/selection; mapped datasets keep
    // their own map colors. This prevents button state colors from being confused with map evidence.
    private static final int COLOR_SURFACE = Color.rgb(250, 250, 250);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_BORDER = Color.rgb(190, 207, 209);
    private static final int COLOR_TEXT = Color.rgb(32, 38, 40);
    private static final int COLOR_MUTED = Color.rgb(82, 94, 96);
    private static final int COLOR_ACCENT = Color.rgb(0, 112, 121);
    private static final int COLOR_ACCENT_BG = Color.rgb(222, 242, 243);

    private final Activity activity;
    private final FrameLayout root;
    private LinearLayout panel;
    private LinearLayout expandedGroup;
    private LinearLayout collapsedBar;
    private TextView title;
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

    /** Reopen from a map-context label or explicit Research action. */
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
    public boolean isVisible() {
        return panel != null && !MODE_HIDDEN.equals(mode) && panel.getVisibility() == View.VISIBLE;
    }
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
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1f);
                if (count > 0) params.setMargins(dp(4), 0, 0, 0);
                primaryActions.addView(button, params);
                count++;
                if (count >= 3) break;
            }
        }
        primaryActions.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    /** Long details/results live here; header/tabs/primary actions above never scroll away. */
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
        panel.setElevation(dp(7));
        panel.setBackground(panelBackground());

        expandedGroup = new LinearLayout(activity);
        expandedGroup.setOrientation(LinearLayout.VERTICAL);
        expandedGroup.setPadding(dp(8), dp(6), dp(8), dp(7));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Button back = iconButton("‹", "Back without hiding the active Research map feature");
        back.setOnClickListener(v -> back());
        header.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));

        title = new TextView(activity);
        title.setTextSize(13.5f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(COLOR_TEXT);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(7), 0, dp(5), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button collapse = iconButton("›", "Collapse Research to the right edge");
        collapse.setOnClickListener(v -> collapse());
        header.addView(collapse, new LinearLayout.LayoutParams(dp(46), dp(46)));

        Button close = iconButton("×", "Close Research panel without hiding mapped Research layers");
        close.setOnClickListener(v -> closePanel());
        header.addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));
        expandedGroup.addView(header);

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(0, dp(2), 0, dp(4));
        geology = tabButton("Geology");
        minerals = tabButton("Mineral Evidence");
        mines = tabButton("Historic Mines");
        geology.setOnClickListener(v -> { if (listener != null) listener.onGeology(); });
        minerals.setOnClickListener(v -> { if (listener != null) listener.onMinerals(); });
        mines.setOnClickListener(v -> { if (listener != null) listener.onMines(); });
        LinearLayout.LayoutParams tabA = new LinearLayout.LayoutParams(0, dp(46), 1f);
        LinearLayout.LayoutParams tabB = new LinearLayout.LayoutParams(0, dp(46), 1.16f);
        tabB.setMargins(dp(4), 0, dp(4), 0);
        tabs.addView(geology, tabA);
        tabs.addView(minerals, tabB);
        tabs.addView(mines, new LinearLayout.LayoutParams(0, dp(46), 1f));
        expandedGroup.addView(tabs);

        primaryActions = new LinearLayout(activity);
        primaryActions.setOrientation(LinearLayout.HORIZONTAL);
        primaryActions.setGravity(Gravity.CENTER_VERTICAL);
        primaryActions.setPadding(0, 0, 0, dp(3));
        primaryActions.setVisibility(View.GONE);
        expandedGroup.addView(primaryActions);

        status = new TextView(activity);
        status.setTextSize(11.5f);
        status.setTextColor(COLOR_MUTED);
        status.setPadding(dp(6), dp(4), dp(6), dp(4));
        expandedGroup.addView(status);

        detailScroll = new ScrollView(activity);
        detailScroll.setFillViewport(false);
        scrollContent = new LinearLayout(activity);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.setPadding(dp(4), 0, dp(4), dp(2));
        detailScroll.addView(scrollContent);
        int detailHeight = Math.min(dp(176), Math.max(dp(96),
                Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.19f)));
        expandedGroup.addView(detailScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, detailHeight));
        detailScroll.setVisibility(View.GONE);
        panel.addView(expandedGroup);

        // Collapsed state mirrors RockMap's Track/Measure side tabs: a compact reopen target plus
        // an explicit close affordance. It never occupies the bottom navigation area.
        collapsedBar = new LinearLayout(activity);
        collapsedBar.setOrientation(LinearLayout.HORIZONTAL);
        collapsedBar.setGravity(Gravity.CENTER_VERTICAL);
        collapsedBar.setPadding(dp(3), dp(3), dp(3), dp(3));

        Button expand = actionButton("‹ Research");
        expand.setTextSize(11.5f);
        expand.setContentDescription("Expand Research map workspace");
        expand.setOnClickListener(v -> expand());
        collapsedBar.addView(expand, new LinearLayout.LayoutParams(dp(94), dp(46)));

        Button collapsedClose = iconButton("×", "Close Research panel without hiding mapped Research layers");
        collapsedClose.setOnClickListener(v -> closePanel());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(46), dp(46));
        closeParams.setMargins(dp(3), 0, 0, 0);
        collapsedBar.addView(collapsedClose, closeParams);
        panel.addView(collapsedBar);

        root.addView(panel, expandedLayoutParams());
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
        if (title != null) title.setText("Research — " + areaLabel + " · " + viewLabel(activeView));
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
            panel.setLayoutParams(collapsed ? collapsedLayoutParams() : expandedLayoutParams());
            panel.setBackground(panelBackground());
            panel.bringToFront();
        }
        FieldMapController.ensurePersistentEntry(activity);
    }

    private FrameLayout.LayoutParams expandedLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.setMargins(dp(8), statusBarHeight() + dp(8), dp(8), 0);
        return params;
    }

    private FrameLayout.LayoutParams collapsedLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.setMargins(0, statusBarHeight() + dp(82), dp(6), 0);
        return params;
    }

    private void updateTabs() {
        updateTab(geology, VIEW_GEOLOGY.equals(activeView));
        updateTab(minerals, VIEW_MINERALS.equals(activeView));
        updateTab(mines, VIEW_MINES.equals(activeView));
    }

    private void updateTab(Button button, boolean selected) {
        if (button == null) return;
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setTextColor(selected ? COLOR_ACCENT : COLOR_TEXT);
        button.setBackground(buttonBackground(selected));
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
        button.setTextSize(11.5f);
        button.setTextColor(COLOR_TEXT);
        button.setMinHeight(dp(46));
        button.setMinimumHeight(dp(46));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setGravity(Gravity.CENTER);
        button.setBackground(buttonBackground(false));
        return button;
    }

    private Button iconButton(String text, String description) {
        Button button = actionButton(text);
        button.setTextSize(19f);
        button.setTextColor(COLOR_ACCENT);
        button.setPadding(0, 0, 0, 0);
        button.setContentDescription(description);
        return button;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_SURFACE);
        background.setStroke(dp(1), COLOR_BORDER);
        background.setCornerRadius(dp(9));
        return background;
    }

    private GradientDrawable buttonBackground(boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(selected ? COLOR_ACCENT_BG : COLOR_CARD);
        background.setStroke(dp(1), selected ? COLOR_ACCENT : COLOR_BORDER);
        background.setCornerRadius(dp(7));
        return background;
    }

    private String viewLabel(String view) {
        if (VIEW_MINERALS.equals(view)) return "Minerals";
        if (VIEW_MINES.equals(view)) return "Mines";
        return "Geology";
    }

    private int statusBarHeight() {
        int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? activity.getResources().getDimensionPixelSize(id) : 0;
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
