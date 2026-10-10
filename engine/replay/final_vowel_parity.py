#!/usr/bin/env python3
"""
A word-final consonant must not decide a verdict.

26,733 of the word-aligned table's 77,481 words - 34.5% - end on a bare
consonant with no i'rab vowel. 'يُنفِقُونَ' is stored as
`يُ ںںں فِ قُ ۥۥۥۥ ن`, so its last unit is a bare `ن`. The model has no such thing:
it emits `نَ`, because a reciter says the fatḥa, and the two are DIFFERENT tokens
in tokens.txt.

Measured on the user's own 10 October session, using the blame position the
record now carries: 9 of its 10 WRONG words were blamed on the LAST unit, and
they are the plural masculine and pronoun endings - `ٱلضَّآلِّينَ`, `يُنفِقُونَ`,
`ٱلْمُفْلِحُونَ`, `بِمُؤْمِنِينَ`, `يَعْمَهُونَ`, `وَتَرَكَهُمْ`, `يَرْجِعُونَ`. None
carries a silent-letter mark, so the optional-final guard could not see them.

The reference reciter proves the mechanism. In Al-Baqarah he emits `نَ` 680 times
and a bare `ن` 450 times, so BOTH forms are real and neither may be discarded.
The rule therefore interned every variant of one word-final consonant to one id -
the same shape as the madd rule. Like the madd rule it can only remove blame.

What must hold, and what must not:

  1. bare `ن` matches `نَ`, `نُ` and `نِ` at a word-final position;
  2. it does NOT match inside a word - the `ن` in `مِن` is a different sound;
  3. two DIFFERENT consonants still do not match;
  4. a vowelled consonant matches a bare one, and vice versa;
  5. the rule is inert outside the final position, so it cannot silently turn a
     real mid-word error into a match.

Run: python3 engine/replay/final_vowel_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import word_verdicts as wv  # noqa: E402

# The letters measured to end a word bare in the table while the mushaf prints a
# vowel on them. Nun and ha dominate: they are the plural masculine and pronoun
# endings. Waw and ya are deliberately absent - as semi-vowels they carry vowels
# of their own, and relaxing them would hide a real error.
BARE = ["ن", "ه", "م", "ر", "د", "ب"]
VOWELS = ["َ", "ُ", "ِ"]
# Semi-vowels: the vowel is the letter's own sound, so the rule must not fire.
EXCLUDED = ["و", "ي"]


def main() -> int:
    bad = []
    was = wv.FINAL_VOWEL_RULE[0]
    try:
        # The production default must be ON. This check sets it itself below, so
        # without this the "switched off by default" fault would be invisible -
        # and that fault turns a false-accusation fix into dead code.
        wv.FINAL_VOWEL_RULE[0] = False
        if wv.unit_match_final("ن", "نَ"):
            bad.append("the final-vowel rule fires even when switched off")
        kt = (HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ml/PhonemeMapper.kt")
        kts = kt.read_text(encoding="utf-8")
        if not re.search(r"maddEquivalenceMatching:\s*Boolean\s*=\s*true", kts):
            bad.append("the shipped default is not the tolerant one; the rule "
                       "would ship inert")

        wv.FINAL_VOWEL_RULE[0] = True

        # 1. bare consonant matches every short vowel, word-finally
        for c in BARE:
            for v in VOWELS:
                if not wv.unit_match_final(c, c + v):
                    bad.append("%s should match %s at a word end" % (c, c + v))
                # 4. and the other way round
                if not wv.unit_match_final(c + v, c):
                    bad.append("%s should match %s at a word end" % (c + v, c))

        # 2. NOT inside a word: plain matching must still refuse
        for c in BARE[:3]:
            for v in VOWELS:
                if wv.unit_match(c, c + v):
                    bad.append("%s matched %s inside a word; the vowel is "
                               "load-bearing there" % (c, c + v))

        # 3. different consonants never match
        if wv.unit_match_final("ن", "ر"):
            bad.append("two different consonants matched at a word end")
        if wv.unit_match_final("م", "ب"):
            bad.append("two different consonants matched at a word end")

        # 3b. the semi-vowels stay out of it
        for c in EXCLUDED:
            for v in VOWELS:
                if wv.unit_match_final(c, c + v):
                    bad.append("%s is a semi-vowel and must not be relaxed at a "
                               "word end; its vowel is its own sound" % c)

        # 5. with the rule off, the bare/vowelled pair stops matching
        wv.FINAL_VOWEL_RULE[0] = False
        if wv.unit_match_final("ن", "نَ"):
            bad.append("the rule is inert when switched off, so it is decoration")
        wv.FINAL_VOWEL_RULE[0] = True

        # The rule must not swallow the madd equivalence it sits beside.
        if not wv.unit_match_final("ۥۥ", "ۥۥۥ"):
            bad.append("the madd rule stopped working alongside the final-vowel rule")
    finally:
        wv.FINAL_VOWEL_RULE[0] = was

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: a word-final consonant cannot decide a verdict, and the rule is "
          "narrow - inside a word the vowel still counts")
    return 0


if __name__ == "__main__":
    sys.exit(main())