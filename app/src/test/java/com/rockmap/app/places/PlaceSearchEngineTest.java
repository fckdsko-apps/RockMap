package com.rockmap.app.places;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PlaceSearchEngineTest {
    private PlaceSearchEngine engine() {
        return new PlaceSearchEngine(Arrays.asList(
                new PlaceRecord("Mount Antero", "Peak", "near Buena Vista",
                        38.6741, -106.2462, Arrays.asList("Antero", "Mt Antero"), 75),
                new PlaceRecord("Antero Reservoir", "Reservoir", "Park County",
                        38.995, -105.891, Collections.emptyList(), 45),
                new PlaceRecord("County Road 162", "Road", "near Nathrop",
                        38.741, -106.162, Arrays.asList("CR 162", "Co Rd 162"), 35),
                new PlaceRecord("Colorado Trail", "Trail", "near Mount Princeton",
                        38.770, -106.170, Arrays.asList("CT"), 55),
                new PlaceRecord("Buena Vista", "Town", "Colorado",
                        38.842, -106.131, Collections.emptyList(), 95),
                new PlaceRecord("Mount Princeton", "Peak", "near Nathrop",
                        38.749, -106.242, Collections.emptyList(), 82)
        ));
    }

    @Test public void exactAndPrefixSearchRankMountAnteroFirst() {
        assertEquals("Mount Antero", engine().search("mount antero").get(0).record.name);
        assertEquals("Mount Antero", engine().search("anter").get(0).record.name);
    }

    @Test public void bareMountainNameMatchesLaterNameToken() {
        assertEquals("Mount Princeton", engine().search("princeton").get(0).record.name);
    }

    @Test public void commonMountainAbbreviationsWork() {
        assertEquals("Mount Antero", engine().search("mt antero").get(0).record.name);
        assertEquals("Mount Antero", engine().search("mtn antr").get(0).record.name);
    }

    @Test public void typoToleranceIsConservativeButUseful() {
        assertEquals("Mount Antero", engine().search("mount antro").get(0).record.name);
        assertTrue(engine().search("zz").isEmpty());
    }

    @Test public void roadAbbreviationsResolveToSameRecord() {
        assertEquals("County Road 162", engine().search("cr 162").get(0).record.name);
        assertEquals("County Road 162", engine().search("co rd 162").get(0).record.name);
        assertEquals("County Road 162", engine().search("county road 162").get(0).record.name);
    }

    @Test public void exactMatchBeatsFuzzyAlternatives() {
        List<PlaceSearchEngine.Match> matches = engine().search("antero reservoir");
        assertFalse(matches.isEmpty());
        assertEquals("Antero Reservoir", matches.get(0).record.name);
    }

    @Test public void normalizationIgnoresCasePunctuationAndAccents() {
        assertEquals("mount antero", PlaceSearchEngine.normalize(" MOUNT—ANTERO "));
        assertEquals("county road 162", PlaceSearchEngine.normalize("CR 162"));
        assertEquals("buena vista", PlaceSearchEngine.normalize("Buena Vístá"));
    }
}
