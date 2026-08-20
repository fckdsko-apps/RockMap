package com.rockmap.app.trips;

import java.util.List;
import java.util.Locale;

public final class TripExport {
    private TripExport() {}

    public static String geoJson(TripEntity trip, List<TripItemEntity> items) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"type\": \"FeatureCollection\",\n")
                .append("  \"rockmapSchema\": 2,\n")
                .append("  \"trip\": {")
                .append("\"name\":\"").append(json(trip.name)).append("\",")
                .append("\"plannedDate\":\"").append(json(trip.plannedDate)).append("\",")
                .append("\"notes\":\"").append(json(trip.notes)).append("\"},\n")
                .append("  \"features\": [\n");
        for (int i = 0; i < items.size(); i++) {
            TripItemEntity item = items.get(i);
            out.append("    {\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                    .append(number(item.longitude)).append(',').append(number(item.latitude))
                    .append("]},\"properties\":{")
                    .append("\"order\":").append(i + 1).append(',')
                    .append("\"name\":\"").append(json(item.name)).append("\",")
                    .append("\"kind\":\"").append(json(item.kind)).append("\",")
                    .append("\"context\":\"").append(json(item.context)).append("\",")
                    .append("\"notes\":\"").append(json(item.notes)).append("\",")
                    .append("\"sourceType\":\"").append(json(item.sourceType)).append("\",")
                    .append("\"sourceRef\":\"").append(json(item.sourceRef)).append("\"}}")
                    .append(i + 1 == items.size() ? "\n" : ",\n");
        }
        out.append("  ]\n}\n");
        return out.toString();
    }

    public static String rockMapXml(TripEntity trip, List<TripItemEntity> items) {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<rockmapTrip schema=\"2\">\n")
                .append("  <trip>\n")
                .append("    <name>").append(xml(trip.name)).append("</name>\n")
                .append("    <plannedDate>").append(xml(trip.plannedDate)).append("</plannedDate>\n")
                .append("    <notes>").append(xml(trip.notes)).append("</notes>\n")
                .append("  </trip>\n")
                .append("  <stops>\n");
        for (int i = 0; i < items.size(); i++) {
            TripItemEntity item = items.get(i);
            out.append("    <stop order=\"").append(i + 1).append("\">\n")
                    .append("      <name>").append(xml(item.name)).append("</name>\n")
                    .append("      <kind>").append(xml(item.kind)).append("</kind>\n")
                    .append("      <context>").append(xml(item.context)).append("</context>\n")
                    .append("      <latitude>").append(number(item.latitude)).append("</latitude>\n")
                    .append("      <longitude>").append(number(item.longitude)).append("</longitude>\n")
                    .append("      <notes>").append(xml(item.notes)).append("</notes>\n")
                    .append("      <sourceType>").append(xml(item.sourceType)).append("</sourceType>\n")
                    .append("      <sourceRef>").append(xml(item.sourceRef)).append("</sourceRef>\n")
                    .append("    </stop>\n");
        }
        out.append("  </stops>\n")
                .append("</rockmapTrip>\n");
        return out.toString();
    }

    public static String gpx(TripEntity trip, List<TripItemEntity> items) {
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<gpx version=\"1.1\" creator=\"RockMap\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
                .append("  <metadata><name>").append(xml(trip.name)).append("</name>");
        String tripDescription = joinNonBlank(trip.plannedDate, trip.notes);
        if (!tripDescription.isEmpty()) {
            out.append("<desc>").append(xml(tripDescription)).append("</desc>");
        }
        out.append("</metadata>\n");
        for (TripItemEntity item : items) {
            out.append("  <wpt lat=\"").append(number(item.latitude))
                    .append("\" lon=\"").append(number(item.longitude)).append("\">\n")
                    .append("    <name>").append(xml(item.name)).append("</name>\n");
            String description = joinNonBlank(item.kind, item.context, item.notes);
            if (!description.isEmpty()) {
                out.append("    <desc>").append(xml(description)).append("</desc>\n");
            }
            if (!safe(item.kind).isEmpty()) {
                out.append("    <type>").append(xml(item.kind)).append("</type>\n");
            }
            out.append("  </wpt>\n");
        }
        out.append("</gpx>\n");
        return out.toString();
    }

    public static String csv(TripEntity trip, List<TripItemEntity> items) {
        StringBuilder out = new StringBuilder();
        out.append("order,name,kind,latitude,longitude,context,notes,source_type,source_ref,trip,planned_date,trip_notes\n");
        for (int i = 0; i < items.size(); i++) {
            TripItemEntity item = items.get(i);
            out.append(i + 1).append(',')
                    .append(csvField(item.name)).append(',')
                    .append(csvField(item.kind)).append(',')
                    .append(number(item.latitude)).append(',')
                    .append(number(item.longitude)).append(',')
                    .append(csvField(item.context)).append(',')
                    .append(csvField(item.notes)).append(',')
                    .append(csvField(item.sourceType)).append(',')
                    .append(csvField(item.sourceRef)).append(',')
                    .append(csvField(trip.name)).append(',')
                    .append(csvField(trip.plannedDate)).append(',')
                    .append(csvField(trip.notes)).append('\n');
        }
        return out.toString();
    }

    private static String joinNonBlank(String... values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            String clean = safe(value).trim();
            if (clean.isEmpty()) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(clean);
        }
        return out.toString();
    }

    private static String number(double value) {
        return String.format(Locale.US, "%.7f", value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String json(String value) {
        String text = safe(value);
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    private static String xml(String value) {
        return safe(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String csvField(String value) {
        String text = safe(value).replace("\r\n", "\n").replace('\r', '\n');
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
