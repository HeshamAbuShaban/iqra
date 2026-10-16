#!/usr/bin/env python3
"""
A page opens a new surah only when it starts one, not when it contains one.
import re

`onUserPageTurn` used `surahsOnPage.any { it > activeSurah }` to decide whether
a swipe was a surah change. 51 of the 604 mushaf pages carry two surahs - page
106 holds 4:176 and 5:1, page 293 holds 17:105 and 18:1 - so a reciter
finishing surah 4 swipes forward onto a page that CONTINUES surah 4, and the
app arms a handoff to 5:1, skipping the rest of surah 4. The user reported both
sides of it: "jumping unwanted" and "glitching in page swiping."

Measured over a full forward and backward walk of Al-Baqarah, the loose test
produces zero false arms: no mid-surah page ever holds a later surah. The defect
is confined to those 51 boundary pages, which is why it looked like corruption
rather than a rule.

Run: python3 engine/replay/page_turn_boundary.py
"""

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VM = HERE.parent.parent / "android" / "app" / "src" / "main" / "java" / "com" / "iqra" / "quran" / "ui" / "PracticeViewModel.kt"
MUSHAF = HERE.parent.parent / "android" / "app" / "src" / "main" / "assets" / "mushaf.json"


def main() -> int:
    src = VM.read_text(encoding="utf-8")
    bad = []

    if not re.search(r"val opensNewSurah = surahsOnPage\.isNotEmpty\(\) && currentSurah !in surahsOnPage", src):
        bad.append("onUserPageTurn no longer tests whether the page lacks the "
                   "current surah; the 51 two-surah pages will arm a false handoff")

    if re.search(r"opensNewSurah\s*=\s*surahsOnPage\.any \{\s*it\s*>\s*activeSurah\s*\}", src):
        bad.append("the loose `any { it > activeSurah }` test is back")

    # The invariant, evaluated on the real layout with BOTH tests, so the
    # failure of the loose rule is measured rather than asserted.
    mushaf = json.load(open(MUSHAF, encoding="utf-8"))
    false_arms_loose = 0
    two_surah_pages = 0
    first = None
    for page in mushaf:
        surahs = set()
        for line in page["lines"]:
            if line.get("type") != "text":
                continue
            for w in line["words"]:
                surahs.add(int(w["location"].split(":")[0]))
        if not surahs:
            continue
        if len(surahs) > 1:
            two_surah_pages += 1
        for s in surahs:                      # a reciter currently in surah s
            opens_loose = any(x > s for x in surahs)
            opens_narrow = s not in surahs
            if opens_loose and not opens_narrow:
                # The page contains surah s, so a reciter in s is mid-surah,
                # yet the loose rule told them this page opens a new surah.
                false_arms_loose += 1
                if first is None:
                    first = (page["page"], s, sorted(surahs))
    print("note: %d two-surah pages; the loose rule falsely arms a handoff in "
          "%d cases (first: %s)" % (two_surah_pages, false_arms_loose, first))
    if false_arms_loose == 0:
        bad.append("the loose test measured as safe, so this check guards "
                   "nothing; delete it or fix its rule")

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: a page opens a new surah only when it starts one")
    return 0


if __name__ == "__main__":
    sys.exit(main())

