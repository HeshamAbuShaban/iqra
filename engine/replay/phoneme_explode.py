#!/usr/bin/env python3
"""Prove the fix: explode expected words into the MODEL's phoneme units.

The app compares model-emitted PHONEMES against table WORDS, so coverage is
identically 0.00. This rebuilds the expected side as the model's own unit
inventory (the 250 units in tokens.txt, which include multi-char units like
'ۦۦۦۦ', 'اااااا', 'للَ', 'ررَ') and re-measures coverage.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
W = os.path.join(HERE, "..", "shootout", "weights", "zipformer")


def load_units():
    units = {}
    with open(os.path.join(W, "tokens.txt")) as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            sym, _idx = line.rsplit(" ", 1)
            units[sym] = int(_idx)
    return units


def make_tokenizer(units):
    # Greedy longest-match: units are multi-codepoint and ambiguous if scanned
    # left-to-right shortest-first (e.g. 'ا' vs 'اا' vs 'اااااا').
    ordered = sorted(units.keys(), key=len, reverse=True)
    maxlen = max(len(u) for u in ordered)

    def tok(s):
        out = []
        i = 0
        while i < len(s):
            for u in ordered:
                if s.startswith(u, i):
                    out.append(u)
                    i += len(u)
                    break
            else:
                i += 1  # unmapped codepoint (diacritic we do not model)
        return out

    return tok


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

    # DP coverage: fraction of expected units aligned 1:1 to a query slice.
    def coverage(query, ref):
        n, m = len(ref), len(query)
        if not n or not m:
            return 0.0, 0, n
        prev = list(range(m + 1))
        dirs = []
        for i in range(1, n + 1):
            cur = [i] + [0] * m
            row = bytearray(m + 1)
            for j in range(1, m + 1):
                sub = prev[j - 1] + (0 if ref[i - 1] == query[j - 1] else 1)
                dele = prev[j] + 1
                ins = cur[j - 1] + 1
                best, d = sub, 0
                if dele < best:
                    best, d = dele, 1
                if ins < best:
                    best, d = ins, 2
                cur[j] = best
                row[j] = d
            dirs.append(row)
            prev = cur
        matched = 0
        i, j = n, m
        while i > 0 and j > 0:
            d = dirs[i - 1][j]
            if d == 0:
                if ref[i - 1] == query[j - 1]:
                    matched += 1
                i -= 1
                j -= 1
            elif d == 1:
                i -= 1
            else:
                j -= 1
        return matched / float(n), matched, n

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
