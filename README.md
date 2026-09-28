# Iqra — Offline Quran Memorization

<p align="center">
<b>Free · Offline · No account · No cost</b><br/>
Recite, and the app follows you word by word — highlighting what you got
right, what you missed, and what you mispronounced. Entirely on-device.
</p>

Iqra listens while you recite, recognises which verse you are reading, and
marks every word **correct**, **skipped** or **wrong**. It also hides whole
verses for memorisation, on the authentic standard Madinah mushaf pages.

No account, no subscription, no network at runtime.

> This project is not affiliated with Tarteel. It was informed by studying
> the behaviour of open-source apps — see [docs/](docs/) and [NOTICE](NOTICE).

---

## How recognition works

```
 microphone (16 kHz mono)
        │  float32 PCM
        ▼
┌──────────────────────────────┐
│  sherpa-onnx streaming       │  zipformer2 CTC, phoneme output
│  zipformer_p-arabic-v3       │  (int8, 72 MB, user-supplied)
└──────────────────────────────┘
        │  phoneme units
        ▼
┌──────────────────────────────┐
│  expected side, EXPLODED     │  the mushaf's phoneme table is per-WORD;
│  into the model's own units  │  words are split into the 250-unit model
│  (greedy longest match)      │  inventory, keeping a unit→word map
└──────────────────────────────┘
        │
        ▼
  coverage = matched units / expected units, per candidate ayah
        │
        ▼
  lock advances on coverage of lock+1, in order, scoped to the page
        │
        ▼
  per-word CORRECT / WRONG / SKIPPED → Compose highlights
```

The step that matters is the second one. Comparing the model's **phoneme
units** against the table's **word strings** cannot ever match — coverage is
identically `0.00` and nothing is ever detected. See
[docs/RECOGNITION_ROOT_CAUSE.md](docs/RECOGNITION_ROOT_CAUSE.md).

---

## Data on the device

Heavy data is not in the APK. It lives in a shared folder so the app stays
small and updates are cheap.

```
/sdcard/Iqra/
  manifest.json               name → sha256 → bytes
  pages/001.png … 604.png     standard Madinah mushaf, 1024 × 1656
  ayahinfo_1024.db            88,246 per-word glyph boxes
  model.int8.onnx             acoustic model   (gated upstream)
  tokens.txt                  model unit inventory
  ordered_quran_phonemes.json canonical phoneme table
  silero_vad.onnx             voice activity detector
```

On first run, a **Data setup** screen lists every file with its size and
integrity, and offers to download whatever is missing straight from the
original sources. The acoustic model is gated by its authors and cannot be
fetched automatically, so that one file is the user's to copy in — via USB or
a file manager. It only has to be done once.

Copying the folder with `adb push` will **not** work: Android creates those
directories root-owned with no traversal permission, and the app is denied
access. Use the file manager or a USB cable.

---

## Building

Requires JDK 17+ and the Android SDK (platform 36, build-tools 35).

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build is signed with a committed debug keystore so successive
builds install over one another instead of demanding an uninstall. Debug
signing only; release signing is unaffected.

`scripts/fetch_assets.sh` populates `android/app/src/main/assets` with the
text tables. Page images and the glyph database are **not** bundled — they
are staged to `.iqra-stage/` for copying to the phone.

---

## Project layout

```
android/app/src/main/java/com/iqra/quran/
  data/     QuranData, AyahSearch, AssetPaths, DataFetcher,
            GlyphCoords, Mushaf, Verse
  ml/       SherpaZipformer, SherpaVad, PhonemeMapper, Levenshtein
  audio/    AudioRecorder (16 kHz capture)
  ui/       MainActivity (reader + home), PracticeViewModel, DataSetupScreen
engine/
  replay/   offline measurement harness (see below)
  shootout/ acoustic backbone comparison + gated weights
docs/       RECOGNITION_ROOT_CAUSE.md, REVERSE_ENGINEERING.md
```

---

## Measuring changes

Recognition is only trustworthy if it is measured. `engine/replay/` replays
real audio through the real `sherpa-onnx` recognizer and scores the matching
policy, with no device and no download:

```bash
cd engine
uv venv .venv-replay --python 3.12
uv pip install --python .venv-replay/bin/python sherpa-onnx numpy

.venv-replay/bin/python replay/dump_tokens.py audio/001.raw \
    --frame-ms 250 --label fatiha --out replay/out/fatiha.json
.venv-replay/bin/python replay/lock_policy.py replay/out/fatiha.json 1 7 0.60 2
```

Current result across six surahs / 269 s of recitation: **28/28 ayat locked
in order**. `word_verdicts.py` separately checks per-word CORRECT / WRONG /
SKIPPED on labelled mutations (64 cases).

This harness is what found the alphabet mismatch, and it is why the
recognition code is not tuned by feel.

---

## Still unmeasured

Honest list, so nothing here reads as more finished than it is:

- deliberate mispronunciation under live microphone conditions
- hesitation and reciting from memory
- ambiguous repeated words (e.g. الرحمٰن الرحيم in 1:3 and 1:4)
- background noise and far-field audio

Auto feedback is a study aid. A teacher's ear is the authority.

---

## Licence

App code MIT — see [LICENSE](LICENSE) and [NOTICE](NOTICE). Page images and
glyph data originate from the quran_android Madinah data set; see
[reference/README.md](reference/README.md).
