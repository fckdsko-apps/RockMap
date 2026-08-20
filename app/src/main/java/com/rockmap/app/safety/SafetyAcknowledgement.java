package com.rockmap.app.safety;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Stores a device-local acknowledgment of RockMap's current safety/data-limitations notice.
 * Nothing in this class transmits the acknowledgment or token off-device.
 */
public final class SafetyAcknowledgement {
    public static final int DISCLOSURE_VERSION = 1;
    public static final String PREFS_NAME = "rockmap_safety";

    private static final String KEY_VERSION = "disclosure_version";
    private static final String KEY_TOKEN = "acknowledgment_token";
    private static final String KEY_ACCEPTED_AT = "accepted_at";
    private static final String KEY_ACCEPTANCE_COUNT = "acceptance_count";
    private static final String KEY_SUPPRESS = "suppress_current_disclosure";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile boolean acceptedThisProcess;

    private SafetyAcknowledgement() {}

    /**
     * Access is allowed after this process has acknowledged the current notice, or when the user
     * previously chose not to see this disclosure version again.
     */
    public static boolean isAccessAllowed(Context context) {
        return acceptedThisProcess || isReminderSuppressed(context);
    }

    /** True only when the user chose not to show this exact disclosure version again. */
    public static boolean isReminderSuppressed(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs.getInt(KEY_VERSION, 0) == DISCLOSURE_VERSION
                && prefs.getBoolean(KEY_SUPPRESS, false)
                && validToken(prefs.getString(KEY_TOKEN, ""));
    }

    /**
     * Records an acknowledgment. The random token is local evidence of the acknowledged disclosure
     * version; it is not an account token, authentication credential, analytics ID, or network ID.
     */
    public static Status acknowledge(Context context, boolean suppressCurrentVersion) {
        SharedPreferences prefs = prefs(context);
        int storedVersion = prefs.getInt(KEY_VERSION, 0);
        String token = storedVersion == DISCLOSURE_VERSION
                ? prefs.getString(KEY_TOKEN, "") : "";
        int count = storedVersion == DISCLOSURE_VERSION
                ? prefs.getInt(KEY_ACCEPTANCE_COUNT, 0) : 0;
        if (!validToken(token)) token = generateToken();
        long now = System.currentTimeMillis();
        count = Math.max(0, count) + 1;
        prefs.edit()
                .putInt(KEY_VERSION, DISCLOSURE_VERSION)
                .putString(KEY_TOKEN, token)
                .putLong(KEY_ACCEPTED_AT, now)
                .putInt(KEY_ACCEPTANCE_COUNT, count)
                .putBoolean(KEY_SUPPRESS, suppressCurrentVersion)
                .apply();
        acceptedThisProcess = true;
        return new Status(DISCLOSURE_VERSION, token, now, count, suppressCurrentVersion);
    }

    public static Status getStatus(Context context) {
        SharedPreferences prefs = prefs(context);
        int version = prefs.getInt(KEY_VERSION, 0);
        String token = prefs.getString(KEY_TOKEN, "");
        long acceptedAt = prefs.getLong(KEY_ACCEPTED_AT, 0L);
        int count = prefs.getInt(KEY_ACCEPTANCE_COUNT, 0);
        boolean suppress = version == DISCLOSURE_VERSION
                && prefs.getBoolean(KEY_SUPPRESS, false)
                && validToken(token);
        return new Status(version, token, acceptedAt, count, suppress);
    }

    /** Keep the current session open, but show the notice again on the next process launch. */
    public static void enableReminder(Context context) {
        prefs(context).edit().putBoolean(KEY_SUPPRESS, false).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean validToken(String value) {
        return value != null && value.matches("^[0-9a-f]{32}$");
    }

    private static String generateToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder out = new StringBuilder(32);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    public static final class Status {
        public final int disclosureVersion;
        public final String token;
        public final long acceptedAt;
        public final int acceptanceCount;
        public final boolean reminderSuppressed;

        Status(int disclosureVersion, String token, long acceptedAt,
               int acceptanceCount, boolean reminderSuppressed) {
            this.disclosureVersion = disclosureVersion;
            this.token = token == null ? "" : token;
            this.acceptedAt = acceptedAt;
            this.acceptanceCount = acceptanceCount;
            this.reminderSuppressed = reminderSuppressed;
        }
    }
}
