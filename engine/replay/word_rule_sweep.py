#!/usr/bin/env python3
"""Sweep the per-word CORRECT/WRONG/SKIPPED rule against real model output.

The shipped rule is `bad > 0 -> WRONG`: ONE mismatched unit turns the whole word
red. On clean studio recitation that is harmless - every word comes back all
matched. But a global alignment absorbs a deleted word as substitutions in its
neighbour, so skipping one word marks the NEXT one red too, and a false red in
hide mode reveals a word the reciter did not say.

There is no neutral state in WordStatus, so partial evidence is currently forced
to WRONG. This measures whether a mismatch-ratio rule can keep skip detection
while removing the collateral, and it does so by collecting the raw per-word
counts once and then evaluating every candidate rule over them.

Nothing here changes the app. It answers: which rule, if any, is actually better.

Run:  engine/.venv-replay/bin/python engine/replay/word_rule_sweep.py
"""
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import word_verdicts as W  # noqa: E402

REPLAY = Path(__file__).resolve().parent
OUT = REPLAY / "out"
PHONEMES = os.path.join(W.W, "ordered_quran_phonemes.json")

# (dump, surah, ayat) for every recorded clip
CLIPS = [
    ("s001.json", 1, 7),
    ("s103.json", 103, 3),
    ("s108.json", 108, 3),
    ("s112.json", 112, 4),
    ("s113.json", 113, 5),
    ("s114.json", 114, 6),
]


def counts(matched, wrong, unit_word, nwords):
    """(total, ok, bad) per word - the only inputs a status rule may use."""
    out = {}
    for wi in range(nwords):
        tot = ok = bad = 0
        for k, owner in enumerate(unit_word):
            if owner != wi:
                continue
            tot += 1
            if matched[k]:
                ok += 1
            if wrong[k]:
                bad += 1
        out[wi] = (tot, ok, bad)
    return out


def collect():
    """Gather per-word counts for clean, one-word-skipped, and swapped audio."""
    table = json.load(open(PHONEMES))
    tok = W.make_tokenizer(W.load_units())
    cases = []   # (surah, ayah, kind, word, (tot, ok, bad), [(neighbour, ...)])
    for name, surah, n_ayat in CLIPS:
        path = OUT / name
        if not path.is_file():
            continue
        dump = json.load(open(path))
        exp = W.build_expected(table, tok, surah, n_ayat)
        syms = [e["symbol"] for e in dump["emissions"]]
        for a in range(1, n_ayat + 1):
            ref, unit_word, nwords = exp[a]
            cov, s, ln = W.find_window(syms, ref)
            if cov < 0.9:
                continue
            window = syms[s:s + ln]
            m0, w0, r2q, _e, _h, _n = W.align(window, ref, unit_word)
            hit = [q for q in r2q if q >= 0]
            if hit:
                window = window[min(hit):max(hit) + 1]
                m0, w0, r2q, _e, _h, _n = W.align(window, ref, unit_word)
            spans = W.word_spans(ref, unit_word, r2q, nwords)

            cases.append(
                (surah, a, "clean", None, counts(m0, w0, unit_word, nwords), set(range(nwords)))
            )

            for wi in range(nwords):
                if wi not in spans:
                    continue
                lo, hi = spans[wi]
                if hi <= lo:
                    continue
                mut = window[:lo] + window[hi:]
                m, wr, *_ = W.align(mut, ref, unit_word)
                # Every OTHER word must survive this mutation: those are the
                # ones that would turn red without the reciter having erred.
                others = set(range(nwords)) - {wi}
                cases.append(
                    (surah, a, "skip", wi, counts(m, wr, unit_word, nwords), others)
                )

                donor = (wi + 1) % nwords
                if donor == wi or donor not in spans:
                    continue
                dlo, dhi = spans[donor]
                repl = window[dlo:dhi]
                if len(repl) != hi - lo or not repl:
                    continue
                mut = window[:lo] + repl + window[hi:]
                m, wr, *_ = W.align(mut, ref, unit_word)
                cases.append(
                    (surah, a, "swap", wi, counts(m, wr, unit_word, nwords), {wi})
                )
    return cases


def status(rule, tot, ok, bad):
    """Apply a candidate rule. 'UNKNOWN' = neutral, the state WordStatus lacks."""
    return rule(tot, ok, bad)


def shipped(tot, ok, bad):
    """The rule now in PhonemeMapper.align, branch for branch.

    Coverage decides "not said"; a mismatch means WRONG; anything in between is
    UNKNOWN. The previous rule's final `else -> WRONG` called a partly covered
    word wrong, which is what made a skipped word paint its neighbour red."""
    if tot == 0:
        return "SKIPPED"
    if ok == tot:
        return "CORRECT"
    if ok * 2 < tot:
        return "SKIPPED"
    if bad > 0:
        return "WRONG"
    return "UNKNOWN"


def previous(tot, ok, bad):
    """The rule this one replaced, kept so the sweep can show the difference."""
    if tot == 0:
        return "SKIPPED"
    if bad > 0:
        return "WRONG"
    if ok == tot:
        return "CORRECT"
    if ok == 0:
        return "SKIPPED"
    return "WRONG"


def make(coverage_num, coverage_den, bad_num, bad_den, bad_min=1):
    """Coverage decides "not said"; a mismatch RATIO decides "said wrong".

    Everything in between is UNKNOWN, i.e. left unrevealed and uncoloured, which
    is the conservative reading: we do not accuse a word of error, and we do not
    pretend we heard it."""

    def f(tot, ok, bad):
        if tot == 0:
            return "SKIPPED"
        if ok == tot:
            return "CORRECT"
        if ok * coverage_den < tot * coverage_num:
            return "SKIPPED"          # barely heard -> not said
        if bad >= bad_min and bad * bad_den >= tot * bad_num:
            return "WRONG"            # enough disagreement to accuse
        return "UNKNOWN"

    return f


def evaluate(cases, name, rule):
    clean_bad = 0
    clean_tot = 0
    skip_hits = 0
    skip_tot = 0
    collateral = 0
    swap_hits = 0
    swap_tot = 0
    for _s, _a, kind, wi, c, targets in cases:
        if kind == "clean":
            for w, (tot, ok, bad) in c.items():
                clean_tot += 1
                if status(rule, tot, ok, bad) != "CORRECT":
                    clean_bad += 1
        elif kind == "skip":
            tot, ok, bad = c[wi]
            skip_tot += 1
            if status(rule, tot, ok, bad) in ("SKIPPED", "WRONG"):
                skip_hits += 1
            for w in targets:
                tot, ok, bad = c[w]
                if status(rule, tot, ok, bad) == "WRONG":
                    collateral += 1
        elif kind == "swap":
            for w in targets:
                tot, ok, bad = c[w]
                swap_tot += 1
                if status(rule, tot, ok, bad) in ("WRONG", "SKIPPED"):
                    swap_hits += 1
    return {
        "rule": name,
        "clean_ok": f"{clean_tot - clean_bad}/{clean_tot}",
        "skip_detected": f"{skip_hits}/{skip_tot}",
        "swap_detected": f"{swap_hits}/{swap_tot}",
        "collateral_red": collateral,
    }


def main() -> int:
    cases = collect()
    if not cases:
        print("no dumps found in", OUT)
        return 1
    kinds = {}
    for c in cases:
        kinds[c[2]] = kinds.get(c[2], 0) + 1
    print(f"collected {len(cases)} word observations: " + ", ".join(f"{k}={v}" for k, v in sorted(kinds.items())))
    print()

    candidates = [
        ("PREVIOUS (else->WRONG)", previous),
        ("SHIPPED (cov + UNKNOWN)", shipped),
        # coverage threshold = when a word counts as "not said"
        # bad ratio        = when partial disagreement counts as "said wrong"
        ("cov<1/3, any bad", make(1, 3, 1, 99)),
        ("cov<1/2, bad>=1/2", make(1, 2, 1, 2)),
        ("cov<1/2, bad>=1/3", make(1, 2, 1, 3)),
        ("cov<2/3, any bad", make(2, 3, 1, 99)),
    ]

    rows = [evaluate(cases, n, r) for n, r in candidates]
    w = max(len(r["rule"]) for r in rows) + 2
    print(f"{'rule'.ljust(w)}{'clean all-CORRECT':>19}{'skip found':>12}{'swap found':>12}{'collateral red':>16}")
    print("-" * (w + 59))
    for r in rows:
        print(
            f"{r['rule'].ljust(w)}{r['clean_ok']:>19}{r['skip_detected']:>12}"
            f"{r['swap_detected']:>12}{r['collateral_red']:>16}"
        )
    print()
    print("clean all-CORRECT must stay 100%: a word the reciter DID say may never")
    print("lose its CORRECT verdict, or hide mode stops revealing it.")
    print("collateral red = a word flagged WRONG that the reciter got right.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
