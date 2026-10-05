#!/usr/bin/env bash
# Fetch the three inputs for build_word_table.py, pinned by content hash.
#
# WHY A SCRIPT AND NOT A NOTE
# ---------------------------
# `word_aligned_phonemes.json` is COMMITTED (2.7 MB, bundled in the APK). A
# generated asset that cannot be regenerated is a liability: nobody can tell
# whether the committed bytes still follow from the source. So the source is
# pinned to a commit AND to a sha256, and the build is one command.
#
# NOT COMMITTED: the 149 MB source. It is reproducible from this script.
#
# SOURCE
#   Quran-Lab/quran-tajweed-phonetics
#   Same lab, same NPL licence family as the table it replaces, and UNGATED
#   (the Zipformer weights on the same account are gated; this dataset is not,
#   which is why it was usable without credentials).
#
# LICENCE
#   quran-lab-npl-1.2 per the dataset card ("other"). The Quran text itself is
#   not copyrightable, but check the terms before redistributing the DERIVED
#   asset outside this repo - it embeds their segmentation.
#
# USAGE
#   engine/replay/fetch_word_table_inputs.sh [dest-dir]
#   engine/.venv-replay/bin/python engine/replay/build_word_table.py [dest-dir]
set -euo pipefail

REPO="Quran-Lab/quran-tajweed-phonetics"
SHA="6f4be6257c140f7e318574bca01386474b606999"   # dataset repo commit
DEST="${1:-/tmp/opencode/tajweed}"
BASE="https://huggingface.co/datasets/${REPO}/resolve/${SHA}"

mkdir -p "$DEST"

fetch() {
  local name="$1" want="$2" got
  if [ -f "$DEST/$name" ]; then
    got=$(sha256sum "$DEST/$name" | cut -d' ' -f1)
    [ "$got" = "$want" ] && { echo "have  $name"; return 0; }
    echo "stale $name ($got)"
    rm -f "$DEST/$name"
  fi
  echo "fetch $name"
  curl -fSL --retry 3 -o "$DEST/$name.part" "$BASE/$name"
  got=$(sha256sum "$DEST/$name.part" | cut -d' ' -f1)
  if [ "$got" != "$want" ]; then
    rm -f "$DEST/$name.part"
    echo "HASH MISMATCH $name: got $got want $want" >&2
    exit 1
  fi
  mv "$DEST/$name.part" "$DEST/$name"
  echo "ok    $name"
}

# name                       sha256
fetch quran_phonetics.jsonl   5c54b75410f099045deb733301b843be2dca4d3b407c16f6b6bc75e0dc76aeaf
fetch quran_labels_v1.jsonl   8ea0efe2def20504072e5af3b1913dbb6d9048394149db88827221d0eaf9d2ea
fetch bijection_old250.json   61adb3fc89f11bad59d2b38b7c5c0383d6edeae48d9605f261d5d87f8ac0cf0d

# bij.json is what the generator was developed against; keep the name it uses.
[ -f "$DEST/bij.json" ] || cp "$DEST/bijection_old250.json" "$DEST/bij.json"

echo
echo "next:"
echo "  engine/.venv-replay/bin/python engine/replay/build_word_table.py $DEST"
