#!/usr/bin/env python3
"""TASK 1 probe, part 2: a DISCRIMINATING test for "was the skipped ayah recited".

lock_trace.coverage() is a GLOBAL alignment of the whole observation window
against the whole ayah, so a 30 s window will find any short ayah's units
scattered anywhere in it and report cov=1.00.  That is not evidence of
presence.  This probe adds `local_cov`, the best CONTIGUOUS read of an ayah
anywhere in a window (semi-global in the query: free start, free end, global
in the ref), plus a null baseline: the same measure for the same ayah over
unrelated windows of the same length in the same surah.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lock_trace as L  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

SUB, GO, GE = 1, 1, 1  # plain edit costs, same shape as word_verdicts.align


def local_cov(ref, obs):
    """Best contiguous read of `ref` inside `obs`.

    Returns (hits/ref_len, hits, j_start, j_end) of the best-scoring
    monotone match, where obs[j_start:j_end] is the consumed span.
    """
    n, m = len(ref), len(obs)
    if not n or not m:
        return 0.0, 0, 0, 0
    INF = float("inf")
    M = [[INF] * (m + 1) for _ in range(n + 1)]
    D = [[INF] * (m + 1) for _ in range(n + 1)]
    pm = [[0] * (m + 1) for _ in range(n + 1)]
    pd = [[0] * (m + 1) for _ in range(n + 1)]
    M[0][0] = 0
    for j in range(m + 1):
        D[0][j] = 0          # free query start
    for i in range(1, n + 1):
        D[i][0] = GO + (i - 1) * GE
        pd[i][0] = 1
    for i in range(1, n + 1):
        ri = ref[i - 1]
        Mi, Mp, Dp = M[i], M[i - 1], D[i - 1]
        Mi[0] = INF
        for j in range(1, m + 1):
            best, src = M[i - 1][j - 1], 0
            if D[i - 1][j - 1] < best:
                best, src = D[i - 1][j - 1], 1
            Mi[j] = best + (0 if ri == obs[j - 1] else SUB)
            pm[i][j] = src
            if Mi[j - 1] + GO <= D[i][j - 1] + GE:
                D[i][j] = Mi[j - 1] + GO
                pd[i][j] = 0
            else:
                D[i][j] = D[i][j - 1] + GE
                pd[i][j] = 1
    # free query end: best over all j on the final ref row
    bestj, bestc = 0, INF
    for j in range(m + 1):
        c = M[n][j]
        if c < bestc:
            bestc, bestj = c, j
    # traceback counting hits
    i, j, hits, j_start = n, bestj, 0, bestj
    while i > 0 and j > 0:
        if pm[i][j] == 0 or pm[i][j] == 1:
            if ref[i - 1] == obs[j - 1]:
                hits += 1
            j -= 1
            i -= 1
        elif pd[i][j] == 0:
            j -= 1
        elif pd[i][j] == 1:
            i -= 1
        else:
            break
    return hits / float(n), hits, j_start, bestj


def syms(dump, lo, hi):
    return [(e["audio_sec"], e["symbol"]) for e in dump["emissions"]
            if lo <= e["audio_sec"] < hi]


def main():
    tbl = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    JUMPS = [
        (8, 75, 68, 70, 1410.50, 1360.75, 1420.75),
        (52, 49, 5, 7, 28.25, 22.50, 32.00),
        (54, 55, 37, 39, 287.25, 281.25, 294.00),
        (74, 56, 30, 38, 197.50, 186.25, 202.00),
        (80, 42, 17, 19, 71.00, 64.75, 73.75),
        (81, 29, 13, 15, 55.50, 45.50, 59.25),
    ]
    for sur, n_ay, frm, to, t_j, t_prev, t_next in JUMPS:
        exp = L.expected_all(tbl, tok, sur, n_ay)
        dump = json.load(open(os.path.join(ROOT, "engine/corpus/out/%03d.json" % sur)))
        dur = t_next - t_prev
        lo, hi = t_prev, t_next
        w = syms(dump, lo, hi)
        obs = [s for _, s in w]
        ts = [t for t, _ in w]
        print("=" * 78)
        print("SURAH %d   jump %d:%d -> %d:%d at t=%.2fs   window [%.2f,%.2f] "
              "(%.1fs, move-to-move interval), %d symbols, %.2f sym/s"
              % (sur, sur, frm, sur, to, t_j, lo, hi, dur, len(obs), len(obs) / dur))
        skipped = list(range(frm + 1, to))
        for a in skipped + [to]:
            ref = exp[a][0]
            cov, hits, js, je = local_cov(ref, obs)
            span = (ts[js], ts[je - 1]) if je > js and js < len(ts) else (0, 0)
            print("  %s %d:%d  nwords=%2d units=%3d  bestcontig=%.3f (%d/%d) "
                  "matched span %.2f..%.2f s (%.2fs)"
                  % ("SKIP" if a in skipped else "TGT ", sur, a, exp[a][2], len(ref),
                     cov, hits, len(ref), span[0], span[1], span[1] - span[0]))
            print("        text: %s" % tbl["%d:%d" % (sur, a)]["aya_text"])
        # null baselines: same ayah, same window LENGTH, elsewhere in the surah
        print("  -- null baseline: bestcontig of the SKIPPED ayah over %0.0fs "
              "windows elsewhere in the same surah --" % dur)
        for a in skipped:
            ref = exp[a][0]
            row = []
            for shift in (-120, -90, -60, -40, -20, 20, 40, 60, 90, 120):
                l2, h2 = lo + shift, lo + shift + dur
                if l2 < 0 or h2 > dump["audio_sec"]:
                    row.append("shift%+d:--" % shift)
                    continue
                o2 = [s for _, s in syms(dump, l2, h2)]
                if len(o2) < 5:
                    row.append("shift%+d:--" % shift)
                    continue
                c, h, _, _ = local_cov(ref, o2)
                row.append("shift%+d:%.2f" % (shift, c))
            print("     %d:%d  %s" % (sur, a, "  ".join(row)))
        # print the raw window symbols
        print("  window symbols:")
        line = []
        for t, s in w:
            line.append("%.2f:%s" % (t, s))
            if len(line) == 8:
                print("     " + "  ".join(line))
                line = []
        if line:
            print("     " + "  ".join(line))


if __name__ == "__main__":
    main()