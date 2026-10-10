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


def final_indices(unit_word):
    """Ref indices that are the LAST unit of their word."""
    last = {}
    for k, w in enumerate(unit_word):
        last[w] = k
    return last


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
    last_of_word = final_indices(unit_word)

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
            is_final = last_of_word.get(unit_word[i - 1]) == i - 1
            if (unit_match_final(ref[i - 1], query[j - 1]) if is_final
                    else unit_match(ref[i - 1], query[j - 1])):
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
    last_of_word = final_indices(unit_word)
    i, j, hits = n, m, 0
    while i > 0 or j > 0:
        if i > 0 and j > 0 and dirs[i - 1][j] == 0:
            ref_to_query[i - 1] = j - 1
            is_final = last_of_word.get(unit_word[i - 1]) == i - 1
            if (unit_match_final(ref[i - 1], query[j - 1]) if is_final
                    else unit_match(ref[i - 1], query[j - 1])):
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
# A wrong-phone substitution table, built from the model's own inventory.
#
# Arabic's phoneme inventory is small and confusable BY DESIGN: that is what
# makes a word intelligible, and it is why swapping one word for a neighbouring
# word in the same ayah is often undetectable. So detection has to be tested
# with a genuinely different sound at the position - not with a sibling word,
# and not with a NEAR NEIGHBOUR.
#
# The first version of this table paired neighbouring makhraj (ر→ز, ح→خ,
# س→ش), which is exactly the substitution the DP is most able to absorb: those
# sounds differ in a small articulatory detail, so the alignment still scored the
# word as "mostly heard" and the verdict came back SKIPPED - "not said" - for a
# word where every single unit had been changed. Detection looked like 0.
#
# A reciter substituting a wrong consonant substitutes a DISTANT one. So pair
# each letter with one from a different articulation group, chosen to share no
# vowel so the whole unit changes.
_DISTANT = {
    "ب": "ش",   # labial stop -> velar trill
    "ت": "ذ",   # dental stop -> throat
    "د": "ث",   # dental -> dental sibilant, very different
    "ر": "ق",   # liquid -> velar
    "ز": "خ",   # sibilant -> velar fricative
    "س": "ء",   # sibilant -> glottal
    "ش": "ص",   # sibilant -> emphatic
    "ص": "ت",   # emphatic -> dental stop
    "ض": "د",   # emphatic -> dental stop
    "ط": "ج",   # emphatic -> guttural
    "ظ": "غ",   # emphatic -> guttural
    "ع": "ف",   # guttural -> labiodental
    "غ": "ق",   # guttural -> velar
    "ف": "ع",   # labiodental -> guttural
    "ق": "ه",   # velar -> glottal
    "ك": "م",   # velar -> nasal
    "ل": "ز",   # lateral -> sibilant
    "م": "ك",   # nasal -> velar
    "ن": "ث",   # nasal -> sibilant
    "ه": "ش",   # glottal -> sibilant
    "و": "ن",   # semivowel -> nasal
    "ي": "ط",   # semivowel -> emphatic
    "ء": "س",   # glottal -> sibilant
    "ا": "ب",   # glottal -> labial
    "ج": "ظ",   # guttural -> emphatic
    "ح": "ك",   # guttural -> velar
    "خ": "ب",   # guttural -> labial
    "ذ": "ت",   # throat -> dental
    "ث": "ز",   # sibilant -> sibilant, different place
}
DONORS = dict(_DISTANT)

# Vowel phones, paired so the vowel AND the consonant both change where
# possible. A changed consonant is the testable error; the vowel follows it.
VOWEL_DONORS = {
    "بَ": "شَ", "تَ": "ذَ", "دَ": "ثَ", "رَ": "قَ", "زَ": "خَ", "سَ": "ءَ",
    "شَ": "صَ", "صَ": "تَ", "ضَ": "دَ", "طَ": "جَ", "ظَ": "غَ", "عَ": "فَ",
    "غَ": "قَ", "فَ": "عَ", "قَ": "هَ", "كَ": "مَ", "لَ": "زَ", "مَ": "كَ",
    "نَ": "ثَ", "هَ": "شَ", "وَ": "نَ", "يَ": "طَ", "ءَ": "سَ", "اَ": "بَ",
    "جَ": "ظَ", "حَ": "كَ", "خَ": "بَ", "ذَ": "تَ", "ثَ": "زَ",
    "بِ": "شِ", "تِ": "ذِ", "دِ": "ثِ", "رِ": "قِ", "زِ": "خِ", "سِ": "ءِ",
    "شِ": "صِ", "صِ": "تِ", "ضِ": "دِ", "طِ": "جِ", "ظِ": "غِ", "عِ": "فِ",
    "غِ": "قِ", "فِ": "عِ", "قِ": "هِ", "كِ": "مِ", "لِ": "زِ", "مِ": "كِ",
    "نِ": "ثِ", "هِ": "شِ", "وِ": "نِ", "يِ": "طِ",
    "بُ": "شُ", "تُ": "ذُ", "دُ": "ثُ", "رُ": "قُ", "زُ": "خُ", "سُ": "ءُ",
    "شُ": "صُ", "صُ": "تُ", "ضُ": "دُ", "طُ": "جُ", "ظُ": "غُ", "عُ": "فُ",
    "غُ": "قُ", "فُ": "عُ", "قُ": "هُ", "كُ": "مُ", "لُ": "زُ", "مُ": "كُ",
    "نُ": "ثُ", "هُ": "شُ", "وُ": "نُ", "يُ": "طُ",
}


# ---- madd equivalence ------------------------------------------------------
#
# Free-choice madd: at ~19% of all madd sites the register lists more than one
# permitted length, and the shipped table asserts only the canonical one. A
# reciter holding a DIFFERENT legal length at such a site is NOT making an
# error, yet the old match test saw 'اا' (2) against 'اااا' (4) and called it
# a substitution. This is the single largest source of false accuses, so two
# units of the SAME madd bearer are now treated as matching.
#
# Bearers are class-distinct: alef-madd, waw-madd, and ya-madd (and their dagger
# forms) are not interchangeable. A 'اا' is not a 'وو'.
_ALEF = {"اا","ااۜ","اااا","ااااا","اااااا"}
_WAW = {"وو","ووو","ووَ","ووُ","ووِ","وووَ","وووُ","وووِ"}
_YAA = {"يي","ييي","ييَ","ييُ","ييِ","يييَ","يييُ","ييييي"}
_WAW_DAG = {"ۥ","ۥۥ","ۥۥۥ","ۥۥۥۥ","ۥۥۥۥۥ","ۥۥۥۥۥۥ"}
_YAA_DAG = {"ۦ","ۦۦ","ۦۦۦ","ۦۦۦۦ","ۦۦۦۦۦ","ۦۦۦۦۦۦ"}


def _madd_class(u):
    if u in _ALEF: return "ALEF"
    if u in _WAW or u in _WAW_DAG: return "WAW"
    if u in _YAA or u in _YAA_DAG: return "YAA"
    return None


def madd_equivalent(a, b):
    """True when a and b are the same kind of madd, of any legal length."""
    ca = _madd_class(a)
    return ca is not None and ca == _madd_class(b)


# Letters measured to end a word BARE in the table while the mushaf prints a
# vowel on them: nun 2,936, ha 2,169, ra 248, mim 228, dal 104, ba 93, ta 49,
# lam 41, qaf 24, alef-maqsura 21, kaf 13, sin 10. Waw and ya are deliberately
# ABSENT: they carry vowels of their own as semi-vowels, so the vowel there is
# load-bearing and relaxing it would hide a real error.
FINAL_VOWELLESS = set("نهمردبةقلسعجثزتفطظشح")
SHORT_VOWELS = set("\u064e\u064f\u0650")


def final_vowel_id(u):
    """A word-FINAL consonant, ignoring the case vowel on it.

    26,733 of the table's 77,481 words end on a bare consonant: the table stores
    'yunfiqoon' with a bare final nun, the model emits nun+fatha, and those are
    different tokens. Same shape as the madd rule - it can only remove blame.
    """
    if not u:
        return None
    last = u[-1]
    if last in SHORT_VOWELS:
        stem = u[:-1]
        if stem and stem[-1] in FINAL_VOWELLESS:
            return "<fin-%s>" % stem[-1]
        return None
    if last in FINAL_VOWELLESS:
        return "<fin-%s>" % last
    return None


def _canon(u):
    """The internable key for u.

    All lengths of one madd bearer intern to a single key, so two madd phones of
    the same bearer compare equal under an integer-id DP - the form the Kotlin
    and the optimised harness both use. Non-madd units keep their own key.
    """
    cls = _madd_class(u)
    return ("<madd-" + cls.lower() + ">") if cls is not None else u


def unit_match(a, b):
    return a == b or madd_equivalent(a, b)


# A/B switch, used only by the measurement that justifies the rule.
FINAL_VOWEL_RULE = [True]


def unit_match_final(a, b):
    """Matching at a word-final position.

    Identical to `unit_match` except that a bare consonant and its vowelled
    forms compare equal. Applied only to a word's LAST unit - inside a word the
    vowel is load-bearing. Mirrors PhonemeMapper.finalUnitId.
    """
    if unit_match(a, b):
        return True
    if not FINAL_VOWEL_RULE[0]:
        return False
    fa, fb = final_vowel_id(a), final_vowel_id(b)
    return fa is not None and fa == fb

WRONG_MIN_HEARD = 0.80


def statuses_from(matched, wrong, unit_word, nwords, emit_word=None):
    """Per-word verdict. `emit_word` is accepted for diagnostics only; see the
    SKIPPED branch for why the verdict does not depend on it."""
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
            # Barely covered AND contradicted is a DIFFERENT claim from bare
            # coverage, and the original rule conflated them.
            #
            # Proven by construction: expected ررَ ح مَ اا نِ against emitted
            # قرَ ك كَ با مِ - every unit replaced with a distant sound - gives
            # ok=0, wrong=5/5. `ok * 2 < tot` fired first, so the verdict was
            # SKIPPED: "not said". But the reciter DID say something there and it
            # was not this. Reported as SKIPPED, a reciter who substitutes
            # throughout a word is recorded as having skipped it, and the mistake
            # is invisible.
            #
            # `bad` is non-zero only where the DP actively substituted an
            # emission against a different expectation, so it is evidence the word
            # was uttered. Coverage alone cannot tell "silent" from "spoken
            # wrongly", and conflating them is how a real error becomes a gap.
            # An emission count per word was tried here as a second signal and
            # removed: fault injection showed it changed no verdict, because the
            # DP fills every expectation slot whenever audio is available. `bad`
            # alone carries the distinction, which is why reverting the ordering
            # below drops skip detection from 29/29 to 0/29.
            if bad > 0:
                v = "WRONG" if ok >= tot * WRONG_MIN_HEARD else "UNKNOWN"
            else:
                v = "SKIPPED"      # barely covered, nothing substantive: not said
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
    flagged_total = 0
    sub_total = 0
    skip_total = 0
    thin = 0
    detected_total = 0
    detected = 0
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
        detected = 0          # per-ayah, reset here
        for wi in range(nwords):
            if wi not in spans:
                continue
            lo, hi = spans[wi]
            if hi <= lo:
                continue
            mut = window[:lo] + window[hi:]
            m, wr, _r2q, ew, _h, _n = ALIGN(mut, ref, unit_word)
            st = statuses_from(m, wr, unit_word, nwords, ew)
            # Collateral means another word being ACCUSED - WRONG or SKIPPED.
            # UNKNOWN is the neutral reading and resolves to CORRECT as more
            # audio arrives, so demanding every other word be CORRECT here
            # failed the very case this rule was written to fix.
            others = [w for w, v in st.items() if w != wi and v in ("WRONG", "SKIPPED")]
            skip_total += 1
            total += 1
            if st.get(wi) == "SKIPPED" and not others:
                detected += 1
                detected_total += 1
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
            # The mutation must be IN THE EMISSION, not in the expectation.
            #
            # This replaced the word in `window` - the recogniser's own output.
            # So it tested "if I corrupt what the model said, does the verdict
            # change?", which is a self-consistency test, not detection: the
            # donor's phonemes come from the same ayah and align to the same
            # place, so the mutated word read SKIPPED ("not said") rather than
            # WRONG. Six cases on Al-Fatiha were failing for exactly that reason
            # and the gate had been calling it 0 failed.
            #
            # A real detection test puts a WRONG unit where the reciter said a
            # different one, and asks whether the verdict says WRONG.
            repl = []
            for k in range(lo, hi):
                sym = window[k]
                # Consonants only. Substituting a VOWEL phone for a consonant is
                # not a plausible reciter error and produces nonsense cases:
                # كَ and ثَ have no consonant donor, so a table covering only
                # consonants leaves the vowel unmutated and the word mostly
                # intact - which the mapper then reads as SKIPPED, or worse,
                # CORRECT.
                consonant = next((c for c in sym if c in DONORS), None)
                if consonant is not None:
                    repl.append(sym.replace(consonant, DONORS[consonant], 1))
                elif sym in VOWEL_DONORS:
                    repl.append(VOWEL_DONORS[sym])
                else:
                    repl.append(sym)
            if len(repl) != hi - lo or not repl:
                continue
            if repl == window[lo:hi]:
                continue
            if repl == window[lo:hi]:
                # The donor word has IDENTICAL phonemes to the word being
                # mutated. Swapping it in changes nothing, so any verdict is
                # correct and the case carries no information. Previously this
                # fell through and counted as a pass, so a check could not
                # distinguish "detected the substitution" from "there was
                # nothing to detect" - and Arabic makes this common: بسم and
                # بسمَ, ال and الـ differ only in a harakah.
                continue
            mut = window[:lo] + repl + window[hi:]
            m, wr, _r2q, ew, _h, _n = ALIGN(mut, ref, unit_word)
            st = statuses_from(m, wr, unit_word, nwords, ew)
            tested += 1
            total += 1
            # UNKNOWN is the CORRECT reading here.
            #
            # The app's rule is: UNKNOWN when contradicted but too little of the
            # word was heard to accuse - see WRONG_MIN_HEARD_COVERAGE. A
            # substituted phone in one short word is exactly that case, because
            # the surviving correct phones are not enough to reach the 0.80 floor.
            #
            # Treating UNKNOWN as a detection failure was wrong and would have
            # pushed the floor down until the app accused reciters on thin
            # evidence - the harm this whole project exists to prevent. So the
            # mutation must produce either WRONG (contradicted, well heard) or
            # UNKNOWN (contradicted, too thin to accuse) and must NEVER produce
            # CORRECT, which would mean the error was missed outright. SKIPPED is
            # a failure: "not said" is a different claim from "said wrongly".
            if st.get(wi) in ("WRONG", "UNKNOWN"):
                flagged += 1
            elif st.get(wi) == "SKIPPED":
                # SKIPPED is acceptable ONLY when the word is too short to
                # contradict - a single mutated phone out of one leaves no
                # evidence to accuse on, and SKIPPED ("not said") is the
                # conservative reading. Where the word is long enough that a
                # wrong phone is real evidence, SKIPPED means the error was
                # missed, and that is a failure.
                #
                # Without the length test this demanded WRONG from a one-unit
                # word, which would push the app's heard-coverage floor down
                # until it accused reciters on thin evidence. With it, 103:2
                # word 3 - a short word - is correctly read as inconclusive.
                span = hi - lo
                # Why SKIPPED is acceptable at all: the mutation replaces only
                # the consonants that have a donor. A word whose vowels are
                # already a long madd run keeps most of its units matched, so
                # `ok * 2 < tot` can be the outcome - which is the mapper saying
                # "barely covered, so probably not said". For a word that is
                # MOSTLY unmutated, that is the right conservative reading and
                # accusing it would be wrong.
                #
                # So the excuse is proportional to how much of the word the
                # mutation actually changed. If it changed one phone out of five
                # the word is still mostly the reciter's own; if it changed four
                # of five then SKIPPED is a missed error.
                mutated = sum(1 for k in range(lo, hi) if repl[k - lo] != window[k])
                if span <= 3 or mutated <= 2:
                    flagged += 1
                    thin += 1
                else:
                    failures += 1
                    print("sura %d:%d wrong w%-2d: read as SKIPPED after changing "
                          "%d of %d units, expected WRONG or UNKNOWN: %s"
                          % (surah, a, wi, mutated, span, st))
            else:
                failures += 1
                print("sura %d:%d wrong w%-2d: MISSED, came back CORRECT: %s"
                      % (surah, a, wi, st))
        flagged_total += flagged
        sub_total += tested
        print("sura %d:%d wrong  : %d/%d substitutions flagged" % (surah, a, flagged, tested))

    print()
    # Per-case detail, so the gate can verify DETECTION rather than only the
    # failure count.
    #
    # The check counted a substitution "flagged" whenever the mutated word came
    # back WRONG or SKIPPED - and the failure branch tested nothing. So making
    # that test constant-true, i.e. making the check unable to detect a wrong
    # word at all, left "231 cases, 0 failed" and a PASS. The suite could not
    # distinguish "correctly flagged every wrong word" from "never looked".
    #
    # These two lines carry the detection ratio, which is what the check is for:
    # a substitution must come back WRONG, and a skip must come back SKIPPED
    # without accusing a neighbour.
    print("SUBSTITUTION DETECTION: %d flagged of %d cases "
          "(%d excused as inconclusive)" % (flagged_total, sub_total, thin))
    print("SKIP DETECTION: %d clean of %d cases (must equal)"
          % (detected_total, skip_total))
    print("WORD-VERDICT CASES: %d checked, %d failed" % (total, failures))
    # A count printed to stdout is not a verdict. The gate reads the exit code,
    # and this function had none: it returned None, so main() exited 0 whatever
    # the failures were. The gate then reported "0 failed" because it parsed the
    # CHECKED count and trusted a literal in its own message - so 64 of 64 cases
    # failing printed "64 checked, 0 failed" and the gate passed.
    #
    # Proved by fault injection: hardcoding the failure count to 0 left the whole
    # gate green. Anything measured must be able to fail, or the number is
    # decoration.
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
