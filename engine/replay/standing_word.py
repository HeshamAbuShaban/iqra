#!/usr/bin/env python3
"""
The standing word must never be null while a session is live, and it must
follow the recitation across the ayah boundary.

The pointer is derived per poll in the verdict pass. The original walk took the
first word whose predecessor had a real verdict, which is right while the ayah
is in progress and wrong at its end: a fully-spoken ayah has no un-judged word
left, so `currentKey` stayed null. The user saw it as "standingWord always
null" - the highlight vanished exactly when the ayah was finished, when they
needed it most.

The second defect: the walk only ever ran on the LOCKED ayah, so while the
reciter was speaking the next ayah (which the lock reaches only at 60%
coverage) the pointer sat on the ayah behind the voice. The pointer ayah is
therefore the next ayah once it holds any real verdict - and only then, only
after a forward lock move, because a repeat must not be dragged forward by
the next ayah's stale verdicts.

What must hold:

  1. the pointer ayah is the LOCKED ayah unless the next ayah holds a real
     verdict and the last lock move was forward;
  2. the engine's timed-word pointer (timedKey) yields to that evidence, or
     once the reciter moves on the pointer stays on the locked ayah for the
     whole recitation of the next one;
  3. the fallback exists and is reached when the walk fails: it uses the
     pointer ayah's own last word - a real word of what is being recited -
     never the first word, which would jump the pointer backwards onto
     judged material;
  4. the verdict-walk stays in front of the fallback, so the pointer still
     lands on the word being recited mid-ayah.

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

    # 1. the pointer may stand on the next ayah only with real evidence there
    # and a forward lock move; otherwise it stands on the locked ayah
    if not re.search(
        r"val aheadHasEvidence = lastMoveDir >= 0 && newMap\.any[\s\S]{0,200}?"
        r"v == WordStatus\.WRONG", src
    ):
        bad.append("the next-ayah pointer is not gated on a real verdict in the "
                   "next ayah and on a forward lock move; it could drag the "
                   "pointer onto an ayah that was not recited, or backwards "
                   "after a repeat")
    if not re.search(r"val pointerAyah = if \(aheadHasEvidence\) ahead else lockedAyah", src):
        bad.append("no pointer-ayah selection; the pointer has no defined home")
    # 2. the timed word yields to the reciter having moved on
    if not re.search(r"var currentKey: String\? = if \(aheadHasEvidence\) null else timedKey", src):
        bad.append("the timed-word pointer still beats the next-ayah evidence, "
                   "so once the reciter moves to the next ayah the pointer "
                   "stays on the locked one for its whole recitation")

    # 3. the guarded fallback
    if not re.search(r"if \(currentKey == null\)\s*\{[\s\S]{0,4000}?currentKey = verseWords\[pointerAyah\]", src):
        bad.append("no null fallback for the standing word; a finished ayah "
                   "removes the pointer exactly when it is needed")
    else:
        block = re.search(r"if \(currentKey == null\)\s*\{[\s\S]{0,4000}?currentKey = verseWords\[pointerAyah\][^\n]*", src).group(0)
        if "lastOrNull" not in block:
            bad.append("the fallback is not the ayah's last word; it would jump "
                       "the pointer backwards onto already-judged material")
        if "firstOrNull" in block:
            bad.append("the fallback uses the ayah's FIRST word, which moves the "
                       "pointer backwards instead of marking what was finished")

    # 4. the verdict walk still runs first
    walk = re.search(r"if \(st != WordStatus\.CORRECT && seen\)\s*\{[\s\S]{0,120}?currentKey", src)
    if not walk:
        bad.append("the verdict walk is gone; the pointer would always sit on "
                   "the ayah's last word instead of the word being recited")
    elif "verseWords[pointerAyah]?.lastOrNull()" not in src:
        bad.append("the fallback's last-word literal is gone; the pointer has "
                   "no guaranteed resting word on a finished ayah")
    elif (src.index("if (st != WordStatus.CORRECT && seen)")
          > src.index("verseWords[pointerAyah]?.lastOrNull()")):
        bad.append("the fallback runs BEFORE the verdict walk, so the pointer "
                   "can never land on the word actually being recited")

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: the standing word is never null, follows the reciter across the "
          "ayah boundary, and the verdict walk still leads")
    return 0


if __name__ == "__main__":
    sys.exit(main())
