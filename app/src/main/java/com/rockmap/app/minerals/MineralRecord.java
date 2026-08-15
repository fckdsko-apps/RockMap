package com.rockmap.app.minerals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MineralRecord {
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

    public MineralRecord(String id, String name, double latitude, double longitude,
                         String status, String grade,
                         List<String> materials, List<String> commodities,
                         List<String> districts, List<String> models, List<String> rocks) {
        this.id = safe(id);
        this.name = safe(name).isEmpty() ? "Unnamed MRDS occurrence" : safe(name);
        this.latitude = latitude;
        this.longitude = longitude;
        this.status = safe(status);
        this.grade = safe(grade);
        this.materials = immutable(materials);
        this.commodities = immutable(commodities);
        this.districts = immutable(districts);
        this.models = immutable(models);
        this.rocks = immutable(rocks);
    }

    private static List<String> immutable(List<String> input) {
        return Collections.unmodifiableList(input == null ? new ArrayList<>() : new ArrayList<>(input));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
