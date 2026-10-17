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
from pathlib import Path
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



def judged_span(o, surah, ayah):
    """The emission window ayahObs() built for this ayah, or None.

    **There is no window to report unless both neighbour arrivals survived.**
    The record's `arrivals` is pruned to lockedAyah +/- 3 while the session is
    running, so a word judged long before the lock has no neighbour entry. The
    first version of this tool filled those gaps - `ayah-1` defaulted to 0 and
    `ayah+1` defaulted to the end of the log - which produced a window spanning
    the entire session and looked completely plausible. 10 of 14 false
    accusations were then "explained" by a window that was 1,200 symbols wide.
    A fallback that manufactures data is worse than no answer, which is the
    same lesson the emission replay taught: it defaulted to the total length and
    validated a rule that was never applied.
    """
    base = o.get("emissionBase", 0)
    arr = o.get("arrivals") or {}
    total = len(o.get("emissions", []))
    here = arr.get("%d:%d" % (surah, ayah))
    before = arr.get("%d:%d" % (surah, ayah - 1))
    after = arr.get("%d:%d" % (surah, ayah + 1))
    if here is None:
        return None, None, "no arrival in the retained log"
    if before is None or after is None:
        # A missing neighbour is a judgment the record cannot reproduce. The
        # app itself CLAMPS the window from the log's far end - see below - so
        # importing the log start or the log end stands in for audio the window
        # never held.
        pass
    # The app's own bound: a long ayah cannot let the per-frame cost blow up.
    # The replay must apply the same clamp, or every width it prints is the
    # full session when the arrivals are sparse - which is how 10 of 26 false
    # accusations were once "explained" by 1,200 symbols of the wrong ayah.
    lo = max(before if before is not None else here, base)
    hi = min(after if after is not None else base + total, base + total)
    # Same bound as ayahObs: hi - min(lo, hi - 480) can never exceed 480.
    bounded = max(lo, hi - 480)
    return bounded, hi, None
    lo = max(before if before is not None else here, base)
    hi = min(after if after is not None else base + total, base + total)
    return lo, hi, None


def show_cause(o, path):
    """For every WRONG word: the expected units, and what was actually emitted
    in the window the app judged against.

    This is the read-back that `wu` and `wt` could not give. `wu` says where in
    the word the blame landed; this says what was heard there.
    """
    ems = o.get("emissions")
    if not ems:
        print("\nNo emission stream in this record: it predates the capture, so "
              "the cause of each WRONG cannot be named. Re-recite on the build "
              "that writes it.")
        return
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import word_verdicts as wv
    # The app's own table: word_aligned_phonemes.json is what the runtime
    # ships, already tokenized, with the mushaf's word boundaries. The old
    # phrase-segmented table in the shootout weights is a different
    # segmentation (measured: 2:5 has 6 phrase-words against the app's 8), so
    # judging a device record against it invents mismatches the phone never
    # saw. See HARNESS_FIDELITY_AUDIT.md, D23.
    wt_path = (Path(__file__).resolve().parents[2]
               / "android" / "app" / "src" / "main" / "assets"
               / "word_aligned_phonemes.json")
    table = json.load(open(wt_path, encoding="utf-8"))
    units = wv.load_units()
    unit_set = set(units)
    tok = wv.make_tokenizer(units)
    print("\nEmission stream: %d symbols, base %d." % (len(ems), o.get("emissionBase", 0)))
    n = 0
    for w in o.get("words", []):
        if w.get("st") != "WRONG":
            continue
        n += 1
        if n > 40:
            break
        s_, a_, i_ = (int(v) for v in w["key"].split(":"))
        wlist = table.get("%d:%d" % (s_, a_))
        if not wlist or i_ > len(wlist):
            continue
        flat = [u for wd in wlist for u in wd]
        uw2 = [j for j, wd in enumerate(wlist) for _ in wd]
        lo, hi, why = judged_span(o, s_, a_)
        if why:
            print("  %-9s wu=%-5s window: NOT RECONSTRUCTABLE (%s). The record's"
                  " arrivals were pruned to the lock's vicinity, so the window this"
                  " word was judged against cannot be rebuilt from this record."
                  % (w["key"], w.get("wu"), why))
            continue
        span = ems[lo - o.get("emissionBase", 0):hi - o.get("emissionBase", 0)]
        print("  %-9s wu=%-5s window[%d:%d] emitted: %s"
              % (w["key"], w.get("wu"), lo, hi, " ".join(span)))
        exp_units = [flat[k] for k in range(len(flat)) if uw2[k] == i_ - 1]
        print("            word's expected units: %s" % " ".join(exp_units))
        # Re-run the app's own alignment on this window and name what the
        # blamed unit was charged against. Only for words the phone marked
        # WRONG: this is the read-back that answers what was heard there.
        try:
            got = [e for e in span if e in unit_set]
            flat = [u for wd in wlist for u in wd]
            uw2 = [j for j, wd in enumerate(wlist) for _ in wd]
            matched, wrong, _r2q, _e, _h, _nw = wv.align(got, flat, uw2)
            for k in range(len(flat)):
                if uw2[k] != i_ - 1:
                    continue
                q = _r2q[k]
                heard = got[q] if q is not None and q >= 0 else "(no symbol)"
                flag = " <-- CHARGED" if wrong[k] else ("matched" if matched[k] else "")
                print("              exp %-6s heard %-6s %s" % (flat[k], heard, flag))
        except Exception as e:
            print("              (re-align skipped: %s)" % e)
    if n:
        print("  ^ the emitted span is the window the app judged against; the "
              "expected line is that word's own units.")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    total = 0
    by_bucket = Counter()
    first_unit_words = []
    last_unit_words = []
    lats = []
    LAST_O = {}
    for path in sys.argv[1:]:
        try:
            o = json.load(open(path, encoding="utf-8"))
        except Exception as e:
            print("skip %s: %s" % (path, e))
            continue
        LAST_O[path] = o
        have_wu = any("wu" in w for w in o.get("words", []))
        have_wt = any("wt" in w for w in o.get("words", []))
        if not have_wu and not have_wt:
            print("%s: neither `wu` nor `wt`. That session predates both."
                  % path.rsplit("/", 1)[-1])
            continue
        if not have_wu:
            print("%s: no `wu`, so blame positions are unavailable; reading `wt` only."
                  % path.rsplit("/", 1)[-1])
        for w in o.get("words", []):
            if "wt" in w:
                lats.append(w["wt"])
            if w.get("st") != "WRONG":
                continue
            fr = w.get("wu", -1)
            total += 1
            by_bucket[bucket(fr)] += 1
            if fr == 0:
                first_unit_words.append(w["key"])
            if fr >= 1000:
                last_unit_words.append(w["key"])

    if total == 0 and not lats:
        print("No WRONG word with a blame position in these records.")
        if not lats:
            return 0

    if total:
        print("WRONG words with a recorded blame position: %d" % total)
    for name, n in by_bucket.most_common():
        print("  %-22s %3d  (%2.0f%%)" % (name, n, 100.0 * n / total))
    if lats:
        lats.sort()
        n = len(lats)
        print()
        print("Verdict latency (`wt`, ms from session start to the word being decided):")
        for label, v in (("first quarter", lats[n // 4]),
                         ("median", lats[n // 2]),
                         ("three quarters", lats[3 * n // 4]),
                         ("last", lats[-1])):
            print("  %-16s %6d ms" % (label, v))
        print()
        print("This is the measurement behind \"colour only appears when most of the")
        print("ayah is done\". Compare it with the frames' per-poll cadence to tell")
        print("a slow engine from a late paint.")

    show_cause(LAST_O.get(path), path)
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
