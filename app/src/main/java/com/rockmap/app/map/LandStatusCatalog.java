package com.rockmap.app.map;

/**
 * Shared Alpha 4 land-status category catalog.
 *
 * The renderer, legend, and tap readout all use this same table so a color cannot
 * silently mean one thing in the map and something different in the UI.
 */
public final class LandStatusCatalog {
    public static final String DEFAULT_COLOR_HEX = "#b8b8b8";

    public static final class Entry {
        public final String code;
        public final String label;
        public final String colorHex;

        private Entry(String code, String label, String colorHex) {
            this.code = code;
            this.label = label;
            this.colorHex = colorHex;
        }
    }

    private static final Entry[] ENTRIES = new Entry[]{
            new Entry("PRI", "Private", "#d39282"),
            new Entry("BLM", "Bureau of Land Management", "#f2cf63"),
            new Entry("USFS", "U.S. Forest Service", "#91c686"),
            new Entry("STA", "State", "#82bdcd"),
            new Entry("LOCAL", "State, County, City Recreation Areas", "#78a3aa"),
            new Entry("NPS", "National Park Service", "#aa98c8"),
            new Entry("USFW", "U.S. Fish and Wildlife Service", "#78b995"),
            new Entry("BIA", "Indian Reservation", "#e79a62"),
            new Entry("DOD", "Military Reservation", "#d99bb6"),
            new Entry("USFS_NG", "National Grasslands", "#c8df84"),
            new Entry("BOR", "Bureau of Reclamation", "#eadf91"),
            new Entry("BLM_LU", "Bankhead-Jones Land Use Lands", "#e7adb4"),
            new Entry("USFS_LU", "Bankhead-Jones Land Use Lands", "#e7adb4"),
            new Entry("OTHER", "Other Federal", "#bea283")
    };

    private LandStatusCatalog() {}

    public static Entry[] entries() {
        return ENTRIES.clone();
    }

    public static Entry find(String code) {
        if (code == null) return null;
        for (Entry entry : ENTRIES) {
            if (entry.code.equalsIgnoreCase(code.trim())) return entry;
        }
        return null;
    }

    public static String labelFor(String code, String fallback) {
        Entry entry = find(code);
        return entry == null ? fallback : entry.label;
    }
}
