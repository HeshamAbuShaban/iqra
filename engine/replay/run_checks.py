#!/usr/bin/env python3
"""The model-free check gate. One command, one exit code.

Split deliberately by what a check NEEDS:

  none   runs anywhere. No model, no audio, no sherpa, no numpy. These are the
         checks that guard the invariants a code change can silently break -
         the search tables, the ring buffer arithmetic, the session-stream
         guard, the DP's structure, and the DP's value-identity.
  dumps  needs engine/replay/out/*.json, which come from running the real
         recogniser over recorded audio, which needs the gated model. It is
         deliberately absent from CI. These are reported SKIPPED, never as a
         silent pass: a skipped check that looks green is how the 5.3% feed
         bug survived a green build for so long.

Nothing here imports sherpa or numpy, so the gate costs a Python interpreter
and about a minute.

Run:  engine/.venv-replay/bin/python engine/replay/run_checks.py
      engine/.venv-replay/bin/python engine/replay/run_checks.py --list
"""
import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
OUT = HERE / "out"

# (name, needs, one-line description)
CHECKS = [
    ("dp_source_parity", "none",
     "the DP is unbanded, fill and backtrace agree, tie-break intact"),
    ("dp_equivalence", "none",
     "optimised DP is value-identical to the shipped one (5473 alignments)"),
    ("archive_parity", "none",
     "the session record is not pruned by the paint window or wiped by a handoff"),
    ("emission_log_replay", "none",
     "the emission log grows across the stream resets a real session performs"),
    ("evidence_window_parity", "none",
     "each ayah is judged against its own audio, red means a real WRONG, and the "
     "lock policy is unchanged"),
    ("ui_state_parity", "none",
     "every reader remember() keys the navigation values it reads"),
    ("word_alignment_parity", "none",
     "how many ayat get no word verdict, and that it is not getting worse"),
    ("search_parity", "none",
     "search tables match the validated dual-reading behaviour"),
    ("ring_buffer", "none",
     "ring wrap-around, eviction and resync behave as the loop expects"),
    ("session_stream", "none",
     "every session gets a live native stream; the log cannot claim audio"),
    # Needs the dumps despite importing cleanly: with no real observations it
    # measures nothing, and a "PASS" over an empty set would be a lie.
    ("word_rule_sweep", "dumps",
     "per-word status rule measured over 154 real word observations"),
    ("word_verdicts", "dumps",
     "per-word CORRECT/WRONG/SKIPPED on labelled mutations"),
    ("lock_trace", "dumps",
     "the app's own lock policy over the recorded surahs"),
    ("back_policy_sweep", "dumps",
     "BACK/STUCK thresholds measured, including false back-moves"),
    ("hesitation_policy", "dumps",
     "repeat and backtrack scenarios built from real emissions"),
    ("pinned_escape", "dumps",
     "the deadlock state has an exit: Ar-Rahman 55 freezes without it"),
    ("handoff_boundary", "dumps",
     "surah handoff is reachable, and the page can show what it waits for"),
    ("advisory_parity", "none",
     "advisories inform, they never accuse: not red, not streaked, not in hide"),
    ("pref_consumption", "none",
     "every Settings toggle is consumed somewhere but the settings UI"),
    ("session_record_integrity", "none",
     "a session's counters survive browsing; only the next startRecite clears them"),
    ("dead_code", "none",
     "nothing in the recognition surface is declared and never referenced"),
]

# Scripts that need a dump argument rather than running bare.
DUMPED = {"word_verdicts", "lock_trace", "back_policy_sweep", "hesitation_policy",
          "pinned_escape", "handoff_boundary"}

CASES = re.compile(r"(\d+)\s+checked,\s+(\d+)\s+failed")
# `lock_trace` prints "  moves=6  oscillations=0  final lock=1:7"; the gate has
# to read the FINAL LOCK, not a substring the script prints unconditionally.
FINAL = re.compile(r"final lock=(\d+:\d+)")
OSC = re.compile(r"oscillations=(\d+)")

CLIPS = [("s001", 1, 7), ("s103", 103, 3), ("s108", 108, 3),
         ("s112", 112, 4), ("s113", 113, 5), ("s114", 114, 6)]


def have_dumps() -> bool:
    return all((OUT / f"{c[0]}.json").is_file() for c in CLIPS)


def run(name: str, needs: str) -> tuple:
    """-> (status, detail). status is PASS / FAIL / SKIP."""
    if needs == "dumps" and not have_dumps():
        return "SKIP", "no token dumps (needs the gated model)"
    script = HERE / f"{name}.py"
    if not script.is_file():
        return "SKIP", "not present"
    t0 = time.time()
    if name == "word_verdicts":
        # Takes (dump, surah, ayat) and returns non-zero on any failure.
        r = subprocess.run([sys.executable, str(script)],
                           capture_output=True, text=True)
        checked = 0
        failed = 0
        for stem, s, n in CLIPS:
            p = subprocess.run([sys.executable, str(script), str(OUT / f"{stem}.json"), str(s), str(n)],
                               capture_output=True, text=True)
            if p.returncode != 0:
                return "FAIL", p.stdout.strip().splitlines()[-1][:90]
            for line in p.stdout.splitlines():
                m = CASES.search(line)
                if m:
                    # BOTH numbers, from the regex that captures both. This used
                    # to read group(1) - the CHECKED count - and then print a
                    # literal "0 failed" in its own message. So the gate reported
                    # "64 cases, 0 failed" with all 64 failing, and a fault that
                    # removed the failure counter entirely left the whole suite
                    # green. A reported total that cannot disagree with reality is
                    # worse than no total: it looks like a measurement.
                    checked += int(m.group(1))
                    failed += int(m.group(2))
        if not checked:
            return "FAIL", "no cases ran - a PASS over an empty set is not a result"
        # The detection ratios must be COMPLETE, not merely present. Both
        # reported "0 flagged of 6" and "9 clean of 29" while the check passed
        # for six other clips, because only the failure COUNT was summed.
        #
        # So: every substitution case must have produced WRONG, and every skip
        # case a clean SKIPPED. Partial detection is a failure, because the
        # alternative is accusing the reciter without evidence - which is the
        # whole failure mode this project exists to avoid.
        ratios = []
        q0 = ""
        for stem, s_, n_ in CLIPS:
            q = subprocess.run([sys.executable, str(script), str(OUT / f"{stem}.json"),
                                str(s_), str(n_)], capture_output=True, text=True)
            for key in ("SUBSTITUTION DETECTION", "SKIP DETECTION"):
                mm = re.search(key + r": (\d+) \w+ of (\d+)", q.stdout)
                if mm:
                    ratios.append((key.split()[0], int(mm.group(1)), int(mm.group(2))))
        if not ratios:
            return "FAIL", "no detection ratios reported - the check is not reporting what it measures"
        for key, got, want in ratios:
            if got != want:
                # The reason matters: "9/29" and "9/29 with 20 excused" are the
                # same headline and opposite findings. Report the excuse.
                m2 = re.search(key + r": (\d+) \w+ of (\d+) cases \((\d+) excused", q0)
                exc = (" (%s excused as inconclusive)" % m2.group(3)) if m2 else ""
                kind = ("a substituted word must be detected"
                        if key == "SUBSTITUTION"
                        else "a skipped word must be read SKIPPED with no collateral")
                return ("FAIL",
                        f"{key}: {got}/{want} detected{exc}. {kind}. Either an "
                        f"error is being missed, or the reciter is being accused "
                        f"without evidence.")
        # A per-clip run must contribute cases. Proven by fault injection: making
        # the substitution test `continue` instead of judging drove every clip to
        # zero cases, and the gate reported "0 cases across 6 clips" as a detail
        # string on a PASS. Silence read as success.
        empty = [stem for stem, s_, n_ in CLIPS
                 if not any(CASES.search(l)
                            for l in subprocess.run(
                                [sys.executable, str(script), str(OUT / f"{stem}.json"),
                                 str(s_), str(n_)],
                                capture_output=True, text=True).stdout.splitlines())]
        if empty:
            return "FAIL", f"no cases exercised on {empty[:3]}"
        if failed:
            return "FAIL", f"{failed} of {checked} word-verdict cases failed"
        return "PASS", f"{checked} cases across 6 clips, 0 failed"
    if name == "lock_trace":
        # Every clip must REACH its last ayah, sequentially, with no oscillation.
        #
        # This used to test `p.returncode != 0 or "oscillations=0" not in
        # stdout`, and the script exits 0 unconditionally while printing the
        # literal "28/28 ayat, 0 reversals" itself. So the gate tested for a
        # substring the script always printed. Proved by fault injection twice:
        # disabling the forward-advance branch in PracticeViewModel, and raising
        # the harness's advance_coverage to 0.99 where nothing can advance - both
        # left this check PASS. The gate could not fail, so nothing else it
        # reported could be trusted.
        bad = []
        reached = 0
        osc = 0
        for stem, s, n in CLIPS:
            p = subprocess.run([sys.executable, str(script), str(OUT / f"{stem}.json"),
                                "--surah", str(s), "--ayat-count", str(n)],
                               capture_output=True, text=True)
            if p.returncode != 0:
                bad.append(f"{stem}: exit {p.returncode}")
                continue
            m = FINAL.search(p.stdout)
            if not m:
                bad.append(f"{stem}: no final lock reported")
                continue
            got = m.group(1)
            want = f"{s}:{n}"
            if got != want:
                bad.append(f"{stem}: ended at {got}, expected {want}")
            reached += 1
            o = OSC.search(p.stdout)
            if o:
                osc = max(osc, int(o.group(1)))
        if osc:
            bad.append(f"{osc} oscillation(s) across the clips")
        if not reached:
            return "FAIL", "no clip produced a result - a PASS here would be vacuous"
        if bad:
            return "FAIL", "; ".join(bad[:3])
        return "PASS", f"{reached}/{len(CLIPS)} clips reached their last ayah, 0 oscillations"
    r = subprocess.run([sys.executable, str(script)], capture_output=True, text=True)
    if r.returncode != 0:
        tail = [l for l in r.stdout.splitlines() if l.strip().startswith("-")]
        return "FAIL", (tail[0].strip()[:100] if tail
                        else (r.stderr.strip().splitlines() or ["exit %d" % r.returncode])[-1][:100])
    first = next((l for l in r.stdout.splitlines() if l.startswith("PASS")), "")
    return "PASS", (first[:100] or "ok")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--only", default=None, help="comma-separated check names")
    args = ap.parse_args()

    if args.list:
        for n, needs, desc in CHECKS:
            print(f"{n:20s} needs={needs:6s} {desc}")
        return 0

    selected = CHECKS
    if args.only:
        want = {s.strip() for s in args.only.split(",")}
        selected = [c for c in CHECKS if c[0] in want]

    print(f"check gate  python={sys.version.split()[0]}  dumps={'yes' if have_dumps() else 'no'}")
    print("-" * 100)
    failures = 0
    skipped = 0
    t0 = time.time()
    for name, needs, desc in CHECKS:
        if name not in {c[0] for c in selected}:
            continue
        status, detail = run(name, needs)
        if status == "FAIL":
            failures += 1
        elif status == "SKIP":
            skipped += 1
        print(f"  {status:4s} {name:20s} {detail}")
    print("-" * 100)
    print(f"{failures} failed, {skipped} skipped, {(time.time() - t0):.0f}s")
    if skipped:
        print("SKIPPED checks are NOT passes. They need the gated model; run them")
        print("locally with engine/.venv-replay/bin/python engine/replay/run_checks.py")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
