# Working TODO

Canonical backlog for the recognition work. Update at commit boundaries;
check items only when the shipped code proves them.

## Where this sits — 16 October

**Fixed and installed today:**

- **The window-trim regression.** A trim meant to fix ayah-boundary blame cost a
  third of every decided verdict (332 to 27, 95.9% correct to 84.2%) because its
  end was measured from an already-trimmed window and ratcheted. Now bounded by
  `MIN_WINDOW_SYMBOLS` and a raw origin. `window_anchor_parity.py`.
- **The standing word was null.** Confirmed in the record: session
  `1792114078210-102` ended `103:3` with 9/9 words decided, the exact state that
  nulled it. Falls back to the ayah's last word. `standing_word.py`.
- **The page-turn "corruption" was one line.** `any { it > activeSurah }` counted
  any later surah on the page as a change, and 51 pages hold two surahs, so
  finishing surah 4 onto page 106 jumped to 5:1. Now "no word of the current
  surah on the page". `page_turn_boundary.py`.
- The **LiveTest ending before it began**, by inheriting the previous session's
  scope. `live_scope_inherit_parity.py`.

**Where the recognition actually stands**, from the user's own noisy phone
audio: 96-98% of decided words correct, `noWindowWords=0` in every session.

**Still open, in order:**

1. **Verify these four fixes on the phone.** DONE for three, 17 October
   03:00: LiveTest-after-completed-session (own scope, 35 fresh verdicts), the
   deliberate 2:2→2:1 backtrack (`reversals: 2`, no false accusation), and the
   first-word guard (0/11, 0/11, 0/17 first-word failures; see STATUS). Still
   unverified on device: **two-surah page walk** (finish surah 4 onto page
   106), the **`standingWord`-stays-lit** visual at a completed surah, and
   **one new session confirming `spans` lands in the record** (the span-end
   capture shipped 17 October 06:35; the next recording is its first test).
2. **The remaining boundary blame.** The first-unit blame share fell from
   13.4% pooled pre-guard to 0% in tonight's three runs and 2/31 in the 22:10
   surah-2 session — where `device_blame.py` now names each: a real خ/ق
   confusion, and the 2:21 boundary glide that charges one unit of five. The
   `wu`/`wt` table is the thing to read; do not tune blind.
3. **Repeat and backtrack scenarios.** Backtrack exercised 17 October
   (reversals: 2, no false accusation). Repeat-to-refresh still unexercised.
4. **The 2.65s word-latency floor.** Re-measured 17 October: verdict batches
   1.4–2.7s apart at pollSec 0.333, unchanged by the first-word guard.
5. **Engine comparison** per `engine/shootout/README.md`. The harness does not
   care which model produced the emissions, so a candidate plugs in as a
   different emission stream.
6. **UI polish**: type sizes, session list order, RTL on the live caption,
   search deep-link, reference-app pop-menu.

**Notes on the wiring.** Subagents available for research, verification and
fault injection. Everything that changes the verdict path is main-context and
gated. Every new check must be proven able to fail before its result is used.

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
