#!/usr/bin/env bash
# Fetch bundled assets into the Android asset folder. The voice engine
# (zipformer) and VAD models are user-supplied via adb push (gated weights)
# and are NEVER bundled or committed — so there is no acoustic model here.
# quran.json (verse texts + names, MIT) is the only text table we ship.
#
# Source: https://github.com/yazinsai/tilawa/releases/tag/v0.2.0  (MIT)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ASSET_DIR="$SCRIPT_DIR/../android/app/src/main/assets"
mkdir -p "$ASSET_DIR"

BASE="https://github.com/yazinsai/tilawa/releases/download/v0.2.0"

fetch() { # fetch <url> <out-name>
  if [ -f "$ASSET_DIR/$2" ]; then
    echo "skip   $2 (already present)"
    return
  fi
  echo "fetch  $2"
  curl -fL -o "$ASSET_DIR/$2" "$1"
}

# Stale tilawa-era assets must never linger next to the new pipeline.
rm -f "$ASSET_DIR/model.onnx" "$ASSET_DIR/vocab.json" "$ASSET_DIR/quran_ctc_tokens.json"
fetch "$BASE/quran.json"                     quran.json

# --- Authentic Madinah Mushaf page images ---
# Source: murtraja/quran-android-images-helper -> the SAME standard Madinah
# Mushaf (Hafs, Uthmani) pages used by quran_android / quran.com / Tarteel.
# 1024x1656 PNG, ~30-100KB each.
#
# These are NOT bundled any more: 54 MB of page images in the APK meant every
# update re-downloaded them. They now live in the phone's shared folder
# /sdcard/Iqra/pages and the app fetches them from this same source when
# missing. Set IQRA_STAGE_DIR to stage them to disk instead (for adb push).
PAGES_DIR="${IQRA_PAGES_DIR:-$ASSET_DIR/pages}"
mkdir -p "$PAGES_DIR"
rm -f "$PAGES_DIR"/*.webp
IMG_BASE="https://raw.githubusercontent.com/murtraja/quran-android-images-helper/master/static/images_1024"
fetch_pages() {
  local need=0
  for n in $(seq 1 604); do
    [ -f "$PAGES_DIR/$(printf '%03d' "$n").png" ] || { need=1; break; }
  done
  if [ "$need" -eq 0 ]; then echo "skip   pages (all present)"; return; fi
  echo "fetch  pages 1..604 (1024x1656 Madinah PNG) -> $PAGES_DIR"
  seq 1 604 | xargs -P 8 -I{} sh -c '
    n=$(printf "%03d" "{}")
    f="'"$PAGES_DIR"'/$n.png"
    [ -f "$f" ] && exit 0
    for i in 1 2 3; do
      curl -fsSL -o "$f" "'"$IMG_BASE"'/page$n.png" && exit 0
      sleep 1
    done
    echo "FAILED page $n" >&2; exit 1
  '
}
if [ "${IQRA_SKIP_PAGES:-0}" = "1" ]; then
  rm -rf "$ASSET_DIR/pages"
  echo "skip   pages (not bundled; app fetches into /sdcard/Iqra/pages)"
else
  fetch_pages
  # Keep them out of the APK: the reader reads them from the shared folder.
  if [ "$PAGES_DIR" = "$ASSET_DIR/pages" ]; then
    mkdir -p "${IQRA_STAGE_DIR:-$PWD/.iqra-stage}/pages"
    cp "$PAGES_DIR"/*.png "${IQRA_STAGE_DIR:-$PWD/.iqra-stage}/pages/" 2>/dev/null || true
    rm -rf "$ASSET_DIR/pages"
    echo "moved  pages out of assets -> ${IQRA_STAGE_DIR:-$PWD/.iqra-stage}/pages"
  fi
fi

# --- Per-word glyph coordinate DB (murtraja 1024, standard Madinah Mushaf) ---
# 88246 glyph rows in the 1024x1656 page space, one box PER VISUAL WORD on
# every line of every page -> the reader highlights the exact printed word
# you are reciting (and the exact word in the reference-audio play-head).
# Source: murtraja/quran-android-images-helper/static/databases (quran_android
# madani data, width 1024). Like the pages, this is no longer bundled.
rm -f "$ASSET_DIR/ayahinfo.db"
DB_DEST="${IQRA_STAGE_DIR:-$PWD/.iqra-stage}/ayahinfo_1024.db"
mkdir -p "$(dirname "$DB_DEST")"
if [ ! -f "$DB_DEST" ]; then
  echo "fetch  ayahinfo_1024.db -> $DB_DEST"
  # NB: not via fetch(), which prefixes $ASSET_DIR onto its name argument.
  curl -fL -o "$DB_DEST" \
    "https://raw.githubusercontent.com/murtraja/quran-android-images-helper/master/static/databases/ayahinfo_1024.db"
fi
rm -f "$ASSET_DIR/ayahinfo_1024.db"

echo "Assets ready in: $ASSET_DIR"
echo "Staged for phone: ${IQRA_STAGE_DIR:-$PWD/.iqra-stage}"
