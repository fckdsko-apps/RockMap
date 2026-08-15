package com.rockmap.app.mines;

import com.rockmap.app.minerals.MineralRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HistoricMineCatalog {
    public static final String SOURCE_USGS_MAS = "USGS_MAS";
    public static final String SOURCE_CGS_B40 = "CGS_B40";
    public static final String SOURCE_CGS_MS17 = "CGS_MS17";
    public static final String SOURCE_CGS_USFS_AML = "CGS_USFS_AML";
    public static final String SOURCE_CGS_DISTRICTS = "CGS_DISTRICTS";

    private HistoricMineCatalog() {}

    public static boolean isMineRecord(MineralRecord record) {
        if (record == null) return false;
        String source = normalizeSource(record.sourceCode);
        return SOURCE_USGS_MAS.equals(source)
                || SOURCE_CGS_MS17.equals(source)
                || SOURCE_CGS_USFS_AML.equals(source);
    }

    public static boolean isOpening(MineralRecord record) {
        return record != null && SOURCE_CGS_USFS_AML.equals(normalizeSource(record.sourceCode));
    }

    public static String identity(MineralRecord record) {
        if (record == null) return "";
        return normalizeSource(record.sourceCode) + ":" + record.id;
    }

    public static String displayName(MineralRecord record) {
        if (record == null) return "Historic mine / working";
        String name = record.name == null ? "" : record.name.trim();
        if (!name.isEmpty()
                && !"Unnamed mineral evidence".equalsIgnoreCase(name)
                && !"Unnamed mineral occurrence".equalsIgnoreCase(name)) {
            return name;
        }
        if (isOpening(record)) return "USFS abandoned mine opening";
        if (SOURCE_CGS_MS17.equals(normalizeSource(record.sourceCode))) return "CGS mine / permit site";
        if (SOURCE_USGS_MAS.equals(normalizeSource(record.sourceCode))) return "Historic mine / mineral property";
        if (isDirectMineralEvidence(record)) return "Unnamed mineral occurrence";
        return "Mineral evidence record";
    }

    public static String typeLabel(MineralRecord record) {
        if (record == null) return "Historic mine / working";
        String source = normalizeSource(record.sourceCode);
        if (SOURCE_CGS_USFS_AML.equals(source)) return "USFS abandoned-mine opening";
        if (SOURCE_USGS_MAS.equals(source)) return "Historic mine / mineral property";
        if (SOURCE_CGS_MS17.equals(source)) return "Industrial/nonmetallic mine or permit site";
        return record.evidenceType == null || record.evidenceType.trim().isEmpty()
                ? "Mine / mineral site" : record.evidenceType.trim();
    }

    public static boolean isDirectMineralEvidence(MineralRecord record) {
        if (record == null || isMineRecord(record)) return false;
        String source = normalizeSource(record.sourceCode);
        if (SOURCE_CGS_DISTRICTS.equals(source)) return false;
        if (MineralRecord.SOURCE_MRDS.equals(source) || SOURCE_CGS_B40.equals(source)) return true;
        if ("CGS_GEMSTONES".equals(source) || "CGS_TEACHERS".equals(source)) return true;
        if (source.startsWith("USGS_PUB_")) return true;
        String evidence = record.evidenceType == null ? "" : record.evidenceType.toLowerCase(Locale.US);
        return evidence.contains("documented mineral occurrence")
                || evidence.contains("official gemstone")
                || evidence.contains("official mineral locality");
    }

    public static List<NearbyEvidence> nearbyEvidence(List<MineralRecord> all,
                                                       MineralRecord origin,
                                                       double maxMeters,
                                                       int maxResults) {
        ArrayList<NearbyEvidence> out = new ArrayList<>();
        if (all == null || origin == null || maxMeters <= 0 || maxResults <= 0) return out;

        Set<String> seen = new HashSet<>();
        for (MineralRecord candidate : all) {
            if (!isDirectMineralEvidence(candidate)) continue;
            String id = identity(candidate);
            if (id.isEmpty() || id.equals(identity(origin)) || !seen.add(id)) continue;
            double distance = distanceMeters(origin.latitude, origin.longitude,
                    candidate.latitude, candidate.longitude);
            if (Double.isFinite(distance) && distance <= maxMeters) {
                out.add(new NearbyEvidence(candidate, distance));
            }
        }
        out.sort(Comparator
                .comparingDouble((NearbyEvidence item) -> item.distanceMeters)
                .thenComparing(item -> displayName(item.record), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(item -> identity(item.record)));
        if (out.size() > maxResults) {
            return new ArrayList<>(out.subList(0, maxResults));
        }
        return out;
    }

    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6_371_008.8;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2.0) * Math.sin(dPhi / 2.0)
                + Math.cos(p1) * Math.cos(p2)
                * Math.sin(dLambda / 2.0) * Math.sin(dLambda / 2.0);
        return r * 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
    }

    private static String normalizeSource(String source) {
        return source == null ? "" : source.trim().toUpperCase(Locale.US);
    }

    public static final class NearbyEvidence {
        public final MineralRecord record;
        public final double distanceMeters;

        public NearbyEvidence(MineralRecord record, double distanceMeters) {
            this.record = record;
            this.distanceMeters = distanceMeters;
        }
    }
}
