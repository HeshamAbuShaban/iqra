#!/usr/bin/env python3
"""
The ahead ayah is judged against its own audio, and only its own.

Read off the device on 18 October: while the reciter was inside ayah N, the
NEXT ayah's words flashed with colours - "words ahead flash to my current
recitation, sometimes they match the current, more often they don't". The
mechanism: `ayahObs` served N+1 from the locked ayah's arrival, so the
window's tail was the CURRENT speech, and the trim's `hi - MIN_WINDOW_SYMBOLS`
floor kept those freshest symbols inside the window no matter what. Every
spurious match was then retained as terminal (first verdict wins), so it
stuck.

The guard, in three pieces:
  1. the ahead window's left edge is raised to `lockedSpeechEnd` - the end of
     the locked ayah's own matched speech - and the window REFUSES (null)
     until that end is known and real audio exists past it;
  2. `lockedSpeechEnd` is measured from the SERVED window start
     (`lockedObsStart`), because `emitWord` indexes the served list; a
     raw-start origin is short by the trim;
  3. the untrimmed fallback window is gated on `ayahArrival != null`, or the
     ahead ayah would get the flash window back through the fallback: word 0
     unseated (exactly what the floor does when the locked DP swallowed the
     opening) would re-judge against audio that belongs to the other ayah.

And the flicker pair: the `newMap` writes on the no-window path and the
word-count-mismatch path must consult `sessionStatuses` before writing
UNKNOWN, or a retained terminal blinks off for a poll - "the right shown
kept word go disappear for a second".

Run: python3 engine/replay/ahead_window_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VM = (HERE.parent.parent / "android" / "app" / "src" / "main" / "java"
      / "com" / "iqra" / "quran" / "ui" / "PracticeViewModel.kt")


def fn(name, src):
    m = re.search(r"private fun %s\(" % name, src)
    if not m:
        return ""
    depth = 0
    for i in range(m.start(), len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[m.start():i + 1]
    return ""


def main() -> int:
    vm = VM.read_text(encoding="utf-8")
    bad = []

    body = fn("ayahObs", vm)
    if not body:
        bad.append("ayahObs is gone")
    else:
        if "val ahead = ayahArrival[key] == null" not in body:
            bad.append("ayahObs no longer distinguishes the ahead ayah, so the "
                       "own-audio floor has no owner")
        if not re.search(r"if \(ahead && lockedSpeechEndKey != [^)]+\) return null", body):
            bad.append("the ahead window is served before the locked ayah's "
                       "speech end is known; that window is the flash")
        if "from = maxOf(from, lockedSpeechEnd)" not in body:
            bad.append("the ahead window's left edge is not raised to the "
                       "locked ayah's speech end; it reaches back into the "
                       "speech being finished")
        if "if (from >= hi) return null" not in body:
            bad.append("the ahead window can still be an empty sliver instead "
                       "of a refusal; a sliver judges words without their audio")
        if not re.search(r"if \(surah == activeSurah && ayah == lockedAyah\) lockedObsStart = trimmed", body):
            bad.append("ayahObs does not record where the locked ayah's served "
                       "window started, so the speech end has no exact origin")

    loop = vm[vm.find("var al = if (aObs == null"):]
    loop = loop[:loop.find("if (al == null && aObs != null)")]
    if not loop:
        bad.append("the verdict loop slice is gone")
    else:
        if not re.search(r'if \(al != null && ayahArrival\["\$activeSurah:\$a"\] != null &&\s*\n\s*al\.wordCoverage', loop):
            bad.append("the untrimmed fallback is not gated on a visited "
                       "ayah; the ahead ayah can re-judge against the wide "
                       "window - the flash by the back door")
        if not re.search(r'if \(al != null && aObs != null && ayahArrival\["\$activeSurah:\$a"\] != null\)', loop):
            bad.append("the span-end block records for unvisited ayat too; an "
                       "ahead alignment's hits index a window that starts at "
                       "the floor, not at the raw start")
        if "lockedObsStart + lastHit + 1" not in loop:
            bad.append("the locked speech end is not measured from the served "
                       "window start; it is short by the trim the moment the "
                       "window was trimmed")
        if "lockedSpeechEndKey != key" not in loop:
            bad.append("the locked speech end is not keyed to its ayah; a "
                       "lock move leaves a stale boundary standing")
        if not re.search(r"if \(al == null\)[\s\S]{0,1100}?sessionStatuses\[k\]", loop):
            bad.append("the no-window path writes UNKNOWN without consulting "
                       "the retained verdicts - a kept word flickers off")
        if not re.search(r"unjudgeableKeys\.add[\s\S]{0,900}?sessionStatuses\[k\]", vm):
            bad.append("the word-count-mismatch path writes UNKNOWN without "
                       "consulting the retained verdicts - a kept word "
                       "flickers off")

    clear = fn("clearEmissionLog", vm)
    if not clear:
        bad.append("clearEmissionLog is gone")
    else:
        if 'lockedSpeechEndKey = ""' not in clear:
            bad.append("the locked speech end survives a session boundary; "
                       "the next session's first ahead window would trust a "
                       "boundary from a recitation that no longer exists")
        if "lockedObsStart = -1" not in clear:
            bad.append("the served-window origin survives a session boundary")

    if "private var lockedSpeechEnd = 0" not in vm:
        bad.append("lockedSpeechEnd is not declared")
    if "private var lockedObsStart = -1" not in vm:
        bad.append("lockedObsStart is not declared")

    if bad:
        for x in bad:
            print("FAIL:", x)
        return 1
    print("ok: the ahead window starts at the locked ayah's own speech end, "
          "refuses when unknown, and a retained verdict survives a dead poll")
    return 0


if __name__ == "__main__":
    sys.exit(main())
