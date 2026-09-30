#!/usr/bin/env python3
"""Simulate the lock policy over a real token dump.  (DEPRECATED - see below)

THIS SCRIPT IS NOT THE APP'S POLICY. Do not quote its number.

It can only ever move the lock UPWARD: it scores lock+1 and nothing else, so
its trace is monotone by construction and cannot fail or oscillate. It
iterates per EMISSION rather than once per 250 ms poll, so its `need` is
satisfied inside a single frame. And it models none of the app's backward
branch, long jump, surah handoff or 1.5 s tail replay
(PracticeViewModel.kt:897-932, 841-857, 330-335).

The symbol-level DP that used to be duplicated here now lives in
`lock_trace.coverage`, which is `word_verdicts.align` - the single shipped-
equivalent copy, also used by `phoneme_explode.py` and `tail_replay_cost.py`.

Use `lock_trace.py` for anything you intend to believe:

    .venv-replay/bin/python replay/lock_trace.py replay/out/s001.json

That module also models the 1.5 s tail replay; `tail_replay_cost.py` is the
narrow A/B that isolates the replay's cost on its own.

This file is kept because README.md and docs/RECOGNITION_ROOT_CAUSE.md still
cite it by name and its CLI (`dump surah n_ayat threshold need`) is stable.
"""
import json
import os
import sys

import lock_trace

HERE = os.path.dirname(os.path.abspath(__file__))
PHONEMES = os.path.join(HERE, "..", "shootout", "weights", "zipformer",
                        "ordered_quran_phonemes.json")
TOKENS = os.path.join(HERE, "..", "shootout", "weights", "zipformer",
                      "tokens.txt")

# the shared DP; see lock_trace.py
coverage = lock_trace.coverage
load_units = lock_trace.word_verdicts.load_units
make_tokenizer = lock_trace.word_verdicts.make_tokenizer


def main():
    dump = json.load(open(sys.argv[1]))
    table = json.load(open(PHONEMES))
    surah = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    n_ayat = int(sys.argv[3]) if len(sys.argv) > 3 else 7
    thresh = float(sys.argv[4]) if len(sys.argv) > 4 else 0.55
    need = int(sys.argv[5]) if len(sys.argv) > 5 else 2

    syms = [e["symbol"] for e in dump["emissions"]]
    times = [e["audio_sec"] for e in dump["emissions"]]
    refs = {}
    tok = make_tokenizer(load_units())
    for a in range(1, n_ayat + 1):
        seq = []
        for w in table["%d:%d" % (surah, a)]["aya_phonemes_list"]:
            seq.extend(tok(w))
        refs[a] = seq

    print("!! DEPRECATED. This harness only scores lock+1 and only moves "
          "forward, so it cannot fail. Use lock_trace.py.")
    print("policy: advance when coverage(lock+1) >= %.2f for %d consecutive "
          "EMISSIONS (the app evaluates once per 250 ms poll)" % (thresh, need))
    print()
    lock = 1
    slice_start = 0
    streak = 0
    streak_key = None
    events = []
    seen = 0

    for idx in range(len(syms)):
        seen = idx + 1
        if lock >= n_ayat:
            break
        nxt = lock + 1
        sl = syms[slice_start:seen]
        cov, matched, rlen = coverage(sl, refs[nxt])
        if cov >= thresh:
            if streak_key == nxt:
                streak += 1
            else:
                streak_key = nxt
                streak = 1
            if streak >= need:
                events.append((times[idx], lock, nxt, cov, matched, rlen))
                lock = nxt
                slice_start = seen
                streak = 0
                streak_key = None
        else:
            streak = 0
            streak_key = None

    print("  %-8s %-6s %-6s %-8s %-14s" % ("time", "from", "to", "coverage", "phonemes"))
    print("  " + "-" * 44)
    for t, a, b, cov, m, rl in events:
        print("  %-8.2f 1:%-4d 1:%-4d %-8.2f %d/%d" % (t, a, b, cov, m, rl))

    print()
    reached = events[-1][2] if events else 0
    correct = [e for e in events if e[2] == e[1] + 1]
    print("LOCK ACCURACY : %d/%d ayat locked, all sequential: %s" % (reached, n_ayat, "yes" if reached == n_ayat and not events[:-1] else "see table"))
    print("final lock    : 1:%d (truth 1:%d)" % (lock, n_ayat))
    if not events:
        print("!! NEVER LOCKED — coverage never reached %.2f" % thresh)


if __name__ == "__main__":
    main()
