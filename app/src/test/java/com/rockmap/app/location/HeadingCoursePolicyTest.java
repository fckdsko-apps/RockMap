package com.rockmap.app.location;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HeadingCoursePolicyTest {
    @Test public void reliableWalkingCourseEntersGpsMode() {
        HeadingCoursePolicy.Decision decision = HeadingCoursePolicy.evaluate(
                false, 500L, true, 1.35f, true, 274f,
                true, 8f, true, 5f);
        assertTrue(decision.useCourse);
    }

    @Test public void stationaryFixDoesNotOverrideSensor() {
        HeadingCoursePolicy.Decision decision = HeadingCoursePolicy.evaluate(
                false, 500L, true, 0.10f, true, 274f,
                true, 5f, true, 4f);
        assertFalse(decision.useCourse);
    }

    @Test public void hysteresisKeepsCourseAtSlowWalkingSpeed() {
        HeadingCoursePolicy.Decision decision = HeadingCoursePolicy.evaluate(
                true, 500L, true, 0.72f, true, 274f,
                true, 20f, true, 7f);
        assertTrue(decision.useCourse);
    }

    @Test public void sameSlowSpeedCannotInitiallyEnterCourseMode() {
        HeadingCoursePolicy.Decision decision = HeadingCoursePolicy.evaluate(
                false, 500L, true, 0.72f, true, 274f,
                true, 20f, true, 7f);
        assertFalse(decision.useCourse);
    }

    @Test public void poorBearingAccuracyDoesNotOverrideSensor() {
        HeadingCoursePolicy.Decision decision = HeadingCoursePolicy.evaluate(
                false, 500L, true, 1.6f, true, 274f,
                true, 70f, true, 8f);
        assertFalse(decision.useCourse);
    }

    @Test public void heldCourseToleratesModerateBearingAccuracy() {
        HeadingCoursePolicy.Decision decision = HeadingCoursePolicy.evaluate(
                true, 500L, true, 1.0f, true, 274f,
                true, 45f, true, 8f);
        assertTrue(decision.useCourse);
    }

    @Test public void staleCourseNeverOverridesSensor() {
        HeadingCoursePolicy.Decision decision = HeadingCoursePolicy.evaluate(
                true, HeadingCoursePolicy.MAX_COURSE_AGE_MS + 1L,
                true, 2.0f, true, 274f,
                true, 5f, true, 4f);
        assertFalse(decision.useCourse);
    }

    @Test public void missingBearingCannotEnterCourseMode() {
        HeadingCoursePolicy.Decision decision = HeadingCoursePolicy.evaluate(
                false, 500L, true, 1.5f, false, Float.NaN,
                false, Float.NaN, true, 5f);
        assertFalse(decision.useCourse);
    }

    @Test public void unknownBearingAccuracyRequiresStrongerMovement() {
        HeadingCoursePolicy.Decision slow = HeadingCoursePolicy.evaluate(
                false, 500L, true, 1.0f, true, 274f,
                false, Float.NaN, true, 5f);
        HeadingCoursePolicy.Decision faster = HeadingCoursePolicy.evaluate(
                false, 500L, true, 1.4f, true, 274f,
                false, Float.NaN, true, 5f);
        assertFalse(slow.useCourse);
        assertTrue(faster.useCourse);
    }

    @Test public void unknownBearingAccuracyRejectsPoorGpsPosition() {
        HeadingCoursePolicy.Decision decision = HeadingCoursePolicy.evaluate(
                false, 500L, true, 1.5f, true, 274f,
                false, Float.NaN, true, 55f);
        assertFalse(decision.useCourse);
    }
}
