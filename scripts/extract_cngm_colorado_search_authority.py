#!/usr/bin/env python3
"""
Extract the authoritative Colorado search relationships needed by RockMap from
USGS CNGM's *full relational geospatial database* (DOI 10.5066/P1DC4XFG).

This is a fail-closed scientific extraction step. It does not invent aliases,
ages, lithologies, or geological relationships. It retains only relationships
published by CNGM and cross-checks them against RockMap's reviewed map50-only
Earth Surface debug pack.

Outputs are artifact-only. This script does not modify Android source and does
not publish a production data release.
"""
from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
import html
from html.parser import HTMLParser
import io
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Tuple

ROOT = Path(__file__).resolve().parents[1]
PRODUCT_PAGE = "https://ngmdb.usgs.gov/Prodesc/proddesc_118545.htm"
FULL_DATABASE_DOI = "10.5066/P1DC4XFG"
EARTH_SURFACE_DOI = "10.5066/P146VGVM"
DATA_REPORT_DOI = "10.3133/dr1210"
SOURCE_MAP_ID = "map50"
EXPECTED_BASE_SOURCE_UNITS = 185
EXPECTED_BASE_POLYGONS = 9500
BASE_ASSET = ROOT / "app/src/main/assets/rockmap-cngm-stage2-debug.db.gz"
BASE_MANIFEST = ROOT / "app/src/main/assets/rockmap-cngm-stage2-debug.json"
USER_AGENT = "RockMap-CNGM-search-authority/1.0"
MIN_ARCHIVE_BYTES = 1_000_000_000
MAX_ARCHIVE_BYTES = 12_000_000_000
MAX_EXTRACTED_BYTES = 48_000_000_000

ALLOWED_HOSTS = {
    "ngmdb.usgs.gov", "data.usgs.gov", "pubs.usgs.gov", "www.usgs.gov", "usgs.gov",
    "sciencebase.gov", "www.sciencebase.gov", "doi.org", "prd-tnm.s3.amazonaws.com",
}

WANTED_PG_TABLES = [
    # Keep this extraction deliberately small. These are the only full-CNGM
    # relational tables required for authoritative Colorado age/material search.
    ("source", "source_descriptionofmapunits"),
    ("vocabularies", "agedict"),
    ("vocabularies", "geomaterialdict"),
    ("vocabularies", "lithologydict"),
    ("vocabularies", "confidencedict"),
    ("vocabularies", "proportiondict"),
    ("assignments", "age"),
    ("assignments", "lithology"),
]

MANDATORY_PG_TABLES = set(WANTED_PG_TABLES)


def safe_text(v: Any) -> str:
    return "" if v is None else str(v).strip()


def norm(v: Any) -> str:
    s = unicodedata.normalize("NFKD", safe_text(v)).replace("’", "'").replace("–", "-").replace("—", "-")
    return re.sub(r"[^a-z0-9]+", "", s.lower())


def norm_ws(v: Any) -> str:
    """Normalize insignificant whitespace only; do not change scientific wording."""
    return " ".join(safe_text(v).split())


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def is_allowed(url: str) -> bool:
    try:
        p = urllib.parse.urlparse(url)
    except ValueError:
        return False
    return p.scheme == "https" and (p.hostname or "").lower() in ALLOWED_HOSTS


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        absolute = urllib.parse.urljoin(req.full_url, newurl)
        if not is_allowed(absolute):
            raise urllib.error.URLError(f"Refusing redirect to non-whitelisted host: {absolute}")
        return super().redirect_request(req, fp, code, msg, headers, absolute)


def open_allowed(url: str, timeout: int = 180):
    if not is_allowed(url):
        raise RuntimeError(f"Refusing non-whitelisted URL: {url}")
    opener = urllib.request.build_opener(SafeRedirect())
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*", "Cache-Control": "no-cache"})
    return opener.open(req, timeout=timeout)


class Links(HTMLParser):
    def __init__(self):
        super().__init__(); self.items=[]; self.href=None; self.text=[]
    def handle_starttag(self, tag, attrs):
        if tag.lower()=="a": self.href=dict(attrs).get("href"); self.text=[]
    def handle_data(self, data):
        if self.href is not None: self.text.append(data)
    def handle_endtag(self, tag):
        if tag.lower()=="a" and self.href is not None:
            self.items.append((self.href, html.unescape(" ".join(self.text))))
            self.href=None; self.text=[]


def resolve_full_database_url() -> str:
    with open_allowed(PRODUCT_PAGE, 60) as r:
        raw = r.read(4 * 1024 * 1024)
    p = Links(); p.feed(raw.decode("utf-8", "replace"))
    matches=[]
    for href, label in p.items:
        label_n=" ".join(label.split()).lower()
        if "entire geospatial database" in label_n:
            u=urllib.parse.urljoin(PRODUCT_PAGE, href)
            if is_allowed(u): matches.append(u)
    matches=list(dict.fromkeys(matches))
    if len(matches)!=1:
        raise RuntimeError(f"Expected exactly one official Entire geospatial database link; found {len(matches)}")
    return matches[0]


def download(url: str, target: Path) -> Dict[str, Any]:
    h=hashlib.sha256(); total=0; started=time.monotonic(); target.parent.mkdir(parents=True, exist_ok=True)
    with open_allowed(url, 240) as r, target.open("wb") as out:
        final=r.geturl()
        if not is_allowed(final): raise RuntimeError(f"Final URL is not whitelisted: {final}")
        declared=r.headers.get("Content-Length")
        if declared:
            n=int(declared)
            if n < MIN_ARCHIVE_BYTES or n > MAX_ARCHIVE_BYTES:
                raise RuntimeError(f"Full CNGM archive Content-Length outside safe bounds: {n}")
        while True:
            chunk=r.read(8*1024*1024)
            if not chunk: break
            total += len(chunk)
            if total > MAX_ARCHIVE_BYTES: raise RuntimeError("Full CNGM archive exceeded fail-closed size limit")
            h.update(chunk); out.write(chunk)
            if total % (512*1024*1024) < len(chunk): print(f"Downloaded {total/(1024**3):.2f} GiB", flush=True)
    if total < MIN_ARCHIVE_BYTES: raise RuntimeError(f"Full CNGM archive implausibly small: {total}")
    return {"requested_url":url,"final_url":final,"bytes":total,"sha256":h.hexdigest(),"seconds":round(time.monotonic()-started,3)}


def run(cmd: Sequence[str], *, capture=False, env=None) -> str:
    print("+ " + " ".join(map(str,cmd)), flush=True)
    p=subprocess.run(list(map(str,cmd)), check=True, text=True,
                     stdout=subprocess.PIPE if capture else None,
                     stderr=subprocess.STDOUT if capture else None,
                     env=env)
    return p.stdout or ""


def safe_member(name: str) -> bool:
    p=Path(name)
    return not p.is_absolute() and ".." not in p.parts


def identify_payload(archive: Path, work: Path) -> Tuple[str, Path, List[str]]:
    """Return (kind,path,inventory). Extract only the likely database payload."""
    inventory=[]
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as z:
            infos=z.infolist(); inventory=[i.filename for i in infos]
            if any(not safe_member(i.filename) for i in infos): raise RuntimeError("Unsafe ZIP path in CNGM archive")
            total=sum(i.file_size for i in infos)
            if total > MAX_EXTRACTED_BYTES: raise RuntimeError("CNGM archive extracted size exceeds fail-closed limit")
            files=[i.filename for i in infos if not i.is_dir()]
            dump=[n for n in files if n.lower().endswith((".dump",".backup",".pgdump"))]
            sql=[n for n in files if n.lower().endswith(".sql")]
            sqlite=[n for n in files if n.lower().endswith((".gpkg",".sqlite",".db"))]
            gdb_roots=[]
            for n in files:
                parts=Path(n).parts
                for i,part in enumerate(parts):
                    if part.lower().endswith(".gdb"):
                        gdb_roots.append("/".join(parts[:i+1])); break
            gdb_roots=list(dict.fromkeys(gdb_roots))
            (work/"source-inventory.txt").write_text("\n".join(inventory)+"\n", encoding="utf-8")
            def extract_one(member: str) -> Path:
                out=work/Path(member).name
                with z.open(member) as src, out.open("wb") as dst:
                    shutil.copyfileobj(src,dst,length=8*1024*1024)
                return out
            if len(dump)==1:
                return "pg_dump",extract_one(dump[0]),inventory
            if len(sql)==1:
                return "sql",extract_one(sql[0]),inventory
            if len(sqlite)==1:
                return "sqlite",extract_one(sqlite[0]),inventory
            if len(gdb_roots)==1:
                root=gdb_roots[0]; dest=work/Path(root).name
                for info in infos:
                    if info.filename.startswith(root.rstrip("/")+"/"):
                        rel=Path(info.filename).relative_to(root)
                        tgt=dest/rel
                        if info.is_dir(): tgt.mkdir(parents=True,exist_ok=True)
                        else:
                            tgt.parent.mkdir(parents=True,exist_ok=True)
                            with z.open(info) as src, tgt.open("wb") as dst: shutil.copyfileobj(src,dst)
                return "gdb",dest,inventory
            raise RuntimeError("Could not identify exactly one supported database payload in official CNGM ZIP")
    if tarfile.is_tarfile(archive):
        with tarfile.open(archive) as t:
            members=t.getmembers(); inventory=[m.name for m in members]
            (work/"source-inventory.txt").write_text("\n".join(inventory)+"\n", encoding="utf-8")
            if any(not safe_member(m.name) for m in members): raise RuntimeError("Unsafe TAR path in CNGM archive")
            total=sum(m.size for m in members if m.isfile())
            if total > MAX_EXTRACTED_BYTES: raise RuntimeError("CNGM TAR extracted size exceeds fail-closed limit")
            files=[m.name for m in members if m.isfile()]
            cand=[n for n in files if n.lower().endswith((".dump",".backup",".pgdump",".sql",".gpkg",".sqlite",".db"))]
            if len(cand)!=1: raise RuntimeError("Could not identify exactly one database payload in official CNGM TAR")
            m=t.getmember(cand[0]); out=work/Path(cand[0]).name
            with t.extractfile(m) as src, out.open("wb") as dst: shutil.copyfileobj(src,dst)
            ext=out.suffix.lower(); kind="pg_dump" if ext in {".dump",".backup",".pgdump"} else ("sql" if ext==".sql" else "sqlite")
            return kind,out,inventory
    head=archive.open("rb").read(16)
    if head.startswith(b"PGDMP"): return "pg_dump",archive,inventory
    if head.startswith(b"SQLite format 3\x00"): return "sqlite",archive,inventory
    raise RuntimeError("Official full CNGM download is not a supported ZIP/TAR/PostgreSQL-dump/SQLite payload")


def read_base_pack() -> Dict[str, Any]:
    if not BASE_ASSET.is_file() or not BASE_MANIFEST.is_file(): raise RuntimeError("Stage 2 base assets missing from repo")
    manifest=json.loads(BASE_MANIFEST.read_text("utf-8"))
    with tempfile.TemporaryDirectory(prefix="cngm-base-") as td:
        db=Path(td)/"base.db"
        with gzip.open(BASE_ASSET,"rb") as src, db.open("wb") as dst: shutil.copyfileobj(src,dst)
        con=sqlite3.connect(db)
        try:
            meta=dict(con.execute("select key,value from metadata"))
            units=[{"source_unit_upstream_id":str(r[0]),"source_mapunit":r[1],"geomaterial":r[2],"source_age_text":r[3]} for r in con.execute(
                "select source_unit_upstream_id,source_mapunit,geomaterial,source_age_text from source_units order by source_mapunit")]
            polygons=con.execute("select count(*) from polygons").fetchone()[0]
        finally: con.close()
    if meta.get("source_map_id")!=SOURCE_MAP_ID or len(units)!=EXPECTED_BASE_SOURCE_UNITS or polygons!=EXPECTED_BASE_POLYGONS:
        raise RuntimeError("Stage 2 base pack no longer matches reviewed map50 checkpoint")
    return {"manifest":manifest,"metadata":meta,"units":units,"polygon_count":polygons,
            "asset_sha256":sha256_file(BASE_ASSET)}


def table_to_csv_postgres(dsn: str, schema: str, table: str, target: Path) -> None:
    sql=f'SELECT * FROM "{schema}"."{table}"'
    out=run(["psql",dsn,"-X","--csv","-P","footer=off","-v","ON_ERROR_STOP=1","-c",sql],capture=True)
    target.write_text(out,encoding="utf-8")


def select_pg_toc_entries(toc: str) -> Tuple[List[str], set[Tuple[str, str]]]:
    """Select only reviewed CNGM table-data entries from pg_restore's TOC."""
    wanted=set(WANTED_PG_TABLES)
    lines: List[str] = []
    found: set[Tuple[str, str]] = set()
    for line in toc.splitlines():
        if line.lstrip().startswith(";"):
            continue
        # Typical pg_restore -l row:
        # 1234; 0 0 TABLE DATA schema table owner
        m=re.search(r"\bTABLE DATA\s+(\S+)\s+(\S+)\s+",line)
        if not m:
            continue
        key=(m.group(1),m.group(2))
        if key in wanted:
            lines.append(line)
            found.add(key)
    missing_mandatory=sorted(MANDATORY_PG_TABLES-found)
    if missing_mandatory:
        raise RuntimeError(
            "Full CNGM PostgreSQL dump missing required TABLE DATA entries: "
            + ", ".join(f"{s}.{t}" for s,t in missing_mandatory)
        )
    return lines, found


def validate_postgres_target(dsn: str) -> None:
    # Fail before touching a multi-gigabyte pg_dump if the runner cannot actually
    # provide the geospatial extension the full CNGM dump may reference in pre-data.
    run(["psql",dsn,"-X","-v","ON_ERROR_STOP=1","-c","CREATE EXTENSION IF NOT EXISTS postgis;"])
    version=run(["psql",dsn,"-X","-At","-v","ON_ERROR_STOP=1","-c","SELECT postgis_version();"],capture=True).strip()
    if not version:
        raise RuntimeError("PostGIS extension probe returned no version")


def restore_pg_selected(dump: Path, dsn: str, outdir: Path) -> Dict[str,Path]:
    # Read and validate the dump TOC before altering PostgreSQL. This catches
    # pg_restore format/version incompatibility and missing CNGM tables as early
    # as possible after the source download.
    toc=run(["pg_restore","-l",str(dump)],capture=True)
    lines,found=select_pg_toc_entries(toc)
    listfile=outdir/"selected-toc.list"
    listfile.write_text("\n".join(lines)+"\n",encoding="utf-8")

    validate_postgres_target(dsn)

    # Restore schema/pre-data only. No national table rows are loaded here.
    # The full pre-data section is used because table defaults/types in a native
    # pg_dump can depend on database-level objects. Data restoration remains
    # strictly allowlisted to the eight reviewed nonspatial authority tables.
    run(["pg_restore","--section=pre-data","--no-owner","--no-privileges","--exit-on-error","-d",dsn,str(dump)])
    run(["pg_restore","--data-only","--no-owner","--no-privileges","--exit-on-error","-L",str(listfile),"-d",dsn,str(dump)])

    csvs={}
    for schema,table in sorted(found):
        p=outdir/f"{schema}__{table}.csv"
        table_to_csv_postgres(dsn,schema,table,p)
        if not p.is_file() or p.stat().st_size <= 0:
            raise RuntimeError(f"PostgreSQL export produced an empty file for {schema}.{table}")
        csvs[f"{schema}.{table}"]=p
    return csvs


def sqlite_table_map(path: Path) -> Dict[str,str]:
    con=sqlite3.connect(f"file:{path}?mode=ro",uri=True)
    try: names=[r[0] for r in con.execute("select name from sqlite_master where type in ('table','view')")]
    finally: con.close()
    return {norm(n):n for n in names}


def export_sqlite_tables(path: Path, outdir: Path) -> Dict[str,Path]:
    mapping=sqlite_table_map(path); csvs={}
    aliases={f"{s}.{t}":[f"{s}_{t}",f"{s}.{t}",t] for s,t in WANTED_PG_TABLES}
    con=sqlite3.connect(f"file:{path}?mode=ro",uri=True)
    try:
        for key,cands in aliases.items():
            actual=None
            for c in cands:
                if norm(c) in mapping: actual=mapping[norm(c)]; break
            if not actual: continue
            cur=con.execute(f'SELECT * FROM "{actual}"'); p=outdir/(key.replace(".","__")+".csv")
            with p.open("w",encoding="utf-8",newline="") as f:
                w=csv.writer(f); w.writerow([d[0] for d in cur.description]); w.writerows(cur)
            csvs[key]=p
    finally: con.close()
    return csvs


def export_gdb_tables(gdb: Path, outdir: Path) -> Dict[str,Path]:
    inv=json.loads(run(["ogrinfo","-ro","-json",str(gdb)],capture=True))
    names=[safe_text(x.get("name")) for x in inv.get("layers",[]) if isinstance(x,dict)]
    by={norm(n):n for n in names}; csvs={}
    aliases={f"{s}.{t}":[f"{s}_{t}",t] for s,t in WANTED_PG_TABLES}
    for key,cands in aliases.items():
        actual=next((by[norm(c)] for c in cands if norm(c) in by),None)
        if not actual: continue
        p=outdir/(key.replace(".","__")+".csv")
        run(["ogr2ogr","-overwrite","-f","CSV",str(p),str(gdb),actual,"-lco","LINEFORMAT=LF"])
        csvs[key]=p
    return csvs


def read_csv(path: Path) -> Tuple[List[str],List[Dict[str,str]]]:
    with path.open("r",encoding="utf-8-sig",newline="") as f:
        r=csv.DictReader(f); fields=r.fieldnames or []
        return fields,[{k:safe_text(v) for k,v in row.items()} for row in r]


def idx(fields: Sequence[str]) -> Dict[str,str]: return {norm(x):x for x in fields}

def pick(row: Mapping[str,Any], ix: Mapping[str,str], *names: str) -> str:
    for n in names:
        a=ix.get(norm(n))
        if a is not None: return safe_text(row.get(a))
    return ""


def table(csvs: Mapping[str,Path], key: str, required=True):
    p=csvs.get(key)
    if not p:
        if required: raise RuntimeError(f"Required extracted table missing: {key}")
        return [],[],{}
    f,r=read_csv(p); return f,r,idx(f)


def require_columns(key: str, fields: Sequence[str], required_groups: Sequence[Sequence[str]]) -> None:
    """Fail with a clear schema error before interpreting any scientific rows."""
    normalized={norm(x) for x in fields}
    missing=[]
    for aliases in required_groups:
        if not any(norm(a) in normalized for a in aliases):
            missing.append("/".join(aliases))
    if missing:
        raise RuntimeError(
            f"Required columns missing from {key}: " + ", ".join(missing)
        )


def build_authority(csvs: Mapping[str,Path], base: Mapping[str,Any], outdb: Path, summary: Dict[str,Any]) -> None:
    source_fields,source_rows,source_ix=table(csvs,"source.source_descriptionofmapunits")
    age_fields,age_rows,age_ix=table(csvs,"vocabularies.agedict")
    gm_fields,gm_rows,gm_ix=table(csvs,"vocabularies.geomaterialdict")
    lith_fields,lith_rows,lith_ix=table(csvs,"vocabularies.lithologydict")
    conf_fields,conf_rows,conf_ix=table(csvs,"vocabularies.confidencedict")
    prop_fields,prop_rows,prop_ix=table(csvs,"vocabularies.proportiondict")
    aa_fields,aa_rows,aa_ix=table(csvs,"assignments.age")
    la_fields,la_rows,la_ix=table(csvs,"assignments.lithology")

    # These field names and relationships are documented by USGS Data Report
    # 1210. Fail before interpretation if the released schema does not match.
    require_columns("source.source_descriptionofmapunits",source_fields,[
        ("source_descriptionofmapunits_id","source_descriptionofmapunitsid"),
        ("source_mapunit",),("mapsourceid",),("age",),("geomaterial",),
    ])
    require_columns("vocabularies.agedict",age_fields,[
        ("agedict_id","agedictid"),("age",),("hierarchykey","hierarchy_key"),
    ])
    require_columns("vocabularies.geomaterialdict",gm_fields,[
        ("geomaterialdict_id","geomaterialdictid"),("geomaterial",),
        ("hierarchykey","hierarchy_key"),
    ])
    require_columns("vocabularies.lithologydict",lith_fields,[
        ("lithologydict_id","lithologydictid"),("lithology",),
        ("hierarchykey","hierarchy_key"),
    ])
    require_columns("vocabularies.confidencedict",conf_fields,[
        ("confidencedict_id","confidencedictid"),("confidence",),
    ])
    require_columns("vocabularies.proportiondict",prop_fields,[
        ("proportiondict_id","proportiondictid"),("proportion",),
    ])
    require_columns("assignments.age",aa_fields,[
        ("age_id","ageid"),
        ("source_descriptionofmapunitsid","source_descriptionofmapunits_id"),
        ("source_mapunit",),("agedictid_min",),("agedictid_max",),
        ("confidencedictid_min",),("confidencedictid_max",),
    ])
    require_columns("assignments.lithology",la_fields,[
        ("lithology_id","lithologyid"),
        ("source_descriptionofmapunitsid","source_descriptionofmapunits_id"),
        ("source_mapunit",),("lithologydictid",),("confidencedictid",),
        ("proportiondictid",),
    ])

    base_units=base["units"]
    wanted={u["source_mapunit"] for u in base_units}
    if len(wanted)!=EXPECTED_BASE_SOURCE_UNITS:
        raise RuntimeError("Base source_mapunit set is not unique")

    def dict_by_id(rows,ix,id_names,term_names,label):
        out={}
        for r in rows:
            rid=pick(r,ix,*id_names)
            term=pick(r,ix,*term_names)
            if not rid:
                continue
            previous=out.get(rid)
            if previous is not None and previous!=(term,r):
                raise RuntimeError(f"Duplicate/conflicting {label} id {rid}")
            out[rid]=(term,r)
        if not out:
            raise RuntimeError(f"Authoritative {label} vocabulary was empty")
        return out

    ages=dict_by_id(age_rows,age_ix,("agedict_id","agedictid"),("age",),"age")
    liths=dict_by_id(lith_rows,lith_ix,("lithologydict_id","lithologydictid"),("lithology",),"lithology")
    confs=dict_by_id(conf_rows,conf_ix,("confidencedict_id","confidencedictid"),("confidence",),"confidence")
    props=dict_by_id(prop_rows,prop_ix,("proportiondict_id","proportiondictid"),("proportion",),"proportion")

    geomaterial_by_term={}
    geomaterial_by_id={}
    for r in gm_rows:
        gid=pick(r,gm_ix,"geomaterialdict_id","geomaterialdictid")
        term=pick(r,gm_ix,"geomaterial")
        if not gid or not term:
            continue
        if gid in geomaterial_by_id:
            raise RuntimeError(f"Duplicate GeoMaterial dictionary id {gid}")
        if term in geomaterial_by_term:
            raise RuntimeError(f"Duplicate GeoMaterial term {term!r}")
        geomaterial_by_id[gid]=r
        geomaterial_by_term[term]=(gid,r)
    if not geomaterial_by_term:
        raise RuntimeError("Authoritative GeoMaterial vocabulary was empty")

    # The full relational CNGM source_mapunit is globally unique because USGS
    # prepends mapsourceid. Use it as the scientific linkage key. Do not compare
    # Earth-Surface exported row IDs to full-database IDs; they are preserved as
    # separate namespaces.
    full_source_by_mapunit={}
    full_source_by_id={}
    for r in source_rows:
        sm=pick(r,source_ix,"source_mapunit")
        sid=pick(r,source_ix,"source_descriptionofmapunits_id","source_descriptionofmapunitsid")
        if not sm or not sid:
            continue
        if sm in full_source_by_mapunit:
            raise RuntimeError(f"Duplicate source_mapunit in full CNGM source table: {sm}")
        if sid in full_source_by_id:
            raise RuntimeError(f"Duplicate source_descriptionofmapunits_id in full CNGM source table: {sid}")
        full_source_by_mapunit[sm]=r
        full_source_by_id[sid]=r

    missing_source=sorted(wanted-set(full_source_by_mapunit))
    if missing_source:
        raise RuntimeError(
            "Reviewed map50 source units were missing from the full CNGM source table; "
            f"first examples: {missing_source[:12]}"
        )

    source_mismatches=[]
    full_source_id_for={}
    for u in base_units:
        sm=u["source_mapunit"]
        r=full_source_by_mapunit[sm]
        sid=pick(r,source_ix,"source_descriptionofmapunits_id","source_descriptionofmapunitsid")
        mapsourceid=pick(r,source_ix,"mapsourceid")
        full_source_id_for[sm]=sid
        if mapsourceid != "50":
            source_mismatches.append((sm,"mapsourceid",mapsourceid,"50"))
        full_age=pick(r,source_ix,"age")
        full_gm=pick(r,source_ix,"geomaterial")
        if norm_ws(full_age) != norm_ws(u["source_age_text"]):
            source_mismatches.append((sm,"age",full_age,u["source_age_text"]))
        if norm_ws(full_gm) != norm_ws(u["geomaterial"]):
            source_mismatches.append((sm,"geomaterial",full_gm,u["geomaterial"]))
    if source_mismatches:
        raise RuntimeError(
            "Full CNGM source facts did not match the reviewed Earth Surface map50 checkpoint; "
            f"first examples: {source_mismatches[:8]}"
        )

    wanted_full_ids={full_source_id_for[sm]:sm for sm in wanted}

    def resolve_assignment_source(row,ix,label):
        sm=pick(row,ix,"source_mapunit")
        sid=pick(row,ix,"source_descriptionofmapunitsid","source_descriptionofmapunits_id")
        by_id=wanted_full_ids.get(sid) if sid else None
        by_key=sm if sm in wanted else None
        if by_id and by_key and by_id != by_key:
            raise RuntimeError(
                f"{label} assignment source foreign keys disagree: "
                f"source_mapunit={sm!r}, source_descriptionofmapunitsid={sid!r}"
            )
        target=by_key or by_id
        if target is None:
            return None
        expected_sid=full_source_id_for[target]
        if sid and sid != expected_sid:
            raise RuntimeError(
                f"{label} assignment full-database source ID mismatch for {target}: "
                f"{sid} != {expected_sid}"
            )
        if sm and sm != target:
            raise RuntimeError(
                f"{label} assignment source_mapunit mismatch for full-database source ID {sid}: "
                f"{sm!r} != {target!r}"
            )
        return target

    age_assign=[]
    age_seen_source=set()
    age_seen_id=set()
    for r in aa_rows:
        sm=resolve_assignment_source(r,aa_ix,"Age")
        if sm is None:
            continue
        assignment_id=pick(r,aa_ix,"age_id","ageid")
        if not assignment_id:
            raise RuntimeError(f"Age assignment for {sm} has no age_id")
        if assignment_id in age_seen_id:
            raise RuntimeError(f"Duplicate age_id in selected Colorado assignments: {assignment_id}")
        if sm in age_seen_source:
            raise RuntimeError(
                f"Full CNGM assignments.age violated documented one-to-one relationship for {sm}"
            )
        age_seen_id.add(assignment_id)
        age_seen_source.add(sm)

        sid=pick(r,aa_ix,"source_descriptionofmapunitsid","source_descriptionofmapunits_id")
        amin=pick(r,aa_ix,"agedictid_min")
        amax=pick(r,aa_ix,"agedictid_max")
        cmin=pick(r,aa_ix,"confidencedictid_min")
        cmax=pick(r,aa_ix,"confidencedictid_max")
        if amin and amin not in ages:
            raise RuntimeError(f"Age assignment references unknown agedict id {amin}")
        if amax and amax not in ages:
            raise RuntimeError(f"Age assignment references unknown agedict id {amax}")
        if cmin and cmin not in confs:
            raise RuntimeError(f"Age assignment references unknown confidence id {cmin}")
        if cmax and cmax not in confs:
            raise RuntimeError(f"Age assignment references unknown confidence id {cmax}")
        age_assign.append((assignment_id,sm,sid,amin,amax,cmin,cmax))

    lith_assign=[]
    lith_seen_id=set()
    for r in la_rows:
        sm=resolve_assignment_source(r,la_ix,"Lithology")
        if sm is None:
            continue
        assignment_id=pick(r,la_ix,"lithology_id","lithologyid")
        if not assignment_id:
            raise RuntimeError(f"Lithology assignment for {sm} has no lithology_id")
        if assignment_id in lith_seen_id:
            raise RuntimeError(f"Duplicate lithology_id in selected Colorado assignments: {assignment_id}")
        lith_seen_id.add(assignment_id)

        sid=pick(r,la_ix,"source_descriptionofmapunitsid","source_descriptionofmapunits_id")
        lid=pick(r,la_ix,"lithologydictid")
        cid=pick(r,la_ix,"confidencedictid")
        pid=pick(r,la_ix,"proportiondictid")
        if not lid:
            raise RuntimeError(f"Lithology assignment {assignment_id} for {sm} has no lithologydictid")
        if lid not in liths:
            raise RuntimeError(f"Lithology assignment references unknown lithologydict id {lid}")
        if cid and cid not in confs:
            raise RuntimeError(f"Lithology assignment references unknown confidence id {cid}")
        if pid and pid not in props:
            raise RuntimeError(f"Lithology assignment references unknown proportion id {pid}")
        lith_assign.append((assignment_id,sm,sid,lid,cid,pid))

    if outdb.exists():
        outdb.unlink()
    con=sqlite3.connect(outdb)
    try:
        con.executescript("""
        PRAGMA page_size=4096;
        PRAGMA journal_mode=OFF;
        PRAGMA synchronous=OFF;
        PRAGMA foreign_keys=ON;

        CREATE TABLE metadata(
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE base_source_units(
          source_mapunit TEXT PRIMARY KEY,
          earth_surface_source_unit_id TEXT NOT NULL,
          full_db_source_unit_id TEXT NOT NULL UNIQUE,
          map_source_id TEXT NOT NULL,
          source_geomaterial_text TEXT NOT NULL,
          source_age_text TEXT NOT NULL,
          full_db_geomaterial_text TEXT NOT NULL,
          full_db_age_text TEXT NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE age_concepts(
          concept_id TEXT PRIMARY KEY,
          term TEXT NOT NULL,
          hierarchy_key TEXT NOT NULL,
          definition TEXT NOT NULL,
          indented_name TEXT NOT NULL,
          rank TEXT NOT NULL,
          t_min_ma REAL,
          t_max_ma REAL,
          baseage TEXT NOT NULL,
          notes TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE INDEX idx_age_concepts_term ON age_concepts(term COLLATE NOCASE);
        CREATE INDEX idx_age_concepts_hierarchy ON age_concepts(hierarchy_key);

        CREATE TABLE geomaterial_concepts(
          concept_id TEXT PRIMARY KEY,
          term TEXT NOT NULL UNIQUE,
          hierarchy_key TEXT NOT NULL,
          definition TEXT NOT NULL,
          indented_name TEXT NOT NULL,
          notes TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE INDEX idx_gm_hierarchy ON geomaterial_concepts(hierarchy_key);

        CREATE TABLE lithology_concepts(
          concept_id TEXT PRIMARY KEY,
          term TEXT NOT NULL,
          hierarchy_key TEXT NOT NULL,
          definition TEXT NOT NULL,
          indented_name TEXT NOT NULL,
          notes TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE INDEX idx_lith_term ON lithology_concepts(term COLLATE NOCASE);
        CREATE INDEX idx_lith_hierarchy ON lithology_concepts(hierarchy_key);

        CREATE TABLE confidence_concepts(
          concept_id TEXT PRIMARY KEY,
          term TEXT NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE proportion_concepts(
          concept_id TEXT PRIMARY KEY,
          term TEXT NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE source_geomaterial(
          source_mapunit TEXT PRIMARY KEY REFERENCES base_source_units(source_mapunit),
          concept_id TEXT NOT NULL REFERENCES geomaterial_concepts(concept_id),
          term TEXT NOT NULL,
          hierarchy_key TEXT NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE age_assignments(
          assignment_id TEXT PRIMARY KEY,
          source_mapunit TEXT NOT NULL UNIQUE REFERENCES base_source_units(source_mapunit),
          full_db_source_unit_id TEXT NOT NULL,
          min_concept_id TEXT REFERENCES age_concepts(concept_id),
          max_concept_id TEXT REFERENCES age_concepts(concept_id),
          min_term TEXT NOT NULL,
          max_term TEXT NOT NULL,
          min_hierarchy_key TEXT NOT NULL,
          max_hierarchy_key TEXT NOT NULL,
          min_confidence_id TEXT REFERENCES confidence_concepts(concept_id),
          max_confidence_id TEXT REFERENCES confidence_concepts(concept_id),
          min_confidence TEXT NOT NULL,
          max_confidence TEXT NOT NULL
        ) WITHOUT ROWID;

        CREATE TABLE lithology_assignments(
          assignment_id TEXT PRIMARY KEY,
          source_mapunit TEXT NOT NULL REFERENCES base_source_units(source_mapunit),
          full_db_source_unit_id TEXT NOT NULL,
          concept_id TEXT NOT NULL REFERENCES lithology_concepts(concept_id),
          term TEXT NOT NULL,
          hierarchy_key TEXT NOT NULL,
          confidence_id TEXT REFERENCES confidence_concepts(concept_id),
          confidence TEXT NOT NULL,
          proportion_id TEXT REFERENCES proportion_concepts(concept_id),
          proportion TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE INDEX idx_lith_assign_source ON lithology_assignments(source_mapunit);
        CREATE INDEX idx_lith_assign_term ON lithology_assignments(term COLLATE NOCASE);
        """)

        meta={
            "format_version":"2",
            "authority":"USGS CNGM full relational geospatial database",
            "full_database_doi":FULL_DATABASE_DOI,
            "earth_surface_doi":EARTH_SURFACE_DOI,
            "data_report_doi":DATA_REPORT_DOI,
            "source_map_id":SOURCE_MAP_ID,
            "base_source_units":str(len(wanted)),
            "base_polygons":str(base["polygon_count"]),
            "base_asset_sha256":base["asset_sha256"],
            "source_linkage_key":"source_mapunit",
            "production_release_approved":"false",
        }
        con.executemany("insert into metadata(key,value) values(?,?)",sorted(meta.items()))

        con.executemany(
            "insert into confidence_concepts values(?,?)",
            sorted((cid,term) for cid,(term,_) in confs.items())
        )
        con.executemany(
            "insert into proportion_concepts values(?,?)",
            sorted((pid,term) for pid,(term,_) in props.items())
        )

        base_rows=[]
        for u in base_units:
            sm=u["source_mapunit"]
            r=full_source_by_mapunit[sm]
            base_rows.append((
                sm,
                u["source_unit_upstream_id"],
                full_source_id_for[sm],
                pick(r,source_ix,"mapsourceid"),
                u["geomaterial"],
                u["source_age_text"],
                pick(r,source_ix,"geomaterial"),
                pick(r,source_ix,"age"),
            ))
        con.executemany("insert into base_source_units values(?,?,?,?,?,?,?,?)",base_rows)

        def real(x):
            try:
                return float(x) if safe_text(x) else None
            except (TypeError,ValueError):
                return None

        for r in age_rows:
            cid=pick(r,age_ix,"agedict_id","agedictid")
            term=pick(r,age_ix,"age")
            if not cid or not term:
                continue
            hierarchy=pick(r,age_ix,"hierarchykey","hierarchy_key")
            if not hierarchy:
                raise RuntimeError(f"Age concept {cid}/{term!r} has no hierarchykey")
            con.execute("insert into age_concepts values(?,?,?,?,?,?,?,?,?,?)",(
                cid,term,hierarchy,pick(r,age_ix,"definition"),
                pick(r,age_ix,"indentedname","indented_name"),pick(r,age_ix,"rank"),
                real(pick(r,age_ix,"t_min_ma")),real(pick(r,age_ix,"t_max_ma")),
                pick(r,age_ix,"baseage"),pick(r,age_ix,"notes")
            ))

        for term,(gid,r) in geomaterial_by_term.items():
            hierarchy=pick(r,gm_ix,"hierarchykey","hierarchy_key")
            if not hierarchy:
                raise RuntimeError(f"GeoMaterial concept {gid}/{term!r} has no hierarchykey")
            con.execute("insert into geomaterial_concepts values(?,?,?,?,?,?)",(
                gid,term,hierarchy,pick(r,gm_ix,"definition"),
                pick(r,gm_ix,"indentedname","indented_name"),pick(r,gm_ix,"notes")
            ))

        for cid,(term,r) in liths.items():
            if not term:
                raise RuntimeError(f"Lithology concept {cid} has no term")
            hierarchy=pick(r,lith_ix,"hierarchykey","hierarchy_key")
            if not hierarchy:
                raise RuntimeError(f"Lithology concept {cid}/{term!r} has no hierarchykey")
            con.execute("insert into lithology_concepts values(?,?,?,?,?,?)",(
                cid,term,hierarchy,pick(r,lith_ix,"definition"),
                pick(r,lith_ix,"indentedname","indented_name"),pick(r,lith_ix,"notes")
            ))

        missing_gm=[]
        for u in base_units:
            sm=u["source_mapunit"]
            term=u["geomaterial"]
            if not term:
                continue
            pair=geomaterial_by_term.get(term)
            if pair is None:
                missing_gm.append((sm,term))
                continue
            gid,r=pair
            con.execute(
                "insert into source_geomaterial values(?,?,?,?)",
                (sm,gid,term,pick(r,gm_ix,"hierarchykey","hierarchy_key"))
            )
        if missing_gm:
            raise RuntimeError(
                "Base source GeoMaterial terms missing from authoritative geomaterialdict: "
                + str(missing_gm[:8])
            )

        for assignment_id,sm,sid,amin,amax,cmin,cmax in age_assign:
            amin_term,amin_r=ages.get(amin,("",{}))
            amax_term,amax_r=ages.get(amax,("",{}))
            con.execute(
                "insert into age_assignments values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    assignment_id,sm,sid or full_source_id_for[sm],
                    amin or None,amax or None,
                    amin_term,amax_term,
                    pick(amin_r,age_ix,"hierarchykey","hierarchy_key") if amin_r else "",
                    pick(amax_r,age_ix,"hierarchykey","hierarchy_key") if amax_r else "",
                    cmin or None,cmax or None,
                    confs.get(cmin,("",{}))[0],confs.get(cmax,("",{}))[0],
                )
            )

        for assignment_id,sm,sid,lid,cid,pid in lith_assign:
            term,r=liths[lid]
            con.execute(
                "insert into lithology_assignments values(?,?,?,?,?,?,?,?,?,?)",
                (
                    assignment_id,sm,sid or full_source_id_for[sm],
                    lid,term,pick(r,lith_ix,"hierarchykey","hierarchy_key"),
                    cid or None,confs.get(cid,("",{}))[0],
                    pid or None,props.get(pid,("",{}))[0],
                )
            )

        con.commit()
        if con.execute("pragma quick_check").fetchone()[0].lower()!="ok":
            raise RuntimeError("Authority SQLite quick_check failed")
        fk_errors=con.execute("pragma foreign_key_check").fetchall()
        if fk_errors:
            raise RuntimeError(f"Authority SQLite foreign-key check failed: {fk_errors[:8]}")

        counts={t:con.execute(f"select count(*) from {t}").fetchone()[0] for t in [
            "base_source_units","age_concepts","geomaterial_concepts","lithology_concepts",
            "confidence_concepts","proportion_concepts","source_geomaterial",
            "age_assignments","lithology_assignments",
        ]}
        age_terms={safe_text(r[0]).lower() for r in con.execute("select term from age_concepts")}
        summary["authority_counts"]=counts
        summary["coverage"]={
            "base_source_units":len(wanted),
            "full_source_units_exactly_matched":len(wanted),
            "age_assigned_source_units":con.execute("select count(*) from age_assignments").fetchone()[0],
            "geomaterial_assigned_source_units":con.execute("select count(*) from source_geomaterial").fetchone()[0],
            "lithology_assigned_source_units":con.execute(
                "select count(distinct source_mapunit) from lithology_assignments"
            ).fetchone()[0],
        }
        summary["probe_terms"]={
            x:(x in age_terms)
            for x in ["precambrian","cenozoic","paleogene","neogene","proterozoic","archean","cretaceous"]
        }
        summary["age_assignment_missing_source_mapunits"]=sorted(wanted-age_seen_source)
        summary["lithology_assignment_missing_source_mapunits"]=sorted(
            wanted-{r[1] for r in lith_assign}
        )
    finally:
        con.close()


def write_summary(outdir: Path, summary: Mapping[str,Any], db: Path) -> None:
    s=dict(summary)
    s["authority_db"]={
        "file":db.name,
        "bytes":db.stat().st_size,
        "sha256":sha256_file(db),
    }
    (outdir/"search-authority-summary.json").write_text(
        json.dumps(s,indent=2,sort_keys=True)+"\n",
        encoding="utf-8",
    )
    cov=s.get("coverage",{})
    probes=s.get("probe_terms",{})
    md=[
        "# RockMap CNGM Colorado search-authority extraction",
        "",
        "**Artifact-only scientific checkpoint. Not a production release.**",
        "",
        f"- Full relational source DOI: {FULL_DATABASE_DOI}",
        f"- Earth Surface DOI: {EARTH_SURFACE_DOI}",
        f"- Data Report: {DATA_REPORT_DOI}",
        f"- Reviewed source map: {SOURCE_MAP_ID}",
        f"- Base Colorado source units: {cov.get('base_source_units','?')}",
        f"- Full-CNGM source units exactly matched: {cov.get('full_source_units_exactly_matched','?')}",
        f"- Units with authoritative CNGM age assignments: {cov.get('age_assigned_source_units','?')}",
        f"- Units with GeMS/CNGM GeoMaterial links: {cov.get('geomaterial_assigned_source_units','?')}",
        f"- Units with authoritative CNGM lithology assignments: {cov.get('lithology_assigned_source_units','?')}",
        "",
        "## Probe terms present in CNGM agedict",
        "",
    ]
    for k,v in probes.items():
        md.append(f"- {k}: {'YES' if v else 'NO'}")
    md += [
        "",
        "## Scientific rules",
        "",
        "- No RockMap-generated age or lithology relationships are added.",
        "- Assignments are retained only for the 185 reviewed map50 source units used by the Stage 2 Colorado Earth Surface pack.",
        "- The full CNGM source table must contain all 185 source_mapunit keys and must agree with the reviewed source age and GeoMaterial text.",
        "- Earth Surface exported row IDs and full-database row IDs are preserved as separate identifier namespaces.",
        "- Original source facts remain separate from CNGM standardized assignments.",
        "- Missing/ambiguous source relationships are reported rather than inferred.",
        "",
    ]
    (outdir/"summary.md").write_text("\n".join(md),encoding="utf-8")
    files=[db,outdir/"search-authority-summary.json",outdir/"summary.md"]
    (outdir/"SHA256SUMS.txt").write_text(
        "".join(f"{sha256_file(p)}  {p.name}\n" for p in files),
        encoding="utf-8",
    )


def write_table_schema_diagnostics(csvs: Mapping[str,Path], target: Path) -> None:
    payload={}
    for key,path in sorted(csvs.items()):
        try:
            with path.open("r",encoding="utf-8-sig",newline="") as f:
                reader=csv.reader(f)
                header=next(reader,[])
            payload[key]={
                "file":path.name,
                "bytes":path.stat().st_size,
                "columns":header,
            }
        except Exception as exc:
            payload[key]={"error":f"{type(exc).__name__}: {exc}"}
    target.write_text(json.dumps(payload,indent=2,sort_keys=True)+"\n",encoding="utf-8")


def write_failure_diagnostics(
    outdir: Optional[Path],
    *,
    stage: str,
    exc: BaseException,
    summary: Optional[Mapping[str,Any]]=None,
    csvs: Optional[Mapping[str,Path]]=None,
) -> None:
    if outdir is None:
        return
    outdir.mkdir(parents=True,exist_ok=True)
    payload={
        "stage":stage,
        "error_type":type(exc).__name__,
        "error":str(exc),
        "production_release_approved":False,
    }
    if summary:
        # Only safe scalar/list metadata; never include raw source rows or binaries.
        for key in (
            "full_database_doi","earth_surface_doi","data_report_doi",
            "source_map_id","download","payload_kind","payload_file",
            "archive_inventory_count","archive_inventory_sample",
            "base","extracted_tables",
        ):
            if key in summary:
                payload[key]=summary[key]
    (outdir/"failure-diagnostics.json").write_text(
        json.dumps(payload,indent=2,sort_keys=True)+"\n",
        encoding="utf-8",
    )
    if csvs:
        write_table_schema_diagnostics(csvs,outdir/"table-schema.json")


def self_test() -> int:
    """Regression checks that run before any multi-gigabyte source download."""
    own=Path(__file__).read_text(encoding="utf-8")

    # Regression 0: workflow YAML must never overwrite this Python file.
    assert own.startswith("#!/usr/bin/env python3")
    assert re.search(r"(?m)^\s*uses:\s+actions/upload-artifact@",own) is None
    assert re.search(r"(?m)^name:\s+Extract Colorado CNGM Search Authority\s*$",own) is None

    assert norm("Early Proterozoic")=="earlyproterozoic"
    assert norm_ws("  Early   Proterozoic ")=="Early Proterozoic"
    assert safe_member("x/y/file.dump")
    assert not safe_member("../bad")
    assert not safe_member("/abs")
    assert is_allowed("https://ngmdb.usgs.gov/example.zip")
    assert not is_allowed("http://ngmdb.usgs.gov/example.zip")
    assert not is_allowed("https://example.com/example.zip")

    # Regression 1: structured PostgreSQL TOC selection. Never parse quiet
    # human-formatted layer output and never restore unreviewed table rows.
    toc_lines=[]
    oid=1000
    for schema,table_name in WANTED_PG_TABLES:
        toc_lines.append(f"{oid}; 0 0 TABLE DATA {schema} {table_name} usgs")
        oid+=1
    toc_lines.append(f"{oid}; 0 0 TABLE DATA source mapunitpolys usgs")
    selected,found=select_pg_toc_entries("\n".join(toc_lines))
    assert found==MANDATORY_PG_TABLES
    assert ("source","mapunitpolys") not in found
    assert len(selected)==len(WANTED_PG_TABLES)

    # Regression 2: FileGDB fallback uses JSON enumeration and direct CSV
    # filenames—the two exact issues already encountered by the earlier CNGM
    # candidate workflow.
    assert re.search(r'run\(\["ogrinfo","-ro","-json",str\(gdb\)\]',own)
    assert re.search(r'run\(\["ogr2ogr","-overwrite","-f","CSV",str\(p\),str\(gdb\),actual',own)
    assert re.search(r"(?m)^\s*tmp_dir\.mkdir\(\)",own) is None

    # Regression 3: PostgreSQL path probes PostGIS before pg_restore.
    assert "validate_postgres_target(dsn)" in own
    assert "SELECT postgis_version();" in own

    # Build a complete synthetic authority pack with the exact field names
    # documented by USGS Data Report 1210.
    with tempfile.TemporaryDirectory(prefix="cngm-authority-selftest-") as td:
        root=Path(td)

        def write_csv_file(name: str, fields: Sequence[str], rows: Sequence[Mapping[str,Any]]) -> Path:
            p=root/name
            with p.open("w",encoding="utf-8",newline="") as f:
                w=csv.DictWriter(f,fieldnames=list(fields))
                w.writeheader()
                for row in rows:
                    w.writerow({k:safe_text(row.get(k)) for k in fields})
            return p

        base_units=[
            {
                "source_unit_upstream_id":f"earth-surface-{i+1}",
                "source_mapunit":f"50|TEST{i:03d}",
                "geomaterial":"Igneous rock",
                "source_age_text":"Early Proterozoic",
            }
            for i in range(EXPECTED_BASE_SOURCE_UNITS)
        ]

        source_rows=[
            {
                "source_descriptionofmapunits_id":str(5000+i),
                "source_mapunit":u["source_mapunit"],
                "mapsourceid":"50",
                "age":"Early Proterozoic",
                "geomaterial":"Igneous rock",
            }
            for i,u in enumerate(base_units)
        ]
        source_rows.append({
            "source_descriptionofmapunits_id":"9999",
            "source_mapunit":"31|OUTSIDE",
            "mapsourceid":"31",
            "age":"Cretaceous",
            "geomaterial":"Igneous rock",
        })

        csvs={}
        csvs["source.source_descriptionofmapunits"]=write_csv_file(
            "source_dmu.csv",
            ["source_descriptionofmapunits_id","source_mapunit","mapsourceid","age","geomaterial"],
            source_rows,
        )
        csvs["vocabularies.agedict"]=write_csv_file(
            "agedict.csv",
            ["agedict_id","age","hierarchykey","definition","indentedname","rank","t_min_ma","t_max_ma","baseage","notes"],
            [
                {"agedict_id":"1","age":"Precambrian","hierarchykey":"01","definition":"synthetic parent"},
                {"agedict_id":"2","age":"Proterozoic","hierarchykey":"01.01","definition":"synthetic child"},
                {"agedict_id":"3","age":"Cretaceous","hierarchykey":"02","definition":"synthetic age"},
            ],
        )
        csvs["vocabularies.geomaterialdict"]=write_csv_file(
            "geomaterial.csv",
            ["geomaterialdict_id","geomaterial","hierarchykey","definition","indentedname","notes"],
            [{"geomaterialdict_id":"40","geomaterial":"Igneous rock","hierarchykey":"01","definition":"synthetic material"}],
        )
        csvs["vocabularies.lithologydict"]=write_csv_file(
            "lithology.csv",
            ["lithologydict_id","lithology","hierarchykey","definition","indentedname","notes"],
            [{"lithologydict_id":"10","lithology":"granite","hierarchykey":"01.01","definition":"synthetic lithology"}],
        )
        csvs["vocabularies.confidencedict"]=write_csv_file(
            "confidence.csv",
            ["confidencedict_id","confidence","hierarchykey","definition","indentedname"],
            [{"confidencedict_id":"20","confidence":"certain","hierarchykey":"01","definition":"synthetic confidence"}],
        )
        csvs["vocabularies.proportiondict"]=write_csv_file(
            "proportion.csv",
            ["proportiondict_id","proportion","hierarchykey","definition","indentedname"],
            [{"proportiondict_id":"30","proportion":"major","hierarchykey":"01","definition":"synthetic proportion"}],
        )

        age_rows=[]
        lith_rows=[]
        for i,u in enumerate(base_units):
            sid=str(5000+i)
            age_rows.append({
                "age_id":f"A{i}",
                "source_descriptionofmapunitsid":sid,
                "source_mapunit":u["source_mapunit"],
                "agedictid_min":"2",
                "agedictid_max":"2",
                "confidencedictid_min":"20",
                "confidencedictid_max":"20",
            })
            lith_rows.append({
                "lithology_id":f"L{i}",
                "source_descriptionofmapunitsid":sid,
                "source_mapunit":u["source_mapunit"],
                "lithologydictid":"10",
                "confidencedictid":"20",
                "proportiondictid":"30",
            })

        # An unrelated map must never leak into the Colorado authority pack.
        age_rows.append({
            "age_id":"A-OUTSIDE",
            "source_descriptionofmapunitsid":"9999",
            "source_mapunit":"31|OUTSIDE",
            "agedictid_min":"3",
            "agedictid_max":"3",
            "confidencedictid_min":"20",
            "confidencedictid_max":"20",
        })
        lith_rows.append({
            "lithology_id":"L-OUTSIDE",
            "source_descriptionofmapunitsid":"9999",
            "source_mapunit":"31|OUTSIDE",
            "lithologydictid":"10",
            "confidencedictid":"20",
            "proportiondictid":"30",
        })

        # Preserve assignment row identity instead of assuming one row per
        # (source_mapunit,lithology concept). The published relationship is
        # many-to-one from lithology assignments to source units.
        lith_rows.append({
            "lithology_id":"L-DUP-CONCEPT",
            "source_descriptionofmapunitsid":"5000",
            "source_mapunit":"50|TEST000",
            "lithologydictid":"10",
            "confidencedictid":"20",
            "proportiondictid":"30",
        })

        csvs["assignments.age"]=write_csv_file(
            "age_assign.csv",
            [
                "age_id","source_descriptionofmapunitsid","source_mapunit",
                "agedictid_min","agedictid_max",
                "confidencedictid_min","confidencedictid_max",
            ],
            age_rows,
        )
        csvs["assignments.lithology"]=write_csv_file(
            "lith_assign.csv",
            [
                "lithology_id","source_descriptionofmapunitsid","source_mapunit",
                "lithologydictid","confidencedictid","proportiondictid",
            ],
            lith_rows,
        )

        summary={}
        outdb=root/"authority.db"
        build_authority(
            csvs,
            {
                "units":base_units,
                "polygon_count":EXPECTED_BASE_POLYGONS,
                "asset_sha256":"synthetic",
            },
            outdb,
            summary,
        )

        con=sqlite3.connect(f"file:{outdb}?mode=ro",uri=True)
        try:
            assert con.execute("pragma quick_check").fetchone()[0].lower()=="ok"
            assert con.execute("pragma foreign_key_check").fetchall()==[]
            assert con.execute("select count(*) from base_source_units").fetchone()[0]==EXPECTED_BASE_SOURCE_UNITS
            assert con.execute("select count(*) from age_assignments").fetchone()[0]==EXPECTED_BASE_SOURCE_UNITS
            assert con.execute("select count(distinct source_mapunit) from lithology_assignments").fetchone()[0]==EXPECTED_BASE_SOURCE_UNITS
            assert con.execute("select count(*) from lithology_assignments").fetchone()[0]==EXPECTED_BASE_SOURCE_UNITS+1
            assert con.execute("select count(*) from age_assignments where source_mapunit='31|OUTSIDE'").fetchone()[0]==0
            assert con.execute("select count(*) from lithology_assignments where source_mapunit='31|OUTSIDE'").fetchone()[0]==0
            ids=con.execute(
                "select earth_surface_source_unit_id,full_db_source_unit_id from base_source_units where source_mapunit='50|TEST000'"
            ).fetchone()
            assert ids==("earth-surface-1","5000")
        finally:
            con.close()

        assert summary["coverage"]["full_source_units_exactly_matched"]==EXPECTED_BASE_SOURCE_UNITS

        # Foreign-key disagreement must fail closed rather than silently choose
        # one identifier namespace.
        bad_age=list(age_rows)
        bad_age[0]=dict(bad_age[0])
        bad_age[0]["source_descriptionofmapunitsid"]="5001"
        bad_csvs=dict(csvs)
        bad_csvs["assignments.age"]=write_csv_file(
            "bad_age_assign.csv",
            [
                "age_id","source_descriptionofmapunitsid","source_mapunit",
                "agedictid_min","agedictid_max",
                "confidencedictid_min","confidencedictid_max",
            ],
            bad_age,
        )
        try:
            build_authority(
                bad_csvs,
                {
                    "units":base_units,
                    "polygon_count":EXPECTED_BASE_POLYGONS,
                    "asset_sha256":"synthetic",
                },
                root/"bad.db",
                {},
            )
        except RuntimeError as exc:
            assert "foreign keys disagree" in str(exc)
        else:
            raise AssertionError("Conflicting assignment foreign keys were not rejected")

    print("self-test: OK")
    return 0


def main() -> int:
    ap=argparse.ArgumentParser()
    ap.add_argument("--output-dir",default="dist-cngm-search-authority")
    ap.add_argument("--source-url",default="")
    ap.add_argument("--pg-dsn",default=os.environ.get("CNGM_PG_DSN",""))
    ap.add_argument("--self-test",action="store_true")
    args=ap.parse_args()
    if args.self_test:
        return self_test()

    outdir=Path(args.output_dir).resolve()
    summary: Dict[str,Any]={}
    csvs: Dict[str,Path]={}
    stage="initialize"

    try:
        shutil.rmtree(outdir,ignore_errors=True)
        outdir.mkdir(parents=True)
        work=outdir/"_work"
        work.mkdir()

        stage="read reviewed Stage 2 Colorado checkpoint"
        base=read_base_pack()

        stage="resolve official full CNGM download"
        url=args.source_url.strip() or resolve_full_database_url()

        stage="download official full CNGM source"
        archive=work/"cngm-full-download"
        dl=download(url,archive)

        stage="identify official CNGM payload"
        kind,payload,inventory=identify_payload(archive,work)

        summary={
            "production_release_approved":False,
            "full_database_doi":FULL_DATABASE_DOI,
            "earth_surface_doi":EARTH_SURFACE_DOI,
            "data_report_doi":DATA_REPORT_DOI,
            "source_map_id":SOURCE_MAP_ID,
            "download":dl,
            "payload_kind":kind,
            "payload_file":payload.name,
            "archive_inventory_count":len(inventory),
            "archive_inventory_sample":inventory[:100],
            "base":{
                "source_units":len(base["units"]),
                "polygons":base["polygon_count"],
                "asset_sha256":base["asset_sha256"],
            },
        }

        tables_dir=work/"tables"
        tables_dir.mkdir()

        stage=f"extract reviewed authority tables from {kind}"
        if kind=="pg_dump":
            if not args.pg_dsn:
                raise RuntimeError(
                    "PostgreSQL dump detected but --pg-dsn/CNGM_PG_DSN was not provided"
                )
            csvs=restore_pg_selected(payload,args.pg_dsn,tables_dir)
        elif kind=="sqlite":
            csvs=export_sqlite_tables(payload,tables_dir)
        elif kind=="gdb":
            csvs=export_gdb_tables(payload,tables_dir)
        elif kind=="sql":
            raise RuntimeError(
                "Plain SQL full-database payload detected. Refusing to load the "
                "entire national database without a reviewed selective restore path."
            )
        else:
            raise RuntimeError(f"Unsupported payload kind: {kind}")

        summary["extracted_tables"]=sorted(csvs)
        write_table_schema_diagnostics(csvs,outdir/"table-schema.json")

        missing=sorted(MANDATORY_PG_TABLES-{tuple(k.split(".",1)) for k in csvs})
        if missing:
            raise RuntimeError(
                "Official full CNGM payload did not expose all required authority tables: "
                + ", ".join(f"{s}.{t}" for s,t in missing)
            )

        stage="build Colorado authority SQLite"
        db=outdir/"cngm-colorado-search-authority-v2.db"
        build_authority(csvs,base,db,summary)

        stage="write and verify authority summary"
        write_summary(outdir,summary,db)

        # Keep the small table-schema diagnostic in the successful artifact, but
        # remove the multi-gigabyte raw source/payload before upload.
        shutil.rmtree(work,ignore_errors=True)
        print((outdir/"summary.md").read_text("utf-8"))
        return 0

    except Exception as exc:
        write_failure_diagnostics(
            outdir,
            stage=stage,
            exc=exc,
            summary=summary,
            csvs=csvs,
        )
        print(f"FAILED_STAGE: {stage}",file=sys.stderr)
        raise


if __name__=="__main__":
    try: raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}",file=sys.stderr)
        raise
