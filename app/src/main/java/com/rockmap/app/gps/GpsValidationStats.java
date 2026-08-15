package com.rockmap.app.gps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure-Java statistics used by the Alpha 6.4 foreground GPS validation screen. */
public final class GpsValidationStats {
    private GpsValidationStats() {}

    public static final class Sample {
        public final double latitude;
        public final double longitude;
        public final float horizontalAccuracyMeters;

        public Sample(double latitude, double longitude, float horizontalAccuracyMeters) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.horizontalAccuracyMeters = horizontalAccuracyMeters;
        }
    }

    public static final class Summary {
        public final int sampleCount;
        public final double medianLatitude;
        public final double medianLongitude;
        public final double medianReportedAccuracyMeters;
        public final double medianScatterMeters;
        public final double p95ScatterMeters;
        public final double maxScatterMeters;
        public final double referenceErrorMeters;

        private Summary(int sampleCount,
                        double medianLatitude,
                        double medianLongitude,
                        double medianReportedAccuracyMeters,
                        double medianScatterMeters,
                        double p95ScatterMeters,
                        double maxScatterMeters,
                        double referenceErrorMeters) {
            this.sampleCount = sampleCount;
            this.medianLatitude = medianLatitude;
            this.medianLongitude = medianLongitude;
            this.medianReportedAccuracyMeters = medianReportedAccuracyMeters;
            this.medianScatterMeters = medianScatterMeters;
            this.p95ScatterMeters = p95ScatterMeters;
            this.maxScatterMeters = maxScatterMeters;
            this.referenceErrorMeters = referenceErrorMeters;
        }
    }

    public static Summary summarize(List<Sample> input, Double referenceLatitude, Double referenceLongitude) {
        if (input == null || input.isEmpty()) {
            return new Summary(0, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        ArrayList<Sample> valid = new ArrayList<>();
        ArrayList<Double> latitudes = new ArrayList<>();
        ArrayList<Double> longitudes = new ArrayList<>();
        ArrayList<Double> reportedAccuracies = new ArrayList<>();
        for (Sample sample : input) {
            if (sample == null
                    || !Double.isFinite(sample.latitude)
                    || !Double.isFinite(sample.longitude)
                    || sample.latitude < -90d || sample.latitude > 90d
                    || sample.longitude < -180d || sample.longitude > 180d) {
                continue;
            }
            valid.add(sample);
            latitudes.add(sample.latitude);
            longitudes.add(sample.longitude);
            if (Float.isFinite(sample.horizontalAccuracyMeters) && sample.horizontalAccuracyMeters >= 0f) {
                reportedAccuracies.add((double) sample.horizontalAccuracyMeters);
            }
        }

        if (valid.isEmpty()) {
            return new Summary(0, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        double medianLatitude = median(latitudes);
        double medianLongitude = median(longitudes);
        ArrayList<Double> scatter = new ArrayList<>();
        double maxScatter = 0d;
        for (Sample sample : valid) {
            double distance = distanceMeters(medianLatitude, medianLongitude,
                    sample.latitude, sample.longitude);
            scatter.add(distance);
            if (distance > maxScatter) maxScatter = distance;
        }
        Collections.sort(scatter);

        double referenceError = Double.NaN;
        if (referenceLatitude != null && referenceLongitude != null
                && Double.isFinite(referenceLatitude) && Double.isFinite(referenceLongitude)
                && referenceLatitude >= -90d && referenceLatitude <= 90d
                && referenceLongitude >= -180d && referenceLongitude <= 180d) {
            referenceError = distanceMeters(medianLatitude, medianLongitude,
                    referenceLatitude, referenceLongitude);
        }

        return new Summary(
                valid.size(),
                medianLatitude,
                medianLongitude,
                reportedAccuracies.isEmpty() ? Double.NaN : median(reportedAccuracies),
                percentileSorted(scatter, 0.50d),
                percentileSorted(scatter, 0.95d),
                maxScatter,
                referenceError);
    }

    static double median(List<Double> values) {
        if (values == null || values.isEmpty()) return Double.NaN;
        ArrayList<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        if ((size & 1) == 1) return sorted.get(size / 2);
        return (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2d;
    }

    static double percentileSorted(List<Double> sorted, double percentile) {
        if (sorted == null || sorted.isEmpty()) return Double.NaN;
        double p = Math.max(0d, Math.min(1d, percentile));
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        if (index < 0) index = 0;
        if (index >= sorted.size()) index = sorted.size() - 1;
        return sorted.get(index);
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_008.8d;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2d) * Math.sin(dPhi / 2d)
                + Math.cos(p1) * Math.cos(p2)
                * Math.sin(dLambda / 2d) * Math.sin(dLambda / 2d);
        return r * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }
}
