---
name: iqra-recognition
description: Use when working on Iqra (/home/oldbrain_exe/WorkingOn/SideProject/iqra), a Quran recitation-teaching Android app that verifies word-by-word against a Zipformer phoneme recogniser. Routes to the project's hard-won docs, states the gate contract (every check must be proven able to fail), and records where the engine, the word table, the lock policy and the tajweed data live. Also use for run_checks.py, lock_trace.py, PhonemeMapper, PracticeViewModel, emission log, waqf, madd, saktah, memorisation, or "the colouring is wrong" / "no verdict" / "the lock does not advance".
---

# Iqra: recitation recognition

An Android app that listens to someone reciting the Quran and judges each word.
It runs a sherpa-onnx Zipformer phoneme recogniser over 16 kHz audio and aligns
the emitted phonemes against an expected per-word phoneme sequence with a
dynamic program, then derives a verdict per word: CORRECT, WRONG, or no verdict.

Two facts govern everything below.

**A verdict is an accusation.** Painting a word red tells a reciter they recited
it wrongly. Every change to this pipeline is, in the end, about not accusing
somebody falsely. That is the harm the project exists to avoid, and it outranks
every other consideration here, including how the screen looks.

**Recognition has never been verified against the user's own voice.** Most
ground truth is the pinned Husary reference clips and an Al-Dosari corpus. When a
result depends on how a human actually recites, say so.

## Start here, in this order

| Read | When |
|---|---|
| `docs/STATUS.md` | Always, first. What is fixed, what is open, ranked by what hurts. |
| `docs/LESSONS.md` | Before changing any threshold, metric or gate. 13 lessons, each earned. |
| `docs/TAJWEED_AND_MEMORISATION.md` | Before anything touching expected phonemes. What the engine cannot judge, and why. |
| `docs/HARNESS_FIDELITY_AUDIT.md` | Before trusting any offline test result. 22 harness-vs-device divergences. |
| `engine/replay/emission_log_replay.py` | When verdicts are missing on the device. Replays a real session's polls through the log rule. |
| `engine/replay/fixtures/` | Real device recordings kept as gate input. Prefer these to any simulation. |
| `docs/DEFECTS_AND_METHOD.md` | When a symptom looks familiar. Catalogue with the instrument that caught each. |
| `docs/ASK_mic_bands.md` | Only for mic spectrum work; it explains the one-method ask. |
| `docs/REVERSE_ENGINEERING.md` | Only for Tarteel's architecture and its network endpoints. |
| `docs/WORD_ALIGNMENT_4116.md` | Historical. Resolved; kept because the reasoning error is instructive. |

`docs/` is ~3,700 lines. Do not read it all. Route by symptom.

## The gate contract

`engine/replay/run_checks.py` is the **only** source of truth. It runs 16 checks.

**Every check must be proven able to fail before its result is believed.** This
is not a formality. Three checks were green while unable to report failure:

- `lock_trace` tested for the substring `oscillations=0`, which its own script
  prints unconditionally, and its detail string was the literal
  `"28/28 ayat, 0 reversals"`. Disabling the forward-advance branch in
  `PracticeViewModel` — the thing that makes the lock move — left it passing.
- `word_verdicts` parsed only the *checked* number from a two-number line and
  printed a literal `"0 failed"` of its own, so hardcoding the failure count to
  zero left the suite green.
- Its substitution test counted a mutation "flagged" whenever the verdict was
  WRONG **or SKIPPED**, with the failure branch unreachable — so detection was
  structurally zero and reported as a pass. It hid a real defect (below).

To add or trust a check:

1. Fault-inject it. Break the thing it measures and confirm it goes red.
2. If the mutation changes nothing, the check measures nothing. Delete it or
   rewrite it — do not keep it as decoration.
3. A check that reports a number must be able to disagree with reality.

Run it: `engine/.venv-replay/bin/python engine/replay/run_checks.py`.
Add `--only <name>` for one check. Some need token dumps under `engine/corpus/out/`.

**17 checks.** A check that cannot fail measures nothing — see the failure
section below before trusting any of them.

## The failure that cost the most time

**sherpa's `getResult()` returns tokens since the last `reset()` — not cumulative
since the stream started.** Anything that stores an index into that list must
survive a reset, and `resetAudioPipeline()` runs on **every lock move and every
starvation recovery**, roughly 20–30 times per session.

This presented as an app that simply produced no verdicts: 0 CORRECT, 0 WRONG,
every word UNKNOWN, which renders as nothing at all. It was found only by
replaying the **user's own recorded poll sequence** — the phone writes
`(symbols, lock)` per poll, so the failure was reproducible from a file. Measured
on that recording: 202 symbols logged, 529 of 613 polls skipped.

Two fixes failed this way before the cause was isolated, because every harness
check read *token dumps*, which contain no stream resets at all. If a bug can
only happen on the device, the check has to be fed a device recording.

Rules that follow:

- **A per-stream index is not a session index.** A high-water mark or cursor that
  survives a reset will compare against the longer of two streams.
- When a counter's value is a *position*, ask what resets it. If nothing does, it
  is wrong.
- Prefer `run_checks.py --only <name>` against a **committed fixture of real
  input** over any simulation of it. `engine/replay/fixtures/` exists for this.

## The five rules that keep recurring

From `docs/LESSONS.md`, condensed. Each cost weeks.

1. **Measure the whole corpus before trusting the first sample.** Six clean clips
   were the pinned reference — same reciter, studio conditions, in order. They
   tested the happy path and read as a pass rate. 114 surahs, 6,236 ayat, 26
   hours found nineteen that were not clean.
2. **Check your instrument models the device.** Three fixes passed offline and
   failed on the phone, every time because the harness was wrong. See
   `docs/HARNESS_FIDELITY_AUDIT.md`.
3. **A metric's blind spot is always at its own threshold.** Coverage
   `unitsMatched/unitsTotal` pins at 1.000 when the locked ayah is short, which
   holds every gate shut.
4. **Extract the numeric kernel so CI can execute it, then break it on purpose.**
   `UnitAligner.kt` exists for this reason.
5. **Delete the branch that cannot fire.** A gate that can never pass is worse
   than none: it looks like a safety measure while disabling the feature.

## Where things live

```
android/app/src/main/java/com/iqra/quran/
  ml/PhonemeMapper.kt        the DP, the verdict rule. The heart.
  ml/SherpaZipformer.kt      the recogniser wrapper (sherpa-onnx)
  ml/SherpaVad.kt            voice activity detection, 250 ms feed poll
  ml/UnitAligner.kt          DP extracted for CI
  ui/PracticeViewModel.kt    lock policy, evidence windows, session records
  ui/SessionReport.kt        what the user is shown
  assets/word_aligned_phonemes.json   expected phonemes, per Mushaf word
engine/replay/               the harness + run_checks.py
engine/corpus/               token dumps, session records, corpus reports
docs/                        the 3,700 lines above
```

**Ownership.** With another agent working in the same tree: this project's
recogniser owns `PracticeViewModel`, `PhonemeMapper`, `engine/replay`, and the
session records under `filesDir/sessions/`. Do not sweep unfamiliar modified
files into a commit; stage by path and check `git status` first.

## Vocabulary that has caused real confusion

- **"pushed" means git.** **"installed" means the phone.** They are different
  acts and both need saying. Saying "pushed" when only git was updated has
  twice left the user believing the device had a build it did not have.
- **SKIPPED is not a verdict.** It means "no evidence". It once painted red with
  a strikethrough, which told a reciter they had erred when nothing had been
  established.
- **UNKNOWN is a non-verdict** that still reaches the renderer, so the ayah keeps
  its recitation highlight without accusing anyone.
- **unjudgeable** means the word counts disagree between the mushaf and the
  phoneme table. Those ayat yield UNKNOWN, never a guess.

## The recogniser is expected to be swappable

The user wants to choose the model, and to be able to add a stronger one later
without rewiring anything. Keep that seam open:

- The engine surface is narrow — `filesPresent`, `ensure`, `startStream`,
  `accept`, `decodeIfReady`, `resetStream`, `closeStream`, `resetCounters`,
  `hasStream`. All 20 call sites are in `PracticeViewModel`.
- A new model must emit the **model's own unit inventory** (251 units from
  `tokens.txt`) or coverage is identically zero. A recogniser producing different
  units needs its own expected table, not the current one.
- `decodeIfReady()` returns tokens **since the last `reset()`**, not cumulative
  since stream start. `resetAudioPipeline()` runs on every lock move and every
  starvation recovery, so per-stream indices are invalidated ~19 times per
  session. Anything that stores an index into that list must survive a reset —
  this is the `emissionLog` bug, and it silently made every word UNKNOWN.
- Poll cadence is 250 ms and the policy only evaluates on polls that produced
  new symbols, so the effective evaluation rate is far below 4 Hz.

## Tajweed: what the engine cannot judge

From `docs/TAJWEED_AND_MEMORISATION.md`, measured over all 6,236 ayat. Do not
ignore these when touching expected phonemes.

- **The table is one realisation per ayah** — waqf at every ayah end, wasl
  everywhere inside. Stopping on a mid-ayah waqf mark is a *different, equally
  legal* realisation that the table cannot express. 9,950 mid-ayah stop marks in
  3,970 ayat; at 1,409 junctions a legitimate stop currently reads WRONG.
- **Madd is a reciter choice, not a value.** 9,716 sites are free-choice (madd
  al-'aridh, muttasil, munfasil, silah, leen). The table asserts 4 counts at
  9,705 of them. **Madd length must never produce a verdict.**
- **Saktah occurs only when continuing** — it is free acoustic evidence of which
  realisation the reciter chose. The engine carries the marker at 3 of its 4
  obligatory sites and ignores it.
- **Idgham merges a word-final sound into the next word**, which is the original
  4,116-ayat word-alignment problem at source.
- Qalqalah becomes major when stopping. Tafkhim/tarqiq, hamzatul wasl vs qat',
  and silent letters all change what is audible.
- Known gaps with no data: the taa mabsuta exception list, the seven silent
  alefs, and whether the hub of `ة` in wasl is `ه` or `ت` (1,709 sites).

## Memorisation is not tilawah

- Ibn al-Jazari's fastest speed, **haḍr, is specifically for reviewing memorised
  material**. Any heuristic treating slow as uncertain is backwards for someone
  reciting from memory.
- The observable signature of memorisation is **not hesitation**. It is
  re-emission: a gap, then a partial re-run of already-passed material, then
  continuation. The lock currently reports that as a stall, which is wrong.
- A portion is consolidated after **three consecutive correct repetitions**.
  Progress for a memoriser is repetitions of a page, not an accuracy percentage.
- Listening back to one's own recording is a documented step in the hifz loop.
  The app never lets the user hear themselves.
- A complete hafiz does 40–60 pages a day on a Sabaq/Sabqi/Manzil rotation. The
  lock follows one surah forward, which is a tilawah assumption.

## Reporting

Never state a number without naming what produced it and whether it was measured
offline or on the device. "94.8% CORRECT" is meaningless without "with stream
resets modelled, which the first version of the harness did not do".

When a check cannot be trusted, say so rather than quoting it. The user is a
serious practitioner; uncertainty stated plainly is more useful than a clean
number.
