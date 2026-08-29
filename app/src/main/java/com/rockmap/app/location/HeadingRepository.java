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

import com.rockmap.app.TourDebugLog;

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
    private long lastSensorDebugElapsedMs;
    private int lastAccuracy = Integer.MIN_VALUE;

    public HeadingRepository(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        sensorManager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
        Sensor sensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (sensor == null && sensorManager != null) {
            sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        }
        headingSensor = sensor;
        TourDebugLog.headingDiagnostic(activity, "HEADING_INIT",
                "manager=" + (sensorManager != null)
                        + " sensor=" + sensorSummary(headingSensor));
    }

    public void start() {
        if (running) return;
        running = true;
        sensorUnreliable = false;
        unavailableEmitted = false;
        if (sensorManager != null && headingSensor != null) {
            sensorRegistered = sensorManager.registerListener(
                    this, headingSensor, SensorManager.SENSOR_DELAY_UI);
            TourDebugLog.headingDiagnostic(activity, "HEADING_START",
                    "registered=" + sensorRegistered + " sensor=" + sensorSummary(headingSensor));
            if (sensorRegistered) return;
        } else {
            TourDebugLog.headingDiagnostic(activity, "HEADING_START",
                    "registered=false manager=" + (sensorManager != null)
                            + " sensor=" + sensorSummary(headingSensor));
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
        lastSensorDebugElapsedMs = 0L;
        lastAccuracy = Integer.MIN_VALUE;
        TourDebugLog.headingDiagnostic(activity, "HEADING_STOP", "sensor listener stopped");
    }

    public void updateLocation(Location location) {
        latestLocation = location == null ? null : new Location(location);
        declinationDegrees = calculateDeclination(latestLocation);
        TourDebugLog.headingDiagnostic(activity, "HEADING_GPS_CONTEXT",
                locationSummary(latestLocation) + " declinationDeg=" + format(declinationDegrees)
                        + " running=" + running + " registered=" + sensorRegistered
                        + " unreliable=" + sensorUnreliable);
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
        float trueHeading = toTrueHeading(magneticHeading);
        long now = SystemClock.elapsedRealtime();
        if (lastSensorDebugElapsedMs == 0L || now - lastSensorDebugElapsedMs >= 750L) {
            lastSensorDebugElapsedMs = now;
            TourDebugLog.headingDiagnostic(activity, "HEADING_SENSOR_SAMPLE",
                    "accuracy=" + event.accuracy
                            + " magneticDeg=" + format(magneticHeading)
                            + " declinationDeg=" + format(declinationDegrees)
                            + " trueDeg=" + format(trueHeading)
                            + " displayRotation=" + activity.getWindowManager().getDefaultDisplay().getRotation());
        }
        acceptHeading(trueHeading, SOURCE_SENSOR);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor == null || sensor != headingSensor) return;
        sensorUnreliable = accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE;
        if (accuracy != lastAccuracy) {
            lastAccuracy = accuracy;
            TourDebugLog.headingDiagnostic(activity, "HEADING_ACCURACY",
                    "accuracy=" + accuracy + " unreliable=" + sensorUnreliable
                            + " sensor=" + sensorSummary(sensor));
        }
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
            TourDebugLog.headingDiagnostic(activity, "HEADING_COURSE_FALLBACK",
                    locationSummary(location));
            acceptHeading(location.getBearing(), SOURCE_COURSE);
            return;
        }
        if (!unavailableEmitted) {
            unavailableEmitted = true;
            TourDebugLog.headingDiagnostic(activity, "HEADING_UNAVAILABLE",
                    "registered=" + sensorRegistered + " unreliable=" + sensorUnreliable
                            + " " + locationSummary(location));
            listener.onHeadingUnavailable();
        }
    }

    private void acceptHeading(float rawHeading, int source) {
        float heading = normalize360(rawHeading);
        boolean sourceChanged = source != lastSource || filteredHeading == null;
        if (sourceChanged) {
            filteredHeading = heading;
            lastSource = source;
            TourDebugLog.headingDiagnostic(activity, "HEADING_SOURCE",
                    "source=" + sourceName(source) + " headingDeg=" + format(heading));
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

    private static String sourceName(int source) {
        if (source == SOURCE_SENSOR) return "sensor";
        if (source == SOURCE_COURSE) return "gps-course";
        return "none";
    }

    private static String sensorSummary(Sensor sensor) {
        if (sensor == null) return "none";
        return sensor.getName() + "/type=" + sensor.getType()
                + "/vendor=" + sensor.getVendor()
                + "/resolution=" + format(sensor.getResolution())
                + "/powerMa=" + format(sensor.getPower());
    }

    private static String locationSummary(Location location) {
        if (location == null) return "location=none";
        return "provider=" + location.getProvider()
                + " accuracyM=" + (location.hasAccuracy() ? format(location.getAccuracy()) : "n/a")
                + " speedMps=" + (location.hasSpeed() ? format(location.getSpeed()) : "n/a")
                + " bearingDeg=" + (location.hasBearing() ? format(location.getBearing()) : "n/a")
                + " bearingAccuracyDeg=" + (location.hasBearingAccuracy()
                    ? format(location.getBearingAccuracyDegrees()) : "n/a");
    }

    private static String format(float value) {
        return Float.isFinite(value) ? String.format(java.util.Locale.US, "%.2f", value) : "n/a";
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
