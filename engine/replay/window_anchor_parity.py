#!/usr/bin/env python3
"""
Span-end trimming must stay DISABLED, and why: a regression log, not a guard.

The previous version of this check asserted the opposite - that `ayahObs` must
trim its window's leading edge to `ayahSpanEnd[N-1]`. That check passed while the
app lost a third of its decided verdicts.

What happened, measured on the phone. Before the trim, three sessions gave 332
decided words at 95.9% / 97.2% / 97.6% correct. With the trim, two sessions gave
27 decided words at 84.2% / 100%. No Window-fault class was large enough to
justify that, and the cause was arithmetic rather than tajweed.

`ayahSpanEnd[N]` was computed as `ayahWindowStartOf(N) + lastHit + 1`, and
`ayahWindowStartOf` itself returned the already-trimmed start. So every stored end
denoted a position slightly short of the real audio boundary, the next ayah was
trimmed to it, and because a stored end only ever moved forward the shortfall
could never recover. The trim was ratcheting.

The boundary defect is still real and still unfixed - two rebuilt windows proved
it (`100:8:1` expecting `\u0648\u064e\u0644\u0650\u0644\u0651\u064e\u0647\u0650` over another ayah's
text, `2:2:2` expecting `\u0628\u0650\u0627\u0644\u0652\u0643\u064e\u0627\u0641\u0650\u0631\u0650\u064a\u0646\u064e` over Al-Fatiha's). This
check exists to keep the naive fix from being re-applied by someone who has not
read that log.

Run: python3 engine/replay/window_anchor_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VM = HERE.parent.parent / "android" / "app" / "src" / "main" / "java" / "com" / "iqra" / "quran" / "ui" / "PracticeViewModel.kt"


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

    def fn(name):
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

    body_ = fn("ayahObs")
    if not body_:
        bad.append("ayahObs is gone")
    else:
        if "applySpanEndTrim" not in body_:
            bad.append("ayahObs does not go through the guarded trim; the "
                       "boundary artefact is back or the trim is unbounded")
        if re.search(r"ayahSpanEnd\[[^\]]+\][\s\S]{0,200}?from = prevEnd", body_):
            bad.append("ayahObs assigns from a raw span end again - that is the "
                       "ratchet that took 332 decided words to 27")

    trim = fn("applySpanEndTrim")
    if not trim:
        bad.append("applySpanEndTrim is gone; the guards have no owner")
    else:
        if "MIN_WINDOW_SYMBOLS" not in trim:
            bad.append("the trim has no minimum-window floor; the first version "
                       "starved windows to 15-36 symbols and lost a third of "
                       "every decided verdict")
        if "hi - MIN_WINDOW_SYMBOLS" not in trim:
            bad.append("the floor is not applied to the right edge, so it "
                       "cannot actually bound the window's length")
        if not re.search(r"prevEnd <= from", trim):
            bad.append("the trim can move the left edge BACKWARD, which would "
                       "reintroduce the prefix it is meant to remove")
        if "private const val MIN_WINDOW_SYMBOLS" not in vm:
            bad.append("MIN_WINDOW_SYMBOLS is not declared")

    start = fn("ayahWindowStartOf")
    if not start:
        bad.append("ayahWindowStartOf is gone; a stored end has no origin")
    elif "ayahSpanEnd" in start:
        bad.append("ayahWindowStartOf applies the trim, so any end stored "
                   "against it is short of the real audio boundary and the "
                   "trim ratchets forward")

    clear = fn("clearEmissionLog")
    if not clear or "ayahSpanEnd.clear()" not in clear:
        bad.append("the span ends are not cleared with the log they index; a "
                   "new session could start a window after a position from a "
                   "recitation that no longer exists")

    if bad:
        for x in bad:
            print("FAIL:", x)
        return 1
    print("ok: the window trim is floored, forward-only, and measured from a raw "
          "start")
    return 0


if __name__ == "__main__":
    sys.exit(main())
