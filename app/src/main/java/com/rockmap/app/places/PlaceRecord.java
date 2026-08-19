package com.rockmap.app.places;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PlaceRecord {
    public final String name;
    public final String kind;
    public final String context;
    public final double latitude;
    public final double longitude;
    public final List<String> aliases;
    public final int importance;

    public PlaceRecord(String name, String kind, String context,
                       double latitude, double longitude,
                       List<String> aliases, int importance) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Place name is required.");
        }
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90d || latitude > 90d
                || longitude < -180d || longitude > 180d) {
            throw new IllegalArgumentException("Invalid place coordinates.");
        }
        this.name = name.trim();
        this.kind = kind == null || kind.trim().isEmpty() ? "Place" : kind.trim();
        this.context = context == null ? "" : context.trim();
        this.latitude = latitude;
        this.longitude = longitude;
        this.aliases = Collections.unmodifiableList(
                aliases == null ? new ArrayList<>() : new ArrayList<>(aliases));
        this.importance = importance;
    }
}
