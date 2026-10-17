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

**Still open, in order — the full list, so nothing is lost:**

1. **Verify today's fixes on the phone.** The next session that starts mid-
   page is the un-anchored guard's first test; re-recite the first ayah and
   the record must show UNKNOWN + note, not red.
2. **Two-surah page walk** (finish surah 4 onto page 106). The user walked it
   in bits on 17 October — surah 4 pieces, then Quraysh, then the circling
   experiment — and the piecewise sessions behaved, but a continuous walk
   with the page-turn at the boundary is the real test. "Would take some
   time", user says.
3. **The `standingWord`-stays-lit visual** at a completed surah — record the
   behaviour in STATUS.
4. **Repeat-to-refresh scenario** — backtrack exercised 17 October
   (`reversals: 2`, no false accusation); repeat is not.
5. **The duplicates-in-window charge class.** `4:2:12` was blamed (wu=1000)
   while all 7 of its units match inside the final span-trimmed window — the
   charge was made in an earlier poll, when `أَمْوَالَهُمْ` and `أَمْوَالَكُمْ`
   (both in the 4:2–4:3 stream) were both visible and the window picked the
   wrong twin. Named 17 October with spans; fix design not started.
6. **Whole-corpus measurement of the optional-final class.**
   `engine/replay/optional_final.py` sweeps all 114 reference-reciter dumps.
   Run it before trusting `UNMODELLED_FINAL` as more than a partial fix.
7. **The `-هُمْ` / `-كُمْ` / `-تُمْ` endings** — mutamakkin-meem table gap or
   real limit, unknown until the sweep answers.
8. **Rebuild the word table with case vowels on finals** (26,733 bare-end
   words; the builder's STOP condition needs a deliberate corpus rescore,
   not a weakening).
9. **The 2.65s word-latency floor** — re-measured 17 October: verdict batches
   1.4–2.7s apart at pollSec 0.333. Untouched by every guard so far.
10. **Engine comparison** per `engine/shootout/README.md`; swap only on loss,
    plugged into the current setup (user's decision).
11. **Replay screen, desktop first** — data layer complete; the time-scrubbed
    mushaf rendering remains. Note the record holds only the LATEST span end
    per ayah, so per-poll replay of past charges stays impossible even with
    spans; future charges are replayable.
12. **Noise hardening** — `pollSec` 0.299 → 0.556 under noise; measure the
    VAD/RMS cost.
13. **UI polish** (deferred but requested): session-list hierarchy, list
    typography, RTL on the Live caption, search deep-link, reference-app
    pop-menu.
14. **Memorisation workflow** — a "hide until recall" mode driven by
    advisories.
15. **Prune asymmetry** (documented, deliberately not changed): arrivals are
    dropped for other surahs on a page handoff but spans are not; the record
    writer's retention filter is what keeps both honest.

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
5. **The 2.65s word-latency floor.** Untouched; the trim did not move it and
   `wt` confirms.
6. **UI polish**: type sizes, session list order, RTL on the live caption.

## Shipped — measured, not hoped

- **Paint scope.** The map is seeded from the session's verdicts, so a word
  keeps its colour when the lock moves on. `paint_scope_parity.py`.
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
2. **Whole-corpus measurement of the optional-final class.**
   `engine/replay/optional_final.py` sweeps all 114 reference-reciter dumps and
   reports every WRONG word with its final mark and whether the substitution is
   on the final unit. Run it before trusting `UNMODELLED_FINAL` as more than a
   partial fix — on surah 67 alone the guard catches none of the one WRONG word.
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
