package com.rockmap.app.trips;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public class TripExportTest {
    @Test
    public void exportsEscapeUserTextAndPreserveCoordinates() {
        TripEntity trip = new TripEntity("Antero \"weekend\"", "Aug 29-30", "A&B", 1L, 1L);
        TripItemEntity item = new TripItemEntity(
                1L, "Peak, north", "Peak", "Chaffee & Lake", 38.6741, -106.2469,
                "line 1\nline 2", "place", "place:mount-antero", 0, 1L);

        String json = TripExport.geoJson(trip, Arrays.asList(item));
        assertTrue(json.contains("Antero \\\"weekend\\\""));
        assertTrue(json.contains("-106.2469000"));

        String gpx = TripExport.gpx(trip, Arrays.asList(item));
        assertTrue(gpx.contains("A&amp;B") || gpx.contains("Chaffee &amp; Lake"));
        assertTrue(gpx.contains("lat=\"38.6741000\""));

        String csv = TripExport.csv(trip, Arrays.asList(item));
        assertTrue(csv.contains("\"Peak, north\""));
        assertTrue(csv.contains("\"Antero \"\"weekend\"\"\""));
    }
}
