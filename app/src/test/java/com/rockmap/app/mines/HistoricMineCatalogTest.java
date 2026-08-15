package com.rockmap.app.mines;

import com.rockmap.app.minerals.MineralRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HistoricMineCatalogTest {
    @Test
    public void mineOverlayUsesOnlyMineAndWorkingSources() {
        assertTrue(HistoricMineCatalog.isMineRecord(record("mas", "Mine", 39, -105, "USGS_MAS")));
        assertTrue(HistoricMineCatalog.isMineRecord(record("aml", "", 39, -105, "CGS_USFS_AML")));
        assertTrue(HistoricMineCatalog.isMineRecord(record("ms17", "Pit", 39, -105, "CGS_MS17")));

        assertFalse(HistoricMineCatalog.isMineRecord(record("mrds", "Occurrence", 39, -105, "MRDS")));
        assertFalse(HistoricMineCatalog.isMineRecord(record("b40", "Occurrence", 39, -105, "CGS_B40")));
        assertFalse(HistoricMineCatalog.isMineRecord(record("district", "District", 39, -105, "CGS_DISTRICTS")));
    }

    @Test
    public void unnamedAmlPointGetsHonestOpeningLabel() {
        MineralRecord aml = record("aml", "Unnamed mineral evidence", 39, -105, "CGS_USFS_AML");
        assertEquals("USFS abandoned mine opening", HistoricMineCatalog.displayName(aml));
        assertEquals("USFS abandoned-mine opening", HistoricMineCatalog.typeLabel(aml));
        assertTrue(HistoricMineCatalog.isOpening(aml));
    }

    @Test
    public void nearbyEvidenceIncludesOnlyDirectOccurrenceEvidenceAndSortsByDistance() {
        MineralRecord origin = record("mine", "Historic Mine", 39.0, -105.0, "USGS_MAS");
        MineralRecord mrds = record("mrds", "Quartz occurrence", 39.00020, -105.0, "MRDS");
        MineralRecord b40 = record("b40", "Uranium occurrence", 39.00050, -105.0, "CGS_B40");
        MineralRecord district = record("district", "District", 39.00010, -105.0, "CGS_DISTRICTS");
        MineralRecord otherMine = record("mine2", "Another Mine", 39.00010, -105.0, "CGS_MS17");
        MineralRecord far = record("far", "Far occurrence", 39.0020, -105.0, "MRDS");

        List<HistoricMineCatalog.NearbyEvidence> nearby = HistoricMineCatalog.nearbyEvidence(
                Arrays.asList(origin, mrds, b40, district, otherMine, far),
                origin, 100.0, 8);

        assertEquals(2, nearby.size());
        assertEquals("mrds", nearby.get(0).record.id);
        assertEquals("b40", nearby.get(1).record.id);
        assertTrue(nearby.get(0).distanceMeters < nearby.get(1).distanceMeters);
    }

    @Test
    public void officialLocalityCountsAsDirectEvidence() {
        MineralRecord locality = record("loc", "Official locality", 39, -105, "CGS_GEMSTONES");
        assertTrue(HistoricMineCatalog.isDirectMineralEvidence(locality));
    }

    private static MineralRecord record(String id, String name, double lat, double lon, String source) {
        return new MineralRecord(
                id, name, lat, lon,
                "", "",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                source,
                source.equals("MRDS") || source.equals("CGS_B40")
                        ? "Documented mineral occurrence" : "Mine evidence",
                "Source-record point",
                source,
                "Source reliability",
                "");
    }
}
