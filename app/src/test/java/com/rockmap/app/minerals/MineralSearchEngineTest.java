package com.rockmap.app.minerals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MineralSearchEngineTest {
    private static MineralRecord record(String id, String name, List<String> materials,
                                        List<String> commodities, List<String> rocks) {
        return new MineralRecord(id, name, 39.0, -106.0, "Occurrence", "C",
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

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOneCharacterSearches() {
        MineralSearchEngine.search(Collections.emptyList(), "q", 100);
    }
}
