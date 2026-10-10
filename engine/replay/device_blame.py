#!/usr/bin/env python3
"""
Where in a word does the mismatch begin? Read it off the phone's own records.

The whole-corpus sweep and the device disagree about which words are blamed:

  harness window (fits its own window)   first word of an ayah  42%
  the phone (ayahObs, three ayat wide)   first word of an ayah  10%

and the device's dominant class - 53% of its WRONG verdicts - has no textual
signature at all: plain mid-ayah words, no stop mark, not the ayah's last word,
not a pronoun ending. Neither figure could say WHERE the mismatch sat. A boundary
artefact and a wrong phoneme produce the same verdict and different blame
positions.

So `wu` was added to the session record: for each WRONG word, the fraction of the
word's own units at which the contradiction begins. 0 is the first unit, 1000 is
the last. This tool reads it and buckets it.

Run:  adb shell run-as com.iqra.quran cat files/sessions/<file>.json > /tmp/x.json
      python3 engine/replay/device_blame.py /tmp/x.json [more.json ...]
"""

import json
import sys
from collections import Counter


def bucket(fr):
    """Map a 0..1000 fraction to a position inside the word."""
    if fr < 0:
        return "no blame recorded"
    if fr == 0:
        return "first unit"
    if fr >= 1000:
        return "last unit"
    if fr <= 200:
        return "near the start (0-20%)"
    if fr <= 400:
        return "early (20-40%)"
    if fr <= 600:
        return "middle (40-60%)"
    if fr <= 800:
        return "late (60-80%)"
    return "near the end (80-100%)"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    total = 0
    by_bucket = Counter()
    first_unit_words = []
    last_unit_words = []
    for path in sys.argv[1:]:
        try:
            o = json.load(open(path, encoding="utf-8"))
        except Exception as e:
            print("skip %s: %s" % (path, e))
            continue
        have_wu = any("wu" in w for w in o.get("words", []))
        if not have_wu:
            print("%s: no `wu` field. That session predates the blame "
                  "position, or it recorded no WRONG word." % path.rsplit("/", 1)[-1])
            continue
        for w in o.get("words", []):
            if w.get("st") != "WRONG":
                continue
            fr = w.get("wu", -1)
            total += 1
            by_bucket[bucket(fr)] += 1
            if fr == 0:
                first_unit_words.append(w["key"])
            if fr >= 1000:
                last_unit_words.append(w["key"])

    if total == 0:
        print("No WRONG word with a blame position in these records.")
        return 0

    print("WRONG words with a recorded blame position: %d" % total)
    for name, n in by_bucket.most_common():
        print("  %-22s %3d  (%2.0f%%)" % (name, n, 100.0 * n / total))
    print()
    if first_unit_words:
        print("Blamed on the FIRST unit (%d):" % len(first_unit_words))
        print("  ", " ".join(first_unit_words[:24]))
    if last_unit_words:
        print("Blamed on the LAST unit (%d):" % len(last_unit_words))
        print("  ", " ".join(last_unit_words[:24]))
    print()
    print("A word blamed on its first unit at an ayah opening is the boundary")
    print("artefact the harness already shows. A word blamed in the middle is a")
    print("different fault, and the table or the pipeline is where it lives.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
