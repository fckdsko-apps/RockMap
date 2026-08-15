#!/usr/bin/env python3
"""Build Alpha 6.2.1 Colorado mineral-evidence supplement from official USGS/CGS sources.

This intentionally keeps evidence classes separate. A historic mine inventory, an abandoned-mine
feature, a district review, and a documented mineral occurrence are not interchangeable evidence.
"""
import argparse
import gzip
import hashlib
import json
import math
import re
from datetime import datetime, timezone
from pathlib import Path


COLORADO_BBOX = (-109.10, 36.95, -102.00, 41.05)

SOURCE_RULES = {
    "USGS_MAS": {
        "title": "USGS MAS/MILS OFR 03-090",
        "reliability": "Historic site data; location and status may be approximate or outdated.",
        "evidence": "Historic mine/mineral property",
        "precision": "Mostly revised point; many locations reliable only near 1:500,000.",
    },
    "CGS_B40": {
        "title": "CGS ON-B-40D / B-40",
        "reliability": "Documented occurrence; mapped precision and 1978-era details may vary.",
        "evidence": "Documented radioactive mineral occurrence",
        "precision": "Digitized occurrence point; precision varies with original source mapping.",
    },
    "CGS_MS17": {
        "title": "CGS MS-17 (2022; updates IS-62)",
        "reliability": "Updated inventory; historic mine locations and activity may still be dated.",
        "evidence": "Industrial/nonmetallic mine or permit location",
        "precision": "2022-reviewed mine/permit point; original source precision varies.",
    },
    "CGS_USFS_AML": {
        "title": "CGS/USFS ON-008-04D",
        "reliability": "Field inventory; locations vary and site conditions may have changed.",
        "evidence": "USFS abandoned-mine inventory",
        "precision": "Estimated mine-feature point; field methods and precision vary.",
    },
    "CGS_DISTRICTS": {
        "title": "CGS ON-007-08D Historic Districts",
        "reliability": "District evidence; boundaries are subjective, approximate 1:150,000 areas.",
        "evidence": "District mineralogy (broad-area evidence)",
        "precision": "Display point represents an entire approximate district polygon.",
    },
}

SPECIAL_COMMODITY_CODES = {
    "ABR": "abrasives", "ASB": "asbestos", "BRI": "brines/salines", "CLY": "clay",
    "COA": "coal", "DIT": "diatomite", "FLD": "feldspar", "GAR": "garnet",
    "GEM": "gemstones", "GEO": "geothermal", "GRF": "graphite", "GRT": "granite",
    "GYP": "gypsum", "LST": "limestone", "MBL": "marble", "MIC": "mica",
    "MON": "monazite", "PEA": "peat", "PER": "perlite", "PET": "petroleum",
    "PUM": "pumice", "PYR": "pyrite", "QTZ": "quartz", "REE": "rare earth elements",
    "SDG": "sand and gravel", "SIL": "silica", "SST": "sandstone", "STN": "stone",
    "VOL": "volcanic materials", "VRM": "vermiculite", "ZEO": "zeolites",
}
ELEMENT_CODES = {
    "AG":"silver","AL":"aluminum","AS":"arsenic","AU":"gold","BA":"barium","BE":"beryllium",
    "BI":"bismuth","CD":"cadmium","CE":"cerium","CO":"cobalt","CR":"chromium","CS":"cesium",
    "CU":"copper","FE":"iron","GA":"gallium","GE":"germanium","HG":"mercury","IN":"indium",
    "LA":"lanthanum","LI":"lithium","MG":"magnesium","MN":"manganese","MO":"molybdenum",
    "NB":"niobium","NI":"nickel","PB":"lead","PD":"palladium","PT":"platinum","RA":"radium",
    "RB":"rubidium","SB":"antimony","SC":"scandium","SE":"selenium","SN":"tin","SR":"strontium",
    "TA":"tantalum","TE":"tellurium","TH":"thorium","TI":"titanium","U":"uranium","V":"vanadium",
    "W":"tungsten","Y":"yttrium","ZN":"zinc","ZR":"zirconium",
}

DISTRICT_MINERALS = [
    "amazonite","amethyst","apatite","barite","beryl","bornite","calcite","carnotite","chalcedony",
    "chalcopyrite","chrysocolla","corundum","covellite","epidote","fluorite","galena","garnet",
    "hematite","jarosite","malachite","microcline","molybdenite","monazite","muscovite","olivine",
    "orthoclase","pyrite","pyrrhotite","quartz","rhodochrosite","rutile","scheelite","siderite",
    "sphalerite","tetrahedrite","topaz","tourmaline","uraninite","vanadinite","wolframite","zircon",
]
DISTRICT_COMMODITIES = [
    "gold","silver","copper","lead","zinc","molybdenum","tungsten","uranium","vanadium","thorium",
    "beryllium","lithium","tellurium","rare earth","rare earth elements","iron","manganese","tin",
    "antimony","arsenic","bismuth","cobalt","nickel","platinum","palladium","fluorspar","feldspar",
]


def utc_now():
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def norm(value):
    return re.sub(r"[^a-z0-9]+", "", str(value or "").lower())


def clean_text(value, max_chars=280):
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return ""
    text = re.sub(r"\s+", " ", str(value)).strip()
    if text.lower() in {"nan", "none", "null", "n/a", "na", "unknown", "unk"}:
        return "" if text.lower() in {"nan", "none", "null", "n/a", "na"} else text
    return text[:max_chars]


def unique(values, max_count=20, max_chars=280):
    out, seen = [], set()
    for value in values:
        text = clean_text(value, max_chars)
        key = text.lower()
        if not text or key in seen:
            continue
        seen.add(key)
        out.append(text)
        if len(out) >= max_count:
            break
    return out


def split_compact(value, max_count=12):
    text = clean_text(value, 500)
    if not text:
        return []
    pieces = re.split(r"\s*[;|/]\s*|\s*,\s*(?=[A-Za-z])", text)
    return unique(pieces if len(pieces) > 1 else [text], max_count=max_count, max_chars=220)


def find_col(columns, exact=(), contains=()):
    pairs = [(c, norm(c)) for c in columns]
    exact_norm = [norm(x) for x in exact]
    for wanted in exact_norm:
        for col, key in pairs:
            if key == wanted:
                return col
    contains_norm = [norm(x) for x in contains]
    for wanted in contains_norm:
        for col, key in pairs:
            if wanted and wanted in key:
                return col
    return None


def cols_containing(columns, includes, excludes=()):
    out = []
    for col in columns:
        key = norm(col)
        if any(norm(x) in key for x in includes) and not any(norm(x) in key for x in excludes):
            out.append(col)
    return out


def row_values(row, columns, max_count=20):
    vals = []
    for col in columns:
        if col in row:
            vals.extend(split_compact(row[col]))
    return unique(vals, max_count=max_count, max_chars=220)


def in_colorado(lat, lon):
    xmin, ymin, xmax, ymax = COLORADO_BBOX
    return math.isfinite(lat) and math.isfinite(lon) and ymin <= lat <= ymax and xmin <= lon <= xmax


def float_value(value):
    try:
        v = float(value)
        return v if math.isfinite(v) else None
    except (TypeError, ValueError):
        return None


def record(rec_id, name, lat, lon, source_code, status="", materials=None, commodities=None,
           districts=None, models=None, rocks=None, note=""):
    rule = SOURCE_RULES[source_code]
    return {
        "id": clean_text(rec_id, 120),
        "name": clean_text(name, 180) or "Unnamed mineral evidence",
        "lat": round(float(lat), 6),
        "lon": round(float(lon), 6),
        "status": clean_text(status, 140),
        "grade": "",
        "materials": unique(materials or [], 20, 220),
        "commodities": unique(commodities or [], 20, 140),
        "districts": unique(districts or [], 12, 140),
        "models": unique(models or [], 12, 160),
        "rocks": unique(rocks or [], 20, 220),
        "source_code": source_code,
        "evidence_type": rule["evidence"],
        "location_precision": rule["precision"],
        "source_title": rule["title"],
        "source_reliability": rule["reliability"],
        "source_note": clean_text(note, 360),
    }


def mas_column_map(columns):
    """Resolve the documented OFR 03-090 GIS field names without depending on column order."""
    return {
        "id": find_col(columns, exact=("MAS_NO", "SEQUENCE_N")),
        "name": find_col(columns, exact=("SITE_NAME",)),
        "code": find_col(columns, exact=("COMM",)),
        "full": find_col(columns, exact=("COMMO_FULL",)),
        "district": find_col(columns, exact=("MINING_DIS",)),
        "status": find_col(columns, exact=("CURRENT_ST",)),
        "mrds": find_col(columns, exact=("GEOLSURVEY",)),
    }


def read_mas(root):
    # Use the OFR 03-090 GIS table rather than the original unedited XLS workbook.
    # The publication metadata says the GIS file contains the revised plotted locations
    # and commonly populated analysis fields, while the XLS is the original unedited data.
    layers = vector_layers(root)
    point_layers = [(n, p, g) for n, p, g in layers if geometry_kind(g) == "point"]
    candidates = []
    for name, path, gdf in point_layers:
        columns = [c for c in gdf.columns if c != "geometry"]
        mapped = mas_column_map(columns)
        required_score = sum(1 for key in ("name", "code", "full") if mapped[key] is not None)
        name_score = 1 if "mas" in norm(name) or "mas" in norm(path) else 0
        candidates.append((required_score, name_score, len(gdf), name, path, gdf, mapped))
    candidates.sort(key=lambda x: (x[0], x[1], x[2]), reverse=True)
    if not candidates or candidates[0][0] < 2:
        details = [(n, len(g), [str(c) for c in g.columns[:30]]) for n, p, g in point_layers[:10]]
        raise RuntimeError(f"OFR 03-090 MAS GIS package missing expected SITE_NAME/COMM/COMMO_FULL fields: {details}")

    _, _, _, layer_name, layer_path, gdf, mapped = candidates[0]
    gdf = to_wgs84(gdf)
    columns = [c for c in gdf.columns if c != "geometry"]
    mapped = mas_column_map(columns)
    out = []
    for i, row in gdf.iterrows():
        geom = row.geometry
        if geom is None or geom.is_empty:
            continue
        lat, lon = float(geom.y), float(geom.x)
        if not in_colorado(lat, lon):
            continue
        raw_id = clean_text(row.get(mapped["id"]), 80) if mapped["id"] else ""
        rec_id = f"mas-{raw_id or i+1}"
        commodity = []
        full = clean_text(row.get(mapped["full"]), 120) if mapped["full"] else ""
        if full:
            commodity.append(full)
        code = clean_text(row.get(mapped["code"]), 20).upper() if mapped["code"] else ""
        if code:
            commodity.append(SPECIAL_COMMODITY_CODES.get(code, ELEMENT_CODES.get(code, code)))
        note = "Primary commodity/property record; commodity is not mineral-species proof."
        mrds = clean_text(row.get(mapped["mrds"]), 60) if mapped["mrds"] else ""
        if mrds:
            note += f" MRDS cross-reference: {mrds}."
        out.append(record(
            rec_id, row.get(mapped["name"]) if mapped["name"] else "", lat, lon, "USGS_MAS",
            status=row.get(mapped["status"]) if mapped["status"] else "",
            commodities=commodity,
            districts=[row.get(mapped["district"])] if mapped["district"] else [],
            note=note,
        ))
    return out, {
        "selected_layer": layer_name,
        "selected_path": layer_path,
        "candidate_layers": [(x[3], x[2]) for x in candidates[:10]],
        "columns": [str(c) for c in columns],
        "raw_rows": len(gdf),
        "location_source": "GIS point geometry from USGS OFR 03-090 CO_MAS table",
    }


def vector_layers(root):
    import geopandas as gpd
    import pyogrio
    layers = []
    root = Path(root)
    for shp in root.rglob("*.shp"):
        try:
            gdf = gpd.read_file(shp)
            layers.append((shp.stem, str(shp), gdf))
        except Exception:
            continue
    # USGS OFR 03-090 distributes the revised Colorado MAS GIS table as MapInfo TAB.
    # Pyogrio/GDAL reads the TAB plus its companion DAT/MAP/ID files as one vector layer.
    for tab in root.rglob("*.tab"):
        try:
            gdf = gpd.read_file(tab)
            layers.append((tab.stem, str(tab), gdf))
        except Exception:
            continue
    for tab in root.rglob("*.TAB"):
        try:
            gdf = gpd.read_file(tab)
            layers.append((tab.stem, str(tab), gdf))
        except Exception:
            continue
    for gdb in root.rglob("*.gdb"):
        try:
            listed = pyogrio.list_layers(gdb)
        except Exception:
            continue
        for item in listed:
            layer_name = str(item[0])
            try:
                gdf = gpd.read_file(gdb, layer=layer_name)
                layers.append((layer_name, f"{gdb}:{layer_name}", gdf))
            except Exception:
                continue
    return layers


def geometry_kind(gdf):
    if gdf is None or gdf.empty or "geometry" not in gdf:
        return ""
    types = {str(x).lower() for x in gdf.geometry.geom_type.dropna().unique()}
    if any("point" in x for x in types): return "point"
    if any("polygon" in x for x in types): return "polygon"
    return "other"


def to_wgs84(gdf):
    if gdf.crs is None:
        bounds = gdf.total_bounds
        if len(bounds) == 4 and -180 <= bounds[0] <= 180 and -180 <= bounds[2] <= 180 and -90 <= bounds[1] <= 90 and -90 <= bounds[3] <= 90:
            return gdf.set_crs(4326, allow_override=True)
        raise RuntimeError("GIS layer has no CRS and is not obviously longitude/latitude")
    return gdf.to_crs(4326)


def choose_layer(layers, kind, keyword_bonus=()):
    candidates = []
    for name, path, gdf in layers:
        if geometry_kind(gdf) != kind:
            continue
        key = norm(name) + " " + " ".join(norm(c) for c in gdf.columns)
        bonus = sum(100000 for word in keyword_bonus if norm(word) in key)
        candidates.append((len(gdf) + bonus, len(gdf), name, path, gdf))
    if not candidates:
        raise RuntimeError(f"No {kind} GIS layer found")
    candidates.sort(key=lambda x: (x[0], x[1]), reverse=True)
    return candidates[0][2], candidates[0][3], candidates[0][4], [(x[2], x[1]) for x in candidates[:10]]


def read_b40(root):
    layers = vector_layers(root)
    name, path, gdf, candidates = choose_layer(layers, "point", ("radioactive", "occurrence", "b40", "mineral"))
    gdf = to_wgs84(gdf)
    columns = [c for c in gdf.columns if c != "geometry"]
    name_col = find_col(columns, exact=("MINE_NAME","SITE_NAME","NAME","OCCURRENCE_NAME","MINE"), contains=("minename","sitename","occurrencename"))
    id_col = find_col(columns, exact=("ID","REC_ID","RECORD_ID","OCC_ID","NO","NUMBER"), contains=("occurrenceid","recordid"))
    mineral_cols = cols_containing(columns, ("mineralog","mineral"), ("mineralbelt",))
    commodity_cols = cols_containing(columns, ("commodity","element"), ())
    rock_cols = cols_containing(columns, ("hostrock","host_rock","rocktype","geology","alteration","formation"), ())
    district_cols = cols_containing(columns, ("district",), ("ranger",))
    status_col = find_col(columns, exact=("DEVELOPMENT","STATUS","MINE_DEV"), contains=("development","status"))
    out = []
    for i, row in gdf.iterrows():
        geom = row.geometry
        if geom is None or geom.is_empty:
            continue
        lat, lon = float(geom.y), float(geom.x)
        if not in_colorado(lat, lon):
            continue
        materials = row_values(row, mineral_cols)
        commodities = row_values(row, commodity_cols)
        searchable = " ".join(materials + commodities).lower()
        for term in ("uranium", "thorium", "vanadium", "radium"):
            if re.search(rf"\b{term}\b", searchable):
                commodities.append(term)
        rid = clean_text(row.get(id_col), 80) if id_col else ""
        out.append(record(
            f"b40-{rid or i+1}", row.get(name_col) if name_col else "", lat, lon, "CGS_B40",
            status=row.get(status_col) if status_col else "",
            materials=materials, commodities=commodities,
            districts=row_values(row, district_cols, 6), rocks=row_values(row, rock_cols, 12),
            note="Digitized from the 1978 B-40 occurrence compilation.",
        ))
    meta = {"selected_layer": name, "selected_path": path, "candidate_layers": candidates,
            "columns": [str(c) for c in columns]}
    return out, meta


def read_ms17(root):
    layers = vector_layers(root)
    point_layers = [(n,p,g) for n,p,g in layers if geometry_kind(g) == "point" and len(g) >= 20]
    if not point_layers:
        raise RuntimeError("MS-17 package contains no usable point GIS layers")
    subset_words = {"sand","gravel","borrow","sandstone","silica","volcan","clay","shale","crushed","granite","dolomite","limestone","perlite"}
    preferred = []
    for n,p,g in point_layers:
        k = norm(n)
        if any(w in k for w in subset_words) and "all" not in k:
            continue
        if "all" in k or "permit" in k or "mine" in k or "mineral" in k:
            preferred.append((n,p,g))
    if not preferred:
        preferred = sorted(point_layers, key=lambda x: len(x[2]), reverse=True)[:3]
    else:
        preferred = sorted(preferred, key=lambda x: len(x[2]), reverse=True)[:4]

    out, seen, layer_meta = [], set(), []
    for layer_name, path, raw in preferred:
        gdf = to_wgs84(raw)
        columns = [c for c in gdf.columns if c != "geometry"]
        name_col = find_col(columns, exact=("NAME","MINE_NAME","SITE_NAME","OPERATION","FACILITY"), contains=("minename","sitename","operationname","facilityname"))
        id_col = find_col(columns, exact=("ID","REC_ID","RECORD_ID","PERMIT","PERMIT_NO"), contains=("permitno","recordid"))
        material_cols = cols_containing(columns, ("material","commodity","mineral","product","type"), ("facilitytype","geomtype"))
        status_col = find_col(columns, exact=("STATUS","ACTIVITY","MINE_STATUS"), contains=("status","activity"))
        for i, row in gdf.iterrows():
            geom = row.geometry
            if geom is None or geom.is_empty:
                continue
            lat, lon = float(geom.y), float(geom.x)
            if not in_colorado(lat, lon):
                continue
            name_value = clean_text(row.get(name_col), 180) if name_col else ""
            materials = row_values(row, material_cols, 12)
            key = (round(lat,5), round(lon,5), name_value.lower(), tuple(x.lower() for x in materials[:3]))
            if key in seen:
                continue
            seen.add(key)
            rid = clean_text(row.get(id_col), 80) if id_col else ""
            out.append(record(
                f"ms17-{norm(layer_name)[:24]}-{rid or i+1}", name_value, lat, lon, "CGS_MS17",
                status=row.get(status_col) if status_col else "", materials=materials, commodities=materials,
                note="2022 GIS update based on the historic MS-17 / IS-62 nonmetallic inventory.",
            ))
        layer_meta.append({"layer": layer_name, "path": path, "rows": len(raw), "columns": [str(c) for c in columns]})
    return out, {"selected_layers": layer_meta, "all_point_layers": [(n,len(g)) for n,p,g in point_layers]}


def read_aml(root):
    layers = vector_layers(root)
    name, path, gdf, candidates = choose_layer(layers, "point", ("aml", "mine", "feature"))
    gdf = to_wgs84(gdf)
    columns = [c for c in gdf.columns if c != "geometry"]
    name_col = find_col(columns, exact=("NAME","MINE_NAME","SITE_NAME","FEATURE_NAME"), contains=("minename","sitename","featurename"))
    id_col = find_col(columns, exact=("ID","REC_ID","RECORD_ID","FEATURE_ID"), contains=("featureid","recordid"))
    material_cols = cols_containing(columns, ("commodity","mineral"), ("mineralcreek",))
    status_col = find_col(columns, exact=("STATUS","FEATURE_TYPE","TYPE"), contains=("featuretype","status"))
    out = []
    for i, row in gdf.iterrows():
        geom = row.geometry
        if geom is None or geom.is_empty:
            continue
        lat, lon = float(geom.y), float(geom.x)
        if not in_colorado(lat, lon):
            continue
        rid = clean_text(row.get(id_col), 80) if id_col else ""
        explicit_materials = row_values(row, material_cols, 8)
        out.append(record(
            f"aml-{rid or i+1}", row.get(name_col) if name_col else "", lat, lon, "CGS_USFS_AML",
            status=row.get(status_col) if status_col else "",
            materials=explicit_materials, commodities=explicit_materials,
            note="Mine-feature evidence only; mineral association requires an explicit source field.",
        ))
    return out, {"selected_layer": name, "selected_path": path, "candidate_layers": candidates,
                 "columns": [str(c) for c in columns]}


def extract_pdf_text(root):
    from pypdf import PdfReader
    pdfs = sorted(Path(root).rglob("*.pdf"), key=lambda p: p.stat().st_size, reverse=True)
    report = next((p for p in pdfs if "rpt" in p.name.lower() or "report" in p.name.lower()), pdfs[0] if pdfs else None)
    if report is None:
        return "", ""
    reader = PdfReader(str(report))
    # Preserve page boundaries so district extraction can avoid bleeding mineral terms
    # from an unrelated district review on a following page.
    text = "\n\f\n".join((page.extract_text() or "") for page in reader.pages)
    return text, str(report)


def literal_terms(text, terms):
    lower = text.lower()
    found = []
    for term in terms:
        if re.search(r"(?<![a-z0-9])" + re.escape(term.lower()) + r"(?![a-z0-9])", lower):
            found.append(term)
    return found


def district_window(report_text, district_name, all_district_names=()):
    if not report_text or not district_name:
        return ""
    matches = list(re.finditer(re.escape(district_name), report_text, flags=re.I))
    if not matches:
        short = re.sub(r"\s+(mining\s+)?district\b.*$", "", district_name, flags=re.I).strip()
        if len(short) >= 4:
            matches = list(re.finditer(re.escape(short), report_text, flags=re.I))
    if not matches:
        return ""

    best, best_score = "", -1
    all_terms = DISTRICT_MINERALS + DISTRICT_COMMODITIES
    other_names = [x for x in all_district_names if x and x.lower() != district_name.lower()]
    for m in matches[:40]:
        start = m.start()
        hard_end = min(len(report_text), start + 8000)
        end = hard_end
        # District reviews are laid out as separate sections. Prefer a line-start match for the
        # next known district heading; this prevents terms in a neighboring review from being
        # attributed to the current district.
        tail = report_text[m.end():hard_end]
        for other in other_names:
            heading = re.search(r"(?:^|[\n\f])\s*" + re.escape(other) + r"\b", tail, flags=re.I)
            if heading and m.end() + heading.start() < end:
                end = m.end() + heading.start()
        window = report_text[start:end]
        score = len(literal_terms(window, all_terms))
        # Table-of-contents hits tend to have many district headings but little descriptive text.
        # Prefer a tied window with more prose, while never inferring unmentioned minerals.
        prose_score = min(len(window), 4000) / 4000.0
        combined = score * 10 + prose_score
        if combined > best_score:
            best, best_score = window, combined
    return best


def read_districts(root):
    layers = vector_layers(root)
    name, path, raw, candidates = choose_layer(layers, "polygon", ("district", "mining"))
    # Representative point in source CRS avoids a geographic-centroid calculation warning.
    raw = raw.copy()
    raw["__display_point"] = raw.geometry.representative_point()
    points = raw.set_geometry("__display_point")
    points = to_wgs84(points)
    columns = [c for c in raw.columns if c not in {"geometry", "__display_point"}]
    name_col = find_col(columns, exact=("DISTRICT","DIST_NAME","NAME","DISTRICT_N"), contains=("districtname","distname"))
    id_col = find_col(columns, exact=("ID","DIST_ID","DISTRICT_ID"), contains=("districtid",))
    county_col = find_col(columns, exact=("COUNTY","COUNTY_NAM"), contains=("county",))
    report_text, report_path = extract_pdf_text(root)
    district_names = []
    if name_col:
        for value in points[name_col].tolist():
            value = clean_text(value, 160)
            if value:
                district_names.append(value)
    out = []
    active_geometry = points.geometry.name
    for i, row in points.iterrows():
        geom = row[active_geometry]
        if geom is None or geom.is_empty:
            continue
        lat, lon = float(geom.y), float(geom.x)
        if not in_colorado(lat, lon):
            continue
        district_name = clean_text(row.get(name_col), 160) if name_col else f"Historic district {i+1}"
        window = district_window(report_text, district_name, district_names)
        minerals = literal_terms(window, DISTRICT_MINERALS)
        commodities = literal_terms(window, DISTRICT_COMMODITIES)
        rid = clean_text(row.get(id_col), 80) if id_col else ""
        county = clean_text(row.get(county_col), 80) if county_col else ""
        note = "Minerals/commodities are literal terms from the CGS district review; evidence applies to the district, not this display point."
        out.append(record(
            f"district-{rid or i+1}", district_name, lat, lon, "CGS_DISTRICTS",
            materials=minerals, commodities=commodities,
            districts=[district_name] + ([county + " County"] if county else []),
            note=note,
        ))
    return out, {"selected_layer": name, "selected_path": path, "candidate_layers": candidates,
                 "columns": [str(c) for c in columns], "report_pdf": report_path,
                 "report_text_chars": len(report_text)}


def validate_source_counts(groups):
    counts = {k: len(v) for k, v in groups.items()}
    minimums = {"USGS_MAS": 14000, "CGS_B40": 1800, "CGS_MS17": 100,
                "CGS_USFS_AML": 10000, "CGS_DISTRICTS": 80}
    for code, minimum in minimums.items():
        if counts.get(code, 0) < minimum:
            raise RuntimeError(f"{code} parsed only {counts.get(code,0)} records; expected at least {minimum}. Refusing to publish.")
    searchable = {
        code: sum(1 for r in rows if r["materials"] or r["commodities"])
        for code, rows in groups.items()
    }
    if searchable["USGS_MAS"] < 10000:
        raise RuntimeError("MAS/MILS commodity extraction failed closed (<10,000 searchable commodity records).")
    if searchable["CGS_B40"] < 900:
        raise RuntimeError("B-40 mineral/commodity extraction failed closed (<900 searchable occurrence records).")
    if searchable["CGS_MS17"] < 75:
        raise RuntimeError("MS-17 material extraction failed closed (<75 searchable mine records).")
    if searchable["CGS_DISTRICTS"] < 25:
        raise RuntimeError("Historic-district report mineralogy extraction failed closed (<25 searchable districts).")
    return counts, searchable


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--mas-root", required=True)
    p.add_argument("--b40-root", required=True)
    p.add_argument("--ms17-root", required=True)
    p.add_argument("--aml-root", required=True)
    p.add_argument("--district-root", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--metadata", required=True)
    args = p.parse_args()

    mas, mas_meta = read_mas(Path(args.mas_root))
    b40, b40_meta = read_b40(Path(args.b40_root))
    ms17, ms17_meta = read_ms17(Path(args.ms17_root))
    aml, aml_meta = read_aml(Path(args.aml_root))
    districts, district_meta = read_districts(Path(args.district_root))

    groups = {"USGS_MAS": mas, "CGS_B40": b40, "CGS_MS17": ms17,
              "CGS_USFS_AML": aml, "CGS_DISTRICTS": districts}
    print("Parser source counts before fail-closed validation:")
    print(json.dumps({k: len(v) for k, v in groups.items()}, indent=2, sort_keys=True))
    print("Selected B-40 layer/columns:", b40_meta.get("selected_layer"), b40_meta.get("columns"))
    print("Selected AML layer/columns:", aml_meta.get("selected_layer"), aml_meta.get("columns"))
    print("Selected MS-17 layers:", [(x.get("layer"), x.get("rows")) for x in ms17_meta.get("selected_layers", [])])
    print("Selected district layer/report:", district_meta.get("selected_layer"), district_meta.get("report_pdf"))
    counts, searchable = validate_source_counts(groups)
    records = []
    identities = set()
    for code in ("USGS_MAS","CGS_B40","CGS_MS17","CGS_USFS_AML","CGS_DISTRICTS"):
        for item in groups[code]:
            identity = (item["source_code"], item["id"])
            if identity in identities:
                continue
            identities.add(identity)
            records.append(item)
    records.sort(key=lambda r: (r["source_code"], r["id"]))

    payload = {
        "schema": 1,
        "source": "RockMap Alpha 6.2.1 official USGS/CGS Colorado mineral-evidence supplement",
        "generatedAt": utc_now(),
        "recordCount": len(records),
        "records": records,
    }
    raw_bytes = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("wb") as raw_out:
        with gzip.GzipFile(fileobj=raw_out, mode="wb", compresslevel=9, mtime=0) as gz:
            gz.write(raw_bytes)

    coverage_terms = ["amazonite","aquamarine","beryl","fluorite","topaz","rhodochrosite","quartz",
                      "gold","silver","copper","uranium","vanadium","feldspar","garnet","tourmaline"]
    coverage = {}
    for term in coverage_terms:
        coverage[term] = sum(1 for r in records if term in " | ".join(
            r["materials"] + r["commodities"] + r["districts"] + [r["name"]]).lower())

    source_files = {}
    # The workflow hashes every downloaded source archive before extraction. The builder records
    # the selected GIS layer diagnostics here; archive hashes are published in SOURCE_SHA256SUMS.txt.

    metadata = {
        "built_at": utc_now(),
        "record_count": len(records),
        "record_counts_by_source": counts,
        "searchable_records_by_source": searchable,
        "compressed_index_bytes": output.stat().st_size,
        "uncompressed_json_bytes": len(raw_bytes),
        "coverage_record_counts": coverage,
        "source_files": source_files,
        "parser_diagnostics": {
            "USGS_MAS": mas_meta, "CGS_B40": b40_meta, "CGS_MS17": ms17_meta,
            "CGS_USFS_AML": aml_meta, "CGS_DISTRICTS": district_meta,
        },
        "source_reliability": {code: rule["reliability"] for code, rule in SOURCE_RULES.items()},
        "mindat_status": "Not bundled: API requires approved access and CC BY-NC-SA 4.0; RockMap does not scrape Mindat.",
    }
    Path(args.metadata).write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("Alpha 6.2.1 official evidence counts:")
    print(json.dumps(counts, indent=2, sort_keys=True))
    print("Searchable-by-mineral/commodity counts:")
    print(json.dumps(searchable, indent=2, sort_keys=True))
    print(f"Total evidence records: {len(records)}")
    print(f"Compressed evidence index bytes: {output.stat().st_size}")
    print("Coverage sample:")
    print(json.dumps(coverage, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
