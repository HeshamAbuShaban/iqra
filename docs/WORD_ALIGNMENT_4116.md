# The 4,116 unjudgeable ayat — what is actually wrong, and the fix

Established by measurement, 2026-10-03. Companion to
[DEFECTS_AND_METHOD.md](DEFECTS_AND_METHOD.md); the `word_alignment_parity` check
is the standing measurement.

## The symptom

`PracticeViewModel` refuses to judge an ayah whose word counts disagree:

```kotlin
if (pw.wordCount != ws.size) continue      // the whole ayah is skipped
```

Skipped means no status reaches the renderer, so the ayah paints as UNSTARTED:
no highlight, no colouring, nothing. **4,116 of 6,236 ayat — 66%.**

## The cause is not a text mismatch

Three candidate explanations were tested and all three are dead ends. Recorded so
nobody spends a day on them again.

| hypothesis | result |
|---|---|
| The table is segmented like some other text file we already have | **No.** `reference/tarteel-ml/data/data-uthmani.json` matches the *mushaf*, not the table (2:4 → 12/12, 2:8 → 11/11). |
| It matches imlaei, and `reference/quran_android/app/src/madani/assets/word_alignment.db` maps imlaei→uthmani many-to-many | **No.** Table vs imlaei agrees on only **2,030** of 6,236. The db is the right *shape* and the wrong *source*. |
| The merges happen only at waqf marks | **No.** A waqf-only rule predicts the wrong count on **2,967** of 4,116. 2:8 merges `وَمَا هُم بِمُؤْمِنِينَ` with no waqf mark anywhere. |

**The phonemiser invented a segmentation that matches nothing external.**

For 2:4 — mushaf 12 words, table 11:

```
mushaf  8: مِن        9: قَبْلِكَ
table   8: مِںںںقَبڇلِكَ
```

## Why the data is lossy by construction

The table is a **connected-recitation** phonemisation: idgham is applied across
word boundaries, so `مِن قَبْلِكَ` genuinely becomes one phonological run. The
word count is lost because of the physics of recitation, not because of a bug or
a mismatched asset.

That is why every attempt to *recover* the split is fighting the data, and why
`word_alignment.db` and a waqf rule both fail: neither can invert a merge that was
never recorded.

## The fix: do not invert it — do not join in the first place

`quranic-phonemizer` (MIT, `pip install quranic-phonemizer`, QUD-Technologies)
emits **per-word** phonemes *and* tags every phoneme with its word:

```python
r = Phonemizer().analyse("2:4")
[r.text() for r in [r]]          # 12 words - the mushaf's own segmentation
[w.text for w in r.words]        # Word(ref='2:4:1', ...)
[s.token  for s in r.sounds]     # Sound(order=, token=, word_id=)
```

For 2:4 it returns **12 word groups where the table returns 11.** Its
`variant_catalogue` even models the joins as first-class events — `noon_wasl`,
`irkab_maana`, `yalhath_dhalik` all carry `anchor='boundary'` — so the thing that
causes the merge is a labelled boundary rather than a silent one.

Phonemising **one mushaf word at a time** makes the segmentation equal the
mushaf's by construction. There is no count to reconcile and no boundary to
guess, which is what removes the risk the other session was right to be careful
about.

## The one thing still to build

`quranic-phonemizer` emits a Latin/IPA inventory (`w a ll ð i:`) and the model
emits a 251-unit Arabic-script inventory (`phoneme_units.json`, ungated at
`huggingface.co/Saboorhsn/quran-stt-onnx`). The bridge between them is the
remaining work, and it is a closed vocabulary rather than open Arabic text.

It is derivable rather than guessed, because 2,120 ayat already agree on their
boundaries: align a mushaf word's Latin phonemes against the Arabic units that
`explode()` produces for the same word, inside a single word where the alignment
is tiny and unambiguous. **Those 2,120 ayat are the acceptance test** — if the
derived mapping reproduces their existing boundaries, the remaining 3,996 follow.
If it does not, that is known in an hour instead of a fortnight.

## Two traps recorded on the way

- **The verse numeral lives inside the last mushaf word** — `'بِمُؤْمِنِينَ ٨'`,
  `'يُوقِنُونَ ٤'`. It can never match a phoneme and must be stripped before any
  comparison. Systematic, one per ayah, and invisible unless you look.
- **The canonical table is NOT Tilawa's.** It belongs to
  `Quran-Lab/zipformer_p-arabic-v3` (NPL-1.2), which ships `tokens.txt`,
  `phoneme_units.json`, `ordered_quran_phonemes.json` and
  `quran_text2phoneme.json`. `quran_text2phoneme.json` was checked: on every
  ayah whose text matched exactly (133), its word grouping is **identical** to the
  table, so it carries no alternative segmentation. The ungated mirror
  `huggingface.co/Saboorhsn/quran-stt-onnx` has the same six files and nothing
  word-segmented; `Muno459/quran-phonemes`, `obadx/muaalem-annotated-v3` and
  `Quran-Lab/quran-tajweed-phonetics` are gated and may hold it.
