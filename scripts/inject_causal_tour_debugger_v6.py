#!/usr/bin/env python3
"""Compatibility runner for causal v5 on the existing causal-v1 clean-start audit.

Causal v1 already inserts TourDebugSurfaceAudit between TOUR_PREPARE_COMPLETE and return clean.
This wrapper keeps that audit and places v5's deeper cleanup audits immediately after it.
"""
import inject_causal_tour_debugger_v5 as v5


def main() -> int:
    original_replace = v5.replace_once

    def compatible_replace(path, marker, old, new, label):
        if marker != "causal-v5-tour-cleanup-after":
            return original_replace(path, marker, old, new, label)

        compatible_old = '''        UiInvariantMonitor.state(this, transition, "TOUR_PREPARE_COMPLETE",
                "origin=" + origin + " clean=" + clean);
        TourDebugSurfaceAudit.auditTourCleanStart(this, origin);
        // marker: causal-debug-clean-start-audit
        return clean;
'''
        compatible_new = '''        UiInvariantMonitor.state(this, transition, "TOUR_PREPARE_COMPLETE",
                "origin=" + origin + " clean=" + clean);
        TourDebugSurfaceAudit.auditTourCleanStart(this, origin);
        // marker: causal-debug-clean-start-audit
        debugAuditTourCleanup(transition, "immediate", cleanupSavedBaseline,
                cleanupSavedResearchBaseline, true);
        debugSchedulePostCleanupAudits(transition, cleanupSavedBaseline,
                cleanupSavedResearchBaseline);
        // marker: causal-v5-tour-cleanup-after
        return clean;
'''
        return original_replace(path, marker, compatible_old, compatible_new, label)

    v5.replace_once = compatible_replace
    try:
        result = v5.main()
        print("Causal debugger v6 compatibility anchor complete.")
        return 0 if result is None else int(result)
    finally:
        v5.replace_once = original_replace


if __name__ == "__main__":
    raise SystemExit(main())
