package com.rockmap.app.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;
import android.os.SystemClock;

import com.rockmap.app.WholeAppDiagnostics;

import java.util.function.Consumer;

public final class LocationRepository implements LocationListener {
    private static final long MAX_FRESH_FIX_AGE_MS = 30_000L;
    public interface Listener {
        void onLocation(Location location);
        void onLocationError(String message);
    }

    private final Context context;
    private final LocationManager manager;
    private final Listener listener;
    private Location latest;

    public LocationRepository(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public boolean hasCoarsePermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean hasFinePermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isGpsEnabled() {
        try {
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (RuntimeException ex) {
            WholeAppDiagnostics.event("LOCATION_CAPABILITY",
                    "state=gps_provider_check_failed error=" + WholeAppDiagnostics.errorSummary(ex));
            return false;
        }
    }

    public Location getLatest() {
        return latest;
    }

    public boolean isRecent(Location location) {
        if (location == null) return false;
        long elapsed = location.getElapsedRealtimeNanos();
        if (elapsed <= 0L) return false;
        long ageNanos = SystemClock.elapsedRealtimeNanos() - elapsed;
        return ageNanos >= 0L && ageNanos <= MAX_FRESH_FIX_AGE_MS * 1_000_000L;
    }

    public void start() {
        boolean coarse = hasCoarsePermission();
        boolean fine = hasFinePermission();
        boolean gps = isGpsEnabled();
        WholeAppDiagnostics.event("LOCATION_UPDATES",
                "state=request coarse=" + coarse + " fine=" + fine + " gpsEnabled=" + gps);
        if (!coarse) {
            WholeAppDiagnostics.permission(context, Manifest.permission.ACCESS_COARSE_LOCATION,
                    "coarse_location", "continuous_location_start");
            return;
        }
        if (!gps) {
            WholeAppDiagnostics.event("LOCATION_UPDATES", "state=rejected reason=gps_disabled");
            listener.onLocationError("GPS provider is disabled.");
            return;
        }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 2f, this, Looper.getMainLooper());
            WholeAppDiagnostics.event("LOCATION_UPDATES",
                    "state=registered provider=gps minTimeMs=3000 minDistanceM=2");
        } catch (SecurityException ex) {
            WholeAppDiagnostics.event("LOCATION_UPDATES",
                    "state=error reason=permission_revoked error=" + WholeAppDiagnostics.errorSummary(ex));
            listener.onLocationError("Location permission was revoked.");
        } catch (RuntimeException ex) {
            WholeAppDiagnostics.event("LOCATION_UPDATES",
                    "state=error reason=request_failed error=" + WholeAppDiagnostics.errorSummary(ex));
            listener.onLocationError("GPS update failed: " + ex.getMessage());
        }
    }

    public void stop() {
        try {
            manager.removeUpdates(this);
            WholeAppDiagnostics.event("LOCATION_UPDATES", "state=stopped");
        } catch (RuntimeException ex) {
            WholeAppDiagnostics.event("LOCATION_UPDATES",
                    "state=stop_failed error=" + WholeAppDiagnostics.errorSummary(ex));
        }
    }

    public void requestCurrent(Consumer<Location> onSuccess, Consumer<String> onError) {
        if (!hasCoarsePermission() && !hasFinePermission()) {
            WholeAppDiagnostics.event("LOCATION_CURRENT", "state=rejected reason=no_location_permission");
            onError.accept("Location permission is required.");
            return;
        }
        requestCurrentFromGps(onSuccess, onError, false);
    }

    public void requestFreshPrecise(Consumer<Location> onSuccess, Consumer<String> onError) {
        if (!hasFinePermission()) {
            WholeAppDiagnostics.permission(context, Manifest.permission.ACCESS_FINE_LOCATION,
                    "fine_location", "fresh_precise_location");
            WholeAppDiagnostics.event("LOCATION_CURRENT", "state=rejected reason=no_precise_permission");
            onError.accept("Precise location permission is required to save a field waypoint.");
            return;
        }
        requestCurrentFromGps(onSuccess, onError, true);
    }

    private void requestCurrentFromGps(Consumer<Location> onSuccess, Consumer<String> onError, boolean precise) {
        final long diagnostic = WholeAppDiagnostics.start("location", "current_fix",
                "precise=" + precise + " sdk=" + Build.VERSION.SDK_INT);
        if (!isGpsEnabled()) {
            WholeAppDiagnostics.failure(diagnostic, "location", "current_fix",
                    "precise=" + precise + " reason=gps_disabled", null);
            onError.accept("GPS is disabled. Enable GPS and try again.");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(LocationManager.GPS_PROVIDER, new CancellationSignal(),
                        context.getMainExecutor(), location -> {
                            if (location == null) {
                                WholeAppDiagnostics.failure(diagnostic, "location", "current_fix",
                                        "precise=" + precise + " reason=null_fix", null);
                                onError.accept("GPS could not obtain a current fix.");
                            } else if (!isUsableLocation(location, precise)) {
                                WholeAppDiagnostics.failure(diagnostic, "location", "current_fix",
                                        "precise=" + precise + " reason=stale_or_invalid accuracy="
                                                + (location.hasAccuracy() ? location.getAccuracy() : -1f), null);
                                onError.accept(precise
                                        ? "GPS fix was stale or invalid. Keep the phone where it can see the sky and try again."
                                        : "GPS returned an invalid current fix. Try again.");
                            } else {
                                latest = location;
                                WholeAppDiagnostics.success(diagnostic, "location", "current_fix",
                                        "precise=" + precise + " accuracy="
                                                + (location.hasAccuracy() ? location.getAccuracy() : -1f));
                                onSuccess.accept(location);
                            }
                        });
            } else {
                LocationListener once = new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        if (!isUsableLocation(location, precise)) {
                            WholeAppDiagnostics.failure(diagnostic, "location", "current_fix",
                                    "precise=" + precise + " reason=stale_or_invalid", null);
                            onError.accept(precise
                                    ? "GPS fix was stale or invalid. Keep the phone where it can see the sky and try again."
                                    : "GPS returned an invalid current fix. Try again.");
                            try { manager.removeUpdates(this); } catch (RuntimeException ignored) {}
                            return;
                        }
                        latest = location;
                        WholeAppDiagnostics.success(diagnostic, "location", "current_fix",
                                "precise=" + precise + " accuracy="
                                        + (location.hasAccuracy() ? location.getAccuracy() : -1f));
                        onSuccess.accept(location);
                        try { manager.removeUpdates(this); } catch (RuntimeException ignored) {}
                    }
                    @Override public void onProviderDisabled(String provider) {
                        WholeAppDiagnostics.failure(diagnostic, "location", "current_fix",
                                "precise=" + precise + " reason=gps_disabled_mid_request", null);
                        onError.accept("GPS was disabled before a current fix was obtained.");
                    }
                    @Override public void onProviderEnabled(String provider) {}
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                };
                manager.requestSingleUpdate(LocationManager.GPS_PROVIDER, once, Looper.getMainLooper());
            }
        } catch (SecurityException ex) {
            WholeAppDiagnostics.failure(diagnostic, "location", "current_fix",
                    "precise=" + precise + " reason=permission_revoked", ex);
            onError.accept(precise ? "Precise location permission was revoked." : "Location permission was revoked.");
        } catch (RuntimeException ex) {
            WholeAppDiagnostics.failure(diagnostic, "location", "current_fix",
                    "precise=" + precise + " reason=request_failed", ex);
            onError.accept("GPS fix failed: " + ex.getMessage());
        }
    }

    private boolean isUsableLocation(Location location, boolean requireFresh) {
        if (location == null) return false;
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90d || latitude > 90d || longitude < -180d || longitude > 180d) {
            return false;
        }
        if (location.hasAccuracy() && (!Float.isFinite(location.getAccuracy()) || location.getAccuracy() < 0f)) {
            return false;
        }
        return !requireFresh || isRecent(location);
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!isUsableLocation(location, false)) return;
        latest = location;
        listener.onLocation(location);
    }

    @Override public void onProviderDisabled(String provider) {
        WholeAppDiagnostics.event("LOCATION_PROVIDER", "provider=" + provider + " state=disabled");
        listener.onLocationError("GPS provider is disabled.");
    }
    @Override public void onProviderEnabled(String provider) {
        WholeAppDiagnostics.event("LOCATION_PROVIDER", "provider=" + provider + " state=enabled");
    }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
}
