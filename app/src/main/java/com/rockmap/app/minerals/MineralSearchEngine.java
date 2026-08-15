package com.rockmap.app.minerals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MineralSearchEngine {
    public static final int DEFAULT_LIMIT = 50;

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
        String requested = normalize(rawQuery);
        if (requested.length() < 2) throw new IllegalArgumentException("Enter at least 2 characters.");
        int safeLimit = Math.max(1, Math.min(250, limit));

        List<Hit> exact = searchInternal(records, requested);
        String effective = requested;
        String note = "";
        if (exact.isEmpty() && GEM_ALIASES.containsKey(requested)) {
            effective = GEM_ALIASES.get(requested);
            exact = searchInternal(records, effective);
            if (!exact.isEmpty()) {
                note = "No exact MRDS record matched “" + rawQuery.trim() + "”. Showing parent-mineral matches for “"
                        + effective + "”. These are geological leads, not proof that the gemstone variety occurs at every result.";
            }
        }

        exact.sort(Comparator.comparingInt((Hit hit) -> hit.score).reversed()
                .thenComparing(hit -> hit.record.name.toLowerCase(Locale.US))
                .thenComparing(hit -> hit.record.id));
        int total = exact.size();
        List<Hit> limited = new ArrayList<>(exact.subList(0, Math.min(total, safeLimit)));
        return new SearchResult(rawQuery == null ? "" : rawQuery.trim(), effective, note, total, limited);
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
        Hit hit = matchList(record, record.materials, "mineral/material", query, singular, 100, 92);
        if (hit != null) return hit;
        hit = matchList(record, record.commodities, "commodity", query, singular, 88, 82);
        if (hit != null) return hit;
        hit = matchText(record, record.name, "site", query, singular, 76, 72);
        if (hit != null) return hit;
        hit = matchList(record, record.districts, "district", query, singular, 66, 62);
        if (hit != null) return hit;
        hit = matchList(record, record.models, "deposit model", query, singular, 56, 52);
        if (hit != null) return hit;
        return matchList(record, record.rocks, "rock/geologic context", query, singular, 46, 42);
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
