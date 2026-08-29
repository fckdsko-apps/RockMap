package com.rockmap.app.location;

import android.app.Activity;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.SystemClock;
import android.view.Surface;

public final class HeadingRepository implements SensorEventListener {
    public interface Listener {
        void onHeading(float headingDegrees);
        void onHeadingUnavailable();
    }

    private static final float FILTER_GAIN = 0.22f;
    private static final float MIN_EMIT_DELTA_DEGREES = 0.75f;
    private static final long MIN_EMIT_INTERVAL_MS = 80L;
    private static final long MAX_QUIET_INTERVAL_MS = 400L;
    private static final float MIN_COURSE_SPEED_MPS = 1.0f;
    private static final float MAX_COURSE_BEARING_ACCURACY_DEGREES = 45.0f;
    private static final int SOURCE_NONE = 0;
    private static final int SOURCE_SENSOR = 1;
    private static final int SOURCE_COURSE = 2;

    private final Activity activity;
    private final Listener listener;
    private final SensorManager sensorManager;
    private final Sensor headingSensor;
    private final float[] rotationMatrix = new float[9];
    private final float[] remappedRotationMatrix = new float[9];
    private final float[] orientation = new float[3];

    private boolean running;
    private boolean sensorRegistered;
    private boolean sensorUnreliable;
    private Location latestLocation;
    private float declinationDegrees;
    private Float lastMagneticHeading;
    private Float filteredHeading;
    private Float lastEmittedHeading;
    private long lastEmitElapsedMs;
    private int lastSource = SOURCE_NONE;
    private boolean unavailableEmitted;

    public HeadingRepository(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        sensorManager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
        Sensor sensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (sensor == null && sensorManager != null) {
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        }
        headingSensor = sensor;
    }

    public void start() {
        if (running) return;
        running = true;
        sensorUnreliable = false;
        unavailableEmitted = false;
        if (sensorManager != null && headingSensor != null) {
            sensorRegistered = sensorManager.registerListener(
                    this, headingSensor, SensorManager.SENSOR_DELAY_UI);
            if (sensorRegistered) return;
        }
        emitCourseFallbackOrUnavailable();
    }

    public void stop() {
        if (!running) return;
        running = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        sensorRegistered = false;
        lastMagneticHeading = null;
        filteredHeading = null;
        lastEmittedHeading = null;
        lastEmitElapsedMs = 0L;
        lastSource = SOURCE_NONE;
        unavailableEmitted = false;
    }

    public void updateLocation(Location location) {
        latestLocation = location == null ? null : new Location(location);
        declinationDegrees = calculateDeclination(latestLocation);
        if (!running) return;

        if (!sensorRegistered || sensorUnreliable) {
            emitCourseFallbackOrUnavailable();
            return;
        }

        if (lastMagneticHeading != null) {
            acceptHeading(toTrueHeading(lastMagneticHeading), SOURCE_SENSOR);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!running || event == null || event.sensor == null || event.sensor != headingSensor) return;
        if (sensorUnreliable) {
            emitCourseFallbackOrUnavailable();
            return;
        }

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        if (!remapForDisplay(rotationMatrix, remappedRotationMatrix)) return;
        SensorManager.getOrientation(remappedRotationMatrix, orientation);

        float magneticHeading = normalize360((float) Math.toDegrees(orientation[0]));
        lastMagneticHeading = magneticHeading;
        acceptHeading(toTrueHeading(magneticHeading), SOURCE_SENSOR);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor == null || sensor != headingSensor) return;
        sensorUnreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE;
        if (sensorUnreliable) emitCourseFallbackOrUnavailable();
    }

    private boolean remapForDisplay(float[] input, float[] output) {
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int axisX;
        int axisY;
        switch (rotation) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_180:
                axisX = SensorManager.AXIS_MINUS_X;
                axisY = SensorManager.AXIS_MINUS_Y;
                break;
            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            case Surface.ROTATION_0:
            default:
                axisX = SensorManager.AXIS_X;
                axisY = SensorManager.AXIS_Y;
                break;
        }
        return SensorManager.remapCoordinateSystem(input, axisX, axisY, output);
    }

    private float toTrueHeading(float magneticHeading) {
        return normalize360(magneticHeading + declinationDegrees);
    }

    private static float calculateDeclination(Location location) {
        if (location == null) return 0f;
        float altitudeMeters = location.hasAltitude() ? (float) location.getAltitude() : 0f;
        GeomagneticField field = new GeomagneticField(
                (float) location.getLatitude(),
                (float) location.getLongitude(),
                altitudeMeters,
                System.currentTimeMillis());
        return field.getDeclination();
    }

    private void emitCourseFallbackOrUnavailable() {
        Location location = latestLocation;
        if (location != null
                && location.hasBearing()
                && location.hasSpeed()
                && location.getSpeed() >= MIN_COURSE_SPEED_MPS
                && (!location.hasBearingAccuracy()
                    || location.getBearingAccuracyDegrees() <= MAX_COURSE_BEARING_ACCURACY_DEGREES)) {
            acceptHeading(location.getBearing(), SOURCE_COURSE);
            return;
        }
        if (!unavailableEmitted) {
            unavailableEmitted = true;
            listener.onHeadingUnavailable();
        }
    }

    private void acceptHeading(float rawHeading, int source) {
        float heading = normalize360(rawHeading);
        if (source != lastSource || filteredHeading == null) {
            filteredHeading = heading;
            lastSource = source;
        } else {
            float delta = shortestDelta(filteredHeading, heading);
            filteredHeading = normalize360(filteredHeading + delta * FILTER_GAIN);
        }

        long now = SystemClock.elapsedRealtime();
        if (lastEmittedHeading != null) {
            float deltaFromLastEmit = Math.abs(shortestDelta(lastEmittedHeading, filteredHeading));
            long elapsed = now - lastEmitElapsedMs;
            if (elapsed < MIN_EMIT_INTERVAL_MS) return;
            if (deltaFromLastEmit < MIN_EMIT_DELTA_DEGREES && elapsed < MAX_QUIET_INTERVAL_MS) return;
        }

        unavailableEmitted = false;
        lastEmittedHeading = filteredHeading;
        lastEmitElapsedMs = now;
        listener.onHeading(filteredHeading);
    }

    private static float normalize360(float degrees) {
        float normalized = degrees % 360f;
        return normalized < 0f ? normalized + 360f : normalized;
    }

    private static float shortestDelta(float fromDegrees, float toDegrees) {
        float delta = normalize360(toDegrees) - normalize360(fromDegrees);
        if (delta > 180f) delta -= 360f;
        if (delta < -180f) delta += 360f;
        return delta;
    }
}
