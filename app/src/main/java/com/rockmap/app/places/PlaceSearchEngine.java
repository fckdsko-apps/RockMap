package com.rockmap.app.places;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PlaceSearchEngine {
    public static final int DEFAULT_LIMIT = 30;

    public static final class Match {
        public final PlaceRecord record;
        public final int score;

        Match(PlaceRecord record, int score) {
            this.record = record;
            this.score = score;
        }
    }

    private static final Map<String, String> TOKEN_ALIASES = new HashMap<>();
    static {
        TOKEN_ALIASES.put("mt", "mount");
        TOKEN_ALIASES.put("mtn", "mount");
        TOKEN_ALIASES.put("mountain", "mount");
        TOKEN_ALIASES.put("rd", "road");
        TOKEN_ALIASES.put("rte", "route");
        TOKEN_ALIASES.put("rt", "route");
        TOKEN_ALIASES.put("hwy", "highway");
        TOKEN_ALIASES.put("trl", "trail");
        TOKEN_ALIASES.put("tr", "trail");
        TOKEN_ALIASES.put("st", "street");
        TOKEN_ALIASES.put("ave", "avenue");
        TOKEN_ALIASES.put("blvd", "boulevard");
        TOKEN_ALIASES.put("ln", "lane");
        TOKEN_ALIASES.put("dr", "drive");
        TOKEN_ALIASES.put("pkwy", "parkway");
        TOKEN_ALIASES.put("jct", "junction");
        TOKEN_ALIASES.put("lk", "lake");
        TOKEN_ALIASES.put("res", "reservoir");
    }

    private final List<IndexedRecord> records;

    public PlaceSearchEngine(List<PlaceRecord> input) {
        ArrayList<IndexedRecord> indexed = new ArrayList<>();
        if (input != null) {
            for (PlaceRecord record : input) {
                if (record != null) indexed.add(new IndexedRecord(record));
            }
        }
        records = Collections.unmodifiableList(indexed);
    }

    public List<Match> search(String rawQuery) {
        return search(rawQuery, DEFAULT_LIMIT);
    }

    public List<Match> search(String rawQuery, int limit) {
        String query = normalize(rawQuery);
        if (query.length() < 2 || limit <= 0) return Collections.emptyList();

        ArrayList<Match> matches = new ArrayList<>();
        for (IndexedRecord indexed : records) {
            int score = indexed.score(query);
            if (score > 0) matches.add(new Match(indexed.record, score));
        }

        matches.sort((left, right) -> {
            int byScore = Integer.compare(right.score, left.score);
            if (byScore != 0) return byScore;
            int byImportance = Integer.compare(right.record.importance, left.record.importance);
            if (byImportance != 0) return byImportance;
            int byName = String.CASE_INSENSITIVE_ORDER.compare(left.record.name, right.record.name);
            if (byName != 0) return byName;
            int byLatitude = Double.compare(left.record.latitude, right.record.latitude);
            if (byLatitude != 0) return byLatitude;
            return Double.compare(left.record.longitude, right.record.longitude);
        });

        if (matches.size() <= limit) return matches;
        return new ArrayList<>(matches.subList(0, limit));
    }

    public static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.US);

        normalized = normalized
                .replace('&', ' ')
                .replaceAll("\\bco(?:unty)?\\s+(?:rd|road)\\b", " county road ")
                .replaceAll("\\bcr\\s*(?=\\d)", " county road ")
                .replaceAll("\\bfsr\\s*(?=\\d)", " forest service road ")
                .replaceAll("\\bfs\\s+(?:rd|road)\\b", " forest service road ")
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return "";

        String[] tokens = normalized.split(" ");
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            String canonical = TOKEN_ALIASES.getOrDefault(token, token);
            if (out.length() > 0) out.append(' ');
            out.append(canonical);
        }
        return out.toString();
    }

    private static final class IndexedRecord {
        final PlaceRecord record;
        final String name;
        final List<String> candidates;

        IndexedRecord(PlaceRecord record) {
            this.record = record;
            this.name = normalize(record.name);
            ArrayList<String> all = new ArrayList<>();
            addUnique(all, this.name);
            for (String alias : record.aliases) addUnique(all, normalize(alias));
            if (!record.context.isEmpty()) {
                addUnique(all, normalize(record.name + " " + record.context));
            }
            this.candidates = all;
        }

        int score(String query) {
            int best = 0;
            for (String candidate : candidates) {
                int candidateScore = scoreCandidate(query, candidate);
                if (candidateScore > best) best = candidateScore;
            }
            if (best <= 0) return 0;
            // Importance only breaks close ties; it must not let a famous fuzzy match beat an exact one.
            return best + Math.max(0, Math.min(40, record.importance / 5));
        }
    }

    private static void addUnique(List<String> values, String value) {
        if (value != null && !value.isEmpty() && !values.contains(value)) values.add(value);
    }

    private static int scoreCandidate(String query, String candidate) {
        if (candidate == null || candidate.isEmpty()) return 0;
        if (query.equals(candidate)) return 1200;
        if (candidate.startsWith(query + " ") || candidate.startsWith(query)) return 1040;
        if (query.length() >= 3) {
            for (String token : candidate.split(" ")) {
                if (token.startsWith(query)) return 1040;
            }
        }
        if (candidate.contains(" " + query + " ") || candidate.endsWith(" " + query)) return 930;
        if (candidate.contains(query) && query.length() >= 4) return 880;

        String[] queryTokens = query.split(" ");
        String[] candidateTokens = candidate.split(" ");
        int matched = 0;
        int fuzzyCost = 0;
        boolean[] used = new boolean[candidateTokens.length];
        for (String queryToken : queryTokens) {
            int bestIndex = -1;
            int bestCost = Integer.MAX_VALUE;
            for (int i = 0; i < candidateTokens.length; i++) {
                if (used[i]) continue;
                int cost = tokenCost(queryToken, candidateTokens[i]);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestIndex = i;
                }
            }
            if (bestIndex >= 0 && bestCost < 100) {
                used[bestIndex] = true;
                matched++;
                fuzzyCost += bestCost;
            }
        }

        if (matched == queryTokens.length) {
            int coveragePenalty = Math.max(0, candidateTokens.length - queryTokens.length) * 8;
            return Math.max(650, 840 - fuzzyCost * 35 - coveragePenalty);
        }

        // Whole-string typo fallback is deliberately conservative.
        int allowed = allowedEdits(query);
        if (allowed > 0 && Math.abs(query.length() - candidate.length()) <= allowed) {
            int distance = levenshtein(query, candidate, allowed);
            if (distance <= allowed) return 720 - distance * 45;
        }
        return 0;
    }

    private static int tokenCost(String query, String candidate) {
        if (query.equals(candidate)) return 0;
        if (query.length() >= 2 && candidate.startsWith(query)) return 1;
        if (candidate.length() >= 2 && query.startsWith(candidate) && candidate.length() >= 4) return 2;
        int allowed = allowedEdits(query);
        if (allowed <= 0 || Math.abs(query.length() - candidate.length()) > allowed) return 100;
        int distance = levenshtein(query, candidate, allowed);
        return distance <= allowed ? distance + 2 : 100;
    }

    private static int allowedEdits(String token) {
        int length = token == null ? 0 : token.length();
        if (length < 4) return 0;
        if (length <= 7) return 1;
        return 2;
    }

    static int levenshtein(String left, String right, int stopAfter) {
        if (left.equals(right)) return 0;
        if (Math.abs(left.length() - right.length()) > stopAfter) return stopAfter + 1;
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            char lc = left.charAt(i - 1);
            for (int j = 1; j <= right.length(); j++) {
                int replace = previous[j - 1] + (lc == right.charAt(j - 1) ? 0 : 1);
                int insert = current[j - 1] + 1;
                int delete = previous[j] + 1;
                current[j] = Math.min(replace, Math.min(insert, delete));
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > stopAfter) return stopAfter + 1;
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
