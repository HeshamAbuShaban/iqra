#!/usr/bin/env python3
"""
Advisories inform; they never accuse. Structural gate on the separation:

  - no advisory kind ever reaches archiveVerdict or the wrong streak;
  - the waqf downgrade writes UNKNOWN BEFORE the streak sees the word;
  - the same-surah/same-ayah junction constraint holds;
  - the in-flow cue never uses the error colour, and is suppressed in hide
    mode so a note cannot reveal which word the engine suspects;
  - the session record exposes advisories under their own key.
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
UI = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ui"

VM = (UI / "PracticeViewModel.kt").read_text(encoding="utf-8")
MAIN = (UI / "MainActivity.kt").read_text(encoding="utf-8")
ADVISORY = (UI / "Advisory.kt").read_text(encoding="utf-8")
SETTINGS = (UI / "SettingsScreen.kt").read_text(encoding="utf-8")

def main() -> int:
    bad = []

    def archive_fn():
        m = re.search(r"private fun archiveVerdict.*?\n    \}\n", VM, re.S)
        return m.group(0) if m else ""

    def addadvisory_fn():
        m = re.search(r"private fun addAdvisory.*?\n    \}\n", VM, re.S)
        return m.group(0) if m else ""

    for name, ok in [
        ("archiveVerdict never sees an Advisory", "Advisory" not in archive_fn()),
        ("addAdvisory never writes a verdict", "WordStatus" not in addadvisory_fn() and "wrongStreak" not in addadvisory_fn()),
        ("waqf downgrade before streak", VM.index("MISSED_RULING_POSSIBLE") < VM.index("wrongStreak[key] = streak")),
        ("downgrade writes UNKNOWN", "s = WordStatus.UNKNOWN\n                        addAdvisory(key, AdvisoryKind.MISSED_RULING_POSSIBLE)" in VM),
        ("junctions same surah+ayah", "n.surah == flats[i].surah && n.verse == flats[i].verse" in VM),
        ("waqf needs wrongAtWordStart", "al.wrongAtWordStart.getOrElse(i) { false }" in VM),
        ("hard mode keeps engine-fact notes", "ReaderPrefs.AdvisoryAlarm.HARD_RULES_ONLY" in VM),
        ("cue suppressed in hide mode", "!hide && ReaderPrefs.advisoryDisplay" in MAIN),
        ("session record carries advisories", '\\"advisories\\":[' in VM and '\\"kind\\":\\"' in VM),
        ("enum separate from WordStatus", "enum class AdvisoryKind" in ADVISORY and "WordStatus" not in ADVISORY),
        ("all emissions present", all(f"AdvisoryKind.{k}" in VM for k in ("NO_AUDIO_WINDOW", "LOW_EVIDENCE", "DECODER_STARVATION", "MISSED_RULING_POSSIBLE"))),
        ("settings expose advisory rows", "AdvisoryAlarm" in SETTINGS and "AdvisoryDisplay" in SETTINGS),
    ]:
        if not ok:
            bad.append(name)

    # Amber wash, never the error colour, is what a note wears.
    cue_lines = [l for l in MAIN.splitlines() if "advis" in l.lower()]
    if any("wrongColor" in l for l in cue_lines):
        bad.append("advisory lines never paint with wrongColor")

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: an advisory informs, it never accuses; waqf downgrade blocks blame; cue never red, never in hide mode")
    return 0

if __name__ == "__main__":
    sys.exit(main())
