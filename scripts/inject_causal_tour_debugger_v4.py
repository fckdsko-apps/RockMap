#!/usr/bin/env python3
"""Causal debugger v4: retain startup evidence and reduce diagnostic I/O pressure.

Diagnostic-only. This pass changes logger retention/mirroring and schema identity. It does not
move UI, change application/tour state, retry actions, or alter HUD ownership.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def replace_once(path: Path, marker: str, old: str, new: str, label: str) -> None:
    current = text(path)
    if marker in current:
        print(f"{label}: already present")
        return
    count = current.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one match in {path.relative_to(ROOT)}, found {count}"
        )
    path.write_text(current.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: injected")


def patch_log(path: Path) -> None:
    replace_once(
        path,
        "causal-v4-retention-limits",
        '''    private static final long MAX_INTERNAL_BYTES = 2L * 1024L * 1024L;
    private static final int KEEP_INTERNAL_BYTES = 1536 * 1024;
    private static final long MIRROR_DELAY_MS = 400L;
''',
        '''    // Keep startup evidence permanently inside every exported session. The previous
    // tail-only 2 MB trim could delete the exact cold-start UI evidence the debugger was built for.
    private static final long MAX_INTERNAL_BYTES = 8L * 1024L * 1024L;
    private static final int KEEP_STARTUP_BYTES = 1024 * 1024;
    private static final int KEEP_TAIL_BYTES = 6 * 1024 * 1024;
    private static final long MIRROR_DELAY_MS = 1200L; // marker: causal-v4-retention-limits
''',
        "startup-preserving debugger retention",
    )

    replace_once(
        path,
        "causal-v4-causal-event-priority",
        '''    /** Structured causal/debugger event. Diagnostic-only. */
    public static void causalEvent(String event, String detail) {
        recordImportant(clean(event, 60), clean(detail, 5000));
    } // marker: causal-debug-public-event-api
''',
        '''    /** Structured causal/debugger event. Diagnostic-only. */
    public static void causalEvent(String event, String detail) {
        String type = clean(event, 60);
        String safeDetail = clean(detail, 5000);
        // Do not rewrite the entire Downloads mirror for every callback bookkeeping line. Preserve
        // immediate durability for user intent, state changes and actual failures; routine causal
        // traffic is still written losslessly and mirrored on the delayed batch schedule.
        boolean urgent = "DEBUG_FINDING".equals(type)
                || "USER_GESTURE_START".equals(type)
                || "USER_GESTURE_END".equals(type)
                || "STATE_MUTATION".equals(type)
                || "UNATTRIBUTED_STATE_CHANGE".equals(type)
                || "STEP_ENTER".equals(type)
                || "STEP_EXIT".equals(type)
                || "CALLBACK_THROW".equals(type)
                || "SURFACE_SNAPSHOT_BEGIN".equals(type);
        if (urgent) recordImportant(type, safeDetail);
        else record(type, safeDetail);
    } // marker: causal-debug-public-event-api
      // marker: causal-v4-causal-event-priority
''',
        "batch routine causal-event mirroring",
    )

    replace_once(
        path,
        "causal-v4-startup-preserving-trim",
        '''    private static void trimIfNeeded() throws IOException {
        if (internalLog == null || !internalLog.isFile()
                || internalLog.length() <= MAX_INTERNAL_BYTES) {
            return;
        }

        byte[] bytes = readFile(internalLog);
        int start = Math.max(0, bytes.length - KEEP_INTERNAL_BYTES);
        while (start < bytes.length && bytes[start] != '\\n') start++;
        if (start < bytes.length) start++;

        try (FileOutputStream output = new FileOutputStream(internalLog, false)) {
            output.write("[older Tour Debug entries trimmed]\\n".getBytes(StandardCharsets.UTF_8));
            output.write(bytes, Math.min(start, bytes.length),
                    bytes.length - Math.min(start, bytes.length));
        }
    }
''',
        '''    private static void trimIfNeeded() throws IOException {
        if (internalLog == null || !internalLog.isFile()
                || internalLog.length() <= MAX_INTERNAL_BYTES) {
            return;
        }

        byte[] bytes = readFile(internalLog);

        // Prefix: retain process/application startup even after a very noisy later failure.
        int prefixEnd = Math.min(bytes.length, KEEP_STARTUP_BYTES);
        while (prefixEnd < bytes.length && bytes[prefixEnd] != '\\n') prefixEnd++;
        if (prefixEnd < bytes.length) prefixEnd++;

        // Tail: retain the most recent user interaction/failure. Start on a full line and never
        // overlap the pinned startup prefix.
        int tailStart = Math.max(prefixEnd, bytes.length - KEEP_TAIL_BYTES);
        while (tailStart < bytes.length && bytes[tailStart] != '\\n') tailStart++;
        if (tailStart < bytes.length) tailStart++;

        try (FileOutputStream output = new FileOutputStream(internalLog, false)) {
            output.write(bytes, 0, Math.min(prefixEnd, bytes.length));
            output.write("[middle Tour Debug entries trimmed; startup retained]\\n"
                    .getBytes(StandardCharsets.UTF_8));
            if (tailStart < bytes.length) {
                output.write(bytes, tailStart, bytes.length - tailStart);
            }
        }
    } // marker: causal-v4-startup-preserving-trim
''',
        "retain startup prefix while trimming debugger",
    )


def patch_schema(path: Path) -> None:
    replace_once(
        path,
        "causal-v4-schema",
        '''    public static final String SCHEMA = "causal-v2";
''',
        '''    public static final String SCHEMA = "causal-v4"; // marker: causal-v4-schema
''',
        "causal debugger schema v4",
    )


def main() -> int:
    log = ROOT / "app/src/main/java/com/rockmap/app/TourDebugLog.java"
    causality = ROOT / "app/src/main/java/com/rockmap/app/TourDebugCausality.java"
    for path in (log, causality):
        if not path.is_file():
            raise RuntimeError(f"required file missing: {path.relative_to(ROOT)}")

    originals = {path: text(path) for path in (log, causality)}
    try:
        patch_log(log)
        patch_schema(causality)

        # Diagnostics only: fail if this pass ever grows UI/state-mutating behavior.
        injected = text(causality)
        forbidden = (
            ".performClick(", ".setVisibility(", ".bringToFront(", ".requestLayout(",
            ".invalidate(", "GuidedTourState.setStep(", "GuidedTourState.advance(",
            "FieldMapState.set", "MapHudCoordinator.beforeExpand("
        )
        for token in forbidden:
            if token in injected:
                raise RuntimeError(f"causal v4 scope guard failed: causality contains {token}")

        print("Causal debugger v4 retention/I-O diagnostics complete.")
        print("Startup prefix is pinned; routine causal events use batched mirroring.")
        return 0
    except Exception:
        for path, content in originals.items():
            path.write_text(content, encoding="utf-8")
        print("Causal debugger v4 rolled back after failure.")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
