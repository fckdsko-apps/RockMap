package com.rockmap.app;

/** Shared deterministic map context used only by guided-tour examples. */
public final class TourTrainingArea {
    public static final String PLACE_NAME = "Saint Peters Dome";
    public static final double LATITUDE = 38.747412d;
    public static final double LONGITUDE = -104.911468d;
    /** Broad enough to teach vicinity-scale Prospecting Areas rather than pinpoint polygons. */
    public static final double MAP_ZOOM = 15.0d;
    public static final String COORDINATES = "38.747412, -104.911468";

    private TourTrainingArea() {}

    public static boolean matchesPlaceName(String value) {
        return value != null && PLACE_NAME.equalsIgnoreCase(value.trim());
    }
}
