# Core logic — how this app works, and how far it gets

This document tells you what the app does at each step. Use simple words on
purpose. Short sentences help a new reader, and they help a repairer find the
defect. When you change the code, change this document.

The words follow ASD-STE100 in most places. You write short sentences. You use
one idea in each sentence. You use the active voice. You use approved words,
such as `make`, `use`, `show`, `find`, `keep`, `stop`. You do not use words such
as `utilize`, `commence`, `subsequent` or `facilitate`. Some technical words do
not have a simple alternative. Those words stay. That is why this document is
"80% of the way" and not 100%.

---

## The goal of the app

The app teaches Quran recitation. It listens to you. It finds the word you
recite. It compares that word with correct phonemes. Then it shows you the
result, word by word.

There is one rule that controls everything:

> **A verdict is an accusation.**

When the app marks a word WRONG, it says to you: you made a mistake. The app
must not say this when it is not true. A wrong verdict from bad data is worse
than no verdict. There are six states of a word:

| State | What it means | How the app shows it |
|---|---|---|
| `CORRECT` | The app heard the word. The word agrees with the expected phonemes. | Normal ink |
| `WRONG` | The app heard the word. The word does not agree. The app has enough evidence. | Red |
| `SKIPPED` | The app has no evidence for the word. It does not accuse. | Muted |
| `UNKNOWN` | The app has no verdict. It does not accuse. | Muted |
| `ADVISORY` | The engine has a note. It does not accuse. | Amber (muted in hide mode) |
| `EXTRA` | Not in use. The aligner does not report it. | - |

Two states show the absence of a verdict: `SKIPPED` and `UNKNOWN`. Keep them
out of any count of judged words.

---

## Step 1 — Detect

The app listens to the microphone. It reads a growing buffer. The reader reads
only the samples nobody has read before.

The loop does this on each pass:

1. Wait for the next poll.
2. Read the new samples.
3. Test the samples for silence.
4. Let the VAD (Voice Activity Detector) test the samples.
5. Feed the samples to the recognizer, or drop them.

The app measures the silence threshold from your room. It does this one time at
the start of a session. It samples the ambient noise, then uses three times that
level. A fixed threshold hurts quiet microphones.

When the gate closes, no audio goes to the recognizer. The app counts those
passes, and it reports them. A session with a closed gate and a session with a
full gate look the same without that count.

**Declared time between polls:** 250 ms.

**Measured time between polls:** the loop takes longer than 250 ms. Each pass
also accepts audio, decodes it, aligns it, and writes the screen state. On one
1,428-second session the app measured a median of 563 ms.

This matters. Any constant that thinks in seconds must use the measured rate,
not the declared rate. Wrong-latch and words-per-minute both do this now. The
session record carries both numbers, so a reader can tell which rate was in
force.

### What can fail here

- The audio ring holds more samples than the app can use. If you pause for too
  long, the ring loses audio. The app resyncs to the present time. It does not
  feed a gap of unrelated audio to the recognizer.
- The recognizer gets audio, but it makes no tokens. The app watches for this.
  It makes one recovery attempt each ten seconds. After the last attempt, it
  says that the decoder has failed. It does not loop a recovery that cannot
  work.

---

## Step 2 — Decode

The app sends audio to a Zipformer speech model. The model writes phoneme
symbols. The app keeps a list of those symbols, one entry per emission.

The app recycles the model stream on each lock move. It keeps a tail of recent
audio, and it replays that tail into the new stream. This bounds the list
length, and it keeps the context across the move.

The app uses one counter for the symbols it has consumed. That counter is
cleared each time the stream resets. Do not compare a count from one stream
with a count from another stream. Do not clear that counter in `endSession`:
a page jump clears the stream, and `endSession` runs on every jump.

### What can fail here

A reset with a stale counter makes the app read the same symbols again, or read
none. The symptom is a session record with `evaluatedWords` at zero, or with a
large number of words that have no audio window. Real measured sessions show
two faults from this class:

- Before the per-stream fix: a 302-second session that recorded 11,408 words
  with no audio window, and 922 empty windows.
- After the fix: zero and zero, on six device sessions.

In a fourth session (1,428 s, 106 lock moves, 28,000 audio frames), the counter
returns: 2,501 words with no window, across 212 windows. The fault scales with
the length of the session. This is open. See `STATUS.md`.

---

## Step 3 — Judge

The app has a table of expected phonemes for each ayah. It has a table that
says which phonemes belong to which word. It aligns the emitted phonemes to the
expected phonemes. Then it gives a verdict to each word.

The alignment uses a DP (Dynamic Program) that finds the best match. It is
unbanded, so it can move a symbol any distance. It uses affine gaps, so one
deleted word costs less than many wrong sounds. That keeps a skipped word from
becoming a red word.

The table holds one realization per ayah: a stop at each ayah end, a join
everywhere inside. The mushaf carries 9,950 mid-ayah stop marks, across 3,970
ayat. The app cannot represent both realizations, and stopping is legal, so
the app must not accuse at a mark.

### The three rules that protect you

**1. A word must be heard before it can be wrong.**
A word is WRONG only when the app heard at least 80% of that word's own units.
Below that level, a contradiction gives `UNKNOWN`. That number comes from 20
Al-Dosari surahs (34,059 CORRECT, 1,454 WRONG).

**2. A madd length is never an accusation.**
The register lists 51,395 madd sites. 9,706 of them let you choose the length.
41,543 are fixed at 2. 146 are fixed at 6. When the length is free, all lengths
of the same madd bearer match. The setting `Madd length` controls this.
Turning it off makes a length accusable, and it is for measurement only.

**3. A stop at a mark is not an accusation.**
When a word begins after a mark, and the mismatch sits on that word's first
unit, the app removes the blame. It writes an advisory instead.

There is one more rule for words whose final letter need not be said. 3,559
mushaf words end in `U+06DF`, 1,733 in `U+06E2`, and 1,667 in `U+06ED`. Those
marks let the final letter go unsaid. When the mismatch sits on the last unit,
the app removes the blame there too. This is not a setting. It is a fact about
the text.

Both guards remove blame. Neither adds blame. This is deliberate. A guard that
could accuse is a new false accuser.

### The advisory channel

The engine can see things it cannot judge. It writes a note for each of those
things. Advisories never accuse. They never advance the mistake count, never
paint red, and never show up in hide mode.

| Kind | What it says |
|---|---|
| `MISSED_RULING_POSSIBLE` | A legal stop may have occurred, and the engine cannot model the join. |
| `UNMODELLED_FINAL` | The word ends in a letter that need not be said. |
| `NO_AUDIO_WINDOW` | The engine had no audio for the word. |
| `LOW_EVIDENCE` | The engine heard a contradiction, but it does not have enough evidence. |
| `DECODER_STARVATION` | The audio stream reset before the engine heard the word. |
| `UNSUPPORTED_REALISATION` | Not in use. No code writes it. |

Count advisories by their shape. A per-frame condition (a word with weak audio
stays weak) counts one time per word. A per-event condition (a reset happens)
counts each event. Count both shapes, and never count frames.

### What can fail here

If the table is wrong, every reciter is accused. The app's own reference
reciter is accused on a small number of words, measured over 114 surahs. Each
of those words is either a table fault or a real limit. See `STATUS.md`.

---

## Step 4 — Move

The app moves a lock. The lock points at the ayah you are reciting. The page
follows the lock.

The lock moves forward when the next ayah has coverage of at least 0.60. If
the coverage is between 0.60 and 0.85, the move needs two or three passes.
Coverage at 0.85 or more moves the lock at once.

The lock moves back when the previous ayah has high coverage, the lock has low
coverage, and no forward move is possible.

There are two escapes, for states the gates above cannot leave:

| Escape | When it fires | What it does |
|---|---|---|
| Pinned-lock | Coverage is high here, and no branch can fire. The lock has been still for a set time. | Advances one ayah. |
| Long jump | You skipped ahead. Coverage of the next ayah is low, coverage here is low, and an ayah ahead has coverage at 0.92 or more. | Moves to that ayah. |

Both escapes are measured states, not guesses. Before the pinned-lock escape
existed, surah 55 could not pass ayah 63.

### The boundaries

A session can cover one surah or two. When you finish a surah, the app hands
off to the next surah. The handoff needs evidence that you finished the surah
that you leave. It also needs a streak of passes, like the forward move.

The boundary for a handoff is the **last ayah of the surah**. It is not the
last ayah on the page.

A page boundary must never end a surah. Measured on your own session: the lock
left surah 18 at ayah 27 and went to 19:1 while you were still inside surah 18.
It came back to 18:28 twenty seconds later. An earlier session did the same at
2:164. Both sessions skipped ayat.

### What can fail here

The long jump can skip one ayah. Measured twice in one session: 18:65 to 18:67,
which skips 18:66, and 18:91 to 18:93, which skips 18:92. Both moves fired with
the next ayah at coverage 0.50. That coverage is partial evidence that you were
speaking the next ayah, and the jump treated it as evidence that you had
skipped it. This is open. See `STATUS.md`.

---

## Step 5 — Paint

The app paints each mushaf word with the color of its verdict.

The reader paints a page image. It puts a box over each word. It reads the
verdict for that word, and it applies a style.

There are three things to keep true:

1. Red is only for `WRONG`.
2. `SKIPPED` and `UNKNOWN` are muted.
3. An advisory is amber, and it does not show in hide mode.

The advisory mark must not show on a masked word. It would show which word the
engine had a note about.

The live list paints the same verdicts. It takes its words from the mushaf, and
it uses the same word index as the verdict maps. A whitespace split of the
uthmani text does not agree with that index. The mushaf prints `بَعْدَ مَا` as
one word across a space. A split moved every word after that point by one, so
the app painted the wrong word. Use `vm.standWordsFor(ayah)`.

### What can fail here

If the two word indexes disagree, the app paints the wrong word. This is the
failure the app most exists to prevent.

---

## Step 6 — Record

The app writes a record for each session. The record is the evidence for
everything above. A record that lies is worse than no record.

The record holds:

| Field group | What it holds |
|---|---|
| Header | Start time, flush time, surahs, lock, generation, words-per-minute |
| `counters` | Moves, reversals, polls, words evaluated, words judged, words with no window, empty windows, unjudgeable ayat |
| `words` | Every word the archive holds, with its verdict. A WRONG word also carries `wu`: the fraction of its own units at which the contradiction began, 0..1000. Without that field the app cannot say WHY a word was accused, and it cannot be argued with. |
| `ayahStatus` | Per-ayah counts, including `SKIPPED` and `CORRECT` |
| `advisories` | The engine's notes, by kind and count |
| `frames` | The policy state at each poll |
| `diagTail` | Lock moves, handoffs, and engine notes |
| `thresholds` | The thresholds that were in force |
| `pollSec`, `declaredPollSec` | The rate the loop delivered, and the rate it asked for |

The engine writes the record every 25 polls and once at the end. A record that
is written after teardown would rewrite a good record with reset counters. The
end write happens before the teardown.

### Three rules for the record

**1. Only the next session clears a session.**
Counters live from one start to the next start. `endSession` used to clear them.
`endSession` also runs on every page jump, so browsing after a recitation wiped
the counters of the record still open. The record then showed 322 CORRECT words
next to 0 judged words and 0 moves. Clear them in `startRecite` only.

**2. A re-fold must be able to undo itself.**
The app rewrites the record during the session. Each rewrite replaces the old
numbers. The app must store the old numbers so it can subtract them. It stores
them for sessions, correct, wrong, skipped, unknown, ayat, hard words — and it
did not store them for per-surah rows. A ten-minute session therefore inflated
its per-surah totals about 24 times, while the headline numbers stayed correct.
The screen showed one number above another number that disagreed with it.

**3. "No data" and "zero" are different claims.**
A session that judged nothing has no accuracy. A session that judged words and
got all of them wrong has an accuracy of 0.00. If both encode as 0, the trend
chart drops a failed session, and the "up or down on your last session" line
loses its comparison. The app encodes `-1` for no data and `0` for zero.

### What can fail here

Any counter that one path clears and a sibling path does not clear. That class
of fault produced the self-contradictory record above. `resetSessionCounters`
now clears the siblings too: `consecutiveRetreats` and `noiseFloor`.

---

## What the app has achieved, and what it has not

### Achieved, measured

| What | Evidence |
|---|---|
| The lock reaches the end of 104 surahs of 114, and completes 113 of 114 | 26 hours of reference audio |
| The stale-index fault is closed on short sessions | 6 device sessions, `noWindowWords=0` |
| A clean recording no longer looks silent | the same 6 sessions |
| False verdicts on ayah-final words are mostly gone | 16 of 18 → 3 of 11 in your sessions |
| You cannot browse away a recitation's statistics | 3 gate checks |

### Not achieved

| What | Status |
|---|---|
| Legal stop detection | The app removes blame at a mark. It cannot detect the stop itself. |
| The optional-final guard | Measured over the whole corpus: it removes **2%** of the reference reciter's false accusations (14 of 768). It is worth keeping, and it is not the answer. |
| The unexplained accusation | **71% of the DEVICE class (108 of 152).** Plain mid-ayah words, no signature. Nothing textual separates them from correct words. |
| The per-surah fold | Fixed in code. Not yet confirmed on a device. |
| `noWindowWords` on long sessions | Back at 2,501 on the 1,428-second session. Open. |
| The long jump skips an ayah | Two measured events. Open. |
| Page turns at a surah end | Code changed. Not confirmed on a device. |
| The standing-word pointer | Can still be empty between evidence windows. |
| UI polish: text size, list order, RTL on live, search to text | Deferred. |

The truth about the goal: the app keeps a clean lock, and it keeps an honest
record. It does not yet keep an honest verdict on every word. On the phone the
size of that failure is 152 accused words in six sessions, and the app cannot
explain 108 of them. Do not report a corpus figure to a user as "accuracy" — it
is the harness's count over its own window, not the phone's.

### A note on where those numbers come from

The harness fits its window tightly to the ayah. The app uses a window three
ayat wide, from the previous ayah's arrival. Those two disagree, and
`docs/HARNESS_FIDELITY_AUDIT.md` already lists 22 places where they do. So a
sweep count is evidence that a class exists. It is not a count of what a user
saw on the phone. When you need the second number, take it from the device
session records.

---

## How to check the work

Run the gate:

```
python3 engine/replay/run_checks.py
```

It runs 23 checks. Every check must be shown able to fail. Break the thing that
a check measures, and see the check go red. A check that cannot fail measures
nothing.

| Check | What it stops |
|---|---|
| `dp_source_parity`, `dp_equivalence` | The two DP paths drifting apart |
| `archive_parity` | A handoff pruning the record |
| `emission_log_replay` | The log freezing at a stream reset |
| `evidence_window_parity` | A judged ayah against the wrong audio |
| `word_verdicts` (314 cases) | A substitute word read as `SKIPPED` |
| `lock_trace`, `back_policy_sweep`, `hesitation_policy`, `pinned_escape`, `handoff_boundary` | Lock defects |
| `search_parity`, `ui_state_parity`, `ring_buffer`, `session_stream` | Navigation, search, and buffering |
| `advisory_parity` | An advisory that accuses |
| `pref_consumption` | A switch that nothing reads |
| `session_record_integrity` | A counter that a browse wipes |
| `dead_code` | A declaration, or a whole file, that nobody references |
| `unused_imports` | An import that names a component that is not used |
| `honest_numbers` | Two meanings of zero |
| `word_rule_sweep` | Per-word collateral damage over 154 real words |

Three of those checks exist because of defects the ones before them could not
see. `dead_code` required four spaces of indentation, so it passed over every
declaration in `MainActivity.kt` while reporting them elsewhere.
`handoff_boundary` grepped for a variable name I had deleted, so it failed on a
name rather than a behaviour. Write the check to a behaviour, then break the
behaviour and watch it go red. Do not write it to a symbol.

When you change a constant in this document, change the measurement too. When
you fix a defect, write the number that shows the fix.

---

## Vocabulary

| Word | Meaning here |
|---|---|
| Ayah | A verse of the Quran. |
| Emission | One phoneme symbol, from the recognizer. |
| Coverage | Matched units divided by total units. 1.0 means all. |
| Lock | The ayah the app considers current. |
| Move | One change of the lock. |
| Reversal | A move that goes against the last move. |
| Gate | The silence test. |
| Window | The audio the app judges an ayah against. |
| Advisory | A note the engine writes when it cannot judge. |
| Mushaf | The printed page layout. |
| VAD | Voice Activity Detector. |
