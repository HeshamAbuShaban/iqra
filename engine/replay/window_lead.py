#!/usr/bin/env python3
"""
Is the first word of an ayah falsely accused because the window includes the
previous ayah's tail?

ayahObs() deliberately starts the window at the PREVIOUS ayah's arrival:

    val lo = max(ayahArrival["$surah:${ayah - 1}"] ?: 0, emissionBase)

with the note that the overlap is deliberate, because on arriving at ayah N you
are already 60% of the way through it. Against [arrival(N), arrival(N+1)) only
5.3% of words reached the CORRECT bar; against [arrival(N-1), arrival(N+1)) 93.4%
did. That measurement is not in question.

What it did not count is the cost on the OTHER side of the window. A whole-corpus
sweep over 6,112 ayat of reference audio found 768 WRONG verdicts on a reciter
who made none, and 320 of them - 42% - are the FIRST word of their ayah. The
window hands the aligner ayah N-1's tail as well as ayah N, and the first word of
N competes with it. Connective openers (وَإِن, وَٱلَّذِينَ) are short and repeated,
so they lose that contest.

This measures the fix rather than arguing it: for each ayah, sweep a leading trim
of the window and count how many of its words reach CORRECT, WRONG and SKIPPED.
If a consistent trim recovers the first word without losing the rest, the rule is
a number and can be implemented. If it does not, the hypothesis is wrong and this
says so.

Run: python3 engine/replay/window_lead.py [surah ...]   (needs corpus dumps)
"""

import json
import os
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import word_verdicts as wv  # noqa: E402

ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
OUT = os.path.join(ROOT, "engine/corpus/out")


def n_ayat_of(table, surah):
    best = 0
    for k in table:
        p = k.split(":")
        if int(p[0]) == surah:
            best = max(best, int(p[1]))
    return best


def local_window(syms, ref, cursor, back=300, fwd=600):
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
    surahs = [int(a) for a in sys.argv[1:]] or [2]
    table = json.load(open(os.path.join(wv.W, "ordered_quran_phonemes.json"), encoding="utf-8"))
    tok = wv.make_tokenizer(wv.load_units())

    # how many words at each trim
    by_trim = defaultdict(lambda: [0, 0, 0])  # correct, wrong, skipped
    first_by_trim = defaultdict(lambda: [0, 0, 0])
    open_by_trim = defaultdict(lambda: [0, 0, 0])  # words 2..n
    by_offset = defaultdict(lambda: [0, 0, 0])
    first_by_off = defaultdict(lambda: [0, 0, 0])
    open_by_off = defaultdict(lambda: [0, 0, 0])
    prev_offsets = []

    for surah in surahs:
        dump_path = os.path.join(OUT, "%03d.json" % surah)
        if not os.path.isfile(dump_path):
            print("skip surah %d: no dump" % surah)
            continue
        dump = json.load(open(dump_path, encoding="utf-8"))
        syms = [e["symbol"] for e in dump["emissions"]]
        n_ayat = n_ayat_of(table, surah)
        exp = wv.build_expected(table, tok, surah, n_ayat)
        cursor = 0
        for a in range(1, n_ayat + 1):
            ref, unit_word, nwords = exp[a]
            prev_ref, prev_word, _pn_ = exp[a - 1] if a > 1 else (None, None, 0)
            cov, s, ln = local_window(syms, ref, cursor)
            if cov < 0.9 or s < 0:
                continue
            window = syms[s:s + ln]
            m0, w0, r2q, _e, _h, _n = wv.align(window, ref, unit_word)
            hit = [q for q in r2q if q >= 0]
            if not hit:
                continue
            window = window[min(hit):max(hit) + 1]
            cursor = s + max(hit) + 1
            if len(window) < 6:
                continue

            for trim in (0, 1, 2, 3, 5, 8):
                sub = window[trim:]
                m, w, _r, _e2, _h2, _nw = wv.align(sub, ref, unit_word)
                st = wv.statuses_from(m, w, unit_word, nwords)
                slot = st.get(0, "UNKNOWN")
                by_trim[trim][0 if slot == "CORRECT" else 1 if slot == "WRONG" else 2] += 1
                for wi, status in st.items():
                    bucket = first_by_trim if wi == 0 else open_by_trim
                    bucket[trim][0 if status == "CORRECT" else 1 if status == "WRONG" else 2] += 1

            # The leading OFFSET, rather than a fixed trim: align the PREVIOUS
            # ayah inside this same window, and start after the last symbol it
            # consumed. That is the part of the window that is not this ayah,
            # and it is the only boundary the overlap actually needs.
            if prev_ref is None:
                continue
            pm, pw, pr2q, _pe, _ph, _pn = wv.align(window, prev_ref, prev_word)
            used = [q for q in pr2q if q >= 0]
            off = (max(used) + 1) if used else 0
            off = min(max(off, 0), max(0, len(window) - 3))
            sub = window[off:]
            m, w, _r, _e2, _h2, _nw = wv.align(sub, ref, unit_word)
            st = wv.statuses_from(m, w, unit_word, nwords)
            slot = st.get(0, "UNKNOWN")
            by_offset[off][0 if slot == "CORRECT" else 1 if slot == "WRONG" else 2] += 1
            for wi, status in st.items():
                bucket = first_by_off if wi == 0 else open_by_off
                bucket[off][0 if status == "CORRECT" else 1 if status == "WRONG" else 2] += 1
            prev_offsets.append(off)

    if not by_trim:
        print("nothing measured")
        return 0
    print("Leading trim sweep over the ayah window (surahs %s)" % surahs)
    print("  trim   all-aayat CORRECT/WRONG/SKIP     word0 CORRECT/WRONG/SKIP     words1+ CORRECT/WRONG/SKIP")
    for t in sorted(by_trim):
        a = by_trim[t]
        f = first_by_trim[t]
        o = open_by_trim[t]
        print("   %2d      %4d/%4d/%4d            %4d/%4d/%4d            %4d/%4d/%4d"
              % (t, a[0], a[1], a[2], f[0], f[1], f[2], o[0], o[1], o[2]))
    print()
    if by_offset:
        tot_c = sum(v[0] for v in by_offset.values())
        tot_w = sum(v[1] for v in by_offset.values())
        tot_s = sum(v[2] for v in by_offset.values())
        f_w0 = sum(v[1] for v in first_by_off.values())
        print("Leading OFFSET from the previous ayah's own alignment:")
        print("  all words:  %d CORRECT / %d WRONG / %d SKIP" % (tot_c, tot_w, tot_s))
        print("  word 0:     %d WRONG" % f_w0)
        print("  (compare trim 0 above: word0 WRONG %d)" % first_by_trim[0][1])
        import statistics
        print("  median offset actually used: %.1f" % statistics.median(prev_offsets))
    print()
    best = max(by_trim, key=lambda t: (by_trim[t][0] - by_trim[t][1]))
    print("The trim that nets the most CORRECT is %d." % best)
    base = by_trim[0]
    b = by_trim[best]
    print("At trim 0: %d CORRECT, %d WRONG, %d SKIP overall." % (base[0], base[1], base[2]))
    print("At trim %d: %d CORRECT, %d WRONG, %d SKIP overall." % (best, b[0], b[1], b[2]))
    fb, fw = first_by_trim[0][1], first_by_trim[best][1]
    print("Word 0 WRONG: %d -> %d." % (fb, fw))
    return 0


if __name__ == "__main__":
    sys.exit(main())
