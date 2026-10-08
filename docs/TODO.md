# Working TODO

Canonical backlog for the recognition work. Update at commit boundaries;
check items only when the shipped code proves them.

## Next, in order

1. **Device acoustic validation of this build** — ask the user to recite:
   - ayah-final WRONG verdicts should fall now that madd length is
     canonical-tolerant (16 of the last 18 pre-fix WRONGs sat on madd);
   - the first ayah should no longer come back all-UNKNOWN;
   - the session record should show `noWindowWords: 0` still;
   - advisories should appear for `MISSED_RULING_POSSIBLE` and
     `NO_AUDIO_WINDOW` where applicable, and must never recolour a word red.
2. **Two legal realisations, beyond the junction guard**: acoustic alternates
   for wasl/waqf at every mark. Cannot be built without measured mappings;
   the waqf downgrade in `PhonemeMapper`/`PracticeViewModel` is the safe
   stopgap and is in place. Do not generate alternates by guess.
3. **Standing-word pointer**: restore a never-null current word and surface it
   in the UI; it must not disappear between evidence windows.
4. **Page-turn semantics**: verify all three scenarios on-device (mid-surah
   free navigation, surah-end gated handoff, intent handoff on swipe into a
   new surah) and record the behaviour in STATUS.
5. **UI polish pass** (deferred but requested): session-list hierarchy, list
   item typography, RTL on the Live caption, search deep-link straight to the
   ayah text, and a reference-app pop-menu on long-press.
6. **Memorisation workflow**: a "hide until recall" mode driven by advisories
   (the alarm channel doubles as the cue-to-recall).

## Parking lot

- Decoder starvation emission more precisely than the pipeline-reset site.
- `tawaqquf`/saktah words as two-realisation candidates.
- Silent-letter and taa-mabsuta cumul-congruences (1,709 + 7 sites) when a
  measured rule lands.
- Session list should show advisory tallies next to the score.
