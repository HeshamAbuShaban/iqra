# The core study — what the app is for, what it lacks, what it handles poorly

Written 5 November, from the docs' own measurements, the code as it stands,
and the device sessions of October and November. Every claim carries its
anchor. This document exists to rank the NEXT core work honestly — including
the things that should never be built.

---

## 1. The core job, stated plainly

> A second pair of eyes for the hours you practise alone.

The loop the app serves: **recite → be followed → be judged per word → keep
the evidence → review what was heard → improve.** Everything below is
measured against that loop. The app's differentiator is unchanged and
load-bearing: the hard half runs on the device, nothing leaves the phone,
and a wrong verdict is worse than no verdict.

## 2. What the core proves it can do (measured, not hoped)

- Word-level verdicts on a real voice: October–November sessions run
  90–96.6% accuracy rows on the phone, with the kind split clean on every
  wrong row (zero violations across all pulled records).
- Verdicts land DURING the recitation (median −3.8 s before the ayah's
  window audio completes; the floor is the model's own phoneme cadence).
- The evidence chain is closed: record → read-back (device_blame) → desktop
  replay → the report's per-row clip and kind line — all matched against the
  pulled records row by row.
- The advisory channel (7 kinds) and the blame-removal guard family (waqf,
  optional-final, unanchored-head, final-vowel) are live and gated.

## 3. THE BIGGEST CORE GAP — the app judges tilawah; the memoriser's job is muraja'ah

The session model follows one surah forward, page by page. The docs name it:
"a tilawah model, not a muraja'ah model." A memoriser's actual loop — new
sabaq, recent revision, older manzil, consolidation by repetition, peeking
without penalty-shame, hearing themselves — is almost entirely absent:

| The memoriser needs | Today |
|---|---|
| Sabaq / sabqi / manzil rotation (daily new, 7–15 days recent, weekly older) | **Absent — zero occurrences in code.** No portion concept, no goal, no daily werd |
| Consolidation = survives 3 consecutive correct repetitions | **Absent** — the judgement unit is a pass, and a verdict is final per session; repetition is not a concept the engine or the record carries |
| Re-emission (gap + partial re-read + continue) read as normal recovery | **Inverted** — stall policy treats it as a defect; the lock refuses backwards while a memoriser moves backwards by nature |
| Listening back to one's own recitation | **Partial** — the session WAV is kept and sliced per mistake in the report; there is no whole-session listen-back seat |
| Peek accounting in memorisation mode | **Absent** — hide reveals on a word's own verdict; a peek is never recorded, so memorisation-mode honesty (what did you LOOK at vs SAY) is unmeasured |
| Tempo truth (haḍr fast = review pace; slow ≠ uncertain) | **Absent** — no tempo-aware handling anywhere |
| Spaced repetition (the competitor runs FSRS-5.0 with a mistake-threshold rubric) | **Absent** — and correctly so until repetition tracking exists to feed it |

The raw material for most of this already accumulates: every session carries
wordRows (verdict, time, kind split), per-ayah counts, and the durable
aggregate keeps hard words with miss counts across sessions. What is missing
is the LAYER above the data, not the data.

## 4. What the engine cannot judge — the tajweed register, ranked by sites

Handled by guards/advisories today, never a wrong-verdict surface:

| Class | Sites | Handling |
|---|---:|---|
| Waqf junctions (stop vs continue) | 9,950 marks; 3,638 wasl junctions | Blame-removal advisory; **cannot detect the stop itself** |
| Madd free-choice length | 9,716 sites | Free-length matcher (the largest structural class, correctly never accused) |
| Tafkhim/tarqiq | 26,257 sites | Never; recogniser separation not established |
| Idgham cross-word | 2,048 word-initial doubled letters | Subsumed under the waqf guard only |
| Ta marbuta hub in wasl | 1,709 mid-ayah positions | **Unmodelled** — a real gap the register carries nothing for |
| Madd 'iwad at fathatan stop | 1,823 | Never (the seat alif is silent in wasl) |
| Ghunna length | 5,304 | Never; both counts attested |
| Qalqalah readings | 3,837 sites; 68 charged (2.60× baseline) | **QALQALAH_FORM guard designed, not built** — would remove 59 of 2,115 corpus charges, zero added |
| Saktah | 7 sites (4 obligatory) | Carried, unused — mode evidence only |
| Silent alefs / taa mabsuta list | 7 + a fixed word list | In no source; would be guaranteed false WRONGs |
| Ikhfa heavy/light, rawm/ishmam, imala | ~0 app-visible | Nothing to model |

The letter-level advisory family has four built members; the full
letter→class table exists (the census), and the corpus rates are measured
(qalqalah is the only elevated class at 2.60× the 0.69% baseline).

## 5. What it handles poorly (measured, open)

- **The reader bounce** — page publishes reverse direction around a turn
  (every 19-October record); needs a repro on the current build.
- **The gated long jump** — 2 measured events where the jump skipped an ayah
  (nextCov = 0.50 both times).
- **Noise cost** — pollSec degraded 0.299 → 0.556 under noise; the work-poll
  counter exists to name it on the next noisy session.
- **The duplicates-in-window charges** — twins in the window steal charges
  (4:2:12, 76:11:1 named); the census stays unshipped until window truth is
  modelled — which the orbit pilot just demonstrated the page-level monotone
  path CAN do (22 accusations absolved with zero substitutions).
- **Mirror-vs-device window disagreements** — 3 of 10 wrong rows on the
  newest record read all-missed in the rebuilt window; the class is
  documented, the device rows are ground truth.
- **The report looks backward only.** Nothing generates a PLAN: no
  "revise these ayat next", no per-ayah memory across sessions (the
  aggregate folds counts, not per-ayah trends), no werd. The data exists.

## 6. Ranked recommendations (core work, in order)

1. **The revision plan layer** — biggest core value, no engine change, data
   already accumulated: hardWords (miss counts), perAyah, perSurah. Start
   report-only ("your revision queue: these ayat, ranked by misses ×
   recency"), then a muraja'ah session shape that walks the weak ayat under
   hide mode. This is the first feature that serves the memoriser instead of
   the tilawah reader.
2. **Repetition + consolidation tracking** — count consecutive correct
   passes per ayah across a session and across sessions; surface in the
   report ("this ayah: 3 clean passes — consolidated"). Report-only; no
   verdict change; the prerequisite for any spaced repetition later.
3. **Peek accounting in hide mode** — record peeks (word/ayah scope) as
   honest events, never as red; the memorisation-mode truth the competitor
   already keeps.
4. **QALQALAH_FORM guard** — designed, measured (59 of 2,115 corpus charges
   removed, zero added), family shape fixed; the build decision is his.
5. **Whole-session listen-back seat** — the WAV exists; a play-through with
   the verdict timeline is the memoriser's documented step of hearing
   themselves.
6. **Spaced repetition** — only after (2) exists to feed it; the competitor's
   FSRS-5.0 is the reference, not the blueprint.

## 7. The refusals — measured negatives; do not resurrect

EXTRA as a verdict (needs a labelled corpus first) · madd length as WRONG
(9,705 sites, structural) · stop/continue as WRONG (9,950 marks) · qalqalah
degree (one unit is deliberate) · tafkhim as word verdict · saktah as
verdict · ghunna length, ikhfa colour, rawm/ishmam, madd 'iwad, silent alef,
taa mabsuta, wasl-hamza · the whole-window borrow guard (621/621 firings,
25 sound CORRECTs lost) · position-aware final vowels without the labelled
device A/B (+81 false accusations on reference audio) · guessing at the 31
surah-opening ayat · rewarding hesitation. The full register of refusals
lives in TAJWEED_AND_MEMORISATION §7 and CORE_LOGIC's not-achieved table.

---

The one-sentence summary: **the judging half is real and measured; the
memorising half — the reason most users will open this app — is the missing
core, and its raw data is already on the phone.**
