#!/usr/bin/env python3
"""
The three-ayah orbit, put to the measurement before anyone builds it.

The user's proposal (31 October): open the lock to the whole ayah or a
three-ayah window - "the reciter's own circle" - moving with its range in
real time, so the comparison runs text-against-what-was-said inside one
moving window. This tool prices it on REAL data without touching the app:

the pulled device records carry the audio stream the phone actually judged
(emissions, emMs, arrivals, spans) and the verdicts the phone actually gave
(wordRows). For each ayah A of a session this tool builds the orbit slice -
the THREE ayat A-1, A, A+1 as ONE expected text - runs ONE monotone
alignment (the app's own DP, the app's own app_key costing) against the
slice's emissions, and derives per-word verdicts for A's words from that
single path. Then it compares, per word, the orbit's verdict with the
device's verdict.

What the numbers mean:

  - accusations ADDED (orbit WRONG where the device was not) are the orbit's
    false-accusation cost - every one is listed with its word, because a
    rule that adds blame is guilty until measured innocent;
  - accusations REMOVED (device WRONG, orbit not-WRONG) are what the wider
    context forgives;
  - kind agreement on shared WRONG words says whether the wider alignment
    reads the same mistake the same way.

The monotone path is LESSONS 7's proposal for identical text (Ar-Rahman's
refrain): one path cannot sit on verse 63 while 65, 67 and 69 lie ahead.
The corpus half of this measurement belongs to a follow-up run (--corpus is
NOT implemented here; device records first, where the verdicts are real).

Refuses rather than invents: an ayah whose window cannot be rebuilt from
the record (pruned arrivals) is reported and skipped, never guessed.

Run: python3 engine/replay/orbit_pilot.py <record.json> [record.json ...]
"""

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import word_verdicts as wv          # noqa: E402
import device_blame as db           # noqa: E402

TABLE = (HERE.parents[1] / "android" / "app" / "src" / "main" / "assets"
         / "word_aligned_phonemes.json")


def slice_for(o, surah, ayah, table, unit_set):
    """The orbit slice for ayah (surah, ayah): the emissions of [A-1, A+1]
    against the three ayat's expected text as one word map.

    Edges come from the record's own spans and arrivals - the same
    reconstructable-window rules device_blame keeps. Returns
    (got_symbols, flat_units, unit_word, words_keyed) or None with a reason.
    """
    lo = None
    for a in (ayah - 1, ayah):
        if a == ayah - 1:
            # left edge: where the PREVIOUS ayah's speech ended, or its
            # arrival - whichever the record offers
            sp = o.get("spans", {}).get("%d:%d" % (surah, ayah - 1))
            ar = o.get("arrivals", {}).get("%d:%d" % (surah, ayah - 1))
            cand = sp if sp is not None else ar
            if cand is not None and (lo is None or cand < lo):
                lo = cand
        else:
            pass
    hi = o.get("spans", {}).get("%d:%d" % (surah, ayah + 1))
    if hi is None:
        ar = o.get("arrivals", {}).get("%d:%d" % (surah, ayah + 1))
        hi = ar
    if lo is None or hi is None or hi <= lo:
        return None, "no reconstructable left/right edge"
    base = o.get("emissionBase", 0)
    ems = o.get("emissions", [])
    if lo < base or hi > base + len(ems):
        return None, "edges outside the retained emission log"
    span = ems[lo - base:hi - base]
    got = [e for e in span if e in unit_set]

    flat = []
    uw = []
    word_keys = []
    for a in (ayah - 1, ayah, ayah + 1):
        wlist = table.get("%d:%d" % (surah, a))
        if not wlist:
            return None, "no table row for %d:%d" % (surah, a)
        for wi, wd in enumerate(wlist):
            for _u in wd:
                uw.append(len(word_keys))
            flat.extend(wd)
            word_keys.append("%d:%d:%d" % (surah, a, wi + 1))
    return (got, flat, uw, word_keys), None


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    try:
        unit_set = set(wv.load_units())
    except FileNotFoundError:
        print("REFUSED: the token table is absent (the gated model was not "
              "fetched); no re-align, no pilot.")
        return 1
    table = json.load(open(TABLE, encoding="utf-8"))

    grand = {"compared": 0, "agree": 0, "added": [], "removed": [],
             "kind_same": 0, "kind_diff": 0, "skipped_ayahs": 0,
             "matrix": {}}
    for path in sys.argv[1:]:
        try:
            o = json.load(open(path, encoding="utf-8"))
        except Exception as e:
            print("skip %s: %s" % (path, e))
            continue
        rows = {w["key"]: w for w in o.get("words", [])}
        surahs = sorted({int(k.split(":")[0]) for k in rows})
        print("\n== %s  (%d judged words)" % (path.rsplit("/", 1)[-1], len(rows)))
        for surah in surahs:
            ayat = sorted({int(k.split(":")[1]) for k in rows
                           if int(k.split(":")[0]) == surah})
            for ayah in ayat:
                sl, why = slice_for(o, surah, ayah, table, unit_set)
                if sl is None:
                    grand["skipped_ayahs"] += 1
                    print("  %d:%d refused: %s" % (surah, ayah, why))
                    continue
                got, flat, uw, word_keys = sl
                try:
                    matched, wrong, r2q, _e, _h, nw = wv.align(
                        [db.app_key(x) for x in got],
                        [db.app_key(u) for u in flat], uw)
                    subs, missed, totals = wv.mistake_counts(
                        matched, wrong, r2q, uw, nw)
                    st = wv.statuses_from(matched, wrong, uw, nw)
                except Exception as e:
                    print("  %d:%d re-align failed: %s" % (surah, ayah, e))
                    continue
                # the orbit's own word index space: the CENTER ayah's words
                center_words = [i for i, k in enumerate(word_keys)
                                if k.startswith("%d:%d:" % (surah, ayah))]
                for wi in center_words:
                    dev = rows.get(word_keys[wi])
                    if dev is None:
                        continue
                    dst = dev.get("st", "UNKNOWN")
                    ost = st.get(wi, "UNKNOWN")
                    grand["compared"] += 1
                    key = "%s->%s" % (dst, ost)
                    grand["matrix"][key] = grand["matrix"].get(key, 0) + 1
                    if dst == ost:
                        grand["agree"] += 1
                        if dst == "WRONG":
                            if subs[wi] >= 1:
                                grand["kind_same"] += 1
                            else:
                                grand["kind_diff"] += 1
                    elif dst == "WRONG":
                        grand["removed"].append(
                            "%s device WRONG -> orbit %s (sub %d, miss %d of %d)"
                            % (word_keys[wi], ost, subs[wi], missed[wi], totals[wi]))
                    elif ost == "WRONG":
                        grand["added"].append(
                            "%s device %s -> orbit WRONG (sub %d, miss %d of %d)"
                            % (word_keys[wi], dst, subs[wi], missed[wi], totals[wi]))

    print("\n" + "=" * 74)
    print("ORBIT PILOT (harness re-alignment over device records; "
          "harness numbers, not device numbers)")
    print("  words compared:        %d" % grand["compared"])
    if grand["compared"]:
        print("  verdict agreement:     %d (%.1f%%)"
              % (grand["agree"], 100.0 * grand["agree"] / grand["compared"]))
    print("  accusations REMOVED:   %d  (device WRONG, orbit not-WRONG)"
          % len(grand["removed"]))
    print("  accusations ADDED:     %d  (orbit WRONG where the device was not)"
          % len(grand["added"]))
    print("  shared WRONG kind:     %d same split, %d differ"
          % (grand["kind_same"], grand["kind_diff"]))
    print("  ayahs refused:         %d" % grand["skipped_ayahs"])
    print("  transition matrix (device -> orbit):")
    for k, n in sorted(grand["matrix"].items(), key=lambda kv: -kv[1]):
        print("    %-22s %5d" % (k, n))
    for k in ("added", "removed"):
        for line in grand[k][:30]:
            print("    [%s] %s" % (k, line))
        if len(grand[k]) > 30:
            print("    [%s] ... %d more" % (k, len(grand[k]) - 30))
    return 0


if __name__ == "__main__":
    sys.exit(main())
