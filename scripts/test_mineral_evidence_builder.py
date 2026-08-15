#!/usr/bin/env python3
"""Small deterministic regression checks for Alpha 6.2.1 evidence normalization."""
from build_mineral_evidence import (
    SOURCE_RULES,
    SPECIAL_COMMODITY_CODES,
    district_window,
    literal_terms,
    record,
    mas_column_map,
    DISTRICT_MINERALS,
    DISTRICT_COMMODITIES,
)


def main():
    assert SPECIAL_COMMODITY_CODES["QTZ"] == "quartz"
    assert SPECIAL_COMMODITY_CODES["FLD"] == "feldspar"

    mas = mas_column_map([
        "MAS_NO", "GEOLSURVEY", "SITE_NAME", "COMM", "COMMO_FULL",
        "MINING_DIS", "CURRENT_ST", "geometry",
    ])
    assert mas["id"] == "MAS_NO"
    assert mas["name"] == "SITE_NAME"
    assert mas["code"] == "COMM"
    assert mas["full"] == "COMMO_FULL"
    assert mas["district"] == "MINING_DIS"
    assert mas["status"] == "CURRENT_ST"
    assert mas["mrds"] == "GEOLSURVEY"

    for code, rule in SOURCE_RULES.items():
        item = record(code.lower(), "Test", 39.0, -106.0, code, materials=["Quartz"])
        assert item["source_code"] == code
        assert item["source_title"] == rule["title"]
        assert item["source_reliability"] == rule["reliability"]
        assert 0 < len(item["source_reliability"].split()) <= 15

    report = (
        "Alpha District\nMineralogy includes rhodochrosite, quartz, gold, silver and lead.\n"
        "\f\nBeta District\nOres include fluorite, galena, zinc and copper.\n"
    )
    names = ["Alpha District", "Beta District"]
    alpha = district_window(report, "Alpha District", names)
    beta = district_window(report, "Beta District", names)
    alpha_minerals = literal_terms(alpha, DISTRICT_MINERALS)
    alpha_commodities = literal_terms(alpha, DISTRICT_COMMODITIES)
    beta_minerals = literal_terms(beta, DISTRICT_MINERALS)
    beta_commodities = literal_terms(beta, DISTRICT_COMMODITIES)
    assert "rhodochrosite" in alpha_minerals
    assert "fluorite" not in alpha_minerals
    assert "copper" not in alpha_commodities
    assert "fluorite" in beta_minerals
    assert "rhodochrosite" not in beta_minerals
    assert "copper" in beta_commodities

    print("Alpha 6.2.1 mineral-evidence builder regression checks passed")


if __name__ == "__main__":
    main()
