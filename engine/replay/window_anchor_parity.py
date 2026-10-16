#!/usr/bin/env python3
"""
The window's leading edge must be trimmed by the alignment, never by a guess.

Measured on the user's own sessions of 16 October, with the retention fixed so
windows are rebuildable at last: of 12 WRONG verdicts, 11 sat on an ayah's FIRST
or LAST word. Two windows were rebuilt and showed the cause outright - `100:8:1`
expected `وَلِلَّهِ` while its window opened on another ayah's text, and `2:2:2`
expected `بِالْكَافِرِينَ` while its window held Al-Fatiha's.

The window starts at the previous ayah's arrival because the lock arrives
mid-ayah and N's own opening is still in the previous slice. That is necessary.
The artefact is that the previous ayah's TAIL then sits at the leading edge and
is blamed for the next ayah's first word.

`ayahSpanEnd` records, from the alignment's own `emitWord`, where each ayah's
speech actually ended, and `ayahObs` trims the left edge to just past it.

What must hold:

  1. the trim can only move the left edge RIGHT, and only to a position the
     alignment produced - a fallback that invents a position would be the same
     fabrication the replay produced;
  2. the trim can never move the left edge past the right edge, or the window
     collapses;
  3. `ayahWindowStartOf` and `ayahObs` must agree, or the end is stored in a
     different coordinate system from the window it trims;
  4. the span ends are cleared with the log they index.

Run: python3 engine/replay/window_anchor_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VM = HERE.parent.parent / "android/app/src/main/java/com/iqra/quren/ui/PracticeViewModel.kt"
VM = HERE.parent.parent / "android" / "app" / "src" / "main" / "java" / "com" / "iqra" / "quran" / "ui" / "PracticeViewModel.kt"


def fn(name, vm):
    """Whole body of one private function, brace-matched.

    A non-greedy `\n    }` stops at the first four-space closing brace, which for
    `ayahObs` is inside a nested block - so the original check passed on a fix it
    had never actually seen, and an "ungated trim" mutation survived.
    """
    m = re.search(r"private fun %s\(" % name, vm)
    if not m:
        return ""
    depth = 0
    for i in range(m.start(), len(vm)):
        if vm[i] == "{":
            depth += 1
        elif vm[i] == "}":
            depth -= 1
            if depth == 0:
                return vm[m.start():i + 1]
    return ""


def main() -> int:
    vm = VM.read_text(encoding="utf-8")
    bad = []

    trim = fn("ayahObs", vm)
    if not trim:
        bad.append("ayahObs is gone")
    else:
        # 1. only from a recorded alignment end, and only forward
        if "ayahSpanEnd" not in trim:
            bad.append("ayahObs does not consult the recorded span end; the "
                       "boundary artefact has no fix")
        if not re.search(r"prevEnd != null && prevEnd > from && prevEnd < hi", trim):
            bad.append("the trim is not gated on being strictly inside the "
                       "window, so it could consume it")
        # The trim must READ the map and branch, never default a position. A
        # `?: 0` here is the exact fabrication the replay was caught making: a
        # missing neighbour defaulted to 0, produced a window spanning the whole
        # session, and explained 10 of 26 false accusations with 1,200 symbols
        # of the wrong ayah. Both functions read the same map, so both must be
        # checked - testing one and not the other passed a default the check
        # could not see.
        for site in (trim, fn("ayahWindowStartOf", vm)):
            if re.search(r"ayahSpanEnd\[[^\]]+\]\s*\?:\s*0", site):
                bad.append("a window function defaults a missing span end to a "
                           "position; a missing boundary must mean no trim, not "
                           "position 0")

        if "emissionBase + emissionLog.size" not in trim:
            bad.append("the trim lost the clamp that keeps the right edge the "
                       "limit on the left edge")

    helper = fn("ayahWindowStartOf", vm)
    if not helper:
        bad.append("ayahWindowStartOf is gone; the coordinate systems have one "
                   "definition between two consumers")
    else:
        # 3. the two must compute the same left edge
        for frag in ("maxOf(ayahArrival", "emissionBase + emissionLog.size",
                     "maxOf(start, end - ayahWindowLimit())",
                     "prevEnd > from && prevEnd < end"):
            if frag not in helper:
                bad.append("ayahWindowStartOf no longer mirrors ayahObs: %r" % frag)

    # 4. cleared with the log
    clear = fn("clearEmissionLog", vm)
    if not clear or "ayahSpanEnd.clear()" not in clear:
        bad.append("the span ends are not cleared with the log they index, so "
                   "a new session's first window could start after a position "
                   "from a recitation that no longer exists")

    if bad:
        for x in bad:
            print("FAIL:", x)
        return 1
    print("ok: the window's leading edge is trimmed to where the previous ayah's "
          "speech ended, from the alignment alone")
    return 0


if __name__ == "__main__":
    sys.exit(main())