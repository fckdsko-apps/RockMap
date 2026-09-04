package com.rockmap.app;

import android.app.Activity;

import java.util.WeakHashMap;

/**
 * Presentation-only arbiter for the live MainActivity map workspace.
 *
 * It deliberately knows nothing about Track recording, navigation targets, measurement geometry,
 * Research results, databases or map-layer data.  A Host exposes only expanded/collapse
 * presentation semantics.  This prevents HUD exclusivity from becoming an owner of app state.
 */
public final class MapHudCoordinator {
    public static final String SURFACE_FIELD = "field";
    public static final String SURFACE_RESEARCH = "research";
    public static final String SURFACE_MAPPED_CONTEXT = "mapped-context";

    public interface Host {
        boolean isExpanded(String surface);
        boolean collapsePresentationOnly(String surface, long transitionId);
    }

    private static final String[] SURFACES = new String[]{
            SURFACE_FIELD, SURFACE_RESEARCH, SURFACE_MAPPED_CONTEXT
    };
    private static final WeakHashMap<Activity, Long> IN_FLIGHT = new WeakHashMap<>();

    private MapHudCoordinator() {}

    /** Collapse other expanded presentations before an intentional expansion. */
    public static long beforeExpand(Activity activity, String requested, Host host) {
        if (activity == null || host == null || !known(requested)) return -1L;
        final long transition = UiInvariantMonitor.begin(activity, "hud-expand",
                "requested=" + requested + " expandedBefore=" + expandedSummary(host));
        synchronized (IN_FLIGHT) {
            Long existing = IN_FLIGHT.get(activity);
            if (existing != null) {
                UiInvariantMonitor.invariant(activity, transition,
                        "no_recursive_hud_transition", false,
                        "requested=" + requested + " existingTransition=" + existing);
                return -1L;
            }
            IN_FLIGHT.put(activity, transition);
        }

        if (!UiInvariantMonitor.isResumed(activity)) {
            finish(activity, transition);
            return -1L;
        }

        for (String surface : SURFACES) {
            if (surface.equals(requested) || !host.isExpanded(surface)) continue;
            UiInvariantMonitor.state(activity, transition, "HUD_COLLAPSE_REQUEST",
                    "surface=" + surface + " requested=" + requested);
            boolean collapsed = host.collapsePresentationOnly(surface, transition);
            boolean verified = collapsed && !host.isExpanded(surface);
            UiInvariantMonitor.invariant(activity, transition,
                    "previous_hud_collapsed", verified,
                    "surface=" + surface + " requested=" + requested);
            if (!verified) {
                UiInvariantMonitor.state(activity, transition, "HUD_SWITCH_BLOCKED",
                        "requested=" + requested + " couldNotCollapse=" + surface);
                finish(activity, transition);
                return -1L;
            }
        }
        UiInvariantMonitor.state(activity, transition, "HUD_SWITCH_READY",
                "requested=" + requested + " expandedAfterCollapse=" + expandedSummary(host));
        return transition;
    }

    /** Complete an expansion and verify the one-expanded-HUD invariant. */
    public static void afterExpand(Activity activity, String requested,
                                   long transition, Host host) {
        if (activity == null || transition < 0L || host == null) return;
        int count = expandedCount(host);
        boolean requestedExpanded = host.isExpanded(requested);
        UiInvariantMonitor.state(activity, transition, "HUD_EXPAND_COMMITTED",
                "requested=" + requested + " expanded=" + expandedSummary(host));
        UiInvariantMonitor.invariant(activity, transition,
                "requested_hud_expanded", requestedExpanded,
                "requested=" + requested);
        UiInvariantMonitor.invariant(activity, transition,
                "at_most_one_expanded_hud", count <= 1,
                "count=" + count + " expanded=" + expandedSummary(host));
        finish(activity, transition);
    }

    /**
     * Collapse other presentations for an explicit action that will open a surface later (for
     * example Research returning from another Activity).  No in-flight ownership is retained.
     */
    public static boolean prepareForUpcoming(Activity activity, String requested, Host host) {
        if (activity == null || host == null || !known(requested)) return false;
        long transition = UiInvariantMonitor.begin(activity, "hud-prepare-upcoming",
                "requested=" + requested + " expandedBefore=" + expandedSummary(host));
        if (!UiInvariantMonitor.isResumed(activity)) {
            UiInvariantMonitor.invariant(activity, transition,
                    "upcoming_hud_mutation_requires_resumed_activity", false,
                    "requested=" + requested);
            return false;
        }
        boolean ok = true;
        for (String surface : SURFACES) {
            if (surface.equals(requested) || !host.isExpanded(surface)) continue;
            boolean collapsed = host.collapsePresentationOnly(surface, transition);
            ok &= collapsed && !host.isExpanded(surface);
        }
        UiInvariantMonitor.invariant(activity, transition,
                "upcoming_hud_has_clean_slot", ok,
                "requested=" + requested + " expandedAfter=" + expandedSummary(host));
        return ok;
    }

    /**
     * Reconcile old persisted presentation state after MainActivity/FieldMapController are live.
     * Functional state is untouched.  Active Field presentation wins, then Research, then mapped
     * context, because Field may represent an in-progress interaction.
     */
    public static void reconcile(Activity activity, Host host) {
        if (activity == null || host == null || !UiInvariantMonitor.isResumed(activity)) return;
        if (expandedCount(host) <= 1) return;
        String winner = host.isExpanded(SURFACE_FIELD) ? SURFACE_FIELD
                : host.isExpanded(SURFACE_RESEARCH) ? SURFACE_RESEARCH
                : SURFACE_MAPPED_CONTEXT;
        long transition = UiInvariantMonitor.begin(activity, "hud-reconcile",
                "winner=" + winner + " expandedBefore=" + expandedSummary(host));
        for (String surface : SURFACES) {
            if (surface.equals(winner) || !host.isExpanded(surface)) continue;
            host.collapsePresentationOnly(surface, transition);
        }
        UiInvariantMonitor.invariant(activity, transition,
                "at_most_one_expanded_hud", expandedCount(host) <= 1,
                "winner=" + winner + " expandedAfter=" + expandedSummary(host));
    }

    public static int expandedCount(Host host) {
        int count = 0;
        if (host != null) {
            for (String surface : SURFACES) if (host.isExpanded(surface)) count++;
        }
        return count;
    }

    public static String expandedSummary(Host host) {
        if (host == null) return "none";
        StringBuilder out = new StringBuilder();
        for (String surface : SURFACES) {
            if (!host.isExpanded(surface)) continue;
            if (out.length() > 0) out.append(',');
            out.append(surface);
        }
        return out.length() == 0 ? "none" : out.toString();
    }

    private static void finish(Activity activity, long transition) {
        synchronized (IN_FLIGHT) {
            Long current = IN_FLIGHT.get(activity);
            if (current != null && current == transition) IN_FLIGHT.remove(activity);
        }
    }

    private static boolean known(String surface) {
        return SURFACE_FIELD.equals(surface)
                || SURFACE_RESEARCH.equals(surface)
                || SURFACE_MAPPED_CONTEXT.equals(surface);
    }
}
