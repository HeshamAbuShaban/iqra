#!/usr/bin/env python3
"""
Does the optional-final guard remove real false accusations, or does it just
hide errors?

Measured against the reference reciter's own recordings (the Al-Dosari dumps in
engine/corpus/out). A word the register does not require you to finish must
never be judged WRONG by the shipped table - not by a user, and not by the
reference reciter. If it is, the accusation is false by construction and the
guard is removing it, not hiding an error.

Reports every WRONG word in the dump with:
  - the mushaf text and any final mark that licenses an unsounded final letter
  - whether the substitution sits on that word's LAST expected unit

Those two together are exactly what the new guard demotes. Anything else stays
a verdict.

Run: python3 engine/replay/optional_final.py [surah ...]   (needs corpus dumps)
"""

import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import word_verdicts as wv  # noqa: E402

ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
MUSHAF = os.path.join(ROOT, "android/app/src/main/assets/mushaf.json")
OUT = os.path.join(ROOT, "engine/corpus/out")

# High marks that let a word's final letter go unsounded: ۟ silent alef,
# ۢ dagger alef madd, ۭ sajdah/tanween mark, plus the waqf marks.
OPTIONAL_FINAL_MARKS = set("ۭ۟ۢۖۗۘۙۚۛۜ")


def strip_verse_number(raw):
    return re.sub(r"[\s﻿]*[٠-٩١-٩]+$", "", raw)


def words_by_key():
    m = json.load(open(MUSHAF, encoding="utf-8"))
    out = {}
    for page in m:
        for line in page["lines"]:
            if line.get("type") != "text":
                continue
            for w in line["words"]:
                s, a, i = (int(x) for x in w["location"].split(":"))
                out[(s, a, i)] = strip_verse_number(w["word"])
    return out


def n_ayat_of(table, surah):
    best = 0
    for k in table:
        p = k.split(":")
        if int(p[0]) == surah:
            best = max(best, int(p[1]))
    return best


def local_window(syms, ref, cursor, back=300, fwd=600):
    """Best window search restricted to a band around the cursor.

    wv.find_window scans the WHOLE emission list for every ayah, which is
    O(len(emissions)) align calls per ayah. On Al-Baqarah - 24,891 emissions -
    that is minutes per surah and made a whole-corpus measurement impossible.
    The corpus is sequential (104 of 114 surahs complete in order), so the
    window for ayah a+1 starts at or after ayah a's. Searching a band around
    the cursor finds the same window a full scan would, at a fraction of the
    cost, and the sweep below reports how many ayat were judged so a bad
    cursor cannot quietly shrink the measurement.
    """
    L = len(ref)
    best = (0.0, -1, 0)
    for s in range(max(0, cursor - back), min(max(1, len(syms) - L), cursor + fwd)):
        sl = syms[s:s + L + 12]
        if len(sl) < L:
            continue
        _m, _w, _r2q, _e, hits, _n = wv.align(sl, ref, list(range(L)))
        c = hits / float(L)
        if c > best[0]:
            best = (c, s, L + 12)
    return best


def main():
    surahs = [int(a) for a in sys.argv[1:]] or [67]
    table = json.load(open(os.path.join(wv.W, "ordered_quran_phonemes.json"), encoding="utf-8"))
    tok = wv.make_tokenizer(wv.load_units())
    W = words_by_key()

    total_wrong = covered = 0
    rows = []
    ayat_seen = 0
    ayat_total = 0
    for surah in surahs:
        dump_path = os.path.join(OUT, "%03d.json" % surah)
        if not os.path.isfile(dump_path):
            print("skip surah %d: no dump at %s" % (surah, dump_path))
            continue
        dump = json.load(open(dump_path, encoding="utf-8"))
        syms = [e["symbol"] for e in dump["emissions"]]
        n_ayat = n_ayat_of(table, surah)
        ayat_total += n_ayat
        exp = wv.build_expected(table, tok, surah, n_ayat)
        cursor = 0

        for a in range(1, n_ayat + 1):
            ref, unit_word, nwords = exp[a]
            cov, s, ln = local_window(syms, ref, cursor)
            if cov < 0.9 or s < 0:
                continue
            ayat_seen += 1
            window = syms[s:s + ln]
            m0, w0, r2q, _e0, _h0, _n0 = wv.align(window, ref, unit_word)
            hit = [q for q in r2q if q >= 0]
            if hit:
                window = window[min(hit):max(hit) + 1]
                m0, w0, r2q, _e0, _h0, _n0 = wv.align(window, ref, unit_word)
                cursor = s + max(hit) + 1
            st = wv.statuses_from(m0, w0, unit_word, nwords)

            for wi, status in st.items():
                if status != "WRONG":
                    continue
                total_wrong += 1
                key = (surah, a, wi + 1)
                txt = W.get(key, "")
                marks = [c for c in txt if c in OPTIONAL_FINAL_MARKS]
                last_unit = None
                for k, owner in enumerate(unit_word):
                    if owner == wi:
                        last_unit = k
                at_end = last_unit is not None and bool(w0[last_unit])
                guarded = bool(marks) and at_end
                if guarded:
                    covered += 1
                rows.append(("%d:%d:%d" % key, txt, "".join(marks) or "-", at_end, guarded))

    if total_wrong == 0:
        print("No WRONG words in these dumps, so this measurement cannot justify")
        print("anything. Run more surahs before trusting the guard.")
        return 0

    print("Reference reciter judged WRONG: %d words over %d ayat judged of %d"
          % (total_wrong, ayat_seen, ayat_total))
    print("Removed by the optional-final guard: %d (%.0f%%)" % (covered, 100.0 * covered / total_wrong if total_wrong else 0.0))
    print()
    for key, txt, marks, at_end, guarded in rows:
        tag = "GUARDED" if guarded else ("mark but not final" if marks else "-")
        print("  %-11s %-24s marks=%-3s wrongAtFinalUnit=%-5s %s" % (key, txt, marks, at_end, tag))
    print()
    print("A GUARDED row is a word the reference reciter was accused on. The")
    print("guard therefore removes false accusations; it does not hide errors,")
    print("because a garbled middle is not the final unit and stays a verdict.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
