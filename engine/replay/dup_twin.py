#!/usr/bin/env python3
"""How many of the reference-reciter WRONGs are duplicates-in-window?

The two known cases (device records, 17 October):
  4:2:12  blamed `wu=1000` while all 7 of its units match inside the span-
          trimmed window - the charge was placed in an earlier poll when both
          twins of `amwalahum/amwalakum` (4:2 and 4:3) were visible and the
          window picked the wrong twin.
  76:11:1 blamed `wa-` while 5/6 units matched - `wal aqad` repeats inside
          surah 76 and the window consumed the other twin first.

The mechanism is window-composition, not reciter error: the DP had a legal
place to put the phrase that did not end where the verdict did. This census
measures how big that class COULD be on reference reciter audio, the same
universe optional_final.py swept: every WRONG the final alignment charges,
checked for a twin - the word's exact unit sequence occurring again inside the
REF of the same or the previous ayah (what the in-session judged window can
contain). A twin outside that range cannot cause the class and is not counted.

Rows and counts say: how many of the corpus wrongs had an in-window twin
worth >= TWIN_MIN units, and where the twin was. That bounds the class; it
does not prove a device case for each - per rule 8, a harness number is
evidence of a defect class, not a count of what a user saw.
"""

import collections
import json
import os
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import optional_final as of
import tajweed_sites as tj
import word_verdicts as wv

TABLE = tj.TABLE
OUT = of.OUT
TWIN_MIN = 3  # a twin shorter than 3 units confuses nothing worth blaming
TWIN_MAX_DIFF = 1  # near-twin difference bound (one unit)


def units_for_word(words, wi):
    """The (units, owner-list slice) of word wi in the expected-unit list."""
    flat, uw = [], []
    for wi2, w in enumerate(words):
        for u in w:
            flat.append(u)
            uw.append(wi2)
    i = [j for j, o in enumerate(uw) if o == wi]
    return [flat[j] for j in i], [uw[j] for j in i]


def sweep_one(surah):
    path = os.path.join(OUT, "%03d.json" % surah)
    if not os.path.isfile(path):
        return {"surah": surah, "note": "no dump", "n": 0, "rows": [],
                "twins": 0, "same_prev": {k: 0 for k in
                                          ("same", "prev", "both")}}
    tbl, cls = tj._table()
    surah_ayat = sorted({int(k.split(":")[1]) for k in tbl
                         if int(k.split(":")[0]) == surah})
    dump = json.load(open(path, encoding="utf-8"))
    syms = [e["symbol"] for e in dump["emissions"]]

    W = of.words_by_key()
    rows = []
    twins = 0
    where = collections.Counter()
    for a in surah_ayat:
        words = tbl["%d:%d" % (surah, a)]
        prev = tbl.get("%d:%d" % (surah, a - 1))
        ref = [u for w in words for u in w]
        uw = [wi for wi, w in enumerate(words) for u in w]
        cov, s, ln = of.local_window(syms, ref, 0)
        if cov < 0.9 or s < 0:
            continue
        window = syms[s:s + ln]
        mw, wq, r2q, _e, _h, _n = wv.align(window, ref, uw)
        hit = [q for q in r2q if q >= 0]
        if hit:
            window = window[min(hit):max(hit) + 1]
            mw, wq, r2q, _e, _h, _n = wv.align(window, ref, uw)
        for wi, st in wv.statuses_from(mw, wq, uw, len(words)).items():
            if st != "WRONG":
                continue
            wunits, _u = units_for_word(words, wi)
            key = "%d:%d:%d" % (surah, a, wi + 1)
            if len(wunits) < TWIN_MIN:
                where["short<%d" % TWIN_MIN] += 1
                continue
            phrase = tuple(wunits)
            m = len(phrase)
            same = prev_t = 0
            why = "exact"
            for kind, other in (("same", words), ("prev", prev or [])):
                if not other:
                    continue
                flat = [u for w2 in other for u in w2]
                n = len(flat)
                count = 0
                for i in range(n - m + 1):
                    if tuple(flat[i:i + m]) == phrase:
                        count += 1
                        continue
                    # near-twin: same length, one unit different (amwA-lahum/
                    # amwalakum; fa-wa-qa-hum vs wa-la-qad). Longer than
                    # TWIN_MAX_DIFF units is a different phrase, not a twin.
                    diff = sum(1 for x, y in zip(flat[i:i + m], phrase)
                               if x != y)
                    if diff <= TWIN_MAX_DIFF:
                        count += 1
                # count only EXTRA occurrences beyond the word's own
                own = 0 if kind == "prev" else 1
                extra = max(0, count - own)
                if extra:
                    if kind == "same":
                        same += extra
                    else:
                        prev_t += extra
            kinds = ("both" if (same and prev_t) else
                     "same" if same else "prev" if prev_t else "-")
            if kinds != "-":
                twins += 1
                where[kinds] += 1
                rows.append((key, " ".join(wunits), kinds, extra_str(same, prev_t)))
            else:
                where["no-twin"] += 1
    return {"surah": surah, "n": len(rows) + where["no-twin"] + sum(
        v for k, v in where.items() if k.startswith("short")), "rows": rows,
        "twins": twins, "same_prev": dict(where)}


def extra_str(same, prev_t):
    parts = []
    if same:
        parts.append("same x%d" % same)
    if prev_t:
        parts.append("prev x%d" % prev_t)
    return ", ".join(parts)


def main():
    surahs = [int(a) for a in sys.argv[1:]] or list(range(1, 115))
    total = twins = 0
    all_rows = []
    counter = collections.Counter()
    with ProcessPoolExecutor(max_workers=min(8, len(surahs))) as pool:
        futs = {pool.submit(sweep_one, s): s for s in sorted(surahs, reverse=True)}
        for fut in as_completed(futs):
            r = fut.result()
            if "note" in r:
                print("surah %03d: not measurable (%s)" % (r["surah"], r["note"]))
                continue
            total += r["n"]
            twins += r["twins"]
            counter.update(r["same_prev"])
            all_rows.extend(r["rows"])
            print("surah %03d: %d wrong %d twin" % (r["surah"], r["n"], r["twins"]))
    print()
    print("WRONG on reference audio: %d words" % total)
    print("with an in-window twin (>= %d units): %d" % (TWIN_MIN, twins))
    print("  %s" % "  ".join("%s=%s" % kv for kv in sorted(counter.items())))
    print()
    for key, phrase, kinds, where_s in sorted(all_rows)[:40]:
        print("  %-11s %-38s %s (%s)" % (key, phrase[:38], kinds, where_s))
    return 0


if __name__ == "__main__":
    sys.exit(main())
