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
MAIN = ROOT / "app/src/main/java/com/rockmap/app/MainActivity.java"
COACH = ROOT / "app/src/main/java/com/rockmap/app/GuidedTourCoach.java"
UPDATE_WORKER = ROOT / "app/src/main/java/com/rockmap/app/research/GeologyDataUpdateWorker.java"
BOOTSTRAP = ROOT / "app/src/main/java/com/rockmap/app/research/CngmStage2DebugBootstrap.java"
SEARCH_HELPER = ROOT / "app/src/main/java/com/rockmap/app/research/CngmAuthoritativeSearch.java"
SEARCH_UI = ROOT / "app/src/main/java/com/rockmap/app/research/CngmSearchUi.java"
GEOLOGY_TOUR_STATE = ROOT / "app/src/main/java/com/rockmap/app/research/CngmGeologyTourState.java"

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

            # Search-UI semantics: mapped-unit lookup stays distinct from broad mapped text.
            morrison_like = "%morrison%"
            morrison_unit_count = con.execute(
                """
                SELECT COUNT(*) FROM units
                WHERE unit_name LIKE ? OR orig_label LIKE ? OR sgmc_label LIKE ? OR unit_link LIKE ?
                """,
                (morrison_like, morrison_like, morrison_like, morrison_like),
            ).fetchone()[0]
            if morrison_unit_count != 495:
                raise RuntimeError(
                    f"CNGM Stage 2B mapped-unit UI regression for Morrison: "
                    f"{morrison_unit_count} != 495"
                )

            granite_authority = literal_units("lithology_text", "granite")
            granite_authority |= hierarchy_units(
                "geomaterial_concepts", "source_geomaterial", "granite"
            )
            granite_authority |= hierarchy_units(
                "lithology_concepts", "lithology_assignments", "granite"
            )
            granite_general = literal_units("search_text", "granite") | granite_authority
            if polygon_count(granite_authority) != 247 or polygon_count(granite_general) != 873:
                raise RuntimeError(
                    "CNGM Stage 2B UI regression: Granite rock-type and mapped-text scopes collapsed."
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



def write_search_ui() -> None:
    source = r'''package com.rockmap.app.research;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.rockmap.app.GuidedTourCoach;
import com.rockmap.app.GuidedTourState;
import com.rockmap.app.TourDebugLog;
import com.rockmap.app.field.FieldTourState;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/** Stage 2B debug UI for understandable, explicit geology search semantics. */
public final class CngmSearchUi {
    interface Callback {
        void onSearch(GeologyRepository.Filter filter, GeologyRepository.Bounds bounds,
                      String resultTitle, String resultSummary);
        boolean hasTourResults();
        void onReturnToTourResults();
        boolean hasTourUnitDetails();
        void onReturnToTourUnitDetails();
        void onBack();
    }

    private enum Kind { ROCK_TYPE, AGE, UNIT, MAPPED_TEXT }
    private static final WeakHashMap<Activity, SavedState> SAVED_STATES = new WeakHashMap<>();

    private static final class SavedState {
        final String quickText;
        final Option selectedQuick;
        final String unit;
        final String lithology;
        final String age;
        final boolean refineOpen;
        final boolean visibleArea;

        SavedState(String quickText, Option selectedQuick, String unit, String lithology,
                   String age, boolean refineOpen, boolean visibleArea) {
            this.quickText = clean(quickText);
            this.selectedQuick = selectedQuick;
            this.unit = clean(unit);
            this.lithology = clean(lithology);
            this.age = clean(age);
            this.refineOpen = refineOpen;
            this.visibleArea = visibleArea;
        }
    }

    private static final class Option {
        final String label;
        final String subtitle;
        final Kind kind;
        final String query;

        Option(String label, String subtitle, Kind kind, String query) {
            this.label = clean(label);
            this.subtitle = clean(subtitle);
            this.kind = kind;
            this.query = clean(query);
        }
    }

    private static final class SearchPlan {
        final GeologyRepository.Filter filter;
        final GeologyRepository.Bounds bounds;
        final String title;
        final String summary;

        SearchPlan(GeologyRepository.Filter filter, GeologyRepository.Bounds bounds,
                   String title, String summary) {
            this.filter = filter;
            this.bounds = bounds;
            this.title = title;
            this.summary = summary;
        }
    }

    private CngmSearchUi() {}


    /** Lightweight, user-initiated external learning links for geology terms. */
    public static void showLearningSearches(Activity activity, String unit, String age, String lithology) {
        showLearningSearches(activity, unit, age, lithology, null);
    }

    public static void showLearningSearches(Activity activity, String unit, String age, String lithology,
                                            Runnable tourContinue) {
        if (activity == null) return;
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<String> subtitles = new ArrayList<>();
        final ArrayList<String> queries = new ArrayList<>();

        final String unitSearch = mappedUnitSearchTerm(unit);
        final String ageSearch = searchTerm(age);
        final String lithologySearch = searchTerm(lithology);

        if (meaningfulTerm(unit, "Mapped geologic unit") && !unitSearch.isEmpty()) {
            labels.add(clean(unit));
            subtitles.add("About this mapped unit");
            queries.add(searchQuery(unitSearch, ageSearch, "Colorado geology"));
        }
        if (meaningfulTerm(age, "Not reported") && !ageSearch.isEmpty()) {
            labels.add(clean(age));
            subtitles.add("About this geologic age");
            queries.add(searchQuery(ageSearch, "geologic age geology"));
        }
        if (meaningfulTerm(lithology, "Not reported") && !lithologySearch.isEmpty()) {
            labels.add(clean(lithology));
            subtitles.add("About this rock type");
            queries.add(searchQuery(lithologySearch, "geology rock type"));
        }

        // Prospecting searches deliberately prefer standardized rock type + age over a long
        // source-map label. This gives the search engine useful geological concepts without
        // RockMap asserting that any mineral occurs at the mapped location.
        String prospectingLabel = meaningfulTerm(unit, "Mapped geologic unit") ? clean(unit)
                : meaningfulTerm(lithology, "Not reported") ? clean(lithology) : "";
        String prospectingGeology = !lithologySearch.isEmpty() ? lithologySearch : unitSearch;
        if (!prospectingLabel.isEmpty() && !prospectingGeology.isEmpty()) {
            labels.add(prospectingLabel);
            subtitles.add("Explore rockhounding & prospecting context");
            queries.add(searchQuery(prospectingGeology, ageSearch,
                    "Colorado rockhounding prospecting minerals geology"));
        }
        if (queries.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Learn online")
                    .setMessage("This mapped polygon does not expose a geology term that can be searched safely.")
                    .setPositiveButton("Close", null)
                    .show();
            return;
        }

        Dialog dialog = new Dialog(activity);
        LinearLayout shell = new LinearLayout(activity);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadii(new float[]{dp(activity, 18), dp(activity, 18), dp(activity, 18), dp(activity, 18), 0, 0, 0, 0});
        shell.setBackground(bg);

        TextView title = standaloneText(activity, "Search online", 20f, 0xff202020, true);
        title.setPadding(0, 0, 0, dp(activity, 4));
        shell.addView(title);
        TextView intro = standaloneText(activity,
                "Choose what you want to learn about. Online search results are external information and may not apply to this mapped location.",
                13f, 0xff555555, false);
        intro.setTextIsSelectable(true);
        intro.setPadding(0, 0, 0, dp(activity, 8));
        shell.addView(intro);

        ScrollView scroller = new ScrollView(activity);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        boolean prospectingHeadingAdded = false;
        for (int i = 0; i < queries.size(); i++) {
            final int index = i;
            boolean prospecting = "Explore rockhounding & prospecting context".equals(subtitles.get(i));
            if (prospecting && !prospectingHeadingAdded) {
                TextView heading = standaloneText(activity, "ROCKHOUNDING & PROSPECTING", 12f, 0xff666666, true);
                heading.setPadding(0, dp(activity, 14), 0, dp(activity, 4));
                rows.addView(heading);
                prospectingHeadingAdded = true;
            }
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(activity, 12), dp(activity, 9), dp(activity, 12), dp(activity, 9));
            row.setMinimumHeight(dp(activity, 58));
            row.setClickable(true);
            row.setFocusable(true);
            applyStandaloneSelectable(activity, row);
            TextView primary = standaloneText(activity, labels.get(i), 15.5f, 0xff202020, true);
            TextView secondary = standaloneText(activity, subtitles.get(i) + "   ↗", 13f, 0xff205b93, false);
            secondary.setPadding(0, dp(activity, 2), 0, 0);
            row.addView(primary);
            row.addView(secondary);
            row.setContentDescription(labels.get(i) + ". " + subtitles.get(i) + ". Opens an external web search in your browser.");
            row.setOnClickListener(v -> {
                dialog.dismiss();
                openOnlineSearch(activity, queries.get(index));
            });
            rows.addView(row);
        }
        scroller.addView(rows);
        shell.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button close = new Button(activity);
        close.setText("Close");
        close.setAllCaps(false);
        close.setMinHeight(dp(activity, 52));
        close.setOnClickListener(v -> dialog.dismiss());
        shell.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(shell);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.32f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.show();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (CngmGeologyTourState.isActive(activity)
                && CngmGeologyTourState.step(activity) == 8) {
            TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_TRANSITION",
                    "screen=Search online chooser step=8 rows=" + queries.size());
            FrameLayout host = GuidedTourCoach.prepareDialogHost(activity, dialog);
            GuidedTourCoach.show(activity, host, 8, 9,
                    "Search online",
                    "Choose whether to research the mapped unit, its geologic age, its rock type, or rockhounding and prospecting context. The browser only opens if you choose one of these rows.",
                    "Review the available research paths.", shell,
                    null,
                    "Next", () -> {
                        dialog.dismiss();
                        CngmGeologyTourState.setStep(activity, 9);
                        CngmGeologyTourState.setPhase(activity, CngmGeologyTourState.PHASE_AWAIT_MAP);
                        TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_ACTION",
                                "step=8 action=close chooser expectedNext=Show Geology on Map");
                        GuidedTourCoach.clear(activity);
                        if (tourContinue != null) activity.getWindow().getDecorView().post(tourContinue);
                    },
                    () -> {
                        dialog.dismiss();
                        CngmGeologyTourState.setStep(activity, 9);
                        CngmGeologyTourState.setPhase(activity, CngmGeologyTourState.PHASE_AWAIT_MAP);
                        GuidedTourCoach.clear(activity);
                        if (tourContinue != null) activity.getWindow().getDecorView().post(tourContinue);
                    },
                    () -> {
                        dialog.dismiss();
                        CngmGeologyTourState.exit(activity);
                        TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_EXIT", "screen=Search online chooser step=8");
                        GuidedTourCoach.clear(activity);
                    });
        }
    }

    private static void openOnlineSearch(Activity activity, String query) {
        try {
            // Always send a normal unquoted keyword query. Quotation marks and source-map
            // punctuation can make obscure map labels effectively impossible to match online.
            String onlineQuery = searchTerm(query);
            android.net.Uri uri = android.net.Uri.parse(
                    "https://www.google.com/search?q=" + android.net.Uri.encode(onlineQuery));
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, uri);
            activity.startActivity(intent);
        } catch (RuntimeException ex) {
            new AlertDialog.Builder(activity)
                    .setTitle("Could not open browser")
                    .setMessage("Android could not open the external web search.")
                    .setPositiveButton("Close", null)
                    .show();
        }
    }

    private static boolean meaningfulTerm(String value, String fallback) {
        String clean = clean(value);
        return !clean.isEmpty() && !clean.equalsIgnoreCase(fallback)
                && !clean.equalsIgnoreCase("Lithology not reported");
    }

    /**
     * Convert a displayed geology value into forgiving search-engine keywords only.
     * This never alters the value RockMap displays or stores.
     */
    private static String searchTerm(String value) {
        String out = clean(value);
        if (out.isEmpty()) return "";
        out = out.replace('\u2013', ' ').replace('\u2014', ' ').replace('\u2212', ' ');
        out = out.replaceAll("[\\p{Punct}]+", " ");
        out = out.replaceAll("\\s+", " ").trim();
        return out;
    }

    /**
     * Source-map unit labels sometimes contain publication-era numeric age notation that is
     * useful on the map but poor web-search vocabulary. Remove only that search noise, then
     * add the separately stored standardized age as context in the query builder.
     */
    private static String mappedUnitSearchTerm(String value) {
        String out = clean(value);
        if (out.isEmpty()) return "";
        out = out.replaceAll(
                "(?i)\\([^)]*(?:\\bage\\b|\\bm\\.?\\s*y\\.?\\b|\\bma\\b|million\\s+years?)[^)]*\\)",
                " ");
        out = out.replaceAll(
                "(?i)\\b\\d[\\d,]*(?:\\.\\d+)?\\s*[-\u2013\u2014]?\\s*m\\.?\\s*y\\.?\\b",
                " ");
        out = out.replaceAll(
                "(?i)\\b\\d[\\d,]*(?:\\.\\d+)?\\s*(?:to|[-\u2013\u2014])\\s*\\d[\\d,]*(?:\\.\\d+)?\\s*(?:ma|m\\.?\\s*y\\.?)\\b",
                " ");
        out = out.replaceAll("(?i)\\bof\\s+age\\s+group\\b", " ");
        out = out.replaceAll("(?i)\\bage\\s+group\\b", " ");
        out = searchTerm(out);
        out = out.replaceAll("(?i)\\bof\\s*$", " ").trim();
        return out;
    }

    private static String searchQuery(String... parts) {
        StringBuilder out = new StringBuilder();
        if (parts != null) {
            for (String part : parts) {
                String normalized = searchTerm(part);
                if (normalized.isEmpty()) continue;
                if (out.length() > 0) out.append(' ');
                out.append(normalized);
            }
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    private static TextView standaloneText(Activity activity, String value, float sp, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private static void applyStandaloneSelectable(Activity activity, View view) {
        android.util.TypedValue selectable = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                selectable, true) && selectable.resourceId != 0) {
            view.setBackgroundResource(selectable.resourceId);
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    static void show(Activity activity, GeologyRepository geology,
                     GeologyRepository.Bounds visibleBounds, Callback callback) {
        if (activity == null || geology == null || callback == null) return;
        new Screen(activity, geology, visibleBounds, callback).show();
    }

    private static final class Screen {
        private final Activity activity;
        private final GeologyRepository geology;
        private final GeologyRepository.Bounds visibleBounds;
        private final Callback callback;

        private EditText quick;
        private Button clearQuick;
        private LinearLayout suggestions;
        private LinearLayout interpretationRow;
        private TextView interpretationText;
        private Button changeInterpretation;
        private Button refineToggle;
        private LinearLayout refineBox;
        private AutoCompleteTextView unitField;
        private AutoCompleteTextView lithologyField;
        private AutoCompleteTextView ageField;
        private RadioButton allColorado;
        private RadioButton visibleArea;
        private Button searchButton;
        private Button termsButton;
        private Option selectedQuick;
        private boolean suppressQuickWatcher;
        private int tourStep;
        private View geologyIntroTarget;
        private View examplesTarget;
        private View searchAreaTarget;

        Screen(Activity activity, GeologyRepository geology,
               GeologyRepository.Bounds visibleBounds, Callback callback) {
            this.activity = activity;
            this.geology = geology;
            this.visibleBounds = visibleBounds;
            this.callback = callback;
        }

        void show() {
            GuidedTourCoach.clear(activity);
            ScrollView scroll = new ScrollView(activity);
            scroll.setFillViewport(true);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(16), dp(10), dp(16), dp(28));
            root.setBackgroundColor(0xfffafafa);
            scroll.addView(root, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.setOnApplyWindowInsetsListener((v, insets) -> {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            });

            LinearLayout topBar = new LinearLayout(activity);
            topBar.setOrientation(LinearLayout.HORIZONTAL);
            topBar.setGravity(Gravity.CENTER_VERTICAL);
            Button back = textButton("‹ Research");
            back.setContentDescription("Back to Research");
            back.setOnClickListener(v -> leave());
            topBar.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1f));
            Button help = textButton("?");
            help.setTextSize(20f);
            help.setTypeface(Typeface.DEFAULT_BOLD);
            help.setGravity(Gravity.CENTER);
            help.setContentDescription("Search Geology help and guided tour");
            help.setOnClickListener(v -> showHelp());
            topBar.addView(help, new LinearLayout.LayoutParams(dp(48), dp(48)));
            root.addView(topBar);

            LinearLayout geologyIntro = new LinearLayout(activity);
            geologyIntro.setOrientation(LinearLayout.VERTICAL);
            TextView title = text("Search Geology", 24f, 0xff202020, true);
            title.setPadding(0, dp(4), 0, dp(6));
            geologyIntro.addView(title);
            TextView intro = helper("Search Colorado's mapped geology by rock type, geologic age, or mapped unit.");
            intro.setPadding(0, 0, 0, dp(18));
            geologyIntro.addView(intro);
            geologyIntroTarget = geologyIntro;
            root.addView(geologyIntro, matchWrap());

            root.addView(fieldLabel("Search geology"));
            quick = new EditText(activity);
            quick.setSingleLine(true);
            quick.setTextSize(16f);
            quick.setHint("Granite, Cretaceous, Morrison...");
            quick.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            quick.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
            quick.setCompoundDrawablePadding(dp(8));
            quick.setPadding(dp(12), dp(8), dp(8), dp(8));
            quick.setMinHeight(dp(56));
            LinearLayout quickRow = new LinearLayout(activity);
            quickRow.setOrientation(LinearLayout.HORIZONTAL);
            quickRow.setGravity(Gravity.CENTER_VERTICAL);
            quickRow.addView(quick, new LinearLayout.LayoutParams(0, dp(56), 1f));
            clearQuick = textButton("×");
            clearQuick.setGravity(Gravity.CENTER);
            clearQuick.setContentDescription("Clear geology search");
            clearQuick.setVisibility(View.GONE);
            clearQuick.setOnClickListener(v -> {
                quick.setText("");
                quick.requestFocus();
                showKeyboard(quick);
            });
            quickRow.addView(clearQuick, new LinearLayout.LayoutParams(dp(48), dp(56)));
            root.addView(quickRow, matchWrap());

            suggestions = new LinearLayout(activity);
            suggestions.setOrientation(LinearLayout.VERTICAL);
            suggestions.setVisibility(View.GONE);
            root.addView(suggestions, matchWrap());

            interpretationRow = new LinearLayout(activity);
            interpretationRow.setOrientation(LinearLayout.HORIZONTAL);
            interpretationRow.setGravity(Gravity.CENTER_VERTICAL);
            interpretationRow.setPadding(0, dp(6), 0, dp(6));
            interpretationRow.setVisibility(View.GONE);
            interpretationText = helper("");
            interpretationText.setPadding(0, 0, dp(6), 0);
            interpretationText.setTextIsSelectable(true);
            interpretationRow.addView(interpretationText,
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            changeInterpretation = textButton("Change");
            changeInterpretation.setTextSize(13f);
            changeInterpretation.setOnClickListener(v -> changeInterpretation());
            interpretationRow.addView(changeInterpretation,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)));
            root.addView(interpretationRow, matchWrap());

            refineToggle = disclosureButton("Refine search", false);
            refineToggle.setOnClickListener(v -> toggleRefine());
            root.addView(refineToggle, matchWrap());

            refineBox = new LinearLayout(activity);
            refineBox.setOrientation(LinearLayout.VERTICAL);
            refineBox.setVisibility(View.GONE);
            refineBox.setPadding(0, dp(4), 0, dp(8));
            refineBox.addView(helper("Use a specific geology field to narrow results."));

            unitField = autocompleteField("Morrison...");
            addRefineField(refineBox, "Mapped unit or name", unitField,
                    "Names and labels used on the geologic map");
            lithologyField = autocompleteField("Granite...");
            addRefineField(refineBox, "Rock type (lithology)", lithologyField,
                    "The type of rock or sediment");
            ageField = autocompleteField("Cretaceous...");
            addRefineField(refineBox, "Geologic age", ageField,
                    "When the mapped material formed or was deposited");
            TextView combined = helper("Filters are combined.");
            combined.setPadding(0, dp(2), 0, dp(4));
            refineBox.addView(combined);
            root.addView(refineBox, matchWrap());

            TextView areaHeading = fieldLabel("Search area");
            areaHeading.setPadding(0, dp(18), 0, dp(3));
            root.addView(areaHeading);
            RadioGroup areaGroup = new RadioGroup(activity);
            searchAreaTarget = areaGroup;
            areaGroup.setOrientation(RadioGroup.VERTICAL);
            allColorado = radio("All Colorado", "Search all installed Colorado geology");
            allColorado.setId(View.generateViewId());
            visibleArea = radio("Visible map area", visibleBounds == null
                    ? "Return to the map to search its visible area"
                    : "Search only the area shown on the map");
            visibleArea.setId(View.generateViewId());
            allColorado.setChecked(true);
            visibleArea.setEnabled(visibleBounds != null);
            if (visibleBounds == null) visibleArea.setAlpha(0.55f);
            areaGroup.addView(allColorado);
            areaGroup.addView(visibleArea);
            root.addView(areaGroup, matchWrap());

            searchButton = primaryButton("SEARCH GEOLOGY");
            searchButton.setEnabled(false);
            searchButton.setOnClickListener(v -> submit());
            LinearLayout.LayoutParams searchParams = matchWrap();
            searchParams.setMargins(0, dp(18), 0, dp(8));
            root.addView(searchButton, searchParams);

            termsButton = disclosureButton("Geology search terms", false);
            termsButton.setOnClickListener(v -> showDefinitions());
            root.addView(termsButton, matchWrap());

            installWatchers();
            configureRefineAutocomplete();
            restoreSavedState();
            updateSearchButton();
            activity.setContentView(scroll);
            scroll.requestApplyInsets();
            if (CngmGeologyTourState.isActive(activity)) {
                int resumeStep = CngmGeologyTourState.step(activity);
                if (resumeStep == 5) {
                    CngmGeologyTourState.setStep(activity, 6);
                    resumeStep = 6;
                    TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_RECOVER",
                            "screen=search invalidStep=5 recoveredStep=6 reason=unit-details-not-active");
                }
                if (resumeStep == 1 || resumeStep == 2 || resumeStep == 3
                        || resumeStep == 6 || resumeStep == 7) {
                    tourStep = resumeStep;
                    TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_RESUME",
                            "screen=search step=" + resumeStep);
                    activity.getWindow().getDecorView().post(this::showTourStep);
                }
            }
        }

        private void restoreSavedState() {
            SavedState saved = SAVED_STATES.get(activity);
            if (saved == null) return;
            suppressQuickWatcher = true;
            quick.setText(saved.quickText);
            quick.setSelection(quick.getText().length());
            suppressQuickWatcher = false;
            selectedQuick = saved.selectedQuick;
            unitField.setText(saved.unit);
            lithologyField.setText(saved.lithology);
            ageField.setText(saved.age);
            if (saved.refineOpen) {
                refineBox.setVisibility(View.VISIBLE);
                refineToggle.setText("Refine search   ⌄");
            }
            if (saved.visibleArea && visibleBounds != null) visibleArea.setChecked(true);
            else allColorado.setChecked(true);
            clearQuick.setVisibility(saved.quickText.isEmpty() ? View.GONE : View.VISIBLE);
            updateInterpretation();
        }

        private void saveState() {
            SAVED_STATES.put(activity, new SavedState(
                    quick.getText().toString(), selectedQuick,
                    unitField.getText().toString(), lithologyField.getText().toString(),
                    ageField.getText().toString(), refineBox.getVisibility() == View.VISIBLE,
                    visibleArea.isChecked()));
        }

        private void installWatchers() {
            quick.addTextChangedListener(new SimpleWatcher() {
                @Override public void changed(String value) {
                    if (suppressQuickWatcher) return;
                    selectedQuick = null;
                    clearQuick.setVisibility(value.trim().isEmpty() ? View.GONE : View.VISIBLE);
                    updateInterpretation();
                    if (quick.hasFocus()) renderSuggestions(value);
                    updateSearchButton();
                }
            });
            quick.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) return;
                renderSuggestions(quick.getText().toString());
            });
            quick.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    submit();
                    return true;
                }
                return false;
            });
            SimpleWatcher update = new SimpleWatcher() {
                @Override public void changed(String value) { updateSearchButton(); }
            };
            unitField.addTextChangedListener(update);
            lithologyField.addTextChangedListener(update);
            ageField.addTextChangedListener(update);
        }

        private void configureRefineAutocomplete() {
            configureAutocomplete(unitField, Kind.UNIT);
            configureAutocomplete(lithologyField, Kind.ROCK_TYPE);
            configureAutocomplete(ageField, Kind.AGE);
        }

        private void configureAutocomplete(AutoCompleteTextView field, Kind kind) {
            field.setThreshold(1);
            field.setOnFocusChangeListener((v, focused) -> {
                if (focused && !field.getText().toString().trim().isEmpty()) {
                    refreshAutocomplete(field, kind);
                    field.showDropDown();
                }
            });
            field.addTextChangedListener(new SimpleWatcher() {
                @Override public void changed(String value) {
                    if (field.hasFocus() && !value.trim().isEmpty()) {
                        refreshAutocomplete(field, kind);
                        field.showDropDown();
                    }
                }
            });
        }

        private void refreshAutocomplete(AutoCompleteTextView field, Kind kind) {
            String prefix = field.getText().toString().trim();
            if (prefix.isEmpty()) return;
            List<String> values;
            if (kind == Kind.UNIT) values = unitSuggestions(prefix, 8);
            else if (kind == Kind.AGE) values = vocabularySuggestions(prefix, Kind.AGE, 8);
            else values = vocabularySuggestions(prefix, Kind.ROCK_TYPE, 8);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_dropdown_item_1line, values);
            field.setAdapter(adapter);
        }

        private void renderSuggestions(String raw) {
            String query = raw == null ? "" : raw.trim();
            examplesTarget = null;
            suggestions.removeAllViews();
            suggestions.setVisibility(View.VISIBLE);
            if (query.isEmpty()) {
                renderExamples();
                return;
            }
            List<Option> options = typedSuggestions(query, 5);
            for (Option option : options) suggestions.addView(suggestionRow(option));
            Option broad = new Option("Search mapped text for “" + query + "”",
                    "Broader search", Kind.MAPPED_TEXT, query);
            suggestions.addView(suggestionRow(broad));
        }

        private void renderExamples() {
            LinearLayout examples = new LinearLayout(activity);
            examples.setOrientation(LinearLayout.VERTICAL);
            examples.addView(microHeading("TRY AN EXAMPLE"));
            examples.addView(suggestionRow(
                    new Option("Granite", "Rock type", Kind.ROCK_TYPE, "Granite")));
            examples.addView(suggestionRow(
                    new Option("Sandstone", "Rock type", Kind.ROCK_TYPE, "Sandstone")));
            examples.addView(suggestionRow(
                    new Option("Cretaceous", "Geologic age", Kind.AGE, "Cretaceous")));
            examples.addView(suggestionRow(
                    new Option("Precambrian", "Geologic age", Kind.AGE, "Precambrian")));
            examples.addView(suggestionRow(
                    new Option("Morrison", "Mapped unit or name", Kind.UNIT, "Morrison")));
            Button more = disclosureButton("Browse more examples", false);
            more.setOnClickListener(v -> showMoreExamples());
            examples.addView(more, matchWrap());
            examplesTarget = examples;
            suggestions.addView(examples, matchWrap());
        }

        private View suggestionRow(Option option) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            row.setMinimumHeight(dp(56));
            applySelectable(row);
            TextView primary = text(option.label, 16f, 0xff202020, false);
            TextView secondary = text(option.subtitle, 13f, 0xff666666, false);
            secondary.setPadding(0, dp(2), 0, 0);
            row.addView(primary);
            row.addView(secondary);
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(option.label + ". " + option.subtitle + ".");
            row.setOnClickListener(v -> applyOption(option));
            return row;
        }

        private void applyOption(Option option) {
            selectedQuick = option;
            suppressQuickWatcher = true;
            quick.setText(option.query);
            quick.setSelection(quick.getText().length());
            suppressQuickWatcher = false;
            suggestions.setVisibility(View.GONE);
            hideKeyboard(quick);
            quick.clearFocus();
            updateInterpretation();
            updateSearchButton();
        }

        private void changeInterpretation() {
            selectedQuick = null;
            updateInterpretation();
            quick.requestFocus();
            showKeyboard(quick);
            renderSuggestions(quick.getText().toString());
        }

        private void updateInterpretation() {
            if (selectedQuick == null) {
                interpretationRow.setVisibility(View.GONE);
                return;
            }
            interpretationText.setText("Searching as: " + interpretationLabel(selectedQuick));
            interpretationRow.setVisibility(View.VISIBLE);
        }

        private void toggleRefine() {
            boolean opening = refineBox.getVisibility() != View.VISIBLE;
            refineBox.setVisibility(opening ? View.VISIBLE : View.GONE);
            refineToggle.setText("Refine search   " + (opening ? "⌄" : "›"));
        }

        private void submit() {
            String quickText = quick.getText().toString().trim();
            String unitText = unitField.getText().toString().trim();
            String lithText = lithologyField.getText().toString().trim();
            String ageText = ageField.getText().toString().trim();
            if (quickText.isEmpty() && unitText.isEmpty() && lithText.isEmpty() && ageText.isEmpty()) {
                quick.setError("Enter a geology term or add a filter.");
                return;
            }
            unitField.setError(null);
            lithologyField.setError(null);
            ageField.setError(null);

            if (!lithText.isEmpty() && !isSupportedExact(lithText, Kind.ROCK_TYPE)) {
                lithologyField.setError("Choose a supported rock type from the suggestions.");
                lithologyField.requestFocus();
                refreshAutocomplete(lithologyField, Kind.ROCK_TYPE);
                lithologyField.showDropDown();
                return;
            }
            if (!ageText.isEmpty() && !isSupportedExact(ageText, Kind.AGE)) {
                ageField.setError("Choose a supported geologic age from the suggestions.");
                ageField.requestFocus();
                refreshAutocomplete(ageField, Kind.AGE);
                ageField.showDropDown();
                return;
            }
            if (!unitText.isEmpty() && !hasUnitMatch(unitText)) {
                unitField.setError("No mapped unit or label matches this text.");
                unitField.requestFocus();
                return;
            }

            if (selectedQuick == null && !quickText.isEmpty()) {
                List<Option> exact = exactInterpretations(quickText);
                if (exact.size() > 1) {
                    showMeaningChoice(quickText, exact);
                    return;
                }
                selectedQuick = exact.isEmpty()
                        ? new Option(quickText, "Mapped text", Kind.MAPPED_TEXT, quickText)
                        : exact.get(0);
                updateInterpretation();
            }

            if (selectedQuick != null) {
                if (selectedQuick.kind == Kind.UNIT && !unitText.isEmpty()) {
                    unitField.setError("Quick search already uses a mapped-unit search. Clear one of them.");
                    return;
                }
                if (selectedQuick.kind == Kind.ROCK_TYPE && !lithText.isEmpty()) {
                    lithologyField.setError("Quick search already uses a rock-type search. Clear one of them.");
                    return;
                }
                if (selectedQuick.kind == Kind.AGE && !ageText.isEmpty()) {
                    ageField.setError("Quick search already uses a geologic-age search. Clear one of them.");
                    return;
                }
            }

            String mappedText = "";
            String unit = unitText;
            String lith = lithText;
            String age = ageText;
            ArrayList<String> summaryParts = new ArrayList<>();
            String title = "Geology Search";
            if (selectedQuick != null) {
                title = selectedQuick.query;
                if (selectedQuick.kind == Kind.MAPPED_TEXT) mappedText = selectedQuick.query;
                else if (selectedQuick.kind == Kind.UNIT) unit = selectedQuick.query;
                else if (selectedQuick.kind == Kind.ROCK_TYPE) lith = selectedQuick.query;
                else if (selectedQuick.kind == Kind.AGE) age = selectedQuick.query;
                summaryParts.add(interpretationLabel(selectedQuick));
            }
            if (!unitText.isEmpty()) summaryParts.add("Mapped unit · " + unitText);
            if (!lithText.isEmpty()) summaryParts.add("Rock type · " + lithText);
            if (!ageText.isEmpty()) summaryParts.add("Geologic age · " + ageText);

            GeologyRepository.Bounds bounds = visibleArea.isChecked() ? visibleBounds : null;
            String area = bounds == null ? "All Colorado" : "Visible map area";
            StringBuilder summary = new StringBuilder();
            if (!summaryParts.isEmpty()) summary.append(String.join("\n", summaryParts)).append('\n');
            summary.append(area);
            if (selectedQuick != null && selectedQuick.kind == Kind.MAPPED_TEXT) {
                summary.append("\nBroader mapped-text searches can match names and descriptive text in addition to rock-type and age information.");
            }
            SearchPlan plan = new SearchPlan(
                    new GeologyRepository.Filter(mappedText, unit, lith, age),
                    bounds, title, summary.toString());
            if (CngmGeologyTourState.isActive(activity)) {
                int activeStep = tourStep > 0 ? tourStep : CngmGeologyTourState.step(activity);
                if (activeStep >= 1 && activeStep <= 3) {
                    tourStep = 3;
                    CngmGeologyTourState.setStep(activity, 3);
                    CngmGeologyTourState.setPhase(activity, CngmGeologyTourState.PHASE_AWAIT_RESULTS);
                    TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_SEARCH_STARTED",
                            "fromStep=" + activeStep + " expectedNext=4 area=" + area);
                } else if (activeStep == 6 || activeStep == 7) {
                    tourStep = 7;
                    CngmGeologyTourState.setStep(activity, 7);
                    CngmGeologyTourState.setPhase(activity,
                            CngmGeologyTourState.PHASE_AWAIT_LEARN_MORE_RESULTS);
                    TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_SEARCH_STARTED",
                            "fromStep=" + activeStep + " expectedNext=8 area=" + area);
                }
            }
            saveState();
            GuidedTourCoach.clear(activity);
            hideKeyboard(quick);
            callback.onSearch(plan.filter, plan.bounds, plan.title, plan.summary);
        }

        private void showMeaningChoice(String query, List<Option> choices) {
            ArrayList<String> labels = new ArrayList<>();
            for (Option option : choices) labels.add(interpretationLabel(option));
            labels.add("Mapped text · “" + query + "” — broader search");
            String[] rows = labels.toArray(new String[0]);
            new AlertDialog.Builder(activity)
                    .setTitle("What do you mean by “" + query + "”?")
                    .setItems(rows, (d, which) -> {
                        if (which < choices.size()) applyOption(choices.get(which));
                        else applyOption(new Option(query, "Mapped text", Kind.MAPPED_TEXT, query));
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }

        private List<Option> exactInterpretations(String query) {
            ArrayList<Option> out = new ArrayList<>();
            File file = geology.getDatabaseFile();
            if (file == null) return out;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
                CngmAuthoritativeSearch.Resolution age = CngmAuthoritativeSearch.resolveAge(db, query);
                if (!"literal-only".equals(age.method)) {
                    out.add(new Option(displayConcept(age, query), ageSubtitle(age), Kind.AGE, query));
                }
                CngmAuthoritativeSearch.Resolution lith = CngmAuthoritativeSearch.resolveLithology(db, query);
                if (!"literal-only".equals(lith.method)) {
                    out.add(new Option(displayConcept(lith, query), "Rock type", Kind.ROCK_TYPE, query));
                }
                String exactUnit = exactUnitName(db, query);
                if (!exactUnit.isEmpty()) {
                    out.add(new Option(exactUnit, "Mapped unit or name", Kind.UNIT, exactUnit));
                }
            } catch (RuntimeException ignored) {
                // Search can still safely fall back to mapped text.
            }
            return dedupeOptions(out, 4);
        }

        private List<Option> typedSuggestions(String query, int limit) {
            ArrayList<Option> out = new ArrayList<>();
            File file = geology.getDatabaseFile();
            if (file == null) return out;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
                addTermOptions(db, "lithology_concepts", query, "Rock type", Kind.ROCK_TYPE, out, limit);
                addTermOptions(db, "geomaterial_concepts", query, "Rock type", Kind.ROCK_TYPE, out, limit);
                addTermOptions(db, "age_concepts", query, "Geologic age", Kind.AGE, out, limit);
                if (out.size() < limit) addCrosswalkOptions(db, query, out, limit);
                if (out.size() < limit) addUnitOptions(db, query, out, limit);
            } catch (RuntimeException ignored) {
                return new ArrayList<>();
            }
            return dedupeOptions(out, limit);
        }

        private void showMoreExamples() {
            hideKeyboard(quick);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(18), dp(8), dp(18), dp(18));
            content.addView(helper("Tap any example to use it as the search."));
            content.addView(microHeading("ROCK TYPES"));
            String[] rocks = {"Granite", "Sandstone", "Limestone", "Igneous", "Sedimentary", "Metamorphic"};
            for (String value : rocks) {
                if (isSupportedExact(value, Kind.ROCK_TYPE)) {
                    content.addView(bottomSheetExample(value, "Rock type", Kind.ROCK_TYPE));
                }
            }
            content.addView(microHeading("GEOLOGIC AGES"));
            String[] ages = {"Precambrian", "Cretaceous", "Paleogene", "Neogene", "Proterozoic", "Tertiary"};
            for (String value : ages) {
                if (isSupportedExact(value, Kind.AGE)) {
                    content.addView(bottomSheetExample(value, "Geologic age", Kind.AGE));
                }
            }
            content.addView(microHeading("MAPPED UNITS & NAMES"));
            LinkedHashSet<String> units = new LinkedHashSet<>();
            units.add("Morrison");
            units.addAll(popularUnitExamples(7));
            for (String value : units) {
                if (hasUnitMatch(value)) content.addView(bottomSheetExample(value, "Mapped unit or name", Kind.UNIT));
            }
            showBottomSheet("Browse geology examples", content);
        }

        private View bottomSheetExample(String value, String subtitle, Kind kind) {
            Option option = new Option(value, subtitle, kind, value);
            View row = suggestionRow(option);
            row.setOnClickListener(v -> {
                dismissBottomSheet();
                applyOption(option);
            });
            return row;
        }

        private Dialog activeBottomSheet;

        private void showBottomSheet(String title, View content) {
            dismissBottomSheet();
            Dialog dialog = new Dialog(activity);
            activeBottomSheet = dialog;
            LinearLayout shell = new LinearLayout(activity);
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.setPadding(dp(4), dp(10), dp(4), dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadii(new float[]{dp(18), dp(18), dp(18), dp(18), 0, 0, 0, 0});
            shell.setBackground(bg);
            TextView heading = text(title, 20f, 0xff202020, true);
            heading.setPadding(dp(18), dp(6), dp(18), dp(8));
            shell.addView(heading);
            ScrollView scroller = new ScrollView(activity);
            scroller.addView(content);
            shell.addView(scroller, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            Button close = textButton("Close");
            close.setOnClickListener(v -> dismissBottomSheet());
            shell.addView(close, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            dialog.setContentView(shell);
            dialog.setOnDismissListener(d -> activeBottomSheet = null);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setDimAmount(0.32f);
                w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setGravity(Gravity.BOTTOM);
            }
            dialog.show();
            if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.72f));
        }

        private void dismissBottomSheet() {
            if (activeBottomSheet != null && activeBottomSheet.isShowing()) activeBottomSheet.dismiss();
            activeBottomSheet = null;
        }

        private void showDefinitions() {
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(18), dp(4), dp(18), dp(18));
            addDefinition(content, "Mapped unit",
                    "A body of rock or sediment represented as a distinct unit on a geologic map. It may have a name, map label, or both.");
            addDefinition(content, "Rock type (lithology)",
                    "Lithology describes the type of rock or sediment in a mapped unit, such as granite, sandstone, limestone, or alluvium.");
            addDefinition(content, "Geologic age",
                    "The time interval when mapped rock or sediment formed or was deposited, such as Jurassic, Cretaceous, or Precambrian.");
            addDefinition(content, "Mapped-text search",
                    "A broader search through names, labels, descriptions, and other searchable mapped-geology text. It can return more results than a specific rock-type or age search.");
            TextView note = helper("Geologic maps represent interpretations at the source-map scale. GPS precision does not make the mapped geology equally precise.");
            note.setTextIsSelectable(true);
            note.setPadding(0, dp(10), 0, dp(4));
            content.addView(note);
            showBottomSheet("Geology search terms", content);
        }

        private void addDefinition(LinearLayout parent, String term, String definition) {
            TextView h = text(term, 15.5f, 0xff202020, true);
            h.setTextIsSelectable(true);
            h.setPadding(0, dp(12), 0, dp(2));
            parent.addView(h);
            TextView d = helper(definition);
            d.setTextIsSelectable(true);
            d.setPadding(0, 0, 0, dp(3));
            parent.addView(d);
        }

        private void showHelp() {
            String message = "Search uses the installed Colorado geology database and works offline. "
                    + "RockMap distinguishes mapped-unit text, rock types, geologic ages, and broader mapped-text searches.\n\n"
                    + "Use Geology search terms for definitions. Search results preserve source-map information separately from standardized CNGM search relationships.";
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Search Geology help")
                    .setMessage(message)
                    .setPositiveButton("Start Guided Tour", null)
                    .setNegativeButton("Close", null)
                    .create();
            dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (GuidedTourState.isActive(activity) || FieldTourState.active(activity)) {
                    new AlertDialog.Builder(activity)
                            .setTitle("Another guided tour is active")
                            .setMessage("Finish or exit the current RockMap guided tour before starting the Search Geology tour.")
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                dialog.dismiss();
                CngmGeologyTourState.start(activity);
                tourStep = 1;
                TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_START", "source=Search Geology help");
                showTourStep();
            }));
            dialog.show();
        }

        private void showTourStep() {
            if (!CngmGeologyTourState.isActive(activity)) {
                tourStep = 0;
                return;
            }
            if (GuidedTourState.isActive(activity) || FieldTourState.active(activity)) {
                int previous = CngmGeologyTourState.step(activity);
                CngmGeologyTourState.exit(activity);
                tourStep = 0;
                TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_SUPPRESSED",
                        "screen=search step=" + previous + " reason=another-tour-active");
                return;
            }
            if (tourStep <= 0) tourStep = CngmGeologyTourState.step(activity);
            final int step = tourStep;
            CngmGeologyTourState.setStep(activity, step);
            TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_STEP",
                    "screen=search step=" + step
                            + " introAttached=" + (geologyIntroTarget != null && geologyIntroTarget.isAttachedToWindow())
                            + " examplesAttached=" + (examplesTarget != null && examplesTarget.isAttachedToWindow()));
            if (step == 1) {
                if (geologyIntroTarget == null || !geologyIntroTarget.isAttachedToWindow()) {
                    TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_TARGET_WAIT",
                            "screen=search step=1 target=Search Geology intro");
                    activity.getWindow().getDecorView().postDelayed(this::showTourStep, 60L);
                    return;
                }
                GuidedTourCoach.show(activity, 1, 9,
                        "Geology",
                        "Search Colorado's mapped geology by geologic unit, rock type, or geologic age.",
                        "Review the Search Geology screen.", geologyIntroTarget,
                        null,
                        "Next", () -> { tourStep = 2; showTourStep(); },
                        this::skipTourStep, this::exitSearchTour);
            } else if (step == 2) {
                GuidedTourCoach.show(activity, 2, 9,
                        "Search geology",
                        "Enter a geologic term here, such as a mapped unit, rock type, or geologic age.",
                        "Use the search field when you know a term.", quick,
                        () -> { tourStep = 1; showTourStep(); },
                        "Next", () -> {
                            hideKeyboard(quick);
                            quick.clearFocus();
                            renderSuggestions("");
                            tourStep = 3;
                            activity.getWindow().getDecorView().post(this::showTourStep);
                        },
                        this::skipTourStep, this::exitSearchTour);
            } else if (step == 3) {
                if (examplesTarget == null || !examplesTarget.isAttachedToWindow()
                        || examplesTarget.getWidth() <= 0 || examplesTarget.getHeight() <= 0) {
                    hideKeyboard(quick);
                    quick.clearFocus();
                    renderSuggestions("");
                    TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_TARGET_WAIT",
                            "screen=search step=3 target=Quick examples group");
                    activity.getWindow().getDecorView().postDelayed(this::showTourStep, 60L);
                    return;
                }
                TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_STEP3_SHOWN",
                        "target=Quick examples group action=run-any-search");
                GuidedTourCoach.show(activity, 3, 9,
                        "Quick examples",
                        "Examples are shortcuts for common geology searches. Choose any example, or enter your own search.",
                        "Run any geology search.", examplesTarget,
                        () -> { tourStep = 2; showTourStep(); },
                        null, null,
                        this::skipTourStep, this::exitSearchTour);
            } else if (step == 6) {
                if (refineBox.getVisibility() != View.VISIBLE) toggleRefine();
                GuidedTourCoach.show(activity, 6, 9,
                        "Narrow the search",
                        "Use these fields to specify a mapped unit, rock type, or geologic age. A term can appear in different parts of the geologic data, and filters can be combined.",
                        "Review the mapped unit, rock type, and geologic age fields.", refineBox,
                        () -> {
                            if (callback.hasTourUnitDetails()) {
                                CngmGeologyTourState.setStep(activity, 5);
                                TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_BACK",
                                        "screen=search from=6 to=5 destination=unit-details");
                                callback.onReturnToTourUnitDetails();
                            } else {
                                tourStep = 3;
                                CngmGeologyTourState.setStep(activity, 3);
                                hideKeyboard(quick);
                                quick.clearFocus();
                                renderSuggestions("");
                                showTourStep();
                            }
                        },
                        "Next", () -> { tourStep = 7; showTourStep(); },
                        () -> { tourStep = 7; showTourStep(); }, this::exitSearchTour);
            } else if (step == 7) {
                if (callback.hasTourResults()) {
                    GuidedTourCoach.show(activity, 7, 9,
                            "Search area",
                            "Searches normally cover all of Colorado. Use Visible map area when you only want results from the part of the map currently on screen.",
                            "Review the geographic scope options.", searchAreaTarget,
                            () -> { tourStep = 6; showTourStep(); },
                            "Next", this::returnToResultsForLearnMore,
                            this::returnToResultsForLearnMore, this::exitSearchTour);
                } else {
                    GuidedTourCoach.show(activity, 7, 9,
                            "Search area",
                            "Searches normally cover all of Colorado. Use Visible map area when you only want results from the part of the map currently on screen.",
                            "Run a geology search to continue to the result-based lessons.", searchAreaTarget,
                            () -> { tourStep = 6; showTourStep(); },
                            null, null,
                            this::finishSearchTourWithoutResults, this::exitSearchTour);
                }
            }
        }

        private void returnToResultsForLearnMore() {
            if (!callback.hasTourResults()) {
                TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_TARGET_FAIL",
                        "screen=search step=7 target=reusable-results missing");
                return;
            }
            CngmGeologyTourState.setStep(activity, 8);
            CngmGeologyTourState.setPhase(activity, CngmGeologyTourState.PHASE_DEFAULT);
            TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_STATE_COMMITTED",
                    "from=7 to=8 destination=existing-results");
            GuidedTourCoach.clear(activity);
            callback.onReturnToTourResults();
        }

        private void finishSearchTourWithoutResults() {
            CngmGeologyTourState.finish(activity);
            tourStep = 0;
            TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_FINISH",
                    "screen=search reason=result-dependent-lessons-skipped");
            GuidedTourCoach.clear(activity);
        }

        void resumeTourAt(int step) {
            if (!CngmGeologyTourState.isActive(activity)) return;
            tourStep = step;
            activity.getWindow().getDecorView().post(this::showTourStep);
        }

        private void skipTourStep() {
            int from = tourStep;
            if (tourStep == 1) tourStep = 2;
            else if (tourStep == 2) {
                hideKeyboard(quick);
                quick.clearFocus();
                renderSuggestions("");
                tourStep = 3;
            } else if (tourStep == 3) {
                if (refineBox.getVisibility() != View.VISIBLE) toggleRefine();
                tourStep = 6;
            } else if (tourStep == 6) tourStep = 7;
            else if (tourStep == 7) {
                if (callback.hasTourResults()) returnToResultsForLearnMore();
                else finishSearchTourWithoutResults();
                return;
            } else {
                exitSearchTour();
                return;
            }
            CngmGeologyTourState.setStep(activity, tourStep);
            CngmGeologyTourState.setPhase(activity, CngmGeologyTourState.PHASE_DEFAULT);
            TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_SKIP",
                    "screen=search from=" + from + " to=" + tourStep);
            activity.getWindow().getDecorView().post(this::showTourStep);
        }

        private void exitSearchTour() {
            int previous = CngmGeologyTourState.step(activity);
            tourStep = 0;
            CngmGeologyTourState.exit(activity);
            TourDebugLog.mainTourAction(activity, "GEOLOGY_TOUR_EXIT", "screen=search step=" + previous);
            GuidedTourCoach.clear(activity);
        }

        private void leave() {
            saveState();
            dismissBottomSheet();
            exitSearchTour();
            hideKeyboard(quick);
            callback.onBack();
        }

        private boolean isSupportedExact(String value, Kind kind) {
            File file = geology.getDatabaseFile();
            if (file == null || value == null || value.trim().isEmpty()) return false;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
                if (kind == Kind.AGE) {
                    return !"literal-only".equals(CngmAuthoritativeSearch.resolveAge(db, value).method);
                }
                if (kind == Kind.ROCK_TYPE) {
                    return !"literal-only".equals(CngmAuthoritativeSearch.resolveLithology(db, value).method);
                }
                return false;
            } catch (RuntimeException ex) {
                return false;
            }
        }

        private boolean hasUnitMatch(String value) {
            File file = geology.getDatabaseFile();
            if (file == null || value == null || value.trim().isEmpty()) return false;
            String like = "%" + value.trim() + "%";
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor c = db.rawQuery(
                         "SELECT 1 FROM units WHERE unit_name LIKE ? COLLATE NOCASE "
                                 + "OR orig_label LIKE ? COLLATE NOCASE OR sgmc_label LIKE ? COLLATE NOCASE "
                                 + "OR unit_link LIKE ? COLLATE NOCASE LIMIT 1",
                         new String[]{like, like, like, like})) {
                return c.moveToFirst();
            } catch (RuntimeException ex) {
                return false;
            }
        }

        private List<String> vocabularySuggestions(String prefix, Kind kind, int limit) {
            LinkedHashSet<String> out = new LinkedHashSet<>();
            File file = geology.getDatabaseFile();
            if (file == null) return new ArrayList<>();
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY)) {
                if (kind == Kind.AGE) {
                    addTerms(db, "age_concepts", prefix, out, limit);
                    addCrosswalkTerms(db, prefix, out, limit);
                } else {
                    addTerms(db, "lithology_concepts", prefix, out, limit);
                    addTerms(db, "geomaterial_concepts", prefix, out, limit);
                }
            } catch (RuntimeException ignored) {}
            return new ArrayList<>(out);
        }

        private List<String> unitSuggestions(String prefix, int limit) {
            ArrayList<String> out = new ArrayList<>();
            File file = geology.getDatabaseFile();
            if (file == null) return out;
            String like = "%" + prefix.trim() + "%";
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor c = db.rawQuery(
                         "SELECT DISTINCT unit_name FROM units WHERE unit_name LIKE ? COLLATE NOCASE "
                                 + "AND TRIM(unit_name)<>'' ORDER BY unit_name COLLATE NOCASE LIMIT ?",
                         new String[]{like, Integer.toString(Math.max(1, Math.min(limit, 20))) })) {
                while (c.moveToNext()) {
                    String v = clean(c.getString(0));
                    if (!v.isEmpty()) out.add(v);
                }
            } catch (RuntimeException ignored) {}
            return out;
        }

        private List<String> popularUnitExamples(int limit) {
            ArrayList<String> out = new ArrayList<>();
            File file = geology.getDatabaseFile();
            if (file == null) return out;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                 Cursor c = db.rawQuery(
                         "SELECT unit_name,COUNT(*) n FROM units WHERE TRIM(unit_name)<>'' "
                                 + "GROUP BY unit_name ORDER BY n DESC,unit_name COLLATE NOCASE LIMIT ?",
                         new String[]{Integer.toString(Math.max(1, Math.min(limit, 12))) })) {
                while (c.moveToNext()) {
                    String v = clean(c.getString(0));
                    if (!v.isEmpty() && !v.equalsIgnoreCase("Morrison")) out.add(v);
                }
            } catch (RuntimeException ignored) {}
            return out;
        }

        private void addTermOptions(SQLiteDatabase db, String table, String prefix, String subtitle,
                                    Kind kind, List<Option> out, int limit) {
            if (out.size() >= limit) return;
            String like = "%" + prefix.trim() + "%";
            try (Cursor c = db.rawQuery(
                    "SELECT term FROM " + table + " WHERE term LIKE ? COLLATE NOCASE "
                            + "ORDER BY CASE WHEN term LIKE ? COLLATE NOCASE THEN 0 ELSE 1 END,"
                            + "term COLLATE NOCASE LIMIT 20",
                    new String[]{like, prefix.trim() + "%"})) {
                while (c.moveToNext() && out.size() < limit) {
                    String value = clean(c.getString(0));
                    if (!value.isEmpty()) out.add(new Option(value, subtitle, kind, value));
                }
            }
        }

        private void addCrosswalkOptions(SQLiteDatabase db, String prefix, List<Option> out, int limit) {
            String like = "%" + prefix.trim() + "%";
            try (Cursor c = db.rawQuery(
                    "SELECT query_term,min_term,max_term FROM age_query_crosswalk "
                            + "WHERE query_term LIKE ? COLLATE NOCASE ORDER BY query_term COLLATE NOCASE",
                    new String[]{like})) {
                while (c.moveToNext() && out.size() < limit) {
                    String term = clean(c.getString(0));
                    String min = clean(c.getString(1));
                    String max = clean(c.getString(2));
                    String subtitle = min.equalsIgnoreCase(max)
                            ? "Historical geologic age term · USGS maps to " + min
                            : "Historical geologic age term · USGS maps to " + min + " through " + max;
                    out.add(new Option(term, subtitle, Kind.AGE, term));
                }
            }
        }

        private void addUnitOptions(SQLiteDatabase db, String prefix, List<Option> out, int limit) {
            String like = "%" + prefix.trim() + "%";
            try (Cursor c = db.rawQuery(
                    "SELECT DISTINCT unit_name FROM units WHERE unit_name LIKE ? COLLATE NOCASE "
                            + "AND TRIM(unit_name)<>'' ORDER BY unit_name COLLATE NOCASE LIMIT 20",
                    new String[]{like})) {
                while (c.moveToNext() && out.size() < limit) {
                    String value = clean(c.getString(0));
                    if (!value.isEmpty()) out.add(new Option(value, "Mapped unit or name", Kind.UNIT, value));
                }
            }
        }

        private void addTerms(SQLiteDatabase db, String table, String prefix,
                              LinkedHashSet<String> out, int limit) {
            if (out.size() >= limit) return;
            String like = "%" + prefix.trim() + "%";
            try (Cursor c = db.rawQuery(
                    "SELECT term FROM " + table + " WHERE term LIKE ? COLLATE NOCASE "
                            + "ORDER BY term COLLATE NOCASE LIMIT 30", new String[]{like})) {
                while (c.moveToNext() && out.size() < limit) {
                    String value = clean(c.getString(0));
                    if (!value.isEmpty()) out.add(value);
                }
            }
        }

        private void addCrosswalkTerms(SQLiteDatabase db, String prefix,
                                       LinkedHashSet<String> out, int limit) {
            if (out.size() >= limit) return;
            String like = "%" + prefix.trim() + "%";
            try (Cursor c = db.rawQuery(
                    "SELECT query_term FROM age_query_crosswalk WHERE query_term LIKE ? COLLATE NOCASE "
                            + "ORDER BY query_term COLLATE NOCASE LIMIT 20", new String[]{like})) {
                while (c.moveToNext() && out.size() < limit) {
                    String value = clean(c.getString(0));
                    if (!value.isEmpty()) out.add(value);
                }
            }
        }

        private String exactUnitName(SQLiteDatabase db, String query) {
            try (Cursor c = db.rawQuery(
                    "SELECT unit_name FROM units WHERE unit_name=? COLLATE NOCASE "
                            + "OR orig_label=? COLLATE NOCASE OR sgmc_label=? COLLATE NOCASE "
                            + "ORDER BY unit_name COLLATE NOCASE LIMIT 1",
                    new String[]{query, query, query})) {
                return c.moveToFirst() ? clean(c.getString(0)) : "";
            }
        }

        private List<Option> dedupeOptions(List<Option> raw, int limit) {
            ArrayList<Option> out = new ArrayList<>();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (Option option : raw) {
                String key = option.kind.name() + "|" + option.label.toLowerCase(Locale.US);
                if (!seen.add(key)) continue;
                out.add(option);
                if (out.size() >= limit) break;
            }
            return out;
        }

        private String displayConcept(CngmAuthoritativeSearch.Resolution resolution, String fallback) {
            if (resolution != null && resolution.method != null && resolution.method.startsWith("dr1210-")) {
                return fallback;
            }
            if (resolution != null && !resolution.concepts.isEmpty()) {
                String raw = resolution.concepts.get(0);
                int colon = raw.lastIndexOf(':');
                if (colon >= 0 && colon + 1 < raw.length()) return raw.substring(colon + 1);
            }
            return fallback;
        }

        private String ageSubtitle(CngmAuthoritativeSearch.Resolution age) {
            return age != null && age.method != null && age.method.startsWith("dr1210-")
                    ? "Historical geologic age term" : "Geologic age";
        }

        private String interpretationLabel(Option option) {
            if (option.kind == Kind.ROCK_TYPE) return "Rock type · " + option.query;
            if (option.kind == Kind.AGE) return "Geologic age · " + option.query;
            if (option.kind == Kind.UNIT) return "Mapped unit · " + option.query;
            return "Mapped text · “" + option.query + "”";
        }

        private void updateSearchButton() {
            boolean any = !quick.getText().toString().trim().isEmpty()
                    || !unitField.getText().toString().trim().isEmpty()
                    || !lithologyField.getText().toString().trim().isEmpty()
                    || !ageField.getText().toString().trim().isEmpty();
            searchButton.setEnabled(any);
        }

        private AutoCompleteTextView autocompleteField(String hint) {
            AutoCompleteTextView field = new AutoCompleteTextView(activity);
            field.setSingleLine(true);
            field.setTextSize(15f);
            field.setHint(hint);
            field.setPadding(dp(12), dp(8), dp(12), dp(8));
            field.setMinHeight(dp(52));
            return field;
        }

        private void addRefineField(LinearLayout parent, String label,
                                    AutoCompleteTextView field, String hint) {
            TextView h = fieldLabel(label);
            h.setPadding(0, dp(10), 0, dp(2));
            parent.addView(h);
            parent.addView(field, matchWrap());
            TextView helper = helper(hint);
            helper.setPadding(dp(2), dp(1), 0, dp(3));
            parent.addView(helper);
        }

        private RadioButton radio(String label, String hint) {
            RadioButton button = new RadioButton(activity);
            button.setText(label + "\n" + hint);
            button.setTextSize(14.5f);
            button.setTextColor(0xff303030);
            button.setGravity(Gravity.CENTER_VERTICAL);
            button.setMinHeight(dp(60));
            button.setPadding(dp(4), dp(3), dp(4), dp(3));
            button.setContentDescription(label + ". " + hint);
            return button;
        }

        private Button primaryButton(String label) {
            Button button = new Button(activity);
            button.setText(label);
            button.setAllCaps(false);
            button.setTextSize(15f);
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setMinHeight(dp(52));
            return button;
        }

        private Button disclosureButton(String label, boolean expanded) {
            Button button = textButton(label + "   " + (expanded ? "⌄" : "›"));
            button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            button.setTextSize(15f);
            button.setMinHeight(dp(52));
            return button;
        }

        private Button textButton(String label) {
            Button button = new Button(activity);
            button.setText(label);
            button.setAllCaps(false);
            button.setTextSize(14f);
            button.setMinHeight(dp(48));
            button.setMinimumHeight(dp(48));
            button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            return button;
        }

        private TextView fieldLabel(String value) {
            TextView view = text(value, 14f, 0xff303030, true);
            view.setPadding(0, dp(5), 0, dp(3));
            return view;
        }

        private TextView microHeading(String value) {
            TextView view = text(value, 12f, 0xff666666, true);
            view.setPadding(dp(12), dp(10), dp(12), dp(4));
            return view;
        }

        private TextView helper(String value) {
            return text(value, 13f, 0xff555555, false);
        }

        private TextView text(String value, float sp, int color, boolean bold) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(sp);
            view.setTextColor(color);
            if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
            return view;
        }

        private LinearLayout.LayoutParams matchWrap() {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        private void applySelectable(View view) {
            android.util.TypedValue selectable = new android.util.TypedValue();
            if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,
                    selectable, true) && selectable.resourceId != 0) {
                view.setBackgroundResource(selectable.resourceId);
            }
        }

        private int dp(int value) {
            return Math.round(value * activity.getResources().getDisplayMetrics().density);
        }

        private void hideKeyboard(View view) {
            if (view == null) return;
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }

        private void showKeyboard(View view) {
            if (view == null) return;
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private abstract static class SimpleWatcher implements android.text.TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            changed(s == null ? "" : s.toString());
        }
        @Override public void afterTextChanged(android.text.Editable s) {}
        public abstract void changed(String value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
'''
    SEARCH_UI.write_text(source, encoding="utf-8")
    generated = SEARCH_UI.read_text(encoding="utf-8")
    required = [
        "Search Colorado's mapped geology by rock type, geologic age, or mapped unit.",
        "Searching as: ",
        "Geology search terms",
        "Browse geology examples",
        "Mapped-text search",
        "showLearningSearches",
        "mappedUnitSearchTerm",
        "searchQuery",
        "https://www.google.com/search?q=",
        "Clear geology search",
        "SAVED_STATES",
    ]
    for marker in required:
        if marker not in generated:
            raise RuntimeError("CNGM search UI helper missing marker: " + marker)
    if "Lithology filter (optional)" in generated or "Age filter (optional)" in generated:
        raise RuntimeError("CNGM search UI reintroduced optional-field labels.")
    for provider_copy in ("Search Google", "Google results", "Opens Google", "external Google search"):
        if provider_copy in generated:
            raise RuntimeError("CNGM search UI exposed provider-specific Google wording: " + provider_copy)
    if "quotedSearchTerm" in generated:
        raise RuntimeError("CNGM search UI still contains exact-phrase external-search quoting.")
    if '"\\\"" + clean(value)' in generated:
        raise RuntimeError("CNGM search UI still constructs quoted external-search terms.")
    print("CNGM Stage 2B search UX helper: generated")



def write_geology_tour_state() -> None:
    source = r'''package com.rockmap.app.research;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent runner-only state for the dedicated Search Geology guided tour. */
public final class CngmGeologyTourState {
    private static final String PREFS = "rockmap_cngm_geology_tour";
    private static final String KEY_ACTIVE = "active";
    private static final String KEY_STEP = "step";
    private static final String KEY_PHASE = "phase";

    public static final String PHASE_DEFAULT = "default";
    public static final String PHASE_AWAIT_RESULTS = "await_results";
    public static final String PHASE_AWAIT_LEARN_MORE_RESULTS = "await_learn_more_results";
    public static final String PHASE_AWAIT_MAP = "await_map";
    public static final String PHASE_AWAIT_POLYGON = "await_polygon";

    private CngmGeologyTourState() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void start(Context context) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, true).putInt(KEY_STEP, 1)
                .putString(KEY_PHASE, PHASE_DEFAULT).apply();
    }

    public static boolean isActive(Context context) {
        return context != null && prefs(context).getBoolean(KEY_ACTIVE, false);
    }

    public static int step(Context context) {
        return context == null ? 0 : Math.max(0, prefs(context).getInt(KEY_STEP, 0));
    }

    public static String phase(Context context) {
        return context == null ? PHASE_DEFAULT : prefs(context).getString(KEY_PHASE, PHASE_DEFAULT);
    }

    public static void setStep(Context context, int step) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ACTIVE, true).putInt(KEY_STEP, Math.max(1, step)).apply();
    }

    public static void setPhase(Context context, String phase) {
        if (context == null) return;
        prefs(context).edit().putString(KEY_PHASE,
                phase == null || phase.trim().isEmpty() ? PHASE_DEFAULT : phase.trim()).apply();
    }

    public static void exit(Context context) {
        if (context == null) return;
        prefs(context).edit().putBoolean(KEY_ACTIVE, false).putInt(KEY_STEP, 0)
                .putString(KEY_PHASE, PHASE_DEFAULT).apply();
    }

    public static void finish(Context context) {
        exit(context);
    }
}
'''
    GEOLOGY_TOUR_STATE.write_text(source, encoding="utf-8")
    print("CNGM geology tour state generated")

def inject_sources() -> None:
    replace_once(
        COACH,
        '''import android.app.AlertDialog;
''',
        '''import android.app.AlertDialog;
import android.app.Dialog;
''',
        "import android.app.Dialog;",
        "allow guided-tour coach on custom Dialog surfaces",
    )
    replace_once(
        COACH,
        '''    public static FrameLayout prepareDialogHost(Activity activity, AlertDialog dialog) {
        if (activity == null || dialog == null || dialog.getWindow() == null) return null;
        return new DialogCoachHost(activity, dialog);
    }
''',
        '''    public static FrameLayout prepareDialogHost(Activity activity, AlertDialog dialog) {
        return prepareDialogHost(activity, (Dialog) dialog);
    }

    public static FrameLayout prepareDialogHost(Activity activity, Dialog dialog) {
        if (activity == null || dialog == null || dialog.getWindow() == null) return null;
        return new DialogCoachHost(activity, dialog);
    }
''',
        "prepareDialogHost(Activity activity, Dialog dialog)",
        "support tour coach over custom geology dialogs",
    )
    replace_once(
        COACH,
        '''        private final AlertDialog sourceDialog;
''',
        '''        private final Dialog sourceDialog;
''',
        "private final Dialog sourceDialog;",
        "generalize dialog coach host source",
    )
    replace_once(
        COACH,
        '''        DialogCoachHost(Activity activity, AlertDialog sourceDialog) {
''',
        '''        DialogCoachHost(Activity activity, Dialog sourceDialog) {
''',
        "DialogCoachHost(Activity activity, Dialog sourceDialog)",
        "generalize dialog coach host constructor",
    )

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
        '''    public static final class Filter {
        public final String text;
        public final String lithology;
        public final String age;
        public Filter(String text, String lithology, String age) {
            this.text = normalize(text);
            this.lithology = normalize(lithology);
            this.age = normalize(age);
        }
    }
''',
        '''    public static final class Filter {
        public final String text;
        public final String unit;
        public final String lithology;
        public final String age;

        public Filter(String text, String lithology, String age) {
            this(text, "", lithology, age);
        }

        public Filter(String text, String unit, String lithology, String age) {
            this.text = normalize(text);
            this.unit = normalize(unit);
            this.lithology = normalize(lithology);
            this.age = normalize(age);
        }
    }
''',
        "public final String unit;",
        "add explicit mapped-unit search field",
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
            if (!actual.unit.isEmpty()) {
                String like = "%" + actual.unit + "%";
                clauses.add("(unit_name LIKE ? OR orig_label LIKE ? OR sgmc_label LIKE ? OR unit_link LIKE ?)");
                args.add(like);
                args.add(like);
                args.add(like);
                args.add(like);
                TourDebugLog.mapDiagnostic("CNGM_SEARCH_RESOLVE",
                        "field=unit query=" + actual.unit
                                + " method=source-map-unit-fields authority="
                                + CngmAuthoritativeSearch.AUTHORITY_DOI);
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
        '''    private void showSearch() {
        LinearLayout box = page();
        EditText text = input("Unit, label, rock, or geology term", "");
        EditText lith = input("Lithology filter (optional)", "");
        EditText age = input("Age filter (optional)", "");
        CheckBox visible = new CheckBox(this);
        visible.setText("Search Visible Area Only");
        visible.setChecked(visibleBounds != null);
        visible.setEnabled(visibleBounds != null);
        visible.setMinHeight(dp(48));
        box.addView(text);
        box.addView(lith);
        box.addView(age);
        box.addView(visible);

        TextView suggestions = help(suggestionText(""));
        box.addView(suggestions);
        text.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                suggestions.setText(suggestionText(s == null ? "" : s.toString()));
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Search Geology")
                .setView(scroll(box))
                .setPositiveButton("Search", null)
                .setNegativeButton("Cancel", (d, w) -> showHub())
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            GeologyRepository.Filter filter = new GeologyRepository.Filter(
                    text.getText().toString(), lith.getText().toString(), age.getText().toString());
            if (filter.text.isEmpty() && filter.lithology.isEmpty() && filter.age.isEmpty()) {
                text.setError("Enter a search or filter term.");
                return;
            }
            GeologyRepository.Bounds bounds = visible.isChecked() ? visibleBounds : null;
            dialog.dismiss();
            runAsync("Searching geology…", () -> geology.search(filter, bounds, 0),
                    results -> showResults(results,
                            "Geology Search" + (filter.text.isEmpty() ? "" : ": " + text.getText().toString().trim()),
                            bounds, queryBoundsContext(bounds, "Visible Area search")));
        }));
        dialog.show();
        text.requestFocus();
    }
''',
        '''    private void showSearch() {
        CngmSearchUi.show(this, geology, visibleBounds, new CngmSearchUi.Callback() {
            @Override public void onSearch(GeologyRepository.Filter filter,
                                           GeologyRepository.Bounds bounds,
                                           String resultTitle,
                                           String resultSummary) {
                String context = bounds == null ? "" : queryBoundsContext(bounds, "Visible Area search");
                runAsync("Searching geology…", () -> geology.search(filter, bounds, 0),
                        results -> showResults(results, resultTitle, bounds, context, resultSummary));
            }

            @Override public boolean hasTourResults() {
                return currentResults != null && !currentResults.isEmpty();
            }

            @Override public void onReturnToTourResults() {
                TourDebugLog.mainTourAction(ResearchActivity.this, "GEOLOGY_TOUR_TRANSITION",
                        "destination=existing-results count="
                                + (currentResults == null ? 0 : currentResults.size()));
                showResults(currentResults, currentResultTitle, currentResultBounds,
                        currentQueryContextJson, currentResultSearchSummary);
            }

            @Override public boolean hasTourUnitDetails() {
                return cngmTourSelectedGroup != null;
            }

            @Override public void onReturnToTourUnitDetails() {
                if (cngmTourSelectedGroup == null) {
                    CngmGeologyTourState.setStep(ResearchActivity.this, 4);
                    onReturnToTourResults();
                    return;
                }
                showUnitGroup(cngmTourSelectedGroup, cngmTourSelectedResultTitle);
            }

            @Override public void onBack() {
                showHub();
            }
        });
    }
''',
        "CngmSearchUi.show(this, geology, visibleBounds",
        "replace Search Geology with approved progressive-disclosure UI",
    )

    replace_once(
        RESEARCH,
        '''    private String currentResultTitle = "Analysis";
    private GeologyRepository.Bounds currentResultBounds;
''',
        '''    private String currentResultTitle = "Analysis";
    private GeologyRepository.Bounds currentResultBounds;
    private String currentResultSearchSummary = "";
''',
        'private String currentResultSearchSummary = "";',
        "preserve Search Geology interpretation on result navigation",
    )

    replace_once(
        RESEARCH,
        '''    private void showResults(List<GeologyUnit> results, String resultTitle,
                             GeologyRepository.Bounds queryBounds, String queryContextJson) {
        GuidedTourCoach.clear(this);
''',
        '''    private void showResults(List<GeologyUnit> results, String resultTitle,
                             GeologyRepository.Bounds queryBounds, String queryContextJson) {
        showResults(results, resultTitle, queryBounds, queryContextJson, "");
    }

    private void showResults(List<GeologyUnit> results, String resultTitle,
                             GeologyRepository.Bounds queryBounds, String queryContextJson,
                             String resultSearchSummary) {
        GuidedTourCoach.clear(this);
        currentResultSearchSummary = resultSearchSummary == null ? "" : resultSearchSummary.trim();
''',
        "String resultSearchSummary",
        "add Search Geology result interpretation summary",
    )

    replace_once(
        RESEARCH,
        '''        List<GeologyUnit> safe = results == null ? new ArrayList<>() : results;
        currentResults = new ArrayList<>(safe);
        currentResultTitle = resultTitle;
''',
        '''        List<GeologyUnit> safe = results == null ? new ArrayList<>() : results;
        currentResults = new ArrayList<>(safe);
        handleCngmGeologyTourSearchResults(safe);
        currentResultTitle = resultTitle;
''',
        "handleCngmGeologyTourSearchResults(safe);",
        "advance geology tour only after real search results exist",
    )

    replace_once(
        RESEARCH,
        '''        top.addView(title(resultTitle));
        top.addView(help(compactSummary(safe, groups)));
''',
        '''        top.addView(title(resultTitle));
        if (!currentResultSearchSummary.isEmpty()) {
            TextView searchMeaning = help(currentResultSearchSummary);
            searchMeaning.setTextIsSelectable(true);
            top.addView(searchMeaning);
            Button editSearch = button("Edit Search");
            cngmTourEditSearchTarget = editSearch;
            editSearch.setOnClickListener(v -> {
                if (CngmGeologyTourState.isActive(this)) {
                    int tourStep = CngmGeologyTourState.step(this);
                    if (tourStep == 3) {
                        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_RETRY_SEARCH",
                                "screen=results step=3 action=Edit Search");
                    } else if (tourStep == 4) {
                        CngmGeologyTourState.setStep(this, 3);
                        CngmGeologyTourState.setPhase(this, CngmGeologyTourState.PHASE_DEFAULT);
                        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_BACK",
                                "screen=results from=4 to=3 action=Edit Search");
                    } else if (tourStep == 8) {
                        CngmGeologyTourState.setStep(this, 7);
                        CngmGeologyTourState.setPhase(this, CngmGeologyTourState.PHASE_DEFAULT);
                        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_BACK",
                                "screen=results from=8 to=7 action=Edit Search");
                    }
                }
                showSearch();
            });
            top.addView(editSearch);
        }
        top.addView(help(compactSummary(safe, groups)));
''',
        "TextView searchMeaning = help(currentResultSearchSummary);",
        "show explicit search meaning on results",
    )

    replace_once(
        RESEARCH,
        '''            for (UnitGroup group : groups) {
                unitList.addView(action(group.name,
                        group.detailLine(),
                        v -> showUnitGroup(group, resultTitle)));
            }
''',
        '''            for (UnitGroup group : groups) {
                final UnitGroup onlineGroup = group;
                LinearLayout unitBlock = new LinearLayout(this);
                unitBlock.setOrientation(LinearLayout.VERTICAL);
                View unitDetails = action(group.name,
                        group.detailLine(),
                        v -> openCngmTourAwareUnitGroup(group, resultTitle));
                unitBlock.addView(unitDetails);
                Button searchOnline = button("Search online  ↗");
                searchOnline.setTextSize(13f);
                searchOnline.setContentDescription("Search online for information about " + group.name + ". Opens an external browser.");
                searchOnline.setOnClickListener(v -> {
                    Runnable continueTour = CngmGeologyTourState.isActive(this)
                            && CngmGeologyTourState.step(this) == 8
                            ? this::maybeShowCngmGeologyTourCoach : null;
                    CngmSearchUi.showLearningSearches(
                            this, onlineGroup.name, onlineGroup.age, onlineGroup.lithology, continueTour);
                });
                if (cngmTourFirstResultTarget == null) cngmTourFirstResultTarget = unitDetails;
                if (cngmTourFirstOnlineTarget == null) cngmTourFirstOnlineTarget = searchOnline;
                unitBlock.addView(searchOnline);
                unitList.addView(unitBlock);
            }
''',
        "final UnitGroup onlineGroup = group;",
        "add Search online to each geologic-unit result",
    )

    replace_once(
        RESEARCH,
        '''        root.addView(title(group.name));
        root.addView(help(group.detailLine()));
''',
        '''        root.addView(title(group.name));
        TextView unitSummary = help("Mapped unit: " + group.name + "\\n" + group.detailLine());
        unitSummary.setTextIsSelectable(true);
        root.addView(unitSummary);
        Button searchOnline = button("Search online  ↗");
        searchOnline.setContentDescription("Search online for information about this mapped geologic unit, its age, or rock type. Opens an external browser.");
        searchOnline.setOnClickListener(v -> CngmSearchUi.showLearningSearches(
                this, group.name, group.age, group.lithology));
        root.addView(searchOnline);
''',
        'TextView unitSummary = help("Mapped unit: " + group.name',
        "make selected geology-unit terms copyable",
    )

    replace_once(
        RESEARCH,
        '''        root.addView(nav("Back to Results", v -> showResults(
                currentResults, currentResultTitle, currentResultBounds, currentQueryContextJson)));
        setContentView(scroll(root));
''',
        '''        root.addView(nav("Back to Results", v -> {
                if (CngmGeologyTourState.isActive(this)
                        && CngmGeologyTourState.step(this) == 5) {
                    CngmGeologyTourState.setStep(this, 4);
                    CngmGeologyTourState.setPhase(this, CngmGeologyTourState.PHASE_DEFAULT);
                    TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_BACK",
                            "screen=unit-details from=5 to=4 action=Back to Results");
                }
                showResults(currentResults, currentResultTitle, currentResultBounds,
                        currentQueryContextJson, currentResultSearchSummary);
            }));
        setContentView(scroll(root));
        maybeShowCngmGeologyUnitDetailsTourCoach(group, resultTitle, unitSummary);
''',
        "screen=unit-details from=5 to=4 action=Back to Results",
        "keep search meaning and tour state when returning from unit details",
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
        RESEARCH,
        '''        new AlertDialog.Builder(this)
                .setTitle("Source & Technical Details")
                .setMessage(text.toString())
                .setPositiveButton("Close", null)
                .show();
''',
        '''        TextView technicalBody = help(text.toString());
        technicalBody.setTextIsSelectable(true);
        technicalBody.setPadding(dp(20), dp(8), dp(20), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("Source & Technical Details")
                .setView(scroll(technicalBody))
                .setNeutralButton("Search online  ↗", (d, w) -> CngmSearchUi.showLearningSearches(
                        this, u.displayName(), u.compactAgeLabel(), u.compactLithologyLabel()))
                .setPositiveButton("Close", null)
                .show();
''',
        "TextView technicalBody = help(text.toString());",
        "make geology source details copyable",
    )

    replace_once(
        MAIN,
        '''import com.rockmap.app.research.GeologyDataManager;
''',
        '''import com.rockmap.app.research.CngmGeologyTourState;
import com.rockmap.app.research.CngmSearchUi;
import com.rockmap.app.research.GeologyDataManager;
''',
        "import com.rockmap.app.research.CngmSearchUi;",
        "import geology learning-link UI",
    )

    replace_once(
        MAIN,
        '''        StringBuilder text = new StringBuilder();
        text.append("Rock type: ").append(lith);
''',
        '''        StringBuilder text = new StringBuilder();
        text.append("Mapped unit: ").append(unit);
        text.append("\\nRock type: ").append(lith);
''',
        'text.append("Mapped unit: ").append(unit);',
        "make tapped geology unit copyable in HUD body",
    )

    replace_once(
        MAIN,
        '''        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(text.toString());
        LinearLayout detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.addView(body);
        Button saveArea = smallActionButton("Save as Prospecting Area");
        saveArea.setOnClickListener(v -> saveGeologyFeatureAsProspectingArea(feature, coordinate, unit));
        detailBox.addView(saveArea);
''',
        '''        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(text.toString());
        body.setTextIsSelectable(true);
        LinearLayout detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.addView(body);
        TextView learnLabel = new TextView(this);
        learnLabel.setText("Learn online\\nSearch for explanations of the geology terms shown here.");
        learnLabel.setTextSize(13f);
        learnLabel.setTextColor(Color.rgb(70, 70, 70));
        learnLabel.setPadding(dp(8), dp(10), dp(8), dp(2));
        detailBox.addView(learnLabel);
        final String learningUnit = unit;
        final String learningAge = age;
        final String learningLithology = lith;
        Button learnOnline = smallActionButton("Search online  ↗");
        learnOnline.setContentDescription("Search online for educational information about this mapped geology. Opens an external browser.");
        learnOnline.setOnClickListener(v -> CngmSearchUi.showLearningSearches(
                this, learningUnit, learningAge, learningLithology));
        detailBox.addView(learnOnline);
        Button saveArea = smallActionButton("Save as Prospecting Area");
        saveArea.setOnClickListener(v -> saveGeologyFeatureAsProspectingArea(feature, coordinate, unit));
        detailBox.addView(saveArea);
''',
        "CngmSearchUi.showLearningSearches(\n                this, learningUnit, learningAge, learningLithology)",
        "add educational web search to geology polygon HUD",
    )

    replace_once(
        MAIN,
        '''        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(text.toString());
        new AlertDialog.Builder(this)
                .setTitle(unit + " — Source Details")
''',
        '''        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setText(text.toString());
        body.setTextIsSelectable(true);
        // CNGM Stage 2B: technical geology values remain copyable.
        new AlertDialog.Builder(this)
                .setTitle(unit + " — Source Details")
''',
        "CNGM Stage 2B: technical geology values remain copyable.",
        "make tapped geology source details copyable",
    )

    replace_once(
        RESEARCH,
        '''import com.rockmap.app.RockMapHelp;
''',
        '''import com.rockmap.app.RockMapHelp;
import com.rockmap.app.TourDebugLog;
''',
        "import com.rockmap.app.TourDebugLog;",
        "import geology-tour debugger logging",
    )

    replace_once(
        RESEARCH,
        '''    private boolean showTourOfferAfterResultLoad;
''',
        '''    private boolean showTourOfferAfterResultLoad;
    private View cngmTourFirstResultTarget;
    private View cngmTourFirstOnlineTarget;
    private View cngmTourShowMapTarget;
    private View cngmTourEditSearchTarget;
    private UnitGroup cngmTourSelectedGroup;
    private String cngmTourSelectedResultTitle = "";
''',
        "private View cngmTourFirstResultTarget;",
        "add dedicated geology-tour live targets",
    )

    replace_once(
        RESEARCH,
        '''        GuidedTourCoach.clear(this);
        currentResultSearchSummary = resultSearchSummary == null ? "" : resultSearchSummary.trim();
        tourCombinedControl = null;
        tourShowGeologyControl = null;
''',
        '''        GuidedTourCoach.clear(this);
        currentResultSearchSummary = resultSearchSummary == null ? "" : resultSearchSummary.trim();
        tourCombinedControl = null;
        tourShowGeologyControl = null;
        cngmTourFirstResultTarget = null;
        cngmTourFirstOnlineTarget = null;
        cngmTourShowMapTarget = null;
        cngmTourEditSearchTarget = null;
''',
        "cngmTourFirstOnlineTarget = null;",
        "reset dedicated geology-tour result targets",
    )

    replace_once(
        RESEARCH,
        '''            Button showMap = button("Show Geology on Map");
            tourShowGeologyControl = showMap;
''',
        '''            Button showMap = button("Show Geology on Map");
            tourShowGeologyControl = showMap;
            cngmTourShowMapTarget = showMap;
''',
        "cngmTourShowMapTarget = showMap;",
        "capture Show Geology on Map for geology tour",
    )

    replace_once(
        RESEARCH,
        '''            showMap.setOnClickListener(v -> {
                if (GuidedTourState.isActive(this)
                        && GuidedTourState.step(this) == GuidedTourState.STEP_SHOW_GEOLOGY) {
                    GuidedTourCoach.clear(this);
                }
                returnGeology(geoJson, resultTitle, safe.size(), mapBounds);
            });
''',
        '''            showMap.setOnClickListener(v -> {
                if (GuidedTourState.isActive(this)
                        && GuidedTourState.step(this) == GuidedTourState.STEP_SHOW_GEOLOGY) {
                    GuidedTourCoach.clear(this);
                }
                if (CngmGeologyTourState.isActive(this)
                        && CngmGeologyTourState.step(this) == 9) {
                    CngmGeologyTourState.setPhase(this, CngmGeologyTourState.PHASE_AWAIT_POLYGON);
                    TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_ACTION",
                            "step=9 action=Show Geology on Map expectedNext=tap mapped polygon");
                    GuidedTourCoach.clear(this);
                }
                returnGeology(geoJson, resultTitle, safe.size(), mapBounds);
            });
''',
        "PHASE_AWAIT_POLYGON",
        "advance geology tour from results to map",
    )

    replace_once(
        RESEARCH,
        '''        setContentView(screen);
        screen.requestApplyInsets();
        maybeShowTourCoach();
''',
        '''        setContentView(screen);
        screen.requestApplyInsets();
        maybeShowTourCoach();
        maybeShowCngmGeologyTourCoach();
''',
        "maybeShowCngmGeologyTourCoach();",
        "resume dedicated geology tour on result render",
    )

    replace_once(
        RESEARCH,
        '''    private void maybeShowTourCoach() {
''',
        '''    private boolean cngmAnotherTourActive() {
        return GuidedTourState.isActive(this)
                || com.rockmap.app.field.FieldTourState.active(this);
    }

    private boolean suppressCngmForAnotherTour(String screen) {
        if (!CngmGeologyTourState.isActive(this) || !cngmAnotherTourActive()) return false;
        int step = CngmGeologyTourState.step(this);
        CngmGeologyTourState.exit(this);
        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_SUPPRESSED",
                "screen=" + screen + " step=" + step + " reason=another-tour-active");
        return true;
    }

    private void handleCngmGeologyTourSearchResults(List<GeologyUnit> safe) {
        if (!CngmGeologyTourState.isActive(this) || suppressCngmForAnotherTour("results")) return;
        String phase = CngmGeologyTourState.phase(this);
        int step = CngmGeologyTourState.step(this);
        int rows = safe == null ? 0 : safe.size();
        if (CngmGeologyTourState.PHASE_AWAIT_RESULTS.equals(phase) && step == 3) {
            cngmTourSelectedGroup = null;
            cngmTourSelectedResultTitle = "";
            TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_RESULTS_RETURNED",
                    "from=3 rows=" + rows + " expectedNext=4");
            CngmGeologyTourState.setPhase(this, CngmGeologyTourState.PHASE_DEFAULT);
            if (rows > 0) {
                CngmGeologyTourState.setStep(this, 4);
                TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_STATE_COMMITTED",
                        "from=3 to=4 reason=successful-search");
            } else {
                TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_SEARCH_EMPTY",
                        "step=3 rows=0 next=retry-search");
            }
        } else if (CngmGeologyTourState.PHASE_AWAIT_LEARN_MORE_RESULTS.equals(phase)
                && step == 7) {
            cngmTourSelectedGroup = null;
            cngmTourSelectedResultTitle = "";
            TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_RESULTS_RETURNED",
                    "from=7 rows=" + rows + " expectedNext=8");
            CngmGeologyTourState.setPhase(this, CngmGeologyTourState.PHASE_DEFAULT);
            if (rows > 0) {
                CngmGeologyTourState.setStep(this, 8);
                TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_STATE_COMMITTED",
                        "from=7 to=8 reason=successful-search");
            } else {
                TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_SEARCH_EMPTY",
                        "step=7 rows=0 next=retry-search");
            }
        }
    }

    private void openCngmTourAwareUnitGroup(UnitGroup group, String resultTitle) {
        if (group == null) return;
        cngmTourSelectedGroup = group;
        cngmTourSelectedResultTitle = resultTitle == null ? "" : resultTitle;
        if (CngmGeologyTourState.isActive(this)
                && CngmGeologyTourState.step(this) == 4) {
            TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_RESULT_SELECTED",
                    "step=4 unit=" + group.name);
            CngmGeologyTourState.setStep(this, 5);
            CngmGeologyTourState.setPhase(this, CngmGeologyTourState.PHASE_DEFAULT);
            TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_STATE_COMMITTED",
                    "from=4 to=5 destination=unit-details");
            GuidedTourCoach.clear(this);
        }
        showUnitGroup(group, resultTitle);
    }

    private void maybeShowCngmGeologyUnitDetailsTourCoach(
            UnitGroup group, String resultTitle, View target) {
        if (!CngmGeologyTourState.isActive(this)
                || suppressCngmForAnotherTour("unit-details")
                || CngmGeologyTourState.step(this) != 5 || target == null) return;
        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_UNIT_DETAILS_RENDER",
                "step=5 unit=" + (group == null ? "" : group.name)
                        + " attached=" + target.isAttachedToWindow()
                        + " size=" + target.getWidth() + "x" + target.getHeight());
        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_STEP5_TARGET_WAIT",
                "target=unit-summary");
        GuidedTourCoach.show(this, 5, 9,
                "Mapped unit details",
                "This view shows the selected mapped unit and its geologic context, including rock type and age when they are available.",
                "Review the mapped unit details.", target,
                () -> {
                    CngmGeologyTourState.setStep(this, 4);
                    TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_BACK",
                            "from=5 to=4 destination=results");
                    showResults(currentResults, currentResultTitle, currentResultBounds,
                            currentQueryContextJson, currentResultSearchSummary);
                },
                "Next", () -> {
                    CngmGeologyTourState.setStep(this, 6);
                    TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_STATE_COMMITTED",
                            "from=5 to=6 destination=search-refine");
                    showSearch();
                },
                () -> {
                    CngmGeologyTourState.setStep(this, 6);
                    TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_SKIP",
                            "screen=unit-details from=5 to=6");
                    showSearch();
                },
                this::exitCngmGeologyTour);
        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_STEP5_SHOWN",
                "target=unit-summary request=issued");
    }

    private void maybeShowCngmGeologyTourCoach() {
        if (!CngmGeologyTourState.isActive(this) || suppressCngmForAnotherTour("results")) return;
        int step = CngmGeologyTourState.step(this);
        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_STEP",
                "screen=results step=" + step
                        + " firstResult=" + (cngmTourFirstResultTarget != null && cngmTourFirstResultTarget.isAttachedToWindow())
                        + " online=" + (cngmTourFirstOnlineTarget != null && cngmTourFirstOnlineTarget.isAttachedToWindow())
                        + " showMap=" + (cngmTourShowMapTarget != null && cngmTourShowMapTarget.isAttachedToWindow()));
        if (step == 3 && (currentResults == null || currentResults.isEmpty())) {
            if (cngmTourEditSearchTarget == null) {
                TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_TARGET_FAIL",
                        "screen=results step=3 target=Edit Search missing");
                return;
            }
            GuidedTourCoach.show(this, 3, 9,
                    "No mapped matches",
                    "That search did not return any mapped geology. Edit the search and try another term or example.",
                    "Tap Edit Search.", cngmTourEditSearchTarget,
                    null, null, null,
                    () -> {
                        CngmGeologyTourState.setStep(this, 6);
                        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_SKIP",
                                "screen=empty-results from=3 to=6");
                        showSearch();
                    },
                    this::exitCngmGeologyTour);
        } else if (step == 4) {
            if (cngmTourFirstResultTarget == null) {
                TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_TARGET_FAIL",
                        "screen=results step=4 target=first result missing");
                return;
            }
            TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_STEP4_TARGET_READY",
                    "target=first-result");
            GuidedTourCoach.show(this, 4, 9,
                    "Mapped geologic units",
                    "Results show mapped geologic units that match the search. The mapped-area count tells you how many mapped polygons use that grouped unit; it is not a count of separate formations.",
                    "Tap any mapped unit result.", cngmTourFirstResultTarget,
                    () -> {
                        CngmGeologyTourState.setStep(this, 3);
                        CngmGeologyTourState.setPhase(this, CngmGeologyTourState.PHASE_DEFAULT);
                        showSearch();
                    },
                    null, null,
                    () -> {
                        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_SKIP",
                                "screen=results step=4 action=open-first-result");
                        if (cngmTourFirstResultTarget != null) cngmTourFirstResultTarget.performClick();
                    },
                    this::exitCngmGeologyTour);
            TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_STEP4_SHOWN",
                    "target=first-result action=tap-any-result");
        } else if (step == 8) {
            if (cngmTourFirstOnlineTarget == null) {
                TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_TARGET_FAIL",
                        "screen=results step=8 target=Search online missing");
                return;
            }
            GuidedTourCoach.show(this, 8, 9,
                    "Learn more",
                    "Search online can research the mapped unit, its age, its rock type, or rockhounding and prospecting context without changing the mapped geology stored in RockMap.",
                    "Tap Search online.", cngmTourFirstOnlineTarget,
                    null, null, null,
                    () -> {
                        CngmGeologyTourState.setStep(this, 9);
                        CngmGeologyTourState.setPhase(this, CngmGeologyTourState.PHASE_AWAIT_MAP);
                        maybeShowCngmGeologyTourCoach();
                    },
                    this::exitCngmGeologyTour);
        } else if (step == 9
                && CngmGeologyTourState.PHASE_AWAIT_MAP.equals(CngmGeologyTourState.phase(this))) {
            if (cngmTourShowMapTarget == null) {
                TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_TARGET_FAIL",
                        "screen=results step=9 target=Show Geology on Map missing");
                return;
            }
            GuidedTourCoach.show(this, 9, 9,
                    "Geology on the map",
                    "Return these mapped geology results to the map, then tap a mapped geology area to inspect the unit beneath that location.",
                    "Tap Show Geology on Map.", cngmTourShowMapTarget,
                    null, null, null,
                    () -> cngmTourShowMapTarget.performClick(),
                    this::exitCngmGeologyTour);
        }
    }

    private void exitCngmGeologyTour() {
        int step = CngmGeologyTourState.step(this);
        CngmGeologyTourState.exit(this);
        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_EXIT", "screen=results step=" + step);
        GuidedTourCoach.clear(this);
    }

    private void maybeShowTourCoach() {
''',
        "private void handleCngmGeologyTourSearchResults",
        "add event-driven dedicated geology-tour results lifecycle",
    )

    replace_once(
        MAIN,
        '''                geologyOverlayController.show(geoJson, title, count);
                if (queryBounds == null) zoomToResearchBounds(returnedBounds);
''',
        '''                geologyOverlayController.show(geoJson, title, count);
                if (CngmGeologyTourState.isActive(this)
                        && CngmGeologyTourState.step(this) == 9
                        && CngmGeologyTourState.PHASE_AWAIT_POLYGON.equals(
                                CngmGeologyTourState.phase(this))) {
                    TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_TRANSITION",
                            "screen=map step=9 geologyOverlayCount=" + count
                                    + " expectedNext=tap polygon");
                    mapView.post(this::maybeShowCngmGeologyMapTour);
                }
                if (queryBounds == null) zoomToResearchBounds(returnedBounds);
''',
        "maybeShowCngmGeologyMapTour",
        "resume dedicated geology tour after map handoff",
    )

    replace_once(
        MAIN,
        '''    private void onGeologyTapped(Feature feature, LatLng coordinate) {
''',
        '''    private void maybeShowCngmGeologyMapTour() {
        if (CngmGeologyTourState.isActive(this)
                && (GuidedTourState.isActive(this) || FieldTourState.active(this))) {
            int previous = CngmGeologyTourState.step(this);
            CngmGeologyTourState.exit(this);
            TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_SUPPRESSED",
                    "screen=map step=" + previous + " reason=another-tour-active");
            return;
        }
        if (!CngmGeologyTourState.isActive(this)
                || CngmGeologyTourState.step(this) != 9
                || !CngmGeologyTourState.PHASE_AWAIT_POLYGON.equals(
                        CngmGeologyTourState.phase(this))) return;
        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_STEP",
                "screen=map step=9 phase=await_polygon overlayVisible="
                        + (geologyOverlayController != null && geologyOverlayController.isVisible()));
        GuidedTourCoach.showMapInteraction(this, 9, 9,
                "Geology on the map",
                "Tap a mapped geology area to identify the unit beneath that location and open its geology details.",
                "Tap a mapped geology area.",
                null, null, null,
                this::finishCngmGeologyTour, this::exitCngmGeologyTourFromMap);
    }

    private void finishCngmGeologyTour() {
        CngmGeologyTourState.finish(this);
        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_FINISH", "screen=map step=9");
        GuidedTourCoach.clear(this);
    }

    private void exitCngmGeologyTourFromMap() {
        int step = CngmGeologyTourState.step(this);
        CngmGeologyTourState.exit(this);
        TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_EXIT", "screen=map step=" + step);
        GuidedTourCoach.clear(this);
    }

    private void onGeologyTapped(Feature feature, LatLng coordinate) {
''',
        "private void maybeShowCngmGeologyMapTour()",
        "add dedicated geology-tour map prompt",
    )

    replace_once(
        MAIN,
        '''        Button saveArea = smallActionButton("Save as Prospecting Area");
        saveArea.setOnClickListener(v -> saveGeologyFeatureAsProspectingArea(feature, coordinate, unit));
        detailBox.addView(saveArea);
        new AlertDialog.Builder(this)
                .setTitle(unit)
                .setView(boundedScrollableContent(detailBox, 430))
                .setPositiveButton("Research", (d, w) -> showResearch())
                .setNeutralButton("Source Details", (d, w) -> showGeologySourceDetails(feature, coordinate, unit))
                .setNegativeButton("Close", null)
                .show();
''',
        '''        Button saveArea = smallActionButton("Save as Prospecting Area");
        saveArea.setOnClickListener(v -> saveGeologyFeatureAsProspectingArea(feature, coordinate, unit));
        detailBox.addView(saveArea);
        AlertDialog geologyDialog = new AlertDialog.Builder(this)
                .setTitle(unit)
                .setView(boundedScrollableContent(detailBox, 430))
                .setPositiveButton("Research", (d, w) -> showResearch())
                .setNeutralButton("Source Details", (d, w) -> showGeologySourceDetails(feature, coordinate, unit))
                .setNegativeButton("Close", null)
                .create();
        geologyDialog.setOnShowListener(ignored -> {
            if (CngmGeologyTourState.isActive(this)
                    && (GuidedTourState.isActive(this) || FieldTourState.active(this))) {
                int previous = CngmGeologyTourState.step(this);
                CngmGeologyTourState.exit(this);
                TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_SUPPRESSED",
                        "screen=geology HUD step=" + previous + " reason=another-tour-active");
                return;
            }
            if (!CngmGeologyTourState.isActive(this)
                    || CngmGeologyTourState.step(this) != 9) return;
            TourDebugLog.mainTourAction(this, "GEOLOGY_TOUR_TARGET_READY",
                    "screen=geology HUD step=9 unit=" + unit);
            FrameLayout host = dialogTourRoot(geologyDialog);
            GuidedTourCoach.show(this, host, 9, 9,
                    "Geology on the map",
                    "This panel identifies the mapped unit at the location you tapped. You can read the mapped unit, rock type, and age, open source details, or continue researching the terms online.",
                    "Review the mapped geology details.", detailBox,
                    null, "Finish", () -> {
                        geologyDialog.dismiss();
                        finishCngmGeologyTour();
                    },
                    () -> {
                        geologyDialog.dismiss();
                        finishCngmGeologyTour();
                    },
                    () -> {
                        geologyDialog.dismiss();
                        exitCngmGeologyTourFromMap();
                    });
        });
        geologyDialog.show();
''',
        "AlertDialog geologyDialog = new AlertDialog.Builder(this)",
        "finish dedicated geology tour in tapped-polygon HUD",
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


def validate_search_ui_injection() -> None:
    checks = {
        REPOSITORY: [
            "public final String unit;",
            "method=source-map-unit-fields",
            "CngmAuthoritativeSearch.resolveLithology",
        ],
        RESEARCH: [
            "CngmSearchUi.show(this, geology, visibleBounds",
            "private void handleCngmGeologyTourSearchResults",
            "private void openCngmTourAwareUnitGroup",
            "private void maybeShowCngmGeologyUnitDetailsTourCoach",
            "private void maybeShowCngmGeologyTourCoach()",
            "cngmTourFirstResultTarget",
            "cngmTourEditSearchTarget",
            "PHASE_AWAIT_POLYGON",
            "String resultSearchSummary",
            "currentResultSearchSummary",
            'Button editSearch = button("Edit Search");',
            "TextView unitSummary = help(\"Mapped unit: \" + group.name",
            "final UnitGroup onlineGroup = group;",
            'Button searchOnline = button("Search online  ↗");',
            '.setNeutralButton("Search online  ↗"',
        ],
        MAIN: [
            "import com.rockmap.app.research.CngmGeologyTourState;",
            "import com.rockmap.app.research.CngmSearchUi;",
            "private void maybeShowCngmGeologyMapTour()",
            "AlertDialog geologyDialog = new AlertDialog.Builder(this)",
            "GEOLOGY_TOUR_FINISH",
            "learningLithology = lith;",
            'smallActionButton("Search online  ↗")',
            "CngmSearchUi.showLearningSearches(",
            "CNGM Stage 2B: technical geology values remain copyable.",
        ],
    }
    for path, markers in checks.items():
        text = path.read_text(encoding="utf-8")
        for marker in markers:
            if marker not in text:
                raise RuntimeError(
                    f"Search UX injection validation failed in {path.relative_to(ROOT)}: {marker}"
                )
    coach_text = COACH.read_text(encoding="utf-8")
    for marker in [
        "prepareDialogHost(Activity activity, Dialog dialog)",
        "private final Dialog sourceDialog;",
    ]:
        if marker not in coach_text:
            raise RuntimeError(f"Geology-tour dialog host validation failed: {marker}")
    geology_tour_text = GEOLOGY_TOUR_STATE.read_text(encoding="utf-8")
    for marker in [
        "public final class CngmGeologyTourState",
        "PHASE_AWAIT_RESULTS",
        "PHASE_AWAIT_LEARN_MORE_RESULTS",
        "PHASE_AWAIT_MAP",
        "PHASE_AWAIT_POLYGON",
    ]:
        if marker not in geology_tour_text:
            raise RuntimeError(f"Geology-tour state validation failed: {marker}")
    ui_tour_markers = [
        'GuidedTourCoach.show(activity, 1, 9',
        'geologyIntroTarget',
        'examplesTarget',
        'Examples are shortcuts for common geology searches.',
        'GEOLOGY_TOUR_SEARCH_STARTED',
        'PHASE_AWAIT_RESULTS',
        'PHASE_AWAIT_LEARN_MORE_RESULTS',
        'callback.onReturnToTourResults()',
        'FieldTourState.active(activity)',
        'GEOLOGY_TOUR_SUPPRESSED',
        'GuidedTourCoach.show(activity, host, 8, 9',
        'CngmGeologyTourState.setStep(activity, 9)',
    ]
    ui_text = SEARCH_UI.read_text(encoding="utf-8")
    for marker in ui_tour_markers:
        if marker not in ui_text:
            raise RuntimeError(f"Geology-tour Search UI validation failed: {marker}")
    for forbidden in [
        'Try Morrison',
        'runMorrisonTourSearch',
        'runMorrisonTourSearchForResultsStep',
        'target=Morrison example',
        'via Morrison',
        'morrisonExampleTarget',
        'graniteExampleTarget',
    ]:
        if forbidden in ui_text:
            raise RuntimeError(
                "Geology-tour Search UI still contains canned-example tour logic: " + forbidden
            )

    research_text = RESEARCH.read_text(encoding="utf-8")
    geology_tour_unique_contracts = [
        "private View cngmTourFirstResultTarget;",
        "private View cngmTourFirstOnlineTarget;",
        "private View cngmTourShowMapTarget;",
        "private View cngmTourEditSearchTarget;",
        "private UnitGroup cngmTourSelectedGroup;",
        'private String currentResultSearchSummary = "";',
        "View unitDetails = action(group.name,",
        "openCngmTourAwareUnitGroup(group, resultTitle)",
        "private void handleCngmGeologyTourSearchResults",
        "private void maybeShowCngmGeologyUnitDetailsTourCoach",
    ]
    for contract in geology_tour_unique_contracts:
        if research_text.count(contract) != 1:
            raise RuntimeError(
                f"Geology-tour generated-source contract failed in ResearchActivity.java: "
                f"expected exactly one {contract!r}, found {research_text.count(contract)}"
            )
    for marker in [
        "GEOLOGY_TOUR_STATE_COMMITTED",
        "GEOLOGY_TOUR_RESULT_SELECTED",
        "GEOLOGY_TOUR_SEARCH_EMPTY",
        "GEOLOGY_TOUR_SUPPRESSED",
        "screen=unit-details from=5 to=4 action=Back to Results",
    ]:
        if marker not in research_text:
            raise RuntimeError(
                "Geology-tour generated-source marker missing in ResearchActivity.java: " + marker
            )
    if "Button unitDetails = action(group.name," in research_text:
        raise RuntimeError(
            "Geology-tour generated-source contract failed: action(...) returns View, not Button."
        )
    if "Lithology filter (optional)" in research_text or "Age filter (optional)" in research_text:
        raise RuntimeError("Legacy optional geology-search labels survived the UI injection.")
    main_text = MAIN.read_text(encoding="utf-8")
    # The dedicated geology tour must consume the existing shared coach engine without
    # injecting geology state or callbacks into GuidedTourCoach itself.
    if "CngmGeologyTourState" in coach_text or "GEOLOGY_TOUR_" in coach_text:
        raise RuntimeError("Dedicated geology-tour logic leaked into GuidedTourCoach.java.")
    if "private void maybeShowTourCoach()" not in research_text:
        raise RuntimeError("Existing Research guided-tour lifecycle was removed or renamed.")
    if "GuidedTourState.STEP_SHOW_GEOLOGY" not in research_text:
        raise RuntimeError("Existing Research geology-tour handoff was removed.")
    if "Search Google" in research_text or "Search Google" in main_text:
        raise RuntimeError("Provider-specific Search Google wording survived the UX injection.")
    if "minerals found here" in ui_text.lower() or "what can i find" in ui_text.lower():
        raise RuntimeError("Search UX generated a location-specific mineral-occurrence claim.")
    print("CNGM Stage 2B search UX source validation: PASS")


def main() -> int:
    manifest = load_and_validate_asset()
    write_bootstrap(manifest)
    write_search_helper()
    write_search_ui()
    write_geology_tour_state()
    inject_sources()
    validate_search_ui_injection()
    print("CNGM Stage 2B authoritative-search injection complete.")
    print("Production geology files/manifests remain untouched; this APK uses a separate debug DB.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"CNGM Stage 2 debug injection failed: {exc}", file=sys.stderr)
        raise
