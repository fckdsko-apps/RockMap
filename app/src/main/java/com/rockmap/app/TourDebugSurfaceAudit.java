package com.rockmap.app;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Observational registry and failure snapshotter for important RockMap UI surfaces.
 *
 * Registration never changes a View. Snapshotting reads attachment, visibility, bounds, z/elevation,
 * translations and hit-test results only. It deliberately does not call bringToFront(), requestLayout(),
 * invalidate(), setVisibility(), performClick(), or any application state mutator.
 */
public final class TourDebugSurfaceAudit {
    private static final Object LOCK = new Object();
    private static final WeakHashMap<Activity, Map<String, Entry>> SURFACES = new WeakHashMap<>();
    private static final int MAX_TREE_LINES = 90;

    private TourDebugSurfaceAudit() {}

    private static final class Entry {
        WeakReference<View> view;
        String state;
        boolean allowedAtTourCleanStart;
        String lastGeometry;

        Entry(View view, String state, boolean allowedAtTourCleanStart) {
            this.view = new WeakReference<>(view);
            this.state = state == null ? "" : state;
            this.allowedAtTourCleanStart = allowedAtTourCleanStart;
            this.lastGeometry = geometry(view);
        }
    }

    private static final class Hit {
        View view;
        float z = -Float.MAX_VALUE;
        int depth = -1;
        int order = -1;

        boolean shouldReplace(View candidate, int candidateDepth, int candidateOrder) {
            float candidateZ = safeZ(candidate);
            if (view == null) return true;
            if (candidateZ > z + 0.01f) return true;
            if (candidateZ < z - 0.01f) return false;
            if (candidateDepth > depth) return true;
            return candidateDepth == depth && candidateOrder > order;
        }

        void set(View candidate, int candidateDepth, int candidateOrder) {
            view = candidate;
            z = safeZ(candidate);
            depth = candidateDepth;
            order = candidateOrder;
        }
    }

    public static void register(Activity activity, String id, View view,
                                String state, boolean allowedAtTourCleanStart) {
        if (activity == null || id == null || id.trim().isEmpty() || view == null) return;
        String safeId = id.trim();
        synchronized (LOCK) {
            Map<String, Entry> map = SURFACES.get(activity);
            if (map == null) {
                map = new HashMap<>();
                SURFACES.put(activity, map);
            }
            Entry entry = map.get(safeId);
            if (entry == null) {
                entry = new Entry(view, state, allowedAtTourCleanStart);
                map.put(safeId, entry);
                TourDebugLog.causalEvent("SURFACE_REGISTER",
                        "activity=" + activityName(activity)
                                + " surface=" + clean(safeId, 120)
                                + " state=" + clean(state, 160)
                                + " allowedAtCleanStart=" + allowedAtTourCleanStart
                                + " view={" + summary(view) + "}");
            } else {
                entry.view = new WeakReference<>(view);
                entry.allowedAtTourCleanStart = allowedAtTourCleanStart;
            }
        }
    }

    public static void observe(Activity activity, String id, View view,
                               String state, boolean allowedAtTourCleanStart) {
        if (activity == null || id == null || id.trim().isEmpty() || view == null) return;
        String safeId = id.trim();
        String safeState = state == null ? "" : state;
        String beforeState = null;
        String beforeGeometry = null;
        String afterGeometry = geometry(view);
        boolean changed = false;
        synchronized (LOCK) {
            Map<String, Entry> map = SURFACES.get(activity);
            if (map == null) {
                map = new HashMap<>();
                SURFACES.put(activity, map);
            }
            Entry entry = map.get(safeId);
            if (entry == null) {
                entry = new Entry(view, safeState, allowedAtTourCleanStart);
                map.put(safeId, entry);
                TourDebugLog.causalEvent("SURFACE_REGISTER",
                        "activity=" + activityName(activity)
                                + " surface=" + clean(safeId, 120)
                                + " state=" + clean(safeState, 160)
                                + " allowedAtCleanStart=" + allowedAtTourCleanStart
                                + " view={" + summary(view) + "}");
                return;
            }
            beforeState = entry.state;
            beforeGeometry = entry.lastGeometry;
            entry.view = new WeakReference<>(view);
            entry.allowedAtTourCleanStart = allowedAtTourCleanStart;
            entry.state = safeState;
            entry.lastGeometry = afterGeometry;
            changed = !safeState.equals(beforeState) || !afterGeometry.equals(beforeGeometry);
        }
        if (changed) {
            TourDebugCausality.stateMutation(activity, safeId,
                    clean(beforeState, 180), clean(safeState, 180),
                    "geometry=" + clean(beforeGeometry, 260) + "->" + clean(afterGeometry, 260));
        }
    }

    public static void unregister(Activity activity, String id, String reason) {
        if (activity == null || id == null) return;
        Entry removed = null;
        synchronized (LOCK) {
            Map<String, Entry> map = SURFACES.get(activity);
            if (map != null) removed = map.remove(id);
        }
        if (removed != null) {
            TourDebugLog.causalEvent("SURFACE_UNREGISTER",
                    "activity=" + activityName(activity)
                            + " surface=" + clean(id, 120)
                            + " lastState=" + clean(removed.state, 160)
                            + " reason=" + clean(reason, 180));
        }
    }

    public static String hitSummary(Activity activity, float rawX, float rawY) {
        if (activity == null || activity.getWindow() == null) return "activity/window unavailable";
        View root = activity.getWindow().getDecorView();
        if (root == null) return "decor unavailable";
        Hit any = new Hit();
        Hit interactive = new Hit();
        int[] order = new int[]{0};
        collectHit(root, rawX, rawY, 0, order, any, interactive);
        return "top=" + stableViewId(any.view)
                + " interactive=" + stableViewId(interactive.view)
                + " topView={" + compactSummary(any.view) + "}"
                + " interactiveView={" + compactSummary(interactive.view) + "}";
    }

    private static void collectHit(View view, float rawX, float rawY, int depth, int[] order,
                                   Hit any, Hit interactive) {
        if (view == null || view.getVisibility() != View.VISIBLE || !view.isShown()
                || view.getAlpha() <= 0.01f) return;
        Rect rect = new Rect();
        boolean visible;
        try {
            visible = view.getGlobalVisibleRect(rect);
        } catch (Throwable ignored) {
            visible = false;
        }
        if (!visible || !rect.contains(Math.round(rawX), Math.round(rawY))) return;
        int here = order[0]++;
        if (any.shouldReplace(view, depth, here)) any.set(view, depth, here);
        if ((view.isClickable() || view.isLongClickable() || view.isFocusable())
                && interactive.shouldReplace(view, depth, here)) {
            interactive.set(view, depth, here);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectHit(group.getChildAt(i), rawX, rawY, depth + 1, order, any, interactive);
            }
        }
    }

    public static void auditTourCleanStart(Activity activity, String reason) {
        if (activity == null) return;
        List<String> violations = new ArrayList<>();
        synchronized (LOCK) {
            Map<String, Entry> map = SURFACES.get(activity);
            if (map != null) {
                for (Map.Entry<String, Entry> item : map.entrySet()) {
                    Entry entry = item.getValue();
                    View view = entry == null || entry.view == null ? null : entry.view.get();
                    if (entry == null || entry.allowedAtTourCleanStart || !actuallyVisible(view)) continue;
                    violations.add(item.getKey() + " state=" + entry.state + " view={"
                            + compactSummary(view) + "}");
                }
            }
        }
        TourDebugLog.causalEvent("TOUR_CLEAN_START_SURFACE_AUDIT",
                "activity=" + activityName(activity)
                        + " reason=" + clean(reason, 160)
                        + " violations=" + violations.size()
                        + (violations.isEmpty() ? "" : " items=" + clean(violations.toString(), 1800)));
        if (!violations.isEmpty()) {
            TourDebugCausality.finding(activity, "ERROR", "TOUR_CLEAN_START_VISIBLE_SURFACES",
                    "reason=" + clean(reason, 120) + " items=" + clean(violations.toString(), 1600));
        }
        snapshot(activity, "tour-clean-start:" + clean(reason, 100));
    }

    public static void snapshot(Activity activity, String reason) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread(() -> snapshot(activity, reason));
            return;
        }
        TourDebugLog.causalEvent("SURFACE_SNAPSHOT_BEGIN",
                "activity=" + activityName(activity)
                        + " reason=" + clean(reason, 180)
                        + " causeContext={" + TourDebugCausality.contextSummary() + "}");

        List<Map.Entry<String, Entry>> registered = new ArrayList<>();
        synchronized (LOCK) {
            Map<String, Entry> map = SURFACES.get(activity);
            if (map != null) registered.addAll(map.entrySet());
        }
        Collections.sort(registered, Comparator.comparing(Map.Entry::getKey));
        for (Map.Entry<String, Entry> item : registered) {
            Entry entry = item.getValue();
            View view = entry == null || entry.view == null ? null : entry.view.get();
            TourDebugLog.causalEvent("SURFACE_SNAPSHOT",
                    "surface=" + clean(item.getKey(), 120)
                            + " state=" + clean(entry == null ? "" : entry.state, 180)
                            + " allowedAtCleanStart="
                            + (entry != null && entry.allowedAtTourCleanStart)
                            + " view={" + summary(view) + "}");
        }

        View content = activity.findViewById(android.R.id.content);
        int[] emitted = new int[]{0};
        if (content != null) {
            snapshotInterestingTree(content, "content", 0, emitted);
        }
        TourDebugLog.causalEvent("SURFACE_SNAPSHOT_END",
                "activity=" + activityName(activity)
                        + " reason=" + clean(reason, 180)
                        + " registered=" + registered.size()
                        + " treeLines=" + emitted[0]);
    }

    private static void snapshotInterestingTree(View view, String path, int depth, int[] emitted) {
        if (view == null || emitted[0] >= MAX_TREE_LINES || view.getVisibility() != View.VISIBLE
                || !view.isShown() || view.getAlpha() <= 0.01f) return;
        boolean interesting = depth <= 2
                || view.getTag() != null
                || (view.getContentDescription() != null
                && view.getContentDescription().length() > 0)
                || view.getElevation() > 0f
                || view.isClickable();
        if (interesting) {
            emitted[0]++;
            TourDebugLog.causalEvent("VISIBLE_VIEW",
                    "path=" + clean(path, 240) + " depth=" + depth
                            + " view={" + summary(view) + "}");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && emitted[0] < MAX_TREE_LINES; i++) {
                View child = group.getChildAt(i);
                snapshotInterestingTree(child, path + "/" + i + ":"
                        + child.getClass().getSimpleName(), depth + 1, emitted);
            }
        }
    }

    public static boolean actuallyVisible(View view) {
        if (view == null || !view.isAttachedToWindow() || !view.isShown()
                || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0.01f
                || view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        Rect rect = new Rect();
        try {
            return view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String stableViewId(View view) {
        if (view == null) return "none";
        Object tag = view.getTag();
        if (tag != null && !String.valueOf(tag).trim().isEmpty()) {
            return "tag:" + clean(String.valueOf(tag), 100);
        }
        CharSequence desc = view.getContentDescription();
        if (desc != null && desc.length() > 0) {
            return "desc:" + clean(desc.toString(), 100);
        }
        if (view.getId() != View.NO_ID) {
            try {
                return "id:" + view.getResources().getResourceEntryName(view.getId());
            } catch (Throwable ignored) {
                return "id:" + view.getId();
            }
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                return view.getClass().getSimpleName() + ":" + clean(text.toString(), 80);
            }
        }
        return view.getClass().getSimpleName();
    }

    public static String summary(View view) {
        if (view == null) return "null";
        try {
            Rect rect = new Rect();
            boolean global = view.getGlobalVisibleRect(rect);
            ViewGroup parent = view.getParent() instanceof ViewGroup ? (ViewGroup) view.getParent() : null;
            int siblingIndex = parent == null ? -1 : parent.indexOfChild(view);
            String blocker = "none";
            if (global && rect.width() > 0 && rect.height() > 0) {
                Activity owner = findActivity(view);
                if (owner != null) {
                    String hit = hitSummary(owner, rect.exactCenterX(), rect.exactCenterY());
                    blocker = clean(hit, 440);
                }
            }
            return "stable=" + stableViewId(view)
                    + ",class=" + view.getClass().getSimpleName()
                    + ",attached=" + view.isAttachedToWindow()
                    + ",shown=" + view.isShown()
                    + ",visibility=" + view.getVisibility()
                    + ",alpha=" + view.getAlpha()
                    + ",clickable=" + view.isClickable()
                    + ",enabled=" + view.isEnabled()
                    + ",focusable=" + view.isFocusable()
                    + ",size=" + view.getWidth() + "x" + view.getHeight()
                    + ",global=" + global
                    + ",rect=" + rect.left + ":" + rect.top + ":" + rect.right + ":" + rect.bottom
                    + ",z=" + safeZ(view)
                    + ",elevation=" + view.getElevation()
                    + ",translation=" + Math.round(view.getTranslationX()) + ","
                    + Math.round(view.getTranslationY())
                    + ",sibling=" + siblingIndex
                    + ",centerHit=" + blocker;
        } catch (Throwable error) {
            return "summary-error=" + error.getClass().getSimpleName();
        }
    }

    private static String compactSummary(View view) {
        if (view == null) return "null";
        Rect rect = new Rect();
        boolean global = false;
        try { global = view.getGlobalVisibleRect(rect); } catch (Throwable ignored) {}
        return "stable=" + stableViewId(view)
                + ",shown=" + view.isShown()
                + ",clickable=" + view.isClickable()
                + ",global=" + global
                + ",rect=" + rect.left + ":" + rect.top + ":" + rect.right + ":" + rect.bottom
                + ",z=" + safeZ(view);
    }

    private static String geometry(View view) {
        if (view == null) return "null";
        Rect rect = new Rect();
        boolean global = false;
        try { global = view.getGlobalVisibleRect(rect); } catch (Throwable ignored) {}
        return "shown=" + view.isShown()
                + ",vis=" + view.getVisibility()
                + ",global=" + global
                + ",rect=" + rect.left + ":" + rect.top + ":" + rect.right + ":" + rect.bottom
                + ",z=" + safeZ(view)
                + ",translation=" + Math.round(view.getTranslationX()) + ","
                + Math.round(view.getTranslationY());
    }

    private static Activity findActivity(View view) {
        if (view == null) return null;
        android.content.Context context = view.getContext();
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private static float safeZ(View view) {
        if (view == null) return -Float.MAX_VALUE;
        try { return view.getZ(); } catch (Throwable ignored) { return view.getElevation(); }
    }

    private static String activityName(Activity activity) {
        return activity == null ? "null" : activity.getClass().getSimpleName();
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "…";
    }
}