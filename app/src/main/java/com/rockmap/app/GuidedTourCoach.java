package com.rockmap.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Non-blocking guided-tour coach.
 *
 * The coach uses the same drag interaction pattern as RockMap's proven draggable map-context box:
 * a real touch listener on a dedicated draggable row, Android touch-slop before movement begins,
 * and root-relative bounded positioning. Automatic placement treats the target and its containing
 * UI as occupied space and chooses the least-obstructive live location before every step.
 */
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
                            Runnable backAction,
                            String primaryLabel, Runnable primaryAction,
                            Runnable skipAction, Runnable exitAction) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        clear(activity);
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) content;

        DraggableCard card = new DraggableCard(activity, root, target);
        card.setTag(TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 12), dp(activity, 9), dp(activity, 12), dp(activity, 9));
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
        header.setClickable(true);
        header.setFocusable(true);
        header.setMinimumHeight(dp(activity, 44));
        header.setContentDescription("Drag guided tour card");
        header.setOnTouchListener(card::handleDrag);

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
        card.addNormalView(header);

        TextView heading = new TextView(activity);
        heading.setText(title == null ? "" : title);
        heading.setTextSize(16f);
        heading.setTextColor(Color.rgb(30, 36, 38));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(activity, 2), 0, dp(activity, 2));
        card.addView(heading);
        card.addNormalView(heading);

        TextView body = new TextView(activity);
        body.setText(message == null ? "" : message);
        body.setTextSize(12.5f);
        body.setTextColor(Color.rgb(66, 75, 77));
        body.setLineSpacing(0f, 1.06f);
        card.addView(body);
        card.addNormalView(body);

        if (requiredAction != null && !requiredAction.trim().isEmpty()) {
            TextView next = new TextView(activity);
            next.setText("ACTION: " + requiredAction.trim());
            next.setTextSize(12.5f);
            next.setTextColor(Color.rgb(28, 50, 52));
            next.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            next.setPadding(0, dp(activity, 5), 0, 0);
            next.setContentDescription("Guided tour action: " + requiredAction.trim());
            card.addView(next);
            card.addNormalView(next);
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(activity, 5), 0, 0);

        boolean hasPrimary = primaryLabel != null && !primaryLabel.trim().isEmpty();
        boolean hasBack = backAction != null;

        if (hasPrimary) {
            LinearLayout navigation = new LinearLayout(activity);
            navigation.setOrientation(LinearLayout.HORIZONTAL);
            navigation.setGravity(Gravity.CENTER_VERTICAL);

            if (hasBack) {
                Button back = button(activity, "Back");
                back.setOnClickListener(v -> backAction.run());
                navigation.addView(back, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }

            Button primary = button(activity, primaryLabel.trim());
            primary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            primary.setOnClickListener(v -> { if (primaryAction != null) primaryAction.run(); });
            LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (hasBack) primaryParams.setMargins(dp(activity, 6), 0, 0, 0);
            navigation.addView(primary, primaryParams);
            actions.addView(navigation, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout persistentActions = new LinearLayout(activity);
        persistentActions.setOrientation(LinearLayout.HORIZONTAL);
        persistentActions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        if (hasBack && !hasPrimary) {
            Button back = button(activity, "Back");
            back.setOnClickListener(v -> backAction.run());
            persistentActions.addView(back, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        Button skip = button(activity, "Skip step");
        skip.setOnClickListener(v -> { if (skipAction != null) skipAction.run(); });
        LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (hasBack && !hasPrimary) skipParams.setMargins(dp(activity, 5), 0, 0, 0);
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
        card.addNormalView(actions);

        boolean denseEligible = true;
        if (denseEligible) {
            LinearLayout compact = new LinearLayout(activity);
            compact.setOrientation(LinearLayout.VERTICAL);
            compact.setVisibility(View.GONE);

            LinearLayout compactTop = new LinearLayout(activity);
            compactTop.setOrientation(LinearLayout.HORIZONTAL);
            compactTop.setGravity(Gravity.CENTER_VERTICAL);
            compactTop.setMinimumHeight(dp(activity, 52));

            TextView compactDrag = new TextView(activity);
            compactDrag.setText(Math.max(1, step) + "/" + Math.max(step, total) + "\n↕");
            compactDrag.setTextSize(10f);
            compactDrag.setTextColor(Color.rgb(0, 112, 121));
            compactDrag.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            compactDrag.setGravity(Gravity.CENTER);
            compactDrag.setClickable(true);
            compactDrag.setFocusable(true);
            compactDrag.setContentDescription("Drag guided tour card");
            compactDrag.setOnTouchListener(card::handleDrag);
            compactTop.addView(compactDrag, new LinearLayout.LayoutParams(
                    dp(activity, 48), dp(activity, 52)));

            TextView compactNext = new TextView(activity);
            String compactAction = requiredAction == null || requiredAction.trim().isEmpty()
                    ? "Continue the guided tour" : requiredAction.trim();
            compactNext.setText("ACTION: " + compactAction);
            compactNext.setTextSize(11.5f);
            compactNext.setTextColor(Color.rgb(28, 50, 52));
            compactNext.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            compactNext.setMaxLines(3);
            compactNext.setGravity(Gravity.CENTER_VERTICAL);
            compactNext.setContentDescription("Guided tour action: " + compactAction);
            LinearLayout.LayoutParams compactNextParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            compactNextParams.setMargins(dp(activity, 4), 0, dp(activity, 4), 0);
            compactTop.addView(compactNext, compactNextParams);
            compact.addView(compactTop, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout compactActions = new LinearLayout(activity);
            compactActions.setOrientation(LinearLayout.HORIZONTAL);
            compactActions.setGravity(Gravity.CENTER_VERTICAL);
            compactActions.setPadding(0, dp(activity, 2), 0, 0);

            if (backAction != null) {
                Button compactBack = button(activity, "Back");
                compactBack.setContentDescription("Go back one guided tour step");
                compactBack.setOnClickListener(v -> backAction.run());
                compactActions.addView(compactBack, new LinearLayout.LayoutParams(
                        0, dp(activity, 48), 1f));
            }

            if (hasPrimary) {
                Button compactPrimary = button(activity, primaryLabel.trim());
                compactPrimary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                compactPrimary.setOnClickListener(v -> {
                    if (primaryAction != null) primaryAction.run();
                });
                LinearLayout.LayoutParams compactPrimaryParams = new LinearLayout.LayoutParams(
                        0, dp(activity, 48), 1f);
                if (backAction != null) compactPrimaryParams.setMargins(dp(activity, 5), 0, 0, 0);
                compactActions.addView(compactPrimary, compactPrimaryParams);
            }

            Button compactSkip = button(activity, "Skip");
            compactSkip.setContentDescription("Skip guided tour step");
            compactSkip.setOnClickListener(v -> { if (skipAction != null) skipAction.run(); });
            LinearLayout.LayoutParams compactSkipParams = new LinearLayout.LayoutParams(
                    0, dp(activity, 48), 1f);
            if (backAction != null || hasPrimary) compactSkipParams.setMargins(dp(activity, 5), 0, 0, 0);
            compactActions.addView(compactSkip, compactSkipParams);

            Button compactExit = button(activity, "Exit");
            compactExit.setContentDescription("Exit guided tour");
            compactExit.setOnClickListener(v -> {
                if (exitAction != null) exitAction.run();
                else GuidedTourState.exit(activity);
                clear(activity);
            });
            LinearLayout.LayoutParams compactExitParams = new LinearLayout.LayoutParams(
                    0, dp(activity, 48), 1f);
            compactExitParams.setMargins(dp(activity, 5), 0, 0, 0);
            compactActions.addView(compactExit, compactExitParams);
            compact.addView(compactActions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            card.addView(compact, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.setCompactView(compact);
        }

        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
        int maxWidth = Math.max(dp(activity, 236), Math.round(screenWidth * 0.76f));
        int width = Math.min(dp(activity, 340), Math.min(maxWidth, screenWidth - dp(activity, 16)));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        params.setMargins(dp(activity, 8), dp(activity, 8), 0, 0);
        root.addView(card, params);
        card.bringToFront();
        card.placeForCurrentStep();
        highlight(target);
        if (target != null) {
            target.post(() -> {
                target.requestFocus();
                target.announceForAccessibility(requiredAction == null
                        ? "Guided tour target" : requiredAction);
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
        private final View target;
        private final int touchSlop;
        private float dragDownRawX;
        private float dragDownRawY;
        private int dragStartLeft;
        private int dragStartTop;
        private boolean dragging;
        private boolean placementRunning;
        private boolean denseMode;
        private final ArrayList<View> normalViews = new ArrayList<>();
        private View compactView;
        private Rect lastTargetRect;
        private Rect lastAvoidRegion;
        private ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener;

        DraggableCard(Activity activity, FrameLayout root, View target) {
            super(activity);
            this.root = root;
            this.target = target;
            this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        }

        void addNormalView(View view) {
            if (view != null) normalViews.add(view);
        }

        void setCompactView(View compactView) {
            this.compactView = compactView;
        }

        /** Same gesture model as the working draggable map-context rows. */
        boolean handleDrag(View touchedView, MotionEvent event) {
            if (event == null || root == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    FrameLayout.LayoutParams start = (FrameLayout.LayoutParams) getLayoutParams();
                    dragDownRawX = event.getRawX();
                    dragDownRawY = event.getRawY();
                    dragStartLeft = start.leftMargin;
                    dragStartTop = start.topMargin;
                    dragging = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - dragDownRawX;
                    float dy = event.getRawY() - dragDownRawY;
                    if (!dragging && Math.hypot(dx, dy) >= Math.max(1, touchSlop)) {
                        dragging = true;
                        touchedView.setPressed(false);
                        ViewParent parent = touchedView.getParent();
                        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        moveTo(dragStartLeft + Math.round(dx),
                                dragStartTop + Math.round(dy), false);
                        return true;
                    }
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        float upDx = event.getRawX() - dragDownRawX;
                        float upDy = event.getRawY() - dragDownRawY;
                        moveTo(dragStartLeft + Math.round(upDx),
                                dragStartTop + Math.round(upDy), true);
                        touchedView.setPressed(false);
                        dragging = false;
                        return true;
                    }
                    return false;
                default:
                    return dragging;
            }
        }

        void placeForCurrentStep() {
            post(() -> {
                if (getWidth() <= 0 || getHeight() <= 0 || root.getWidth() <= 0 || root.getHeight() <= 0) {
                    postDelayed(this::placeForCurrentStep, 40L);
                    return;
                }
                Rect safe = visibleSafeRect();
                Rect avoidRegion = findContainingUiRegion(target);
                if (!denseMode && compactView != null && shouldUseDenseMode(safe, avoidRegion)) {
                    denseMode = true;
                    setPadding(dp(this, 6), dp(this, 4), dp(this, 6), dp(this, 4));
                    for (View normal : normalViews) normal.setVisibility(View.GONE);
                    compactView.setVisibility(View.VISIBLE);
                    FrameLayout.LayoutParams compactParams = (FrameLayout.LayoutParams) getLayoutParams();
                    compactParams.width = Math.max(dp(this, 236), root.getWidth() - dp(this, 16));
                    setLayoutParams(compactParams);
                    requestLayout();
                    postDelayed(this::placeForCurrentStep, 40L);
                    return;
                }
                chooseSafePosition(true);
                installPlacementTracking();
            });
        }

        private boolean shouldUseDenseMode(Rect safe, Rect avoidRegion) {
            if (safe == null || avoidRegion == null) return false;
            return avoidRegion.width() >= safe.width() * 0.78f
                    && avoidRegion.height() >= safe.height() * 0.30f;
        }

        private void installPlacementTracking() {
            if (globalLayoutListener != null || root == null) return;
            globalLayoutListener = () -> {
                if (dragging || placementRunning || getParent() == null) return;
                post(this::ensureStillSafe);
            };
            root.getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (globalLayoutListener != null && root != null
                    && root.getViewTreeObserver().isAlive()) {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(globalLayoutListener);
            }
            globalLayoutListener = null;
            super.onDetachedFromWindow();
        }

        private void ensureStillSafe() {
            if (dragging || placementRunning || getParent() == null) return;
            Rect current = currentRect();
            Rect targetRect = rectInRoot(target);
            Rect avoidRegion = findContainingUiRegion(target);
            boolean geometryChanged = !sameRect(lastTargetRect, targetRect)
                    || !sameRect(lastAvoidRegion, avoidRegion);
            if (intersectsExpanded(current, targetRect, dp(this, 12)) || geometryChanged) {
                // User drag is a preference, not permission for the next required control to be hidden.
                chooseSafePosition(false);
            }
        }

        private void chooseSafePosition(boolean considerSavedPosition) {
            if (placementRunning || getWidth() <= 0 || getHeight() <= 0) return;
            placementRunning = true;
            try {
                Rect safe = visibleSafeRect();
                if (safe.width() < getWidth() || safe.height() < getHeight()) {
                    moveTo(safe.left, safe.top, false);
                    return;
                }

                Rect targetRect = rectInRoot(target);
                Rect avoidRegion = findContainingUiRegion(target);
                lastTargetRect = targetRect == null ? null : new Rect(targetRect);
                lastAvoidRegion = avoidRegion == null ? null : new Rect(avoidRegion);
                ArrayList<Rect> obstacles = new ArrayList<>();
                collectInteractiveObstacles(root, obstacles);

                ArrayList<Rect> candidates = new ArrayList<>();
                addGridCandidates(candidates, safe);
                addTargetCandidates(candidates, safe, targetRect, avoidRegion);
                addObstacleCandidates(candidates, safe, obstacles);

                Rect savedRect = null;
                if (considerSavedPosition && GuidedTourState.hasCoachPosition(getContext())) {
                    int availableW = Math.max(0, safe.width() - getWidth());
                    int availableH = Math.max(0, safe.height() - getHeight());
                    int savedLeft = safe.left + Math.round(GuidedTourState.coachX(getContext()) * availableW);
                    int savedTop = safe.top + Math.round(GuidedTourState.coachY(getContext()) * availableH);
                    savedRect = rectAt(savedLeft, savedTop);
                    candidates.add(0, savedRect);
                }

                Rect best = null;
                double bestScore = Double.POSITIVE_INFINITY;
                for (Rect candidate : candidates) {
                    Rect bounded = clampRect(candidate, safe);
                    double score = placementScore(bounded, targetRect, avoidRegion, obstacles);
                    if (savedRect != null && candidate != savedRect) {
                        score += Math.hypot(bounded.left - savedRect.left,
                                bounded.top - savedRect.top) * 0.15d;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        best = bounded;
                    }
                }
                if (best == null) best = new Rect(safe.left, safe.top,
                        safe.left + getWidth(), safe.top + getHeight());
                moveTo(best.left, best.top, false);
            } finally {
                placementRunning = false;
            }
        }

        private void addGridCandidates(List<Rect> out, Rect safe) {
            int gap = dp(this, 8);
            int left = safe.left;
            int centerX = safe.left + Math.max(0, (safe.width() - getWidth()) / 2);
            int right = safe.right - getWidth();
            int top = safe.top;
            int centerY = safe.top + Math.max(0, (safe.height() - getHeight()) / 2);
            int bottom = safe.bottom - getHeight();
            int[] xs = new int[]{left, centerX, right};
            int[] ys = new int[]{top, centerY, bottom};
            for (int y : ys) {
                for (int x : xs) out.add(rectAt(x, y));
            }
            // Small offsets keep the coach from visually sticking to an edge or system bar.
            out.add(rectAt(left + gap, top + gap));
            out.add(rectAt(right - gap, top + gap));
            out.add(rectAt(left + gap, bottom - gap));
            out.add(rectAt(right - gap, bottom - gap));
        }

        private void addTargetCandidates(List<Rect> out, Rect safe, Rect targetRect, Rect avoidRegion) {
            Rect anchor = avoidRegion != null ? avoidRegion : targetRect;
            if (anchor == null) return;
            int gap = dp(this, 12);
            int centeredX = anchor.centerX() - getWidth() / 2;
            int centeredY = anchor.centerY() - getHeight() / 2;
            out.add(clampRect(rectAt(centeredX, anchor.top - getHeight() - gap), safe));
            out.add(clampRect(rectAt(centeredX, anchor.bottom + gap), safe));
            out.add(clampRect(rectAt(anchor.left - getWidth() - gap, centeredY), safe));
            out.add(clampRect(rectAt(anchor.right + gap, centeredY), safe));
        }

        private void addObstacleCandidates(List<Rect> out, Rect safe, List<Rect> obstacles) {
            if (obstacles == null || obstacles.isEmpty()) return;
            int gap = dp(this, 10);
            int leftX = safe.left;
            int centerX = safe.left + Math.max(0, (safe.width() - getWidth()) / 2);
            int rightX = safe.right - getWidth();
            int[] xs = new int[]{leftX, centerX, rightX};
            for (Rect obstacle : obstacles) {
                if (obstacle == null) continue;
                int above = obstacle.top - getHeight() - gap;
                int below = obstacle.bottom + gap;
                for (int x : xs) {
                    out.add(clampRect(rectAt(x, above), safe));
                    out.add(clampRect(rectAt(x, below), safe));
                }
            }
        }

        private double placementScore(Rect cardRect, Rect targetRect, Rect avoidRegion,
                                      List<Rect> obstacles) {
            double score = 0d;
            if (targetRect != null) {
                Rect expandedTarget = new Rect(targetRect);
                int pad = dp(this, 12);
                expandedTarget.inset(-pad, -pad);
                // The required action is inviolable: the coach never wins this collision.
                score += overlapArea(cardRect, expandedTarget) * 1000000000d;
            }
            if (avoidRegion != null) {
                // Panel/background overlap is preferable to covering a real tappable control.
                score += overlapArea(cardRect, avoidRegion) * 100d;
            }
            if (obstacles != null) {
                for (Rect obstacle : obstacles) {
                    if (obstacle == null) continue;
                    score += overlapArea(cardRect, obstacle) * 1000000d;
                }
            }
            // Prefer screen edges over the center when obstruction is otherwise equal; the map
            // remains easier to read and the instructional card behaves like a docked helper.
            Rect safe = visibleSafeRect();
            int edgeDistance = Math.min(
                    Math.min(Math.abs(cardRect.left - safe.left), Math.abs(safe.right - cardRect.right)),
                    Math.min(Math.abs(cardRect.top - safe.top), Math.abs(safe.bottom - cardRect.bottom)));
            score += edgeDistance * 0.35d;
            return score;
        }

        private void collectInteractiveObstacles(View view, List<Rect> out) {
            if (view == null || view == this || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f) {
                return;
            }
            if (view != root && (view.isClickable() || view.isLongClickable())) {
                Rect rect = rectInRoot(view);
                if (rect != null && rect.width() > 0 && rect.height() > 0) {
                    long rootArea = (long) Math.max(1, root.getWidth()) * Math.max(1, root.getHeight());
                    long rectArea = (long) rect.width() * rect.height();
                    if (rectArea < rootArea * 0.55d) out.add(rect);
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child == this || isDescendantOf(child, this)) continue;
                    collectInteractiveObstacles(child, out);
                }
            }
        }

        private Rect findContainingUiRegion(View targetView) {
            if (targetView == null || root == null) return null;
            Rect best = null;
            long bestArea = 0L;
            long rootArea = (long) Math.max(1, root.getWidth()) * Math.max(1, root.getHeight());
            View current = targetView;
            while (current != null && current != root) {
                if (current.getVisibility() == View.VISIBLE
                        && current.getBackground() != null
                        && current.getWidth() >= dp(this, 120)
                        && current.getHeight() >= dp(this, 76)) {
                    Rect rect = rectInRoot(current);
                    if (rect != null) {
                        long area = (long) rect.width() * rect.height();
                        if (area > bestArea && area < rootArea * 0.86d) {
                            best = rect;
                            bestArea = area;
                        }
                    }
                }
                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            if (best != null) {
                int pad = dp(this, 6);
                best.inset(-pad, -pad);
            }
            return best;
        }

        private Rect visibleSafeRect() {
            int margin = dp(this, 8);
            Rect screenVisible = new Rect();
            root.getWindowVisibleDisplayFrame(screenVisible);
            int[] rootLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            int left = Math.max(0, screenVisible.left - rootLocation[0]) + margin;
            int top = Math.max(0, screenVisible.top - rootLocation[1]) + margin;
            int right = Math.min(root.getWidth(), screenVisible.right - rootLocation[0]) - margin;
            int bottom = Math.min(root.getHeight(), screenVisible.bottom - rootLocation[1]) - margin;
            if (right <= left) { left = margin; right = Math.max(left + 1, root.getWidth() - margin); }
            if (bottom <= top) { top = margin; bottom = Math.max(top + 1, root.getHeight() - margin); }
            return new Rect(left, top, right, bottom);
        }

        private Rect rectInRoot(View view) {
            if (view == null || root == null || view.getVisibility() != View.VISIBLE) return null;
            Rect screen = new Rect();
            if (!view.getGlobalVisibleRect(screen) || screen.width() <= 0 || screen.height() <= 0) return null;
            int[] rootLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            screen.offset(-rootLocation[0], -rootLocation[1]);
            return screen;
        }

        private Rect currentRect() {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) getLayoutParams();
            return rectAt(p.leftMargin, p.topMargin);
        }

        private Rect rectAt(int left, int top) {
            return new Rect(left, top, left + getWidth(), top + getHeight());
        }

        private Rect clampRect(Rect source, Rect safe) {
            int left = Math.max(safe.left, Math.min(source.left, safe.right - getWidth()));
            int top = Math.max(safe.top, Math.min(source.top, safe.bottom - getHeight()));
            return rectAt(left, top);
        }


        private boolean sameRect(Rect left, Rect right) {
            if (left == null || right == null) return left == right;
            return left.equals(right);
        }

        private boolean intersectsExpanded(Rect a, Rect b, int pad) {
            if (a == null || b == null) return false;
            Rect expanded = new Rect(b);
            expanded.inset(-pad, -pad);
            return Rect.intersects(a, expanded);
        }

        private long overlapArea(Rect a, Rect b) {
            if (a == null || b == null) return 0L;
            int left = Math.max(a.left, b.left);
            int top = Math.max(a.top, b.top);
            int right = Math.min(a.right, b.right);
            int bottom = Math.min(a.bottom, b.bottom);
            if (right <= left || bottom <= top) return 0L;
            return (long) (right - left) * (bottom - top);
        }

        private boolean isDescendantOf(View candidate, View possibleAncestor) {
            if (candidate == null || possibleAncestor == null) return false;
            ViewParent parent = candidate.getParent();
            while (parent instanceof View) {
                if (parent == possibleAncestor) return true;
                parent = parent.getParent();
            }
            return false;
        }

        private void moveTo(int left, int top, boolean save) {
            Rect safe = visibleSafeRect();
            int maxLeft = Math.max(safe.left, safe.right - getWidth());
            int maxTop = Math.max(safe.top, safe.bottom - getHeight());
            int safeLeft = Math.max(safe.left, Math.min(left, maxLeft));
            int safeTop = Math.max(safe.top, Math.min(top, maxTop));
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) getLayoutParams();
            p.gravity = Gravity.TOP | Gravity.START;
            p.leftMargin = safeLeft;
            p.topMargin = safeTop;
            p.rightMargin = 0;
            p.bottomMargin = 0;
            setLayoutParams(p);
            if (save) {
                int availableW = Math.max(0, safe.width() - getWidth());
                int availableH = Math.max(0, safe.height() - getHeight());
                float x = availableW <= 0 ? 0f : (safeLeft - safe.left) / (float) availableW;
                float y = availableH <= 0 ? 0f : (safeTop - safe.top) / (float) availableH;
                GuidedTourState.saveCoachPosition(getContext(), x, y);
            }
        }
    }
}
