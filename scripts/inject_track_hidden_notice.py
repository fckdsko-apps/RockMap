#!/usr/bin/env python3
"""Commit 3 simplified behavior: warn when starting a Track while the Tracks layer is hidden.

Tracks keep one rendering/visibility model. Recording still starts normally; if the global Tracks
layer is off, RockMap shows a dismissible notice explaining where to turn Tracks back on.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIELD_ACTIVITY = ROOT / "app/src/main/java/com/rockmap/app/field/FieldActivity.java"


def main() -> int:
    if not FIELD_ACTIVITY.is_file():
        raise RuntimeError(f"required file missing: {FIELD_ACTIVITY.relative_to(ROOT)}")

    original = FIELD_ACTIVITY.read_text(encoding="utf-8")
    marker = "commit3-track-hidden-start-notice"
    if marker in original:
        print("Track hidden-start notice: already present")
        return 0

    old = '''                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
                    else startService(service);
                    dialog.setOnDismissListener(null);
                    dialog.dismiss();
                    GuidedTourCoach.clear(this);
                    returnToMap();
'''
    new = '''                    boolean tracksHiddenWhenStarted = !FieldMapState.tracksVisible(this);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
                    else startService(service);
                    dialog.setOnDismissListener(null);
                    dialog.dismiss();
                    GuidedTourCoach.clear(this);
                    if (tracksHiddenWhenStarted) {
                        AlertDialog hiddenTracksNotice = new AlertDialog.Builder(this)
                                .setTitle("Tracks are hidden")
                                .setMessage("Tracks are currently hidden. Toggle Tracks on and off in Layers.")
                                .setPositiveButton("Close", null)
                                .create();
                        hiddenTracksNotice.setOnDismissListener(d -> returnToMap());
                        hiddenTracksNotice.show();
                        // marker: commit3-track-hidden-start-notice
                    } else {
                        returnToMap();
                    }
'''

    count = original.count(old)
    if count != 1:
        raise RuntimeError(
            f"Track hidden-start notice: expected exactly one start-recording anchor, found {count}"
        )

    updated = original.replace(old, new, 1)

    # Scope guard: this changes only post-Start presentation, not recorder lifecycle or DB writes.
    for token in ("db.createTrack(", "startForegroundService(service)", "startService(service)"):
        if updated.count(token) != original.count(token):
            raise RuntimeError("Track hidden-start notice unexpectedly changed lifecycle token: " + token)

    FIELD_ACTIVITY.write_text(updated, encoding="utf-8")
    print("Commit 3 Track hidden-start notice injected.")
    print("Scope: one rendering model; recording unchanged; notice only when Tracks layer is off.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
