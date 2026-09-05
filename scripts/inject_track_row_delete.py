#!/usr/bin/env python3
"""Track-list usability fix: every Track row has a Delete action independent of map geometry.

0/1-point tracks cannot be opened on the map, but they must still be manageable. Completed and
interrupted tracks delete directly from FieldDatabase. Recording/paused tracks use a dedicated
service action so GPS updates are removed before the track row and points are deleted.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIELD = ROOT / "app/src/main/java/com/rockmap/app/field/FieldActivity.java"
SERVICE = ROOT / "app/src/main/java/com/rockmap/app/field/TrackRecordingService.java"


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
    for path in (FIELD, SERVICE):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {FIELD: text(FIELD), SERVICE: text(SERVICE)}
    try:
        replace_once(
            FIELD,
            "commit3-track-row-delete-button",
            '''                tagClickable(trackAction, "rockmap-track-row:" + track.id);
                root.addView(trackAction);
''',
            '''                tagClickable(trackAction, "rockmap-track-row:" + track.id);
                LinearLayout trackRow = row();
                trackRow.setGravity(Gravity.CENTER_VERTICAL);
                trackRow.addView(trackAction, weight());
                Button deleteTrack = small("Delete", v -> confirmDeleteTrack(track));
                deleteTrack.setTag("rockmap-track-delete:" + track.id);
                LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                        dp(96), ViewGroup.LayoutParams.WRAP_CONTENT);
                deleteParams.setMargins(dp(8), 0, 0, 0);
                trackRow.addView(deleteTrack, deleteParams);
                root.addView(trackRow); // marker: commit3-track-row-delete-button
''',
            "Track row Delete button",
        )

        replace_once(
            FIELD,
            "commit3-track-delete-flow",
            '''    private String trackStatus(FieldDatabase.Track track, List<GeoMath.Point> pts) {
''',
            '''    private void confirmDeleteTrack(FieldDatabase.Track track) {
        if (track == null) return;
        List<GeoMath.Point> points = db.getTrackPoints(track.id);
        boolean active = FieldDatabase.TRACK_RECORDING.equals(track.status)
                || FieldDatabase.TRACK_PAUSED.equals(track.status);
        String message = active
                ? "This track is currently " + (FieldDatabase.TRACK_PAUSED.equals(track.status)
                    ? "paused" : "recording")
                    + ". Deleting it will stop Track Recording and permanently remove the track and its "
                    + points.size() + " recorded point" + (points.size() == 1 ? "" : "s") + "."
                : "Permanently delete “" + track.name + "” and its " + points.size()
                    + " recorded point" + (points.size() == 1 ? "" : "s")
                    + "? This cannot be undone.";
        new AlertDialog.Builder(this)
                .setTitle("Delete this track?")
                .setMessage(message)
                .setPositiveButton("Delete", (d, w) -> deleteTrackFromList(track, active))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteTrackFromList(FieldDatabase.Track track, boolean active) {
        if (track == null) return;
        // Remove stale presentation references regardless of point count. This does not turn the
        // global Tracks layer on; showTrack() only removes this id from the per-track hidden set.
        FieldMapState.showTrack(this, track.id);
        if (FieldMapState.selectedTrackDetail(this) == track.id) {
            FieldMapState.clearSelectedTrackDetail(this);
        }
        if (FieldTourState.is(this, FieldUiNames.TRACK)
                && FieldTourState.entityId(this) == track.id) {
            FieldTourState.entityId(this, -1L);
        }

        if (active) {
            Intent service = new Intent(this, TrackRecordingService.class)
                    .setAction(TrackRecordingService.ACTION_DELETE)
                    .putExtra(TrackRecordingService.EXTRA_TRACK_ID, track.id);
            startService(service);
            getWindow().getDecorView().postDelayed(() -> {
                if (db.getTrack(track.id) == null) {
                    toast("Track deleted.");
                } else {
                    toast("Track could not be deleted yet. Stop the recording and try again.");
                }
                showTracks();
            }, 300L);
        } else {
            db.deleteTrack(track.id);
            toast("Track deleted.");
            showTracks();
        }
    } // marker: commit3-track-delete-flow

    private String trackStatus(FieldDatabase.Track track, List<GeoMath.Point> pts) {
''',
            "Track deletion confirmation/flow",
        )

        replace_once(
            SERVICE,
            "commit3-track-delete-action",
            '''    public static final String ACTION_STOP = "com.rockmap.app.field.STOP_TRACK";
''',
            '''    public static final String ACTION_STOP = "com.rockmap.app.field.STOP_TRACK";
    public static final String ACTION_DELETE = "com.rockmap.app.field.DELETE_TRACK"; // marker: commit3-track-delete-action
''',
            "Track service delete action",
        )

        replace_once(
            SERVICE,
            "commit3-track-delete-service-handler",
            '''        } else if (ACTION_STOP.equals(action)) {
            finishTrack();
        }
''',
            '''        } else if (ACTION_STOP.equals(action)) {
            finishTrack();
        } else if (ACTION_DELETE.equals(action)) {
            removeUpdates();
            if (trackId > 0L) database.deleteTrack(trackId);
            trackId = -1L;
            paused = false;
            stopForeground(true);
            stopSelf();
            // marker: commit3-track-delete-service-handler
        }
''',
            "Safe active Track delete handler",
        )

        field = text(FIELD)
        service = text(SERVICE)
        required = (
            "commit3-track-row-delete-button",
            "commit3-track-delete-flow",
            "rockmap-track-delete:",
            "ACTION_DELETE",
        )
        combined = field + "\n" + service
        missing = [token for token in required if token not in combined]
        if missing:
            raise RuntimeError("Track deletion postcondition missing: " + ", ".join(missing))

        # The map-open geometry guard is intentionally preserved. Deletion bypasses it by living
        # directly on the list row rather than weakening what counts as mappable line geometry.
        if 'if (points.size() < 2 && !tourNeedsSavedTrackHud)' not in field:
            raise RuntimeError("Track map geometry guard was unexpectedly changed")

        print("Track row deletion injection complete.")
        print("Scope: list management only; 0/1-point tracks remain non-mappable but are deletable.")
        return 0
    except Exception:
        for path, original in originals.items():
            path.write_text(original, encoding="utf-8")
        print("Track row deletion injection rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
