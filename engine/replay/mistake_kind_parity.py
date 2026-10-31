#!/usr/bin/env python3
"""
A wrong verdict must be able to say what KIND of mistake it charged.

Phase 1 of the mistake-kind taxonomy (docs/STATUS.md, "What is open next"):
per WRONG word, how many of its units the emission answered with a DIFFERENT
sound (substituted) and how many it answered with nothing (never said), of the
word's own unit count. Tarteel's taxonomy names these "wrong" and "missed"
(docs/REVERSE_ENGINEERING.md §8); here they are derived from the ONE traceback
PhonemeMapper.align already runs, so the kind line and the verdict under it
cannot disagree.

What must hold:

  1. the mirror's counts are exact on deterministic cases - a substitution
     costs 1, a deletion costs 1, so a case with one query unit missing from
     two ref units has exactly one sub and one missed WHICHEVER way the
     tie-break placed them (the tie-break itself is dp_source_parity's pin);
  2. a word judged WRONG always has substituted >= 1: `bad > 0` is the rule
     that claims WRONG, and `bad` counts substituted units only. A kind line
     of "0 substituted" on a WRONG row would mean the report invented it;
  3. substituted + missed + matched == the word's own unit total, always -
     the counts are a partition, not a re-alignment;
  4. the Kotlin derives the three arrays inside align's own ref-loop (the
     deletion increment inside the `qi < 0` branch), the session captures
     them only on the WRONG path beside `wu`, clears them with the other
     per-session maps, the record writer writes them only on WRONG rows, and
     the reader parses all three;
  5. the counting comparator can actually disagree - a function that answers
     all-zeros must fail this check, or the case list proves nothing.

EXTRA (insertions) is deliberately absent: the aligner drops insertions from
per-word attribution on purpose (istiaadha, basmala, leading noise), and
inventing an attribution would be the fallback that "explains" wrong ayah
audio (AGENTS rule 10). Phase 1 reports only what the traceback proves.

Run: python3 engine/replay/mistake_kind_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import word_verdicts as wv  # noqa: E402

ROOT = HERE.parent.parent
MAPPER = ROOT / "android/app/src/main/java/com/iqra/quran/ml/PhonemeMapper.kt"
VIEWMODEL = ROOT / "android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt"
LOG = ROOT / "android/app/src/main/java/com/iqra/quran/data/PracticeLog.kt"
REPORT = ROOT / "android/app/src/main/java/com/iqra/quran/ui/SessionReport.kt"

# Symbols far apart in every canon: a substitution between them is
# unambiguous, and neither collapses into the other. No madd, no bare/vowelled
# pair - the final-vowel rule must stay out of these cases.
A, B, C, D, E, F = "ب", "ش", "ق", "ه", "ن", "ث"


def counts(query, ref, unit_word):
    """The mirror's own alignment, then the kind split."""
    matched, wrong, ref_to_query, _emit, _hits, nwords = wv.align(
        query, ref, unit_word)
    subs, missed, total = wv.mistake_counts(
        matched, wrong, ref_to_query, unit_word, nwords)
    statuses = wv.statuses_from(matched, wrong, unit_word, nwords)
    return subs, missed, total, statuses, matched


def main() -> int:
    bad = []

    # 1. Deterministic cases. The counts are exact even where the traceback
    #    has ties, because the COST structure fixes how many subs and misses
    #    exist, not only where they sit.
    cases = [
        # (name, query, ref, unit_word, expected subs, expected missed)
        ("clean word",
         [A, B, C], [A, B, C], [0, 0, 0], [0], [0]),
        # one ref unit with no query unit to answer it: exactly one miss
        ("one unit never said",
         [A, C], [A, B, C], [0, 0, 0], [0], [1]),
        # one query unit differing at one position: exactly one sub
        ("one unit said differently",
         [A, B, D], [A, B, C], [0, 0, 0], [1], [0]),
        # two ref units, one query unit, nothing matches: every 2-cost path is
        # one substitution plus one deletion, WHICHEVER way the tie-break
        # places them - so the COUNTS are exact even where the path is not
        ("one sub and one miss, tied",
         [D], [B, C], [0, 0], [1], [1]),
        # the word said with every unit changed - no query symbol occurs in
        # the reference at all, so no del/match/ins reshuffle can beat three
        # substitutions: the split counts every unit. At zero heard coverage
        # the verdict itself is UNKNOWN (the verdict rule needs most of the
        # word heard); the kind split is verdict-independent, which is its point
        ("every unit substituted",
         [C, D, C], [A, B, A], [0, 0, 0], [3], [0]),
        # 4 of 5 units heard, the fifth said differently: this IS a WRONG
        # verdict (ok >= tot * WRONG_MIN_HEARD, bad > 0), so the invariant
        # "WRONG implies substituted >= 1" is exercised positively
        ("wrong verdict, one substituted unit",
         [A, B, C, D, F], [A, B, C, D, E], [0, 0, 0, 0, 0], [1], [0]),
        # whole word absent: all units missed, verdict SKIPPED, not WRONG
        ("whole word absent",
         [], [A, B], [0, 0], [0], [2]),
        # two words: the sub must land on its own word's count only
        ("two words, sub on the first",
         [A, D, B, C], [A, B, B, C], [0, 0, 1, 1], [1, 0], [0, 0]),
        # insertion at the end is an EXTRA: it enters neither count
        ("trailing insertion is neither sub nor miss",
         [A, B, C, D], [A, B, C], [0, 0, 0], [0], [0]),
    ]
    for name, query, ref, uw, want_subs, want_missed in cases:
        subs, missed, total, statuses, matched = counts(query, ref, uw)
        nwords = (max(uw) + 1) if uw else 0
        if subs != want_subs:
            bad.append("%s: substituted %s, expected %s" % (name, subs, want_subs))
        if missed != want_missed:
            bad.append("%s: missed %s, expected %s" % (name, missed, want_missed))
        for w in range(nwords):
            if subs[w] + missed[w] + sum(1 for k, o in enumerate(uw)
                                         if o == w and matched[k]) != total[w]:
                bad.append("%s: word %d is not a partition of its %d units"
                           % (name, w, total[w]))
        # 2. every WRONG word has substituted >= 1
        for wi in range(nwords):
            if statuses.get(wi) == "WRONG" and subs[wi] < 1:
                bad.append("%s: word %d is WRONG with substituted == 0; "
                           "`bad > 0` is the rule that claims WRONG" % (name, wi))

    # The whole-word-absent case must be SKIPPED - missed alone is never a
    # verdict (phase 1 is report data, and the verdict rule never reads it).
    _s, _m, _t, st_all_missed, _mch = counts([], [A, B], [0, 0])
    if st_all_missed.get(0) != "SKIPPED":
        bad.append("a word whose every unit was missed must stay SKIPPED, got %s"
                   % st_all_missed.get(0))

    # 5. the comparator can disagree: an all-zeros answer must fail the case
    #    list above, or the list discriminates nothing.
    zeros_failures = 0
    for name, query, ref, uw, want_subs, want_missed in cases:
        subs, missed, total, statuses, matched = counts(query, ref, uw)
        if [0] * len(subs) != subs or [0] * len(missed) != missed:
            zeros_failures += 1
    if zeros_failures < 3:
        bad.append("an all-zeros counter survives %d of %d cases; the case "
                   "list cannot catch a broken counter"
                   % (len(cases) - zeros_failures, len(cases)))

    # 4. the Kotlin derives the arrays inside align's own ref-loop...
    kts = MAPPER.read_text(encoding="utf-8")
    loop = re.search(
        r"for \(j in ref\.indices\) \{(.*?)\n        \}", kts, re.S)
    if loop is None:
        bad.append("align's ref-loop is gone or reshaped; the kind arrays can "
                   "no longer be read as derived from the one traceback")
    else:
        body = loop.group(1)
        if "wordUnitsTotal[wordOf[j]]++" not in body:
            bad.append("the word unit total is not counted in align's ref-loop")
        if not re.search(r"if \(qi < 0\) \{\s*wordMissed\[wordOf\[j\]\]\+\+", body):
            bad.append("the miss increment is not inside the deletion branch "
                       "(qi < 0); a unit answered by an emission must not count "
                       "as never-said")
        if not re.search(r"wrong\[j\] = true\s*wordSubstituted\[wordOf\[j\]\]\+\+", body):
            bad.append("the substitution increment is not on the differing "
                       "branch; a matched unit must not count as substituted")

    # ...the session captures them on the WRONG path, beside `wu`...
    vm = VIEWMODEL.read_text(encoding="utf-8")
    capture = re.search(
        r"if \(s == WordStatus\.WRONG\) \{\s*"
        r"al\.wrongStartFrac\.getOrNull\(i\)\?\.let \{ wrongStartOf\[key\] = it \}\s*"
        r"al\.wordSubstituted", vm)
    if capture is None:
        bad.append("the kind capture no longer sits on the WRONG path beside "
                   "the `wu` capture; the record's kind split would drift from "
                   "the frame that charged the word")
    if "mistakeShapeOf.clear()" not in vm:
        bad.append("mistakeShapeOf is never cleared; the next session's report "
                   "would inherit this one's kind split")
    # ...the record writer writes the fields only on WRONG rows...
    writer = re.search(
        r"val shape = if \(v == WordStatus\.WRONG\) mistakeShapeOf\[k\] else null\s*"
        r"if \(shape != null\) \{", vm)
    if writer is None:
        bad.append("the record writer does not gate the kind fields on a WRONG "
                   "row; CORRECT rows must not grow them")
    for field in ("wsub", "wmiss", "wunits"):
        if not re.search(r'\\"%s\\":' % field, vm):
            bad.append("the record writer does not write %s" % field)
    # ...and the reader parses all three.
    log = LOG.read_text(encoding="utf-8")
    for field in ("wsub", "wmiss", "wunits"):
        if 'w.has("%s")' % field not in log:
            bad.append("PracticeLog does not parse %s; the report could never "
                       "see the split the record carries" % field)
    # The report renders the split, or the record writes it for nothing - and
    # it refuses a zero-substitution split, which cannot belong to a WRONG
    # verdict: printing it would put a contradiction under the row's own red.
    rep = REPORT.read_text(encoding="utf-8")
    for name in ("report_mistake_shape", "report_mistake_shape_clean"):
        if "R.string.%s" % name not in rep:
            bad.append("SessionReport no longer renders %s" % name)
    # The guard reads the split into a local that REFUSES (negative fallback,
    # not a render-through default) and only renders past zero.
    guard = re.search(
        r"val sub = row\.wsub \?: -1\s*val miss = row\.wmiss \?: 0\s*if \(sub > 0\) \{",
        rep)
    if guard is None:
        bad.append("SessionReport does not refuse a zero-substitution split; "
                   "a WRONG row cannot carry one, and rendering it would "
                   "contradict the verdict on the same row")

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: the kind split is exact, partitioned, WRONG-gated, and carried "
          "from alignment to record to report")
    return 0


if __name__ == "__main__":
    sys.exit(main())
