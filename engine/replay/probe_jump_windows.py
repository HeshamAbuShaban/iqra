#!/usr/bin/env python3
"""TASK 1 probe: for each of the 6 jumps, was the skipped ayah recited?

Reuses lock_trace._Session for the stream bookkeeping so the replayed
observation windows are the SAME windows the policy saw, then measures
coverage of the skipped / target ayat over several windows around the
jump time.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lock_trace as L  # noqa: E402

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

# surah, n_ayat, from_ayah, to_ayah, jump_t  (from report_v2_drained.jsonl)
JUMPS = [
    (8, 75, 68, 70, 1410.50),
    (52, 49, 5, 7, 28.25),
    (54, 55, 37, 39, 287.25),
    (74, 56, 30, 38, 197.50),
    (80, 42, 17, 19, 71.00),
    (81, 29, 13, 15, 55.50),
]
# t_prev_move, t_next_move  (measured, see report)
NBRS = {
    8: (1360.75, 1420.75),
    52: (22.50, 32.00),
    54: (281.25, 294.00),
    74: (186.25, 202.00),
    80: (64.75, 73.75),
    81: (45.50, 59.25),
}


def replay_to_jump(by_frame, n_frames, frame_sec, plan, exp, policy,
                   from_ayah, to_ayah, t_jump, limit=None):
    """Byte-for-byte the lock_trace.simulate loop, but it records every
    evaluated poll's obs and stops at the jump. Returns (polls, obs_at_jump)."""
    tail_frames = int(round(policy.tail_seconds / frame_sec))
    s = L._Session(plan, exp, policy, None, 1, tail_frames)
    total = n_frames + max(0, tail_frames if limit is None else 0)
    polls = []
    obs_at_jump = None
    for wall in range(total):
        dump_frame = wall - s.backlog
        if dump_frame < 0:
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
            continue
        obs = s.epoch[s.slice_start:]
        if not obs:
            continue
        e = s.exp()
        next_cov = L.coverage(obs, e[s.ayah + 1][0])[0] if s.ayah + 1 in e else 0.0
        here_cov = L.coverage(obs, e[s.ayah][0])[0] if s.ayah in e else 0.0
        polls.append({"wall": wall, "audio_sec": audio_sec,
                      "obs_len": len(obs), "next_cov": round(next_cov, 3),
                      "here_cov": round(here_cov, 3),
                      "obs": list(obs)})
        # replicate the policy branches verbatim
        need_frames = 3 if s.wpm < policy.wpm_low else 2
        moved = False
        if next_cov >= policy.advance:
            if next_cov >= policy.strong:
                reason = "forward-strong"
            elif s.pending_next_ayah == s.ayah + 1:
                s.pending_next_frames += 1
                reason = ("forward-pending"
                          if s.pending_next_frames >= need_frames else None)
            else:
                s.pending_next_ayah = s.ayah + 1
                s.pending_next_frames = 1
                reason = None
            if reason:
                s.move(audio_sec, wall, s.surah, s.ayah + 1, +1, reason, next_cov)
                s.reset_stream(tail_replay=True)
                moved = True
        elif next_cov < policy.weak:
            if s.pending_next_frames > 0:
                s.pending_next_frames -= 1
        else:
            s.pending_next_ayah = None
            s.pending_next_frames = 0
        if next_cov < policy.advance and here_cov < policy.stuck:
            best = None
            for a in sorted(e):
                if a <= s.ayah + 1:
                    continue
                c = L.coverage(obs, e[a][0])[0]
                if c >= policy.jump and (best is None or a < best[0]):
                    best = (a, c)
            if best is not None:
                if best[0] == to_ayah and abs(audio_sec - t_jump) < 0.01:
                    obs_at_jump = list(obs)
                s.move(audio_sec, wall, s.surah, best[0], +1, "jump", best[1],
                       measure_speed=False)
                s.reset_stream(tail_replay=True)
                moved = True
        if moved:
            continue
        # keep going through the forward/handoff-only model is enough for our
        # purpose: we only need to reach the jump, and no backward move can
        # precede a jump in these six traces (checked against lock_trace).
    return polls, obs_at_jump


def window_emissions(dump, lo, hi):
    out = []
    for em in dump["emissions"]:
        if lo <= em["audio_sec"] < hi:
            out.append(em)
    return out


def cov_report(exp_units, query):
    c, hits, n = L.coverage(query, exp_units)
    matched, wrong, r2q, _ew, hits2, _nw = L.align(query, exp_units,
                                                    list(range(len(exp_units))))
    umiss = [i for i, m in enumerate(matched) if not m]
    return c, hits, n, umiss


def main():
    tbl = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    out = {}
    for sur, n_ay, frm, to, t_j in JUMPS:
        plan = [(sur, n_ay)]
        exp = L.build_exp(tbl, tok, plan)
        dump = json.load(open(os.path.join(ROOT, "engine/corpus/out/%03d.json" % sur)))
        by_frame = L.group_frames(dump)
        fs = dump.get("frame_ms", 250) / 1000.0
        policy = L.LockPolicy()
        polls, obs_jump = replay_to_jump(by_frame, dump["frames"], fs, plan, exp,
                                         policy, frm, to, t_j)
        t_prev, t_next = NBRS[sur]
        print("=" * 78)
        print("SURAH %d  jump %d:%d -> %d:%d  t=%.2fs  (prev move %.2fs, next move %.2fs)"
              % (sur, sur, frm, sur, to, t_j, t_prev, t_next))
        print("  audio_sec=%.1f  tokens=%d  polls_evaluated_to_jump=%d" %
              (dump["audio_sec"], dump["tokens"], len(polls)))
        rec = {"surah": sur, "from": "%d:%d" % (sur, frm), "to": "%d:%d" % (sur, to),
               "t": t_j, "t_prev": t_prev, "t_next": t_next,
               "obs_at_jump": len(obs_jump) if obs_jump else None}
        if obs_jump:
            for a in (frm, frm + 1, to):
                if a not in exp[sur]:
                    continue
                c, hits, n, umiss = cov_report(exp[sur][a][0], obs_jump)
                print("  POLICY obs (%d symbols, t_prev..t_jump): %d:%d cov=%.3f "
                      "(%d/%d units), unmatched unit idx=%s"
                      % (len(obs_jump), sur, a, c, hits, n, umiss))
        half = (t_next - t_prev) / 2.0
        wins = [("neighbour-interval [%.1f,%.1f]" % (t_prev, t_next), t_prev, t_next),
                ("symmetric +/-%.1fs [%.1f,%.1f]" % (half, t_j - half, t_j + half),
                 t_j - half, t_j + half),
                ("+/-15s", t_j - 15, t_j + 15),
                ("+/-30s", t_j - 30, t_j + 30)]
        skipped = list(range(frm + 1, to))
        rec["windows"] = {}
        for name, lo, hi in wins:
            em = window_emissions(dump, lo, hi)
            q = [x["symbol"] for x in em]
            dur = hi - lo
            dens = len(q) / dur if dur else 0.0
            print("  WINDOW %s  dur=%.1fs  n_sym=%d  density=%.2f sym/s"
                  % (name, dur, len(q), dens))
            row = {"lo": round(lo, 2), "hi": round(hi, 2), "dur": round(dur, 2),
                   "n": len(q), "dens": round(dens, 3)}
            for a in skipped + [to]:
                if a not in exp[sur]:
                    continue
                c, hits, n, umiss = cov_report(exp[sur][a][0], q)
                role = "SKIP" if a in skipped else "TGT "
                print("      %s %d:%d  cov=%.3f (%d/%d units)  nwords=%d  unmatched=%d %s"
                      % (role, sur, a, c, hits, n, exp[sur][a][2], len(umiss), umiss))
                row["%d:%d" % (sur, a)] = {"cov": round(c, 4), "hits": hits,
                                            "n": n, "unmatched": umiss,
                                            "nwords": exp[sur][a][2]}
            rec["windows"][name] = row
        # expected unit strings for the skipped ayat, for the report
        rec["exp_units"] = {}
        for a in skipped + [to, frm]:
            if a in exp[sur]:
                rec["exp_units"]["%d:%d" % (sur, a)] = exp[sur][a][0]
        out[sur] = rec
    with open("/tmp/opencode/probe_jumps.json", "w") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("\nwrote /tmp/opencode/probe_jumps.json")


if __name__ == "__main__":
    main()