"""Guards for the per-ayah evidence window and the verdict palette.

WHY THIS EXISTS
---------------
The worst defect found on the device. The lock advances when the reciter is
`ADVANCE_COVERAGE` (0.60) through the target ayah, so the last ~40% of every
ayah is still being spoken when the lock leaves it. Judging used
`obs = symbols.drop(sliceStart)`, and `sliceStart` is rebased to zero on every
lock move - so the audio that would judge an ayah's trailing words was gone the
instant the lock left it, and behind-lock words were re-aligned against the NEXT
ayah's audio instead.

Measured over a 302 s recitation of 2:59-2:76 that the lock followed correctly to
0.933 coverage: **365 SKIPPED, 24 UNKNOWN, 0 CORRECT, 0 WRONG**, every word
painted red. SKIPPED rendered as `wrongColor` with a strikethrough, so "no
evidence" was drawn as "you got this wrong".

THE SECOND FAILURE, WHICH THE FIRST FIX CAUSED
---------------------------------------------
Fixing that needed arrival indices into the emission list. But
`sherpa-onnx`'s `getResult()` returns tokens **since the last `reset()`**, and
`resetAudioPipeline()` calls `resetStream()` on EVERY lock move - 19 times in one
recorded session, 29 in another. So `res.symbols` restarts near zero once per
ayah, the arrival indices pointed into a list that had been discarded, every
window computed `end <= from`, and **every word became UNKNOWN**. Six sessions
recorded `0 CORRECT, 0 WRONG, 0 SKIPPED, 29-333 UNKNOWN` and the report showed no
data at all. Offline reproduction: CORRECT yield collapses 29.5% -> 0.0%.

The fix is a session-scoped emission log: arrivals index a list that no stream
reset can invalidate. The stream still resets on lock moves - that is what keeps
the lock responsive - but a reset no longer moves where anything lives.

WHAT IS ASSERTED
----------------
1. The lock policy itself is UNCHANGED, so the 26 h corpus result still holds and
   no re-run is owed.
2. Arrivals are absolute indices into a SESSION log, appended once per poll, and
   that log is cleared at session start.
3. A window is clamped into the surviving range, so trimming cannot collapse it.
   "Never visited" (null) is distinct from "visited but empty".
4. Red is reserved for a real WRONG verdict.
5. A handoff cannot fire while the surah being left is unfinished - unless the
   reciter swiped onto the next surah, which is intent.
6. Only a USER page turn arms the intent handoff, never a programmatic scroll.
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
    # The condition folded into `surahDone`, which ORs the evidence gate with the
    # intent gate. Assert BOTH inputs, because either one alone can silently
    # become the whole gate.
    if "val surahDone = byIntent || hereDone >= HANDOFF_SURAH_DONE" not in vm:
        failures.append(
            "PracticeViewModel.kt: the handoff condition does not combine the "
            "outgoing surah's completeness with the page-turn intent. Without the "
            "evidence term it fires while the surah being left is still being "
            "recited - measured as `handoff -> s=2:1 coverage=0.63` 35 s into "
            "Al-Fatiha, which the reciter then had to fight.")
    if not re.search(r"handoffFrames >= if \(byIntent\) INTENT_FRAMES else HANDOFF_FRAMES", vm):
        failures.append(
            "PracticeViewModel.kt: the handoff fires on a single frame. A lone frame "
            "is not evidence of intent; the forward advance already needs two.")
    if not re.search(r"val byIntent = intentHandoffAyah == activeSurah \+ 1", vm):
        failures.append(
            "PracticeViewModel.kt: the intent path does not check that the armed "
            "surah is the NEXT surah. A stale intent would hand off to a surah the "
            "reciter never swiped to.")
    if "lastAyahObsCoverage(" not in vm:
        failures.append("PracticeViewModel.kt: the handoff does not check the outgoing surah.")

    # 6. arrivals must be absolute into a SESSION log, not into the per-stream one
    # Structural, not textual: the earlier mutation ADDED the declaration while
    # breaking every use of it, and a `not in vm` test passed. A declaration
    # nothing writes is not an emission log.
    log_writes = len(re.findall(r"emissionLog\.(?:add|clear|removeAt|subList|size)", vm))
    log_decl = "private val emissionLog = ArrayList<String>()" in vm
    # Exactly the three operations that make it a log. A count threshold passed
    # M14 (the append deleted) because nine READS of emissionLog.size remained -
    # the check was measuring reads and calling them writes. Each is asserted.
    log_appends = len(re.findall(r"emissionLog\.add\(", vm))
    log_clears = len(re.findall(r"emissionLog\.clear\(\)", vm))
    log_reads = len(re.findall(r"emissionLog\.subList\(", vm))
    # "Trims" means the log actually gets smaller. `emissionBase` advancing
    # is asserted separately below, because a trim that forgets to advance
    # the base is exactly the silent shift this whole fix is about.
    log_trims = len(re.findall(r"emissionBase \+= drop", vm))
    if not log_decl:
        failures.append(
            "PracticeViewModel.kt: no session-scoped emissionLog. sherpa's "
            "getResult() returns tokens since the last reset(), and the stream is "
            "reset on every lock move, so per-stream indices are invalidated ~19 "
            "times per session. That made every ayahObs empty and every word "
            "UNKNOWN - six sessions with 0 CORRECT.")
    else:
        if log_appends < 1:
            failures.append(
                "PracticeViewModel.kt: emissionLog is never appended to. "
                "appendEmissions() is what copies each poll's new tokens in, and "
                "without it the log stays empty so every ayahObs is empty and "
                "every word UNKNOWN.")
        if log_clears < 1:
            failures.append(
                "PracticeViewModel.kt: emissionLog is never cleared, so arrivals "
                "from a previous session index audio that no longer exists.")
        if log_reads < 1:
            failures.append(
                "PracticeViewModel.kt: emissionLog is never read. A log that is "
                "written but never indexed cannot produce a window.")
        if log_trims < 1:
            failures.append(
                "PracticeViewModel.kt: emissionLog is never trimmed, so a long "
                "session grows without bound and emissionBase never advances.")
        # The base must advance WITH the trim, or absolute arrivals silently
        # shift by however much was dropped.
        if "emissionBase += drop" not in vm:
            failures.append(
                "PracticeViewModel.kt: the log is trimmed without advancing "
                "emissionBase, so every arrival index shifts by the number of "
                "dropped symbols and each window points at the wrong audio.")
        # And a trim must not be O(n) per dropped element: that is on the audio
        # path, every poll.
        if re.search(r"for \([^)]*\)\s*emissionLog\.removeAt\(0\)", vm):
            failures.append(
                "PracticeViewModel.kt: the log is trimmed with removeAt(0) in a "
                "loop, which is O(n) per dropped element on the audio path.")
    if "private val emissionLog" not in vm:
        failures.append(
            "PracticeViewModel.kt: no session-scoped emissionLog. sherpa's "
            "getResult() returns tokens since the last reset(), and the stream is "
            "reset on every lock move, so per-stream indices are invalidated ~19 "
            "times per session. That made every ayahObs empty and every word "
            "UNKNOWN - six sessions with 0 CORRECT.")
    # `emissionBase + sliceStart` LOOKS absolute but is not: sliceStart is a
    # per-stream cursor that restarts near zero on every reset, so it is not a
    # position in the log. Measuring the wrong form collapsed CORRECT from 94.8%
    # to 3.6% in word_window_yield.py, so this is pinned exactly.
    if "ayahArrival[it] = emissionBase + emissionLog.size" not in vm:
        failures.append(
            "PracticeViewModel.kt: arrivals are not absolute. They must be "
            "emissionBase + emissionLog.size - the end of the session log at that "
            "instant. emissionBase + sliceStart is per-stream and restarts near "
            "zero on every lock move.")
    if "val aObs = ayahObs(activeSurah, a)" not in vm:
        failures.append(
            "PracticeViewModel.kt: the paint loop does not call the window helper "
            "by surah/ayah. It must read the session log, not res.symbols.")
    if "ayahObs(res.symbols" in vm:
        failures.append(
            "PracticeViewModel.kt: a window still reads res.symbols, which resets "
            "per stream. This is the exact defect that made every word UNKNOWN.")
    if "clearEmissionLog()" not in vm:
        failures.append(
            "PracticeViewModel.kt: the emission log is never cleared, so arrivals "
            "from a previous session index audio that no longer exists.")
    # "never visited" must be distinguishable from "visited but empty"
    if "return null" not in vm.split("private fun ayahObs")[1].split("private fun lastAyahObsCoverage")[0]:
        failures.append(
            "PracticeViewModel.kt: ayahObs does not return null for 'never "
            "visited'. An empty window used to be the silent form of a stale "
            "index and read as UNKNOWN on every word.")
    if "maxOf(ayahArrival" not in vm or "emissionBase" not in vm:
        failures.append(
            "PracticeViewModel.kt: the window is not clamped into the surviving "
            "log range, so a trimmed arrival collapses it to nothing.")

    # 7. only a USER page turn may arm the intent handoff
    if "fun onUserPageTurn(" not in vm:
        failures.append(
            "PracticeViewModel.kt: no onUserPageTurn(). Scenario 3 - deliberately "
            "choosing the next surah - has no path, so it still demands the "
            "current surah be finished first.")
    ma_raw = open(MA, encoding="utf-8").read()
    if "if (byUser) vm.onUserPageTurn(page)" not in ma_raw:
        failures.append(
            "MainActivity.kt: onUserPageTurn is not gated on a user drag. Without "
            "that guard the app's own scrolls (lock follow, deep link, handoff "
            "latch) arm the intent handoff and a surah changes unasked.")
    if "val byUser = userTurned" not in ma_raw:
        failures.append("MainActivity.kt: programmatic scrolls are not distinguished from drags.")

    # 8. the handoff must reset arrivals, and a stale anchor must not survive
    handoff_body = vm.split("if (next != null && surahDone)")[1][:2600] if "if (next != null && surahDone)" in vm else ""
    if handoff_body:
        if "ayahArrival.clear()" not in handoff_body:
            failures.append(
                "PracticeViewModel.kt: the handoff does not clear ayahArrival, so "
                "the new surah inherits indices recorded against the old ayah "
                "sequence.")
        if "pendingAnchor = null" not in handoff_body:
            failures.append(
                "PracticeViewModel.kt: the handoff does not clear pendingAnchor. "
                "A pending anchor names an ayah in the surah being LEFT, and "
                "applying it to the new surah is what made resuming hinder the "
                "second surah.")
        if "intentHandoffAyah = 0" not in handoff_body:
            failures.append("PracticeViewModel.kt: the handoff does not disarm the intent.")
    else:
        failures.append("PracticeViewModel.kt: cannot find the handoff body.")

    # 9. judged must not count SKIPPED
    if "val judged: Int get() = correct + wrong + skipped" in log:
        failures.append(
            "PracticeLog.kt: Session.judged counts SKIPPED, so a session that "
            "tested nothing reports a large 'words judged' and an accuracy derived "
            "from it - a population wearing the costume of a score.")

    # 10. the headline number must not count non-verdicts as judged
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