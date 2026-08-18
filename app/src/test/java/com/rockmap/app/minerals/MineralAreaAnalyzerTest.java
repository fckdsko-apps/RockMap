package com.rockmap.app.minerals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class MineralAreaAnalyzerTest {
    private static MineralRecord record(String id, double lat, double lon,
                                        List<String> materials, List<String> commodities,
                                        String sourceCode) {
        return new MineralRecord(
                id, "Site " + id, lat, lon, "", "",
                materials, commodities,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                sourceCode, "evidence", "point", "source", "reliability", "");
    }

    @Test
    public void analyzeInventoriesOnlyExplicitTermsInsideBounds() {
        MineralSearchEngine.Bounds bounds = new MineralSearchEngine.Bounds(40, -104, 39, -106);
        List<MineralRecord> records = Arrays.asList(
                record("a", 39.5, -105.0,
                        Arrays.asList("Quartz", "Gold"), Arrays.asList("Gold"), "MRDS"),
                record("b", 39.6, -105.1,
                        Collections.emptyList(), Arrays.asList("Gold"), "CGS_DISTRICTS"),
                record("c", 38.0, -105.0,
                        Collections.singletonList("Beryl"), Collections.emptyList(), "MRDS"));

        MineralAreaAnalyzer.AnalysisResult result = MineralAreaAnalyzer.analyze(records, bounds);

        assertEquals(2, result.recordsInArea);
        assertEquals(2, result.recordsWithExplicitMineralTerms);
        assertEquals(2, result.minerals.size());
        assertEquals("Gold", result.minerals.get(0).displayName);
        assertEquals(2, result.minerals.get(0).recordCount);
        assertEquals(1, result.minerals.get(0).materialRecordCount);
        assertEquals(1, result.minerals.get(0).commodityOnlyRecordCount);
        assertEquals("Quartz", result.minerals.get(1).displayName);
    }

    @Test
    public void materialAndCommodityOnSameRecordAreCountedOnce() {
        MineralSearchEngine.Bounds bounds = new MineralSearchEngine.Bounds(40, -104, 39, -106);
        MineralRecord record = record("a", 39.5, -105.0,
                Collections.singletonList("Fluorite"), Collections.singletonList("fluorite"), "MRDS");

        MineralAreaAnalyzer.AnalysisResult result = MineralAreaAnalyzer.analyze(
                Collections.singletonList(record), bounds);

        assertEquals(1, result.minerals.size());
        assertEquals(1, result.minerals.get(0).recordCount);
        assertEquals(1, result.minerals.get(0).materialRecordCount);
        assertEquals(0, result.minerals.get(0).commodityOnlyRecordCount);
    }

    @Test
    public void evidenceHeatmapUsesExactExplicitTermAndWeightsSourceConservatively() {
        MineralSearchEngine.Bounds bounds = new MineralSearchEngine.Bounds(40, -104, 39, -106);
        MineralRecord direct = record("a", 39.5, -105.0,
                Collections.singletonList("Gold"), Collections.emptyList(), "MRDS");
        MineralRecord district = record("b", 39.6, -105.1,
                Collections.emptyList(), Collections.singletonList("Gold"), "CGS_DISTRICTS");
        MineralRecord rockOnly = new MineralRecord(
                "c", "Gold Hill", 39.7, -105.2, "", "",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.singletonList("gold-bearing schist"),
                "MRDS", "evidence", "point", "source", "reliability", "");

        List<MineralAreaAnalyzer.EvidencePoint> points = MineralAreaAnalyzer.evidenceFor(
                Arrays.asList(direct, district, rockOnly), bounds, "gold");

        assertEquals(2, points.size());
        assertEquals("a", points.get(0).record.id);
        assertTrue(points.get(0).weight > points.get(1).weight);
        assertTrue(points.get(0).reason.startsWith("area mineral/material:"));
        assertTrue(points.get(1).reason.startsWith("area commodity:"));
    }

    @Test
    public void genericPlaceholdersAreIgnored() {
        MineralSearchEngine.Bounds bounds = new MineralSearchEngine.Bounds(40, -104, 39, -106);
        MineralRecord record = record("a", 39.5, -105.0,
                Arrays.asList("Unknown", "None", "Quartz"), Arrays.asList("Other", "Ore"), "MRDS");

        MineralAreaAnalyzer.AnalysisResult result = MineralAreaAnalyzer.analyze(
                Collections.singletonList(record), bounds);

        assertEquals(1, result.minerals.size());
        assertEquals("Quartz", result.minerals.get(0).displayName);
    }

    @Test
    public void emptyAreaIsSafeAndDoesNotInventAbsence() {
        MineralSearchEngine.Bounds bounds = new MineralSearchEngine.Bounds(40, -104, 39, -106);
        MineralRecord outside = record("a", 38.0, -105.0,
                Collections.singletonList("Topaz"), Collections.emptyList(), "MRDS");

        MineralAreaAnalyzer.AnalysisResult result = MineralAreaAnalyzer.analyze(
                Collections.singletonList(outside), bounds);

        assertEquals(0, result.recordsInArea);
        assertEquals(0, result.recordsWithExplicitMineralTerms);
        assertTrue(result.minerals.isEmpty());
        assertFalse(result.minerals.iterator().hasNext());
    }
}
