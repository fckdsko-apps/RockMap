package com.rockmap.app.offline;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DataValidatorsTest {
    @Test public void acceptsExpectedFileNames() {
        assertTrue(DataValidators.isSafeFileName("colorado-claims-20260814.pmtiles"));
        assertTrue(DataValidators.isSafeFileName("rockmap-style-v1.json"));
    }

    @Test public void rejectsTraversalAndPaths() {
        assertFalse(DataValidators.isSafeFileName("../claims.pmtiles"));
        assertFalse(DataValidators.isSafeFileName("maps/claims.pmtiles"));
        assertFalse(DataValidators.isSafeFileName(".."));
    }

    @Test public void requiresHttps() {
        assertTrue(DataValidators.isSafeHttpsUrl("https://github.com/example/file.pmtiles"));
        assertFalse(DataValidators.isSafeHttpsUrl("http://example.com/file.pmtiles"));
        assertFalse(DataValidators.isSafeHttpsUrl("file:///tmp/file.pmtiles"));
        assertFalse(DataValidators.isSafeHttpsUrl("https://user:pass@example.com/file.pmtiles"));
    }

    @Test public void validatesHashesAndSizes() {
        assertTrue(DataValidators.isSha256("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
        assertFalse(DataValidators.isSha256("abc"));
        assertTrue(DataValidators.isSafeByteCount(1));
        assertFalse(DataValidators.isSafeByteCount(0));
        assertFalse(DataValidators.isSafeByteCount(DataValidators.MAX_FILE_BYTES + 1));
    }
}
