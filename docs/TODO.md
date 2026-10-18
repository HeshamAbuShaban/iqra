# Working TODO

Canonical backlog for the recognition work. Update at commit boundaries;
check items only when the shipped code proves them.

## Where this sits — 17 October

**Fixed and installed today (08:57 build):**

- **`unjudgeableKeys` reported a phantom ayah.** The record writer joined keys
  inside always-quoted brackets, so an empty set printed `[""]` — a literal
  empty-string key every reader counted as one unjudgeable ayah named "". All
  three 17-October records carried it. The writer now uses a first-flag, like
  the arrivals object.
- **The un-anchored head no longer accuses.** Measured on the user's circling
  experiment: surah 107 was recited first while the lock sat on `106:1`,
  whose window then held 107's audio and blamed `106:1:1` — the only wrong of
  a 15/16-correct session. An ayah with no previous ayah's arrival has no
  anchor; any WRONG from such a window demotes to UNKNOWN with an
  `UNANCHORED_AUDIO` note (the page-602 walk on the guard build itself
  charged 106:1:1 at wu=1000, so no mismatch position is required), behind the `unanchoredNeverAccuses`
  toggle (default on, same ground as waqf and plain madd: the engine cannot
  prove the audio is the reciter's). Blame removed, never added.
  `advisory_parity.py` + `pref_consumption.py`.
- **Spans confirmed on device.** All three records carry the span-end capture
  (14, 4 and 5 entries), with the same retention filter as arrivals.
- **The surah-qualified reader washes** (installed 09:57): wash layers compared
  ayah numbers without surah, so ayah 2 lit across page-mates (your own
  observation). Scope carries the surah now; `paint_scope_parity`.
- **The sibling escape** (installed today): on a multi-surah page the scope
  itself was unmovable — a sibling recited first left the lock standing, then
  drifting on stale windows (the walk's last 20 s: lock walked 106:1→4 while
  the fresh slice held only 107:1). Ported the measured rule
  (`page_scope_escape.py`): sibling opening ≥0.85 of the last 120 symbols,
  leading `here` by ≥0.35, 3 frames → the lock's surah moves. Zero false
  escapes on the ten other records; CORE_LOGIC.md updated.

**Still open, in order — the full list, so nothing is lost:**

1. **Verify today's fixes on the phone.** The next session that starts mid-
   page is the un-anchored guard's first test; re-recite the first ayah and
   the record must show UNKNOWN + note, not red. The sibling escape's first
   test is the same walk: start with a middle surah of a multi-surah page and
   the lock must follow into it (frame tag 3 in the record).
2. **Two-surah page walk** (finish surah 4 onto page 106). The user walked it
   in bits on 17 October — surah 4 pieces, then Quraysh, then the circling
   experiment — and the piecewise sessions behaved, but a continuous walk
   with the page-turn at the boundary is the real test. "Would take some
   time", user says.
3. **The `standingWord`-stays-lit visual** at a completed surah — record the
   behaviour in STATUS. The pointer now follows the reciter past the ayah
   boundary (18 October), so this visit covers both.
4. **Repeat-to-refresh scenario** — backtrack exercised 17 October
   (`reversals: 2`, no false accusation); repeat is not.
5. **The duplicates-in-window charge class.** `4:2:12` was blamed (wu=1000)
   while all 7 of its units match inside the final span-trimmed window — the
   charge was made in an earlier poll, when `أَمْوَالَهُمْ` and `أَمْوَالَكُمْ`
   (both in the 4:2–4:3 stream) were both visible and the window picked the
   wrong twin. Named 17 October with spans; a second case found the same day:
   `76:11:1` blamed وَ while 5/6 units matched — its sibling pair in 76:11
   (`فَوَقَاهُمُ` / `وَلَقَاهُمْ`) shares only a 4-unit suffix (قَ اا هُ م).
   Measured the same evening (`dup_twin.py`): exact and hamming-1 twins are
   ABSENT on reference audio (surah 76: 5 wrong, 0 twins) — a live-session
   window-composition race, not a corpus pattern. The census stays unshipped
   until it can model window truth; device records are the only real evidence.
6. **DONE — whole-corpus measurement, on the table the phone runs.** The
   first sweep (17 October, **699/12**) measured the stale phrase-cut table
   `ordered_quran_phonemes.json` — an old-engine number. Ported the same
   day: `optional_final.py` now loads `word_aligned_phonemes.json` (the
   shipped table) and refuses to run without it. Shipped-table census
   (`python3 engine/replay/optional_final.py $(seq 1 114)`, 114 Al-Dosari
   dumps, 6,135 of 6,236 ayat above the 0.9 gate): the reference reciter is
   accused on **566 words**; the guard removes **28 (5%)** — every marked
   word charged at its final unit. Of 69 final-unit charges, 41 carry no
   licensed mark (the register requires those letters sounded — the guard
   stays out correctly); their letters scatter across 18 types (7×ب, 6×ه,
   no و/ي cluster) — a thin, broad recogniser miss on reference audio.
   The other 497 sit mid-word and stay verdicts. Full list
   `/tmp/opencode/of_shipped_full.log`; the old engine's list at
   `/tmp/opencode/optional_final_full.txt` is superseded.
7. **The `-هُمْ` / `-كُمْ` / `-تُمْ` endings** — ANSWERED 18 October
   (night), the 41 re-read to its letters: 17 distinct endings, ب7 ه6 ا4 م3
   ل3 ى3 ط2 ن2 ء2 ج2 + seven singles (ك د ض ر ف ظ ق) (not 18; the earlier
   count slipped). Three of the 41 end in meem by bare form, and only ONE is a
   table defect. 2:136:10 إِبْرَٰهِـۧمَ and 4:94:24 مَغَانِمُ are plain
   recogniser substitutions at the final unit (مَ heard هِ; مُ heard بُ -
   both measured against the corpus dumps at coverage ≥0.99) and no table
   rule reaches them. 57:12:9 وَبِأَيْمَـٰنِهِم IS one: its final unit is
   `۾۾۾`, the phonemizer's idgham/iqlab material for the join into the NEXT
   word's ب, absorbed into the word itself; the model emitted bare `م`
   there, and `۾۾۾` is matchable only by the exact token (`final_vowel_id`
   has no ۾ rule), so the word is charged. Sized: 981 words end on a pure
   ۾-run over 91 surahs; the model emits `۾۾۾` 1,077 times corpus-wide, so
   the table and the model mostly agree - the charge needs the model to
   NOT merge. Candidate fix: a one-sided final-position equivalence
   (`۾۾۾` vs the word's own meem/nun family, remove-blame direction only).
   NOT applied: per item 8's own rule this needs a deliberate corpus
   rescore first. `optional_final.py` now prints `no licensed mark`
   truthfully (the old tag said "mark but not final" on every un-marked
   row; the booleans were always right, only the tag lied).
8. **Rebuild the word table with case vowels on finals** (26,733 bare-end
   words; the builder's STOP condition needs a deliberate corpus rescore,
   not a weakening).
9. **The 2.65s word-latency floor** — re-read 18 October: the walk's
   `pollSec` counter is 0.310 — the earlier 0.56 claim was decided-frame
   `dt`, never poll cadence (`pollMsEma` measures delay+work, not a
   slowdown). Word batches: s16 full-ayah batches align to ayah pace at
   pollSec 0.301 with 0.6–1.5s stragglers; `s-1792196309286-1` gaps reach
   3.8s at pollSec 0.333. Per-word decode-time reconstruction fails on
   records: no per-emission timestamps and `emissionBase` resets. An
   instrument change (emission times in the record) is the requirement.
   **DONE 18 October (night):** the record now carries `emMs` — one arrival
   time per retained emission, poll-granular, on `wt`'s clock — and
   `device_blame.py` prints the verdict lag (engine time) and the feed
   cadence (poll time), refusing rather than guessing when the times are
   absent. Gated and fault-injected in `device_blame_parity.py`. **DONE 18
   October (evening):** read off the #238 record of the last device session
   - verdict-lag median **-3.8 s** (decisions during the recitation, before
   the ayah's own audio finishes), feed cadence **565 ms** (the model's
   phoneme rate is the floor, not the 250 ms poll; the tool now prints the
   sign's meaning). The cadence experiment: closed by measurement. See
   STATUS, item 5 above.
10. **Engine comparison** per `engine/shootout/README.md`; swap only on loss,
    plugged into the current setup (user's decision).
11. **DONE — Replay screen, desktop first.** `engine/replay/replay_export.py`
    <records...> --glyphdb <ayahinfo_1024.db> --pages-dir <pngs> writes a
    self-contained `out/replay/<record>/` (html+js+data, pages embedded);
    `data.js` carries frames, emissions, arrivals, spans, word verdicts and
    per-WRONG causes where the record can reproduce them (NOT RECONSTRUCTABLE
    otherwise). No headless browser here — open the html.
12. **Noise hardening** — `pollSec` 0.299 → 0.556 under noise. The
    `workPollSec` counter (installed 18 October, `notePollWork`) now splits
    feeding-poll work from the 250 ms cadence delay; the next noise session
    on the new build reads it. The 15 October noise records are gone from
    the device (10-file cap) — 0.556 lives in STATUS only.
13. **UI polish** (deferred but requested): session-list hierarchy, list
    typography, RTL on the Live caption, search deep-link, reference-app
    pop-menu.
14. **Memorisation workflow** — a "hide until recall" mode driven by
    advisories.
15. **Prune asymmetry** (documented, deliberately not changed): arrivals are
    dropped for other surahs on a page handoff but spans are not; the record
    writer's retention filter is what keeps both honest.
16. **DONE — the observer screen on the phone** (18 October, 820a917 +
    4dc644e). "Watch it again" from a session report: timeline scrub, the
    lock's walk from the frame ring, verdicts at their `wt`, lock moves at
    their frame times, the heard stream from `emMs`, real-time playback.
    Advisories carry no time in the record, so they are counted and named
    untimed rather than placed; pre-emMs records get the refusal. Verified
    on device against #238 and two older writers. The desktop replay stays
    the place with per-WRONG cause panels; the phone seat is the smallest
    honest slice the user asked for.

## The user's idea, documented — 17 October

The user proposed, in their words: **(A)** a list capturing the words they
recited, compared to the actual words of the ayah; **(B)** a letter-detection
level that understands tajweed complexity. Surveyed against the code, both
have a partial basis already.

**A — the recited transcript.** Nothing ordered exists today: verdicts are a
map from expected word to status (`statusMap`/`sessionArchive`, Practice-
ViewModel.kt), and the UI shows the expected side coloured
(`ExpectedWordsLine`, MainActivity.kt:481–529; `LineText`/`LineBox` :2464+).
What the user said is only implied by which expected words lit up. The
base for building it exists in the session record: `emissions` (the phoneme
stream) plus spans now reconstruct the exact window per ayah, so the recited
phonemes can be walked against the expected table and printed as "you said X,
the ayah reads Y" — per word, in the session report. This is a display layer
over data that already ships; no new recognition work.

**B — letter-level tajweed.** The engine already aligns at phoneme-unit
level and maps units to mushaf words (`word_aligned_phonemes.json`), and
holds per-word coverage and per-unit mismatch position (`wrongStartFrac`).
What does not exist: named tajweed classes (qalqalah, ikhfa, ghunnah, madd
already has a partial equivalence in `canonicalId`) attributed to specific
letters. The advisory channel is the delivery mechanism that already exists
(`AdvisoryKind`, severity, ONCE_PER_WORD/PER_EVENT), and the waqf/optional-
final/madd guards are the first three members of exactly this family.
`docs/TAJWEED_AND_MEMORISATION.md` tracks the gap list; B is the systematic
version of it. The honest boundary: the unit alphabet must first grow a
checked mapping letter → tajweed class, measured from the reference corpus,
not guessed.

**B measured — 17 October evening:** the class census exists
(`engine/replay/tajweed_sites.py`); first rates in
`TAJWEED_AND_MEMORISATION.md` §8.1. Qalqalah marked sites charge at 2.60× the
corpus baseline (1.80% vs 0.69%) — the ONLY elevated tajweed class; nasal,
madd and geminate are all BELOW baseline (0.40×/0.90×/0.55×). An earlier
letter-binned pass read qalqalah at 4.15×; the honest unit-level split scales
it back, which is exactly why the census bins by what the class occupies.
Next: attribute the 68 charged qalqalah sites to their words, then design the
advisory family (demote, never accuse) like the waqf junctions.

(That is "if we aren't already on some bases with this": A is ~80% data-complete,
0% UI; B is ~30% — the alignment and the channel exist, the class table does
not. Both documented here so they are not lost.)

## Where this sits right now — measured, 15 October 2026

Four sessions recited **with background noise**, through the app, on the build
that contains every fix below (APK built 04:57, sessions 18:03–18:13).

| session | length | lock | moves | evaluated | decided | noWindow | pollSec |
|---|---|---|---|---|---|---|---|
| 1 → 2:20 | 300 s | 2:20 | 25 | 269 | 193 | **0** | 0.556 |
| 1 → 1:7 | 55 s | 1:7 | 6 | 29 | 29 | **0** | 0.299 |
| 109 complete | 102 s | 6 | 4 | 26 | 20 | **0** | 0.310 |
| 112 complete | 52 s | 4 | 3 | 15 | 14 | **0** | 0.288 |

Three surahs finished to their last ayah (1:7, 109:6, 112:4) and **two of them
produced zero WRONG verdicts.** `noWindowWords` is 0 in every session, including
the longest, on a phone in a noisy room. That is the record-integrity and
window work holding under real conditions.

**And the thing that did not hold.** 14 WRONG verdicts, and 10 of them (71%) are
still blamed on the word's LAST unit. Every last unit is a bare `م`, `ن`, `هُ` or
`رَ` — letters inside `FINAL_VOWELLESS`, where `final_vowel_parity.py` proves the
rule fires. The APK contains the fix. **So the emission is not the bare/vowelled
pair the rule covers, and nothing currently on record can say what it is.**

That single unanswered question is now the top item, because every other blame
finding is a guess without it.

## Next, in order

0. **Read the residual back on the widened retention.** The two long sessions
   ran on the 110-ayah / 4,000-symbol retention; the build now keeps 300 ayat /
   12,000 symbols. Re-run `device_blame.py` on the next session and confirm the
   last-unit class resolves into named causes instead of refusals.
   ```
   adb -s 35d5637e shell run-as com.iqra.quran cat files/sessions/<file>.json > /tmp/x.json
   python3 engine/replay/device_blame.py /tmp/x.json
   ```
1. **Rebuild the word table with case vowels on finals.** 26,733 of 77,481
   words end bare; the mushaf text carries the vowel; `build_word_table.py`
   already walks word by word. The runtime `final_vowel_id` rule then becomes a
   backstop. The builder's STOP condition (flat units byte-identical) must be
   re-baselined deliberately with a corpus rescore, not weakened.
2. **Replay screen, desktop first.** Data layer complete (reconstructed windows,
   named charges); the time-scrubbed mushaf rendering remains.
3. **Engine comparison** on the noisy human corpus; swap only on loss, per the
   shootout's own decision rule.
4. **Noise hardening.** `pollSec` 0.299 → 0.556 under noise. Measure whether the
   VAD is being honest or the RMS/VAD path costs polls, and whether the
   word-latency floor rises with it.
5. **The 2.65s word-latency floor — CLOSED by measurement, 18 October
   (evening).** The paint half was the ahead rule (see STATUS). The
   verdict-time half: on the #238 record, decisions spread through the
   recitation and the median word is decided 3.8 s BEFORE its ayah's final
   window audio has arrived (negative lag = deciding during recitation);
   the gap between emission stamps is 565 ms, so the floor is the model's
   phoneme cadence, not the 250 ms poll. Poll halving buys ≤ ~130 ms - a
   measured negative, not worth the CPU. See STATUS.
6. **UI polish — status 18 October:** session list carries its denominator
   (done); RTL on the Live caption was already complete (every Latin string
   bidi-isolated); search deep-link already opens the anchored ayah; the
   reader's long-press action sheet already exists. Remaining: none known -
   reopen only with a specific observation.

## Shipped — measured, not hoped

- **Paint scope.** The map is seeded from the session's verdicts, so a word
  keeps its colour when the lock moves on. `paint_scope_parity.py`.
- **Verdicts are final for the session** (18 October). The retention chain is
  final at every position — the locked ayah's provisional revision was what
  produced the paint-and-unpaint — and the reader clears its copy only on the
  engine's session epoch, never at teardown. `archive_parity` (retention
  model), `session_epoch_parity`, both fault-injected. The Hide toggle
  persists across visits.
- **The next ayah colours while it is recited** (18 October). `ayahObs` no
  longer refuses the ayah the lock has not reached yet, so a word earns its
  colour as it is said instead of the whole ayah arriving in one batch at the
  lock move; red still waits for the lock. `evidence_window_parity`.
- **The standing pointer follows the reciter** (18 October) into the next
  ayah once it holds a verdict, and does not move on with stale verdicts
  after a repeat. `standing_word.py`, fault-injected.
- **Word-final vowel rule**, including sukun: `م` and `مْ` now match, because
  pausing at an ayah end is the commonest way to finish a word and the first
  version of the rule charged it as a mistake. `final_vowel_parity.py`.
- **Verdict latency in the record (`wt`).**
- **Tap / long-press selection.** `selection_parity.py`.
- **Hide-mode progress strip.**
- **In-reader search** — the magnifier searches the mushaf and jumps to the ayah.
- **Page-turn follow** — the lock follows an explicit page choice when the
  reciter is silent, and credits nothing.
- **Standing-word pointer** — falls back to the locked ayah's last word;
  confirmed null in the record before the fix. `standing_word.py`.
- **LiveTest scope inheritance** — a finished scope can no longer end the next
  test before it begins. `live_scope_inherit_parity.py`.
- **Arrival/emission retention widened** — 12,000 symbols / 300 ayat, covering a
  full Al-Baqarah, after the 57-minute session showed `noWindowWords=3889`
  from the log rolling past needed audio. `arrival_retention_parity.py`.
- **From the other worker, verified on top:** whisper rescued by the VAD
  (`mic_gate.py`), pause that actually pauses, the denominator that could
  exceed its own count, the tone crest measured from audio being judged rather
  than standing in on words-per-minute, keep-awake, the recitation theme.

## Next, in order (superseded)

1. **Ask which of the 16 WRONG words were genuinely wrong.** They are listed
   by class in `STATUS.md`; 7 of 16 are ambiguous between a real error and a
   table defect. This is the highest-value item on the page because it converts
   the last unknown in the verdict path into data.
2. **DONE — whole-corpus measurement, now on the shipped table.**
   `engine/replay/optional_final.py` points at `word_aligned_phonemes.json`
   (refuses the stale phrase-cut table). Latest: **566** WRONG words on the
   reference reciter over 6,135 ayat judged, **28 (5%)** removed by the
   optional-final guard; 41 of 69 final-unit charges have no licensed mark
   and stay verdicts. Remaining question: whether the 41 are reachable by
   any rule at all (letters scatter across 18 types — item 6/7 above).
3. **The `-هُمْ` / `-كُمْ` / `-تُمْ` endings.** Four of sixteen WRONG words sit on
   these (`أَصَـٰبِعَهُمْ`, `وَأَنتُمْ`, `ءَأَمِنتُمْ`, `يَنصُرُكُمْ`). Whether this is
   a mutamakkin-meem table gap or a real limit is unknown until item 1 answers.
4. **Device validation of the record-integrity fix.** Recite, then jump around
   several pages, then stop. The record must still report the moves and judged
   words of the recitation. `session_record_integrity.py` guards the code path;
   only the phone can show the record.
4b. **Confirm the per-surah fold fix on the device.** The `foldedSurahs` undo is
   new; a long session followed by a page jump is the only proof. The old code
   inflated per-surah totals once per re-fold.
5. **Two legal realisations, beyond the junction guard**: acoustic alternates
   for wasl/waqf at every mark. Cannot be built without measured mappings;
   the waqf downgrade is the safe stopgap. Do not generate alternates by guess.
6. **Standing-word pointer**: restore a never-null current word and surface it
   in the UI; it must not disappear between evidence windows.
7. **Page-turn semantics**: verify all three scenarios on-device and record the
   behaviour in STATUS.
8. **UI polish pass** (deferred but requested): session-list hierarchy, list
   item typography, RTL on the Live caption, search deep-link straight to the
   ayah text, and a reference-app pop-menu on long-press.
9. **Memorisation workflow**: a "hide until recall" mode driven by advisories
   (the alarm channel doubles as the cue-to-recall).

## Parking lot

- Decoder starvation emission more precisely than the pipeline-reset site.
- `tawaqquf`/saktah words as two-realisation candidates.
- Silent-letter and taa-mabsuta cumul-congruences (1,709 + 7 sites) when a
  measured rule lands.
- Session list should show advisory tallies next to the score.
