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

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
