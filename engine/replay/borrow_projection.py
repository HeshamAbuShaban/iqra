#!/usr/bin/env python3
"""
The per-word borrow test, projected before it is built.

The whole-window borrow test was measured and rejected: it fired in 621 of
621 guard firings, turned the first-word fallback off, and cost 25 sound
CORRECT verdicts across three sessions. The next shape the docs name is the
test PER WORD: a charge on a word of ayah A is a borrow charge when the
heard symbol it was laid on sits BEFORE ayah A's own arrival index - audio
the ayah had not even started receiving when the charge was made.

This tool projects exactly that rule on the pulled device records, without
touching the engine: for every WRONG word with a reconstructable window it
re-runs the app's own alignment, takes each charge's heard symbol absolute
index, and asks where it falls relative to the ayah's arrival.

Three numbers decide whether the rule is worth building:

  charges_dropped   charges the rule would remove (its purpose)
  words_flipped     WRONG words whose every charge is dropped - they would
                    stop being accusations entirely
  supported_lost    charges on words whose charge REPRODUCES in the window
                    (the honest-charge class) that the rule would still drop
                    - the rule's cost, the thing that killed the whole-window
                    version

A charge whose ayah has no surviving arrival is counted in no rule arm (the
record cannot say; refusing beats guessing, same as everywhere else).

Run: python3 engine/replay/borrow_projection.py <record.json> [...]
"""

import json
import sys
from pathlib import Path
from collections import Counter

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import device_blame as db  # noqa: E402
import word_verdicts as wv  # noqa: E402

TABLE = (HERE.parents[1] / "android/app/src/main/assets"
         / "word_aligned_phonemes.json")


def project(record):
    ems = record.get("emissions")
    if not ems:
        return None, "no emission stream"
    table = json.load(open(TABLE, encoding="utf-8"))
    try:
        unit_set = set(wv.load_units())
    except FileNotFoundError:
        return None, "no token table fetched"
    arrivals = record.get("arrivals") or {}
    base = record.get("emissionBase", 0)

    stats = Counter()
    details = []
    for w in record.get("words", []):
        if w.get("st") != "WRONG":
            continue
        s_, a_, i_ = (int(v) for v in w["key"].split(":"))
        wlist = table.get("%d:%d" % (s_, a_))
        if not wlist or i_ > len(wlist):
            stats["no_table_row"] += 1
            continue
        lo, hi, why = db.judged_span(record, s_, a_, w.get("wt"))
        if why:
            stats["window_unreconstructable"] += 1
            continue
        span = ems[lo - base:hi - base]
        flat = [u for wd in wlist for u in wd]
        uw2 = [j for j, wd in enumerate(wlist) for _ in wd]
        got_idx = [(e, lo + k) for k, e in enumerate(span) if e in unit_set]
        got = [e for e, _ in got_idx]
        try:
            _m, wrong, r2q, _e, _h, _nw = wv.align(
                [db.app_key(e) for e in got], [db.app_key(u) for u in flat], uw2)
        except Exception:
            stats["align_failed"] += 1
            continue
        arrival = arrivals.get("%d:%d" % (s_, a_))
        supported = any(
            uw2[k] == i_ - 1 and wrong[k] and r2q[k] is not None and r2q[k] >= 0
            for k in range(len(flat)))
        charges, kept, dropped, unknown = 0, 0, 0, 0
        margins = []
        for k in range(len(flat)):
            if uw2[k] != i_ - 1 or not wrong[k]:
                continue
            charges += 1
            q = r2q[k]
            if q is None or q < 0:
                unknown += 1          # charge with nothing aligned: no rule
                continue
            heard_abs = got_idx[q][1]
            if arrival is None:
                unknown += 1          # record cannot say: no rule
            elif heard_abs < arrival:
                dropped += 1          # the rule fires: borrow
                margins.append(arrival - heard_abs)
            else:
                kept += 1
        # A reproduced charge (a real heard symbol): every dropped charge is
        # one by construction, so the cost question is not "supported vs not"
        # but HOW FAR before the ayah's arrival the charge sat - a margin of
        # 1-5 symbols is poll-granularity ambiguity, 6+ is the previous
        # ayah's audio.
        stats["charges_total"] += charges
        stats["charges_kept"] += kept
        stats["charges_dropped"] += dropped
        stats["charges_unrulable"] += unknown
        for m in margins:
            key = "margin_1-5" if m <= 5 else "margin_6-15" if m <= 15 else "margin_16-31+"
            stats[key] += 1
        if charges and dropped == charges:
            stats["words_flipped_to_clean"] += 1
            if supported:
                details.append((w["key"], w.get("wu"), arrival,
                                [got_idx[r2q[k]][1] for k in range(len(flat))
                                 if uw2[k] == i_ - 1 and wrong[k]
                                 and r2q[k] is not None and r2q[k] >= 0]))
        elif dropped:
            stats["words_partially_dropped"] += 1
    return stats, details


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    totals = Counter()
    for path in sys.argv[1:]:
        rec = json.load(open(path, encoding="utf-8"))
        stats, details = project(rec)
        if stats is None:
            print("%s: skipped - %s" % (Path(path).name, details))
            continue
        print("%s: %s" % (Path(path).name,
                          ", ".join("%s %d" % kv for kv in stats.most_common())))
        for key, wu, arrival, idxs in details[:6]:
            print("   supported charge dropped: %s wu=%s arrival=%s heard@%s"
                  % (key, wu, arrival, idxs))
        totals.update(stats)
    print("\nAll records: %s" % ", ".join(
        "%s %d" % kv for kv in totals.most_common()))
    print("The rule only ever removes charges from words already judged "
          "WRONG, so it cannot cost a CORRECT verdict by construction - the "
          "whole-window test's 25 lost CORRECTs have no analogue here. The "
          "open question is the margin_1-5 bucket: charges that could be "
          "real mistakes whose arrival stamp lags by a poll.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
