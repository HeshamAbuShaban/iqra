#!/usr/bin/env python3
"""TASK 2 probe: why does surah 55 (and 94) freeze?

A full mirror of lock_trace.simulate()'s policy body with diagnostics added
per evaluated poll: the observation window (symbols since the last rebase),
how many of them are distinct new symbols, here_cov / next_cov, and which
expected units of the candidate matched.

Fidelity is CHECKED, not assumed: `verify()` runs the real
lock_trace.run_dump on a small surah and asserts the mirror reproduces the
move list (times, from, to, reason, coverage) exactly.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lock_trace as L  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def mirror(by_frame, n_frames, frame_sec, plan, exp_by_surah, policy,
           repeat=None, start=1, drain_frames=None, diag_from=None):
    """simulate() verbatim + a per-poll diagnostic log."""
    tail_frames = int(round(policy.tail_seconds / frame_sec))
    if policy.tail_mode == "none":
        tail_frames = 0
    s = L._Session(plan, exp_by_surah, policy, repeat, start, tail_frames)
    drain = tail_frames if drain_frames is None else drain_frames
    total = n_frames + max(0, drain)
    diags = []
    prev_epoch_tail = []
    for wall in range(total):
        dump_frame = wall - s.backlog
        if dump_frame < 0:
            new = []
        elif policy.tail_mode == "blank" and dump_frame < wall:
            new = []
        else:
            new = by_frame.get(dump_frame, [])
        if new:
            s.epoch.extend(e["symbol"] for e in new)
        size = len(s.epoch)
        if not new or size == s.last_count:
            continue
        s.last_count = size
        audio_sec = (wall + 1) * frame_sec
        if s.rebase_pending:
            s.slice_start = size
            s.rebase_pending = False
            s.rebase_polls += 1
            continue
        obs = s.epoch[s.slice_start:]
        if not obs:
            continue
        s.evaluated += 1
        exp = s.exp()
        if (s.ayah >= s.scope_end and s.si + 1 < len(s.plan)):
            nxt_surah = s.plan[s.si + 1][0]
            nxt = (s.exp_by_surah.get(nxt_surah) or {}).get(1)
            if nxt:
                cov = L.coverage(obs, nxt[0])[0]
                if cov >= policy.handoff:
                    s.moves.append(L.Move(audio_sec, wall, s.surah, s.ayah,
                                          nxt_surah, 1, 0, "handoff", cov))
                    s.si += 1
                    s.surah = nxt_surah
                    s.scope_end = s.plan[s.si][1]
                    s.ayah = 1
                    s.full_reset()
                    continue
        next_ayah = s.ayah + 1
        next_cov = L.coverage(obs, exp[next_ayah][0])[0] if next_ayah in exp else 0.0
        here_cov = L.coverage(obs, exp[s.ayah][0])[0] if s.ayah in exp else 0.0
        need_frames = 3 if s.wpm < policy.wpm_low else 2
        moved = False
        if diag_from is not None and audio_sec >= diag_from:
            # how much of `obs` is NEW (never emitted earlier in the epoch)?
            seen_before = s.epoch[:s.slice_start]
            n_new = 0
            i = 0
            seen = set()
            # symbols that appeared at least once before the slice
            pre = set(seen_before)
            for sym in obs:
                if sym not in pre:
                    n_new += 1
                    pre.add(sym)
            dist = len(set(obs))
            back_cov = (L.coverage(obs, exp[next_ayah - 1][0])[0]
                        if next_ayah - 1 in exp else 0.0)
            diags.append({
                "audio_sec": round(audio_sec, 2), "wall": wall,
                "ayah": s.ayah, "obs_len": len(obs), "obs_distinct": dist,
                "obs_new_types": n_new, "epoch_len": len(s.epoch),
                "slice_start": s.slice_start,
                "since_rebase_walls": wall - getattr(s, "_last_rebase_wall", wall),
                "here_cov": round(here_cov, 4), "next_cov": round(next_cov, 4),
                "back_cov": round(back_cov, 4),
                "in_dead_band": bool(policy.weak <= next_cov < policy.advance),
                "here_under_stuck": bool(here_cov < policy.stuck),
                "pending_next": s.pending_next_ayah,
                "pending_next_frames": s.pending_next_frames,
                "obs": list(obs),
            })
        if next_cov >= policy.advance:
            if next_cov >= policy.strong:
                reason, pend = "forward-strong", 0
            elif s.pending_next_ayah == next_ayah:
                s.pending_next_frames += 1
                pend = s.pending_next_frames
                reason = ("forward-pending"
                          if s.pending_next_frames >= need_frames else None)
            else:
                s.pending_next_ayah = next_ayah
                s.pending_next_frames = 1
                pend = s.pending_next_frames
                reason = None
            if reason:
                s.move(audio_sec, wall, s.surah, next_ayah, +1, reason,
                       next_cov, pend, measure_speed=True)
                s.reset_stream(tail_replay=True)
                moved = True
                s._last_rebase_wall = wall
        elif next_cov < policy.weak:
            if s.pending_next_frames > 0:
                s.pending_next_frames -= 1
        else:
            s.pending_next_ayah = None
            s.pending_next_frames = 0
        if next_cov < policy.advance and here_cov < policy.stuck:
            best = None
            for a in sorted(exp):
                if a <= s.ayah + 1:
                    continue
                c = L.coverage(obs, exp[a][0])[0]
                if c >= policy.jump and (best is None or a < best[0]):
                    best = (a, c)
            if best is not None:
                s.move(audio_sec, wall, s.surah, best[0], +1, "jump",
                       best[1], 0, measure_speed=False)
                s.reset_stream(tail_replay=True)
                moved = True
                s._last_rebase_wall = wall
        back_ayah = s.ayah - 1
        lock_held = not moved
        back_cov = (L.coverage(obs, exp[back_ayah][0])[0]
                    if back_ayah in exp else 0.0)
        need_back = (policy.back_need_frames if policy.back_need_frames is not None
                     else (3 if s.wpm < policy.wpm_low else 2))
        if (lock_held and back_cov >= policy.back and here_cov < policy.stuck
                and back_ayah in exp):
            if s.pending_back_ayah == back_ayah:
                s.pending_back_frames += 1
                if (s.pending_back_frames >= need_back
                        and s.retreat_allowed(audio_sec)):
                    s.move(audio_sec, wall, s.surah, back_ayah, -1,
                           "backward", back_cov, s.pending_back_frames,
                           measure_speed=False)
                    s.reset_stream(tail_replay=True)
                    s.pending_back_ayah = None
                    s.pending_back_frames = 0
                    moved = True
                    s._last_rebase_wall = wall
            else:
                s.pending_back_ayah = back_ayah
                s.pending_back_frames = 1
        elif back_cov >= policy.back:
            s.pending_back_ayah = None
            s.pending_back_frames = 0
        elif s.pending_back_frames > 0:
            s.pending_back_frames -= 1
        if (s.repeat_left > 0 and s.repeat_target is not None
                and s.surah == s.repeat_target[0]
                and s.ayah > s.repeat_target[1]):
            s.repeat_left -= 1
            s.moves.append(L.Move(audio_sec, wall, s.surah, s.ayah, s.surah,
                                  s.repeat_target[1], -1, "repeat", 0.0))
            s.ayah = s.repeat_target[1]
            s.full_reset()
            moved = True
            s._last_rebase_wall = wall
        if not moved:
            s.speech_since_advance += 1
    n_em = sum(len(v) for v in by_frame.values())
    return (L.TraceResult(s.moves, s.surah, s.ayah, s.wpm, s.evaluated, n_em,
                          n_frames, s.rebase_polls, s.stuck), diags)


def verify(tbl, tok):
    """The mirror must reproduce lock_trace exactly."""
    for sur, n_ay in [(94, 8), (97, 5), (105, 5), (93, 11), (91, 15), (80, 42)]:
        plan = [(sur, n_ay)]
        exp = L.build_exp(tbl, tok, plan)
        d = json.load(open(os.path.join(ROOT, "engine/corpus/out/%03d.json" % sur)))
        p = L.LockPolicy()
        ref = L.run_dump(d, plan, policy=p, exp=exp, table=tbl, tok=tok)
        mine, _ = mirror(L.group_frames(d), d["frames"],
                         d.get("frame_ms", 250) / 1000.0, plan, exp, p)
        a = [(round(m.t, 3), m.from_ayah, m.to_ayah, m.reason,
              round(m.coverage, 6)) for m in ref.moves]
        b = [(round(m.t, 3), m.from_ayah, m.to_ayah, m.reason,
              round(m.coverage, 6)) for m in mine.moves]
        assert a == b, (sur, [x for x in a if x not in b][:3],
                        [x for x in b if x not in a][:3])
        assert ref.final_lock == mine.final_lock, (sur, ref.final_lock,
                                                   mine.final_lock)
        assert ref.oscillations == mine.oscillations
        print("  verify surah %-3d OK  moves=%d final=%s" % (sur, len(a),
                                                             ref.final_lock))


def main():
    tbl = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    print("fidelity check (mirror vs lock_trace.run_dump):")
    verify(tbl, tok)

    for sur, n_ay in [(94, 8), (55, 78)]:
        plan = [(sur, n_ay)]
        exp = L.build_exp(tbl, tok, plan)
        d = json.load(open(os.path.join(ROOT, "engine/corpus/out/%03d.json" % sur)))
        p = L.LockPolicy()
        res, diags = mirror(L.group_frames(d), d["frames"],
                            d.get("frame_ms", 250) / 1000.0, plan, exp, p,
                            diag_from=0.0)
        print("=" * 78)
        print("SURAH %d  final=%s moves=%d audio_sec=%.1f tokens=%d"
              % (sur, res.final_lock, len(res.moves), d["audio_sec"], d["tokens"]))
        print("  last 6 moves: %s" % ["%s %d->%d %s c=%.2f" % (
            m.t, m.from_ayah, m.to_ayah, m.reason, m.coverage) for m in res.moves[-6:]])
        last_t = res.moves[-1].t if res.moves else 0.0
        stall = [x for x in diags if x["audio_sec"] >= last_t]
        print("  stall diagnostics: %d evaluated polls after last move (%.1fs)"
              % (len(stall), d["audio_sec"] - last_t))
        print("  %-8s %-5s %-6s %-6s %-6s %-6s %-9s %-9s %-6s %-6s %s"
              % ("t", "ayah", "obs_n", "dist", "newtyp", "epoch", "here_cov",
                 "next_cov", "dead", "pend", "obs"))
        for x in stall:
            print("  %-8.2f %-5d %-6d %-6d %-6d %-6d %-9.3f %-9.3f %-6s %-6s %s"
                  % (x["audio_sec"], x["ayah"], x["obs_len"], x["obs_distinct"],
                     x["obs_new_types"], x["epoch_len"], x["here_cov"],
                     x["next_cov"], x["in_dead_band"], x["pending_next_frames"],
                     " ".join(x["obs"])))
            sys.stdout.flush()
        # expected units of the locked / candidate ayah
        for a in (res.final_ayah, res.final_ayah + 1, res.final_ayah + 2):
            if a in exp[sur]:
                print("  expected %d:%d  units=%d nwords=%d : %s"
                      % (sur, a, len(exp[sur][a][0]), exp[sur][a][2],
                         tbl["%d:%d" % (sur, a)]["aya_text"]))
                print("      units: %s" % " ".join(exp[sur][a][0]))
        with open("/tmp/opencode/probe_freeze_%03d.json" % sur, "w") as f:
            json.dump({"surah": sur, "final": res.final_lock,
                       "moves": ["%s %d->%d %s %.3f" % (m.t, m.from_ayah,
                                                        m.to_ayah, m.reason,
                                                        m.coverage)
                                 for m in res.moves],
                       "diag": [{k: v for k, v in x.items() if k != "obs"}
                                for x in stall],
                       "obs": [x["obs"] for x in stall]}, f,
                      ensure_ascii=False, indent=1)
    print("\nwrote /tmp/opencode/probe_freeze_*.json")


if __name__ == "__main__":
    main()