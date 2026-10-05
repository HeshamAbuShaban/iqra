#!/usr/bin/env python3
"""Does the tail replay break the lock on-device?

The app resets the sherpa stream on every lock advance and re-feeds up to
1.5 s of already-recognised audio (tailBuf, 24,000 samples). Those tokens
land at the START of the next slice, ahead of the ayah the reciter is now
starting - so each slice opens with ~4 stale symbols from the ayah just
finished.

`lock_policy.py` models NO such replay, so its 28/28 was measured on a
policy the app does not actually run. `lock_trace.py` now models it properly
(backlog polls, per-poll evaluation, all seven thresholds) - use that for
anything you intend to believe. This script stays as the narrow, cheap A/B
that isolates the replay's cost and nothing else.

Its symbol-level DP is no longer duplicated here: `coverage` comes from
`lock_trace` (which is `word_verdicts.align`), the same copy used by
`lock_policy.py` and `phoneme_explode.py`.

Replays are sized from real timestamps: how many emitted tokens fall in
the final 1.5 s of the previous slice.
"""
import json
import os
import sys

import lock_trace

HERE = os.path.dirname(os.path.abspath(__file__))
W = os.path.join(HERE, "..", "shootout", "weights", "zipformer")
REPLAY_SECONDS = 1.5


# the shared DP (word_verdicts.align), not a second copy
_cov = lock_trace.coverage
load_units = lock_trace.word_verdicts.load_units
make_tokenizer = lock_trace.word_verdicts.make_tokenizer


def coverage(query, ref):
    """Scalar form of the shared DP, kept so this file's own shape - and its
    output format, which docs/ references - is unchanged."""
    return _cov(query, ref)[0]


def run(syms, times, refs, replay, thresh, need):
    lock = 1
    slice_start = 0
    pending = []
    streak, streak_key = 0, None
    events = []
    for idx in range(len(syms)):
        if lock >= len(refs):
            break
        nxt = lock + 1
        # what the slice would contain, optionally prefixed by the replay
        sl = pending + syms[slice_start:idx + 1] if replay else syms[slice_start:idx + 1]
        cov = coverage(sl, refs[nxt - 1])
        if cov >= thresh:
            if streak_key == nxt:
                streak += 1
            else:
                streak_key, streak = nxt, 1
            if streak >= need:
                events.append((times[idx], lock, nxt, cov))
                lock = nxt
                if replay:
                    # replay = the final REPLAY_SECONDS of what was just fed
                    cut = None
                    for k in range(idx, slice_start, -1):
                        if times[idx] - times[k] > REPLAY_SECONDS:
                            cut = k + 1
                            break
                    pending = syms[(cut or slice_start):idx + 1]
                slice_start = idx + 1
                streak, streak_key = 0, None
        else:
            streak, streak_key = 0, None
    return events, lock


def main():
    dump = json.load(open(sys.argv[1]))
    table = json.load(open(os.path.join(W, "ordered_quran_phonemes.json")))
    surah = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    n_ayat = int(sys.argv[3]) if len(sys.argv) > 3 else 7
    tok = make_tokenizer(load_units())
    # Same preference as the app and lock_trace: word-aligned ownership.
    import lock_trace as _lt
    wt = _lt.load_word_table() or {}
    refs = []
    for a in range(1, n_ayat + 1):
        key = "%d:%d" % (surah, a)
        wa = wt.get(key)
        if wa:
            refs.append([u for w in wa for u in w])
            continue
        seq = []
        for w in table[key]["aya_phonemes_list"]:
            seq.extend(tok(w))
        refs.append(seq)
    syms = [e["symbol"] for e in dump["emissions"]]
    times = [e["audio_sec"] for e in dump["emissions"]]

    print("clip: %s   replay window %.1fs" % (os.path.basename(sys.argv[1]), REPLAY_SECONDS))
    for label, replay in (("no replay  (what the replay harness measures)", False),
                          ("with tail replay (what the app actually does)", True)):
        ev, lock = run(syms, times, refs, replay, 0.60, 2)
        ok = 1 if (ev and ev[-1][2] == n_ayat) else 0
        print("  %-46s reached 1:%-3d  events=%d  %s" % (
            label, lock, len(ev), "OK" if ok else "DEGRADED"))
        if replay and ev:
            print("      advance coverages: " + " ".join("%.2f" % e[3] for e in ev))


if __name__ == "__main__":
    main()
