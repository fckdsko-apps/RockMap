package com.rockmap.app;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cross-app production diagnostics for RockMap.
 *
 * This layer is observational only. It never retries work, changes application state, repairs UI,
 * requests permissions, or alters service lifecycles. Diagnostics remain opt-in and app-private;
 * the only public export path is the explicit user action in Data settings.
 */
public final class WholeAppDiagnostics {
    private static final String PREFS = "rockmap_diagnostics";
    private static final String KEY_ENABLED = "enabled";
    private static final String LOG_FILE = "rockmap-diagnostics.log";
    private static final long STALL_THRESHOLD_MS = 6000L;
    private static final long STALL_REPORT_COOLDOWN_MS = 15_000L;
    private static final long HEARTBEAT_INTERVAL_MS = 2000L;
    private static final int MAX_STACK_CHARS = 24000;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean HOOKS_STARTED = new AtomicBoolean();
    private static final AtomicLong NEXT_OPERATION = new AtomicLong(1L);
    private static final AtomicLong NEXT_HEARTBEAT = new AtomicLong(1L);
    private static final AtomicLong ACK_HEARTBEAT = new AtomicLong();
    private static final AtomicLong LAST_REPORTED_STALL_AT = new AtomicLong(Long.MIN_VALUE);
    private static final Object CRITICAL_FILE_LOCK = new Object();

    // Deliberately lazy: pure JVM parser/export tests can load this class without initializing
    // Android's main Looper. The Handler exists only after diagnostics are enabled in the app.
    private static Handler mainHandler;
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "rockmap-diagnostics-watchdog");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private static Context app;
    private static SharedPreferences prefs;
    private static SharedPreferences.OnSharedPreferenceChangeListener prefListener;
    private static Thread.UncaughtExceptionHandler previousCrashHandler;

    private WholeAppDiagnostics() {}

    /** Installs only lightweight preference observation while diagnostics are disabled. */
    public static void install(Context context) {
        if (context == null) return;
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        if (INSTALLED.compareAndSet(false, true)) {
            app = application;
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefListener = (shared, key) -> {
                if (KEY_ENABLED.equals(key) && enabled()) startHooksIfNeeded();
            };
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
        }
        if (enabled()) startHooksIfNeeded();
    }

    public static boolean enabled() {
        return prefs != null && prefs.getBoolean(KEY_ENABLED, false);
    }

    private static void startHooksIfNeeded() {
        if (!enabled() || app == null || !HOOKS_STARTED.compareAndSet(false, true)) return;
        mainHandler = new Handler(Looper.getMainLooper());
        installCrashHandler();
        installMemoryCallbacks();
        startMainThreadWatchdog();
        event("DIAGNOSTICS_FORENSICS", "state=whole_app_hooks_started");
        storageSnapshot("diagnostics_enabled");
    }

    public static long start(String category, String operation, String detail) {
        long id = NEXT_OPERATION.getAndIncrement();
        long started = SystemClock.elapsedRealtime();
        event("OP_START", "id=" + id + " category=" + safe(category, 80)
                + " operation=" + safe(operation, 120) + " detail=" + safe(detail, 1200));
        return encodeOperation(id, started);
    }

    public static void success(long token, String category, String operation, String detail) {
        event("OP_SUCCESS", operationDetail(token, category, operation, detail, null));
    }

    public static void failure(long token, String category, String operation,
                               String detail, Throwable error) {
        event("OP_FAILURE", operationDetail(token, category, operation, detail, error));
    }

    /** Safe to call from pure parser/export code; it becomes a no-op outside the Android runtime. */
    public static void event(String event, String detail) {
        try {
            TourDebugLog.appDiagnostic(safe(event, 60), safe(detail, 3000));
        } catch (Throwable ignored) {
            // Diagnostics must never make local JVM tests or app code fail.
        }
    }

    public static void worker(String worker, String state, String detail) {
        event("WORKER", "worker=" + safe(worker, 100) + " state=" + safe(state, 80)
                + " detail=" + safe(detail, 1800));
    }

    public static void service(String service, String state, String detail) {
        event("SERVICE", "service=" + safe(service, 100) + " state=" + safe(state, 80)
                + " detail=" + safe(detail, 1800));
    }

    public static void permission(Context context, String permission, String label, String reason) {
        if (!enabled() || context == null || permission == null) return;
        int value;
        try {
            value = context.checkSelfPermission(permission);
        } catch (RuntimeException ex) {
            event("PERMISSION", "label=" + safe(label, 100) + " state=check_failed reason="
                    + safe(reason, 160) + " error=" + errorSummary(ex));
            return;
        }
        event("PERMISSION", "label=" + safe(label, 100)
                + " granted=" + (value == android.content.pm.PackageManager.PERMISSION_GRANTED)
                + " reason=" + safe(reason, 160));
    }

    public static void storageSnapshot(String reason) {
        if (!enabled() || app == null) return;
        try {
            File files = app.getFilesDir();
            StatFs stats = new StatFs(files.getAbsolutePath());
            long free = stats.getAvailableBytes();
            long total = stats.getTotalBytes();
            File log = new File(files, LOG_FILE);
            event("STORAGE", "reason=" + safe(reason, 140)
                    + " freeBytes=" + free + " totalBytes=" + total
                    + " diagnosticsBytes=" + (log.isFile() ? log.length() : 0L));
        } catch (RuntimeException ex) {
            event("STORAGE", "reason=" + safe(reason, 140)
                    + " state=unavailable error=" + errorSummary(ex));
        }
    }

    public static String errorSummary(Throwable error) {
        if (error == null) return "none";
        return safe(error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage()), 800);
    }

    private static String operationDetail(long token, String category, String operation,
                                          String detail, Throwable error) {
        long id = decodeOperationId(token);
        long started = decodeStarted(token);
        long duration = started <= 0L ? -1L
                : Math.max(0L, SystemClock.elapsedRealtime() - started);
        String out = "id=" + id + " category=" + safe(category, 80)
                + " operation=" + safe(operation, 120) + " durationMs=" + duration
                + " detail=" + safe(detail, 1400);
        if (error != null) out += " error=" + errorSummary(error);
        return out;
    }

    /* Operation token packs a small correlation id with a coarse elapsed start time. */
    private static long encodeOperation(long id, long started) {
        return ((id & 0xFFFFFL) << 44) | (started & 0xFFFFFFFFFFFL);
    }

    private static long decodeOperationId(long token) {
        return (token >>> 44) & 0xFFFFFL;
    }

    private static long decodeStarted(long token) {
        return token & 0xFFFFFFFFFFFL;
    }

    private static void installCrashHandler() {
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String detail = "thread=" + safe(thread == null ? "null" : thread.getName(), 120)
                        + " activity=" + currentActivity()
                        + " cause={" + safe(causalContext(), 800) + "}"
                        + " error=" + errorSummary(error)
                        + " stack=" + safe(stackTrace(error), MAX_STACK_CHARS);
                recordCriticalSync("UNCAUGHT_EXCEPTION", detail);
            } catch (Throwable ignored) {
            }
            Thread.UncaughtExceptionHandler previous = previousCrashHandler;
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static void installMemoryCallbacks() {
        if (!(app instanceof Application)) return;
        ((Application) app).registerComponentCallbacks(new ComponentCallbacks2() {
            @Override public void onTrimMemory(int level) {
                event("MEMORY_TRIM", "level=" + level + " activity=" + currentActivity());
            }

            @Override public void onLowMemory() {
                recordCriticalSync("LOW_MEMORY", "activity=" + currentActivity());
            }

            @Override public void onConfigurationChanged(Configuration newConfig) {
                event("CONFIGURATION", "activity=" + currentActivity());
            }
        });
    }

    private static void startMainThreadWatchdog() {
        final Handler handler = mainHandler;
        if (handler == null) return;
        WATCHDOG.scheduleAtFixedRate(() -> {
            if (!enabled()) return;
            final long token = NEXT_HEARTBEAT.getAndIncrement();
            final long postedAt = SystemClock.elapsedRealtime();
            handler.post(() -> ACK_HEARTBEAT.accumulateAndGet(token, Math::max));
            WATCHDOG.schedule(() -> {
                if (!enabled() || ACK_HEARTBEAT.get() >= token) return;
                long now = SystemClock.elapsedRealtime();
                long previous = LAST_REPORTED_STALL_AT.get();
                if (previous != Long.MIN_VALUE && now - previous < STALL_REPORT_COOLDOWN_MS) return;
                if (!LAST_REPORTED_STALL_AT.compareAndSet(previous, now)) return;
                Thread mainThread = Looper.getMainLooper().getThread();
                String detail = "heartbeat=" + token
                        + " blockedMsAtLeast=" + Math.max(0L, now - postedAt)
                        + " activity=" + currentActivity()
                        + " cause={" + safe(causalContext(), 800) + "}"
                        + " mainStack=" + safe(stackTrace(mainThread), MAX_STACK_CHARS);
                recordCriticalSync("MAIN_THREAD_STALL", detail);
            }, STALL_THRESHOLD_MS, TimeUnit.MILLISECONDS);
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private static String currentActivity() {
        try {
            Activity activity = TourDebugCausality.lastResumedActivity();
            return activity == null ? "none" : activity.getClass().getSimpleName();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static String causalContext() {
        try {
            return TourDebugCausality.contextSummary();
        } catch (Throwable ignored) {
            return "cause=unavailable";
        }
    }

    private static String stackTrace(Throwable error) {
        if (error == null) return "none";
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static String stackTrace(Thread thread) {
        if (thread == null) return "none";
        StringBuilder out = new StringBuilder();
        StackTraceElement[] stack = thread.getStackTrace();
        int limit = Math.min(stack.length, 80);
        for (int i = 0; i < limit; i++) {
            if (i > 0) out.append(" <- ");
            out.append(stack[i]);
        }
        return out.toString();
    }

    /** Synchronous persistence reserved for crash/stall evidence that may precede process death. */
    private static void recordCriticalSync(String event, String detail) {
        if (!enabled() || app == null) return;
        synchronized (CRITICAL_FILE_LOCK) {
            try {
                File file = new File(app.getFilesDir(), LOG_FILE);
                String timestamp = new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                String line = timestamp + " | CRITICAL_SYNC | " + safe(event, 80)
                        + " | " + safe(detail, 30000) + "\n";
                try (FileOutputStream output = new FileOutputStream(file, true)) {
                    output.write(line.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    output.getFD().sync();
                }
            } catch (Throwable ignored) {
                // Diagnostics must never become an application failure source.
            }
        }
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String compact = value.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max) + "…";
    }
}
