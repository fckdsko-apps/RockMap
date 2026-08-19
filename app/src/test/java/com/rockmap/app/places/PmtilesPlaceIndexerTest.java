package com.rockmap.app.places;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public final class PmtilesPlaceIndexerTest {
    @Test
    public void statewideDeviceScannerIsDisabled() {
        assertTrue(PmtilesPlaceIndexer.isDisabled());
    }
}
