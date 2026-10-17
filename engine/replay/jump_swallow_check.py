#!/usr/bin/env python3
"""How much can be re-judged after a jump swallows an ayah?

Measured 17 October on the user's mid-page walk: the lock jumped 107:1 -> 3
in one poll (the short ayah 107:2 never became the locked ayah) and its four
words retired UNKNOWN - never accused, but never judged either, in a session
the reciter completed.

The window of a skipped ayah is reconstructible from the record: its left
edge is the arrival of the ayah before it (or the log start), its right edge
is the arrival of the NEXT ayah that has one - the jump move's target, which
got its arrival because the pendingArrivalKey points at the lock, not at the
ayat that were passed over. If aligning within that window judges the ayah,
the catch-up design work is justified; if it cannot judge it, the honest
answer is that no audio constitutes enough and the UNKNOWN was correct.

Rules:
  - word statuses come from the app's own statuses_from (same verdict code)
  - the record's `words` rows tell which words were already judged; only the
    un-judged ones (no row) are candidates
  - a candidate whose window corpus < its full unit count is refused
"""

import collections
import json
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import word_verdicts as wv

TABLE = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                     "..", "..", "android", "app", "src", "main",
                     "assets", "word_aligned_phonemes.json")


def main(path):
    rec = json.load(open(path))
    table = json.load(open(TABLE, encoding="utf-8"))
    ems = rec.get("emissions") or []
    arr = rec.get("arrivals") or {}
    surah = rec.get("surahNow")
    rev = {}

    wv_md = wv.load_units()
    unit_set = set(wv_md)

    judged = {w.get("key") if isinstance(w, dict) else w[0]
              for w in (rec.get("words") or [])}

    rows = []
    for a in range(1, 8):   # the surah's real ayah bound comes from the table
        key = "%d:%d" % (surah, a)
        if key not in table:
            continue
        wlist = table[key]
        need = [u for wd in wlist for u in wd]
        lo = arr.get("%s:%d" % (surah, a - 1), 0)
        hi = next((arr["%s:%d" % (surah, b)] for b in range(a + 1, a + 9)
                   if "%s:%d" % (surah, b) in arr), len(ems))
        window = ems[lo:hi]
        if len({c for c in window if c in unit_set}) < len(need):
            kind = "no-audio"
        else:
            uw = [w for w, ws in enumerate(wlist) for u in ws]
            m, wr, _r2q, _e, _h, _n = wv.align(window, need, uw)
            sts = wv.statuses_from(m, wr, uw, len(wlist))
            un_judged = [wi for wi in sorted(sts)
                         if "%d:%d:%d" % (surah, a, wi + 1) not in judged]
            if not un_judged:
                kind = "already-judged"
            else:
                st_set = {sts[wi] for wi in un_judged}
                kind = "+".join(sorted(st_set))
        rows.append((key, len(need), lo, hi, kind))

    print("== %s surah %d" % (os.path.basename(path), surah))
    for key, nUnits, lo, hi, kind in rows:
        print("  %-7s units=%d window=[%d:%d) -> %s" % (key, len(need), lo, hi, kind))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
