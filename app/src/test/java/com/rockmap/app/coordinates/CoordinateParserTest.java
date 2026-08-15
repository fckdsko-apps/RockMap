package com.rockmap.app.coordinates;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CoordinateParserTest {
    private static final double EPS = 1e-6;

    @Test
    public void parsesDecimalCommaPair() {
        CoordinateParser.Result result = CoordinateParser.parse("39.290719, -106.212474");
        assertEquals(39.290719, result.latitude, EPS);
        assertEquals(-106.212474, result.longitude, EPS);
    }

    @Test
    public void parsesDecimalWhitespacePair() {
        CoordinateParser.Result result = CoordinateParser.parse("39.290719 -106.212474");
        assertEquals(39.290719, result.latitude, EPS);
        assertEquals(-106.212474, result.longitude, EPS);
    }

    @Test
    public void parsesDmsPair() {
        CoordinateParser.Result result = CoordinateParser.parse("39°17'26.6\"N 106°12'44.9\"W");
        assertEquals(39.2907222, result.latitude, EPS);
        assertEquals(-106.2124722, result.longitude, EPS);
    }

    @Test
    public void parsesDegreeMinutePairWithUnicodePrime() {
        CoordinateParser.Result result = CoordinateParser.parse("39°17.443′ N, 106°12.748′ W");
        assertEquals(39.2907167, result.latitude, EPS);
        assertEquals(-106.2124667, result.longitude, EPS);
    }

    @Test
    public void parsesDecimalHemispherePair() {
        CoordinateParser.Result result = CoordinateParser.parse("39.290719 N, 106.212474 W");
        assertEquals(39.290719, result.latitude, EPS);
        assertEquals(-106.212474, result.longitude, EPS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLatitudeOutOfRange() {
        CoordinateParser.parse("95, -106");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMinutesAtSixty() {
        CoordinateParser.parse("39°60'0\"N 106°12'0\"W");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAmbiguousBareDms() {
        CoordinateParser.parse("39 17 26.6 106 12 44.9");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsConflictingSignAndHemisphere() {
        CoordinateParser.parse("-39 N, 106 W");
    }
}
