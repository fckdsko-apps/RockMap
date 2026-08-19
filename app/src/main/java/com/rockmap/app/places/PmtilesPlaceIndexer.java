package com.rockmap.app.places;

/**
 * Disabled compatibility marker for the first Alpha 6.6 prototype.
 * Statewide PMTiles scanning on Android was removed because it was too slow on-device.
 */
@Deprecated
public final class PmtilesPlaceIndexer {
    private PmtilesPlaceIndexer() {}

    static boolean isDisabled() {
        return true;
    }
}
