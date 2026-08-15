package com.rockmap.app.minerals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MineralRecord {
    public static final String SOURCE_MRDS = "MRDS";

    public final String id;
    public final String name;
    public final double latitude;
    public final double longitude;
    public final String status;
    public final String grade;
    public final List<String> materials;
    public final List<String> commodities;
    public final List<String> districts;
    public final List<String> models;
    public final List<String> rocks;
    public final String sourceCode;
    public final String evidenceType;
    public final String locationPrecision;
    public final String sourceTitle;
    public final String sourceNote;

    public MineralRecord(String id, String name, double latitude, double longitude,
                         String status, String grade,
                         List<String> materials, List<String> commodities,
                         List<String> districts, List<String> models, List<String> rocks) {
        this(id, name, latitude, longitude, status, grade,
                materials, commodities, districts, models, rocks,
                SOURCE_MRDS,
                "Documented mineral occurrence",
                "MRDS record point; precision varies by record",
                "USGS Mineral Resources Data System (MRDS)",
                "");
    }

    public MineralRecord(String id, String name, double latitude, double longitude,
                         String status, String grade,
                         List<String> materials, List<String> commodities,
                         List<String> districts, List<String> models, List<String> rocks,
                         String sourceCode, String evidenceType, String locationPrecision,
                         String sourceTitle, String sourceNote) {
        this.id = safe(id);
        this.name = safe(name).isEmpty() ? "Unnamed mineral occurrence" : safe(name);
        this.latitude = latitude;
        this.longitude = longitude;
        this.status = safe(status);
        this.grade = safe(grade);
        this.materials = immutable(materials);
        this.commodities = immutable(commodities);
        this.districts = immutable(districts);
        this.models = immutable(models);
        this.rocks = immutable(rocks);
        this.sourceCode = safe(sourceCode).isEmpty() ? SOURCE_MRDS : safe(sourceCode);
        this.evidenceType = safe(evidenceType);
        this.locationPrecision = safe(locationPrecision);
        this.sourceTitle = safe(sourceTitle);
        this.sourceNote = safe(sourceNote);
    }

    public boolean isMrds() {
        return SOURCE_MRDS.equalsIgnoreCase(sourceCode);
    }

    private static List<String> immutable(List<String> input) {
        return Collections.unmodifiableList(input == null ? new ArrayList<>() : new ArrayList<>(input));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
