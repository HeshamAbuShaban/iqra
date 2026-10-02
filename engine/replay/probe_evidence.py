#!/usr/bin/env python3
"""TASK 1/2 final evidence: the "room" argument + exact unit mapping."""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lock_trace as L  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

CASES = [
    # sur, n_ay, frm, to, t_prev, t_jump, t_next
    (8, 75, 68, 70, 1360.75, 1410.50, 1420.75),
    (52, 49, 5, 7, 22.50, 28.25, 32.00),
    (54, 55, 37, 39, 281.25, 287.25, 294.00),
    (74, 56, 30, 38, 186.25, 197.50, 202.00),
    (80, 42, 17, 19, 64.75, 71.00, 73.75),
    (81, 29, 13, 15, 45.50, 55.50, 59.25),
]


def main():
    tbl = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    print("ROOM ARGUMENT: could the skipped ayat physically fit in the window "
          "the lock was sitting on?")
    print("(coverage <= available_symbols / skipped_units, so if the right "
          "side is <1 the skip is arithmetically impossible)\n")
    print("%-7s %-22s %8s %8s %8s %10s" %
          ("surah", "skipped set", "units", "win_sym", "win_sec", "ceiling"))
    for sur, n_ay, frm, to, tp, tj, tn in CASES:
        exp = L.expected_all(tbl, tok, sur, n_ay)
        d = json.load(open(os.path.join(ROOT, "engine/corpus/out/%03d.json" % sur)))
        w = [e["symbol"] for e in d["emissions"] if tp <= e["audio_sec"] < tn]
        skipped = list(range(frm + 1, to))
        units = sum(len(exp[a][0]) for a in skipped if a in exp)
        ceil = len(w) / float(units)
        print("%-7s %-22s %8d %8d %8.1f %9.2fx  %s" % (
            sur, "%d-%d" % (skipped[0], skipped[-1]) if skipped else "-",
            units, len(w), tn - tp, ceil,
            "IMPOSSIBLE to have been recited here" if ceil < 1.0
            else "fits; must be decided on content"))
        # also: the span strictly between arriving on `from` and the jump
        w2 = [e["symbol"] for e in d["emissions"] if tp <= e["audio_sec"] < tj]
        u2 = sum(len(exp[a][0]) for a in skipped if a in exp)
        print("        [arrival, jump) only: %d symbols for %d units -> %.2fx"
              % (len(w2), u2, len(w2) / float(u2)))

    print("\n\nUNIT MAPPING for the skipped ayah, over the jump window")
    print("(global alignment, exactly what lock_trace.coverage() does)\n")
    for sur, n_ay, frm, to, tp, tj, tn in CASES:
        exp = L.expected_all(tbl, tok, sur, n_ay)
        d = json.load(open(os.path.join(ROOT, "engine/corpus/out/%03d.json" % sur)))
        w = [(e["audio_sec"], e["symbol"]) for e in d["emissions"]
             if tp <= e["audio_sec"] < tn]
        obs = [s for _, s in w]
        ts = [t for t, _ in w]
        for a in range(frm + 1, to):
            u = exp[a][0]
            cov, hits, n = L.coverage(obs, u)
            matched, wrong, r2q, _e, _h, _n = L.align(obs, u, list(range(n)))
            hit_idx = [i for i, m in enumerate(matched) if m]
            print("  %d:%d  cov=%.3f (%d/%d)  matched=%s"
                  % (sur, a, cov, hits, n, hit_idx))
            if hit_idx:
                pos = [(i, round(ts[r2q[i]], 2)) for i in hit_idx if r2q[i] >= 0]
                print("        unit -> t(s): %s" % pos)
                span = [p[1] for p in pos]
                print("        matched over %.1fs..%.1fs = %.1fs of a %.1fs "
                      "window; contiguous=%s"
                      % (min(span), max(span), max(span) - min(span),
                         tn - tp,
                         "yes" if (max(span) - min(span)) < 6.0 else "NO"))
        print("  %d:%d (target) cov=%.3f"
              % (sur, to, L.coverage(obs, exp[to][0])[0]))

    # surah 74: the move times right before the jump
    print("\n\nSURAH 74 context around the jump")
    exp = L.expected_all(tbl, tok, 74, 56)
    for a in (28, 29, 30, 31, 38):
        print("  74:%d units=%3d nwords=%2d  %s"
              % (a, len(exp[a][0]), exp[a][2],
                 tbl["74:%d" % a]["aya_text"][:60]))
    tot = sum(len(exp[a][0]) for a in range(31, 38))
    print("  74:31..37 total expected units = %d" % tot)

    # surah 94: what is actually at the end of the clip
    print("\n\nSURAH 94 tail of the recording")
    d = json.load(open(os.path.join(ROOT, "engine/corpus/out/094.json")))
    exp = L.expected_all(tbl, tok, 94, 8)
    print("  audio_sec=%.1f tokens=%d" % (d["audio_sec"], d["tokens"]))
    print("  last 12 emissions:")
    for e in d["emissions"][-12:]:
        print("     t=%.2f frame=%d %s" % (e["audio_sec"], e["frame"],
                                            e["symbol"]))
    for a in (7, 8):
        print("  94:%d units=%d : %s" % (a, len(exp[a][0]),
                                          tbl["94:%d" % a]["aya_text"]))
    for k in (1, 2, 3, 4, 6, 8, 10, 12):
        o = [e["symbol"] for e in d["emissions"][-k:]]
        c8, h8, _ = L.coverage(o, exp[8][0])
        c7, h7, _ = L.coverage(o, exp[7][0])
        print("    last %2d emissions: cov(94:8)=%.3f (%d/11)  cov(94:7)=%.3f"
              % (k, c8, h8, c7))


if __name__ == "__main__":
    main()