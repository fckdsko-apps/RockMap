package com.rockmap.app.field;

import org.junit.Test;
import static org.junit.Assert.*;

public class CoordinateFormatsTest {
    @Test public void denverUsesUtmZone13() {
        CoordinateFormats.Utm utm=CoordinateFormats.toUtm(39.7392,-104.9903);
        assertEquals(13,utm.zone);
        assertEquals('S',utm.band);
        assertTrue(utm.easting>490000d&&utm.easting<510000d);
        assertTrue(utm.northing>4300000d&&utm.northing<4500000d);
    }

    @Test public void mgrsIncludesZoneAndBand() {
        assertTrue(CoordinateFormats.mgrs(39.7392,-104.9903).startsWith("13S "));
    }
}
