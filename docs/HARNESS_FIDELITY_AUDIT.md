# Harness fidelity audit

An audit of `engine/replay/` against the Kotlin it claims to model. The question
is not "is the harness right" — it is **where the harness and the device
disagree**, and which of those disagreements could let a fix pass offline and
fail on a phone.

Method: the Kotlin is treated as the specification. Every divergence below is
anchored to a line in `PracticeViewModel.kt`, `PhonemeMapper.kt`,
`SherpaZipformer.kt`, `SherpaVad.kt`, `AudioRecorder.kt` or `UnitAligner.kt`,
and where possible to a sherpa-onnx API contract. Where the Kotlin looks wrong
rather than the harness, that is said plainly — several of the worst items are
device-side defects that the harness is structurally unable to see.

Where a number is given it was measured on this repository: the six Husary
clips in `engine/replay/out/`, the 114 corpus dumps in `engine/corpus/out/`, the
real sherpa-onnx 1.13.8 model in `engine/shootout/weights/zipformer/`, and
fault injection against the shipped Kotlin.

---

## Summary

- **22 findings**, of two kinds, and the kinds are counted separately because
  they have different fixes:
  - **18 divergences** (D1-D17, D22) between the harness's model of the device
    and the device's actual behaviour. **15 of the 18** can directly produce a
    harness PASS with a device FAIL. The other three (D14, D15, D22) are
    amplifiers or bookkeeping: they do not decide an outcome on their own, but
    each corrupts a measurement or a baseline that another finding depends on.
  - **4 gate defects** (D18-D21) in `run_checks.py` and the structural checks:
    checks that report numbers they did not measure, and assertions that stay
    green with the code they guard disabled. These are why the divergences
    survived three rounds of fixes.
- **Three of the known failures share one root cause.** The session emission log
  freezes after roughly the second poll of a slice on the device
  (`appendEmissions`, `PracticeViewModel.kt:836`). The harness builds that log
  correctly, so every offline yield measurement is measuring a log the device
  never has. This is the same defect as failure 2b, and it is why the fix for
  failure 2a could not have worked.
- **The most serious single finding is not a modelling gap at all.** The gate
  reports `"28/28 ayat, 0 reversals"` and `"0 failed"` as literal strings and
  parsed-then-discarded numbers. With the forward branch disabled entirely the
  gate still reports `PASS lock_trace 28/28 ayat, 0 reversals`.

---

## Divergences

Ordered most severe first.

### D1. The session emission log freezes on the device. The harness builds it correctly.

**What the harness does.** `word_window_yield.py:127-138` maintains a
per-stream high-water, resets it to `-1` when the stream restarts, and appends
`range(high + 1, len(syms))` on every poll that produces tokens. The log grows
monotonically for the whole session. That model is correct, and it is what
produces the headline "CORRECT 94.8%".

**What the device does.** `PracticeViewModel.kt:835-841`:

```kotlin
private fun appendEmissions(symbols: List<String>, since: Int) {
    if (since < emissionHighWater) return          // restart: already logged
    for (i in (emissionHighWater + 1)..<symbols.size) {
        emissionLog.add(symbols[i])
    }
    emissionHighWater = symbols.size - 1
```

The call site is `appendEmissions(res.symbols, sliceStart.coerceAtMost(res.symbols.size))`
(line 1828). So `since` is **`sliceStart`** — the slice base, set once per rebase
at line 1804 to `res.symbols.size` and then held constant for the rest of the
slice. `emissionHighWater` is the last-appended index, which **grows every
poll**. So after the second poll of any slice, `sliceStart < emissionHighWater`
is permanently true and the guard returns. The log stops growing and never
recovers, because the guard compares a constant against a monotonically
increasing quantity.

Measured, transcribing the Kotlin exactly and driving it with the real resets
the app performs on every lock move:

| clip | symbols emitted | session log holds | arrivals recorded |
|---|---|---|---|
| s001 | 145 | **7** | `{2:7, 3:7, 4:7, 5:7, 6:7, 7:7}` |
| s114 | 83 | **4** | `{2:4, 3:4, 4:4, 5:4, 6:4}` |

Every arrival index is identical. Feeding that through the device's own
`ayahObs` (lines 912-949) gives, per ayah: ayah 1 never visited → UNKNOWN;
ayah 2 a 7-symbol window; **ayat 3 onwards `hi <= lo` → empty list → UNKNOWN on
every word**. And `lastAyahObsCoverage` returns `-1f` for an empty window
(line 958), so the handoff's outgoing-surah gate can never be satisfied either.
The device cannot produce a single CORRECT verdict, or a single evidence-based
handoff, for the whole session.

**How to tell which is right.** The device is wrong. The docstring immediately
above the function (lines 828-834) states the intent — "`since` is the index
within the CURRENT stream … anything at or below the high-water mark is already
in the log" — and the intent is not what the code does, because the argument
passed is `sliceStart` and not a per-stream append cursor. The harness's
`word_window_yield.py:153-157` says exactly this in its own comment: the append
position must be derived from the log, not from a per-stream cursor.
Independently, the fix is one line: the guard's purpose is to detect a *stream
restart*, which means comparing against the previous **stream length**, not
against `sliceStart`. Deleting the guard is also safe, because
`for (i in (emissionHighWater + 1)..<symbols.size)` is already an empty range
when the stream has restarted, so the loop appends nothing.

**Could it PASS offline and FAIL on device?** Yes, and it has. This is the
mechanism behind the "0 CORRECT, 0 WRONG, 0 SKIPPED, 29-333 UNKNOWN" sessions in
`67b7b6a`. That commit attributes the symptom to `getResult()` resetting per
stream and fixes it with the session log; the reset was real, but the log the fix
introduces never fills, so the fix cannot have worked. The harness cannot see
this because it never executes the guard — see D21.

---

### D2. `resetAudioPipeline()` leaves every emission cursor and the log untouched.

**Harness.** `lock_trace.py:496-518`. `reset_stream()` clears `epoch`, sets
`slice_start = 0` and `rebase_pending = True`. `full_reset()` additionally
zeroes `last_count`. The harness models a full reset of the recognition
bookkeeping.

**Device.** `PracticeViewModel.kt:1114-1133`. `resetAudioPipeline()` resets the
recorder, the stream, `fedAbs`, `fedTotal`, `streamBaseSec`, `tailBuf`,
`lastEmitCount`, `lastTokenTime` and `lastRecoveryTime`. It does **not** touch
`sliceStart`, `rebaseSlice`, `emissionLog`, `emissionBase`, `emissionHighWater`,
`ayahArrival`, `wrongStreak`, `wpmEma` or `speechFramesSinceAdvance`.

**Consequence on device.** After a watchdog reset the sherpa stream restarts at
token 0 while `sliceStart` still holds the pre-reset value. `base =
sliceStart.coerceAtMost(res.symbols.size)` therefore equals `res.symbols.size`
on every poll until the fresh stream grows *past* the old `sliceStart`, so `obs`
is empty, every frame takes the `TAG_EMPTY_SLICE` early return (line 1851), and
the lock policy is never evaluated. The emission log is not cleared either, so
the two defects compound: the cursors still point into a log that is no longer
being appended to.

**Could it PASS offline and FAIL on device?** Yes. The device showed 6+
starvation resets per session; the harness has no watchdog at all (D3), so this
path is never executed offline even once.

---

### D3. The starvation watchdog is not modelled at all.

**Device.** `PracticeViewModel.kt:1755-1795`. The path fires when **all** of:

- `res == null || res.symbols.isEmpty() || res.symbols.size == lastEmitCount`
  (line 1755 — note this includes "not ready", which is ~48% of polls), **and**
- `fedThisFrame` (audio was accepted this poll), **and**
- `idleSec >= 4`, where `idleSec` is wall-clock time since `lastTokenTime`
  (line 1758), **and**
- `fedTotal > 0`, **and**
- more than 10 s since `lastRecoveryTime`.

Then: `lastRecoveryTime = now`; `starvedRecoveries++`; if
`starvedRecoveries >= MAX_STARVATION_RECOVERIES` (3) the app sets
`_decoderState = "failed"`, writes an engine hint telling the user to start a new
session, and **returns on every subsequent frame** — recognition is over for the
session. Otherwise it calls `resetAudioPipeline()`, plus `startStream()` if the
native stream was gone.

`starvedRecoveries` is cleared only where tokens actually arrive (line 1799) or
at session start (line 1625). `lastTokenTime` is refreshed by a lock move
(line 1084) and by `resetAudioPipeline` (line 1125). `resetAudioPipeline` also
calls `recorder.reset()`, which discards the ring buffer and zeroes `total`, so
the loop then skips polls until `recorder.totalCount() >= 4800` (line 1664) —
roughly 0.3 s of audio thrown away per reset.

**Harness.** `lock_trace.py` contains no `lastTokenTime`, no `lastRecoveryTime`,
no `starvedRecoveries`, no `idleSec`, no `MAX_STARVATION_RECOVERIES`. Grep for
`starv` across `engine/replay/*.py` finds it only in `dump_tokens.py` (which
merely *reports* a token-gap metric) and `feed_starvation.py` (which models the
**old** 3-second-window feed bug, fixed in `d7c0e8a`, not the watchdog). No
harness run can fail because of starvation, and no harness number accounts for
the 0.3 s of audio dropped per reset or for the hard stop at three recoveries.

**Could it PASS offline and FAIL on device?** Yes. A device session with 6+
resets has 6+ windows in which the recogniser was torn down and 2 s of audio
discarded, and a session unlucky enough to hit three consecutive recoveries stops
recognising entirely. The harness models a session where the decoder never
stalls.

---

### D4. `lastEmitCount` on a lock move: the harness models the app as it was eleven hours before it was changed.

**Device.** `advanceLockTo` sets `lastEmitCount = 0` at line 1078, so after a
move any token at all reads as growth. Added in `7a94e8f`.

**Harness.** `lock_trace.py:501-502`:

> `last_count` is left alone on purpose: advanceLockTo never touches lastEmitCount.

and the module docstring repeats it at lines 35-39 as a deliberate fidelity
note. That was true when `lock_trace.py` was written (`b6969ca`, 08:24 on
30 Sep) and became false eleven hours later (`7a94e8f`, 19:33 the same day).
`git show b6969ca:engine/replay/lock_trace.py` confirms the comment was accurate
at the time.

**Consequence.** After a move the harness skips the poll whose fresh-stream size
happens to equal the pre-move count — the exact collision the Kotlin comment at
1071-1077 describes. It also means `reset_stream` and `full_reset` now disagree
with the device in opposite directions: `full_reset` zeroes `last_count`
(correct — `resetAudioPipeline` does too, line 1121), `reset_stream` does not
(incorrect since `7a94e8f`).

**Could it PASS offline and FAIL on device?** Yes, though rarely: it is a
one-poll skip after a move, and the collision is real but uncommon. It matters
mainly as evidence that the port is not kept in step automatically.

---

### D5. Poll cadence, emission batching, and the speech-frame clock.

**Device.** Four separate mechanisms shape what the policy actually sees, and
none of them is in the dumps the harness reads:

1. **The poll loop skips.** `delay(250ms)` (line 1659), then `if
   (recorder.totalCount() < 4800) continue` (1664), then `readSince(fedAbs)`
   which can return `null` on a 30-second ring overflow and force a resync
   (1674-1682), then the silence gate `if (quiet && vadSilent != false)
   continue` (1691). A measured session had **1192 of ~2250 frames gated out,
   53% of the session** (`58371b8`).
2. **Decoding is gated on `isReady`.** `decodeIfReady()` returns `null` when
   `!rec.isReady(s)` (`SherpaZipformer.kt:160`). Measured `ready_ratio` on the
   dumps is **0.5175-0.5219** — roughly half of all polls never decode.
3. **The policy runs only on growth.** Line 1755: `res == null ||
   symbols.isEmpty() || symbols.size == lastEmitCount` returns before the rebase
   and before the decision.
4. **The speech clock counts every fed frame.** `speechFramesSinceAdvance++` at
   line 1706 runs on every non-gated fed poll, regardless of whether tokens
   appeared.

**Harness.** `dump_tokens.py` feeds a fixed 4000-sample chunk per frame with **no
RMS gate, no VAD, and no endpointing**, and decodes once per frame if ready.
`group_frames()` (`lock_trace.py:388-401`) buckets emissions by the dump's frame
index, and `simulate()` iterates `range(total)` treating **every** iteration as
0.25 s of audio reaching the recogniser. The docstring at lines 55-60 admits
this: "`speechFramesSinceAdvance` counts polls that emitted a token … so `wpmEma`
is measured on a lower bound of speech time and is an upper bound on speed", and
concludes "It stayed >= 48 wpm on every clip measured, so `needFrames` was 2
throughout and the wpm path is not load-bearing".

**That conclusion does not follow.** Measured harness wpm across the six clips:
60.1, 47.8, 57.3, 75.0, 86.9, 69.8. Token-producing polls are 28-37% of all
polls, so the harness's `dtSec` is roughly **2.7x smaller** than the device's for
the same audio. `s103` already reads 47.8, i.e. **below** the 50 wpm threshold
at which `needFrames` becomes 3 on both sides (`PracticeViewModel.kt:1969`,
harness `lock_trace.py:668`) — so on that clip the harness is measuring
`needFrames = 3` from an inflated wpm and the device is measuring something
else entirely. The `dtSec in 2.0..180.0` window (line 1042) is a further gate
that the inflated value passes more often.

**Also divergent: the two "poll" numbering schemes.** The dump's `frame` index
is a *chunk* counter (`dump_tokens.py` increments it per chunk); the device's is
a wall-clock poll that may have fed nothing. `group_frames` subtracts 1 and
treats the result as a poll index. The two coincide only when nothing is ever
gated, which is never true on a device.

**Could it PASS offline and FAIL on device?** Yes. A `needFrames` that is 2
offline and 3 on device turns every forward advance into a one-frame-later move;
combined with D3's resets it changes both the lock trajectory and the number of
verdicts reached.

---

### D6. Tail replay: modelled as an optimistic re-emission, and `fedTotal` bookkeeping is absent.

**Device.** `advanceLockTo` lines 1088-1093:

```kotlin
streamBaseSec += fedTotal / 16000f
fedTotal = 0
SherpaZipformer.resetStream()
if (tailBuf.isNotEmpty() && SherpaZipformer.accept(tailBuf)) {
    fedTotal = tailBuf.size
}
```

So after every move the fresh stream is handed up to 24000 samples (1.5 s) of
**already-decoded** audio, and `fedTotal` is then set to 24000, not 0 —
`fedTotal` means "samples in the current stream", including replayed audio, and
`audioSec = streamBaseSec + fedTotal / 16000` (line 1855) is built from that.

**Harness.** `policy.tail_mode` (`lock_trace.py:146`, documented at 578-585),
default `"replay"`, which models the fresh stream re-emitting *the same symbol
sequence* delayed by `tail_frames = 1.5 / 0.25 = 6` polls. `"blank"` is the
pessimistic bound and is **not** the default. `run_checks.py` runs `lock_trace`
with the default, so every corpus number in the repository was measured under
the optimistic model.

The device has no left context after `resetStream()` — that is the whole point of
the reset — so its re-emission of that 1.5 s is a *different and probably worse*
symbol sequence, not the original one. The truth is between the two modes and
the harness's default is the wrong end of it.

The harness also has no `fedTotal` at all: `audio_sec = (wall + 1) * frame_sec`
(line 631) is a pure wall clock, so the stream-clock continuity that
`streamBaseSec` exists to preserve is unmodelled. And the `drain` extension
(extra polls past EOF, lines 592-609) is a device-behaviour fiction in the
opposite direction — defensible as a correction for end-of-file, but it means
the last frames of a surah are consumed by a mechanism the device does not have.

**Could it PASS offline and FAIL on device?** Yes. "The replayed tail is exactly
what we saw before" is an assumption, not a measurement, and it is the assumption
underneath the 104/114 sequential corpus result.

---

### D7. The handoff gate: the harness fires on one frame, the device needs three plus a surah-completeness test.

**Device.** Lines 1882-1900:

- `hereDone = lastAyahObsCoverage(activeSurah, lastAyah)` — coverage of the
  **outgoing** surah's last in-scope ayah, in its own window; `-1f` if that
  window is empty.
- `surahDone = byIntent || hereDone >= HANDOFF_SURAH_DONE` (0.85).
- `if (cov >= HANDOFF_COVERAGE) handoffFrames++ else handoffFrames = 0`.
- Fires only when `handoffFrames >= if (byIntent) INTENT_FRAMES else
  HANDOFF_FRAMES` — **2 or 3 consecutive frames**, not one.
- Plus `next != null`, and `activeSurah < 114`.

**Harness.** `lock_trace.py:649-662` fires the handoff on a **single** frame with
`cov >= policy.handoff` and no other condition. There is no surah-done gate, no
frame streak, and no intent path. The scope end comes from `plan`, not from
`scopeEndAyah()` reading the current page.

`handoff_boundary.py` gets this right *structurally* — it asserts
`HANDOFF_SURAH_DONE`, the `surahDone` composition, the frame-streak expression,
the `byIntent` surah check and `lastAyahObsCoverage` are all present in the
Kotlin (lines 170-209). It then runs the **single-frame** policy through
`lock_trace` for its behavioural half (line 110). So the structural half is
sound and the behavioural half measures a policy the device does not run. The
file's own docstring (lines 4-7) records that the branch was unexercised for
months; it is now exercised, but against the wrong gate.

Compounding: `lastAyahObsCoverage` reads `ayahObs`, which returns `emptyList()`
whenever `hi <= lo` — which, per D1, is every ayah after the second. So the
device's evidence gate is unreachable, and the harness's is always open.

**Could it PASS offline and FAIL on device?** Yes, and directly. "The handoff
fires" is exactly the claim `handoff_boundary` makes, and it is the claim a
device fix would rest on.

---

### D8. The long-jump page gate is absent from the harness.

**Device.** Line 2057: `if (p == null || p in (pageNumber - 1..pageNumber + 1))`
— a jump is **suppressed** unless the target ayah is on the current page or an
adjacent one.

**Harness.** `lock_trace.py:749`: "the page check at line 903 needs a page map we
do not have". Skipped.

**Could it PASS offline and FAIL on device?** Yes: the harness fires jumps the
device silently drops, so a jump count measured offline overstates what the
reader will see.

---

### D9. The wpm measurement uses different word counts on each side.

**Device.** Line 1041: `val prevWords = verseWords[prev]?.size ?: 0` — the
**Mushaf** word count.

**Harness.** `lock_trace.py:557`: `self.exp().get(prev_ayah, (None, None, 0))[2]`
— the **phoneme-table** word count.

These agree for the 6205 ayat where the word-aligned table matches the Mushaf
and disagree for the rest. It is a small number today and was a large one before
`e5bf1d5`.

---

### D10. The verdict rule matches; everything the caller does to it does not.

**What agrees.** I checked `PhonemeMapper.align`'s `when` block (lines 346-376)
against `word_verdicts.statuses_from` (lines 235-262) and
`word_window_yield.verdicts` (lines 78-95) exhaustively over every
`(total, ok, bad)` combination for `total` up to 12: **zero mismatches**, including
the `ok * 2 < total` SKIPPED rule and the `WRONG_MIN_HEARD_COVERAGE` 0.80 gate.
The DP itself agrees too — over 8000 random cases the harness's `align` and the
shipped kernel differ on 57 (0.7%), and over 127,738 real corpus windows
coverage differs on 488 (0.38%) and per-word status on 727 of 2,153,374
(0.034%) — see D11.

**What diverges.** The device's *caller* applies three transforms to `align`'s
output that no harness check models:

1. **WRONG is only claimed for the locked ayah.** Line 2261:
   `if (a != lockedAyah) s = SKIPPED`.
2. **A WRONG must persist before it latches.** Lines 2186 and 2263-2269:
   `needWrong = wrongLatchFrames()` = `(1.2 * (60/wpm) / 0.25).roundToInt()
   .coerceIn(2, 8)` **evaluated frames**, and until the streak reaches it the
   status is demoted to SKIPPED.
3. **The retention chain and the archive.** Lines 2286-2310 keep a terminal
   verdict behind the lock, first one wins; `archiveVerdict` (428-439) applies the
   same rule to the session record and counts `CORRECT + WRONG` as judged.

`word_window_yield.py` reports `align`'s raw statuses as the verdict mix, so its
"CORRECT 94.8% / WRONG 3.8% / UNKNOWN 1.2% / SKIPPED 0.2%" is the mix *before*
the lock-ayah restriction, before the 2-8 frame latch, and before retention. A
word the harness calls WRONG may never latch; a word behind the lock that
`align` scores SKIPPED against the next ayah's audio is retained as CORRECT on
the device and reported as SKIPPED offline.

`archive_parity.py` models the retention chain — but as a **Python table**
(`retention()`, lines 154-166) over a hand-written set of cases, wired to the
Kotlin by two substring assertions (lines 203-215). It is a statement of intent,
not an execution of the code.

**Could it PASS offline and FAIL on device?** Yes: every per-word number the
harness reports is a different quantity from the one the device paints.

---

### D11. The harness DP is the transpose of the shipped DP.

**Device.** `UnitAligner.refToQuery(qry, ref)` (lines 40-104): `n = qry.size`,
`len = ref.size`; rows are the **emission**, columns are the **reference**; the
traceback walks `(i, j)` from `(n, len)`.

**Harness.** `word_verdicts.align(query, ref, unit_word)` (lines 173-226):
`n, m = len(ref), len(query)`; rows are the **reference**, columns are the
**emission**; the traceback walks from `(n, m)`.

Same costs, same preference order (substitution, then deletion, then
insertion) — but applied to transposed axes, so where the optimum is not unique
the traceback takes a different path. Smallest disagreement found:
`ref=['b','a','b']`, `emit=['a','y','y','b','a']` — the device returns
`matched=[F,F,T], wrong=[T,T,F]`, `unitsMatched=1`; the harness returns
`matched=[T,T,F], wrong=[F,F,F]`, `hits=2`.

Measured on real data, 20 corpus dumps, every ayah, 160-symbol windows:
**coverage differs on 488 of 127,738 windows (0.382%)**, with deltas up to
**0.083** — larger than the margin between `ADVANCE_COVERAGE` 0.60 and the
observed per-advance coverages of 0.64-0.78 the policy is calibrated on. Per-word
status differs on 727 of 2,153,374 observations (0.034%).

`dp_equivalence.py` compares `align_reference` against `align_optimised`, both in
the **shipped** orientation (rows = emission), and imports `word_verdicts` only
for `build_expected` / `load_units` (line 353). The one function every harness
measurement routes through is the one the equivalence proof never touches.

**Could it PASS offline and FAIL on device?** Yes, at a rate of roughly one
window in 260 — enough to flip a threshold comparison near a boundary, and enough
to make a "value-identical" claim untrue.

---

### D12. The pinned-escape and retreat-budget clocks are different clocks.

**Device.** `pinnedFor = System.currentTimeMillis() - lastLockMoveMs` (line
2018) — **wall clock**. `lastLockMoveMs` is refreshed by `advanceLockTo` (1069),
`jumpToPage` (1299), `anchorToVerse` (1388), the handoff (1905) and the repeat
hook (2166).

`retreatAllowed()` (1031-1034): `System.currentTimeMillis() - lastAdvanceAt <
MIN_RETREAT_GAP_MS` — also wall clock, with `lastAdvanceAt` also set at
`startRecite` (1590).

**Harness.** `lock_trace.py:727`: `(audio_sec - s.last_move_t) >=
policy.pinned_escape_sec` — the **audio** clock. `retreat_allowed` (542-547):
`now - self.last_move_t < p.retreat_gap_sec` — also the audio clock, with
`last_move_t` initialised to `0.0` so the first retreat is always permitted.

Under a closed silence gate the wall clock runs and the audio clock does not, so
on the device the 12-second escape timer and the 1.5-second retreat budget both
expire during silence that the harness cannot see. The harness's `last_move_wall`
is also never refreshed by a page turn or an anchor, both of which restart the
device's timer.

**Could it PASS offline and FAIL on device?** Yes: the pinned escape is exactly
the mechanism that recovers surah 55, and it fires on a wall-clock deadline the
harness measures on a different one.

---

### D13. The repeat hook fires one ayah late in the harness.

**Device.** Line 2162: `lockedAyah >= rep.second` — the counter drops when the
lock **reaches** the target.

**Harness.** `lock_trace.py:791-792`: `s.ayah > s.repeat_target[1]` —
strictly greater, so the counter drops only once the lock has **passed** it.

This is the off-by-one `b6969ca` fixed on the device ("the counter only
decremented once the lock was *past* the target, so a reciter who could not get
past it — precisely the case repeat practice exists for — never saw the repeat
count fall"). `git show b6969ca:engine/replay/lock_trace.py` shows the harness
was written in the same commit with the old `>` and never updated.

**Could it PASS offline and FAIL on device?** Yes, and it is a one-character
divergence that inverts the feature's purpose.

---

### D14. `wpmEma` and `speechFramesSinceAdvance` survive a watchdog reset on device, not in the harness.

Covered under D2 for completeness: the device does not reset them
(`resetAudioPipeline`, 1114-1133), so a recovery changes the baseline the next
advance measures from. The harness resets `speech_since_advance` on every move
and has no recovery path at all.

---

### D15. `back_policy_sweep.py` treats `need_frames = 2` as "the shipped value".

`recommend()` (lines 292-300) compares against `(0.80, 0.35, 2)` and calls it
"shipped". The device's `needBack` is `if (wpmEma < 50) 3 else 2` (line 2080) —
wpm-dependent. On the clips where the harness measures 47.8 wpm (`s103`) the
device would use 3. So the sweep's "shipped" column is not the shipped value on
every clip it runs, and the recommended region is derived against a baseline
that moves.

---

### D16. `probe_gate_reach.py` has already drifted from the policy it claims to measure.

Its docstring admits it: "`measure()` re-implements the forward/jump/backward
branch logic rather than calling into `simulate()` … That copy WILL drift."
It has. Line 187 uses `here_cov < pol.advance` (0.60) where the device uses
`pol.stuck` (0.35) for the backward gate; there is no pinned escape, no handoff,
no backward streak, and `if s.slice_start >= len(s.epoch): s.full_reset()`
(line 191) is a rule the device does not have. Its headline "jump gate open on
85.3% of evaluated polls" no longer reproduces (67.5% on surah 94 today).

`probe_freeze.py` has the same problem, worse: its `mirror()` has no
pinned-escape branch, and its `verify()` — which is supposed to *prove* the
mirror matches `lock_trace` — currently fails:

```
AssertionError: (97, [(12.25, 1, 2, 'pinned-escape', 0.555556)],
                 [(12.75, 1, 2, 'forward-strong', 0.888889)])
```

Neither probe is in `run_checks.py`, so nothing catches this.

---

### D17. `handoff_boundary.py`'s first assertion block is dead code.

Lines 128-149 append four findings to `failures`. Line 154 then rebinds
`failures = []`. Because `failures` is a local assigned at 154, any of those
appends reached before it raises `UnboundLocalError` rather than reporting. In
the current corpus the handoff *does* fire, so none of the four is reached and
the block is silently inert: the "handoff never fired" and "handoff landed on
the wrong ayah" assertions cannot report a failure at all. (Verified the Python
semantics with a minimal reproduction.)

---

### D18. `run_checks.py` reports numbers it did not measure.

Three separate instances, all confirmed by running the gate:

- **`lock_trace`**: the check is `if p.returncode != 0 or "oscillations=0" not in
  p.stdout` (lines 116-119). `lock_trace.py`'s `main()` returns `None`, so the
  exit code is always 0; and `print_result` always prints a line containing
  `oscillations=0` when there are no moves. **Disabling the forward branch
  entirely** — `moves=0`, `final lock=1:1` — still reports
  `PASS lock_trace 28/28 ayat, 0 reversals`. The "28/28" is a literal string in
  the detail column (line 119), not a measurement.
- **`word_verdicts`**: the gate parses `(\d+) checked, (\d+) failed` (line 75) and
  reads **group(1)**, the checked count (line 106). The failed count is parsed
  and discarded. `word_verdicts.py`'s `main()` also returns `None`, so the exit
  code is always 0. With 35 of 64 cases failing, the gate reports
  `PASS word_verdicts 64 cases across 6 clips, 0 failed`. With an emptied dump —
  measuring nothing at all — it reports `42 cases across 6 clips, 0 failed`.
- **`word_rule_sweep`, `back_policy_sweep`, `hesitation_policy`**: all three
  `return 0` unconditionally (lines 247, 212, 422-of-469 respectively, the last
  returning `ok` from a table that marks one case `UNREACHABLE` and counts it as
  a pass).

---

### D19. `evidence_window_parity.py` cannot see D1.

This is the gate that was written specifically to guard the emission log, so it
is worth being precise about what it checks. It counts regex occurrences of
`emissionLog.add(`, `emissionLog.clear()`, `emissionLog.subList(`,
`emissionBase += drop`, and asserts the arrival form is
`emissionBase + emissionLog.size`. Every one of those is a **presence** check on
the source text.

Fault injection: replace the guard at line 836 with `if (since >= 0) return` —
which makes `appendEmissions` append **nothing, ever**, the exact device
behaviour of D1 — and the full 16-check gate reports **0 failed, 0 skipped,
113s**. The function still contains an `emissionLog.add(`; the check still
passes.

This is the same trap `docs/DEFECTS_AND_METHOD.md` records as "a guard that
cannot find the thing it guards is worse than none": here it is a guard that
finds the thing and cannot see whether it runs.

---

### D20. `word_window_yield.py` runs its own lock, not the app's.

Lines 140-171 implement a two-frame pending streak at `cov >= 0.60` and nothing
else: no `STRONG_COVERAGE` immediate-advance, no `WEAK_COVERAGE` decay branch, no
backward branch, no long jump, no pinned escape, no handoff, no retreat budget,
no tail replay, no drain. The docstring's "Measured on Al-Dosari gold audio"
figures come from that policy, not from `lock_trace.simulate`.

---

### D21. `tail_replay_cost.py` models a third variant of the tail.

Independent of `lock_trace`, it models the replay as a **prefix list**
(`pending + syms[slice_start:idx+1]`, line 57) with no backlog offset and no
slice rebase, and iterates per emission rather than per poll. So the repository
contains three different models of the same 1.5 seconds: `lock_trace`'s backlog
(default optimistic), `word_window_yield`'s none, and this prefix. It is not in
`run_checks.py`.

---

### D22. `rebaseSlice` is set in five places the harness does not model.

Device: lines 1070 (`advanceLockTo`), 1300 (`jumpToPage`), 1389
(`anchorToVerse`), 1583 (`startRecite`), 1906 (handoff), 2169 (repeat hook). The
harness's `_Session` sets `rebase_pending` only in `reset_stream` and
`full_reset`, i.e. only on a lock move, a handoff and the repeat hook. Page
jumps and anchors — both of which the harness has no equivalent of at all —
leave the harness's slice state as it was.

---

## Which harness checks are not currently trustworthy

In descending order of how much they are relied upon.

**`run_checks.py` as a gate.** It cannot fail on the two checks it reports
numbers for (D18). It reported `PASS` for a `lock_trace` that never moved the
lock, a `word_verdicts` with 35 of 64 cases failing, and a `PracticeViewModel`
with the emission log disabled. The green gate is not evidence.

**`evidence_window_parity.py` and `handoff_boundary.py`'s structural halves.**
Both are presence checks on Kotlin source. They proved the *shape* of the D1 fix
is present and said nothing about whether it executes (D19). Both had already
been caught passing vacuously once — `evidence_window_parity.py:225-231`
documents a mutation that fooled an earlier version — and the class of check was
not changed, only the instance.

**`lock_trace` as a model of the device.** D1, D3, D4, D5, D6, D7, D8, D11, D12,
D13 all live in or around it. Its thresholds and its branch ordering are
faithful; its **environment** — what the recogniser is fed, when, and what state
the stream is in — is not.

**`word_window_yield.py` as a yield measurement.** D1 (it models a log the device
does not build), D10 (it reports pre-latch, pre-retention statuses), D20 (it runs
its own lock policy). Its 94.8% figure is a real measurement of a program that
is not the app.

**`word_verdicts.py` and `word_rule_sweep.py` as verdict-rule guards.** The DP
transposition (D11) and the dead `failures` gate (D18) mean both can pass while
the rule is wrong. `word_rule_sweep.py`'s `shipped()` (lines 123-137) is *also*
stale in a different way: it has no `WRONG_MIN_HEARD` gate at all, so its
"SHIPPED (cov + UNKNOWN)" baseline is the rule from **before** `5e650b1`. Every
conclusion drawn from that sweep's shipped column is about a rule the app no
longer ships.

**`probe_freeze.py` and `probe_gate_reach.py`.** Both are self-declared
duplicates that have drifted; `probe_freeze.verify()` fails today (D16). Not in
the gate, so the drift is silent.

**`tail_replay_cost.py`, `lock_policy.py`, `probe_tail_sweep.py`,
`probe_jump_windows.py`.** Two are deprecated or superseded and still cited from
`docs/`; the others duplicate policy logic. None is in the gate.

**What *is* trustworthy.** `dp_source_parity.py` and `dp_equivalence.py` read
the shipped source or reproduce the shipped kernel, and both are fault-tested
against the classes of error they were written for. `ring_buffer.py`,
`session_stream.py` and `search_parity.py` derive their expectations from the
Kotlin rather than restating them. Those are the pattern the rest of the suite
should be built on.

---

## What to change

The root problem is that the harness is a **second implementation** of the
policy, and the gate is a **presence check** on the first. Three of the four
known failures came from exactly that: a fix validated against a model of the
device, checked by a grep of the device.

### 1. Make the device the harness. One implementation, not two.

The lock policy, the emission-log lifecycle, the slice rebase, the verdict
transforms and the starvation watchdog are all pure logic over
`(emissions, audio-fed-per-poll)`. None of them needs Android. Extract them
from `PracticeViewModel` into a plain Kotlin module — the same move that made
`UnitAligner` executable by CI in `9423b53` — with a single entry point that
takes a poll and a recognition result and returns the session's decisions. Then:

- the Python harness reads that module's *output* over a recorded session, rather
  than reimplementing the policy;
- `lock_trace.py`, `word_window_yield.py`, `tail_replay_cost.py` and the probes
  stop existing as policy copies and become drivers;
- a Kotlin-side JUnit test pins the thresholds and the constants, so a change to
  `ADVANCE_COVERAGE` is a compile-visible edit rather than a value duplicated in
  two languages.

If the extraction is judged too large, the minimum version is to make
`lock_trace.simulate` **import** its thresholds from a generated file that
`PracticeViewModel.kt` is checked against, so the two cannot drift silently —
today `evidence_window_parity.py:148-154` checks only `ADVANCE_COVERAGE`.

### 2. Record the session, not the policy. Replay device sessions, not token dumps.

Every divergence in D3, D5, D6 and D12 exists because the harness's input is a
token dump produced by `dump_tokens.py` under conditions the device never has:
no silence gate, no VAD, no not-ready polls, no starvation resets, no clock
skew. The session record already contains most of what is needed — the frame
ring carries `tRel`, `syms`, `obs`, `gen` and the tag per frame, and the
counters carry `gateClosed`, `fedSess`, `evaluations`, `moves`.

Replaying **that** into the extracted policy module would close D3, D5, D6 and
D12 at once, because the record contains the gate-closed frames, the reset
events and the real poll spacing. `session_report.py` already reads these files
off a device; the gap is that nothing feeds them back into a decision.

The three known failures were all found by reading a session record by hand.
This makes that mechanical.

### 3. Replace presence checks with executable checks.

A check that asserts `emissionLog.add(` appears in a function cannot tell
whether it runs. Each of the following should be a mutation test — break the
Kotlin deliberately, confirm the check goes red:

| check | mutation that must fail it |
|---|---|
| emission log | guard at `:836` inverted so the log never appends (D1) — **currently passes** |
| emission log | `resetAudioPipeline` not resetting `sliceStart` (D2) |
| starvation | watchdog thresholds changed or the `idleSec >= 4` gate removed (D3) |
| handoff | `HANDOFF_FRAMES` changed from 3 to 1 (D7) |
| jump | page gate at `:2057` deleted (D8) |
| repeat | `>=` back to `>` at `:2162` (D13) |
| retention | the `a < lockedAyah` branch deleted (D10) |
| gate itself | forward branch disabled — **currently passes** (D18) |

The first and last rows are the ones that matter: they are the two places where
a green gate currently means nothing.

### 4. Fix the gate's own reporting before anything else.

Three small changes, all in `run_checks.py`:

- `lock_trace`: assert on the **final lock** (`final lock=1:7`, `reached`), not on
  the presence of the string `oscillations=0`, and make `lock_trace.py` exit
  non-zero when a clip fails to reach its last ayah.
- `word_verdicts`: read **group(2)**, the failed count, and fail when it is
  non-zero.
- Drop the literal "28/28" and "0 failed" from the detail strings; print what was
  measured.

Until these are in, no other check in the suite can be believed, because the
suite reports success by construction.

### 5. Retire the duplicate policy copies.

`lock_policy.py` (deprecated, still cited from `docs/`), `tail_replay_cost.py`,
`word_window_yield.py`'s embedded lock, `probe_freeze.mirror()`,
`probe_gate_reach.measure()` and `probe_jump_windows.replay_to_jump()` are six
implementations of one decision procedure. After step 1 they should be drivers,
not implementations. Until then, each should carry a header line naming the
Kotlin commit it was last checked against, and `run_checks.py` should fail when
that line is older than the Kotlin file — the drift signal that would have caught
D4, D13 and D16.

---

## Appendix: measurements taken for this audit

All on this repository, with every mutation reverted afterwards (`git status`
clean apart from the pre-existing untracked `docs/TAJWEED_AND_MEMUNISATION.md`).

- **sherpa-onnx contract, verified on the real model** (`engine/shootout/weights/zipformer`,
  1.13.8, `greedy_search`, endpointing off): `rec.tokens(stream)` returns tokens
  **since the last `reset()`** — after `rec.reset(s)` it returns 0, and feeding
  0.5 s of audio plus a decode returns 0 again. `ys_probs` and `lm_probs` are
  **empty** while `tokens` and `timestamps` are populated (44 and 44 against 0
  and 0 on 15 s of audio). This confirms the Kotlin comment at 796-800 and
  `dump_tokens.py`'s note at 16-33. The `OnlineRecognizerResult` struct in
  `c-api/c-api.h:403-431` carries `text`, `tokens`, `tokens_arr`, `timestamps`,
  `count`, `json` — no probability array at all; only the *offline* result has
  `ys_log_probs`.
- **`ready_ratio`** on the six Husary dumps: 0.5175-0.5219. Roughly half of all
  polls do not decode.
- **Emission-log freeze**: session log reaches 7 symbols on s001 (145 emitted)
  and 4 on s114 (83 emitted) under a literal transcription of
  `appendEmissions` with the app's own reset-on-every-move behaviour.
- **Gate blindness**: forward branch disabled → gate still reports
  `PASS lock_trace 28/28 ayat, 0 reversals`. `word_verdicts` with 35/64 failing
  → gate still reports `0 failed`. `appendEmissions` guard inverted in
  `PracticeViewModel.kt` → full 16-check gate reports `0 failed, 0 skipped`.
- **DP orientation**: 57 of 8000 random cases differ between `word_verdicts.align`
  and `UnitAligner.refToQuery` + `derive`; 488 of 127,738 real corpus windows
  (0.382%) differ in coverage, 727 of 2,153,374 word observations (0.034%) in
  status.
- **Verdict rule**: `PhonemeMapper.align`'s `when` block against
  `word_verdicts.statuses_from` and `word_window_yield.verdicts`, exhaustively
  over `(total, ok, bad)` for `total` 0-12: zero mismatches.
- **`probe_freeze.verify()`**: fails on surah 97 — the mirror has no
  pinned-escape branch.
- **`handoff_boundary.py`**: `failures` is appended to at 128-149 and rebound at
  154; reaching any of the first four raises `UnboundLocalError`.