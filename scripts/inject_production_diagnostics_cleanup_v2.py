#!/usr/bin/env python3
"""Production diagnostics cleanup v2.

Runs after the existing production diagnostics retention/noise policy. This pass is strictly
observational: it reduces routine log volume, carries a very recent same-Activity USER cause into
immediate diagnostic bookkeeping/scheduled debugger work, and makes the tour no-progress watchdog
aware of already-pending tour callbacks.

It also fail-closes if the production diagnostics UI/export contract disappears. In particular,
diagnostics must remain opt-in, user-toggleable, manually exportable through Android's document
picker, and must never automatically write a diagnostics text file into shared storage.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
LOG = ROOT / "app/src/main/java/com/rockmap/app/TourDebugLog.java"
CAUSALITY = ROOT / "app/src/main/java/com/rockmap/app/TourDebugCausality.java"
PANEL = ROOT / "app/src/main/java/com/rockmap/app/DiagnosticsPanel.java"


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


def regex_replace_once(path: Path, marker: str, pattern: str, replacement: str, label: str) -> None:
    current = read(path)
    if marker in current:
        print(f"{label}: already present")
        return
    updated, count = re.subn(pattern, replacement, current, count=1, flags=re.DOTALL)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one regex match in {path.relative_to(ROOT)}, found {count}"
        )
    path.write_text(updated, encoding="utf-8")
    print(f"{label}: injected")


def patch_logger() -> None:
    regex_replace_once(
        LOG,
        "production-diagnostics-cleanup-v2-persist-policy",
        r'''    private static boolean shouldPersist\(String type, String detail\) \{.*?    \} // marker: production-diagnostics-persist-policy''',
        '''    private static boolean shouldPersist(String type, String detail) {
        String safeType = type == null ? "" : type;
        String safeDetail = detail == null ? "" : detail;
        long now = SystemClock.elapsedRealtime();
        synchronized (RATE_LIMIT_LOCK) {
            // These were the remaining routine floods in the causal-v5 field log. State/source/
            // accuracy/policy transitions remain logged under their own event types, so raw sensor
            // samples, repeated output values, and repeated compass renders add volume but no new
            // causal evidence.
            if ("HEADING_OUTPUT".equals(safeType)
                    || "HEADING_SENSOR_SAMPLE".equals(safeType)
                    || "COMPASS_RENDER".equals(safeType)) {
                return false;
            }

            // Aggregate FIELD_RECORD_ZOOM_RENDER remains available. Per-record rows are only useful
            // when a dedicated anomaly is emitted for a specific record; routine camera-idle dumps
            // were the single largest avoidable source of log volume.
            if ("FIELD_RECORD_ZOOM_ITEM".equals(safeType)) return false;

            if ("TRACK_GEOJSON_BUILD".equals(safeType)
                    && safeDetail.contains("activeTrackId=-1")) {
                if (!elapsedGate(now, lastInactiveTrackGeoJsonElapsed, 10000L)) return false;
                lastInactiveTrackGeoJsonElapsed = now;
            } else if ("TRACK_MAP_RENDER_STATE".equals(safeType)
                    && safeDetail.contains("activeTrackId=-1")) {
                if (!elapsedGate(now, lastInactiveTrackRenderElapsed, 10000L)) return false;
                lastInactiveTrackRenderElapsed = now;
            }

            // Successful periodic refreshes are represented by the resulting state/invariant
            // events. Retain full HUD lifecycle evidence for user/tour transitions and failures,
            // but do not persist unchanged periodic START/BUILT/READY triplets.
            if (safeDetail.contains("reason=periodic_refresh")
                    && ("HUD_RENDER_START".equals(safeType)
                        || "HUD_RENDER_BUILT".equals(safeType)
                        || "HUD_READY".equals(safeType))) {
                return false;
            }
        }
        return true;
    } // marker: production-diagnostics-persist-policy
      // marker: production-diagnostics-cleanup-v2-persist-policy''',
        "tight production persistence policy",
    )


def patch_causality() -> None:
    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v2-pending-step",
        '''        volatile String preparation = "";
        volatile String targetLabel = "";
''',
        '''        volatile String preparation = "";
        volatile String targetLabel = "";
        volatile int pendingTourCallbacks;
        // marker: production-diagnostics-cleanup-v2-pending-step
''',
        "pending tour callback bookkeeping",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v2-recent-user-helper",
        '''    public static Scope begin(Activity activity, String origin, String action, String target) {
''',
        '''    private static Cause recentSameActivityUserCause(Activity activity, long maxAgeMs) {
        if (activity == null || maxAgeMs < 0L) return null;
        synchronized (LOCK) {
            Activity userActivity = lastUserActivity == null ? null : lastUserActivity.get();
            if (userActivity != activity || lastUserElapsed <= 0L
                    || "none".equals(lastUserCauseId)) return null;
            long age = Math.max(0L, SystemClock.elapsedRealtime() - lastUserElapsed);
            if (age > maxAgeMs) return null;
            return new Cause(lastUserCauseId, ORIGIN_USER, "recent-user-continuation",
                    lastUserTarget, "", lastUserElapsed);
        }
    } // marker: production-diagnostics-cleanup-v2-recent-user-helper

    public static Scope begin(Activity activity, String origin, String action, String target) {
''',
        "recent same-Activity user cause helper",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v2-scheduled-parent",
        '''        final Cause schedulingCause = CURRENT.get();
        final String parentId = schedulingCause == null ? "" : schedulingCause.id;
        final String callbackId = "CB" + NEXT_CALLBACK.getAndIncrement();
''',
        '''        Cause directSchedulingCause = CURRENT.get();
        final Cause schedulingCause = directSchedulingCause != null
                ? directSchedulingCause : recentSameActivityUserCause(activity, 350L);
        final String parentId = schedulingCause == null ? "" : schedulingCause.id;
        final long pendingStepSerial;
        synchronized (LOCK) {
            StepState live = currentStep;
            if (live != null && ORIGIN_TOUR.equals(empty(origin, ORIGIN_CALLBACK))) {
                live.pendingTourCallbacks++;
                pendingStepSerial = live.serial;
            } else {
                pendingStepSerial = -1L;
            }
        }
        // marker: production-diagnostics-cleanup-v2-scheduled-parent
        final String callbackId = "CB" + NEXT_CALLBACK.getAndIncrement();
''',
        "carry recent USER parent into immediate scheduled debugger work",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v2-callback-start",
        '''        return () -> {
            Cause previous = CURRENT.get();
''',
        '''        return () -> {
            if (pendingStepSerial > 0L) {
                synchronized (LOCK) {
                    StepState live = currentStep;
                    if (live != null && live.serial == pendingStepSerial
                            && live.pendingTourCallbacks > 0) {
                        live.pendingTourCallbacks--;
                    }
                }
            }
            // marker: production-diagnostics-cleanup-v2-callback-start
            Cause previous = CURRENT.get();
''',
        "clear pending tour callback when callback begins",
    )

    regex_replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v2-state-mutation",
        r'''    public static void stateMutation\(Activity activity, String surface, String before,\n                                     String after, String detail\) \{.*?\n    \} // marker: production-diagnostics-recent-user-state-attribution''',
        '''    public static void stateMutation(Activity activity, String surface, String before,
                                     String after, String detail) {
        Cause previous = CURRENT.get();
        Cause effective = previous != null ? previous : recentSameActivityUserCause(activity, 350L);
        boolean continuedUserCause = previous == null && effective != null;
        if (continuedUserCause) CURRENT.set(effective);
        try {
            String event = effective == null ? "UNATTRIBUTED_STATE_CHANGE" : "STATE_MUTATION";
            TourDebugLog.causalEvent(event,
                    "activity=" + activityName(activity)
                            + " surface=" + clean(surface, 120)
                            + " before=" + clean(before, 180)
                            + " after=" + clean(after, 180)
                            + " " + contextSummary()
                            + (continuedUserCause ? " attribution=recent-same-activity-user" : "")
                            + " detail=" + clean(detail, 800));
            if (effective == null) {
                finding(activity, "WARNING", "UNATTRIBUTED_STATE_CHANGE",
                        "surface=" + clean(surface, 120)
                                + " before=" + clean(before, 120)
                                + " after=" + clean(after, 120));
            }
        } finally {
            if (continuedUserCause) CURRENT.set(previous);
        }
    } // marker: production-diagnostics-recent-user-state-attribution
      // marker: production-diagnostics-cleanup-v2-state-mutation''',
        "use a real recent USER cause for immediate state mutations",
    )

    regex_replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v2-no-progress-watchdog",
        r'''            if \(live\.preparedElapsed == 0L && live\.targetVerifiedElapsed == 0L\n                    && live\.coachRequestedElapsed == 0L\) \{\n                finding\(lastResumedActivity\(\), "ERROR", "STEP_NO_PROGRESS",\n                        "step=" \+ live\.step \+ " elapsedMs="\n                                \+ Math\.max\(0L, SystemClock\.elapsedRealtime\(\) - live\.enteredElapsed\)\);\n            \}\n        \}, 1200L, TimeUnit\.MILLISECONDS\);''',
        '''            if (live.preparedElapsed == 0L && live.targetVerifiedElapsed == 0L
                    && live.coachRequestedElapsed == 0L
                    && live.pendingTourCallbacks == 0) {
                finding(lastResumedActivity(), "WARNING", "STEP_NO_PROGRESS",
                        "step=" + live.step
                                + " pendingTourCallbacks=" + live.pendingTourCallbacks
                                + " elapsedMs="
                                + Math.max(0L, SystemClock.elapsedRealtime() - live.enteredElapsed));
            }
        }, 3500L, TimeUnit.MILLISECONDS);
        // marker: production-diagnostics-cleanup-v2-no-progress-watchdog''',
        "make STEP_NO_PROGRESS delayed, warning-only, and callback-aware",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v2-hard-watchdog-detail",
        '''                                + " coachRequested=" + (live.coachRequestedElapsed > 0L)
                                + " elapsedMs=" + Math.max(0L,
''',
        '''                                + " coachRequested=" + (live.coachRequestedElapsed > 0L)
                                + " pendingTourCallbacks=" + live.pendingTourCallbacks
                                + " elapsedMs=" + Math.max(0L,
                                // marker: production-diagnostics-cleanup-v2-hard-watchdog-detail
''',
        "include pending callback state in hard watchdog",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v2-schema",
        '''    public static final String SCHEMA = "causal-v5"; // marker: causal-v4-schema
      // marker: causal-v5-schema
''',
        '''    public static final String SCHEMA = "causal-v6-prod"; // marker: causal-v4-schema
      // marker: causal-v5-schema
      // marker: production-diagnostics-cleanup-v2-schema
''',
        "production causal schema identity",
    )


def validate_contract() -> None:
    log = read(LOG)
    causality = read(CAUSALITY)
    panel = read(PANEL)

    required_panel = (
        'toggle.setText(enabled ? "Diagnostics: on — tap to turn off"',
        '"Diagnostics: off — tap to enable"',
        'Button export = button(activity, "Export diagnostics")',
        'Button clear = button(activity, "Clear diagnostics")',
        'new Intent(Intent.ACTION_CREATE_DOCUMENT)',
        'TourDebugLog.exportTo(activity, data.getData())',
    )
    missing_panel = [token for token in required_panel if token not in panel]
    if missing_panel:
        raise RuntimeError("production diagnostics UI/export contract regressed: " + str(missing_panel))

    required_log = (
        'private static final String DIAGNOSTICS_PREFS = "rockmap_diagnostics";',
        'private static final String KEY_ENABLED = "enabled";',
        'public static boolean isEnabled(Context context)',
        'public static void setEnabled(Context context, boolean value)',
        'public static boolean exportTo(Context context, Uri uri)',
        'public static boolean clear(Context context)',
        'if (!enabled() || app == null || internalLog == null) return;',
        'production-diagnostics-cleanup-v2-persist-policy',
    )
    missing_log = [token for token in required_log if token not in log]
    if missing_log:
        raise RuntimeError("production diagnostics logger contract regressed: " + str(missing_log))

    forbidden_auto_export = (
        "MediaStore.Downloads",
        "publishMirror",
        "scheduleMirror",
        'FILE_NAME = "RockMap-Tour-Debug.txt"',
    )
    present_auto = [token for token in forbidden_auto_export if token in log]
    if present_auto:
        raise RuntimeError("automatic shared-storage diagnostics export reintroduced: " + str(present_auto))

    required_causal = (
        'SCHEMA = "causal-v6-prod"',
        'recentSameActivityUserCause(activity, 350L)',
        'pendingTourCallbacks',
        '3500L, TimeUnit.MILLISECONDS',
        '"WARNING", "STEP_NO_PROGRESS"',
    )
    missing_causal = [token for token in required_causal if token not in causality]
    if missing_causal:
        raise RuntimeError("production causal cleanup incomplete: " + str(missing_causal))

    # Preserve failure evidence. The persistence filter may not name/filter these event types.
    policy_start = log.index("private static boolean shouldPersist")
    policy_end = log.index("private static void enqueue", policy_start)
    policy = log[policy_start:policy_end]
    failure_events = (
        "DEBUG_FINDING", "UNCAUGHT_EXCEPTION", "MAIN_THREAD_STALL", "OP_ERROR",
        "WORK_FAILURE", "DOWNLOAD_ERROR", "CNGM_QUERY_ERROR", "CALLBACK_THROW",
        "SURFACE_SNAPSHOT_BEGIN", "VISIBLE_VIEW",
    )
    filtered_failures = [event for event in failure_events if event in policy]
    if filtered_failures:
        raise RuntimeError("failure evidence unexpectedly filtered: " + str(filtered_failures))


def validate_observational_only(originals) -> None:
    for path in (LOG, CAUSALITY):
        before = originals[path]
        after = read(path)
        for forbidden in (
            ".performClick(", ".setVisibility(", ".bringToFront(", ".requestLayout(",
            ".invalidate(", "GuidedTourState.setStep(", "GuidedTourState.advance(",
            "FieldMapState.set", "MapHudCoordinator.beforeExpand(", "deleteTrack(",
            "deleteArea(", "deleteFieldRecord("
        ):
            if after.count(forbidden) > before.count(forbidden):
                raise RuntimeError(
                    f"production diagnostics cleanup v2 introduced app mutation token {forbidden}"
                )


def main() -> int:
    for path in (LOG, CAUSALITY, PANEL):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: read(path) for path in (LOG, CAUSALITY, PANEL)}
    try:
        patch_logger()
        patch_causality()
        validate_contract()
        validate_observational_only(originals)
        print("Production diagnostics cleanup v2 complete.")
        print("Opt-in toggle/manual export preserved; routine floods suppressed; callback-aware watchdog active.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("Production diagnostics cleanup v2 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
