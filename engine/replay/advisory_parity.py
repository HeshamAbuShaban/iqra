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

    def end_session_fn():
        m = re.search(r"private fun endSession.*?\n    \}\n", VM, re.S)
        if not m:
            return ""
        # Comments explain why the call is absent; only code counts.
        code = []
        for l in m.group(0).splitlines():
            s = l.strip()
            if s.startswith("//"):
                continue
            code.append(s.split("//")[0])
        return "\n".join(code)

    def block_after(text: str, needle: str) -> str:
        """Source of the `{ ... }` block that opens after `needle`.

        Brace-counted rather than regex-matched, because the property that
        matters is what happens INSIDE the guard: a guard that logs an advisory
        and forgets to remove the blame is exactly the false accuser this whole
        channel exists to prevent.
        """
        i = text.find(needle)
        if i < 0:
            return ""
        # The guard's body opens at the `) {` that closes the IF CONDITION,
        # which is the first `)\s*{` at or after the end of the condition's
        # last line. Anchoring on `) {` anywhere would land inside the
        # `getOrElse(i) { false }` trailing lambda instead.
        m = re.search(r"\)\s*\{\s*\n", text[i:])
        if not m:
            return ""
        start = i + m.end() - 2  # the brace itself
        depth = 0
        for j in range(start, len(text)):
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    return text[start : j + 1]
        return ""

    def demotes_to_unknown(block: str) -> bool:
        if not block:
            return False
        code = [l.strip() for l in block.splitlines() if not l.strip().startswith("//")]
        body = "\n".join(code)
        return "s = WordStatus.UNKNOWN" in body and "s = WordStatus.WRONG" not in body

    waqf_block = block_after(VM, "isWaqfJunction(key) &&")
    final_block = block_after(VM, "hasOptionalFinal(key) &&")

    for name, ok in [
        ("archiveVerdict never sees an Advisory", "Advisory" not in archive_fn()),
        ("addAdvisory never writes a verdict", "WordStatus" not in addadvisory_fn() and "wrongStreak" not in addadvisory_fn()),
        ("waqf downgrade before streak", VM.index("MISSED_RULING_POSSIBLE") < VM.index("wrongStreak[key] = streak")),
        ("waqf downgrade writes UNKNOWN", "s = WordStatus.UNKNOWN\n                        addAdvisory(key, AdvisoryKind.MISSED_RULING_POSSIBLE)" in VM),
        ("waqf guard demotes the verdict", demotes_to_unknown(waqf_block)),
        ("optional-final guard demotes the verdict", demotes_to_unknown(final_block)),
        ("junctions same surah+ayah", "n.surah == flats[i].surah && n.verse == flats[i].verse" in VM),
        ("waqf needs wrongAtWordStart", "al.wrongAtWordStart.getOrElse(i) { false }" in VM),
        ("optional-final needs wrongAtWordEnd", "al.wrongAtWordEnd.getOrElse(i) { false }" in VM),
        ("optional-final guard is unconditional", "hasOptionalFinal(key) &&" in VM),
        ("optional-final emits its own kind", "AdvisoryKind.UNMODELLED_FINAL" in VM),
        ("frame-shaped advisories count once per word", "ONCE_PER_WORD" in ADVISORY and "shouldCount" in ADVISORY),
        ("hard mode keeps engine-fact notes", "ReaderPrefs.AdvisoryAlarm.HARD_RULES_ONLY" in VM),
        ("cue suppressed in hide mode", "!hide && ReaderPrefs.advisoryDisplay" in MAIN),
        ("session record carries advisories", '\\"advisories\\":[' in VM and '\\"kind\\":\\"' in VM),
        ("enum separate from WordStatus", "enum class AdvisoryKind" in ADVISORY and "WordStatus" not in ADVISORY),
        ("all emissions present", all(f"AdvisoryKind.{k}" in VM for k in ("NO_AUDIO_WINDOW", "LOW_EVIDENCE", "DECODER_STARVATION", "MISSED_RULING_POSSIBLE"))),
        ("settings expose advisory rows", "AdvisoryAlarm" in SETTINGS and "AdvisoryDisplay" in SETTINGS),
        # A session record that browsed its way to zero. endSession used to call
        # resetSessionCounters(), and endSession also runs for every page jump
        # (`preparing-cancelled`, ran=no), so navigating after a recitation
        # wiped the counters of the record still being written while the archive
        # survived: 322 CORRECT words reported alongside 0 judged and 0 moves.
        ("endSession never resets the counters", "resetSessionCounters()" not in end_session_fn()),
        # The page-turn follow moves the LOCK on a navigation signal while the
        # reciter is silent. It must never archive a verdict for the ayah it
        # moved onto: an unrehearsed ayah is not a mistake, and a follow that
        # credited one would invent a false accusation out of a swipe.
        ("page-turn follow credits no verdict",
         "archiveVerdict" not in (re.search(r"private fun followArmedIntentIfIdle.*?\n    \}", VM, re.S).group(0)
                                  if re.search(r"private fun followArmedIntentIfIdle.*?\n    \}", VM, re.S) else "")),
        ("page-turn follow only moves to ayah 1",
         "lockedAyah = 1" in (re.search(r"private fun followArmedIntentIfIdle.*?\n    \}", VM, re.S).group(0)
                              if re.search(r"private fun followArmedIntentIfIdle.*?\n    \}", VM, re.S) else "")),
        # A zero or tiny idle window would make the follow fire on every quiet
        # poll, which is the same class of bug as a gate that can never pass -
        # in the other direction, a safety measure that fires when nothing is
        # wrong.
        ("page-turn follow waits a real silence",
         re.search(r"INTENT_FOLLOW_IDLE_MS\s*=\s*([0-9_]+)L", VM) is not None
         and int(re.search(r"INTENT_FOLLOW_IDLE_MS\s*=\s*([0-9_]+)L", VM).group(1).replace("_", "")) >= 3000),
        ("page-turn follow is only called when silent",
         "followArmedIntentIfIdle()" in VM and
         VM.index("followArmedIntentIfIdle()\n                    _gateReason.value = \"silence\"") > 0),
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
