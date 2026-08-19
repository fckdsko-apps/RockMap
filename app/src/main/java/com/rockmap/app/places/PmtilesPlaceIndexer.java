package com.rockmap.app.places;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Builds RockMap's compact place index directly from the already-installed PMTiles basemap.
 *
 * This reader intentionally supports only the PMTiles/MVT contract RockMap already validates:
 * PMTiles v3, MVT tiles, and gzip/no-compression directories/tiles. It fails closed on any
 * future format drift instead of silently returning misleading search results.
 */
public final class PmtilesPlaceIndexer {
    static final String INDEX_HEADER = "# RockMap local place index v2";
    static final String BASE_SHA_PREFIX = "# base_sha256=";
    static final int SEARCH_ZOOM = 13;

    private static final int PMTILES_HEADER_BYTES = 127;
    private static final int PMTILES_VERSION = 3;
    private static final int COMPRESSION_NONE = 1;
    private static final int COMPRESSION_GZIP = 2;
    private static final int TILE_TYPE_MVT = 1;
    private static final int MAX_DIRECTORY_COMPRESSED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIRECTORY_DECOMPRESSED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TILE_COMPRESSED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TILE_DECOMPRESSED_BYTES = 24 * 1024 * 1024;
    private static final int MAX_LAYER_BYTES = 16 * 1024 * 1024;
    private static final int MAX_FEATURES_PER_LAYER = 250_000;
    private static final int MAX_TILES_TO_SCAN = 25_000;
    private static final int MAX_INDEX_RECORDS = 80_000;
    private static final long MAX_INDEX_BYTES = 8L * 1024L * 1024L;

    private static final Set<String> SEARCH_LAYERS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("places", "pois", "water")));

    private PmtilesPlaceIndexer() {}

    public static final class BuildStats {
        public final int tilesVisited;
        public final int tilesFound;
        public final int records;
        public final long outputBytes;

        BuildStats(int tilesVisited, int tilesFound, int records, long outputBytes) {
            this.tilesVisited = tilesVisited;
            this.tilesFound = tilesFound;
            this.records = records;
            this.outputBytes = outputBytes;
        }
    }

    public static BuildStats build(File pmtiles,
                                   File output,
                                   String baseSha256,
                                   BooleanSupplier cancelled) throws IOException {
        if (pmtiles == null || !pmtiles.isFile() || pmtiles.length() < PMTILES_HEADER_BYTES) {
            throw new IOException("installed basemap PMTiles is missing or truncated");
        }
        if (baseSha256 == null || !baseSha256.matches("(?i)[0-9a-f]{64}")) {
            throw new IOException("active basemap SHA-256 is invalid");
        }
        if (cancelled == null) cancelled = () -> false;

        File parent = output.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("cannot create local place-index directory");
        }

        LinkedHashMap<String, Aggregate> aggregates = new LinkedHashMap<>();
        int tilesVisited = 0;
        int tilesFound = 0;

        try (RandomAccessFile raf = new RandomAccessFile(pmtiles, "r")) {
            Header header = Header.read(raf);
            header.validateForRockMap(pmtiles.length());
            if (header.maxZoom < SEARCH_ZOOM) {
                throw new IOException("installed basemap does not contain zoom " + SEARCH_ZOOM);
            }

            List<Entry> root = decodeDirectory(
                    decompress(readRange(raf, header.rootOffset, header.rootLength,
                                    MAX_DIRECTORY_COMPRESSED_BYTES),
                            header.internalCompression, MAX_DIRECTORY_DECOMPRESSED_BYTES));
            if (root.isEmpty()) throw new IOException("PMTiles root directory is empty");

            ArrayList<TileCoord> tiles = enumerateTiles(header, SEARCH_ZOOM);
            if (tiles.size() > MAX_TILES_TO_SCAN) {
                throw new IOException("installed basemap search scan would exceed safe tile count: " + tiles.size());
            }
            tiles.sort(Comparator.comparingLong(tile -> tile.tileId));

            Map<LeafKey, List<Entry>> leafCache = new HashMap<>();
            TileBlobCache blobCache = new TileBlobCache();

            for (TileCoord tile : tiles) {
                if ((tilesVisited & 127) == 0 && cancelled.getAsBoolean()) {
                    throw new IOException("offline place indexing was cancelled");
                }
                tilesVisited++;
                TileLocation location = findTileLocation(
                        raf, header, root, leafCache, tile.tileId);
                if (location == null) continue;
                tilesFound++;

                byte[] mvt;
                if (blobCache.matches(location.absoluteOffset, location.length)) {
                    mvt = blobCache.bytes;
                } else {
                    byte[] compressed = readRange(raf, location.absoluteOffset, location.length,
                            MAX_TILE_COMPRESSED_BYTES);
                    mvt = decompress(compressed, header.tileCompression, MAX_TILE_DECOMPRESSED_BYTES);
                    blobCache.set(location.absoluteOffset, location.length, mvt);
                }

                for (Candidate candidate : decodeMvt(mvt, SEARCH_ZOOM, tile.x, tile.y)) {
                    addCandidate(aggregates, candidate);
                    if (aggregates.size() > MAX_INDEX_RECORDS * 2) {
                        throw new IOException("local place-index candidate set exceeded safe size");
                    }
                }
            }
        }

        ArrayList<Candidate> records = finishAggregates(aggregates);
        addNearbyPlaceContext(records);
        validate(records);
        writeIndex(records, output, baseSha256.toLowerCase(Locale.US));
        if (!output.isFile() || output.length() <= 0 || output.length() > MAX_INDEX_BYTES) {
            throw new IOException("generated local place index has unsafe size: "
                    + (output.isFile() ? output.length() : 0) + " bytes");
        }
        return new BuildStats(tilesVisited, tilesFound, records.size(), output.length());
    }

    static long zxyToTileId(int z, int x, int y) {
        if (z < 0 || z > 26) throw new IllegalArgumentException("unsupported zoom");
        int n = 1 << z;
        if (x < 0 || y < 0 || x >= n || y >= n) {
            throw new IllegalArgumentException("tile outside zoom bounds");
        }
        long nLong = 1L << z;
        long acc = (nLong * nLong - 1L) / 3L;
        int tx = x;
        int ty = y;
        for (int s = 1 << (z - 1); s > 0; s >>= 1) {
            int rx = tx & s;
            int ry = ty & s;
            acc += (((3L * rx) ^ ry) * (long) s);
            int[] rotated = rotate(s, tx, ty, rx, ry);
            tx = rotated[0];
            ty = rotated[1];
        }
        return acc;
    }

    private static int[] rotate(int n, int x, int y, int rx, int ry) {
        if (ry == 0) {
            if (rx != 0) return new int[]{n - 1 - y, n - 1 - x};
            return new int[]{y, x};
        }
        return new int[]{x, y};
    }

    private static ArrayList<TileCoord> enumerateTiles(Header header, int z) {
        int n = 1 << z;
        int minX = clamp(lonToTileX(header.minLon, z), 0, n - 1);
        int maxX = clamp(lonToTileX(header.maxLon, z), 0, n - 1);
        int minY = clamp(latToTileY(header.maxLat, z), 0, n - 1);
        int maxY = clamp(latToTileY(header.minLat, z), 0, n - 1);
        ArrayList<TileCoord> out = new ArrayList<>((maxX - minX + 1) * (maxY - minY + 1));
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                out.add(new TileCoord(x, y, zxyToTileId(z, x, y)));
            }
        }
        return out;
    }

    private static int lonToTileX(double lon, int z) {
        double n = 1 << z;
        double safe = Math.max(-180.0, Math.min(180.0 - 1e-12, lon));
        return (int) Math.floor((safe + 180.0) / 360.0 * n);
    }

    private static int latToTileY(double lat, int z) {
        double n = 1 << z;
        double safe = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double radians = Math.toRadians(safe);
        double mercator = Math.log(Math.tan(radians) + 1.0 / Math.cos(radians));
        return (int) Math.floor((1.0 - mercator / Math.PI) / 2.0 * n);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static TileLocation findTileLocation(RandomAccessFile raf,
                                                 Header header,
                                                 List<Entry> root,
                                                 Map<LeafKey, List<Entry>> leafCache,
                                                 long tileId) throws IOException {
        List<Entry> directory = root;
        for (int depth = 0; depth <= 3; depth++) {
            Entry entry = findTile(directory, tileId);
            if (entry == null) return null;
            if (entry.runLength > 0) {
                long absolute = Math.addExact(header.tileDataOffset, entry.offset);
                return new TileLocation(absolute, entry.length);
            }
            LeafKey key = new LeafKey(entry.offset, entry.length);
            directory = leafCache.get(key);
            if (directory == null) {
                long absolute = Math.addExact(header.leafDirectoryOffset, entry.offset);
                byte[] bytes = readRange(raf, absolute, entry.length, MAX_DIRECTORY_COMPRESSED_BYTES);
                directory = decodeDirectory(decompress(bytes, header.internalCompression,
                        MAX_DIRECTORY_DECOMPRESSED_BYTES));
                if (directory.isEmpty()) throw new IOException("PMTiles leaf directory is empty");
                leafCache.put(key, directory);
            }
        }
        throw new IOException("PMTiles directory depth exceeds supported maximum");
    }

    private static Entry findTile(List<Entry> entries, long tileId) {
        int low = 0;
        int high = entries.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Entry entry = entries.get(mid);
            long cmp = tileId - entry.tileId;
            if (cmp > 0) low = mid + 1;
            else if (cmp < 0) high = mid - 1;
            else return entry;
        }
        if (high >= 0) {
            Entry entry = entries.get(high);
            if (entry.runLength == 0) return entry;
            long delta = tileId - entry.tileId;
            if (delta >= 0 && delta < entry.runLength) return entry;
        }
        return null;
    }

    static List<Entry> decodeDirectoryForTest(byte[] bytes) throws IOException {
        return decodeDirectory(bytes);
    }

    private static List<Entry> decodeDirectory(byte[] bytes) throws IOException {
        ProtoReader reader = new ProtoReader(bytes);
        long countLong = reader.readVarint64();
        if (countLong <= 0 || countLong > 1_000_000) {
            throw new IOException("invalid PMTiles directory entry count: " + countLong);
        }
        int count = (int) countLong;
        ArrayList<Entry> entries = new ArrayList<>(count);
        long lastId = 0;
        for (int i = 0; i < count; i++) {
            long delta = reader.readVarint64();
            lastId = Math.addExact(lastId, delta);
            entries.add(new Entry(lastId, 0, 0, 0));
        }
        for (int i = 0; i < count; i++) entries.get(i).runLength = reader.readVarint64();
        for (int i = 0; i < count; i++) {
            long length = reader.readVarint64();
            if (length <= 0 || length > Integer.MAX_VALUE) throw new IOException("invalid PMTiles entry length");
            entries.get(i).length = (int) length;
        }
        long nextOffset = 0;
        for (int i = 0; i < count; i++) {
            long encoded = reader.readVarint64();
            long offset;
            if (encoded == 0 && i > 0) offset = nextOffset;
            else {
                if (encoded == 0) throw new IOException("invalid first PMTiles directory offset");
                offset = encoded - 1;
            }
            entries.get(i).offset = offset;
            nextOffset = Math.addExact(offset, entries.get(i).length);
        }
        if (reader.hasRemaining()) {
            throw new IOException("unexpected trailing PMTiles directory bytes");
        }
        return entries;
    }

    private static byte[] readRange(RandomAccessFile raf,
                                    long offset,
                                    long length,
                                    int maxBytes) throws IOException {
        if (offset < 0 || length <= 0 || length > maxBytes || length > Integer.MAX_VALUE) {
            throw new IOException("unsafe PMTiles byte range");
        }
        long end;
        try {
            end = Math.addExact(offset, length);
        } catch (ArithmeticException ex) {
            throw new IOException("PMTiles byte range overflow", ex);
        }
        if (end > raf.length()) throw new IOException("PMTiles byte range exceeds file size");
        byte[] out = new byte[(int) length];
        raf.seek(offset);
        raf.readFully(out);
        return out;
    }

    private static byte[] decompress(byte[] bytes, int compression, int maxOutput) throws IOException {
        if (compression == COMPRESSION_NONE) {
            if (bytes.length > maxOutput) throw new IOException("uncompressed PMTiles block exceeds limit");
            return bytes;
        }
        if (compression != COMPRESSION_GZIP) {
            throw new IOException("unsupported PMTiles compression: " + compression);
        }
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes));
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxOutput, bytes.length * 3))) {
            byte[] buffer = new byte[32 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxOutput) throw new IOException("decompressed PMTiles block exceeds limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static List<PlaceRecord> decodeMvtForTest(byte[] mvt, int z, int x, int y) throws IOException {
        ArrayList<Candidate> candidates = decodeMvt(mvt, z, x, y);
        ArrayList<PlaceRecord> records = new ArrayList<>();
        for (Candidate item : candidates) records.add(item.toRecord());
        return records;
    }

    private static ArrayList<Candidate> decodeMvt(byte[] mvt, int z, int tileX, int tileY) throws IOException {
        ArrayList<Candidate> out = new ArrayList<>();
        ProtoReader tileReader = new ProtoReader(mvt);
        while (tileReader.hasRemaining()) {
            int tag = tileReader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 3 && wire == 2) {
                byte[] layerBytes = tileReader.readLengthDelimited(MAX_LAYER_BYTES);
                decodeLayer(layerBytes, z, tileX, tileY, out);
            } else {
                tileReader.skipField(wire);
            }
        }
        return out;
    }

    private static void decodeLayer(byte[] bytes,
                                    int z,
                                    int tileX,
                                    int tileY,
                                    List<Candidate> out) throws IOException {
        String name = readLayerName(bytes);
        if (!SEARCH_LAYERS.contains(name)) return;

        ProtoReader reader = new ProtoReader(bytes);
        int extent = 4096;
        ArrayList<byte[]> rawFeatures = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>();
        ArrayList<Object> values = new ArrayList<>();

        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) reader.readString(1024);
            else if (field == 2 && wire == 2) {
                if (rawFeatures.size() >= MAX_FEATURES_PER_LAYER) {
                    throw new IOException("MVT layer feature count exceeds safe limit");
                }
                rawFeatures.add(reader.readLengthDelimited(MAX_LAYER_BYTES));
            } else if (field == 3 && wire == 2) keys.add(reader.readString(16 * 1024));
            else if (field == 4 && wire == 2) values.add(readValue(reader.readLengthDelimited(256 * 1024)));
            else if (field == 5 && wire == 0) {
                long parsed = reader.readVarint64();
                if (parsed <= 0 || parsed > 1_000_000) throw new IOException("invalid MVT extent");
                extent = (int) parsed;
            } else reader.skipField(wire);
        }

        for (byte[] rawFeature : rawFeatures) {
            Candidate candidate = decodeFeature(rawFeature, name, keys, values, extent, z, tileX, tileY);
            if (candidate != null) out.add(candidate);
        }
    }


    private static String readLayerName(byte[] bytes) throws IOException {
        ProtoReader reader = new ProtoReader(bytes);
        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) return reader.readString(1024);
            reader.skipField(wire);
        }
        return "";
    }

    private static Object readValue(byte[] bytes) throws IOException {
        ProtoReader reader = new ProtoReader(bytes);
        Object value = "";
        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 1 && wire == 2) value = reader.readString(256 * 1024);
            else if (field == 2 && wire == 5) value = Float.intBitsToFloat(reader.readFixed32());
            else if (field == 3 && wire == 1) value = Double.longBitsToDouble(reader.readFixed64());
            else if (field == 4 && wire == 0) value = reader.readVarint64();
            else if (field == 5 && wire == 0) value = reader.readVarint64();
            else if (field == 6 && wire == 0) value = zigZag64(reader.readVarint64());
            else if (field == 7 && wire == 0) value = reader.readVarint64() != 0;
            else reader.skipField(wire);
        }
        return value;
    }

    private static Candidate decodeFeature(byte[] bytes,
                                           String layer,
                                           List<String> keys,
                                           List<Object> values,
                                           int extent,
                                           int z,
                                           int tileX,
                                           int tileY) throws IOException {
        ProtoReader reader = new ProtoReader(bytes);
        byte[] tags = null;
        byte[] geometry = null;
        while (reader.hasRemaining()) {
            int tag = reader.readTag();
            int field = tag >>> 3;
            int wire = tag & 7;
            if (field == 2 && wire == 2) tags = reader.readLengthDelimited(2 * 1024 * 1024);
            else if (field == 4 && wire == 2) geometry = reader.readLengthDelimited(8 * 1024 * 1024);
            else reader.skipField(wire);
        }
        if (tags == null || geometry == null) return null;

        HashMap<String, Object> properties = decodeTags(tags, keys, values);
        String displayName = clean(stringValue(properties.get("name:en")));
        if (displayName.isEmpty()) displayName = clean(stringValue(properties.get("name")));
        if (displayName.isEmpty()) return null;

        Classification classification = classify(layer, properties);
        if (classification == null) return null;
        double[] point = geometryCenter(geometry, extent, z, tileX, tileY);
        if (point == null) return null;
        if (point[0] < -90 || point[0] > 90 || point[1] < -180 || point[1] > 180) return null;

        HashSet<String> aliases = new HashSet<>();
        String rawName = clean(stringValue(properties.get("name")));
        String englishName = clean(stringValue(properties.get("name:en")));
        if (!rawName.isEmpty() && !rawName.equals(displayName)) aliases.add(rawName);
        if (!englishName.isEmpty() && !englishName.equals(displayName)) aliases.add(englishName);
        if (displayName.toLowerCase(Locale.US).startsWith("mount ")) {
            String rest = displayName.substring(6).trim();
            if (!rest.isEmpty()) {
                aliases.add(rest);
                aliases.add("Mt " + rest);
                aliases.add("Mtn " + rest);
            }
        }

        int importance = classification.baseImportance;
        double minZoom = numberValue(properties.get("min_zoom"), 99);
        if (minZoom < 30) {
            importance += Math.max(0, Math.min(22, (int) Math.round((14 - minZoom) * 2)));
        }
        importance = Math.max(1, Math.min(120, importance));

        String hint = classification.hint;
        if ("Peak".equals(classification.kind)) {
            String elevation = clean(stringValue(properties.get("ele")));
            if (!elevation.isEmpty()) hint = "elev. " + elevation;
        }
        return new Candidate(displayName, classification.kind, hint,
                point[0], point[1], aliases, importance, layer);
    }

    private static HashMap<String, Object> decodeTags(byte[] bytes,
                                                       List<String> keys,
                                                       List<Object> values) throws IOException {
        ProtoReader reader = new ProtoReader(bytes);
        HashMap<String, Object> out = new HashMap<>();
        while (reader.hasRemaining()) {
            long keyIndex = reader.readVarint64();
            if (!reader.hasRemaining()) throw new IOException("odd MVT feature tag list");
            long valueIndex = reader.readVarint64();
            if (keyIndex < 0 || keyIndex >= keys.size() || valueIndex < 0 || valueIndex >= values.size()) {
                throw new IOException("MVT feature tag index outside layer tables");
            }
            String key = keys.get((int) keyIndex);
            if ("name".equals(key) || "name:en".equals(key) || "kind".equals(key)
                    || "kind_detail".equals(key) || "ele".equals(key)
                    || "min_zoom".equals(key) || "reservoir".equals(key)) {
                out.put(key, values.get((int) valueIndex));
            }
        }
        return out;
    }

    private static double[] geometryCenter(byte[] bytes,
                                           int extent,
                                           int z,
                                           int tileX,
                                           int tileY) throws IOException {
        ProtoReader reader = new ProtoReader(bytes);
        long x = 0;
        long y = 0;
        long minX = Long.MAX_VALUE;
        long minY = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE;
        long maxY = Long.MIN_VALUE;
        int pointCount = 0;

        while (reader.hasRemaining()) {
            long commandInteger = reader.readVarint64();
            int command = (int) (commandInteger & 0x7);
            long count = commandInteger >>> 3;
            if (count <= 0 || count > 2_000_000) throw new IOException("invalid MVT geometry command count");
            if (command == 1 || command == 2) {
                for (long i = 0; i < count; i++) {
                    if (!reader.hasRemaining()) throw new IOException("truncated MVT geometry");
                    x += zigZag64(reader.readVarint64());
                    y += zigZag64(reader.readVarint64());
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    pointCount++;
                }
            } else if (command == 7) {
                // ClosePath has no parameters.
            } else {
                throw new IOException("unsupported MVT geometry command: " + command);
            }
        }
        if (pointCount == 0) return null;
        double px = (minX + maxX) / 2.0;
        double py = (minY + maxY) / 2.0;
        double n = 1 << z;
        double worldX = (tileX + px / extent) / n;
        double worldY = (tileY + py / extent) / n;
        double lon = worldX * 360.0 - 180.0;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * worldY))));
        return new double[]{lat, lon};
    }

    private static Classification classify(String layer, Map<String, Object> properties) {
        String kind = clean(stringValue(properties.get("kind"))).toLowerCase(Locale.US);
        String detail = clean(stringValue(properties.get("kind_detail"))).toLowerCase(Locale.US);
        if ("places".equals(layer)) {
            String value = !detail.isEmpty() ? detail : kind;
            if ("city".equals(value)) return new Classification("City", 100, "Colorado");
            if ("town".equals(value)) return new Classification("Town", 92, "Colorado");
            if ("village".equals(value)) return new Classification("Village", 80, "Colorado");
            if ("hamlet".equals(value)) return new Classification("Hamlet", 68, "Colorado");
            if ("locality".equals(value) || "isolated_dwelling".equals(value)) {
                return new Classification("Locality", 58, "Colorado");
            }
            if ("region".equals(value) || "state".equals(value)) {
                return new Classification("Region", 70, "Colorado");
            }
            // Named place features are still useful even if Protomaps adds a new subtype.
            return new Classification("Place", 52, "Colorado");
        }
        if ("pois".equals(layer)) {
            String value = !kind.isEmpty() ? kind : detail;
            if ("peak".equals(value) || "volcano".equals(value)) return new Classification("Peak", 84, "");
            if ("saddle".equals(value) || "mountain_pass".equals(value)) return new Classification("Mountain pass", 72, "");
            if ("viewpoint".equals(value)) return new Classification("Viewpoint", 62, "");
            if ("landmark".equals(value) || "attraction".equals(value)) return new Classification("Landmark", 58, "");
            if ("national_park".equals(value) || "nature_reserve".equals(value)) return new Classification("Park / protected area", 64, "");
            if ("park".equals(value)) return new Classification("Park", 52, "");
            if ("camp_site".equals(value)) return new Classification("Campground", 58, "");
            if ("trailhead".equals(value)) return new Classification("Trailhead", 62, "");
            if ("monument".equals(value) || "memorial".equals(value)) return new Classification("Historic landmark", 50, "");
            if ("historic".equals(value) || "archaeological_site".equals(value) || "ruins".equals(value)) {
                return new Classification("Historic site", 48, "");
            }
            if ("cave_entrance".equals(value)) return new Classification("Cave", 46, "");
            if ("spring".equals(value)) return new Classification("Spring", 44, "");
            return null;
        }
        if ("water".equals(layer)) {
            String value = !detail.isEmpty() ? detail : kind;
            if (booleanValue(properties.get("reservoir")) || "reservoir".equals(value)) {
                return new Classification("Reservoir", 58, "");
            }
            if ("lake".equals(value)) return new Classification("Lake", 56, "");
            if ("river".equals(value)) return new Classification("River", 52, "");
            if ("stream".equals(value) || "creek".equals(value)) return new Classification("Stream / creek", 46, "");
            if ("canal".equals(value)) return new Classification("Canal", 42, "");
            if ("basin".equals(value)) return new Classification("Basin", 40, "");
            if ("water".equals(value) || value.isEmpty()) return new Classification("Lake / water", 46, "");
            return new Classification("Water", 40, "");
        }
        return null;
    }

    private static void addCandidate(Map<String, Aggregate> aggregates, Candidate candidate) {
        String normalized = normalize(candidate.name);
        if (normalized.length() < 2) return;
        String key;
        if ("water".equals(candidate.layer)) {
            // Keep separate occurrences/segments of common water names while collapsing tile-fragment duplicates.
            double cellLat = Math.rint(candidate.lat * 4.0) / 4.0;
            double cellLon = Math.rint(candidate.lon * 4.0) / 4.0;
            key = candidate.kind + "|" + normalized + "|" + cellLat + "|" + cellLon;
        } else {
            key = candidate.kind + "|" + normalized + "|"
                    + Math.round(candidate.lat * 50.0) + "|" + Math.round(candidate.lon * 50.0);
        }
        Aggregate aggregate = aggregates.get(key);
        if (aggregate == null) aggregates.put(key, new Aggregate(candidate));
        else aggregate.add(candidate);
    }

    private static ArrayList<Candidate> finishAggregates(Map<String, Aggregate> aggregates) throws IOException {
        ArrayList<Candidate> out = new ArrayList<>(aggregates.size());
        for (Aggregate aggregate : aggregates.values()) out.add(aggregate.finish());
        if (out.size() > MAX_INDEX_RECORDS) {
            throw new IOException("local place index exceeds safe record count: " + out.size());
        }
        return out;
    }

    private static void addNearbyPlaceContext(List<Candidate> records) {
        ArrayList<Candidate> places = new ArrayList<>();
        for (Candidate item : records) {
            if (item.kind.equals("City") || item.kind.equals("Town") || item.kind.equals("Village")
                    || item.kind.equals("Hamlet") || item.kind.equals("Locality") || item.kind.equals("Place")) {
                places.add(item);
            }
        }
        HashMap<String, ArrayList<Candidate>> grid = new HashMap<>();
        for (Candidate place : places) {
            String key = ((int) Math.floor(place.lat)) + ":" + ((int) Math.floor(place.lon));
            grid.computeIfAbsent(key, ignored -> new ArrayList<>()).add(place);
        }
        for (Candidate item : records) {
            if (item.context.equals("Colorado")) continue;
            Candidate best = null;
            double bestKm = 65.0;
            int gy = (int) Math.floor(item.lat);
            int gx = (int) Math.floor(item.lon);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    List<Candidate> bucket = grid.get((gy + dy) + ":" + (gx + dx));
                    if (bucket == null) continue;
                    for (Candidate place : bucket) {
                        if (normalize(place.name).equals(normalize(item.name))) continue;
                        double km = haversineKm(item.lat, item.lon, place.lat, place.lon);
                        if (km < bestKm) {
                            best = place;
                            bestKm = km;
                        }
                    }
                }
            }
            if (best != null) {
                item.context = item.context.isEmpty() ? "near " + best.name : item.context + " · near " + best.name;
            } else if (item.context.isEmpty()) {
                item.context = "Colorado";
            }
        }
    }

    private static double haversineKm(double aLat, double aLon, double bLat, double bLon) {
        double radius = 6371.0088;
        double p1 = Math.toRadians(aLat);
        double p2 = Math.toRadians(bLat);
        double dp = Math.toRadians(bLat - aLat);
        double dl = Math.toRadians(bLon - aLon);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return radius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0.0, 1.0 - h)));
    }

    private static void validate(List<Candidate> records) throws IOException {
        if (records.size() < 500) throw new IOException("local place index is unexpectedly small: " + records.size());
        boolean denver = false;
        boolean buenaVista = false;
        boolean mountAntero = false;
        boolean peak = false;
        boolean water = false;
        boolean place = false;
        for (Candidate item : records) {
            String normalized = normalize(item.name);
            if ("denver".equals(normalized)) denver = true;
            if ("buena vista".equals(normalized)) buenaVista = true;
            if ("mount antero".equals(normalized)) mountAntero = true;
            if ("Peak".equals(item.kind)) peak = true;
            if ("water".equals(item.layer)) water = true;
            if ("places".equals(item.layer)) place = true;
        }
        if (!denver) throw new IOException("installed basemap search sanity record missing: Denver");
        if (!buenaVista) throw new IOException("installed basemap search sanity record missing: Buena Vista");
        if (!mountAntero) throw new IOException("installed basemap search sanity record missing: Mount Antero");
        if (!peak) throw new IOException("installed basemap search scan contains no peaks");
        if (!water) throw new IOException("installed basemap search scan contains no named water features");
        if (!place) throw new IOException("installed basemap search scan contains no populated places");
    }

    private static void writeIndex(List<Candidate> records, File output, String baseSha256) throws IOException {
        records.sort((left, right) -> {
            int byImportance = Integer.compare(right.importance, left.importance);
            if (byImportance != 0) return byImportance;
            int byName = String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name);
            if (byName != 0) return byName;
            int byLat = Double.compare(left.lat, right.lat);
            if (byLat != 0) return byLat;
            return Double.compare(left.lon, right.lon);
        });

        try (FileOutputStream file = new FileOutputStream(output);
             BufferedOutputStream buffered = new BufferedOutputStream(file, 64 * 1024);
             GZIPOutputStream gzip = new GZIPOutputStream(buffered, 64 * 1024)) {
            writeLine(gzip, INDEX_HEADER);
            writeLine(gzip, BASE_SHA_PREFIX + baseSha256);
            writeLine(gzip, "# source=installed RockMap PMTiles; zoom=13; layers=places,pois,water");
            for (Candidate item : records) {
                ArrayList<String> aliases = new ArrayList<>(item.aliases);
                aliases.remove(item.name);
                Collections.sort(aliases, String.CASE_INSENSITIVE_ORDER);
                String row = safeField(item.name) + "\t" + safeField(item.kind) + "\t"
                        + safeField(item.context) + "\t"
                        + String.format(Locale.US, "%.6f", item.lat) + "\t"
                        + String.format(Locale.US, "%.6f", item.lon) + "\t"
                        + joinAliases(aliases) + "\t" + item.importance;
                writeLine(gzip, row);
            }
            gzip.finish();
            buffered.flush();
            file.getFD().sync();
        }
    }

    private static void writeLine(GZIPOutputStream output, String line) throws IOException {
        output.write(line.getBytes(StandardCharsets.UTF_8));
        output.write('\n');
    }

    private static String joinAliases(List<String> aliases) {
        StringBuilder out = new StringBuilder();
        for (String alias : aliases) {
            String safe = safeField(alias).replace('|', '/');
            if (safe.isEmpty()) continue;
            if (out.length() > 0) out.append('|');
            out.append(safe);
        }
        return out.toString();
    }

    private static String safeField(String value) {
        if (value == null) return "";
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
        return normalized;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double numberValue(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(stringValue(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        String text = stringValue(value).toLowerCase(Locale.US);
        return "true".equals(text) || "yes".equals(text) || "1".equals(text);
    }

    private static long zigZag64(long value) {
        return (value >>> 1) ^ -(value & 1L);
    }

    private static final class Header {
        final long rootOffset;
        final long rootLength;
        final long leafDirectoryOffset;
        final long leafDirectoryLength;
        final long tileDataOffset;
        final long tileDataLength;
        final int internalCompression;
        final int tileCompression;
        final int tileType;
        final int minZoom;
        final int maxZoom;
        final double minLon;
        final double minLat;
        final double maxLon;
        final double maxLat;
        final boolean clustered;

        Header(long rootOffset, long rootLength,
               long leafDirectoryOffset, long leafDirectoryLength,
               long tileDataOffset, long tileDataLength,
               int internalCompression, int tileCompression, int tileType,
               int minZoom, int maxZoom,
               double minLon, double minLat, double maxLon, double maxLat,
               boolean clustered) {
            this.rootOffset = rootOffset;
            this.rootLength = rootLength;
            this.leafDirectoryOffset = leafDirectoryOffset;
            this.leafDirectoryLength = leafDirectoryLength;
            this.tileDataOffset = tileDataOffset;
            this.tileDataLength = tileDataLength;
            this.internalCompression = internalCompression;
            this.tileCompression = tileCompression;
            this.tileType = tileType;
            this.minZoom = minZoom;
            this.maxZoom = maxZoom;
            this.minLon = minLon;
            this.minLat = minLat;
            this.maxLon = maxLon;
            this.maxLat = maxLat;
            this.clustered = clustered;
        }

        static Header read(RandomAccessFile raf) throws IOException {
            byte[] bytes = new byte[PMTILES_HEADER_BYTES];
            raf.seek(0);
            raf.readFully(bytes);
            byte[] magic = "PMTiles".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < magic.length; i++) {
                if (bytes[i] != magic[i]) throw new IOException("installed basemap is not a PMTiles archive");
            }
            int version = bytes[7] & 0xff;
            if (version != PMTILES_VERSION) throw new IOException("unsupported PMTiles version: " + version);
            return new Header(
                    uint64LE(bytes, 8), uint64LE(bytes, 16),
                    uint64LE(bytes, 40), uint64LE(bytes, 48),
                    uint64LE(bytes, 56), uint64LE(bytes, 64),
                    bytes[97] & 0xff, bytes[98] & 0xff, bytes[99] & 0xff,
                    bytes[100] & 0xff, bytes[101] & 0xff,
                    int32LE(bytes, 102) / 10_000_000.0,
                    int32LE(bytes, 106) / 10_000_000.0,
                    int32LE(bytes, 110) / 10_000_000.0,
                    int32LE(bytes, 114) / 10_000_000.0,
                    (bytes[96] & 0xff) == 1);
        }

        void validateForRockMap(long fileLength) throws IOException {
            if (!clustered) throw new IOException("installed PMTiles is not clustered");
            if (tileType != TILE_TYPE_MVT) throw new IOException("installed PMTiles is not MVT vector data");
            if (!(internalCompression == COMPRESSION_NONE || internalCompression == COMPRESSION_GZIP)) {
                throw new IOException("unsupported PMTiles internal compression: " + internalCompression);
            }
            if (!(tileCompression == COMPRESSION_NONE || tileCompression == COMPRESSION_GZIP)) {
                throw new IOException("unsupported PMTiles tile compression: " + tileCompression);
            }
            if (minZoom > maxZoom || minZoom < 0 || maxZoom > 26) throw new IOException("invalid PMTiles zoom range");
            if (!(minLon >= -180 && minLon <= 180 && maxLon >= -180 && maxLon <= 180
                    && minLat >= -85.0512 && minLat <= 85.0512 && maxLat >= -85.0512 && maxLat <= 85.0512
                    && minLon <= maxLon && minLat <= maxLat)) {
                throw new IOException("invalid PMTiles geographic bounds");
            }
            validateRange(rootOffset, rootLength, fileLength, "root directory");
            if (leafDirectoryLength > 0) validateRange(leafDirectoryOffset, leafDirectoryLength, fileLength, "leaf directories");
            validateRange(tileDataOffset, tileDataLength, fileLength, "tile data");
        }

        private void validateRange(long offset, long length, long fileLength, String label) throws IOException {
            if (offset < 0 || length < 0) throw new IOException("invalid PMTiles " + label + " range");
            long end;
            try {
                end = Math.addExact(offset, length);
            } catch (ArithmeticException ex) {
                throw new IOException("PMTiles " + label + " range overflow", ex);
            }
            if (end > fileLength) throw new IOException("PMTiles " + label + " exceeds file size");
        }
    }

    private static long uint64LE(byte[] bytes, int offset) throws IOException {
        long value = 0;
        for (int i = 7; i >= 0; i--) value = (value << 8) | (bytes[offset + i] & 0xffL);
        if (value < 0) throw new IOException("PMTiles uint64 exceeds Java signed range");
        return value;
    }

    private static int int32LE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | (bytes[offset + 3] << 24);
    }

    static final class Entry {
        final long tileId;
        long offset;
        int length;
        long runLength;

        Entry(long tileId, long offset, int length, long runLength) {
            this.tileId = tileId;
            this.offset = offset;
            this.length = length;
            this.runLength = runLength;
        }
    }

    private static final class TileCoord {
        final int x;
        final int y;
        final long tileId;

        TileCoord(int x, int y, long tileId) {
            this.x = x;
            this.y = y;
            this.tileId = tileId;
        }
    }

    private static final class TileLocation {
        final long absoluteOffset;
        final int length;

        TileLocation(long absoluteOffset, int length) {
            this.absoluteOffset = absoluteOffset;
            this.length = length;
        }
    }

    private static final class LeafKey {
        final long offset;
        final int length;

        LeafKey(long offset, int length) {
            this.offset = offset;
            this.length = length;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof LeafKey)) return false;
            LeafKey that = (LeafKey) other;
            return offset == that.offset && length == that.length;
        }

        @Override public int hashCode() {
            return (int) (offset ^ (offset >>> 32)) * 31 + length;
        }
    }

    private static final class TileBlobCache {
        long offset = -1;
        int length = -1;
        byte[] bytes;

        boolean matches(long candidateOffset, int candidateLength) {
            return bytes != null && offset == candidateOffset && length == candidateLength;
        }

        void set(long offset, int length, byte[] bytes) {
            this.offset = offset;
            this.length = length;
            this.bytes = bytes;
        }
    }

    private static final class Classification {
        final String kind;
        final int baseImportance;
        final String hint;

        Classification(String kind, int baseImportance, String hint) {
            this.kind = kind;
            this.baseImportance = baseImportance;
            this.hint = hint;
        }
    }

    private static final class Candidate {
        final String name;
        final String kind;
        String context;
        final double lat;
        final double lon;
        final Set<String> aliases;
        final int importance;
        final String layer;

        Candidate(String name, String kind, String context,
                  double lat, double lon, Set<String> aliases,
                  int importance, String layer) {
            this.name = name;
            this.kind = kind;
            this.context = context == null ? "" : context;
            this.lat = lat;
            this.lon = lon;
            this.aliases = new HashSet<>(aliases);
            this.importance = importance;
            this.layer = layer;
        }

        PlaceRecord toRecord() {
            return new PlaceRecord(name, kind, context, lat, lon,
                    new ArrayList<>(aliases), importance);
        }
    }

    private static final class Aggregate {
        Candidate best;
        double latSum;
        double lonSum;
        int count;
        final HashSet<String> aliases = new HashSet<>();

        Aggregate(Candidate initial) {
            best = initial;
            latSum = initial.lat;
            lonSum = initial.lon;
            count = 1;
            aliases.addAll(initial.aliases);
        }

        void add(Candidate item) {
            latSum += item.lat;
            lonSum += item.lon;
            count++;
            aliases.addAll(item.aliases);
            if (item.importance > best.importance) best = item;
        }

        Candidate finish() {
            return new Candidate(best.name, best.kind, best.context,
                    latSum / count, lonSum / count, aliases,
                    best.importance, best.layer);
        }
    }

    private static final class ProtoReader {
        final byte[] bytes;
        int pos;

        ProtoReader(byte[] bytes) {
            this.bytes = bytes;
        }

        boolean hasRemaining() {
            return pos < bytes.length;
        }

        int readTag() throws IOException {
            long tag = readVarint64();
            if (tag <= 0 || tag > Integer.MAX_VALUE) throw new IOException("invalid protobuf tag");
            return (int) tag;
        }

        long readVarint64() throws IOException {
            long value = 0;
            int shift = 0;
            while (shift < 64) {
                if (pos >= bytes.length) throw new IOException("truncated protobuf varint");
                int b = bytes[pos++] & 0xff;
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
                shift += 7;
            }
            throw new IOException("protobuf varint exceeds 64 bits");
        }

        int readFixed32() throws IOException {
            require(4);
            int value = (bytes[pos] & 0xff)
                    | ((bytes[pos + 1] & 0xff) << 8)
                    | ((bytes[pos + 2] & 0xff) << 16)
                    | (bytes[pos + 3] << 24);
            pos += 4;
            return value;
        }

        long readFixed64() throws IOException {
            require(8);
            long value = 0;
            for (int i = 7; i >= 0; i--) value = (value << 8) | (bytes[pos + i] & 0xffL);
            pos += 8;
            return value;
        }

        String readString(int maxBytes) throws IOException {
            return new String(readLengthDelimited(maxBytes), StandardCharsets.UTF_8);
        }

        byte[] readLengthDelimited(int maxBytes) throws IOException {
            long length = readVarint64();
            if (length < 0 || length > maxBytes || length > Integer.MAX_VALUE) {
                throw new IOException("protobuf field exceeds safe size");
            }
            require((int) length);
            byte[] out = new byte[(int) length];
            System.arraycopy(bytes, pos, out, 0, (int) length);
            pos += (int) length;
            return out;
        }

        void skipField(int wire) throws IOException {
            if (wire == 0) readVarint64();
            else if (wire == 1) { require(8); pos += 8; }
            else if (wire == 2) {
                long length = readVarint64();
                if (length < 0 || length > Integer.MAX_VALUE) throw new IOException("protobuf skip length overflow");
                require((int) length);
                pos += (int) length;
            } else if (wire == 5) { require(4); pos += 4; }
            else throw new IOException("unsupported protobuf wire type: " + wire);
        }

        private void require(int count) throws IOException {
            if (count < 0 || pos > bytes.length - count) throw new IOException("truncated protobuf field");
        }
    }
}
