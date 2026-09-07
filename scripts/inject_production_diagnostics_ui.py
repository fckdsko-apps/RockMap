#!/usr/bin/env python3
"""Expose production diagnostics controls from the existing Technical diagnostics dialog.

Presentation/export plumbing only. The production logger/storage contract lives in TourDebugLog and
DiagnosticsPanel. This pass makes the source-owned controls reachable from MainActivity and routes
Android's user-selected document-picker result back to DiagnosticsPanel without changing Offline
Data installation behavior, map state, tours, or CNGM search semantics.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
PANEL = ROOT / "app/src/main/java/com/rockmap/app/DiagnosticsPanel.java"
UI_MARKER = "production-diagnostics-controls-visible"
RESULT_MARKER = "production-diagnostics-export-result"

OLD_UI = '''    private void showDataDiagnostics() {
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

NEW_UI = '''    private void showDataDiagnostics() {
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

OLD_RESULT = '''    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RESEARCH_REQUEST) {
'''

NEW_RESULT = '''    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (DiagnosticsPanel.handleActivityResult(this, requestCode, resultCode, data)) return; // marker: production-diagnostics-export-result
        if (requestCode == RESEARCH_REQUEST) {
'''


def replace_once(current: str, marker: str, old: str, new: str, label: str) -> str:
    if marker in current:
        print(f"{label}: already present")
        return current
    count = current.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one anchor, found {count}")
    print(f"{label}: injected")
    return current.replace(old, new, 1)


def main() -> int:
    for path in (MAIN, PANEL):
        if not path.is_file():
            raise RuntimeError(f"required source missing: {path.relative_to(ROOT)}")

    panel = PANEL.read_text(encoding="utf-8")
    required_panel = (
        "Intent.ACTION_CREATE_DOCUMENT",
        "TourDebugLog.suggestedExportFileName()",
        "public static boolean handleActivityResult(",
        "TourDebugLog.exportTo(activity, data.getData())",
    )
    missing_panel = [token for token in required_panel if token not in panel]
    if missing_panel:
        raise RuntimeError("production diagnostics user-selected export contract incomplete: "
                           + ", ".join(missing_panel))
    forbidden_panel = (
        "MediaStore.Downloads",
        "Environment.DIRECTORY_DOWNLOADS",
        "Downloads/RockMap",
        "createExportDestination",
    )
    present_forbidden = [token for token in forbidden_panel if token in panel]
    if present_forbidden:
        raise RuntimeError("production diagnostics still contains fixed export destination: "
                           + ", ".join(present_forbidden))

    original = MAIN.read_text(encoding="utf-8")
    updated = replace_once(original, UI_MARKER, OLD_UI, NEW_UI,
                           "Production diagnostics controls")
    updated = replace_once(updated, RESULT_MARKER, OLD_RESULT, NEW_RESULT,
                           "Production diagnostics export result routing")

    if updated.count('Button diagnostics = smallActionButton("Technical diagnostics")') != original.count(
            'Button diagnostics = smallActionButton("Technical diagnostics")'):
        raise RuntimeError("Production diagnostics UI unexpectedly changed Offline Data entry control")

    required_main = (
        "DiagnosticsPanel.addTo(this, content)",
        "DiagnosticsPanel.handleActivityResult(this, requestCode, resultCode, data)",
        'readoutHeading.setText("Current technical state")',
        '.setTitle("Technical diagnostics")',
    )
    missing_main = [token for token in required_main if token not in updated]
    if missing_main:
        raise RuntimeError("production diagnostics MainActivity postcondition missing: "
                           + ", ".join(missing_main))

    MAIN.write_text(updated, encoding="utf-8")
    print("Production diagnostics controls exposed through Technical diagnostics.")
    print("Diagnostics export uses Android's user-selected document destination.")
    print("Scope: UI/export plumbing only; existing technical readout retained below opt-in controls.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
