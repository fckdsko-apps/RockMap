#!/usr/bin/env python3
"""Run v8 HUD follow-up, causal v2, readiness v3, then startup-retention/I-O v4."""

from inject_ui_state_debug_v8 import main as inject_ui_state_fixes_v8
from inject_causal_tour_debugger_v2_impl import main as inject_causal_tour_debugger_v2_impl
from inject_causal_tour_debugger_v3 import main as inject_causal_tour_debugger_v3
from inject_causal_tour_debugger_v4 import main as inject_causal_tour_debugger_v4


def main() -> int:
    result = inject_ui_state_fixes_v8()
    if result not in (None, 0):
        return int(result)
    result = inject_causal_tour_debugger_v2_impl()
    if result not in (None, 0):
        return int(result)
    result = inject_causal_tour_debugger_v3()
    if result not in (None, 0):
        return int(result)
    result = inject_causal_tour_debugger_v4()
    return 0 if result is None else int(result)


if __name__ == "__main__":
    raise SystemExit(main())
