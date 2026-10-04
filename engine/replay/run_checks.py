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
]

# Scripts that need a dump argument rather than running bare.
DUMPED = {"word_verdicts", "lock_trace", "back_policy_sweep", "hesitation_policy",
          "pinned_escape", "handoff_boundary"}

CASES = re.compile(r"(\d+)\s+checked,\s+(\d+)\s+failed")

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
        worst = 0
        for stem, s, n in CLIPS:
            p = subprocess.run([sys.executable, str(script), str(OUT / f"{stem}.json"), str(s), str(n)],
                               capture_output=True, text=True)
            for line in p.stdout.splitlines():
                m = CASES.search(line)
                if m:
                    # The CASES count is the number BEFORE "checked"; parsing
                    # the word after it reported "0 cases" on a fully passing run.
                    worst = max(worst, int(m.group(1)))
            if p.returncode != 0:
                return "FAIL", p.stdout.strip().splitlines()[-1][:90]
        return "PASS", f"{worst} cases across 6 clips, 0 failed"
    if name == "lock_trace":
        bad = []
        for stem, s, n in CLIPS:
            p = subprocess.run([sys.executable, str(script), str(OUT / f"{stem}.json"),
                                "--surah", str(s), "--ayat-count", str(n)],
                               capture_output=True, text=True)
            if p.returncode != 0 or "oscillations=0" not in p.stdout:
                bad.append(stem)
        return ("FAIL", f"non-sequential or oscillating: {bad}") if bad else \
               ("PASS", "28/28 ayat, 0 reversals")
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
