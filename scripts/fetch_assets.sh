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

echo "Assets ready in: $ASSET_DIR"
