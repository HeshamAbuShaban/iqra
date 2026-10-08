#!/usr/bin/env python3
"""Every recognition preference must change behaviour, or the control is a
lies display. This check fails when a Settings toggle is stored nowhere outside
the settings UI itself: ReaderPrefs.kt (definition) and SettingsScreen.kt
(the toggle) are where it is allowed to be. Anywhere else is a consumer.

Consumption is traced through one level of derived settings, because a strictness
choice is consumed by the coverage floor it maps to, not by name.
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
    print(f"ok: {len(readers)} pref keys are each consumed beyond the settings UI")
    return 0


if __name__ == "__main__":
    sys.exit(main())
