package com.rockmap.app.field;

import java.util.Locale;

/** WGS84 output formatting for common field-GPS coordinate systems. */
public final class CoordinateFormats {
    private CoordinateFormats() {}

    public static String decimal(double lat, double lon) {
        return String.format(Locale.US, "%.6f, %.6f", lat, lon);
    }

    public static String ddm(double lat, double lon) {
        return ddmOne(lat, true) + "  " + ddmOne(lon, false);
    }

    public static String dms(double lat, double lon) {
        return dmsOne(lat, true) + "  " + dmsOne(lon, false);
    }

    private static String ddmOne(double value, boolean latitude) {
        char hemi = hemisphere(value, latitude);
        double abs = Math.abs(value);
        int deg = (int) Math.floor(abs);
        double minutes = (abs - deg) * 60d;
        return String.format(Locale.US, "%d° %.4f′ %c", deg, minutes, hemi);
    }

    private static String dmsOne(double value, boolean latitude) {
        char hemi = hemisphere(value, latitude);
        double abs = Math.abs(value);
        int deg = (int) Math.floor(abs);
        double minutesFull = (abs - deg) * 60d;
        int minutes = (int) Math.floor(minutesFull);
        double seconds = (minutesFull - minutes) * 60d;
        return String.format(Locale.US, "%d° %d′ %.2f″ %c", deg, minutes, seconds, hemi);
    }

    private static char hemisphere(double value, boolean latitude) {
        if (latitude) return value < 0 ? 'S' : 'N';
        return value < 0 ? 'W' : 'E';
    }

    public static final class Utm {
        public final int zone;
        public final char band;
        public final double easting;
        public final double northing;

        Utm(int zone, char band, double easting, double northing) {
            this.zone = zone;
            this.band = band;
            this.easting = easting;
            this.northing = northing;
        }

        public String label() {
            return String.format(Locale.US, "%d%c %.0fE %.0fN", zone, band, easting, northing);
        }
    }

    public static Utm toUtm(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -80d || lat > 84d) {
            throw new IllegalArgumentException("UTM/MGRS output supports latitudes 80°S through 84°N.");
        }
        int zone = (int) Math.floor((lon + 180d) / 6d) + 1;
        if (lat >= 56d && lat < 64d && lon >= 3d && lon < 12d) zone = 32;
        if (lat >= 72d && lat < 84d) {
            if (lon >= 0d && lon < 9d) zone = 31;
            else if (lon < 21d) zone = 33;
            else if (lon < 33d) zone = 35;
            else if (lon < 42d) zone = 37;
        }

        final double a = 6378137.0d;
        final double f = 1d / 298.257223563d;
        final double k0 = 0.9996d;
        final double e2 = f * (2d - f);
        final double ep2 = e2 / (1d - e2);

        double phi = Math.toRadians(lat);
        double lambda = Math.toRadians(lon);
        double lambda0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        double sin = Math.sin(phi);
        double cos = Math.cos(phi);
        double tan = Math.tan(phi);
        double n = a / Math.sqrt(1d - e2 * sin * sin);
        double t = tan * tan;
        double c = ep2 * cos * cos;
        double A = cos * (lambda - lambda0);

        double e4 = e2 * e2;
        double e6 = e4 * e2;
        double m = a * ((1d - e2 / 4d - 3d * e4 / 64d - 5d * e6 / 256d) * phi
                - (3d * e2 / 8d + 3d * e4 / 32d + 45d * e6 / 1024d) * Math.sin(2d * phi)
                + (15d * e4 / 256d + 45d * e6 / 1024d) * Math.sin(4d * phi)
                - (35d * e6 / 3072d) * Math.sin(6d * phi));

        double easting = k0 * n * (A + (1d - t + c) * Math.pow(A, 3d) / 6d
                + (5d - 18d * t + t * t + 72d * c - 58d * ep2) * Math.pow(A, 5d) / 120d) + 500000d;
        double northing = k0 * (m + n * tan * (A * A / 2d
                + (5d - t + 9d * c + 4d * c * c) * Math.pow(A, 4d) / 24d
                + (61d - 58d * t + t * t + 600d * c - 330d * ep2) * Math.pow(A, 6d) / 720d));
        if (lat < 0d) northing += 10000000d;
        return new Utm(zone, latitudeBand(lat), easting, northing);
    }

    public static String mgrs(double lat, double lon) {
        Utm utm = toUtm(lat, lon);
        String columnSets = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String rowLetters = "ABCDEFGHJKLMNPQRSTUV";
        int set = ((utm.zone - 1) % 6) + 1;
        int columnBase = (set == 1 || set == 4) ? 0 : (set == 2 || set == 5) ? 8 : 16;
        int col = (int) Math.floor(utm.easting / 100000d);
        col = Math.max(1, Math.min(8, col));
        char colLetter = columnSets.charAt(columnBase + col - 1);

        int row = ((int) Math.floor(utm.northing / 100000d)) % 20;
        if (set % 2 == 0) row = (row + 5) % 20;
        char rowLetter = rowLetters.charAt(row);

        int eastRemainder = ((int) Math.floor(utm.easting)) % 100000;
        int northRemainder = ((int) Math.floor(utm.northing)) % 100000;
        return String.format(Locale.US, "%d%c %c%c %05d %05d",
                utm.zone, utm.band, colLetter, rowLetter, eastRemainder, northRemainder);
    }

    private static char latitudeBand(double lat) {
        String bands = "CDEFGHJKLMNPQRSTUVWXX";
        int index = (int) Math.floor((lat + 80d) / 8d);
        index = Math.max(0, Math.min(bands.length() - 1, index));
        return bands.charAt(index);
    }
}
