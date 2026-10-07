#!/usr/bin/env python3
"""How many words actually get a verdict, with the window rule the app uses.

NOT IN THE CI GATE - it replays real corpus audio through the DP and takes
minutes. Run it by hand after touching the evidence window, the emission log, the
lock thresholds or the verdict rules. `evidence_window_parity.py` is the fast
structural guard and runs on every build; this is the measurement behind it.

THE TWO FAILURES THIS FILE EXISTS FOR
-------------------------------------
Found on the device by reading the session records: a 302 s recitation of
2:59-2:76 that the lock followed correctly to 0.933 coverage produced

    365 SKIPPED, 24 UNKNOWN, 0 CORRECT, 0 WRONG

and SKIPPED painted wrongColor with a strikethrough, so "no evidence" was drawn
as "you got this wrong".

Fix 1 gave each ayah its own window. It looked right offline and was WORSE on the
phone: every word became UNKNOWN, six sessions running, 0 CORRECT / 0 WRONG /
0 SKIPPED and 29-333 UNKNOWN each.

The cause was sherpa's contract: `rec.getResult()` returns the tokens emitted
SINCE THE LAST `reset()`, and `resetAudioPipeline()` calls `resetStream()` on
EVERY lock move - 19 times in one recorded session, 29 in another. So the
per-stream list restarts near zero about once per ayah, session-scoped arrival
indices pointed into discarded data, every window collapsed to empty, and empty
reads as UNKNOWN. The pre-fix harness never hit it because it accumulated symbols
forever, so it never simulated the reset at all.

So this file runs the policy with the reset modelled, which is what the device
does, and fails if the yield collapses.

Also worth keeping: the obvious window is wrong. The lock advances when the
reciter is 60% through the TARGET, so on arriving at ayah N you are already 60%
of the way through it and N's first 60% is still in the previous slice.

    [arrival(N), arrival(N+1))       reached CORRECT   5.3%
    [arrival(N-1), arrival(N+1))     reached CORRECT  93.4%

Usage
-----
    engine/.venv-replay/bin/python engine/replay/word_window_yield.py
    RESET_SYMBOLS=0 ... # pre-fix harness, for comparison

Measured on Al-Dosari gold audio, 4 surahs, stream resets on:

    CORRECT 94.8%   WRONG 3.8%   UNKNOWN 1.2%   SKIPPED 0.2%
"""
import json
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lock_trace as L                      # noqa: E402
from word_verdicts import align             # noqa: E402

ADV = 0.60          # PracticeViewModel ADVANCE_COVERAGE - asserted by the gate
FLOOR = 0.80        # WRONG_MIN_HEARD_COVERAGE
CAP = 4000          # EMISSION_LOG_CAP
WIN = 480           # ayahWindowLimit

RESET_SYMBOLS = os.environ.get("RESET_SYMBOLS", "1") != "0"


def exp_of(s, a):
    wa = L.load_word_table().get("%d:%d" % (s, a))
    if not wa:
        return None
    q = [u for w in wa for u in w]
    uw = []
    for wi, w in enumerate(wa):
        uw.extend([wi] * len(w))
    return q, uw, len(wa)


def verdicts(matched, wrong, uw, m):
    out = []
    for wi in range(m):
        idx = [k for k, x in enumerate(uw) if x == wi]
        t = len(idx)
        o = sum(1 for k in idx if matched[k])
        b = sum(1 for k in idx if wrong[k])
        if t == 0:
            out.append("SKIPPED")
        elif o == t:
            out.append("CORRECT")
        elif o * 2 < t:
            out.append("SKIPPED")
        elif b > 0 and o >= t * FLOOR:
            out.append("WRONG")
        else:
            out.append("UNKNOWN")
    return out


def run_policy(sur, reset_symbols):
    """Walk the lock over one surah. Returns (arrivals, final_lock, log, base).

    Mirrors the app: a per-stream symbol list that a lock move resets, plus a
    session-scoped log that arrivals index. With reset_symbols=False the stream
    never resets, which is the harness that made the bug invisible.
    """
    wt = L.load_word_table()
    d = json.load(open("engine/corpus/out/%03d.json" % sur, encoding="utf-8"))
    byf = L.group_frames(d)
    syms, log = [], []
    base = 0
    arrival = {}
    lock = 1
    streak = 0
    cand = None
    slice_start = 0
    high = -1

    def get(a):
        wa = wt.get("%d:%d" % (sur, a))
        if not wa:
            return None
        q = [u for w in wa for u in w]
        uw = []
        for wi, w in enumerate(wa):
            uw.extend([wi] * len(w))
        return q, uw, len(wa)

    for pi in range(d["frames"]):
        chunk = byf.get(pi, [])
        if chunk:
            syms.extend(e["symbol"] for e in chunk)
            if slice_start > high:
                high = -1                     # stream restarted
            for i in range(high + 1, len(syms)):
                log.append(syms[i])
            high = len(syms) - 1
            if len(log) > CAP:
                del log[0]
                base += 1

        e = get(lock + 1)
        if not e:
            continue
        q, uw, m = e
        mtc, _, _, _, _, _ = align(syms[slice_start:], q, uw)
        cov = sum(1 for x in mtc if x) / float(len(q))
        if cov >= ADV:
            if cand == lock + 1:
                streak += 1
            else:
                cand = lock + 1
                streak = 1
            if streak >= 2:
                # ABSOLUTE log index at this instant, exactly as the app records
                # emissionBase + sliceStart. Must be derived from the LOG, not the
                # per-stream list: with resets on, len(syms) restarts near zero
                # every ayah, so base + len(syms) is not a position in the log.
                arrival[lock + 1] = base + len(log)
                lock += 1
                cand = None
                streak = 0
                if reset_symbols:
                    syms = []                 # resetStream()
                    slice_start = 0
                    high = -1
                else:
                    slice_start = len(syms)
        else:
            cand = None
            streak = 0
            if not reset_symbols:
                slice_start = len(syms)
    arrival.setdefault(1, 0)
    return arrival, lock, log, base


def ayah_obs(log, base, arrival, n):
    """Mirror of PracticeViewModel.ayahObs: [arrival(N-1), arrival(N+1)), clamped."""
    if n not in arrival:
        return None
    lo = max(arrival.get(n - 1, 0), base)
    hi = min(arrival.get(n + 1, base + len(log)), base + len(log))
    if hi <= lo:
        return []
    lo = max(lo, hi - WIN)
    a, b = lo - base, hi - base
    if a < 0 or b > len(log) or b <= a:
        return []
    return log[a:b]


def measure(sur, reset_symbols):
    arrival, lock, log, base = run_policy(sur, reset_symbols)
    counts = Counter()
    for a in sorted(arrival):
        e = exp_of(sur, a)
        if not e:
            continue
        q, uw, m = e
        w = ayah_obs(log, base, arrival, a)
        if w is None or not w:
            counts["NOWINDOW"] += m        # on device this is UNKNOWN on every word
            continue
        mtc, wr, _, _, _, _ = align(w, q, uw)
        counts.update(verdicts(mtc, wr, uw, m))
    return counts


def main():
    surahs = (1, 36, 55, 67)
    grand = Counter()
    ran = 0
    for sur in surahs:
        if not os.path.isfile("engine/corpus/out/%03d.json" % sur):
            continue
        c = measure(sur, RESET_SYMBOLS)
        grand.update(c)
        ran += 1
        n = sum(c.values()) or 1
        print("  surah %-3d CORRECT %5.1f%%  SKIPPED %5.1f%%  NOWINDOW %d"
              % (sur, 100.0 * c["CORRECT"] / n, 100.0 * c["SKIPPED"] / n, c["NOWINDOW"]))
        sys.stdout.flush()

    n = sum(grand.values()) or 1
    judged = grand["CORRECT"] + grand["WRONG"]
    print("\nTOTAL over %d surahs, stream resets %s:"
          % (ran, "ON (as on device)" if RESET_SYMBOLS else "off (pre-fix harness)"))
    for k in ("CORRECT", "WRONG", "UNKNOWN", "SKIPPED", "NOWINDOW"):
        if grand[k]:
            print("   %-9s %5d  %5.1f%%" % (k, grand[k], 100.0 * grand[k] / n))
    print("\nreal verdict rate %.1f%%   painted red without cause %.1f%%"
          % (100.0 * judged / n, 100.0 * grand["SKIPPED"] / n))

    if RESET_SYMBOLS:
        bad = []
        if grand["NOWINDOW"] > 0:
            bad.append("NOWINDOW=%d: those words had no usable window, which on "
                       "device means UNKNOWN on every word" % grand["NOWINDOW"])
        if 100.0 * grand["CORRECT"] / n < 85.0:
            bad.append("CORRECT only %.1f%% (<85%%) with stream resets ON"
                       % (100.0 * grand["CORRECT"] / n))
        if 100.0 * grand["SKIPPED"] / n > 5.0:
            bad.append("SKIPPED %.1f%% (>5%%): the red wall is back"
                       % (100.0 * grand["SKIPPED"] / n))
        if bad:
            print("\nFAIL")
            for b in bad:
                print("  - " + b)
            return 1
        print("\nPASS: the window survives the stream resets that happen on every "
              "lock move. This is the case the first fix got wrong.")
    return 0


if __name__ == "__main__":
    sys.exit(main())