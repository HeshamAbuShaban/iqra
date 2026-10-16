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

    body = fn("ayahObs", vm)
    if not body:
        bad.append("ayahObs is gone")
    else:
        # 1. no window is trimmed to a stored span end
        if re.search(r"ayahSpanEnd\[[^\]]+\].*\n\s*from = prevEnd", body) \
                or re.search(r"if \(prevEnd != null[^)]*\).*\n\s*from = prevEnd", body, re.S):
            bad.append("ayahObs trims its window to ayahSpanEnd; that is the "
                       "ratchet that took 332 decided words to 27")
        # 2. the origin helper must be raw, so any future stored end denotes a
        #    real audio position
        start = fn("ayahWindowStartOf", vm)
        if not start:
            bad.append("ayahWindowStartOf is gone; a stored end has no origin")
        elif "ayahSpanEnd" in start:
            # The whole function, not the text before the first `return`: the
            # body contains an early `return -1`, so splitting on "return" hid
            # everything after it - a trim re-added at the end was invisible.
            bad.append("ayahWindowStartOf applies the trim, so any end stored "
                       "against it is short of the real audio boundary")
        # 3. stored ends only ever come from a raw window start
        sweep = re.search(r"val end = spanBase \+ lastHit \+ 1", vm)
        if sweep and "ayahWindowStartOf" not in vm[sweep.start() - 400:sweep.start()]:
            bad.append("a span end is not measured from the raw window start")

    clear = fn("clearEmissionLog", vm)
    if not clear or "ayahSpanEnd.clear()" not in clear:
        bad.append("the span ends are not cleared with the log they index; a "
                   "new session could start a window after a position from a "
                   "recitation that no longer exists")

    if bad:
        for x in bad:
            print("FAIL:", x)
        return 1
    print("ok: the ratchet stays removed; span-end recording is diagnostic only")
    return 0


if __name__ == "__main__":
    sys.exit(main())
