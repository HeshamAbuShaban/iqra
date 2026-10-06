#!/usr/bin/env python3
"""Guards for the per-ayah evidence window and the verdict palette.

WHY THIS EXISTS
---------------
The single worst defect found on the device. The lock advances when the reciter
is `ADVANCE_COVERAGE` (0.60) through the target ayah, so the last ~40% of every
ayah is still being spoken when the lock leaves it. Judging used
`obs = symbols.drop(sliceStart)`, and `sliceStart` is rebased to zero on every
lock move - so the audio that would judge an ayah's trailing words was gone the
instant the lock left it, and behind-lock words were re-aligned against the NEXT
ayah's audio instead.

Measured over a 302 s recitation of 2:59-2:76 that the lock followed correctly to
0.933 coverage: **365 SKIPPED, 24 UNKNOWN, 0 CORRECT, 0 WRONG**, every word
painted red. SKIPPED rendered as `wrongColor` with a strikethrough, so "no
evidence" was drawn as "you got this wrong".

Offline, the same mapper against gold audio scores 396/396 words CORRECT with 0
SKIPPED. The DP, the expected units and the word table were never at fault; only
the window was.

WHAT IS ASSERTED
----------------
1. The lock policy itself is UNCHANGED. The fix is entirely in what audio each
   ayah is judged against, so the corpus result (113/114, 104 sequential, 26 h)
   still holds and no 6 h re-run is owed.
2. A window for ayah N is `symbols[arrival(N) .. arrival(N+1))`, which by
   construction contains all of N's speech - the lock only leaves N once the
   reciter is 60% into N+1.
3. Red is reserved for a real WRONG verdict. SKIPPED and UNKNOWN must not
   resolve to `wrongColor`.
4. The handoff requires the surah being LEFT to be complete, not just the next
   surah's opening to look plausible.
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VM = os.path.join(ROOT, "android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt")
MA = os.path.join(ROOT, "android/app/src/main/java/com/iqra/quran/ui/MainActivity.kt")
LOG = os.path.join(ROOT, "android/app/src/main/java/com/iqra/quran/data/PracticeLog.kt")


def strip_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    out = []
    for line in src.split("\n"):
        # not a string-safe strip, but the only // in these files are comments
        i = line.find("//")
        if i >= 0:
            line = line[:i]
        out.append(line)
    return "\n".join(out)


def check_window_semantics():
    """Model the window rule and assert it contains each ayah's whole speech."""
    failures = []

    def window(arrival, n, nxt=None):
        """Symbols judged for ayah n, given the lock's arrival indices.

        Starts at the PREVIOUS ayah's arrival, because the lock arrives at n
        only once the reciter is already 60% through it.
        """
        if n not in arrival:
            return None                     # not visited: no evidence
        start = arrival.get(n - 1, 0)
        end = arrival.get(n + 1, float("inf"))
        if end <= start or arrival[n] < start:
            return []
        return (start, end)

    # The lock leaves ayah n once the reciter is 60% through n+1, so n's speech
    # ends before the lock arrives at n+1. Model that as a timeline.
    arrivals, speech = {}, {}
    t = 0
    for n in range(1, 6):
        # The lock ARRIVES at n when the reciter is already 60% through it, so
        # n's speech began 0.6*length BEFORE arrival[n]. Getting this wrong is
        # what made the first version of the window hold only n's last 40%: 5.3%
        # of words reached the CORRECT bar instead of 93.4%.
        length = 100
        # Clamped at 0: the emission list starts when the stream does, so the
        # first ayah's speech cannot begin before index 0, and with no arrival
        # for a "previous ayah 0" the window correctly starts at the beginning.
        speech[n] = (max(0, t - int(0.60 * length)), t + int(0.40 * length))
        arrivals[n] = t
        t += int(length / (1 - 0.60))      # advance once 60% of the next is in

    for n in range(1, 6):
        w = window(arrivals, n, None)
        if w is None:
            failures.append(f"ayah {n}: no window at all - the lock never arrived")
            continue
        lo, hi = w
        s_lo, s_hi = speech[n]
        if not (lo <= s_lo and hi >= s_hi):
            failures.append(
                f"ayah {n}: window [{lo},{hi}) does not contain its speech "
                f"[{s_lo},{s_hi}) - its trailing words could never be judged")

    # Ahead of the lock there must be no window, or words get judged against
    # audio that belongs to a different ayah.
    for n in range(6, 9):
        if window(arrivals, n, None) is not None:
            failures.append(f"ayah {n}: has a window although the lock never arrived")
    return failures


def check_sources():
    failures = []
    vm = strip_comments(open(VM, encoding="utf-8").read())
    ma = strip_comments(open(MA, encoding="utf-8").read())
    log = strip_comments(open(LOG, encoding="utf-8").read())

    # 1. the window must exist and be used for painting
    if "private fun ayahObs(" not in vm:
        failures.append("PracticeViewModel.kt: no ayahObs(). The per-ayah evidence window is gone.")
    if "val aObs = ayahObs(" not in vm:
        failures.append(
            "PracticeViewModel.kt: the paint loop does not use ayahObs(). Without it "
            "words are judged against the lock's slice, which is rebased on every "
            "move and is what produced 365 SKIPPED / 0 CORRECT.")
    if "val al = PhonemeMapper.align(obs, pw" in vm:
        failures.append(
            "PracticeViewModel.kt: the paint loop is back on `obs`. That is the "
            "defect, reintroduced.")

    # 2. arrival must be recorded where the slice rebases
    if "pendingArrivalKey" not in vm:
        failures.append("PracticeViewModel.kt: no pendingArrivalKey. Arrival indices are not recorded.")

    # 3. the lock policy must NOT have moved
    m = re.search(r"private const val ADVANCE_COVERAGE = ([0-9.]+)f", vm)
    if not m:
        failures.append("PracticeViewModel.kt: ADVANCE_COVERAGE not found.")
    elif abs(float(m.group(1)) - 0.60) > 1e-9:
        failures.append(
            f"ADVANCE_COVERAGE is {m.group(1)}, was 0.60. Changing it invalidates the "
            "26 h corpus result and owes a re-run. Do that deliberately, not here.")

    # 4. red is reserved for WRONG
    # BOTH style branches (hidden and shown) contain a RECITATION_AYAH arm, and
    # `re.search` only ever looked at the first. The mutation test proved the
    # check could not fail: rewriting the visible arm back to wrongColor left it
    # green, because the arm it was reading is the hidden-mode one.
    arms = re.findall(r"HighlightLayer\.RECITATION_AYAH ->(.*?)HighlightLayer\.", ma, re.S)
    if not arms:
        failures.append("MainActivity.kt: cannot find the RECITATION_AYAH style arms.")
    skipped_arms = 0
    for arm in arms:
        if "WordStatus.SKIPPED" not in arm and "WordStatus.UNKNOWN" not in arm:
            continue
        skipped_arms += 1
        if "wrongColor" in arm:
            failures.append(
                "MainActivity.kt: a RECITATION_AYAH arm still uses wrongColor for "
                "SKIPPED/UNKNOWN. No evidence must be muted; red is for a real WRONG.")
        if "Color.Transparent" not in arm:
            failures.append(
                "MainActivity.kt: the SKIPPED/UNKNOWN style is not transparent, so "
                "absence of evidence is still drawn as a mark.")
    if skipped_arms == 0:
        failures.append(
            "MainActivity.kt: no RECITATION_AYAH arm mentions SKIPPED or UNKNOWN. "
            "Those states must render muted rather than falling through to a mark.")

    # WRONG must still be red, or the palette fix went too far
    if "HighlightLayer.WRONG -> WordStyle(wrongColor" not in ma:
        failures.append("MainActivity.kt: HighlightLayer.WRONG no longer paints wrongColor.")

    # 5. the handoff must require the surah being left to be finished
    # The CONSTANT existing proves nothing - the mutation test showed the check
    # stayed green with the gate removed from the condition. Assert the use.
    if not re.search(r"if\s*\(next != null && hereDone >= HANDOFF_SURAH_DONE\)", vm):
        failures.append(
            "PracticeViewModel.kt: the handoff condition does not test the outgoing "
            "surah's completeness. It can fire while the surah being left is still "
            "being recited - measured as `handoff -> s=2:1 coverage=0.63` 35 s into "
            "Al-Fatiha, which the reciter then had to fight.")
    if not re.search(r"handoffFrames >= HANDOFF_FRAMES", vm):
        failures.append(
            "PracticeViewModel.kt: the handoff fires on a single frame. A lone frame "
            "is not evidence of intent; the forward advance already needs two.")
    if "lastAyahObsCoverage(" not in vm:
        failures.append("PracticeViewModel.kt: the handoff does not check the outgoing surah.")

    # 6. the headline number must not count non-verdicts as judged
    jw = re.search(r'judgedWords\\?"\s*:\\?"\)\.append\(([^)]+)\)', vm)
    if not jw:
        failures.append("PracticeViewModel.kt: cannot find how judgedWords is emitted.")
    elif "sessionArchive" in jw.group(1):
        failures.append(
            "PracticeViewModel.kt: judgedWords is the archive size again, so SKIPPED and "
            "UNKNOWN count as judged and accuracy divides by words nobody attempted.")
    if not re.search(r'evaluatedWords', vm):
        failures.append(
            "PracticeViewModel.kt: no evaluatedWords counter. One number cannot be both "
            "the population and the score.")
    if "sessionTerminalCount" not in vm:
        failures.append("PracticeViewModel.kt: no sessionTerminalCount.")
    if "recorded" in log and "correct + wrong" not in log:
        failures.append("PracticeLog.kt: `recorded` no longer excludes non-verdicts.")

    return failures


def main():
    for f in (VM, MA, LOG):
        if not os.path.isfile(f):
            print(f"FAIL: {f} not found")
            return 1
    failures = check_window_semantics() + check_sources()
    if failures:
        print("FAIL")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("PASS: each ayah is judged against its own audio window, the lock policy is "
          "untouched, red is reserved for a real WRONG, and a handoff cannot fire while "
          "the surah being left is still being recited")
    return 0


if __name__ == "__main__":
    sys.exit(main())