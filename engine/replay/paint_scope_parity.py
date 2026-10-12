#!/usr/bin/env python3
"""
A verdict is permanent for the session, and hide mode reveals on the verdict.

The reader's paint map used to be rebuilt each poll from `window` - lockedAyah
± 1 - so a word lost its colour the moment the lock advanced past it. The user
measured it as "they just stay shortly and on a short scope", and in hide mode
the same bug meant a word you had recited correctly was masked again as soon as
you moved on, because reveal was keyed on the active-window LAYER rather than on
the verdict.

What must hold:

  1. the paint map is SEEDED from the session's retained verdicts, so anything
     the reciter earned survives the lock moving on and the reader scrolling;
  2. the retained verdicts are cleared wherever a session is cleared - a new
     surah, a jump, an anchor, a new recite - so nothing leaks across sessions;
  3. hide mode reveals on the WORD'S VERDICT, not on which ayah the lock is
     holding, so a recited word stays revealed;
  4. UNKNOWN and SKIPPED are still masked in hide mode: they are the absence of
     a verdict, and revealing them would show the answer unasked.

Run: python3 engine/replay/paint_scope_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VM = HERE.parent.parent / "android/app/src/main/java/com/iqra/quren/ui/PracticeViewModel.kt"
MAIN = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ui/MainActivity.kt"
VM = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt"


def code(path):
    out = []
    for l in Path(path).read_text(encoding="utf-8").splitlines():
        s = l.strip()
        if s.startswith("//"):
            continue
        out.append(s.split("//")[0])
    return "\n".join(out)


def main() -> int:
    vm = code(VM)
    main = code(MAIN)
    bad = []

    def body_of(fn):
        """The slice of `fn`'s own body, brace-matched.

        A naive "up to the next declaration" overflowed past the function's
        closing brace and picked up the clears belonging to the functions after
        it, which made the whole check vacuous - it passed with the clear
        removed from loadSurah because loadSurah's slice still contained three
        other functions' clears.
        """
        m = re.search(r"\b(private |internal )?fun %s\s*\(" % re.escape(fn), vm)
        if not m:
            return ""
        depth = 0
        for i in range(m.start(), len(vm)):
            c = vm[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return vm[m.start():i + 1]
        return vm[m.start():]

    # 1. the paint map is seeded from the session's verdicts
    if not re.search(r"val newMap = LinkedHashMap\(sessionStatuses\)", vm):
        bad.append("the paint map is not seeded from the session's verdicts, "
                   "so a word loses its colour when the lock moves on")

    # 2. every session-clearing path also clears the retained verdicts
    # The paths that START or REPLACE a session. These are the only ones that
    # can let a previous session's verdicts onto screen, so these are the only
    # ones that must clear them.
    #
    # endSession is deliberately ABSENT: a teardown clears the PAINT only, and
    # the retained verdicts die at the next startRecite along with the counters.
    # That is the arrangement that survived the fix in which counters were reset
    # in endSession and a page jump wiped the record of a finished recitation.
    # resetSessionUi is absent for the same reason - it clears the paint map.
    for fn in ("loadSurah", "jumpToPage", "anchorToVerse", "startRecite"):
        b = body_of(fn)
        if not b:
            bad.append("%s() is gone; the clear contract cannot be checked" % fn)
        else:
            # Follow ONE hop: endSession clears through resetSessionCounters,
            # and the verdicts are session state that dies with the counters.
            # Testing only the literal clear would fail a correct chain, and
            # testing every hop would need the whole call graph.
            hop = ("sessionStatuses.clear()" in b
                   or any(body_of(callee) and "sessionStatuses.clear()" in body_of(callee)
                          for callee in ("resetSessionUi", "resetSessionCounters")
                          if callee + "()" in b))
            if not hop:
                bad.append("%s() does not clear the retained verdicts, so they "
                           "leak across sessions" % fn)

    # 3. hide mode reveals on the verdict, not on the layer
    i = main.find("if (hide) {")
    hide = main[i:i + 1600] if i >= 0 else ""
    if not hide:
        bad.append("the hide branch no longer keys on the verdict")
    else:
        if "WordStatus.CORRECT" not in hide.split("return when (layer)")[0]:
            bad.append("hide mode does not reveal a CORRECT word on its verdict")
        if "st == WordStatus.CORRECT" in hide and "HighlightLayer.RECITATION_AYAH" in hide:
            bad.append("hide mode still reveals through the active-window layer")
        if "WordStatus.WRONG" not in hide.split("return when (layer)")[0]:
            bad.append("hide mode does not distinguish a WRONG word from an unknown one")

    if bad:
        for x in bad:
            print("FAIL:", x)
        return 1
    print("ok: a verdict is permanent for the session, and hide reveals on the "
          "verdict rather than on where the lock is")
    return 0


if __name__ == "__main__":
    sys.exit(main())
