# Iqra — Offline Quran Memorization

<p align="center">
<b>Free · Offline · No account · No cost</b><br/>
A Quran memorization app for Android that gives you Tarteel-style
<b>live, word-level mistake detection</b> — entirely on your device.
</p>

Iqra listens while you recite, recognizes which verse you are reading, and
highlights every word you got **right** (green), **skipped** (red, struck
through), or **mispronounced** (orange). It needs no internet, no sign-up, and
no subscription. The acoustic model runs locally via ONNX Runtime.

> Inspired by Tarteel's feature set, built cleanly on the open-source
> **[Tilawa](https://github.com/yazinsai/tilawa)** engine (MIT). See
> [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md) for the study that
> informed the design, and [NOTICE](NOTICE) for attribution. This project is not
> affiliated with Tarteel.

---

## Features

- **Mistake detection (offline).** Recite any verse; get per-word feedback.
- **Verse detection.** Recite freely and Iqra tells you the `surah:ayah` it
  heard (with a confidence score).
- **Follow-along reader.** Browse all 114 surahs and verses with the Uthmani
  text; pick a verse and practice it.
- **Fully offline.** The 85 MB model is bundled into the APK. No runtime
  downloads, no telemetry, no servers.
- **Privacy-first.** No account, no network calls for recognition, local storage
  only.

*Roadmap (not yet implemented):* hidden/peek verse mode, reciter playback with
word timings, goals/streaks, FSRS spaced repetition, JSON backup/restore,
F-Droid distribution. See [Roadmap](#roadmap).

---

## How it works

```
 microphone (16 kHz mono)
        │  float32 PCM
        ▼
┌──────────────────────────┐
│  Tilawa ONNX model        │  fastconformer CTC, mel baked in
│  (bundled, on-device)    │
└──────────────────────────┘
        │  log-probs [T, 1025]
        ▼
┌──────────────────────────┐
│  Greedy CTC decode       │  → Arabic transcript + token ids
│  + verse matcher         │  → best surah:ayah (Levenshtein score)
│  + word alignment        │  → per-word correct/skipped/wrong
└──────────────────────────┘
        ▼
   Compose UI (highlighted verse)
```

All three stages run on-device. The matcher and aligner are direct, documented
ports of `@tilawa/core`'s algorithm (`ArabicNormalizer`, `Levenshtein`,
`TextCtcDecoder`, `VerseMatcher`, `WordAligner` under
`android/app/src/main/java/com/iqra/quran/ml/`).

---

## Project structure

```
iqra/
├── android/                     # The Android app (Kotlin + Jetpack Compose)
│   ├── app/src/main/java/com/iqra/quran/
│   │   ├── data/               # QuranData loader + Verse model
│   │   ├── ml/                 # TilawaEngine, decoder, matcher, aligner, normalizer
│   │   ├── audio/              # AudioRecorder (16 kHz capture)
│   │   ├── ui/                 # Compose UI + PracticeViewModel
│   │   └── QuranApplication.kt
│   ├── app/src/main/assets/    # model.onnx + *.json  (fetched, see below)
│   └── build.gradle.kts, settings.gradle.kts, gradle.properties, ...
├── docs/
│   └── REVERSE_ENGINEERING.md  # How Tarteel was studied (transparency)
├── scripts/
│   └── fetch_assets.sh          # Downloads the open model + token tables
├── reference/
│   └── node-proof.mjs           # Node validation of the recognition pipeline
├── LICENSE                      # MIT
├── NOTICE                       # Attribution (Tilawa MIT, concept note)
└── README.md
```

---

## Build it

### Prerequisites
- **JDK 17** (the build sets `jvmTarget = 17`).
- **Android SDK** with platform **35** and a matching **build-tools** (any
  recent one). If you use Android Studio, it provides everything.
- *(Optional)* `gradle` 8.13+ — or just use the Gradle wrapper (`./gradlew`),
  which downloads the right Gradle version automatically.
- An Android device or emulator (minSdk 24 / Android 7).

### 1. Fetch the open model + token tables
These are **not** committed to the repo (they are ~100 MB). Download them once
with the provided script — they are then bundled into the APK so the app is
fully offline at runtime:

```bash
./scripts/fetch_assets.sh
```

This pulls from the Tilawa v0.2.0 release (MIT):
`fastconformer_full_mixed.onnx → model.onnx`, `vocab.json`, `quran.json`,
`quran_ctc_tokens.json` into `android/app/src/main/assets/`.

### 2. Build & install

```bash
cd android
./gradlew assembleDebug          # or: gradlew assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If you build from **Android Studio**, just open the `android/` folder; Studio
generates the Gradle wrapper (the `gradlew` jar) and resolves the SDK for you.
If you prefer the command line and `./gradlew` reports a missing wrapper jar,
run `gradle wrapper --gradle-version 8.13` once (or let Android Studio open the
project). Point the SDK at your install (e.g. on Windows set
`ANDROID_SDK_ROOT` to your `AndroidSdk` directory).

> The first build downloads Gradle, the Android Gradle Plugin, Kotlin,
> Jetpack Compose, and ONNX Runtime from Google Maven / Maven Central. After
> that, everything (including recognition) works with no network.

---

## Using the app

1. Grant the microphone permission when prompted.
2. Pick a surah → pick a verse → tap **● Recite**.
3. Recite the verse out loud, then tap **■ Stop & check**.
4. Iqra shows the verse with each word colored by correctness, plus the
   detected `surah:ayah` and a confidence percentage.

---

## Attribution & License

- **Engine & model:** [Tilawa](https://github.com/yazinsai/tilawa) (MIT),
  Copyright yazinsai — `fastconformer_full_mixed.onnx` and the CTC token tables.
- **App code:** MIT — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
- The app is **not** affiliated with Tarteel. See
  [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md) for the
  interoperability study that shaped the feature set; no Tarteel code, cloud
  model, proprietary databases, or branding are used.

---

## Roadmap

- [ ] Hidden / Peek verse mode (memorization practice)
- [ ] Reciter audio playback + offline word-timings (generated by the engine)
- [ ] Goals, streaks, FSRS spaced repetition, mistake history
- [ ] JSON backup / restore (no account)
- [ ] F-Droid + GitHub Releases distribution
- [ ] Larger "enhanced" model as an optional on-device download

Contributions welcome — this is a free, open project for everyone.
