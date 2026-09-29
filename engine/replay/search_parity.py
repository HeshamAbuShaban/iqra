#!/usr/bin/env python3
"""Parity test for AyahSearch normalisation.

The dual-reading normalisation was originally validated in ad-hoc Python and then
ported to Kotlin. The port silently dropped one table entry (FOLDS[0x0670]), which
is exactly the class of drift this script exists to prevent: the tables are parsed
straight out of the Kotlin source, so a change in AyahSearch.kt is picked up here
and a missing entry fails the test.

Run:  engine/.venv-replay/bin/python engine/replay/search_parity.py
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KT = ROOT / "android/app/src/main/java/com/iqra/quran/data/AyahSearch.kt"
QURAN = ROOT / "android/app/src/main/assets/quran.json"

FOLD, DROP = 0, 1


def parse_tables(src: str):
    """Rebuild STRIP/FOLDS from the Kotlin `init` block, including its range loops."""
    strip = {c: 1 for c in range(0x10000)}
    folds = {c: 0 for c in range(0x10000)}

    for m in re.finditer(
        r"for \(c in (0x[0-9A-Fa-f]+)\.\.(0x[0-9A-Fa-f]+)\) (STRIP|FOLDS)\[c\] = (0x[0-9A-Fa-f]+|\d+)",
        src,
    ):
        lo, hi, arr, val = int(m[1], 16), int(m[2], 16), m[3], int(m[4], 16)
        for c in range(lo, hi + 1):
            (strip if arr == "STRIP" else folds)[c] = val

    for m in re.finditer(
        r"(STRIP|FOLDS)\[(0x[0-9A-Fa-f]+)\] = (0x[0-9A-Fa-f]+|\d+)", src
    ):
        arr, idx, val = m[1], int(m[2], 16), int(m[3], 16)
        (strip if arr == "STRIP" else folds)[idx] = val

    return strip, folds


def reduce(s: str, reading: int, strip, folds) -> str:
    """Faithful port of AyahSearch.reduce()."""
    out = []
    for ch in s:
        c = ord(ch)
        if c < 0x10000:
            if reading == DROP and c == 0x0670:
                continue
            if strip[c] == 0:
                continue
            if folds[c] != 0:
                c = folds[c]
        if c in (0x20, 0xA0, 0x2D):
            continue
        out.append(chr(c))
    return "".join(out)


def build_index(verses, strip, folds):
    """Mirror AyahSearch.build(): FOLD skeleton always, DROP skeleton when different."""
    sk, ref = [], []
    for v in verses:
        text = v.get("text_uthmani") or v.get("text_clean") or ""
        a = reduce(text, FOLD, strip, folds)
        b = reduce(text, DROP, strip, folds)
        sk.append(a)
        ref.append((v["surah"], v["ayah"]))
        if a != b:
            sk.append(b)
            ref.append((v["surah"], v["ayah"]))
    return sk, ref


def main() -> int:
    src = KT.read_text(encoding="utf-8")
    strip, folds = parse_tables(src)

    failures = []

    # 1. The table entry whose absence made plain-alef queries miss 4,421 ayat.
    if folds.get(0x0670) != 0x0627:
        failures.append(
            f"FOLDS[0x0670] missing or wrong (={folds.get(0x0670):#x}); "
            "superscript alef must fold to U+0627 so plain-alef queries match"
        )

    verses = json.loads(QURAN.read_text(encoding="utf-8"))
    sk, ref = build_index(verses, strip, folds)

    def matches(query: str) -> set:
        n = reduce(query, FOLD, strip, folds)
        return {ref[i] for i, s in enumerate(sk) if n in s}

    # 2. U+0670 regression: a plain-alef query must reach the Uthmani spelling.
    #    Fixtures are real ayat where the Uthmani form carries a superscript alef
    #    that a user would never type.
    for q, want in [
        ("العالمين", (1, 2)),      # ٱلْعَٰلَمِينَ
        ("السماوات", (2, 33)),     # ٱلسَّمَٰوَٰتِ  (two superscript alefs)
        ("غشاوة", (2, 7)),        # غِشَٰوَةٌ
        ("الرحمن", (1, 3)),       # ٱلرَّحْمَٰنِ
    ]:
        got = matches(q)
        if want not in got:
            failures.append(f"query {q!r} did not reach {want[0]}:{want[1]} (got {len(got)} hits)")

    # 3. No skeleton may retain U+0670 in either reading. This covers every one of
    #    the 4,421 ayat that carry a superscript alef, not just the fixtures above.
    leaked = 0
    for s in sk:
        if "\u0670" in s:
            leaked += 1
    if leaked:
        failures.append(f"{leaked} skeleton entries still contain U+0670 (superscript alef)")

    # 4. Diacritics/tatweel/ZWNJ must never change meaning.
    base = reduce("بِسْمِ ٱللَّهِ", FOLD, strip, folds)
    for variant in ["بسْمِ اللَّه", "بسم الله", "بسم الله", "بِسْمِ ٱللَّهِ"]:
        if reduce(variant, FOLD, strip, folds) != base:
            failures.append(f"diacritic/tatweel variant diverged: {variant!r}")

    # 5. The drop reading must still differ where it is meaningful, i.e. U+0670
    #    is not silently folded away in both variants (that would make DROP a no-op).
    f = reduce("ٱلرَّحْمَٰنِ", FOLD, strip, folds)
    d = reduce("ٱلرَّحْمَٰنِ", DROP, strip, folds)
    if f == d:
        failures.append("FOLD and DROP readings are identical; the dual index is inert")

    # 5. Report reach, since a broken table still returns *some* hits.
    hits = len(matches("العالمين"))
    print(f"verses indexed      : {len(verses)}")
    print(f"skeleton entries    : {len(sk)}")
    print(f"'العالمين' hits     : {hits}")

    if failures:
        print("\nFAIL")
        for f_ in failures:
            print(f"  - {f_}")
        return 1
    print("\nPASS: Kotlin search tables match the validated dual-reading behaviour")
    return 0


if __name__ == "__main__":
    sys.exit(main())
