package com.rockmap.app.offline;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

public final class DataValidators {
    private static final Pattern SAFE_FILE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,159}$");
    private static final Pattern SHA256 = Pattern.compile("^[A-Fa-f0-9]{64}$");
    private static final Pattern SAFE_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,39}$");
    public static final long MAX_FILE_BYTES = 2_000_000_000L;

    private DataValidators() {}

    public static boolean isSafeId(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }

    public static boolean isSafeFileName(String value) {
        return value != null
                && SAFE_FILE.matcher(value).matches()
                && !value.contains("..")
                && !value.contains("/")
                && !value.contains("\\");
    }

    public static boolean isSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    public static boolean isSafeHttpsUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().trim().isEmpty()
                    && uri.getUserInfo() == null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static boolean isSupportedKind(String kind, String fileName) {
        if (kind == null || fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.US);
        if ("style".equals(kind)) return lower.endsWith(".json");
        if ("pmtiles".equals(kind)) return lower.endsWith(".pmtiles");
        if ("index".equals(kind)) return lower.endsWith(".json") || lower.endsWith(".json.gz");
        return false;
    }

    public static boolean isSafeByteCount(long bytes) {
        return bytes > 0 && bytes <= MAX_FILE_BYTES;
    }
}
