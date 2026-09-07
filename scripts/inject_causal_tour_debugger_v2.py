#!/usr/bin/env python3
"""Run HUD fixes, Track usability, Commit 4 integration, selectable text, then debuggers."""

from inject_ui_state_debug_v8 import main as inject_ui_state_fixes_v8
from inject_ui_state_debug_v11 import main as inject_ui_state_fixes_v11
from inject_ui_state_debug_v10 import main as inject_ui_state_fixes_v10
from inject_ui_state_debug_v12 import main as inject_ui_state_fixes_v12
from inject_ui_state_debug_v13 import main as inject_ui_state_fixes_v13
from inject_track_hidden_notice import main as inject_track_hidden_notice
from inject_track_row_delete import main as inject_track_row_delete
from inject_commit4_trip_areas import main as inject_commit4_trip_areas
from inject_selectable_informational_text import main as inject_selectable_informational_text
from inject_production_diagnostics_ui import main as inject_production_diagnostics_ui
from inject_causal_tour_debugger_v2_impl import main as inject_causal_tour_debugger_v2_impl
from inject_causal_tour_debugger_v3 import main as inject_causal_tour_debugger_v3
from inject_causal_tour_debugger_v4 import main as inject_causal_tour_debugger_v4
from inject_causal_tour_debugger_v6 import main as inject_causal_tour_debugger_v6
from inject_production_diagnostics_noise_policy import main as inject_production_diagnostics_noise_policy


def main() -> int:
    for injector in (
        inject_ui_state_fixes_v8,
        inject_ui_state_fixes_v11,
        inject_ui_state_fixes_v10,
        inject_ui_state_fixes_v12,
        inject_ui_state_fixes_v13,
        inject_track_hidden_notice,
        inject_track_row_delete,
        inject_commit4_trip_areas,
        # Presentation-only rule: non-interactive reference text should be selectable/copyable.
        inject_selectable_informational_text,
        # Production diagnostics controls must be reachable from the existing Technical diagnostics UI.
        inject_production_diagnostics_ui,
        inject_causal_tour_debugger_v2_impl,
        inject_causal_tour_debugger_v3,
        inject_causal_tour_debugger_v4,
        inject_causal_tour_debugger_v6,
        # Final production policy runs after legacy causal injectors so test hooks stay available
        # while routine persistence remains compact and false-positive warnings are suppressed.
        inject_production_diagnostics_noise_policy,
    ):
        result = injector()
        if result not in (None, 0):
            return int(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
