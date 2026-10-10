# Maintaining and upgrading this app

Read `docs/CORE_LOGIC.md` first. That file tells you what the app does at each
step. This file tells you how to change it, how to prove the change, and how to
put the app on a phone.

The words follow the same rule as `CORE_LOGIC.md`: short sentences, one idea in
each sentence, the active voice, and approved words. Some technical words do not
have a simple alternative. Those words stay.

---

## The one rule that never moves

> **A verdict is an accusation.**

Before you change anything, answer one question. Can this change make the app
blame a reciter for something that is legal? If the answer is yes, or if you
cannot tell, do not ship it. Ask a question first.

A wrong verdict is worse than no verdict. A reciter who sees one bad verdict will
not trust the good ones.

---

## How to change recognition code

1. Read `docs/CORE_LOGIC.md`. Find the step you will change: detect, decode,
   judge, move, paint, or record.
2. Make the change.
3. Run the gate. See "How to run the gate" below.
4. Put the APK on the phone.
5. Ask the user to recite. A device session record is the only evidence that
   the change works.
6. Update `docs/STATUS.md`, `docs/CORE_LOGIC.md`, and `docs/TODO.md`.
7. Commit. Then push.

Change the documents in the same commit as the code. A change to the code that
leaves the documents stale teaches the next person a lie.

---

## How to run the gate

```
python3 engine/replay/run_checks.py
```

The gate runs 25 checks. It takes about four minutes. All checks must pass.

To run one check:

```
python3 engine/replay/run_checks.py --only dead_code
```

Some checks need the gated audio model. Those checks report SKIP when the model
is absent, and they say so. A check that reports SKIP over an empty set is a lie.
Never treat a SKIP as a PASS.

---

## How to add a check

Every check must be shown able to fail. This is the whole rule.

1. Write the check.
2. Break the thing the check measures. Confirm the check goes red.
3. If the break did not turn it red, the check measures nothing. Delete it, or
   write it again.
4. Add the check to the `CHECKS` list in `engine/replay/run_checks.py`.

Two faults to avoid:

- **Do not check for a symbol.** Do not grep for a function name or a variable
  name. A name is not a behaviour. A check written against a name fails when the
  name is deleted and the behaviour is still correct. It also passes when the
  name survives and the behaviour is gone. `handoff_boundary.py` failed for
  exactly this reason. It grepped for a variable that was removed, and it went
  red on code that was still correct.
- **Do not let prose satisfy the check.** Strip comments before you look for a
  match. A comment that says "this is checked" is not the check.

Three checks exist here because of faults the first ones could not see:

| Fault | What happened | What it cost |
|---|---|---|
| A check parsed the wrong number from a two-number line | `word_verdicts` printed `"0 failed"` of its own while 64 cases failed | 64 failures reported as a pass, for weeks |
| A check grepped for a substring the script printed always | `lock_trace` tested `oscillations=0`, which its own script prints unconditionally | Disabling the lock's forward branch left it green |
| A check required four spaces of indentation | `dead_code` skipped every declaration in `MainActivity.kt` | 43 dead declarations invisible in one file |

Write the check against the behaviour. Break the behaviour. Watch it go red.

---

## How to swap the phoneme recogniser

The app is built so a new model can be inserted. Do not delete the current
path. Add the new path beside it, and prove the new path against the old one.

The contract between the app and the model:

| The app needs | Where it comes from |
|---|---|
| A phoneme symbol per emission | The model's token list, mapped with `ordered_quran_phonemes.json` |
| The model's own unit inventory | `tokens.txt`, the longest-first greedy match |
| Audible symbols | `SherpaZipformer.accept` and `decodeIfReady` |
| The expected side expressed in those units | `PhonemeMapper.ensureUnits` |

Keep the alignment in `PhonemeMapper` and `UnitAligner` unchanged while you
change the model. The gate pins the two to each other, so a model change that
breaks alignment fails a check rather than a phone.

A new model must pass these three things:

1. The emission log must grow across a stream reset. See `emission_log_replay`.
2. A word must be heard before it can be wrong. See `WRONG_MIN_HEARD_COVERAGE`.
3. A dead word must not be accused. See `word_verdicts`.

---

## How to add a tajweed rule

The engine cannot judge some things. Do not force it. Add the rule in the right
shape.

The three shapes, in order of preference:

1. **A hard prohibition.** The rule says the app may not accuse on this
   difference at all. Use this when the difference is legal, no matter how common
   it is. Example: madd length, where 9,706 of 51,395 sites let you choose.
2. **A guard that removes blame.** The app stops accusing at a specific,
   measurable place. Use this when the difference is legal in one position only.
   Example: the first unit after a waqf mark, or the last unit of a word whose
   final letter need not be said.
3. **An advisory.** The app notes the difference and says nothing about blame.
   Use this when you cannot tell a legal difference from an error.

Never do this:

- Do not make a rule that can add blame. A rule that only removes blame is
  safe by construction. A rule that adds blame needs a measured threshold first.
- Do not write a rule you cannot measure. The sweep over 6,112 ayat of reference
  audio found that the optional-final guard removes **2%** of the reference
  reciter's false accusations. The same guard removed 5 of 16 in the user's four
  sessions. The small sample was enriched for that class. Measure on the corpus
  before you claim a fix.
- Do not leave a rule that cannot fire. A rule that looks like a safety measure
  and is dead code is worse than no rule.

---

## How to put the app on the phone

```
cd android
./gradlew :app:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

The recogniser needs three files under `/sdcard/Iqra`. They are not bundled.
`AssetPaths` reports their state in the diagnostics screen.

Say "pushed" for git and "installed" for the phone. They are two different
facts. Conflating them has twice left the user believing a build was on the
device when it was not.

---

## How to know the app works

A green gate is not proof. A green gate proves the checks pass. Three kinds of
evidence, in order of weight:

| Evidence | What it proves | What it does not prove |
|---|---|---|
| A device session record | What the app did on the phone, with the user's voice | Nothing about a different voice or room |
| A corpus sweep over the reference reciter | That a class of defect exists | What the user saw. The harness fits its own window; the app uses a different one |
| A green gate | That the checks pass | That the app is correct |

When you report a number, say which of the three produced it, and say which
window it was measured in.

---

## Ownership

| Area | Owner |
|---|---|
| `PracticeViewModel.kt`, `PhonemeMapper.kt`, `MainActivity.kt`, `engine/replay/*`, `docs/*` | The recognition work described here |
| `data/PracticeLog.kt`, `ui/SettingsScreen.kt` | Another agent. Stage by path, and run `git status` before you commit |
| `ui/theme/*`, chart helpers, `HomeScreen.kt` | The styling surface. Dead-code deletion is allowed here after `dead_code.py` flags it |

The device is one thing. Install once, and ask for a session before you change
the app again.

---

## The open defects, in order

These are measured, not guessed. The numbers are in `docs/STATUS.md`.

| Defect | Size | Status |
|---|---|---|
| The first word of an ayah is accused falsely | 320 of 768 in the reference corpus, 42% | Three hypotheses measured and rejected. Cause unknown. |
| `noWindowWords` returns on long sessions | 2,501 words over 1,428 s | Open. Scales with session length and lock moves. |
| The gated long jump skips an ayah | 2 measured events | Open. `nextCov=0.50` both times. |
| Page turn at a surah end | code changed | Unconfirmed on a device |
| The standing-word pointer | can be null | Open |
| UI polish: text size, list order, RTL on live | reported | Deferred |

Do not close one of these without a measurement that names the cause. Do not
close two of them with one commit.

---

## If you only remember one thing

Break the thing, then look at the check. If it stays green, the check is
decoration.
