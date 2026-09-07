#!/usr/bin/env python3
"""Expose production diagnostics controls from the existing Technical diagnostics dialog.

Presentation-only. The production logger/storage contract lives in TourDebugLog and DiagnosticsPanel;
this pass only makes those already-source-owned controls reachable from MainActivity without changing
Offline Data installation behavior, map state, tours, or CNGM search semantics.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
PANEL = ROOT / "app/src/main/java/com/rockmap/app/DiagnosticsPanel.java"
MARKER = "production-diagnostics-controls-visible"

OLD = '''    private void showDataDiagnostics() {
        String diagnostics = "RockMap " + BuildConfig.VERSION_NAME
                + (mapController == null ? "\\n\\nMap diagnostics unavailable."
                    : "\\n\\n" + mapController.describeLabelDiagnostics()
                    + "\\n\\n" + mapController.describeLandDiagnostics()
                    + "\\n\\n" + mapController.describeClaimsDiagnostics());

        TextView body = new TextView(this);
        body.setText(diagnostics);
        body.setTextSize(12.5f);
        body.setTextColor(Color.rgb(55, 55, 55));
        body.setTextIsSelectable(true);
        body.setPadding(dp(18), dp(8), dp(18), dp(8));

        new AlertDialog.Builder(this)
                .setTitle("Technical diagnostics")
                .setView(boundedScrollableContent(body, 440))
                .setPositiveButton("Close", null)
                .show();
    }
'''

NEW = '''    private void showDataDiagnostics() {
        String diagnostics = "RockMap " + BuildConfig.VERSION_NAME
                + (mapController == null ? "\\n\\nMap diagnostics unavailable."
                    : "\\n\\n" + mapController.describeLabelDiagnostics()
                    + "\\n\\n" + mapController.describeLandDiagnostics()
                    + "\\n\\n" + mapController.describeClaimsDiagnostics());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(4), dp(18), dp(8));

        DiagnosticsPanel.addTo(this, content); // marker: production-diagnostics-controls-visible

        TextView readoutHeading = new TextView(this);
        readoutHeading.setText("Current technical state");
        readoutHeading.setTextSize(15f);
        readoutHeading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        readoutHeading.setTextColor(Color.rgb(35, 35, 35));
        readoutHeading.setPadding(0, dp(14), 0, dp(4));
        content.addView(readoutHeading);

        TextView body = new TextView(this);
        body.setText(diagnostics);
        body.setTextSize(12.5f);
        body.setTextColor(Color.rgb(55, 55, 55));
        body.setTextIsSelectable(true);
        body.setPadding(0, 0, 0, dp(4));
        content.addView(body);

        new AlertDialog.Builder(this)
                .setTitle("Technical diagnostics")
                .setView(boundedScrollableContent(content, 520))
                .setPositiveButton("Close", null)
                .show();
    }
'''


def main() -> int:
    for path in (MAIN, PANEL):
        if not path.is_file():
            raise RuntimeError(f"required source missing: {path.relative_to(ROOT)}")

    current = MAIN.read_text(encoding="utf-8")
    if MARKER in current:
        required = (
            "DiagnosticsPanel.addTo(this, content)",
            'readoutHeading.setText("Current technical state")',
            '.setTitle("Technical diagnostics")',
        )
        missing = [token for token in required if token not in current]
        if missing:
            raise RuntimeError("production diagnostics UI marker present but contract incomplete: " + ", ".join(missing))
        print("Production diagnostics controls: already present")
        return 0

    count = current.count(OLD)
    if count != 1:
        raise RuntimeError(
            f"Production diagnostics controls: expected exactly one Technical diagnostics method anchor, found {count}"
        )

    updated = current.replace(OLD, NEW, 1)
    if updated.count('Button diagnostics = smallActionButton("Technical diagnostics")') != current.count(
            'Button diagnostics = smallActionButton("Technical diagnostics")'):
        raise RuntimeError("Production diagnostics UI unexpectedly changed Offline Data entry control")
    if "DiagnosticsPanel.addTo(this, content)" not in updated:
        raise RuntimeError("Production diagnostics UI postcondition missing")

    MAIN.write_text(updated, encoding="utf-8")
    print("Production diagnostics controls exposed through Technical diagnostics.")
    print("Scope: presentation-only; existing technical readout retained below opt-in controls.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
