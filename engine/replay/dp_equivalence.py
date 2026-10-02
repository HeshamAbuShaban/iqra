#!/usr/bin/env python3
"""Prove the DP optimisation is value-identical to what the app ships today.

Context: the lock policy calls `PhonemeMapper.align` roughly 4 times a second per
candidate ayah - up to a dozen candidates - and that DP was the hottest loop in
the app. A proposal arrived to speed it up with a BANDED dp. That version was
rejected: with `off = max(n, len) + 1` the band provably contains the whole
grid, so it computed the full table while its fill and its backtrace disagreed on
the column offset by `i`, and its `cur[-off] = i` boundary write threw on the
first row. Its own comment also advertised a coverage-only fast path that was
never implemented (`val full` was assigned and unused).

This file establishes what CAN be optimised safely, and proves it exhaustively:

  reference  the shipped algorithm, transcribed line for line
  optimised  int-interned units, rolling dp rows, flat ByteArray direction table

No banding. A provably safe band half-width for edit distance is bounded by the
trivial alignment cost, max(n, len), which spans the entire grid - so banding
buys nothing while making the backtrace indices fiddly enough to get wrong. The
real wins are the byte-wide direction table, the two rolling rows, and replacing
Arabic string comparison in the inner loop with an int compare.

Run:  engine/.venv-replay/bin/python engine/replay/dp_equivalence.py
"""
import random
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "engine" / "replay"))


# ---------------------------------------------------------------- reference
def align_reference(emitted, flat, word_of):
    """Transcription of the shipped PhonemeMapper.align, exactly as written."""
    n, ln = len(emitted), len(flat)
    matched = [False] * ln
    wrong = [False] * ln
    emit_word = [-1] * n
    units_matched = 0
    if ln == 0 or n == 0:
        return matched, wrong, emit_word, units_matched, ln

    dp = [[0] * (ln + 1) for _ in range(n + 1)]
    dir_ = [[0] * (ln + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        dp[i][0] = i
    for j in range(ln + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, ln + 1):
            cost = 0 if emitted[i - 1] == flat[j - 1] else 1
            sub = dp[i - 1][j - 1] + cost
            dele = dp[i - 1][j] + 1
            ins = dp[i][j - 1] + 1
            best, d = sub, 0
            if dele < best:
                best, d = dele, 1
            if ins < best:
                best, d = ins, 2
            dp[i][j] = best
            dir_[i][j] = d

    i, j = n, ln
    while i > 0 or j > 0:
        if i > 0 and j > 0 and dir_[i][j] == 0:
            if emitted[i - 1] == flat[j - 1]:
                matched[j - 1] = True
                units_matched += 1
            else:
                wrong[j - 1] = True
            emit_word[i - 1] = word_of[j - 1]
            i -= 1
            j -= 1
        elif i > 0 and j > 0 and dir_[i][j] == 1:
            i -= 1
        elif i > 0 and j > 0 and dir_[i][j] == 2:
            j -= 1
        elif i > 0:
            i -= 1
        else:
            j -= 1
    return matched, wrong, emit_word, units_matched, ln


# ---------------------------------------------------------------- optimised
def build_ids(flat):
    ids = {}
    for u in flat:
        if u not in ids:
            ids[u] = len(ids)
    return ids


def align_optimised(emitted, flat, word_of, ids=None):
    """int interning + rolling rows + flat byte direction table. No banding."""
    n, ln = len(emitted), len(flat)
    matched = [False] * ln
    wrong = [False] * ln
    emit_word = [-1] * n
    units_matched = 0
    if ln == 0 or n == 0:
        return matched, wrong, emit_word, units_matched, ln

    if ids is None:
        ids = build_ids(flat)
    ref = [ids[u] for u in flat]                 # every expected unit has an id
    # An emitted symbol outside this ayah's vocabulary becomes -1, which can
    # never equal a ref id, so it is a substitution exactly as the string
    # compare was. Two such symbols also compare equal to each other, but they
    # are only ever compared against ref, never against each other.
    qry = [ids.get(s, -1) for s in emitted]

    width = ln + 1
    dir_ = bytearray((n + 1) * width)
    prev = list(range(ln + 1))                  # dp[0][j] = j
    cur = [0] * width
    for i in range(1, n + 1):
        cur[0] = i                               # dp[i][0] = i
        qi = qry[i - 1]
        row = i * width
        for j in range(1, ln + 1):
            cost = 0 if qi == ref[j - 1] else 1
            sub = prev[j - 1] + cost
            dele = prev[j] + 1
            ins = cur[j - 1] + 1
            best, d = sub, 0
            if dele < best:
                best, d = dele, 1
            if ins < best:
                best, d = ins, 2
            cur[j] = best
            dir_[row + j] = d
        prev, cur = cur, prev

    i, j = n, ln
    while i > 0 or j > 0:
        if i > 0 and j > 0 and dir_[i * width + j] == 0:
            if qry[i - 1] == ref[j - 1]:
                matched[j - 1] = True
                units_matched += 1
            else:
                wrong[j - 1] = True
            emit_word[i - 1] = word_of[j - 1]
            i -= 1
            j -= 1
        elif i > 0 and j > 0 and dir_[i * width + j] == 1:
            i -= 1
        elif i > 0 and j > 0 and dir_[i * width + j] == 2:
            j -= 1
        elif i > 0:
            i -= 1
        else:
            j -= 1
    return matched, wrong, emit_word, units_matched, ln


# ------------------------------------------------------- the extracted kernel
def ref_to_query(qry, ref):
    """UnitAligner.refToQuery - the kernel's real output, and the only traceback.

    PhonemeMapper now derives matched/wrong/emitWord/coverage from this, so the
    derivation is checked against the reference bookkeeping on every alignment
    rather than trusted.
    """
    n, ln = len(qry), len(ref)
    out = [-1] * ln
    if ln == 0 or n == 0:
        return out
    width = ln + 1
    dir_ = bytearray((n + 1) * width)
    prev = list(range(ln + 1))
    cur = [0] * width
    for i in range(1, n + 1):
        cur[0] = i
        qi = qry[i - 1]
        row = i * width
        for j in range(1, ln + 1):
            c = 0 if qi == ref[j - 1] else 1
            sub = prev[j - 1] + c
            dele = prev[j] + 1
            ins = cur[j - 1] + 1
            best, d = sub, 0
            if dele < best:
                best, d = dele, 1
            if ins < best:
                best, d = ins, 2
            cur[j] = best
            dir_[row + j] = d
        prev, cur = cur, prev
    i, j = n, ln
    while i > 0 or j > 0:
        if i > 0 and j > 0:
            d = dir_[i * width + j]
            if d == 0:
                out[j - 1] = i - 1
                i -= 1
                j -= 1
            elif d == 1:
                i -= 1
            elif d == 2:
                j -= 1
            else:
                return out
        elif i > 0:
            i -= 1
        else:
            j -= 1
    return out


def derive(r2q, qry, ref, word_of):
    """What PhonemeMapper now derives from the single traceback."""
    matched = [False] * len(ref)
    wrong = [False] * len(ref)
    emit_word = [-1] * len(qry)
    units = 0
    for j, qi in enumerate(r2q):
        if qi < 0:
            continue
        if qry[qi] == ref[j]:
            matched[j] = True
            units += 1
        else:
            wrong[j] = True
        # An aligned expected unit stamps its word onto the emission that
        # satisfied it, including a substitution - the emission IS this word's
        # sound, just the wrong one.
        emit_word[qi] = word_of[j]
    return matched, wrong, emit_word, units


def digest(cases):
    """FNV-1a over (len(q), len(ref), refToQuery...). Mirrors UnitAligner.digest.

    This is the seam that lets CI run the real Kotlin: both sides walk the same
    generated vector space and fold it to one number, so a behavioural
    difference is one failing assertion instead of a transcript to read.
    """
    h = 0xCBF29CE484222325
    M = (1 << 64) - 1
    P = 0x100000001B3

    def mix_int(v):
        nonlocal h
        for k in range(4):
            h = ((h ^ ((v >> (8 * k)) & 0xFF)) * P) & M

    for q, r, r2q in cases:
        mix_int(len(q))
        mix_int(len(r))
        for v in r2q:
            mix_int(v)
    return h


def canonical_cases():
    """Identical walk to UnitAligner.canonicalCases()."""
    cases = []

    def seq(pat, ln):
        return [(pat >> i) & 1 for i in range(ln)]

    for lq in range(5):
        for q in range(1 << lq):
            query = seq(q, lq)
            for lr in range(5):
                for r in range(1 << lr):
                    ref = seq(r, lr)
                    cases.append((query, ref, ref_to_query(query, ref)))
    for ln in range(1, 49):
        ref = [i % 2 for i in range(ln)]
        q1 = [ref[i + 1] for i in range(ln - 1)]
        cases.append((q1, ref, ref_to_query(q1, ref)))
        sub = list(ref)
        sub[ln // 2] = 1 - sub[ln // 2]
        cases.append((sub, ref, ref_to_query(sub, ref)))
        noisy = [0, 0, 0] + list(ref) + [0, 0, 0]
        cases.append((noisy, ref, ref_to_query(noisy, ref)))
        rev = [ref[ln - 1 - i] for i in range(ln)]
        cases.append((rev, ref, ref_to_query(rev, ref)))
    return cases


def compare(emitted, flat, word_of, label, failures, ids=None):
    a = align_reference(emitted, flat, word_of)
    b = align_optimised(emitted, flat, word_of, ids)
    if ids is None:
        ids = build_ids(flat)
    ref_ids = [ids[u] for u in flat]
    qry_ids = [ids[u] if u in ids else -1 for u in emitted]
    d = derive(ref_to_query(qry_ids, ref_ids), qry_ids, ref_ids, word_of)
    if d != a[:4]:
        names = ("matched", "wrong", "emitWord", "unitsMatched")
        diff = [names[i] for i in range(4) if d[i] != a[i]]
        failures.append(f"{label}: derivation from refToQuery differs on {diff} "
                        f"(direct={a[3]} derived={d[3]})")
    if a != b:
        for k, name in enumerate(("matched", "wrong", "emitWord", "unitsMatched", "unitsTotal")):
            if a[k] != b[k]:
                failures.append(f"{label}: {name} differs (ref={a[k]!r} opt={b[k]!r})")
        return False
    return True


def main() -> int:
    failures = []
    checked = 0

    # 1. Exhaustive over a 2-symbol alphabet, both sides length 0..4. This is
    #    what catches boundary and tie-breaking differences: the reference
    #    prefers substitution, then deletion, then insertion, and any deviation
    #    in that order changes the traceback even when the edit distance ties.
    alpha = ["a", "b"]
    seqs = [[]]
    for L in range(1, 5):
        seqs += [[alpha[(k >> b) & 1] for b in range(L)] for k in range(1 << L)]
    for e in seqs:
        for f in seqs:
            w = list(range(len(f)))
            if compare(e, f, w, f"exhaustive {e}/{f}", failures):
                checked += 1

    # 2. A 3-symbol alphabet at higher length, to exercise real tie density,
    #    plus symbols that appear ONLY in the emission (the -1 path).
    rng = random.Random(20260930)
    alpha3 = ["a", "b", "c", "x", "y"]
    for _ in range(4000):
        f = [rng.choice(alpha3) for _ in range(rng.randint(0, 22))]
        e = [rng.choice(alpha3) for _ in range(rng.randint(0, 34))]
        # force emission-only symbols to appear
        for _ in range(rng.randint(0, 4)):
            e.insert(rng.randrange(len(e) + 1), rng.choice(["x", "y"]))
        w = [0] * len(f)
        for idx in range(len(w)):
            w[idx] = idx // 3
        if compare(e, f, w, f"random {len(e)}/{len(f)}", failures):
            checked += 1

    # 3. Asymmetric shapes like the app's real data: expected 5..45 units,
    #    emission slice a few dozen to a few hundred tokens.
    for _ in range(400):
        f = [rng.choice(alpha3[:3]) for _ in range(rng.randint(5, 45))]
        e = [rng.choice(alpha3) for _ in range(rng.randint(5, 320))]
        w = [i // 4 for i in range(len(f))]
        if compare(e, f, w, "asymmetric", failures):
            checked += 1

    # 4. Real recorded data, through the project's own expected-unit builder.
    try:
        import word_verdicts as W
        import json
        import os
        table = json.load(open(os.path.join(W.W, "ordered_quran_phonemes.json")))
        tok = W.make_tokenizer(W.load_units())
        real = 0
        for dump, surah, n_ayat in [("s001", 1, 7), ("s103", 103, 3), ("s108", 108, 3),
                                    ("s112", 112, 4), ("s113", 113, 5), ("s114", 114, 6)]:
            path = ROOT / "engine" / "replay" / "out" / f"{dump}.json"
            if not path.is_file():
                continue
            syms = [e["symbol"] for e in json.load(open(path))["emissions"]]
            exp = W.build_expected(table, tok, surah, n_ayat)
            for a in range(1, n_ayat + 1):
                flat, uw, _nwords = exp[a]
                ids = build_ids(flat)
                # whole emission slice, plus every window of realistic length
                for q in (syms, syms[:40], syms[20:120], syms[-60:]):
                    if compare(q, flat, uw, f"real {dump}:{a}", failures, ids):
                        real += 1
                        checked += 1
        print(f"real-data alignments compared: {real}")
    except Exception as exc:  # noqa: BLE001
        print(f"real-data section skipped: {exc}")

    # 5. Quantify the claim instead of asserting it. The input is built ONCE
    #    outside the timed region, warmed up, and the median of many rounds is
    #    reported alongside the best. An earlier version of this benchmark built
    #    the emission inside the loop and reported a single best-of, and the
    #    answer moved between 1.08x and 1.26x run to run - which is the same
    #    class of unverified claim this project keeps paying for.
    def bench(fn, flat, word_of, ids, emitted, reps=200):
        if fn is align_optimised:
            for _ in range(20):
                fn(emitted, flat, word_of, ids)
        else:
            for _ in range(20):
                fn(emitted, flat, word_of)
        times = []
        for _ in range(reps):
            t0 = time.perf_counter()
            if fn is align_optimised:
                fn(emitted, flat, word_of, ids)
            else:
                fn(emitted, flat, word_of)
            times.append(time.perf_counter() - t0)
        times.sort()
        return times[0], times[len(times) // 2]

    for label, ln, n in (("45x320", 45, 320), ("10x41", 10, 41), ("5x120", 5, 120)):
        flat_b = [("u%d" % (i % 61)) for i in range(ln)]
        word_b = [i // 5 for i in range(ln)]
        ids_b = build_ids(flat_b)
        emitted_b = [("u%d" % (i % 97)) for i in range(n)]
        ref_best, ref_med = bench(align_reference, flat_b, word_b, ids_b, emitted_b)
        opt_best, opt_med = bench(align_optimised, flat_b, word_b, ids_b, emitted_b)
        print(f"  {label:8} reference {ref_med * 1e3:6.3f} ms (best {ref_best * 1e3:6.3f})"
              f"   optimised {opt_med * 1e3:6.3f} ms (best {opt_best * 1e3:6.3f})"
              f"   {ref_med / opt_med:.2f}x median")

    if failures:
        print("\nFAIL")
        for f in failures[:20]:
            print(f"  - {f}")
        if len(failures) > 20:
            print(f"  ... and {len(failures) - 20} more")
        return 1
    canon = canonical_cases()
    d = digest(canon)
    signed = d - (1 << 64) if d >> 63 else d
    print(f"canonical kernel cases : {len(canon)}")
    print(f"KERNEL DIGEST (hex)    : 0x{d:016x}")
    print(f"KERNEL DIGEST (signed) : {signed}")
    print("\nPASS: optimised DP is value-identical to the shipped DP "
          "on coverage, matched, wrong and emitWord")
    return 0


if __name__ == "__main__":
    sys.exit(main())
