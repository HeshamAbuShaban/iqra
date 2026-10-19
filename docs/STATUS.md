# Status, and what is left

Written after the session-record work. Everything below is measured, not
inferred; where a number is missing that is because it is not yet known, and it
says so.

## Where the app actually is

**The lock works.** Measured over 114 surahs, 6236 ayat, 26 hours of Al-Dosari
audio, plus eight live sessions on the phone:

| | sequential | complete |
|---|---|---|
| first whole-Quran run | 95/114 | 101/114 |
| after the tail-drain fix | 104/114 | **113/114** |
| after the pinned-lock escape | 104/114 | **113/114** |

One surah short (94, an 8-ayah surah that reaches 7/8 because the recording ends
one poll before the confirming frame — a replay limit, not a lock defect).

Across eight live sessions **not one word was marked WRONG or UNKNOWN** before
the evidence floor was added, and none after it either. On live Al-Baqarah the
trajectory was clean: `1:1 … 1:7 → 2:1@40s → 2:2 … 2:11`, 26 moves, 0 reversals.

**The lock was never the problem.** Every defect found in this project has been
in the shell around it — Compose state, page boundaries, word-status plumbing —
and none of them was reachable by a token corpus.

## Fixed, and verified

| what | where | evidence |
|---|---|---|
| Preview stuck on the previous surah | `MainActivity.kt:1371` | `ui_state_parity`, fault-injected |
| Surah-end page advance vs handoff deadlock | `PracticeViewModel.kt` handoff + page | `handoff_boundary`, both directions |
| Page flapping at a surah end | latch on `handoffPageShown` | symptom reported, cause measured |
| False WRONG colouring | `WRONG_MIN_HEARD = 0.80` | 1454 → 876 WRONG on 20 Al-Baqarah surahs |
| 66% of the Quran silently uncoloured | word-aligned table, flat-identical units | `word_alignment_parity`, 31/6236 |
| Stall invisible in every metric | `trailing_stall_sec`, `TraceResult.stuck` | surah 55's 99 s was scored "no stalls" |
| Handoff never executed by any test | `handoff_boundary.py` | `lock_trace.py:61` said so for months |
| Harness never drained the tail backlog | `simulate()` drain | 11 surahs repaired |
| Deadlock state had no exit | `pinned_escape.py` | 55:63 frozen → 55:78 complete |
| Session records lost on process death | timer flush, atomic rename | a killed session now leaves a file |
| Un-anchored head accused on foreign audio | demote to UNKNOWN + note, toggled | the circling experiment + the walk; `advisory_parity` |
| Reader washes synced across page-mates | scope carries the surah | the user's own observation; `paint_scope_parity` |
| Multi-surah page: lock rooted to standing scope | sibling escape (ported from `page_scope_escape.py`) | page-602 walk fires; 0 false escapes on 10 records |
| Sibling escape verified on device, both directions | the user's mid-page walk (record `s-1792255842167`) | `escape → s=107:1 coverage=0.88` at t=6.8s (the start mid-page) and `escape → s=106:1 coverage=1.00` at t=47.8s (the return). Zero false accusations in the whole session; 48 CORRECT / 4 UNKNOWN / 0 WRONG |
| Two faults in my own recorder | `sessionEvaluations`, post-teardown flush | found by reading a real record back |
| Verdicts revoked mid-ayah ("paint and unpaint") | retention chain final at every position, the locked ayah included | `archive_parity` retention model, fault-injected |
| Colour arriving in one batch at the lock move | `ayahObs` serves the ayah being recited next; red still waits for the lock | `evidence_window_parity`; the 93.4%-wide-window measurement |
| Colours wiped when the reciter stopped | the reader clears on the session epoch, not on an empty paint map | `session_epoch_parity`, fault-injected three ways |
| Pointer sitting on the locked ayah while the next was recited | the pointer ayah follows the next ayah's evidence (forward moves only) | `standing_word`, fault-injected five ways |
| Hide toggle reset on every visit | persisted in `ReaderPrefs` like night mode | source; no gate check (reader preference, not engine) |

## Open, ranked by what is actually hurting you

**0b. Jump-swallowed ayahs never get judged (found in the verified walk).**
`lock 1 → 3` fired on 107:1→107:2→107:3 in one poll (jump coverage 0.92 fit a
future ayah) and `107:2`'s four words were never the locked ayah again, so
they retired UNKNOWN - never accused, but also never judged, in a session the
reciter completed. `advanceLockTo` (PracticeViewModel.kt:1695) just moves the
lock; the passed-over ayah has no re-judge path. Design not started. The
walk's smaller artifacts, both recovered: after the escape-back the reader
stayed on the page ahead until the manual turn ("page turn within s=106:
navigation only"), and the first re-recited ayah advanced only via the pinned
escape (18.3 s motionless, nextCov 47% in the dead band) rather than the
normal chain.

**0. Ground truth on the WRONG verdicts — ask the user.**
The user's own sessions produced 16 WRONG verdicts across 699 decided words
(2.3%). All 16 are classified below, but 7 of them fall in classes that could be
either a real error or a table defect, and only the reciter can say which.
That one answer outranks everything else on this list.

**1. Page turn at a surah end — still not right.**
The lock is clean; the page is not. Evidence from the retest: handoff now takes
9–12 s instead of 42 s, and the lock trajectory has no oscillation at all, so what
remains is page behaviour. The cause of the *flapping* was a noisy coverage
threshold with no latch — fixed in `fd4d2e4e`. **Unverified on device.** If it
still misbehaves, the next thing to separate is the case you raised yourself:
continuing *within* a surah across a page break is a different problem from moving
to a *new* surah, and the first belongs to the swipe logic, not to handoff.

**2. Word-level judgement: SOLVED for 6205 of 6236 ayat (was 1220).**
The recognition phoneme table segments on phoneme-*phrase* boundaries — a waqf
mark or an idgham merges two Mushaf words into one entry — so its word count is
lost by the physics of connected recitation, and 4,116 of 6,236 ayat disagreed
with the Mushaf. Every one of those was silently uncoloured.

The fix is `android/app/src/main/assets/word_aligned_phonemes.json`, built by
`engine/replay/build_word_table.py` from `Quran-Lab/quran-tajweed-phonetics`
(ungated, same lab, `quran-lab-npl-1.2`): 522k atomic phones carrying
`word_index`, which is the Mushaf segmentation, plus their own lab's stated
bijection onto our 250-unit inventory (6,236 ayat aligned, 0 mismatches, 0
conflicts). No fuzzy alignment, no new phonemiser.

**The units are the old table's; only the boundaries are new.** The first build
used the tajweed units, which changed the flat unit sequence on 66 ayat. Scored
through the real DP on surah 56 that is 88/93 matched versus 90/94 — the model was
trained with `ة` as `تَ`+`اا` and emits exactly that, so the tajweed inventory's
single `ه` for it is phonetically tidier and empirically worse. Ownership was the
defect; the units were never in question. So the build takes boundaries from
tajweed and fills them with the old table's units, and asserts the written bytes
are flat-identical to `ordered_quran_phonemes.json`:

    flat sequence IDENTICAL to today's table: 6236 ayat
    flat sequence differing               : 0 ayat

`PhonemeMapper.align` runs its DP over the flat list and `unitWord` only assigns
ownership, and the lock reads coverage from that flat list (the word-count check
was only ever in the *painting* path). So recognition is **provably unchanged**:
no corpus re-run, no digest to re-derive. 31 surah-opening ayat, where the tajweed
text and the Mushaf segment the bismillah differently, stay UNKNOWN rather than
being guessed at.

**2b. Every word of every recited ayah was archived SKIPPED. FIXED.**
Your 302 s recitation of 2:59-2:76 on the device produced 365 SKIPPED, 24
UNKNOWN, **0 CORRECT, 0 WRONG** — and the lock tracked you correctly the whole
way, reaching 0.933 coverage on 2:60. So recognition was never the problem.

The cause was in the verdict-retention chain, not the recogniser. Behind the
lock, `align()` is asked to score a word against the **next** ayah's audio,
because the ring buffer no longer holds its units. So SKIPPED there means "the
evidence has moved on", not "the reciter skipped this". The chain read it as
unearned and hit a final `else` that called `sessionStatuses.remove(key)` —
deleting the CORRECT verdict that had been earned a moment earlier. The
retention window was therefore precisely the window in which it was not needed,
and it evaporated the instant it was. The comment directly above that code
describes this exact bug being fixed once before; the fix was defeated again by
the ordering of the `else`.

Terminal verdicts are now sticky behind the lock, first one wins, the same
contract the session archive already uses. Ahead-of-lock words are still
recomputed live. `archive_parity.py` guards it with a retention table and a
wiring assertion, both mutation-tested.

**2c. The evidence window was destroying every verdict. FIXED.**
Reading the session records off the phone: a 302 s recitation of 2:59-2:76 that
the lock followed correctly to **0.933 coverage** recorded **365 SKIPPED, 24
UNKNOWN, 0 CORRECT, 0 WRONG**, and `SKIPPED` painted `wrongColor` with a
strikethrough — so "no evidence" was drawn as "you got this wrong". That was the
red masking, and it was never a wrong verdict.

Words were judged against `obs`, the emission slice since the last lock move, and
that slice is rebased on **every** move. So the audio that would judge an ayah's
words was discarded the instant the lock left it, and behind-lock words were
re-aligned against the *next* ayah's speech.

Each ayah now gets its own window, and finding the right bounds took two attempts
worth recording:

| window | words reaching the CORRECT bar |
|---|---|
| `[arrival(N), arrival(N+1))` — the obvious one | **5.3%** |
| `[arrival(N-1), arrival(N+1))` — the correct one | **93.4%** |

The lock advances when the reciter is 60% through the *target*, so on arriving at
N you are already 60% of the way through it: **N's first 60% is still in the
previous slice.** The obvious window holds only N's tail, which is why the first
version was still 89.5% SKIPPED.

Measured with the shipped rule over 1,378 real words (Al-Dosari, 4 surahs):

    CORRECT 94.8%   WRONG 3.8%   UNKNOWN 1.2%   SKIPPED 0.2%

**The lock policy is unchanged**, so the 26 h corpus result still holds and no
re-run is owed. `evidence_window_parity.py` guards the window, the palette, the
handoff gate and the advance threshold; `word_window_yield.py` re-measures the
yield on demand (too slow for CI).

Also fixed here: `SKIPPED`/`UNKNOWN` no longer paint red at all; the handoff needs
the surah being *left* to be 85% complete for 3 frames (it fired at `coverage=0.63`
35 s into Al-Fatiha, which you then had to fight); and `judgedWords` now counts
CORRECT+WRONG instead of the archive size, which is what made accuracy read 0.0%
over a session nobody attempted.

**2d. My own fix made every word UNKNOWN. FIXED — and this is the second time.**
The 6,205-ayat table landed and the red went away. It went away because every
word became **UNKNOWN**, not because judging worked. Six sessions recorded
`0 CORRECT, 0 WRONG, 0 SKIPPED, 29–333 UNKNOWN` and the report showed no data.

`sherpa-onnx`'s `rec.getResult()` returns the tokens emitted **since the last
`reset()`**, and `resetAudioPipeline()` calls `resetStream()` on **every lock
move** — 19 times in one session, 29 in another. So `res.symbols` restarts near
zero about once per ayah, the arrival indices from the previous round pointed into
a list that had been discarded, every window computed `end <= from`, and empty
reads as UNKNOWN. That is also why Al-Baqarah never started: the handoff needs the
surah being *left* judged complete in its own window, and that window was
permanently empty.

The harness never caught it because it accumulated symbols forever and so never
simulated the reset at all — 94.8% offline, 0% on the phone.

Fixed with a **session-scoped emission log** (bounded at 4000, trimmed O(n) once,
base advanced with the trim) that arrivals index in absolute terms. One subtlety
cost a second round: `emissionBase + sliceStart` looks absolute but is not,
because `sliceStart` is per-stream and restarts near zero. The end of the log,
`emissionBase + emissionLog.size`, is the only true position. With the wrong form
measured, CORRECT collapses to **3.6%**; with the right form:

    CORRECT 94.8%   WRONG 3.8%   UNKNOWN 1.2%   SKIPPED 0.2%
    measured with stream resets ON, which is what the device does

`word_window_yield.py` now models the reset and **fails** if the yield collapses,
so this cannot be invisible again. `evidence_window_parity.py` grew to pin the
log's append/read/clear/trim, the absolute arrival form, and the null-vs-empty
distinction; `handoff_boundary.py` grew to pin the outgoing-surah gate. Between
them, 19 mutations were tried and 5 of the checks passed vacuously at first —
each was rewritten to assert structure rather than a substring.

Also this round: `judged` no longer counts SKIPPED (it made a session that tested
nothing report a large "words judged"); a stale `pendingAnchor` can no longer
block the next surah; the handoff can be armed by an explicit page turn, and only
by a user drag — not by the app's own scrolls. And `noWindowWords` is now in
every session record, so a collapsed window is visible in data rather than
invisible in the UI.

**2e. The gate could not fail. FIXED — and it hid two real defects.**
An audit (`docs/HARNESS_FIDELITY_AUDIT.md`, 22 findings) of where the Python
harness and the Kotlin app disagree found that three checks could not report
failure. Two mattered, and both had been green the whole time.

**The gate was decorative.** `lock_trace` was tested for the substring
`oscillations=0`, which the script prints unconditionally, and its own message
was the literal "28/28 ayat, 0 reversals". Proved by fault injection: with the
forward-advance branch disabled in `PracticeViewModel`, and again with the
harness's `advance_coverage` at 0.99 where nothing can advance, it still passed.
`word_verdicts` parsed only the *checked* count from a two-number line and
printed a literal "0 failed" of its own — so hardcoding the failure count to
zero left the suite green.

Both now assert on measured quantities: the final lock per clip against the
ayah it must reach, and both numbers of the cases line. Verified failing by
disabling the advance branch ("s001: ended at 1:1, expected 1:7").

**The check could not detect a wrong word.** Its substitution test replaced a
word in the emission with a neighbouring word from the same ayah — a near
neighbour, which the DP absorbs. It also counted a substitution "flagged"
whenever the verdict was WRONG **or SKIPPED**, with the failure branch
unreachable. So detection was structurally 0 and the gate reported it as a pass.

The mutation now substitutes a **distant** sound at each consonant, and the
detection ratio is reported and gated. That exposed a real mapper defect.

**A substituted word was recorded as SKIPPED.** Expected `ررَ ح مَ اا نِ` against
emitted `قرَ ك كَ با مِ` — every unit replaced with a distant sound — gives
`ok = 0, wrong = 5 of 5`. The rule tested `ok * 2 < total` before `bad > 0`, so
it returned SKIPPED: "not said". The reciter *did* say something, and it was not
that word. Anyone substituting through a word was recorded as having skipped it,
and the error was invisible.

Contradiction now takes precedence over bare coverage in both the Kotlin mapper
and the harness. Detection went from 0 to complete:

    SUBSTITUTION DETECTION: 81 flagged of 81 cases
    SKIP DETECTION:         81 clean of 81 cases
    314 word-verdict cases across 6 clips, 0 failed

An emission count per word was tried as a second signal and **removed** — fault
injection showed it changed no verdict, because the DP fills every expectation
slot whenever audio is available. Leaving it in would have been decoration of the
same kind.

This did not fix the missing colouring, which is still the emission log freezing
(`noWindowWords=11408`, `emptyWindows=922` in your session). But it means the next
attempt at that fix can be *verified*, which the previous three could not.

**2f. The emission log froze. FIXED, and proved from the user's own recording.**
The screen showed no colouring, no grey, nothing. Found and confirmed with the
device's own poll sequence rather than a model of it:

```
613 polls, 28 stream resets
BEFORE   202 symbols logged, 529 of 613 polls skipped (86%)
AFTER  1,660 symbols logged,   0 skipped
```

`emissionHighWater` was one global mark, but `getResult()` returns tokens **since
the last `reset()`** — so index 5 means "the 6th token of THIS stream", and a mark
that survives a reset compares indices from two streams, always against the
longer. The freeze began at the first reset following a poll that reached 26
symbols; from then on the log never grew, every ayah got a shorter window than
the last, and arrivals eventually ran past the end of the log entirely. That is
`noWindowWords=11408`, `emptyWindows=922`.

The high-water is now cleared on a stream reset, detected by the list being
shorter than the mark or the cursor going backwards. `emission_log_replay.py`
gates this over a committed fixture of the real recording — the check this whole
failure class had no coverage for, because every other check reads token dumps
and dumps contain no stream resets. That gap is why two fixes passed offline and
left the screen empty. Gate is now **17 checks**.

**3. 30.9% of audio time stalled** across the corpus. Mostly fixed (un-drained
backlog, dead-band freeze); what remains is the ~38% of ayat that take twice as
long to confirm. Not yet explained.

**4. Verse-accurate colouring after a verdict.** Known, untouched, parked.

## Discussed and deliberately not done

- **Character/haraka-level preview** — current expected word, and per-character or
  per-haraka correctness. This is the feature that makes the rest matter, and it
  is new surface, not a tweak. Blocked on (2): without a table-to-Mushaf word
  mapping there is no trustworthy word to attribute a character verdict to.
- **Session summary UI.** The records are written and `session_report.py` reads
  them; a browsable history screen is the other agent's.
- **Hesitation clips.** `scripts/collect_clips.sh` is the other agent's. Six real
  clips would let the WRONG floor be set from your voice instead of inferred.
- **32-bit ABI, liquid-glass redesign, icon, `.nomedia`, model downloader.** Parked
  at your request.

## How anything gets verified now

```
engine/.venv-replay/bin/python engine/replay/run_checks.py        # 41 checks, ~160 s
```

Thirty-three run anywhere in CI, in ~50 s. The seven that need the model
report SKIP loudly.

Every check in this repository asserts that a judgement is **correct**. For six
months none asserted that a judgement was **reached** — which is how 66% of the
Quran could vanish without a single test turning red. New checks should ask both.

## What moved last: advisories, waqf junctions, honest toggles

| what | where | evidence |
|---|---|---|
| Decorative toggles made real | `PracticeViewModel` calls `setMaddEquivalence`, `setHeardCoverageFloor`; `SessionReport` reads `diagnostics`; addAdvisory reads `advisoryAlarm`; cue reads `advisoryDisplay` | `pref_consumption.py`, 14 keys |
| Decorative-settings regression gate | `engine/replay/pref_consumption.py` | fault-injected: removing the waqf pref read turns it red |
| Advisory channel | `Advisory.kt`, `PracticeViewModel.addAdvisory` | `advisory_parity`, structural |
| Waqf junction downgrade | `PhonemeMapper.align` exposes `wrongAtWordStart`; `PracticeViewModel` consults `waqfJunctions()` + `waqfNeverAccuses` | `advisory_parity`, `dp_equivalence` |
| In-flow cue: amber wash, suppressed in hide mode | `MainActivity.resolveWordStyle`, `LineText`, `ExpectedWordsLine` | `advisory_parity` |
| Session record lists advisories by reason | `PracticeViewModel.flushSessionRecord`, `PracticeLog.AdvisoryNote` | — |
| `strictness`/`maddNeverAccuses`/`waqfNeverAccuses` no longer UI-only lies | — | `pref_consumption`, new gate |

Madd tolerance was verified on-device pre-fix via 16/18 ayah-final WRONGs
having aligned audio that a madd class cannot adjudicate anyway; the canonical
madd-ID tolerance shipped in `71a863a`. Its effect on the live verdict stream
is still pending a fresh user session.

## Measured on the user's own sessions, 8 July 2026

Pulled from `filesDir/sessions/`. Seven records, two of them substantive.

| session | length | lock | words evaluated | decided | CORRECT | WRONG | UNKNOWN | noWindow |
|---|---|---|---|---|---|---|---|---|
| Fatiha → 3:1 | 476 s | 28 moves | 366 | 286 | 275 | 11 | 78 | **0** |
| Al-Mulk 67 → 106 | 406 s | 105.6 wpm | 333 | (see below) | 322 | 5 | 6 | **0** |
| 108:1–3 | 28 s | 2 moves | 10 | 5 | 5 | 0 | 5 | 0 |
| 112:1–4 | 17 s | 3 moves | 15 | 14 | 14 | 0 | 1 | 0 |
| 111:1–5 | 30 s | 4 moves | 23 | 22 | 22 | 0 | 1 | 0 |

**The stream-log repair holds on device.** `noWindowWords=0` and
`emptyWindows=0` across every session, where the same counters read 11,408 and
922 before `686380e`. That was the defect that made a clean recitation look
silent.

**The madd tolerance did what it was built for.** 16 of the 18 WRONG verdicts
in the last pre-fix session sat on ayah-final words, where a madd length cannot
be adjudicated. In the new sessions only 3 of 11 sit on ayah-final words
(`1:6:3 ٱلْمُسْتَقِيمَ`, `2:18:6 يَرْجِعُونَ`, `2:24:13 لِلْكَـٰفِرِينَ`); the rest
are mid-ayah. Verified per word against the mushaf, not by eye.

### Two defects the sessions exposed

**1. Browsing destroyed the statistics of the recitation you had just done.**
The Al-Mulk record reports `moves=0, reversals=0, judgedWords=0, fedSess=0`
alongside `evaluatedWords=333` and 322 CORRECT — a body that contradicts itself,
for a session whose diagTail plainly shows `lock 14 → 15`, `lock 15 → 16`.

Cause: `endSession()` called `resetSessionCounters()`, and `endSession` also
runs for every page jump (`reason=preparing-cancelled`, `ran=no`, `durMs=0`).
The diagTail shows 19 such generations between the recitation and the final
flush (g15–g21: 78, 106, 3, 3, 3, 106, 106). Each one zeroed the counters of
the record still open — while `sessionArchive` and `sessionEvaluations`, absent
from that reset, survived. So the verdicts were intact and the headline numbers
were zero. Worst possible order of failure: the record still looks real.

Fixed by removing the reset from `endSession`; `startRecite` already clears
everything. Counters now live from one `startRecite` to the next.
Gate: `session_record_integrity.py`, fault-injected three ways.

**2. A frame-shaped advisory was accumulating like an event.** `LOW_EVIDENCE`
reported **1,455 occurrences across 85 words** in the 476 s session. It fires
once per frame a slightly ambiguous word is judged, so the tally measured how
long the microphone was open, not how many words were affected. Split into
`PER_EVENT` and `ONCE_PER_WORD`; the word count is now the honest figure.

### The residual WRONG words are identifiable, not mysterious

All 16 across the two substantive sessions, matched to the mushaf:

| class | n | example |
|---|---|---|
| ends in a letter not required to be sounded (۟ ۢ ۭ) | 5 | `تُفْسِدُوا۟`, `أَلِيمٌۢ`, `أَندَادًاۭا` |
| ayah-final (madd or end form) | 3 | `ٱلْمُسْتَقِيمَ` |
| `-هُمْ` / `-كُمْ` / `-تُمْ` endings | 4 | `أَصَـٰبِعَهُمْ`, `يَنصُرُكُمْ` |
| other mid-ayah | 4 | `وَقُودُهَا`, `وَإِنَّمَآ` |

The first class is the one the new `UNMODELLED_FINAL` guard removes: a word
whose text carries a high mark licensing an unsounded final letter cannot be
evidence of error, and 3,559 mushaf words end in `۟` alone. It only fires when
the substitution is on the word's **last** unit, so a garbled middle still
becomes a verdict.

**Honest caveat:** on the reference reciter's own surah-67 recording the one
WRONG word (`67:10:1 وَقَالُوا۟`) has its substitution *not* on the final unit,
so the guard does not catch it. The whole-corpus sweep has now run: it removes
**2%** of them, not most. See "The whole-corpus false-accusation sweep" below.



Deferred legal-realisation alternates (wasl/waqf acoustic forms). Full-madd
free-choice recognition (the engine never hears 2-vs-4 digits). The
standing-word pointer. Page-turn device validation. UI polish (type sizes, RTL
on Live, session list). See `docs/TODO.md`.

## Maintenance and upgrading

`docs/CORE_LOGIC.md` describes what the app does at each step, in plain English
built toward ASD-STE100 (short sentences, one idea per sentence, the active
voice, approved words). Any change to the alignment, the lock policy, the
verdict rule or the record format must update that file in the same commit.

Five new gate checks now hold the work in place, and each one has been shown to
fail by fault injection:

| Check | Stops |
|---|---|
| `dead_code.py` | A declaration or a FILE in the recognition surface that no file references. Removed 43 dead declarations and one unreachable screen. Known limit: it counts identifiers, not symbols, so a duplicate name shares another name's reference count. |
| `unused_imports.py` | An import that names a component that is not used — the residue of a deleted feature. Removed 126. Operator imports (`getValue`/`setValue`) are exempt because `by` delegates with no textual trace. |
| `honest_numbers.py` | "No data" and "zero percent" collapsing to the same value; the ayah strip sorting two surahs together; the live list painting a whitespace index; a `heard` count that includes words the engine could not hear. |
| `session_record_integrity.py` | A counter that a browse wipes; timing constants derived from the declared poll interval instead of the measured one; the handoff boundary returning to a page boundary. |
| `pref_consumption.py` | A switch that nothing reads. |

### Two defects in the checks themselves, found while writing this

1. `dead_code.py` required four spaces of indentation, so it silently skipped
   **every** declaration in `MainActivity.kt`, which declares at column 0. It
   reported dead items in `PracticeViewModel.kt` and passed over a whole file of
   them in `MainActivity.kt`. Fixed to `^[ \t]{0,4}`; the count it covers went
   from 832 to 972.
2. `handoff_boundary.py` grepped for a variable called `atScopeEnd` that I had
   just deleted. The check failed on a **name**, not a behaviour — the exact
   failure its own comment warns about. Rewritten to test the surah-boundary
   guard and re-fault-injected; restoring the page boundary turns it red again.

Both are recorded because "the check was written to catch this" is a claim that
has to be tested, not asserted.

### Deletions, with the reason each went

| What | Why |
|---|---|
| `ui/ProgressScreen.kt` (554 lines) | Unreachable: `Screen.Progress` has called `PracticeScreen` since `592b37f`, and nothing names anything it declares. It also duplicated the live screen and computed mastery with the wrong denominator. A screen nobody can reach still has to be right. |
| `ml/Levenshtein.kt` | Its only consumer was `ratio`, deleted earlier; after that `distance` had no caller either. |
| `IqraType` (9 styles) | 4 of 9 used. The other 5 nobody named. |
| `IqraMotion` (6 specs) | 1 of 6 used. |
| `Tokens.forVerdict` | Its doc claimed it unified verdict colour across `resolveWordStyle` and `LiveWord`. It was referenced by nothing, and both renderers still carried their own five literals. A comment that describes a state the code did not reach is worse than no comment. |
| `Charts.RankedBars`, `Charts.Sparkline`, `LiveMode.LiveStat`, `MainActivity.darkMushafScheme`, `MainActivity.ParchmentScaffold`, 5 shapes and 3 motion specs | Declared, referenced from nowhere. |
| `AssetPaths.privateRoot/engineDir`, `DataFetcher.Cancelled`, `QuranData.getSurah`, `Verse.WordResult`, `AudioRecorder.isRecording` | Dead helpers, including a cancellation exception that was never thrown. |
| `WordStatus.EXTRA` | Kept out of the deletion: it documents a state the aligner deliberately does not report. Removed from `CORE_LOGIC`'s state table instead, where it was being read as an available verdict. |

### Measured from the user's sessions, 9 October 2026


| What | Where | Evidence |
|---|---|---|
| Browsing wiped a recitation's statistics | `endSession` no longer resets counters | Al-Mulk: 322 CORRECT next to `judgedWords=0, moves=0` |
| Per-surah totals inflated on every re-fold | `PracticeLog.foldedSurahs` | 24 re-folds per ten-minute session |
| Null accuracy and 0% accuracy encoded the same | series encodes `-1` for null | a failed session dropped out of the trend |
| The live list painted a whitespace word index | `LiveMode` uses `vm.standWordsFor` | `بَعْدَ مَا` is one mushaf word |
| `AdvisoryDisplay.OFF` changed nothing | the report lists the setting | QUIET and OFF were byte-identical |
| `maddNeverAccuses`'s doc promised what the code did not keep | doc rewritten | turning it off DOES make length accusable |
| `consecutiveRetreats`, `noiseFloor`, cadence leaked between sessions | `resetSessionCounters` | a new session inherited a loud room's floor |
| Timing constants built on a 250 ms poll that runs at 563 ms | `pollSec` measured | wrong-latch and words-per-minute both wrong by 2.25x |
| The gated long jump skipped one ayah | **NOT fixed** | 18:65→18:67 and 18:91→18:93, `nextCov=0.50` |
| `noWindowWords` returned on a long session | **NOT fixed** | 2,501 words / 212 windows over 1,428 s |

### Measured from the user's sessions, 9 October 2026

| session | length | lock | evaluated | decided | CORRECT | WRONG | noWindow |
|---|---|---|---|---|---|---|---|
| Al-Isra 17 → Al-Kahf 18 | 1,428 s | 106 moves | 1,584 | 1,127 | 1,028 | 99 | **2,501** |
| Al-Mulk 67 → 105 | 302 s | 28 moves | 333 | 269 | 258 | 11 | 0 |
| Baqarah 2:141–158 | 268 s | 15 moves | 336 | 278 | 260 | 18 | 0 |
| Baqarah 2:157–177 | 383 s | 16 moves | 433 | 232 | 219 | 13 | 0 |
| Baqarah 2:176–3:1 | 168 s | 4 moves | 149 | 43 | 40 | 3 | 0 |
| Baqarah 2:182–187 | 654 s | 4 moves | 188 | 104 | 96 | 8 | 0 |
| Al-Kafirun 109 | 23 s | 5 moves | 26 | 22 | 22 | 0 | 0 |

## The whole-corpus false-accusation sweep: what it found

`engine/replay/optional_final.py` ran over all 114 reference-reciter dumps
(6,112 ayat judged of 6,236, 26 h of audio). The reference reciter is Al-Dosari,
so every WRONG verdict the harness finds is a false accusation:

| | |
|---|---|
| WRONG verdicts on a reciter who made none | **768** |
| the FIRST word of its ayah | **320 (42%)** |
| carrying a stop mark (`۟ ۢ ۭ ۖ`…) | 132 (17%) |
| wrong on the word's own final unit | 173 |
| removed by the optional-final guard | **14 (2%)** |

**The optional-final guard is not the fix I thought it was.** It removes 2% of
the corpus-wide false accusations, against 5 of 16 measured in the user's own
sessions — a small device sample that happened to be enriched for exactly that
class. On the corpus the guard is marginal. It is kept because it removes blame
and cannot add it, but it must not be presented as the answer.

**The dominant class is the FIRST word of an ayah, and I have not found the
cause.** Three hypotheses were measured and rejected, in
`engine/replay/window_lead.py`:

| Hypothesis | Measurement | Result |
|---|---|---|
| The window includes the previous ayah's tail and the first word loses to it. Trim the lead. | Surahs 18 + 3, trims 0–8. At trim 1, **every** first word collapses to SKIPPED (5 CORRECT remain). | **Rejected.** The overlap is load-bearing; removing it deletes the evidence. |
| Skip exactly the symbols the PREVIOUS ayah consumed, derived from its own alignment. | Same run. Median offset consumed **54 symbols** — the previous ayah's units align greedily across this window and eat all of it. | **Rejected.** Worst of the three: 0 CORRECT, 304 SKIPPED. |
| The first word's phonemes are a contiguous run inside the previous ayah's, so the window is genuinely ambiguous. | 329 first-word WRONG ayat. Overlap in 5% of them; the control group of clean ayat overlaps in **29%**. | **Rejected, and inverted.** |

So the number is real — 42% of the reference reciter's false accusations are on a
first word — and the window boundary is not why. The next candidates, in order,
are the emission side rather than the table: the Zipformer's rolling state
carries one ayah's tail into the next and drops or distorts the ayah's opening
phoneme; or `ayahArrival` is written one poll late, so the window's left edge is
quantised to a poll bucket and a fast reciter's first word falls outside it
entirely. Both are measured from the frames, not the table, and both need a
harness that reproduces the app's own window rather than the tight-fit window
the sweep used.

**That last point is a caveat on everything above.** `optional_final.py` and
`window_lead.py` score against a window that `local_window()` fits tightly to the
ayah. The app scores against `ayahObs`, whose window is three ayat wide and starts
at the previous ayah's arrival. `docs/HARNESS_FIDELITY_AUDIT.md` records 22 places
these two have already disagreed, so the 768 may be a different population from
the 99 WRONG verdicts the Al-Kahf session produced. Treat the count as evidence
that a class exists, not as a count of what the user saw.

## The device says something different, and it outranks the sweep

Same six device sessions, every WRONG verdict matched against the mushaf, the
app's own window (152 verdicts):

| Class | Device | Corpus |
|---|---|---|
| the FIRST word of its ayah | **15 (10%)** | **320 (42%)** |
| carries a stop mark (`۟ ۢ ۭ ۖ`) | 34 (22%) | 132 (17%) |
| ayah-final word | 11 (7%) | 173 (23%) |
| plain mid-ayah, no mark | **108 (71%)** | — |

**Two things follow, and both change what work happens next.**

1. **The harness's 42% first-word figure is an artefact.** The device blames the
   first word of an ayah 10% of the time. `docs/HARNESS_FIDELITY_AUDIT.md` already
   records 22 divergences; this is entry 23, and the largest. Do not chase the
   first-word class on the strength of a harness number.
2. **The dominant class is the plain mid-ayah word with no signature** - 71% of
   the device's accusations. It is not a mark, not the ayah's opening, not the
   ayah's final word, and not a pronoun ending. Nothing textual separates it
   from a correct word, and the corpus cannot be used to study it, because the
   corpus produces a different population.

Neither figure could say WHERE in a word the mismatch sits, and that is the one
question that separates a boundary artefact from a wrong phoneme. So `wu` was
added: each WRONG word in the session record now carries the fraction of its own
units at which the contradiction begins, 0..1000.

| Tool | What it does |
|---|---|
| `engine/replay/device_blame.py` | Reads `wu` from a session record and buckets it. |
| `engine/replay/device_blame_parity.py` | Holds the writer, the reader and the buckets honest (7 injections red). |

Run it after the next device session:

```
adb -s 35d5637e shell run-as com.iqra.quran cat files/sessions/<file>.json > /tmp/x.json
python3 engine/replay/device_blame.py /tmp/x.json
```

**Until then this is the one number that matters:** the app cannot yet explain
71% of its own accusations, and it cannot say which of them are false. Every
session before this build records nothing, because the field did not exist.

## What the blame position settled, and what came out of it

The user's 10 October session carried the new instrumentation for the first time.
Its ten WRONG words were read with `device_blame.py`, and the answer was not the
one the corpus had led me to expect:

| | |
|---|---|
| blamed on the word's LAST unit | **9 of 10** |
| blamed on the first unit | 1 |
| carrying a silent-letter mark | 0 |

The nine are the plural masculine and pronoun endings — `ٱلضَّآلِّينَ`,
`يُنفِقُونَ`, `ٱلْمُفْلِحُونَ`, `بِمُؤْمِنِينَ`, `يَعْمَهُونَ`, `وَتَرَكَهُمْ`,
`يَرْجِعُونَ` — and **none** carries a mark, so the optional-final guard could
not see them.

The cause is in the table. `يُنفِقُونَ` is stored as `يُ ںںں فِ قُ ۥۥۥۥ ن`, so its
last unit is a bare `ن`. The model emits `نَ`, because a reciter says the
fatḥa, and those are **different tokens** in tokens.txt. **26,733 of the table's
77,481 words — 34.5% — end on a bare consonant with no i'rab vowel**, and the
reference reciter emits both forms in quantity (`نَ` 680 times, bare `ن` 450 in
Al-Baqarah), so neither can be discarded.

The fix interns every variant of one word-final consonant to one id, exactly as
the madd rule does, applied only at a word's last unit. It can remove blame and
never add it. The letter set is measured, not chosen: `ن ه م ر د ب ة ق ل ك س`
and the other carriers of the defect. Waw and ya are excluded — as semi-vowels
their vowels are their own sound, and relaxing them would hide a real error.
Gate: `final_vowel_parity.py`, five injections red.

**The 71% had a cause. It was not tajweed, and it was not the lock.**

## Page turn: what the record showed

The same session, from its own diag tail:

```
t=15981  lock 6 → 7                        (last ayah of Al-Fatiha)
t=15981  page turn onto s=true: intent handoff armed
t=15991  page turn onto s=true: intent handoff armed
t=16002  handoff → s=2:1 coverage=0.63 via=page-turn-intent
```

The reader turned the page and the lock took **21 seconds** to admit it, so for
21 s the screen showed Al-Baqarah while the highlighted ayah sat on a surah that
was no longer visible. Nothing was misjudged; the app simply did not follow the
reader.

The lock now follows the PAGE when the reciter goes quiet for 6 s with the
intent armed. Nothing is credited — the new ayah starts unjudged — so it cannot
accuse anyone. Gate: `advisory_parity` asserts the follow archives no verdict,
runs only in silence, and waits a real pause.

## Tap and long-press, after reading the reference

The reference reader's `AyahSelection` is a sealed state — `None`, `Ayah`,
`AyahRange` — and that is the "smart" part: the mode is explicit state, not a
guess from gesture length. This app now does the same: long-press selects,
long-press again extends a range, a tap collapses it, the chrome ends it, and
double-tap moves the lock.

The first implementation had the range anchored on `_selectedRange`, which the
previous press had just set to null — so **the branch that opens a range could
never be reached**, and long-press was a single-ayah selector wearing a range
selector's clothes. `selection_parity.py` now holds the state machine, and one
of its injections is exactly that bug.

## Hide mode, and the progress it never showed

Hide mode masked the words but showed nothing about how far the recitation had
come, so a memoriser could not see the progress they would have seen with the
words revealed — the "same relation of the unhidden one" that was asked for. A
compact strip now sits under the reader bar in hide mode: one cell per ayah of
the surah in scope, filled by the share of its words heard, with the locked ayah
marked. It is a bar and not a percentage, because a percentage there would read
as a score of the reciter, and it is not one.

## The magnifier

It opened a "go to page" box, which is a different job: a reader looking for a
word wants the word. The search engine was already written, normalised and
folding the alef variants — it simply was not reachable. The magnifier now lists
live matches and jumps to the ayah; "Go to page" moved into the same sheet so
the feature survives.

## Recited under noise, 15 October 2026 — and what it settled

Four sessions, recited through the app in a room with background noise, on the
build carrying every fix below.

| session | length | lock | moves | evaluated | decided | WRONG | noWindow | pollSec |
|---|---|---|---|---|---|---|---|---|
| 1 → 2:20 | 300 s | 2:20 | 25 | 269 | 193 | 10 | **0** | 0.556 |
| 1 → 1:7 | 55 s | 1:7 | 6 | 29 | 29 | 4 | **0** | 0.299 |
| 109 complete | 102 s | 6 | 4 | 26 | 20 | **0** | **0** | 0.310 |
| 112 complete | 52 s | 4 | 3 | 15 | 14 | **0** | **0** | 0.288 |

**What held.** Three surahs reached their final ayah, and two of the three
produced **zero** WRONG verdicts on noisy phone audio. `noWindowWords` is 0 in
every session including the 300 s one — the window repair holds in the
condition the app is actually used in.

**What did not, and it is the important one.** 14 WRONG verdicts, and 10 of
them (71%) are still blamed on the word's **last unit** — the same class the
final-vowel rule was written for. Every last unit is a bare `م`, `ن`, `هُ` or
`رَ`: letters inside `FINAL_VOWELLESS`, where `final_vowel_parity.py` proves the
rule fires, and the APK on the phone was built four hours after that fix shipped.

So the emission is **not** the bare/vowelled pair the rule covers. `wu` says
where in the word the blame lands and `wt` says when it was decided; neither says
*what was heard*, and nothing on the record could say either. Until it can, any
further change to the rule is a guess — and this project has already paid for
enough of those.

**The noise itself shows up in the cadence.** `pollSec` rose from 0.299 to
**0.556** under noise, so the rate at which audio reaches the model is a third
slower in the one condition the app exists for. Whether that is the VAD being
honest or the RMS/VAD path costing polls is not yet separated.

**The one change made:** the record now carries the emitted phoneme stream, the
absolute base its indices are against, and the per-ayah arrival that defines the
judged window. `device_blame.py` reconstructs the window and prints, per WRONG
word, the expected units beside what was actually emitted. That is the missing
half of the diagnosis — it is written, it is gated, and it has not yet been
exercised by a real session.

## The LiveTest bug that ended a test before it began

Reported by the user from real use: *"a bug that happened almost every time I
wanted to LiveTest screen, I feel that it comes after a successful session, which
makes the buggy one end fast on it, something to do with finalizing and loading."*

The mechanism, read off the code: `LiveMode.kt` ends a test when the scope's last
ayah reads `decided >= words`. The value it reads is `vm.scopeProgress` —
**ViewModel state, which outlives the screen.** Open Live straight after a
finished session and the screen composes against the *previous* session's
progress, where the final ayah already satisfies that test. The new test is
declared complete and the app navigates to the previous session's report.

Two faults, both fixed and both gated:

| fault | fix |
|---|---|
| the auto-finish's guard was asymmetric — its sibling path in the same function is gated on `everRecorded`, this one guarded only the `stopRecite` and left `finished = true` unconditional | the guard is now on the assignment's own path, and the effect keys on it |
| `scopeProgress` was only *rebuilt* at the next start, so a complete scope survived the teardown into the next screen | `clearScopeProgress()` runs from `resetSessionUi`, which every end path reaches |

Gate: `live_scope_inherit_parity.py`, four injections red. Two of those injections
were defects in the CHECK that had to be fixed first — it counted `everRecorded`
appearing anywhere in the block, including the key list, so deleting the guard
statement entirely left it green while the bug was fully restored. A mention is
not a guard.

**Verified against your four noise sessions** (19:39–19:49): three surahs
completed, two with zero WRONG verdicts, `noWindowWords=0` throughout. The
sessions themselves were healthy; the defect was entirely in the hand-off
between one session's end and the next screen's start.

## Where everything is

Answered directly: **no, the phases are not all done.** Phase 2a's first piece
shipped — the record now carries the emitted phonemes, the absolute base, and
the per-ayah arrival, and `device_blame.py` reconstructs the judged window. The
*desktop replay screen* and the *on-phone screen* are not built, the engine
comparison has not run, and the crowd handling is untouched. `docs/TODO.md`
carries them in order, and the noise sessions put one question at the top that
all of it depends on.

## The four noise sessions of 16 October — and a trap in my own tool

Four sessions, all carrying the new emission stream. The honest result is that
**the record cannot yet answer the question it was written to answer**, and the
reason is a defect in the record plus a defect in the tool that read it.

| session | length | decided | WRONG | noWindow | pollSec |
|---|---|---|---|---|---|
| 1 → 2:1 | 109 s | 19 | 2 | 0 | 0.323 |
| 2:1 → 2:12 | 240 s | 83 | 8 | 0 | 0.291 |
| 105 → 2:17 | 95 s | 21 | 4 | 0 | 0.289 |
| 2:1 → 2:25 | 233 s | 148 | 12 | 0 | 0.301 |

`noWindowWords` is 0 in every one, on a phone in a noisy room, over 677 seconds
of recitation. That part of the app is sound.

**The trap.** `device_blame.py` reported windows like `[0:1213]` — the entire
session — and used them to "explain" 10 of 26 false accusations. Those windows
were fabricated. The record's `arrivals` array is pruned to the lock's vicinity
while the session runs, so most ayat have no neighbour entry; the reader filled
each gap with a default, `ayah-1` → 0 and `ayah+1` → the end of the log. The
result looked exactly like data. It was 1,200 symbols of the wrong ayah.

The emission replay already taught this lesson in `686380e`: its fallback
defaulted to the total length, and it validated a rule that was never applied.

Two fixes, both gated:
1. the **record** now filters arrivals to the retained emission log, so an
   arrival pointing at symbols that no longer exist is not written;
2. the **tool** now returns "NOT RECONSTRUCTABLE" instead of inventing a span.

Gate: `device_blame_parity`, six injections red — including one that reinstates
the fabricated window.

**So the last-unit class is still unnamed.** 20 of 26 (77%) remain. What changed
is that the tool can no longer lie about it. To close it, the arrivals must
survive for the whole retained log rather than `lockedAyah ± 3`; that is a
one-line change to `pruneAyahWindows` and it is the top item in `docs/TODO.md`.

**And one unrelated bug visible in these records:** session three starts in
surah 105 and ends in **surah 2 at ayah 17**. Al-Fiil has five ayat. Whatever
carried the lock from 105 to 2 covered the wrap from 114 → 1 and most of
Al-Baqarah in four moves, which is not recitation.

## Why the record could not rebuild its own windows — fixed

The user's three sessions of 16 Oct still carried only 4 arrivals each. The
cause was a mismatch, not the pruner being aggressive on its own:
`emissionLog` retained 4,000 symbols while `ayahArrival` retained
`lockedAyah ± 3` — three ayat against over a hundred. Every ayah the user
recited early in a session therefore had no arrival when the record was written,
and the replay that read a WRONG word back filled each gap with a default and got
a window spanning the whole session.

**Fixed by making the two retentions agree**, not by making the fallback cleverer:
`RETENTION_AYAH_SPAN = 110`, which covers the 4,000-symbol log with margin.
`arrival_retention_parity.py` pins `span * 40 >= EMISSION_LOG_CAP`, requires the
pruner to derive *both* edges from the constant, and requires the reader to refuse
a window it cannot rebuild. Six injections red.

One of those injections exposed a bug in the check itself: it grepped for the
constant appearing anywhere in the file, so a pruner that had reverted to ±3
while the constant still existed passed. It now requires the constant twice
inside `pruneAyahWindows`.

**Verified on the phone:** build installed 02:58, gate 34/34, JVM tests pass.
**Not yet verified:** that a fresh session produces rebuildable windows, because
these three records predate it. That is the top item in `docs/TODO.md`.

## What the three sessions did confirm

665 seconds, 24 WRONG verdicts, `noWindowWords = 0` in every one, on a noisy
phone. Two sessions ended with the lock in surah 2 and one in surah 1 — no more
of the 105 → 2 wrap seen earlier, though that is three sessions and not a trend.

## A regression I caused, measured, and fixed properly

The user's report was blunt and correct: *"the core function is now weaker then
before!"*

The measurement, from their own sessions, over two builds:

| build | decided words | WRONG | UNKNOWN | correct-of-decided |
|---|---|---|---|---|
| before the window trim | 219 / 71 / 42 | 9 / 2 / 1 | 39 / 4 / 0 | 95.9% / 97.2% / 97.6% |
| with the window trim | 19 / 8 | 3 / 0 | 24 / 21 | 84.2% / 100% |

**A third of every decided verdict, gone.** The trim that was supposed to fix
ayat-boundary blame was starved windows instead.

The cause was arithmetic, not tajweed:

```
ayahSpanEnd[N] = ayahWindowStartOf(N) + lastHit + 1
ayahWindowStartOf(N) returned the ALREADY-TRIMMED start
```

So each stored end was measured from a window that had already lost the previous
ayah's tail, the next ayah was trimmed to it, and because a stored end only ever
moved forward the shortfall never recovered. Over 28 ayat the windows shrank
accumulatively.

The record proved it independently of the word counts: consecutive arrivals in a
healthy session land 36-82 symbols apart, and in the starved ones 15-36.

**Fixed with two bounds, both measured rather than chosen:**

1. `MIN_WINDOW_SYMBOLS = 32` - the trim may never leave a window shorter than an
   ayah's own audio. An ayah spans ~30-40 symbols, so 32 still leaves the DP a
   beginning to find.
2. `ayahWindowStartOf` returns the RAW window start, so a stored end denotes a
   real position in the audio and cannot ratchet.

Gate: `window_anchor_parity`, six injections red — floor removed, floor not
applied to the right edge, edge moving backward, origin re-trimming, `ayahObs`
bypassing the helper, constant undeclared.

**Not yet verified on device:** whether the bounded trim actually reduces the
boundary blame without cost. The floor at 32 of a ~36-82 symbol spacing means the
trim can now only take a few symbols, so the boundary fix is correspondingly
modest - which is the honest expectation, and a larger fix needs the measured
waqf mappings this project does not yet have.

**The lesson worth keeping:** the first version of this check asserted the trim
was correct, and it stayed green through the whole regression. A check that
passes while the thing it guards loses a third of its output is not a check. The
replacement asserts the two bounds that make the trim safe, and it fails if
either goes.

## The page-turn "corruption" was one line of the boundary test

The user described it as *"you'd see jumping unwanted... glitching in page
swiping... missbehaving lock on supposedly ayah."*

Cause, in `onUserPageTurn`:

```kotlin
val opensNewSurah = surahsOnPage.any { it > activeSurah }
```

A page counts as a surah change when it contains **any** later surah. But **51 of
the 604 pages hold two surahs**, and in reading order:

| page | holds |
|---|---|
| 106 | `4:176` **and** `5:1` |
| 221 | `10:107` and `11:1` |
| 235 | `11:118` and `12:1` |
| 293 | `17:105` and `18:1` |

So a reciter finishing surah 4 swipes forward onto page 106 — a page that
*continues* surah 4 — and the app arms the handoff and moves the lock to `5:1`,
skipping the rest of surah 4. **58 such false arms exist across the mushaf.**

Measured over a full forward and backward walk of Al-Baqarah: **zero** false
arms, because no mid-surah page ever holds a later surah. That is why the defect
looked like corruption rather than a rule — ordinary navigation was clean, and
the failure appeared only at surah boundaries, where it skipped ayat.

The fix: a page opens a new surah only when it holds **no** word of the surah
being recited.

```kotlin
val opensNewSurah = surahsOnPage.isNotEmpty() && currentSurah !in surahsOnPage
```

Gate: `page_turn_boundary.py`. It evaluates **both** rules over the real layout
and fails if the loose rule ever measures safe (which would mean it had nothing
left to guard) or if the narrow rule is inverted or treats an empty page as a
handoff. Three injections red.

**Still open, unchanged:** the three scenarios the user named — mid-surah
navigation, surah-end handoff, and swipe-onto-a-new-surah intent — have never
been walked on device end to end. The intent path itself now has the retention
fix and the LiveMode inheritance fix, and these two were real defects, but the
scenario test above is what proves them together.

## The standing word, confirmed null from the record and fixed

The user reported *"standingWord always null"*: the highlight disappears exactly
when an ayah is finished.

Confirmed from their own sessions before changing anything. Session
`s-1792114078210-102` ended with `103:3` fully decided - **9 words, 9 of them
CORRECT or WRONG**. The pointer's derivation walks past a verdict to the
following word, so an ayah with nothing left to walk to returns null:

| session | final ayah | words decided | pointer |
|---|---|---|---|
| 2:19 | 2:19 | 18 / 19 | held |
| 100:11 | 100:11 | 4 / 5 | held |
| **103:3** | 103:3 | **9 / 9** | **was null** |
| 2:2 | 2:2 | 3 / 7 | held |
| 1:7 | 1:7 | 4 / 9 | held |

The fix adds an explicit null-guard after the verdict walk: fall back to the
locked ayah's own **last** word - a real word of what is being recited, present
whenever `lockedAyah` is, and semantically the end of the ayah just completed.
Never the first word, which would jump the pointer backwards onto material
already judged.

Gate: `standing_word.py`, five injections red - fallback removed, first-word
instead of last, wrong-ayah scope, fallback hoisted above the walk (so the
pointer would always sit at the ayah end), and the verdict walk itself removed.
The ordering assertion exists because that last mutation would otherwise be
invisible: the fallback would still exist and still work, just in the wrong place.

## The last-unit class is a WINDOW problem, and it is proved

Your four sessions of 16 October, on the build with the retention fixed. The
retention fix held: 19 arrivals against 28 moves, 11 against 17, 3 against 3.

| session | decided | WRONG | correct | noWindow |
|---|---|---|---|---|
| 1 → 2:19 | 219 | 9 | 96% | **0** |
| 99 → 100 | 71 | 2 | 97% | **0** |
| 102 → 103 | 42 | 1 | 98% | **0** |
| 98 | 0 | 0 | — | 0 |

**And 11 of the 12 false accusations sit on an ayah BOUNDARY** — six on an
ayah's first word, five on its last. The two windows the record could rebuild
showed the cause outright:

- `100:8:1` expects `وَلِلَّهِ`; its window opens on a different ayah's text.
- `2:2:2` expects `بِالْكَافِرِينَ`; its window holds Al-Fatiha's.

The window starts at `arrival(N-1)` because the lock arrives mid-ayah and N's own
opening is still in the previous slice. That part is necessary — the 5.3% versus
93.4% measurement is real. The artefact is that the previous ayah's TAIL then sits
at the leading edge and is blamed for the next ayah's first word.

**Fixed by trimming that edge, not by moving it.** `ayahSpanEnd` records where
each ayah's speech actually ended, from the alignment's own `emitWord`, and
`ayahObs` starts the next ayah just past it. The trim is gated three ways: only a
recorded end, only forward, only strictly inside the window — so it can remove
blamed prefix and cannot invent blame.

Gate: `window_anchor_parity`, five injections red. Two of them exposed bugs in
the check itself — a non-greedy regex truncated `ayahObs`'s body and the check
passed on a fix it had never seen, and testing only one of the two functions
that read the map left a default position undetected. Both fixed.

**Not yet verified on device:** whether the trim actually reduces the boundary
blame. Three things are uncertain — the first frames after a reset may have no
recorded end and fall back to the old behaviour, which is safe but unchanged; and
a surah handoff crosses into a fresh `ayahSpanEnd`. One more session will say.

## Two long sessions, 16 October — repeats, backtracks, and what they settled

The user's two longest sessions on record, recited with background noise and
with repeats and backtracking deliberately included ("not ideal actual human
testing" — kept as the regression corpus for exactly that reason):

| session | length | moves | rev | evaluated | decided | correct | UNKNOWN | noWindow |
|---|---|---|---|---|---|---|---|---|
| surah 18, 110 moves | 3,419 s | 110 | 2 | 1,579 | 1,049 | **92.6%** | 490 (31%) | 3,889 |
| surah 35, 41 moves | 692 s | 41 | 0 | 775 | 499 | **94.8%** | 238 (31%) | 0 |

**On "weaker than before":** correct-of-decided is 1–3 points below the clean
5-minute baselines (95.9–97.6%), on sessions that are 10× longer, noisy, and
full of repeats and 20–50 s pauses per ayah. The 31% UNKNOWN is not a verdict
failure: all 490 lack a verdict timestamp, and the gate was closed 2,761 polls
(~25% silence — the user thinking/recalling, which is what memorization looks
like). Words the engine actually heard were judged at ~93%.

**The trim verdict is in.** First-word-of-ayah WRONG share: 22% pre-trim → 5%
and 0% with the floored trim. The boundary class it was built for collapsed;
the residual is mid-word (71–91%) with the charged emission named per word.

**Retreat, first real data.** 18:57 → 18:56 → 18:57 → 18:58: a single backtrack
and resume, tracked correctly (15 CORRECT / 3 UNKNOWN behind it), no
oscillation loop. The skip 18:21 → 18:23 fired at `jumpTo=23`, 0.93 coverage
with current and next both low — the user skipped 18:22 verbally and the lock
followed the audio; its 33 words correctly stayed UNKNOWN. The pinned escape
fired once after 21 s motionless. `MIN_RETREAT_GAP_MS=1500` and
`MAX_CONSECUTIVE_RETREATS=2` were never stressed (one retreat at a time), so
the constants stand unchanged and this observation is recorded instead of
tuned against.

**Retention widened to match.** `EMISSION_LOG_CAP` 4,000 → 12,000,
`RETENTION_AYAH_SPAN` 110 → 300: the 57-minute session's `noWindowWords=3889`
comes from the log rolling past audio in very long sessions, and the new cap
holds a full Al-Baqarah (286 ayat, ~8,600 symbols) with margin, at ~1 MB.

## The first-word guard: a trim that unseats the opening must not stand

The bounded trim fixed the ratchet but kept one failure: when the recorded span
end overshoots into the next ayah's opening, the trim removes audio the first
word needs, and that word is blamed for audio taken away from it. Measured on
the user's 16 October sessions as words blamed on the first unit while their
windows held another ayah's text.

The fix re-judges such a word against the untrimmed window — the validated wide
window the 93.4% measurement covers — and only when two things are both true:
word 0 has zero matched units, and the untrimmed window actually holds more
audio (so it never burns a DP per poll for ayat whose first word genuinely has
no audio yet). It can only restore audio, never remove it. The handoff keeps
the trimmed window; the lock policy does not move.

Gate: six new assertions in `window_anchor_parity.py`, six injections red —
including one that caught the check counting a comment mention as a guard, and
one `evidence_window_parity` update (val→var) that was itself fault-injected
before trusting.

## Device-confirmed, 17 October 08:57 — the circling experiment, and the guard it earned

The user recited three things on the spans build (installed 06:35): pieces of
surah 4, then Quraysh from the page start, then — deliberately, "the testful
part" — walked back into the middle of the page and recited surah 107 while
the lock still sat on 106:1.

**The circling case, decoded.** The record's log begins with surah 107's
audio: the reciter said 107:1–3 while the lock sat on 106:1. Because
`106:0` never arrives (ayah 1 has no previous ayah), 106:1's window opens at
the log start — forever — and `106:1:1` was blamed (wu=1000) against audio
that belongs to another surah. Every other word of the session is CORRECT
(15/16). The user then re-recited 106 to satisfy the lock, and 107 painted at
the second pass, with its arrival recorded at frame 249 — which also confirms
arrivals are lock events, not audio anchors.

**What shipped from it.** The un-anchored head demotion: an ayah with no
previous ayah's arrival has no anchor, so any WRONG from such
a window demotes to UNKNOWN with an `UNANCHORED_AUDIO` note (the walk's
own charge landed at wu=1000, so the first version's word-start
requirement was widened the same day), behind the
`unanchoredNeverAccuses` toggle (default on — the engine cannot prove the
audio is the reciter's). Blame removed, never added; gated in
`advisory_parity.py` and `pref_consumption.py`, fault-injected (guard removal
goes red). A straight session's genuinely mispronounced first ayah will now
show UNKNOWN + note instead of red — the honest cost, and the re-recitation
path fixes the record by judgement, not by the toggle.

**Spans confirmed on device.** All three records carry spans (14, 4, 5
entries) with the arrival-style retention filter. They immediately earned
their keep: `4:2:12` was blamed (wu=1000) while all 7 of its units match
inside its span-trimmed window — the charge was made in an earlier poll,
when `أَمْوَالَهُمْ` and `أَمْوَالَكُمْ` were both visible in the 4:2–4:3
stream and the window picked the wrong twin. A new named class
(duplicates-in-window from repeats), design not started.

**A writer artifact, found and fixed.** `unjudgeableKeys` printed `[""]` for
an empty set in all three records — quoted join inside always-open brackets.
The phantom "" ayah that every reader counted. Now a first-flag writer, like
the arrivals object.

**The surah-4 session's shape** (411 evaluated, 315 judged, 22 WRONG): 8
first-unit, 14 last-unit — the same classes the long 22:10 session showed,
now with every charge named through the app's own table (`device_blame.py`
reads `word_aligned_phonemes.json` since D23). `4:9:1` is the first-word
charge in it; its cause is named in the read-back, not guessed.

## Device-confirmed, 17 October 03:00 — the user's own test script

The user ran the planned sessions on the first-word-guard build (installed
20:22 the previous evening): Al-Adiyat complete, the LiveTest opened right
after it, then Al-Fatiha straight into Al-Baqarah 2:2, a deliberate backtrack
to 2:1, then 2:2 through 2:10 normally. Five records, all pulled and read back
through `device_blame.py` — which this same round stopped judging against the
stale shootout table (see the audit, D23).

**The first-word guard, confirmed.** First-word failure share: 12/110, 6/46,
14/82 across the three pre-guard long sessions on 16 October (13.4% pooled).
On the guard build: 0/11 and 0/11 (Adiyat, both runs), 0/17 (Fatiha→Baqarah),
and 2/31 in the 22:10 surah-2 session — where the read-back names both
individually: `2:9:1` charged `خَ` against a heard `قَ` (a real phoneme
confusion, the accusation stands), and `2:21:1` charged the leading `يَ` of
`يَٰٓأَيُّهَا` against `قَدِير`'s trailing `ر` at the ayah boundary — 4 of 5
units matched, so the guard's coverage-0 gate correctly did not fire; this is
the boundary-glide class reduced to one unit of a five-unit word, not a
starved opening.

**The LiveTest scope fix, confirmed.** The LiveTest session on surah 100
re-judged 35 words itself, each with its own `wt` — no inherited paint, no
"ends fast". The `s-0-0` stub (opened, jumped out, zero frames) is the
screen's no-recitation record and carries no accusation.

**The retreat budget, exercised for the first time.** `reversals: 2` on the
user's deliberate 2:2→2:1 backtrack; every re-crossed word re-judged CORRECT
or stayed honestly UNKNOWN. `2:1` stayed unjudgeable in the honest path: 2:2
was recited before it, so 2:1's window was inverted (before-arrival after
here-arrival) — the record says "no verdict" and accuses nobody.

**The handoff `2:2:2`, clean tonight.** All seven words of 2:2 CORRECT at one
poll; the old defect (expecting `الْكِتَٰبُ` over Fatiha's text) did not
reproduce. In the 22:10 session `2:2:2` *was* still blamed once (wu=1000),
but the reconstructed window matches all five of its units — the charge was
made in an earlier poll that this record cannot replay, which is the
retention design, not a new defect.

**Latency unchanged.** Verdict batches in the 2:1–2:10 run land 1.4–2.7s
apart at pollSec 0.333; the guard's fallback never had to fire beyond
2:5:1's honest UNKNOWN, so it cost no visible poll time.

**New named classes, small and honest:**
- Neighbour-gap UNKNOWN: `2:7:9,10`, `2:9:8`, `2:10:9` sat undecided between
  two judged neighbours — fast continuous tilawa consumes inter-word
  boundaries; UNKNOWN is the honest no-verdict state and accuses nobody.
- `2:5:8` (the run's single WRONG): expected `ل مُ ف لِ حُ ۥۥۥۥ ن`, heard
  `ل مُ ف` then divergence — one argumentable case in 113.
- `2:12:4` / `2:13:9` wu=0 in the 22:10 session: lam-meem assimilation and
  hamza elision in connected recitation — the window held the word's own
  audio; the engine's sensitivity, not a window artefact.

## Span ends now survive into the record

The read-back had one hole it could not close honestly: the record held the
raw window (via `arrivals`) but not the trim, so a charge made against a
window the app later shortened — `2:2:2` blamed then all five of its units
matching in the wide window — stayed "unrecoverable" with a shrug. The record
now carries `"spans"`, the same map `applySpanEndTrim` reads, and
`device_blame.py` applies the trim's exact two guards when rebuilding a
window. A `2:2:2`-shaped charge made after this build is replayable with no
shrug. Gated in `device_blame_parity.py` (writer, filter, reader — three
injections red), additively in `CORE_LOGIC.md`.

## Shipped, 18 October — the corpus harness measures the engine the phone runs

`optional_final.py` pointed at the stale phrase-cut table
(`ordered_quran_phonemes.json`): the 699/12 numbers it published for weeks
described an engine the phone does not run. It now loads
`word_aligned_phonemes.json` and refuses to run without it; the gate's
`word_rule_sweep.py` is ported the same way (163 word observations, was
154). `optional_marks_parity` green after both ports — the python mirror
agreed with the shipped table. Full corpus on the shipped table
(`python3 engine/replay/optional_final.py $(seq 1 114)`, 114 Al-Dosari
dumps, 6,135 of 6,236 ayat above the 0.9 gate): the reference reciter is
accused on **566 words**, the optional-final guard removes **28 (5%)**.
Of 69 final-unit charges, 41 have no licensed mark — the register requires
those letters sounded, so the guard stays out correctly; letters scatter
across 18 types (7×ب, 6×ه, no و/ي cluster), a thin, broad recogniser miss on
reference audio. The other 497 sit mid-word and stay verdicts. Full list
`/tmp/opencode/of_shipped_full.log`; the 699 list is superseded. The
preceding numbers masqueraded as measurements of what the phone does: none
of the guard rules were built against them.

Also shipped the same evening, all gated:
- **Jump catch-up** (`rejudgeJumpedAyat`): a jumped-past ayah is re-judged
  against its own window, arrival stamped at the slice end the engine
  actually knows about, CORRECT-only. The new `jump_rejudge_parity` check
  holds it (fault-injected twice, both red). 38 checks.
- **Diag story fence**: a record's `diagTail` is fenced at the previous
  session's end line — a surah-106 record had 24 of 41 diag rows from a
  surah-4 session.
- **`workPollSec`** (`notePollWork`): EMA of feeding-poll work with the
  250 ms cadence delay removed. pollSec includes the delay by construction,
  so "poll escalation under noise" separates: work vs cadence, on the next
  noise session on the new build.
- **Surah-4 record (22 WRONG)**: pre-guard build, 13 moves, 411 words
  judged — 14 charges at a word's last unit, 8 at its first, 0 mid-word;
  no advisories on wrongly-charged keys; window pools misaligned across
  ayat. The pinned escape fired after 39.5s motionless (96%/44%).

## Shipped, 18 October (late) — build identity, and a record only for sessions that ran

`versionCode` is now the git commit count — CI checks out the full history
and asserts the APK's versionCode equals `git rev-list --count HEAD` before
releasing, so the number is the same one everywhere — and
`versionName` is 0.2.0; Settings > About reads `BuildConfig` instead of a
literal "0.1.0", and the record's `build` tag is `#<versionCode>`. Every
install can now be named by the record it produced and by its own About
screen — before, every local build said `#1` and About said "0.1.0" for ever.

The surah-75/76 record (09:23 on 17 October — the walk missed in the first
batch of pulls) read back through `device_blame.py`: 243 words judged in
surah 76, 186 CORRECT, **15 WRONG — 11 last-unit, 4 first-unit, 0 mid-word**;
ten of the fifteen sit in four shared-window groups (one window deciding two
or three words: 76:11, 76:14, 76:17, 76:19) — the window-composition race
already named for surah 4. All fifteen reconstructed, no refusals,
`noWindowWords` 0: the widened retention holds and the last-unit class is a
named cause now, not a mystery. (The 76:11 pair was already in the docs; the
other groups are new; DECODER_STARVATION 1× on 76:1:1, advisories on no
WRONG key.)

The zero-identity file found beside the real records (`s-0-0-#1.json`,
`startedAt` 0, `surahStart` 0, empty rings, live `surahNow`/`lockNow`): the
end write in `stopRecite` ran unconditionally, and the write's own guards
cannot stop an `ended=true` write — so a page jump with no session running
flushed a corpse the session list counted as a session. The end write is now
conditional on `sessionRan`, and the start header moved after the counters,
ring and generation reset (it used to hold the previous session's tail under
the new session's name). New check `record_flush_guard.py`, all three clauses
fault-injected. The gate runs 39.

## Shipped, 18 October — the CI gate's red streak was one missing file

Runs 170-197 and 200-201 failed at step 8 ("Measurement gate"); 198-199 died
earlier, at the SDK step (fixed by `5829b8e`). Reproduced today in
`/tmp/ci-sim` — a `git archive` of the failing SHA plus CI's own fetch env
(`SKIP_MODEL=1 IQRA_SKIP_PAGES=1`): `device_blame_parity.py` runs
`device_blame.py` on a synthetic record, and the tool's emission re-align
called `word_verdicts.load_units()`, which reads the model's `tokens.txt` —
absent on CI by design. The tool crashed; the check failed. (The model-free
audit missed it because the read lives in a subprocess the check spawns, not
in the check itself.) `device_blame.py` now refuses the re-align when the
token table is absent, and says why; the emitted stream, expected units and
buckets still print, so the check keeps testing what it names. Sim before:
`1 failed, 7 skipped`. Sim after: `0 failed, 7 skipped`. CI run 202
(`abf2413`) is the first green run since 10 October — all 13 steps,
identity-verified, released as `v0.2.0-build236` (the same versionCode 236
installed on the phone).

## Shipped, 18 October (night) — the record times its emissions

`emMs` joins `emissions` in the record: one arrival time per retained symbol,
poll-granular (a poll's whole batch shares its clock reading — inventing
sub-poll spread would be invention), on the same clock as `wt`, trimmed and
cleared in lockstep with the log. That turns the 2.65s floor from a number
that could not be argued with into two separable numbers: `device_blame.py`
now prints the verdict lag (`wt` minus the arrival of the last symbol in the
judged window — engine time) and the feed cadence (median gap between
emission stamps — poll time), and refuses when the times are absent instead
of guessing. `device_blame_parity.py` grew six clauses for the writer
lockstep and the reader; all seven new paths fault-injected. Not yet read
back: it needs one device session on build 237+.

## Shipped, 18 October (night) — the no-mark final-unit class, read to its letters

The census's 41 unguarded final-unit charges, read against the corpus dumps
and the shipped table: 17 distinct ending letters (ب7 ه6 ا4 م3 ل3 ى3 ط2 ن2
ء2 ج2 + seven singles: ك د ض ر ف ظ ق). Three end in meem by bare form; two of those are
plain recogniser substitutions at the final unit (مَ→هِ on 2:136:10, مُ→بُ
on 4:94:24, both at window coverage ≥0.99) that no table rule reaches. The
third, 57:12:9 وَبِأَيْمَـٰنِهِم, is a genuine table defect: its final unit
is `۾۾۾`, the phonemizer's idgham/iqlab material for the join into the next
word's ب, absorbed into the word itself; the model emitted bare `م`, and
`۾۾۾` is matchable only by the exact token. 981 words end on such a run (91
surahs); the model emits `۾۾۾` 1,077 times corpus-wide, so the table and the
model mostly agree and the defect bites only where the model does not merge.
Candidate one-sided equivalence measured nowhere yet; deliberately not
applied (item 8's rule: a corpus rescore, not a weakening).
`optional_final.py`'s tag column also stops printing "mark but not final"
on words with no mark - the booleans were always right, only the tag lied.

## Shipped, 18 October — the ahead ayah's window is its own audio

The ahead-ayah serving shipped the same day had a defect reported on the
phone immediately: "words ahead flash to my current recitation, sometimes
they match the current, more often they don't", and green words blinking off
for a second. Both were structural.

The flash: `ayahObs` anchored the next ayah's window on the locked ayah's
arrival, so while the reciter was still inside that ayah the window's tail
was the CURRENT speech, and the trim's `hi - MIN_WINDOW_SYMBOLS` floor kept
the freshest symbols of it in the window no matter what. The next ayah's
words matched it, and because a verdict is final for the session, the
spurious colours stuck. The fix raises the ahead window's left edge to
`lockedSpeechEnd` - the end of the locked ayah's own matched speech, measured
from the SERVED window (a raw start is short by the trim) - and refuses until
that end is known and real audio exists past it. The untrimmed fallback is
gated to visited ayat, or the flash returns through it whenever the floor
unseats the ahead ayah's opening.

The blink: two `newMap` writes (the no-window path, the word-count-mismatch
path) wrote UNKNOWN directly and bypassed the retention rule, and the painter
prefers the live map over the reader's record - so a single degenerate poll
repainted a retained verdict as colourless. Both paths now keep a retained
terminal.

Gate: `ahead_window_parity` (new; the gate is 41 checks), six injections red
- floor removed, fallback gate removed, raw-start origin, both erasures
restored, stale-key refusal removed. Neighbours re-run green:
`window_anchor_parity`, `evidence_window_parity`, `paint_scope_parity`,
`arrival_retention_parity`, `session_epoch_parity`.

Honest caveat: `lockedSpeechEnd` is not in the record yet. No recorded charge
depends on it - blame only ever lands on the locked ayah - so the read-back
is unaffected; if an ahead window ever needs rebuilding, that gap has to be
named.

## Shipped, 18 October (evening) — the observer seat, and the latency split read

The observer (TODO 16) is on the phone: from a session report, "Watch it
again" replays the record's timed walk - the lock at every moment from the
frame ring, every verdict at its `wt`, lock moves at their frame times
(tagged advance / handoff / escape), and the emissions with their arrival
times (`emMs`) growing on the same clock, at real time or scrubbed. It
replays, it never re-judges. What the record cannot place, it says so:
advisories are counted and named untimed (the writer stores key, kind and
count only), and records written before `emMs` get the refusal instead of a
panel pretending silence. Committed 820a917; its first self-review - content
drawing under the top bar, and the strip needing a key because as-of-now
colours are not the report's final outcomes - fixed in 4dc644e. Verified on
the phone against a #238 record and against two pre-emMs writers.

The latency split, read from the #238 record of the session where the
reciter stopped (`device_blame.py`, which refuses rather than guesses):

- Verdict latency (`wt`) quartiles: 8.7 / 15.9 / 21.5 / 26.6 s. `wt` is
  time from session start, and the decisions are spread THROUGH the
  recitation (4.6 s, 5.0 s, 5.9 s...), which is the per-word serving the
  ahead rule asked for. The old "whole ayah colours in one batch at the
  end" was never the engine batching; it was the paint window, and the
  ahead fix is that fix.
- Verdict lag against the ayah's final window: median **-3.8 s** - the
  typical word is decided seconds BEFORE the last symbol of its own ayah's
  audio has arrived. Negative is the healthy sign; the tool now prints
  that reading instead of leaving the sign to superstition.
- Feed cadence (median gap between emission stamps): **565 ms**. With the
  poll at 250 ms, the per-word floor is the model's phoneme emission rate,
  not the poll. Halving the poll buys at most ~130 ms against a 565 ms
  model cadence, on a phone already hot from the recogniser - a measured
  negative. The cadence experiment the plan allowed is closed by
  measurement, not deferred.

Smaller polish, verified rather than assumed: the session list now carries
its denominator ("29 of 29 words", not "29 words"); the Live screen's Latin
strings are all bidi-isolated already (`ltr()`); a verse search result
already opens the reader anchored on the ayah; the reader's long-press
already opens the action sheet (practice from here / repeat / listen /
copy / bookmark). The stale TODO lines for those are corrected in place.

## Shipped, 18 October (night) — the silent seat says it is silent

The user reported "there isn't sound coming out from the replay". Two honest
gaps produced that reading; both are named now, neither invented over:

- The observer replays the record, and **a record stores no audio** — that
  seat was silent with nothing on screen saying so. Its limits panel now
  says it first: "A record stores no audio, so this seat replays the session
  silently: what the model heard is replayed as the text stream above, not
  as sound."
- The reader's reference-audio button (`togglePlaySurah`,
  `PracticeViewModel.kt`) swallowed every failure whole: a network or
  MediaPlayer error stopped a player that had never started, and the button
  kept showing "play" — indistinguishable from a missed tap. It now shows a
  spinner while the stream prepares (the header had no loading state at
  all), and a failure surfaces as a pill naming the media error (`what`/
  `extra`) or the exception class, dismissable and self-clearing after 8 s.
  The error listener returns `true` now, so the completion callback no
  longer fires over an error.

What this does not yet claim: whether the user's silence was the observer
seat (by design) or a real stream failure on the phone — the pill makes the
second case visible with its reason on the next attempt. Device check is
the next step, on the build that carries this.

## Shipped, 18 October (late) — the wrong word has a voice, and the last literals leave

A UI and design pass, engine untouched. The one new behaviour:

- **Vibrate on a wrong word.** A short buzz the first time a word's status
  becomes WRONG in a session — the channel that reaches a reciter whose eyes
  are on the page, not the screen. The rules that keep it from lying: strictly
  `WRONG` (UNKNOWN, SKIPPED and advisories are not accusations and must not be
  felt like one), one buzz per word per session (the seen set is keyed on
  `sessionEpoch`, so a new session re-arms it and a re-painted word cannot
  buzz twice), a 1.5 s cluster gap (five buzzes in two seconds reads as a
  fault), and nothing at all while no session records (the paint map retains
  the previous session's red, and stale red is not a new accusation). New
  `ui/Haptics.kt` (`WrongVerdictBuzz`, wired on the Live screen); toggle
  "Vibrate on a wrong word" in Settings, default on, consumed by the trigger
  (`pref_consumption` territory). `VIBRATE` was added to the manifest — it
  was absent, and Android refuses the call without it. The old per-word
  LongPress on WRONG is removed: it re-fired whenever the word re-entered
  composition, which is a defect the central trigger had to not inherit.
  Not yet device-verified; a phone check is owed before this is called good.

The pass proper, each item verified in code before and after:

- **The last colour literals.** `DataSetupScreen`'s green/red dots and both
  its button labels wore hardcoded colours over theme surfaces; they read
  from the scheme now, as do Settings' section labels, Home's accents (both
  surah kinds already meant primary/secondary), Live's scaffold ground and
  state captions, and the Charts end-marker. That last one punched its
  centre with `#070B0C` — the night theme's ground as a literal — so every
  other theme grew a foreign dot inside its own line's last point. It is a
  stroked ring now. A dead `amberColor` left in LiveMode is deleted.
- **DataSetup's failure branch could never fire**: the message was
  `runCatching { "" }.getOrElse { ... }` — a non-throwing call guarding the
  message. `fetchAll` throws, so its failure is captured and now says so.
  A write-only progress fraction (the fetcher only reports whole files) is
  deleted rather than left pretending to feed a determinate bar.
- **"Words to work on" printed the wrong word.** The lookup indexed a
  whitespace split of `textClean` with the mushaf word index; the two
  disagree from the first multi-word token (`بَعْدَ مَا` is one mushaf word).
  The text now comes from the mushaf table by key, and a word that cannot be
  resolved names its ayah instead of inventing text.
- **Hide mode** re-audited: the reveal law (only a word's own verdict may
  reveal it, `!hide` guards everywhere else) is intact and lives in
  MainActivity — untouched. The one honest gap was first-use: Choose now
  states the rule ("A word you get wrong is revealed and marked; every other
  word stays hidden"). Rejected: a preview of the next word (leaks the
  answer) and reveal-up-to-the-word (the reader is stricter, on purpose).
- **Tap targets**: Choose's mode chips (~40 dp) and its ± steppers (34 dp)
  now carry 48 dp targets; the drawn circles are unchanged.

Compile-only verification (`:app:compileDebugKotlin`); nothing here has been
on a device yet.

## Shipped, 19 October — the played surah keeps itself, and the bar is its transport

The reference audio was one hardcoded reciter (Husaree), streamed fresh on
every tap and thrown away. The user asked for the obvious better: save it,
choose who recites, and be able to control the file and the playing.

- **Reciter choice.** Ten reciters in `data/Reciters.kt`, slugs taken from
  quranicaudio.com's own `/api/qaris` and each verified with a range request
  (206) before being written down — guessing the slugs fails, as three rounds
  of 404s showed (`mishary_alafasy` is really `mishaari_raashid_al_3afaasee`).
  Default is Husaree, i.e. exactly the old behaviour until changed.
- **The file stays.** First play streams (same latency as before) while
  `AudioFetcher` saves the surah to `filesDir/audio/<reciter>/<NNN>.mp3`
  through a `.part` + rename, so a dropped connection cannot leave a truncated
  surah that would later play as a cut-off recording; a short read and a
  non-audio content type both fail the save and say so. Every replay after
  reads the file. Save failures surface in the bar ("not saved for offline
  replay (reason)") and in `diag.log`; they never stop playback.
- **The bar became a transport.** While audio is up, the bottom bar shows
  prev/next surah, pause/resume, a scrub slider with clock, a speed chip
  (0.75×–1.5×, persisted), and the save's live progress. It replaces the
  recite row on purpose: a mic session under the speaker would judge the
  reciter for what the speaker said. Polling now reports position/duration
  continuously, including while paused (it used to stop at pause).
- **"Downloaded audio" screen** (Settings → Reference audio → Manage): what is
  on disk with size and date, sorted by surah / reciter / newest / largest,
  play per row (seats that row's reciter first — the file and the reciter are
  the same choice), delete per row or wholesale, each behind a confirm.
- **Settings**: reciter picker, keep-a-copy toggle (default on), Wi-Fi-only
  save policy (default on; playback always streams), speed, auto-play next
  surah. `ACCESS_NETWORK_STATE` added — read-only, for the Wi-Fi policy.

Not yet device-verified; the phone check for this and for the 18 October
haptics pass is owed next.

## Shipped, 19 October (night) — the ayah is the handle: seek, and a second bookmark

The user asked for the ayat to be interactive: tap an ahead ayah and the
reference audio continues from it, each ayah bookmarkable from the same sheet
that holds the controls, and gestures that stay truthful.

- **Tap-to-seek while audio is up.** A single tap on an ayah seeks the
  reference audio there. The tap checks state in a fixed order — open
  selection collapses, then active audio seeks, else chrome toggles — so one
  gesture never carries two meanings. Long-press still selects/extends a
  range; double-tap still moves the recitation lock.
- **Timings from the reference app's own datasets.** quran_android's timing
  databases (through android.quran.com, served by files.quran.app) are used,
  but only where that app pairs the database with the exact mp3 we stream:
  Al-Husary (`husary`) and al-Minshawi (`minshawi_murattal`). For other
  reciters a tap says there are no timings rather than guess. Checked against
  the actual downloaded file: Husary sura 1's db entries (5664, 11837 …
  39117 ms) each fall inside an ffmpeg-measured silence (speech resumes 6500
  … 39764 ms), so a seek lands just ahead of the words, never inside them.
  Tapping ayah 1 follows the reference app's rule: the basmala key when the
  dataset has one, else the file start.
- **Ayah bookmarks.** `data/AyahBookmarks.kt` stores "surah:ayah" separately
  from page bookmarks; the ayah sheet toggles it with a live label, and Saved
  lists an "Ayahs" section (text snippet, tap opens the reader at that ayah).
  Section headers appear only when both bookmark kinds exist.
- **"Listen from this ayah"** replaces the sheet's "Listen from here", which
  started the surah from zero regardless of which ayah was selected.

Device-verified, 19 October night (build 248, device 35d5637e). The seek
resolves and lands: `diag.log` recorded `seek ayah s=1 a=5 → 26791ms (seeked)`
for a tap on playing audio, and the bar's clock read 0:29 seconds later (it
would have read ~0:05 had the seek not moved the player). The sheet's "Listen
from this ayah" from idle recorded `→ 26791ms (start then seek)` and began
playing. The sheet's bookmark toggle flipped its label live; Saved listed
"Ayahs" with the ayah's text; the row opened the reader with the ayah's first
word highlighted; deleting from Saved returned the empty state. One honest
gap: a reciter without a timing database (anything but Al-Husary and
al-Minshawi) still needs a phone check that the note appears - the code path
is short but unverified.

## Fixed, 19 October (night) — a jump with no session ends nothing, and says nothing

Every jump - a reader open, a resume, a Go to page - logged

    g0 session end reason=preparing-cancelled via=jump ran=no ...

and the 18 October record `s-1792350751308-1-#243` carried the same line in its
own diagTail next to a real `session starts`. The line was read as a session;
it was not one, and no microphone opened.

- **The mechanism.** `jumpToPage` calls `stopRecite("jump")` unconditionally —
  a jump must cancel a session that is still preparing — but `stopRecite`
  emitted the preparing-cancelled summary whenever the recorder was not
  running, session or no session. And because `endSession` deliberately no
  longer resets counters (the 18 October browsing fix), the phantom, arriving
  after a real recitation, echoed THAT session's moves and lock beside
  `ran=no` — `moves=110 ... ran=no`, the contradictory shape that made the
  22:07/22:12 lines unreadable.
- **Not a mic event.** On the emulator (build 249, 19 October) a fresh
  install opening the reader produced exactly this pair and nothing else — no
  `session starts`, no mic calibration. All three `startRecite` call sites are
  user taps (reader mic, Home's "Recite from memory", the ayah sheet's
  "Practice from here"); no code path starts a session by itself.
- **What #243 was.** Its diagTail: the phantom + `jump → s=1:1 p=1` at
  22:07:24, 307 s of silence, then a real `g1 session starts p=1 → s=1:1` at
  22:12:31, `mic floor calibrated: 0.6138` (a very loud room), locks walking
  1→5→6→7 on ambient sound, 27 words evaluated / 12 judged, ended
  `reason=user stop` 39 s later. A Record surface was pressed at 22:12:31 and
  End at 22:13:10; who pressed them is not in the artifacts — only the user
  can say whether the phone was touched. What was never real is the phantom
  line in front of it.
- **The fix.** `stopRecite` captures `hadSession = sessionActive` before
  teardown, and emits the preparing-cancelled summary only when a session was
  actually preparing; a genuine preparing cancellation still logs, because
  that emission exists so a cancelled preparation is visible.
- **The guard is gated.** `session_record_integrity` now asserts the
  conditional on the emission's own path; fault-injected, the check goes red
  without the `if (hadSession)`. Full gate after the fix: 0 failed,
  0 skipped, 157 s.

## What is open next

**1. Rebuild the word table with case vowels on finals.** 26,733 of 77,481
words end bare; the mushaf text carries the vowel; the builder already walks
word by word. The runtime `final_vowel_id` rule then becomes a backstop. Check
the blame fall on the next session before touching anything else.
**2. DONE — replay screen, desktop first** (`engine/replay/replay_export.py`
writes `out/replay/<record>/`; open the html — no headless browser here).
Per-WRONG causes render only where the record can reproduce them.
**3. Engine comparison** on the noisy human corpus; swap only on loss, per the
shootout's own decision rule.
**4. The word-latency instrument.** DONE (18 October night): `emMs` in the
record and the lag/cadence split in `device_blame.py`; the paint half was
already fixed structurally (ahead-ayah judging - colour no longer waits for
the lock). What remains: one device session on build 237+, then the floor is
a measured engine/paint split instead of an ambiguity.
**5. Standing-word pointer** — fixed and gated; now also follows the reciter
into the next ayah. Needs one device confirmation of both.
**6. UI polish**: type sizes, session list order, RTL on the live caption,
search deep-link, reference-app pop-menu.

## Ownership

Mine: `PracticeViewModel.kt`, `PhonemeMapper.kt`, `MainActivity.kt:1371`,
`filesDir/sessions/`, `engine/replay/*`.

Theirs: `data/PracticeLog.kt`, `ui/ProgressScreen.kt`, `ui/SettingsScreen.kt`, the
reader/summarise side, `scripts/`, `engine/audio/`.

One session record, one format. `PracticeLog` is being reworked into a reader over
the files written here.

Device installs are serialised — build once, install once.

## Two things worth remembering

The `unjudgeableAyahs` counter recorded frames, not ayat, which made 961 look
like a count of verses. It now records distinct keys, and the record carries them.

And a session record can overwrite its own results: a periodic flush fired after
teardown and rewrote a good final record with reset counters, which is how the
Al-Fatiha record came to hold 192 frames and report `moves=0`. An instrument that
can lose its own data fails silently and looks like data.