# Defects and method

A record of the bugs that were found in this project, **how each one was
caught**, and the traps that cost the most time. Written for whoever works on
this next — including future me.

The honest summary is uncomfortable: the recognition engine was wrong in
**seven independent ways**, and every one of them presented as the same
sentence. *Audio is flowing, the model is silent, the thresholds must need
tuning.* Several of them had already survived a round of threshold retuning,
which is the trap this document exists to name.

Only two of the seven were findable from the source alone. The rest needed an
instrument built before the bug could be seen.

---

## Why the same symptom kept recurring

| # | Defect | What the log said | What was true |
|---|---|---|---|
| 1 | Expected side in the wrong alphabet | `Last match 4 @ 0.26` | coverage was identically `0.00` for every ayah, every frame |
| 2 | 3s sliding window, window-relative cursor | `decoder starved 50s/92s` | the model received **5.3%** of the session |
| 3 | `zipformerOn` never cleared | `fed=161792 toks=0 opErr=-` | **not one sample** reached the model |

Defect 3 is the one to study. The log read *audio is flowing*. It was not: the
counter that produced `fed=` was incremented next to a call that silently
returned. A plausible, confident, completely wrong reading — and I believed it
for a long time before measuring it.

**Lesson: a number in a diagnostic must be tied to the thing that consumes it.
A count maintained beside a call, rather than derived from its result, is a
fiction.** When a metric is the sole evidence for "the data arrived", derive it
from the call's return value or do not print it.

---

## The four instruments

Each found a class of bug the others could not.

### 1. Replay harness — real model, real audio

`engine/replay/dump_tokens.py` runs the actual sherpa-onnx recogniser over
16 kHz PCM and records the token stream. Everything else scores against that
dump, so the matcher is never tuned against a placeholder.

*Found:* the alphabet mismatch (1), the feed starvation (2).

*Blind to:* anything in the app's own audio path or session lifecycle. It feeds
the recogniser directly and drives no session. Defects 3 and every lock-policy
defect were invisible to it — and the harness itself *reported 28/28* while the
app was wrong. See "a test that cannot fail".

### 2. Faithful policy port — the same code, in Python

`engine/replay/lock_trace.py` mirrors the app's lock decision, all seven
thresholds, the tail replay, and iteration per **poll** rather than per
token.

*Found:* the long jump firing on a nested ayah (9) — the single worst
recognition bug, invisible on the device because the backward branch happened
to walk the lock back and land in the right place anyway.

*Requires discipline:* a port drifts the moment the app changes. Every app-side
policy change has to be mirrored here **in the same commit**, or the harness
goes back to validating code the app does not run. That is exactly how it became
worthless once already.

### 3. Source-parity tests — parse the Kotlin, do not restate it

`search_parity.py` builds its tables *out of* `AyahSearch.kt`.
`session_stream.py` asserts on the text of `ensureVoice`. `ring_buffer.py`
reads the ring capacity out of `AudioRecorder.kt`.

*Found:* the dropped table entry (26), the dead-stream guard (3), the wrap
arithmetic (24).

*Why:* the dual-reading search logic was validated in ad-hoc Python, then ported
to Kotlin by hand with no seam. One entry was dropped in the port and
`العالمين` returned **0 hits instead of 61** — and nothing failed, because
nothing tested it. These tests cannot drift, because they read the shipped
source.

*Trap:* a parity test that re-declares the table it is checking is not a parity
test. It will pass forever while the Kotlin is wrong.

### 4. On-device render measurement

Screenshots are not evidence. Counting pixels is.

*Found:* hide mode was **inverted on 5.1% of the mushaf** (21) and left a
visible silhouette of every masked word (22).

*How:* the hidden render's colour set is a strict subset of the plain render's
— **0 colours appear only when hiding** — and on a sajdah page, 0 of 743
ornament pixels were lost while all eleven words erased. Bit-identical
off-state in both themes.

---

## The traps

These are the expensive lessons. Each one cost real time.

### A test that cannot fail is not a test

`lock_policy.py` claimed to validate the lock and reported 28/28. It only ever
scored `lock+1` and only ever assigned upward — **monotone by construction**. It
could not fail, because it could not express a backward move. Worse, it omitted
six of the seven thresholds and the tail replay, and the one file that
disqualified the claim (`tail_replay_cost.py`) was referenced by nothing.

*Ask: what is the input for which this returns a failure?*

### A baseline that isn't the real code measures nothing

When I swept the per-word status rule, every candidate scored identically and
zero collateral — a suspiciously perfect result. The sweep's baseline was a
strawman: it modelled a simplified rule, not the shipped one, whose real defect
lived in its final `else` branch. Once the true rule was modelled, the shipped
version showed **2 collateral false-WRONGs** and the candidate removed them at
no cost.

*Ask: is the "before" in this measurement the code that actually shipped?*

### Two languages drift silently

Hand-porting logic between Kotlin and Python has no compiler help and no test
seam. One dropped array entry made the most common Arabic search return
nothing. The fix was not the entry; it was making the test *read the Kotlin*.

### Python's `%` is not Java's

Modelling a Kotlin ring buffer in Python silently accepted a broken wrap: Python
floors, Java truncates and keeps the dividend's sign, which is why the Kotlin
needs its `((x % cap) + cap) % cap` fixup at all. Python's negative list
indexing then hid the second half of the bug. A real `FloatArray` would throw.
The test now models Java semantics and bounds-checks every index.

### Coverage normalised per-candidate is not evidence of that candidate

`coverage = unitsMatched / unitsTotal` is computed against each candidate's own
length. So an ayah whose units are a **subsequence** of another ayah's scores
`1.00` before the reciter has finished the first one. Al-Fatiha 1:3 is a
subsequence of 1:1, so the long jump skipped 1:2 on clean studio audio.

No threshold on that number can fix it, because the number is true and means
something else. The gate has to also ask about the item the lock is *currently*
on.

### Banding a DP that has no safe band to give

A proposal arrived from outside the project to speed up the matcher by "simplifying
the band width computation". It was rejected, and the reason generalises.

A banded edit-distance DP prunes cells outside a diagonal band, which is an
**approximation** unless the band provably contains the optimal path. The safe
half-width is bounded by the trivial alignment cost, `max(n, len)` — and that
spans the entire grid. So a *safe* band saves nothing here. The proposal banded
anyway with `max(n, len) + 1`, which is also safe but strictly larger than the
whole grid, and then got the arithmetic wrong three ways:

- `cur[-off] = i` on the first row, because `lo` is zero on every row. It throws
  in Kotlin. A Python model of the same arithmetic *wraps* and hides it.
- the fill wrote the direction table at column `j + off` while the backtrace read
  column `j - i + off` — the same path, offset by `i`. Silent: the wrong
  alignment still produces a plausible number, and that number is `coverage`.
- the doc comment advertised a fast path that was never implemented (`val full`
  assigned and unused), and claimed a 12x cell reduction that the inactive band
  made impossible.

None of that is visible to an equivalence harness, because **none of it changes
a line of Python**. Structural faults in the shipped language need a guard that
reads the shipped source. `dp_source_parity.py` does, and it catches all five
fault classes it was written for.

The speedup that *is* available came from elsewhere: intern the units so the hot
loop compares ints instead of Arabic strings, make the direction table a flat
`ByteArray` when it holds only three states, and roll `dp` into two rows since
the traceback never reads it. All provably value-identical, and proven over
5,473 alignments rather than asserted.

The same episode also showed how easy it is to write a guard that is worse than
none. Mine failed on *correct* code three ways before it worked: its write
pattern's trailing `=` matched the first `=` of a `==`, so reads were counted as
writes and a real mis-indexing passed; it flagged an unreachable `-1` fallback
as a collision risk; and its backtrace check was anchored on a line that did not
exist, so it was dead. Every guard here is now verified to fail on a deliberately
broken copy before being trusted on the real file.

### A permanently-false gate is worse than no gate

The WRONG verdict was gated on `wordProb`. `sherpa-onnx` returns an **empty
`ys_probs`** for streaming CTC with `greedy_search` — the beam decoders that
populate it are unreachable for this model — so the check was always true and
every WRONG was demoted to SKIPPED. The feature was dead on device, behind code
that read like a safety measure. Removed in favour of the frame streak plus an
alignment measured at zero collateral.

*Ask: has this condition ever been observed true?*

### "Not enough evidence" is not "the reciter was wrong"

The word rule ended in `else -> WRONG`, which conflated a partly covered word
with an incorrect one. A global alignment absorbs a deleted word as
substitutions in its neighbours, so skipping one word turned the next red. On
Al-Asr 3:3 with word 1 deleted, the innocent neighbour sat at 1-of-2 units and
the skipped word at 1-of-4 — only the coverage ratio separated them, and
nothing did.

The fix added a neutral state. A matcher with no neutral state has to invent
verdicts from absent evidence.

---

## The defect catalogue

Grouped by area, most instructive first. Full numbers in the commit that fixed
each.

### Lock policy

- **Long jump fired on a nested ayah** (9). Skipped Al-Fatiha 1:2 on clean
  audio; 7 moves and 2 reversals instead of 6 and 0. Fixed by gating on the
  locked ayah looking un-recited. *No threshold was changed* — the sweep then
  showed the false backward-move falling 1/7 → 0/7 for free, because that
  retreat had been a consequence of the jump.
- **Backward branch tested the wrong ayah** (10). `hereCov` was measured before
  the forward and jump blocks had moved the lock.
- **Backward hysteresis was weaker than forward** (11). A leaky decrement with
  no hard reset where the forward path had one; a hard-coded 2 where the
  forward path scaled on words-per-minute.
- **Streaks survived the slice rebase** (12). The empty-slice return neither
  incremented nor cleared them, so a streak armed before a lock move could fire
  on unrelated evidence afterwards.
- **Surah handoff leaked counters** (13). The pending keys were ayah numbers,
  not `(surah, ayah)`, and the handoff changed the surah — so old-surah ayah 2
  armed new-surah ayah 2.
- **No retreat budget** (14). Each move rebased the slice, which drove the
  post-move `hereCov` below the stuck threshold almost by construction: the gate
  was weakest exactly when the lock was most able to ping-pong.
- **A retreat deleted the marks it had just earned** (18). The abandoned ayah
  was repainted from a slice that no longer contained it — SKIPPED, which draws
  as red strikethrough. A retreat is a cursor move, not a retraction.
- **Repeat practice could not rescue a stuck reciter** (19). The counter only
  decremented once the lock was *past* the target.

### Recognition and the audio path

- **Expected side in the wrong alphabet** (1). Coverage identically `0.00` for
  every ayah, every frame, every session. Roughly ten rounds of threshold
  tuning had tuned a constant.
- **The model heard 5.3% of the session** (2). A delta computed against a 3-second
  sliding window using a window-relative cursor: **48,000 of 909,056** samples.
  This was what the `decoder starved 50s/92s` lines had actually meant.
- **Only the first Recite of a process got a stream** (3). Readiness asked "is
  the recogniser loaded", not "does a stream exist", and the flag was never
  cleared — while every Stop, page swipe and Resume destroyed the stream.
- **The feed counter counted samples the model never took** (4).
- **Native stream leaked every session** (5). Nulled, never `release`d.
- **`lastOpError` never cleared** (6), so one error in session 1 was reported
  for the rest of the process.
- **Token counter survived a stream recycle** (7), so a fresh stream reaching
  exactly the pre-move count read as "no new tokens".
- **The recorder grew without bound** (8): a boxed `ArrayList<Float>` copied in
  full every 250 ms — quadratic over a session. Replaced with a primitive ring
  read by absolute index.

### Per-word status

- **A skipped word painted its neighbour red** (15, 16). Fixed by coverage-based
  skip detection plus a neutral `UNKNOWN`. 231/231 word-verdict cases; zero
  collateral over 154 real word observations.
- **The WRONG feature was unreachable** (17).

### Hide mode and rendering

- **Hide was inverted on 5.1% of the mushaf** (21): the word being recited was
  erased while the previous word stayed visible, across **3,975 words**. Caused
  by the sajdah ۞ (199 ayat) and rub-el-hizb ۩ (15) being folded into the word
  text, so those ayat carry an extra glyph box, and an area-ranked tiebreak
  discarded a real word box instead. Now structural: **6,236/6,236** ayat pair
  every word, up from 6,018.
- **Every masked word left a visible silhouette** (22) — a cream rectangle on
  white paper. Night mode matched by accident.
- **Diacritics survived hiding** (23): 855 px of ink outside all 121 word rects
  on one page. Closed with a 3 px outset applied identically to the fill and
  the clip.
- **2,190 glyph rows discarded** (24) by a size test that also caught inverted
  boxes.
- **`drawPlayHeadWord` was dead** (25) — it joined on a 0-based text ordinal
  against a 1-based DB column, so it returned early on every page.

### Search and data

- **The most common Arabic search returned nothing** (26). `FOLDS[0x0670]` was
  never ported: `العالمين` gave 0 hits instead of 61, and 4,421 ayat leaked the
  superscript alef into the index.
- **A false "engine files missing"** (27). `File.isFile` is also false when a
  path cannot be traversed, which is exactly what a root-owned adb-pushed
  directory looks like — so a file genuinely on the device was reported as
  absent, and the message blamed the user.

### The harness itself

Worth recording, because these are the errors that make measurement worthless:

- **A stale 5-tuple return** in the shared aligner aborted the run, so **two
  ayat of Al-Asr had never been word-tested at all**.
- **The skip case demanded every other word be CORRECT** — which fails the very
  defect the test was written to catch.
- **A strawman baseline** in the rule sweep (see above).
- **A `-1.0` sentinel printed where a probability belongs**, reading like a
  score.
- **`prob` is unavailable, not broken.** Three independent confirmations,
  including the C struct itself: the streaming result carries no probability
  array at all. Only the offline recogniser does, and this model cannot be
  driven by it.

---

## Reproducing every check

The SDK is local; these need no network.

```bash
cd /home/oldbrain_exe/WorkingOn/SideProject/iqra
export ANDROID_HOME=/home/oldbrain_exe/Android/Sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME

# build (offline)
(cd android && ./gradlew :app:assembleDebug --offline)
# add x86_64 for the AVD only; the released artifact is arm64
(cd android && ./gradlew :app:assembleDebug --offline -PemulatorAbi=true)

# recognition, six real clips
engine/.venv-replay/bin/python engine/replay/dump_tokens.py \
    --out engine/replay/out/s001.json engine/audio/001.raw
for spec in "s001 1 7" "s103 103 3" "s108 108 3" \
            "s112 112 4" "s113 113 5" "s114 114 6"; do
  set -- $spec
  engine/.venv-replay/bin/python engine/replay/word_verdicts.py \
      engine/replay/out/$1.json $2 $3      # 0 failed
  engine/.venv-replay/bin/python engine/replay/lock_trace.py \
      engine/replay/out/$1.json --surah $2 --ayat-count $3
done                                    # 28/28 ayat, 0 reversals

# the rest
for t in search_parity ring_buffer session_stream word_rule_sweep \
         dp_source_parity; do
  engine/.venv-replay/bin/python engine/replay/$t.py   # all PASS
done
# the DP: value-identity, and a source guard for what Python cannot see
engine/.venv-replay/bin/python engine/replay/dp_equivalence.py
engine/.venv-replay/bin/python engine/replay/dp_source_parity.py

# lock behaviour on real and synthetic hesitation
engine/.venv-replay/bin/python engine/replay/hesitation_policy.py
engine/.venv-replay/bin/python engine/replay/back_policy_sweep.py
engine/.venv-replay/bin/python engine/replay/feed_starvation.py
```

On-device evidence:

```bash
adb shell "run-as com.iqra.quran cat files/diag.log"   # rotated: diag.log, diag.1.log
```

Diagnostics now has a **Copy frame ring** button: TSV, one row per frame, with
every threshold in the header. It is the only artefact that can answer "why did
the lock decide that" after the fact.

---

## Leads not yet closed

1. **No real hesitation audio.** Every backward-lock result comes from
   *rearranged emissions* — real symbols in a new order. That tests the gate
   arithmetic and says nothing about whether the acoustic model actually emits
   those symbols for a hesitant reciter. `engine/audio/hesitate/README.md` has
   the format. This is the largest remaining gap and it needs a person with a
   microphone.
2. **Tail replay cannot be re-decoded offline.** A token dump has no audio, so
   the 1.5 s replay at each move is bracketed: replayed (optimistic) or blank
   (pessimistic). Reality is between them, and the gap is large — under the
   pessimistic bound the clean Fatiha clip stalls at 1:2. Only a real recording
   closes this.
3. **One word in 77,429** (`36:18` word 12) has a DB box that overlaps the
   ayah-end roundel by 94%, so hiding it erases the marker too. Not
   structurally detectable; left alone rather than special-cased.
4. **The page-boundary scope is a design question, deferred.** A session is
   bounded to the visible page; crossing a page mid-surah is not something the
   policy can act on unaided. Deliberately left as-is.
5. **No per-frame VAD readout.** `SherpaVad.feedAndDetect()`'s verdict is folded
   into the gate and discarded; Diagnostics shows the session-level `VAD`/`RMS`
   label instead. A true per-frame row also needs a probability getter the
   sherpa binding does not expose, and would alias against a 300 ms poll.
6. **Hide mode is verified in the page-image path only.** The missing-page text
   fallback was corrected but has no page to fall back from in practice.

## The DP is now executable by CI (two independent layers)

The matcher was a pure-Kotlin routine inside an Android class. Hosted CI could
compile it and never run it, so every proposal to change it was reviewed by
reading alone - and in one week two of the three that arrived were broken (one
crashed on a negative array index, one silently banded away most of the grid).
Reading was not the failure. The failure was that reading was all there was.

Two layers, deliberately not sharing a mechanism, so a blind spot in one is not
a blind spot in both:

1. **A pinned digest.** `UnitAligner.kt` holds the traceback and nothing else -
   no `android.*`, no `Log`, no `WordStatus`, all asserted by a test. Kotlin and
   `dp_equivalence.py` walk the same generated vector space (961 pairs up to
   length 4, plus 192 larger asymmetric cases up to 48) and fold it to one
   FNV-1a digest, currently `0x743fce71b4fbecd0`. A change to either side moves
   the number, and one failing assertion replaces a transcript to read.
   `:app:testDebugUnitTest` runs it on every build.

2. **The source guard.** `dp_source_parity.py` reads the Kotlin and checks the
   structure. It was repointed at the extracted file rather than deleted, and a
   second check now fails if `PhonemeMapper` stops calling the kernel or grows
   its own direction table - so there is exactly one traceback, and the digest
   describes what the app actually runs.

Both were verified by injecting faults, not by reading the checks:

| injected fault                          | caught by                    |
|-----------------------------------------|------------------------------|
| banded fill                             | source guard                 |
| `best` seeded from insertion            | source guard                 |
| insertion considered before deletion    | source guard + digest        |
| backtrace stops bailing on unknown dir  | source guard                 |
| backtrace tail branches removed         | source guard                 |
| interning bypassed at the lookup        | source guard                 |
| caller stops using the kernel           | source guard                 |
| interning built from the wrong list     | source guard                 |

Three of those eight slipped through the first version and were fixed before this
was trusted. Two had been hiding in plain sight: the tie-break check verified
the order sub/del/ins were *declared*, not which one *won* a tie, and the
interning check verified the map existed rather than that anything looked up
through it - an expected unit read straight into the `IntArray` would not even
have compiled. A guard that passes a fault is worse than no guard, because it
looks green.

### The measured lesson

A skipped check that renders as a pass is indistinguishable from a working one
at a glance, which is precisely how the 5.3% feed bug lived under a green build
for so long. So the gate prints `SKIP` and says so in the summary, and CI runs
the five model-free checks only - the five that need no model, no sherpa, no
numpy and no audio, and cost about fifteen seconds.

## The whole-Quran corpus (Al-Dosari, 114 surahs, 6236 ayat, 26 h)

Run to completion in 6.1 h. A different reciter from the pinned Husary six, so
it is a stress test rather than a pass/fail gate - and it found more than the
first seven surahs did. Judging the policy from those seven would have shipped
all nineteen failures.

| | sequential | complete |
|---|---|---|
| as first measured | 95/114 | 101/114 |
| after the tail-drain fix | **104/114** | **112/114** |

### Ten of the nineteen were the harness, not the app

After every forward move the fresh stream is handed 1.5 s of already-decoded
audio (`TAIL_SAMPLES`, `PracticeViewModel.kt:1157`), so it runs that far behind
live audio. The replay loop stopped at end of file, so the backlog could never
drain and the last frames of a surah were never consumed. The symptom was a lock
that refused to advance onto the FINAL ayah, on audio whose tokens ran to 100 %
of the file, with the target's coverage sitting comfortably above `ADVANCE`.

On a device this cannot happen - the microphone keeps delivering frames after
the reciter stops. `simulate()` now runs `tail_frames` extra polls past the end
of the recording, which models exactly that. Eleven surahs repaired by that one
line. Verified three ways: surah 97, 105 and 53 each advance with the drain on
and stop one short with `--drain-frames -1`, which reproduces the bug exactly.

`--rescore` then re-ran all 114 from the existing dumps - no ffmpeg, no
recogniser - in 110 minutes. A harness fix has to be applicable to a finished
run, or every future finding costs another full pass.

`tail_seconds` itself was then swept over {0, 0.75, 1.5, 3.0} on five surahs:
**final lock, moves, oscillations and deviations were identical in all 20 runs.**
The only thing that moved was the trailing stall, by exactly `-tail_seconds`. So
the tail length is not a correctness knob; it delays the trajectory in wall-clock
and nothing else. `tail=0` is a diagnostic bound, not a candidate fix - the device
always has the tail.

### Four of the six jumps were correct, and the metric called them failures

A jump is non-sequential by definition, so a *correct* jump - one over an ayah
the reciter really did skip - was being counted as a deviation. Measuring
coverage of the skipped ayah against the emission stream (a best-contiguous
variant, because coverage over a wide window is 1.00 for the skipped ayah in 5
of 6 cases and tells you nothing):

`lock_trace.local_coverage()` does this now: semi-global in the query (free
start, free end), global in the reference, substitutions allowed inside the
matched span - because a single recogniser slip must not break the run, which is
why a strict longest-common-substring is the wrong tool. It is scored against a
null baseline of the same ayah over same-length windows elsewhere in the same
surah, since a three-word ayah scores high by chance in any window.

`run_corpus.py` now runs this automatically for every jump and emits
`jump_verdict`. Borderline cases are reported as AMBIGUOUS with their numbers
rather than forced to one side, because forcing them is the same mistake this
whole function exists to remove:

| surah | jump | skipped | local | null | verdict |
|---|---|---|---|---|---|
| 52 | 52:5 -> 52:7 | 52:6 | 0.182 | 0.273 | reciter skipped it |
| 74 | 74:30 -> 74:38 | 74:31-37 | 0.017 | 0.022 | reciter skipped 7 |
| 80 | 80:17 -> 80:19 | 80:18 | 0.333 | 0.083 | reciter skipped it |
| 81 | 81:13 -> 81:15 | 81:14 | 0.500 | 0.143 | ambiguous |
| 8 | 8:68 -> 8:70 | 8:69 | 0.590 | 0.154 | ambiguous |
| 54 | 54:37 -> 54:39 | 54:38 | 1.000 | 0.174 | **recited but missed** |

Three were unambiguously the policy working, one is a real miss, and two do not
admit a verdict at this threshold. The real miss shares a mechanism worth
naming: **coverage is computed over the post-rebase slice only, so a jump can
fire on arrival order rather than presence.** At surah 54's jump the slice held
13 symbols scoring 54:39 = 0.929 against 54:38 = 0.130, because 54:39's audio had
already flowed through while 54:38's had only just arrived.

### Ar-Rahman 55: a real deadlock, and no threshold fixes it

The lock stops at 55:63 and stays there for 86 seconds. **55:63's expected units
are byte-identical to 55:65's, 55:67's, 55:69's** - the refrain occupies every
other ayah. So the slice permanently contains a full exact copy of the locked
ayah, `here_cov` pins at **1.000** and never dips below `STUCK`, which shuts the
jump gate; and `next_cov` sits at 0.375-0.500, inside the dead band
`[WEAK, ADVANCE)`, which shuts the forward gate. Neither can open.

The successor, 55:64, is a one-word 8-unit ayah (*mudh-hamat*) that the
recogniser essentially never produces - the best 5-second window anywhere in all
530 s is 2/8, and during the stall it emitted only `mim-ta`. Meanwhile coverage
of 55:65, 55:66 and 55:67 against the same slice is **1.000**: the lock is eight
ayat behind and can see it, but the policy only ever evaluates `ayah+1`.

There is no threshold that fixes this. The evidence is absent - the recogniser
cannot hear the ayah - so the right response is to *say so*, which is why the app
now reports the stall and its cause. Retuning here would be the exact mistake of
optimising a number that was never wrong.

One hypothesis of mine was wrong and the measurement said so: I expected the
jump gate to be unreachable, because coverage over a growing window can only
increase. It is open on **85.3 %** of evaluated polls - the rebase resets the
window on every move, so `here_cov` stays low normally. The freeze needs the
refrain specifically.

### What the metrics could not see

`stall_gaps` measured only gaps *between* moves, so a stall running to the end of
the file was invisible: surah 55 sat on one ayah for its final 99 seconds and
scored as having no stalls at all. The report now carries `trailing_stall_sec`,
`stuck_polls` and `stuck_dead_band_polls`, and `TraceResult.stuck` samples the
coverage pair during a stall so the next occurrence explains itself instead of
needing to be re-derived by hand.

## The deadlock had an exit already; my attempt to add one was wrong

`pinned-escape` is in both `PracticeViewModel.kt` and `lock_trace.py` and fires
only from the dead band: `nextCov` in `[WEAK, ADVANCE)` while `hereCov` is
strong, for longer than `PINNED_ESCAPE_MS`. Lowering `ADVANCE` would also have
fixed surah 55, but `ADVANCE` exists to stop the lock crediting an ayah on a
phrase it has not finished, so that was not an option.

It had never been run against the corpus - it was written *after* the first
scoring pass. Validated now, on real audio, both directions:

```
surah 55, 78 ayat, 530s audio
  escape OFF: final 55:63   moves 62   stuck_polls 118
  escape ON : final 55:78   moves 74   escapes 2
    escape 63 -> 64 at t=445s (next_cov 0.500)
    escape 65 -> 66 at t=468s (next_cov 0.412)
```

`engine/replay/pinned_escape.py` is that A/B as a permanent check, including the
premise: it fails if 55:63 and 55:65 ever stop being byte-identical, because the
whole diagnosis rests on the refrain. It also fails if an escape steps more than
one ayah, or if escapes become frequent enough to stop being an escape hatch.

### The addition that was built and then deleted

`pinned-escape` needs `nextCov >= WEAK`. A successor scoring *below* `WEAK` is
equally unrecoverable, so I added a stall-recovery path: after a sustained stall,
read a recent window, and if something two or more ahead beats the locked ayah
by a margin, step forward one ayah. Zero occurrences in 114 surahs - the corpus
never reached that state - so I built a synthetic one to test it, and the
synthetic test refused to isolate the branch.

Two reasons, both worth recording:

1. Diluting a successor does **not** create the deadlock. The slice grows with
   the unheard material, `hereCov` falls, and the jump gate opens and rescues
   the lock. Holding `hereCov` high requires the locked ayah's text to keep
   reappearing - which is the refrain again.
2. In the refrain case the margin test cannot fire, because the candidate it
   compares against is *identical text*. 55:63, 55:65 and 55:67 are all the same
   20 units. `bestCov >= hereRecent + 0.10` is `1.0 >= 1.0 + 0.1`: never.

So the branch was unreachable in the one situation that needed it, and
`pinned-escape` already covers every deadlock the corpus can actually produce.
It was removed from both files rather than left in as untested policy. The
unreachable state is worth knowing about and not worth shipping code for: the
lock's inability to hear a successor is an evidence problem, and no policy
change creates evidence.
