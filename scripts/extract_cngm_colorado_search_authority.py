name: Extract Colorado CNGM Search Authority

on:
  push:
    branches:
      - tour-debug
    paths:
      - '.github/workflows/extract-cngm-colorado-search-authority.yml'
      - 'scripts/extract_cngm_colorado_search_authority.py'

  workflow_dispatch:
    inputs:
      source_url:
        description: 'Optional: official NGMDB Entire geospatial database URL. Leave blank to auto-resolve.'
        required: false
        type: string

permissions:
  contents: read

concurrency:
  group: rockmap-cngm-search-authority
  cancel-in-progress: false

jobs:
  extract-authority:
    name: Extract authoritative Colorado search relationships
    runs-on: ubuntu-24.04
    timeout-minutes: 210

    steps:
      - name: Checkout repository
        uses: actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803 # v6
        with:
          fetch-depth: 1

      - name: Validate RockMap source
        shell: bash
        run: python3 scripts/preflight.py

      - name: Free runner disk for official CNGM source
        shell: bash
        run: |
          set -euo pipefail

          # This workflow needs the official ~6.4 GB relational source only long
          # enough to extract a small nonspatial Colorado authority artifact.
          # Android/CodeQL/.NET toolchains are not used by this job.
          sudo rm -rf \
            /usr/local/lib/android \
            /opt/ghc \
            /usr/share/dotnet \
            /opt/hostedtoolcache/CodeQL \
            || true

          sudo apt-get clean
          df -h .

      - name: Install pinned extraction dependencies
        shell: bash
        run: |
          set -euo pipefail

          sudo apt-get update
          sudo apt-get install -y --no-install-recommends \
            postgresql \
            postgresql-contrib \
            postgis \
            gdal-bin

          python3 --version
          psql --version
          pg_restore --version
          ogrinfo --version
          ogr2ogr --version

      - name: Validate search-authority extractor
        shell: bash
        run: |
          set -euo pipefail

          python3 -m py_compile scripts/extract_cngm_colorado_search_authority.py
          python3 scripts/extract_cngm_colorado_search_authority.py --self-test

      - name: Prepare isolated PostgreSQL restore target
        shell: bash
        run: |
          set -euo pipefail

          sudo service postgresql start
          sudo -u postgres createuser --superuser "$USER" 2>/dev/null || true

          dropdb --if-exists cngm_search_extract
          createdb cngm_search_extract

          psql -X -v ON_ERROR_STOP=1 postgresql:///cngm_search_extract \
            -c 'CREATE EXTENSION IF NOT EXISTS postgis;'

      - name: Check disk before official source download
        shell: bash
        run: |
          set -euo pipefail

          df -h .

          FREE_BYTES="$(df -PB1 . | awk 'NR==2 {print $4}')"

          # The official download is ~6.4 GB. Archive + selected restore and
          # extraction need substantial temporary headroom.
          if [ "$FREE_BYTES" -lt 22000000000 ]; then
            echo "::error::Less than 22 GB free after cleanup; refusing the full CNGM extraction."
            exit 1
          fi

      - name: Extract Colorado CNGM search authority
        shell: bash
        env:
          SOURCE_URL: ${{ github.event.inputs.source_url || '' }}
        run: |
          set -euo pipefail

          rm -rf dist-cngm-search-authority

          ARGS=(
            --output-dir dist-cngm-search-authority
            --pg-dsn postgresql:///cngm_search_extract
          )

          if [ -n "${SOURCE_URL:-}" ]; then
            ARGS+=(--source-url "$SOURCE_URL")
          fi

          python3 scripts/extract_cngm_colorado_search_authority.py "${ARGS[@]}"

      - name: Fail closed on authority artifact
        shell: bash
        run: |
          set -euo pipefail

          test -s dist-cngm-search-authority/cngm-colorado-search-authority-v1.db
          test -s dist-cngm-search-authority/search-authority-summary.json
          test -s dist-cngm-search-authority/summary.md
          test -s dist-cngm-search-authority/SHA256SUMS.txt

          python3 - <<'PY'
          import json
          import sqlite3
          from pathlib import Path

          root = Path('dist-cngm-search-authority')

          summary = json.loads(
              (root / 'search-authority-summary.json').read_text(encoding='utf-8')
          )

          assert summary['production_release_approved'] is False
          assert summary['full_database_doi'] == '10.5066/P1DC4XFG'
          assert summary['earth_surface_doi'] == '10.5066/P146VGVM'
          assert summary['data_report_doi'] == '10.3133/dr1210'
          assert summary['source_map_id'] == 'map50'
          assert summary['base']['source_units'] == 185
          assert summary['base']['polygons'] == 9500

          db = root / 'cngm-colorado-search-authority-v1.db'

          con = sqlite3.connect(f'file:{db}?mode=ro', uri=True)

          try:
              assert con.execute('PRAGMA quick_check').fetchone()[0].lower() == 'ok'

              meta = dict(con.execute('SELECT key,value FROM metadata'))

              assert meta['full_database_doi'] == '10.5066/P1DC4XFG'
              assert meta['earth_surface_doi'] == '10.5066/P146VGVM'
              assert meta['source_map_id'] == 'map50'
              assert meta['base_source_units'] == '185'
              assert meta['base_polygons'] == '9500'
              assert meta['production_release_approved'] == 'false'

              assert con.execute(
                  'SELECT COUNT(*) FROM age_concepts'
              ).fetchone()[0] > 0

              assert con.execute(
                  'SELECT COUNT(*) FROM geomaterial_concepts'
              ).fetchone()[0] > 0

              assert con.execute(
                  'SELECT COUNT(*) FROM base_source_units'
              ).fetchone()[0] == 185

              assert con.execute(
                  'SELECT COUNT(*) FROM source_geomaterial'
              ).fetchone()[0] > 0

              assert con.execute(
                  'SELECT COUNT(*) FROM age_assignments'
              ).fetchone()[0] > 0

              # Do not guess what fraction of the 185 units USGS assigned.
              # We report coverage gaps and only require that every retained
              # relationship belongs to a reviewed map50 source unit.
              base_units = {
                  row[0]
                  for row in con.execute(
                      'SELECT source_mapunit FROM base_source_units'
                  )
              }

              assigned_age = {
                  row[0]
                  for row in con.execute(
                      'SELECT source_mapunit FROM age_assignments'
                  )
              }

              assigned_lith = {
                  row[0]
                  for row in con.execute(
                      'SELECT DISTINCT source_mapunit FROM lithology_assignments'
                  )
              }

              assert assigned_age <= base_units
              assert assigned_lith <= base_units

          finally:
              con.close()

          print((root / 'summary.md').read_text(encoding='utf-8'))
          PY

      - name: Upload Colorado search-authority artifact
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: cngm-colorado-search-authority
          path: |
            dist-cngm-search-authority/cngm-colorado-search-authority-v1.db
            dist-cngm-search-authority/search-authority-summary.json
            dist-cngm-search-authority/summary.md
            dist-cngm-search-authority/SHA256SUMS.txt
          retention-days: 14
          if-no-files-found: error

      - name: Upload safe extraction diagnostics on failure
        if: failure()
        uses: actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a # v7
        with:
          name: cngm-search-authority-failure-diagnostics
          path: |
            dist-cngm-search-authority/_work/source-inventory.txt
            dist-cngm-search-authority/search-authority-summary.json
            dist-cngm-search-authority/summary.md
          retention-days: 7
          if-no-files-found: ignore