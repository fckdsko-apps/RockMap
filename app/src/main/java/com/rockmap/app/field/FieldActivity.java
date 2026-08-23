package com.rockmap.app.field;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
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
import com.rockmap.app.research.ResearchActivity;
import com.rockmap.app.research.ResearchResultStore;
import com.rockmap.app.waypoints.WaypointEntity;
import com.rockmap.app.waypoints.WaypointRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
    private static final int REQ_EXPORT = 814;
    private static final int REQ_RESEARCH = 815;
    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;

    private static final String STATE_EXPORT_KIND = "field.export.kind";
    private static final String STATE_EXPORT_FORMAT = "field.export.format";
    private static final String STATE_EXPORT_ID = "field.export.id";

    private static final String EXPORT_SAVED = "saved";
    private static final String EXPORT_TRACK = "track";
    private static final String EXPORT_TRACKS = "tracks";
    private static final String EXPORT_RECORDS = "records";
    private static final String EXPORT_AREA = "area";
    private static final String EXPORT_AREAS = "areas";
    private static final String EXPORT_IMPORT = "import";
    private static final String EXPORT_ALL = "all";
    private static final String EXPORT_RESEARCH = "research";

    private static final String FORMAT_GEOJSON = "geojson";
    private static final String FORMAT_GPX = "gpx";
    private static final String FORMAT_CSV = "csv";
    private static final String FORMAT_KML = "kml";

    private FieldDatabase db;
    private WaypointRepository waypointRepository;
    private LocationRepository locationRepository;
    private Runnable pendingLocationAction;
    private String pendingPhotoUri = "";
    private String pendingExportKind = "";
    private String pendingExportFormat = "";
    private long pendingExportId = -1L;
    private boolean started;
    private boolean waypointDataChanged;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = FieldDatabase.get(this);
        waypointRepository = new WaypointRepository(this);
        locationRepository = new LocationRepository(this, this);
        if (state != null) {
            pendingExportKind = state.getString(STATE_EXPORT_KIND, "");
            pendingExportFormat = state.getString(STATE_EXPORT_FORMAT, "");
            pendingExportId = state.getLong(STATE_EXPORT_ID, -1L);
        }

        String screen = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_SCREEN);
        if ("tracks".equals(screen)) showTracks();
        else if ("records".equals(screen)) showFieldRecords();
        else if ("imports".equals(screen)) showImports();
        else if ("export".equals(screen)) showExportHub();
        else if ("coordinates".equals(screen)) showCoordinates();
        else if ("areas".equals(screen)) showProspectingAreas();
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

        root.addView(action(FieldUiNames.TRACK,
                "Record GPS tracks that draw live on the main map. Opening a saved track now opens its real basemap view instead of an abstract squiggle.",
                v -> showTracks()));
        root.addView(action(FieldUiNames.FIELD_RECORDS,
                "Richer saved observations with category, mineral, sample ID, notes, photo, GPS accuracy and elevation.",
                v -> showFieldRecords()));
        root.addView(action(FieldUiNames.SAVED_LOCATIONS,
                "View your existing RockMap Saved Locations or copy one into a richer Field Record.",
                v -> showLegacyWaypoints()));
        root.addView(action(FieldUiNames.PROSPECTING_AREAS,
                "Create, open, analyze, map, and manage saved prospecting areas.",
                v -> showProspectingAreas()));
        root.addView(action(FieldUiNames.MEASURE,
                "Start a temporary map measurement. Save it as a Prospecting Area when you want to keep and analyze the polygon.",
                v -> { FieldMapState.requestMeasurement(this); returnToMap(); }));
        root.addView(action(FieldUiNames.IMPORT,
                "Import GPX, KML, or GeoJSON files into RockMap.",
                v -> beginImport()));
        root.addView(action(FieldUiNames.IMPORTED_DATA,
                "Review imported files, show their contents on the map, or remove one import without affecting unrelated data.",
                v -> showImports()));
        root.addView(action("Research",
                "Open Mineral Evidence, Geology, and Combined Area Analysis.",
                v -> startResearch(new Intent(this, ResearchActivity.class))));
        root.addView(action(FieldUiNames.EXPORT,
                "Export Saved Locations, Tracks, Field Records, Prospecting Areas, imported files, or combined field data.",
                v -> showExportHub()));
        root.addView(action(FieldUiNames.COORDINATES,
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
                        FieldMapState.clearViewedMapContext(this);
                        FieldMapState.setExpandedTool(this, FieldMapState.TOOL_TRACK);
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
            FieldMapState.clearViewedMapContext(this);
        } else {
            FieldMapState.selectTrackDetail(this, trackId);
        }
        FieldMapState.setExpandedTool(this, FieldMapState.TOOL_TRACK);
        returnToMap();
    }

    // ---------- FIELD RECORDS ----------

    private void showFieldRecords() {
        LinearLayout root = page();
        root.addView(title(FieldUiNames.FIELD_RECORDS));
        LinearLayout add = row();
        add.addView(small("New at GPS", v -> newFieldAtGps()), weight());
        add.addView(small("New at coordinates", v -> newFieldAtCoordinates()), weight());
        root.addView(add);

        List<FieldDatabase.FieldRecord> records = db.listFieldRecords();
        if (records.isEmpty()) {
            root.addView(help("No Field Records yet."));
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
        root.addView(action("Show on Map",
                "Center this Field Record on the main map without starting navigation.",
                v -> {
                    FieldMapState.clearViewedMapContext(this);
                    FieldMapState.requestFocusBounds(this, new FieldMapState.Bounds(r.lat, r.lon, r.lat, r.lon));
                    returnToMap();
                }));
        root.addView(action("Research this location",
                "Choose a radius, then inspect mapped geology and continue into Mineral Evidence or Historic Mines & Workings.",
                v -> startResearch(new Intent(this, ResearchActivity.class)
                        .putExtra(ResearchActivity.EXTRA_POINT_LAT, r.lat)
                        .putExtra(ResearchActivity.EXTRA_POINT_LON, r.lon)
                        .putExtra(ResearchActivity.EXTRA_POINT_LABEL, r.name))));
        View createAreaAction = action("Create Prospecting Area Around Here",
                "Choose a radius around this Field Record and save it as a Prospecting Area.",
                v -> ProspectingAreaCreator.chooseRadiusAndSave(this, r.lat, r.lon, r.name,
                        "Created from Field Record: " + r.name));
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
        root.addView(nav("Back to Field Records", v -> showFieldRecords()));
        setContentView(pageWithPinnedAction(root, createAreaAction));
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
            root.addView(title(FieldUiNames.SAVED_LOCATIONS));
            root.addView(help("These are the existing RockMap Saved Locations used by the main map. Their symbols and labels follow Layers > Saved Locations."));
            if (items.isEmpty()) {
                root.addView(help("No Saved Locations yet."));
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
        root.addView(action("Show on Map",
                "Center this Saved Location on the main map.",
                v -> {
                    FieldMapState.clearViewedMapContext(this);
                    FieldMapState.requestFocusBounds(this,
                            new FieldMapState.Bounds(w.latitude, w.longitude, w.latitude, w.longitude));
                    returnToMap();
                }));
        root.addView(action("Navigate to this point",
                "Open the main map with a live target line, distance and bearing from GPS.",
                v -> showPointNavigation(w.name, new GeoMath.Point(w.latitude, w.longitude))));
        root.addView(action("Copy to Field Record",
                "Creates a richer editable Field Record; the original Saved Location remains.",
                v -> {
                    long now = System.currentTimeMillis();
                    FieldDatabase.FieldRecord r = new FieldDatabase.FieldRecord(
                            0, w.name, "Saved Location", "", "", w.notes,
                            w.latitude, w.longitude, Double.NaN, w.accuracyMeters, "", now, now);
                    r.id = db.insertFieldRecord(r);
                    showFieldRecord(r.id);
                }));
        root.addView(nav("Back to Saved Locations", v -> showLegacyWaypoints()));
        setContentView(scroll(root));
    }

    // ---------- MEASURE / AREAS ----------

    private void showMeasure() {
        showProspectingAreas();
    }

    private void showProspectingAreas() {
        LinearLayout root = page();
        root.addView(title(FieldUiNames.PROSPECTING_AREAS));
        root.addView(help("Saved polygons live here. Create one on the map, then reopen it for map view, analysis, export or deletion."));
        root.addView(action("Create Prospecting Area",
                "Draw the boundary on the map, then save it as a Prospecting Area.",
                v -> {
                    FieldMapState.requestMeasurement(this);
                    returnToMap();
                }));
        List<FieldDatabase.Area> areas = db.listAreas();
        root.addView(section("Saved Prospecting Areas"));
        if (areas.isEmpty()) {
            root.addView(help("No saved Prospecting Areas yet."));
        } else {
            for (FieldDatabase.Area a : areas) {
                root.addView(action(a.name,
                        GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(a.points)) + " · " + a.points.size() + " vertices",
                        v -> showArea(a)));
            }
        }
        root.addView(nav("Back to Field", v -> showHub()));
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
                + "\n\nUse Show on Map for the geographic view."));
        root.addView(action("Analyze This Area",
                "Use this saved area for Combined Area Analysis: geology first, with Mineral Evidence and historic activity immediately available from the result.",
                v -> startResearch(new Intent(this, ResearchActivity.class)
                        .putExtra(ResearchActivity.EXTRA_AREA_ID, a.id))));
        root.addView(action("Show on Map",
                "Zoom to this saved area and keep the polygon visible in geographic context.",
                v -> {
                    ProspectingAreaVisibility.showOnly(this, a.id);
                    FieldMapState.setAreasVisible(this, true);
                    FieldMapState.clearViewedMapContext(this);
                    FieldMapState.Bounds bounds = FieldMapState.Bounds.fromPoints(a.points);
                    if (bounds != null) FieldMapState.requestFocusBounds(this, bounds);
                    returnToMap();
                }));
        Button del = button("Delete Area");
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete Area?")
                .setPositiveButton("Delete", (d, w) -> {
                    db.deleteArea(a.id);
                    ProspectingAreaVisibility.forget(this, a.id);
                    showProspectingAreas();
                })
                .setNegativeButton("Cancel", null)
                .show());
        root.addView(del);
        root.addView(nav("Back to Prospecting Areas", v -> showProspectingAreas()));
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
                                + ".\n\nTo test a clean re-import, remove that import first. You can still intentionally import another copy.")
                        .setPositiveButton("Open Existing Import", (d, w) -> showImportBatch(previous.id))
                        .setNeutralButton("Import Another Copy", (d, w) -> confirmImport(result, name, sha))
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
                + result.waypoints.size() + " Saved Locations\n"
                + result.tracks.size() + " tracks\n"
                + result.areas.size() + " Prospecting Areas\n"
                + result.pointCount + " total geometry points\n\n"
                + "RockMap will track this file as one removable import. Existing unrelated RockMap data will not be replaced.";
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
                ProspectingAreaVisibility.show(this, id);
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

        box.addView(help("Imported " + r.waypoints.size() + " Saved Location" + (r.waypoints.size() == 1 ? "" : "s")
                + ", " + r.tracks.size() + " Track" + (r.tracks.size() == 1 ? "" : "s")
                + ", and " + r.areas.size() + " Prospecting Area" + (r.areas.size() == 1 ? "" : "s")
                + ". This import can now be shown, reviewed, or removed as one unit."));

        box.addView(action("Show Import on Map",
                "Zoom to all imported geometry. Tracks, Prospecting Areas, and Saved Locations are visible immediately.",
                v -> {
                    holder[0].dismiss();
                    focusImportBatch(batchId);
                }));

        if (!r.waypoints.isEmpty()) {
            box.addView(action("Saved Locations from This File",
                    r.waypoints.size() + " imported Saved Location" + (r.waypoints.size() == 1 ? "" : "s") + ".",
                    v -> {
                        holder[0].dismiss();
                        showImportedWaypoints(r.waypoints);
                    }));
        }

        List<Long> trackIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_TRACK);
        if (!trackIds.isEmpty()) {
            box.addView(action("Tracks from This File",
                    trackIds.size() + " imported track" + (trackIds.size() == 1 ? "" : "s") + ".",
                    v -> {
                        holder[0].dismiss();
                        showImportedTracks(trackIds);
                    }));
        }

        List<Long> areaIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_AREA);
        if (!areaIds.isEmpty()) {
            box.addView(action("Prospecting Areas from This File",
                    areaIds.size() + " imported Prospecting Area" + (areaIds.size() == 1 ? "" : "s") + ".",
                    v -> {
                        holder[0].dismiss();
                        showImportedAreas(areaIds);
                    }));
        }

        box.addView(action("Manage This Import",
                "Open this import later or remove only the objects created by this file.",
                v -> {
                    holder[0].dismiss();
                    showImportBatch(batchId);
                }));

        box.addView(action("Remove This Import",
                "Remove this import’s remaining Saved Locations, Tracks, and Prospecting Areas without touching unrelated RockMap data.",
                v -> {
                    holder[0].dismiss();
                    confirmDeleteImportBatch(batchId);
                }));

        holder[0] = new AlertDialog.Builder(this)
                .setTitle("Import Complete — " + displayName)
                .setView(scroll(box))
                .setNegativeButton("Done", (d, w) -> showHub())
                .create();
        holder[0].show();
    }

    private void showImports() {
        LinearLayout root = page();
        root.addView(title(FieldUiNames.IMPORTED_DATA));
        root.addView(help("Each GPX, KML, or GeoJSON file you import is tracked separately. Removing an import removes only the objects created by that file."));

        List<FieldDatabase.ImportBatch> batches = db.listImportBatches();
        root.addView(section("Imported Files"));
        if (batches.isEmpty()) {
            root.addView(help("No imported files yet."));
            root.addView(action("Import File",
                    "Choose a GPX, KML, or GeoJSON file.",
                    v -> beginImport()));
        } else {
            root.addView(action("Import Another File",
                    "Choose another GPX, KML, or GeoJSON file.",
                    v -> beginImport()));
            for (FieldDatabase.ImportBatch batch : batches) {
                String detail = DateFormat.getDateTimeInstance().format(new Date(batch.importedAt))
                        + "\n" + batch.waypointCount + " Saved Locations · "
                        + batch.trackCount + " Tracks · " + batch.areaCount + " Prospecting Areas";
                root.addView(action(batch.sourceName, detail, v -> showImportBatch(batch.id)));
            }
        }

        root.addView(section("Older Imports"));
        root.addView(help("Older imports created before RockMap began tracking each imported file separately cannot be safely removed as one group. Manage those older items from Saved Locations, Tracks, or Prospecting Areas. New imports are tracked individually."));
        root.addView(action("Review Saved Locations", "Delete any older imported Saved Location manually if you need a completely clean first re-test.", v -> showLegacyWaypoints()));
        root.addView(action("Review Tracks", "Open or remove older untracked imported tracks.", v -> showTracks()));
        root.addView(action("Review Prospecting Areas", "Open or remove older Prospecting Areas.", v -> showMeasure()));
        root.addView(nav("Back to Field", v -> showHub()));
        setContentView(scroll(root));
    }

    private void showImportBatch(long batchId) {
        FieldDatabase.ImportBatch batch = db.getImportBatch(batchId);
        if (batch == null) {
            toast("Import not found.");
            showImports();
            return;
        }

        LinearLayout root = page();
        root.addView(title(batch.sourceName));
        String fingerprint = batch.sha256 == null || batch.sha256.length() < 12
                ? batch.sha256 : batch.sha256.substring(0, 12) + "…";
        root.addView(help("Imported: " + DateFormat.getDateTimeInstance().format(new Date(batch.importedAt))
                + "\nOriginally imported: " + batch.waypointCount + " Saved Locations · "
                + batch.trackCount + " Tracks · " + batch.areaCount + " Prospecting Areas"
                + "\nFile fingerprint: " + fingerprint
                + "\n\nIf an item was already deleted individually, removing this import simply removes the remaining items."));

        root.addView(action("Show Import on Map",
                "Zoom to all remaining geometry that belongs to this import.",
                v -> focusImportBatch(batchId)));

        List<Long> waypointIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_WAYPOINT);
        List<Long> trackIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_TRACK);
        List<Long> areaIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_AREA);

        if (!waypointIds.isEmpty()) {
            root.addView(action("Saved Locations from This File", waypointIds.size() + " Saved Location" + (waypointIds.size() == 1 ? "" : "s") + ".",
                    v -> showImportedWaypointsByIds(waypointIds)));
        }
        if (!trackIds.isEmpty()) {
            root.addView(action("Tracks from This File", trackIds.size() + " track" + (trackIds.size() == 1 ? "" : "s") + ".",
                    v -> showImportedTracks(trackIds)));
        }
        if (!areaIds.isEmpty()) {
            root.addView(action("Prospecting Areas from This File", areaIds.size() + " Prospecting Area" + (areaIds.size() == 1 ? "" : "s") + ".",
                    v -> showImportedAreas(areaIds)));
        }

        Button delete = button("Remove This Import");
        delete.setOnClickListener(v -> confirmDeleteImportBatch(batchId));
        root.addView(delete);
        root.addView(nav("Back to Manage Imports", v -> showImports()));
        setContentView(scroll(root));
    }

    private void confirmDeleteImportBatch(long batchId) {
        FieldDatabase.ImportBatch batch = db.getImportBatch(batchId);
        if (batch == null) {
            showImports();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Remove This Import?")
                .setMessage("Remove the remaining data created by “" + batch.sourceName + "”?\n\n"
                        + "This does not delete unrelated Saved Locations, recorded Tracks, Prospecting Areas, or Field Records.")
                .setPositiveButton("Remove Import", (d, w) -> deleteImportBatch(batchId))
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
                toast("Import removed.");
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
            if (area != null) {
                all.addAll(area.points);
                ProspectingAreaVisibility.show(this, id);
            }
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
            FieldMapState.clearViewedMapContext(this);
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
        root.addView(title("Saved Locations from This File"));
        root.addView(help("These are normal RockMap Saved Locations. RockMap remembers which imported file created them."));
        if (items.isEmpty()) {
            root.addView(help("No remaining Saved Locations in this import."));
        } else {
            for (WaypointEntity w : items) {
                root.addView(action(w.name, CoordinateFormats.decimal(w.latitude, w.longitude) + "\nTap for map and navigation options.",
                        v -> showImportedWaypoint(w)));
            }
        }
        root.addView(nav("Back to Manage Imports", v -> showImports()));
        setContentView(scroll(root));
    }

    private void showImportedWaypoint(WaypointEntity w) {
        LinearLayout root = page();
        root.addView(title(w.name == null || w.name.trim().isEmpty() ? "Imported Saved Location" : w.name));
        root.addView(help(CoordinateFormats.decimal(w.latitude, w.longitude)
                + (w.accuracyMeters >= 0 ? String.format(Locale.US, "\nGPS accuracy: ±%.1f m", w.accuracyMeters) : "")
                + (w.notes == null || w.notes.trim().isEmpty() ? "" : "\n\n" + w.notes)));
        root.addView(action("Show on Map",
                "Center this imported Saved Location without changing any active navigation target.",
                v -> {
                    FieldMapState.clearViewedMapContext(this);
                    FieldMapState.requestFocusBounds(this,
                            new FieldMapState.Bounds(w.latitude, w.longitude, w.latitude, w.longitude));
                    returnToMap();
                }));
        root.addView(action("Navigate to this point",
                "Make this marker the active navigation target.",
                v -> showPointNavigation(w.name, new GeoMath.Point(w.latitude, w.longitude))));
        root.addView(nav("Back to Manage Imports", v -> showImports()));
        setContentView(scroll(root));
    }

    private void showImportedTracks(List<Long> ids) {
        LinearLayout root = page();
        root.addView(title("Tracks from This File"));
        boolean any = false;
        for (Long id : ids) {
            FieldDatabase.Track t = db.getTrack(id);
            if (t == null) continue;
            any = true;
            root.addView(action(t.name, trackStatus(t, db.getTrackPoints(t.id)) + "\nTap to open on the real map.",
                    v -> showTrackOnMap(t.id)));
        }
        if (!any) root.addView(help("No remaining tracks in this import."));
        root.addView(nav("Back to Manage Imports", v -> showImports()));
        setContentView(scroll(root));
    }

    private void showImportedAreas(List<Long> ids) {
        LinearLayout root = page();
        root.addView(title("Prospecting Areas from This File"));
        boolean any = false;
        for (Long id : ids) {
            FieldDatabase.Area a = db.getArea(id);
            if (a == null) continue;
            any = true;
            root.addView(action(a.name, GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(a.points)),
                    v -> showArea(a)));
        }
        if (!any) root.addView(help("No remaining Prospecting Areas in this import."));
        root.addView(nav("Back to Manage Imports", v -> showImports()));
        setContentView(scroll(root));
    }


    // ---------- EXPORT ----------

    private interface ExportChoiceHandler {
        void onChoice(int which);
    }

    private void showExportHub() {
        waypointRepository.getAll(waypoints -> {
            List<FieldDatabase.Track> tracks = db.listTracks(0);
            List<FieldDatabase.FieldRecord> records = db.listFieldRecords();
            List<FieldDatabase.Area> areas = db.listAreas();
            List<FieldDatabase.ImportBatch> batches = db.listImportBatches();

            LinearLayout root = page();
            root.addView(title(FieldUiNames.EXPORT));
            root.addView(help("Every outlined box below is an export option. Tap the entire box to export that type of data. RockMap will ask for a specific item or file format when needed, then Android will ask where to save the file."));
            root.addView(section("Export Options"));

            root.addView(exportOption(FieldUiNames.SAVED_LOCATIONS,
                    waypoints.size() + " saved location" + (waypoints.size() == 1 ? "" : "s"),
                    waypoints.isEmpty() ? "Nothing to export" : "Tap this box to export ›",
                    !waypoints.isEmpty(),
                    v -> showSavedLocationExportFormats(waypoints.size())));

            root.addView(exportOption("Tracks",
                    tracks.size() + " track" + (tracks.size() == 1 ? "" : "s") + " · choose all or one track",
                    tracks.isEmpty() ? "Nothing to export" : "Tap this box to export ›",
                    !tracks.isEmpty(),
                    v -> showTrackExportPicker()));

            root.addView(exportOption(FieldUiNames.FIELD_RECORDS,
                    records.size() + " record" + (records.size() == 1 ? "" : "s"),
                    records.isEmpty() ? "Nothing to export" : "Tap this box to export ›",
                    !records.isEmpty(),
                    v -> showFieldRecordExportFormats()));

            root.addView(exportOption(FieldUiNames.PROSPECTING_AREAS,
                    areas.size() + " Prospecting Area" + (areas.size() == 1 ? "" : "s") + " · choose all or one",
                    areas.isEmpty() ? "Nothing to export" : "Tap this box to export ›",
                    !areas.isEmpty(),
                    v -> showAreaExportPicker()));

            root.addView(exportOption("Imported Files",
                    batches.size() + " imported file" + (batches.size() == 1 ? "" : "s") + " · choose one",
                    batches.isEmpty() ? "Nothing to export" : "Tap this box to export ›",
                    !batches.isEmpty(),
                    v -> showImportExportPicker()));

            if (ResearchResultStore.exists(this)) {
                ResearchResultStore.Summary research = ResearchResultStore.summary(this);
                root.addView(exportOption("Last Analysis",
                        research.title + " · " + research.count + " mapped geology area" + (research.count == 1 ? "" : "s")
                                + " · GeoJSON or CSV",
                        "Tap this box to export ›",
                        true,
                        v -> showResearchExportFormats()));
            } else {
                root.addView(exportOption("Last Analysis",
                        "No analysis saved yet · run Research first",
                        "Unavailable until an analysis is saved",
                        false,
                        null));
            }

            int total = waypoints.size() + tracks.size() + records.size() + areas.size();
            root.addView(exportOption("All Field Data",
                    total + " saved object" + (total == 1 ? "" : "s") + " · combined GeoJSON",
                    total <= 0 ? "Nothing to export" : "Tap this box to export GeoJSON ›",
                    total > 0,
                    v -> beginFieldExport(EXPORT_ALL, FORMAT_GEOJSON, -1L,
                            "RockMap-All-Field-Data.geojson", "application/geo+json")));

            root.addView(section("Trips"));
            root.addView(help("Trip exports stay under Main map → Trips → open a trip → Export."));
            root.addView(help("Exporting never removes or uploads RockMap data. Temporary unsaved measurements are not exported. Photos are referenced by Field Record exports but are not embedded. All field map data is a GIS export, not a full restore backup."));
            root.addView(nav("Back to Field", v -> showHub()));
            setContentView(scroll(root));
        });
    }

    private void showSavedLocationExportFormats(int count) {
        if (count <= 0) {
            toast("There are no Saved Locations to export.");
            return;
        }
        showExportChoiceDialog(
                "Choose Export Format · Saved Locations",
                null,
                new String[]{"GeoJSON Backup", "GPX"},
                new String[]{
                        "Preserves names, notes, timestamps and accuracy; compatible with RockMap's Saved Locations backup import.",
                        "Portable waypoint file for GPS and mapping apps."
                },
                new String[]{"Tap this box to export GeoJSON ›", "Tap this box to export GPX ›"},
                which -> {
                    if (which == 0) {
                        beginFieldExport(EXPORT_SAVED, FORMAT_GEOJSON, -1L,
                                "RockMap-Locations.geojson", "application/geo+json");
                    } else {
                        beginFieldExport(EXPORT_SAVED, FORMAT_GPX, -1L,
                                "RockMap-Locations.gpx", "application/gpx+xml");
                    }
                });
    }

    private void showTrackExportPicker() {
        List<FieldDatabase.Track> stored = db.listTracks(0);
        ArrayList<FieldDatabase.Track> tracks = new ArrayList<>();
        for (FieldDatabase.Track track : stored) {
            if (track != null && db.getTrackPoints(track.id).size() >= 2) tracks.add(track);
        }
        if (tracks.isEmpty()) {
            toast("There are no tracks with enough recorded points to export yet.");
            return;
        }

        String[] labels = new String[tracks.size() + 1];
        String[] details = new String[tracks.size() + 1];
        String[] ctas = new String[tracks.size() + 1];
        labels[0] = "All Exportable Tracks";
        details[0] = "Export every stored track that currently has at least 2 points.";
        ctas[0] = "Tap this box to choose ›";
        for (int i = 0; i < tracks.size(); i++) {
            FieldDatabase.Track track = tracks.get(i);
            labels[i + 1] = track.name;
            details[i + 1] = trackStatus(track, db.getTrackPoints(track.id));
            ctas[i + 1] = "Tap this box to choose ›";
        }
        showExportChoiceDialog(
                "Choose Tracks",
                "Choose all exportable tracks or one track.",
                labels, details, ctas,
                which -> {
                    long id = which == 0 ? -1L : tracks.get(which - 1).id;
                    String name = which == 0 ? "RockMap-Tracks"
                            : "RockMap-Track-" + safeExportFilename(tracks.get(which - 1).name);
                    showTrackExportFormats(id, name);
                });
    }

    private void showTrackExportFormats(long trackId, String baseName) {
        showExportChoiceDialog(
                trackId < 0L ? "Choose Export Format · All Tracks" : "Choose Export Format · Track",
                null,
                new String[]{"GPX", "GeoJSON"},
                new String[]{
                        "Best for GPS apps and track exchange; includes recorded elevations/times when available.",
                        "Best for GIS and mapping software; includes RockMap track metadata."
                },
                new String[]{"Tap this box to export GPX ›", "Tap this box to export GeoJSON ›"},
                which -> {
                    String kind = trackId < 0L ? EXPORT_TRACKS : EXPORT_TRACK;
                    if (which == 0) {
                        beginFieldExport(kind, FORMAT_GPX, trackId, baseName + ".gpx", "application/gpx+xml");
                    } else {
                        beginFieldExport(kind, FORMAT_GEOJSON, trackId, baseName + ".geojson", "application/geo+json");
                    }
                });
    }

    private void showFieldRecordExportFormats() {
        if (db.listFieldRecords().isEmpty()) {
            toast("There are no Field Records to export.");
            return;
        }
        showExportChoiceDialog(
                "Choose Export Format · Field Records",
                null,
                new String[]{"CSV", "GeoJSON"},
                new String[]{
                        "Spreadsheet-friendly rows with coordinates, sample/category/mineral fields, notes, accuracy, elevation and photo reference.",
                        "GIS-ready point features with the same RockMap metadata. Photo files are not embedded."
                },
                new String[]{"Tap this box to export CSV ›", "Tap this box to export GeoJSON ›"},
                which -> {
                    if (which == 0) {
                        beginFieldExport(EXPORT_RECORDS, FORMAT_CSV, -1L,
                                "RockMap-Field-Records.csv", "text/csv");
                    } else {
                        beginFieldExport(EXPORT_RECORDS, FORMAT_GEOJSON, -1L,
                                "RockMap-Field-Records.geojson", "application/geo+json");
                    }
                });
    }

    private void showAreaExportPicker() {
        List<FieldDatabase.Area> areas = db.listAreas();
        if (areas.isEmpty()) {
            toast("There are no Prospecting Areas to export.");
            return;
        }
        String[] labels = new String[areas.size() + 1];
        String[] details = new String[areas.size() + 1];
        String[] ctas = new String[areas.size() + 1];
        labels[0] = "All Prospecting Areas";
        details[0] = "Export every saved polygon area.";
        ctas[0] = "Tap this box to choose ›";
        for (int i = 0; i < areas.size(); i++) {
            FieldDatabase.Area area = areas.get(i);
            labels[i + 1] = area.name;
            details[i + 1] = area.points.size() + " vertices · "
                    + GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(area.points));
            ctas[i + 1] = "Tap this box to choose ›";
        }
        showExportChoiceDialog(
                "Choose Prospecting Areas",
                "Choose all saved Prospecting Areas or one area.",
                labels, details, ctas,
                which -> {
                    long id = which == 0 ? -1L : areas.get(which - 1).id;
                    String name = which == 0 ? "RockMap-Prospecting-Areas"
                            : "RockMap-Area-" + safeExportFilename(areas.get(which - 1).name);
                    showAreaExportFormats(id, name);
                });
    }

    private void showAreaExportFormats(long areaId, String baseName) {
        showExportChoiceDialog(
                areaId < 0L ? "Choose Export Format · All Prospecting Areas"
                        : "Choose Export Format · Prospecting Area",
                null,
                new String[]{"GeoJSON", "KML"},
                new String[]{
                        "Best for GIS and for bringing polygon geometry back through RockMap's Field import.",
                        "Convenient for Google Earth and other KML-compatible mapping tools."
                },
                new String[]{"Tap this box to export GeoJSON ›", "Tap this box to export KML ›"},
                which -> {
                    String kind = areaId < 0L ? EXPORT_AREAS : EXPORT_AREA;
                    if (which == 0) {
                        beginFieldExport(kind, FORMAT_GEOJSON, areaId, baseName + ".geojson", "application/geo+json");
                    } else {
                        beginFieldExport(kind, FORMAT_KML, areaId, baseName + ".kml", "application/vnd.google-earth.kml+xml");
                    }
                });
    }

    private void showImportExportPicker() {
        List<FieldDatabase.ImportBatch> batches = db.listImportBatches();
        if (batches.isEmpty()) {
            toast("There are no imported files to export.");
            return;
        }
        String[] labels = new String[batches.size()];
        String[] details = new String[batches.size()];
        String[] ctas = new String[batches.size()];
        for (int i = 0; i < batches.size(); i++) {
            FieldDatabase.ImportBatch batch = batches.get(i);
            labels[i] = batch.sourceName;
            details[i] = "Originally " + batch.waypointCount + " Saved Locations · "
                    + batch.trackCount + " Tracks · " + batch.areaCount + " Prospecting Areas";
            ctas[i] = "Tap this box to export GeoJSON ›";
        }
        showExportChoiceDialog(
                "Choose Imported File",
                "Exports the remaining RockMap objects associated with the selected imported file, not the original file bytes.",
                labels, details, ctas,
                which -> {
                    FieldDatabase.ImportBatch batch = batches.get(which);
                    beginFieldExport(EXPORT_IMPORT, FORMAT_GEOJSON, batch.id,
                            "RockMap-Import-" + safeExportFilename(batch.sourceName) + ".geojson",
                            "application/geo+json");
                });
    }

    private void showResearchExportFormats() {
        if (!ResearchResultStore.exists(this)) {
            toast("There is no saved analysis to export.");
            return;
        }
        ResearchResultStore.Summary summary = ResearchResultStore.summary(this);
        String base = "RockMap-Research-" + safeExportFilename(summary.title);
        showExportChoiceDialog(
                "Choose Export Format · Last Analysis",
                summary.title + " · " + summary.count + " mapped geology area" + (summary.count == 1 ? "" : "s"),
                new String[]{"GeoJSON", "CSV"},
                new String[]{
                        "Preserves full mapped-area geometry and USGS SGMC source attributes for GIS/mapping software.",
                        "Spreadsheet-friendly geology attributes. Mapped-area geometry is not included in CSV."
                },
                new String[]{"Tap this box to export GeoJSON ›", "Tap this box to export CSV ›"},
                which -> {
                    if (which == 0) {
                        beginFieldExport(EXPORT_RESEARCH, FORMAT_GEOJSON, -1L,
                                base + ".geojson", "application/geo+json");
                    } else {
                        beginFieldExport(EXPORT_RESEARCH, FORMAT_CSV, -1L,
                                base + ".csv", "text/csv");
                    }
                });
    }

    private void showExportChoiceDialog(String title, String message,
                                        String[] labels, String[] details, String[] ctas,
                                        ExportChoiceHandler handler) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(12));
        content.setBackgroundColor(0xfffafafa);

        String guidance = "Tap an outlined box below.";
        if (message != null && !message.trim().isEmpty()) guidance = message.trim() + "\n\n" + guidance;
        content.addView(help(guidance));

        final AlertDialog[] holder = new AlertDialog[1];
        for (int i = 0; i < labels.length; i++) {
            final int choice = i;
            content.addView(exportOption(
                    labels[i],
                    details != null && i < details.length ? details[i] : "",
                    ctas != null && i < ctas.length ? ctas[i] : "Tap this box to select ›",
                    true,
                    v -> {
                        if (holder[0] != null) holder[0].dismiss();
                        handler.onChoice(choice);
                    }));
        }

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroller)
                .setNegativeButton("Cancel", null)
                .create();
        holder[0] = dialog;
        dialog.show();
    }

    private View exportOption(String title, String detail, String cta,
                              boolean enabled, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable background = new GradientDrawable();
        background.setColor(enabled ? 0xffffffff : 0xfff0f0f0);
        background.setStroke(dp(2), enabled ? 0xff66829d : 0xffbdbdbd);
        background.setCornerRadius(dp(8));
        card.setBackground(background);

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(16f);
        heading.setTextColor(enabled ? 0xff173f66 : 0xff777777);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(heading);

        if (detail != null && !detail.trim().isEmpty()) {
            TextView body = new TextView(this);
            body.setText(detail);
            body.setTextSize(13f);
            body.setTextColor(enabled ? 0xff555555 : 0xff888888);
            body.setPadding(0, dp(4), 0, 0);
            card.addView(body);
        }

        TextView actionText = new TextView(this);
        actionText.setText(cta == null ? "" : cta);
        actionText.setTextSize(12.5f);
        actionText.setTextColor(enabled ? 0xff205b93 : 0xff888888);
        actionText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        actionText.setPadding(0, dp(8), 0, 0);
        card.addView(actionText);

        card.setMinimumHeight(dp(76));
        card.setEnabled(enabled);
        card.setClickable(enabled);
        card.setFocusable(enabled);
        if (enabled && listener != null) card.setOnClickListener(listener);
        card.setContentDescription(title + ". " + (detail == null ? "" : detail) + ". " + (cta == null ? "" : cta));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(5), 0, dp(5));
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private void beginFieldExport(String kind, String format, long id, String filename, String mime) {
        pendingExportKind = kind;
        pendingExportFormat = format;
        pendingExportId = id;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        startActivityForResult(intent, REQ_EXPORT);
    }

    private void performPendingExport(Uri uri) {
        final String kind = pendingExportKind;
        final String format = pendingExportFormat;
        final long id = pendingExportId;
        if (kind.isEmpty() || format.isEmpty()) {
            toast("RockMap lost the pending export selection. Choose Export Data and try again.");
            clearPendingExport();
            return;
        }

        if (EXPORT_SAVED.equals(kind) || EXPORT_IMPORT.equals(kind) || EXPORT_ALL.equals(kind)) {
            waypointRepository.getAll(waypoints -> {
                try {
                    String content = buildExportContent(kind, format, id, waypoints);
                    writeExport(uri, content);
                    toast(exportSuccessMessage(kind));
                } catch (Exception ex) {
                    toast("Export failed: " + ex.getMessage());
                } finally {
                    clearPendingExport();
                }
            });
            return;
        }

        try {
            String content = buildExportContent(kind, format, id, new ArrayList<>());
            writeExport(uri, content);
            toast(exportSuccessMessage(kind));
        } catch (Exception ex) {
            toast("Export failed: " + ex.getMessage());
        } finally {
            clearPendingExport();
        }
    }

    private String buildExportContent(String kind, String format, long id,
                                      List<WaypointEntity> waypoints) throws Exception {
        if (EXPORT_SAVED.equals(kind)) {
            return FORMAT_GPX.equals(format)
                    ? FieldExport.savedLocationsGpx(waypoints)
                    : FieldExport.savedLocationsGeoJson(waypoints);
        }
        if (EXPORT_TRACK.equals(kind)) {
            FieldDatabase.Track track = db.getTrack(id);
            if (track == null) throw new IllegalStateException("The selected track no longer exists.");
            ArrayList<FieldExport.TrackData> one = new ArrayList<>();
            one.add(new FieldExport.TrackData(track, db.getTrackPoints(track.id)));
            return FORMAT_GPX.equals(format) ? FieldExport.tracksGpx(one) : FieldExport.tracksGeoJson(one);
        }
        if (EXPORT_TRACKS.equals(kind)) {
            List<FieldExport.TrackData> tracks = exportTrackData(db.listTracks(0));
            return FORMAT_GPX.equals(format) ? FieldExport.tracksGpx(tracks) : FieldExport.tracksGeoJson(tracks);
        }
        if (EXPORT_RECORDS.equals(kind)) {
            List<FieldDatabase.FieldRecord> records = db.listFieldRecords();
            return FORMAT_CSV.equals(format) ? FieldExport.fieldRecordsCsv(records) : FieldExport.fieldRecordsGeoJson(records);
        }
        if (EXPORT_AREA.equals(kind)) {
            FieldDatabase.Area area = db.getArea(id);
            if (area == null) throw new IllegalStateException("The selected Prospecting Area no longer exists.");
            ArrayList<FieldDatabase.Area> one = new ArrayList<>();
            one.add(area);
            return FORMAT_KML.equals(format) ? FieldExport.areasKml(one) : FieldExport.areasGeoJson(one);
        }
        if (EXPORT_AREAS.equals(kind)) {
            List<FieldDatabase.Area> areas = db.listAreas();
            return FORMAT_KML.equals(format) ? FieldExport.areasKml(areas) : FieldExport.areasGeoJson(areas);
        }
        if (EXPORT_IMPORT.equals(kind)) {
            FieldDatabase.ImportBatch batch = db.getImportBatch(id);
            if (batch == null) throw new IllegalStateException("The selected import no longer exists.");
            Set<Long> waypointIds = new HashSet<>(db.getImportItemIds(id, FieldDatabase.IMPORT_WAYPOINT));
            ArrayList<WaypointEntity> batchWaypoints = new ArrayList<>();
            for (WaypointEntity waypoint : waypoints) if (waypointIds.contains(waypoint.id)) batchWaypoints.add(waypoint);

            ArrayList<FieldExport.TrackData> batchTracks = new ArrayList<>();
            for (Long trackId : db.getImportItemIds(id, FieldDatabase.IMPORT_TRACK)) {
                FieldDatabase.Track track = db.getTrack(trackId);
                if (track != null) batchTracks.add(new FieldExport.TrackData(track, db.getTrackPoints(track.id)));
            }
            ArrayList<FieldDatabase.Area> batchAreas = new ArrayList<>();
            for (Long areaId : db.getImportItemIds(id, FieldDatabase.IMPORT_AREA)) {
                FieldDatabase.Area area = db.getArea(areaId);
                if (area != null) batchAreas.add(area);
            }
            return FieldExport.importBatchGeoJson(batch, batchWaypoints, batchTracks, batchAreas);
        }
        if (EXPORT_ALL.equals(kind)) {
            return FieldExport.allFieldGeoJson(
                    waypoints,
                    exportTrackData(db.listTracks(0)),
                    db.listFieldRecords(),
                    db.listAreas());
        }
        if (EXPORT_RESEARCH.equals(kind)) {
            return FORMAT_CSV.equals(format)
                    ? ResearchResultStore.csv(this)
                    : ResearchResultStore.geoJson(this);
        }
        throw new IllegalArgumentException("Unknown export selection.");
    }

    private List<FieldExport.TrackData> exportTrackData(List<FieldDatabase.Track> tracks) {
        ArrayList<FieldExport.TrackData> out = new ArrayList<>();
        if (tracks == null) return out;
        for (FieldDatabase.Track track : tracks) {
            if (track != null) out.add(new FieldExport.TrackData(track, db.getTrackPoints(track.id)));
        }
        return out;
    }

    private void writeExport(Uri uri, String content) throws IOException {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("Android could not open the selected export file.");
            output.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private String exportSuccessMessage(String kind) {
        if (EXPORT_SAVED.equals(kind)) return "Saved Locations exported.";
        if (EXPORT_TRACK.equals(kind)) return "Track exported.";
        if (EXPORT_TRACKS.equals(kind)) return "Tracks exported.";
        if (EXPORT_RECORDS.equals(kind)) return "Field Records exported.";
        if (EXPORT_AREA.equals(kind)) return "Prospecting Area exported.";
        if (EXPORT_AREAS.equals(kind)) return "Prospecting Areas exported.";
        if (EXPORT_IMPORT.equals(kind)) return "Imported file data exported.";
        if (EXPORT_RESEARCH.equals(kind)) return "Analysis exported.";
        return "Field data exported.";
    }

    private void clearPendingExport() {
        pendingExportKind = "";
        pendingExportFormat = "";
        pendingExportId = -1L;
    }

    private String safeExportFilename(String value) {
        String text = value == null ? "RockMap" : value.trim();
        if (text.isEmpty()) text = "RockMap";
        text = text.replaceAll("[^A-Za-z0-9._ -]+", "-").replaceAll("\\s+", "-");
        if (text.length() > 72) text = text.substring(0, 72);
        return text.isEmpty() ? "RockMap" : text;
    }

    // ---------- COORDINATES ----------

    private void showCoordinates() {
        LinearLayout root = page();
        root.addView(title(FieldUiNames.COORDINATES));
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
        FieldMapState.clearViewedMapContext(this);
        FieldMapState.startNavigation(this, name, target);
        FieldMapState.setExpandedTool(this, FieldMapState.TOOL_NAVIGATE);
        FieldMapState.requestFocusBounds(this,
                new FieldMapState.Bounds(target.lat, target.lon, target.lat, target.lon));
        returnToMap();
    }

    // ---------- LOCATION / ACTIVITY RESULTS ----------

    private void startResearch(Intent intent) {
        if (intent == null) intent = new Intent(this, ResearchActivity.class);
        startActivityForResult(intent, REQ_RESEARCH);
    }

    private void forwardResearchResultToMap(Intent researchResult) {
        Intent map = new Intent(this, MainActivity.class);
        if (researchResult.getExtras() != null) map.putExtras(researchResult.getExtras());
        map.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(map);
        finish();
    }

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
        if (requestCode == REQ_RESEARCH) {
            if (resultCode == RESULT_OK && data != null) forwardResearchResultToMap(data);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == REQ_EXPORT) clearPendingExport();
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT) {
            handleImport(uri);
        } else if (requestCode == REQ_PHOTO) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {}
            pendingPhotoUri = uri.toString();
            toast("Photo attached. Tap Save to keep the field record.");
        } else if (requestCode == REQ_EXPORT) {
            performPendingExport(uri);
        }
    }

    @Override public void onLocation(Location location) {}
    @Override public void onLocationError(String message) { toast(message); }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_EXPORT_KIND, pendingExportKind);
        outState.putString(STATE_EXPORT_FORMAT, pendingExportFormat);
        outState.putLong(STATE_EXPORT_ID, pendingExportId);
        super.onSaveInstanceState(outState);
    }

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

    private View pageWithPinnedAction(View content, View action) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(0xfffafafa);
        ScrollView scrolling = new ScrollView(this);
        scrolling.setFillViewport(true);
        scrolling.addView(content);
        outer.addView(scrolling, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout pinned = new LinearLayout(this);
        pinned.setOrientation(LinearLayout.VERTICAL);
        pinned.setPadding(dp(14), dp(4), dp(14), dp(8));
        pinned.setBackgroundColor(0xfffafafa);
        pinned.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        outer.addView(pinned);
        outer.setOnApplyWindowInsetsListener((v, i) -> {
            v.setPadding(i.getSystemWindowInsetLeft(), i.getSystemWindowInsetTop(),
                    i.getSystemWindowInsetRight(), i.getSystemWindowInsetBottom());
            return i;
        });
        outer.requestApplyInsets();
        return outer;
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
