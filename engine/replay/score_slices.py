#!/usr/bin/env python3
"""Why does the app fail to lock, when the acoustic model is near-perfect?

Takes a real token dump and scores the app's CURRENT matcher against the
fix we intend to use, over the exact slices the app would see.

Current app matcher (Levenshtein.ratio, global + length-normalised):
    score = (lenA + lenB - dist) / (lenA + lenB)
applied to a PARTIAL emission slice vs the WHOLE ayah, gated at 0.60.

Proposed matcher (Levenshtein.fragmentScore, semi-global / free start):
    score = 1 - semiGlobalDistance(slice, ayah) / len(slice)
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PHONEMES = os.path.join(HERE, "..", "shootout", "weights", "zipformer", "ordered_quran_phonemes.json")


def ratio(a, b):
    """python-Levenshtein ratio() - exactly what Levenshtein.ratio does."""
    if not a and not b:
        return 1.0
    la, lb = len(a), len(b)
    d = levenshtein(a, b)
    return (la + lb - d) / (la + lb)


def levenshtein(a, b):
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def semi_global(query, ref):
    """Free-start global alignment: distance from query into ref, free start
    in ref. This is Levenshtein.semiGlobalDistance in the Kotlin code."""
    if not query:
        return 0
    prev = list(range(len(ref) + 1))
    for i, qc in enumerate(query, 1):
        cur = [0] * (len(ref) + 1)  # cur[0] = 0 => free start anywhere
        for j, rc in enumerate(ref, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (qc != rc))
        prev = cur
    return prev[-1]


def fragment_score(query, ref):
    if not query:
        return 0.0
    return max(0.0, 1.0 - semi_global(query, ref) / len(query))


def main():
    dump = json.load(open(sys.argv[1]))
    table = json.load(open(PHONEMES))
    surah = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    n_ayat = int(sys.argv[3]) if len(sys.argv) > 3 else 7

    syms = [e["symbol"] for e in dump["emissions"]]
    print("emitted tokens: %d over %.1fs" % (len(syms), dump["audio_sec"]))
    print()

    # The app's view: an emission SLICE grows between lock moves, and each
    # frame it compares the WHOLE slice so far against each candidate ayah.
    # Simulate progressive prefixes: 25%, 50%, 75%, 100% of the full emission
    # stream, and score the true ayah each time.
    print("slice = first N%% of the emission stream, scored against ayah 1:%d" % 1)
    print()
    header = "  %-8s %-7s %-9s %-9s" % ("prefix", "ntok", "ratio(cur)", "fragment(new)")
    print(header)
    print("  " + "-" * (len(header) - 2))

    target = table["%d:1" % surah]["aya_phonemes_list"]
    target_s = " ".join(target)

    for pct in (20, 40, 60, 80, 100):
        n = max(1, int(len(syms) * pct / 100))
        slice_s = " ".join(syms[:n])
        r_cur = ratio(slice_s, target_s)
        r_new = fragment_score(slice_s, target_s)
        verdict = "LOCK" if r_cur >= 0.60 else "no"
        print("  %3d%%    %-7d %-9.3f %-9.3f  %s" % (pct, n, r_cur, r_new, verdict))

    print()
    print("Per-ayah full-slice scoring (the app scores the WHOLE slice vs EACH candidate):")
    print("  %-8s %-8s %-9s %-9s" % ("ayah", "ntok", "ratio", "fragment"))
    print("  " + "-" * 36)
    whole = " ".join(syms)
    for a in range(1, n_ayat + 1):
        key = "%d:%d" % (surah, a)
        ref = " ".join(table[key]["aya_phonemes_list"])
        print(
            "  1:%-6d %-8d %-9.3f %-9.3f"
            % (a, len(table[key]["aya_phonemes_list"]), ratio(whole, ref), fragment_score(whole, ref))
        )


if __name__ == "__main__":
    main()
