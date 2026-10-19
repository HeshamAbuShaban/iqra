#!/usr/bin/env python3
"""Every recognition preference must change behaviour, or the control is a
lies display. This check fails when a Settings toggle is stored nowhere outside
the settings UI itself: ReaderPrefs.kt (definition) and SettingsScreen.kt
(the toggle) are where it is allowed to be. Anywhere else is a consumer.

Consumption is traced through one level of derived settings, because a strictness
choice is consumed by the coverage floor it maps to, not by name.

A second property rides here because it closes the same kind of lie: the
wrong-verdict buzz (the vibrate toggle's consumer) must be composed on every
surface where a session can record without leaving - the live screen and the
reader. A buzz that fires on one surface and stays silent on the other is the
toggle lying about half the app. This is a source-level assertion, not a
composition proof; it pins the call inside each surface composable, the same
way session_record_integrity pins the stopRecite guard, and it strips comments
so prose cannot satisfy it.
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
JAVA = HERE.parent.parent / "android/app/src/main/java"

PREFS = JAVA / "com/iqra/quran/ui/ReaderPrefs.kt"
SETTINGS = JAVA / "com/iqra/quran/ui/SettingsScreen.kt"


def reader_entries() -> dict:
    """key -> set of ReaderPrefs function names that read it directly."""
    src = PREFS.read_text(encoding="utf-8")
    # Function bodies: between "fun name(" and the next "\n    fun " or end.
    out = {}
    names = [m for m in re.finditer(r"^    fun (\w+)\(", src, re.M)]
    for i, m in enumerate(names):
        body = src[m.start(): names[i + 1].start() if i + 1 < len(names) else len(src)]
        for key in re.findall(r"get(?:Boolean|Int|String|Float)\(\"([^\"]+)\"", body):
            out.setdefault(key, set()).add(m.group(1))
    return out, src


def strip_comments(text: str) -> str:
    out = []
    for l in text.splitlines():
        s = l.strip()
        if s.startswith("//"):
            continue
        out.append(s.split("//")[0])
    return "\n".join(out)


def top_level_body(src: str, name: str) -> str:
    """A top-level fun's body, up to the next top-level declaration. One level
    of nesting is enough here: the call sits near the top of the composable."""
    m = re.search(r"^fun %s\b" % name, src, re.M)
    if not m:
        return ""
    rest = src[m.start():]
    nxt = re.search(r"\n(?=@|fun |private fun |internal fun )", rest)
    return rest if nxt is None else rest[: nxt.start()]


def main() -> int:
    readers, prefs_src = reader_entries()
    if not readers:
        print("FAIL: could not classify any ReaderPrefs keys")
        return 1

    # Intra-ReaderPrefs calls: does one pref function call another?
    derives = {}
    for m in re.finditer(r"fun (\w+)\(", prefs_src):
        pass
    # Derived functions: a ReaderPrefs function whose body mentions another
    # pref function's name.
    fn_bodies = {}
    names = [m for m in re.finditer(r"^    fun (\w+)\(", prefs_src, re.M)]
    for i, m in enumerate(names):
        fn_bodies[m.group(1)] = prefs_src[m.start(): names[i + 1].start() if i + 1 < len(names) else len(prefs_src)]
    for derived, body in fn_bodies.items():
        for other in fn_bodies:
            if other != derived and re.search(r"\b" + re.escape(other) + r"\s*\(", body):
                derives.setdefault(other, set()).add(derived)

    others = [p for p in JAVA.rglob("*.kt") if p not in (PREFS, SETTINGS)]
    bag = {p: p.read_text(encoding="utf-8") for p in others}

    bad = []
    for key, direct in readers.items():
        candidates = set(direct)
        for d in direct:
            candidates |= derives.get(d, set())
        for d in list(candidates):
            candidates |= derives.get(d, set())
        if not any(re.search(r"\b" + re.escape(fn) + r"\s*\(", text) for fn in candidates for text in bag.values()):
            bad.append(f"{key}: read only by {sorted(direct)}, used nowhere else")
    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1

    # The reader records in place (the Recite button and the ayah sheet's
    # "Practice from here"), so WrongVerdictBuzz - the vibrate toggle's
    # consumer - must be composed there as well as on the live screen.
    live = next((p for p in bag if p.name == "LiveMode.kt"), None)
    main_kt = next((p for p in bag if p.name == "MainActivity.kt"), None)
    surfaces = {}
    if live is not None:
        surfaces["LiveMode.kt"] = strip_comments(bag[live])
    if main_kt is not None:
        surfaces["ReaderScreen"] = strip_comments(top_level_body(bag[main_kt], "ReaderScreen"))
    missing = sorted(n for n, t in surfaces.items() if "WrongVerdictBuzz(vm)" not in t)
    if len(surfaces) < 2 or missing:
        print("FAIL: WrongVerdictBuzz is not composed on:", ", ".join(missing) or "its surfaces")
        return 1

    print(
        f"ok: {len(readers)} pref keys are each consumed beyond the settings UI; "
        "the vibrate buzz is composed on the live screen and the reader"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
