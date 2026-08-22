#!/usr/bin/env python3
import gzip
import importlib.util
import json
import os
import sqlite3
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("build_colorado_geology.py")
spec = importlib.util.spec_from_file_location("build_colorado_geology", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


def feature(object_id, state="CO", offset=0.0):
    props = {name: "" for name in module.FIELDS}
    props.update({
        "OBJECTID": object_id,
        "STATE": state,
        "ORIG_LABEL": "Kgr",
        "SGMC_LABEL": "Kgr",
        "UNIT_LINK": "CO_Kgr",
        "UNIT_NAME": "Granite unit",
        "AGE_MIN": "Cretaceous",
        "AGE_MAX": "Cretaceous",
        "GENERALIZED_LITH": "Plutonic rock",
        "MAJOR1": "granite",
        "MINOR1": "pegmatite",
        "REF_ID": "CO001",
        "REFERENCE": "Synthetic unit-test reference",
        "DIGITAL_URL": "https://example.invalid/unit",
        "rgba": "150,150,150,255",
    })
    x = -105.0 + offset
    y = 39.0 + offset
    return {
        "type": "Feature",
        "properties": props,
        "geometry": {
            "type": "Polygon",
            "coordinates": [[
                [x, y], [x + 0.01, y], [x + 0.01, y + 0.01],
                [x, y + 0.01], [x, y],
            ]],
        },
    }


def assert_raises(callback, text):
    try:
        callback()
    except Exception as exc:
        assert text.lower() in str(exc).lower(), (text, exc)
    else:
        raise AssertionError(f"Expected error containing {text!r}")


def main():
    row1 = module.validate_feature(feature(101))
    row2 = module.validate_feature(feature(102, offset=0.02))
    assert row1["state"] == "CO"
    assert row1["south"] == 39.0
    assert row1["west"] == -105.0
    assert "granite" in row1["search_text"]
    assert "pegmatite" in row1["lithology_text"]
    assert "cretaceous" in row1["age_text"]
    assert_raises(lambda: module.validate_feature(feature(103, state="UT")), "not a Colorado")

    with tempfile.TemporaryDirectory() as temp_dir:
        out = Path(temp_dir)
        os.environ["ROCKMAP_TEST_SMALL_DB"] = "1"
        manifest = module.package_release(
            [row1, row2], out,
            "owner/repo", "rockmap-test-geology-v1", "test-v1",
            "2026-08-22T18:00:00Z", "colorado-geology-test-v1.db",
        )
        db = out / manifest["database"]["fileName"]
        asset = out / manifest["asset"]["fileName"]
        assert db.stat().st_size == manifest["database"]["bytes"]
        assert asset.stat().st_size == manifest["asset"]["bytes"]
        assert module.sha256_file(db) == manifest["database"]["sha256"]
        assert module.sha256_file(asset) == manifest["asset"]["sha256"]

        unpacked = out / "unpacked.db"
        with gzip.open(asset, "rb") as source, unpacked.open("wb") as target:
            target.write(source.read())
        assert unpacked.read_bytes() == db.read_bytes()

        con = sqlite3.connect(db)
        try:
            assert con.execute("PRAGMA quick_check").fetchone()[0] == "ok"
            assert con.execute("SELECT value FROM metadata WHERE key='schema_version'").fetchone()[0] == "1"
            assert con.execute("SELECT value FROM metadata WHERE key='record_count'").fetchone()[0] == "2"
            assert con.execute("SELECT COUNT(*) FROM units").fetchone()[0] == 2
            assert con.execute("SELECT COUNT(*) FROM units WHERE state='CO'").fetchone()[0] == 2
            columns = {row[1] for row in con.execute("PRAGMA table_info(units)")}
            for required in (
                "object_id", "unit_name", "generalized_lith", "geometry_json",
                "search_text", "lithology_text", "age_text", "south", "west", "north", "east",
            ):
                assert required in columns
        finally:
            con.close()

        parsed = json.loads((out / "geology-manifest.json").read_text())
        assert parsed == manifest
        assert parsed["source"]["recordCount"] == 2
        assert parsed["asset"]["url"].endswith("/rockmap-test-geology-v1/colorado-geology-test-v1.db.gz")
        assert len((out / "SHA256SUMS.txt").read_text().splitlines()) == 3

    print("Colorado geology builder tests passed")


if __name__ == "__main__":
    main()
