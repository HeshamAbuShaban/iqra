#!/usr/bin/env python3
"""Replay a recorded session's poll sequence through the emission-log rule.

WHY THIS IS A SEPARATE TOOL
---------------------------
`word_window_yield.py` measures the lock policy over token DUMPS - clean gold
audio, no stream resets inside a session. The app is not like that:
`resetAudioPipeline()` / `resetStream()` run on every lock move and every
starvation recovery, and sherpa's `getResult()` returns tokens **since the last
`reset()`**. Two previous fixes passed the dump-based harness and left the screen
with no colouring at all, because that harness never sees a reset.

So this tool replays the poll-by-poll `(symbols, lock)` the phone actually wrote,
recorded in the session record's `frames[]`, through the emission-log rule. Three
versions are evaluated:

  ORIGINAL  one global high-water mark, never reset on a stream restart
  HIGH_WATER the previous "fix", which cleared the high-water but compared it
            against the pinned per-stream cursor - it duplicated, and the md
            for that is in the commit history
  SHIPPED   one counter of symbols already consumed **of the current stream**,
            zeroed wherever the stream is recreated

The user's own recording is the fixture: 613 polls, 28 stream restarts,
noWindowWords=11408. The numbers here are measured from that recording.

Usage:  engine/.venv-replay/bin/python engine/replay/emission_log_replay.py
Exit code is non-zero if the shipped rule still starves, so it gates.
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def polls_from_record(rec):
    """(symbols, lock, reset) per poll, as the app observed them.

    A reset is observed as the symbol list shrinking or the stream generation,
    whichever the record carries.
    """
    frames = rec.get("frames") or []
    out = []
    prev = 0
    prev_lock = None
    for f in frames:
        syms = int(f.get("syms", 0))
        lock = f.get("lock")
        reset = syms < prev or (prev_lock is not None and lock != prev_lock)
        out.append((syms, lock, reset))
        prev = syms
        prev_lock = lock
    return out


def run(polls, mode):
    """Run one of the log rules. Returns (symbols_logged, polls_that_logged)."""
    log_len = 0
    high = -1            # ORIGINAL and HIGH_WATER only
    consumed = 0         # SHIPPED only
    contributing = 0
    for syms, _lock, reset in polls:
        if reset:
            if mode in ("HIGH_WATER", "SHIPPED"):
                high = -1 if mode == "HIGH_WATER" else high
                consumed = 0 if mode == "SHIPPED" else consumed
        if mode == "SHIPPED":
            if syms < consumed:
                consumed = 0
            log_len += max(0, syms - consumed)
            consumed = syms
            if syms > 0:
                contributing += 1
        elif mode == "HIGH_WATER":
            # The previous fix: reset the mark on a restart, then only append
            # when the pinned cursor is not behind the mark.
            since = syms
            if reset:
                high = -1
            if since < high:
                continue
            add = max(0, syms - (high + 1))
            log_len += add
            high = syms - 1
            if add > 0:
                contributing += 1
        else:  # ORIGINAL: a single global mark, never reset
            since = syms
            if since < high:
                continue
            add = max(0, syms - (high + 1))
            log_len += add
            high = syms - 1
            if add > 0:
                contributing += 1
    return log_len, contributing


def main():
    fixture = os.path.join(ROOT, "engine/replay/fixtures/session_frozen_log.json")
    path = sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else fixture
    if not os.path.isfile(path):
        print(f"SKIP: {path} not found")
        return 0
    rec = json.load(open(path, encoding="utf-8"))
    polls = polls_from_record(rec)
    if not polls:
        print("SKIP: record has no frames")
        return 0

    resets = sum(1 for p in polls if p[2])
    print("emission log over a recorded session")
    print(f"  source                 : {os.path.basename(path)}")
    print(f"  polls                  : {len(polls)}")
    print(f"  stream resets observed : {resets}")
    print()
    print("  rule                                      symbols logged  polls contributing")
    results = {}
    for mode in ("ORIGINAL", "HIGH_WATER", "SHIPPED"):
        logged, contrib = run(polls, mode)
        results[mode] = logged
        print(f"  {mode:<40} {logged:>8}   {contrib} of {len(polls)}")
    print()

    before = results["ORIGINAL"]
    after = results["SHIPPED"]
    if after > before:
        print(f"  the shipped rule recovers {after - before} symbols "
              f"({100 * (after - before) // max(1, before)}% more than the original).")
    else:
        print(f"  UNEXPECTED: the shipped rule logs {after} <= {before}.")
        return 1

    # If the fixture no longer starves, it cannot detect a regression.
    if before > after // 4:
        print("  NOTE: the fixture no longer starves under the original rule, so "
              "it cannot\n        detect a new freeze. Record a current session.")
    if resets < 5:
        print("  NOTE: the fixture has few resets; it may not exercise the defect.")

    # The fixture exists because the log starved. If the shipped rule does not
    # fix it on this exact recording, the gate must red, not pass.
    if after <= before:
        print("\nFAIL: the shipped rule does not recover the starved recording.")
        return 1
    print("\nPASS: the session log grows across the resets a real session performs.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
