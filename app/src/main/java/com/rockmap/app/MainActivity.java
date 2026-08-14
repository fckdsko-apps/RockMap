package com.rockmap.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ContentResolver;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.WorkInfo;

import com.rockmap.app.location.LocationRepository;
import com.rockmap.app.map.MapController;
import com.rockmap.app.offline.OfflineDataManager;
import com.rockmap.app.waypoints.WaypointEntity;
import com.rockmap.app.waypoints.WaypointRepository;

import org.maplibre.android.MapLibre;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapView;
import org.maplibre.geojson.Feature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements LocationRepository.Listener, MapController.Listener {
    private static final int LOCATION_PERMISSION_REQUEST = 501;
    private static final int EXPORT_WAYPOINTS_REQUEST = 502;
    private static final int IMPORT_WAYPOINTS_REQUEST = 503;
    private static final int MAX_IMPORT_BYTES = 5_000_000;
    private static final int MAX_IMPORT_WAYPOINTS = 10_000;

    private MapView mapView;
    private MapController mapController;
    private LocationRepository locationRepository;
    private WaypointRepository waypointRepository;
    private OfflineDataManager offlineDataManager;
    private TextView safetyBanner;
    private LiveData<WorkInfo> updateLiveData;
    private Observer<WorkInfo> updateObserver;
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // MapLibre must be initialized before MapView.onCreate(). No commercial token is required.
        MapLibre.getInstance(this);

        offlineDataManager = new OfflineDataManager(this);
        waypointRepository = new WaypointRepository(this);
        locationRepository = new LocationRepository(this, this);

        FrameLayout root = new FrameLayout(this);
        mapView = new MapView(this);
        root.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        safetyBanner = new TextView(this);
        safetyBanner.setTextColor(Color.WHITE);
        safetyBanner.setBackgroundColor(Color.rgb(150, 35, 25));
        safetyBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
        safetyBanner.setTextSize(14f);
        safetyBanner.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams bannerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(safetyBanner, bannerParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(6), dp(6), dp(6), dp(6));
        controls.setBackgroundColor(Color.argb(235, 255, 255, 255));
        addControl(controls, "Locate", v -> locate());
        addControl(controls, "Save", v -> saveLocation());
        addControl(controls, "Layers", v -> showLayers());
        addControl(controls, "Saved", v -> showSaved());
        addControl(controls, "Data", v -> showData());
        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(controls, controlsParams);

        // Android 15+ enforces edge-to-edge for modern target SDKs. Keep the map immersive,
        // but move safety text and controls inside the actual system-bar/cutout insets so they
        // are never hidden behind the status or navigation UI. These accessors exist on minSdk 26.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            safetyBanner.setPadding(dp(12) + left, dp(10) + top, dp(12) + right, dp(10));
            controls.setPadding(dp(6) + left, dp(6), dp(6) + right, dp(6) + bottom);
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();

        mapView.onCreate(savedInstanceState);
        mapController = new MapController(mapView, offlineDataManager, this);
        mapController.initialize();
        refreshWaypoints();
    }

    private void addControl(LinearLayout row, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        row.addView(button, params);
    }

    private void locate() {
        if (!ensureLocationPermission()) return;
        Location latest = locationRepository.getLatest();
        if (locationRepository.isRecent(latest)) {
            mapController.centerOn(latest);
            return;
        }
        locationRepository.requestCurrent(location -> {
            mapController.updateCurrentLocation(location);
            mapController.centerOn(location);
        }, this::showMessage);
    }

    private void saveLocation() {
        if (!ensureLocationPermission()) return;
        if (!locationRepository.hasFinePermission()) {
            new AlertDialog.Builder(this)
                    .setTitle("Precise location required")
                    .setMessage("RockMap will not save a field waypoint from Android's approximate-location permission. Enable precise location, then try again.")
                    .setPositiveButton("App settings", (d, w) -> openAppSettings())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        locationRepository.requestFreshPrecise(this::showSaveDialog, this::showMessage);
    }

    private void showSaveDialog(Location location) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);

        TextView fix = new TextView(this);
        String accuracy = location.hasAccuracy()
                ? String.format(Locale.US, "Reported GPS accuracy: ±%.1f m", location.getAccuracy())
                : "GPS did not report an accuracy estimate.";
        fix.setText(accuracy + "\n" + String.format(Locale.US, "%.6f, %.6f",
                location.getLatitude(), location.getLongitude()));
        box.addView(fix);

        EditText name = new EditText(this);
        name.setHint("Name (for example: quartz vein)");
        box.addView(name);
        EditText notes = new EditText(this);
        notes.setHint("Notes (optional)");
        notes.setMinLines(2);
        box.addView(notes);

        String warning = location.hasAccuracy() && location.getAccuracy() > 50f
                ? "\n\nThis GPS fix is relatively imprecise. The exact accuracy estimate above will be saved with the waypoint."
                : "";

        new AlertDialog.Builder(this)
                .setTitle("Save current GPS location")
                .setMessage(warning.isEmpty() ? null : warning.trim())
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    long now = System.currentTimeMillis();
                    String label = boundedText(name.getText().toString().trim(), 500);
                    if (label.isEmpty()) label = "Saved location";
                    String noteText = boundedText(notes.getText().toString().trim(), 20_000);
                    float savedAccuracy = location.hasAccuracy() && Float.isFinite(location.getAccuracy())
                            && location.getAccuracy() >= 0f ? location.getAccuracy() : -1f;
                    WaypointEntity waypoint = new WaypointEntity(
                            location.getLatitude(), location.getLongitude(),
                            savedAccuracy,
                            location.getTime() > 0 ? location.getTime() : now,
                            label, noteText, now, now);
                    waypointRepository.insert(waypoint, () -> {
                        refreshWaypoints();
                        showMessage("Location saved.");
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLayers() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(4));
        CheckBox land = checkbox("Land status", mapController.isLandVisible());
        CheckBox claims = checkbox("Mining claims — not closed", mapController.isClaimsVisible());
        CheckBox saved = checkbox("Saved locations", mapController.isWaypointsVisible());
        box.addView(land);
        box.addView(claims);
        box.addView(saved);
        new AlertDialog.Builder(this)
                .setTitle("Layers")
                .setView(box)
                .setPositiveButton("Apply", (d, w) -> {
                    mapController.setLandVisible(land.isChecked());
                    mapController.setClaimsVisible(claims.isChecked());
                    mapController.setWaypointsVisible(saved.isChecked());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private CheckBox checkbox(String label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setChecked(checked);
        box.setPadding(0, dp(4), 0, dp(4));
        return box;
    }

    private void showSaved() {
        waypointRepository.getAll(items -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle("Saved locations")
                    .setPositiveButton("Export", (dialog, which) -> beginWaypointExport())
                    .setNeutralButton("Import", (dialog, which) -> beginWaypointImport())
                    .setNegativeButton("Close", null);
            if (items.isEmpty()) {
                builder.setMessage("No saved locations yet. You can import a RockMap GeoJSON backup.");
            } else {
                String[] labels = new String[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    WaypointEntity w = items.get(i);
                    labels[i] = w.name + "\n" + String.format(Locale.US, "%.5f, %.5f", w.latitude, w.longitude);
                }
                builder.setItems(labels, (dialog, which) -> showWaypoint(items.get(which)));
            }
            builder.show();
        });
    }

    private void beginWaypointExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/geo+json");
        intent.putExtra(Intent.EXTRA_TITLE, "RockMap-Locations.geojson");
        startActivityForResult(intent, EXPORT_WAYPOINTS_REQUEST);
    }

    private void beginWaypointImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, IMPORT_WAYPOINTS_REQUEST);
    }

    private void showWaypoint(WaypointEntity waypoint) {
        String accuracy = waypoint.accuracyMeters >= 0
                ? String.format(Locale.US, "±%.1f m", waypoint.accuracyMeters) : "not reported";
        String body = String.format(Locale.US,
                "%.6f, %.6f\nAccuracy when saved: %s\nCaptured: %s%s",
                waypoint.latitude, waypoint.longitude, accuracy,
                DateFormat.getDateTimeInstance().format(new Date(waypoint.capturedAt)),
                waypoint.notes == null || waypoint.notes.trim().isEmpty() ? "" : "\n\n" + waypoint.notes);
        new AlertDialog.Builder(this)
                .setTitle(waypoint.name)
                .setMessage(body)
                .setPositiveButton("Show on map", (d, w) -> mapController.centerOn(waypoint))
                .setNeutralButton("Edit", (d, w) -> editWaypoint(waypoint))
                .setNegativeButton("Delete", (d, w) -> confirmDelete(waypoint))
                .show();
    }

    private void editWaypoint(WaypointEntity waypoint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);
        EditText name = new EditText(this);
        name.setText(waypoint.name);
        box.addView(name);
        EditText notes = new EditText(this);
        notes.setText(waypoint.notes);
        notes.setMinLines(2);
        box.addView(notes);
        new AlertDialog.Builder(this)
                .setTitle("Edit saved location")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String label = boundedText(name.getText().toString().trim(), 500);
                    waypoint.name = label.isEmpty() ? "Saved location" : label;
                    waypoint.notes = boundedText(notes.getText().toString().trim(), 20_000);
                    waypointRepository.update(waypoint, this::refreshWaypoints);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDelete(WaypointEntity waypoint) {
        new AlertDialog.Builder(this)
                .setTitle("Delete saved location?")
                .setMessage(waypoint.name + " will be removed from RockMap.")
                .setPositiveButton("Delete", (d, w) -> waypointRepository.delete(waypoint, this::refreshWaypoints))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showData() {
        new AlertDialog.Builder(this)
                .setTitle("Offline data")
                .setMessage(offlineDataManager.describeStatus())
                .setPositiveButton("Check for update", (d, w) -> startDataUpdate())
                .setNegativeButton("Close", null)
                .show();
    }

    private void startDataUpdate() {
        if (BuildConfig.DATA_MANIFEST_URL == null || BuildConfig.DATA_MANIFEST_URL.trim().isEmpty()) {
            showMessage("This APK was not built from a configured public GitHub repository.");
            return;
        }
        androidx.work.OneTimeWorkRequest request = offlineDataManager.queueUpdate();
        Toast.makeText(this, "Checking verified map data…", Toast.LENGTH_SHORT).show();
        clearUpdateObserver();
        updateLiveData = androidx.work.WorkManager.getInstance(this)
                .getWorkInfoByIdLiveData(request.getId());
        updateObserver = new Observer<WorkInfo>() {
            @Override
            public void onChanged(WorkInfo info) {
                if (info == null || !info.getState().isFinished()) return;
                clearUpdateObserver();
                if (info.getState() == WorkInfo.State.SUCCEEDED) {
                    mapController.reloadStyle();
                    showMessage("Verified offline data downloaded. RockMap is validating it on the map now.");
                } else {
                    showMessage(offlineDataManager.getLastUpdateStatus());
                }
            }
        };
        updateLiveData.observeForever(updateObserver);
    }

    private void clearUpdateObserver() {
        if (updateLiveData != null && updateObserver != null) {
            updateLiveData.removeObserver(updateObserver);
        }
        updateLiveData = null;
        updateObserver = null;
    }

    private void refreshWaypoints() {
        if (mapController == null) return;
        waypointRepository.getAll(mapController::setWaypoints);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == EXPORT_WAYPOINTS_REQUEST) {
            exportWaypoints(uri);
        } else if (requestCode == IMPORT_WAYPOINTS_REQUEST) {
            importWaypoints(uri);
        }
    }

    private void exportWaypoints(Uri uri) {
        waypointRepository.getAll(items -> {
            try {
                JSONObject root = new JSONObject();
                root.put("type", "FeatureCollection");
                root.put("rockmapSchema", 1);
                JSONArray features = new JSONArray();
                for (WaypointEntity waypoint : items) {
                    JSONObject feature = new JSONObject();
                    feature.put("type", "Feature");
                    JSONObject geometry = new JSONObject();
                    geometry.put("type", "Point");
                    geometry.put("coordinates", new JSONArray().put(waypoint.longitude).put(waypoint.latitude));
                    feature.put("geometry", geometry);
                    JSONObject props = new JSONObject();
                    props.put("name", waypoint.name);
                    props.put("notes", waypoint.notes);
                    props.put("accuracyMeters", waypoint.accuracyMeters);
                    props.put("capturedAt", waypoint.capturedAt);
                    props.put("createdAt", waypoint.createdAt);
                    props.put("updatedAt", waypoint.updatedAt);
                    feature.put("properties", props);
                    features.put(feature);
                }
                root.put("features", features);
                ContentResolver resolver = getContentResolver();
                try (OutputStream output = resolver.openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("Android could not open the selected export file.");
                    output.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                showMessage("Exported " + items.size() + " saved location" + (items.size() == 1 ? "." : "s."));
            } catch (IOException | JSONException ex) {
                showMessage("Waypoint export failed: " + ex.getMessage());
            }
        });
    }

    private void importWaypoints(Uri uri) {
        try {
            byte[] bytes = readContentUri(uri, MAX_IMPORT_BYTES);
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!"FeatureCollection".equals(root.optString("type"))) {
                throw new JSONException("Expected a GeoJSON FeatureCollection.");
            }
            JSONArray features = root.getJSONArray("features");
            if (features.length() > MAX_IMPORT_WAYPOINTS) {
                throw new JSONException("Backup contains too many locations.");
            }
            ArrayList<WaypointEntity> imports = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject geometry = feature.getJSONObject("geometry");
                if (!"Point".equals(geometry.optString("type"))) continue;
                JSONArray coords = geometry.getJSONArray("coordinates");
                if (coords.length() < 2) continue;
                double longitude = coords.getDouble(0);
                double latitude = coords.getDouble(1);
                if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                        || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) continue;
                JSONObject props = feature.optJSONObject("properties");
                String name = props == null ? "Imported location" : props.optString("name", "Imported location");
                String notes = props == null ? "" : props.optString("notes", "");
                float accuracy = props == null ? -1f : (float) props.optDouble("accuracyMeters", -1d);
                if (!Float.isFinite(accuracy) || accuracy < 0f) accuracy = -1f;
                long capturedAt = props == null ? now : props.optLong("capturedAt", now);
                long createdAt = props == null ? now : props.optLong("createdAt", now);
                long updatedAt = props == null ? now : props.optLong("updatedAt", now);
                if (name.length() > 500) name = name.substring(0, 500);
                if (notes.length() > 20_000) notes = notes.substring(0, 20_000);
                imports.add(new WaypointEntity(latitude, longitude, accuracy, capturedAt, name, notes, createdAt, updatedAt));
            }
            if (imports.isEmpty()) {
                showMessage("No valid point locations were found in that file.");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Import saved locations?")
                    .setMessage("RockMap found " + imports.size() + " valid point locations. Importing adds them; it does not delete existing locations.")
                    .setPositiveButton("Import", (d, w) -> waypointRepository.insertAll(imports, count -> {
                        refreshWaypoints();
                        showMessage("Imported " + count + " saved location" + (count == 1 ? "." : "s."));
                    }))
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (IOException | JSONException ex) {
            showMessage("Waypoint import rejected: " + ex.getMessage());
        }
    }

    private byte[] readContentUri(Uri uri, int maxBytes) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Android could not open the selected file.");
            byte[] buffer = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Selected file exceeds the import size limit.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private boolean ensureLocationPermission() {
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (coarse || fine) return true;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                LOCATION_PERMISSION_REQUEST);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) return;
        if (locationRepository.hasCoarsePermission()) {
            if (started) locationRepository.start();
            if (!locationRepository.hasFinePermission()) {
                showMessage("Approximate location granted. Viewing can work, but RockMap will not save a field waypoint without precise location.");
            }
        } else {
            showMessage("Location permission denied. Offline maps remain available, but GPS features are disabled.");
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    @Override
    public void onLocation(Location location) {
        mapController.updateCurrentLocation(location);
    }

    @Override
    public void onLocationError(String message) {
        showMessage(message);
    }

    @Override
    public void onMapSafetyState(boolean verified, String message) {
        safetyBanner.setText(message);
        safetyBanner.setBackgroundColor(verified ? Color.rgb(30, 100, 55) : Color.rgb(150, 35, 25));
        safetyBanner.setVisibility(View.VISIBLE);
    }

    @Override
    public void onMapFeaturesTapped(LatLng coordinate, List<Feature> land, List<Feature> claims) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.US, "%.6f, %.6f", coordinate.getLatitude(), coordinate.getLongitude()));
        text.append("\n\nLAND STATUS\n");
        if (!mapController.isLandVisible()) {
            text.append("Land-status layer is turned off. No land-status conclusion was made.");
        } else if (land.isEmpty()) {
            text.append("No land-status feature was rendered at this exact tap. Treat this as unknown, not as public land.");
        } else {
            for (Feature feature : land) {
                String manager = stringProp(feature, "manager_name", stringProp(feature, "manager_code", "Unknown"));
                text.append("• ").append(manager).append('\n');
            }
        }
        text.append("\nMINING CLAIMS — NOT CLOSED\n");
        if (!mapController.isClaimsVisible()) {
            text.append("Claims layer is turned off. No claim conclusion was made.");
        } else if (claims.isEmpty()) {
            text.append("No claim feature was rendered at this exact tap and zoom. This is not proof that no mining claim exists.");
        } else {
            text.append(claims.size()).append(claims.size() == 1 ? " claim shown here:\n" : " claims shown here:\n");
            for (Feature feature : claims) {
                text.append("• ").append(stringProp(feature, "name", "Unnamed claim"));
                String serial = stringProp(feature, "serial", "");
                if (!serial.isEmpty()) text.append(" (").append(serial).append(')');
                String quality = stringProp(feature, "quality_description", "");
                if (!quality.isEmpty()) text.append(" — ").append(quality);
                text.append('\n');
            }
        }
        text.append("\nDisplayed BLM claim geometry can be approximate and is not a surveyed legal boundary. RockMap does not determine whether collecting is legal at a location.");

        ScrollView scroll = new ScrollView(this);
        TextView body = new TextView(this);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(text.toString());
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle("Location information")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    private String stringProp(Feature feature, String name, String fallback) {
        if (feature == null || !feature.hasProperty(name) || feature.getStringProperty(name) == null) return fallback;
        return feature.getStringProperty(name);
    }

    private String boundedText(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private void showMessage(String message) {
        Toast.makeText(this, message == null ? "" : message, Toast.LENGTH_LONG).show();
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        mapView.onStart();
        if (locationRepository.hasCoarsePermission()) locationRepository.start();
    }
    @Override protected void onResume() { super.onResume(); mapView.onResume(); }
    @Override protected void onPause() { mapView.onPause(); super.onPause(); }
    @Override protected void onStop() {
        started = false;
        locationRepository.stop();
        mapView.onStop();
        super.onStop();
    }
    @Override protected void onLowMemory() { super.onLowMemory(); mapView.onLowMemory(); }
    @Override protected void onSaveInstanceState(Bundle outState) { super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState); }
    @Override protected void onDestroy() {
        clearUpdateObserver();
        waypointRepository.close();
        mapView.onDestroy();
        super.onDestroy();
    }
}
