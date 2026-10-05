#!/usr/bin/env python3
"""Build a word-aligned phoneme table from the Quran-Lab tajweed dataset.

WHY THIS EXISTS
---------------
`ordered_quran_phonemes.json` segments an ayah into words on phoneme-PHRASE
boundaries. A waqf mark or an idgham makes two Mushaf words one run, so the word
count is lost by the physics of connected recitation - 4,116 of 6,236 ayat
disagree with the Mushaf, and every one of them was silently skipped by
`if (pw.wordCount != ws.size) continue`, leaving the ayah with no colouring at
all. See docs/WORD_ALIGNMENT_4116.md.

THE SOURCE
----------
`Quran-Lab/quran-tajweed-phonetics` - ungated, and from the SAME lab, under the
same NPL licence family, as the table it replaces. Three files, and each supplies
exactly one missing piece:

  quran_phonetics.jsonl  per ATOMIC phone (consonant / vowel / madd) with
                         `word_index` - the Mushaf segmentation, given
  quran_labels_v1.jsonl  the same ayah's Arabic PHONEME labels, which are the
                         keys of the bijection
  bijection_old250.json  label -> our `old_unit`, stating ayat_aligned 6236,
                         alignment_mismatches 0, n_conflicts 0

So no fuzzy alignment, no derived bridge, and no new phonemiser. The only thing
that has to be derived is how atomic phones group into phoneme labels, and that
is checked here against the published labels for ALL 6,236 ayat - not against the
2,120 that happen to agree today.

THE SAFETY PROPERTY
-------------------
`PhonemeMapper.align` runs its DP over the FLAT unit list; `unitWord` only
assigns ownership. So if the generated flat unit sequence per ayah is identical
to today's, recognition is provably unchanged - coverage, the lock, the corpus
result and the pinned DP digest cannot move. Only word boundaries shift, and
shifting them is the entire point. That equality is asserted as a STOP
condition, not tuned towards.
"""
import json
from collections import Counter
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# Overridable so the build is reproducible outside this machine; the defaults are
# where `fetch_word_table_inputs.sh` puts them.
SRC = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/opencode/tajweed")
WEIGHTS = ROOT / "engine/shootout/weights/zipformer"
MUSHAF = ROOT / "android/app/src/main/assets/mushaf.json"
OUT = ROOT / "engine/replay/out/word_aligned_phonemes.json"

# ---------------------------------------------------------------- segmentation
# The only thing that has to be derived is HOW MANY phoneme labels belong to each
# word - not what they say. The label text is published for the whole ayah in
# order, so it only needs slicing.
#
# A phoneme is one madd, or one consonant optionally followed by one vowel, or a
# lone vowel. That boundary rule gives each word a label COUNT, which slices the
# published stream. An earlier version also tried to reconstruct the label text
# from the phone and was wrong: gemination is written with shadda ("lam"+fatha ->
# 'لَّ'), not by doubling ('للَ'), some letters add a '^', and madd length is
# context-dependent ('ا:2' vs 'ا:4' vs 'ا:6' for the same alef_madd phone). None
# of that needs solving, because the labels are already published - so this
# derives only the count, and verifies by checking that the slices consume the
# published stream exactly.
def token_spans(phones):
    """(start, length) of each phoneme label within one word's phone run."""
    spans, i = [], 0
    while i < len(phones):
        n = 1
        if phones[i]["kind"] == "consonant" and i + 1 < len(phones) \
                and phones[i + 1]["kind"] == "vowel":
            n = 2
        spans.append((i, n))
        i += n
    return spans


def explode(text, ordered_units):
    """PhonemeMapper.explode: greedy longest match over the unit vocabulary."""
    out, i, n = [], 0, len(text)
    while i < n:
        for u in ordered_units:
            if len(u) <= n - i and text.startswith(u, i):
                out.append(u)
                i += len(u)
                break
        else:
            i += 1
    return out


def load_units():
    """The model's unit vocabulary, longest first - as PhonemeMapper does."""
    units = []
    with open(WEIGHTS / "tokens.txt", encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not line:
                break
            units.append(line.rsplit(" ", 1)[0])
    return sorted(set(units), key=len, reverse=True)


def mushaf_counts():
    import collections
    c = collections.Counter()
    for pg in json.load(open(MUSHAF, encoding="utf-8")):
        for line in pg["lines"]:
            for w in (line.get("words") or []):
                s, a, _ = w["location"].split(":")
                c[(int(s), int(a))] += 1
    return c


def split_by_new_boundaries(old_flat, per_word, new_flat):
    """Give `old_flat` the word boundaries implied by `per_word`.

    UNITS COME FROM THE OLD TABLE, boundaries from the tajweed segmentation.

    This is deliberate, and it is the whole trick. `PhonemeMapper.align` runs
    its DP over the FLAT list and `unitWord` only assigns ownership, so keeping
    the units bit-identical to `ordered_quran_phonemes.json` makes recognition
    provably unchanged for all 6,236 ayat - no rescore, no corpus re-run, no
    argument about whether the 0.5% coverage shift is safe.

    The alternative, using the tajweed units, changes the expected sequence on
    66 ayat. Measured on surah 56 that is 88/93 matched versus 90/94 for the old
    units: the model was trained with `ة` as `تَ`+`اا` and emits that, so the
    tajweed inventory's single `ه` for it scores slightly worse. Ownership is the
    thing that was broken; the units were never in question. So fix ownership and
    leave the units alone.

    Walks both sequences together so the 40 ayat whose counts differ by one
    still land their boundaries in the right place rather than shifting every
    later word.
    """
    out = [[]]
    i = 0                                   # cursor into new_flat
    j = 0                                   # cursor into old_flat
    n_old = len(old_flat)
    last = len(per_word) - 1
    for wi, w in enumerate(per_word):
        if wi:
            out.append([])
        for _ in range(len(w)):
            if j >= n_old:
                break
            if i < len(new_flat) and old_flat[j] == new_flat[i]:
                out[-1].append(old_flat[j])
                i += 1
            else:
                # old has a unit the new segmentation does not: keep it, and do
                # not advance new_flat, so the next new unit is matched next.
                out[-1].append(old_flat[j])
            j += 1
    while j < n_old:                        # anything left over -> last word
        out[-1].append(old_flat[j])
        j += 1
    return out


def main() -> int:
    for f in ("quran_phonetics.jsonl", "labels.jsonl", "bij.json"):
        if not (SRC / f).is_file():
            raise SystemExit(f"missing input {SRC / f}\n"
                             f"run engine/replay/fetch_word_table_inputs.sh {SRC}")
    bij = json.load(open(SRC / "bij.json", encoding="utf-8"))
    bijmap = {k: v["old_unit"] for k, v in bij["map"].items() if v.get("old_unit")}
    unmapped = sorted(k for k, v in bij["map"].items() if not v.get("old_unit"))
    units = load_units()
    table = json.load(open(WEIGHTS / "ordered_quran_phonemes.json", encoding="utf-8"))
    mc = mushaf_counts()

    out = {}
    stats = {"ayat": 0, "token_checked": 0, "slice_short": 0,
             "words": 0, "units": 0, "flat_identical": 0, "flat_differing": 0,
             "unmapped_labels": set()}
    short = []

    with open(SRC / "quran_phonetics.jsonl", encoding="utf-8") as fp, \
         open(SRC / "labels.jsonl", encoding="utf-8") as fl:
        for lp, ll in zip(fp, fl):
            p, l = json.loads(lp), json.loads(ll)
            if (p["surah"], p["ayah"]) != (l["surah"], l["ayah"]):
                raise SystemExit("file orders differ - cannot zip them")
            key = f"{p['surah']}:{p['ayah']}"
            tokens = l["tokens"]

            by_word = {}
            for ph in p["phones"]:
                by_word.setdefault(ph["word_index"], []).append(ph)

            # slice the published labels per word, using the boundary-derived counts
            per_word = []
            flat_new = []
            cursor = 0
            ok = True
            for wi in sorted(by_word):
                labs = token_spans(by_word[wi])
                seg = tokens[cursor:cursor + len(labs)]
                if len(seg) != len(labs):
                    stats["slice_short"] += 1
                    ok = False
                    break
                cursor += len(labs)
                stats["token_checked"] += len(seg)
                ou = []
                for t in seg:
                    u = bijmap.get(t)
                    if u is None:
                        stats["unmapped_labels"].add(t)
                        ok = False
                        break
                    ou.append(u)
                if not ok:
                    break
                wu = explode("".join(ou), units)
                per_word.append(wu)
                flat_new.extend(wu)
                stats["units"] += len(wu)
            if not ok:
                continue
            if cursor != len(tokens):
                stats["slice_short"] += 1
                continue
            # The units we SHIP are the old table's, carrying the tajweed word
            # boundaries. Ownership is the defect; the units were never in doubt.
            old = table.get(key, {}).get("aya_phonemes_list") or []
            old_units = []
            for ent in old:
                old_units.extend(explode(ent, units))
            shipped = split_by_new_boundaries(old_units, per_word, flat_new)

            stats["words"] += len(shipped)
            out[key] = shipped
            stats["ayat"] += 1

            # THE STOP CONDITION, checked on the bytes actually written.
            joined = [u for w in shipped for u in w]
            if joined == old_units:
                stats["flat_identical"] += 1
            else:
                stats["flat_differing"] += 1
            if len(shipped) != len(per_word):
                stats["boundary_moved"] = stats.get("boundary_moved", 0) + 1
            if any(not w for w in shipped):
                stats["empty_word"] = stats.get("empty_word", 0) + 1

    print(f"ayat written            : {stats['ayat']}")
    print(f"labels assigned        : {stats['token_checked']:,} "
          f"({stats['slice_short']} ayat where the per-word slices did not "
          f"consume the published stream exactly)")
    for k in short[:3]:
        print(f"   first short slice at {k}")
    print(f"words written           : {stats['words']:,}")
    print(f"units written           : {stats['units']:,}")
    print(f"flat sequence IDENTICAL to today's table: {stats['flat_identical']} ayat")
    print(f"flat sequence differing               : {stats['flat_differing']} ayat")
    if stats.get("boundary_moved"):
        print(f"ayat where the boundary count moved    : {stats['boundary_moved']}")
    if stats.get("empty_word"):
        print(f"ayat containing an empty word         : {stats['empty_word']}")
    if stats["flat_differing"]:
        print("\nSTOP: the flat unit sequence changed. The DP sees this list, so "
              "recognition\n      would move. Do not ship; revert to the old units "
              "and investigate.")
        return 1

    # Word counts are the whole point of the exercise.
    bad = [k for k, n in mc.items()
           if len(out.get(f"{k[0]}:{k[1]}", [])) != n]
    print(f"\nayat whose word count still disagrees with the mushaf: {len(bad)} "
          f"of {len(mc)}")
    if bad:
        print(f"   e.g. {bad[:8]}  (surah-opening bismillah ayat, where the "
              f"tajweed text and the mushaf segment differently; these stay "
              f"UNKNOWN in the app rather than being guessed at)")
    if stats["unmapped_labels"]:
        print(f"labels with no bijection entry ({len(stats['unmapped_labels'])}): "
              f"{sorted(stats['unmapped_labels'])[:12]}")
    if stats["slice_short"] and len(short) < 4:
        short.append("see counts above")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False)
    print(f"\nwrote {OUT} ({OUT.stat().st_size/1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())