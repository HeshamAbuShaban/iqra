#!/usr/bin/env python3
"""
The standing word must never be null while a session is live.

The pointer is derived per poll in the verdict pass. The original walk took the
first word whose predecessor had a real verdict, which is right while the ayah
is in progress and wrong at its end: a fully-spoken ayah has no un-judged word
left, so `currentKey` stayed null. The user saw it as "standingWord always
null" - the highlight vanished exactly when the ayah was finished, when they
needed it most.

The fix is a fallback to the locked ayah's own last word. What must hold:

  1. the fallback exists and is reached when the walk fails;
  2. it uses the LOCKED ayah's last word - a real word of what is being
     recited - never the first word, which would jump the pointer backwards
     onto judged material;
  3. it is only a null guard: the verdict-walk stays in front of it, so the
     pointer still lands on the word being recited mid-ayah.

Run: python3 engine/replay/standing_word.py
"""

import re
import sys
from pathlib import Path

VM = (Path(__file__).resolve().parent.parent.parent / "android" / "app" / "src" /
      "main" / "java" / "com" / "iqra" / "quran" / "ui" / "PracticeViewModel.kt")


def main() -> int:
    src = VM.read_text(encoding="utf-8")
    bad = []

    # 1. the guarded fallback
    if not re.search(r"if \(currentKey == null\)\s*\{[\s\S]{0,4000}?currentKey = verseWords\[lockedAyah\]", src):
        bad.append("no null fallback for the standing word; a finished ayah "
                   "removes the pointer exactly when it is needed")
    else:
        block = re.search(r"if \(currentKey == null\)\s*\{[\s\S]{0,4000}?currentKey = verseWords\[lockedAyah\][^\n]*", src).group(0)
        # 2. last word, not first
        if "lastOrNull" not in block:
            bad.append("the fallback is not the ayah's last word; it would jump "
                       "the pointer backwards onto already-judged material")
        if "firstOrNull" in block:
            bad.append("the fallback uses the ayah's FIRST word, which moves the "
                       "pointer backwards instead of marking what was finished")
        if "lockedAyah" not in block:
            bad.append("the fallback is not scoped to the locked ayah")

    # 3. the verdict walk still runs first
    walk = re.search(r"if \(st != WordStatus\.CORRECT && seen\)\s*\{[\s\S]{0,120}?currentKey", src)
    if not walk:
        bad.append("the verdict walk is gone; the pointer would always sit on "
                   "the ayah's last word instead of the word being recited")
    elif src.index("if (st != WordStatus.CORRECT && seen)") > src.index("verseWords[lockedAyah]?.lastOrNull()"):
        bad.append("the fallback runs BEFORE the verdict walk, so the pointer "
                   "can never land on the word actually being recited")

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: the standing word is never null, and the verdict walk still leads")
    return 0


if __name__ == "__main__":
    sys.exit(main())
