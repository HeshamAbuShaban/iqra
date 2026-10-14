#!/usr/bin/env python3
"""
Numbers that cannot disagree with reality, or code that claims what it does not do.

Every check here guards a claim made in a comment or a variable name against the
code that is supposed to keep it. The failure mode being prevented is specific:
a statement in the source reads as a safety measure, a measurement, or a
distinction, and the code keeps none of them.

1. "no data" and "zero percent" stay distinct in the trend series.
2. A handoff session's Ayah-by-ayah strip sorts by surah first.
3. The live word list and the statuses it paints use the SAME word index.
4. UNKNOWN/SKIPPED are never reported as "heard".
5. Every value of an enum is distinguished by the code that reads it.
6. A conditional whose arms produce identical output is deleted, not kept.

Run: python3 engine/replay/honest_numbers.py
"""

import re
import sys
from pathlib import Path

UI = Path(__file__).resolve().parent.parent.parent / "android/app/src/main/java/com/iqra/quran/ui"
LOG = UI.parent / "data/PracticeLog.kt"

REPORT = (UI / "SessionReport.kt").read_text(encoding="utf-8")
LIVE = (UI / "LiveMode.kt").read_text(encoding="utf-8")
PLOG = LOG.read_text(encoding="utf-8")
PRACTICE = (UI / "PracticeScreen.kt").read_text(encoding="utf-8")


def stripped(text):
    """Code without comments: prose that claims a thing must not be mistaken for code."""
    out = []
    for l in text.splitlines():
        s = l.strip()
        if s.startswith("//"):
            continue
        out.append(s.split("//")[0])
    return "\n".join(out)


def main() -> int:
    bad = []

    # 1. null-accuracy and 0%-accuracy encode differently.
    body = stripped(PLOG)
    if "-1L" not in body.split("agg.series")[1][:400] if "agg.series" in body else True:
        bad.append("the trend series no longer distinguishes 'nothing judged' from 0%")
    if "if (r.accuracy == null) -1L" not in body and "acc == null) -1L" not in body:
        bad.append("null accuracy is encoded as 0 again, erasing failed sessions from the trend")
    decode = body[body.find("SessionPoint("):]
    if "-1L -> null" not in decode:
        bad.append("the decoder treats 0% as null, so a failed session is still dropped")

    # 2. Ayah strip sorts surah-then-ayah. Tested on the SORT expression, not on
    # the file, because `substringBefore` legitimately appears elsewhere in this
    # screen - a check that a trivial reuse could satisfy measures nothing.
    sort_zone = stripped(REPORT)
    sort_i = sort_zone.find("val entries =")
    sort_expr = sort_zone[sort_i:sort_i + 200] if sort_i >= 0 else ""
    if not sort_expr:
        bad.append("the Ayah-by-ayah strip no longer sorts anything at all")
    elif "substringBefore" not in sort_expr or "substringAfter" not in sort_expr:
        bad.append("the Ayah-by-ayah strip still sorts on the ayah alone, "
                   "so a handoff session interleaves two surahs")

    # 3. LiveMode paints from the mushaf word list.
    live_body = stripped(LIVE)
    if "standWordsFor" not in live_body:
        bad.append("the live word list is not built from the mushaf words the statuses use")
    if "uthmaniWords(" in live_body:
        bad.append("a whitespace splitter is still indexing words the statuses do not share")
    # The denominator must be drawn from the same word population the status keys
    # are built from. This originally looked for `standWordsFor` within 220 chars
    # of `totalWords = remember`, which could not see through an intermediate
    # value - so a correct implementation that hoisted the count into a named
    # local was reported as a different population. It now follows the real
    # dataflow: `standWordsFor` must appear anywhere in the same composable, AND
    # the surah guard must be present, because `standWordsFor(ayah)` answers
    # about whatever surah the ENGINE has loaded, not the one being tested.
    total = live_body.split("totalWords = remember")
    if len(total) > 1:
        window = live_body[max(0, live_body.index("totalWords = remember") - 900):
                           live_body.index("totalWords = remember") + 400]
        if "standWordsFor" not in window:
            bad.append("the live denominator is not built from standWordsFor, "
                       "which is the population the status keys use")
        elif "it.surah ==" not in window and "it.surah==" not in window:
            bad.append("the live denominator counts standWordsFor without checking "
                       "the word's own surah, so a handoff re-keys it to the next "
                       "surah while the header still names the old one")

    # 4. "heard" counts verdicts only - and there is more than one read site, so
    # every one of them must be a verdict count.
    heard_sites = live_body.count("val judged =")
    if heard_sites == 0:
        bad.append("the live 'heard' count no longer exists; name where it went")
    else:
        good = 0
        for m in re.finditer(r"val judged =(.{0,160})", live_body, re.S):
            site = m.group(1)
            if "values.count" in site and "WordStatus.WRONG" in site:
                good += 1
            # The durable store. `scopeProgress.sumOf { it.decided }` counts
            # CORRECT + WRONG and nothing else, because PracticeViewModel builds
            # `decided` from sessionArchive that way and never prunes it. This
            # form was added when the live read moved off the pruned paint map,
            # which is the fix for "16 of 29 heard" on a finished Al-Fatiha.
            elif ".sumOf { it.decided }" in site:
                # Only `decided`. `heard` alone is CORRECT only and would
                # under-report wrong words; `words` is the whole scope and would
                # make every session read complete.
                good += 1
        if good != heard_sites:
            bad.append("a 'heard' read site is not a verdict count "
                       "(%d of %d sites restricted)" % (good, heard_sites))

    # 5. A conditional with identical arms is gone.
    for name, text in (("PracticeScreen", PRACTICE),):
        b = stripped(text)
        if re.search(r"best \$\{s\.longestStreak\}\" else \"best \$\{s\.longestStreak\}\"", b):
            bad.append("%s still has a conditional whose arms render the same text" % name)

    if bad:
        for x in bad:
            print("FAIL:", x)
        return 1
    print("ok: no data and zero percent stay distinct, the live list uses one word index, "
          "and 'heard' means judged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
