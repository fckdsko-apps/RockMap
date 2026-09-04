package com.rockmap.app.field;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.GuidedTourCoach;
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
    public static final String EXTRA_AREA_ID = "rockmap.field.area_id";
    public static final String EXTRA_SHOW_HELP_TOURS = "rockmap.field.show_help_tours";
    public static final String EXTRA_START_HELP_TOOL = "rockmap.field.start_help_tool";
    public static final String EXTRA_START_CONTEXTUAL_RESEARCH = "rockmap.field.start_contextual_research";
    private static final int REQ_LOCATION = 811;
    private static final int REQ_IMPORT = 812;
    private static final int REQ_PHOTO = 813;
    private static final int REQ_EXPORT = 814;
    private static final int REQ_RESEARCH = 815;
    private static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;

    // Versioned, app-private acknowledgment for the prominent first-use Track Recording disclosure.
    private static final String TRACK_DISCLOSURE_PREFS = "rockmap.track.disclosure";
    private static final String TRACK_DISCLOSURE_VERSION_KEY = "accepted_version";
    private static final int TRACK_DISCLOSURE_VERSION = 1;

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
        else if ("areas".equals(screen)) {
            long areaId = getIntent() == null ? -1L : getIntent().getLongExtra(EXTRA_AREA_ID, -1L);
            FieldDatabase.Area area = areaId > 0L ? db.getArea(areaId) : null;
            if (area != null) {
                showArea(area);
                if (getIntent() != null
                        && getIntent().getBooleanExtra(EXTRA_START_CONTEXTUAL_RESEARCH, false)) {
                    getIntent().removeExtra(EXTRA_START_CONTEXTUAL_RESEARCH);
                    getWindow().getDecorView().post(() -> startResearch(
                            new Intent(FieldActivity.this, ResearchActivity.class)
                                    .putExtra(ResearchActivity.EXTRA_AREA_ID, area.id)
                                    .putExtra(ResearchActivity.EXTRA_SHOW_HELP_ON_START, true)));
                }
            } else showProspectingAreas();
        }
        else if ("measure".equals(screen)) {
            FieldMapState.requestMeasurement(this);
            returnToMap();
        } else {
            showHub();
            if ("import".equals(screen)) getWindow().getDecorView().post(this::beginImport);
            String requestedToolTour = getIntent() == null ? null
                    : getIntent().getStringExtra(EXTRA_START_HELP_TOOL);
            if (requestedToolTour != null && !requestedToolTour.trim().isEmpty()) {
                getIntent().removeExtra(EXTRA_START_HELP_TOOL);
                final String tool = requestedToolTour.trim();
                getWindow().getDecorView().post(() -> startFieldTourByName(tool));
            } else if (getIntent() != null
                    && getIntent().getBooleanExtra(EXTRA_SHOW_HELP_TOURS, false)) {
                getIntent().removeExtra(EXTRA_SHOW_HELP_TOURS);
                getWindow().getDecorView().post(this::showFieldTourPicker);
            }
        }
    }

    private void showHub() {
        GuidedTourCoach.clear(this);
        setTitle("RockMap Field");
        LinearLayout root = page();
        root.addView(title("Field Tools"));
        root.addView(help("Field tools are map-first. Each ? opens a concise explainer and, when useful, a guided walkthrough for that specific tool."));

        root.addView(fieldToolRow(FieldUiNames.TRACK,
                "Record GPS tracks that draw live on the main map. Opening a saved track shows it on the basemap.",
                "Records a GPS breadcrumb track. Start, pause, resume, stop, reopen, and export tracks. Recording uses a visible Android foreground service and does not request background-location permission.",
                this::showTracks, true));
        root.addView(fieldToolRow(FieldUiNames.FIELD_RECORDS,
                "Richer saved observations with category, mineral, sample ID, notes, photo, GPS accuracy and elevation.",
                "Field Records are richer observations than simple Saved Locations. They can include category, mineral, sample ID, notes, photo, GPS accuracy, elevation, map actions, and location-based Research.",
                this::showFieldRecords, true));
        root.addView(fieldToolRow(FieldUiNames.SAVED_LOCATIONS,
                "View your existing RockMap Saved Locations or copy one into a richer Field Record.",
                "Saved Locations are lightweight points stored on the device. Open one to view it on the map, navigate to it, edit it, or copy it into a Field Record.",
                this::showLegacyWaypoints, true));
        root.addView(fieldToolRow(FieldUiNames.PROSPECTING_AREAS,
                "Create, open, analyze, map, and manage saved prospecting areas.",
                "Prospecting Areas are saved polygons. They can come from Measure or imports, stay visible on the map, and can be analyzed with Research without turning spatial correlation into a mineral prediction.",
                this::showProspectingAreas, true));
        root.addView(fieldToolRow(FieldUiNames.MEASURE,
                "Start a temporary map measurement. Save it as a Prospecting Area when you want to keep and analyze the polygon.",
                "Measure is a temporary map tool. Add or edit points on the map, finish or cancel explicitly, and save the polygon as a Prospecting Area when you want it to persist.",
                () -> { FieldMapState.requestMeasurement(this); returnToMap(); }, false));
        root.addView(fieldToolRow(FieldUiNames.IMPORT,
                "Import GPX, KML, or GeoJSON files into RockMap.",
                "Import accepts GPX, KML, and GeoJSON. Imported objects stay tied to their import batch so removing one import does not delete unrelated RockMap data.",
                this::beginImport, false));
        root.addView(fieldToolRow(FieldUiNames.IMPORTED_DATA,
                "Review imported files, show their contents on the map, or remove one import without affecting unrelated data.",
                "Imported Data manages the files RockMap has already imported. Open a batch to inspect its Saved Locations, Tracks, and Prospecting Areas, show them on the map, or remove only that import.",
                this::showImports, true));
        root.addView(fieldToolRow("Research",
                "Open Mineral Evidence, Geology, and Combined Area Analysis.",
                "Research connects field objects back to geology, Mineral Evidence, historic activity, and spatial analysis. Evidence and overlap are research context, not collecting permission or a prediction of what you will find.",
                () -> startResearch(new Intent(this, ResearchActivity.class)), false));
        root.addView(fieldToolRow(FieldUiNames.EXPORT,
                "Export Saved Locations, Tracks, Field Records, Prospecting Areas, imported files, or combined field data.",
                "Export Data lets you choose the object and format deliberately. Export does not delete or move the original RockMap record.",
                this::showExportHub, true));
        root.addView(fieldToolRow(FieldUiNames.COORDINATES,
                "Convert one location between decimal degrees, DDM, DMS, WGS84 UTM and MGRS.",
                "Coordinates converts the same location between common coordinate formats. It is a conversion/reference tool, not a replacement for checking current GPS accuracy.",
                this::showCoordinates, true));
        root.addView(action("Back to map",
                "Return to the main RockMap map and its visual Field controls.",
                v -> returnToMap()));
        setContentView(scroll(root));
    }

    private View fieldToolRow(String tool, String detail, String explainer,
                              Runnable openAction, boolean staysInField) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        View action = action(tool, detail, v -> openAction.run());
        action.setTag(fieldToolTag(tool));
        row.addView(action, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View helpButton = compactHelpButton("Help for " + tool,
                v -> showFieldToolHelp(tool, explainer, openAction, staysInField));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(40));
        hp.setMargins(dp(6), 0, 0, 0);
        row.addView(helpButton, hp);
        return row;
    }

    private void showFieldToolHelp(String tool, String explainer,
                                   Runnable openAction, boolean staysInField) {
        new AlertDialog.Builder(this)
                .setTitle(tool + " help")
                .setMessage(explainer)
                .setPositiveButton("Close", null)
                .setNeutralButton("Start guided tour", (d, w) ->
                        startFieldToolTour(tool, explainer, openAction, staysInField))
                .show();
    }

    private void showFieldTourPicker() {
        final String[] tools = new String[]{
                FieldUiNames.TRACK, FieldUiNames.NAVIGATE, FieldUiNames.MEASURE,
                FieldUiNames.FIELD_RECORDS, FieldUiNames.PROSPECTING_AREAS,
                FieldUiNames.IMPORT, FieldUiNames.IMPORTED_DATA,
                FieldUiNames.EXPORT, FieldUiNames.COORDINATES,
                FieldUiNames.SAVED_LOCATIONS, "Research"
        };
        new AlertDialog.Builder(this)
                .setTitle("Field Tools guided tours")
                .setItems(tools, (d, which) -> startFieldTourByName(tools[which]))
                .setNegativeButton("Close", null)
                .show();
    }

    private boolean usesTrainingArea(String tool) {
        return FieldUiNames.MEASURE.equals(tool)
                || FieldUiNames.PROSPECTING_AREAS.equals(tool)
                || FieldUiNames.FIELD_RECORDS.equals(tool);
    }

    private void confirmTrainingAreaAndReturnToMap(String tool) {
        new AlertDialog.Builder(this)
                .setTitle("Use a training area?")
                .setMessage("This guided tour uses Saint Peters Dome as an example so later Research steps have enough mapped mineral evidence to demonstrate the tools properly.\n\nRockMap will move the map there before the numbered tour begins. This changes only the map view; it does not change your GPS location. The example Prospecting Area is intentionally broad because source records can represent approximate localities and general vicinities.")
                .setPositiveButton("Continue", (d, w) -> {
                    GuidedTourCoach.clear(FieldActivity.this);
                    Intent intent = new Intent(FieldActivity.this, MainActivity.class);
                    intent.putExtra(MainActivity.EXTRA_START_TRAINING_FIELD_TOUR, tool);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startFieldTourByName(String tool) {
        if (tool == null || tool.trim().isEmpty()) return;
        if (FieldUiNames.SAVED_LOCATIONS.equals(tool)) {
            startLegacyTwoStepTour(tool,
                    "Saved Locations are quick saved points. Open one to map it, navigate to it, edit it, or copy it into a richer Field Record.",
                    this::showLegacyWaypoints, true);
            return;
        }
        if ("Research".equals(tool)) {
            startLegacyTwoStepTour(tool,
                    "Research connects field locations and areas to geology, Mineral Evidence, historic activity, and spatial analysis.",
                    () -> startResearch(new Intent(this, ResearchActivity.class)), false);
            return;
        }
        if (usesTrainingArea(tool)) {
            confirmTrainingAreaAndReturnToMap(tool);
            return;
        }
        FieldTourState.start(this, tool);
        GuidedTourCoach.clear(this);
        returnToMap();
    }

    private void startFieldToolTour(String tool, String explainer,
                                    Runnable openAction, boolean staysInField) {
        // All actual Field Tools now enter the same map-first interactive tour engine used by the
        // main Field menu. Saved Locations and Research retain their small local walkthroughs.
        if (!FieldUiNames.SAVED_LOCATIONS.equals(tool) && !"Research".equals(tool)) {
            if (usesTrainingArea(tool)) {
                confirmTrainingAreaAndReturnToMap(tool);
                return;
            }
            FieldTourState.start(this, tool);
            GuidedTourCoach.clear(this);
            returnToMap();
            return;
        }
        startLegacyTwoStepTour(tool, explainer, openAction, staysInField);
    }

    private void startLegacyTwoStepTour(String tool, String explainer,
                                        Runnable openAction, boolean staysInField) {
        showHub();
        getWindow().getDecorView().post(() -> {
            View target = findViewById(android.R.id.content).findViewWithTag(fieldToolTag(tool));
            if (target == null) return;
            final boolean[] tourActive = new boolean[]{true};
            target.setOnClickListener(v -> {
                GuidedTourCoach.clear(FieldActivity.this);
                openAction.run();
                if (tourActive[0] && staysInField) {
                    getWindow().getDecorView().postDelayed(() ->
                            showLegacyToolScreenTour(tool, explainer, openAction, staysInField), 260L);
                }
            });
            GuidedTourCoach.show(this, 1, staysInField ? 2 : 1,
                    tool,
                    explainer,
                    "Tap “" + tool + "”.", target,
                    null, null, null,
                    () -> {
                        GuidedTourCoach.clear(FieldActivity.this);
                        openAction.run();
                        if (staysInField) getWindow().getDecorView().postDelayed(() ->
                                showLegacyToolScreenTour(tool, explainer, openAction, staysInField), 260L);
                    },
                    () -> {
                        tourActive[0] = false;
                        GuidedTourCoach.clear(FieldActivity.this);
                    });
        });
    }

    private void showLegacyToolScreenTour(String tool, String explainer,
                                          Runnable openAction, boolean staysInField) {
        View target = findFirstFeatureAction(findViewById(android.R.id.content));
        if (FieldUiNames.SAVED_LOCATIONS.equals(tool) && target == null) {
            GuidedTourCoach.show(this, 2, 2, tool,
                    "Saved Locations are lightweight map points stored on this device. Once you save one, you can reopen it for map, navigation, editing, and Field Record actions.\n\nThere are no Saved Locations to demonstrate yet.",
                    "Finish when you are ready.", null,
                    () -> startLegacyTwoStepTour(tool, explainer, openAction, staysInField),
                    "Finish", () -> GuidedTourCoach.clear(FieldActivity.this),
                    () -> GuidedTourCoach.clear(FieldActivity.this),
                    () -> GuidedTourCoach.clear(FieldActivity.this));
            return;
        }
        GuidedTourCoach.show(this, 2, 2, tool, explainer,
                target == null ? "Review the available actions." : "Try the highlighted control.", target,
                () -> startLegacyTwoStepTour(tool, explainer, openAction, staysInField),
                "Finish", () -> GuidedTourCoach.clear(FieldActivity.this),
                () -> GuidedTourCoach.clear(FieldActivity.this),
                () -> GuidedTourCoach.clear(FieldActivity.this));
    }

    private int fieldTourTotal(String tool) {
        if (FieldUiNames.TRACK.equals(tool)) return 17;
        if (FieldUiNames.NAVIGATE.equals(tool)) return 9;
        if (FieldUiNames.MEASURE.equals(tool)) return 17;
        if (FieldUiNames.FIELD_RECORDS.equals(tool)) return 15;
        if (FieldUiNames.PROSPECTING_AREAS.equals(tool)) return 19;
        if (FieldUiNames.IMPORT.equals(tool)) return 3;
        if (FieldUiNames.IMPORTED_DATA.equals(tool)) return 8;
        if (FieldUiNames.EXPORT.equals(tool)) return 3;
        if (FieldUiNames.COORDINATES.equals(tool)) return 5;
        return 2;
    }

    private void finishFieldTour() {
        FieldTourState.finish(this);
        GuidedTourCoach.clear(this);
    }

    private void exitFieldTour() {
        finishFieldTour();
    }

    private void showFieldCoach(int step, String tool, String title, String body, String action,
                                View target, Runnable back, String primaryLabel,
                                Runnable primary, Runnable skip) {
        GuidedTourCoach.show(this, step, fieldTourTotal(tool), title, body, action, target,
                back, primaryLabel, primary, skip, this::exitFieldTour);
    }

    private FrameLayout dialogTourRoot(AlertDialog dialog) {
        return GuidedTourCoach.prepareDialogHost(this, dialog);
    }

    private void showDialogCoach(AlertDialog dialog, int step, String tool, String title,
                                 String body, String action, View target, Runnable back,
                                 String primaryLabel, Runnable primary, Runnable skip) {
        FrameLayout host = dialogTourRoot(dialog);
        if (host == null) return;
        GuidedTourCoach.show(this, host, step, fieldTourTotal(tool), title, body, action, target,
                back, primaryLabel, primary, skip, () -> {
                    finishFieldTour();
                    if (dialog.isShowing()) dialog.dismiss();
                });
    }

    private void tagClickable(View root, String tag) {
        if (root == null || tag == null) return;
        View target = firstClickableDescendant(root);
        (target == null ? root : target).setTag(tag);
    }

    private View firstClickableDescendant(View root) {
        if (root == null) return null;
        if (root.isClickable()) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstClickableDescendant(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findFirstFeatureAction(View root) {
        if (root == null) return null;
        if (root.isClickable() && root.getVisibility() == View.VISIBLE) {
            CharSequence description = root.getContentDescription();
            String text = root instanceof TextView && ((TextView) root).getText() != null
                    ? ((TextView) root).getText().toString().trim() : "";
            String combined = (description == null ? "" : description.toString()) + " " + text;
            if (!combined.toLowerCase(Locale.US).contains("back")) return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFirstFeatureAction(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private String fieldToolTag(String tool) {
        return "rockmap-field-tool-" + (tool == null ? "tool" : tool.toLowerCase(Locale.US)
                .replace(' ', '-').replace('&', '-').replace('/', '-'));
    }

    // ---------- TRACKS ----------

    private void showTracks() {
        LinearLayout root = page();
        root.addView(title("Tracks"));
        root.addView(help("Tracks are geographic objects. Tap any track below to open it on the basemap, zoomed to its recorded extent with START/END context."));

        FieldDatabase.Track active = db.getActiveTrack();
        if (active == null) {
            root.addView(help("Track recording uses the GPS provider and an Android foreground service. RockMap does not request background-location permission."));
            View startTrack = action("Start new track", "Begins recording after a precise-location check.", v -> {
                if (FieldTourState.is(this, FieldUiNames.TRACK, 2)) GuidedTourCoach.clear(this);
                startNewTrack();
            });
            tagClickable(startTrack, "rockmap-track-start-new");
            root.addView(startTrack);
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
                View trackAction = action(track.name,
                        trackStatus(track, pts) + " · " + visibility + "\nTap to open the map view.",
                        v -> {
                            if (FieldTourState.is(this, FieldUiNames.TRACK, 12)
                                    && FieldTourState.entityId(this) == track.id) {
                                FieldTourState.step(this, 13);
                            }
                            showTrackOnMap(track.id);
                        });
                tagClickable(trackAction, "rockmap-track-row:" + track.id);
                root.addView(trackAction);
            }
        }
        Button backToMap = back();
        backToMap.setTag("rockmap-tracks-back-to-map");
        root.addView(backToMap);
        setContentView(scroll(root));
        getWindow().getDecorView().post(this::showTracksTourCoach);
    }

    private void startNewTrack() {
        int acceptedVersion = getSharedPreferences(TRACK_DISCLOSURE_PREFS, MODE_PRIVATE)
                .getInt(TRACK_DISCLOSURE_VERSION_KEY, 0);
        if (acceptedVersion >= TRACK_DISCLOSURE_VERSION) {
            continueStartNewTrack();
            return;
        }

        AlertDialog disclosure = new AlertDialog.Builder(this)
                .setTitle("Track Recording and precise location")
                .setMessage("RockMap uses precise location to record your GPS track. While Track Recording is active, location continues to be accessed when RockMap is in the background until you stop recording. Track points are stored locally on your device and are not sent to RockMap servers.")
                .setPositiveButton("Continue", (d, w) -> {
                    getSharedPreferences(TRACK_DISCLOSURE_PREFS, MODE_PRIVATE)
                            .edit()
                            .putInt(TRACK_DISCLOSURE_VERSION_KEY, TRACK_DISCLOSURE_VERSION)
                            .apply();
                    continueStartNewTrack();
                })
                .setNegativeButton("Cancel", (d, w) -> restoreTrackTourAfterDisclosureCancel())
                .create();
        disclosure.setOnCancelListener(d -> restoreTrackTourAfterDisclosureCancel());
        disclosure.show();
    }

    private void restoreTrackTourAfterDisclosureCancel() {
        if (FieldTourState.is(this, FieldUiNames.TRACK, 2)) {
            getWindow().getDecorView().post(this::showTracksTourCoach);
        }
    }

    private void continueStartNewTrack() {
        runWithPreciseLocation(() -> {
            if (FieldTourState.is(this, FieldUiNames.TRACK, 2)) {
                FieldTourState.step(this, 3);
            }
            EditText input = new EditText(this);
            input.setHint("Track name");
            input.setText("Field track — " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date()));
            input.setSingleLine(true);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Start track recording")
                    .setMessage("Recording can continue while you use the RockMap map or lock the screen. Android keeps a foreground-service indicator active until you stop the track.")
                    .setView(input)
                    .setPositiveButton("Start", null)
                    .setNegativeButton("Cancel", null)
                    .create();
            dialog.setOnShowListener(ignored -> {
                Button startButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                startButton.setOnClickListener(v -> {
                    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        toast("Precise location is required to start track recording.");
                        return;
                    }
                    long id = db.createTrack(input.getText().toString().trim(), System.currentTimeMillis());
                    if (FieldTourState.is(this, FieldUiNames.TRACK)) {
                        FieldTourState.entityId(this, id);
                        FieldTourState.step(this, 5);
                    }
                    Intent service = new Intent(this, TrackRecordingService.class)
                            .setAction(TrackRecordingService.ACTION_START)
                            .putExtra(TrackRecordingService.EXTRA_TRACK_ID, id);
                    FieldMapState.showTrack(this, id);
                    FieldMapState.requestTrackFocus(this, id);
                    FieldMapState.clearViewedMapContext(this);
                    FieldMapState.setExpandedTool(this, FieldMapState.TOOL_TRACK);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
                    else startService(service);
                    dialog.setOnDismissListener(null);
                    dialog.dismiss();
                    GuidedTourCoach.clear(this);
                    returnToMap();
                });
                if (FieldTourState.is(this, FieldUiNames.TRACK, 3)) {
                    showDialogCoach(dialog, 3, FieldUiNames.TRACK, "Name the track",
                            "Give the recording a useful name. RockMap provides a default name if you do not change it.",
                            "Enter or review the track name.", input,
                            () -> {
                                dialog.setOnDismissListener(null);
                                dialog.dismiss();
                                FieldTourState.step(this, 2);
                                showTracks();
                            },
                            "Continue", () -> {
                                FieldTourState.step(this, 4);
                                showTrackStartButtonTour(dialog, input, startButton);
                            },
                            () -> {
                                FieldTourState.step(this, 4);
                                showTrackStartButtonTour(dialog, input, startButton);
                            });
                } else if (FieldTourState.is(this, FieldUiNames.TRACK, 4)) {
                    showTrackStartButtonTour(dialog, input, startButton);
                }
            });
            dialog.setOnDismissListener(d -> {
                if (FieldTourState.is(this, FieldUiNames.TRACK, 3)
                        || FieldTourState.is(this, FieldUiNames.TRACK, 4)) {
                    GuidedTourCoach.clear(this);
                    FieldTourState.step(this, 2);
                    getWindow().getDecorView().post(this::showTracks);
                }
            });
            dialog.show();
        });
    }

    private void showTrackStartButtonTour(AlertDialog dialog, EditText input, Button startButton) {
        showDialogCoach(dialog, 4, FieldUiNames.TRACK, "Start recording",
                "Start begins GPS recording and saves the new track. Recording can continue while you use other RockMap tools or collapse the Track panel.",
                "Tap “Start”.", startButton,
                () -> {
                    FieldTourState.step(this, 3);
                    showDialogCoach(dialog, 3, FieldUiNames.TRACK, "Name the track",
                            "Give the recording a useful name. RockMap provides a default name if you do not change it.",
                            "Enter or review the track name.", input,
                            () -> {
                                dialog.setOnDismissListener(null);
                                dialog.dismiss();
                                FieldTourState.step(this, 2);
                                showTracks();
                            },
                            "Continue", () -> {
                                FieldTourState.step(this, 4);
                                showTrackStartButtonTour(dialog, input, startButton);
                            },
                            () -> {
                                FieldTourState.step(this, 4);
                                showTrackStartButtonTour(dialog, input, startButton);
                            });
                },
                null, null,
                startButton::performClick);
    }

    private void showTracksTourCoach() {
        if (!FieldTourState.is(this, FieldUiNames.TRACK)) return;
        int step = FieldTourState.step(this);
        if (step == 2) {
            View target = findViewById(android.R.id.content).findViewWithTag("rockmap-track-start-new");
            if (target == null) {
                FieldDatabase.Track active = db.getActiveTrack();
                if (active != null) {
                    FieldTourState.entityId(this, active.id);
                    FieldTourState.step(this, 5);
                    FieldMapState.showTrack(this, active.id);
                    FieldMapState.clearViewedMapContext(this);
                    FieldMapState.setExpandedTool(this, FieldMapState.TOOL_TRACK);
                    returnToMap();
                }
                return;
            }
            showFieldCoach(2, FieldUiNames.TRACK, "Start a track",
                    "Tracks record your movement as a GPS line that you can view, reopen, navigate, and export later.",
                    "Tap “Start new track”.", target,
                    () -> {
                        FieldTourState.step(this, 1);
                        returnToMap();
                    }, null, null,
                    target::performClick);
            return;
        }
        if (step == 10) {
            View target = findViewById(android.R.id.content).findViewWithTag("rockmap-tracks-back-to-map");
            if (target == null) {
                FieldTourState.step(this, 11);
                returnToMap();
                return;
            }
            target.setOnClickListener(v -> {
                FieldTourState.step(FieldActivity.this, 11);
                GuidedTourCoach.clear(FieldActivity.this);
                returnToMap();
            });
            showFieldCoach(10, FieldUiNames.TRACK, "Tracks while recording",
                    "The active recording stays listed here with earlier tracks. Recording continues while you review this screen.",
                    "Tap “Back to map” to continue.", target,
                    () -> {
                        FieldTourState.step(this, 9);
                        returnToMap();
                    }, null, null, target::performClick);
            return;
        }
        if (step == 12) {
            long id = FieldTourState.entityId(this);
            FieldDatabase.Track track = id > 0L ? db.getTrack(id) : null;
            if (track == null) {
                FieldTourState.step(this, 2);
                FieldTourState.entityId(this, -1L);
                showTracks();
                return;
            }
            View target = findViewById(android.R.id.content).findViewWithTag("rockmap-track-row:" + track.id);
            if (target == null) {
                getWindow().getDecorView().postDelayed(this::showTracksTourCoach, 60L);
                return;
            }
            final FieldDatabase.Track selected = track;
            showFieldCoach(12, FieldUiNames.TRACK, "Reopen the saved track",
                    "Completed tracks stay under Recent tracks. Opening one returns to its recorded line and map controls.",
                    "Tap “" + selected.name + "”.", target,
                    () -> {
                        FieldTourState.step(this, 2);
                        FieldTourState.entityId(this, -1L);
                        showTracks();
                    }, null, null,
                    () -> {
                        FieldTourState.step(this, 13);
                        showTrackOnMap(selected.id);
                    });
        }
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
        boolean tourNeedsSavedTrackHud = FieldTourState.is(this, FieldUiNames.TRACK)
                && FieldTourState.step(this) >= 13 && FieldTourState.step(this) <= 17
                && FieldTourState.entityId(this) == trackId;
        if (points.size() < 2 && !tourNeedsSavedTrackHud) {
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
        Button newGps = small("New at GPS", v -> {
            if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 2)) {
                toast("This guided example uses Saint Peters Dome coordinates so the later Research steps stay in the training area. New at GPS remains available outside the tour.");
                getWindow().getDecorView().post(this::showFieldRecordsTourCoach);
                return;
            }
            newFieldAtGps();
        });
        Button newCoords = small("New at coordinates", v -> {
            if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 2)) FieldTourState.step(this, 3);
            newFieldAtCoordinates();
        });
        newGps.setTag("rockmap-field-record-new-gps");
        newCoords.setTag("rockmap-field-record-new-coordinates");
        add.addView(newGps, weight());
        add.addView(newCoords, weight());
        add.setTag("rockmap-field-record-create-choice");
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
                View recordAction = action(r.name, detail, v -> showFieldRecord(r.id));
                tagClickable(recordAction, "rockmap-field-record-row:" + r.id);
                root.addView(recordAction);
            }
        }
        root.addView(back());
        setContentView(scroll(root));
        getWindow().getDecorView().post(this::showFieldRecordsTourCoach);
    }

    private void newFieldAtGps() {
        runWithPreciseLocation(() -> locationRepository.requestFreshPrecise(
                l -> {
                    if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 2)) {
                        FieldTourState.step(this, 4);
                    }
                    editFieldRecord(new FieldDatabase.FieldRecord(
                            0, "", "", "", "", "",
                            l.getLatitude(), l.getLongitude(),
                            l.hasAltitude() ? l.getAltitude() : Double.NaN,
                            l.hasAccuracy() ? l.getAccuracy() : -1f,
                            "", System.currentTimeMillis(), System.currentTimeMillis()));
                },
                message -> {
                    toast(message);
                    if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 2)) {
                        getWindow().getDecorView().post(this::showFieldRecords);
                    }
                }));
    }

    private void newFieldAtCoordinates() {
        EditText input = new EditText(this);
        input.setHint("Latitude, longitude");
        input.setSingleLine(true);
        if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 3)) {
            input.setText(com.rockmap.app.TourTrainingArea.COORDINATES);
            input.setSelection(input.getText().length());
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("New field record at coordinates")
                .setView(input)
                .setPositiveButton("Continue", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(x -> {
            Button cont = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            cont.setOnClickListener(v -> {
                try {
                    CoordinateParser.Result r = CoordinateParser.parse(input.getText().toString());
                    dialog.setOnDismissListener(null);
                    dialog.dismiss();
                    if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 3)) {
                        FieldTourState.text(this, "");
                        FieldTourState.step(this, 4);
                    }
                    editFieldRecord(new FieldDatabase.FieldRecord(
                            0, "", "", "", "", "", r.latitude, r.longitude,
                            Double.NaN, -1f, "", System.currentTimeMillis(), System.currentTimeMillis()));
                } catch (IllegalArgumentException ex) {
                    input.setError(ex.getMessage());
                }
            });
            if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 3)) {
                showFieldRecordCoordinateTour(dialog, input, cont);
            }
        });
        dialog.setOnDismissListener(d -> {
            if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 3)) {
                GuidedTourCoach.clear(this);
                FieldTourState.text(this, "");
                FieldTourState.step(this, 2);
                getWindow().getDecorView().post(this::showFieldRecords);
            }
        });
        dialog.show();
    }

    private void showFieldRecordCoordinateTour(AlertDialog dialog, EditText input, Button cont) {
        if (dialog == null || input == null || cont == null
                || !FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 3)) return;
        boolean readyForContinue = "field-coordinate-continue".equals(FieldTourState.text(this));
        if (!readyForContinue) {
            showDialogCoach(dialog, 3, FieldUiNames.FIELD_RECORDS, "Use the training location",
                    "This guided example uses Saint Peters Dome instead of your real GPS position so the later Research steps stay in a known evidence-rich vicinity. The training coordinates are filled in for you.",
                    "Review the coordinates, then Continue.", input,
                    () -> {
                        dialog.setOnDismissListener(null);
                        dialog.dismiss();
                        FieldTourState.text(this, "");
                        FieldTourState.step(this, 2);
                        showFieldRecords();
                    }, "Continue", () -> {
                        FieldTourState.text(this, "field-coordinate-continue");
                        showFieldRecordCoordinateTour(dialog, input, cont);
                    }, () -> {
                        // Skip still uses the deterministic training coordinates; it must never
                        // substitute the phone's real GPS position into this guided example.
                        cont.performClick();
                    });
        } else {
            showDialogCoach(dialog, 3, FieldUiNames.FIELD_RECORDS, "Use these coordinates",
                    "Continue creates the new Field Record at the coordinates you entered.",
                    "Tap “Continue”.", cont,
                    () -> {
                        FieldTourState.text(this, "");
                        showFieldRecordCoordinateTour(dialog, input, cont);
                    }, null, null,
                    () -> {
                        if (input.getText().toString().trim().isEmpty()) {
                            input.setError("Enter coordinates before continuing.");
                            FieldTourState.text(this, "");
                            showFieldRecordCoordinateTour(dialog, input, cont);
                        } else {
                            cont.performClick();
                        }
                    });
        }
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
        dialog.setOnShowListener(x -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
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
                if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS)) {
                    FieldTourState.entityId(this, record.id);
                    FieldTourState.step(this, 12);
                }
                dialog.setOnDismissListener(null);
                dialog.dismiss();
                GuidedTourCoach.clear(this);
                showFieldRecord(record.id);
            });
            if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS)
                    && FieldTourState.step(this) >= 4 && FieldTourState.step(this) <= 11) {
                showFieldRecordEditorTour(dialog, record, name, category, mineral, sample, notes, coords, photo, save);
            }
        });
        dialog.setOnDismissListener(d -> {
            int step = FieldTourState.step(this);
            if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS) && step >= 4 && step <= 11) {
                GuidedTourCoach.clear(this);
                FieldTourState.step(this, 2);
                getWindow().getDecorView().post(this::showFieldRecords);
            }
        });
        dialog.show();
    }

    private void showFieldRecordEditorTour(AlertDialog dialog, FieldDatabase.FieldRecord record,
                                           EditText name, EditText category, EditText mineral,
                                           EditText sample, EditText notes, TextView coords,
                                           Button photo, Button save) {
        int step = FieldTourState.step(this);
        View target;
        String title;
        String body;
        String action;
        String primary = "Continue";
        Runnable next;
        if (step == 4) {
            target = name; title = "Name";
            body = "Name is the required label for this Field Record. Use something you will recognize later in the field list and on the map.";
            action = "Enter a name for the observation.";
            next = () -> { FieldTourState.step(this, 5); showFieldRecordEditorTour(dialog, record, name, category, mineral, sample, notes, coords, photo, save); };
        } else if (step == 5) {
            target = category; title = "Category";
            body = "Category describes the kind of observation, such as an outcrop, float, mine, dump, or vein.";
            action = "Add a category if it is useful for this observation.";
            next = () -> { FieldTourState.step(this, 6); showFieldRecordEditorTour(dialog, record, name, category, mineral, sample, notes, coords, photo, save); };
        } else if (step == 6) {
            target = mineral; title = "Mineral / material";
            body = "Record the mineral, rock, or material you observed. This is your field observation, separate from RockMap's mapped Mineral Evidence datasets.";
            action = "Add a mineral or material if known.";
            next = () -> { FieldTourState.step(this, 7); showFieldRecordEditorTour(dialog, record, name, category, mineral, sample, notes, coords, photo, save); };
        } else if (step == 7) {
            target = sample; title = "Sample ID";
            body = "Use Sample ID when you want this record to correspond to a physical sample, bag, specimen, or your own catalog number.";
            action = "Add a sample ID if you use one.";
            next = () -> { FieldTourState.step(this, 8); showFieldRecordEditorTour(dialog, record, name, category, mineral, sample, notes, coords, photo, save); };
        } else if (step == 8) {
            target = notes; title = "Notes";
            body = "Notes are for the details you want to remember later: texture, alteration, structure, access observations, sample context, or anything else useful in the field.";
            action = "Add any notes you want to keep.";
            next = () -> { FieldTourState.step(this, 9); showFieldRecordEditorTour(dialog, record, name, category, mineral, sample, notes, coords, photo, save); };
        } else if (step == 9) {
            target = coords; title = "Location details";
            body = "The record keeps its coordinates and, when available, GPS accuracy and elevation so you can judge the quality and context of the saved location.";
            action = "Review the saved location details.";
            next = () -> { FieldTourState.step(this, 10); showFieldRecordEditorTour(dialog, record, name, category, mineral, sample, notes, coords, photo, save); };
        } else if (step == 10) {
            target = photo; title = "Photo";
            body = "Attach a photo when a visual reference will help. The tour does not require you to choose a photo.";
            action = "Use “Attach photo” if you want one, or continue.";
            next = () -> { FieldTourState.step(this, 11); showFieldRecordEditorTour(dialog, record, name, category, mineral, sample, notes, coords, photo, save); };
        } else {
            target = save; title = "Save the Field Record";
            body = "Save keeps the observation on this device and opens the actions you can use with the saved location.";
            action = "Tap “Save”.";
            primary = null;
            next = null;
        }
        Runnable back = () -> {
            if (step <= 4) {
                dialog.setOnDismissListener(null);
                dialog.dismiss();
                FieldTourState.step(this, 2);
                showFieldRecords();
            } else {
                FieldTourState.step(this, step - 1);
                showFieldRecordEditorTour(dialog, record, name, category, mineral, sample, notes, coords, photo, save);
            }
        };
        Runnable skip;
        if (step >= 11) {
            skip = () -> {
                if (name.getText().toString().trim().isEmpty()) name.setText("Field Record");
                save.performClick();
            };
        } else {
            skip = next;
        }
        showDialogCoach(dialog, step, FieldUiNames.FIELD_RECORDS, title, body, action, target,
                back, primary, next, skip);
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
        View showMapAction = action("Show on Map",
                "Center this Field Record on the main map without starting navigation.",
                v -> {
                    if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 12)) {
                        FieldTourState.step(this, 13);
                        showSavedFieldRecordTourCoach(r);
                        return;
                    }
                    FieldMapState.clearViewedMapContext(this);
                    FieldMapState.requestFocusBounds(this, new FieldMapState.Bounds(r.lat, r.lon, r.lat, r.lon));
                    returnToMap();
                });
        tagClickable(showMapAction, "rockmap-field-record-show-map");
        root.addView(showMapAction);
        View researchAction = action("Research this location",
                "Choose a radius, then inspect mapped geology and continue into Mineral Evidence or Historic Mines & Workings.",
                v -> {
                    if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 13)) {
                        FieldTourState.step(this, 14);
                        showSavedFieldRecordTourCoach(r);
                        return;
                    }
                    startResearch(new Intent(this, ResearchActivity.class)
                        .putExtra(ResearchActivity.EXTRA_POINT_LAT, r.lat)
                        .putExtra(ResearchActivity.EXTRA_POINT_LON, r.lon)
                        .putExtra(ResearchActivity.EXTRA_POINT_LABEL, r.name)
                        .putExtra(ResearchActivity.EXTRA_SHOW_HELP_ON_START, true));
                });
        tagClickable(researchAction, "rockmap-field-record-research");
        root.addView(researchAction);
        View createAreaAction = action("Create Prospecting Area Around Here",
                "Choose a radius around this Field Record and save it as a Prospecting Area.",
                v -> {
                    final boolean tourStep = FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 14);
                    ProspectingAreaCreator.SaveCallback callback = (areaId, savedName) -> {
                        wireSavedResearchPromptToContextualResearch(areaId);
                        if (tourStep) {
                            FieldTourState.auxId(FieldActivity.this, areaId);
                            FieldTourState.step(FieldActivity.this, 15);
                            GuidedTourCoach.clear(FieldActivity.this);
                            getWindow().getDecorView().post(() -> showSavedFieldRecordTourCoach(r));
                        }
                    };
                    ProspectingAreaCreator.chooseRadiusAndSave(this, r.lat, r.lon, r.name,
                            "Created from Field Record: " + r.name, callback);
                });
        tagClickable(createAreaAction, "rockmap-field-record-create-area");
        View navigateAction = action("Navigate to this point",
                "Open the main map with a live target line, distance and bearing from GPS.",
                v -> showPointNavigation(r.name, new GeoMath.Point(r.lat, r.lon)));
        tagClickable(navigateAction, "rockmap-field-record-navigate");
        root.addView(navigateAction);

        LinearLayout row = row();
        Button editButton = small("Edit", v -> editFieldRecord(r));
        editButton.setTag("rockmap-field-record-edit");
        Button deleteButton = small("Delete", v -> new AlertDialog.Builder(this)
                .setTitle("Delete field record?")
                .setMessage(r.name + " will be removed from this device.")
                .setPositiveButton("Delete", (d, w) -> {
                    db.deleteFieldRecord(r.id);
                    showFieldRecords();
                })
                .setNegativeButton("Cancel", null)
                .show());
        deleteButton.setTag("rockmap-field-record-delete");
        row.addView(editButton, weight());
        row.addView(deleteButton, weight());
        root.addView(row);
        root.addView(nav("Back to Field Records", v -> showFieldRecords()));
        setContentView(pageWithPinnedAction(root, createAreaAction));
        getWindow().getDecorView().post(() -> showSavedFieldRecordTourCoach(r));
    }

    private void showFieldRecordsTourCoach() {
        if (!FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 2)) return;
        View target = findViewById(android.R.id.content).findViewWithTag("rockmap-field-record-new-coordinates");
        showFieldCoach(2, FieldUiNames.FIELD_RECORDS, "Create a Field Record",
                "Field Records can start from GPS or entered coordinates. For this guided example, use coordinates so the record stays at the Saint Peters Dome training area instead of jumping back to your real GPS location.",
                "Tap “New at coordinates”.", target,
                () -> {
                    FieldTourState.step(this, 1);
                    returnToMap();
                }, null, null,
                () -> {
                    View coordinates = findViewById(android.R.id.content)
                            .findViewWithTag("rockmap-field-record-new-coordinates");
                    if (coordinates != null) coordinates.performClick();
                });
    }

    private void createDefaultFieldRecordTourArea(FieldDatabase.FieldRecord record) {
        if (record == null || !FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 14)) return;
        List<GeoMath.Point> points = ProspectingAreaCreator.circlePoints(record.lat, record.lon, 1000d);
        if (points.size() < 3) {
            FieldTourState.step(this, 12);
            showFieldRecord(record.id);
            return;
        }
        ProspectingAreaCreator.saveNamedPolygonAndPrompt(this,
                record.name + " — 1 km",
                "Created from Field Record: " + record.name,
                points, false,
                (areaId, savedName) -> {
                    wireSavedResearchPromptToContextualResearch(areaId);
                    FieldTourState.auxId(FieldActivity.this, areaId);
                    FieldTourState.step(FieldActivity.this, 15);
                    GuidedTourCoach.clear(FieldActivity.this);
                    getWindow().getDecorView().post(() -> showSavedFieldRecordTourCoach(record));
                });
    }

    private void wireSavedResearchPromptToContextualResearch(long areaId) {
        wireSavedResearchPromptToContextualResearch(areaId, 0);
    }

    private void wireSavedResearchPromptToContextualResearch(long areaId, int attempt) {
        if (areaId <= 0L || attempt >= 20) return;
        View root = findViewById(android.R.id.content);
        View research = root == null ? null
                : root.findViewWithTag(ProspectingAreaCreator.SAVED_RESEARCH_BUTTON_TAG);
        if (research == null) {
            getWindow().getDecorView().postDelayed(
                    () -> wireSavedResearchPromptToContextualResearch(areaId, attempt + 1), 60L);
            return;
        }
        research.setOnClickListener(v -> {
            ProspectingAreaCreator.dismissSavedResearchPrompt(FieldActivity.this);
            startResearch(new Intent(FieldActivity.this, ResearchActivity.class)
                    .putExtra(ResearchActivity.EXTRA_AREA_ID, areaId)
                    .putExtra(ResearchActivity.EXTRA_SHOW_HELP_ON_START, true));
        });
    }

    private void showSavedFieldRecordTourCoach(FieldDatabase.FieldRecord record) {
        if (record == null || !FieldTourState.is(this, FieldUiNames.FIELD_RECORDS)) return;
        int step = FieldTourState.step(this);
        if (step < 12 || step > 15) return;
        View root = findViewById(android.R.id.content);
        View target;
        String title;
        String body;
        if (step == 12) {
            target = root.findViewWithTag("rockmap-field-record-show-map");
            title = "Show on Map";
            body = "Show on Map centers this saved observation without starting navigation.";
        } else if (step == 13) {
            target = root.findViewWithTag("rockmap-field-record-research");
            title = "Research this location";
            body = "Research this location opens location-based Research around the Field Record so you can inspect geology, Mineral Evidence, and historic activity nearby.";
        } else if (step == 14) {
            target = root.findViewWithTag("rockmap-field-record-create-area");
            title = "Create a Prospecting Area";
            body = "Create Prospecting Area Around Here builds a saved area around this point using a radius you choose.";
        } else {
            long areaId = FieldTourState.auxId(this);
            FieldDatabase.Area area = areaId > 0L ? db.getArea(areaId) : null;
            if (area == null) {
                FieldTourState.auxId(this, -1L);
                FieldTourState.step(this, 14);
                showSavedFieldRecordTourCoach(record);
                return;
            }
            target = root.findViewWithTag(ProspectingAreaCreator.SAVED_RESEARCH_BUTTON_TAG);
            if (target == null) {
                ProspectingAreaCreator.showSavedResearchPrompt(this, area.id, area.name);
                getWindow().getDecorView().postDelayed(() -> showSavedFieldRecordTourCoach(record), 60L);
                return;
            }
            title = "Research Area";
            body = "Research Area opens the Prospecting Area you just created in Research and keeps that exact area as the active analysis context.";
            final long selectedAreaId = areaId;
            if (target != null && selectedAreaId > 0L) {
                target.setOnClickListener(v -> {
                    finishFieldTour();
                    ProspectingAreaCreator.dismissSavedResearchPrompt(FieldActivity.this);
                    Intent research = new Intent(FieldActivity.this, ResearchActivity.class)
                            .putExtra(ResearchActivity.EXTRA_AREA_ID, selectedAreaId)
                            .putExtra(ResearchActivity.EXTRA_SHOW_HELP_ON_START, true);
                    startResearch(research);
                });
                View notNow = root.findViewWithTag(ProspectingAreaCreator.SAVED_NOT_NOW_BUTTON_TAG);
                if (notNow != null) {
                    notNow.setOnClickListener(v -> {
                        finishFieldTour();
                        ProspectingAreaCreator.dismissSavedResearchPrompt(FieldActivity.this);
                    });
                }
            }
        }

        if (target != null && step <= 14) {
            Rect visible = new Rect();
            if (!target.getGlobalVisibleRect(visible) || visible.width() <= 0 || visible.height() <= 0) {
                scrollTargetIntoView(target);
                getWindow().getDecorView().postDelayed(() -> showSavedFieldRecordTourCoach(record), 80L);
                return;
            }
        }

        Runnable back = step == 15 ? null : () -> {
            if (step == 12) {
                FieldTourState.step(this, 11);
                editFieldRecord(record);
            } else {
                FieldTourState.step(this, step - 1);
                showSavedFieldRecordTourCoach(record);
            }
        };

        if (step == 14) {
            showFieldCoach(step, FieldUiNames.FIELD_RECORDS, title, body,
                    "Tap “Create Prospecting Area Around Here”.", target, back,
                    null, null, () -> createDefaultFieldRecordTourArea(record));
        } else if (step == 15) {
            final View researchTarget = target;
            showFieldCoach(step, FieldUiNames.FIELD_RECORDS, title, body,
                    "Tap “Research Area”.", target, back,
                    null, null, () -> {
                        if (researchTarget != null) researchTarget.performClick();
                    });
        } else {
            showFieldCoach(step, FieldUiNames.FIELD_RECORDS, title, body,
                    "Review “" + title + "”, then Continue.", target, back,
                    "Continue", () -> {
                        FieldTourState.step(this, step + 1);
                        showSavedFieldRecordTourCoach(record);
                    }, () -> {
                        FieldTourState.step(this, step + 1);
                        showSavedFieldRecordTourCoach(record);
                    });
        }
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
        root.addView(help("Saved polygons live here. Create one on the map, then reopen it to view it on the map, run Research, review saved Research, or delete it. Export is available from Field > Export Data."));
        View createArea = action("Create Prospecting Area",
                "Draw the boundary on the map, then save it as a Prospecting Area.",
                v -> {
                    if (FieldTourState.is(this, FieldUiNames.PROSPECTING_AREAS, 2)) {
                        FieldTourState.step(this, 3);
                    }
                    FieldMapState.requestMeasurement(this);
                    returnToMap();
                });
        tagClickable(createArea, "rockmap-create-prospecting-area");
        root.addView(createArea);
        List<FieldDatabase.Area> areas = db.listAreas();
        root.addView(section("Saved Prospecting Areas"));
        if (areas.isEmpty()) {
            root.addView(help("No saved Prospecting Areas yet."));
        } else {
            for (FieldDatabase.Area a : areas) {
                View areaAction = action(a.name,
                        GeoMath.areaLabel(GeoMath.polygonAreaSquareMeters(a.points)) + " · " + a.points.size() + " vertices",
                        v -> showArea(a));
                tagClickable(areaAction, "rockmap-area-row:" + a.id);
                root.addView(areaAction);
            }
        }
        root.addView(nav("Back to Field", v -> showHub()));
        setContentView(scroll(root));
        getWindow().getDecorView().post(this::showProspectingAreasTourCoach);
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
                + "\n\nSaved Research is a snapshot you explicitly keep with this Prospecting Area. "
                + "Opening Research later can run a fresh analysis without overwriting these saved notes."));

        List<ProspectingAreaResearchStore.Snapshot> research =
                ProspectingAreaResearchStore.list(this, a.id);
        if (!research.isEmpty()) {
            ProspectingAreaResearchStore.Snapshot latest = research.get(0);
            View savedResearchCard = action("Saved Research (" + research.size() + ")",
                    latest.compactLabel() + " · "
                            + DateFormat.getDateTimeInstance().format(new Date(latest.savedAt)),
                    v -> showAreaResearch(a));
            savedResearchCard.setTag("rockmap-area-saved-research-card");
            root.addView(savedResearchCard);
        } else {
            root.addView(help("No Research snapshots are saved with this area yet. Use Save Research from the Research Area panel when you want to keep one."));
        }

        Button del = button("Delete Area");
        del.setTag("rockmap-area-delete");
        del.setOnClickListener(v -> {
            if (FieldTourState.is(this, FieldUiNames.PROSPECTING_AREAS, 19)) {
                finishFieldTour();
                return;
            }
            new AlertDialog.Builder(this)
                .setTitle("Delete Area?")
                .setMessage("Delete this Prospecting Area and its saved Research snapshots from this device?")
                .setPositiveButton("Delete", (d, w) -> {
                    db.deleteArea(a.id);
                    ProspectingAreaVisibility.forget(this, a.id);
                    ProspectingAreaResearchStore.forget(this, a.id);
                    showProspectingAreas();
                })
                .setNegativeButton("Cancel", null)
                .show();
        });
        root.addView(del);
        root.addView(nav("Back to Prospecting Areas", v -> showProspectingAreas()));

        LinearLayout primary = row();
        Button researchButton = small("Research", v -> {
            if (FieldTourState.is(this, FieldUiNames.PROSPECTING_AREAS, 16)) {
                FieldTourState.step(this, 17);
                showSavedAreaTourCoach(a);
                return;
            }
            startResearch(new Intent(this, ResearchActivity.class)
                    .putExtra(ResearchActivity.EXTRA_AREA_ID, a.id)
                    .putExtra(ResearchActivity.EXTRA_SHOW_HELP_ON_START, true));
        });
        researchButton.setTag("rockmap-area-research");
        Button showMapButton = small("Show on Map", v -> {
            if (FieldTourState.is(this, FieldUiNames.PROSPECTING_AREAS, 17)) {
                FieldTourState.step(this, 18);
                showSavedAreaTourCoach(a);
                return;
            }
            ProspectingAreaVisibility.showOnly(this, a.id);
            FieldMapState.setAreasVisible(this, true);
            FieldMapState.clearViewedMapContext(this);
            FieldMapState.Bounds bounds = FieldMapState.Bounds.fromPoints(a.points);
            if (bounds != null) FieldMapState.requestFocusBounds(this, bounds);
            returnToMap();
        });
        showMapButton.setTag("rockmap-area-show-map");
        Button savedResearchButton = small(research.isEmpty() ? "Saved Research" : "Research (" + research.size() + ")",
                v -> {
                    if (FieldTourState.is(this, FieldUiNames.PROSPECTING_AREAS, 18)) {
                        FieldTourState.step(this, 19);
                        showSavedAreaTourCoach(a);
                        return;
                    }
                    showAreaResearch(a);
                });
        savedResearchButton.setTag("rockmap-area-saved-research");
        primary.addView(researchButton, weight());
        primary.addView(showMapButton, weight());
        primary.addView(savedResearchButton, weight());
        setContentView(pageWithPinnedAction(root, primary));
        getWindow().getDecorView().post(() -> showSavedAreaTourCoach(a));
    }

    private void showProspectingAreasTourCoach() {
        if (!FieldTourState.is(this, FieldUiNames.PROSPECTING_AREAS, 2)) return;
        View target = findViewById(android.R.id.content).findViewWithTag("rockmap-create-prospecting-area");
        showFieldCoach(2, FieldUiNames.PROSPECTING_AREAS, "Create a Prospecting Area",
                "Prospecting Areas are saved polygons you can keep on the map and analyze with Research. Creating one opens Measure so you can define its boundary.",
                "Tap “Create Prospecting Area”.", target,
                () -> {
                    FieldTourState.step(this, 1);
                    returnToMap();
                }, null, null,
                target == null ? null : target::performClick);
    }

    private void showSavedAreaTourCoach(FieldDatabase.Area area) {
        if (area == null || !FieldTourState.is(this, FieldUiNames.PROSPECTING_AREAS)) return;
        int step = FieldTourState.step(this);
        if (step < 16 || step > 19) return;
        View root = findViewById(android.R.id.content);
        View target;
        String title;
        String body;
        if (step == 16) {
            target = root.findViewWithTag("rockmap-area-research");
            title = "Research";
            body = "Research opens this exact saved polygon as the active Research area so you can inspect mapped geology, Mineral Evidence, historic activity, and other area analysis. Spatial overlap is research context, not a mineral prediction or collecting permission.";
        } else if (step == 17) {
            target = root.findViewWithTag("rockmap-area-show-map");
            title = "Show on Map";
            body = "Show on Map makes this Prospecting Area visible and frames the polygon on the main map without starting Research.";
        } else if (step == 18) {
            target = root.findViewWithTag("rockmap-area-saved-research");
            title = "Saved Research";
            body = "Saved Research opens analysis snapshots you explicitly saved with this Prospecting Area. Running Research again can create a fresh analysis without replacing those saved snapshots.";
        } else {
            target = root.findViewWithTag("rockmap-area-delete");
            title = "Delete Area";
            body = "Delete Area permanently removes this Prospecting Area and its saved Research snapshots from this device.";
        }
        if (target == null || !target.isShown() || target.getWidth() <= 0 || target.getHeight() <= 0) {
            getWindow().getDecorView().postDelayed(() -> showSavedAreaTourCoach(area), 60L);
            return;
        }
        Rect visible = new Rect();
        if (!target.getGlobalVisibleRect(visible) || visible.width() <= 0 || visible.height() <= 0) {
            scrollTargetIntoView(target);
            getWindow().getDecorView().postDelayed(() -> showSavedAreaTourCoach(area), 80L);
            return;
        }
        Runnable back = step > 16 ? () -> {
            FieldTourState.step(this, step - 1);
            showSavedAreaTourCoach(area);
        } : null;
        String action = step == 19
                ? "Review “Delete Area”, then Finish."
                : "Review “" + title + "”, then Continue.";
        if (step == 19) {
            showFieldCoach(step, FieldUiNames.PROSPECTING_AREAS, title, body,
                    action, target, back,
                    "Finish", this::finishFieldTour, this::finishFieldTour);
        } else {
            showFieldCoach(step, FieldUiNames.PROSPECTING_AREAS, title, body,
                    action, target, back,
                    "Continue", () -> {
                        FieldTourState.step(this, step + 1);
                        showSavedAreaTourCoach(area);
                    }, () -> {
                        FieldTourState.step(this, step + 1);
                        showSavedAreaTourCoach(area);
                    });
        }
    }

    private void showAreaResearch(FieldDatabase.Area area) {
        if (area == null) { showProspectingAreas(); return; }
        List<ProspectingAreaResearchStore.Snapshot> research =
                ProspectingAreaResearchStore.list(this, area.id);
        LinearLayout root = page();
        root.addView(title("Saved Research — " + area.name));
        root.addView(help("These are Research snapshots you explicitly saved with this Prospecting Area. They are not silently refreshed or replaced by later analyses."));
        if (research.isEmpty()) {
            root.addView(help("No saved Research yet. Open Research for this area and use Save Research when you want to keep the current findings."));
        } else {
            for (ProspectingAreaResearchStore.Snapshot snapshot : research) {
                StringBuilder detail = new StringBuilder();
                detail.append(DateFormat.getDateTimeInstance().format(new Date(snapshot.savedAt)));
                if (snapshot.title != null && !snapshot.title.trim().isEmpty()) {
                    detail.append("\n").append(snapshot.title.trim());
                }
                if (snapshot.summary != null && !snapshot.summary.trim().isEmpty()) {
                    detail.append("\n\n").append(snapshot.summary.trim());
                }
                if (snapshot.source != null && !snapshot.source.trim().isEmpty()) {
                    detail.append("\n\nSource: ").append(snapshot.source.trim());
                    if (snapshot.version != null && !snapshot.version.trim().isEmpty()) {
                        detail.append(" · version ").append(snapshot.version.trim());
                    }
                }
                root.addView(savedResearchCard(snapshot.compactLabel(), detail.toString()));
            }
        }
        LinearLayout primary = row();
        primary.addView(small("Back", v -> showArea(area)), weight());
        primary.addView(small("Show on Map", v -> showSavedResearchOnMap(area, research)), weight());
        primary.addView(small("Close", v -> returnToMap()), weight());
        setContentView(pageWithPinnedAction(root, primary));
    }

    private void showSavedResearchOnMap(FieldDatabase.Area area,
                                        List<ProspectingAreaResearchStore.Snapshot> snapshots) {
        if (area == null) { returnToMap(); return; }
        if (snapshots == null || snapshots.isEmpty()) {
            showAreaOnMap(area, null);
            return;
        }
        if (snapshots.size() == 1) {
            showAreaOnMap(area, snapshots.get(0));
            return;
        }

        String[] labels = new String[snapshots.size()];
        for (int i = 0; i < snapshots.size(); i++) {
            ProspectingAreaResearchStore.Snapshot snapshot = snapshots.get(i);
            labels[i] = snapshot.compactLabel() + " — "
                    + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(snapshot.savedAt));
        }
        new AlertDialog.Builder(this)
                .setTitle("Show Saved Research on Map")
                .setItems(labels, (dialog, which) -> {
                    if (which >= 0 && which < snapshots.size()) {
                        showAreaOnMap(area, snapshots.get(which));
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAreaOnMap(FieldDatabase.Area area,
                               ProspectingAreaResearchStore.Snapshot snapshot) {
        ProspectingAreaVisibility.showOnly(this, area.id);
        FieldMapState.setAreasVisible(this, true);
        FieldMapState.clearViewedMapContext(this);

        FieldMapState.Bounds bounds = null;
        if (snapshot != null
                && Double.isFinite(snapshot.south) && Double.isFinite(snapshot.west)
                && Double.isFinite(snapshot.north) && Double.isFinite(snapshot.east)
                && snapshot.north > snapshot.south && snapshot.east > snapshot.west) {
            bounds = new FieldMapState.Bounds(snapshot.south, snapshot.west, snapshot.north, snapshot.east);
        }
        if (bounds == null) bounds = FieldMapState.Bounds.fromPoints(area.points);
        if (bounds != null) FieldMapState.requestFocusBounds(this, bounds);
        returnToMap();
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
                                + ".\n\nTo replace it with a fresh import, remove the existing import first. You can also intentionally import another copy.")
                        .setPositiveButton("Open Existing Import", (d, w) -> {
                            if (FieldTourState.is(this, FieldUiNames.IMPORT)) finishFieldTour();
                            showImportBatch(previous.id);
                        })
                        .setNeutralButton("Import Another Copy", (d, w) -> confirmImport(result, name, sha))
                        .setNegativeButton("Cancel", (d, w) -> {
                            if (FieldTourState.is(this, FieldUiNames.IMPORT, 2)) {
                                FieldTourState.step(this, 1);
                                GuidedTourCoach.clear(this);
                                getWindow().getDecorView().post(this::returnToMap);
                            }
                        })
                        .show();
                return;
            }
            confirmImport(result, name, sha);
        } catch (Exception ex) {
            toast("Import rejected: " + ex.getMessage());
            if (FieldTourState.is(this, FieldUiNames.IMPORT, 2)) {
                FieldTourState.step(this, 1);
                GuidedTourCoach.clear(this);
                getWindow().getDecorView().post(this::returnToMap);
            }
        }
    }

    private void confirmImport(FieldImport.Result result, String name, String sha) {
        String summary = "Found:\n"
                + result.waypoints.size() + " Saved Locations\n"
                + result.tracks.size() + " tracks\n"
                + result.areas.size() + " Prospecting Areas\n"
                + result.pointCount + " total geometry points\n\n"
                + "Point/waypoint geometry can become Saved Locations, line/track geometry can become Tracks, and polygon geometry can become Prospecting Areas. "
                + "RockMap will track this file as one removable import. Existing unrelated RockMap data will not be replaced.";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Import " + name + "?")
                .setMessage(summary)
                .setPositiveButton("Import", null)
                .setNegativeButton("Cancel", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button importButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            importButton.setOnClickListener(v -> {
                if (FieldTourState.is(this, FieldUiNames.IMPORT, 2)) FieldTourState.step(this, 3);
                dialog.setOnDismissListener(null);
                dialog.dismiss();
                GuidedTourCoach.clear(this);
                applyImport(result, name, sha);
            });
            if (FieldTourState.is(this, FieldUiNames.IMPORT, 2)) {
                showDialogCoach(dialog, 2, FieldUiNames.IMPORT, "Review the import",
                        "RockMap reads GPX, KML, and GeoJSON geometry before anything is added. Points may become Saved Locations, lines may become Tracks, and polygons may become Prospecting Areas. Review the counts before importing.",
                        "Tap “Import”.", importButton,
                        () -> {
                            dialog.setOnDismissListener(null);
                            dialog.dismiss();
                            FieldTourState.step(this, 1);
                            returnToMap();
                        }, null, null,
                        importButton::performClick);
            }
        });
        dialog.setOnDismissListener(d -> {
            if (FieldTourState.is(this, FieldUiNames.IMPORT, 2)) {
                GuidedTourCoach.clear(this);
                FieldTourState.step(this, 1);
                getWindow().getDecorView().post(this::returnToMap);
            }
        });
        dialog.show();
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
            if (FieldTourState.is(this, FieldUiNames.IMPORT)) {
                finishFieldTour();
                getWindow().getDecorView().post(this::showImports);
            }
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
                + ". This import can be shown, reviewed, or removed as one unit."));

        View showImportOnMap = action("Show Import on Map",
                "Zoom to all imported geometry. Tracks, Prospecting Areas, and Saved Locations are visible immediately.",
                v -> {
                    holder[0].dismiss();
                    focusImportBatch(batchId);
                });
        tagClickable(showImportOnMap, "rockmap-import-complete-show-map");
        box.addView(showImportOnMap);

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

        View manageThisImport = action("Manage This Import",
                "Open this import later or remove only the objects created by this file.",
                v -> {
                    holder[0].dismiss();
                    showImportBatch(batchId);
                });
        tagClickable(manageThisImport, "rockmap-import-complete-manage");
        box.addView(manageThisImport);

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
        holder[0].setOnDismissListener(d -> {
            if (FieldTourState.is(this, FieldUiNames.IMPORT)) {
                finishFieldTour();
            }
        });
        holder[0].show();
        if (FieldTourState.is(this, FieldUiNames.IMPORT, 3)) {
            FieldTourState.entityId(this, batchId);
            View target = box.findViewWithTag("rockmap-import-complete-show-map");
            showDialogCoach(holder[0], 3, FieldUiNames.IMPORT, "Import complete",
                    "The file is now grouped as one import batch. Depending on its geometry, you can review imported Saved Locations, Tracks, and Prospecting Areas, show the whole import on the map, manage it later, or remove only this import.",
                    "Review the available actions for this imported file.", target,
                    () -> {
                        FieldTourState.step(this, 2);
                        View reviewTarget = box.findViewWithTag("rockmap-import-complete-manage");
                        showDialogCoach(holder[0], 2, FieldUiNames.IMPORT, "What RockMap loaded",
                                "Imported point/waypoint geometry becomes Saved Locations, line/track geometry becomes Tracks, and polygon geometry becomes Prospecting Areas. The exact buttons shown here depend on what this file contained.",
                                "Review the imported categories.", reviewTarget,
                                null, "Continue", () -> {
                                    FieldTourState.step(this, 3);
                                    View showTarget = box.findViewWithTag("rockmap-import-complete-show-map");
                                    showDialogCoach(holder[0], 3, FieldUiNames.IMPORT, "Import complete",
                                            "The file is now grouped as one import batch. You can show, review, manage, or remove this import without affecting unrelated RockMap data.",
                                            "Review the available actions for this imported file.", showTarget,
                                            null, "Finish", () -> {
                                                finishFieldTour();
                                                holder[0].dismiss();
                                                showHub();
                                            }, () -> {
                                                finishFieldTour();
                                                holder[0].dismiss();
                                                showHub();
                                            });
                                }, () -> {
                                    FieldTourState.step(this, 3);
                                    View showTarget = box.findViewWithTag("rockmap-import-complete-show-map");
                                    showDialogCoach(holder[0], 3, FieldUiNames.IMPORT, "Import complete",
                                            "The file is now grouped as one import batch. You can show, review, manage, or remove this import without affecting unrelated RockMap data.",
                                            "Review the available actions for this imported file.", showTarget,
                                            null, "Finish", () -> {
                                                finishFieldTour();
                                                holder[0].dismiss();
                                                showHub();
                                            }, () -> {
                                                finishFieldTour();
                                                holder[0].dismiss();
                                                showHub();
                                            });
                                });
                    },
                    "Finish", () -> {
                        finishFieldTour();
                        holder[0].dismiss();
                        showHub();
                    }, () -> {
                        finishFieldTour();
                        holder[0].dismiss();
                        showHub();
                    });
        }
    }

    private void showImports() {
        LinearLayout root = page();
        root.addView(title(FieldUiNames.IMPORTED_DATA));
        root.addView(help("Each GPX, KML, or GeoJSON file you import is tracked separately. Removing an import removes only the objects created by that file."));

        List<FieldDatabase.ImportBatch> batches = db.listImportBatches();
        root.addView(section("Imported Files"));
        if (batches.isEmpty()) {
            root.addView(help("No imported files yet."));
            View importFile = action("Import File",
                    "Choose a GPX, KML, or GeoJSON file.",
                    v -> {
                        if (FieldTourState.is(this, FieldUiNames.IMPORTED_DATA, 2)) {
                            finishFieldTour();
                            return;
                        }
                        beginImport();
                    });
            tagClickable(importFile, "rockmap-import-file");
            root.addView(importFile);
        } else {
            View importAnother = action("Import Another File",
                    "Choose another GPX, KML, or GeoJSON file.",
                    v -> beginImport());
            tagClickable(importAnother, "rockmap-import-file");
            root.addView(importAnother);
            for (FieldDatabase.ImportBatch batch : batches) {
                String detail = DateFormat.getDateTimeInstance().format(new Date(batch.importedAt))
                        + "\n" + batch.waypointCount + " Saved Locations · "
                        + batch.trackCount + " Tracks · " + batch.areaCount + " Prospecting Areas";
                View batchAction = action(batch.sourceName, detail, v -> {
                    if (FieldTourState.is(this, FieldUiNames.IMPORTED_DATA, 2)) {
                        FieldTourState.entityId(this, batch.id);
                        FieldTourState.step(this, 3);
                    }
                    showImportBatch(batch.id);
                });
                tagClickable(batchAction, "rockmap-import-batch:" + batch.id);
                root.addView(batchAction);
            }
        }

        View olderImports = section("Older Imports");
        tagClickable(olderImports, "rockmap-older-imports");
        root.addView(olderImports);
        root.addView(help("Some imported items are not grouped under a source file, so they cannot be safely removed as one batch. Manage those items from Saved Locations, Tracks, or Prospecting Areas."));
        root.addView(action("Review Saved Locations", "Review or delete imported Saved Locations that are not grouped under an import file.", v -> showLegacyWaypoints()));
        root.addView(action("Review Tracks", "Open or remove imported tracks that are not grouped under an import file.", v -> showTracks()));
        root.addView(action("Review Prospecting Areas", "Open or remove imported Prospecting Areas that are not grouped under an import file.", v -> showMeasure()));
        root.addView(nav("Back to Field", v -> showHub()));
        setContentView(scroll(root));
        getWindow().getDecorView().post(() -> showImportsTourCoach(batches));
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

        View showMap = action("Show Import on Map",
                "Zoom to all remaining geometry that belongs to this import.",
                v -> focusImportBatch(batchId));
        tagClickable(showMap, "rockmap-import-show-map");
        root.addView(showMap);

        List<Long> waypointIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_WAYPOINT);
        List<Long> trackIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_TRACK);
        List<Long> areaIds = db.getImportItemIds(batchId, FieldDatabase.IMPORT_AREA);

        if (!waypointIds.isEmpty()) {
            View saved = action("Saved Locations from This File", waypointIds.size() + " Saved Location" + (waypointIds.size() == 1 ? "" : "s") + ".",
                    v -> showImportedWaypointsByIds(waypointIds));
            tagClickable(saved, "rockmap-import-saved");
            root.addView(saved);
        }
        if (!trackIds.isEmpty()) {
            View tracksAction = action("Tracks from This File", trackIds.size() + " track" + (trackIds.size() == 1 ? "" : "s") + ".",
                    v -> showImportedTracks(trackIds));
            tagClickable(tracksAction, "rockmap-import-tracks");
            root.addView(tracksAction);
        }
        if (!areaIds.isEmpty()) {
            View areasAction = action("Prospecting Areas from This File", areaIds.size() + " Prospecting Area" + (areaIds.size() == 1 ? "" : "s") + ".",
                    v -> showImportedAreas(areaIds));
            tagClickable(areasAction, "rockmap-import-areas");
            root.addView(areasAction);
        }

        Button delete = button("Remove This Import");
        delete.setTag("rockmap-import-remove");
        delete.setOnClickListener(v -> confirmDeleteImportBatch(batchId));
        root.addView(delete);
        View back = nav("Back to Manage Imports", v -> showImports());
        back.setTag("rockmap-import-back");
        root.addView(back);
        setContentView(scroll(root));
        getWindow().getDecorView().post(() -> showImportBatchTourCoach(batch, waypointIds, trackIds, areaIds));
    }

    private void showImportsTourCoach(List<FieldDatabase.ImportBatch> batches) {
        if (!FieldTourState.is(this, FieldUiNames.IMPORTED_DATA)) return;
        int step = FieldTourState.step(this);
        if (step == 2) {
            if (batches == null || batches.isEmpty()) {
                View target = findViewById(android.R.id.content).findViewWithTag("rockmap-import-file");
                showFieldCoach(2, FieldUiNames.IMPORTED_DATA, "Imported Data",
                        "Imported Data keeps each imported file as its own batch so you can review the Saved Locations, Tracks, and Prospecting Areas created from that file and remove only that import later.\n\nNo imported files are currently available to demonstrate. Import File is where you add a GPX, KML, or GeoJSON file when you have one.",
                        "Review “Import File”, then Finish.", target,
                        () -> {
                            FieldTourState.step(this, 1);
                            returnToMap();
                        },
                        "Finish", this::finishFieldTour, this::finishFieldTour);
                return;
            }
            FieldDatabase.ImportBatch batch = batches.get(0);
            View target = findViewById(android.R.id.content).findViewWithTag("rockmap-import-batch:" + batch.id);
            showFieldCoach(2, FieldUiNames.IMPORTED_DATA, "Choose an imported file",
                    "Each imported file is tracked as its own batch. Open one to see the RockMap objects created from that file and the actions that apply only to that import.",
                    "Tap an imported file to manage it.", target,
                    () -> {
                        FieldTourState.step(this, 1);
                        returnToMap();
                    }, null, null,
                    () -> {
                        FieldTourState.entityId(this, batch.id);
                        FieldTourState.step(this, 3);
                        showImportBatch(batch.id);
                    });
            return;
        }
        if (step == 8) {
            View target = findViewById(android.R.id.content).findViewWithTag("rockmap-older-imports");
            showFieldCoach(8, FieldUiNames.IMPORTED_DATA, "Older Imports",
                    "Some older imported objects are not grouped under a source file. Manage those individually through Saved Locations, Tracks, or Prospecting Areas rather than removing them as a batch.",
                    "Review the Older Imports section.", target,
                    () -> {
                        long id = FieldTourState.entityId(this);
                        if (id > 0L) {
                            FieldTourState.step(this, 7);
                            showImportBatch(id);
                        }
                    },
                    "Finish", this::finishFieldTour, this::finishFieldTour);
        }
    }

    private void showImportBatchTourCoach(FieldDatabase.ImportBatch batch,
                                          List<Long> waypointIds,
                                          List<Long> trackIds,
                                          List<Long> areaIds) {
        if (batch == null || !FieldTourState.is(this, FieldUiNames.IMPORTED_DATA)) return;
        int step = FieldTourState.step(this);
        View root = findViewById(android.R.id.content);

        // Skip category steps that do not exist for this particular file. The tour never points at
        // a button that is absent from the selected import.
        if (step == 4 && (waypointIds == null || waypointIds.isEmpty())) {
            FieldTourState.step(this, 5); getWindow().getDecorView().post(() -> showImportBatchTourCoach(batch, waypointIds, trackIds, areaIds)); return;
        }
        if (step == 5 && (trackIds == null || trackIds.isEmpty())) {
            FieldTourState.step(this, 6); getWindow().getDecorView().post(() -> showImportBatchTourCoach(batch, waypointIds, trackIds, areaIds)); return;
        }
        if (step == 6 && (areaIds == null || areaIds.isEmpty())) {
            FieldTourState.step(this, 7); getWindow().getDecorView().post(() -> showImportBatchTourCoach(batch, waypointIds, trackIds, areaIds)); return;
        }
        if (step < 3 || step > 7) return;

        View target;
        String title;
        String body;
        if (step == 3) {
            target = root.findViewWithTag("rockmap-import-show-map");
            title = "Show Import on Map";
            body = "Show Import on Map frames all remaining geometry that belongs to this file. It does not change or delete the imported objects.";
        } else if (step == 4) {
            target = root.findViewWithTag("rockmap-import-saved");
            title = "Saved Locations from This File";
            body = "Open the point and waypoint locations created by this import.";
        } else if (step == 5) {
            target = root.findViewWithTag("rockmap-import-tracks");
            title = "Tracks from This File";
            body = "Open the line or track geometry created by this import.";
        } else if (step == 6) {
            target = root.findViewWithTag("rockmap-import-areas");
            title = "Prospecting Areas from This File";
            body = "Open the polygon geometry created by this import as saved Prospecting Areas.";
        } else {
            target = root.findViewWithTag("rockmap-import-remove");
            title = "Remove This Import";
            body = "Remove This Import deletes the remaining RockMap objects created by this file. It does not delete unrelated Saved Locations, recorded Tracks, Prospecting Areas, or Field Records.";
        }

        Runnable back = () -> {
            if (step == 3) {
                FieldTourState.step(this, 2);
                showImports();
                return;
            }
            int prior = step - 1;
            if (prior == 6 && (areaIds == null || areaIds.isEmpty())) prior--;
            if (prior == 5 && (trackIds == null || trackIds.isEmpty())) prior--;
            if (prior == 4 && (waypointIds == null || waypointIds.isEmpty())) prior--;
            FieldTourState.step(this, Math.max(3, prior));
            showImportBatchTourCoach(batch, waypointIds, trackIds, areaIds);
        };
        Runnable next = () -> {
            if (step == 7) {
                FieldTourState.step(this, 8);
                showImports();
            } else {
                FieldTourState.step(this, step + 1);
                showImportBatchTourCoach(batch, waypointIds, trackIds, areaIds);
            }
        };
        showFieldCoach(step, FieldUiNames.IMPORTED_DATA, title, body,
                step == 7 ? "Review this destructive action; do not press it for the tour." : "Review the highlighted action.",
                target, back, "Continue", next, next);
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
            root.addView(action(t.name, trackStatus(t, db.getTrackPoints(t.id)) + "\nTap to open on the map.",
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
            boolean hasExportable = !waypoints.isEmpty() || !tracks.isEmpty() || !records.isEmpty()
                    || !areas.isEmpty() || !batches.isEmpty() || ResearchResultStore.exists(this);
            getWindow().getDecorView().post(() -> showExportTourCoach(hasExportable));
        });
    }

    private void showExportTourCoach(boolean hasExportable) {
        if (!FieldTourState.is(this, FieldUiNames.EXPORT)) return;
        int step = FieldTourState.step(this);
        if (step < 2 || step > 3) return;
        View target = hasExportable ? findFirstFeatureAction(findViewById(android.R.id.content)) : null;
        if (step == 2) {
            String body = hasExportable
                    ? "Export creates a copy of selected RockMap data for use elsewhere. Saved Locations, Tracks, Field Records, Prospecting Areas, imported files, the last saved analysis, and combined field data appear here when available."
                    : "Export creates copies of RockMap data for use elsewhere. Export options appear here after you have Saved Locations, Tracks, Field Records, Prospecting Areas, imported data, or other supported saved content.";
            showFieldCoach(2, FieldUiNames.EXPORT, "Choose what to export",
                    body,
                    hasExportable ? "Review the available export categories." : "Continue to learn about export formats.", target,
                    () -> { FieldTourState.step(this, 1); returnToMap(); },
                    "Continue", () -> {
                        FieldTourState.step(this, 3);
                        if (hasExportable) openExportTourFormatStep();
                        else showExportTourCoach(false);
                    },
                    () -> {
                        FieldTourState.step(this, 3);
                        if (hasExportable) openExportTourFormatStep();
                        else showExportTourCoach(false);
                    });
        } else if (hasExportable) {
            // Step 3 teaches the real format chooser, not an abstract card on the Export hub.
            // If this screen was rebuilt while the tour was already on step 3, reopen the same
            // real chooser so the tutorial never asks for a dialog that is not actually visible.
            openExportTourFormatStep();
        } else {
            String body = "Different data types support formats such as GPX, GeoJSON, CSV, or KML. Android asks where to save the exported copy. Exporting does not remove the original RockMap data.";
            showFieldCoach(3, FieldUiNames.EXPORT, "Choose a format",
                    body, "Finish when you are ready.", null,
                    () -> { FieldTourState.step(this, 2); showExportTourCoach(false); },
                    "Finish", this::finishFieldTour, this::finishFieldTour);
        }
    }

    /** Open the same real format chooser used by Export Data when tour step 3 asks about formats. */
    private void openExportTourFormatStep() {
        if (!FieldTourState.is(this, FieldUiNames.EXPORT, 3)) return;
        GuidedTourCoach.clear(this);
        waypointRepository.getAll(waypoints -> {
            if (!FieldTourState.is(this, FieldUiNames.EXPORT, 3)) return;
            if (waypoints != null && !waypoints.isEmpty()) {
                showSavedLocationExportFormats(waypoints.size());
                return;
            }
            if (!db.listFieldRecords().isEmpty()) {
                showFieldRecordExportFormats();
                return;
            }
            for (FieldDatabase.Track track : db.listTracks(0)) {
                if (track != null && db.getTrackPoints(track.id).size() >= 2) {
                    showTrackExportFormats(-1L, "RockMap-Tracks");
                    return;
                }
            }
            if (!db.listAreas().isEmpty()) {
                showAreaExportFormats(-1L, "RockMap-Prospecting-Areas");
                return;
            }
            if (ResearchResultStore.exists(this)) {
                showResearchExportFormats();
                return;
            }

            // Imported files export as GeoJSON directly after choosing the import, so there is no
            // format chooser to fake. Explain that exception rather than manufacturing a dialog.
            List<FieldDatabase.ImportBatch> batches = db.listImportBatches();
            if (!batches.isEmpty()) {
                View target = findFirstFeatureAction(findViewById(android.R.id.content));
                showFieldCoach(3, FieldUiNames.EXPORT, "Choose a format",
                        "Different data types support formats such as GPX, GeoJSON, CSV, or KML. Android asks where to save the exported copy. Exporting does not remove the original RockMap data.",
                        "Finish when you are ready.", target,
                        () -> { FieldTourState.step(this, 2); showExportTourCoach(true); },
                        "Finish", this::finishFieldTour, this::finishFieldTour);
                return;
            }

            showExportTourCoach(false);
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
        final View[] firstChoice = new View[1];
        for (int i = 0; i < labels.length; i++) {
            final int choice = i;
            View option = exportOption(
                    labels[i],
                    details != null && i < details.length ? details[i] : "",
                    ctas != null && i < ctas.length ? ctas[i] : "Tap this box to select ›",
                    true,
                    v -> {
                        boolean exportTourFormatStep = FieldTourState.is(
                                FieldActivity.this, FieldUiNames.EXPORT, 3);
                        if (exportTourFormatStep) finishFieldTour();
                        if (holder[0] != null) holder[0].dismiss();
                        handler.onChoice(choice);
                    });
            if (firstChoice[0] == null) firstChoice[0] = option;
            content.addView(option);
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
        dialog.setOnDismissListener(d -> {
            // Canceling the real format chooser during the tour returns to the preceding valid
            // Export step instead of leaving step 3 alive with no dialog/coach to teach it.
            if (FieldTourState.is(FieldActivity.this, FieldUiNames.EXPORT, 3)) {
                GuidedTourCoach.clear(FieldActivity.this);
                FieldTourState.step(FieldActivity.this, 2);
                getWindow().getDecorView().post(FieldActivity.this::showExportHub);
            }
        });
        dialog.show();

        if (FieldTourState.is(this, FieldUiNames.EXPORT, 3)) {
            String body = "Tap an available export category to choose its applicable output format, such as GPX, GeoJSON, CSV, or KML. Android then asks where to save the copy. Export does not remove the original RockMap data.";
            showDialogCoach(dialog, 3, FieldUiNames.EXPORT, "Choose a format",
                    body,
                    "Review an available export option.",
                    firstChoice[0],
                    () -> {
                        FieldTourState.step(this, 2);
                        GuidedTourCoach.clear(this);
                        if (dialog.isShowing()) dialog.dismiss();
                        showExportHub();
                    },
                    "Finish", () -> {
                        finishFieldTour();
                        if (dialog.isShowing()) dialog.dismiss();
                    },
                    () -> {
                        finishFieldTour();
                        if (dialog.isShowing()) dialog.dismiss();
                    });
        }
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

        final Button[] useGpsRef = new Button[1];
        final Button[] convertRef = new Button[1];
        LinearLayout row = row();
        Button useGps = small("Use GPS", v -> runWithPreciseLocation(() ->
                locationRepository.requestFreshPrecise(l -> {
                    input.setText(CoordinateFormats.decimal(l.getLatitude(), l.getLongitude()));
                    renderFormats(input, output);
                    if (FieldTourState.is(this, FieldUiNames.COORDINATES, 3)) {
                        FieldTourState.step(this, 4);
                        getWindow().getDecorView().post(() -> showCoordinatesTourCoach(input, output, useGpsRef[0], convertRef[0]));
                    }
                }, this::toast)));
        Button convert = small("Convert", v -> {
            renderFormats(input, output);
            if (FieldTourState.is(this, FieldUiNames.COORDINATES, 4)
                    && input.getError() == null) {
                FieldTourState.step(this, 5);
                getWindow().getDecorView().post(() -> showCoordinatesTourCoach(input, output, useGpsRef[0], convertRef[0]));
            }
        });
        // Stable references are used by the asynchronous GPS callback above.
        useGpsRef[0] = useGps;
        convertRef[0] = convert;
        row.addView(useGps, weight());
        row.addView(convert, weight());
        root.addView(row);
        root.addView(back());
        setContentView(scroll(root));
        getWindow().getDecorView().post(() -> showCoordinatesTourCoach(input, output, useGps, convert));
    }

    private void showCoordinatesTourCoach(EditText input, TextView output, Button useGps, Button convert) {
        if (!FieldTourState.is(this, FieldUiNames.COORDINATES)) return;
        int step = FieldTourState.step(this);
        if (step == 2) {
            showFieldCoach(2, FieldUiNames.COORDINATES, "Coordinate input",
                    "Enter latitude and longitude in decimal, DDM, or DMS format. RockMap converts the same location into its other supported coordinate formats.",
                    "Enter coordinates, or continue to see the GPS shortcut.", input,
                    () -> { FieldTourState.step(this, 1); returnToMap(); },
                    "Continue", () -> { FieldTourState.step(this, 3); showCoordinatesTourCoach(input, output, useGps, convert); },
                    () -> { FieldTourState.step(this, 3); showCoordinatesTourCoach(input, output, useGps, convert); });
        } else if (step == 3) {
            showFieldCoach(3, FieldUiNames.COORDINATES, "Use GPS",
                    "Use GPS fills the input with a fresh current GPS location instead of making you type coordinates. It does not improve or change the accuracy of the GPS fix itself.",
                    "Tap “Use GPS”, or continue if you entered coordinates manually.", useGps,
                    () -> { FieldTourState.step(this, 2); showCoordinatesTourCoach(input, output, useGps, convert); },
                    "Continue", () -> { FieldTourState.step(this, 4); showCoordinatesTourCoach(input, output, useGps, convert); },
                    () -> { FieldTourState.step(this, 4); showCoordinatesTourCoach(input, output, useGps, convert); });
        } else if (step == 4) {
            showFieldCoach(4, FieldUiNames.COORDINATES, "Convert",
                    "Convert reads the entered location and shows the same point in each supported coordinate system.",
                    "Tap “Convert”.", convert,
                    () -> { FieldTourState.step(this, 3); showCoordinatesTourCoach(input, output, useGps, convert); },
                    null, null,
                    () -> {
                        if (input.getText().toString().trim().isEmpty()) {
                            finishFieldTour();
                        } else {
                            convert.performClick();
                        }
                    });
        } else if (step == 5) {
            showFieldCoach(5, FieldUiNames.COORDINATES, "Coordinate formats",
                    "The result shows Decimal, DDM, DMS, WGS84 UTM, and MGRS representations of the same location.",
                    "Review the converted coordinate formats.", output,
                    () -> { FieldTourState.step(this, 4); showCoordinatesTourCoach(input, output, useGps, convert); },
                    "Finish", this::finishFieldTour, this::finishFieldTour);
        }
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
                .setNegativeButton("Cancel", (d, w) -> {
                    pendingLocationAction = null;
                    restoreLocationTourAfterPermissionFailure();
                })
                .show();
    }

    private void restoreLocationTourAfterPermissionFailure() {
        if (FieldTourState.is(this, FieldUiNames.TRACK, 2)) {
            getWindow().getDecorView().post(this::showTracks);
        } else if (FieldTourState.is(this, FieldUiNames.FIELD_RECORDS, 2)) {
            getWindow().getDecorView().post(this::showFieldRecords);
        }
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
            restoreLocationTourAfterPermissionFailure();
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
            if (requestCode == REQ_IMPORT && FieldTourState.is(this, FieldUiNames.IMPORT, 2)) {
                FieldTourState.step(this, 1);
                GuidedTourCoach.clear(this);
                returnToMap();
            }
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
        GuidedTourCoach.clear(this);
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

    private void scrollTargetIntoView(View target) {
        if (target == null) return;
        View parent = target;
        while (parent.getParent() instanceof View) {
            parent = (View) parent.getParent();
            if (parent instanceof ScrollView) {
                ScrollView scroll = (ScrollView) parent;
                Rect rect = new Rect();
                target.getDrawingRect(rect);
                scroll.offsetDescendantRectToMyCoords(target, rect);
                scroll.scrollTo(0, Math.max(0, rect.top - dp(16)));
                return;
            }
        }
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

    private View compactHelpButton(String description, View.OnClickListener listener) {
        FrameLayout touch = new FrameLayout(this);
        touch.setClickable(true);
        touch.setFocusable(true);
        touch.setContentDescription(description);
        touch.setOnClickListener(listener);

        TextView icon = new TextView(this);
        icon.setText("?");
        icon.setTextSize(14f);
        icon.setTextColor(0xff1e5591);
        icon.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0xffeef4fb);
        background.setStroke(dp(1), 0xff7ea6cf);
        icon.setBackground(background);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER);
        touch.addView(icon, iconParams);
        return touch;
    }


    private View savedResearchCard(String heading, String detail) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackgroundColor(0xffffffff);

        TextView h = new TextView(this);
        h.setText(heading == null || heading.trim().isEmpty() ? "Research" : heading.trim());
        h.setTextSize(15f);
        h.setTextColor(0xff303030);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(h);

        TextView d = help(detail == null ? "" : detail);
        d.setPadding(0, dp(4), 0, 0);
        card.addView(d);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(0, dp(4), 0, dp(4));
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
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
