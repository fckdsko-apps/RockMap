package com.rockmap.app.location;

/**
 * Pure source-selection policy for the map heading arrow.
 *
 * GPS bearing describes direction of travel and should win while the device is genuinely moving.
 * Sensor heading describes device orientation and should win when stationary or when GPS course is
 * too weak/stale to trust. Hysteresis prevents rapid source flapping near walking speed.
 */
final class HeadingCoursePolicy {
    static final float ENTER_COURSE_SPEED_MPS = 0.90f;
    static final float STAY_COURSE_SPEED_MPS = 0.60f;
    static final float ENTER_MAX_BEARING_ACCURACY_DEG = 35.0f;
    static final float STAY_MAX_BEARING_ACCURACY_DEG = 50.0f;
    static final float MAX_HORIZONTAL_ACCURACY_WITHOUT_BEARING_ACCURACY_M = 30.0f;
    static final float MIN_SPEED_WITHOUT_BEARING_ACCURACY_MPS = 1.10f;
    static final long MAX_COURSE_AGE_MS = 6_000L;

    private HeadingCoursePolicy() {}

    static Decision evaluate(boolean wasUsingCourse,
                             long locationAgeMs,
                             boolean hasSpeed,
                             float speedMps,
                             boolean hasBearing,
                             float bearingDeg,
                             boolean hasBearingAccuracy,
                             float bearingAccuracyDeg,
                             boolean hasHorizontalAccuracy,
                             float horizontalAccuracyM) {
        if (locationAgeMs < 0L || locationAgeMs > MAX_COURSE_AGE_MS) {
            return Decision.reject("stale-location");
        }
        if (!hasSpeed) return Decision.reject("missing-speed");
        if (!Float.isFinite(speedMps) || speedMps < 0f) {
            return Decision.reject("invalid-speed");
        }
        if (!hasBearing) return Decision.reject("missing-bearing");
        if (!Float.isFinite(bearingDeg)) return Decision.reject("invalid-bearing");

        float minimumSpeed = wasUsingCourse ? STAY_COURSE_SPEED_MPS : ENTER_COURSE_SPEED_MPS;
        if (speedMps < minimumSpeed) {
            return Decision.reject(wasUsingCourse ? "below-hold-speed" : "below-enter-speed");
        }

        if (hasBearingAccuracy) {
            if (!Float.isFinite(bearingAccuracyDeg) || bearingAccuracyDeg < 0f) {
                return Decision.reject("invalid-bearing-accuracy");
            }
            float maximumBearingAccuracy = wasUsingCourse
                    ? STAY_MAX_BEARING_ACCURACY_DEG
                    : ENTER_MAX_BEARING_ACCURACY_DEG;
            if (bearingAccuracyDeg > maximumBearingAccuracy) {
                return Decision.reject(wasUsingCourse
                        ? "bearing-accuracy-too-poor-to-hold"
                        : "bearing-accuracy-too-poor-to-enter");
            }
        } else {
            // When Android cannot quantify course accuracy, require both stronger movement and a
            // reasonably precise GPS position before preferring travel direction over the sensor.
            if (speedMps < MIN_SPEED_WITHOUT_BEARING_ACCURACY_MPS) {
                return Decision.reject("unknown-bearing-accuracy-low-speed");
            }
            if (hasHorizontalAccuracy) {
                if (!Float.isFinite(horizontalAccuracyM) || horizontalAccuracyM < 0f) {
                    return Decision.reject("invalid-horizontal-accuracy");
                }
                if (horizontalAccuracyM > MAX_HORIZONTAL_ACCURACY_WITHOUT_BEARING_ACCURACY_M) {
                    return Decision.reject("unknown-bearing-accuracy-poor-position");
                }
            }
        }

        return Decision.accept(wasUsingCourse ? "good-course-hold" : "good-course-enter");
    }

    static final class Decision {
        final boolean useCourse;
        final String reason;

        private Decision(boolean useCourse, String reason) {
            this.useCourse = useCourse;
            this.reason = reason;
        }

        static Decision accept(String reason) {
            return new Decision(true, reason);
        }

        static Decision reject(String reason) {
            return new Decision(false, reason);
        }
    }
}
