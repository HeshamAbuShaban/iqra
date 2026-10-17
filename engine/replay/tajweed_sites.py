#!/usr/bin/env python3
"""
Which tajweed classes does the unit alphabet already separate, and what does
the recogniser pay for on them?

Two measurements, both read-only over shipped files and the reference reciter's
corpus (the Al-Dosari dumps in engine/corpus/out - harness numbers, not device
numbers):

1. STATIC. Every distinct unit of android/app/src/main/assets/
   word_aligned_phonemes.json (6,236 ayat, 77,481 words) is binned by code
   points alone - the symbol sets are read off the table's own alphabet, not
   written in by hand:
     QALQALAH  the unit's body is ONE copy of a letter that carries the
               marked variant U+0687 (TCHEHEH). That marker is discovered
               here: every unit containing it is collected, and the letters
               it attaches to ARE the qalqalah set. It attaches to exactly
               the five tajweed qalqalah letters (b j d q + emphatic t; plain
               t never carries it - measured, and correct in tajweed).
               Sites are split by what follows: word-final, followed by a
               madd unit, or followed by another consonant unit. Qalqalah
               belongs to the sukoon readings, so word-final is the real
               class and the other two are where it cannot apply.
     NASAL     the unit's body is 2..4 copies of a nasal marker: plain
               noon/meem duplicated (ghunnah written as length, e.g. the
               mushaddad noon), or a variant code point that appears ONLY in
               duplicated bodies - U+06BA (NOON GHUNNA) and U+06FE, both
               discovered from the data exactly this way.
     MADD      the unit's body is 2..6 copies of a vowel-bearer letter
               (a w y a) or a dagger mark (U+06E5 small waw / U+06E6 small
               yeh); duration written as repetition, which is why madd is
               free-length in the matcher.
     GEMINATE  any other repeated body (shadda of a consonant).
     OTHER     everything else: plain consonants and vowelled units, plus
               five one-off oddballs listed in the report.
   Per class: distinct units, mushaf words containing at least one such
   unit, and 3 example words with the unit highlighted.

2. CORPUS. Each of the 114 dumps is aligned with the app's own machinery,
   exactly as optional_final.py does (word_verdicts.load_units and align,
   optional_final's own local_window, the shipped table itself), and every
   expected unit the aligner refuses - substituted or unheard - is CHARGED
   to the site that expected it. Per class: charged sites / sites of that
   class over judged ayat, against the all-units baseline.

Honesty rules: a surah with no dump prints "(not measurable: ...)"; ayat
below the 0.90 coverage gate are excluded from BOTH sides of the rate and
their count is printed; a class with no judged sites prints "(not
measurable: no judged sites)" rather than a rate. No shipped file is
modified by this tool.

Run: python3 engine/replay/tajweed_sites.py [surah ...]   (no args = all 114)
"""

import json
import os
import sys
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor, as_completed

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import word_verdicts as wv                                  # noqa: E402
from optional_final import local_window, words_by_key       # noqa: E402

ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
TABLE = os.path.join(ROOT, "android/app/src/main/assets/word_aligned_phonemes.json")
OUT = os.path.join(ROOT, "engine/corpus/out")

# The variant code points the buckets are built from. HARAKA/TANWIN/SUKUN are
# the standard marks; the qalqalah marker and the nasal variant markers are
# DERIVED from the table inside classify_units() and printed as discovered.
HARAKA = set("\u064e\u064f\u0650")
TANWIN = set("\u064b\u064c\u064d")
SUKUN = set("\u0652")
TAILS = HARAKA | TANWIN | SUKUN
QALQ_MARK = "\u0687"          # TCHEHEH, the qalqalah variant suffix
VOWEL_BEARERS = set("\u0627\u0648\u064a\u0649")   # a w y a
DAGGERS = set("\u06e5\u06e6")                     # small waw, small yeh
PLAIN_NASALS = set("\u0646\u0645")                # n, m

CLASSES = ("QALQALAH", "NASAL", "MADD", "GEMINATE", "OTHER")
POSITIONS = ("final", "pre-vowel", "mid")
GENUINE = "genuine"           # row sub-tag: marked + bare-final qalqalah only


def _body(u):
    """Unit minus its trailing harakah/tanwin/sukun and the qalqalah marker.

    Returns (body, flagged). `flagged` says the qalqalah variant marker was
    present, which is itself one of the measured facts.
    """
    body, flagged = u, False
    if body and body[-1] in TAILS:
        body = body[:-1]
    if body and body[-1] == QALQ_MARK:
        body, flagged = body[:-1], True
    return body, flagged


def classify_units(units):
    """Bin every distinct unit. Returns (unit->class, derivation facts).

    Derivation, in the order the facts are gathered:
      - letters carrying the qalqalah marker -> QALQALAH set (data-derived)
      - variant code points that appear ONLY inside duplicated bodies
        -> the nasal/ghunnah variant set (data-derived)
      - every unit is then binned by body shape and membership in those sets.
    """
    qalq_letters = set()
    variant_only = set()
    seen_single = set()
    for u in units:
        body, _flag = _body(u)
        if QALQ_MARK in u:
            qalq_letters.add(body[0])
        if len(body) >= 2 and len(set(body)) == 1:
            pass
        elif len(body) == 1:
            seen_single.add(body[0])
    for u in units:
        body, _flag = _body(u)
        if len(body) >= 2 and len(set(body)) == 1 and body[0] not in seen_single:
            variant_only.add(body[0])
    variant_only -= qalq_letters
    # U+0640 (tatweel) is a stretch filler, not a nasalisation marker; its one
    # duplicated occurrence is elongation and lands in GEMINATE with the other
    # non-tajweed repeated bodies.
    variant_only.discard("\u0640")

    classes = {}
    for u in units:
        body, flagged = _body(u)
        if len(body) >= 2 and len(set(body)) == 1:
            c = body[0]
            if c in VOWEL_BEARERS or c in DAGGERS:
                classes[u] = "MADD"
            elif c in PLAIN_NASALS or c in variant_only:
                classes[u] = "NASAL"
            else:
                classes[u] = "GEMINATE"
        elif len(body) == 1 and body[0] in qalq_letters:
            classes[u] = "QALQALAH"
        else:
            classes[u] = "OTHER"
    facts = {
        "qalq_letters": sorted(qalq_letters, key=ord),
        "variant_only": sorted(variant_only, key=ord),
        "qalq_units": sorted(u for u, c in classes.items() if c == "QALQALAH"),
    }
    return classes, facts


def position_of(k, w, classes):
    if k == len(w) - 1:
        return "final"
    if classes[w[k + 1]] == "MADD":
        return "pre-vowel"
    return "mid"


def _tag(cls, u):
    """Sub-type label used to pick example words per class."""
    if cls == "QALQALAH":
        body, flagged = _body(u)
        if flagged:
            return "marked"
        if u[-1] in HARAKA:
            return "vowelled"
        return "bare"
    if cls == "NASAL":
        body, _ = _body(u)
        return body[0]
    if cls == "MADD":
        body, _ = _body(u)
        return body[0]
    return u


def static_inventory():
    tbl = json.load(open(TABLE, encoding="utf-8"))
    distinct = set()
    for words in tbl.values():
        for w in words:
            distinct.update(w)
    classes, facts = classify_units(distinct)

    nwords = sites = 0
    cls_units = defaultdict(set)
    sites_by = Counter()
    words_with = defaultdict(set)
    examples = defaultdict(list)
    keys = sorted(tbl, key=lambda k: (int(k.split(":")[0]), int(k.split(":")[1])))
    W = words_by_key()

    def word_text(key, wi):
        s, a = key.split(":")
        return W.get((int(s), int(a), wi + 1), "?")

    for key in keys:
        for wi, w in enumerate(tbl[key]):
            nwords += 1
            for k, u in enumerate(w):
                sites += 1
                cls = classes[u]
                pos = position_of(k, w, classes)
                sub_t = site_sub(classes, u, k, w)
                cls_units[cls].add(u)
                sites_by[(cls, pos, sub_t)] += 1
                words_with[cls].add((key, wi))
                tag = _tag(cls, u)
                if tag not in {e[0] for e in examples[cls]} and len(examples[cls]) < 6:
                    seq = " ".join("[%s]" % v if classes[v] == cls else v
                                   for v in w)
                    examples[cls].append((tag, "%s w%d" % (key, wi + 1),
                                          word_text(key, wi), seq))

    print("=" * 74)
    print("STATIC: the unit alphabet of %s" % os.path.basename(TABLE))
    print("  %d ayat, %s words, %s unit sites, %d distinct units"
          % (len(tbl), "{:,}".format(nwords), "{:,}".format(sites), len(distinct)))
    q = facts["qalq_letters"]
    print("  derivation: marker U+%04X (TCHEHEH) attaches to %d letters: %s"
          % (ord(QALQ_MARK), len(q), " ".join(q)))
    print("  derivation: code points occurring ONLY as duplicated bodies: %s;"
          % " ".join("U+%04X" % ord(c) for c in facts["variant_only"]))
    nasal_variants = [c for c in facts["variant_only"]
                      if c not in VOWEL_BEARERS and c not in DAGGERS]
    print("    minus the madd bearers, the nasal variant markers are: %s"
          % " ".join("U+%04X" % ord(c) for c in nasal_variants))
    print()
    print("  %-9s %5s %9s %8s   %s" % ("class", "units", "sites", "words", "final/pre-vowel/mid"))
    for cls in CLASSES:
        tot = sum(v for (c2, p2, st2), v in sites_by.items()
                  if c2 == cls)
        us = sorted(cls_units[cls], key=lambda u: (len(u), u))
        print("  %-9s %5d %9s %8s   %d/%d/%d"
              % (cls, len(us), "{:,}".format(tot),
                 "{:,}".format(len(words_with[cls])),
                 sum(v for (c2, p2, st2), v in sites_by.items()
                     if c2 == cls and p2 == "final"),
                 sum(v for (c2, p2, st2), v in sites_by.items()
                     if c2 == cls and p2 == "pre-vowel"),
                 sum(v for (c2, p2, st2), v in sites_by.items()
                     if c2 == cls and p2 == "mid")))
        if cls == "QALQALAH":
            for sub_t in ("marked", "bare-final", "ghost"):
                tot = sum(sites_by[(cls, p, sub_t)] for p in POSITIONS)
                print("      %-8s %9s sites %d/%d/%d (final/pre-vowel/mid)"
                      % (sub_t, "{:,}".format(tot),
                         sites_by[(cls, "final", sub_t)],
                         sites_by[(cls, "pre-vowel", sub_t)],
                         sites_by[(cls, "mid", sub_t)]))
    print()
    for cls in CLASSES:
        us = sorted(cls_units[cls], key=lambda u: (len(u), u))
        print("  %s units (%d): %s" % (cls, len(us), " ".join(us)))
        for tag, key, txt, seq in examples[cls][:3]:
            print("      %-7s %-20s %s" % (tag, txt if txt != "?" else key, seq))
        print()
    return classes


_STATE = None


def _table():
    global _STATE
    if _STATE is None:
        tbl = json.load(open(TABLE, encoding="utf-8"))
        distinct = set()
        for words in tbl.values():
            for w in words:
                distinct.update(w)
        cls, _facts = classify_units(distinct)
        _STATE = (tbl, cls)
    return _STATE


def site_sub(classes, u, k, ref):
    """The third bin key: how a qalqalah-LETTER site becomes a qalqalah SITE.
    Empty for every other class; the class bins stay 2-keyed in spirit.

    The classifier bins units by BODY LETTER, so bare vowelled forms of the
    five qalqalah letters (\u0628\u062f\u0637\u0642\u062c + fatha etc.) land in the
    class although they are NOT qalqalah readings - a vowel means the letter is
    voiced normally. Split into:
      marked      - the unit carries the U+0687 variant suffix: always qalqalah
      bare-final  - bare body, word's last unit, no harakah: the implicit
                    final sukoon makes it qalqalah
      ghost       - everything else: a qalqalah LETTER, not a qalqalah reading
    """
    if classes[u] != "QALQALAH":
        return ""
    _b, flagged = _body(u)
    if flagged:
        return "marked"
    if k == len(ref) - 1 and u[-1] not in HARAKA and u[-1] not in TANWIN:
        return "bare-final"
    return "ghost"


def sweep_one(surah):
    """Align one surah dump and charge every refused unit.

    Identical machinery to optional_final.py: its local_window, its coverage
    gate, its trim-and-realign; the table is the app's own
    word_aligned_phonemes.json. Charges on the FINAL alignment (post-trim),
    the same one the verdicts are read from.
    """
    path = os.path.join(OUT, "%03d.json" % surah)
    if not os.path.isfile(path):
        return {"surah": surah, "note": "not measurable: no dump at engine/corpus/out/%03d.json" % surah}
    tbl, classes = _table()
    ayat = sorted({int(k.split(":")[1]) for k in tbl
                   if int(k.split(":")[0]) == surah})
    dump = json.load(open(path, encoding="utf-8"))
    syms = [e["symbol"] for e in dump["emissions"]]

    judged = skipped = 0
    sites = Counter()
    charged = Counter()
    sub = Counter()
    unheard = Counter()
    cursor = 0
    for a in ayat:
        words = tbl["%d:%d" % (surah, a)]
        ref = [u for w in words for u in w]
        uw = [wi for wi, w in enumerate(words) for u in w]
        cov, s, ln = local_window(syms, ref, cursor)
        if cov < 0.9 or s < 0:
            skipped += 1
            continue
        window = syms[s:s + ln]
        m0, w0, r2q, _e0, _h0, _n0 = wv.align(window, ref, uw)
        hit = [q for q in r2q if q >= 0]
        if hit:
            window = window[min(hit):max(hit) + 1]
            m0, w0, r2q, _e0, _h0, _n0 = wv.align(window, ref, uw)
            cursor = s + max(hit) + 1
        judged += 1
        for k, u in enumerate(ref):
            pos_t = site_sub(classes, u, k, ref)
            ch = (classes[u], position_of(k, ref, classes), pos_t)
            sites[("ALL", "", "")] += 1
            sites[ch] += 1
            if not m0[k]:
                charged[("ALL", "", "")] += 1
                charged[ch] += 1
                (sub if w0[k] else unheard)[("ALL", "", "")] += 1
                (sub if w0[k] else unheard)[ch] += 1
    return {"surah": surah, "ayat": len(ayat), "judged": judged,
            "skipped": skipped, "symbols": len(syms),
            "sites": sites, "charged": charged, "sub": sub, "unheard": unheard}


def corpus_sweep(surahs):
    """Run every surah's dump through the app's own aligner and charge every
    refused unit to the site that expected it, bucketed by tajweed class and
    position; the ALL row is the baseline every rate is read against."""
    sites = Counter()
    charged = Counter()
    sub = Counter()
    unheard = Counter()
    ayat_total = ayat_judged = ayat_skipped = 0
    notes = []
    workers = min(8, len(surahs)) or 1
    # Submit biggest first: the pool starts the long surahs at once and fills
    # the small ones into the gaps, so the wall clock tracks total work
    # instead of the slowest surah's slot in the numbering.
    tbl = json.load(open(TABLE, encoding="utf-8"))
    cost = Counter()
    for k, words in tbl.items():
        cost[int(k.split(":")[0])] += sum(len(w) * len(w) for w in words)
    del tbl
    order = sorted(surahs, key=lambda s: -cost[s])
    with ProcessPoolExecutor(max_workers=workers) as pool:
        futs = {pool.submit(sweep_one, s): s for s in order}
        for fut in as_completed(futs):
            r = fut.result()
            if "note" in r:
                print("surah %03d: %s" % (r["surah"], r["note"]))
                notes.append(r["note"])
                continue
            ayat_total += r["ayat"]
            ayat_judged += r["judged"]
            ayat_skipped += r["skipped"]
            sites += r["sites"]
            charged += r["charged"]
            sub += r["sub"]
            unheard += r["unheard"]
            extra = "" if not r["skipped"] else " (%d below the gate)" % r["skipped"]
            print("surah %03d: %d/%d ayat judged, %s symbols%s"
                  % (r["surah"], r["judged"], r["ayat"],
                     "{:,}".format(r["symbols"]), extra))
    print()
    if notes:
        for n in notes:
            print("(not measurable: %s)" % n)
        print()
    print("=" * 74)
    print("CORPUS: %d/%d ayat judged over %d surahs, %d skipped below the 0.90 gate"
          % (ayat_judged, ayat_total, len(surahs), ayat_skipped))
    all_sites = sites[("ALL", "")]
    all_charged = charged[("ALL", "")]
    if all_sites == 0:
        print("(not measurable: no ayat passed the 0.90 coverage gate)")
        return
    base_rate = all_charged / float(all_sites)
    print("  baseline: %s of %s judged unit sites charged (%.2f%%)"
          % ("{:,}".format(all_charged), "{:,}".format(all_sites), 100.0 * base_rate))
    print()
    print("  %-22s %9s %9s %8s %8s %7s %7s"
          % ("class", "sites", "charged", "sub", "unheard", "rate", "vs base"))

    def row(name, cls, pos, sub_t=""):
        if sub_t == GENUINE:   # genuine qalqalah sites: marked + bare-final
            keys = [(cls, p, st) for p in POSITIONS
                    for st in ("marked", "bare-final")]
        elif pos is None:
            keys = [(cls, p, sub_t) for p in POSITIONS]
        else:
            keys = [(cls, pos, sub_t)]
        n = sum(sites[k] for k in keys)
        c = sum(charged[k] for k in keys)
        s_ = sum(sub[k] for k in keys)
        h_ = sum(unheard[k] for k in keys)
        rate = c / float(n) if n else None
        if rate is None or base_rate == 0.0:
            vs = "-" if (rate is None or rate == 0.0) else "inf"
        else:
            vs = "%5.2fx" % (rate / base_rate)
        print("  %-22s %9s %9s %8s %8s %6s%% %6s"
              % (name,
                 "{:,}".format(n), "{:,}".format(c),
                 "{:,}".format(s_), "{:,}".format(h_),
                 "-" if rate is None else "%.2f" % (100.0 * rate), vs))

    for sub_t in ("marked", "bare-final", "ghost"):
        row("QALQALAH " + sub_t, "QALQALAH", None, sub_t)
    row("QALQALAH (q)", "QALQALAH", None, None)
    for cls in ("NASAL", "MADD", "GEMINATE", "OTHER"):
        row(cls, cls, None)
    row("ALL (baseline)", "ALL", "")


def main():
    surahs = [int(a) for a in sys.argv[1:]] or list(range(1, 115))
    static_inventory()
    print("=" * 74)
    print()
    corpus_sweep(surahs)
    return 0


if __name__ == "__main__":
    sys.exit(main())
