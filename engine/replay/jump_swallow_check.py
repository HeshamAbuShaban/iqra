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
    spans = rec.get("spans") or {}
    # The swallow is cross-surah by construction: a jump on a multi-surah page
    # lands in a surah whose sibling's ayat were never arrived at. Every surah
    # the record's own arrival and span logs mention gets checked, not just the
    # one the record says it stands on.
    keys = set(arr) | set(spans)
    surahs = sorted({int(k.split(":")[0]) for k in keys})

    wv_md = wv.load_units()
    unit_set = set(wv_md)

    rows_by_key = {}
    for w in (rec.get("words") or []):
        if isinstance(w, dict):
            rows_by_key[w["key"]] = w.get("st", "")

    for surah in surahs:
        rows = []
        n_ayat = sum(1 for a in range(1, 300) if ("%d:%d" % (surah, a)) in table)
        for a in range(1, n_ayat + 1):
            key = "%d:%d" % (surah, a)
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
                decided = set()
                for wi in sorted(sts):
                    st = (rows_by_key.get("%d:%d:%d" % (surah, a, wi + 1)) or "").strip()
                    if st in ("CORRECT", "WRONG"):
                        decided.add(wi)
                un_decided = [wi for wi in sorted(sts) if wi not in decided]
                if not un_decided:
                    kind = "already-decided"
                else:
                    st_set = sorted({sts[wi] for wi in un_decided})
                    st_col = sorted(rows_by_key.get("%d:%d:%d" % (surah, a, wi + 1), "")
                                    for wi in un_decided if wi not in decided
                                    and rows_by_key.get("%d:%d:%d" % (surah, a, wi + 1)))
                    kind = "+".join(st_set)
                    if st_col and st_col != ["-"]:
                        kind += " (written:%s)" % ",".join(st_col)
            rows.append((key, len(need), lo, hi, kind))

        print("== %s surah %d" % (os.path.basename(path), surah))
        for key, nUnits, lo, hi, kind in rows:
            print("  %-7s units=%d window=[%d:%d) -> %s" % (key, len(need), lo, hi, kind))
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: jump_swallow_check.py <record.json>")
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
