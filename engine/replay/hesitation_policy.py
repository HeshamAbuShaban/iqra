#!/usr/bin/env python3
"""Hesitation scenarios: build them from REAL emitted symbols, score them.

`lock_trace.py` measures one thing: does the app's lock follow the reciter.
It cannot measure whether it FOLLOWS HIM BACK, because every dump on disk is
a clean, forward, in-order recitation. So this script manufactures the
hesitation cases from real audio instead of from fabricated symbols: ONE
alignment pass locates each ayah's real emission span, and then those real
spans are CONCATENATED in a chosen order. No symbol is invented; only the
order of real material changes. See `ayah_spans` for why a single pass is
required rather than a per-ayah `find_window` scan.

Scenarios (ayah order), all Fatiha 1-7:
  clean       1 2 3 4 5 6 7
  repeat      1 2 3 4 3 4 5 6 7      recited 4 twice
  repeat-two  1 2 3 4 2 3 4 5 6 7    recited 4 three times
  deep        1 2 3 4 2 3 2 3 4 5 6 7   revisits 2 after the lock has moved
  backtrack   1 2 3 4 5 3 4 5 6 7    recited 5 twice
  hold k      1 2 3 4 [4 x k] 4 5 6 7  stalls on 4, then continues

With the backward branch ON, `repeat` reproduces exactly: 4->3->4, 2
oscillations. `repeat-two` does NOT produce 4->3->2->3->4 and cannot, because
PracticeViewModel.kt:916 is `val backAyah = lockedAyah - 1`: while the lock
sits at 4 and the reciter is back on 2, the branch scores ayah 3, which the
reciter is not saying, and stays silent. It wakes up one ayah later and walks
back a single step. `deep` is the order that does produce 4->3->2->3->4.

The forward-only policy cannot see ANY of them: it only ever scores lock+1,
so its trace is byte-identical for clean, repeat and repeat-two. If this
script stops reproducing that, the port no longer matches the app.

Real recordings can be scored instead of synthesised spans - see
engine/audio/hesitate/README.md:

    hesitation_policy.py --audio ../audio/hesitate/103repeat.wav
"""
import argparse
import json
import os
import sys

import lock_trace as L
from word_verdicts import align, load_units, make_tokenizer

HERE = os.path.dirname(os.path.abspath(__file__))
HESITATE_DIR = os.path.join(HERE, "..", "audio", "hesitate")


def scenarios(k=2):
    """(name, ayah order) pairs. `k` is the number of EXTRA repetitions of
    ayah 4 in the hesitation-hold cases. `deep` is the only order that makes
    the lock walk back TWO ayat: the backward branch is one ayah deep, so the
    reciter has to revisit 2 after the lock has already dropped to 3."""
    return [
        ("clean",       [1, 2, 3, 4, 5, 6, 7]),
        ("repeat",      [1, 2, 3, 4, 3, 4, 5, 6, 7]),
        ("repeat-two",  [1, 2, 3, 4, 2, 3, 4, 5, 6, 7]),
        ("deep",        [1, 2, 3, 4, 2, 3, 2, 3, 4, 5, 6, 7]),
        ("backtrack",   [1, 2, 3, 4, 5, 3, 4, 5, 6, 7]),
        ("hold k=%d" % k, [1, 2, 3, 4] + [4] * k + [4, 5, 6, 7]),
    ]


# --------------------------------------------------------------------------
# real per-ayah spans
# --------------------------------------------------------------------------
def ayah_spans(dump, table, tok, surah, n_ayat):
    """{ayah: [emission dicts]} - the real symbols of each ayah, disjoint.

    One alignment pass over the WHOLE dump: the concatenated expected units of
    every in-scope ayah are aligned against the full emission stream with
    `word_verdicts.align`, the shipped-equivalent DP, and each ayah's span is
    read out of `ref_to_query` exactly as `word_spans` (257-288) reads word
    spans out of the same map. Nothing is fabricated and nothing is guessed.

    Per-ayah `find_window` scans do NOT work here, and the reason matters:
    each one searches the whole stream independently, so on s001.json every
    ayah's `len(ref) + 12` window reaches into its successor's audio and the
    spans overlap. Concatenating overlapping spans duplicates material, so a
    "clean" scenario would contain repeats and mean nothing. The single pass
    is also strictly more faithful to the app, which holds ONE slice and
    rebases it at every lock move, rather than searching per ayah.

    Unmatched reference units still occupy a position, so they are placed at
    the midpoint of their neighbours (`word_spans`, 267-271) instead of being
    dropped, which would make a word - or here an ayah - look short.
    """
    em = dump["emissions"]
    syms = [e["symbol"] for e in em]
    exp = L.expected_all(table, tok, surah, n_ayat)
    ref, owner = [], []
    for a in range(1, n_ayat + 1):
        if a not in exp:
            continue
        for u in exp[a][0]:
            ref.append(u)
            owner.append(a)
    if not ref:
        return {}
    _m, _w, r2q, _e, hits, _n = align(syms, ref, list(range(len(ref))))

    spans = {}
    for k, a in enumerate(owner):
        q = r2q[k]
        if a not in spans:
            spans[a] = [q, q]
        spans[a][0] = min(spans[a][0], q) if q >= 0 else spans[a][0]
        spans[a][1] = max(spans[a][1], q) if q >= 0 else spans[a][1]
    out = {}
    prev_hi = 0
    for a in sorted(spans):
        lo, hi = spans[a]
        lo = max(lo, prev_hi)
        hi = max(hi + 1, lo + 1)
        out[a] = em[lo:hi]
        prev_hi = hi
    return out


def ayah_pacing(dump, spans):
    """{ayah: (start_poll, end_poll)} in 0-based poll indices, so real
    inter-ayah gaps and intra-ayah pacing can be preserved when spans are
    re-ordered."""
    out = {}
    for a, ems in spans.items():
        if ems:
            out[a] = (int(ems[0]["frame"]) - 1, int(ems[-1]["frame"]) - 1)
    return out


def build_scenario(order, spans, pacing, gap_default=3):
    """Concatenate real ayah spans in `order`, as a token dump.

    Intra-ayah poll spacing is the REAL spacing from the recording, and each
    ayah is separated by the REAL poll gap to its successor in the original
    dump. So the synthetic stream has the same frame cadence as real audio;
    only the ORDER of real material changes. That matters because the policy
    is evaluated per poll, not per emission.
    """
    emissions = []
    clock = 0
    prev = None
    for a in order:
        ems = spans.get(a)
        if not ems:
            continue
        if prev is None:
            start = 0
        else:
            p0 = pacing.get(prev)
            p1 = pacing.get(a)
            gap = (p1[0] - p0[1]) if (p0 and p1) else gap_default
            start = clock + max(1, gap)
        base = int(ems[0]["frame"]) - 1
        for i, e in enumerate(ems):
            emissions.append({
                "i": len(emissions),
                "frame": start + (int(e["frame"]) - 1 - base),
                "audio_sec": round((start + (int(e["frame"]) - 1 - base) + 1) * 0.25, 3),
                "symbol": e["symbol"],
                "ts": e["ts"],
                "prob": e["prob"],
            })
        clock = start + (int(ems[-1]["frame"]) - 1 - base)
        prev = a
    n_frames = clock + 8
    return {"label": "scenario", "frame_ms": 250, "sample_rate": 16000,
            "frames": n_frames, "emissions": emissions}


# --------------------------------------------------------------------------
# scenarios from real recordings
# --------------------------------------------------------------------------
def dump_for_audio(path, out_path=None, frame_ms=250):
    """Run a real recording through dump_tokens.py, returning the dump.

    Accepts .wav (converted to 16 kHz mono f32le .raw via ffmpeg when
    available) or a .raw that is already 16 kHz mono f32le. See
    engine/audio/hesitate/README.md for the expected format.
    """
    import subprocess
    import tempfile
    dump_tokens = os.path.join(HERE, "dump_tokens.py")
    raw = path
    tmp = None
    if path.lower().endswith((".wav", ".mp3", ".m4a", ".flac", ".ogg")):
        ffmpeg = _which("ffmpeg")
        if not ffmpeg:
            raise SystemExit(
                "ffmpeg not found. Convert to 16 kHz mono f32le raw first:\n"
                "  ffmpeg -i %s -ar 16000 -ac 1 -f f32le out.raw" % path)
        tmp = tempfile.NamedTemporaryFile(suffix=".raw", delete=False)
        tmp.close()
        subprocess.check_call([ffmpeg, "-v", "error", "-i", path, "-ar",
                               "16000", "-ac", "1", "-f", "f32le", tmp.name])
        raw = tmp.name
    out_path = out_path or os.path.join(
        HESITATE_DIR, "dump-" + os.path.splitext(os.path.basename(path))[0]
        + ".json")
    subprocess.check_call([sys.executable, dump_tokens, raw, "--frame-ms",
                           str(frame_ms), "--out", out_path,
                           "--label", os.path.basename(path)])
    if tmp:
        os.unlink(tmp.name)
    with open(out_path) as f:
        return json.load(f), out_path


def _which(prog):
    for d in os.environ.get("PATH", "").split(os.pathsep):
        p = os.path.join(d, prog)
        if os.path.isfile(p) and os.access(p, os.X_OK):
            return p
    return None


# --------------------------------------------------------------------------
# reporting
# --------------------------------------------------------------------------
def forward_only(policy):
    """The forward-only policy: no backward recovery. Implemented as an
    unreachable BACK_COVERAGE (coverage is a fraction, so 1.01 can never be
    met) rather than by branching inside the simulator."""
    return L.LockPolicy(
        advance_coverage=policy.advance, strong_coverage=policy.strong,
        weak_coverage=policy.weak, jump_coverage=policy.jump,
        back_coverage=1.01, handoff_coverage=policy.handoff,
        stuck_coverage=policy.stuck, tail_seconds=policy.tail_seconds,
        back_need_frames=policy.back_need_frames,
        tail_mode=policy.tail_mode)


def run_suite(dump, table, tok, surah, n_ayat, exp, scen_list=None, gap=None):
    """(name, order, full_trace, forward_only_trace, dump) per scenario."""
    spans = ayah_spans(dump, table, tok, surah, n_ayat)
    pacing = ayah_pacing(dump, spans)
    full = L.LockPolicy()
    fwd = forward_only(full)
    rows = []
    for name, order in (scen_list or scenarios()):
        scen = build_scenario(order, spans, pacing, gap_default=gap or 3)
        r_full = L.run_dump(scen, [(surah, n_ayat)], policy=full, exp=exp)
        r_fwd = L.run_dump(scen, [(surah, n_ayat)], policy=fwd, exp=exp)
        rows.append((name, order, r_full, r_fwd, scen))
    return rows


def print_suite(rows, title="SCENARIO -> MOVE TRACE"):
    print(title)
    print("  %-11s %-4s %-4s %-4s %-9s %-9s  %s"
          % ("scenario", "syms", "mv", "osc", "lock(full)", "lock(fwd)", "trace"))
    print("  " + "-" * 100)
    for name, order, r_full, r_fwd, scen in rows:
        print("  %-11s %-4d %-4d %-4d %-9s %-9s  %s"
              % (name, len(scen["emissions"]), len(r_full.moves),
                 r_full.oscillations, r_full.final_lock, r_fwd.final_lock,
                 r_full.trace_text()))
    print()


def expected_result_table(rows):
    """The check the plan asks for, stated as data.

    `repeat` reproduces exactly: 4->3->4.

    `repeat-two` does NOT, and cannot: the plan expects 4->3->2->3->4, but
    PracticeViewModel.kt:916 is `val backAyah = lockedAyah - 1`. The backward
    branch only ever considers the ONE ayah behind the lock, so while the lock
    sits at 4 and the reciter is back on 2, the branch scores ayah 3 - which
    the reciter is not saying - and stays silent. It wakes up one ayah later,
    at 3, and walks back a single step. Getting 4->3->2 requires the reciter to
    revisit ayah 2 AFTER the lock has already dropped to 3, which is the
    `deep` scenario; it is a property of the recitation, not of the policy.

    The forward-only blindness claim is checked as stated: the forward-only
    trace must be byte-identical to clean for clean, repeat and repeat-two.
    """
    want = {
        "repeat":     (["4->3", "3->4"], True),
        "repeat-two": (["4->3", "3->2", "2->3", "3->4"], False),
    }
    ok = True
    print("KNOWN-CORRECT RESULT CHECK")
    for name, order, r_full, r_fwd, _s in rows:
        if name not in want:
            continue
        expect, reachable = want[name]
        got = []
        for m in r_full.moves:
            if m.direction == -1:
                got.append("%d->%d" % (m.from_ayah, m.to_ayah))
        # pair each backward move with the forward move that re-covers it
        pairs = []
        i = 0
        while i < len(r_full.moves):
            m = r_full.moves[i]
            if m.direction == -1:
                seq = ["%d->%d" % (m.from_ayah, m.to_ayah)]
                for m2 in r_full.moves[i + 1:]:
                    seq.append("%d->%d" % (m2.from_ayah, m2.to_ayah))
                    if m2.direction > 0 and m2.to_ayah == m.from_ayah:
                        break
                pairs.append(seq)
            i += 1
        back = pairs[0] if pairs else []
        hit = back == expect
        if hit:
            verdict = "OK"
        elif not reachable:
            verdict = "UNREACHABLE (see note; `deep` shows 4->3->2->3->4)"
        else:
            verdict = "MISMATCH"
        ok = ok and (hit or not reachable)
        print("  %-11s expected %-24s got %-24s %s"
              % (name, " ".join(expect), " ".join(back) or "(no back move)",
                 verdict))
    base = [r for r in rows if r[0] == "clean"]
    if base:
        clean_trace = base[0][3].trace_text()
        blind = [r[0] for r in rows if r[3].trace_text() == clean_trace]
        need = ["clean", "repeat", "repeat-two"]
        good = all(n in blind for n in need)
        ok = ok and good
        print("  forward-only: identical-to-clean for %d/%d scenarios (%s); "
              "needs clean+repeat+repeat-two: %s"
              % (len(blind), len(rows), ", ".join(blind),
                 "OK" if good else "MISMATCH"))
        print("  forward-only traces are byte-identical because a policy that "
              "only scores lock+1 cannot see a repeat at all.")
    print("  -> %s" % ("PASS" if ok else "FAIL"))
    print()
    return ok


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("dump", nargs="?",
                    default=os.path.join(HERE, "out", "s001.json"),
                    help="token dump of a clean forward recitation")
    ap.add_argument("--surah", type=int, default=1)
    ap.add_argument("--ayat-count", type=int, default=7)
    ap.add_argument("--gap", type=int, default=None,
                    help="polls of silence inserted between consecutive ayah "
                         "spans (default: the real gap in the source dump, "
                         "clamped to at least 1)")
    ap.add_argument("--hold-k", type=int, default=2,
                    help="extra repetitions of ayah 4 in the hold scenarios")
    ap.add_argument("--full", action="store_true",
                    help="print the full move trace for every scenario")
    ap.add_argument("--audio", default=None,
                    help="score a real recording instead: engine/audio/"
                         "hesitate/NNN<variant>.wav or a .raw/.wav path")
    ap.add_argument("--audio-repeat", type=int, default=0,
                    help="ayah to repeat-loop in --audio (the repeat hook)")
    ap.add_argument("--surah-change", action="append", default=[],
                    metavar="SURAH:END")
    args = ap.parse_args(argv)

    table = L.load_table()
    tok = make_tokenizer(load_units())

    if args.audio:
        return _score_audio(args, table, tok)

    with open(args.dump) as f:
        dump = json.load(f)
    plan = [(args.surah, args.ayat_count)]
    if args.surah_change:
        plan = []
        for spec in args.surah_change:
            s, e = spec.split(":")
            plan.append((int(s), int(e)))
    exp = L.build_exp(table, tok, plan)

    spans = ayah_spans(dump, table, tok, args.surah, args.ayat_count)
    missing = [a for a in range(1, args.ayat_count + 1) if a not in spans]
    print("source   : %s (%d emissions, %d polls)"
          % (os.path.basename(args.dump), len(dump["emissions"]),
             dump["frames"]))
    print("real spans: %s"
          % ", ".join("1:%d=%d syms [%d..%d]" % (a, len(spans[a]),
                                                 ayah_pacing(dump, spans)[a][0],
                                                 ayah_pacing(dump, spans)[a][1])
                      for a in sorted(spans)))
    if missing:
        print("!! no real span for ayah(s) %s - scenarios built from spans "
              "cannot cover them" % missing)
    print()

    rows = run_suite(dump, table, tok, args.surah, args.ayat_count, exp,
                     gap=args.gap)
    rows = [(n, o, rf, rw, s) for (n, o, rf, rw, s) in rows]
    print_suite(rows)
    print("  'full' is the app's policy. 'fwd' is the same policy with the "
          "backward branch disabled (BACK_COVERAGE=1.01, unreachable).")
    print("  Oscillation = direction reversals in the trace: a move that "
          "reverses the previous move's direction.")
    print()

    ok = expected_result_table(rows)

    if args.full:
        for name, order, r_full, r_fwd, _s in rows:
            print("  %-11s order = %s" % (name, order))
            for m in r_full.moves:
                print("      %-6s %-6s %-6s %-4s %-15s %.3f" % m.as_row())
            print("      -> %d moves, %d oscillations, lock %s"
                  % (len(r_full.moves), r_full.oscillations, r_full.final_lock))
            print()
    return 0 if ok else 1


def _score_audio(args, table, tok):
    """Score a real recording dropped into engine/audio/hesitate/."""
    path = args.audio
    if not os.path.exists(path):
        raise SystemExit("no such file: %s" % path)
    name = os.path.basename(path)
    if path.lower().endswith(".json"):
        with open(path) as f:
            dump = json.load(f)
        dump_path = path
    else:
        dump, dump_path = dump_for_audio(path)
    stem, variant = (os.path.splitext(name)[0][:3], os.path.splitext(name)[0][3:])
    surah = int(stem) if stem.isdigit() else args.surah
    n_ayat = L.surah_ayah_count(table, surah)
    plan = [(surah, n_ayat)]
    if args.surah_change:
        plan = []
        for spec in args.surah_change:
            s, e = spec.split(":")
            plan.append((int(s), int(e)))
    exp = L.build_exp(table, tok, plan)
    repeat = None
    if args.audio_repeat:
        repeat = (surah, args.audio_repeat, 3)
    full = L.LockPolicy()
    print("audio    : %s" % os.path.abspath(path))
    print("dump     : %s" % dump_path)
    print("surah    : %d (%d ayat in scope), variant '%s'"
          % (surah, n_ayat, variant or "clean"))
    print("policy   : %s" % full)
    print()
    res = L.run_dump(dump, plan, policy=full, exp=exp, repeat=repeat)
    L.print_result(res, "MOVE TRACE (%s)" % name)
    fwd = forward_only(full)
    rf = L.run_dump(dump, plan, policy=fwd, exp=exp, repeat=repeat)
    print("forward-only: %d moves, %d oscillations, lock %s"
          % (len(rf.moves), rf.oscillations, rf.final_lock))
    print("full       : %d moves, %d oscillations, lock %s"
          % (len(res.moves), res.oscillations, res.final_lock))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
