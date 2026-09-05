#!/usr/bin/env python3
"""Run HUD follow-up, cleanup/routing fixes, Commit 3 Track trace, then causal debugger passes."""

from inject_ui_state_debug_v8 import main as inject_ui_state_fixes_v8
from inject_ui_state_debug_v11 import main as inject_ui_state_fixes_v11
from inject_ui_state_debug_v10 import main as inject_ui_state_fixes_v10
from inject_ui_state_debug_v12 import main as inject_ui_state_fixes_v12
from inject_ui_state_debug_v13 import main as inject_ui_state_fixes_v13
from inject_track_live_trace import main as inject_track_live_trace
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
        # Commit 3 is a behavior pass, so run it before observational causal diagnostics.
        inject_track_live_trace,
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
