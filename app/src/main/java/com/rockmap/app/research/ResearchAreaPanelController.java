package com.rockmap.app.research;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rockmap.app.RockMapDragHandle;
import com.rockmap.app.field.FieldMapController;

/**
 * Map-first Research workspace for sibling views of one geographic area.
 *
 * Interaction contract:
 * - Back appears only inside subviews that actually have a previous Research view.
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
        void onHelp();
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
    private LinearLayout fixedContent;
    private LinearLayout scrollContent;
    private ScrollView detailScroll;
    private Button geology;
    private Button minerals;
    private Button mines;
    private Button collapseButton;
    private Button expandButton;
    private Button closeButton;
    private Button firstPrimaryAction;
    private Listener listener;
    private String activeView = "";
    private String areaLabel = "Selected Area";
    private String mode = MODE_EXPANDED;
    private final int panelTouchSlop;
    private float panelDragDownRawX;
    private float panelDragDownRawY;
    private int panelDragStartLeft;
    private int panelDragStartTop;
    private boolean panelDragging;
    private boolean panelUserPositioned;
    private int panelUserLeft;
    private int panelUserTop;

    public ResearchAreaPanelController(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        this.panelTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
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
    public View getCollapseControl() { ensurePanel(); return collapseButton; }
    public View getExpandControl() { ensurePanel(); return expandButton; }
    public View getCloseControl() { ensurePanel(); return closeButton; }
    public View getGeologyControl() { ensurePanel(); return geology; }
    public View getMineralControl() { ensurePanel(); return minerals; }
    public View getHistoricControl() { ensurePanel(); return mines; }
    public View getPrimaryActionControl() { ensurePanel(); return firstPrimaryAction; }
    public View getScrollableContentControl() { ensurePanel(); return detailScroll; }

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
        firstPrimaryAction = null;
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
                if (firstPrimaryAction == null) firstPrimaryAction = button;
                count++;
                if (count >= 3) break;
            }
        }
        primaryActions.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    /** Fixed controls such as Research search/browse tools remain visible while results scroll. */
    public void setFixedContent(View content) {
        ensurePanel();
        fixedContent.removeAllViews();
        if (content == null) {
            fixedContent.setVisibility(View.GONE);
            return;
        }
        detach(content);
        fixedContent.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fixedContent.setVisibility(View.VISIBLE);
    }

    public void clearFixedContent() { setFixedContent(null); }

    public void scrollDetailsToTop() {
        ensurePanel();
        detailScroll.post(() -> detailScroll.scrollTo(0, 0));
    }

    public void scrollDetailsTo(View target) {
        ensurePanel();
        if (target == null) return;
        detailScroll.post(() -> detailScroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(4))));
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
        header.setOnTouchListener(this::handlePanelDrag);

        collapseButton = iconButton("›", "Collapse Research workspace");
        collapseButton.setOnClickListener(v -> collapse());
        header.addView(collapseButton, new LinearLayout.LayoutParams(dp(46), dp(46)));

        title = new TextView(activity);
        title.setTextSize(13.5f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(COLOR_TEXT);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(7), 0, dp(5), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1f));

        View dragHandle = RockMapDragHandle.labeled(activity, COLOR_MUTED,
                this::handlePanelDrag, "Drag Research workspace");
        header.addView(dragHandle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)));

        Button help = iconButton("?", "Open help for the current Research view");
        help.setTextSize(16f);
        help.setOnClickListener(v -> { if (listener != null) listener.onHelp(); });
        header.addView(help, new LinearLayout.LayoutParams(dp(46), dp(46)));

        closeButton = actionButton("Close");
        closeButton.setTextSize(10.5f);
        closeButton.setContentDescription("Close Research workspace without hiding mapped Research layers");
        closeButton.setOnClickListener(v -> closePanel());
        header.addView(closeButton, new LinearLayout.LayoutParams(dp(64), dp(46)));
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

        fixedContent = new LinearLayout(activity);
        fixedContent.setOrientation(LinearLayout.VERTICAL);
        fixedContent.setPadding(dp(4), 0, dp(4), dp(3));
        fixedContent.setVisibility(View.GONE);
        expandedGroup.addView(fixedContent);

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

        // Collapsed state stays movable and uses the same visual interaction language as expanded panels.
        collapsedBar = new LinearLayout(activity);
        collapsedBar.setOrientation(LinearLayout.HORIZONTAL);
        collapsedBar.setGravity(Gravity.CENTER_VERTICAL);
        collapsedBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        collapsedBar.setOnTouchListener(this::handlePanelDrag);

        expandButton = actionButton("‹ Research");
        expandButton.setTextSize(11.5f);
        expandButton.setContentDescription("Expand Research workspace");
        expandButton.setOnClickListener(v -> expand());
        collapsedBar.addView(expandButton, new LinearLayout.LayoutParams(dp(94), dp(46)));

        View collapsedDrag = RockMapDragHandle.compact(activity, COLOR_MUTED,
                this::handlePanelDrag, "Drag collapsed Research workspace");
        LinearLayout.LayoutParams dragParams = new LinearLayout.LayoutParams(dp(40), dp(46));
        dragParams.setMargins(dp(3), 0, 0, 0);
        collapsedBar.addView(collapsedDrag, dragParams);

        Button collapsedClose = actionButton("Close");
        collapsedClose.setTextSize(10.5f);
        collapsedClose.setContentDescription("Close Research workspace without hiding mapped Research layers");
        collapsedClose.setOnClickListener(v -> closePanel());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(64), dp(46));
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
        int rootWidth = Math.max(dp(320), root.getWidth() > 0 ? root.getWidth()
                : activity.getResources().getDisplayMetrics().widthPixels);
        int width = Math.max(dp(280), Math.min(dp(520), rootWidth - dp(24)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        int left = panelUserPositioned ? panelUserLeft : Math.max(dp(8), (rootWidth - width) / 2);
        int top = panelUserPositioned ? panelUserTop : statusBarHeight() + dp(8);
        params.leftMargin = clamp(left, dp(6), Math.max(dp(6), rootWidth - width - dp(6)));
        params.topMargin = clampPanelTop(top, panel == null ? dp(260) : Math.max(dp(120), panel.getHeight()));
        return params;
    }

    private FrameLayout.LayoutParams collapsedLayoutParams() {
        int rootWidth = Math.max(dp(320), root.getWidth() > 0 ? root.getWidth()
                : activity.getResources().getDisplayMetrics().widthPixels);
        int width = dp(204);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        int left = panelUserPositioned ? panelUserLeft : Math.max(dp(6), rootWidth - width - dp(6));
        int top = panelUserPositioned ? panelUserTop : statusBarHeight() + dp(82);
        params.leftMargin = clamp(left, dp(6), Math.max(dp(6), rootWidth - width - dp(6)));
        params.topMargin = clampPanelTop(top, dp(52));
        return params;
    }

    private boolean handlePanelDrag(View touched, MotionEvent event) {
        if (panel == null || root == null || event == null) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!(panel.getLayoutParams() instanceof FrameLayout.LayoutParams)) return false;
                FrameLayout.LayoutParams start = (FrameLayout.LayoutParams) panel.getLayoutParams();
                panelDragDownRawX = event.getRawX();
                panelDragDownRawY = event.getRawY();
                panelDragStartLeft = start.leftMargin;
                panelDragStartTop = start.topMargin;
                panelDragging = false;
                return false;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - panelDragDownRawX;
                float dy = event.getRawY() - panelDragDownRawY;
                if (!panelDragging && Math.hypot(dx, dy) >= Math.max(1, panelTouchSlop)) {
                    panelDragging = true;
                    touched.setPressed(false);
                    ViewParent parent = touched.getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                }
                if (panelDragging) {
                    movePanelTo(panelDragStartLeft + Math.round(dx),
                            panelDragStartTop + Math.round(dy));
                    return true;
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (panelDragging) {
                    float upDx = event.getRawX() - panelDragDownRawX;
                    float upDy = event.getRawY() - panelDragDownRawY;
                    movePanelTo(panelDragStartLeft + Math.round(upDx),
                            panelDragStartTop + Math.round(upDy));
                    touched.setPressed(false);
                    panelDragging = false;
                    return true;
                }
                return false;
            default:
                return panelDragging;
        }
    }

    private void movePanelTo(int left, int top) {
        if (panel == null || root == null || !(panel.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        int rootWidth = Math.max(root.getWidth(), activity.getResources().getDisplayMetrics().widthPixels);
        int width = panel.getWidth() > 0 ? panel.getWidth() : params.width;
        if (width <= 0) width = MODE_COLLAPSED.equals(mode) ? dp(204) : Math.max(dp(280), rootWidth - dp(24));
        left = clamp(left, dp(6), Math.max(dp(6), rootWidth - width - dp(6)));
        top = clampPanelTop(top, Math.max(dp(52), panel.getHeight()));
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = left;
        params.topMargin = top;
        params.rightMargin = 0;
        params.bottomMargin = 0;
        panel.setLayoutParams(params);
        panelUserPositioned = true;
        panelUserLeft = left;
        panelUserTop = top;
    }

    private int clampPanelTop(int top, int height) {
        int rootHeight = root.getHeight() > 0 ? root.getHeight()
                : activity.getResources().getDisplayMetrics().heightPixels;
        int minTop = Math.max(dp(6), statusBarHeight() + dp(2));
        int bottomGuard = dp(118);
        int maxTop = Math.max(minTop, rootHeight - Math.max(dp(52), height) - bottomGuard);
        return clamp(top, minTop, maxTop);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
