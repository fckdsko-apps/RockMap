package com.rockmap.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Non-blocking, draggable guided-tour coach with a touch-transparent target outline. */
public final class GuidedTourCoach {
    private static final String TAG = "rockmap-guided-tour-coach";
    private static WeakReference<View> highlightedView = new WeakReference<>(null);
    private static Drawable highlightedDrawable;

    private GuidedTourCoach() {}

    public static void clear(Activity activity) {
        clearHighlight();
        if (activity == null) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        View old = root.findViewWithTag(TAG);
        if (old != null) root.removeView(old);
    }

    public static void show(Activity activity, int step, int total, String title, String message,
                            String requiredAction, View target,
                            String primaryLabel, Runnable primaryAction,
                            Runnable skipAction, Runnable exitAction) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        clear(activity);
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) content;

        DraggableCard card = new DraggableCard(activity, root);
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

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView progress = new TextView(activity);
        progress.setText("GUIDED TOUR · " + Math.max(1, step) + " OF " + Math.max(step, total));
        progress.setTextSize(10.5f);
        progress.setTextColor(Color.rgb(0, 112, 121));
        progress.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(progress, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView drag = new TextView(activity);
        drag.setText("DRAG  ↕");
        drag.setTextSize(10.5f);
        drag.setTextColor(Color.rgb(75, 85, 87));
        drag.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        drag.setContentDescription("Drag guided tour card");
        header.addView(drag);
        card.addView(header);

        TextView heading = new TextView(activity);
        heading.setText(title == null ? "" : title);
        heading.setTextSize(16f);
        heading.setTextColor(Color.rgb(30, 36, 38));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(activity, 2), 0, dp(activity, 2));
        card.addView(heading);

        TextView body = new TextView(activity);
        SpannableStringBuilder bodyText = new SpannableStringBuilder(message == null ? "" : message);
        if (requiredAction != null && !requiredAction.trim().isEmpty()) {
            if (bodyText.length() > 0) bodyText.append("\n\n");
            int start = bodyText.length();
            bodyText.append("NEXT: ").append(requiredAction.trim());
            bodyText.setSpan(new StyleSpan(Typeface.BOLD), start, bodyText.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        body.setText(bodyText);
        body.setTextSize(12.5f);
        body.setTextColor(Color.rgb(66, 75, 77));
        body.setLineSpacing(0f, 1.08f);
        card.addView(body);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(activity, 6), 0, 0);

        if (primaryLabel != null && !primaryLabel.trim().isEmpty()) {
            Button primary = button(activity, primaryLabel.trim());
            primary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            primary.setOnClickListener(v -> { if (primaryAction != null) primaryAction.run(); });
            actions.addView(primary, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout persistentActions = new LinearLayout(activity);
        persistentActions.setOrientation(LinearLayout.HORIZONTAL);
        persistentActions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        Button skip = button(activity, "Skip step");
        skip.setOnClickListener(v -> { if (skipAction != null) skipAction.run(); });
        LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        persistentActions.addView(skip, skipParams);

        Button exit = button(activity, "Exit tour");
        exit.setOnClickListener(v -> {
            if (exitAction != null) exitAction.run();
            else GuidedTourState.exit(activity);
            clear(activity);
        });
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        exitParams.setMargins(dp(activity, 5), 0, 0, 0);
        persistentActions.addView(exit, exitParams);
        actions.addView(persistentActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(actions);

        int width = Math.min(dp(activity, 420), Math.max(dp(activity, 280),
                activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 20)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        params.setMargins(dp(activity, 10), dp(activity, 110), 0, 0);
        root.addView(card, params);
        card.bringToFront();
        card.restoreSavedPosition();
        highlight(target);
        if (target != null) {
            target.post(() -> {
                target.requestFocus();
                target.announceForAccessibility(requiredAction == null ? "Guided tour target" : requiredAction);
            });
        }
    }

    private static void highlight(View target) {
        clearHighlight();
        if (target == null) return;
        target.post(() -> {
            if (target.getWidth() <= 0 || target.getHeight() <= 0) return;
            GradientDrawable outline = new GradientDrawable();
            outline.setColor(Color.TRANSPARENT);
            outline.setStroke(dp(target, 4), Color.rgb(0, 112, 121));
            outline.setCornerRadius(dp(target, 8));
            outline.setBounds(new Rect(0, 0, target.getWidth(), target.getHeight()));
            target.getOverlay().add(outline);
            highlightedView = new WeakReference<>(target);
            highlightedDrawable = outline;
        });
    }

    private static void clearHighlight() {
        View old = highlightedView.get();
        if (old != null && highlightedDrawable != null) old.getOverlay().remove(highlightedDrawable);
        highlightedView = new WeakReference<>(null);
        highlightedDrawable = null;
    }

    private static Button button(Activity activity, String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(11f);
        button.setMinHeight(dp(activity, 48));
        button.setMinimumHeight(dp(activity, 48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(activity, 7), 0, dp(activity, 7), 0);
        return button;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static final class DraggableCard extends LinearLayout {
        private final FrameLayout root;
        private final int touchSlop;
        private float downRawX;
        private float downRawY;
        private int startLeft;
        private int startTop;
        private boolean dragging;

        DraggableCard(Activity activity, FrameLayout root) {
            super(activity);
            this.root = root;
            this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) getLayoutParams();
                    startLeft = p.leftMargin;
                    startTop = p.topMargin;
                    dragging = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(event.getRawX() - downRawX, event.getRawY() - downRawY)
                            >= Math.max(1, touchSlop)) {
                        dragging = true;
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    moveTo(startLeft + Math.round(event.getRawX() - downRawX),
                            startTop + Math.round(event.getRawY() - downRawY), false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        moveTo(startLeft + Math.round(event.getRawX() - downRawX),
                                startTop + Math.round(event.getRawY() - downRawY), true);
                        dragging = false;
                        return true;
                    }
                    return false;
                default:
                    return true;
            }
        }

        void restoreSavedPosition() {
            post(() -> {
                if (!GuidedTourState.hasCoachPosition(getContext())) return;
                int availableW = Math.max(1, root.getWidth() - getWidth() - dp(this, 8));
                int availableH = Math.max(1, root.getHeight() - getHeight() - dp(this, 118));
                moveTo(Math.round(GuidedTourState.coachX(getContext()) * availableW),
                        Math.round(GuidedTourState.coachY(getContext()) * availableH), false);
            });
        }

        private void moveTo(int left, int top, boolean save) {
            int availableW = Math.max(0, root.getWidth() - getWidth() - dp(this, 8));
            int availableH = Math.max(0, root.getHeight() - getHeight() - dp(this, 118));
            int safeLeft = Math.max(dp(this, 6), Math.min(left, availableW));
            int safeTop = Math.max(dp(this, 6), Math.min(top, availableH));
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) getLayoutParams();
            p.gravity = Gravity.TOP | Gravity.START;
            p.leftMargin = safeLeft;
            p.topMargin = safeTop;
            p.rightMargin = 0;
            p.bottomMargin = 0;
            setLayoutParams(p);
            if (save) {
                float x = availableW <= 0 ? 0f : safeLeft / (float) availableW;
                float y = availableH <= 0 ? 0f : safeTop / (float) availableH;
                GuidedTourState.saveCoachPosition(getContext(), x, y);
            }
        }
    }
}
