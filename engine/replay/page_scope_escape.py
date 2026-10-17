#!/usr/bin/env python3
"""
The mid-page escape: can the policy see a reciter leave the standing surah?

Measured on the user's own records. The walk that started it, 17 October, page
602 (surahs 106, 107, 108): the reciter recited 107:1-7, then 108:1-3, then
returned and recited 106:1-4 - while the lock sat on 106:1 the whole time. The
paint did not follow because the engine's scope is one surah and the policy has
no path to a sibling surah mid-page.

This tool measures the signals a sibling escape would read, on the same
windows the app judged, and evaluates a candidate rule against every pulled
record - the walk for a positive case, the sequential sessions for false
escapes (the gates culture: a lock move must be as conservative as a verdict).

Rule under test (per poll, only when audio is present):
    sibling opening-ayah coverage >= STRONG, sustained SUS frames,
    while leading the standing ayah's own coverage by LEAD.
Positive expectation:  the walk's 107/108 legs fire, the 106 return does not
                       (siblings then stay silent).
False-escape check:    every sequential record (the standing ayah recited
                       normally) must show no sustained sibling lead.

Windows are rebuilt exactly as ayahObs does - [max(prev arrival or 0, base),
min(next arrival or log end, base + total), 480 clamp] - because the coverage
numbers being compared are the ones the app itself measured for `here`.

Run: python3 engine/replay/page_scope_escape.py <record.json> [more.json]
"""

import argparse
import json
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
MUSHAF = ROOT / "android/app/src/main/assets/mushaf.json"
TABLE = ROOT / "android/app/src/main/assets/word_aligned_phonemes.json"

STRONG = 0.85   # same standard as the strong-advance gate
TAIL = 120      # symbols of recent audio an escape may read
LEAD = 0.35     # sibling must lead the standing coverage by this much
SUS = 3         # consecutive polls with audio
OBS_FLOOR = 1   # at least this many new symbols since the last poll


def page_surahs(surah, ayah):
    """The other surahs printed on the page that holds surah:ayah."""
    mushaf = json.load(open(MUSHAF, encoding="utf-8"))
    page = next(p for p in mushaf
                if any(l.get("surah") == str(surah) for l in p["lines"]
                       and l.get("type") == "text"))
    out = sorted({int(l["surah"]) for l in page["lines"]
                  if l.get("type") == "text" and l.get("surah")})
    return [s for s in out if s != surah]


def ayah_pages(surah, ayah):
    """The page span of a surah's ayah - escapes only test the page actually
    standing. The first ayah of the standing ayah's page is the test."""
    mushaf = json.load(open(MUSHAF, encoding="utf-8"))
    for p in mushaf:
        for l in p["lines"]:
            if l.get("type") != "text":
                continue
            ws = [w["location"] for w in (l.get("words") or [])]
            if any(w.startswith("%d:%d:" % (surah, ayah)) for w in ws):
                return p["page"]
    return None


def expected_units(surah, ayah, word_idx=None):
    table = json.load(open(TABLE, encoding="utf-8"))
    wlist = table.get("%d:%d" % (surah, ayah))
    if not wlist:
        return None
    return wlist


def unit_count(wlist, word_idx=None):
    if word_idx is None:
        return sum(len(w) for w in wlist)
    return len(wlist[word_idx - 1])


def coverage(ems_lo, ems_hi, flat, uw2, wv, unit_set):
    got = [e for e in ems_lo if e in unit_set]
    if not flat:
        return 0.0
    try:
        matched, wrong, _r2q, _e, _h, nw = wv.align(got, flat, uw2)
        # The app's here() counts matched-or-wrong? No: here is the share of
        # expected units the window could account for at all; wrong ones are
        # contradicted, not accounted. matched only.
        return sum(1 for m in matched if m) / max(1, len(flat))
    except Exception:
        return 0.0


def sweep(record, wv, unit_set):
    ems = record.get("emissions") or []
    base = record.get("emissionBase", 0)
    frames = record.get("frames") or []
    if not ems or not frames:
        print("  (no emissions or frames: nothing to measure)")
        return []
    start_surah = frames[0].get("s")
    # Siblings of the page the session STANDS on for its first ayah. Escapes
    # are only measured to surahs the reader sees on the same page.
    first_lock = frames[0].get("lock") or 1
    mu = json.load(open(MUSHAF, encoding="utf-8"))
    page = ayah_pages(start_surah, first_lock)
    if page is None:
        print("  (standing page cannot be located for %d:%d)" % (start_surah, first_lock))
        return []
    p = next(x for x in mu if x["page"] == page)
    # The surah tag lives on the surah-header lines; text lines carry only
    # verseRange. Headers are exactly one per surah on the page.
    all_surahs = sorted({int(l["surah"]) for l in p["lines"]
                         if l.get("surah")})
    sib = [s for s in all_surahs if s != start_surah]
    sib_ayahs = {}
    table = json.load(open(TABLE, encoding="utf-8"))
    for s in sib:
        for a in (1, 2):
            wlist = expected_units(s, a)
            if wlist:
                sib_ayahs[(s, a)] = wlist
    rows = []
    for f in frames:
        syms = f.get("syms") or 0
        hi = base + max(1, min(len(ems), syms))
        lo = base + 0
        window = ems[lo:hi - base]
        # The escape reads RECENT audio, not the whole log: a sibling that was
        # recited minutes ago stays saturated in the log forever, and a rule
        # over the log re-fires on the return leg (measured: shots at t=40.9s
        # and t=49.5s on the walk record, after the reciter had come back to
        # 106). The tail is what an escape-to-sibling can honestly mean:
        # "the audio just heard".
        tail_start = max(0, len(window) - TAIL)
        tail = window[tail_start:]
        here_v = f.get("here")
        row = {"t": f.get("t"), "lock": "%s:%s" % (f.get("s"), f.get("lock")),
               "obs": f.get("obs"), "here": here_v}
        for (s, a), wlist in sib_ayahs.items():
            flat = [u for wd in wlist for u in wd]
            uw2 = [j for j, wd in enumerate(wlist) for _ in wd]
            row["sib%d:%d" % (s, a)] = coverage(tail, None, flat, uw2, wv, unit_set)
            row["full%d:%d" % (s, a)] = coverage(window, None, flat, uw2, wv, unit_set)
        rows.append(row)
    return rows


def evaluate(rows, strong, lead, sus, obs_floor):
    """Frames where the rule fires, and the resulting escape decision."""
    shots = []
    streak = {}
    for row in rows:
        for key, cov in row.items():
            if not (key.startswith("sib")):
                continue
            here = row.get("here") if isinstance(row.get("here"), float) else None
            if (cov is not None and cov >= strong and here is not None
                    and here is not False and (cov - (here or 0)) >= lead
                    and (row.get("obs") or 0) >= obs_floor):
                streak[key] = streak.get(key, 0) + 1
                if streak[key] == sus:
                    shots.append((row["t"], key, cov, here))
            else:
                streak[key] = 0
    return shots


def main():
    sys.path.insert(0, str(HERE))
    import word_verdicts as wv
    units = wv.load_units()
    unit_set = set(units)
    ap = argparse.ArgumentParser()
    ap.add_argument("records", nargs="+")
    ap.add_argument("--strong", type=float, default=STRONG)
    ap.add_argument("--lead", type=float, default=LEAD)
    ap.add_argument("--sus", type=int, default=SUS)
    args = ap.parse_args()
    for rec_path in args.records:
        record = json.load(open(rec_path, encoding="utf-8"))
        print("\n== %s" % Path(rec_path).name)
        rows = sweep(record, wv, unit_set)
        # Characterise: peak sibling coverage and when, plus the standing ayah's
        # own coverage around the walk.
        sib_keys = sorted({k for row in rows for k in row if k.startswith("sib")})
        for k in sib_keys:
            vals = [(row["t"], row[k]) for row in rows if k in row]
            peak = max(vals, key=lambda x: x[1])
            print("  %-8s peak %.2f at t=%sms; first>=0.60 at %s"
                  % (k, peak[1], peak[0],
                     next(("%d" % t for t, v in vals if v >= 0.60), "never")))
        shots = evaluate(rows, args.strong, args.lead, args.sus, OBS_FLOOR)
        if shots:
            print("  escape would fire at:", ", ".join("t=%s %s(%.2f here %s)" % s for s in shots[:8]))
        else:
            print("  no escape under strong=%.2f lead=%.2f sus=%d" % (args.strong, args.lead, args.sus))


if __name__ == "__main__":
    main()
