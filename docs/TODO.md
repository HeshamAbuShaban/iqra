# Working TODO

Canonical backlog for the recognition work. Update at commit boundaries;
check items only when the shipped code proves them.

## Shipped 10 October 2026 (measured, not hoped)

- **Word-final vowel rule.** 34.5% of the table's words end on a bare
  consonant; the model says the vowel; they are different tokens. The DP now
  treats them as one class at a word's last unit only. `final_vowel_parity.py`.
- **Page-turn follow.** The lock follows an explicit page choice when the
  reciter is silent, and credits nothing.
- **Verdict latency in the record (`wt`).** The colour complaint could not be
  checked because nothing recorded when a word was decided. It does now.
- **Tap / long-press selection.** Long-press selects, extends a range, tap
  collapses, chrome ends. `selection_parity.py`.
- **Hide-mode progress strip.** How far through the surah the recitation has
  come, in the same place whether or not the words are masked.
- **In-reader search.** The magnifier searches the mushaf and jumps to the
  ayah; page jump moved into the same sheet.

## Next, in order

1. **Recite on the new build and read it back.** The final-vowel rule, the
   page-turn follow and the latency field all want one session:
   ```
   adb -s 35d5637e shell run-as com.iqra.quran cat files/sessions/<file>.json > /tmp/x.json
   python3 engine/replay/device_blame.py /tmp/x.json
   ```
   Expect: the last-unit blame class gone, page-turn wait under 6 s, and a `wt`
   distribution that says whether colour is late in the engine or in the paint.
2. **The residual accusation classes.** With the final-vowel class gone, what
   is left is the real measurement: interior elisions (`اا بِ` for `بِ`) and
   window misplacement, both visible in the harness dump of surah 3.
3. **Standing-word pointer**, which can still be empty between windows.
4. **UI polish**: type sizes, session list order, RTL on the live caption.

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
