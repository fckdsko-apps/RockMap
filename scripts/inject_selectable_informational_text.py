#!/usr/bin/env python3
"""Make non-interactive RockMap information copyable without changing controls.

Scope is deliberately presentation-only: Research HUD result trees, Field pages, Field HUD status
text, and main-map informational detail dialogs. Buttons, editable fields, check/radio controls,
and tappable rows remain interactive.

Geology-specific MainActivity bodies are intentionally left to the later CNGM Stage 2 injector,
which already makes those geology terms/details copyable and relies on exact pre-injection anchors.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
FIELD = ROOT / "app/src/main/java/com/rockmap/app/field/FieldActivity.java"
MAP_FIELD = ROOT / "app/src/main/java/com/rockmap/app/field/FieldMapController.java"
RESEARCH = ROOT / "app/src/main/java/com/rockmap/app/research/ResearchAreaPanelController.java"

FILES = (MAIN, FIELD, MAP_FIELD, RESEARCH)


def replace_once(path: Path, marker: str, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"{label}: already present")
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match in {path.name}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: injected")


def replace_in_region(path: Path, marker: str, start_token: str, end_token: str,
                      old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"{label}: already present")
        return
    start = text.find(start_token)
    if start < 0:
        raise RuntimeError(f"{label}: start token missing")
    end = text.find(end_token, start + len(start_token))
    if end < 0:
        raise RuntimeError(f"{label}: end token missing")
    region = text[start:end]
    count = region.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one regional match, found {count}")
    path.write_text(text[:start] + region.replace(old, new, 1) + text[end:], encoding="utf-8")
    print(f"{label}: injected")


def insert_method_prologue(path: Path, marker: str, method_name: str, line: str, label: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"{label}: already present")
        return True
    pattern = re.compile(r"(^\s*private\s+[^\n{]+\b" + re.escape(method_name) + r"\([^\n]*\)\s*\{\s*$)", re.M)
    match = pattern.search(text)
    if not match:
        return False
    insertion = match.group(1) + "\n" + line + " // marker: " + marker
    path.write_text(text[:match.start()] + insertion + text[match.end():], encoding="utf-8")
    print(f"{label}: injected")
    return True


def main() -> int:
    missing = [str(p.relative_to(ROOT)) for p in FILES if not p.is_file()]
    if missing:
        raise RuntimeError("required source missing: " + ", ".join(missing))
    originals = {p: p.read_text(encoding="utf-8") for p in FILES}
    try:
        replace_once(
            RESEARCH,
            "selectable-research-import",
            "import com.rockmap.app.RockMapDragHandle;\n",
            "import com.rockmap.app.RockMapDragHandle;\nimport com.rockmap.app.SelectableText; // marker: selectable-research-import\n",
            "Research selectable-text import",
        )
        replace_in_region(
            RESEARCH,
            "selectable-research-fixed-tree",
            "    public void setFixedContent(View content) {",
            "    public void clearFixedContent()",
            "        detach(content);\n",
            "        SelectableText.applyToTree(content); // marker: selectable-research-fixed-tree\n        detach(content);\n",
            "Research fixed content selectable",
        )
        replace_in_region(
            RESEARCH,
            "selectable-research-scroll-tree",
            "    public void setScrollableContent(View content) {",
            "    public void clearScrollableContent()",
            "        detach(content);\n",
            "        SelectableText.applyToTree(content); // marker: selectable-research-scroll-tree\n        detach(content);\n",
            "Research result content selectable",
        )
        replace_once(
            RESEARCH,
            "selectable-research-status",
            "        status.setPadding(dp(6), dp(4), dp(6), dp(4));\n",
            "        status.setPadding(dp(6), dp(4), dp(6), dp(4));\n        SelectableText.informational(status); // marker: selectable-research-status\n",
            "Research status selectable",
        )

        replace_once(
            FIELD,
            "selectable-field-import",
            "import com.rockmap.app.MainActivity;\n",
            "import com.rockmap.app.MainActivity;\nimport com.rockmap.app.SelectableText; // marker: selectable-field-import\n",
            "Field selectable-text import",
        )
        replace_in_region(
            FIELD,
            "selectable-field-scroll-tree",
            "    private ScrollView scroll(View content) {",
            "    private void scrollTargetIntoView(",
            "        ScrollView s = new ScrollView(this);\n",
            "        SelectableText.applyToTree(content); // marker: selectable-field-scroll-tree\n        ScrollView s = new ScrollView(this);\n",
            "Field page content selectable",
        )
        replace_in_region(
            FIELD,
            "selectable-field-pinned-tree",
            "    private View pageWithPinnedAction(View content, View action) {",
            "    private TextView title(",
            "        LinearLayout outer = new LinearLayout(this);\n",
            "        SelectableText.applyToTree(content); // marker: selectable-field-pinned-tree\n        LinearLayout outer = new LinearLayout(this);\n",
            "Field pinned-page content selectable",
        )
        for method, marker, label in (
            ("title", "selectable-field-title", "Field titles selectable"),
            ("section", "selectable-field-section", "Field section headings selectable"),
            ("help", "selectable-field-help", "Field help/detail text selectable"),
        ):
            text = FIELD.read_text(encoding="utf-8")
            if marker in text:
                print(f"{label}: already present")
                continue
            start = text.find(f"    private TextView {method}(String text) {{")
            if start < 0:
                raise RuntimeError(f"{label}: method missing")
            end = text.find("    }\n", start)
            region = text[start:end]
            old = "        return t;\n"
            if region.count(old) != 1:
                raise RuntimeError(f"{label}: return anchor missing/ambiguous")
            region = region.replace(old,
                    f"        SelectableText.informational(t); // marker: {marker}\n        return t;\n", 1)
            FIELD.write_text(text[:start] + region + text[end:], encoding="utf-8")
            print(f"{label}: injected")

        replace_once(
            MAP_FIELD,
            "selectable-field-map-import",
            "import com.rockmap.app.RockMapDragHandle;\n",
            "import com.rockmap.app.RockMapDragHandle;\nimport com.rockmap.app.SelectableText; // marker: selectable-field-map-import\n",
            "Field map selectable-text import",
        )
        text = MAP_FIELD.read_text(encoding="utf-8")
        if "selectable-field-hud-text" not in text:
            start = text.find("    private TextView hudText(String text) {")
            if start < 0:
                raise RuntimeError("Field HUD hudText() method missing")
            end = text.find("    }\n", start)
            region = text[start:end]
            old = "        return view;\n"
            if region.count(old) != 1:
                raise RuntimeError("Field HUD hudText() return anchor missing/ambiguous")
            region = region.replace(old,
                    "        SelectableText.informational(view); // marker: selectable-field-hud-text\n        return view;\n", 1)
            MAP_FIELD.write_text(text[:start] + region + text[end:], encoding="utf-8")
            print("Field HUD informational text selectable: injected")
        else:
            print("Field HUD informational text selectable: already present")

        main_bounded = insert_method_prologue(
            MAIN, "selectable-main-bounded-content", "boundedScrollableContent",
            "        SelectableText.applyToTree(content);",
            "Main bounded dialog content selectable")
        if not main_bounded:
            print("Main boundedScrollableContent helper not found; explicit detail coverage remains")

        text = MAIN.read_text(encoding="utf-8")
        marker = "selectable-location-info-detail"
        if marker not in text:
            start_token = "    public void onMapFeaturesTapped("
            end_token = "    private String compactClaimQuality("
            start = text.find(start_token)
            end = text.find(end_token, start + len(start_token)) if start >= 0 else -1
            if start < 0 or end < 0:
                raise RuntimeError("Location/land/claim details selectable: method region missing")
            region = text[start:end]
            matches = list(re.finditer(r"(^\s*body\.setText\([^\n]+\);\s*$)", region, re.M))
            if len(matches) != 1:
                raise RuntimeError(
                    f"Location/land/claim details selectable: expected one body.setText assignment, found {len(matches)}")
            m = matches[0]
            insert = m.group(1) + "\n        SelectableText.informational(body); // marker: " + marker
            region = region[:m.start()] + insert + region[m.end():]
            MAIN.write_text(text[:start] + region + text[end:], encoding="utf-8")
            print("Location/land/claim details selectable: injected")
        else:
            print("Location/land/claim details selectable: already present")

        checks = {
            RESEARCH: ("selectable-research-scroll-tree", "selectable-research-status"),
            FIELD: ("selectable-field-scroll-tree", "selectable-field-help"),
            MAP_FIELD: ("selectable-field-hud-text",),
            MAIN: ("selectable-location-info-detail",),
        }
        for path, markers in checks.items():
            final = path.read_text(encoding="utf-8")
            absent = [m for m in markers if m not in final]
            if absent:
                raise RuntimeError(f"{path.name}: missing postconditions {absent}")

        main_final = MAIN.read_text(encoding="utf-8")
        if "selectable-geology-detail" in main_final or "selectable-geology-source-detail" in main_final:
            raise RuntimeError("selectable-text pass unexpectedly modified CNGM geology call sites")

        forbidden = ("CREATE TABLE", "ALTER TABLE", "deleteTrack(", "insertTrackPoint(",
                     "requestLocationUpdates(", "CameraUpdateFactory")
        for path in FILES:
            before = originals[path]
            after = path.read_text(encoding="utf-8")
            for token in forbidden:
                if before.count(token) != after.count(token):
                    raise RuntimeError(f"selectable-text pass changed forbidden token {token} in {path.name}")

        print("Selectable informational text pass complete.")
        print("Non-interactive Research/Field/HUD/location text can now be long-pressed and copied.")
        print("Geology-specific copyability remains owned by the later CNGM Stage 2 pass.")
        return 0
    except Exception:
        for path, original in originals.items():
            path.write_text(original, encoding="utf-8")
        print("Selectable informational text pass rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
