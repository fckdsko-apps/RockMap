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
        if (!hasCoarsePermission()) return;
        if (!isGpsEnabled()) {
            listener.onLocationError("GPS provider is disabled.");
            return;
        }
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 2f, this, Looper.getMainLooper());
        } catch (SecurityException ex) {
            listener.onLocationError("Location permission was revoked.");
        } catch (RuntimeException ex) {
            listener.onLocationError("GPS update failed: " + ex.getMessage());
        }
    }

    public void stop() {
        try {
            manager.removeUpdates(this);
        } catch (RuntimeException ignored) {
        }
    }

    public void requestCurrent(Consumer<Location> onSuccess, Consumer<String> onError) {
        if (!hasCoarsePermission() && !hasFinePermission()) {
            onError.accept("Location permission is required.");
            return;
        }
        requestCurrentFromGps(onSuccess, onError, false);
    }

    public void requestFreshPrecise(Consumer<Location> onSuccess, Consumer<String> onError) {
        if (!hasFinePermission()) {
            onError.accept("Precise location permission is required to save a field waypoint.");
            return;
        }
        requestCurrentFromGps(onSuccess, onError, true);
    }

    private void requestCurrentFromGps(Consumer<Location> onSuccess, Consumer<String> onError, boolean precise) {
        if (!isGpsEnabled()) {
            onError.accept("GPS is disabled. Enable GPS and try again.");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(LocationManager.GPS_PROVIDER, new CancellationSignal(),
                        context.getMainExecutor(), location -> {
                            if (location == null) {
                                onError.accept("GPS could not obtain a current fix.");
                            } else if (!isUsableLocation(location, precise)) {
                                onError.accept(precise
                                        ? "GPS fix was stale or invalid. Keep the phone where it can see the sky and try again."
                                        : "GPS returned an invalid current fix. Try again.");
                            } else {
                                latest = location;
                                onSuccess.accept(location);
                            }
                        });
            } else {
                LocationListener once = new LocationListener() {
                    @Override public void onLocationChanged(Location location) {
                        if (!isUsableLocation(location, precise)) {
                            onError.accept(precise
                                    ? "GPS fix was stale or invalid. Keep the phone where it can see the sky and try again."
                                    : "GPS returned an invalid current fix. Try again.");
                            try { manager.removeUpdates(this); } catch (RuntimeException ignored) {}
                            return;
                        }
                        latest = location;
                        onSuccess.accept(location);
                        try { manager.removeUpdates(this); } catch (RuntimeException ignored) {}
                    }
                    @Override public void onProviderDisabled(String provider) {
                        onError.accept("GPS was disabled before a current fix was obtained.");
                    }
                    @Override public void onProviderEnabled(String provider) {}
                    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                };
                manager.requestSingleUpdate(LocationManager.GPS_PROVIDER, once, Looper.getMainLooper());
            }
        } catch (SecurityException ex) {
            onError.accept(precise ? "Precise location permission was revoked." : "Location permission was revoked.");
        } catch (RuntimeException ex) {
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
        listener.onLocationError("GPS provider is disabled.");
    }
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
}
