package com.rockmap.app.places;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class PmtilesPlaceIndexerTest {
    @Test
    public void hilbertTileIdMatchesPmtilesSpecExample() {
        assertEquals(19078479L, PmtilesPlaceIndexer.zxyToTileId(12, 3423, 1763));
    }

    @Test
    public void decodesNamedPeakFromMvt() throws Exception {
        int z = 13;
        double expectedLat = 38.6741;
        double expectedLon = -106.2462;
        int x = lonToTileX(expectedLon, z);
        int y = latToTileY(expectedLat, z);
        int px = pixelX(expectedLon, z, x);
        int py = pixelY(expectedLat, z, y);

        byte[] feature = pointFeature(new long[]{0, 0, 1, 1, 2, 2}, px, py);
        byte[] layer = layer("pois", List.of(feature),
                List.of("name", "kind", "ele"),
                List.of("Mount Antero", "peak", "4350"));
        byte[] tile = bytesField(3, layer);

        List<PlaceRecord> records = PmtilesPlaceIndexer.decodeMvtForTest(tile, z, x, y);

        assertEquals(1, records.size());
        PlaceRecord record = records.get(0);
        assertEquals("Mount Antero", record.name);
        assertEquals("Peak", record.kind);
        assertTrue(record.aliases.contains("Mt Antero"));
        assertTrue(Math.abs(record.latitude - expectedLat) < 0.001);
        assertTrue(Math.abs(record.longitude - expectedLon) < 0.001);
    }

    @Test
    public void buildsCompleteIndexFromSyntheticInstalledPmtiles() throws Exception {
        File pmtiles = File.createTempFile("rockmap-place-test-", ".pmtiles");
        File output = File.createTempFile("rockmap-place-index-", ".tsv.gz");
        try {
            writeSyntheticPmtiles(pmtiles);
            String sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            PmtilesPlaceIndexer.BuildStats stats = PmtilesPlaceIndexer.build(
                    pmtiles, output, sha, () -> false);

            assertEquals(1, stats.tilesVisited);
            assertEquals(1, stats.tilesFound);
            assertTrue(stats.records >= 600);
            assertTrue(stats.outputBytes > 500);

            String text = gunzipUtf8(output);
            assertTrue(text.contains(PmtilesPlaceIndexer.INDEX_HEADER));
            assertTrue(text.contains(PmtilesPlaceIndexer.BASE_SHA_PREFIX + sha));
            assertTrue(text.contains("Mount Antero\tPeak"));
            assertTrue(text.contains("Buena Vista\tTown"));
            assertTrue(text.contains("Denver\tTown"));
            assertTrue(text.contains("Twin Lakes\tLake"));
        } finally {
            pmtiles.delete();
            output.delete();
        }
    }

    private static void writeSyntheticPmtiles(File output) throws Exception {
        int z = 13;
        double lat = 38.6741;
        double lon = -106.2462;
        int x = lonToTileX(lon, z);
        int y = latToTileY(lat, z);
        long tileId = PmtilesPlaceIndexer.zxyToTileId(z, x, y);

        ArrayList<byte[]> tileLayers = new ArrayList<>();
        ArrayList<String> placeNames = new ArrayList<>();
        placeNames.add("Denver");
        placeNames.add("Buena Vista");
        for (int i = 0; i < 520; i++) placeNames.add("Dummy Town " + i);
        tileLayers.add(bytesField(3, namedPointLayer("places", placeNames, "town", 2000, 2000)));

        ArrayList<String> peakNames = new ArrayList<>();
        peakNames.add("Mount Antero");
        for (int i = 0; i < 60; i++) peakNames.add("Dummy Peak " + i);
        tileLayers.add(bytesField(3, namedPointLayer("pois", peakNames, "peak", 2100, 2100)));

        ArrayList<String> waterNames = new ArrayList<>();
        waterNames.add("Twin Lakes");
        for (int i = 0; i < 60; i++) waterNames.add("Dummy Lake " + i);
        tileLayers.add(bytesField(3, namedPointLayer("water", waterNames, "lake", 2200, 2200)));

        byte[] tile = message(tileLayers.toArray(new byte[0][]));
        byte[] compressedTile = gzip(tile);

        byte[] directory = message(
                rawVarint(1),
                rawVarint(tileId),
                rawVarint(1),
                rawVarint(compressedTile.length),
                rawVarint(1));
        byte[] compressedRoot = gzip(directory);

        byte[] header = new byte[127];
        byte[] magic = "PMTiles".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(magic, 0, header, 0, magic.length);
        header[7] = 3;
        long rootOffset = 127;
        long rootLength = compressedRoot.length;
        long metadataOffset = rootOffset + rootLength;
        long leafOffset = metadataOffset;
        long tileOffset = leafOffset;
        putLongLE(header, 8, rootOffset);
        putLongLE(header, 16, rootLength);
        putLongLE(header, 24, metadataOffset);
        putLongLE(header, 32, 0);
        putLongLE(header, 40, leafOffset);
        putLongLE(header, 48, 0);
        putLongLE(header, 56, tileOffset);
        putLongLE(header, 64, compressedTile.length);
        putLongLE(header, 72, 1);
        putLongLE(header, 80, 1);
        putLongLE(header, 88, 1);
        header[96] = 1; // clustered
        header[97] = 2; // gzip internal compression
        header[98] = 2; // gzip tile compression
        header[99] = 1; // MVT
        header[100] = 13;
        header[101] = 13;
        putIntLE(header, 102, (int) Math.round((lon - 0.0001) * 10_000_000));
        putIntLE(header, 106, (int) Math.round((lat - 0.0001) * 10_000_000));
        putIntLE(header, 110, (int) Math.round((lon + 0.0001) * 10_000_000));
        putIntLE(header, 114, (int) Math.round((lat + 0.0001) * 10_000_000));
        header[118] = 13;
        putIntLE(header, 119, (int) Math.round(lon * 10_000_000));
        putIntLE(header, 123, (int) Math.round(lat * 10_000_000));

        try (FileOutputStream stream = new FileOutputStream(output)) {
            stream.write(header);
            stream.write(compressedRoot);
            stream.write(compressedTile);
        }
    }

    private static byte[] namedPointLayer(String layerName,
                                          List<String> names,
                                          String kind,
                                          int px,
                                          int py) {
        ArrayList<byte[]> features = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            features.add(pointFeature(new long[]{0, i, 1, names.size()}, px, py));
        }
        ArrayList<String> values = new ArrayList<>(names);
        values.add(kind);
        return layer(layerName, features, List.of("name", "kind"), values);
    }

    private static byte[] layer(String name,
                                List<byte[]> features,
                                List<String> keys,
                                List<String> values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, stringField(1, name));
        for (byte[] feature : features) write(out, bytesField(2, feature));
        for (String key : keys) write(out, stringField(3, key));
        for (String value : values) write(out, bytesField(4, stringField(1, value)));
        write(out, varintField(5, 4096));
        write(out, varintField(15, 2));
        return out.toByteArray();
    }

    private static byte[] pointFeature(long[] tags, int px, int py) {
        return message(
                bytesField(2, packed(tags)),
                varintField(3, 1),
                bytesField(4, packed(9, zigZag(px), zigZag(py))));
    }

    private static String gunzipUtf8(File file) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new FileInputStream(file));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }

    private static int lonToTileX(double lon, int z) {
        int n = 1 << z;
        return (int) Math.floor((lon + 180.0) / 360.0 * n);
    }

    private static int latToTileY(double lat, int z) {
        int n = 1 << z;
        double radians = Math.toRadians(lat);
        return (int) Math.floor((1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI) / 2.0 * n);
    }

    private static int pixelX(double lon, int z, int tileX) {
        int n = 1 << z;
        double world = (lon + 180.0) / 360.0 * n;
        return (int) Math.round((world - tileX) * 4096.0);
    }

    private static int pixelY(double lat, int z, int tileY) {
        int n = 1 << z;
        double radians = Math.toRadians(lat);
        double world = (1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI) / 2.0 * n;
        return (int) Math.round((world - tileY) * 4096.0);
    }

    private static byte[] message(byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] field : fields) write(out, field);
        return out.toByteArray();
    }

    private static byte[] stringField(int field, String value) {
        return bytesField(field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytesField(int field, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, (field << 3) | 2);
        writeVarint(out, value.length);
        write(out, value);
        return out.toByteArray();
    }

    private static byte[] varintField(int field, long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, field << 3);
        writeVarint(out, value);
        return out.toByteArray();
    }

    private static byte[] packed(long... values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (long value : values) writeVarint(out, value);
        return out.toByteArray();
    }

    private static byte[] rawVarint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarint(out, value);
        return out.toByteArray();
    }

    private static long zigZag(long value) {
        return (value << 1) ^ (value >> 63);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static void putLongLE(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 8; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }

    private static void putIntLE(byte[] bytes, int offset, int value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (8 * i));
    }
}
