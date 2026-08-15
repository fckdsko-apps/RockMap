package com.rockmap.app.gps;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class GpsValidationStatsTest {
    @Test
    public void emptySummaryIsExplicitlyEmpty() {
        GpsValidationStats.Summary summary = GpsValidationStats.summarize(Collections.emptyList(), null, null);
        assertEquals(0, summary.sampleCount);
        assertTrue(Double.isNaN(summary.medianLatitude));
        assertTrue(Double.isNaN(summary.referenceErrorMeters));
    }

    @Test
    public void stationarySamplesProduceSmallScatterAndReferenceError() {
        double lat = 39.739236;
        double lon = -104.990251;
        GpsValidationStats.Summary summary = GpsValidationStats.summarize(Arrays.asList(
                new GpsValidationStats.Sample(lat, lon, 4f),
                new GpsValidationStats.Sample(lat + 0.00001, lon, 5f),
                new GpsValidationStats.Sample(lat - 0.00001, lon, 6f),
                new GpsValidationStats.Sample(lat, lon + 0.00001, 5f),
                new GpsValidationStats.Sample(lat, lon - 0.00001, 4f)
        ), lat, lon);

        assertEquals(5, summary.sampleCount);
        assertEquals(lat, summary.medianLatitude, 1e-9);
        assertEquals(lon, summary.medianLongitude, 1e-9);
        assertEquals(5d, summary.medianReportedAccuracyMeters, 0.001d);
        assertTrue(summary.p95ScatterMeters < 2d);
        assertTrue(summary.referenceErrorMeters < 0.05d);
    }

    @Test
    public void distanceFunctionIsSymmetric() {
        double a = GpsValidationStats.distanceMeters(39.7, -105.0, 39.8, -105.1);
        double b = GpsValidationStats.distanceMeters(39.8, -105.1, 39.7, -105.0);
        assertEquals(a, b, 1e-6);
        assertTrue(a > 10_000d);
    }
}
