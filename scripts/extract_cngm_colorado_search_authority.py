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
MAX_EXTRACTED_BYTES = 18_000_000_000

ALLOWED_HOSTS = {
    "ngmdb.usgs.gov", "data.usgs.gov", "pubs.usgs.gov", "www.usgs.gov", "usgs.gov",
    "sciencebase.gov", "www.sciencebase.gov", "doi.org", "prd-tnm.s3.amazonaws.com",
}

WANTED_PG_TABLES = [
    ("source", "source_descriptionofmapunits"),
    ("source", "mapsources"),
    ("vocabularies", "agedict"),
    ("vocabularies", "geomaterialdict"),
    ("vocabularies", "lithologydict"),
    ("vocabularies", "confidencedict"),
    ("vocabularies", "proportiondict"),
    ("vocabularies", "vocabularysources"),
    ("assignments", "age"),
    ("assignments", "lithology"),
    ("synthesis", "descriptionofmapunits"),
    ("synthesis", "synthesissources"),
]


def safe_text(v: Any) -> str:
    return "" if v is None else str(v).strip()


def norm(v: Any) -> str:
    s = unicodedata.normalize("NFKD", safe_text(v)).replace("’", "'").replace("–", "-").replace("—", "-")
    return re.sub(r"[^a-z0-9]+", "", s.lower())


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


def restore_pg_selected(dump: Path, dsn: str, outdir: Path) -> Dict[str,Path]:
    # Restore all pre-data only: schemas/table definitions/types/extensions. No national spatial rows.
    run(["psql",dsn,"-X","-v","ON_ERROR_STOP=1","-c","CREATE EXTENSION IF NOT EXISTS postgis;"])
    run(["pg_restore","--section=pre-data","--no-owner","--no-privileges","--exit-on-error","-d",dsn,str(dump)])
    toc=run(["pg_restore","-l",str(dump)],capture=True)
    lines=[]
    wanted={(s,t) for s,t in WANTED_PG_TABLES}
    found=set()
    for line in toc.splitlines():
        if line.lstrip().startswith(";"): continue
        # Typical: 1234; 0 0 TABLE DATA schema table owner
        m=re.search(r"\bTABLE DATA\s+(\S+)\s+(\S+)\s+",line)
        if m and (m.group(1),m.group(2)) in wanted:
            lines.append(line); found.add((m.group(1),m.group(2)))
    missing=sorted(wanted-found)
    # synthesis tables are useful but not mandatory for core authority extraction.
    mandatory={("source","source_descriptionofmapunits"),("vocabularies","agedict"),("vocabularies","geomaterialdict"),
               ("vocabularies","lithologydict"),("vocabularies","confidencedict"),("vocabularies","proportiondict"),
               ("assignments","age"),("assignments","lithology")}
    missing_mandatory=sorted(mandatory-found)
    if missing_mandatory: raise RuntimeError("Full CNGM PostgreSQL dump missing required TABLE DATA entries: "+", ".join(f"{s}.{t}" for s,t in missing_mandatory))
    listfile=outdir/"selected-toc.list"; listfile.write_text("\n".join(lines)+"\n",encoding="utf-8")
    run(["pg_restore","--data-only","--no-owner","--no-privileges","--exit-on-error","-L",str(listfile),"-d",dsn,str(dump)])
    csvs={}
    for schema,table in sorted(found):
        p=outdir/f"{schema}__{table}.csv"; table_to_csv_postgres(dsn,schema,table,p); csvs[f"{schema}.{table}"]=p
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


def build_authority(csvs: Mapping[str,Path], base: Mapping[str,Any], outdb: Path, summary: Dict[str,Any]) -> None:
    _,age_rows,age_ix=table(csvs,"vocabularies.agedict")
    _,gm_rows,gm_ix=table(csvs,"vocabularies.geomaterialdict")
    _,lith_rows,lith_ix=table(csvs,"vocabularies.lithologydict")
    _,conf_rows,conf_ix=table(csvs,"vocabularies.confidencedict")
    _,prop_rows,prop_ix=table(csvs,"vocabularies.proportiondict")
    _,aa_rows,aa_ix=table(csvs,"assignments.age")
    _,la_rows,la_ix=table(csvs,"assignments.lithology")
    synth_fields,synth_rows,synth_ix=table(csvs,"synthesis.descriptionofmapunits",required=False)

    base_units=base["units"]; wanted={u["source_mapunit"] for u in base_units}
    if len(wanted)!=EXPECTED_BASE_SOURCE_UNITS: raise RuntimeError("Base source_mapunit set is not unique")

    def dict_by_id(rows,ix,id_names,term_names):
        out={}
        for r in rows:
            rid=pick(r,ix,*id_names); term=pick(r,ix,*term_names)
            if rid: out[rid]=(term,r)
        return out
    ages=dict_by_id(age_rows,age_ix,("agedict_id","agedictid"),("age",))
    liths=dict_by_id(lith_rows,lith_ix,("lithologydict_id","lithologydictid"),("lithology",))
    confs=dict_by_id(conf_rows,conf_ix,("confidencedict_id","confidencedictid"),("confidence",))
    props=dict_by_id(prop_rows,prop_ix,("proportiondict_id","proportiondictid"),("proportion",))
    gms={pick(r,gm_ix,"geomaterial"):r for r in gm_rows if pick(r,gm_ix,"geomaterial")}

    age_assign=[]
    for r in aa_rows:
        sm=pick(r,aa_ix,"source_mapunit")
        if sm not in wanted: continue
        amin=pick(r,aa_ix,"agedictid_min"); amax=pick(r,aa_ix,"agedictid_max")
        cmin=pick(r,aa_ix,"confidencedictid_min"); cmax=pick(r,aa_ix,"confidencedictid_max")
        if amin and amin not in ages: raise RuntimeError(f"Age assignment references unknown agedict id {amin}")
        if amax and amax not in ages: raise RuntimeError(f"Age assignment references unknown agedict id {amax}")
        age_assign.append((sm,amin,amax,cmin,cmax))
    lith_assign=[]
    for r in la_rows:
        sm=pick(r,la_ix,"source_mapunit")
        if sm not in wanted: continue
        lid=pick(r,la_ix,"lithologydictid"); cid=pick(r,la_ix,"confidencedictid"); pid=pick(r,la_ix,"proportiondictid")
        if lid and lid not in liths: raise RuntimeError(f"Lithology assignment references unknown lithologydict id {lid}")
        lith_assign.append((sm,lid,cid,pid))

    if outdb.exists(): outdb.unlink()
    con=sqlite3.connect(outdb)
    try:
        con.executescript("""
        PRAGMA page_size=4096; PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA foreign_keys=ON;
        CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE base_source_units(
          source_mapunit TEXT PRIMARY KEY, source_unit_upstream_id TEXT NOT NULL,
          source_geomaterial_text TEXT NOT NULL, source_age_text TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE age_concepts(
          concept_id TEXT PRIMARY KEY, term TEXT NOT NULL UNIQUE, hierarchy_key TEXT NOT NULL,
          definition TEXT NOT NULL, indented_name TEXT NOT NULL, rank TEXT NOT NULL,
          t_min_ma REAL, t_max_ma REAL, baseage TEXT NOT NULL, notes TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE INDEX idx_age_concepts_term ON age_concepts(term COLLATE NOCASE);
        CREATE INDEX idx_age_concepts_hierarchy ON age_concepts(hierarchy_key);
        CREATE TABLE geomaterial_concepts(
          term TEXT PRIMARY KEY, hierarchy_key TEXT NOT NULL, definition TEXT NOT NULL,
          indented_name TEXT NOT NULL, notes TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE INDEX idx_gm_hierarchy ON geomaterial_concepts(hierarchy_key);
        CREATE TABLE lithology_concepts(
          concept_id TEXT PRIMARY KEY, term TEXT NOT NULL UNIQUE, hierarchy_key TEXT NOT NULL,
          definition TEXT NOT NULL, indented_name TEXT NOT NULL, notes TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE INDEX idx_lith_term ON lithology_concepts(term COLLATE NOCASE);
        CREATE INDEX idx_lith_hierarchy ON lithology_concepts(hierarchy_key);
        CREATE TABLE source_geomaterial(
          source_mapunit TEXT PRIMARY KEY REFERENCES base_source_units(source_mapunit),
          term TEXT NOT NULL REFERENCES geomaterial_concepts(term), hierarchy_key TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE age_assignments(
          source_mapunit TEXT PRIMARY KEY REFERENCES base_source_units(source_mapunit), min_concept_id TEXT, max_concept_id TEXT,
          min_term TEXT NOT NULL, max_term TEXT NOT NULL, min_hierarchy_key TEXT NOT NULL, max_hierarchy_key TEXT NOT NULL,
          min_confidence TEXT NOT NULL, max_confidence TEXT NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE lithology_assignments(
          source_mapunit TEXT NOT NULL REFERENCES base_source_units(source_mapunit), concept_id TEXT NOT NULL, term TEXT NOT NULL, hierarchy_key TEXT NOT NULL,
          confidence TEXT NOT NULL, proportion TEXT NOT NULL,
          PRIMARY KEY(source_mapunit,concept_id)
        ) WITHOUT ROWID;
        CREATE INDEX idx_lith_assign_term ON lithology_assignments(term COLLATE NOCASE);
        CREATE TABLE synthesis_hierarchy(
          synthesis_id TEXT NOT NULL, mapunit TEXT NOT NULL, name TEXT NOT NULL, full_name TEXT NOT NULL,
          age_text TEXT NOT NULL, geomaterial TEXT NOT NULL, hierarchy_key TEXT NOT NULL,
          paragraph_style TEXT NOT NULL, synthesis_source_id TEXT NOT NULL
        );
        """)
        meta={
            "format_version":"1","authority":"USGS CNGM full relational geospatial database",
            "full_database_doi":FULL_DATABASE_DOI,"earth_surface_doi":EARTH_SURFACE_DOI,
            "data_report_doi":DATA_REPORT_DOI,"source_map_id":SOURCE_MAP_ID,
            "base_source_units":str(len(wanted)),"base_polygons":str(base["polygon_count"]),
            "base_asset_sha256":base["asset_sha256"],"production_release_approved":"false",
        }
        con.executemany("insert into metadata(key,value) values(?,?)",sorted(meta.items()))
        con.executemany(
            "insert into base_source_units values(?,?,?,?)",
            [(u["source_mapunit"],u["source_unit_upstream_id"],u["geomaterial"],u["source_age_text"]) for u in base_units]
        )
        for r in age_rows:
            cid=pick(r,age_ix,"agedict_id","agedictid"); term=pick(r,age_ix,"age")
            if not cid or not term: continue
            def real(x):
                try:return float(x) if safe_text(x) else None
                except:return None
            con.execute("insert into age_concepts values(?,?,?,?,?,?,?,?,?,?)",(
                cid,term,pick(r,age_ix,"hierarchykey","hierarchy_key"),pick(r,age_ix,"definition"),
                pick(r,age_ix,"indentedname","indented_name"),pick(r,age_ix,"rank"),
                real(pick(r,age_ix,"t_min_ma")),real(pick(r,age_ix,"t_max_ma")),
                pick(r,age_ix,"baseage"),pick(r,age_ix,"notes")))
        for term,r in gms.items():
            con.execute("insert into geomaterial_concepts values(?,?,?,?,?)",(
                term,pick(r,gm_ix,"hierarchykey","hierarchy_key"),pick(r,gm_ix,"definition"),
                pick(r,gm_ix,"indentedname","indented_name"),pick(r,gm_ix,"notes")))
        for cid,(term,r) in liths.items():
            con.execute("insert into lithology_concepts values(?,?,?,?,?,?)",(
                cid,term,pick(r,lith_ix,"hierarchykey","hierarchy_key"),pick(r,lith_ix,"definition"),
                pick(r,lith_ix,"indentedname","indented_name"),pick(r,lith_ix,"notes")))
        missing_gm=[]
        for u in base_units:
            term=u["geomaterial"]
            if not term: continue
            r=gms.get(term)
            if r is None: missing_gm.append((u["source_mapunit"],term)); continue
            con.execute("insert into source_geomaterial values(?,?,?)",(u["source_mapunit"],term,pick(r,gm_ix,"hierarchykey","hierarchy_key")))
        if missing_gm:
            raise RuntimeError("Base source GeoMaterial terms missing from authoritative geomaterialdict: "+str(missing_gm[:8]))
        for sm,amin,amax,cmin,cmax in age_assign:
            amin_term,amin_r=ages.get(amin,("",{})); amax_term,amax_r=ages.get(amax,("",{}))
            con.execute("insert into age_assignments values(?,?,?,?,?,?,?,?,?)",(
                sm,amin or None,amax or None,amin_term,amax_term,
                pick(amin_r,age_ix,"hierarchykey","hierarchy_key") if amin_r else "",
                pick(amax_r,age_ix,"hierarchykey","hierarchy_key") if amax_r else "",
                confs.get(cmin,("",{}))[0],confs.get(cmax,("",{}))[0]))
        for sm,lid,cid,pid in lith_assign:
            term,r=liths.get(lid,("",{}))
            if not lid or not term: continue
            con.execute("insert into lithology_assignments values(?,?,?,?,?,?)",(
                sm,lid,term,pick(r,lith_ix,"hierarchykey","hierarchy_key"),
                confs.get(cid,("",{}))[0],props.get(pid,("",{}))[0]))
        for r in synth_rows:
            con.execute("insert into synthesis_hierarchy values(?,?,?,?,?,?,?,?,?)",(
                pick(r,synth_ix,"descriptionofmapunits_id","descriptionofmapunitsid"),pick(r,synth_ix,"mapunit"),
                pick(r,synth_ix,"name"),pick(r,synth_ix,"fullname","full_name"),pick(r,synth_ix,"age"),
                pick(r,synth_ix,"geomaterial"),pick(r,synth_ix,"hierarchykey","hierarchy_key"),
                pick(r,synth_ix,"paragraphstyle","paragraph_style"),pick(r,synth_ix,"synthesissourceid","synthesis_source_id")))
        con.commit()
        if con.execute("pragma quick_check").fetchone()[0].lower()!="ok": raise RuntimeError("Authority SQLite quick_check failed")
        counts={t:con.execute(f"select count(*) from {t}").fetchone()[0] for t in [
            "base_source_units","age_concepts","geomaterial_concepts","lithology_concepts","source_geomaterial","age_assignments","lithology_assignments","synthesis_hierarchy"]}
        age_terms={r[0].lower() for r in con.execute("select term from age_concepts")}
        summary["authority_counts"]=counts
        summary["coverage"]={
            "base_source_units":len(wanted),
            "age_assigned_source_units":con.execute("select count(*) from age_assignments").fetchone()[0],
            "geomaterial_assigned_source_units":con.execute("select count(*) from source_geomaterial").fetchone()[0],
            "lithology_assigned_source_units":con.execute("select count(distinct source_mapunit) from lithology_assignments").fetchone()[0],
        }
        summary["probe_terms"]={x:(x in age_terms) for x in ["precambrian","cenozoic","paleogene","neogene","proterozoic","archean","cretaceous"]}
        summary["age_assignment_missing_source_mapunits"]=sorted(wanted-{r[0] for r in age_assign})
        summary["lithology_assignment_missing_source_mapunits"]=sorted(wanted-{r[0] for r in lith_assign})
    finally: con.close()


def write_summary(outdir: Path, summary: Mapping[str,Any], db: Path) -> None:
    s=dict(summary); s["authority_db"]={"file":db.name,"bytes":db.stat().st_size,"sha256":sha256_file(db)}
    (outdir/"search-authority-summary.json").write_text(json.dumps(s,indent=2,sort_keys=True)+"\n",encoding="utf-8")
    cov=s.get("coverage",{}); probes=s.get("probe_terms",{})
    md=["# RockMap CNGM Colorado search-authority extraction","",
        "**Artifact-only scientific checkpoint. Not a production release.**","",
        f"- Full relational source DOI: {FULL_DATABASE_DOI}",f"- Earth Surface DOI: {EARTH_SURFACE_DOI}",f"- Data Report: {DATA_REPORT_DOI}",
        f"- Reviewed source map: {SOURCE_MAP_ID}",f"- Base Colorado source units: {cov.get('base_source_units','?')}",
        f"- Units with authoritative CNGM age assignments: {cov.get('age_assigned_source_units','?')}",
        f"- Units with GeMS/CNGM GeoMaterial links: {cov.get('geomaterial_assigned_source_units','?')}",
        f"- Units with authoritative CNGM lithology assignments: {cov.get('lithology_assigned_source_units','?')}","",
        "## Probe terms present in CNGM agedict","" ]
    for k,v in probes.items(): md.append(f"- {k}: {'YES' if v else 'NO'}")
    md += ["","## Scientific rules","","- No RockMap-generated age or lithology relationships are added.",
           "- Assignments are retained only for the 185 reviewed map50 source units used by the Stage 2 Colorado Earth Surface pack.",
           "- Original source facts remain separate from CNGM standardized assignments.",
           "- Missing/ambiguous source relationships are reported rather than inferred.",""]
    (outdir/"summary.md").write_text("\n".join(md),encoding="utf-8")
    files=[db,outdir/"search-authority-summary.json",outdir/"summary.md"]
    (outdir/"SHA256SUMS.txt").write_text("".join(f"{sha256_file(p)}  {p.name}\n" for p in files),encoding="utf-8")


def self_test() -> int:
    # Validate helpers and safe path logic without downloading anything.
    assert norm("Early Proterozoic") == "earlyproterozoic"
    assert safe_member("x/y/file.dump") and not safe_member("../bad") and not safe_member("/abs")
    print("self-test: OK")
    return 0


def main() -> int:
    ap=argparse.ArgumentParser()
    ap.add_argument("--output-dir",default="dist-cngm-search-authority")
    ap.add_argument("--source-url",default="")
    ap.add_argument("--pg-dsn",default=os.environ.get("CNGM_PG_DSN",""))
    ap.add_argument("--self-test",action="store_true")
    args=ap.parse_args()
    if args.self_test: return self_test()
    outdir=Path(args.output_dir).resolve(); shutil.rmtree(outdir,ignore_errors=True); outdir.mkdir(parents=True)
    work=outdir/"_work"; work.mkdir()
    base=read_base_pack()
    url=args.source_url.strip() or resolve_full_database_url()
    archive=work/"cngm-full-download"
    dl=download(url,archive)
    kind,payload,inventory=identify_payload(archive,work)
    summary={"production_release_approved":False,"full_database_doi":FULL_DATABASE_DOI,"earth_surface_doi":EARTH_SURFACE_DOI,
             "data_report_doi":DATA_REPORT_DOI,"source_map_id":SOURCE_MAP_ID,"download":dl,"payload_kind":kind,
             "payload_file":payload.name,"archive_inventory_count":len(inventory),"archive_inventory_sample":inventory[:100],
             "base":{"source_units":len(base["units"]),"polygons":base["polygon_count"],"asset_sha256":base["asset_sha256"]}}
    tables_dir=work/"tables"; tables_dir.mkdir()
    if kind=="pg_dump":
        if not args.pg_dsn: raise RuntimeError("PostgreSQL dump detected but --pg-dsn/CNGM_PG_DSN was not provided")
        csvs=restore_pg_selected(payload,args.pg_dsn,tables_dir)
    elif kind=="sqlite": csvs=export_sqlite_tables(payload,tables_dir)
    elif kind=="gdb": csvs=export_gdb_tables(payload,tables_dir)
    elif kind=="sql":
        raise RuntimeError("Plain SQL full-database payload detected. Refusing to load the entire national database without a reviewed selective restore path.")
    else: raise RuntimeError(f"Unsupported payload kind: {kind}")
    summary["extracted_tables"]=sorted(csvs)
    db=outdir/"cngm-colorado-search-authority-v1.db"
    build_authority(csvs,base,db,summary)
    write_summary(outdir,summary,db)
    # Remove multi-GB source/payload before artifact upload.
    shutil.rmtree(work,ignore_errors=True)
    print((outdir/"summary.md").read_text("utf-8"))
    return 0

if __name__=="__main__":
    try: raise SystemExit(main())
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}",file=sys.stderr)
        raise
