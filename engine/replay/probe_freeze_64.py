#!/usr/bin/env python3
"""TASK 2 probe, part 2: exactly why next_cov is frozen at 0.500 on 55:64.

coverage() is a GLOBAL, MONOTONE, 1:1 alignment of the whole observation
window against the whole ayah. Adding symbols at the END of the window can
only raise hits if it extends the longest monotone matching subsequence.
This probe measures that directly: which of 55:64's 8 units match, at which
obs positions, and whether any longer window could ever have matched more.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lock_trace as L  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def main():
    tbl = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    exp55 = L.expected_all(tbl, tok, 55, 78)
    d = json.load(open(os.path.join(ROOT, "engine/corpus/out/055.json")))
    em = d["emissions"]
    syms = [e["symbol"] for e in em]
    ts = [e["audio_sec"] for e in em]

    stall = json.load(open("/tmp/opencode/probe_freeze_055.json"))
    obs = stall["obs"][-1]
    print("SURAH 55  final lock %s   stall obs at last evaluated poll: %d symbols"
          % (stall["final"], len(obs)))
    print("last move: %s" % stall["moves"][-1])
    print()

    u63 = exp55[63][0]
    u64 = exp55[64][0]
    u65 = exp55[65][0]
    print("55:63 units (%d): %s" % (len(u63), " ".join(u63)))
    print("55:64 units (%d): %s" % (len(u64), " ".join(u64)))
    print("55:65 units (%d): %s" % (len(u65), " ".join(u65)))
    print("55:63 == 55:65 unit lists identical? %s" % (u63 == u65))
    print()

    for a in (62, 63, 64, 65, 66, 67, 68):
        print("  %d:%d units=%d nwords=%d  %s"
              % (55, a, len(exp55[a][0]), exp55[a][2],
                 tbl["55:%d" % a]["aya_text"]))
    print()

    # which units of 55:64 match, and where
    matched, wrong, r2q, ew, hits, _ = L.align(obs, u64, list(range(len(u64))))
    cov, hits2, n = L.coverage(obs, u64)
    print("coverage(obs, 55:64) = %d/%d = %.4f" % (hits, n, cov))
    print("  matched unit idx : %s" % [i for i, m in enumerate(matched) if m])
    print("  WRONG  unit idx  : %s" % [i for i, w in enumerate(wrong) if w])
    print("  unmatched idx    : %s" % [i for i, m in enumerate(matched) if not m])
    for i, u in enumerate(u64):
        j = r2q[i]
        print("    unit %d %-6s -> obs[%s] = %-6s   %s"
              % (i, u, j, obs[j] if 0 <= j < len(obs) else "-",
                 "MATCH" if matched[i] else ("wrong" if wrong[i] else "-")))
    print()

    # the LONGEST monotone matching subsequence: can a longer obs ever do better?
    print("how the count evolves with window length (prefixes of the stall obs):")
    for k in (1, 2, 3, 4, 5, 6, 8, 10, 15, 20, 30, 50, 80, 120, 160, 200, 240,
              len(obs)):
        c, h, _ = L.coverage(obs[:k], u64)
        ch, hh, _ = L.coverage(obs[:k], u63)
        print("   obs[:%3d] -> 55:64 cov=%.3f (%d/8)   55:63 cov=%.3f"
              % (k, c, h, ch))
    print()

    # 55:64 coverage over TIGHT windows in the raw stream, not the long one
    print("55:64 coverage over tight windows of the RAW stream:")
    print("   (where does 'مدهآمتانين' actually sit?)")
    for lo in range(425, 530, 5):
        w = [s for s, t in zip(syms, ts) if lo <= t < lo + 5]
        if len(w) < 3:
            continue
        c, h, _ = L.coverage(w, u64)
        c2, h2, _ = L.coverage(w, u63)
        c3, h3, _ = L.coverage(w, u65)
        flag = "  <== 55:64 local max" if h >= 4 else ""
        print("   [%3d,%3d) n=%3d  55:63=%.3f  55:64=%.3f (%d/8)  55:65=%.3f%s"
              % (lo, lo + 5, len(w), c2, c, h, c3, flag))
    print()

    # null: 55:64 elsewhere in the recording
    print("null baseline: coverage of 55:64 over 5 s windows across the WHOLE file")
    best = (0.0, 0)
    for lo in range(0, int(d["audio_sec"]) - 5, 5):
        w = [s for s, t in zip(syms, ts) if lo <= t < lo + 5]
        if len(w) < 3:
            continue
        c, h, _ = L.coverage(w, u64)
        if h > best[0]:
            best = (h, lo)
    print("   best 5 s window anywhere in 055.raw: %d/8 units at t=%.0fs"
          % (best[0], best[1]))
    print("   note 55:64 is ONE word of 8 units; the two long ayat 55:64/55:66-68")
    print("   are the only short ones in this neighbourhood.")

    # how short is 55:64 relative to the corpus?
    ln = {a: len(exp55[a][0]) for a in exp55}
    short = sorted(ln.items(), key=lambda kv: kv[1])[:8]
    print("   shortest ayat in surah 55: %s"
          % ", ".join("55:%d=%du" % (a, n) for a, n in short))
    # how many ayat in the whole corpus have <=8 units?
    cnt = 0
    tot = 0
    for s in range(1, 115):
        for a, v in L.expected_all(tbl, tok, s, L.surah_ayah_count(tbl, s)).items():
            tot += 1
            if len(v[0]) <= 8:
                cnt += 1
    print("   corpus: %d of %d ayat have <=8 expected units (%.1f%%)"
          % (cnt, tot, 100.0 * cnt / tot))


if __name__ == "__main__":
    main()