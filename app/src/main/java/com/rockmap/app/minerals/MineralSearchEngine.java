package com.rockmap.app.minerals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MineralSearchEngine {
    // Zero means no permanent result cap. UI pagination controls how many list rows are shown at once.
    public static final int DEFAULT_LIMIT = 0;

    public static final class Bounds {
        public final double north;
        public final double east;
        public final double south;
        public final double west;

        public Bounds(double north, double east, double south, double west) {
            if (!Double.isFinite(north) || !Double.isFinite(east)
                    || !Double.isFinite(south) || !Double.isFinite(west)
                    || north < south || east < west) {
                throw new IllegalArgumentException("Invalid mineral-search map bounds.");
            }
            this.north = north;
            this.east = east;
            this.south = south;
            this.west = west;
        }

        public boolean contains(MineralRecord record) {
            return record != null
                    && record.latitude <= north && record.latitude >= south
                    && record.longitude <= east && record.longitude >= west;
        }
    }

    public static final class Hit {
        public final MineralRecord record;
        public final String reason;
        public final int score;

        Hit(MineralRecord record, String reason, int score) {
            this.record = record;
            this.reason = reason;
            this.score = score;
        }
    }

    public static final class SearchResult {
        public final String requestedQuery;
        public final String effectiveQuery;
        public final String aliasNote;
        public final int totalMatches;
        public final List<Hit> hits;

        SearchResult(String requestedQuery, String effectiveQuery, String aliasNote,
                     int totalMatches, List<Hit> hits) {
            this.requestedQuery = requestedQuery;
            this.effectiveQuery = effectiveQuery;
            this.aliasNote = aliasNote;
            this.totalMatches = totalMatches;
            this.hits = Collections.unmodifiableList(hits);
        }
    }

    private static final Map<String, String> GEM_ALIASES = new HashMap<>();
    static {
        GEM_ALIASES.put("aquamarine", "beryl");
        GEM_ALIASES.put("amazonite", "microcline");
        GEM_ALIASES.put("smoky quartz", "quartz");
        GEM_ALIASES.put("smokey quartz", "quartz");
        GEM_ALIASES.put("amethyst", "quartz");
        GEM_ALIASES.put("agate", "chalcedony");
        GEM_ALIASES.put("jasper", "chalcedony");
        GEM_ALIASES.put("peridot", "olivine");
        GEM_ALIASES.put("sapphire", "corundum");
    }

    private MineralSearchEngine() {}

    public static SearchResult search(List<MineralRecord> records, String rawQuery, int limit) {
        return search(records, rawQuery, limit, null);
    }

    public static SearchResult search(List<MineralRecord> records, String rawQuery, int limit, Bounds bounds) {
        String requested = normalize(rawQuery);
        if (requested.length() < 2) throw new IllegalArgumentException("Enter at least 2 characters.");

        List<MineralRecord> scopedRecords = filterBounds(records, bounds);
        List<Hit> exact = searchInternal(scopedRecords, requested);
        String effective = requested;
        String note = "";
        if (exact.isEmpty() && GEM_ALIASES.containsKey(requested)) {
            effective = GEM_ALIASES.get(requested);
            exact = searchInternal(scopedRecords, effective);
            if (!exact.isEmpty()) {
                note = "No exact indexed evidence matched “" + rawQuery.trim() + "” in the selected area. Showing parent-mineral matches for “"
                        + effective + "”. These are geological leads, not proof that the gemstone variety occurs at every result.";
            }
        }

        exact.sort(Comparator.comparingInt((Hit hit) -> hit.score).reversed()
                .thenComparing(hit -> hit.record.name.toLowerCase(Locale.US))
                .thenComparing(hit -> hit.record.id));
        int total = exact.size();
        int shown = limit <= 0 ? total : Math.min(total, limit);
        List<Hit> resultHits = new ArrayList<>(exact.subList(0, shown));
        return new SearchResult(rawQuery == null ? "" : rawQuery.trim(), effective, note, total, resultHits);
    }

    private static List<MineralRecord> filterBounds(List<MineralRecord> records, Bounds bounds) {
        if (records == null) return Collections.emptyList();
        if (bounds == null) return records;
        ArrayList<MineralRecord> scoped = new ArrayList<>();
        for (MineralRecord record : records) {
            if (bounds.contains(record)) scoped.add(record);
        }
        return scoped;
    }

    private static List<Hit> searchInternal(List<MineralRecord> records, String query) {
        List<Hit> hits = new ArrayList<>();
        if (records == null) return hits;
        String singular = query.endsWith("s") && query.length() > 3 ? query.substring(0, query.length() - 1) : query;
        for (MineralRecord record : records) {
            Hit hit = bestHit(record, query, singular);
            if (hit != null) hits.add(hit);
        }
        return hits;
    }

    private static Hit bestHit(MineralRecord record, String query, String singular) {
        int evidence = evidenceAdjustment(record);
        Hit hit = matchList(record, record.materials, "mineral/material", query, singular, 100 + evidence, 92 + evidence);
        if (hit != null) return hit;
        hit = matchList(record, record.commodities, "commodity", query, singular, 88 + evidence, 82 + evidence);
        if (hit != null) return hit;
        hit = matchText(record, record.name, "site", query, singular, 76 + evidence, 72 + evidence);
        if (hit != null) return hit;
        hit = matchList(record, record.districts, "district", query, singular, 66 + evidence, 62 + evidence);
        if (hit != null) return hit;
        hit = matchList(record, record.models, "deposit model", query, singular, 56 + evidence, 52 + evidence);
        if (hit != null) return hit;
        return matchList(record, record.rocks, "rock/geologic context", query, singular, 46 + evidence, 42 + evidence);
    }

    // Internal ordering only; RockMap does not expose a probabilistic or "chance" score.
    // Direct occurrence/locality evidence should appear ahead of broad district or AML leads.
    private static int evidenceAdjustment(MineralRecord record) {
        if (record == null) return 0;
        String code = record.sourceCode == null ? "" : record.sourceCode.toUpperCase(Locale.US);
        if (MineralRecord.SOURCE_MRDS.equals(code) || "CGS_B40".equals(code)) return 18;
        if (code.startsWith("CGS_LOCALITY") || code.startsWith("USGS_LOCALITY")) return 16;
        if ("CGS_MS17".equals(code)) return 6;
        if ("USGS_MAS".equals(code)) return 2;
        if ("CGS_DISTRICTS".equals(code)) return -18;
        if ("CGS_USFS_AML".equals(code)) return -24;
        return 0;
    }

    private static Hit matchList(MineralRecord record, List<String> values, String label,
                                 String query, String singular, int exactScore, int containsScore) {
        for (String value : values) {
            Hit hit = matchText(record, value, label, query, singular, exactScore, containsScore);
            if (hit != null) return hit;
        }
        return null;
    }

    private static Hit matchText(MineralRecord record, String value, String label,
                                 String query, String singular, int exactScore, int containsScore) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) return null;
        if (normalized.equals(query) || (!singular.equals(query) && normalized.equals(singular))) {
            return new Hit(record, label + ": " + value, exactScore);
        }
        if (normalized.contains(query) || query.contains(normalized)
                || allTokensPresent(normalized, query)
                || (!singular.equals(query) && (normalized.contains(singular) || allTokensPresent(normalized, singular)))) {
            return new Hit(record, label + ": " + value, containsScore);
        }
        return null;
    }

    private static boolean allTokensPresent(String value, String query) {
        String[] tokens = query.split(" ");
        if (tokens.length < 2) return false;
        for (String token : tokens) {
            if (token.length() < 2 || !value.contains(token)) return false;
        }
        return true;
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.US)
                .replace('–', '-')
                .replace('—', '-')
                .replaceAll("[^a-z0-9+.-]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
