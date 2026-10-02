#!/usr/bin/env python3
"""TASK 3 probe: tail_seconds sweep on small surahs only.

tail_seconds controls how much ALREADY-DECODED audio the fresh stream is
handed after each lock move (TAIL_SAMPLES/16000), so it decides how much of a
just-started ayah is thrown away by the slice rebase.

tail_seconds=0 is a DIAGNOSTIC BOUND ONLY. On device the fresh stream is
always handed the tail; tail=0 is a policy that does not exist, so it bounds
how much of the result is tail-caused rather than proposing a fix.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lock_trace as L  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SURAHS = [(94, 8), (97, 5), (105, 5), (91, 15), (93, 11)]
TAILS = [0.0, 0.75, 1.5, 3.0]


def main():
    tbl = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    rows = []
    for sur, n_ay in SURAHS:
        plan = [(sur, n_ay)]
        exp = L.build_exp(tbl, tok, plan)
        dump = json.load(open(os.path.join(ROOT, "engine/corpus/out/%03d.json" % sur)))
        for tail in TAILS:
            for mode in ("replay",):
                p = L.LockPolicy(tail_seconds=tail, tail_mode=mode)
                r = L.run_dump(dump, plan, policy=p, exp=exp, table=tbl, tok=tok)
                seq = all(m.to_ayah == m.from_ayah + 1
                          for m in r.moves if m.reason.startswith("forward"))
                dev = [m for m in r.moves
                       if not (m.reason.startswith("forward")
                               and m.to_ayah == m.from_ayah + 1)]
                stall = (r.moves[-1].t if r.moves else 0.0)
                # trailing stall = audio_sec - time of last move
                trail = dump["audio_sec"] - stall
                rows.append({
                    "surah": sur, "n_ayat": n_ay, "tail": tail, "mode": mode,
                    "final": r.final_lock, "moves": len(r.moves),
                    "osc": r.oscillations, "dev": len(dev),
                    "dev_detail": ["%s->%s %s" % (m.from_ayah, m.to_ayah, m.reason)
                                   for m in dev],
                    "trailing_stall_sec": round(trail, 2),
                    "last_move_t": round(stall, 2),
                    "audio_sec": dump["audio_sec"],
                    "reached_last": r.final_ayah == n_ay,
                    "stuck_samples": r.stuck[:3],
                    "n_stuck_samples": len(r.stuck),
                    "wpm": round(r.wpm, 1),
                })
                print("%3d(%2d) tail=%4.2f %-6s final=%-7s moves=%2d osc=%d dev=%d "
                      "trailing_stall=%6.2fs reached_last=%s wpm=%.1f  %s"
                      % (sur, n_ay, tail, mode, r.final_lock, len(r.moves),
                         r.oscillations, len(dev), trail, r.final_ayah == n_ay,
                         r.wpm, ";".join(x for x in
                                         ["%s->%s %s" % (m.from_ayah, m.to_ayah, m.reason)
                                          for m in dev])))
                sys.stdout.flush()
    with open("/tmp/opencode/probe_tail_sweep.json", "w") as f:
        json.dump(rows, f, indent=1)
    print("wrote /tmp/opencode/probe_tail_sweep.json")


if __name__ == "__main__":
    main()