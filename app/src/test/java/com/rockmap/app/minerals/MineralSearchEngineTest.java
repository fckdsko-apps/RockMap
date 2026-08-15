package com.rockmap.app.minerals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MineralSearchEngineTest {
    private static MineralRecord record(String id, String name, List<String> materials,
                                        List<String> commodities, List<String> rocks) {
        return recordAt(id, name, 39.0, -106.0, materials, commodities, rocks);
    }

    private static MineralRecord recordAt(String id, String name, double lat, double lon,
                                          List<String> materials, List<String> commodities,
                                          List<String> rocks) {
        return new MineralRecord(id, name, lat, lon, "Occurrence", "C",
                materials, commodities, Collections.emptyList(), Collections.emptyList(), rocks);
    }

    @Test public void mineralMatchOutranksCommodityAndRockContext() {
        List<MineralRecord> records = Arrays.asList(
                record("1", "Fluorite prospect", Arrays.asList("Fluorite"), Arrays.asList("Fluorspar"), Collections.emptyList()),
                record("2", "Other site", Collections.emptyList(), Collections.emptyList(), Arrays.asList("fluorite-bearing rock")));
        MineralSearchEngine.SearchResult result = MineralSearchEngine.search(records, "fluorite", 100);
        assertEquals(2, result.totalMatches);
        assertEquals("1", result.hits.get(0).record.id);
        assertTrue(result.hits.get(0).reason.startsWith("mineral/material"));
    }

    @Test public void aquamarineFallsBackToBerylOnlyWhenNeeded() {
        List<MineralRecord> records = Collections.singletonList(
                record("1", "Pegmatite", Arrays.asList("Beryl"), Collections.emptyList(), Arrays.asList("Pegmatite")));
        MineralSearchEngine.SearchResult result = MineralSearchEngine.search(records, "aquamarine", 100);
        assertEquals("beryl", result.effectiveQuery);
        assertEquals(1, result.totalMatches);
        assertTrue(result.aliasNote.contains("parent-mineral"));
    }

    @Test public void multiwordMineralSearchMatchesTokenOrderVariants() {
        List<MineralRecord> records = Collections.singletonList(
                record("1", "Crystal locality", Arrays.asList("Quartz, Smoky"), Collections.emptyList(), Collections.emptyList()));
        MineralSearchEngine.SearchResult result = MineralSearchEngine.search(records, "smoky quartz", 100);
        assertEquals(1, result.totalMatches);
        assertEquals("1", result.hits.get(0).record.id);
    }

    @Test public void zeroLimitReturnsEveryMatch() {
        List<MineralRecord> records = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            records.add(record(Integer.toString(i), "Fluorite " + i,
                    Collections.singletonList("Fluorite"), Collections.emptyList(), Collections.emptyList()));
        }
        MineralSearchEngine.SearchResult result = MineralSearchEngine.search(records, "fluorite", 0);
        assertEquals(600, result.totalMatches);
        assertEquals(600, result.hits.size());
    }

    @Test public void mapBoundsFilterBeforeGemAliasFallback() {
        List<MineralRecord> records = Arrays.asList(
                recordAt("outside-exact", "Aquamarine locality", 40.5, -105.0,
                        Collections.singletonList("Aquamarine"), Collections.emptyList(), Collections.emptyList()),
                recordAt("inside-parent", "Beryl pegmatite", 38.5, -106.0,
                        Collections.singletonList("Beryl"), Collections.emptyList(), Collections.emptyList()));
        MineralSearchEngine.Bounds bounds = new MineralSearchEngine.Bounds(39.0, -105.5, 38.0, -106.5);
        MineralSearchEngine.SearchResult result = MineralSearchEngine.search(records, "aquamarine", 0, bounds);
        assertEquals("beryl", result.effectiveQuery);
        assertEquals(1, result.hits.size());
        assertEquals("inside-parent", result.hits.get(0).record.id);
    }


    @Test public void officialAmazoniteExactMatchPreventsParentFallback() {
        MineralRecord exact = new MineralRecord(
                "official-amazonite", "Crystal Peak locality", 38.99, -105.29, "", "",
                Collections.singletonList("Amazonite"), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.singletonList("Miarolitic Pegmatite"),
                "USGS_PUB_TEST", "Published geologic mineral locality",
                "Area reference point", "U.S. Geological Survey publication", "test");
        MineralRecord parent = record("mrds-microcline", "Microcline occurrence",
                Collections.singletonList("Microcline"), Collections.emptyList(), Collections.emptyList());
        MineralSearchEngine.SearchResult result = MineralSearchEngine.search(
                Arrays.asList(parent, exact), "amazonite", 0);
        assertEquals("amazonite", result.effectiveQuery);
        assertTrue(result.aliasNote.isEmpty());
        assertEquals(1, result.totalMatches);
        assertEquals("official-amazonite", result.hits.get(0).record.id);
    }

    @Test public void directOccurrenceRanksAheadOfBroadDistrictEvidence() {
        MineralRecord district = new MineralRecord(
                "district-1", "Test District", 39.0, -106.0, "", "",
                Collections.singletonList("Fluorite"), Collections.emptyList(),
                Collections.singletonList("Test District"), Collections.emptyList(), Collections.emptyList(),
                "CGS_DISTRICTS", "District mineralogy (broad-area evidence)",
                "Display point represents an entire approximate district polygon.",
                "CGS ON-007-08D Historic Districts",
                "District evidence; boundaries are subjective, approximate 1:150,000 areas.", "");
        MineralRecord occurrence = record("mrds-1", "Fluorite Prospect",
                Collections.singletonList("Fluorite"), Collections.emptyList(), Collections.emptyList());
        MineralSearchEngine.SearchResult result = MineralSearchEngine.search(
                Arrays.asList(district, occurrence), "fluorite", 0);
        assertEquals("mrds-1", result.hits.get(0).record.id);
        assertEquals("district-1", result.hits.get(1).record.id);
    }

    @Test public void expandedEvidenceKeepsSourceReliabilityAndGenericAliasWording() {
        MineralRecord evidence = new MineralRecord(
                "mas-1", "Historic Beryl Property", 39.1, -106.1, "PAST PRODUCER", "",
                Collections.singletonList("Beryl"), Collections.singletonList("Beryllium"),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                "USGS_MAS", "Historic mine/mineral property",
                "Historic point; precision varies", "USGS MAS/MILS OFR 03-090",
                "Historic site data; location and status may be approximate or outdated.",
                "Primary commodity/site record; not mineral-species proof.");
        MineralSearchEngine.SearchResult result = MineralSearchEngine.search(
                Collections.singletonList(evidence), "aquamarine", 0);
        assertEquals("beryl", result.effectiveQuery);
        assertTrue(result.aliasNote.contains("indexed evidence"));
        assertTrue(!result.aliasNote.contains("MRDS"));
        assertEquals("USGS MAS/MILS OFR 03-090", result.hits.get(0).record.sourceTitle);
        assertTrue(result.hits.get(0).record.sourceReliability.contains("approximate or outdated"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOneCharacterSearches() {
        MineralSearchEngine.search(Collections.emptyList(), "q", 100);
    }
}
