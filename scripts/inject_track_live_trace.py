#!/usr/bin/env python3
"""Commit 3: keep the active/paused Track trace visible independently of saved Track visibility.

This pass runs after the existing HUD/tour behavior and Track diagnostics. It does not change
Track recording, point acceptance, database writes, pause/resume/stop semantics, or saved-Track
visibility preferences. It adds a dedicated active-Track GeoJSON source/layer fed from the same
persisted track_points data that the normal Track renderer already uses.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIELD = ROOT / "app/src/main/java/com/rockmap/app/field/FieldMapController.java"


def read() -> str:
    return FIELD.read_text(encoding="utf-8")


def replace_once(marker: str, old: str, new: str, label: str) -> None:
    text = read()
    if marker in text:
        print(f"{label}: already present")
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one match in {FIELD.relative_to(ROOT)}, found {count}"
        )
    FIELD.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: injected")


def main() -> int:
    if not FIELD.is_file():
        raise RuntimeError(f"required file missing: {FIELD.relative_to(ROOT)}")

    original = read()
    try:
        replace_once(
            "COMMIT3_ACTIVE_TRACK_SOURCE",
            '''    private static final String TRACK_SOURCE = "rockmap-field-track-source";
    private static final String TRACK_LAYER = "rockmap-field-track-layer";
''',
            '''    private static final String TRACK_SOURCE = "rockmap-field-track-source";
    private static final String TRACK_LAYER = "rockmap-field-track-layer";
    // Dedicated live trace: saved/historical Track visibility must never suppress recording.
    private static final String ACTIVE_TRACK_SOURCE = "rockmap-field-active-track-source";
    private static final String ACTIVE_TRACK_LAYER = "rockmap-field-active-track-layer"; // marker: COMMIT3_ACTIVE_TRACK_SOURCE
''',
            "active Track source/layer constants",
        )

        replace_once(
            "commit3-active-track-cache",
            '''    private String trackJson = emptyCollection();
''',
            '''    private String trackJson = emptyCollection();
    private String activeTrackJson = emptyCollection(); // marker: commit3-active-track-cache
    private volatile long activeTraceTrackId = -1L;
    private volatile int activeTracePointCount;
    private volatile boolean activeTraceFeaturePresent;
    private String lastActiveTraceBuildSignature = "";
    private long lastActiveTraceBuildLogElapsedMs;
    private String lastActiveTraceMapSignature = "";
    private long lastActiveTraceMapLogElapsedMs;
''',
            "active Track cache/diagnostic state",
        )

        replace_once(
            "commit3-refresh-build-active-track",
            '''            String newTracks = buildTrackJson(hidden);
            String newAreas = buildAreaJson();
''',
            '''            String newTracks = buildTrackJson(hidden);
            String newActiveTrack = buildActiveTrackJson(); // marker: commit3-refresh-build-active-track
            String newAreas = buildAreaJson();
''',
            "build active Track snapshot during refresh",
        )

        replace_once(
            "commit3-refresh-publish-active-track",
            '''                trackJson = newTracks;
                areaJson = newAreas;
''',
            '''                trackJson = newTracks;
                activeTrackJson = newActiveTrack; // marker: commit3-refresh-publish-active-track
                areaJson = newAreas;
''',
            "publish active Track snapshot on main thread",
        )

        replace_once(
            "commit3-active-track-json-builder",
            '''    private String buildTrackJson(Set<String> hidden) {
''',
            '''    private String buildActiveTrackJson() {
        JSONArray features = new JSONArray();
        FieldDatabase.Track active = db.getActiveTrack();
        activeTraceTrackId = active == null ? -1L : active.id;
        activeTracePointCount = 0;
        activeTraceFeaturePresent = false;
        if (active != null) {
            List<GeoMath.Point> points = db.getTrackPoints(active.id);
            activeTracePointCount = points.size();
            if (points.size() >= 2) {
                try {
                    JSONObject props = new JSONObject();
                    props.put("id", active.id);
                    props.put("name", active.name);
                    props.put("status", active.status);
                    props.put("active_trace", true);
                    features.put(lineFeature(points, props));
                    activeTraceFeaturePresent = true;
                } catch (JSONException ignored) {
                    activeTraceFeaturePresent = false;
                }
            }
        }
        logActiveTrackBuildState();
        return collection(features);
    } // marker: commit3-active-track-json-builder

    private void logActiveTrackBuildState() {
        long now = SystemClock.elapsedRealtime();
        String signature = activeTraceTrackId + ":" + activeTracePointCount
                + ":" + activeTraceFeaturePresent;
        if (signature.equals(lastActiveTraceBuildSignature)
                && now - lastActiveTraceBuildLogElapsedMs < 5000L) return;
        lastActiveTraceBuildSignature = signature;
        lastActiveTraceBuildLogElapsedMs = now;
        UiInvariantMonitor.track("TRACK_ACTIVE_GEOJSON_BUILD",
                "activeTrackId=" + activeTraceTrackId
                        + " points=" + activeTracePointCount
                        + " feature=" + activeTraceFeaturePresent
                        + " savedTracksVisible=" + FieldMapState.tracksVisible(activity)
                        + " individuallyHidden=" + (activeTraceTrackId > 0L
                            && FieldMapState.isTrackHidden(activity, activeTraceTrackId)));
    }

    private void logActiveTrackMapState(Style style) {
        boolean sourceReady = style != null && style.getSource(ACTIVE_TRACK_SOURCE) != null;
        boolean layerReady = style != null && style.getLayer(ACTIVE_TRACK_LAYER) != null;
        long now = SystemClock.elapsedRealtime();
        String signature = activeTraceTrackId + ":" + activeTracePointCount
                + ":" + activeTraceFeaturePresent + ":" + sourceReady + ":" + layerReady;
        if (!signature.equals(lastActiveTraceMapSignature)
                || now - lastActiveTraceMapLogElapsedMs >= 5000L) {
            lastActiveTraceMapSignature = signature;
            lastActiveTraceMapLogElapsedMs = now;
            UiInvariantMonitor.track("TRACK_ACTIVE_MAP_RENDER_STATE",
                    "activeTrackId=" + activeTraceTrackId
                            + " points=" + activeTracePointCount
                            + " feature=" + activeTraceFeaturePresent
                            + " source=" + sourceReady
                            + " layer=" + layerReady
                            + " savedTracksVisible=" + FieldMapState.tracksVisible(activity)
                            + " individuallyHidden=" + (activeTraceTrackId > 0L
                                && FieldMapState.isTrackHidden(activity, activeTraceTrackId)));
            if (activeTracePointCount >= 2) {
                UiInvariantMonitor.track(sourceReady && layerReady && activeTraceFeaturePresent
                                ? "TRACK_ACTIVE_RENDER_INVARIANT_OK"
                                : "TRACK_ACTIVE_RENDER_INVARIANT_FAIL",
                        "activeTrackId=" + activeTraceTrackId
                                + " points=" + activeTracePointCount
                                + " feature=" + activeTraceFeaturePresent
                                + " source=" + sourceReady + " layer=" + layerReady);
            }
        }
    }

    private String buildTrackJson(Set<String> hidden) {
''',
            "active Track GeoJSON builder and diagnostics",
        )

        # The existing Track pipeline diagnostics inserted by Commit 1 sit between TRACK_SOURCE
        # and AREA_SOURCE here. Insert the active source immediately before AREA_SOURCE so those
        # diagnostics remain byte-for-byte intact and continue describing the historical layer.
        replace_once(
            "commit3-active-source-publish",
            '''            setSource(style, AREA_SOURCE, areaJson);
''',
            '''            setSource(style, ACTIVE_TRACK_SOURCE, activeTrackJson);
            logActiveTrackMapState(style); // marker: commit3-active-source-publish
            setSource(style, AREA_SOURCE, areaJson);
''',
            "publish active Track source",
        )

        replace_once(
            "commit3-active-layer-install",
            '''        if (style.getSource(AREA_SOURCE) == null) style.addSource(new GeoJsonSource(AREA_SOURCE, emptyCollection()));
''',
            '''        if (style.getSource(ACTIVE_TRACK_SOURCE) == null) {
            style.addSource(new GeoJsonSource(ACTIVE_TRACK_SOURCE, emptyCollection()));
        }
        if (style.getLayer(ACTIVE_TRACK_LAYER) == null) {
            LineLayer activeTrack = new LineLayer(ACTIVE_TRACK_LAYER, ACTIVE_TRACK_SOURCE);
            activeTrack.setProperties(
                    lineColor(Color.rgb(20, 105, 210)),
                    lineWidth(5f),
                    lineOpacity(0.98f));
            // addLayer places the live trace above the historical Track layer. The geometry is
            // sourced from persisted points, not from a second recorder.
            style.addLayer(activeTrack);
        } // marker: commit3-active-layer-install

        if (style.getSource(AREA_SOURCE) == null) style.addSource(new GeoJsonSource(AREA_SOURCE, emptyCollection()));
''',
            "install active Track source/layer",
        )

        replace_once(
            "commit3-active-layer-visibility",
            '''        setLayerVisible(style, TRACK_LAYER, FieldMapState.tracksVisible(activity));
        setLayerVisible(style, AREA_FILL, FieldMapState.areasVisible(activity));
''',
            '''        setLayerVisible(style, TRACK_LAYER, FieldMapState.tracksVisible(activity));
        // Active recording/paused trace is functional feedback, not a historical overlay.
        // It remains visible even when saved Tracks are globally or individually hidden.
        setLayerVisible(style, ACTIVE_TRACK_LAYER, true); // marker: commit3-active-layer-visibility
        setLayerVisible(style, AREA_FILL, FieldMapState.areasVisible(activity));
''',
            "decouple active Track visibility from saved Tracks",
        )

        final = read()
        required = (
            "ACTIVE_TRACK_SOURCE",
            "ACTIVE_TRACK_LAYER",
            "buildActiveTrackJson()",
            "TRACK_ACTIVE_GEOJSON_BUILD",
            "TRACK_ACTIVE_MAP_RENDER_STATE",
            "TRACK_ACTIVE_RENDER_INVARIANT_OK",
            "setLayerVisible(style, ACTIVE_TRACK_LAYER, true)",
        )
        missing = [token for token in required if token not in final]
        if missing:
            raise RuntimeError("active Track postcondition missing: " + ", ".join(missing))

        # Scope guards: this pass must never become another recorder or mutate Track lifecycle.
        injected_region = final[len(original):] if final.startswith(original) else final
        forbidden_new_behavior = (
            "addTrackPoint(", "createTrack(", "setTrackStatus(",
            "ACTION_START", "ACTION_PAUSE", "ACTION_RESUME", "ACTION_STOP",
        )
        # Compare occurrence counts rather than banning legitimate baseline calls elsewhere.
        for token in forbidden_new_behavior:
            if final.count(token) != original.count(token):
                raise RuntimeError("Commit 3 must not change Track recording lifecycle token: " + token)

        print("Commit 3 active Track trace injection complete.")
        print("Scope: dedicated active/paused map trace only; recorder and saved Track state unchanged.")
        return 0
    except Exception:
        FIELD.write_text(original, encoding="utf-8")
        print("Commit 3 active Track trace injection rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
