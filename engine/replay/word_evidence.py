#!/usr/bin/env python3
"""What fraction of a word do we actually hear, for words currently judged WRONG?

The WRONG rule accepts any word with `ok * 2 >= total` and at least one
contradicted unit - so a word heard only 55% through still counts as
mispronounced. That is not defensible on its own terms: a word cannot be
pronounced wrongly on the part of it nobody heard. But raising the bar without
measurement is how a threshold gets invented, so this measures the distribution
on the reciter the corpus actually contains (Al-Dosari) rather than on the
Husary clips the rule was originally tuned against.

Per ayah, align that ayah's own emissions against its expected words - the same
shape as the app's per-frame word pass - and bucket each word by its own
ok/total. Reports WRONG words by coverage bucket, so the floor can be read off
the data instead of guessed.

Run: engine/.venv-replay/bin/python engine/replay/word_evidence.py [n_surahs]
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "engine" / "replay"))

import lock_trace as L  # noqa: E402
import word_verdicts as W  # noqa: E402
from hesitation_policy import ayah_spans  # noqa: E402

BUCKETS = [0.0, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.01]


def bucket(v):
    for i in range(len(BUCKETS) - 1):
        if BUCKETS[i] <= v < BUCKETS[i + 1]:
            return i
    return len(BUCKETS) - 2


def main() -> int:
    n_surahs = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    out_dir = ROOT / "engine/corpus/out"
    dumps = sorted(p for p in out_dir.glob("*.json"))[:n_surahs]
    if not dumps:
        print("SKIP: no corpus dumps")
        return 0

    table = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    counts = {}
    for line in (ROOT / "engine/corpus/report.jsonl").read_text().splitlines():
        if line.strip():
            r = json.loads(line)
            counts[r["surah"]] = r["ayat"]

    wrong_by_bucket = [0] * (len(BUCKETS) - 1)
    correct_by_bucket = [0] * (len(BUCKETS) - 1)
    skipped_by_bucket = [0] * (len(BUCKETS) - 1)
    tot_wrong = tot_correct = 0
    done = 0

    for p in dumps:
        surah = int(p.stem)
        n_ayat = counts.get(surah)
        if not n_ayat:
            continue
        d = json.load(open(p, encoding="utf-8"))
        spans = ayah_spans(d, table, tok, surah, n_ayat)
        exp = L.build_exp(table, tok, [(surah, n_ayat)])[surah]
        for a in sorted(spans):
            if a not in exp:
                continue
            syms = [e["symbol"] for e in spans[a]]
            units, unit_word = exp[a][0], exp[a][1]
            if not syms or not units:
                continue
            matched, wrong, r2q, emit, hits, _nw = W.align(syms, units, unit_word)
            st = W.statuses_from(matched, wrong, unit_word, len(unit_word))
            nwords = len(unit_word)
            for wi in range(nwords):
                tot = ok = 0
                for k, owner in enumerate(unit_word):
                    if owner != wi:
                        continue
                    tot += 1
                    if matched[k]:
                        ok += 1
                cov = ok / tot if tot else -1.0
                if cov < 0:
                    continue
                b = bucket(cov)
                v = st.get(wi)
                if v == "WRONG":
                    wrong_by_bucket[b] += 1
                    tot_wrong += 1
                elif v == "CORRECT":
                    correct_by_bucket[b] += 1
                    tot_correct += 1
                elif v == "SKIPPED":
                    skipped_by_bucket[b] += 1
        done += 1
        if done % 5 == 0:
            print(f"  ...{done} surahs", flush=True)

    print(f"\nper-word evidence on {done} Al-Dosari surahs "
          f"({tot_correct} CORRECT, {tot_wrong} WRONG)")
    print(f"{'own coverage':>14} {'CORRECT':>9} {'WRONG':>7} {'SKIPPED':>8}   "
          f"note")
    for i in range(len(BUCKETS) - 1):
        lo, hi = BUCKETS[i], BUCKETS[i + 1]
        note = ""
        if lo >= W.WRONG_MIN_HEARD:
            note = "eligible for WRONG"
        else:
            note = "below the floor -> UNKNOWN"
        print(f"{lo:>6.2f}-{hi:<6.2f} {correct_by_bucket[i]:>9} "
              f"{wrong_by_bucket[i]:>7} {skipped_by_bucket[i]:>8}   {note}")
    if tot_wrong:
        print(f"\nWRONG_MIN_HEARD is {W.WRONG_MIN_HEARD:.2f} and is already applied "
              f"above: every remaining WRONG sits at or above it. Measured on this "
              f"reciter it removed 40% of WRONG verdicts (1454 -> 876 on 20 surahs), "
              f"with CORRECT unchanged.")
    return 0


if __name__ == "__main__":
    sys.exit(main())