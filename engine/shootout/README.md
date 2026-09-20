# Backbone shootout — numbers decide, no sunk-cost swaps

Compares acoustic backbones on the SAME clips with the SAME scorer, measuring
what the app actually needs (mistake flagging), not generic transcription WER.

## Contenders

| id | backbone | notes |
|---|---|---|
| `tilawa` | `yazinsai/tilawa` fastconformer_full_mixed (current) | baseline; must be beaten convincingly |
| `zipformer` | `Quran-Lab/zipformer_p-arabic-v3` int8 (gated, user-supplied) | streaming phoneme, tajweed alphabet |
| `fastconformer-quran` | `Muno459/fastconformer-quran` fp16 (gated, user-supplied) | word-level + mispronunciation head |

Drop-in slots: put gated weight files (as-received, SHA-verified) under
`engine/shootout/weights/<id>/` and register the entrypoint in
`clips.manifest.json`. Nothing gated is ever committed.

## Clips (`clips.manifest.json`)

Each clip: `{ "id", "wav": "path to 16k mono", "ayahs": ["S:A", ...],
"mistakes": [{ "ayah": "S:A", "word": N, "type": "wrong|skip|harakat" }] }`.
Cover: slow + fast reciters, clean + mistakes + skips, kids if available.
Reference clips in `../audio/` are admissible as clean cases.

## Metrics (`score.mjs`)

From each backbone's `results-<id>.json`
(`{ clipId, lockedAyah, wordFlags: [{ayah, word, flagged}], msPerFrame }`):

- verse-lock accuracy (% clips where the lock lands on a labeled ayah)
- mistake-flag precision / recall (flagged vs labeled mistakes)
- harakat-catch rate (subset of mistakes typed `harakat`)
- false-red rate (CORRECT words flagged WRONG)
- p50/p95 ms per frame + model MB

## Decision rule (locked)

Primary = best mistake-flag precision at equal-or-better latency;
runner-up becomes the on-demand second opinion (dual-witness consensus).
If neither beats `tilawa` convincingly on real clips, we keep `tilawa` +
sherpa plumbing and report honestly. No swap on leaderboard numbers alone.
