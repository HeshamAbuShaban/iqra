#!/usr/bin/env python3
"""Does the tail replay break the lock on-device?

The app resets the sherpa stream on every lock advance and re-feeds up to
1.5 s of already-recognised audio (tailBuf, 24,000 samples). Those tokens
land at the START of the next slice, ahead of the ayah the reciter is now
starting - so each slice opens with ~4 stale symbols from the ayah just
finished.

engine/replay/lock_policy.py models NO such replay, so its 28/28 was
measured on a policy the app does not actually run. This measures the
replay's cost.

Replays are sized from real timestamps: how many emitted tokens fall in
the final 1.5 s of the previous slice.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
W = os.path.join(HERE, "..", "shootout", "weights", "zipformer")
REPLAY_SECONDS = 1.5


def load_units():
    units = {}
    with open(os.path.join(W, "tokens.txt")) as f:
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


def coverage(query, ref):
    n, m = len(ref), len(query)
    if not n or not m:
        return 0.0
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
    hits = 0
    i, j = n, m
    while i > 0 and j > 0:
        if dirs[i - 1][j] == 0:
            if ref[i - 1] == query[j - 1]:
                hits += 1
            i -= 1
            j -= 1
        elif dirs[i - 1][j] == 1:
            i -= 1
        else:
            j -= 1
    return hits / float(n)


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
    refs = []
    for a in range(1, n_ayat + 1):
        seq = []
        for w in table["%d:%d" % (surah, a)]["aya_phonemes_list"]:
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
