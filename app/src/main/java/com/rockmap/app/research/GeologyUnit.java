package com.rockmap.app.research;

public final class GeologyUnit {
    public final long objectId;
    public final String state;
    public final String originalLabel;
    public final String sgmcLabel;
    public final String unitLink;
    public final String unitName;
    public final String ageMin;
    public final String ageMax;
    public final String generalizedLithology;
    public final String major1;
    public final String major2;
    public final String major3;
    public final String minor1;
    public final String minor2;
    public final String minor3;
    public final String minor4;
    public final String minor5;
    public final String incidental;
    public final String indeterminate;
    public final String referenceId;
    public final String reference;
    public final String digitalUrl;
    public final String ngmdb1;
    public final String ngmdb2;
    public final String ngmdb3;
    public final String rgba;
    public final double south;
    public final double west;
    public final double north;
    public final double east;
    public final String geometryJson;

    public GeologyUnit(long objectId, String state, String originalLabel, String sgmcLabel,
                       String unitLink, String unitName, String ageMin, String ageMax,
                       String generalizedLithology, String major1, String major2, String major3,
                       String minor1, String minor2, String minor3, String minor4, String minor5,
                       String incidental, String indeterminate, String referenceId, String reference,
                       String digitalUrl, String ngmdb1, String ngmdb2, String ngmdb3, String rgba,
                       double south, double west, double north, double east, String geometryJson) {
        this.objectId = objectId;
        this.state = safe(state);
        this.originalLabel = safe(originalLabel);
        this.sgmcLabel = safe(sgmcLabel);
        this.unitLink = safe(unitLink);
        this.unitName = safe(unitName);
        this.ageMin = safe(ageMin);
        this.ageMax = safe(ageMax);
        this.generalizedLithology = safe(generalizedLithology);
        this.major1 = safe(major1);
        this.major2 = safe(major2);
        this.major3 = safe(major3);
        this.minor1 = safe(minor1);
        this.minor2 = safe(minor2);
        this.minor3 = safe(minor3);
        this.minor4 = safe(minor4);
        this.minor5 = safe(minor5);
        this.incidental = safe(incidental);
        this.indeterminate = safe(indeterminate);
        this.referenceId = safe(referenceId);
        this.reference = safe(reference);
        this.digitalUrl = safe(digitalUrl);
        this.ngmdb1 = safe(ngmdb1);
        this.ngmdb2 = safe(ngmdb2);
        this.ngmdb3 = safe(ngmdb3);
        this.rgba = safe(rgba);
        this.south = south;
        this.west = west;
        this.north = north;
        this.east = east;
        this.geometryJson = safe(geometryJson);
    }

    public String displayName() {
        if (!unitName.isEmpty()) return unitName;
        if (!sgmcLabel.isEmpty()) return sgmcLabel;
        if (!originalLabel.isEmpty()) return originalLabel;
        return "Geologic unit " + objectId;
    }

    public String ageLabel() {
        if (ageMin.isEmpty()) return ageMax;
        if (ageMax.isEmpty() || ageMax.equalsIgnoreCase(ageMin)) return ageMin;
        return ageMin + " – " + ageMax;
    }

    public String lithologyLabel() {
        if (!generalizedLithology.isEmpty()) return generalizedLithology;
        if (!major1.isEmpty()) return major1;
        return "Lithology not reported";
    }

    /** Compact user-facing age. Raw SGMC age strings remain available for details/export. */
    public String compactAgeLabel() {
        String min = mostSpecificAge(ageMin);
        String max = mostSpecificAge(ageMax);
        if (min.isEmpty()) return max;
        if (max.isEmpty() || min.equalsIgnoreCase(max)) return min;
        return min + " – " + max;
    }

    /** Prefer a human-readable rock type over a broad SGMC classification path. */
    public String compactLithologyLabel() {
        String fromName = rockTypeFromName(displayName());
        if (!fromName.isEmpty()) return fromName;

        String[] candidates = new String[]{major1, major2, major3, minor1, minor2, minor3, minor4, minor5};
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (String candidate : candidates) {
            int score = lithologySpecificity(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = safe(candidate);
            }
        }
        if (!best.isEmpty() && bestScore > -20) return titleCase(best);
        if (!generalizedLithology.isEmpty()) return titleCase(generalizedLithology);
        return "Lithology not reported";
    }

    private static String rockTypeFromName(String rawName) {
        String lower = safe(rawName).toLowerCase(java.util.Locale.US);
        String[] rocks = new String[]{
                "quartzite", "limestone", "dolomite", "sandstone", "siltstone", "mudstone",
                "conglomerate", "granite", "granodiorite", "diorite", "monzonite", "gabbro",
                "pegmatite", "rhyolite", "dacite", "andesite", "basalt", "tuff", "gneiss",
                "schist", "marble", "phyllite", "slate", "amphibolite", "arkose"
        };
        for (String rock : rocks) {
            if (lower.matches(".*\\b" + java.util.regex.Pattern.quote(rock) + "\\b.*")) {
                return titleCase(rock);
            }
        }
        return "";
    }

    private static int lithologySpecificity(String raw) {
        String value = safe(raw);
        if (value.isEmpty()) return Integer.MIN_VALUE;
        String lower = value.toLowerCase(java.util.Locale.US);
        int score = 0;
        if (lower.contains("undifferentiated") || lower.contains("unknown")) score -= 30;
        if (lower.contains(",") || lower.contains(" and ")) score -= 8;
        if (value.length() <= 32) score += 4;
        String[] specific = new String[]{
                "quartzite", "limestone", "dolomite", "sandstone", "siltstone", "mudstone",
                "conglomerate", "granite", "granodiorite", "diorite", "monzonite", "gabbro",
                "pegmatite", "rhyolite", "dacite", "andesite", "basalt", "tuff", "gneiss",
                "schist", "marble", "phyllite", "slate", "amphibolite", "arkose"
        };
        for (String rock : specific) if (lower.contains(rock)) score += 20;
        return score;
    }

    /** Stable grouping identity for user-facing results; individual polygons remain separate in export. */
    public String resultGroupKey() {
        if (!unitLink.isEmpty()) return "link:" + unitLink.toLowerCase(java.util.Locale.US);
        return (displayName() + "|" + compactAgeLabel() + "|" + compactLithologyLabel())
                .toLowerCase(java.util.Locale.US);
    }

    private static String mostSpecificAge(String raw) {
        String value = safe(raw);
        if (value.isEmpty()) return "";
        String[] parts = value.split("\\s+-\\s+");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            if (!part.isEmpty()) return part;
        }
        return value;
    }

    private static String titleCase(String raw) {
        String value = safe(raw);
        if (value.isEmpty()) return value;
        StringBuilder out = new StringBuilder(value.length());
        boolean upper = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (upper && Character.isLetter(c)) {
                out.append(Character.toUpperCase(c));
                upper = false;
            } else {
                out.append(c);
            }
            if (c == ' ' || c == '/' || c == '-') upper = true;
        }
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
