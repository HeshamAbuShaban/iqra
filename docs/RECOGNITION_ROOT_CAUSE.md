# Recognition root cause — the expected side was in the wrong alphabet

**Status:** fixed in `fb99122`'s successor (see "The fix" below).
**Impact:** this single defect explains the entire "detection does nothing" saga.

## Symptom

The app never detected a recited ayah. The user reported "one response then stuck",
`decoder starved 50s/92s` in the log with audio demonstrably flowing, and a single
weak `Last match 4 @ 0.26` followed by nothing. Roughly ten commits had already
retuned lock thresholds, the silence gate, scope windows and recovery heuristics
without moving the number.

## Root cause

The acoustic model and the app were comparing **two different alphabets**.

- The zipformer emits **individual phoneme units**: `بِ` `س` `مِ` `للَ` `اا` `هِ` `ررَ`
- `ordered_quran_phonemes.json` stores **per-word phoneme strings**: `بِسمِ` `للَااهِ`

`PhonemeMapper.alignToWords` flattened the expected side and compared it directly
against the emission. Its own comment asserted the mismatch was impossible:

```kotlin
// Symbols may themselves contain spaces? No: aya_phonemes_list items
// are whole words like "بِسمِ". Split above is a no-op safeguard.
```

So every expected symbol was a word and every emitted symbol was a phoneme. A
symbol can never equal a word, therefore:

**coverage of every candidate ayah was identically `0.00` — for every frame, every
ayah, every session.** No threshold could ever have worked; the matcher was
returning a constant, and ~10 rounds of tuning were tuning a constant.

A second, independent defect sat on top: the lock was gated on
`Levenshtein.ratio(emission, wholeAyah) >= 0.60`. That score is *length-normalised
against a single short ayah*, so it **decreases as the user recites more**:

| fraction of Al-Fatiha recited | `ratio` (old) | coverage (new) |
|---|---|---|
| 20% | 0.484 | 0.770 |
| 40% | 0.332 | 0.858 |
| 60% | 0.233 | 0.906 |
| 80% | 0.180 | 0.926 |
| 100% | 0.148 | 0.948 |

The gate was mathematically unreachable.

## How it was found

The project had a scoring harness (`engine/shootout/`) with one placeholder clip
and zero results, and five native crashes that were all found by a user report
rather than a test. This time the loop was built first (`engine/replay/`):

1. `dump_tokens.py` runs the real `sherpa-onnx` 1.13.8 streaming recognizer
   (same version, same config as the app) over 16 kHz f32le audio.
2. Matching stays in one place and is scored against real model output.

**The model was never the problem.** On clean Husary audio it transcribed all of
Al-Fatiha essentially perfectly, including istiaadha.

## The fix

1. Load the model's own unit inventory from `tokens.txt` (`ensureUnits`).
2. Explode each expected word into that inventory with greedy longest-match
   (`PhonemeMapper.explode`), keeping a `unit -> word` map so per-word verdicts
   still work. Units are multi-codepoint and ambiguous — `ا` vs `اا` vs `اااااا` —
   so longest-first is required.
3. Score candidates by **coverage**: the fraction of a candidate ayah's units the
   emission accounts for. Insertions are ignored, so leading istiaadha/basmala
   cannot reduce it.
4. Lock advances when coverage of `lock+1` clears a threshold for N consecutive
   frames. Thresholds are now named constants calibrated from the replay below,
   not guesses.

## Result — 6 surahs, 269 s of recitation, fully offline

| surah | ayat | old | new |
|---|---|---|---|
| 1 Al-Fatiha | 7 | 0 | **7/7** |
| 103 Al-Asr | 3 | 0 | **3/3** |
| 108 Al-Kawthar | 3 | 0 | **3/3** |
| 112 Al-Ikhlas | 4 | 0 | **4/4** |
| 113 Al-Falaq | 5 | 0 | **5/5** |
| 114 An-Nas | 6 | 0 | **6/6** |
| **total** | **28** | **0/28** | **28/28** |

All 28 locks were sequential and in order, e.g. Al-Fatiha
`1:1→1:2→1:3→1:4→1:5→1:6→1:7` at 15.75 s, 20.50 s, 24.75 s, 31.00 s, 36.75 s,
45.75 s, with per-advance coverage 0.64–0.78.

## A hypothesis that measurement killed

The planned Phase 2 fix was to split a high-rate ingest loop (10–20 ms) from the
4 Hz analysis poll, on the theory that decoding 4×/s under-drives a streaming
model. Replaying at 20 ms produced **identical** output — same 145 tokens, same
7/7 locks, same lock times. The frame cadence is not the constraint, so that
refactor was dropped rather than shipped on a hunch.

## Reproducing

```bash
cd engine
uv venv .venv-replay --python 3.12
uv pip install --python .venv-replay/bin/python sherpa-onnx numpy
.venv-replay/bin/python replay/dump_tokens.py audio/001.raw --frame-ms 250 \
    --label fatiha --out replay/out/fatiha.json
.venv-replay/bin/python replay/lock_policy.py replay/out/fatiha.json 1 7 0.60 2
```

`replay/score_slices.py` reproduces the old scorer's failure curve and
`replay/phoneme_explode.py` shows the 1.00-coverage alignment per ayah.

## Still unverified

This is clean, studio-grade recitation with no mistakes. Not yet measured:
deliberate mispronunciation (must flag exactly one word), skipped ayat, a user
reciting from memory with hesitation, and live microphone conditions. Those clips
do not exist yet and are the next thing to record.
