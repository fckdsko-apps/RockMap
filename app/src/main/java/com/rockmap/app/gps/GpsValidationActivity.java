package com.rockmap.app.gps;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.rockmap.app.MainActivity;
import com.rockmap.app.coordinates.CoordinateParser;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Foreground-only diagnostic screen for Alpha 6.4 field validation.
 * It intentionally uses LocationManager.GPS_PROVIDER, matching RockMap's normal GPS path.
 */
public final class GpsValidationActivity extends Activity implements LocationListener {
    private static final int LOCATION_PERMISSION_REQUEST = 6401;
    private static final int MAX_SAMPLES = 300;

    private LocationManager locationManager;
    private TextView diagnostics;
    private EditText referenceInput;
    private final List<GpsValidationStats.Sample> samples = new ArrayList<>();
    private Location latest;
    private Double referenceLatitude;
    private Double referenceLongitude;
    private long sessionStartRealtimeMs;
    private boolean receivingUpdates;
    private boolean gnssRegistered;
    private boolean gnssStarted;
    private int satellitesVisible;
    private int satellitesUsed;
    private int firstFixMillis = -1;

    private final GnssStatus.Callback gnssCallback = new GnssStatus.Callback() {
        @Override public void onStarted() {
            gnssStarted = true;
            refreshDiagnostics();
        }

        @Override public void onStopped() {
            gnssStarted = false;
            refreshDiagnostics();
        }

        @Override public void onFirstFix(int ttffMillis) {
            firstFixMillis = ttffMillis;
            refreshDiagnostics();
        }

        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            satellitesVisible = status == null ? 0 : status.getSatelliteCount();
            int used = 0;
            if (status != null) {
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    if (status.usedInFix(i)) used++;
                }
            }
            satellitesUsed = used;
            refreshDiagnostics();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        sessionStartRealtimeMs = SystemClock.elapsedRealtime();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(24));

        TextView title = new TextView(this);
        title.setText("RockMap GPS validation — Alpha 6.4");
        title.setTextSize(21f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);

        TextView help = new TextView(this);
        help.setText("Foreground validation only. Stand still with the phone where it can see the sky and collect at least 20 fixes. Android's reported horizontal accuracy is an estimated 68% uncertainty radius, not measured error. Stationary scatter measures repeatability. Enter a known/surveyed coordinate below to measure absolute horizontal error.\n\nGPS altitude is the phone's WGS84-ellipsoid altitude, not surveyed or topo elevation.");
        help.setTextSize(13f);
        help.setPadding(0, dp(8), 0, dp(10));
        content.addView(help);

        diagnostics = new TextView(this);
        diagnostics.setTypeface(Typeface.MONOSPACE);
        diagnostics.setTextSize(13f);
        diagnostics.setTextIsSelectable(true);
        diagnostics.setPadding(dp(10), dp(10), dp(10), dp(10));
        content.addView(diagnostics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView referenceLabel = new TextView(this);
        referenceLabel.setText("Known reference coordinate (optional)");
        referenceLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        referenceLabel.setPadding(0, dp(14), 0, 0);
        content.addView(referenceLabel);

        referenceInput = new EditText(this);
        referenceInput.setHint("39.739236, -104.990251");
        referenceInput.setSingleLine(true);
        content.addView(referenceInput);

        LinearLayout referenceActions = new LinearLayout(this);
        referenceActions.setOrientation(LinearLayout.HORIZONTAL);
        Button setReference = button("Set reference");
        Button clearReference = button("Clear reference");
        referenceActions.addView(setReference, weight());
        referenceActions.addView(clearReference, weight());
        content.addView(referenceActions);

        LinearLayout sessionActions = new LinearLayout(this);
        sessionActions.setOrientation(LinearLayout.HORIZONTAL);
        Button copyReport = button("Copy report");
        Button reset = button("Reset samples");
        sessionActions.addView(copyReport, weight());
        sessionActions.addView(reset, weight());
        content.addView(sessionActions);

        LinearLayout settingsActions = new LinearLayout(this);
        settingsActions.setOrientation(LinearLayout.HORIZONTAL);
        Button settings = button("Location settings");
        Button openRockMap = button("Open RockMap");
        settingsActions.addView(settings, weight());
        settingsActions.addView(openRockMap, weight());
        content.addView(settingsActions);
        LinearLayout navActions = new LinearLayout(this);
        navActions.setOrientation(LinearLayout.HORIZONTAL);
        navActions.setGravity(Gravity.CENTER);
        Button close = button("Close validator");
        navActions.addView(close, weight());
        content.addView(navActions);

        setReference.setOnClickListener(v -> setReferenceCoordinate());
        clearReference.setOnClickListener(v -> {
            referenceLatitude = null;
            referenceLongitude = null;
            referenceInput.setText("");
            refreshDiagnostics();
        });
        copyReport.setOnClickListener(v -> copyReport());
        reset.setOnClickListener(v -> resetSamples());
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        openRockMap.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
        close.setOnClickListener(v -> finish());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        refreshDiagnostics();

        if (!hasFinePermission()) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_PERMISSION_REQUEST);
        }
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12f);
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void setReferenceCoordinate() {
        try {
            CoordinateParser.Result result = CoordinateParser.parse(referenceInput.getText().toString());
            referenceLatitude = result.latitude;
            referenceLongitude = result.longitude;
            referenceInput.setText(String.format(Locale.US, "%.6f, %.6f", result.latitude, result.longitude));
            refreshDiagnostics();
        } catch (IllegalArgumentException ex) {
            referenceInput.setError(ex.getMessage());
        }
    }

    private void copyReport() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || diagnostics == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("RockMap GPS validation", diagnostics.getText()));
        Toast.makeText(this, "GPS validation report copied.", Toast.LENGTH_SHORT).show();
    }

    private void resetSamples() {
        samples.clear();
        latest = null;
        sessionStartRealtimeMs = SystemClock.elapsedRealtime();
        refreshDiagnostics();
        Toast.makeText(this, "GPS validation samples reset.", Toast.LENGTH_SHORT).show();
    }

    private boolean hasFinePermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean gpsEnabled() {
        try {
            return locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void startValidation() {
        if (receivingUpdates || locationManager == null || !hasFinePermission()) {
            refreshDiagnostics();
            return;
        }
        if (!gpsEnabled()) {
            refreshDiagnostics();
            return;
        }
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
            receivingUpdates = true;
            if (!gnssRegistered) {
                locationManager.registerGnssStatusCallback(
                        gnssCallback, new Handler(Looper.getMainLooper()));
                gnssRegistered = true;
            }
        } catch (SecurityException ex) {
            receivingUpdates = false;
            gnssRegistered = false;
        } catch (RuntimeException ex) {
            receivingUpdates = false;
        }
        refreshDiagnostics();
    }

    private void stopValidation() {
        if (locationManager == null) return;
        try {
            locationManager.removeUpdates(this);
        } catch (RuntimeException ignored) {
        }
        receivingUpdates = false;
        if (gnssRegistered) {
            try {
                locationManager.unregisterGnssStatusCallback(gnssCallback);
            } catch (RuntimeException ignored) {
            }
            gnssRegistered = false;
        }
        gnssStarted = false;
        satellitesVisible = 0;
        satellitesUsed = 0;
        refreshDiagnostics();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!validLocation(location)) return;
        latest = new Location(location);
        float accuracy = location.hasAccuracy() && Float.isFinite(location.getAccuracy())
                ? location.getAccuracy() : -1f;
        samples.add(new GpsValidationStats.Sample(
                location.getLatitude(), location.getLongitude(), accuracy));
        if (samples.size() > MAX_SAMPLES) samples.remove(0);
        refreshDiagnostics();
    }

    private boolean validLocation(Location location) {
        if (location == null) return false;
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90d && lat <= 90d && lon >= -180d && lon <= 180d;
    }

    @Override public void onProviderEnabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) startValidation();
        refreshDiagnostics();
    }
    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) stopValidation();
        refreshDiagnostics();
    }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private void refreshDiagnostics() {
        if (diagnostics == null) return;
        StringBuilder text = new StringBuilder();
        text.append("Permission: ").append(hasFinePermission() ? "PRECISE" : "NOT PRECISE");
        text.append("\nGPS provider: ").append(gpsEnabled() ? "enabled" : "DISABLED");
        text.append("\nValidation updates: ").append(receivingUpdates ? "running (1 s / 0 m)" : "stopped");
        text.append("\nGNSS engine: ").append(gnssStarted ? "started" : "not reporting started");
        text.append("\nSatellites: ").append(satellitesVisible)
                .append(" reported / ").append(satellitesUsed).append(" used in fix");
        text.append("\nFirst fix: ").append(firstFixMillis >= 0
                ? String.format(Locale.US, "%.1f s", firstFixMillis / 1000d) : "not reported yet");

        long durationMs = Math.max(0L, SystemClock.elapsedRealtime() - sessionStartRealtimeMs);
        text.append("\n\nSession: ").append(samples.size()).append(" fixes / ")
                .append(String.format(Locale.US, "%.0f s", durationMs / 1000d));

        if (latest == null) {
            text.append("\n\nCurrent GPS fix: waiting…");
        } else {
            String provider = latest.getProvider() == null ? "unknown" : latest.getProvider();
            text.append("\n\nCurrent GPS fix");
            text.append("\nProvider: ").append(provider);
            text.append("\nCoordinates: ").append(String.format(Locale.US, "%.7f, %.7f",
                    latest.getLatitude(), latest.getLongitude()));
            text.append("\nFix age: ").append(formatFixAge(latest));
            if (latest.getTime() > 0L) {
                text.append("\nFix time: ")
                        .append(DateFormat.getDateTimeInstance().format(new Date(latest.getTime())));
            }
            text.append("\nHorizontal accuracy estimate (68%): ")
                    .append(latest.hasAccuracy()
                            ? String.format(Locale.US, "±%.1f m", latest.getAccuracy()) : "not reported");
            if (latest.hasAltitude()) {
                double meters = latest.getAltitude();
                text.append("\nGPS altitude (WGS84 ellipsoid): ")
                        .append(String.format(Locale.US, "%.1f m / %.0f ft", meters, meters * 3.280839895d));
                text.append("\nVertical accuracy estimate (68%): ")
                        .append(latest.hasVerticalAccuracy()
                                ? String.format(Locale.US, "±%.1f m", latest.getVerticalAccuracyMeters())
                                : "not reported");
            } else {
                text.append("\nGPS altitude (WGS84 ellipsoid): not reported");
            }
            text.append("\nMock fix: ").append(isMock(latest) ? "YES" : "no");
        }

        GpsValidationStats.Summary summary = GpsValidationStats.summarize(
                samples, referenceLatitude, referenceLongitude);
        if (summary.sampleCount > 0) {
            text.append("\n\nStationary sample summary");
            text.append("\nMedian coordinate: ")
                    .append(String.format(Locale.US, "%.7f, %.7f",
                            summary.medianLatitude, summary.medianLongitude));
            text.append("\nMedian reported accuracy: ")
                    .append(formatMeters(summary.medianReportedAccuracyMeters));
            text.append("\nMedian scatter: ").append(formatMeters(summary.medianScatterMeters));
            text.append("\n95% scatter: ").append(formatMeters(summary.p95ScatterMeters));
            text.append("\nMaximum scatter: ").append(formatMeters(summary.maxScatterMeters));
            if (referenceLatitude != null && referenceLongitude != null) {
                text.append("\nReference: ")
                        .append(String.format(Locale.US, "%.7f, %.7f", referenceLatitude, referenceLongitude));
                text.append("\nReference error of median: ")
                        .append(formatMeters(summary.referenceErrorMeters));
            } else {
                text.append("\nReference error: enter a known coordinate to measure it");
            }
        }

        text.append("\n\nInterpretation: scatter = repeatability, not absolute accuracy. ")
                .append("Use reference error at a known point for absolute validation. ")
                .append("Do not treat Android's reported ± value as a guarantee.");
        diagnostics.setText(text.toString());
    }

    private String formatFixAge(Location location) {
        long elapsed = location.getElapsedRealtimeNanos();
        if (elapsed <= 0L) return "unknown";
        long ageNanos = SystemClock.elapsedRealtimeNanos() - elapsed;
        if (ageNanos < 0L) return "clock mismatch";
        return String.format(Locale.US, "%.2f s", ageNanos / 1_000_000_000d);
    }

    private String formatMeters(double meters) {
        return Double.isFinite(meters) ? String.format(Locale.US, "%.2f m", meters) : "not reported";
    }

    private boolean isMock(Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return location.isMock();
        return location.isFromMockProvider();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) return;
        if (hasFinePermission()) {
            startValidation();
        } else {
            Toast.makeText(this,
                    "Precise location is required for RockMap GPS validation.",
                    Toast.LENGTH_LONG).show();
            refreshDiagnostics();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        startValidation();
    }

    @Override protected void onResume() {
        super.onResume();
        startValidation();
    }

    @Override protected void onPause() {
        stopValidation();
        super.onPause();
    }

    @Override protected void onStop() {
        stopValidation();
        super.onStop();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
