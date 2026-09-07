#!/usr/bin/env python3
"""Apply production retention/noise policy after all legacy causal debugger injectors.

Observational-only. This pass does not change RockMap feature behavior. It controls which routine
telemetry is persisted, preserves full evidence for failures, expands bounded private retention,
and prevents very recent same-Activity user actions from being falsely reported as unattributed.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOG = ROOT / "app/src/main/java/com/rockmap/app/TourDebugLog.java"
CAUSALITY = ROOT / "app/src/main/java/com/rockmap/app/TourDebugCausality.java"
AUDIT = ROOT / "app/src/main/java/com/rockmap/app/TourDebugSurfaceAudit.java"
MARKER = "production-diagnostics-noise-policy"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def replace_once(path: Path, marker: str, old: str, new: str, label: str) -> None:
    current = read(path)
    if marker in current:
        print(f"{label}: already present")
        return
    count = current.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one match in {path.relative_to(ROOT)}, found {count}"
        )
    path.write_text(current.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: injected")


def patch_logger() -> None:
    replace_once(
        LOG,
        "production-diagnostics-retention-v2",
        '''    private static final long MAX_INTERNAL_BYTES = 2L * 1024L * 1024L;
    private static final int KEEP_INTERNAL_BYTES = 1536 * 1024;
''',
        '''    // Diagnostics are explicitly opt-in. Keep enough bounded history for a real field
    // reproduction while pinning startup evidence and a large recent tail.
    private static final long MAX_INTERNAL_BYTES = 8L * 1024L * 1024L;
    private static final int KEEP_STARTUP_BYTES = 1024 * 1024;
    private static final int KEEP_TAIL_BYTES = 6 * 1024 * 1024; // marker: production-diagnostics-retention-v2
''',
        "production diagnostics bounded retention",
    )

    replace_once(
        LOG,
        "production-diagnostics-rate-state",
        '''    private static final Object HUD_EVENT_LOCK = new Object();
    private static final Object GPS_EVENT_LOCK = new Object();
''',
        '''    private static final Object HUD_EVENT_LOCK = new Object();
    private static final Object GPS_EVENT_LOCK = new Object();
    private static final Object RATE_LIMIT_LOCK = new Object();
    private static long lastHeadingOutputElapsed;
    private static long lastHeadingSampleElapsed;
    private static long lastCompassRenderElapsed;
    private static long lastInactiveTrackGeoJsonElapsed;
    private static long lastInactiveTrackRenderElapsed;
    private static long lastPeriodicHudStartElapsed;
    private static long lastPeriodicHudBuiltElapsed;
    private static long lastPeriodicHudReadyElapsed; // marker: production-diagnostics-rate-state
''',
        "production diagnostics rate-limit state",
    )

    replace_once(
        LOG,
        "production-diagnostics-persist-policy",
        '''    private static void enqueue(String type, String detail) {
        if (!enabled() || app == null || internalLog == null) return;

        long sequence = SEQUENCE.incrementAndGet();
''',
        '''    private static boolean elapsedGate(long now, long previous, long intervalMs) {
        return previous <= 0L || now - previous >= intervalMs;
    }

    /**
     * Keeps routine telemetry compact while never suppressing failures, crashes, stalls,
     * operation errors, user gestures, database/download milestones, or explicit snapshots.
     */
    private static boolean shouldPersist(String type, String detail) {
        String safeType = type == null ? "" : type;
        String safeDetail = detail == null ? "" : detail;
        long now = SystemClock.elapsedRealtime();
        synchronized (RATE_LIMIT_LOCK) {
            if ("HEADING_OUTPUT".equals(safeType)) {
                if (!elapsedGate(now, lastHeadingOutputElapsed, 5000L)) return false;
                lastHeadingOutputElapsed = now;
            } else if ("HEADING_SENSOR_SAMPLE".equals(safeType)) {
                if (!elapsedGate(now, lastHeadingSampleElapsed, 10000L)) return false;
                lastHeadingSampleElapsed = now;
            } else if ("COMPASS_RENDER".equals(safeType)) {
                if (!elapsedGate(now, lastCompassRenderElapsed, 5000L)) return false;
                lastCompassRenderElapsed = now;
            } else if ("FIELD_RECORD_ZOOM_ITEM".equals(safeType)) {
                // Aggregate FIELD_RECORD_ZOOM_RENDER remains lossless. Keep per-item evidence only
                // for records that are actually relevant to the viewport/render hit state.
                boolean relevant = safeDetail.contains("inViewport=true")
                        || !safeDetail.contains("circleHits=0")
                        || !safeDetail.contains("labelHits=0");
                if (!relevant) return false;
            } else if ("TRACK_GEOJSON_BUILD".equals(safeType)
                    && safeDetail.contains("activeTrackId=-1")) {
                if (!elapsedGate(now, lastInactiveTrackGeoJsonElapsed, 10000L)) return false;
                lastInactiveTrackGeoJsonElapsed = now;
            } else if ("TRACK_MAP_RENDER_STATE".equals(safeType)
                    && safeDetail.contains("activeTrackId=-1")) {
                if (!elapsedGate(now, lastInactiveTrackRenderElapsed, 10000L)) return false;
                lastInactiveTrackRenderElapsed = now;
            } else if (safeDetail.contains("reason=periodic_refresh")) {
                if ("HUD_RENDER_START".equals(safeType)) {
                    if (!elapsedGate(now, lastPeriodicHudStartElapsed, 10000L)) return false;
                    lastPeriodicHudStartElapsed = now;
                } else if ("HUD_RENDER_BUILT".equals(safeType)) {
                    if (!elapsedGate(now, lastPeriodicHudBuiltElapsed, 10000L)) return false;
                    lastPeriodicHudBuiltElapsed = now;
                } else if ("HUD_READY".equals(safeType)) {
                    if (!elapsedGate(now, lastPeriodicHudReadyElapsed, 10000L)) return false;
                    lastPeriodicHudReadyElapsed = now;
                }
            }
        }
        return true;
    } // marker: production-diagnostics-persist-policy

    private static void enqueue(String type, String detail) {
        if (!enabled() || app == null || internalLog == null) return;
        if (!shouldPersist(type, detail)) return;

        long sequence = SEQUENCE.incrementAndGet();
''',
        "production diagnostics persistence policy",
    )

    replace_once(
        LOG,
        "production-diagnostics-startup-tail-trim",
        '''    private static void trimIfNeeded() throws IOException {
        if (internalLog == null || !internalLog.isFile()
                || internalLog.length() <= MAX_INTERNAL_BYTES) {
            return;
        }

        byte[] bytes = readFile(internalLog);
        int start = Math.max(0, bytes.length - KEEP_INTERNAL_BYTES);
        while (start < bytes.length && bytes[start] != '\\n') start++;
        if (start < bytes.length) start++;

        try (FileOutputStream output = new FileOutputStream(internalLog, false)) {
            output.write("[older RockMap diagnostics entries trimmed]\\n"
                    .getBytes(StandardCharsets.UTF_8));
            output.write(bytes, Math.min(start, bytes.length),
                    bytes.length - Math.min(start, bytes.length));
        }
    }
''',
        '''    private static void trimIfNeeded() throws IOException {
        if (internalLog == null || !internalLog.isFile()
                || internalLog.length() <= MAX_INTERNAL_BYTES) {
            return;
        }

        byte[] bytes = readFile(internalLog);
        int prefixEnd = Math.min(bytes.length, KEEP_STARTUP_BYTES);
        while (prefixEnd < bytes.length && bytes[prefixEnd] != '\\n') prefixEnd++;
        if (prefixEnd < bytes.length) prefixEnd++;

        int tailStart = Math.max(prefixEnd, bytes.length - KEEP_TAIL_BYTES);
        while (tailStart < bytes.length && bytes[tailStart] != '\\n') tailStart++;
        if (tailStart < bytes.length) tailStart++;

        try (FileOutputStream output = new FileOutputStream(internalLog, false)) {
            output.write(bytes, 0, Math.min(prefixEnd, bytes.length));
            output.write("[middle RockMap diagnostics entries trimmed; startup retained]\\n"
                    .getBytes(StandardCharsets.UTF_8));
            if (tailStart < bytes.length) {
                output.write(bytes, tailStart, bytes.length - tailStart);
            }
        }
    } // marker: production-diagnostics-startup-tail-trim
''',
        "production diagnostics startup/tail retention",
    )


def patch_recent_user_attribution() -> None:
    replace_once(
        CAUSALITY,
        "production-diagnostics-last-user-activity",
        '''    private static String lastUserCauseId = "none";
    private static String lastUserTarget = "";
    private static long lastUserElapsed;
    // marker: causal-v2-last-user-state
''',
        '''    private static String lastUserCauseId = "none";
    private static String lastUserTarget = "";
    private static long lastUserElapsed;
    private static WeakReference<Activity> lastUserActivity = new WeakReference<>(null);
    // marker: causal-v2-last-user-state
    // marker: production-diagnostics-last-user-activity
''',
        "remember Activity for nearest user gesture",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-last-user-activity-capture",
        '''                lastUserCauseId = user.id;
                lastUserTarget = hit;
                lastUserElapsed = SystemClock.elapsedRealtime();
                // Attribute the gesture-start record itself, not only code that executes inside
''',
        '''                lastUserCauseId = user.id;
                lastUserTarget = hit;
                lastUserElapsed = SystemClock.elapsedRealtime();
                lastUserActivity = new WeakReference<>(activity);
                // marker: production-diagnostics-last-user-activity-capture
                // Attribute the gesture-start record itself, not only code that executes inside
''',
        "capture Activity for nearest user gesture",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-recent-user-state-attribution",
        '''    public static void stateMutation(Activity activity, String surface, String before,
                                     String after, String detail) {
        Cause cause = CURRENT.get();
        String event = cause == null ? "UNATTRIBUTED_STATE_CHANGE" : "STATE_MUTATION";
        TourDebugLog.causalEvent(event,
                "activity=" + activityName(activity)
                        + " surface=" + clean(surface, 120)
                        + " before=" + clean(before, 180)
                        + " after=" + clean(after, 180)
                        + " " + contextSummary()
                        + " detail=" + clean(detail, 800));
        if (cause == null) {
            finding(activity, "WARNING", "UNATTRIBUTED_STATE_CHANGE",
                    "surface=" + clean(surface, 120)
                            + " before=" + clean(before, 120)
                            + " after=" + clean(after, 120));
        }
    }
''',
        '''    public static void stateMutation(Activity activity, String surface, String before,
                                     String after, String detail) {
        Cause cause = CURRENT.get();
        boolean recentSameActivityUser = false;
        String inferredUserId = "none";
        String inferredUserTarget = "";
        long inferredUserAgeMs = -1L;
        if (cause == null) {
            synchronized (LOCK) {
                Activity userActivity = lastUserActivity == null ? null : lastUserActivity.get();
                inferredUserAgeMs = lastUserElapsed <= 0L ? -1L
                        : Math.max(0L, SystemClock.elapsedRealtime() - lastUserElapsed);
                inferredUserId = lastUserCauseId;
                inferredUserTarget = lastUserTarget;
                recentSameActivityUser = activity != null && userActivity == activity
                        && inferredUserAgeMs >= 0L && inferredUserAgeMs <= 250L
                        && !"none".equals(inferredUserId);
            }
        }
        String event = (cause != null || recentSameActivityUser)
                ? "STATE_MUTATION" : "UNATTRIBUTED_STATE_CHANGE";
        TourDebugLog.causalEvent(event,
                "activity=" + activityName(activity)
                        + " surface=" + clean(surface, 120)
                        + " before=" + clean(before, 180)
                        + " after=" + clean(after, 180)
                        + " " + contextSummary()
                        + (recentSameActivityUser
                            ? " attribution=recent-same-activity-user inferredCause="
                                + clean(inferredUserId, 80)
                                + " inferredAgeMs=" + inferredUserAgeMs
                                + " inferredTarget=" + clean(inferredUserTarget, 240)
                            : "")
                        + " detail=" + clean(detail, 800));
        if (cause == null && !recentSameActivityUser) {
            finding(activity, "WARNING", "UNATTRIBUTED_STATE_CHANGE",
                    "surface=" + clean(surface, 120)
                            + " before=" + clean(before, 120)
                            + " after=" + clean(after, 120));
        }
    } // marker: production-diagnostics-recent-user-state-attribution
''',
        "conservative recent-user state attribution",
    )


def patch_snapshot_policy() -> None:
    replace_once(
        AUDIT,
        "production-diagnostics-clean-audit-no-snapshot",
        '''        if (!violations.isEmpty()) {
            TourDebugCausality.finding(activity, "ERROR", "TOUR_CLEAN_START_VISIBLE_SURFACES",
                    "reason=" + clean(reason, 120) + " items=" + clean(violations.toString(), 1600));
        }
        snapshot(activity, "tour-clean-start:" + clean(reason, 100));
    }
''',
        '''        if (!violations.isEmpty()) {
            TourDebugCausality.finding(activity, "ERROR", "TOUR_CLEAN_START_VISIBLE_SURFACES",
                    "reason=" + clean(reason, 120) + " items=" + clean(violations.toString(), 1600));
            snapshot(activity, "tour-clean-start:" + clean(reason, 100));
        }
        // A clean audit is already represented by TOUR_CLEAN_START_SURFACE_AUDIT violations=0.
        // Do not dump the full view tree for a successful invariant.
    } // marker: production-diagnostics-clean-audit-no-snapshot
''',
        "snapshot only failed tour-clean-start audits",
    )


def validate() -> None:
    log = read(LOG)
    causality = read(CAUSALITY)
    audit = read(AUDIT)
    required = (
        "production-diagnostics-retention-v2",
        "production-diagnostics-persist-policy",
        "production-diagnostics-startup-tail-trim",
        "production-diagnostics-recent-user-state-attribution",
        "production-diagnostics-clean-audit-no-snapshot",
    )
    combined = log + causality + audit
    missing = [marker for marker in required if marker not in combined]
    if missing:
        raise RuntimeError("production diagnostics noise policy incomplete: " + ", ".join(missing))

    # Failure evidence must remain lossless: none of these are rate-limited or filtered.
    forbidden_filtered_types = (
        "DEBUG_FINDING", "UNCAUGHT_EXCEPTION", "MAIN_THREAD_STALL", "OP_ERROR",
        "WORK_FAILURE", "DOWNLOAD_ERROR", "CNGM_QUERY_ERROR", "CALLBACK_THROW",
        "SURFACE_SNAPSHOT_BEGIN", "VISIBLE_VIEW",
    )
    policy_start = log.index("private static boolean shouldPersist")
    policy_end = log.index("private static void enqueue", policy_start)
    policy = log[policy_start:policy_end]
    accidentally_filtered = [event for event in forbidden_filtered_types if event in policy]
    if accidentally_filtered:
        raise RuntimeError("failure evidence unexpectedly filtered: " + str(accidentally_filtered))

    # This pass must remain observational-only.
    for content, label in ((causality, "causality"), (audit, "surface audit")):
        for token in (
            ".performClick(", ".setVisibility(", ".bringToFront(",
            "GuidedTourState.setStep(", "GuidedTourState.advance(", "FieldMapState.set",
        ):
            if token in content:
                raise RuntimeError(f"production diagnostics observational guard failed: {label} contains {token}")


def main() -> int:
    for path in (LOG, CAUSALITY, AUDIT):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: read(path) for path in (LOG, CAUSALITY, AUDIT)}
    try:
        patch_logger()
        patch_recent_user_attribution()
        patch_snapshot_policy()
        validate()
        print("Production diagnostics noise/retention policy complete.")
        print("Routine telemetry sampled; failure evidence remains lossless; startup + recent tail retained.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("Production diagnostics noise policy rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
