#!/usr/bin/env python3
'''Runner-side CNGM Stage 2B authoritative-search injector for RockMap's tour-debug APK.'''

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
SEARCH_HELPER = ROOT / "app/src/main/java/com/rockmap/app/research/CngmAuthoritativeSearch.java"

EXPECTED_SOURCE_DOI = "10.5066/P146VGVM"
EXPECTED_SOURCE_MAP = "map50"
EXPECTED_RECORDS = 9500
EXPECTED_SOURCE_UNITS = 185
EXPECTED_SYNTHESIS_UNITS = 41
EXPECTED_CROSSWALK = 185
EXPECTED_DEBUG_STAGE = "cngm-stage2b-authoritative-search"
EXPECTED_AUTHORITY_DB_SHA256 = "6ba2201bb7d03da1318f499792cd1d2ba9fb04a3ed55d969c06408c630b82668"
EXPECTED_AUTHORITY_ARCHIVE_SHA256 = "b018765f66fd286257f8120e5ed255288975c3db4478d11ac61154671f4e082f"
EXPECTED_AUTHORITY_FULL_DOI = "10.5066/P1DC4XFG"
EXPECTED_AUTHORITY_AGE_CONCEPTS = 178
EXPECTED_AUTHORITY_GEOMATERIAL_CONCEPTS = 101
EXPECTED_AUTHORITY_LITHOLOGY_CONCEPTS = 197
EXPECTED_AUTHORITY_CONFIDENCE_CONCEPTS = 5
EXPECTED_AUTHORITY_PROPORTION_CONCEPTS = 10
EXPECTED_AUTHORITY_AGE_ASSIGNMENTS = 185
EXPECTED_AUTHORITY_LITHOLOGY_ASSIGNMENTS = 407
EXPECTED_AUTHORITY_LITHOLOGY_SOURCE_UNITS = 152
EXPECTED_AUTHORITY_AGE_QUERY_CROSSWALKS = 5
EXPECTED_AUTHORITY_DATA_REPORT_DOI = "10.3133/dr1210"


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
        "formatVersion": 2,
        "debugStage": EXPECTED_DEBUG_STAGE,
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
    authority = manifest.get("searchAuthority")
    if not isinstance(authority, dict):
        raise RuntimeError("CNGM Stage 2B manifest searchAuthority block is missing.")
    authority_required = {
        "formatVersion": 2,
        "artifactDbSha256": EXPECTED_AUTHORITY_DB_SHA256,
        "fullDatabaseDoi": EXPECTED_AUTHORITY_FULL_DOI,
        "earthSurfaceDoi": EXPECTED_SOURCE_DOI,
        "dataReportDoi": "10.3133/dr1210",
        "reviewedArchiveSha256": EXPECTED_AUTHORITY_ARCHIVE_SHA256,
        "sourceMapId": EXPECTED_SOURCE_MAP,
        "baseSourceUnits": EXPECTED_SOURCE_UNITS,
        "ageConcepts": EXPECTED_AUTHORITY_AGE_CONCEPTS,
        "geomaterialConcepts": EXPECTED_AUTHORITY_GEOMATERIAL_CONCEPTS,
        "lithologyConcepts": EXPECTED_AUTHORITY_LITHOLOGY_CONCEPTS,
        "confidenceConcepts": EXPECTED_AUTHORITY_CONFIDENCE_CONCEPTS,
        "proportionConcepts": EXPECTED_AUTHORITY_PROPORTION_CONCEPTS,
        "ageAssignments": EXPECTED_AUTHORITY_AGE_ASSIGNMENTS,
        "lithologyAssignments": EXPECTED_AUTHORITY_LITHOLOGY_ASSIGNMENTS,
        "lithologyAssignedSourceUnits": EXPECTED_AUTHORITY_LITHOLOGY_SOURCE_UNITS,
        "ageQueryCrosswalks": EXPECTED_AUTHORITY_AGE_QUERY_CROSSWALKS,
        "ageQueryCrosswalkDoi": EXPECTED_AUTHORITY_DATA_REPORT_DOI,
        "ageQueryCrosswalkReference": "USGS Data Report 1210, table 1.1",
    }
    for key, expected in authority_required.items():
        if authority.get(key) != expected:
            raise RuntimeError(
                f"CNGM Stage 2B search-authority manifest mismatch for {key}: "
                f"{authority.get(key)!r} != {expected!r}"
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
                "debug_stage": EXPECTED_DEBUG_STAGE,
                "search_authority_format_version": "2",
                "search_authority_db_sha256": EXPECTED_AUTHORITY_DB_SHA256,
                "search_authority_full_database_doi": EXPECTED_AUTHORITY_FULL_DOI,
                "search_authority_reviewed_archive_sha256": EXPECTED_AUTHORITY_ARCHIVE_SHA256,
                "search_authority_age_query_crosswalks": str(EXPECTED_AUTHORITY_AGE_QUERY_CROSSWALKS),
                "search_authority_age_crosswalk_doi": EXPECTED_AUTHORITY_DATA_REPORT_DOI,
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
            authority_counts = {
                "base_source_units": EXPECTED_SOURCE_UNITS,
                "age_concepts": EXPECTED_AUTHORITY_AGE_CONCEPTS,
                "geomaterial_concepts": EXPECTED_AUTHORITY_GEOMATERIAL_CONCEPTS,
                "lithology_concepts": EXPECTED_AUTHORITY_LITHOLOGY_CONCEPTS,
                "confidence_concepts": EXPECTED_AUTHORITY_CONFIDENCE_CONCEPTS,
                "proportion_concepts": EXPECTED_AUTHORITY_PROPORTION_CONCEPTS,
                "age_assignments": EXPECTED_AUTHORITY_AGE_ASSIGNMENTS,
                "lithology_assignments": EXPECTED_AUTHORITY_LITHOLOGY_ASSIGNMENTS,
                "age_query_crosswalk": EXPECTED_AUTHORITY_AGE_QUERY_CROSSWALKS,
            }
            for table_name, expected_count in authority_counts.items():
                actual_count = con.execute(f"SELECT COUNT(*) FROM {table_name}").fetchone()[0]
                if actual_count != expected_count:
                    raise RuntimeError(
                        f"CNGM Stage 2B authority table {table_name} count mismatch: "
                        f"{actual_count} != {expected_count}"
                    )
            if con.execute(
                "SELECT COUNT(DISTINCT source_mapunit) FROM lithology_assignments"
            ).fetchone()[0] != EXPECTED_AUTHORITY_LITHOLOGY_SOURCE_UNITS:
                raise RuntimeError("CNGM Stage 2B lithology source-unit coverage mismatch.")
            base_keys = {row[0] for row in con.execute("SELECT source_mapunit FROM base_source_units")}
            source_keys = {row[0] for row in con.execute("SELECT source_mapunit FROM source_units")}
            if base_keys != source_keys:
                raise RuntimeError("CNGM Stage 2B authority/base source_mapunit sets do not match.")
            # Search-semantics regression checks. Age intervals are half-open at shared
            # boundaries for search purposes: a Cretaceous unit ending exactly at 66 Ma
            # must not become Cenozoic merely because the intervals touch at 66 Ma.
            def age_range_units(query_youngest: float, query_oldest: float):
                return {
                    row[0] for row in con.execute(
                        """
                        SELECT DISTINCT aa.source_mapunit
                        FROM age_assignments aa
                        JOIN age_concepts amin ON amin.concept_id=aa.min_concept_id
                        JOIN age_concepts amax ON amax.concept_id=aa.max_concept_id
                        WHERE amin.t_min_ma IS NOT NULL
                          AND amax.t_max_ma IS NOT NULL
                          AND amin.t_min_ma < ?
                          AND amax.t_max_ma > ?
                        """,
                        (query_oldest, query_youngest),
                    )
                }

            def authoritative_age_units(term: str):
                concept = con.execute(
                    "SELECT t_min_ma,t_max_ma FROM age_concepts WHERE term=? COLLATE NOCASE",
                    (term,),
                ).fetchone()
                if concept is not None and concept[0] is not None and concept[1] is not None:
                    return age_range_units(float(concept[0]), float(concept[1]))
                crosswalk = con.execute(
                    """
                    SELECT amin.t_min_ma,amax.t_max_ma
                    FROM age_query_crosswalk x
                    JOIN age_concepts amin ON amin.concept_id=x.min_concept_id
                    JOIN age_concepts amax ON amax.concept_id=x.max_concept_id
                    WHERE x.query_term=? COLLATE NOCASE
                    """,
                    (term,),
                ).fetchone()
                if crosswalk is None:
                    return set()
                return age_range_units(float(crosswalk[0]), float(crosswalk[1]))

            def hierarchy_units(concept_table: str, assignment_table: str, term: str):
                units = set()
                for (key,) in con.execute(
                    f"SELECT hierarchy_key FROM {concept_table} WHERE term=? COLLATE NOCASE",
                    (term,),
                ):
                    units.update(
                        row[0] for row in con.execute(
                            f"""
                            SELECT DISTINCT source_mapunit
                            FROM {assignment_table}
                            WHERE hierarchy_key=? OR hierarchy_key LIKE ?
                            """,
                            (key, key + ".%"),
                        )
                    )
                return units

            def literal_units(column: str, term: str):
                return {
                    row[0] for row in con.execute(
                        f"SELECT DISTINCT unit_link FROM units WHERE {column} LIKE ?",
                        ("%" + term.lower() + "%",),
                    )
                }

            def polygon_count(units):
                if not units:
                    return 0
                ordered = sorted(units)
                placeholders = ",".join("?" for _ in ordered)
                return con.execute(
                    f"SELECT COUNT(*) FROM polygons WHERE source_mapunit IN ({placeholders})",
                    ordered,
                ).fetchone()[0]

            expected_age_polygons = {
                "Precambrian": 1705,
                "Cenozoic": 4289,
                "Paleogene": 2443,
                "Neogene": 2287,
                "Proterozoic": 1704,
                "Archean": 1,
                "Cretaceous": 1895,
                "Tertiary": 2475,
                "Early Proterozoic": 1400,
                "Middle Proterozoic": 351,
            }
            for term, expected in expected_age_polygons.items():
                units = authoritative_age_units(term) | literal_units("age_text", term)
                actual = polygon_count(units)
                if actual != expected:
                    raise RuntimeError(
                        f"CNGM Stage 2B age-search regression for {term}: {actual} != {expected}"
                    )

            expected_lithology_polygons = {
                "granite": 247,
                "limestone": 1075,
                "sandstone": 3624,
                "igneous": 2544,
                "sedimentary": 4906,
                "metamorphic": 1227,
            }
            for term, expected in expected_lithology_polygons.items():
                units = literal_units("lithology_text", term)
                units |= hierarchy_units(
                    "geomaterial_concepts", "source_geomaterial", term
                )
                units |= hierarchy_units(
                    "lithology_concepts", "lithology_assignments", term
                )
                actual = polygon_count(units)
                if actual != expected:
                    raise RuntimeError(
                        f"CNGM Stage 2B lithology-search regression for {term}: "
                        f"{actual} != {expected}"
                    )

            # Boundary-only contacts are not positive interval overlap.
            cambrian = con.execute(
                "SELECT t_min_ma,t_max_ma FROM age_concepts WHERE term='Cambrian'"
            ).fetchone()
            precambrian = con.execute(
                "SELECT t_min_ma,t_max_ma FROM age_concepts WHERE term='Precambrian'"
            ).fetchone()
            if cambrian is None or precambrian is None:
                raise RuntimeError("Required age-boundary regression concepts are missing.")
            exact_cambrian_units = {
                row[0] for row in con.execute(
                    """
                    SELECT source_mapunit FROM age_assignments
                    WHERE min_term='Cambrian' AND max_term='Cambrian'
                    """
                )
            }
            if exact_cambrian_units & authoritative_age_units("Precambrian"):
                raise RuntimeError(
                    "CNGM Stage 2B boundary regression: exact Cambrian units leaked into Precambrian."
                )

            crosswalk_rows = con.execute(
                """
                SELECT query_term,min_term,max_term,relationship_type,authority_doi
                FROM age_query_crosswalk ORDER BY query_term
                """
            ).fetchall()
            if len(crosswalk_rows) != EXPECTED_AUTHORITY_AGE_QUERY_CROSSWALKS:
                raise RuntimeError("CNGM Stage 2B age crosswalk row count mismatch.")
            if any(row[4] != EXPECTED_AUTHORITY_DATA_REPORT_DOI for row in crosswalk_rows):
                raise RuntimeError("CNGM Stage 2B age crosswalk authority DOI mismatch.")
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
        "CNGM Stage 2B asset validated: "
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

/** Runner-generated CNGM Stage 2B bootstrap for the tour-debug APK only. */
public final class CngmStage2DebugBootstrap {{
    public static final String DATABASE_NAME = "rockmap-cngm-stage2-debug.db";
    // Android's asset packager automatically expands source assets ending in .gz and
    // strips the .gz suffix. The packaged asset is therefore the SQLite database itself.
    private static final String ASSET_NAME = "rockmap-cngm-stage2-debug.db";
    private static final long ASSET_BYTES = {int(manifest["databaseBytes"])}L;
    private static final String ASSET_SHA256 = {java_string(manifest["databaseSha256"])};
    private static final long DATABASE_BYTES = {int(manifest["databaseBytes"])}L;
    private static final String DATABASE_SHA256 = {java_string(manifest["databaseSha256"])};
    private static final int RECORD_COUNT = {int(manifest["recordCount"])};
    private static final int SOURCE_UNIT_COUNT = {int(manifest["sourceUnitCount"])};
    private static final int SYNTHESIS_UNIT_COUNT = {int(manifest["synthesisUnitCount"])};
    private static final int CROSSWALK_COUNT = {int(manifest["crosswalkCount"])};
    private static final String SOURCE_DOI = {java_string(manifest["sourceDoi"])};
    private static final String SOURCE_MAP_ID = {java_string(manifest["sourceMapId"])};
    private static final int AGE_CONCEPT_COUNT = {int(manifest["searchAuthority"]["ageConcepts"])};
    private static final int GEOMATERIAL_CONCEPT_COUNT = {int(manifest["searchAuthority"]["geomaterialConcepts"])};
    private static final int LITHOLOGY_CONCEPT_COUNT = {int(manifest["searchAuthority"]["lithologyConcepts"])};
    private static final int AGE_ASSIGNMENT_COUNT = {int(manifest["searchAuthority"]["ageAssignments"])};
    private static final int LITHOLOGY_ASSIGNMENT_COUNT = {int(manifest["searchAuthority"]["lithologyAssignments"])};
    private static final int AGE_QUERY_CROSSWALK_COUNT = {int(manifest["searchAuthority"]["ageQueryCrosswalks"])};
    private static final String DATA_REPORT_DOI = {java_string(manifest["searchAuthority"]["ageQueryCrosswalkDoi"])};
    private static final String AUTHORITY_DB_SHA256 = {java_string(manifest["searchAuthority"]["artifactDbSha256"])};
    private static final String AUTHORITY_FULL_DOI = {java_string(manifest["searchAuthority"]["fullDatabaseDoi"])};
    private static final String AUTHORITY_ARCHIVE_SHA256 = {java_string(manifest["searchAuthority"]["reviewedArchiveSha256"])};
    private static final Object LOCK = new Object();

    private CngmStage2DebugBootstrap() {{}}

    public static void install(Context context) {{
        if (context == null) return;
        synchronized (LOCK) {{
            Context app = context.getApplicationContext();
            File research = new File(app.getFilesDir(), "research");
            File target = new File(research, DATABASE_NAME);
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
                                    + " sourceMap=" + SOURCE_MAP_ID
                                + " authority=" + AUTHORITY_FULL_DOI);
                    return;
                }}
                deleteIfExists(dbPart);
                // AAPT has already expanded the source .gz asset into ASSET_NAME inside the APK.
                // Copy those exact SQLite bytes, verify their SHA-256, then validate SQLite.
                copyAssetAndVerify(app, dbPart);
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
                                + " sourceMap=" + SOURCE_MAP_ID
                                + " authority=" + AUTHORITY_FULL_DOI
                                + " ageAssignments=" + AGE_ASSIGNMENT_COUNT
                                + " lithologyAssignments=" + LITHOLOGY_ASSIGNMENT_COUNT
                                + " ageQueryCrosswalks=" + AGE_QUERY_CROSSWALK_COUNT
                                + " dataReport=" + DATA_REPORT_DOI);
            }} catch (Exception exc) {{
                TourDebugLog.mapDiagnostic("CNGM_STAGE2_BOOT_FAIL",
                        "type=" + exc.getClass().getSimpleName()
                                + " message=" + clean(exc.getMessage()));
                deleteIfExists(dbPart);
            }} finally {{
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
            requireMetadata(db, "debug_stage", "cngm-stage2b-authoritative-search");
            requireMetadata(db, "search_authority_format_version", "2");
            requireMetadata(db, "search_authority_db_sha256", AUTHORITY_DB_SHA256);
            requireMetadata(db, "search_authority_full_database_doi", AUTHORITY_FULL_DOI);
            requireMetadata(db, "search_authority_reviewed_archive_sha256", AUTHORITY_ARCHIVE_SHA256);
            requireMetadata(db, "search_authority_age_query_crosswalks", Integer.toString(AGE_QUERY_CROSSWALK_COUNT));
            requireMetadata(db, "search_authority_age_crosswalk_doi", DATA_REPORT_DOI);
            if (scalar(db, "SELECT COUNT(*) FROM age_concepts") != AGE_CONCEPT_COUNT) {{
                throw new IOException("CNGM authoritative age-concept count mismatch.");
            }}
            if (scalar(db, "SELECT COUNT(*) FROM geomaterial_concepts") != GEOMATERIAL_CONCEPT_COUNT) {{
                throw new IOException("CNGM authoritative GeoMaterial-concept count mismatch.");
            }}
            if (scalar(db, "SELECT COUNT(*) FROM lithology_concepts") != LITHOLOGY_CONCEPT_COUNT) {{
                throw new IOException("CNGM authoritative lithology-concept count mismatch.");
            }}
            if (scalar(db, "SELECT COUNT(*) FROM age_assignments") != AGE_ASSIGNMENT_COUNT) {{
                throw new IOException("CNGM authoritative age-assignment count mismatch.");
            }}
            if (scalar(db, "SELECT COUNT(*) FROM lithology_assignments") != LITHOLOGY_ASSIGNMENT_COUNT) {{
                throw new IOException("CNGM authoritative lithology-assignment count mismatch.");
            }}
            if (scalar(db, "SELECT COUNT(*) FROM age_query_crosswalk") != AGE_QUERY_CROSSWALK_COUNT) {{
                throw new IOException("CNGM documented age-query crosswalk count mismatch.");
            }}
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
                        + " sourceMap=" + SOURCE_MAP_ID
                        + " authority=" + AUTHORITY_FULL_DOI
                        + " ageConcepts=" + AGE_CONCEPT_COUNT
                        + " lithologyConcepts=" + LITHOLOGY_CONCEPT_COUNT);
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



def write_search_helper() -> None:
    source = 'package com.rockmap.app.research;\n\nimport android.database.Cursor;\nimport android.database.sqlite.SQLiteDatabase;\n\nimport com.rockmap.app.TourDebugLog;\n\nimport java.util.ArrayList;\nimport java.util.LinkedHashSet;\nimport java.util.List;\nimport java.util.Locale;\nimport java.util.Set;\n\n/**\n * Resolves geology search terms only through relationships published in the\n * reviewed USGS CNGM relational authority tables bundled in the Stage 2B DB.\n *\n * Exact CNGM vocabulary terms may expand through CNGM hierarchy/age-range\n * relationships. Five historical-age query terms are resolved only through\n * the explicit text replacements documented in USGS Data Report 1210,\n * table 1.1. Unknown terms receive no authoritative expansion; the repository\n * may still search literal source-map text. RockMap generates no geology\n * synonym, rock-type, age, or mineral-occurrence relationships here.\n */\nfinal class CngmAuthoritativeSearch {\n    static final String AUTHORITY_DOI = "10.5066/P1DC4XFG";\n    static final String DATA_REPORT_DOI = "10.3133/dr1210";\n    static final String AUTHORITY_DB_SHA256 =\n            "6ba2201bb7d03da1318f499792cd1d2ba9fb04a3ed55d969c06408c630b82668";\n    private static final int MAX_SOURCE_UNITS = 185;\n\n    static final class Resolution {\n        final String query;\n        final String method;\n        final List<String> concepts;\n        final LinkedHashSet<String> sourceMapUnits;\n\n        Resolution(String query, String method, List<String> concepts,\n                   LinkedHashSet<String> sourceMapUnits) {\n            this.query = query;\n            this.method = method;\n            this.concepts = concepts;\n            this.sourceMapUnits = sourceMapUnits;\n        }\n    }\n\n    private static final class HierarchyConcept {\n        final String id;\n        final String term;\n        final String hierarchyKey;\n\n        HierarchyConcept(String id, String term, String hierarchyKey) {\n            this.id = id;\n            this.term = term;\n            this.hierarchyKey = hierarchyKey;\n        }\n    }\n\n    private CngmAuthoritativeSearch() {}\n\n    static Resolution resolveGeneral(SQLiteDatabase db, String query) {\n        Resolution age = resolveAge(db, query);\n        Resolution lithology = resolveLithology(db, query);\n        LinkedHashSet<String> units = new LinkedHashSet<>();\n        units.addAll(age.sourceMapUnits);\n        units.addAll(lithology.sourceMapUnits);\n        ArrayList<String> concepts = new ArrayList<>();\n        concepts.addAll(age.concepts);\n        concepts.addAll(lithology.concepts);\n        return new Resolution(normalize(query), joinMethods(age.method, lithology.method),\n                concepts, units);\n    }\n\n    static Resolution resolveAge(SQLiteDatabase db, String query) {\n        String needle = normalize(query);\n        LinkedHashSet<String> units = new LinkedHashSet<>();\n        ArrayList<String> concepts = new ArrayList<>();\n        if (needle.isEmpty()) return new Resolution(needle, "literal-only", concepts, units);\n\n        boolean exactConceptFound = false;\n        try (Cursor concept = db.rawQuery(\n                "SELECT concept_id,term,t_min_ma,t_max_ma FROM age_concepts "\n                        + "WHERE term=? COLLATE NOCASE ORDER BY concept_id",\n                new String[]{needle})) {\n            while (concept.moveToNext()) {\n                if (concept.isNull(2) || concept.isNull(3)) continue;\n                String id = safe(concept.getString(0));\n                String term = safe(concept.getString(1));\n                double queryYoungest = concept.getDouble(2);\n                double queryOldest = concept.getDouble(3);\n                if (!validInterval(queryYoungest, queryOldest)) continue;\n                exactConceptFound = true;\n                concepts.add("age:" + id + ":" + term);\n                addAgeIntervalMatches(db, queryYoungest, queryOldest, units);\n            }\n        }\n\n        String method;\n        if (exactConceptFound) {\n            method = "age-interval-overlap";\n        } else {\n            method = resolveDocumentedAgeCrosswalk(db, needle, concepts, units);\n        }\n        enforceUnitLimit(units);\n        return new Resolution(needle, method, concepts, units);\n    }\n\n    static Resolution resolveLithology(SQLiteDatabase db, String query) {\n        String needle = normalize(query);\n        LinkedHashSet<String> units = new LinkedHashSet<>();\n        ArrayList<String> concepts = new ArrayList<>();\n        if (needle.isEmpty()) return new Resolution(needle, "literal-only", concepts, units);\n\n        ArrayList<HierarchyConcept> geomaterials =\n                resolveExactHierarchyConcepts(db, "geomaterial_concepts", needle);\n        ArrayList<HierarchyConcept> lithologies =\n                resolveExactHierarchyConcepts(db, "lithology_concepts", needle);\n\n        for (HierarchyConcept concept : geomaterials) {\n            concepts.add("geomaterial:" + concept.id + ":" + concept.term);\n            addHierarchyMatches(db, "source_geomaterial", concept.hierarchyKey, units);\n        }\n        for (HierarchyConcept concept : lithologies) {\n            concepts.add("lithology:" + concept.id + ":" + concept.term);\n            addHierarchyMatches(db, "lithology_assignments", concept.hierarchyKey, units);\n        }\n        enforceUnitLimit(units);\n\n        ArrayList<String> methods = new ArrayList<>();\n        if (!geomaterials.isEmpty()) methods.add("geomaterial-exact-hierarchy");\n        if (!lithologies.isEmpty()) methods.add("lithology-exact-hierarchy");\n        return new Resolution(needle,\n                methods.isEmpty() ? "literal-only" : String.join("+", methods),\n                concepts, units);\n    }\n\n    /**\n     * Preserve literal source/CNGM text and OR it with authoritative expansions.\n     * Literal matches are facts already stored with the reviewed map; the IN-list\n     * contains only source_mapunit relationships resolved above.\n     */\n    static void appendClause(List<String> clauses, List<String> args,\n                             String literalColumn, String query, Resolution resolution) {\n        String needle = normalize(query);\n        if (needle.isEmpty()) return;\n        String literal = literalColumn + " LIKE ?";\n        args.add("%" + needle + "%");\n        if (resolution == null || resolution.sourceMapUnits.isEmpty()) {\n            clauses.add(literal);\n            return;\n        }\n        StringBuilder expanded = new StringBuilder("(").append(literal)\n                .append(" OR unit_link IN (");\n        int i = 0;\n        for (String sourceMapUnit : resolution.sourceMapUnits) {\n            if (i++ > 0) expanded.append(\',\');\n            expanded.append(\'?\');\n            args.add(sourceMapUnit);\n        }\n        expanded.append("))");\n        clauses.add(expanded.toString());\n    }\n\n    static void logResolution(String field, Resolution resolution) {\n        if (resolution == null) return;\n        TourDebugLog.mapDiagnostic("CNGM_SEARCH_RESOLVE",\n                "field=" + clean(field)\n                        + " query=" + clean(resolution.query)\n                        + " method=" + clean(resolution.method)\n                        + " concepts=" + clean(String.join("|", resolution.concepts))\n                        + " sourceUnits=" + resolution.sourceMapUnits.size()\n                        + " authority=" + AUTHORITY_DOI\n                        + " dataReport=" + DATA_REPORT_DOI\n                        + " authorityDbSha256=" + AUTHORITY_DB_SHA256);\n    }\n\n    /** Returns actual CNGM vocabulary terms only; no generated synonyms. */\n    static List<String> vocabularySuggestions(SQLiteDatabase db, String prefix, int limit) {\n        String needle = normalize(prefix);\n        int max = Math.max(0, Math.min(limit, 50));\n        LinkedHashSet<String> out = new LinkedHashSet<>();\n        if (needle.isEmpty() || max == 0) return new ArrayList<>(out);\n        addVocabularySuggestions(db, "age_concepts", needle, max, out);\n        addVocabularySuggestions(db, "geomaterial_concepts", needle, max, out);\n        addVocabularySuggestions(db, "lithology_concepts", needle, max, out);\n        addCrosswalkSuggestions(db, needle, max, out);\n        return new ArrayList<>(out);\n    }\n\n    private static String resolveDocumentedAgeCrosswalk(\n            SQLiteDatabase db, String needle, List<String> concepts, Set<String> units) {\n        boolean found = false;\n        String method = "literal-only";\n        try (Cursor crosswalk = db.rawQuery(\n                "SELECT x.query_term,x.min_term,x.max_term,x.relationship_type,x.authority_doi,"\n                        + "amin.t_min_ma,amax.t_max_ma "\n                        + "FROM age_query_crosswalk x "\n                        + "JOIN age_concepts amin ON amin.concept_id=x.min_concept_id "\n                        + "JOIN age_concepts amax ON amax.concept_id=x.max_concept_id "\n                        + "WHERE x.query_term=? COLLATE NOCASE ORDER BY x.query_term",\n                new String[]{needle})) {\n            while (crosswalk.moveToNext()) {\n                String authority = safe(crosswalk.getString(4));\n                if (!DATA_REPORT_DOI.equals(authority)\n                        || crosswalk.isNull(5) || crosswalk.isNull(6)) {\n                    continue;\n                }\n                double queryYoungest = crosswalk.getDouble(5);\n                double queryOldest = crosswalk.getDouble(6);\n                if (!validInterval(queryYoungest, queryOldest)) continue;\n                String from = safe(crosswalk.getString(0));\n                String min = safe(crosswalk.getString(1));\n                String max = safe(crosswalk.getString(2));\n                String relation = safe(crosswalk.getString(3));\n                found = true;\n                method = "dr1210-" + relation.replace(\'_\', \'-\');\n                concepts.add("age-crosswalk:" + from + "=>" + min\n                        + (min.equals(max) ? "" : " to " + max)\n                        + ":" + relation);\n                addAgeIntervalMatches(db, queryYoungest, queryOldest, units);\n            }\n        }\n        return found ? method : "literal-only";\n    }\n\n    private static void addAgeIntervalMatches(SQLiteDatabase db,\n                                              double queryYoungest,\n                                              double queryOldest,\n                                              Set<String> units) {\n        if (!validInterval(queryYoungest, queryOldest)) return;\n        try (Cursor matches = db.rawQuery(\n                "SELECT DISTINCT aa.source_mapunit "\n                        + "FROM age_assignments aa "\n                        + "JOIN age_concepts amin ON amin.concept_id=aa.min_concept_id "\n                        + "JOIN age_concepts amax ON amax.concept_id=aa.max_concept_id "\n                        + "WHERE amin.t_min_ma IS NOT NULL AND amax.t_max_ma IS NOT NULL "\n                        // Positive interval overlap only. A shared boundary alone is not overlap.\n                        + "AND amin.t_min_ma<? AND amax.t_max_ma>? "\n                        + "ORDER BY aa.source_mapunit",\n                new String[]{Double.toString(queryOldest),\n                        Double.toString(queryYoungest)})) {\n            addUnits(matches, units);\n        }\n    }\n\n    private static ArrayList<HierarchyConcept> resolveExactHierarchyConcepts(\n            SQLiteDatabase db, String table, String needle) {\n        ArrayList<HierarchyConcept> out = new ArrayList<>();\n        try (Cursor c = db.rawQuery(\n                "SELECT concept_id,term,hierarchy_key FROM " + table\n                        + " WHERE term=? COLLATE NOCASE ORDER BY hierarchy_key,concept_id",\n                new String[]{needle})) {\n            while (c.moveToNext()) {\n                String key = safe(c.getString(2));\n                if (!key.isEmpty()) {\n                    out.add(new HierarchyConcept(\n                            safe(c.getString(0)), safe(c.getString(1)), key));\n                }\n            }\n        }\n        return out;\n    }\n\n    private static void addHierarchyMatches(SQLiteDatabase db, String assignmentTable,\n                                            String hierarchyKey, Set<String> units) {\n        try (Cursor c = db.rawQuery(\n                "SELECT DISTINCT source_mapunit FROM " + assignmentTable\n                        + " WHERE hierarchy_key=? OR hierarchy_key LIKE ? ORDER BY source_mapunit",\n                new String[]{hierarchyKey, hierarchyKey + ".%"})) {\n            addUnits(c, units);\n        }\n    }\n\n    private static void addUnits(Cursor c, Set<String> out) {\n        while (c.moveToNext()) {\n            String sourceMapUnit = safe(c.getString(0));\n            if (!sourceMapUnit.isEmpty()) out.add(sourceMapUnit);\n        }\n    }\n\n    private static void enforceUnitLimit(Set<String> units) {\n        if (units.size() > MAX_SOURCE_UNITS) {\n            throw new IllegalStateException(\n                    "CNGM authoritative search exceeded reviewed source-unit scope.");\n        }\n    }\n\n    private static void addVocabularySuggestions(SQLiteDatabase db, String table,\n                                                 String needle, int max, Set<String> out) {\n        if (out.size() >= max) return;\n        try (Cursor c = db.rawQuery(\n                "SELECT term FROM " + table\n                        + " WHERE term LIKE ? COLLATE NOCASE "\n                        + "ORDER BY term COLLATE NOCASE LIMIT 100",\n                new String[]{"%" + needle + "%"})) {\n            while (c.moveToNext() && out.size() < max) {\n                String value = safe(c.getString(0));\n                if (value.length() >= 2 && value.length() <= 120) out.add(value);\n            }\n        }\n    }\n\n    private static void addCrosswalkSuggestions(SQLiteDatabase db, String needle,\n                                                int max, Set<String> out) {\n        if (out.size() >= max) return;\n        try (Cursor c = db.rawQuery(\n                "SELECT query_term FROM age_query_crosswalk "\n                        + "WHERE query_term LIKE ? COLLATE NOCASE "\n                        + "ORDER BY query_term COLLATE NOCASE LIMIT 50",\n                new String[]{"%" + needle + "%"})) {\n            while (c.moveToNext() && out.size() < max) {\n                String value = safe(c.getString(0));\n                if (!value.isEmpty()) out.add(value);\n            }\n        }\n    }\n\n    private static boolean validInterval(double youngest, double oldest) {\n        return Double.isFinite(youngest) && Double.isFinite(oldest) && youngest < oldest;\n    }\n\n    private static String joinMethods(String a, String b) {\n        boolean aReal = a != null && !a.isEmpty() && !"literal-only".equals(a);\n        boolean bReal = b != null && !b.isEmpty() && !"literal-only".equals(b);\n        if (aReal && bReal) return a + "+" + b;\n        if (aReal) return a;\n        if (bReal) return b;\n        return "literal-only";\n    }\n\n    private static String normalize(String value) {\n        return value == null ? "" : value.trim().toLowerCase(Locale.US);\n    }\n\n    private static String safe(String value) {\n        return value == null ? "" : value.trim();\n    }\n\n    private static String clean(String value) {\n        String oneLine = safe(value).replace(\'\\n\', \' \').replace(\'\\r\', \' \');\n        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500);\n    }\n}\n'
    SEARCH_HELPER.write_text(source, encoding="utf-8")
    generated = SEARCH_HELPER.read_text(encoding="utf-8")
    if "amin.t_min_ma<? AND amax.t_max_ma>?" not in generated:
        raise RuntimeError("CNGM search helper lost strict age-overlap semantics.")
    if "leading-token" in generated or 'q + " rock"' in generated:
        raise RuntimeError("CNGM search helper contains heuristic vocabulary expansion.")
    if "age_query_crosswalk" not in generated or EXPECTED_AUTHORITY_DATA_REPORT_DOI not in generated:
        raise RuntimeError("CNGM search helper lost documented DR1210 age crosswalk support.")
    print("CNGM Stage 2B authoritative search helper: generated")


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
        if (getCngmStage2DebugDatabaseFile().isFile()) return "CNGM Stage 2B authoritative-search debug";
        GeologyManifest active = getActiveManifest();
        if (active != null && resolveDatabase(active) != null) return active.version;
        if (getLegacyDatabaseFile().isFile()) return "legacy local snapshot";
        return "";
    }
''',
        'return "CNGM Stage 2B authoritative-search debug";',
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
    public static final String SOURCE_NOTE = "CNGM Stage 2B debug uses the Colorado source-map coverage from Tweto (1979) as preserved in the USGS CNGM Earth's Surface GeMS release. Original source-map facts remain distinct from standardized CNGM synthesis units. Search expansion uses reviewed CNGM age, GeoMaterial, and lithology relationships from DOI 10.5066/P1DC4XFG; neighboring-state boundary slivers are excluded. RockMap does not infer mineral occurrence from mapped geology.";
    public static final String SOURCE_SERVICE = "https://ngmdb.usgs.gov/Prodesc/proddesc_118545.htm";
''',
        "CNGM Stage 2B debug uses the Colorado source-map coverage",
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
        '''    public List<GeologyUnit> search(Filter filter, Bounds bounds, int limit) {
        ensureReady();
        Filter actual = filter == null ? new Filter("", "", "") : filter;
        int safeLimit = limit <= 0 ? 0 : Math.min(limit, 100000);
        ArrayList<String> clauses = new ArrayList<>();
        ArrayList<String> args = new ArrayList<>();
        if (!actual.text.isEmpty()) {
            clauses.add("search_text LIKE ?");
            args.add("%" + actual.text + "%");
        }
        if (!actual.lithology.isEmpty()) {
            clauses.add("lithology_text LIKE ?");
            args.add("%" + actual.lithology + "%");
        }
        if (!actual.age.isEmpty()) {
            clauses.add("age_text LIKE ?");
            args.add("%" + actual.age + "%");
        }
        appendBounds(clauses, args, bounds);
        String where = clauses.isEmpty() ? null : join(clauses, " AND ");
        return query(where, args.toArray(new String[0]), "unit_name COLLATE NOCASE ASC", safeLimit);
    }
''',
        '''    public List<GeologyUnit> search(Filter filter, Bounds bounds, int limit) {
        ensureReady();
        Filter actual = filter == null ? new Filter("", "", "") : filter;
        int safeLimit = limit <= 0 ? 0 : Math.min(limit, 100000);
        ArrayList<String> clauses = new ArrayList<>();
        ArrayList<String> args = new ArrayList<>();
        File file = findReadyDatabase();
        if (file == null) throw new IllegalStateException("CNGM Stage 2B geology database is not ready.");
        try (SQLiteDatabase authorityDb = openRead(file)) {
            if (!actual.text.isEmpty()) {
                CngmAuthoritativeSearch.Resolution resolved =
                        CngmAuthoritativeSearch.resolveGeneral(authorityDb, actual.text);
                CngmAuthoritativeSearch.logResolution("text", resolved);
                CngmAuthoritativeSearch.appendClause(
                        clauses, args, "search_text", actual.text, resolved);
            }
            if (!actual.lithology.isEmpty()) {
                CngmAuthoritativeSearch.Resolution resolved =
                        CngmAuthoritativeSearch.resolveLithology(authorityDb, actual.lithology);
                CngmAuthoritativeSearch.logResolution("lithology", resolved);
                CngmAuthoritativeSearch.appendClause(
                        clauses, args, "lithology_text", actual.lithology, resolved);
            }
            if (!actual.age.isEmpty()) {
                CngmAuthoritativeSearch.Resolution resolved =
                        CngmAuthoritativeSearch.resolveAge(authorityDb, actual.age);
                CngmAuthoritativeSearch.logResolution("age", resolved);
                CngmAuthoritativeSearch.appendClause(
                        clauses, args, "age_text", actual.age, resolved);
            }
        }
        appendBounds(clauses, args, bounds);
        String where = clauses.isEmpty() ? null : join(clauses, " AND ");
        return query(where, args.toArray(new String[0]), "unit_name COLLATE NOCASE ASC", safeLimit);
    }
''',
        "CngmAuthoritativeSearch.resolveGeneral",
        "use authoritative CNGM search relationships",
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
        '''    public List<String> suggestions(String prefix, int limit) {
        ensureReady();
        String needle = normalize(prefix);
        int max = limit <= 0 ? 20 : Math.min(limit, 50);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try (SQLiteDatabase db = openRead()) {
            String where = needle.isEmpty() ? null : "search_text LIKE ?";
            String[] args = needle.isEmpty() ? null : new String[]{"%" + needle + "%"};
            try (Cursor c = db.query("units",
                    new String[]{"generalized_lith","major1","major2","major3","unit_name"},
                    where, args, null, null, "generalized_lith COLLATE NOCASE ASC", "250")) {
                while (c.moveToNext() && out.size() < max) {
                    addSuggestion(out, c.getString(0), needle, max);
                    addSuggestion(out, c.getString(1), needle, max);
                    addSuggestion(out, c.getString(2), needle, max);
                    addSuggestion(out, c.getString(3), needle, max);
                    addSuggestion(out, c.getString(4), needle, max);
                }
            }
        }
        return new ArrayList<>(out);
    }
''',
        '''    public List<String> suggestions(String prefix, int limit) {
        ensureReady();
        String needle = normalize(prefix);
        int max = limit <= 0 ? 20 : Math.min(limit, 50);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try (SQLiteDatabase db = openRead()) {
            if (!needle.isEmpty()) {
                for (String value : CngmAuthoritativeSearch.vocabularySuggestions(db, needle, max)) {
                    if (out.size() >= max) break;
                    out.add(value);
                }
            }
            String where = needle.isEmpty() ? null : "search_text LIKE ?";
            String[] args = needle.isEmpty() ? null : new String[]{"%" + needle + "%"};
            try (Cursor c = db.query("units",
                    new String[]{"generalized_lith","major1","major2","major3","unit_name"},
                    where, args, null, null, "generalized_lith COLLATE NOCASE ASC", "250")) {
                while (c.moveToNext() && out.size() < max) {
                    addSuggestion(out, c.getString(0), needle, max);
                    addSuggestion(out, c.getString(1), needle, max);
                    addSuggestion(out, c.getString(2), needle, max);
                    addSuggestion(out, c.getString(3), needle, max);
                    addSuggestion(out, c.getString(4), needle, max);
                }
            }
        }
        return new ArrayList<>(out);
    }
''',
        "CngmAuthoritativeSearch.vocabularySuggestions",
        "add authoritative CNGM vocabulary suggestions",
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
        if (file == null) throw new IllegalStateException("CNGM Stage 2B geology database is not ready.");
        long started = SystemClock.elapsedRealtime();
        TourDebugLog.mapDiagnostic("CNGM_QUERY_START",
                "db=" + file.getName()
                        + " where=" + (where == null ? "<all>" : where)
                        + " argCount=" + (args == null ? 0 : args.length)
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
        '''        visible.setText("Search Visible Area Only");
        visible.setChecked(visibleBounds != null);
        visible.setEnabled(visibleBounds != null);
        visible.setMinHeight(dp(48));
        box.addView(text);
        box.addView(lith);
        box.addView(age);
        box.addView(visible);
''',
        '''        visible.setText("Search Visible Area Only");
        // Search Geology is statewide by default. Spatial restriction is an explicit opt-in.
        visible.setChecked(false);
        visible.setEnabled(visibleBounds != null);
        visible.setMinHeight(dp(48));
        box.addView(text);
        box.addView(lith);
        box.addView(age);
        box.addView(visible);
        box.addView(help("Searches all installed Colorado geology by default. Check this only when you want to restrict the search to the current map view."));
''',
        "Search Geology is statewide by default",
        "make geology search statewide by default",
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
    write_search_helper()
    inject_sources()
    print("CNGM Stage 2B authoritative-search injection complete.")
    print("Production geology files/manifests remain untouched; this APK uses a separate debug DB.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"CNGM Stage 2 debug injection failed: {exc}", file=sys.stderr)
        raise
