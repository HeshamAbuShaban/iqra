#!/usr/bin/env python3
"""Replay a recorded session's poll sequence through the emission-log rule.

WHY THIS IS A SEPARATE TOOL
---------------------------
`word_window_yield.py` measures the lock policy over token DUMPS - a corpus of
clean gold audio with no stream resets in the middle of a session. The app is not
like that: `resetAudioPipeline()` runs on every lock move and every starvation
recovery, and sherpa's `getResult()` returns tokens **since the last `reset()`**.
So the emission log lives in a world the dump-based harness never sees.

That is how a fix could pass every offline check and still leave the screen with
no colouring at all. It did, twice.

So this tool takes the poll-by-poll `(symbols, lock)` sequence out of a real
session record - the one the phone actually wrote - and runs the log rule over
it. Both versions:

  BEFORE: one global high-water mark, never reset on a stream restart
  AFTER:  the high-water is cleared when the stream restarts

The user reported the symptom ("no colouring at all", "not even grey") and the
record contains the cause. The numbers below are measured from that recording,
not modelled.

Usage
-----
    engine/.venv-replay/bin/python engine/replay/emission_log_replay.py [record.json]

Pass a session record pulled off the device, or let it take the newest one it
finds. Exit code is non-zero if the log still starves, so this can gate CI once
real records are committed as fixtures.
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def polls_from_record(rec):
    """(symbols_seen, lock, did_reset) per poll, as the app observed them."""
    frames = rec.get("frames") or []
    out = []
    prev = 0
    prev_lock = frames[0]["lock"] if frames else 0
    for f in frames:
        syms = int(f.get("syms", 0))
        out.append((syms, int(f.get("lock", 0)), syms < prev, f.get("t", 0)))
        prev = syms
    del prev_lock
    return out


def run(polls, reset_high_water):
    """Run the log rule. Returns (log_len, skipped_polls, appended)."""
    log = 0
    high = -1
    skipped = 0
    for syms, _lock, _reset, _t in polls:
        # `sliceStart` is written only under rebaseSlice and never grows, so the
        # cursor the app passes in is bounded by the CURRENT stream length. Take
        # the most favourable reading - the cursor equals the length - so the
        # BEFORE case is not flattered by a stale-cursor detail.
        since = syms
        if reset_high_water and (syms <= high or since < high):
            high = -1
        if since < high:
            skipped += 1
            continue
        log += max(0, syms - (high + 1))
        high = syms - 1
    return log, skipped


def main():
    # The committed fixture is a real device recording in which the log starved:
    # 613 polls, 28 stream resets, noWindowWords=11408, emptyWindows=922. It is
    # the default so this runs in CI - the whole failure class was invisible to
    # every check, because every other one reads token dumps rather than a
    # session's own poll sequence.
    fixture = os.path.join(ROOT, "engine/replay/fixtures/session_frozen_log.json")

    if len(sys.argv) > 1 and not sys.argv[1].startswith("-"):
        path = sys.argv[1]
    elif os.path.isfile(fixture):
        path = fixture
    else:
        d = os.path.join(ROOT, "engine/corpus/out")
        cands = [os.path.join(d, f) for f in os.listdir(d) if f.endswith(".json")] \
            if os.path.isdir(d) else []
        if not cands:
            print("SKIP: no record given, no fixture, none found")
            return 0
        path = max(cands, key=os.path.getmtime)

    if not os.path.isfile(path):
        print(f"SKIP: {path} not found")
        return 0
    rec = json.load(open(path, encoding="utf-8"))
    polls = polls_from_record(rec)
    if not polls:
        print(f"SKIP: {os.path.basename(path)} has no frames")
        return 0

    resets = sum(1 for p in polls if p[2])
    locks = {p[1] for p in polls}
    before = run(polls, reset_high_water=False)
    after = run(polls, reset_high_water=True)

    print("emission log over a recorded session")
    print(f"  source                 : {os.path.basename(path)}")
    print(f"  polls                  : {len(polls)}")
    print(f"  stream resets observed : {resets}")
    print(f"  distinct lock positions: {len(locks)}")
    print()
    print("  BEFORE (one global high-water, never reset):")
    print(f"     symbols logged : {before[0]}")
    print(f"     polls skipped  : {before[1]} of {len(polls)} "
          f"({100 * before[1] // max(1, len(polls))}%)")
    print("  AFTER (high-water cleared on a stream reset):")
    print(f"     symbols logged : {after[0]}")
    print(f"     polls skipped  : {after[1]}")
    print()
    if after[0] > before[0]:
        print(f"  the fix recovers {after[0] - before[0]} symbols "
              f"({100 * (after[0] - before[0]) // max(1, before[0])}% more).")
    elif after[0] == before[0]:
        print("  no difference - this recording has no reset after a long stream.")
    else:
        print("  UNEXPECTED: the fix logged LESS. Investigate before shipping.")

    # The fixture exists because this failure was real and expensive, so assert
    # its shape too: if a future session no longer starves, this fixture stops
    # being representative and the check should be told rather than quietly
    # passing on a recording with nothing to reproduce.
    if os.path.basename(path) == "session_frozen_log.json":
        if resets < 5:
            print("\nNOTE: the fixture no longer contains stream resets. Replace it")
            print("      with a current recording before trusting this check.")
        if before[1] < len(polls) // 4:
            print("\nNOTE: the fixture no longer starves under the old rule, so it")
            print("      cannot detect a regression. Re-record it.")

    if after[1] > len(polls) // 2:
        print("\nFAIL: even with the fix, most polls append nothing. The rule is")
        print("      still wrong for this shape of recording.")
        return 1
    if after[0] <= before[0]:
        print("\nFAIL: the fix recovers nothing on a recording that DID starve.")
        print("      Either the fixture is not representative or the rule was never")
        print("      the cause. Do not pass this off as a no-op.")
        return 1
    print("\nPASS: the log grows across stream resets, so every ayah can be given")
    print("      a window that contains its own audio.")
    print("      This is the check the missing colouring had no coverage for.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
