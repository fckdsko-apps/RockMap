#!/usr/bin/env python3
"""Fast regression tests for the CNGM production-candidate builder.

These tests do not download the 2.1-GB USGS package. They exercise the science/provenance
normalization and fail-closed SQLite candidate construction using synthetic records.
"""
from __future__ import annotations

import csv
import importlib.util
import json
from pathlib import Path
import tempfile

SCRIPT = Path(__file__).with_name("build_colorado_cngm_candidate.py")
spec = importlib.util.spec_from_file_location("build_colorado_cngm_candidate", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


def write_csv(path: Path, fieldnames, rows):
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def main():
    assert module.norm_token("Source_DescriptionOfMapUnits") == "sourcedescriptionofmapunits"
    assert module.is_allowed_download_url("https://ngmdb.usgs.gov/example.zip")
    assert not module.is_allowed_download_url("http://ngmdb.usgs.gov/example.zip")
    assert not module.is_allowed_download_url("https://example.com/example.zip")

    layer_json = json.dumps({
        "layers": [
            {"name": "MapUnitPolys"},
            {"name": "Source_DescriptionOfMapUnits"},
            {"name": "DescriptionOfMapUnits"},
            {"name": "synthesis_to_source_units"},
            {"name": "DataSources"},
        ]
    })
    assert module.parse_ogrinfo_layer_json(layer_json) == [
        "MapUnitPolys",
        "Source_DescriptionOfMapUnits",
        "DescriptionOfMapUnits",
        "synthesis_to_source_units",
        "DataSources",
    ]

    # Regression for Actions failure 90638298605: the CSV destination must not
    # be pre-created as a directory before ogr2ogr asks the CSV driver to Create().
    builder_source = SCRIPT.read_text(encoding="utf-8")
    assert 'str(target), str(gdb), layers[logical]' in builder_source
    assert 'tmp_dir.mkdir()' not in builder_source

    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        source_csv = root / "source.csv"
        synth_csv = root / "synth.csv"
        cross_csv = root / "cross.csv"
        data_csv = root / "data.csv"
        polygons = root / "polygons.geojsonl"

        write_csv(
            source_csv,
            [
                "Source_DescriptionOfMapUnits_ID", "Source_MapUnit", "Name", "FullName", "Age", "Description",
                "GeoMaterial", "GeoMaterialConfidence", "Label", "HierarchyKey",
                "Symbol", "MapSourceID", "DescriptionSourceID", "AreaFillRGB",
            ],
            [{
                "Source_DescriptionOfMapUnits_ID": "7001",
                "Source_MapUnit": "31 | Tgr",
                "Name": "Granite",
                "FullName": "Granite unit",
                "Age": "Tertiary",
                "Description": "Synthetic granite source description",
                "GeoMaterial": "Intrusive igneous rock",
                "GeoMaterialConfidence": "certain",
                "Label": "Tgr",
                "HierarchyKey": "01.01",
                "Symbol": "101",
                "MapSourceID": "CO_MAP",
                "DescriptionSourceID": "CO_MAP",
                "AreaFillRGB": "200,150,150",
            }],
        )
        write_csv(
            synth_csv,
            [
                "DescriptionOfMapUnits_ID", "MapUnit", "Name", "FullName", "Age",
                "Description", "GeoMaterial", "Label", "HierarchyKey", "Symbol",
            ],
            [{
                "DescriptionOfMapUnits_ID": "9001",
                "MapUnit": "T_i",
                "Name": "Tertiary intrusive rocks",
                "FullName": "Tertiary intrusive rocks",
                "Age": "Tertiary",
                "Description": "Synthetic CNGM synthesis description",
                "GeoMaterial": "Intrusive igneous rock",
                "Label": "Ti",
                "HierarchyKey": "02.03",
                "Symbol": "201",
            }],
        )
        write_csv(
            cross_csv,
            ["Source_DescriptionOfMapUnitsID", "DescriptionOfMapUnitsID", "Source_MapUnit", "MapUnit"],
            [{
                "Source_DescriptionOfMapUnitsID": "7001",
                "DescriptionOfMapUnitsID": "9001",
                "Source_MapUnit": "31 | Tgr",
                "MapUnit": "T_i",
            }],
        )
        write_csv(
            data_csv,
            ["DataSources_ID", "Source", "URL", "Notes"],
            [{
                "DataSources_ID": "CO_MAP",
                "Source": "Synthetic Colorado source map citation",
                "URL": "https://example.invalid/source",
                "Notes": "test only",
            }],
        )

        feature = {
            "type": "Feature",
            "properties": {
                "MapUnitPolys_ID": "123",
                "Source_MapUnit": "31 | Tgr",
                "MapUnit": "T_i",
                "MapSourceID": "CO_MAP",
                "DataSourceID": "CO_MAP",
                "Symbol": "101",
            },
            "geometry": {
                "type": "Polygon",
                "coordinates": [[
                    [-105.0, 39.0],
                    [-104.9, 39.0],
                    [-104.9, 39.1],
                    [-105.0, 39.1],
                    [-105.0, 39.0],
                ]],
            },
        }
        polygons.write_text(json.dumps(feature) + "\n", encoding="utf-8")

        old_min = module.MIN_POLYGONS
        module.MIN_POLYGONS = 1
        try:
            db = root / "candidate.db"
            metrics = module.build_candidate(
                {
                    "source_units": source_csv,
                    "synthesis_units": synth_csv,
                    "crosswalk": cross_csv,
                    "data_sources": data_csv,
                    "polygons": polygons,
                },
                db,
                built_at="2026-08-31T22:00:00Z",
                source_download={
                    "bytes": 123456789,
                    "sha256": "a" * 64,
                    "requested_url": "https://ngmdb.usgs.gov/example.zip",
                    "final_url": "https://ngmdb.usgs.gov/example.zip",
                },
            )
        finally:
            module.MIN_POLYGONS = old_min

        assert metrics["polygons"] == 1
        assert metrics["source_units"] == 1
        assert metrics["synthesis_units"] == 1
        assert metrics["crosswalk_rows"] == 1
        assert metrics["data_sources"] == 1

        import sqlite3
        con = sqlite3.connect(db)
        try:
            assert con.execute("PRAGMA quick_check").fetchone()[0] == "ok"
            assert con.execute("SELECT value FROM metadata WHERE key='schema_version'").fetchone()[0] == "2"
            assert con.execute("SELECT value FROM metadata WHERE key='source_doi'").fetchone()[0] == module.EARTH_SURFACE_DOI
            assert con.execute("SELECT COUNT(*) FROM polygons").fetchone()[0] == 1
            assert con.execute("SELECT upstream_polygon_id FROM polygons").fetchone()[0] == "CO_MAP|123"
            assert con.execute("SELECT source_mapunit FROM source_units").fetchone()[0] == "31 | Tgr"
            assert con.execute("SELECT mapunit FROM synthesis_units").fetchone()[0] == "T_i"
            assert con.execute("SELECT COUNT(*) FROM source_synthesis").fetchone()[0] == 1
            citation = con.execute("SELECT source_citation FROM source_units").fetchone()[0]
            assert "Synthetic Colorado source map citation" in citation
        finally:
            con.close()

    print("Colorado CNGM production-candidate builder tests passed")


if __name__ == "__main__":
    main()
