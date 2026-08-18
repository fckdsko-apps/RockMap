package com.rockmap.app.minerals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offline selected-area mineral inventory and explicit-evidence weighting for Alpha 6.5.
 *
 * This class intentionally does not calculate a probability of finding a mineral. It inventories
 * mineral/material and commodity terms that are explicitly present in installed RockMap records,
 * then supplies conservative source-weighted points for a visual evidence-density heatmap.
 */
public final class MineralAreaAnalyzer {
    private static final Set<String> IGNORED_TERMS = new HashSet<>();

    static {
        Collections.addAll(IGNORED_TERMS,
                "unknown", "none", "not reported", "not applicable", "n a", "na",
                "other", "various", "multiple", "mineral", "minerals", "material", "materials",
                "commodity", "commodities", "ore", "ores");
    }

    public static final class MineralSummary {
        public final String key;
        public final String displayName;
        public final int recordCount;
        public final int materialRecordCount;
        public final int commodityOnlyRecordCount;
        public final float evidenceWeight;

        MineralSummary(String key, String displayName, int recordCount,
                       int materialRecordCount, int commodityOnlyRecordCount,
                       float evidenceWeight) {
            this.key = key;
            this.displayName = displayName;
            this.recordCount = recordCount;
            this.materialRecordCount = materialRecordCount;
            this.commodityOnlyRecordCount = commodityOnlyRecordCount;
            this.evidenceWeight = evidenceWeight;
        }
    }

    public static final class AnalysisResult {
        public final MineralSearchEngine.Bounds bounds;
        public final int recordsInArea;
        public final int recordsWithExplicitMineralTerms;
        public final List<MineralSummary> minerals;

        AnalysisResult(MineralSearchEngine.Bounds bounds, int recordsInArea,
                       int recordsWithExplicitMineralTerms, List<MineralSummary> minerals) {
            this.bounds = bounds;
            this.recordsInArea = recordsInArea;
            this.recordsWithExplicitMineralTerms = recordsWithExplicitMineralTerms;
            this.minerals = Collections.unmodifiableList(new ArrayList<>(minerals));
        }
    }

    public static final class EvidencePoint {
        public final MineralRecord record;
        public final String mineralKey;
        public final String displayName;
        public final String reason;
        public final float weight;

        EvidencePoint(MineralRecord record, String mineralKey, String displayName,
                      String reason, float weight) {
            this.record = record;
            this.mineralKey = mineralKey;
            this.displayName = displayName;
            this.reason = reason;
            this.weight = weight;
        }
    }

    private static final class MutableSummary {
        final String key;
        String displayName;
        int recordCount;
        int materialRecordCount;
        int commodityOnlyRecordCount;
        float evidenceWeight;

        MutableSummary(String key, String displayName) {
            this.key = key;
            this.displayName = displayName;
        }
    }

    private MineralAreaAnalyzer() {}

    public static AnalysisResult analyze(List<MineralRecord> records, MineralSearchEngine.Bounds bounds) {
        if (bounds == null) throw new IllegalArgumentException("Select a map area before analyzing minerals.");

        Map<String, MutableSummary> summaries = new HashMap<>();
        int recordsInArea = 0;
        int recordsWithTerms = 0;

        if (records != null) {
            for (MineralRecord record : records) {
                if (record == null || !bounds.contains(record)) continue;
                recordsInArea++;

                Map<String, TermMatch> terms = explicitTerms(record);
                if (terms.isEmpty()) continue;
                recordsWithTerms++;

                for (TermMatch match : terms.values()) {
                    MutableSummary summary = summaries.get(match.key);
                    if (summary == null) {
                        summary = new MutableSummary(match.key, match.displayName);
                        summaries.put(match.key, summary);
                    } else {
                        summary.displayName = preferredDisplay(summary.displayName, match.displayName);
                    }
                    summary.recordCount++;
                    if (match.material) summary.materialRecordCount++;
                    else summary.commodityOnlyRecordCount++;
                    summary.evidenceWeight += pointWeight(record, match.material);
                }
            }
        }

        ArrayList<MineralSummary> out = new ArrayList<>();
        for (MutableSummary summary : summaries.values()) {
            out.add(new MineralSummary(
                    summary.key,
                    summary.displayName,
                    summary.recordCount,
                    summary.materialRecordCount,
                    summary.commodityOnlyRecordCount,
                    summary.evidenceWeight));
        }
        out.sort(Comparator.comparingInt((MineralSummary item) -> item.recordCount).reversed()
                .thenComparing((MineralSummary item) -> item.evidenceWeight, Comparator.reverseOrder())
                .thenComparing(item -> item.displayName.toLowerCase(Locale.US)));
        return new AnalysisResult(bounds, recordsInArea, recordsWithTerms, out);
    }

    public static List<EvidencePoint> evidenceFor(List<MineralRecord> records,
                                                   MineralSearchEngine.Bounds bounds,
                                                   String mineralKey) {
        if (bounds == null) throw new IllegalArgumentException("Selected map area is missing.");
        String requestedKey = MineralSearchEngine.normalize(mineralKey);
        if (requestedKey.isEmpty() || ignored(requestedKey)) {
            throw new IllegalArgumentException("Choose a mineral/material from the analyzed-area list.");
        }

        ArrayList<EvidencePoint> points = new ArrayList<>();
        if (records == null) return points;
        for (MineralRecord record : records) {
            if (record == null || !bounds.contains(record)) continue;
            TermMatch match = explicitTerms(record).get(requestedKey);
            if (match == null) continue;
            points.add(new EvidencePoint(
                    record,
                    requestedKey,
                    match.displayName,
                    match.material
                            ? "area mineral/material: " + match.displayName
                            : "area commodity: " + match.displayName,
                    pointWeight(record, match.material)));
        }
        points.sort(Comparator.comparingDouble((EvidencePoint point) -> point.weight).reversed()
                .thenComparing(point -> point.record.name.toLowerCase(Locale.US))
                .thenComparing(point -> recordKey(point.record)));
        return Collections.unmodifiableList(points);
    }

    static float pointWeight(MineralRecord record, boolean materialMatch) {
        float sourceWeight = sourceWeight(record);
        float fieldWeight = materialMatch ? 1.0f : 0.85f;
        float result = sourceWeight * fieldWeight;
        if (result < 0.05f) return 0.05f;
        return Math.min(1.0f, result);
    }

    static float sourceWeight(MineralRecord record) {
        if (record == null) return 0.35f;
        String code = record.sourceCode == null ? "" : record.sourceCode.trim().toUpperCase(Locale.US);
        if (MineralRecord.SOURCE_MRDS.equals(code) || "CGS_B40".equals(code)) return 1.0f;
        if (code.startsWith("CGS_LOCALITY") || code.startsWith("USGS_LOCALITY")
                || code.startsWith("USGS_PUB_") || "CGS_GEMSTONES".equals(code)
                || "CGS_TEACHERS".equals(code)) return 0.95f;
        if ("CGS_MS17".equals(code)) return 0.80f;
        if ("USGS_MAS".equals(code)) return 0.70f;
        if ("CGS_USFS_AML".equals(code)) return 0.35f;
        if ("CGS_DISTRICTS".equals(code)) return 0.20f;
        return 0.50f;
    }

    private static final class TermMatch {
        final String key;
        final String displayName;
        final boolean material;

        TermMatch(String key, String displayName, boolean material) {
            this.key = key;
            this.displayName = displayName;
            this.material = material;
        }
    }

    private static Map<String, TermMatch> explicitTerms(MineralRecord record) {
        Map<String, TermMatch> terms = new HashMap<>();
        addTerms(terms, record.materials, true);
        addTerms(terms, record.commodities, false);
        return terms;
    }

    private static void addTerms(Map<String, TermMatch> terms, List<String> values, boolean material) {
        if (values == null) return;
        for (String value : values) {
            String display = cleanDisplay(value);
            String key = MineralSearchEngine.normalize(display);
            if (key.length() < 2 || ignored(key)) continue;
            TermMatch existing = terms.get(key);
            if (existing == null || (material && !existing.material)) {
                terms.put(key, new TermMatch(key, display, material));
            }
        }
    }

    private static boolean ignored(String key) {
        return IGNORED_TERMS.contains(key);
    }

    private static String cleanDisplay(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }

    private static String preferredDisplay(String first, String second) {
        if (first == null || first.trim().isEmpty()) return second == null ? "" : second;
        if (second == null || second.trim().isEmpty()) return first;
        if (isAllCaps(first) && !isAllCaps(second)) return second;
        if (second.length() < first.length() && MineralSearchEngine.normalize(first)
                .equals(MineralSearchEngine.normalize(second))) return second;
        return first;
    }

    private static boolean isAllCaps(String value) {
        boolean letter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetter(c)) continue;
            letter = true;
            if (Character.isLowerCase(c)) return false;
        }
        return letter;
    }

    static String recordKey(MineralRecord record) {
        if (record == null) return "";
        return (record.sourceCode == null ? "" : record.sourceCode) + ":" + record.id;
    }
}
