package com.rockmap.app.field;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class GeoMathTest {
    @Test public void shortDistanceIsReasonable() {
        GeoMath.Point a=new GeoMath.Point(39.7392,-104.9903);
        GeoMath.Point b=new GeoMath.Point(39.7492,-104.9903);
        double meters=GeoMath.distanceMeters(a,b);
        assertTrue(meters>1100d&&meters<1125d);
    }

    @Test public void polygonAreaIsPositive() {
        double area=GeoMath.polygonAreaSquareMeters(Arrays.asList(
                new GeoMath.Point(39.73,-105.00),new GeoMath.Point(39.73,-104.99),
                new GeoMath.Point(39.74,-104.99),new GeoMath.Point(39.74,-105.00)));
        assertTrue(area>900000d&&area<1100000d);
    }
}
