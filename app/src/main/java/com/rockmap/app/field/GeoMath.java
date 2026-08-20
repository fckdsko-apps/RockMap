package com.rockmap.app.field;

import java.util.List;
import java.util.Locale;

public final class GeoMath {
    private static final double EARTH_RADIUS_M = 6371008.8d;

    public static final class Point {
        public final double lat;
        public final double lon;
        public final double alt;
        public final float accuracy;
        public final long time;

        public Point(double lat, double lon) {
            this(lat, lon, Double.NaN, -1f, 0L);
        }

        public Point(double lat, double lon, double alt, float accuracy, long time) {
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || lat < -90d || lat > 90d || lon < -180d || lon > 180d) {
                throw new IllegalArgumentException("Invalid coordinate.");
            }
            this.lat = lat;
            this.lon = lon;
            this.alt = alt;
            this.accuracy = accuracy;
            this.time = time;
        }

        public String decimal() {
            return String.format(Locale.US, "%.6f, %.6f", lat, lon);
        }
    }

    private GeoMath() {}

    public static double distanceMeters(Point a, Point b) {
        double p1 = Math.toRadians(a.lat);
        double p2 = Math.toRadians(b.lat);
        double dp = Math.toRadians(b.lat - a.lat);
        double dl = Math.toRadians(b.lon - a.lon);
        double h = Math.sin(dp / 2d) * Math.sin(dp / 2d)
                + Math.cos(p1) * Math.cos(p2)
                * Math.sin(dl / 2d) * Math.sin(dl / 2d);
        return EARTH_RADIUS_M * 2d * Math.atan2(Math.sqrt(h), Math.sqrt(1d - h));
    }

    public static double pathDistanceMeters(List<Point> points) {
        if (points == null || points.size() < 2) return 0d;
        double total = 0d;
        for (int i = 1; i < points.size(); i++) total += distanceMeters(points.get(i - 1), points.get(i));
        return total;
    }

    public static double initialBearingDegrees(Point a, Point b) {
        double p1 = Math.toRadians(a.lat);
        double p2 = Math.toRadians(b.lat);
        double dl = Math.toRadians(b.lon - a.lon);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double degrees = Math.toDegrees(Math.atan2(y, x));
        return (degrees + 360d) % 360d;
    }

    public static double polygonAreaSquareMeters(List<Point> points) {
        if (points == null || points.size() < 3) return 0d;
        double sum = 0d;
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get((i + 1) % points.size());
            double lon1 = Math.toRadians(a.lon);
            double lon2 = Math.toRadians(b.lon);
            double lat1 = Math.toRadians(a.lat);
            double lat2 = Math.toRadians(b.lat);
            sum += (lon2 - lon1) * (2d + Math.sin(lat1) + Math.sin(lat2));
        }
        return Math.abs(sum) * EARTH_RADIUS_M * EARTH_RADIUS_M / 2d;
    }

    public static String cardinal(double bearing) {
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        int index = (int) Math.round(((bearing % 360d) / 45d)) % 8;
        return names[index];
    }

    public static String distanceLabel(double meters) {
        if (meters < 1000d) return String.format(Locale.US, "%.0f m", meters);
        double miles = meters / 1609.344d;
        if (miles < 10d) return String.format(Locale.US, "%.2f mi", miles);
        return String.format(Locale.US, "%.1f mi", miles);
    }

    public static String areaLabel(double squareMeters) {
        double acres = squareMeters / 4046.8564224d;
        if (acres < 1d) return String.format(Locale.US, "%.2f acres", acres);
        if (acres < 100d) return String.format(Locale.US, "%.1f acres", acres);
        return String.format(Locale.US, "%.0f acres", acres);
    }
}
