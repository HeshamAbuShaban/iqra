#!/usr/bin/env python3
"""The session record must not lose verdicts to pruning or a surah change.

Two defects, both reported by reading a real record off a phone, and both of the
kind that produce a clean-looking file:

  * `loadSurah()` clears `sessionStatuses`, and a surah handoff calls
    `loadSurah(activeSurah + 1)`. So the one moment a session spans two surahs is
    the moment every verdict it produced is destroyed - before the final write.
  * `sessionStatuses` is pruned to the lock's +-2 every frame, and drops UNKNOWN.
    That is correct for PAINTING (stale marks must never freeze on screen) and
    fatal for ARCHIVING.

They were invisible because the record was written from the paint map AND
filtered to WRONG/UNKNOWN only, so a clean session came back with zero verdicts
and read like a wipe rather than like success.

The fix is two separate stores: the paint map forgets, `sessionArchive` does not.

This check is static because the rule is Kotlin logic in a file CI cannot execute,
and it is deliberately narrow - a check that flags legitimate forgetfulness gets
deleted, and then the next one ships. It asserts ONLY that:

  1. `loadSurah` does not clear the archive
  2. the prune `removeAll` does not reach the archive
  3. the archive is cleared exactly once per session, at session start
  4. the retention chain is position-independent and keeps a terminal verdict -
     the locked ayah's "provisional" revision was removed deliberately on
     18 October (see `retention()`); it was the paint-and-unpaint defect

Run: engine/.venv-replay/bin/python engine/replay/archive_parity.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VM = ROOT / "android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt"

ARCHIVE = "sessionArchive"
LOAD_SURAH = "fun loadSurah("
PRUNE = "sessionStatuses.keys.removeAll"


def strip_noise(src: str) -> str:
    """Blank out comments, preserving offsets.

    Comments only - no string-literal stripping. An earlier version did both and
    had to track quote parity across the whole 2,000-line file; this one has an
    odd number of quotes somewhere, the scan ran past the end of the file, and
    the check reported "loadSurah not found" against the real source. A guard that
    cannot find the thing it guards is worse than no guard, because it reads as a
    clean bill of health for a file it never looked at.

    Comments are the only thing that needs removing here: no string literal in
    this file contains `loadSurah(`, `sessionArchive` or `removeAll`, and if one
    ever did, a false positive is the correct amount of noise - a wrong clearing
    of the archive is worth being shouted at for.
    """
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            while i < n and src[i] != "\n":
                out[i] = " "
                i += 1
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                if src[i] != "\n":
                    out[i] = " "
                i += 1
            for k in range(i, min(i + 2, n)):
                out[k] = " "
            i += 2
        else:
            i += 1
    return "".join(out)


def block(src, start, opener="{", closer="}"):
    """The brace-balanced body starting at `start`."""
    i = src.index(opener, start)
    depth = 0
    while i < len(src):
        if src[i] == opener:
            depth += 1
        elif src[i] == closer:
            depth -= 1
            if depth == 0:
                return src[start:i]
        i += 1
    return ""


def check(src, name="PracticeViewModel.kt"):
    src = strip_noise(src)
    failures = []

    ls = src.find(LOAD_SURAH)
    if ls < 0:
        return ["loadSurah not found - the structure changed; update this check"]
    body = block(src, ls)
    if f"{ARCHIVE}.clear()" in body:
        failures.append(
            f"{name}: loadSurah() clears {ARCHIVE}. A surah handoff calls "
            "loadSurah(activeSurah + 1), so crossing a surah boundary would "
            "destroy every verdict the session produced before the final write - "
            "which is exactly what happened and read as a wipe.")

    # Scan EVERY prune, not one hardcoded call. Anchoring on a single literal
    # meant the guard only ever inspected that literal: fault injection wrapped
    # the prune around the archive and the check still passed, because it found
    # the inner call and read only that. A guard that checks one instance of a
    # pattern is not a guard on the pattern.
    prunes = 0
    for m in re.finditer(r"\.keys\.removeAll", src):
        prunes += 1
        seg = block(src, m.start() - 40, opener="{", closer="}")
        if ARCHIVE in seg:
            failures.append(
                f"{name}: a keys.removeAll prune reaches {ARCHIVE}. Pruning to the "
                "window is right for painting and fatal for the record; they must "
                "be separate stores.")
            break
    if prunes == 0:
        failures.append(
            f"{name}: no keys.removeAll found. The paint prune was renamed or "
            "removed - update this check rather than deleting it.")

    clears = len(re.findall(re.escape(ARCHIVE) + r"\.clear\(\)", src))
    if clears != 1:
        failures.append(
            f"{name}: {ARCHIVE}.clear() appears {clears} times, expected exactly "
            "1 (session start). More would forget part of a session; zero would "
            "carry verdicts into the next one.")

    # The verdict-retention chain lives in the same function, and it is what
    # decides whether a recited word is ever recorded as CORRECT at all. It
    # gets its own checks because its failure mode is silent: no crash, no
    # missing colour that looks like a bug, just an archive full of SKIPPED.
    failures += check_retention()
    failures += check_retention_is_wired(src)

    return failures


# The retention chain, as data. `a` is the ayah a word belongs to, `locked` the
# lock's ayah, `live` what align() just said, `held` what was retained.
#
# Kept as an explicit table because the bug it guards was a control-flow
# subtlety, not a typo: behind the lock, align() is scoring the word against the
# NEXT ayah's audio, so `live` degrades to SKIPPED for reasons that have nothing
# to do with the reciter. An `else` clause read that as "unearned" and deleted
# the verdict. Modelling it makes the intended behaviour checkable without
# reimplementing the DP.
#
# Position-independent since 18 October: a terminal verdict is final behind,
# on and ahead of the lock. The locked ayah used to be "provisional" - a live
# SKIPPED deleted a retained verdict - but the painter takes the engine's live
# map over the reader's own record, so every deletion was a green word going
# grey mid-ayah ("paint and unpaint"), and the archive never revokes.
def retention(a, locked, live, held):
    """(status after the chain, what stays retained)."""
    if held in ("CORRECT", "WRONG"):
        return held, held
    if live in ("CORRECT", "WRONG"):
        return live, live
    return live, None


RETENTION_CASES = [
    # (a, locked, live, held, expect_status, expect_held, why)
    (60, 60, "CORRECT", None, "CORRECT", "CORRECT", "earned on the locked ayah"),
    (60, 60, "SKIPPED", "CORRECT", "CORRECT", "CORRECT",
     "the window moved on inside the locked ayah; that is not a revision"),
    (60, 61, "SKIPPED", "CORRECT", "CORRECT", "CORRECT",
     "the ring moved on; SKIPPED here is missing evidence, not a skipped word"),
    (60, 61, "SKIPPED", "WRONG", "WRONG", "WRONG",
     "a wrong word stays wrong after the lock advances"),
    (60, 61, "CORRECT", None, "CORRECT", "CORRECT",
     "still inside the ring, so the live verdict stands"),
    (61, 60, "CORRECT", None, "CORRECT", "CORRECT",
     "the next ayah colours while it is being recited (ayahObs serves it)"),
    (61, 60, "SKIPPED", "CORRECT", "CORRECT", "CORRECT",
     "an ayah ahead of a backward move keeps what it earned"),
    (61, 60, "SKIPPED", None, "SKIPPED", None,
     "ahead of the lock nothing is earned yet"),
]


def check_retention():
    failures = []
    for a, locked, live, held, want_s, want_h, why in RETENTION_CASES:
        got_s, got_h = retention(a, locked, live, held)
        if got_s != want_s or got_h != want_h:
            failures.append(
                f"retention(ayah={a}, locked={locked}, live={live}, held={held}) "
                f"-> ({got_s}, {got_h}), expected ({want_s}, {want_h}) [{why}]")
    # The specific regression, named so the failure is legible.
    got_s, got_h = retention(60, 61, "SKIPPED", "CORRECT")
    if got_s == "SKIPPED" or got_h is None:
        failures.append(
            "a verdict earned behind the lock is dropped once align() starts "
            "scoring it against the next ayah's audio. Measured: 365 SKIPPED, "
            "0 CORRECT, 0 WRONG over a 302 s recitation at 0.933 coverage.")
    return failures


def check_retention_is_wired(src):
    """The Kotlin chain must still branch on the retained value, not just store it."""
    failures = []
    if "val retained = sessionStatuses[key]" not in src:
        failures.append(
            "PracticeViewModel.kt: no `val retained = sessionStatuses[key]`. The "
            "sticky-verdict chain was replaced; update this check deliberately "
            "rather than letting it go.")
    if "s = retained" not in src or "sessionStatuses[key] = s" not in src:
        failures.append(
            "PracticeViewModel.kt: the sticky-verdict chain no longer branches on "
            "the retained value (`s = retained` / `sessionStatuses[key] = s`). "
            "Without it a retained verdict has nothing keeping it alive.")
    # Position-independent: a terminal verdict is final behind, on and ahead of
    # the lock. Re-introducing a per-position branch is how the locked ayah's
    # revocation ("provisional") comes back, and with it a green word going
    # grey mid-ayah.
    start = src.find("val retained = sessionStatuses[key]")
    end = src.find("newMap[key] = s", start) if start >= 0 else -1
    if start >= 0 and end > start:
        block = src[start:end]
        if re.search(r"\ba [<>=!]=?\s*lockedAyah", block):
            failures.append(
                "PracticeViewModel.kt: the retention chain branches on the "
                "word's position relative to the lock again. A terminal verdict "
                "is final at every position; the locked ayah's provisional "
                "revision is what produced the paint-and-unpaint defect.")
    return failures


def main() -> int:
    if not VM.is_file():
        print(f"FAIL: {VM} not found")
        return 1
    src = VM.read_text(encoding="utf-8")
    failures = check(src)
    if failures:
        print("FAIL")
        for f in failures:
            print("  -", f)
        return 1
    print("PASS: the session archive is cleared once per session and is "
          "untouched by loadSurah and by the paint prune")
    return 0


if __name__ == "__main__":
    sys.exit(main())