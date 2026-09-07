#!/usr/bin/env python3
"""Production diagnostics cleanup v3.

Runs after cleanup v2. This pass is deliberately limited to diagnostics behavior:
1) carry a very recent USER cause across the explicitly known ResearchActivity <-> MainActivity
   handoff, without broadening causal inference to arbitrary activities; and
2) keep every DEBUG_FINDING while deduplicating repeated full surface/view-tree snapshots for the
   same stable finding signature during a short window.

No application UI, feature state, tour state, persistence behavior, or user data is modified.
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


def patch_known_activity_handoff() -> None:
    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v3-known-handoff-helper",
        '''    } // marker: production-diagnostics-cleanup-v2-recent-user-helper

    public static Scope begin(Activity activity, String origin, String action, String target) {
''',
        '''    } // marker: production-diagnostics-cleanup-v2-recent-user-helper

    private static boolean isKnownDiagnosticsActivityHandoff(Activity fromActivity,
                                                              Activity toActivity) {
        if (fromActivity == null || toActivity == null || fromActivity == toActivity) return false;
        String from = activityName(fromActivity);
        String to = activityName(toActivity);
        return ("ResearchActivity".equals(from) && "MainActivity".equals(to))
                || ("MainActivity".equals(from) && "ResearchActivity".equals(to));
    }

    private static Cause recentUserCauseForActivity(Activity activity, long maxAgeMs) {
        Cause sameActivity = recentSameActivityUserCause(activity, maxAgeMs);
        if (sameActivity != null) return sameActivity;
        if (activity == null || maxAgeMs < 0L) return null;
        synchronized (LOCK) {
            Activity userActivity = lastUserActivity == null ? null : lastUserActivity.get();
            if (userActivity == null || userActivity == activity
                    || lastUserElapsed <= 0L
                    || lastUserCauseId == null
                    || "none".equals(lastUserCauseId)
                    || !isKnownDiagnosticsActivityHandoff(userActivity, activity)) {
                return null;
            }
            long age = Math.max(0L, SystemClock.elapsedRealtime() - lastUserElapsed);
            if (age > maxAgeMs) return null;
            return new Cause(lastUserCauseId, ORIGIN_USER, "recent-user-handoff",
                    lastUserTarget, "", lastUserElapsed);
        }
    } // marker: production-diagnostics-cleanup-v3-known-handoff-helper

    public static Scope begin(Activity activity, String origin, String action, String target) {
''',
        "narrow known Activity handoff helper",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v3-scheduled-handoff",
        '''                ? directSchedulingCause : recentSameActivityUserCause(activity, 350L);
''',
        '''                ? directSchedulingCause : recentUserCauseForActivity(activity, 350L);
        // marker: production-diagnostics-cleanup-v3-scheduled-handoff
''',
        "allow known handoff parent for immediate scheduled diagnostics work",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v3-state-handoff",
        '''        Cause effective = previous != null ? previous : recentSameActivityUserCause(activity, 350L);
''',
        '''        Cause effective = previous != null ? previous : recentUserCauseForActivity(activity, 350L);
        // marker: production-diagnostics-cleanup-v3-state-handoff
''',
        "allow known handoff cause for immediate state diagnostics",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v3-attribution-label",
        '''                            + (continuedUserCause ? " attribution=recent-same-activity-user" : "")
''',
        '''                            + (continuedUserCause
                                ? " attribution=" + ("recent-user-handoff".equals(effective.action)
                                    ? "known-activity-handoff-user"
                                    : "recent-same-activity-user")
                                : "")
                            // marker: production-diagnostics-cleanup-v3-attribution-label
''',
        "label known handoff attribution explicitly",
    )


def patch_snapshot_dedup() -> None:
    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v3-snapshot-fields",
        '''    private static long lastErrorSnapshotElapsed; // marker: causal-v3-error-snapshot-state
''',
        '''    private static long lastErrorSnapshotElapsed; // marker: causal-v3-error-snapshot-state
    private static final long DUPLICATE_FAILURE_SNAPSHOT_MS = 10000L;
    private static final java.util.HashMap<String, Long> FAILURE_SNAPSHOT_TIMES =
            new java.util.HashMap<>();
    // marker: production-diagnostics-cleanup-v3-snapshot-fields
''',
        "duplicate snapshot timing state",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v3-finding-detail",
        '''        requestFailureSnapshot(activity, severity, code);
''',
        '''        requestFailureSnapshot(activity, severity, code, detail);
        // marker: production-diagnostics-cleanup-v3-finding-detail
''',
        "pass stable finding detail to snapshot dedup",
    )

    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v3-snapshot-dedup",
        '''    private static void requestFailureSnapshot(Activity activity, String severity, String reason) {
        final long now = SystemClock.elapsedRealtime();
        final boolean error = "ERROR".equalsIgnoreCase(empty(severity, ""));
        synchronized (LOCK) {
            if (error) {
                // A recent WARNING must never suppress the first snapshot for a real ERROR.
                if (now - lastErrorSnapshotElapsed < 500L) return;
                lastErrorSnapshotElapsed = now;
                lastFailureSnapshotElapsed = now;
            } else {
                if (now - lastFailureSnapshotElapsed < 500L) return;
                lastFailureSnapshotElapsed = now;
            }
        }
        // marker: causal-v3-severity-aware-snapshot-call
        Activity target = activity != null ? activity : lastResumedActivity();
        if (target == null || target.isFinishing() || target.isDestroyed()) return;
        target.runOnUiThread(() -> TourDebugSurfaceAudit.snapshot(target,
                "finding:" + clean(reason, 100)));
    }
''',
        '''    private static String failureSnapshotSignature(Activity activity, String reason,
                                                   String detail) {
        String stable = clean(detail, 800);
        stable = stable.replaceAll("\\\\btransition=\\\\d+\\\\b", "transition=*")
                .replaceAll("\\\\belapsedMs=\\\\d+\\\\b", "elapsedMs=*")
                .replaceAll("\\\\bageMs=\\\\d+\\\\b", "ageMs=*")
                .replaceAll("\\\\brequest=\\\\d+\\\\b", "request=*")
                .replaceAll("\\\\bgen=\\\\d+\\\\b", "gen=*");
        return activityName(activity) + "|" + clean(reason, 100) + "|" + stable;
    }

    private static void requestFailureSnapshot(Activity activity, String severity, String reason,
                                               String detail) {
        final long now = SystemClock.elapsedRealtime();
        final boolean error = "ERROR".equalsIgnoreCase(empty(severity, ""));
        final String signature = failureSnapshotSignature(activity, reason, detail);
        final long duplicateAgeMs;
        synchronized (LOCK) {
            Long previousSame = FAILURE_SNAPSHOT_TIMES.get(signature);
            if (previousSame != null
                    && now - previousSame >= 0L
                    && now - previousSame < DUPLICATE_FAILURE_SNAPSHOT_MS) {
                duplicateAgeMs = now - previousSame;
            } else {
                duplicateAgeMs = -1L;
                if (error) {
                    // Preserve the existing severity rule: a recent WARNING must never suppress
                    // the first snapshot for a real ERROR.
                    if (now - lastErrorSnapshotElapsed < 500L) return;
                    lastErrorSnapshotElapsed = now;
                    lastFailureSnapshotElapsed = now;
                } else {
                    if (now - lastFailureSnapshotElapsed < 500L) return;
                    lastFailureSnapshotElapsed = now;
                }
                if (FAILURE_SNAPSHOT_TIMES.size() >= 64) FAILURE_SNAPSHOT_TIMES.clear();
                FAILURE_SNAPSHOT_TIMES.put(signature, now);
            }
        }
        if (duplicateAgeMs >= 0L) {
            TourDebugLog.causalEvent("SURFACE_SNAPSHOT_DEDUP",
                    "activity=" + activityName(activity)
                            + " reason=" + clean(reason, 100)
                            + " duplicateAgeMs=" + duplicateAgeMs
                            + " signature=" + clean(signature, 500));
            return;
        }
        // marker: causal-v3-severity-aware-snapshot-call
        Activity target = activity != null ? activity : lastResumedActivity();
        if (target == null || target.isFinishing() || target.isDestroyed()) return;
        target.runOnUiThread(() -> TourDebugSurfaceAudit.snapshot(target,
                "finding:" + clean(reason, 100)));
    } // marker: production-diagnostics-cleanup-v3-snapshot-dedup
''',
        "deduplicate only repeated full failure snapshots",
    )


def patch_schema() -> None:
    replace_once(
        CAUSALITY,
        "production-diagnostics-cleanup-v3-schema",
        '''    public static final String SCHEMA = "causal-v6-prod"; // marker: causal-v4-schema
''',
        '''    public static final String SCHEMA = "causal-v7-prod"; // marker: causal-v4-schema
      // marker: production-diagnostics-cleanup-v3-schema
''',
        "bump production diagnostics schema",
    )


def validate_contract(originals) -> None:
    causality = read(CAUSALITY)

    required = (
        'SCHEMA = "causal-v7-prod"',
        '"ResearchActivity".equals(from) && "MainActivity".equals(to)',
        '"MainActivity".equals(from) && "ResearchActivity".equals(to)',
        'recentUserCauseForActivity(activity, 350L)',
        '"recent-user-handoff"',
        'known-activity-handoff-user',
        'DUPLICATE_FAILURE_SNAPSHOT_MS = 10000L',
        'SURFACE_SNAPSHOT_DEDUP',
        'requestFailureSnapshot(activity, severity, code, detail)',
        'TourDebugLog.causalEvent("DEBUG_FINDING"',
        'production-diagnostics-cleanup-v2-pending-step',
        'production-diagnostics-cleanup-v2-no-progress-watchdog',
        'causal-v3-severity-aware-snapshot-call',
    )
    missing = [token for token in required if token not in causality]
    if missing:
        raise RuntimeError("production diagnostics cleanup v3 incomplete: " + str(missing))

    # This pass must not touch the production diagnostics controls/export logger at all.
    if read(LOG) != originals[LOG]:
        raise RuntimeError("cleanup v3 unexpectedly changed TourDebugLog.java")
    if read(PANEL) != originals[PANEL]:
        raise RuntimeError("cleanup v3 unexpectedly changed DiagnosticsPanel.java")

    # Every finding remains persisted. Only the expensive snapshot call is deduplicated.
    if causality.count('TourDebugLog.causalEvent("DEBUG_FINDING"') != \
            originals[CAUSALITY].count('TourDebugLog.causalEvent("DEBUG_FINDING"'):
        raise RuntimeError("cleanup v3 changed DEBUG_FINDING emission count")

    before = originals[CAUSALITY]
    for forbidden in (
        ".performClick(", ".setVisibility(", ".bringToFront(", ".requestLayout(",
        ".invalidate(", "GuidedTourState.setStep(", "GuidedTourState.advance(",
        "FieldMapState.set", "MapHudCoordinator.beforeExpand(", "deleteTrack(",
        "deleteArea(", "deleteFieldRecord("
    ):
        if causality.count(forbidden) > before.count(forbidden):
            raise RuntimeError(
                f"production diagnostics cleanup v3 introduced app mutation token {forbidden}"
            )


def main() -> int:
    for path in (LOG, CAUSALITY, PANEL):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: read(path) for path in (LOG, CAUSALITY, PANEL)}
    try:
        if "production-diagnostics-cleanup-v2-state-mutation" not in originals[CAUSALITY]:
            raise RuntimeError("cleanup v3 requires production diagnostics cleanup v2 first")
        patch_known_activity_handoff()
        patch_snapshot_dedup()
        patch_schema()
        validate_contract(originals)
        print("Production diagnostics cleanup v3 complete.")
        print("Only known Activity handoff attribution and duplicate snapshot suppression changed.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("Production diagnostics cleanup v3 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
