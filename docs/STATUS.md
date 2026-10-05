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
| 66% of the Quran silently uncoloured | UNKNOWN instead of `continue` | `word_alignment_parity`, 4116/6236 |
| Stall invisible in every metric | `trailing_stall_sec`, `TraceResult.stuck` | surah 55's 99 s was scored "no stalls" |
| Handoff never executed by any test | `handoff_boundary.py` | `lock_trace.py:61` said so for months |
| Harness never drained the tail backlog | `simulate()` drain | 11 surahs repaired |
| Deadlock state had no exit | `pinned_escape.py` | 55:63 frozen → 55:78 complete |
| Session records lost on process death | timer flush, atomic rename | a killed session now leaves a file |
| Two faults in my own recorder | `sessionEvaluations`, post-teardown flush | found by reading a real record back |

## Open, ranked by what is actually hurting you

**1. Page turn at a surah end — still not right.**
The lock is clean; the page is not. Evidence from the retest: handoff now takes
9–12 s instead of 42 s, and the lock trajectory has no oscillation at all, so what
remains is page behaviour. The cause of the *flapping* was a noisy coverage
threshold with no latch — fixed in `fd4d2e4e`. **Unverified on device.** If it
still misbehaves, the next thing to separate is the case you raised yourself:
continuing *within* a surah across a page break is a different problem from moving
to a *new* surah, and the first belongs to the swipe logic, not to handoff.

**2. Word-level judgement for 4116 of 6236 ayat.**
The recognition phoneme table segments on phoneme-phrase boundaries, the Mushaf
on orthographic words. They disagree at a waqf mark, where the phonemiser merges
two words into one entry. The counts cannot be reconciled by counting (coarser in
4089, finer in 27). The ayah now yields UNKNOWN — the highlight shows, nothing is
accused — but **no word-level verdict is possible** until table entries are mapped
onto Mushaf words. That needs an alignment through an orthography the phonemiser
deliberately altered (alef dropped or turned into lam, shadda expanded to a
doubled letter), and then a decision about what verdict two words sharing one
entry should get. A boundary in the wrong place puts a WRONG verdict on the wrong
word, which is the exact harm this feature exists to prevent. Deliberate work, not
a change to make quickly.

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
engine/.venv-replay/bin/python engine/replay/run_checks.py        # 14 checks, ~100 s
```

Five run anywhere in CI in ~15 s. The rest need the model and report SKIP loudly.

Every check in this repository asserts that a judgement is **correct**. For six
months none asserted that a judgement was **reached** — which is how 66% of the
Quran could vanish without a single test turning red. New checks should ask both.

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