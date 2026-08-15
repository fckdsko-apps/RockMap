package com.rockmap.app.coordinates;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline parser for common latitude/longitude formats pasted from mapping and GPS tools.
 * RockMap intentionally treats an unlabeled pair as latitude first, longitude second.
 */
public final class CoordinateParser {
    private static final Pattern HEMISPHERE_COMPONENT = Pattern.compile(
            "(?i)([+-]?\\d(?:[^NSEW]*?))\\s*([NSEW])");
    private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");

    private CoordinateParser() {}

    public static final class Result {
        public final double latitude;
        public final double longitude;

        public Result(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public String formatDecimal() {
            return String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
        }
    }

    public static Result parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter a latitude and longitude.");
        }

        String text = stripLabels(normalize(raw));
        Result hemispheres = parseHemispherePair(text);
        if (hemispheres != null) return hemispheres;

        return parseBareDecimalPair(text);
    }

    private static String normalize(String value) {
        return value.trim()
                .replace('\u00ba', '\u00b0')
                .replace('\u02da', '\u00b0')
                .replace('\u2032', '\'')
                .replace('\u2019', '\'')
                .replace('\u2018', '\'')
                .replace('\u2033', '"')
                .replace('\u201d', '"')
                .replace('\u201c', '"')
                .replace(';', ',')
                .toUpperCase(Locale.US);
    }

    private static String stripLabels(String text) {
        return text
                .replace("LATITUDE", "")
                .replace("LONGITUDE", "")
                .replace("LAT", "")
                .replace("LON", "")
                .replace("LNG", "");
    }

    private static Result parseHemispherePair(String text) {
        if (!containsHemisphere(text)) return null;

        Matcher matcher = HEMISPHERE_COMPONENT.matcher(text);
        Double latitude = null;
        Double longitude = null;
        int componentCount = 0;

        while (matcher.find()) {
            componentCount++;
            if (componentCount > 2) {
                throw new IllegalArgumentException("Enter one latitude and one longitude.");
            }
            char hemisphere = Character.toUpperCase(matcher.group(2).charAt(0));
            double value = parseComponent(matcher.group(1), hemisphere);
            if (hemisphere == 'N' || hemisphere == 'S') {
                if (latitude != null) throw new IllegalArgumentException("Latitude is listed more than once.");
                latitude = value;
            } else {
                if (longitude != null) throw new IllegalArgumentException("Longitude is listed more than once.");
                longitude = value;
            }
        }

        if (componentCount == 0 || latitude == null || longitude == null) {
            throw new IllegalArgumentException("Use N/S for latitude and E/W for longitude.");
        }

        validate(latitude, longitude);
        return new Result(latitude, longitude);
    }

    private static Result parseBareDecimalPair(String text) {
        String cleaned = text.replace("\u00b0", " ");

        Matcher matcher = NUMBER.matcher(cleaned);
        List<Double> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(Double.parseDouble(matcher.group()));
        }
        if (numbers.size() != 2) {
            throw new IllegalArgumentException(
                    "Use decimal coordinates like 39.290719, -106.212474 or include N/S and E/W for DMS.");
        }

        double latitude = numbers.get(0);
        double longitude = numbers.get(1);
        validate(latitude, longitude);
        return new Result(latitude, longitude);
    }

    private static double parseComponent(String component, char hemisphere) {
        Matcher matcher = NUMBER.matcher(component);
        List<Double> numbers = new ArrayList<>();
        while (matcher.find()) numbers.add(Double.parseDouble(matcher.group()));

        if (numbers.isEmpty() || numbers.size() > 3) {
            throw new IllegalArgumentException("Coordinate component has an unsupported format.");
        }

        double degrees = numbers.get(0);
        double minutes = numbers.size() >= 2 ? numbers.get(1) : 0d;
        double seconds = numbers.size() >= 3 ? numbers.get(2) : 0d;

        if (minutes < 0d || minutes >= 60d || seconds < 0d || seconds >= 60d) {
            throw new IllegalArgumentException("Minutes and seconds must be less than 60.");
        }

        boolean negativeDegrees = degrees < 0d;
        degrees = Math.abs(degrees);
        double value = degrees + minutes / 60d + seconds / 3600d;
        boolean negativeHemisphere = hemisphere == 'S' || hemisphere == 'W';

        if (negativeDegrees && !negativeHemisphere) {
            throw new IllegalArgumentException("The minus sign conflicts with the hemisphere letter.");
        }
        if (negativeDegrees || negativeHemisphere) value = -value;
        return value;
    }

    private static boolean containsHemisphere(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'N' || c == 'S' || c == 'E' || c == 'W') return true;
        }
        return false;
    }

    private static void validate(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || latitude < -90d || latitude > 90d) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90.");
        }
        if (!Double.isFinite(longitude) || longitude < -180d || longitude > 180d) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180.");
        }
    }
}
