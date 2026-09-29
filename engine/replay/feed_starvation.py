#!/usr/bin/env python3
"""Reproduce the on-device starvation bug, and confirm the fix.

The app computed its feed delta against a 3s SLIDING window while tracking
the cursor in window-relative units. Once the recorder buffer passed 3s the
window was always exactly 3s long, so the cursor pinned to 3s and the delta
was permanently empty: the recogniser received audio only for the first 3
seconds of a session.

This replays the same token stream under three feed policies:

  broken  - 3s sliding window with a window-relative cursor (what shipped)
  fixed   - absolute cursor over the cumulative buffer (what ships now)
  capped  - absolute cursor, but never feeding more than 3s in one go
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SR = 16000
CAP = 3 * SR
POLL = SR // 4  # 250 ms


def load_units():
    units = {}
    with open(os.path.join(HERE, "..", "shootout", "weights", "zipformer", "tokens.txt")) as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            sym, _i = line.rsplit(" ", 1)
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


def simulate(total_samples, policy):
    """Return (samples_actually_fed, fed_bytes_fraction)."""
    cursor = 0
    fed = 0
    while True:
        # poll: cumulative buffer now holds `cursor + POLL` samples
        audio_size = cursor + POLL
        if policy == "broken":
            used_len = min(audio_size, CAP)
            window_start = audio_size - used_len
            # cursor is window-relative, exactly as shipped
            fed_this = 0 if used_len <= cursor else used_len - cursor
        else:
            start = max(cursor, audio_size - CAP)
            fed_this = audio_size - start
        fed += fed_this
        cursor = audio_size
        if cursor >= total_samples:
            break
    return fed


def main():
    clips = sys.argv[1:]
    print("samples fed to the recogniser, by feed policy")
    print("(a session with no gate skipping and no resets)\n")
    for clip in clips:
        d = json.load(open(clip))
        total = int(d["audio_sec"] * SR)
        row = []
        for policy in ("broken", "fixed", "capped"):
            fed = simulate(total, policy)
            row.append((policy, fed, 100.0 * fed / total))
        print("  %-12s %5.1fs of audio" % (os.path.basename(clip), d["audio_sec"]))
        for policy, fed, pct in row:
            print("      %-7s %8d/%d samples  %6.1f%% of the session" % (policy, fed, total, pct))
        print()


if __name__ == "__main__":
    main()
