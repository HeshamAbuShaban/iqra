#!/usr/bin/env python3
"""
Paint is permanent until the next session, and it survives a stop.

A verdict the reciter earned vanished in two more ways than the engine's own
prunes. The engine revoked it while the lock sat on the ayah (a live SKIPPED
deleting the retained verdict - modelled and fixed in archive_parity's
retention()), and the reader dropped its whole record whenever the paint map
came back empty, which is what a teardown (stopRecite) emits. The user
reported both shapes: "paint and unpaint" while reciting, and colours that
"just stay shortly" after stopping.

The reader side must key its clear on the engine's own clear points and on
nothing else. The engine publishes `sessionEpoch`, bumped exactly where it
clears its retained verdicts: loadSurah, jumpToPage, anchorToVerse,
startRecite. Teardown (resetSessionUi / endSession) must NOT bump it - that
is the whole distinction between "the verdicts are gone" and "the paint was
wiped".

What must hold:

  1. the reader clears its earned verdicts on the epoch, and never on an
     empty paint map;
  2. each of the four session-clearing functions bumps the epoch;
  3. neither teardown path (resetSessionUi, endSession) does.

Run: python3 engine/replay/session_epoch_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VM = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt"
MAIN = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ui/MainActivity.kt"

BUMP = "_sessionEpoch.value = _sessionEpoch.value + 1"
CLEAR_PATHS = ("loadSurah", "jumpToPage", "anchorToVerse", "startRecite")
TEARDOWN_PATHS = ("resetSessionUi", "endSession")


def body_of(fn, src):
    m = re.search(r"\b(private |internal )?fun %s\s*\(" % re.escape(fn), src)
    if not m:
        return ""
    depth = 0
    for i in range(m.start(), len(src)):
        c = src[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[m.start():i + 1]
    return src[m.start():]


def main() -> int:
    vm = VM.read_text(encoding="utf-8")
    main_src = MAIN.read_text(encoding="utf-8")
    bad = []

    # 1. the reader clears on the epoch, never on an empty paint map
    if "LaunchedEffect(sessionEpoch) { earned.clear() }" not in main_src:
        bad.append("MainActivity.kt: the reader does not clear its earned "
                   "verdicts on the session epoch, so either nothing clears "
                   "them or the clear is keyed on something else")
    if "if (statusMap.isEmpty()) earned.clear()" in main_src:
        bad.append("MainActivity.kt: the reader clears on an empty paint map "
                   "again. Teardown emits one, so the colours vanish the "
                   "moment the reciter stops - the defect this check names")
    if "LaunchedEffect(statusMap) { if (statusMap.isNotEmpty()) earned.fold(statusMap) }" not in main_src:
        bad.append("MainActivity.kt: the reader no longer folds the live map "
                   "into its earned verdicts")

    # 2. each session-clearing path bumps the epoch
    for fn in CLEAR_PATHS:
        b = body_of(fn, vm)
        if not b:
            bad.append("%s() is gone; the epoch contract cannot be checked" % fn)
        elif BUMP not in b:
            bad.append("%s() clears the retained verdicts but does not bump "
                       "the session epoch, so the reader keeps paint the "
                       "engine has dropped" % fn)

    # 3. teardown must not
    for fn in TEARDOWN_PATHS:
        b = body_of(fn, vm)
        if not b:
            bad.append("%s() is gone; the epoch contract cannot be checked" % fn)
        elif BUMP in b:
            bad.append("%s() bumps the session epoch. Teardown clears the "
                       "PAINT only; bumping here wipes the colours the reciter "
                       "earned the moment they stop" % fn)

    if bad:
        for x in bad:
            print("FAIL:", x)
        return 1
    print("ok: the reader drops its verdicts exactly where the engine drops "
          "its own - never at teardown")
    return 0


if __name__ == "__main__":
    sys.exit(main())
