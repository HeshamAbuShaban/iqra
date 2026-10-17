#!/usr/bin/env python3
"""A jump past an ayah may not leave it permanently unjudged.

Measured 17 October on the user's mid-page walk (record of the sibling-escape
build): the lock went 107:1 -> 3 in one poll - the short 107:2 was never the
locked ayah - and its four words retired UNKNOWN in a session the reciter
completed. The engine now re-judges each passed-over ayah at the jump, against
the ayah's OWN window (the per-ayah evidence windows, not the lock's slice),
and only a word whose every unit matched is promoted: the catch-up can turn an
UNKNOWN green, cannot invent a red.

This check pins the whole contract, each clause in its own grep so a fault
injection can knock out one clause at a time:

  1. the rule exists (`rejudgeJumpedAyat` defined AND called from the jump),
  2. the stamp exists (ayahObs refuses a never-visited ayah; the skipped one
     must get its arrival, or the catch-up is dead code that greps well),
  3. the evidence window is per-ayah (the paints-judged-defect the evidence
     window check already guards; named here because the catch-up shares its
     hazards),
  4. the promotion is all-of-word (a partial word must not become CORRECT),
  5. no demotion path exists inside the catch-up (it can never accuse).
"""

import os
import sys

VMS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..",
                   "android", "app", "src", "main", "java", "com", "iqra",
                   "quran", "ui", "PracticeViewModel.kt")


def main():
    vm = open(VMS, encoding="utf-8").read()
    failures = []

    if "private fun rejudgeJumpedAyat(from: Int, to: Int)" not in vm:
        failures.append("the catch-up rule is gone (rejudgeJumpedAyat)")
    if "rejudgeJumpedAyat(lockedAyah, ahead.first)" not in vm:
        failures.append("the catch-up is never called from the jump")
    if "if (ayahArrival[key] == null) ayahArrival[key] = logEnd" not in vm:
        failures.append("the skipped ayah's arrival is never stamped "
                        "(ayahObs refuses a never-visited ayah)")
    if "val aObs = ayahObs(activeSurah, a) ?: continue" not in vm:
        failures.append("the catch-up no longer reads the ayah's own window")
    if "if (al?.wordCoverage?.getOrElse(i) { 0f } != 1f) continue" not in vm:
        failures.append("the all-of-word promotion bar is gone")
    for banned in ("WordStatus.WRONG", "WordStatus.SKIPPED", "addAdvisory"):
        block = vm[vm.index("rejudgeJumpedAyat(from"):vm.index("private fun advanceLockTo")]
        if banned in block:
            failures.append("the catch-up contains %s - it must be "
                            "CORRECT-only" % banned)

    for line in failures:
        print("FAIL jump_rejudge_parity - " + line)
    print("(none of the failures hold)" if not failures else "")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
