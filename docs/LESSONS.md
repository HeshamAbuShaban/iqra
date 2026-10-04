# What this project learned the hard way

This is not a defect list. [DEFECTS_AND_METHOD.md](DEFECTS_AND_METHOD.md) is
that — every bug, how it was found, and which instrument caught it. This is the
shorter, more useful thing: the handful of decisions that would each have saved
weeks, written down while they are still fresh enough to be honest about.

Read it if you are building something that listens to people recite, if you are
picking up this codebase, or if you want to know why the obvious approach is not
the one being used here.

---

## 1. Measure on the whole corpus before you believe the first sample

Six surahs, 269 seconds, clean studio audio, and the lock was perfect: 28/28 ayat
in order. It was also **19 failures waiting to happen**.

The full run — 114 surahs, 6,236 ayat, 26 hours of a *different* reciter —
found nineteen surahs that were not clean. Judging the policy from the first
sample would have shipped every one of them.

The six that looked perfect were perfect *because* they were the pinned reference
clips: same reciter, studio conditions, forward, in order. They tested the happy
path and reported it as a pass rate.

> **Build the corpus before you tune.** Six clips answer "does this work at all".
> Only the full corpus answers "how often does this work", and the two questions
> have almost nothing in common.

## 2. Ten of those nineteen failures were the measuring instrument

This is the one worth internalising. After every forward move the app hands the
fresh audio stream 1.5 s of already-decoded audio, so the stream runs that far
behind live audio. The replay loop stopped at end-of-file, so that backlog could
never drain and the last frames of a surah were never evaluated.

The symptom: a lock that refused to advance onto the **final** ayah, on audio
whose tokens ran to 100% of the file, with the target's coverage sitting
comfortably above the threshold. It looks exactly like a broken policy. It was
not; it was a replay that stopped a second and a half before the device would.

One line — run the simulation past end-of-file so the backlog drains — repaired
eleven surahs. Verified by reproducing the bug on demand with a flag.

> **If your harness and your device differ in any timing behaviour, your harness
> is measuring a different program.** Every number it produces is about itself
> until proven otherwise. And make the difference *reproducible*: a flag that
> restores the bug is what turns "I think this fixed it" into a check.

## 3. A metric's blind spot is always at its own threshold

`stall_gaps` recorded gaps over 25 s between consecutive lock moves. So a lock
that froze on one ayah for the final **99 seconds** of a surah scored as having
no stalls at all. The worst failure in the corpus was invisible to the number
that exists to find it.

The same shape appeared twice more: the report could not see a stall running to
end-of-file, and a jump-classification threshold sat at 0.60 while the case that
mattered scored 0.59 — close enough to be interesting, close enough to be
invisible.

> **Ask what your metric cannot see, then go and look for that.** The blind spot
> is never a random shape; it is always exactly the thing the threshold was
> chosen to exclude.

## 4. Make the numeric kernel executable, then break it on purpose

The matcher was a pure-Kotlin routine inside an Android class. CI could compile
it and never run it, so every proposal to change it was reviewed by reading
alone. In one week, **two of the three** proposals that arrived were broken — one
crashed on a negative array index, one silently banded away most of the grid.

Reading was not the failure. The absence of execution was.

The fix has two layers that deliberately share no mechanism, so a blind spot in
one is not a blind spot in both:

1. **A pinned digest.** The kernel is a pure file with no Android imports, so it
   runs on the JVM. Both languages walk the same generated vector space and fold
   it to one number; a change to either side moves the number.
2. **A source guard** that reads the Kotlin and asserts its structure, including
   that the caller has not grown a second traceback.

And then the part almost nobody does: **inject deliberate faults and confirm the
checks catch them.** Banded fill, wrong tie-break order, seeded from the wrong
state, a backtrace that bails early — each was injected, each was caught, and the
table of which check caught which fault is in the defect document. A check that
has never been shown to fail is not a check.

> **Numerically identical is a stronger claim than "looks right", and it is
> checkable.** Two independent implementations agreeing on 1,153 generated cases
> is worth more than a transcript of someone reading carefully.

## 5. Delete the branch that cannot fire

A stall-recovery path was written, tested, found to have **zero occurrences across
114 surahs**, and then removed — because the synthetic test needed to exercise it
refused to isolate the branch, and the one situation that needed it was a case
where the branch provably could not fire.

It was not shipped "just in case". An unreachable recovery path is not free: it
is untested policy sitting in the decision loop of a memorisation app, waiting to
be wrong in a way nothing will catch.

> **"Unreachable" is a finding, not an excuse.** Record what makes it unreachable
> — here, that diluting a successor makes the slice grow, which drops `hereCov`
> and reopens the gate that was meant to rescue it. That sentence is worth more
> than the code was.

## 6. Coverage is not evidence of *that* verse

Coverage of a candidate is computed over the observation window, and the window
is wide. So a verse that was **skipped** still scores 1.00, because its units do
appear — scattered across neighbouring ayat.

This is why four of six "jumps" were the policy working correctly, and the metric
called them failures. It is also why a jump can fire on *arrival order* rather
than presence: one real miss scored the target 0.929 against 0.130 for the verse
that was actually being recited, purely because the target's audio had already
flowed through.

The fix is not a better threshold. It is a **contiguous** read — semi-global in
the query, global in the reference, substitutions allowed inside the matched span
because a single recogniser slip must not break the run — scored against a **null
baseline**: the same verse, over same-length windows, elsewhere in the same surah.
A three-word verse scores high by chance in any window.

Cases that do not separate are reported `AMBIGUOUS` with their numbers. Forcing
them to one side is the exact mistake the measurement exists to remove.

> **Normalise per candidate and you have measured "could these units be here",
> not "were they here".** The difference is the whole product.

## 7. Identical text breaks anything that scores candidates one at a time

Ar-Rahman 55. Verses 63, 65, 67 and 69 are **byte-identical** — the refrain
occupies every other verse. So:

- `hereCov` pins at **1.000** and never dips, which holds the jump gate shut;
- `nextCov` sits at 0.375–0.500, inside the dead band between the advance and
  decay thresholds, which holds the forward gate shut.

Neither gate can open. Meanwhile the successor, 64, is a one-word verse the
recogniser essentially never produces — best window anywhere in 530 seconds is 2
of 8 units.

There is no threshold that fixes this. The evidence is absent, and no policy
change creates evidence. What the app can do is *say so* rather than pretend.

This is the strongest argument in the project for scoring a **page of expected
text in one monotonic alignment** instead of N candidates independently: a
monotone path cannot sit on verse 63 forever while 65, 67 and 69 lie ahead of it,
and it cannot score verse 1:3 highly while the reciter is still on 1:1. The
alignment kernel already in this repo has the required traceback. It is currently
called one verse at a time, which throws away the only thing an alignment knows
that a scalar does not.

## 8. A normaliser can point the wrong way

The first working gate was `Levenshtein.ratio(emission, wholeAyah) >= 0.60`.
That score is length-normalised against one verse, so **it falls as the reciter
gets further into the verse**:

| recited | old ratio | coverage |
|---|---|---|
| 20% | 0.484 | 0.770 |
| 60% | 0.233 | 0.906 |
| 100% | **0.148** | 0.948 |

The gate was mathematically unreachable, and ten rounds of threshold tuning were
tuning a constant. The fix — score by **fraction of the candidate's units the
emission accounts for**, ignoring insertions — makes the score *rise* with
progress, which is the direction a progress signal has to move in.

> **Before tuning a threshold, print the score across the range you expect to
> see.** If it is flat, or decreasing where it should increase, no threshold can
> work and every tuning round is wasted.

## 9. Two alphabets beat every threshold in the book

The acoustic model emits individual phoneme units. The phoneme table stores
per-word strings. They were compared directly, so a symbol could never equal a
word, so coverage was identically `0.00` — for every frame, every verse, every
session.

Roughly ten commits had already retuned thresholds, the silence gate, scope
windows and recovery heuristics against that constant.

The fix was to explode each expected word into the model's own unit inventory by
greedy longest match, keeping a unit→word map so per-word verdicts still work.

> **Verify the two sides of a comparison are in the same alphabet before you
> measure anything about the comparison.** It is one line of inspection and it
> cannot be recovered from by tuning.

## 10. Replay cannot see your audio path or your lifecycle

Two defects survived a full corpus run because both live in the app, never in the
harness:

- **The recogniser received 5.3% of the session.** The per-poll delta was
  computed against a *sliding* window using a window-relative cursor, so once the
  buffer passed three seconds the delta was permanently empty.
- **Only the first recitation of a process ever got a stream.** Releasing the
  stream cleared a flag that was never set, so every later session reported itself
  ready for a stream that did not exist — and accepted audio into nothing.

Both were found by a **user report**, not a test. The log read
`fed=161792 toks=0` — audio apparently flowing, model mysteriously mute — and not
one sample had reached the model.

> **A harness that drives the recogniser directly cannot exercise capture,
> session teardown or process death.** Those are where the unmeasured failures
> live. When the logs say audio is flowing, check that the model *received* it.

## 11. A human is an instrument you cannot build

Everything above is measured on file audio through the real recogniser. It has
never been tested against a person: hesitation, a deliberately mispronounced
word, a skipped verse, a start from the middle of a surah, a cold room, a cheap
microphone.

That gap is the largest one in the project and it needs someone with a phone and
a verse to recite. `scripts/collect_clips.py` is the loop that closes it —
record, pull, score, one table.

## 12. The strongest reference implementation does none of this

Worth knowing before rebuilding any of it.

**quran_android** has no microphone permission, no model, no alignment code. Its
follow-along is `indexOfLast { it.startTime <= pos }` against a downloaded
per-reciter timing database — pure timestamp replay of audio the user is playing.
Its recitation feature is fully scaffolded and entirely unimplemented, disabled
by a hard-coded `false`.

**Tarteel** splits the two problems the same way: follow-along is free and local
via a timing database; word-level mistake detection is the **paid** feature and
runs server-side, streaming your voice to a cloud endpoint.

So the reference implementations solve the *easy* half locally and send the hard
half to a server. This project does the hard half on the device instead. That is
a real differentiator and it is also why the recognition had to be built from
nothing — and why the harness discipline above is not optional overhead.

## 13. Two sentences are worth more than a paragraph of tuning

Every one of these was found by writing down what the number *could not* see:

- "A stall running to end-of-file is invisible to a metric that only measures
  gaps between events."
- "The locked verse being fully covered is exactly why it could not recover."
- "Re-scoring an unchanged window is not persistence."
- "A skipped verse still scores 1.00, because its units do appear."

None of those is a threshold. Every one of them changed what got built.

---

## If you only remember five things

1. Measure the whole corpus before you trust the first sample.
2. Check your instrument models the device, and make the difference reproducible.
3. Extract the numeric kernel so CI can execute it, then fault-inject the checks.
4. Look for what your metric cannot see. The blind spot is always the threshold.
5. Coverage says *these units could be here*. Presence needs a contiguous read
   and a null baseline.