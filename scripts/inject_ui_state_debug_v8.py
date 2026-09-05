#!/usr/bin/env python3
"""v8 follow-up for v7: qualify diagnostics and install the fast-reopen body.

The v7 helper marker accidentally shadowed its body marker. This pass is intentionally tiny and
fails fast if the v2 explicit-expansion body is not exactly where expected.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def replace_once(path: Path, marker: str, old: str, new: str, label: str) -> None:
    current = text(path)
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


def main() -> int:
    context = ROOT / "app/src/main/java/com/rockmap/app/map/MapContextCloseController.java"
    research = ROOT / "app/src/main/java/com/rockmap/app/research/ResearchAreaPanelController.java"
    for path in (context, research):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: text(path) for path in (context, research)}
    try:
        # These subpackages do not import TourDebugLog in baseline source; use a fully-qualified
        # reference rather than expanding production imports just for tour-debug diagnostics.
        for path in (context, research):
            current = text(path)
            current = current.replace(
                    "TourDebugLog.mapDiagnostic(",
                    "com.rockmap.app.TourDebugLog.mapDiagnostic(")
            path.write_text(current, encoding="utf-8")
        print("v8 fully-qualified HUD diagnostics: injected")

        replace_once(
            context,
            "v8-fast-mapped-reopen-body",
            '''        explicitExpansionInProgress = true;
        try {
            if (map != null) refreshNow();
            else ensureViews();
        } finally {
            explicitExpansionInProgress = false;
        }
        notifyPresentationReady(menu);
''',
            '''        explicitExpansionInProgress = true;
        try {
            ensureViews();
            if (!showExistingExpandedMenuFast()) {
                // First presentation still needs to build its rows. Subsequent reopen taps should
                // never synchronously perform DB/map refresh work on the touch dispatch path.
                if (map != null) refreshNow();
            } else {
                // Reconcile changing mapped content after the visible transition, asynchronously.
                refresh();
            }
        } finally {
            explicitExpansionInProgress = false;
        }
        notifyPresentationReady(menu);
        // marker: v8-fast-mapped-reopen-body
''',
            "v8 fast mapped three-dot reopen body",
        )

        print("HUD corrective v8 follow-up complete.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("HUD corrective v8 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
