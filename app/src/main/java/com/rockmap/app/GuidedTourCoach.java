package com.rockmap.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
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
    private static WeakReference<ViewGroup> activeCoachRoot = new WeakReference<>(null);
    private static WeakReference<View> activeTouchInterceptTarget = new WeakReference<>(null);
    private static WeakReference<DialogCoachHost> activeDialogCoachHost = new WeakReference<>(null);
    private static long highlightGeneration;
    private static long coachGeneration;

    private GuidedTourCoach() {}

    /**
     * Create a coach host for an AlertDialog without changing the AlertDialog window at all.
     * The coach itself is shown in a small non-modal PopupWindow above the real dialog. Because
     * the popup window is only as large as the coach card, taps everywhere else continue to reach
     * the untouched dialog. This deliberately avoids resizing/reparenting the dialog, which can
     * destroy AlertDialog layout and background geometry on Samsung/Android builds.
     */
    public static FrameLayout prepareDialogHost(Activity activity, AlertDialog dialog) {
        if (activity == null || dialog == null || dialog.getWindow() == null) return null;
        return new DialogCoachHost(activity, dialog);
    }

    public static void clear(Activity activity) {
        coachGeneration++;
        clearHighlight();
        ViewGroup activeRoot = activeCoachRoot.get();
        if (activeRoot != null) {
            View old = activeRoot.findViewWithTag(TAG);
            if (old != null) activeRoot.removeView(old);
        }
        View touchInterceptTarget = activeTouchInterceptTarget.get();
        if (touchInterceptTarget != null) touchInterceptTarget.setOnTouchListener(null);
        activeTouchInterceptTarget = new WeakReference<>(null);
        DialogCoachHost dialogHost = activeDialogCoachHost.get();
        if (dialogHost != null) dialogHost.dismissPopup();
        activeDialogCoachHost = new WeakReference<>(null);
        activeCoachRoot = new WeakReference<>(null);
        if (activity == null) return;
        ViewGroup activityRoot = activity.findViewById(android.R.id.content);
        if (activityRoot != null && activityRoot != activeRoot) {
            View old = activityRoot.findViewWithTag(TAG);
            if (old != null) activityRoot.removeView(old);
        }
    }

    public static void show(Activity activity, int step, int total, String title, String message,
                            String requiredAction, View target,
                            Runnable backAction,
                            String primaryLabel, Runnable primaryAction,
                            Runnable skipAction, Runnable exitAction) {
        show(activity, null, step, total, title, message, requiredAction, target,
                backAction, primaryLabel, primaryAction, skipAction, exitAction);
    }

    /** Same coach UI inside an alternate window root such as an AlertDialog. */
    public static void show(Activity activity, FrameLayout hostRoot,
                            int step, int total, String title, String message,
                            String requiredAction, View target,
                            Runnable backAction,
                            String primaryLabel, Runnable primaryAction,
                            Runnable skipAction, Runnable exitAction) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        long requestGeneration = ++coachGeneration;
        // Do not tear down the current coach until the destination target really exists. Several
        // tour actions rebuild panels or cross Activity boundaries asynchronously; clearing first
        // creates a visible dead period and can strand the tour if the replacement target arrives
        // a frame later. Keep the prior coach in place while the next real target is resolved.
        View resolvedTarget = resolveEquivalentTarget(activity, target);
        if (resolvedTarget != null) target = resolvedTarget;
        if (target != null && !targetReady(target)) {
            requestTargetVisibility(target);
            waitForTargetAndShow(activity, hostRoot, step, total, title, message, requiredAction,
                    target, backAction, primaryLabel, primaryAction, skipAction, exitAction,
                    requestGeneration, 0);
            return;
        }
        clear(activity);
        FrameLayout root = hostRoot;
        if (root == null) {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            root = (FrameLayout) content;
        }
        activeCoachRoot = new WeakReference<>(root);
        if (root instanceof DialogCoachHost) {
            activeDialogCoachHost = new WeakReference<>((DialogCoachHost) root);
        }

        DraggableCard card = new DraggableCard(activity, root, target);
        card.setTag(TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(activity, 12), dp(activity, 9), dp(activity, 12), dp(activity, 9));
        card.setElevation(dp(activity, 96));
        card.setClickable(true);
        card.setFocusable(true);
        // Guided-tour UI uses a warm instructional surface so it cannot be mistaken for one of
        // RockMap's normal white/gray map or Research panels. Keep the contrast clear but subdued.
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(252, 255, 247, 219));
        background.setStroke(dp(activity, 2), Color.rgb(183, 126, 22));
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
        progress.setTextColor(Color.rgb(126, 78, 0));
        progress.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(progress, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View drag = RockMapDragHandle.labeled(activity, Color.rgb(104, 75, 25),
                card::handleDrag, "Drag guided tour card");
        header.addView(drag, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40)));
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

            LinearLayout compactLead = new LinearLayout(activity);
            compactLead.setOrientation(LinearLayout.HORIZONTAL);
            compactLead.setGravity(Gravity.CENTER_VERTICAL);
            compactLead.setClickable(true);
            compactLead.setFocusable(true);
            compactLead.setContentDescription("Drag guided tour card");
            compactLead.setOnTouchListener(card::handleDrag);

            TextView compactProgress = new TextView(activity);
            compactProgress.setText(Math.max(1, step) + "/" + Math.max(step, total));
            compactProgress.setTextSize(10f);
            compactProgress.setTextColor(Color.rgb(126, 78, 0));
            compactProgress.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            compactProgress.setGravity(Gravity.CENTER);
            compactLead.addView(compactProgress, new LinearLayout.LayoutParams(
                    dp(activity, 34), dp(activity, 52)));
            View compactDrag = RockMapDragHandle.compact(activity, Color.rgb(104, 75, 25),
                    card::handleDrag, "Drag guided tour card");
            compactLead.addView(compactDrag, new LinearLayout.LayoutParams(
                    dp(activity, 36), dp(activity, 52)));
            compactTop.addView(compactLead, new LinearLayout.LayoutParams(
                    dp(activity, 70), dp(activity, 52)));

            TextView compactTitle = new TextView(activity);
            compactTitle.setText(title == null ? "" : title);
            compactTitle.setTextSize(13f);
            compactTitle.setTextColor(Color.rgb(30, 36, 38));
            compactTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            compactTitle.setGravity(Gravity.CENTER_VERTICAL);
            compactTitle.setMaxLines(2);
            LinearLayout.LayoutParams compactTitleParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            compactTitleParams.setMargins(dp(activity, 4), 0, dp(activity, 4), 0);
            compactTop.addView(compactTitle, compactTitleParams);
            compact.addView(compactTop, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView compactBody = new TextView(activity);
            compactBody.setText(message == null ? "" : message);
            compactBody.setTextSize(11.5f);
            compactBody.setTextColor(Color.rgb(66, 75, 77));
            compactBody.setLineSpacing(0f, 1.04f);
            compactBody.setPadding(dp(activity, 4), 0, dp(activity, 4), dp(activity, 2));
            compact.addView(compactBody, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView compactNext = new TextView(activity);
            String compactAction = requiredAction == null || requiredAction.trim().isEmpty()
                    ? "Continue the guided tour" : requiredAction.trim();
            compactNext.setText("ACTION: " + compactAction);
            compactNext.setTextSize(11.5f);
            compactNext.setTextColor(Color.rgb(28, 50, 52));
            compactNext.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            compactNext.setMaxLines(4);
            compactNext.setPadding(dp(activity, 4), dp(activity, 2), dp(activity, 4), 0);
            compactNext.setContentDescription("Guided tour action: " + compactAction);
            compact.addView(compactNext, new LinearLayout.LayoutParams(
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

        int screenWidth = activitySafeScreenRect(activity).width();
        int maxWidth = Math.max(dp(activity, 236), Math.round(screenWidth * 0.76f));
        int width = Math.min(dp(activity, 340), Math.min(maxWidth, screenWidth - dp(activity, 16)));
        FrameLayout.LayoutParams params;
        if (root instanceof DialogCoachHost) {
            params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START);
            params.setMargins(0, 0, 0, 0);
            root.addView(card, params);
            DialogCoachHost dialogHost = (DialogCoachHost) root;
            int maxHeight = Math.max(dp(activity, 160), dialogHost.screenSafeRect().height());
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
            dialogHost.showPopup(width, Math.max(dp(activity, 120), card.getMeasuredHeight()), card);
        } else {
            params = new FrameLayout.LayoutParams(
                    width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            params.setMargins(dp(activity, 8), dp(activity, 8), 0, 0);
            root.addView(card, params);
        }
        installInformationalTargetInterceptor(activity, root, target, requiredAction, primaryAction);
        card.setElevation(dp(activity, 96));
        card.bringToFront();
        card.placeForCurrentStep();
        highlight(target);
        if (target != null) {
            final View accessibilityTarget = target;
            accessibilityTarget.post(() -> {
                accessibilityTarget.requestFocus();
                accessibilityTarget.announceForAccessibility(requiredAction == null
                        ? "Guided tour target" : requiredAction);
            });
        }
    }

    private static void waitForTargetAndShow(Activity activity, FrameLayout hostRoot,
                                                 int step, int total, String title, String message,
                                                 String requiredAction, View target,
                                                 Runnable backAction, String primaryLabel,
                                                 Runnable primaryAction, Runnable skipAction,
                                                 Runnable exitAction, long generation, int attempt) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || target == null || generation != coachGeneration || attempt >= 250) return;
        View scheduler = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (scheduler == null) scheduler = target;
        scheduler.postDelayed(() -> {
            if (generation != coachGeneration || activity.isFinishing() || activity.isDestroyed()) return;
            View liveTarget = resolveEquivalentTarget(activity, target);
            if (liveTarget == null) liveTarget = target;
            if (!targetReady(liveTarget)) requestTargetVisibility(liveTarget);
            if (targetReady(liveTarget)) {
                show(activity, hostRoot, step, total, title, message, requiredAction, liveTarget,
                        backAction, primaryLabel, primaryAction, skipAction, exitAction);
            } else {
                waitForTargetAndShow(activity, hostRoot, step, total, title, message,
                        requiredAction, liveTarget, backAction, primaryLabel, primaryAction,
                        skipAction, exitAction, generation, attempt + 1);
            }
        }, 40L);
    }

    private static void requestTargetVisibility(View target) {
        if (target == null) return;
        target.post(() -> {
            if (!target.isAttachedToWindow()) return;
            Rect local = new Rect(0, 0, Math.max(1, target.getWidth()), Math.max(1, target.getHeight()));
            target.requestRectangleOnScreen(local, true);

            // requestRectangleOnScreen is advisory and some nested Android layouts do not move
            // until a later frame. If this target lives in a ScrollView, explicitly reveal it as
            // well so a tour step cannot disappear merely because its real control starts below
            // the fold (for example Tracks → Back to map).
            ViewParent parent = target.getParent();
            while (parent instanceof View) {
                if (parent instanceof ScrollView) {
                    ScrollView scroll = (ScrollView) parent;
                    Rect inScroll = new Rect(0, 0, Math.max(1, target.getWidth()),
                            Math.max(1, target.getHeight()));
                    scroll.offsetDescendantRectToMyCoords(target, inScroll);
                    int viewport = Math.max(1, scroll.getHeight());
                    int desiredTop = Math.max(0, inScroll.centerY() - viewport / 2);
                    scroll.scrollTo(scroll.getScrollX(), desiredTop);
                    break;
                }
                parent = parent.getParent();
            }
        });
    }

    private static boolean targetReady(View target) {
        if (target == null || !target.isAttachedToWindow() || !target.isShown()
                || target.getWidth() <= 0 || target.getHeight() <= 0) return false;
        Rect visible = new Rect();
        return target.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0;
    }

    /**
     * Panel rebuilds can replace a target View while preserving its stable tag or accessibility
     * identity. Resolve that replacement before giving up so async Research/Field transitions do
     * not depend on pausing and resuming the app to rediscover the control.
     */
    private static View resolveEquivalentTarget(Activity activity, View original) {
        if (original == null || targetReady(original) || activity == null) return original;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return original;
        Object tag = original.getTag();
        if (tag != null) {
            View tagged = root.findViewWithTag(tag);
            if (tagged != null) return tagged;
        }
        CharSequence description = original.getContentDescription();
        if (description != null && description.length() > 0) {
            View described = findByContentDescription(root, description.toString());
            if (described != null) return described;
        }
        return original;
    }

    private static View findByContentDescription(View view, String description) {
        if (view == null || description == null || description.isEmpty()) return null;
        CharSequence own = view.getContentDescription();
        if (own != null && description.contentEquals(own)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByContentDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void installInformationalTargetInterceptor(Activity activity, FrameLayout root,
                                                               View target, String requiredAction,
                                                               Runnable primaryAction) {
        if (activity == null || root == null || target == null || primaryAction == null
                || requiredAction == null
                || !requiredAction.trim().toLowerCase().startsWith("review")) return;
        // Intercept on the real target rather than placing a second view over it. This follows the
        // target automatically if a ScrollView or relayout moves it, and guarantees that a tour
        // informational tap advances without executing the underlying destructive/navigation action.
        target.setOnTouchListener((v, event) -> {
            if (event == null) return true;
            if (event.getActionMasked() == MotionEvent.ACTION_UP) primaryAction.run();
            return true;
        });
        activeTouchInterceptTarget = new WeakReference<>(target);
    }

    private static void highlight(View target) {
        clearHighlight();
        if (target == null) return;
        final long generation = highlightGeneration;
        target.post(() -> applyHighlightWhenReady(target, generation, 0));
    }

    private static void applyHighlightWhenReady(View target, long generation, int attempt) {
        if (target == null || generation != highlightGeneration) return;
        if (target.getWidth() <= 0 || target.getHeight() <= 0 || !target.isShown()) {
            if (attempt < 12) {
                target.postDelayed(() -> applyHighlightWhenReady(target, generation, attempt + 1), 40L);
            }
            return;
        }
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setStroke(dp(target, 4), Color.rgb(0, 112, 121));
        outline.setCornerRadius(dp(target, 8));
        outline.setBounds(new Rect(0, 0, target.getWidth(), target.getHeight()));
        target.getOverlay().add(outline);
        highlightedView = new WeakReference<>(target);
        highlightedDrawable = outline;
    }

    private static void clearHighlight() {
        highlightGeneration++;
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

    private static Rect activitySafeScreenRect(Activity activity) {
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        Rect safe = new Rect(0, 0, width, height);
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (decor != null) {
            Rect visible = new Rect();
            decor.getWindowVisibleDisplayFrame(visible);
            if (visible.width() > 0 && visible.height() > 0) safe.intersect(visible);
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) {
                safe.left = Math.max(safe.left, insets.getSystemWindowInsetLeft());
                safe.top = Math.max(safe.top, insets.getSystemWindowInsetTop());
                safe.right = Math.min(safe.right, width - insets.getSystemWindowInsetRight());
                safe.bottom = Math.min(safe.bottom, height - insets.getSystemWindowInsetBottom());
            }
        }
        if (safe.width() <= 0 || safe.height() <= 0) {
            safe.set(0, 0, Math.max(1, width), Math.max(1, height));
        }
        return safe;
    }

    private static final class DialogCoachHost extends FrameLayout {
        private final Activity activity;
        private final AlertDialog sourceDialog;
        private final PopupWindow popup;
        private int popupX;
        private int popupY;
        private int popupWidth;
        private int popupHeight;

        DialogCoachHost(Activity activity, AlertDialog sourceDialog) {
            super(activity);
            this.activity = activity;
            this.sourceDialog = sourceDialog;
            setClipChildren(false);
            setClipToPadding(false);
            setClickable(false);
            setFocusable(false);
            popup = new PopupWindow(this, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, false);
            popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            popup.setFocusable(false);
            popup.setTouchable(true);
            popup.setOutsideTouchable(false);
            popup.setClippingEnabled(false);
            popup.setElevation(dp(activity, 24));

            View anchor = sourceDialog.getWindow() == null ? null : sourceDialog.getWindow().getDecorView();
            if (anchor != null) {
                anchor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override public void onViewAttachedToWindow(View v) {}
                    @Override public void onViewDetachedFromWindow(View v) { dismissPopup(); }
                });
            }
        }

        void showPopup(int width, int height, View card) {
            if (sourceDialog.getWindow() == null || !sourceDialog.isShowing()) return;
            Rect safe = screenSafeRect();
            popupWidth = Math.min(width, Math.max(1, safe.width()));
            popupHeight = Math.min(height, Math.max(1, safe.height()));
            popup.setWidth(popupWidth);
            popup.setHeight(popupHeight);
            popupX = safe.left + dp(activity, 8);
            popupY = safe.top + dp(activity, 8);
            View anchor = sourceDialog.getWindow().getDecorView();
            if (!popup.isShowing()) {
                popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, popupX, popupY);
            } else {
                popup.update(popupX, popupY, popupWidth, popupHeight);
            }
            if (card != null) {
                card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
                    int h = Math.max(dp(activity, 1), b - t);
                    if (!popup.isShowing() || h == popupHeight) return;
                    Rect currentSafe = screenSafeRect();
                    popupHeight = Math.min(h, Math.max(1, currentSafe.height()));
                    popupY = Math.max(currentSafe.top,
                            Math.min(popupY, currentSafe.bottom - popupHeight));
                    popup.update(popupX, popupY, popupWidth, popupHeight);
                });
            }
        }

        void movePopup(int left, int top, int width, int height) {
            Rect safe = screenSafeRect();
            popupWidth = Math.min(Math.max(1, width), Math.max(1, safe.width()));
            popupHeight = Math.min(Math.max(1, height), Math.max(1, safe.height()));
            popupX = Math.max(safe.left, Math.min(left, safe.right - popupWidth));
            popupY = Math.max(safe.top, Math.min(top, safe.bottom - popupHeight));
            if (popup.isShowing()) popup.update(popupX, popupY, popupWidth, popupHeight);
        }

        Rect currentPopupRect(int width, int height) {
            int w = width > 0 ? width : popupWidth;
            int h = height > 0 ? height : popupHeight;
            return new Rect(popupX, popupY, popupX + w, popupY + h);
        }

        Rect dialogRect() {
            if (sourceDialog.getWindow() == null) return null;
            View decor = sourceDialog.getWindow().getDecorView();
            View panel = decor.findViewById(android.R.id.content);
            if (panel != null) {
                ViewParent parent = panel.getParent();
                while (parent instanceof View && parent != decor) {
                    panel = (View) parent;
                    parent = panel.getParent();
                }
            }
            if (panel == null || panel == decor) panel = decor;
            Rect rect = new Rect();
            if (!panel.getGlobalVisibleRect(rect) || rect.width() <= 0 || rect.height() <= 0) return null;
            return rect;
        }

        Rect screenSafeRect() {
            Rect safe = new Rect(activitySafeScreenRect(activity));
            int margin = dp(activity, 8);
            safe.inset(margin, margin);
            return safe;
        }

        void dismissPopup() {
            if (popup.isShowing()) popup.dismiss();
        }
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
                    dragDownRawX = event.getRawX();
                    dragDownRawY = event.getRawY();
                    if (root instanceof DialogCoachHost) {
                        Rect current = ((DialogCoachHost) root).currentPopupRect(getWidth(), getHeight());
                        dragStartLeft = current.left;
                        dragStartTop = current.top;
                    } else {
                        FrameLayout.LayoutParams start = (FrameLayout.LayoutParams) getLayoutParams();
                        dragStartLeft = start.leftMargin;
                        dragStartTop = start.topMargin;
                    }
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
                if (!denseMode && compactView != null
                        && (getWidth() > safe.width() || getHeight() > safe.height()
                        || shouldUseDenseMode(safe, avoidRegion))) {
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
            Rect safe = visibleSafeRect();
            boolean outsideUsableScreen = current == null || current.left < safe.left
                    || current.top < safe.top || current.right > safe.right || current.bottom > safe.bottom;
            if (outsideUsableScreen || intersectsExpanded(current, targetRect, dp(this, 12)) || geometryChanged) {
                // A saved/previous position is never permission to clip the coach under a system
                // bar or beyond the usable screen, and the required control must remain uncovered.
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
                    // Hard visual priority: if any candidate leaves both the required control and
                    // its containing UI clear, that candidate always wins over one that obstructs
                    // either. This keeps tour copy from competing with the interface it teaches.
                    if (targetRect != null && intersectsExpanded(bounded, targetRect, dp(this, 12))) {
                        score += 1.0e18d;
                    } else if (avoidRegion != null && Rect.intersects(bounded, avoidRegion)) {
                        score += 1.0e15d;
                    }
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
                if (targetRect != null && intersectsExpanded(best, targetRect, dp(this, 12))) {
                    if (!denseMode && compactView != null) {
                        denseMode = true;
                        setPadding(dp(this, 6), dp(this, 4), dp(this, 6), dp(this, 4));
                        for (View normal : normalViews) normal.setVisibility(View.GONE);
                        compactView.setVisibility(View.VISIBLE);
                        FrameLayout.LayoutParams compactParams = (FrameLayout.LayoutParams) getLayoutParams();
                        compactParams.width = Math.min(dp(this, 330), Math.max(dp(this, 236), root.getWidth() - dp(this, 16)));
                        setLayoutParams(compactParams);
                        requestLayout();
                        postDelayed(() -> chooseSafePosition(false), 40L);
                        return;
                    }
                    Rect forced = forcedTargetSafePosition(safe, targetRect);
                    if (forced != null) best = forced;
                }
                moveTo(best.left, best.top, false);
            } finally {
                placementRunning = false;
            }
        }

        private Rect forcedTargetSafePosition(Rect safe, Rect targetRect) {
            if (safe == null || targetRect == null) return null;
            int gap = dp(this, 12);
            int aboveSpace = targetRect.top - safe.top - gap;
            int belowSpace = safe.bottom - targetRect.bottom - gap;
            int leftSpace = targetRect.left - safe.left - gap;
            int rightSpace = safe.right - targetRect.right - gap;
            if (aboveSpace >= getHeight()) {
                return clampRect(rectAt(targetRect.centerX() - getWidth() / 2,
                        targetRect.top - getHeight() - gap), safe);
            }
            if (belowSpace >= getHeight()) {
                return clampRect(rectAt(targetRect.centerX() - getWidth() / 2,
                        targetRect.bottom + gap), safe);
            }
            if (leftSpace >= getWidth()) {
                return clampRect(rectAt(targetRect.left - getWidth() - gap,
                        targetRect.centerY() - getHeight() / 2), safe);
            }
            if (rightSpace >= getWidth()) {
                return clampRect(rectAt(targetRect.right + gap,
                        targetRect.centerY() - getHeight() / 2), safe);
            }
            return null;
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
                score += overlapArea(cardRect, avoidRegion) * 10000000d;
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
            if (root instanceof DialogCoachHost) {
                Rect dialog = ((DialogCoachHost) root).dialogRect();
                if (dialog != null) {
                    int pad = dp(this, 6);
                    dialog.inset(-pad, -pad);
                }
                return dialog;
            }
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
            if (root instanceof DialogCoachHost) {
                return ((DialogCoachHost) root).screenSafeRect();
            }
            Rect screenSafe = activitySafeScreenRect((Activity) getContext());
            int[] rootLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            int left = Math.max(0, screenSafe.left - rootLocation[0]);
            int top = Math.max(0, screenSafe.top - rootLocation[1]);
            int right = Math.min(root.getWidth(), screenSafe.right - rootLocation[0]);
            int bottom = Math.min(root.getHeight(), screenSafe.bottom - rootLocation[1]);
            int margin = dp(this, 8);
            left += margin; top += margin; right -= margin; bottom -= margin;
            if (right <= left) { left = margin; right = Math.max(left + 1, root.getWidth() - margin); }
            if (bottom <= top) { top = margin; bottom = Math.max(top + 1, root.getHeight() - margin); }
            return new Rect(left, top, right, bottom);
        }

        private Rect rectInRoot(View view) {
            if (view == null || root == null || view.getVisibility() != View.VISIBLE) return null;
            Rect screen = new Rect();
            if (!view.getGlobalVisibleRect(screen) || screen.width() <= 0 || screen.height() <= 0) return null;
            if (root instanceof DialogCoachHost) return screen;
            int[] rootLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            screen.offset(-rootLocation[0], -rootLocation[1]);
            return screen;
        }

        private Rect currentRect() {
            if (root instanceof DialogCoachHost) {
                return ((DialogCoachHost) root).currentPopupRect(getWidth(), getHeight());
            }
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
            if (root instanceof DialogCoachHost) {
                ((DialogCoachHost) root).movePopup(safeLeft, safeTop, getWidth(), getHeight());
            } else {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) getLayoutParams();
                p.gravity = Gravity.TOP | Gravity.START;
                p.leftMargin = safeLeft;
                p.topMargin = safeTop;
                p.rightMargin = 0;
                p.bottomMargin = 0;
                setLayoutParams(p);
            }
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
