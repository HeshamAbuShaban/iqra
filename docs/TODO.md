# Working TODO

Canonical backlog for the recognition work. Update at commit boundaries;
check items only when the shipped code proves them.

## Next, in order

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
