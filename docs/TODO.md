# Working TODO

Canonical backlog for the recognition work. Update at commit boundaries;
check items only when the shipped code proves them.

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

1. **Write the phoneme stream into the session record.** One bounded array
   alongside `frames`, holding the symbols the model actually emitted, in
   order, with the poll they arrived on. `wu` says WHERE in a word the blame
   landed and `wt` says WHEN; neither says WHAT was heard, and that is the
   absence stopping this: a WRONG word's expected units are known, so one look
   at the emitted span settles whether the escalation is a table defect, an
   emission the rule does not cover, or a genuine mistake.
   Gate: extend `device_blame_parity` — a record must carry the stream, and
   the read-back must reconstruct the same span the verifier scores.
2. **Read the four noise sessions once item 1 ships.** Re-recite, then run the
   replay tool. Expected outcome: the residual 71% resolves into a named class.
   Until then, do NOT touch the final-vowel rule again — it is written, it is
   gated, and further guesses without the stream are exactly the failure mode
   this project keeps finding.
3. **Noise hardening.** `pollSec` rose from 0.299 to 0.556 under noise, so the
   cadence that decides how fast audio reaches the model is a third slower in
   the condition the app is actually used in. Measure whether that is the VAD
   being honest or the RMS/VAD path costing polls, and whether the word-latency
   floor rises with it.
4. **Engine comparison**, per the decision rule in `engine/shootout/README.md`,
   now that real noisy clips exist: same clips, same scorer, mistake-flag
   precision and false-red rate. The harness does not care which model produced
   the emissions, so a candidate plugs in as a different emission stream — no
   rework of windows, lock policy or the verifier. Swap only if the current one
   loses on those clips.
5. **The 2.65 s word-latency floor.** Judged from the 615 s session, where every
   word of an ayah flipped to its verdict in one batch ~2.7 s after the lock
   arrived. Item 1 shows whether that is the emission lag, the three-ayah
   window's leading tail, or the streaming decoder — and which of them is
   worth changing.
6. **Standing-word pointer**, which can still be empty between windows.
7. **UI polish**: type sizes, session list order, RTL on the live caption.

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
