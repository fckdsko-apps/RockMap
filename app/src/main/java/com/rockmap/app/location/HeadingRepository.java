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

    private static final float SENSOR_FILTER_GAIN = 0.22f;
    private static final float COURSE_FILTER_GAIN = 0.65f;
    private static final float MIN_EMIT_DELTA_DEGREES = 0.75f;
    private static final long MIN_EMIT_INTERVAL_MS = 80L;
    private static final long MAX_QUIET_INTERVAL_MS = 400L;
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
    private long lastCourseSuppressedDebugElapsedMs;
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
            if (sensorRegistered) {
                // If the Activity resumes while already moving, do not wait for a magnetic sample
                // to overwrite a trustworthy GPS course. Otherwise wait for the first sensor event.
                HeadingCoursePolicy.Decision course = evaluateCourseDecision();
                logPolicyDecision("start", course);
                if (course.useCourse && latestLocation != null) {
                    acceptHeading(latestLocation.getBearing(), SOURCE_COURSE);
                }
                return;
            }
        } else {
            TourDebugLog.headingDiagnostic(activity, "HEADING_START",
                    "registered=false manager=" + (sensorManager != null)
                            + " sensor=" + sensorSummary(headingSensor));
        }
        emitBestAvailableHeading("start-no-sensor");
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
        lastCourseSuppressedDebugElapsedMs = 0L;
        lastAccuracy = Integer.MIN_VALUE;
        TourDebugLog.headingDiagnostic(activity, "HEADING_STOP", "sensor listener stopped");
    }

    public void updateLocation(Location location) {
        latestLocation = location == null ? null : new Location(location);
        declinationDegrees = calculateDeclination(latestLocation);
        HeadingCoursePolicy.Decision course = evaluateCourseDecision();
        TourDebugLog.headingDiagnostic(activity, "HEADING_GPS_CONTEXT",
                locationSummary(latestLocation)
                        + " ageMs=" + locationAgeMs(latestLocation)
                        + " declinationDeg=" + format(declinationDegrees)
                        + " running=" + running + " registered=" + sensorRegistered
                        + " unreliable=" + sensorUnreliable
                        + " courseUsable=" + course.useCourse
                        + " courseReason=" + course.reason);
        if (!running) return;
        emitBestAvailableHeading("gps-update", course);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!running || event == null || event.sensor == null || event.sensor != headingSensor) return;
        if (sensorUnreliable) {
            emitBestAvailableHeading("sensor-unreliable-event");
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

        HeadingCoursePolicy.Decision course = evaluateCourseDecision();
        if (course.useCourse && latestLocation != null) {
            // Sensor events arrive far more often than GPS fixes. Once a moving GPS course owns
            // the arrow, do not let the next magnetic sample immediately steal it back.
            if (lastCourseSuppressedDebugElapsedMs == 0L
                    || now - lastCourseSuppressedDebugElapsedMs >= 750L) {
                lastCourseSuppressedDebugElapsedMs = now;
                TourDebugLog.headingDiagnostic(activity, "HEADING_SENSOR_SUPPRESSED_BY_COURSE",
                        "sensorTrueDeg=" + format(trueHeading)
                                + " gpsCourseDeg=" + format(latestLocation.getBearing())
                                + " speedMps=" + (latestLocation.hasSpeed()
                                    ? format(latestLocation.getSpeed()) : "n/a")
                                + " reason=" + course.reason);
            }
            if (lastSource != SOURCE_COURSE) {
                logPolicyDecision("sensor-event", course);
                acceptHeading(latestLocation.getBearing(), SOURCE_COURSE);
            }
            return;
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
        if (running) emitBestAvailableHeading("sensor-accuracy-change");
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

    private void emitBestAvailableHeading(String trigger) {
        emitBestAvailableHeading(trigger, evaluateCourseDecision());
    }

    private void emitBestAvailableHeading(String trigger, HeadingCoursePolicy.Decision course) {
        logPolicyDecision(trigger, course);
        Location location = latestLocation;
        if (course.useCourse && location != null) {
            acceptHeading(location.getBearing(), SOURCE_COURSE);
            return;
        }

        if (sensorRegistered && !sensorUnreliable) {
            if (lastMagneticHeading != null) {
                acceptHeading(toTrueHeading(lastMagneticHeading), SOURCE_SENSOR);
            } else {
                TourDebugLog.headingDiagnostic(activity, "HEADING_WAITING_FOR_SENSOR",
                        "trigger=" + trigger + " courseReason=" + course.reason);
            }
            return;
        }

        if (!unavailableEmitted) {
            unavailableEmitted = true;
            TourDebugLog.headingDiagnostic(activity, "HEADING_UNAVAILABLE",
                    "trigger=" + trigger
                            + " registered=" + sensorRegistered
                            + " unreliable=" + sensorUnreliable
                            + " courseReason=" + course.reason
                            + " " + locationSummary(location));
            listener.onHeadingUnavailable();
        }
    }

    private HeadingCoursePolicy.Decision evaluateCourseDecision() {
        Location location = latestLocation;
        if (location == null) return HeadingCoursePolicy.Decision.reject("missing-location");
        return HeadingCoursePolicy.evaluate(
                lastSource == SOURCE_COURSE,
                locationAgeMs(location),
                location.hasSpeed(),
                location.hasSpeed() ? location.getSpeed() : Float.NaN,
                location.hasBearing(),
                location.hasBearing() ? location.getBearing() : Float.NaN,
                location.hasBearingAccuracy(),
                location.hasBearingAccuracy() ? location.getBearingAccuracyDegrees() : Float.NaN,
                location.hasAccuracy(),
                location.hasAccuracy() ? location.getAccuracy() : Float.NaN);
    }

    private void logPolicyDecision(String trigger, HeadingCoursePolicy.Decision course) {
        String selected;
        if (course.useCourse) selected = "gps-course";
        else if (sensorRegistered && !sensorUnreliable && lastMagneticHeading != null) selected = "sensor";
        else if (sensorRegistered && !sensorUnreliable) selected = "waiting-sensor";
        else selected = "unavailable";
        TourDebugLog.headingDiagnostic(activity, "HEADING_POLICY_DECISION",
                "trigger=" + trigger
                        + " selected=" + selected
                        + " courseReason=" + course.reason
                        + " previousSource=" + sourceName(lastSource)
                        + " ageMs=" + locationAgeMs(latestLocation)
                        + " " + locationSummary(latestLocation));
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
            float gain = source == SOURCE_COURSE ? COURSE_FILTER_GAIN : SENSOR_FILTER_GAIN;
            filteredHeading = normalize360(filteredHeading + delta * gain);
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
        TourDebugLog.headingDiagnostic(activity, "HEADING_OUTPUT",
                "source=" + sourceName(source)
                        + " rawDeg=" + format(heading)
                        + " filteredDeg=" + format(filteredHeading));
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

    private static long locationAgeMs(Location location) {
        if (location == null) return Long.MAX_VALUE;
        long elapsedNanos = location.getElapsedRealtimeNanos();
        if (elapsedNanos <= 0L) return Long.MAX_VALUE;
        long ageNanos = SystemClock.elapsedRealtimeNanos() - elapsedNanos;
        if (ageNanos < 0L) return 0L;
        return ageNanos / 1_000_000L;
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
