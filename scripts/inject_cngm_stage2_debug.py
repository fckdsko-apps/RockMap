#!/usr/bin/env python3
'''Runner-side CNGM Stage 2 migration injector for RockMap's tour-debug APK.'''

from __future__ import annotations

import gzip
import hashlib
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
from typing import Dict

ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app/src/main/assets"
ASSET = ASSET_DIR / "rockmap-cngm-stage2-debug.db.gz"
ASSET_MANIFEST = ASSET_DIR / "rockmap-cngm-stage2-debug.json"

APP = ROOT / "app/src/main/java/com/rockmap/app/RockMapApplication.java"
DATA_MANAGER = ROOT / "app/src/main/java/com/rockmap/app/research/GeologyDataManager.java"
REPOSITORY = ROOT / "app/src/main/java/com/rockmap/app/research/GeologyRepository.java"
UNIT = ROOT / "app/src/main/java/com/rockmap/app/research/GeologyUnit.java"
RESEARCH = ROOT / "app/src/main/java/com/rockmap/app/research/ResearchActivity.java"
UPDATE_WORKER = ROOT / "app/src/main/java/com/rockmap/app/research/GeologyDataUpdateWorker.java"
BOOTSTRAP = ROOT / "app/src/main/java/com/rockmap/app/research/CngmStage2DebugBootstrap.java"

EXPECTED_SOURCE_DOI = "10.5066/P146VGVM"
EXPECTED_SOURCE_MAP = "map50"
EXPECTED_RECORDS = 9500
EXPECTED_SOURCE_UNITS = 185
EXPECTED_SYNTHESIS_UNITS = 41
EXPECTED_CROSSWALK = 185


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def replace_once(path: Path, old: str, new: str, marker: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if marker in text:
        print(f"{label}: already present")
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(
            f"{label}: expected exactly one source match in {path.relative_to(ROOT)}, found {count}"
        )
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"{label}: injected")


def load_and_validate_asset() -> Dict[str, object]:
    if not ASSET_MANIFEST.is_file() or not ASSET.is_file():
        raise RuntimeError(
            "CNGM Stage 2 debug assets are missing. Expected "
            "app/src/main/assets/rockmap-cngm-stage2-debug.{json,db.gz}"
        )
    manifest = json.loads(ASSET_MANIFEST.read_text(encoding="utf-8"))
    required = {
        "formatVersion": 1,
        "debugStage": "cngm-stage2",
        "productionReleaseApproved": False,
        "sourceDoi": EXPECTED_SOURCE_DOI,
        "sourceMapId": EXPECTED_SOURCE_MAP,
        "schemaVersion": 2,
        "recordCount": EXPECTED_RECORDS,
        "sourceUnitCount": EXPECTED_SOURCE_UNITS,
        "synthesisUnitCount": EXPECTED_SYNTHESIS_UNITS,
        "crosswalkCount": EXPECTED_CROSSWALK,
        "assetFile": ASSET.name,
    }
    for key, expected in required.items():
        if manifest.get(key) != expected:
            raise RuntimeError(
                f"CNGM Stage 2 manifest mismatch for {key}: "
                f"{manifest.get(key)!r} != {expected!r}"
            )
    if ASSET.stat().st_size != int(manifest["assetBytes"]):
        raise RuntimeError("CNGM Stage 2 gzip byte count does not match its manifest.")
    if sha256_file(ASSET) != manifest["assetSha256"]:
        raise RuntimeError("CNGM Stage 2 gzip SHA-256 does not match its manifest.")

    with tempfile.TemporaryDirectory(prefix="rockmap-cngm-stage2-") as td:
        db_path = Path(td) / str(manifest["databaseFile"])
        digest = hashlib.sha256()
        total = 0
        with gzip.open(ASSET, "rb") as source, db_path.open("wb") as target:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > int(manifest["databaseBytes"]):
                    raise RuntimeError("CNGM Stage 2 unpacked bytes exceeded manifest.")
                digest.update(chunk)
                target.write(chunk)
        if total != int(manifest["databaseBytes"]):
            raise RuntimeError("CNGM Stage 2 unpacked byte count does not match manifest.")
        if digest.hexdigest() != manifest["databaseSha256"]:
            raise RuntimeError("CNGM Stage 2 database SHA-256 does not match manifest.")

        con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        try:
            meta = dict(con.execute("SELECT key,value FROM metadata"))
            checks = {
                "schema_version": "2",
                "source_doi": EXPECTED_SOURCE_DOI,
                "source_map_id": EXPECTED_SOURCE_MAP,
                "record_count": str(EXPECTED_RECORDS),
                "production_release_approved": "false",
                "debug_stage": "cngm-stage2",
            }
            for key, expected in checks.items():
                if meta.get(key) != expected:
                    raise RuntimeError(
                        f"CNGM Stage 2 database metadata mismatch for {key}: "
                        f"{meta.get(key)!r} != {expected!r}"
                    )
            if con.execute("PRAGMA quick_check").fetchone()[0].lower() != "ok":
                raise RuntimeError("CNGM Stage 2 database failed quick_check.")
            if con.execute("PRAGMA foreign_key_check").fetchall():
                raise RuntimeError("CNGM Stage 2 database failed foreign_key_check.")
            if con.execute("SELECT COUNT(*) FROM units").fetchone()[0] != EXPECTED_RECORDS:
                raise RuntimeError("CNGM Stage 2 compatibility view count is wrong.")
            if con.execute("SELECT COUNT(*) FROM polygons").fetchone()[0] != EXPECTED_RECORDS:
                raise RuntimeError("CNGM Stage 2 polygon count is wrong.")
            if con.execute(
                "SELECT COUNT(*) FROM polygons WHERE map_source_id<>?",
                (EXPECTED_SOURCE_MAP,)
            ).fetchone()[0] != 0:
                raise RuntimeError("CNGM Stage 2 contains non-Colorado-source polygons.")
            if con.execute("SELECT COUNT(*) FROM source_units").fetchone()[0] != EXPECTED_SOURCE_UNITS:
                raise RuntimeError("CNGM Stage 2 source-unit count is wrong.")
            if con.execute("SELECT COUNT(*) FROM synthesis_units").fetchone()[0] != EXPECTED_SYNTHESIS_UNITS:
                raise RuntimeError("CNGM Stage 2 synthesis-unit count is wrong.")
            if con.execute("SELECT COUNT(*) FROM source_synthesis").fetchone()[0] != EXPECTED_CROSSWALK:
                raise RuntimeError("CNGM Stage 2 crosswalk count is wrong.")
            columns = {row[1] for row in con.execute("PRAGMA table_info(units)")}
            required_columns = {
                "object_id","state","orig_label","sgmc_label","unit_link","unit_name",
                "age_min","age_max","generalized_lith","major1","major2","major3",
                "minor1","minor2","minor3","minor4","minor5","incidental","indeterminate",
                "ref_id","reference_text","digital_url","ngmdb1","ngmdb2","ngmdb3","rgba",
                "south","west","north","east","geometry_json","search_text","lithology_text",
                "age_text",
            }
            missing = sorted(required_columns - columns)
            if missing:
                raise RuntimeError("CNGM Stage 2 compatibility view is missing: " + ", ".join(missing))
        finally:
            con.close()

    print(
        "CNGM Stage 2 asset validated: "
        f"{manifest['recordCount']} polygons, "
        f"{manifest['sourceUnitCount']} source units, "
        f"{manifest['assetBytes']} compressed bytes"
    )
    return manifest


def java_string(value: object) -> str:
    return json.dumps(str(value))


def write_bootstrap(manifest: Dict[str, object]) -> None:
    source = f'''package com.rockmap.app.research;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.system.Os;

import com.rockmap.app.TourDebugLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/** Runner-generated CNGM Stage 2 bootstrap for the tour-debug APK only. */
public final class CngmStage2DebugBootstrap {{
    public static final String DATABASE_NAME = "rockmap-cngm-stage2-debug.db";
    private static final String ASSET_NAME = "rockmap-cngm-stage2-debug.db.gz";
    private static final long ASSET_BYTES = {int(manifest["assetBytes"])}L;
    private static final String ASSET_SHA256 = {java_string(manifest["assetSha256"])};
    private static final long DATABASE_BYTES = {int(manifest["databaseBytes"])}L;
    private static final String DATABASE_SHA256 = {java_string(manifest["databaseSha256"])};
    private static final int RECORD_COUNT = {int(manifest["recordCount"])};
    private static final int SOURCE_UNIT_COUNT = {int(manifest["sourceUnitCount"])};
    private static final int SYNTHESIS_UNIT_COUNT = {int(manifest["synthesisUnitCount"])};
    private static final int CROSSWALK_COUNT = {int(manifest["crosswalkCount"])};
    private static final String SOURCE_DOI = {java_string(manifest["sourceDoi"])};
    private static final String SOURCE_MAP_ID = {java_string(manifest["sourceMapId"])};
    private static final Object LOCK = new Object();

    private CngmStage2DebugBootstrap() {{}}

    public static void install(Context context) {{
        if (context == null) return;
        synchronized (LOCK) {{
            Context app = context.getApplicationContext();
            File research = new File(app.getFilesDir(), "research");
            File target = new File(research, DATABASE_NAME);
            File gzipPart = new File(research, DATABASE_NAME + ".gz.part");
            File dbPart = new File(research, DATABASE_NAME + ".part");
            TourDebugLog.mapDiagnostic("CNGM_STAGE2_BOOT_START",
                    "asset=" + ASSET_NAME + " target=" + target.getAbsolutePath()
                            + " expectedRecords=" + RECORD_COUNT);
            try {{
                if (!research.exists() && !research.mkdirs()) {{
                    throw new IOException("Cannot create Research directory.");
                }}
                if (isExactDatabase(target)) {{
                    validateDatabase(target);
                    TourDebugLog.mapDiagnostic("CNGM_STAGE2_READY",
                            "reused=true bytes=" + target.length()
                                    + " sha256=" + DATABASE_SHA256
                                    + " records=" + RECORD_COUNT
                                    + " sourceMap=" + SOURCE_MAP_ID);
                    return;
                }}
                deleteIfExists(gzipPart);
                deleteIfExists(dbPart);
                copyAssetAndVerify(app, gzipPart);
                gunzipAndVerify(gzipPart, dbPart);
                validateDatabase(dbPart);
                if (target.exists() && !target.delete()) {{
                    throw new IOException("Cannot replace stale CNGM debug database.");
                }}
                try {{
                    Os.rename(dbPart.getAbsolutePath(), target.getAbsolutePath());
                }} catch (android.system.ErrnoException exc) {{
                    throw new IOException("Atomic CNGM debug activation failed: " + exc.getMessage(), exc);
                }}
                if (!isExactDatabase(target)) {{
                    throw new IOException("Activated CNGM debug database failed SHA-256 verification.");
                }}
                validateDatabase(target);
                ResearchResultStore.clear(app);
                TourDebugLog.mapDiagnostic("CNGM_STAGE2_INSTALLED",
                        "bytes=" + target.length()
                                + " sha256=" + DATABASE_SHA256
                                + " records=" + RECORD_COUNT
                                + " sourceUnits=" + SOURCE_UNIT_COUNT
                                + " synthesisUnits=" + SYNTHESIS_UNIT_COUNT
                                + " crosswalk=" + CROSSWALK_COUNT
                                + " sourceDoi=" + SOURCE_DOI
                                + " sourceMap=" + SOURCE_MAP_ID);
            }} catch (Exception exc) {{
                TourDebugLog.mapDiagnostic("CNGM_STAGE2_BOOT_FAIL",
                        "type=" + exc.getClass().getSimpleName()
                                + " message=" + clean(exc.getMessage()));
                deleteIfExists(gzipPart);
                deleteIfExists(dbPart);
            }} finally {{
                deleteIfExists(gzipPart);
                deleteIfExists(dbPart);
            }}
        }}
    }}

    public static File databaseFile(Context context) {{
        if (context == null) return null;
        return new File(new File(context.getApplicationContext().getFilesDir(), "research"), DATABASE_NAME);
    }}

    private static void copyAssetAndVerify(Context app, File target) throws Exception {{
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        try (InputStream raw = new BufferedInputStream(app.getAssets().open(ASSET_NAME));
             FileOutputStream file = new FileOutputStream(target);
             BufferedOutputStream output = new BufferedOutputStream(file)) {{
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = raw.read(buffer)) != -1) {{
                total += read;
                if (total > ASSET_BYTES) throw new IOException("CNGM debug asset exceeded declared bytes.");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }}
            output.flush();
            file.getFD().sync();
        }}
        if (total != ASSET_BYTES) throw new IOException("CNGM debug asset byte count mismatch.");
        if (!hex(digest.digest()).equalsIgnoreCase(ASSET_SHA256)) {{
            throw new IOException("CNGM debug asset SHA-256 mismatch.");
        }}
        TourDebugLog.mapDiagnostic("CNGM_STAGE2_ASSET_OK",
                "bytes=" + total + " sha256=" + ASSET_SHA256);
    }}

    private static void gunzipAndVerify(File source, File target) throws Exception {{
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        try (InputStream raw = new BufferedInputStream(new java.io.FileInputStream(source));
             GZIPInputStream gzip = new GZIPInputStream(raw, 128 * 1024);
             FileOutputStream file = new FileOutputStream(target);
             BufferedOutputStream output = new BufferedOutputStream(file)) {{
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = gzip.read(buffer)) != -1) {{
                total += read;
                if (total > DATABASE_BYTES) throw new IOException("CNGM debug database exceeded declared bytes.");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }}
            output.flush();
            file.getFD().sync();
        }}
        if (total != DATABASE_BYTES) throw new IOException("CNGM debug database byte count mismatch.");
        if (!hex(digest.digest()).equalsIgnoreCase(DATABASE_SHA256)) {{
            throw new IOException("CNGM debug database SHA-256 mismatch.");
        }}
        TourDebugLog.mapDiagnostic("CNGM_STAGE2_UNPACK_OK",
                "bytes=" + total + " sha256=" + DATABASE_SHA256);
    }}

    private static boolean isExactDatabase(File file) {{
        if (file == null || !file.isFile() || file.length() != DATABASE_BYTES) return false;
        try {{
            return DATABASE_SHA256.equalsIgnoreCase(sha256(file));
        }} catch (Exception ignored) {{
            return false;
        }}
    }}

    private static void validateDatabase(File file) throws Exception {{
        if (!isExactDatabase(file)) throw new IOException("CNGM debug database bytes are not exact.");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {{
            try (Cursor quick = db.rawQuery("PRAGMA quick_check", null)) {{
                if (!quick.moveToFirst() || !"ok".equalsIgnoreCase(quick.getString(0))) {{
                    throw new IOException("CNGM debug SQLite quick_check failed.");
                }}
            }}
            requireMetadata(db, "schema_version", "2");
            requireMetadata(db, "source_doi", SOURCE_DOI);
            requireMetadata(db, "source_map_id", SOURCE_MAP_ID);
            requireMetadata(db, "record_count", Integer.toString(RECORD_COUNT));
            requireMetadata(db, "production_release_approved", "false");
            requireMetadata(db, "debug_stage", "cngm-stage2");
            if (scalar(db, "SELECT COUNT(*) FROM units") != RECORD_COUNT) {{
                throw new IOException("CNGM compatibility-view record count mismatch.");
            }}
            if (scalar(db, "SELECT COUNT(*) FROM polygons") != RECORD_COUNT) {{
                throw new IOException("CNGM polygon count mismatch.");
            }}
            if (scalar(db, "SELECT COUNT(*) FROM polygons WHERE map_source_id<>'map50'") != 0L) {{
                throw new IOException("CNGM debug database contains non-Colorado-source polygons.");
            }}
            if (scalar(db, "SELECT COUNT(*) FROM source_units") != SOURCE_UNIT_COUNT) {{
                throw new IOException("CNGM source-unit count mismatch.");
            }}
            if (scalar(db, "SELECT COUNT(*) FROM synthesis_units") != SYNTHESIS_UNIT_COUNT) {{
                throw new IOException("CNGM synthesis-unit count mismatch.");
            }}
            if (scalar(db, "SELECT COUNT(*) FROM source_synthesis") != CROSSWALK_COUNT) {{
                throw new IOException("CNGM crosswalk count mismatch.");
            }}
            Set<String> columns = new HashSet<>();
            try (Cursor schema = db.rawQuery("PRAGMA table_info(units)", null)) {{
                while (schema.moveToNext()) columns.add(schema.getString(1));
            }}
            String[] required = new String[] {{
                    "object_id","state","orig_label","sgmc_label","unit_link","unit_name",
                    "age_min","age_max","generalized_lith","ref_id","reference_text",
                    "digital_url","south","west","north","east","geometry_json",
                    "search_text","lithology_text","age_text"
            }};
            for (String column : required) {{
                if (!columns.contains(column)) throw new IOException(
                        "CNGM compatibility view missing column: " + column);
            }}
            try (Cursor foreignKeys = db.rawQuery("PRAGMA foreign_key_check", null)) {{
                if (foreignKeys.moveToFirst()) throw new IOException("CNGM SQLite foreign_key_check failed.");
            }}
        }}
        TourDebugLog.mapDiagnostic("CNGM_STAGE2_DB_VALID",
                "records=" + RECORD_COUNT
                        + " sourceUnits=" + SOURCE_UNIT_COUNT
                        + " synthesisUnits=" + SYNTHESIS_UNIT_COUNT
                        + " sourceMap=" + SOURCE_MAP_ID);
    }}

    private static void requireMetadata(SQLiteDatabase db, String key, String expected)
            throws IOException {{
        try (Cursor c = db.rawQuery("SELECT value FROM metadata WHERE key=?", new String[]{{key}})) {{
            if (!c.moveToFirst()) throw new IOException("CNGM metadata missing: " + key);
            String actual = c.getString(0);
            if (!expected.equals(actual)) {{
                throw new IOException("CNGM metadata mismatch for " + key + ": " + actual + " != " + expected);
            }}
        }}
    }}

    private static long scalar(SQLiteDatabase db, String sql) throws IOException {{
        try (Cursor c = db.rawQuery(sql, null)) {{
            if (!c.moveToFirst()) throw new IOException("CNGM validation query returned no row.");
            return c.getLong(0);
        }}
    }}

    private static String sha256(File file) throws Exception {{
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file))) {{
            byte[] buffer = new byte[128 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }}
        return hex(digest.digest());
    }}

    private static String hex(byte[] value) {{
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }}

    private static void deleteIfExists(File file) {{
        if (file != null && file.exists()) file.delete();
    }}

    private static String clean(String value) {{
        if (value == null) return "";
        String oneLine = value.replace('\\n', ' ').replace('\\r', ' ').trim();
        return oneLine.length() <= 600 ? oneLine : oneLine.substring(0, 600);
    }}
}}
'''
    BOOTSTRAP.write_text(source, encoding="utf-8")
    print("CNGM Stage 2 runtime bootstrap: generated")


def inject_sources() -> None:
    replace_once(
        APP,
        "import com.rockmap.app.field.FieldMapController;\n",
        "import com.rockmap.app.field.FieldMapController;\nimport com.rockmap.app.research.CngmStage2DebugBootstrap;\n",
        "import com.rockmap.app.research.CngmStage2DebugBootstrap;",
        "import CNGM Stage 2 bootstrap",
    )
    replace_once(
        APP,
        "        TourDebugLog.install(this);\n\n        // Manifest-only background checks.",
        "        TourDebugLog.install(this);\n        CngmStage2DebugBootstrap.install(this);\n\n        // Manifest-only background checks.",
        "CngmStage2DebugBootstrap.install(this);",
        "start CNGM Stage 2 bootstrap",
    )

    replace_once(
        DATA_MANAGER,
        '    public static final String LEGACY_DATABASE = "rockmap-geology.db";\n',
        '    public static final String LEGACY_DATABASE = "rockmap-geology.db";\n    public static final String CNGM_STAGE2_DEBUG_DATABASE = "rockmap-cngm-stage2-debug.db";\n',
        "CNGM_STAGE2_DEBUG_DATABASE",
        "declare CNGM debug database",
    )
    replace_once(
        DATA_MANAGER,
        '''    public File getLegacyDatabaseFile() {
        return new File(researchDir, LEGACY_DATABASE);
    }
''',
        '''    public File getLegacyDatabaseFile() {
        return new File(researchDir, LEGACY_DATABASE);
    }

    public File getCngmStage2DebugDatabaseFile() {
        return new File(researchDir, CNGM_STAGE2_DEBUG_DATABASE);
    }
''',
        "getCngmStage2DebugDatabaseFile()",
        "expose CNGM debug database",
    )
    replace_once(
        DATA_MANAGER,
        '''    public String getInstalledVersion() {
        GeologyManifest active = getActiveManifest();
        if (active != null && resolveDatabase(active) != null) return active.version;
        if (getLegacyDatabaseFile().isFile()) return "legacy local snapshot";
        return "";
    }
''',
        '''    public String getInstalledVersion() {
        if (getCngmStage2DebugDatabaseFile().isFile()) return "CNGM Stage 2 debug";
        GeologyManifest active = getActiveManifest();
        if (active != null && resolveDatabase(active) != null) return active.version;
        if (getLegacyDatabaseFile().isFile()) return "legacy local snapshot";
        return "";
    }
''',
        'return "CNGM Stage 2 debug";',
        "report CNGM debug version",
    )

    replace_once(
        REPOSITORY,
        "import android.database.sqlite.SQLiteDatabase;\n",
        "import android.database.sqlite.SQLiteDatabase;\nimport android.os.SystemClock;\n\nimport com.rockmap.app.TourDebugLog;\n",
        "import com.rockmap.app.TourDebugLog;",
        "import CNGM query diagnostics",
    )
    replace_once(
        REPOSITORY,
        '''    public static final String SOURCE_TITLE = "USGS State Geologic Map Compilation (SGMC)";
    public static final String SOURCE_DOI = "10.5066/F7WH2N65";
    public static final String SOURCE_SCALE = "1:500,000 Colorado source map";
    public static final String SOURCE_NOTE = "2017 USGS SGMC source polygons published through an ArcGIS FeatureServer; RockMap stores a Colorado-only local snapshot and reports the source exactly rather than relabeling it as the separate 2026 GeMS release.";
    public static final String SOURCE_SERVICE = "https://services.arcgis.com/v01gqwM5QqNysAAi/ArcGIS/rest/services/SGMC_featureservice/FeatureServer/0/query";
''',
        '''    public static final String SOURCE_TITLE = "USGS Cooperative National Geologic Map (CNGM) — Earth's Surface Geology";
    public static final String SOURCE_DOI = "10.5066/P146VGVM";
    public static final String SOURCE_SCALE = "1:500,000 Colorado source map (Tweto, 1979) within CNGM";
    public static final String SOURCE_NOTE = "CNGM Stage 2 debug uses the Colorado source-map coverage from Tweto (1979) as preserved in the USGS CNGM Earth's Surface GeMS release. Original source-map facts remain distinct from standardized CNGM synthesis units; neighboring-state boundary slivers are excluded. RockMap does not infer mineral occurrence from mapped geology.";
    public static final String SOURCE_SERVICE = "https://ngmdb.usgs.gov/Prodesc/proddesc_118545.htm";
''',
        "CNGM Stage 2 debug uses the Colorado source-map coverage",
        "switch debug source metadata to CNGM",
    )
    replace_once(
        REPOSITORY,
        '''    public GeologyRepository(Context context) {
        dataManager = new GeologyDataManager(context);
    }
''',
        '''    public GeologyRepository(Context context) {
        dataManager = new GeologyDataManager(context);
        File debug = dataManager.getCngmStage2DebugDatabaseFile();
        TourDebugLog.mapDiagnostic("CNGM_STAGE2_REPOSITORY",
                "db=" + debug.getAbsolutePath()
                        + " exists=" + debug.isFile()
                        + " bytes=" + (debug.isFile() ? debug.length() : 0L));
    }
''',
        '"CNGM_STAGE2_REPOSITORY"',
        "log CNGM repository startup",
    )
    replace_once(
        REPOSITORY,
        '''    /** Returns the first structurally usable current/rollback/legacy database candidate. */
    public File getDatabaseFile() {
        File ready = findReadyDatabase();
        return ready != null ? ready : dataManager.getLegacyDatabaseFile();
    }

    public boolean isReady() {
        return findReadyDatabase() != null;
    }

    private File findReadyDatabase() {
        for (File file : dataManager.getDatabaseCandidates()) {
            if (isReadyFile(file)) return file;
        }
        return null;
    }
''',
        '''    /** CNGM Stage 2 debug is fail-closed: never silently relabel an SGMC fallback as CNGM. */
    public File getDatabaseFile() {
        return findReadyDatabase();
    }

    public boolean isReady() {
        return findReadyDatabase() != null;
    }

    private File findReadyDatabase() {
        File debug = dataManager.getCngmStage2DebugDatabaseFile();
        return isReadyFile(debug) ? debug : null;
    }
''',
        "CNGM Stage 2 debug is fail-closed",
        "require CNGM debug database",
    )
    replace_once(
        REPOSITORY,
        '''            root.put("rockmapResearchSchema", 1);
            root.put("source", SOURCE_TITLE);
''',
        '''            root.put("rockmapResearchSchema", 1);
            root.put("rockmapGeologySchema", 2);
            root.put("geologyEvidenceClass", "SOURCE_FACT");
            root.put("sourceMapId", "map50");
            root.put("source", SOURCE_TITLE);
''',
        'root.put("rockmapGeologySchema", 2);',
        "mark CNGM export schema",
    )
    replace_once(
        REPOSITORY,
        '''        p.put("rgba", unit.rgba);
        p.put("rockmap_source", SOURCE_TITLE);
''',
        '''        p.put("rgba", unit.rgba);
        p.put("SOURCE_MAPUNIT", unit.unitLink);
        p.put("SOURCE_LABEL", unit.originalLabel);
        p.put("CNGM_MAPUNIT", unit.sgmcLabel);
        p.put("SOURCE_GEOMATERIAL", unit.generalizedLithology);
        p.put("rockmap_geology_evidence_class", "SOURCE_FACT");
        p.put("rockmap_source", SOURCE_TITLE);
''',
        'p.put("CNGM_MAPUNIT", unit.sgmcLabel);',
        "add CNGM export provenance",
    )
    replace_once(
        REPOSITORY,
        '''    private List<GeologyUnit> query(String where, String[] args, String order, int limit) {
        ArrayList<GeologyUnit> out = new ArrayList<>();
        try (SQLiteDatabase db = openRead();
             Cursor c = db.query("units", UNIT_COLUMNS, where, args, null, null, order,
                     limit <= 0 ? null : Integer.toString(limit))) {
            while (c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }
''',
        '''    private List<GeologyUnit> query(String where, String[] args, String order, int limit) {
        ArrayList<GeologyUnit> out = new ArrayList<>();
        File file = findReadyDatabase();
        if (file == null) throw new IllegalStateException("CNGM Stage 2 geology database is not ready.");
        long started = SystemClock.elapsedRealtime();
        TourDebugLog.mapDiagnostic("CNGM_QUERY_START",
                "db=" + file.getName()
                        + " where=" + (where == null ? "<all>" : where)
                        + " args=" + java.util.Arrays.toString(args)
                        + " order=" + order + " limit=" + limit);
        try (SQLiteDatabase db = openRead(file);
             Cursor c = db.query("units", UNIT_COLUMNS, where, args, null, null, order,
                     limit <= 0 ? null : Integer.toString(limit))) {
            while (c.moveToNext()) out.add(fromCursor(c));
        } catch (RuntimeException ex) {
            TourDebugLog.mapDiagnostic("CNGM_QUERY_FAIL",
                    "type=" + ex.getClass().getSimpleName()
                            + " message=" + (ex.getMessage() == null ? "" : ex.getMessage()));
            throw ex;
        }
        TourDebugLog.mapDiagnostic("CNGM_QUERY_OK",
                "rows=" + out.size()
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
        return out;
    }
''',
        '"CNGM_QUERY_START"',
        "log CNGM database queries",
    )

    replace_once(
        UNIT,
        '''    /** Prefer a human-readable rock type over a broad SGMC classification path. */
    public String compactLithologyLabel() {
        String fromName = rockTypeFromName(displayName());
        if (!fromName.isEmpty()) return fromName;

        String[] candidates = new String[]{major1, major2, major3, minor1, minor2, minor3, minor4, minor5};
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (String candidate : candidates) {
            int score = lithologySpecificity(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = safe(candidate);
            }
        }
        if (!best.isEmpty() && bestScore > -20) return titleCase(best);
        if (!generalizedLithology.isEmpty()) return titleCase(generalizedLithology);
        return "Lithology not reported";
    }
''',
        '''    /** CNGM Stage 2: display explicit source GeoMaterial only; do not infer from names. */
    public String compactLithologyLabel() {
        if (!generalizedLithology.isEmpty()) return titleCase(generalizedLithology);
        String[] explicit = new String[]{major1, major2, major3, minor1, minor2, minor3, minor4, minor5};
        for (String candidate : explicit) {
            if (!safe(candidate).isEmpty()) return titleCase(candidate);
        }
        return "Lithology not reported";
    }
''',
        "CNGM Stage 2: display explicit source GeoMaterial only",
        "remove lithology name inference",
    )

    replace_once(
        RESEARCH,
        '''            GeologyManifest active = geologyDataManager.getActiveManifest();
            String version = active == null || active.version.isEmpty() ? "installed snapshot" : active.version;
''',
        '''            String version = geologyDataManager.getInstalledVersion();
            if (version == null || version.trim().isEmpty()) version = "installed snapshot";
''',
        'String version = geologyDataManager.getInstalledVersion();',
        "show CNGM debug version",
    )
    replace_once(
        RESEARCH,
        '''        root.addView(action("Source & Technical Details",
                "View raw SGMC labels, full age hierarchy, references and source identifiers.",
                v -> showTechnicalDetails(group.representative())));
''',
        '''        root.addView(action("Source & Technical Details",
                "View original source-map identifiers, the standardized CNGM unit, age/lithology, and citations.",
                v -> showTechnicalDetails(group.representative())));
''',
        "standardized CNGM unit",
        "update technical-details description",
    )
    replace_once(
        RESEARCH,
        '''        if (!rocks.isEmpty()) out.append("\\nCommon rock types: ").append(rocks).append('.');
''',
        '''        if (!rocks.isEmpty()) out.append("\\nRock types represented in the mapped units: ").append(rocks).append('.');
''',
        "Rock types represented in the mapped units",
        "fix mapped-unit summary wording",
    )
    replace_once(
        RESEARCH,
        '''        append(text, "SGMC label", u.sgmcLabel);
        append(text, "Original label", u.originalLabel);
''',
        '''        append(text, "CNGM synthesis unit", u.sgmcLabel);
        append(text, "Original source-map label", u.originalLabel);
''',
        '"CNGM synthesis unit"',
        "update CNGM detail labels",
    )
    replace_once(
        RESEARCH,
        '''        append(text, "Unit link", u.unitLink);
        append(text, "Reference ID", u.referenceId);
''',
        '''        append(text, "Source map-unit key", u.unitLink);
        append(text, "Source citation ID", u.referenceId);
''',
        '"Source map-unit key"',
        "update source provenance labels",
    )

    replace_once(
        UPDATE_WORKER,
        '''            if (!GeologyRepository.SOURCE_DOI.equals(metadata(db, "source_doi"))) {
                throw new IOException("Geology database source DOI is unexpected.");
            }
''',
        '''            String sourceDoi = metadata(db, "source_doi");
            if (!"10.5066/F7WH2N65".equals(sourceDoi)
                    && !GeologyRepository.SOURCE_DOI.equals(sourceDoi)) {
                throw new IOException("Geology database source DOI is unexpected.");
            }
''',
        'String sourceDoi = metadata(db, "source_doi");',
        "allow existing SGMC updater during CNGM debug",
    )


def main() -> int:
    manifest = load_and_validate_asset()
    write_bootstrap(manifest)
    inject_sources()
    print("CNGM Stage 2 debug injection complete.")
    print("Production geology files/manifests remain untouched; this APK uses a separate debug DB.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"CNGM Stage 2 debug injection failed: {exc}", file=sys.stderr)
        raise
