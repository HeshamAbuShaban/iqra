#!/usr/bin/env python3
"""Simulate the lock policy over a real token dump.

The app already contains the RIGHT primitive: alignToWords() is a symbol-level
Levenshtein DP that reports, per expected phoneme, whether the emission matched
it. The broken part is the scalar ayah score it gates on (ratio vs whole ayah).

This simulates the proposed policy: lock advances when the emission slice
SINCE THE LAST LOCK covers enough of the next ayah's phonemes.

Ground truth for a surah recording is simply 1, 2, 3, ... n_ayat in order.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PHONEMES = os.path.join(HERE, "..", "shootout", "weights", "zipformer", "ordered_quran_phonemes.json")
TOKENS = os.path.join(HERE, "..", "shootout", "weights", "zipformer", "tokens.txt")


def load_units():
    units = {}
    with open(TOKENS) as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            sym, _idx = line.rsplit(" ", 1)
            units[sym] = int(_idx)
    return units


def make_tokenizer(units):
    ordered = sorted(units.keys(), key=len, reverse=True)

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
                i += 1
        return out

    return tok


def coverage(query, ref):
    """Symbol-level DP. Returns (coverage, matched_count, ref_len).

    coverage = fraction of ref symbols aligned 1:1 with a query symbol.
    This mirrors alignToWords' matched[] bookkeeping, minus the per-word
    bookkeeping we do not need for a lock decision.
    """
    n, m = len(ref), len(query)
    if m == 0 or n == 0:
        return 0.0, 0, n
    # dp over query rows
    prev = list(range(m + 1))
    dirs = []
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        row = bytearray(m + 1)
        for j in range(1, m + 1):
            sub = prev[j - 1] + (0 if ref[i - 1] == query[j - 1] else 1)
            dele = prev[j] + 1        # ref symbol unmatched (deletion)
            ins = cur[j - 1] + 1      # query symbol extra (insertion)
            best = sub
            d = 0
            if dele < best:
                best, d = dele, 1
            if ins < best:
                best, d = ins, 2
            cur[j] = best
            row[j] = d
        dirs.append(row)
        prev = cur
    # backtrack, count exact diagonal matches
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


def main():
    dump = json.load(open(sys.argv[1]))
    table = json.load(open(PHONEMES))
    surah = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    n_ayat = int(sys.argv[3]) if len(sys.argv) > 3 else 7
    thresh = float(sys.argv[4]) if len(sys.argv) > 4 else 0.55
    need = int(sys.argv[5]) if len(sys.argv) > 5 else 2

    syms = [e["symbol"] for e in dump["emissions"]]
    times = [e["audio_sec"] for e in dump["emissions"]]
    refs = {}
    tok = make_tokenizer(load_units())
    for a in range(1, n_ayat + 1):
        seq = []
        for w in table["%d:%d" % (surah, a)]["aya_phonemes_list"]:
            seq.extend(tok(w))
        refs[a] = seq

    print("policy: advance when coverage(lock+1) >= %.2f for %d consecutive frames" % (thresh, need))
    print()
    lock = 1
    slice_start = 0
    streak = 0
    streak_key = None
    events = []
    seen = 0

    for idx in range(len(syms)):
        seen = idx + 1
        if lock >= n_ayat:
            break
        nxt = lock + 1
        sl = syms[slice_start:seen]
        cov, matched, rlen = coverage(sl, refs[nxt])
        if cov >= thresh:
            if streak_key == nxt:
                streak += 1
            else:
                streak_key = nxt
                streak = 1
            if streak >= need:
                events.append((times[idx], lock, nxt, cov, matched, rlen))
                lock = nxt
                slice_start = seen
                streak = 0
                streak_key = None
        else:
            streak = 0
            streak_key = None

    print("  %-8s %-6s %-6s %-8s %-14s" % ("time", "from", "to", "coverage", "phonemes"))
    print("  " + "-" * 44)
    for t, a, b, cov, m, rl in events:
        print("  %-8.2f 1:%-4d 1:%-4d %-8.2f %d/%d" % (t, a, b, cov, m, rl))

    print()
    reached = events[-1][2] if events else 0
    correct = [e for e in events if e[2] == e[1] + 1]
    print("LOCK ACCURACY : %d/%d ayat locked, all sequential: %s" % (reached, n_ayat, "yes" if reached == n_ayat and not events[:-1] else "see table"))
    print("final lock    : 1:%d (truth 1:%d)" % (lock, n_ayat))
    if not events:
        print("!! NEVER LOCKED — coverage never reached %.2f" % thresh)


if __name__ == "__main__":
    main()
