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