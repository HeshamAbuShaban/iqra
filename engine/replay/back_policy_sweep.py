#!/usr/bin/env python3
"""Sweep BACK_COVERAGE, STUCK_COVERAGE and the back-frame requirement.

Three numbers per combination, over the same real-span scenarios
`hesitation_policy.py` builds:

  OSC      oscillations in the move trace (direction reversals)
  FOUND    is the skipped/repeated ayah still found, i.e. does the lock
           actually go BACK to the ayah the reciter returned to
  FALSE    does a back-move happen on the CLEAN scenario - this is the
           number that must be ZERO. A false back-move means the lock
           dragged backwards while the reciter recited straight through,
           which is the ping-pong the STUCK gate exists to prevent.

BACK_COVERAGE and STUCK_COVERAGE are a two-sided gate: the back branch
fires only when `backCov >= BACK_COVERAGE && hereCov < STUCK_COVERAGE`
(PracticeViewModel.kt:919). Raise BACK and the gate gets harder to pass, so
fewer finds and fewer false moves. Raise STUCK and the gate gets easier,
since more ayat qualify as "the lock is clearly not what is being recited",
so more finds and more false moves. The sweep measures both directions
instead of assuming them.
"""
import argparse
import json
import os
import sys

import hesitation_policy as H
import lock_trace as L
from word_verdicts import load_units, make_tokenizer

HERE = os.path.dirname(os.path.abspath(__file__))

BACKS = (0.50, 0.70, 0.80, 0.90)
STUCKS = (0.15, 0.25, 0.35, 0.45, 0.55, 0.65)
NEEDS = (1, 2, 3)

# Real clean recordings. These are the important FALSE-back evidence: a
# backwards move on any of them is a move the reciter never made. The
# synthesised `clean` scenario alone is not enough - it is built from
# concatenated spans and never trips the gate, whereas a real continuous
# recording does (see lock_trace.py on out/s001.json).
CLEAN_DUMPS = (
    ("s001", 1, 7),
    ("s103", 103, 3),
    ("s108", 108, 3),
    ("s112", 112, 4),
    ("s113", 113, 5),
    ("s114", 114, 6),
)


def sweep(cases, exp_by_plan, backs=BACKS, stucks=STUCKS, needs=NEEDS):
    """One row per (BACK, STUCK, need_frames, case). `cases` is a list of
    (name, order, dump, plan_key); `exp_by_plan` maps plan key to expected."""
    out = []
    for need in needs:
        for back in backs:
            for stuck in stucks:
                pol = L.LockPolicy(back_coverage=back, stuck_coverage=stuck,
                                   back_need_frames=need)
                for name, order, scen, plan_key in cases:
                    res = L.run_dump(scen, [plan_key], policy=pol,
                                     exp=exp_by_plan[plan_key])
                    out.append({
                        "back": back, "stuck": stuck, "need": need,
                        "scenario": name, "order": order, "res": res,
                        "osc": res.oscillations,
                        "backward": len(res.backward_moves()),
                    })
    return out


def summarise(rows, clean_names=("clean",), real_names=None):
    """One summary row per (BACK, STUCK, need_frames).

    `false_back` counts backward moves over ALL clean material: the
    synthesised `clean` scenario plus every real clean recording.
    `found` counts how many of the four hesitation scenarios the lock walked
    back on.
    """
    real_names = set(real_names or ())
    keys = []
    seen = set()
    for r in rows:
        k = (r["back"], r["stuck"], r["need"])
        if k not in seen:
            seen.add(k)
            keys.append(k)
    out = []
    for back, stuck, need in keys:
        sel = [r for r in rows
               if (r["back"], r["stuck"], r["need"]) == (back, stuck, need)]
        clean = [r for r in sel if r["scenario"] in clean_names
                 or r["scenario"] in real_names]
        rep = [r for r in sel if r["scenario"] in
               ("repeat", "repeat-two", "deep", "backtrack")]
        offenders = [r["scenario"] for r in clean if r["backward"] > 0]
        out.append({
            "back": back, "stuck": stuck, "need": need,
            "false_back": len(offenders),
            "offenders": offenders,
            "clean_cases": len(clean),
            "found": sum(1 for r in rep if r["backward"] > 0),
            "of": len(rep),
            "osc": sum(r["osc"] for r in sel),
        })
    return out


def print_matrix(table, title):
    print(title)
    print("  %-6s %-6s %-4s %-11s %-9s %-5s  %s"
          % ("BACK", "STUCK", "need", "FALSE back", "found", "osc",
             "clean-case offenders"))
    print("  " + "-" * 76)
    for r in table:
        print("  %-6.2f %-6.2f %-4d %-11s %-9s %-5d  %s"
              % (r["back"], r["stuck"], r["need"],
                 "%d/%d" % (r["false_back"], r["clean_cases"]),
                 "%d/%d" % (r["found"], r["of"]), r["osc"],
                 ", ".join(r["offenders"]) or "-"))
    print()


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("dump", nargs="?",
                    default=os.path.join(HERE, "out", "s001.json"))
    ap.add_argument("--surah", type=int, default=1)
    ap.add_argument("--ayat-count", type=int, default=7)
    ap.add_argument("--backs", default=",".join(str(b) for b in BACKS))
    ap.add_argument("--stucks", default=",".join(str(s) for s in STUCKS))
    ap.add_argument("--needs", default=",".join(str(n) for n in NEEDS))
    ap.add_argument("--no-real", action="store_true",
                    help="skip the real clean recordings in FALSE back")
    ap.add_argument("--detail", action="store_true",
                    help="print every (combination, case) row, not just the "
                         "summary")
    args = ap.parse_args(argv)

    backs = [float(x) for x in args.backs.split(",")]
    stucks = [float(x) for x in args.stucks.split(",")]
    needs = [int(x) for x in args.needs.split(",")]

    table_tbl = L.load_table()
    tok = make_tokenizer(load_units())
    with open(args.dump) as f:
        dump = json.load(f)

    main_plan = (args.surah, args.ayat_count)
    plans = {main_plan}
    exp_by_plan = {main_plan: L.build_exp(table_tbl, tok, [main_plan])}

    spans = H.ayah_spans(dump, table_tbl, tok, args.surah, args.ayat_count)
    pacing = H.ayah_pacing(dump, spans)
    scen_list = H.scenarios()
    cases = []
    for name, order in scen_list:
        cases.append((name, order, H.build_scenario(order, spans, pacing),
                      main_plan))
    real_names = []
    real_cases = []
    if not args.no_real:
        for stem, surah, n_ayat in CLEAN_DUMPS:
            path = os.path.join(HERE, "out", stem + ".json")
            if not os.path.exists(path):
                continue
            plan = (surah, n_ayat)
            plans.add(plan)
            exp_by_plan[plan] = L.build_exp(table_tbl, tok, [plan])
            cases.append(("real:" + stem, [1], _load(stem), plan))
            real_cases.append(("real:" + stem, plan, exp_by_plan[plan]))
            real_names.append("real:" + stem)

    print("source   : %s (%d real ayah spans, %d symbols)"
          % (os.path.basename(args.dump), len(spans),
             sum(len(v) for v in spans.values())))
    print("grid     : BACK  %s" % backs)
    print("           STUCK %s" % stucks)
    print("           need  %s" % needs)
    print("hesitation cases : %s" % ", ".join(n for n, _o in scen_list))
    print("clean cases      : clean (synthesised) + %s"
          % (", ".join(real_names) or "none"))
    print("FALSE back = backward moves on clean material. It must be 0.")
    print("found     = how many of repeat / repeat-two / deep / backtrack the "
          "lock walked back on.")
    print()

    rows = sweep(cases, exp_by_plan, backs, stucks, needs)
    if args.detail:
        print("  %-6s %-6s %-4s %-11s %-4s %-4s %s"
              % ("BACK", "STUCK", "need", "case", "mv", "osc", "trace"))
        print("  " + "-" * 112)
        for r in rows:
            print("  %-6.2f %-6.2f %-4d %-11s %-4d %-4d %s"
                  % (r["back"], r["stuck"], r["need"], r["scenario"],
                     len(r["res"].moves), r["osc"], r["res"].trace_text()))
        print()

    summary = summarise(rows, real_names=real_names)
    print_matrix(summary, "SUMMARY")

    print("RECOMMENDED REGION")
    for line in recommend(summary, cases, real_names):
        print("  " + line)
    print()
    print("ROOT CAUSE OF THE CLEAN-CASE FALSE BACK MOVE")
    for line in root_cause(real_cases):
        print("  " + line)
    print()
    return 0


def root_cause(real_cases):
    """Is the false back-move on clean audio caused by BACK/STUCK, or by
    another branch? Re-run the real clean dumps at the shipped thresholds
    with the long jump disabled (JUMP_COVERAGE above 1.0 is unreachable)."""
    lines = []
    for name, plan, exp in real_cases:
        base = L.run_dump(_dump_of(name), [plan], exp=exp)
        nojump = L.run_dump(_dump_of(name), [plan], exp=exp,
                            policy=L.LockPolicy(jump_coverage=1.01))
        noback = L.run_dump(_dump_of(name), [plan], exp=exp,
                            policy=L.LockPolicy(back_coverage=1.01))
        if not base.backward_moves():
            continue
        lines.append(
            "%-10s shipped: %d backward, %d jumps, osc %d, lock %s"
            % (name, len(base.backward_moves()),
               len([m for m in base.moves if m.reason == "jump"]),
               base.oscillations, base.final_lock))
        lines.append(
            "%-10s jump disabled: %d backward, %d jumps, osc %d, lock %s"
            % ("", len(nojump.backward_moves()),
               len([m for m in nojump.moves if m.reason == "jump"]),
               nojump.oscillations, nojump.final_lock))
        lines.append(
            "%-10s back disabled: %d backward, %d jumps, osc %d, lock %s"
            % ("", len(noback.backward_moves()),
               len([m for m in noback.moves if m.reason == "jump"]),
               noback.oscillations, noback.final_lock))
        if not nojump.backward_moves():
            lines.append("  -> the false back-move is a CONSEQUENCE of the long "
                         "jump, not of BACK_COVERAGE/STUCK_COVERAGE: no "
                         "setting of those two removes it while the jump "
                         "branch can fire.")
    return lines


_DUMP_CACHE = {}


def _load(stem):
    with open(os.path.join(HERE, "out", stem + ".json")) as f:
        return json.load(f)


def _dump_of(name):
    stem = name.split(":", 1)[1]
    if stem not in _DUMP_CACHE:
        _DUMP_CACHE[stem] = _load(stem)
    return _DUMP_CACHE[stem]


def recommend(summary, cases, real_names):
    """The region that keeps the repeat-found rate while holding the false
    back-move count at zero, with the numbers that support it."""
    lines = []
    zero = [r for r in summary if r["false_back"] == 0]
    if not zero:
        lines.append("NO SAFE REGION: every combination produced a false "
                     "back-move on clean material.")
        best = min(summary, key=lambda r: r["false_back"])
        lines.append("best is %d/%d clean cases still false at BACK=%.2f "
                     "STUCK=%.2f need=%d"
                     % (best["false_back"], best["clean_cases"], best["back"],
                        best["stuck"], best["need"]))
        return lines
    best_found = max(r["found"] for r in zero)
    cand = [r for r in zero if r["found"] == best_found]
    lo_back = min(r["back"] for r in cand)
    hi_back = max(r["back"] for r in cand)
    lo_stuck = min(r["stuck"] for r in cand)
    hi_stuck = max(r["stuck"] for r in cand)
    needs = sorted(set(r["need"] for r in cand))
    n_of = cand[0]["of"]
    n_clean = cand[0]["clean_cases"]
    lines.append("max repeats found with FALSE back == 0: %d/%d" % (best_found,
                                                                    n_of))
    app = [r for r in summary
           if (r["back"], r["stuck"], r["need"]) == (0.80, 0.35, 2)]
    if app:
        a = app[0]
        lines.append("shipped BACK=0.80 STUCK=0.35 need=2 -> found %d/%d, "
                     "FALSE back %d/%d, oscillations %d"
                     % (a["found"], a["of"], a["false_back"],
                        a["clean_cases"], a["osc"]))
        lines.append("  -> %s the recommended region"
                     % ("INSIDE" if a in cand else "OUTSIDE"))
    lines.append("BACK_COVERAGE  in [%s, %s]  (at found=%d/%d, FALSE back 0/%d)"
                 % (_fmt(lo_back), _fmt(hi_back), best_found, n_of, n_clean))
    lines.append("STUCK_COVERAGE in [%s, %s]  (at found=%d/%d, FALSE back 0/%d)"
                 % (_fmt(lo_stuck), _fmt(hi_stuck), best_found, n_of, n_clean))
    lines.append("back need_frames in %s" % needs)
    lines.append("")
    lines.append("supporting data - need=2, STUCK=0.35, BACK rising:")
    for back in sorted(set(r["back"] for r in summary)):
        r = [x for x in summary
             if (x["back"], x["need"], x["stuck"]) == (back, 2, 0.35)]
        if r:
            lines.append("  BACK=%.2f  found %d/%d  FALSE back %d/%d  osc %d"
                         % (back, r[0]["found"], r[0]["of"], r[0]["false_back"],
                            r[0]["clean_cases"], r[0]["osc"]))
    lines.append("supporting data - need=2, BACK=0.80, STUCK rising:")
    for stuck in sorted(set(r["stuck"] for r in summary)):
        r = [x for x in summary
             if (x["stuck"], x["need"], x["back"]) == (stuck, 2, 0.80)]
        if r:
            lines.append("  STUCK=%.2f  found %d/%d  FALSE back %d/%d  osc %d"
                         % (stuck, r[0]["found"], r[0]["of"],
                            r[0]["false_back"], r[0]["clean_cases"],
                            r[0]["osc"]))
    lines.append("supporting data - BACK=0.80 STUCK=0.35, need_frames:")
    for need in sorted(set(r["need"] for r in summary)):
        r = [x for x in summary
             if (x["need"], x["back"], x["stuck"]) == (need, 0.80, 0.35)]
        if r:
            lines.append("  need=%d  found %d/%d  FALSE back %d/%d  osc %d"
                         % (need, r[0]["found"], r[0]["of"], r[0]["false_back"],
                            r[0]["clean_cases"], r[0]["osc"]))
    return lines


def _fmt(x):
    return ("%.2f" % x) if isinstance(x, float) else str(x)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
