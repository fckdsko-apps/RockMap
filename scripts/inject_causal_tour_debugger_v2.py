#!/usr/bin/env python3
"""Run HUD fixes, Track usability, Commit 4 integration, then causal debugger passes."""

from inject_ui_state_debug_v8 import main as inject_ui_state_fixes_v8
from inject_ui_state_debug_v11 import main as inject_ui_state_fixes_v11
from inject_ui_state_debug_v10 import main as inject_ui_state_fixes_v10
from inject_ui_state_debug_v12 import main as inject_ui_state_fixes_v12
from inject_ui_state_debug_v13 import main as inject_ui_state_fixes_v13
from inject_track_hidden_notice import main as inject_track_hidden_notice
from inject_track_row_delete import main as inject_track_row_delete
from inject_commit4_trip_areas import main as inject_commit4_trip_areas
from inject_causal_tour_debugger_v2_impl import main as inject_causal_tour_debugger_v2_impl
from inject_causal_tour_debugger_v3 import main as inject_causal_tour_debugger_v3
from inject_causal_tour_debugger_v4 import main as inject_causal_tour_debugger_v4
from inject_causal_tour_debugger_v6 import main as inject_causal_tour_debugger_v6


def main() -> int:
    for injector in (
        inject_ui_state_fixes_v8,
        inject_ui_state_fixes_v11,
        inject_ui_state_fixes_v10,
        inject_ui_state_fixes_v12,
        inject_ui_state_fixes_v13,
        # Commit 3 remains intentionally small: one visibility model plus a hidden-state notice.
        inject_track_hidden_notice,
        # Track-list management is independent of whether line geometry is mappable.
        inject_track_row_delete,
        # Commit 4A: Prospecting Areas become first-class in Layers and Trips without schema churn.
        inject_commit4_trip_areas,
        inject_causal_tour_debugger_v2_impl,
        inject_causal_tour_debugger_v3,
        inject_causal_tour_debugger_v4,
        inject_causal_tour_debugger_v6,
    ):
        result = injector()
        if result not in (None, 0):
            return int(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
