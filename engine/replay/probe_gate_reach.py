#!/usr/bin/env python3
"""Is the jump gate reachable at all?

`here_cov` is coverage over the WHOLE accumulated post-rebase slice. The DP takes
the best monotone matching subsequence, so as the slice grows unitsMatched can
only increase - `here_cov` is monotone non-decreasing between rebases. The jump
gate opens only when `here_cov < STUCK` (0.35), i.e. while the locked ayah is
still mostly unheard. Once the locked ayah reaches full coverage, the gate is
shut for the rest of that ayah - and a lock that falls behind cannot jump to
recover.

This measures how often each gate is actually open, per evaluated poll.

RESULT: the jump gate is open on 85.3% of evaluated polls - the hypothesis in
this file's docstring was WRONG, and the measurement is what said so. The rebase
resets the window on every move, so `here_cov` stays low in normal operation.
Ar-Rahman 55 needs the refrain specifically.

CAUTION: `measure()` re-implements the forward/jump/backward branch logic rather
than calling into `simulate()`, because those values are locals and not
observable from outside. That copy WILL drift from lock_trace.py. The number
above is only trustworthy for the lock_trace revision it was run against -
re-verify before quoting it.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "engine" / "replay"))
import lock_trace as L  # noqa: E402

POLICY = L.LockPolicy()


def probe(surah, n_ayat):
    dump = json.load(open(ROOT / f"engine/corpus/out/{surah:03d}.json", encoding="utf-8"))
    tbl = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    plan = [(surah, n_ayat)]
    exp = L.build_exp(tbl, tok, plan)
    orig = L.simulate

    seen = {"jump_open": 0, "fwd_open": 0, "back_open": 0, "n": 0,
            "here": [], "jump_fired": 0}

    # Re-run with a wrapped coverage that tallies gate openness. We cannot see
    # locals from here, so instead we recompute the two coverages the policy
    # computes for each evaluated poll by replaying and re-deriving them.
    by_frame = L.group_frames(dump)
    frame_sec = dump.get("frame_ms", 250) / 1000.0

    class Counter(dict):
        pass

    real_coverage = L.coverage
    state = {"ayah": 1, "wall": -1}

    def counting_coverage(query, ref):
        c = real_coverage(query, ref)
        return c

    r = L.run_dump(dump, plan, exp=exp, table=tbl, tok=tok)
    # here_cov/next_cov are only inside simulate, so recompute per move plus a
    # sample of polls via the same exp table.
    # Cheap and sufficient: measure at each move, which is where gates matter.
    out = {"surah": surah, "moves": len(r.moves), "final": r.final_lock}
    return out, r


def main():
    targets = [int(a) for a in sys.argv[1:]] or [89, 91, 53, 97]
    tbl = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    rep = ROOT / "engine/corpus/report_v2_drained.jsonl"
    nmap = {}
    if rep.is_file():
        for line in rep.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                nmap[r["surah"]] = r["ayat"]

    print("jump gate = here_cov < %.2f ; forward = next_cov >= %.2f"
          % (POLICY.stuck, POLICY.advance))
    print(f"{'sur':>4} {'ayat':>5} {'polls':>7} {'jump_open':>10} {'jump %':>7} "
          f"{'fwd_open':>9} {'fwd %':>7} {'here=1.0 %':>10}")
    agg = [0, 0, 0]
    for surah in targets:
        n = nmap.get(surah, 7)
        res = measure(surah, n, tbl, tok)
        agg[0] += res["n"]; agg[1] += res["jump_open"]; agg[2] += res["fwd_open"]
        print(f"{surah:>4} {n:>5} {res['n']:>7} {res['jump_open']:>10} "
              f"{100.0 * res['jump_open'] / max(1, res['n']):>6.1f}% "
              f"{res['fwd_open']:>9} "
              f"{100.0 * res['fwd_open'] / max(1, res['n']):>6.1f}% "
              f"{100.0 * res['here_full'] / max(1, res['n']):>9.1f}%")
    print(f"\nALL: {agg[1]}/{agg[0]} polls had the jump gate open "
          f"({100.0 * agg[1] / max(1, agg[0]):.1f}%)")


def measure(surah, n_ayat, tbl, tok):
    import types
    dump = json.load(open(ROOT / f"engine/corpus/out/{surah:03d}.json", encoding="utf-8"))
    plan = [(surah, n_ayat)]
    exp = L.build_exp(tbl, tok, plan)
    tally = {"n": 0, "jump_open": 0, "fwd_open": 0, "here_full": 0}
    real = L.coverage

    def spy(query, ref):
        return real(query, ref)

    # Patch simulate's view of the session by wrapping LockPolicy attributes is
    # not possible from outside, so we re-implement the tally inline using the
    # same loop the module uses, via a small copy of the gate decision.
    by_frame = L.group_frames(dump)
    frame_sec = dump.get("frame_ms", 250) / 1000.0
    pol = L.LockPolicy()
    s = L._Session(plan, exp, pol, None, 1, int(round(pol.tail_seconds / frame_sec)))
    tail_frames = int(round(pol.tail_seconds / frame_sec))
    for wall in range(dump["frames"] + tail_frames):
        dump_frame = wall - s.backlog
        new = [] if dump_frame < 0 else by_frame.get(dump_frame, [])
        if new:
            s.epoch.extend(e["symbol"] for e in new)
        size = len(s.epoch)
        if not new or size == s.last_count:
            continue
        s.last_count = size
        if s.rebase_pending:
            s.slice_start = size
            s.rebase_pending = False
            s.rebase_polls += 1
            continue
        obs = s.epoch[s.slice_start:]
        if not obs:
            continue
        s.evaluated += 1
        cur = s.exp()
        na = s.ayah + 1
        next_cov = real(obs, cur[na][0])[0] if na in cur else 0.0
        here_cov = real(obs, cur[s.ayah][0])[0] if s.ayah in cur else 0.0
        tally["n"] += 1
        if here_cov < pol.stuck:
            tally["jump_open"] += 1
        if next_cov >= pol.advance:
            tally["fwd_open"] += 1
        if here_cov >= 0.999:
            tally["here_full"] += 1
        # replicate the module's forward branch + jump branch minimally
        moved = False
        if next_cov >= pol.advance:
            if next_cov >= pol.strong:
                reason, pend = "forward-strong", 0
            elif s.pending_next_ayah == na:
                s.pending_next_frames += 1
                pend = s.pending_next_frames
                reason = "forward-pending" if s.pending_next_frames >= (3 if s.wpm < pol.wpm_low else 2) else None
            else:
                s.pending_next_ayah, s.pending_next_frames, pend = na, 1, 1
                reason = None
            if reason:
                s.move((wall + 1) * frame_sec, wall, s.surah, na, +1, reason, next_cov, pend)
                s.reset_stream(tail_replay=True)
                moved = True
        elif next_cov < pol.weak:
            if s.pending_next_frames > 0:
                s.pending_next_frames -= 1
        else:
            s.pending_next_ayah = None
            s.pending_next_frames = 0
        if not moved:
            if next_cov < pol.advance and here_cov < pol.stuck:
                best = None
                for a in sorted(cur):
                    if a <= s.ayah + 1:
                        continue
                    c = real(obs, cur[a][0])[0]
                    if c >= pol.jump and (best is None or a < best[0]):
                        best = (a, c)
                if best is not None:
                    s.move((wall + 1) * frame_sec, wall, s.surah, best[0], +1, "jump", best[1], 0, measure_speed=False)
                    s.reset_stream(tail_replay=True)
                    continue
            if s.ayah > 1:
                prev = s.ayah - 1
                c = real(obs, cur[prev][0])[0] if prev in cur else 0.0
                if c >= pol.back and here_cov < pol.advance and s.retreat_allowed((wall + 1) * frame_sec):
                    s.move((wall + 1) * frame_sec, wall, s.surah, prev, -1, "backward", c, 0, measure_speed=False)
                    s.reset_stream(tail_replay=True)
                    continue
        if s.slice_start >= len(s.epoch):
            s.full_reset()
        s.speech_since_advance += 1
    return tally


if __name__ == "__main__":
    main()
