package com.rockmap.app;

import android.app.Activity;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tour-debug UI invariant monitor.
 *
 * This class observes UI transitions; it does not own application state.  Callers remain
 * responsible for the requested state change.  The monitor adds correlation IDs, lifecycle
 * guards, next-frame verification and explicit invariant failures so asynchronous Android UI
 * problems are diagnosable without reconstructing them from unrelated log lines.
 */
public final class UiInvariantMonitor {
    public interface Guard {
        boolean isCurrent();
    }

    private static final String LIFE_RESUMED = "resumed";
    private static final String LIFE_PAUSED = "paused";
    private static final String LIFE_DESTROYED = "destroyed";
    private static final AtomicLong NEXT_TRANSITION = new AtomicLong(1L);
    private static final WeakHashMap<Activity, String> LIFECYCLE = new WeakHashMap<>();

    private UiInvariantMonitor() {}

    public static synchronized void onActivityResumed(Activity activity) {
        if (activity == null) return;
        LIFECYCLE.put(activity, LIFE_RESUMED);
    }

    public static synchronized void onActivityPaused(Activity activity) {
        if (activity == null) return;
        LIFECYCLE.put(activity, LIFE_PAUSED);
    }

    public static synchronized void onActivityDestroyed(Activity activity) {
        if (activity == null) return;
        LIFECYCLE.put(activity, LIFE_DESTROYED);
    }

    public static synchronized boolean isResumed(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        String state = LIFECYCLE.get(activity);
        // During the first onResume callback the lifecycle bridge can be one callback behind.
        // Unknown is therefore permitted; explicit paused/destroyed states are not.
        return !LIFE_PAUSED.equals(state) && !LIFE_DESTROYED.equals(state);
    }

    public static long begin(Activity activity, String type, String detail) {
        long id = NEXT_TRANSITION.getAndIncrement();
        TourDebugLog.mainTourAction(activity, "UI_TRANSITION_BEGIN",
                "id=" + id + " type=" + clean(type, 80) + " " + clean(detail, 700));
        if (!isResumed(activity)) {
            TourDebugLog.mainTourAction(activity, "UI_INVARIANT_FAIL",
                    "id=" + id + " invariant=activity_mutation_requires_live_activity"
                            + " life=" + lifecycle(activity));
        }
        return id;
    }

    public static void state(Activity activity, long id, String event, String detail) {
        TourDebugLog.mainTourAction(activity, clean(event, 60),
                "id=" + id + " " + clean(detail, 900));
    }

    public static boolean invariant(Activity activity, long id, String name,
                                    boolean condition, String detail) {
        TourDebugLog.mainTourAction(activity,
                condition ? "UI_INVARIANT_OK" : "UI_INVARIANT_FAIL",
                "id=" + id + " invariant=" + clean(name, 120)
                        + " " + clean(detail, 900));
        return condition;
    }

    public static void track(String event, String detail) {
        TourDebugLog.mapDiagnostic(clean(event, 60), clean(detail, 1200));
    }

    /**
     * Verify the requested target in the next Android pre-draw pass.  A pre-draw is a stronger
     * signal than View.isShown(): layout has completed for the frame and the target must also have
     * a non-empty global visible rect.  The callback is bounded and lifecycle/step guarded.
     */
    public static void verifyNextFrame(Activity activity,
                                       long transitionId,
                                       String label,
                                       View target,
                                       Guard guard,
                                       Runnable onSuccess,
                                       Runnable onFailure) {
        if (activity == null || target == null || activity.getWindow() == null) {
            state(activity, transitionId, "UI_FRAME_STALL",
                    "label=" + clean(label, 100) + " reason=missing_activity_or_target");
            if (onFailure != null) onFailure.run();
            return;
        }
        final View scheduler = activity.getWindow().getDecorView();
        final AtomicBoolean finished = new AtomicBoolean(false);
        final long armedAt = SystemClock.elapsedRealtime();
        state(activity, transitionId, "UI_FRAME_ARMED",
                "label=" + clean(label, 100) + " target=" + targetSummary(target));

        target.requestLayout();
        target.invalidate();
        scheduler.requestLayout();
        scheduler.invalidate();

        final ViewTreeObserver observer = scheduler.getViewTreeObserver();
        final ViewTreeObserver.OnPreDrawListener[] holder = new ViewTreeObserver.OnPreDrawListener[1];
        holder[0] = () -> {
            if (!finished.compareAndSet(false, true)) return true;
            ViewTreeObserver live = scheduler.getViewTreeObserver();
            if (live.isAlive()) live.removeOnPreDrawListener(holder[0]);
            if (!guardCurrent(activity, guard)) {
                state(activity, transitionId, "UI_FRAME_CANCELLED",
                        "label=" + clean(label, 100) + " reason=stale_or_paused");
                return true;
            }
            boolean visible = actuallyVisible(target);
            long elapsed = SystemClock.elapsedRealtime() - armedAt;
            state(activity, transitionId,
                    visible ? "UI_FRAME_COMMITTED" : "UI_FRAME_STALL",
                    "label=" + clean(label, 100) + " elapsedMs=" + elapsed
                            + " target=" + targetSummary(target));
            invariant(activity, transitionId, "target_visible_on_committed_frame", visible,
                    "label=" + clean(label, 100));
            if (visible) {
                if (onSuccess != null) onSuccess.run();
            } else if (onFailure != null) {
                onFailure.run();
            }
            return true;
        };
        if (observer.isAlive()) observer.addOnPreDrawListener(holder[0]);

        scheduler.postDelayed(() -> {
            if (!finished.compareAndSet(false, true)) return;
            ViewTreeObserver live = scheduler.getViewTreeObserver();
            if (live.isAlive()) live.removeOnPreDrawListener(holder[0]);
            if (!guardCurrent(activity, guard)) {
                state(activity, transitionId, "UI_FRAME_CANCELLED",
                        "label=" + clean(label, 100) + " reason=timeout_after_stale_or_pause");
                return;
            }
            state(activity, transitionId, "UI_FRAME_STALL",
                    "label=" + clean(label, 100) + " reason=no_predraw_within_750ms"
                            + " target=" + targetSummary(target));
            invariant(activity, transitionId, "next_frame_arrived", false,
                    "label=" + clean(label, 100));
            if (onFailure != null) onFailure.run();
        }, 750L);
    }

    public static boolean actuallyVisible(View target) {
        if (target == null || !target.isAttachedToWindow() || !target.isShown()
                || target.getWindowVisibility() != View.VISIBLE
                || target.getAlpha() <= 0.01f || target.getWidth() <= 0 || target.getHeight() <= 0) {
            return false;
        }
        Rect visible = new Rect();
        return target.getGlobalVisibleRect(visible) && visible.width() > 0 && visible.height() > 0;
    }

    private static boolean guardCurrent(Activity activity, Guard guard) {
        return isResumed(activity) && (guard == null || guard.isCurrent());
    }

    private static synchronized String lifecycle(Activity activity) {
        if (activity == null) return "null";
        String state = LIFECYCLE.get(activity);
        return state == null ? "unknown" : state;
    }

    private static String targetSummary(View target) {
        if (target == null) return "null";
        Rect rect = new Rect();
        boolean global = target.getGlobalVisibleRect(rect);
        Object tag = target.getTag();
        CharSequence description = target.getContentDescription();
        return "class=" + target.getClass().getSimpleName()
                + ",attached=" + target.isAttachedToWindow()
                + ",shown=" + target.isShown()
                + ",windowVis=" + target.getWindowVisibility()
                + ",vis=" + target.getVisibility()
                + ",alpha=" + target.getAlpha()
                + ",size=" + target.getWidth() + "x" + target.getHeight()
                + ",global=" + global
                + ",rect=" + rect.left + ":" + rect.top + ":" + rect.right + ":" + rect.bottom
                + ",tag=" + clean(tag == null ? "" : String.valueOf(tag), 100)
                + ",desc=" + clean(description == null ? "" : description.toString(), 140);
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max);
    }
}
