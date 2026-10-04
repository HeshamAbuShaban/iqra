#!/usr/bin/env python3
"""Per-word verdict tests: the mistake-detection feature, with labels.

Covers 28/28 lock accuracy, but NOT the per-word CORRECT/WRONG/SKIPPED
verdicts - which is the feature Tarteel sells and which has never been
measured. The clips are synthesised from a real token dump by mutating the
emission stream, so every case has a known-correct label and needs no
recording and no download:

  clean   -> every word CORRECT
  skipped -> one word's phonemes deleted  -> that word SKIPPED, rest CORRECT
  wrong   -> one word's phonemes swapped for another word's -> that word WRONG

This validates the DP verdict machine (PhonemeMapper.align) directly.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
W = os.path.join(HERE, "..", "shootout", "weights", "zipformer")


def load_units():
    units = {}
    with open(os.path.join(W, "tokens.txt")) as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            sym, _i = line.rstrip().rsplit(" ", 1)
            units[sym] = int(_i)
    return units


def make_tokenizer(units):
    ordered = sorted(units.keys(), key=len, reverse=True)

    def tok(s):
        out, i = [], 0
        while i < len(s):
            for u in ordered:
                if s.startswith(u, i):
                    out.append(u)
                    i += len(u)
                    break
            else:
                i += 1
        return out

    return tok


def build_expected(table, tok, surah, n_ayat):
    exp = {}
    for a in range(1, n_ayat + 1):
        units, unit_word = [], []
        for wi, word in enumerate(table["%d:%d" % (surah, a)]["aya_phonemes_list"]):
            for u in tok(word):
                units.append(u)
                unit_word.append(wi)
        exp[a] = (units, unit_word, len(table["%d:%d" % (surah, a)]["aya_phonemes_list"]))
    return exp


def align_affine(query, ref, unit_word):
    """Gotoh affine-gap alignment.

    A plain edit DP cannot tell a SKIPPED word from a MISPRONOUNCED one: with
    equal costs and ties broken toward substitution, deleting a 3-phoneme word
    is paid for as 3 substitutions smeared across the PRECEDING words, so the
    word the user actually skipped is never reported and the words they
    recited correctly turn red.

    Affine gaps fix it: one contiguous gap costs GAP_OPEN + (k-1)*GAP_EXTEND,
    which is far cheaper than k substitutions, so the gap wins for a run while
    a single mismatched phoneme still costs SUB and stays a substitution.
    Costs are scaled integers: SUB=10, GAP_OPEN=12, GAP_EXTEND=1.
    """
    SUB, GO, GE = 10, 12, 1
    n, m = len(ref), len(query)
    nwords = (max(unit_word) + 1) if unit_word else 0
    if not n or not m:
        return [False] * n, [False] * n, [-1] * n, [-1] * m, 0, nwords

    INF = 1 << 29
    M = [[INF] * (m + 1) for _ in range(n + 1)]
    D = [[INF] * (m + 1) for _ in range(n + 1)]  # expected unit deleted (user skipped it)
    I = [[INF] * (m + 1) for _ in range(n + 1)]  # extra query symbol
    # predecessor STATE for each cell, so the traceback is exact
    pm = [[0] * (m + 1) for _ in range(n + 1)]
    pd = [[0] * (m + 1) for _ in range(n + 1)]
    pi = [[0] * (m + 1) for _ in range(n + 1)]
    M[0][0] = 0
    for i in range(1, n + 1):
        D[i][0] = GO + (i - 1) * GE
        pd[i][0] = 1
    # Free start in the query: the emission slice always begins with material
    # that is not part of this ayah (previous ayah tail, istiaadha, hesitation).
    # With a global start the DP must pay for that leading noise by burning
    # expected units against it, which misreports the first word. Letting the
    # query begin anywhere is what makes the alignment semi-global.
    for j in range(0, m + 1):
        I[0][j] = 0
        pi[0][j] = 2
    for i in range(1, n + 1):
        ri = ref[i - 1]
        Mi, Mp, Dp, Ip = M[i], M[i - 1], D[i], D[i]
        Di, Ip_row, pmi, pdi, pii = D[i - 1], I[i - 1], pm[i], pd[i], pi[i]
        for j in range(1, m + 1):
            # M
            best, src = M[i - 1][j - 1], 0
            if D[i - 1][j - 1] < best:
                best, src = D[i - 1][j - 1], 1
            if I[i - 1][j - 1] < best:
                best, src = I[i - 1][j - 1], 2
            M[i][j] = best + (0 if ri == query[j - 1] else SUB)
            pm[i][j] = src
            # D: expected unit deleted
            if M[i - 1][j] + GO <= D[i - 1][j] + GE:
                D[i][j] = M[i - 1][j] + GO
                pd[i][j] = 0
            else:
                D[i][j] = D[i - 1][j] + GE
                pd[i][j] = 1
            # I: extra query symbol
            if M[i][j - 1] + GO <= I[i][j - 1] + GE:
                I[i][j] = M[i][j - 1] + GO
                pi[i][j] = 0
            else:
                I[i][j] = I[i][j - 1] + GE
                pi[i][j] = 2

    matched = [False] * n
    wrong = [False] * n
    ref_to_query = [-1] * n
    emit_word = [-1] * m
    hits = 0
    # Free end in the query so trailing noise cannot distort the alignment.
    state, i, j = 0, n, m
    end = min(M[n][j], D[n][j], I[n][j])
    if end == D[n][j]:
        state = 1
    elif end == I[n][j]:
        state = 2
    while i > 0 or j > 0:
        if state == 0:
            if i == 0 or j == 0:
                break
            ref_to_query[i - 1] = j - 1
            if ref[i - 1] == query[j - 1]:
                matched[i - 1] = True
                hits += 1
            else:
                wrong[i - 1] = True
            emit_word[j - 1] = unit_word[i - 1]
            state = pm[i][j]
            i -= 1
            j -= 1
        elif state == 1:
            if i == 0:
                break
            state = pd[i][j]
            i -= 1
        else:
            if j == 0:
                break
            state = pi[i][j]
            j -= 1
    return matched, wrong, ref_to_query, emit_word, hits, nwords


def align(query, ref, unit_word):
    """Same DP as PhonemeMapper.align.
    Returns (matched, wrong, ref_to_query, emit_word, hits, nwords)."""
    n, m = len(ref), len(query)
    nwords = (max(unit_word) + 1) if unit_word else 0
    if not n or not m:
        # Must match the normal return's arity AND element types. This used to
        # return a stale 5-tuple, so any mutation that emptied the query raised
        # "not enough values to unpack" and aborted the whole run - silently
        # leaving the rest of the surah unevaluated.
        return [False] * n, [False] * n, [-1] * n, [-1] * m, 0, nwords
    prev = list(range(m + 1))
    dirs = []
    for i in range(1, n + 1):
        cur = [i] + [0] * m
        row = bytearray(m + 1)
        for j in range(1, m + 1):
            sub = prev[j - 1] + (0 if ref[i - 1] == query[j - 1] else 1)
            dele = prev[j] + 1
            ins = cur[j - 1] + 1
            best, d = sub, 0
            if dele < best:
                best, d = dele, 1
            if ins < best:
                best, d = ins, 2
            cur[j] = best
            row[j] = d
        dirs.append(row)
        prev = cur
    matched = [False] * n
    wrong = [False] * n
    ref_to_query = [-1] * n
    emit_word = [-1] * m
    i, j, hits = n, m, 0
    while i > 0 or j > 0:
        if i > 0 and j > 0 and dirs[i - 1][j] == 0:
            ref_to_query[i - 1] = j - 1
            if ref[i - 1] == query[j - 1]:
                matched[i - 1] = True
                hits += 1
            else:
                wrong[i - 1] = True
            emit_word[j - 1] = unit_word[i - 1]
            i -= 1
            j -= 1
        elif i > 0 and j > 0 and dirs[i - 1][j] == 1:
            i -= 1
        elif i > 0 and j > 0 and dirs[i - 1][j] == 2:
            j -= 1
        elif i > 0:
            i -= 1
        else:
            j -= 1
    return matched, wrong, ref_to_query, emit_word, hits, nwords


# Fraction of a word's own units that must be heard before WRONG is claimed.
# Mirrors WRONG_MIN_HEARD_COVERAGE in PhonemeMapper.kt. Measured on 20 Al-Dosari
# surahs: 40% of WRONG verdicts sat below it.
WRONG_MIN_HEARD = 0.80


def statuses_from(matched, wrong, unit_word, nwords):
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
        if tot == 0:
            v = "SKIPPED"
        elif ok == tot:
            v = "CORRECT"
        elif ok * 2 < tot:
            v = "SKIPPED"          # barely covered: not said
        elif bad > 0 and ok >= tot * WRONG_MIN_HEARD:
            v = "WRONG"          # contradicted, and most of the word was heard
        elif bad > 0:
            v = "UNKNOWN"        # contradicted, but too little of it was heard
        else:
            # Partly covered with nothing contradicted: no verdict. This used to
            # be WRONG, which made a skipped word paint its neighbour red.
            v = "UNKNOWN"
        out[wi] = v
    return out


def word_spans(ref, unit_word, ref_to_query, nwords):
    """Exact word -> (lo,hi) window span, from the clean alignment itself.

    Unmatched reference units still occupy a position, so they are placed at
    the midpoint of their neighbours rather than dropped; dropping them made a
    word look one unit short and corrupted the mutation labels.
    """
    pos = []
    last = 0.0
    for k in range(len(ref)):
        q = ref_to_query[k]
        if q < 0:
            q = last + 0.5
        last = float(q)
        pos.append(last)
    idx = {}
    for k, wi in enumerate(unit_word):
        q = pos[k]
        if wi not in idx:
            idx[wi] = [q, q]
        else:
            idx[wi][0] = min(idx[wi][0], q)
            idx[wi][1] = max(idx[wi][1], q)
    spans = {}
    for wi, (lo, hi) in idx.items():
        spans[wi] = (int(lo), int(hi) + 1)
    # close each word's span up to the next word's start so no token is orphaned
    present = sorted(spans.items(), key=lambda t: t[1][0])
    for i, (wi, (lo, hi)) in enumerate(present):
        nxt = present[i + 1][1][0] if i + 1 < len(present) else hi
        spans[wi] = (lo, max(hi, nxt))
    return spans


def find_window(query, ref):
    """Best-matching contiguous window: (coverage, start, length)."""
    L = len(ref)
    best = (0.0, 0, 0)
    for s in range(0, max(1, len(query) - L)):
        sl = query[s:s + L + 12]
        _m, _w, _r2q, _e, hits, _n = align(sl, ref, list(range(L)))
        c = hits / float(L)
        if c > best[0]:
            best = (c, s, L + 12)
    return best


def main():
    dump = json.load(open(sys.argv[1]))
    table = json.load(open(os.path.join(W, "ordered_quran_phonemes.json")))
    surah = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    n_ayat = int(sys.argv[3]) if len(sys.argv) > 3 else 7

    tok = make_tokenizer(load_units())
    exp = build_expected(table, tok, surah, n_ayat)
    syms = [e["symbol"] for e in dump["emissions"]]

    failures = 0
    total = 0
    for a in range(1, n_ayat + 1):
        ref, unit_word, nwords = exp[a]
        cov, s, ln = find_window(syms, ref)
        if cov < 0.9:
            print("sura %d:%d  SKIPPED (window coverage %.2f)" % (surah, a, cov))
            continue
        window = syms[s:s + ln]

        # Word spans come from the CLEAN alignment itself, so the labels are
        # exact rather than guessed by a separate scan.
        ALIGN = globals().get("ALIGN", align)
        m0, w0, r2q, _e0, _h0, _n0 = ALIGN(window, ref, unit_word)

        # Trim the window to the extent actually matched. The app rebases the
        # emission slice at every lock move, so it never carries the previous
        # ayah's tail; a padded window would let word 0's units match inside
        # that foreign material and corrupt the labels.
        hit = [q for q in r2q if q >= 0]
        if hit:
            window = window[min(hit):max(hit) + 1]
            m0, w0, r2q, _e0, _h0, _n0 = ALIGN(window, ref, unit_word)
        spans = word_spans(ref, unit_word, r2q, nwords)

        # --- case 1: clean
        st = statuses_from(m0, w0, unit_word, nwords)
        bad = [w for w, v in st.items() if v != "CORRECT"]
        total += nwords
        if bad:
            failures += len(bad)
            print("sura %d:%d clean   : %d/%d not CORRECT  %s" % (surah, a, len(bad), nwords, st))
        else:
            print("sura %d:%d clean   : all %d words CORRECT" % (surah, a, nwords))

        # --- case 2: skipped word (delete its phonemes)
        detected = 0
        for wi in range(nwords):
            if wi not in spans:
                continue
            lo, hi = spans[wi]
            if hi <= lo:
                continue
            mut = window[:lo] + window[hi:]
            m, wr, _r2q, _e, _h, _n = ALIGN(mut, ref, unit_word)
            st = statuses_from(m, wr, unit_word, nwords)
            # Collateral means another word being ACCUSED - WRONG or SKIPPED.
            # UNKNOWN is the neutral reading and resolves to CORRECT as more
            # audio arrives, so demanding every other word be CORRECT here
            # failed the very case this rule was written to fix.
            others = [w for w, v in st.items() if w != wi and v in ("WRONG", "SKIPPED")]
            total += 1
            if st.get(wi) == "SKIPPED" and not others:
                detected += 1
            else:
                failures += 1
                print("sura %d:%d skip w%-2d: got %s" % (surah, a, wi, st))
        print("sura %d:%d skipped: %d/%d detected cleanly (skip + no collateral)" % (surah, a, detected, nwords))

        # --- case 3: wrong word (swap in another word's phonemes)
        flagged = 0
        tested = 0
        for wi in range(nwords):
            donor = (wi + 1) % nwords
            # A one-word ayah makes the donor the word itself, so the mutation
            # is a no-op and "not flagged" is vacuous rather than a failure.
            if donor == wi:
                continue
            if wi not in spans or donor not in spans:
                continue
            lo, hi = spans[wi]
            dlo, dhi = spans[donor]
            repl = window[dlo:dhi]
            if len(repl) != hi - lo or not repl:
                continue
            mut = window[:lo] + repl + window[hi:]
            m, wr, _r2q, _e, _h, _n = ALIGN(mut, ref, unit_word)
            st = statuses_from(m, wr, unit_word, nwords)
            tested += 1
            total += 1
            if st.get(wi) in ("WRONG", "SKIPPED"):
                flagged += 1
            else:
                failures += 1
                print("sura %d:%d wrong w%-2d: got %s" % (surah, a, wi, st))
        print("sura %d:%d wrong  : %d/%d substitutions flagged" % (surah, a, flagged, tested))

    print()
    print("WORD-VERDICT CASES: %d checked, %d failed" % (total, failures))


if __name__ == "__main__":
    main()
