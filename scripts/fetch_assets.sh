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

# --- Authentic Madinah page images (offline bundle) ---
# Source: noureddin/quran-pages (the same 604-page Madinah mushaf quran_android
# renders). WebP @776x1053, ~130KB each -> ~78MB bundled, fully offline, so the
# reader can show the REAL pages and overlay recitation highlights on them.
PAGES_DIR="$ASSET_DIR/pages"
mkdir -p "$PAGES_DIR"
IMG_BASE="https://raw.githubusercontent.com/noureddin/quran-pages/master/2/pages/776x1053-webp"
fetch_pages() {
  local need=0
  for n in $(seq 1 604); do [ -f "$PAGES_DIR/$n.webp" ] || { need=1; break; }; done
  if [ "$need" -eq 0 ]; then echo "skip   pages (all present)"; return; fi
  echo "fetch  pages 1..604 (webp, offline)"
  seq 1 604 | xargs -P 8 -I{} sh -c '
    f="'"$PAGES_DIR"'/{}.webp"
    [ -f "$f" ] && exit 0
    for i in 1 2 3; do
      curl -fsSL -o "$f" "'"$IMG_BASE"'/{}.webp" && exit 0
      sleep 1
    done
    echo "FAILED page {}" >&2; exit 1
  '
}
fetch_pages

echo "Assets ready in: $ASSET_DIR"
