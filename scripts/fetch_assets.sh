#!/usr/bin/env bash
# Fetch the open-source Tilawa (MIT) model + Quran token tables into the
# Android asset folder. The app bundles these so it runs fully OFFLINE at
# runtime — this download happens once, at build time, on your machine.
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

# The 85 MB acoustic model is NOT bundled in CI builds — the app downloads it
# once on first launch (see ModelManager). Set SKIP_MODEL=1 to skip it; run the
# script without that var to bundle it for a fully offline local build.
if [ -z "${SKIP_MODEL:-}" ]; then
  fetch "$BASE/fastconformer_full_mixed.onnx" model.onnx
else
  echo "skip   model.onnx (SKIP_MODEL set)"
fi
fetch "$BASE/vocab.json"                     vocab.json
fetch "$BASE/quran.json"                     quran.json
fetch "$BASE/quran_ctc_tokens.json"          quran_ctc_tokens.json

# --- Authentic Madinah Mushaf page images (offline bundle) ---
# Source: murtraja/quran-android-images-helper -> the SAME standard Madinah
# Mushaf (Hafs, Uthmani) pages used by quran_android / quran.com / Tarteel.
# 1024x1656 PNG, ~30-100KB each -> ~45MB bundled, fully offline, so the
# reader renders the REAL printed mushaf and overlays per-word highlights.
# (Replaces the earlier noureddin/Dar-ul-Ma'refa set, which was a different
# mushaf.)
PAGES_DIR="$ASSET_DIR/pages"
mkdir -p "$PAGES_DIR"
# Clean stale assets from a previous source/edition so we never mix mushafs.
rm -f "$PAGES_DIR"/*.webp
IMG_BASE="https://raw.githubusercontent.com/murtraja/quran-android-images-helper/master/static/images_1024"
fetch_pages() {
  local need=0
  for n in $(seq 1 604); do
    [ -f "$PAGES_DIR/$(printf '%03d' "$n").png" ] || { need=1; break; }
  done
  if [ "$need" -eq 0 ]; then echo "skip   pages (all present)"; return; fi
  echo "fetch  pages 1..604 (1024x1656 Madinah PNG, offline)"
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
fetch_pages

# --- Per-word glyph coordinate DB (murtraja 1024, standard Madinah Mushaf) ---
# 88246 glyph rows in the 1024x1656 page space, one box PER VISUAL WORD on
# every line of every page -> the reader highlights the exact printed word
# you are reciting (and the exact word in the reference-audio play-head).
# Source: murtraja/quran-android-images-helper/static/databases (quran_android
# madani data, width 1024).
rm -f "$ASSET_DIR/ayahinfo.db"
fetch "https://raw.githubusercontent.com/murtraja/quran-android-images-helper/master/static/databases/ayahinfo_1024.db" ayahinfo_1024.db

echo "Assets ready in: $ASSET_DIR"
