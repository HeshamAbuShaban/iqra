#!/usr/bin/env python3
"""Prove the fix: explode expected words into the MODEL's phoneme units.

The app compares model-emitted PHONEMES against table WORDS, so coverage is
identically 0.00. This rebuilds the expected side as the model's own unit
inventory (the 250 units in tokens.txt, which include multi-char units like
'\u06e6\u06e6\u06e6\u06e6', '\u0627\u0627\u0627\u0627\u0627\u0627', '\u0644\u0644\u064e', '\u0631\u0631\u064e') and re-measures coverage.

The symbol-level DP is not written here any more. `coverage` is imported from
`lock_trace`, which is `word_verdicts.align` - the one shipped-equivalent
copy, shared with `lock_policy.py` and `tail_replay_cost.py`. It returns
(coverage, matched, ref_len), exactly what this script always used.
"""
import json
import os
import sys

import lock_trace

HERE = os.path.dirname(os.path.abspath(__file__))
W = os.path.join(HERE, "..", "shootout", "weights", "zipformer")

# the shared DP (word_verdicts.align), not a fourth copy
coverage = lock_trace.coverage
load_units = lock_trace.word_verdicts.load_units
make_tokenizer = lock_trace.word_verdicts.make_tokenizer


def main():
    dump = json.load(open(sys.argv[1]))
    table = json.load(open(os.path.join(W, "ordered_quran_phonemes.json")))
    surah = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    n_ayat = int(sys.argv[3]) if len(sys.argv) > 3 else 7

    units = load_units()
    tok = make_tokenizer(units)
    syms = [e["symbol"] for e in dump["emissions"]]

    # Expected side, per ayah: words -> model phoneme units.
    expected = {}
    for a in range(1, n_ayat + 1):
        words = table["%d:%d" % (surah, a)]["aya_phonemes_list"]
        seq = []
        for w in words:
            seq.extend(tok(w))
        expected[a] = seq

    print("units in vocabulary : %d" % len(units))
    print("expected units per ayah:")
    for a in range(1, n_ayat + 1):
        print("  1:%-3d %2d units   %s" % (a, len(expected[a]), " ".join(expected[a])[:78]))
    print()

    # Where does each ayah actually start in the emission stream? Walk forward
    # and take the best-coverage window, so we can report the TRUE lock rate.
    print("best-coverage window per ayah (scanning the whole emission stream):")
    print("  %-6s %-8s %-10s %-12s" % ("ayah", "cov", "matched", "window"))
    print("  " + "-" * 40)
    total_hits = 0
    for a in range(1, n_ayat + 1):
        ref = expected[a]
        L = len(ref)
        best = (0.0, 0, 0, 0)
        for s in range(0, max(1, len(syms) - L)):
            sl = syms[s:s + L + 12]
            c, m, n = coverage(sl, ref)
            if c > best[0]:
                best = (c, m, n, s)
        total_hits += 1 if best[0] >= 0.5 else 0
        print(
            "  1:%-4d %-8.2f %-10s syms[%d:%d]"
            % (a, best[0], "%d/%d" % (best[1], best[2]), best[3], best[3] + L + 12)
        )
    print()
    print("ayat with coverage >= 0.50 anywhere in the stream: %d/%d" % (total_hits, n_ayat))


if __name__ == "__main__":
    main()
