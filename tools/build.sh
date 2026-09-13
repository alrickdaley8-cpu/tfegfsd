#!/usr/bin/env bash
# build.sh - Rebuild the Saturn sound/sprite archives and validate.
#
# Usage:
#   bash tools/build.sh                 # rebuild sounds + repack SFF + validate
#   bash tools/build.sh --sprites       # also regenerate PLACEHOLDER sprites
#   bash tools/build.sh --ingest FILE   # build sprites from your own image
#
# Note: build.sh never overwrites your ingested sprite art unless you pass
# --sprites (placeholder generator) or --ingest (your own image).

set -euo pipefail
cd "$(dirname "$0")/.."

gen_sprites=false
ingest=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sprites) gen_sprites=true; shift ;;
    --ingest) ingest="$2"; shift 2 ;;
    *) echo "unknown arg: $1"; exit 2 ;;
  esac
done

echo "== sounds =="
python3 tools/gen_sounds.py
python3 tools/build_snd.py

if [[ -n "$ingest" ]]; then
  echo "== sprites (ingest) =="
  python3 tools/ingest_sprite.py --input "$ingest" --preview
elif $gen_sprites; then
  echo "== sprites (placeholder) =="
  python3 tools/saturn_assets.py
  python3 tools/build_sff.py
else
  echo "== sprites (repack existing manifest) =="
  python3 tools/build_sff.py
fi

echo "== validate =="
python3 tools/validate.py
