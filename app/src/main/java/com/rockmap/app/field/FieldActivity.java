package com.rockmap.app.field;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.MainActivity;
import com.rockmap.app.coordinates.CoordinateParser;
import com.rockmap.app.location.LocationRepository;
import com.rockmap.app.waypoints.WaypointEntity;
import com.rockmap.app.waypoints.WaypointRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FieldActivity extends Activity implements LocationRepository.Listener {
    public static final String EXTRA_SCREEN = "rockmap.field.screen";
    private static final int REQ_LOCATION = 811;
    private static final int REQ_IMPORT = 812;
    private static final int REQ_PHOTO = 813;
    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;

    private FieldDatabase db;
    private WaypointRepository waypointRepository;
    private LocationRepository locationRepository;
    private Runnable pendingLocationAction;
    private String pendingPhotoUri = "";
    private boolean started;
    private boolean waypointDataChanged;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = FieldDatabase.get(this);
        waypointRepository = new WaypointRepository(this);
        locationRepository = new LocationRepository(this, this);

        String screen = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_SCREEN);
        if ("tracks".equals(screen)) showTracks();
        else if ("records".equals(screen)) showFieldRecords();
        else if ("imports".equals(screen)) showImports();
        else if ("coordinates".equals(screen)) showCoordinates();
        else if ("measure".equals(screen)) {
            FieldMapState.requestMeasurement(this);
            returnToMap();
        } else {
            showHub();
            if ("import".equals(screen)) getWindow().getDecorView().post(this::beginImport);
        }
    }

    private void showHub() {
        setTitle("RockMap Field");
        LinearLayout root = page();
        root.addView(title("Field"));
        root.addView(help("Field tools are map-first. Spatial features lead back to the main map so their position, scale, and relationship to the surrounding terrain are visible."));

        root.addView(action("Track recording & backtrack",
                "Record GPS tracks that draw live on the main map. Opening a saved track now opens its real basemap view instead of an abstract squiggle.",
                v -> showTracks()));
        root.addView(action("Field records & samples",
                "Richer saved observations with category, mineral, sample ID, notes, photo, GPS accuracy and elevation.",
                v -> showFieldRecords()));
        root.addView(action("Saved locations",
                "View the existing RockMap saved-marker database or copy a marker into a richer field record.",
                v -> showLegacyWaypoints()));
        root.addView(action("Measure on map",
                "Add points by map tap, GPS, saved marker, Field Record, or pasted coordinate. Segment distances and polygon area are labeled directly on the map.",
                v -> { FieldMapState.requestMeasurement(this); returnToMap(); }));
        root.addView(action("Import GPX / KML / GeoJSON",
                "Import waypoints, tracks and areas as a managed batch, then show or remove that batch cleanly.",
                v -> beginImport()));
        root.addView(action("Imported data",
                "Review imported batches, show a batch on the map, or delete only the data created by that import.",
                v -> showImports()));
        root.addView(action("Coordinate formats",
                "Convert one location between decimal degrees, DDM, DMS, WGS84 UTM and MGRS.",
                v -> showCoordinates()));
        root.addView(action("Back to map",
                "Return to the main RockMap map and its visual Field controls.",
                v -> returnToMap()));
        setContentView(scroll(root));
    }

    // ---------- TRACKS ----------

    private void showTracks() {
        LinearLayout root = page();
        root.addView(title("Tracks"));
        root.addView(help("Tracks are geographic objects. Tap any track below to open it on the real basemap, zoomed to its recorded extent with START/END context."));

        FieldDatabase.Track active = db.getActiveTrack();
        if (active == null) {
            root.addView(help("Track recording uses the GPS provider and an Android foreground service. RockMap does not request background-location permission."));
            root.addView(action("Start new track", "Begins recording after a precise-location check.", v -> startNewTrack()));
        } else {
            List<GeoMath.Point> points = db.getTrackPoints(active.id);
            root.addView(help(active.name + "\n" + trackStatus(active, points)));
            LinearLayout row = row();
            if (FieldDatabase.TRACK_PAUSED.equals(active.status)) {
                row.addView(small("Resume", v -> trackCommand(TrackRecordingService.ACTION_RESUME, active.id)), weight());
            } else {
                row.addView(small("Pause", v -> trackCommand(TrackRecordingService.ACTION_PAUSE, active.id)), weight());
            }
            row.addView(small("Stop", v -> confirmStopTrack(active)), weight());
            root.addView(row);
            root.addView(action("Open active track on map",
                    "Return to the basemap and zoom to the line while the recording HUD remains available.",
                    v -> showTrackOnMap(active.id)));
        }

        root.addView(section("Recent tracks"));
        List<FieldDatabase.Track> tracks = db.listTracks(50);
        if (tracks.isEmpty()) {
            root.addView(help("No recorded or imported tracks yet."));
        } else {
            for (FieldDatabase.Track track : tracks) {
                List<GeoMath.Point> pts = db.getTrackPoints(track.id);
                String visibility = FieldMapState.isTrackHidden(this, track.id) ? "hidden on map" : "visible on map";
                root.addView(action(track.name,
                        trackStatus(track, pts) + " · " + visibility + "\nTap to open the real map view.",
                        v -> showTrackOnMap(track.id)));
            }
        }
        root.addView(back());
        setContentView(scroll(root));
    }

    private void startNewTrack() {
        runWithPreciseLocation(() -> {
            EditText input = new EditText(this);
            input.setHint("Track name");
            input.setText("Field track — " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()));
            input.setSingleLine(true);
            new AlertDialog.Builder(this)
                    .setTitle("Start track recording")
                    .setMessage("Recording can continue while you use the RockMap map or lock the screen. Android keeps a foreground-service indicator active until you stop the track.")
                    .setView(input)
                    .setPositiveButton("Start", (d, w) -> {
                        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                            toast("Precise location is required to start track recording.");
                            return;
                        }
                        long id = db.createTrack(input.getText().toString().trim(), System.currentTimeMillis());
                        Intent service = new Intent(this, TrackRecordingService.class)
                                .setAction(TrackRecordingService.ACTION_START)
                                .putExtra(TrackRecordingService.EXTRA_TRACK_ID, id);
                        FieldMapState.showTrack(this, id);
                        FieldMapState.requestTrackFocus(this, id);
                        FieldMapState.clearSelectedTrackDetail(this);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
                        else startService(service);
                        returnToMap();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private void trackCommand(String action, long id) {
        Intent service = new Intent(this, TrackRecordingService.class)
                .setAction(action)
                .putExtra(TrackRecordingService.EXTRA_TRACK_ID, id);
        startService(service);
        getWindow().getDecorView().postDelayed(this::showTracks, 250);
    }

    private void confirmStopTrack(FieldDatabase.Track track) {
        new AlertDialog.Builder(this)
                .setTitle("Stop this track?")
                .setMessage("The recorded points stay on this device and the track remains visible on the map until you hide or delete it.")
                .setPositiveButton("Stop", (d, w) -> trackCommand(TrackRecordingService.ACTION_STOP, track.id))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String trackStatus(FieldDatabase.Track track, List<GeoMath.Point> pts) {
        double meters = GeoMath.pathDistanceMeters(pts);
        long end = track.endedAt > 0 ? track.endedAt : System.currentTimeMillis();
        long duration = Math.max(0, end - track.startedAt);
        return (track.status == null ? "" : track.status) + " · " + pts.size() + " points · "
                + GeoMath.distanceLabel(meters) + " · " + durationLabel(duration);
    }

    private void showTrackOnMap(long trackId) {
        FieldDatabase.Track track = db.getTrack(trackId);
        if (track == null) {
            toast("Track not found.");
            showTracks();
            return;
        }
        List<GeoMath.Point> points = db.getTrackPoints(trackId);
        if (points.size() < 2) {
            toast("This track has too few points to map yet.");
            return;
        }

        FieldMapState.showTrack(this, trackId);
        FieldMapState.requestTrackFocus(this, trackId);
        if (FieldDatabase.TRACK_RECORDING.equals(track.status) || FieldDatabase.TRACK_PAUSED.equals(track.status)) {
            FieldMapState.clearSelectedTrackDetail(this);
        } else {
            FieldMapState.selectTrackDetail(this, trackId);
        }
        returnToMap();
    }

    // ---------- FIELD RECORDS ----------

    private void showFieldRecords() {
        LinearLayout root = page();
        root.addView(title("Field records & samples"));
        LinearLayout add = row();
        add.addView(small("New at GPS", v -> newFieldAtGps()), weight());
        add.addView(small("New at coordinates", v -> newFieldAtCoordinates()), weight());
        root.addView(add);

        List<FieldDatabase.FieldRecord> records = db.listFieldRecords();
        if (records.isEmpty()) {
            root.addView(help("No field records yet."));
        } else {
            for (FieldDatabase.FieldRecord r : records) {
                String detail = (r.category == null || r.category.isEmpty() ? "Field record" : r.category)
                        + (r.mineral == null || r.mineral.isEmpty() ? "" : " · " + r.mineral)
                        + (r.sampleId == null || r.sampleId.isEmpty() ? "" : " · Sample " + r.sampleId)
                        + "\n" + CoordinateFormats.decimal(r.lat, r.lon);
                root.addView(action(r.name, detail, v -> showFieldRecord(r.id)));
            }
        }
        root.addView(back());
        setContentView(scroll(root));
    }

    private void newFieldAtGps() {
        runWithPreciseLocation(() -> locationRepository.requestFreshPrecise(
                l -> editFieldRecord(new FieldDatabase.FieldRecord(
                        0, "", "", "", "", "",
                        l.getLatitude(), l.getLongitude(),
                        l.hasAltitude() ? l.getAltitude() : Double.NaN,
                        l.hasAccuracy() ? l.getAccuracy() : -1f,
                        "", System.currentTimeMillis(), System.currentTimeMillis())),
                this::toast));
    }

    private void newFieldAtCoordinates() {
        EditText input = new EditText(this);
        input.setHint("Latitude, longitude");
        input.setSingleLine(true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New field record at coordinates")
                .setView(input)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                CoordinateParser.Result r = CoordinateParser.parse(input.getText().toString());
                dialog.dismiss();
                editFieldRecord(new FieldDatabase.FieldRecord(
                        0, "", "", "", "", "", r.latitude, r.longitude,
                        Double.NaN, -1f, "", System.currentTimeMillis(), System.currentTimeMillis()));
            } catch (IllegalArgumentException ex) {
                input.setError(ex.getMessage());
            }
        }));
        dialog.show();
    }

    private void editFieldRecord(FieldDatabase.FieldRecord record) {
        pendingPhotoUri = record.photoUri == null ? "" : record.photoUri;
        LinearLayout box = page();
        EditText name = input("Name", record.name);
        EditText category = input("Category (outcrop, float, mine, dump, vein…)", record.category);
        EditText mineral = input("Mineral / material", record.mineral);
        EditText sample = input("Sample ID (optional)", record.sampleId);
        EditText notes = input("Notes", record.notes);
        notes.setMinLines(4);
        notes.setSingleLine(false);
        box.addView(name);
        box.addView(category);
        box.addView(mineral);
        box.addView(sample);
        box.addView(notes);

        TextView coords = help(CoordinateFormats.decimal(record.lat, record.lon)
                + (Double.isFinite(record.altitude) ? String.format(Locale.US, "\nElevation: %.1f m", record.altitude) : "")
                + (record.accuracy >= 0 ? String.format(Locale.US, "\nGPS accuracy: ±%.1f m", record.accuracy) : ""));
        box.addView(coords);

        Button photo = button(pendingPhotoUri.isEmpty() ? "Attach photo" : "Change attached photo");
        photo.setOnClickListener(v -> beginPhotoPick());
        box.addView(photo);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(record.id > 0 ? "Edit field record" : "New field record")
                .setView(scroll(box))
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = name.getText().toString().trim();
            if (label.isEmpty()) {
                name.setError("Enter a name.");
                return;
            }
            record.name = bounded(label, 500);
            record.category = bounded(category.getText().toString().trim(), 300);
            record.mineral = bounded(mineral.getText().toString().trim(), 500);
            record.sampleId = bounded(sample.getText().toString().trim(), 200);
            record.notes = bounded(notes.getText().toString().trim(), 20000);
            record.photoUri = pendingPhotoUri;
            record.updatedAt = System.currentTimeMillis();
            if (record.createdAt <= 0) record.createdAt = record.updatedAt;
            if (record.id > 0) db.updateFieldRecord(record);
            else record.id = db.insertFieldRecord(record);
            dialog.dismiss();
            showFieldRecord(record.id);
        }));
        dialog.show();
    }

    private void showFieldRecord(long id) {
        FieldDatabase.FieldRecord r = db.getFieldRecord(id);
        if (r == null) {
            showFieldRecords();
            return;
        }

        LinearLayout root = page();
        root.addView(title(r.name));
        StringBuilder s = new StringBuilder();
        s.append(CoordinateFormats.decimal(r.lat, r.lon));
        if (r.accuracy >= 0) s.append(String.format(Locale.US, "\nGPS accuracy: ±%.1f m", r.accuracy));
        if (Double.isFinite(r.altitude)) s.append(String.format(Locale.US, "\nElevation: %.1f m", r.altitude));
        if (!r.category.isEmpty()) s.append("\nCategory: ").append(r.category);
        if (!r.mineral.isEmpty()) s.append("\nMineral/material: ").append(r.mineral);
        if (!r.sampleId.isEmpty()) s.append("\nSample ID: ").append(r.sampleId);
        if (!r.notes.isEmpty()) s.append("\n\n").append(r.notes);
        s.append("\n\nUpdated: ").append(DateFormat.getDateTimeInstance().format(new Date(r.updatedAt)));
        root.addView(help(s.toString()));

        if (r.photoUri != null && !r.photoUri.isEmpty()) {
            root.addView(action("Open attached photo", r.photoUri, v -> openPhoto(r.photoUri)));
        }
        root.addView(action("Show on map",
                "Center this Field Record on the main map without starting navigation.",
                v -> {
                    FieldMapState.requestFocusBounds(this, new FieldMapState.Bounds(r.lat, r.lon, r.lat, r.lon));
                    returnToMap();
                }));
        root.addView(action("Navigate to this point",
                "Open the main map with a live target line, distance and bearing from GPS.",
                v -> showPointNavigation(r.name, new GeoMath.Point(r.lat, r.lon))));

        LinearLayout row = row();
        row.addView(small("Edit", v -> editFieldRecord(r)), weight());
        row.addView(small("Delete", v -> new AlertDialog.Builder(this)
                .setTitle("Delete field record?")
                .setMessage(r.name + " will be removed from this device.")
                .setPositiveButton("Delete", (d, w) -> {
                    db.deleteFieldRecord(r.id);
                    showFieldRecords();
                })
                .setNegativeButton("Cancel", null)
                .show()), weight());
        root.addView(row);
        root.addView(nav("Back to field records", v -> showFieldRecords()));
        setContentView(scroll(root));
    }

    private void beginPhotoPick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, REQ_PHOTO);
    }

    private void openPhoto(String uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (RuntimeException ex) {
            toast("Photo could not be opened.");
        }
    }

    // ---------- LEGACY SAVED LOCATIONS ----------

    private void showLegacyWaypoints() {
        waypointRepository.getAll(items -> {
            LinearLayout root = page();
            root.addView(title("Saved locations"));
            root.addView(help("These are the existing RockMap markers used by the main map. Their symbols and labels follow Layers > Saved locations."));
            if (items.isEmpty()) {
                root.addView(help("No saved locations yet."));
            } else {
                for (WaypointEntity w : items) {
                    root.addView(action(w.name, CoordinateFormats.decimal(w.latitude, w.longitude),
                            v -> showLegacyWaypoint(w)));
                }
            }
            root.addView(nav("Back to Field", v -> showHub()));
            setContentView(scroll(root));
        });
    }

    private void showLegacyWaypoint(WaypointEntity w) {
        LinearLayout root = page();
        root.addView(title(w.name));
        root.addView(help(CoordinateFormats.decimal(w.latitude, w.longitude)
                + (w.accuracyMeters >= 0 ? String.format(Locale.US, "\nGPS accuracy: ±%.1f m", w.accuracyMeters) : "")
                + (w.notes == null || w.notes.trim().isEmpty() ? "" : "\n\n" + w.notes)));
        root.addView(action("Show on map",
                "Center this labeled saved marker on the main map.",
                v -> {
                    FieldMapState.requestFocusBounds(this,
                            new FieldMapState.Bounds(w.latitude, w.longitude, w.latitude, w.longitude));
                    returnToMap();
                }));
        root.addView(action("Navigate to this point",
                "Open the main map with a live target line, distance and bearing from GPS.",
                v -> showPointNavigation(w.name, new GeoMath.Point(w.latitude, w.longitude))));
        root.addView(action("Copy to Field Record",
                "Creates a richer editable field record; the original map marker remains.",
                v -> {
                    long now = System.currentTimeMillis();
                    FieldDatabase.FieldRecord r = new FieldDatabase.FieldRecord(
                            0, w.name, "Saved location", "", "", w.notes,
                            w.latitude, w.longitude, Double.NaN, w.accuracyMeters, "", now, now);
                    r.id = db.insertFieldRecord(r);
                    showFieldRecord(r.id);
                }));
        root.addView(nav("Back to saved locations", v -> showLegacyWaypoints()));
        setContentView(scroll(root));
    }

    // ---------- MEASURE / AREAS ----------

    private void showMeasure() {
        LinearLayout root = page();
        root.addView(title("Measure & prospecting areas"));
        root.addView(help("Measurement happens on the main map. Distance values appear directly on the line in a contrasting label color; with 3+ points, the polygon area is labeled too."));
        root.addView(action("Start measurement on map",
                "Open the clearly labeled measurement HUD with Tap map, GPS, Saved, Field, Paste, Undo, Save area and Done.",
                v -> {
                    FieldMapState.requestMeasurement(this);
                    returnToMap();
                }));
        root.addView(section("Saved areas"));
        List<FieldDatabase.Area> areas = db.listAreas();
        if (areas.isEmpty()) {
            root.addView(help("No saved prospecting areas yet."));
        } else {
            for (FieldDatabase.Area a : areas) {
                root.addView(action(a.name,
                        GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(a.points)) + " · " + a.points.size() + " vertices",
                        v -> showArea(a)));
            }
        }
        root.addView(back());
        setContentView(scroll(root));
    }

    private String measurementText(List<GeoMath.Point> pts) {
        double distance = GeoMath.pathDistanceMeters(pts);
        double bearing = GeoMath.initialBearingDegrees(pts.get(0), pts.get(pts.size() - 1));
        String out = "Path distance: " + GeoMath.distanceLabel(distance)
                + "\nFirst → last bearing: " + String.format(Locale.US, "%.0f° %s", bearing, GeoMath.cardinal(bearing));
        if (pts.size() >= 3) out += "\nPolygon area: " + GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(pts));
        return out;
    }

    private void showArea(FieldDatabase.Area a) {
        LinearLayout root = page();
        root.addView(title(a.name));
        root.addView(help(a.points.size() + " vertices\n" + measurementText(a.points)
                + (a.notes == null || a.notes.isEmpty() ? "" : "\n\n" + a.notes)
                + "\n\nUse Show on map for the geographic view."));
        root.addView(action("Show on map",
                "Zoom to this saved area and keep the polygon visible in geographic context.",
                v -> {
                    FieldMapState.Bounds bounds = FieldMapState.Bounds.fromPoints(a.points);
                    if (bounds != null) FieldMapState.requestFocusBounds(this, bounds);
                    returnToMap();
                }));
        Button del = button("Delete area");
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete area?")
                .setPositiveButton("Delete", (d, w) -> {
                    db.deleteArea(a.id);
                    showMeasure();
                })
                .setNegativeButton("Cancel", null)
                .show());
        root.addView(del);
        root.addView(nav("Back to areas", v -> showMeasure()));
        setContentView(scroll(root));
    }

    // ---------- IMPORT ----------

    private void beginImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void handleImport(Uri uri) {
        try {
            byte[] bytes = read(uri, MAX_IMPORT_BYTES);
            String name = displayName(uri);
            String sha = sha256(bytes);
            FieldImport.Result result = FieldImport.parse(bytes, name);
            FieldDatabase.ImportBatch previous = db.findImportBatchBySha(sha);

            if (previous != null) {
                new AlertDialog.Builder(this)
                        .setTitle("This file was already imported")
                        .setMessage(previous.sourceName + " was imported on "
                                + DateFormat.getDateTimeInstance().format(new Date(previous.importedAt))
                                + ".\n\nTo test a clean re-import, remove that batch first. You can still intentionally import another copy.")
                        .setPositiveButton("Manage existing", (d, w) -> showImportBatch(previous.id))
                        .setNeutralButton("Import another copy", (d, w) -> confirmImport(result, name, sha))
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
            confirmImport(result, name, sha);
        } catch (Exception ex) {
            toast("Import rejected: " + ex.getMessage());
        }
    }

    private void confirmImport(FieldImport.Result result, String name, String sha) {
        String summary = "Found:\n"
                + result.waypoints.size() + " waypoints\n"
                + result.tracks.size() + " tracks\n"
                + result.areas.size() + " polygon areas\n"
                + result.pointCount + " total geometry points\n\n"
                + "This import will be stored as one removable batch. Existing unrelated RockMap data will not be replaced.";
        new AlertDialog.Builder(this)
                .setTitle("Import " + name + "?")
                .setMessage(summary)
                .setPositiveButton("Import", (d, w) -> applyImport(result, name, sha))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyImport(FieldImport.Result r, String displayName, String sha) {
        final long batchId;
        try {
            batchId = db.createImportBatch(displayName, sha,
                    r.waypoints.size(), r.tracks.size(), r.areas.size());

            for (FieldImport.ImportedTrack t : r.tracks) {
                long id = db.createTrack(t.name, System.currentTimeMillis());
                for (GeoMath.Point p : t.points) db.addTrackPoint(id, p);
                db.setTrackStatus(id, FieldDatabase.TRACK_COMPLETE, System.currentTimeMillis());
                FieldMapState.showTrack(this, id);
                db.addImportItem(batchId, FieldDatabase.IMPORT_TRACK, id);
            }

            for (FieldImport.ImportedArea a : r.areas) {
                long id = db.insertArea(a.name, "Imported from " + displayName, a.points);
                db.addImportItem(batchId, FieldDatabase.IMPORT_AREA, id);
            }
        } catch (RuntimeException ex) {
            toast("Import failed safely before completion: " + ex.getMessage());
            return;
        }

        Runnable done = () -> {
            for (WaypointEntity waypoint : r.waypoints) {
                if (waypoint.id > 0L) db.addImportItem(batchId, FieldDatabase.IMPORT_WAYPOINT, waypoint.id);
            }
            if (!r.waypoints.isEmpty()) waypointDataChanged = true;
            showImportComplete(batchId, displayName, r);
        };

        if (!r.waypoints.isEmpty()) {
            waypointRepository.insertAll(r.waypoints, count -> done.run());
        } else {
            done.run();
        }
    }

    private void showImportComplete(long batchId, String displayName, FieldImport.Result r) {
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = page();
        box.setPadding(dp(14), dp(6), dp(14), dp(6));

        box.addView(help("Imported " + r.waypoints.size() + " waypoint" + (r.waypoints.size() == 1 ? "" : "s")
                + ", " + r.tracks.size() + " track" + (r.tracks.size() == 1 ? "" : "s")
                + ", and " + r.areas.size() + " area" + (r.areas.size() == 1 ? "" : "s")
                + ". This batch can now be shown, reviewed, or removed as one unit."));

        box.addView(action("Show import on map",
                "Zoom to all imported geometry. Tracks, areas and labeled waypoints are visible immediately.",
                v -> {
                    holder[0].dismiss();
                    focusImportBatch(batchId);
                }));

        if (!r.waypoints.isEmpty()) {
            box.addView(action("View imported waypoints",
                    r.waypoints.size() + " imported saved marker" + (r.waypoints.size() == 1 ? "" : "s") + ".",
                    v -> {
                        holder[0].dismiss();
                        showImportedWaypoints(r.waypoints);
                    }));
        }

        List<Long> trackIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_TRACK);
        if (!trackIds.isEmpty()) {
            box.addView(action("View imported tracks",
                    trackIds.size() + " imported track" + (trackIds.size() == 1 ? "" : "s") + ".",
                    v -> {
                        holder[0].dismiss();
                        showImportedTracks(trackIds);
                    }));
        }

        List<Long> areaIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_AREA);
        if (!areaIds.isEmpty()) {
            box.addView(action("View imported areas",
                    areaIds.size() + " imported area" + (areaIds.size() == 1 ? "" : "s") + ".",
                    v -> {
                        holder[0].dismiss();
                        showImportedAreas(areaIds);
                    }));
        }

        box.addView(action("Manage this import",
                "See the batch later or remove only the objects created by this import.",
                v -> {
                    holder[0].dismiss();
                    showImportBatch(batchId);
                }));

        box.addView(action("Delete this import",
                "Remove this batch's remaining waypoints, tracks and areas without touching unrelated RockMap data.",
                v -> {
                    holder[0].dismiss();
                    confirmDeleteImportBatch(batchId);
                }));

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("Import complete — " + displayName)
                .setView(scroll(box))
                .setNegativeButton("Done", (d, w) -> showHub())
                .create();
        holder[0].show();
    }

    private void showImports() {
        LinearLayout root = page();
        root.addView(title("Imported data"));
        root.addView(help("Each new GPX/KML/GeoJSON import is tracked as its own batch. Deleting a batch removes only the remaining objects created by that import."));

        List<FieldDatabase.ImportBatch> batches = db.listImportBatches();
        root.addView(section("Managed imports"));
        if (batches.isEmpty()) {
            root.addView(help("No managed import batches yet. You can import one directly from this screen."));
            root.addView(action("Import a file",
                    "Choose a GPX, KML or GeoJSON file and create a managed import batch.",
                    v -> beginImport()));
        } else {
            root.addView(action("Import another file",
                    "Choose another GPX, KML or GeoJSON file.",
                    v -> beginImport()));
            for (FieldDatabase.ImportBatch batch : batches) {
                String detail = DateFormat.getDateTimeInstance().format(new Date(batch.importedAt))
                        + "\n" + batch.waypointCount + " waypoints · "
                        + batch.trackCount + " tracks · " + batch.areaCount + " areas";
                root.addView(action(batch.sourceName, detail, v -> showImportBatch(batch.id)));
            }
        }

        root.addView(section("Imports from the previous build"));
        root.addView(help("Files imported before this update were not assigned batch IDs, so RockMap will not guess which older saved data is safe to bulk-delete. Remove those once from Saved locations / Tracks / Areas if needed. Every new import from this build forward is batch-managed."));
        root.addView(action("Review saved locations", "Delete any older imported waypoint manually if you need a completely clean first re-test.", v -> showLegacyWaypoints()));
        root.addView(action("Review tracks", "Open or remove older untracked imported tracks.", v -> showTracks()));
        root.addView(action("Review areas", "Open or remove older untracked imported polygon areas.", v -> showMeasure()));
        root.addView(nav("Back to Field", v -> showHub()));
        setContentView(scroll(root));
    }

    private void showImportBatch(long batchId) {
        FieldDatabase.ImportBatch batch = db.getImportBatch(batchId);
        if (batch == null) {
            toast("Import batch not found.");
            showImports();
            return;
        }

        LinearLayout root = page();
        root.addView(title(batch.sourceName));
        String fingerprint = batch.sha256 == null || batch.sha256.length() < 12
                ? batch.sha256 : batch.sha256.substring(0, 12) + "…";
        root.addView(help("Imported: " + DateFormat.getDateTimeInstance().format(new Date(batch.importedAt))
                + "\nOriginally imported: " + batch.waypointCount + " waypoints · "
                + batch.trackCount + " tracks · " + batch.areaCount + " areas"
                + "\nFile fingerprint: " + fingerprint
                + "\n\nIf an item was already deleted individually, batch deletion simply removes the remaining members."));

        root.addView(action("Show batch on map",
                "Zoom to all remaining geometry that belongs to this import.",
                v -> focusImportBatch(batchId)));

        List<Long> waypointIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_WAYPOINT);
        List<Long> trackIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_TRACK);
        List<Long> areaIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_AREA);

        if (!waypointIds.isEmpty()) {
            root.addView(action("Imported waypoints", waypointIds.size() + " tracked waypoint IDs.",
                    v -> showImportedWaypointsByIds(waypointIds)));
        }
        if (!trackIds.isEmpty()) {
            root.addView(action("Imported tracks", trackIds.size() + " tracked track IDs.",
                    v -> showImportedTracks(trackIds)));
        }
        if (!areaIds.isEmpty()) {
            root.addView(action("Imported areas", areaIds.size() + " tracked area IDs.",
                    v -> showImportedAreas(areaIds)));
        }

        Button delete = button("Delete this import");
        delete.setOnClickListener(v -> confirmDeleteImportBatch(batchId));
        root.addView(delete);
        root.addView(nav("Back to imported data", v -> showImports()));
        setContentView(scroll(root));
    }

    private void confirmDeleteImportBatch(long batchId) {
        FieldDatabase.ImportBatch batch = db.getImportBatch(batchId);
        if (batch == null) {
            showImports();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete imported batch?")
                .setMessage("Remove the remaining data created by “" + batch.sourceName + "”?\n\n"
                        + "This does not delete unrelated saved markers, recorded tracks, prospecting areas, or Field Records.")
                .setPositiveButton("Delete import", (d, w) -> deleteImportBatch(batchId))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteImportBatch(long batchId) {
        List<Long> waypointIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_WAYPOINT);
        Set<Long> ids = new HashSet<>(waypointIds);
        waypointRepository.getAll(all -> {
            ArrayList<WaypointEntity> owned = new ArrayList<>();
            for (WaypointEntity waypoint : all) {
                if (ids.contains(waypoint.id)) owned.add(waypoint);
            }
            waypointRepository.deleteAll(owned, count -> {
                if (count > 0) waypointDataChanged = true;
                db.deleteImportBatchFieldItems(batchId);
                toast("Imported batch removed.");
                showImports();
            });
        });
    }

    private void focusImportBatch(long batchId) {
        ArrayList<GeoMath.Point> all = new ArrayList<>();
        for (Long id : db.getImportItemIds(batchId, FieldDatabase.IMPORT_TRACK)) {
            all.addAll(db.getTrackPoints(id));
            FieldMapState.showTrack(this, id);
        }
        for (Long id : db.getImportItemIds(batchId, FieldDatabase.IMPORT_AREA)) {
            FieldDatabase.Area area = db.getArea(id);
            if (area != null) all.addAll(area.points);
        }

        Set<Long> waypointIds = new HashSet<>(db.getImportItemIds(batchId, FieldDatabase.IMPORT_WAYPOINT));
        waypointRepository.getAll(items -> {
            for (WaypointEntity waypoint : items) {
                if (waypointIds.contains(waypoint.id)) {
                    all.add(new GeoMath.Point(waypoint.latitude, waypoint.longitude));
                }
            }
            FieldMapState.Bounds bounds = FieldMapState.Bounds.fromPoints(all);
            if (bounds == null) {
                toast("No remaining geometry was found in this import.");
                return;
            }
            FieldMapState.requestFocusBounds(this, bounds);
            returnToMap();
        });
    }

    private void showImportedWaypointsByIds(List<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        waypointRepository.getAll(all -> {
            ArrayList<WaypointEntity> items = new ArrayList<>();
            for (WaypointEntity waypoint : all) if (wanted.contains(waypoint.id)) items.add(waypoint);
            showImportedWaypoints(items);
        });
    }

    private void showImportedWaypoints(List<WaypointEntity> items) {
        LinearLayout root = page();
        root.addView(title("Imported waypoints"));
        root.addView(help("These are normal RockMap saved markers, but their import batch remembers which file created them."));
        if (items.isEmpty()) {
            root.addView(help("No remaining waypoints in this batch."));
        } else {
            for (WaypointEntity w : items) {
                root.addView(action(w.name, CoordinateFormats.decimal(w.latitude, w.longitude),
                        v -> showPointNavigation(w.name, new GeoMath.Point(w.latitude, w.longitude))));
            }
        }
        root.addView(nav("Back to imported data", v -> showImports()));
        setContentView(scroll(root));
    }

    private void showImportedTracks(List<Long> ids) {
        LinearLayout root = page();
        root.addView(title("Imported tracks"));
        boolean any = false;
        for (Long id : ids) {
            FieldDatabase.Track t = db.getTrack(id);
            if (t == null) continue;
            any = true;
            root.addView(action(t.name, trackStatus(t, db.getTrackPoints(t.id)) + "\nTap to open on the real map.",
                    v -> showTrackOnMap(t.id)));
        }
        if (!any) root.addView(help("No remaining tracks in this batch."));
        root.addView(nav("Back to imported data", v -> showImports()));
        setContentView(scroll(root));
    }

    private void showImportedAreas(List<Long> ids) {
        LinearLayout root = page();
        root.addView(title("Imported areas"));
        boolean any = false;
        for (Long id : ids) {
            FieldDatabase.Area a = db.getArea(id);
            if (a == null) continue;
            any = true;
            root.addView(action(a.name, GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(a.points)),
                    v -> showArea(a)));
        }
        if (!any) root.addView(help("No remaining areas in this batch."));
        root.addView(nav("Back to imported data", v -> showImports()));
        setContentView(scroll(root));
    }

    // ---------- COORDINATES ----------

    private void showCoordinates() {
        LinearLayout root = page();
        root.addView(title("Coordinate formats"));
        root.addView(help("Enter latitude/longitude in decimal, DDM or DMS. Output uses WGS84. UTM/MGRS are displayed for supported latitudes."));
        EditText input = input("Latitude, longitude", "");
        root.addView(input);
        TextView output = help("No coordinate converted yet.");
        output.setTextIsSelectable(true);
        root.addView(output);

        LinearLayout row = row();
        row.addView(small("Use GPS", v -> runWithPreciseLocation(() ->
                locationRepository.requestFreshPrecise(l -> {
                    input.setText(CoordinateFormats.decimal(l.getLatitude(), l.getLongitude()));
                    renderFormats(input, output);
                }, this::toast))), weight());
        row.addView(small("Convert", v -> renderFormats(input, output)), weight());
        root.addView(row);
        root.addView(back());
        setContentView(scroll(root));
    }

    private void renderFormats(EditText input, TextView output) {
        try {
            CoordinateParser.Result r = CoordinateParser.parse(input.getText().toString());
            CoordinateFormats.Utm utm = CoordinateFormats.toUtm(r.latitude, r.longitude);
            output.setText("Decimal\n" + CoordinateFormats.decimal(r.latitude, r.longitude)
                    + "\n\nDDM\n" + CoordinateFormats.ddm(r.latitude, r.longitude)
                    + "\n\nDMS\n" + CoordinateFormats.dms(r.latitude, r.longitude)
                    + "\n\nUTM (WGS84)\n" + utm.label()
                    + "\n\nMGRS (5-digit grid)\n" + CoordinateFormats.mgrs(r.latitude, r.longitude));
        } catch (IllegalArgumentException ex) {
            input.setError(ex.getMessage());
        }
    }

    private void showPointNavigation(String name, GeoMath.Point target) {
        FieldMapState.startNavigation(this, name, target);
        FieldMapState.requestFocusBounds(this,
                new FieldMapState.Bounds(target.lat, target.lon, target.lat, target.lon));
        FieldMapState.clearSelectedTrackDetail(this);
        returnToMap();
    }

    // ---------- LOCATION / ACTIVITY RESULTS ----------

    private void runWithPreciseLocation(Runnable action) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        pendingLocationAction = action;
        new AlertDialog.Builder(this)
                .setTitle("Precise location required")
                .setMessage("This field action needs a precise GPS fix. RockMap does not request Android background-location permission. Track recording continues only after you explicitly start it and runs as a visible Android foreground service.")
                .setPositiveButton("Continue", (d, w) -> requestPermissions(
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                        REQ_LOCATION))
                .setNegativeButton("Cancel", (d, w) -> pendingLocationAction = null)
                .show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCATION) return;
        Runnable pending = pendingLocationAction;
        pendingLocationAction = null;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (started) locationRepository.start();
            if (pending != null) pending.run();
        } else {
            toast("Precise location was not granted.");
        }
    }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT) {
            handleImport(uri);
        } else if (requestCode == REQ_PHOTO) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {}
            pendingPhotoUri = uri.toString();
            toast("Photo attached. Tap Save to keep the field record.");
        }
    }

    @Override public void onLocation(Location location) {}
    @Override public void onLocationError(String message) { toast(message); }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        if (locationRepository.hasCoarsePermission()) locationRepository.start();
    }

    @Override protected void onStop() {
        started = false;
        locationRepository.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        waypointRepository.close();
        super.onDestroy();
    }

    // ---------- UI / IO ----------

    private void returnToMap() {
        Intent intent = new Intent(this, MainActivity.class);
        // If waypoint rows changed, recreate MainActivity once so its own waypoint cache and tap
        // resolution are refreshed. Otherwise preserve the current map Activity and camera.
        if (waypointDataChanged) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        else intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private LinearLayout page() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(14), dp(18), dp(24));
        l.setBackgroundColor(0xfffafafa);
        return l;
    }

    private ScrollView scroll(View content) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.addView(content);
        s.setOnApplyWindowInsetsListener((v, i) -> {
            v.setPadding(i.getSystemWindowInsetLeft(), i.getSystemWindowInsetTop(),
                    i.getSystemWindowInsetRight(), i.getSystemWindowInsetBottom());
            return i;
        });
        s.requestApplyInsets();
        return s;
    }

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(24f);
        t.setTextColor(0xff202020);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16f);
        t.setTextColor(0xff303030);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView help(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(0xff555555);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        b.setMinHeight(dp(50));
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        return b;
    }

    private View action(String title, String detail, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackgroundColor(0xffffffff);

        TextView h = new TextView(this);
        h.setText(title + "  ›");
        h.setTextSize(16f);
        h.setTextColor(0xff205b93);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(h);

        TextView d = help(detail);
        d.setPadding(0, dp(3), 0, 0);
        card.addView(d);
        card.setClickable(true);
        card.setFocusable(true);
        card.setMinimumHeight(dp(68));
        card.setOnClickListener(listener);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(4), 0, dp(4));
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private Button nav(String text, View.OnClickListener listener) {
        Button b = button(text);
        b.setOnClickListener(listener);
        return b;
    }

    private Button back() {
        return nav("Back to map", v -> returnToMap());
    }

    private Button small(String text, View.OnClickListener l) {
        Button b = button(text);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private EditText input(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setSingleLine(true);
        e.setTextSize(14f);
        e.setPadding(dp(8), dp(8), dp(8), dp(8));
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text == null ? "" : text, Toast.LENGTH_LONG).show();
    }

    private String bounded(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String durationLabel(long ms) {
        long minutes = ms / 60000L;
        long hours = minutes / 60L;
        minutes %= 60L;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
    }

    private byte[] read(Uri uri, int max) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Android could not open the selected file.");
            byte[] buf = new byte[16384];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > max) throw new IOException("Selected file exceeds the 10 MB import limit.");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private String displayName(Uri uri) {
        String fallback = uri.getLastPathSegment() == null ? "selected file" : uri.getLastPathSegment();
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) return n.trim();
            }
        } catch (RuntimeException ignored) {}
        return fallback;
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }
}
