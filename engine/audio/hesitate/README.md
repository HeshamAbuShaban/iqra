# Hesitation / backtrack recordings

Drop real recordings here. Nothing in this folder is committed as audio: there
are no placeholders, because a synthetic file would be scored as if it were a
reciter and would prove nothing.

## What these clips are for

Everything measured so far in `engine/replay/` is clean, forward, in-order
recitation, so it cannot answer the one question that matters: does the lock
follow a reciter who goes BACK? `lock_trace.py` and `hesitation_policy.py`
approximate it by re-ordering real Fatiha spans; only a real hesitation
recording closes the gap.

## Format

- 16 kHz, **mono**.
- `.wav` (any container the local `ffmpeg` can read: wav/mp3/m4a/flac/ogg) or
  a bare `.raw` that is already **16 kHz mono float32 little-endian** PCM -
  the same format as `engine/audio/001.raw`.
- Record from a real device. Phone voice memos are fine.
- Say the whole passage you are testing, in the order you are testing, with a
  natural pause between ayat. Do not edit the hesitation out: the pauses are
  what the lock's per-poll evaluation reacts to.
- Keep the file under ~60 s. The recogniser runs at roughly 0.2x realtime, so
  60 s is about 12 s of compute.

`hesitation_policy.py --audio <path>` converts with `ffmpeg` if needed
(`ffmpeg -i in.wav -ar 16000 -ac 1 -f f32le tmp.raw`), runs the real
`dump_tokens.py` on it, writes `dump-<stem>.json` next to this README, and
scores the result with the app's full lock policy. `--require-probs` is not
available on this recogniser: `sherpa-onnx` returns an empty `ys_probs` for
streaming CTC `greedy_search`, so no confidence is recorded. See the header of
`engine/replay/dump_tokens.py`.

## Naming

`NNN<variant>.wav`, where `NNN` is the surah number (zero-padded to three
digits) and `<variant>` is the behaviour being recorded:

| file                 | surah | what to recite                              |
|----------------------|-------|---------------------------------------------|
| `001clean.wav`       | 1     | straight through, no mistakes               |
| `001repeat.wav`      | 1     | recite ayah 4 twice                         |
| `001repeat2.wav`     | 1     | recite ayah 4 three times                   |
| `001back.wav`        | 1     | recite 4, then go back to 3, then 4, then on |
| `001hesitate.wav`    | 1     | stop and restart mid-ayah 4 several times   |
| `103clean.wav`       | 103   | straight through                            |
| `103repeat.wav`      | 103   | recite ayah 3 twice                         |
| `103hesitate.wav`    | 103   | restart mid-ayah 2                          |
| `103back.wav`        | 103   | recite 1, 2, back to 1, then 2, 3           |
| `112hesitate.wav`    | 112   | restart mid-ayah 3                          |
| `113wrong.wav`       | 113   | mispronounce exactly one word, clearly       |

The surah number in the filename is what `hesitation_policy.py --audio` uses
to pick the expected phoneme table, so the first three digits must be the
surah you actually recited. The variant suffix is free text and is echoed in
the report.

## Running

```bash
cd engine
.venv-replay/bin/python replay/hesitation_policy.py --audio audio/hesitate/001repeat.wav
.venv-replay/bin/python replay/hesitation_policy.py --audio audio/hesitate/001repeat.wav --full
.venv-replay/bin/python replay/back_policy_sweep.py --detail
```

`--audio` needs `ffmpeg` on `PATH` for anything that is not a bare `.raw`.

## What to look for in the output

- **moves / oscillations** in the `MOVE TRACE`. Oscillation is the number of
  direction reversals: a move that reverses the previous move's direction.
- **reason** on each row. `backward`, `jump`, `handoff` and `repeat` are
  distinguishable here even though the app's own log prints only
  `lock a -> b`.
- the **`forward-only`** line at the bottom. It re-runs the same clip with the
  backward branch disabled. If the two agree, the trace is blind to the
  hesitation and BACK_COVERAGE/STUCK_COVERAGE are doing nothing.
- the **final lock**. A trace can reach the right final ayah having visited
  the wrong ones on the way, which is what the `reason` column is for.

## Caveat worth knowing before you record

The lock policy cannot walk back more than one ayah
(`PracticeViewModel.kt:916` is `val backAyah = lockedAyah - 1`). If you
recite 4, 3, 4 the lock should produce `4 -> 3 -> 4`. If you recite 4, 2, 3,
4 it will only wake up when the lock is already at 3, so the trace shows one
step back, not two. A recording that deliberately re-visits an ayah TWICE
after the lock has moved is the only way to exercise the deeper walk-back.
