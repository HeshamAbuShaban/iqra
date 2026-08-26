# Reverse-Engineering Report — Tarteel (`com.mmmoussa.iqra`)

> **Purpose.** This document records what was learned by analyzing the public
> Tarteel APK. It exists for *interoperability study and transparency only*.
> **No Tarteel code, cloud model, proprietary databases, or branding is used in
> the Iqra app.** The app is a clean reimplementation built on the open-source
> **Tilawa** engine (MIT).

## 1. Artifacts

| Artifact | Source | Notes |
|---|---|---|
| `apk/tarteel.apk` | pulled from a phone over USB | 194 MB, package `com.mmmoussa.iqra` |
| `re/jadx/` | jadx 1.5.6 decompile | sources + resources (R8/ProGuard obfuscated) |
| `re/apktool/` | apktool 3.0.3 decode | smali + full `res/` |

## 2. App architecture (as shipped by Tarteel)

- **Framework: React Native** (`assets/index.android.bundle`, ~19 MB minified
  JS, Hermes). Feature logic lives in the JS bundle, not the native layer.
- **No native ML `.so` libraries** in the APK → the recognition "brain" is
  **cloud-side**.
- Notable libraries in the bundle:
  - `okhttp3` / `retrofit2` — network (REST + WebSocket)
  - `androidx.media3` (ExoPlayer) — audio playback
  - `androidx.room` — local progress / goals / history
  - `fsrs` — spaced-repetition (SRS) scheduling
  - `notifee` / `workmanager` — reminders / background
  - `sentry` / `mixpanel` — analytics *(dropped in Iqra)*
  - `revenuecat` / `paddle` / `stripe` — payments *(dropped in Iqra)*
  - `growthbook` — remote feature flags *(dropped in Iqra)*

## 3. Network endpoints

**Cloud (replaced by on-device Tilawa in Iqra):**
- `https://api.tarteel.ai/v1/...`, `https://api.tarteel.io/v1/...`
- `wss://voice-v2.tarteel.io` — live recitation audio streamed to the server
  for recognition. This is the paid "Mistake Detection" engine.
- `https://cloudfront.net/v1/profile/mistakes` — returned mistake results.

**Audio CDN (we mirror the layout with open reciters):**
- `https://audio-cdn.tarteel.ai/quran/{reciter}/{surah}{ayah}.mp3`
- Reciters seen: `alafasy`, `husary`, `minshawy`.

**Dropped:** analytics / payments / remote-flags endpoints.

## 4. Local data model (schemas only — open equivalents used instead)

- `quran-data.sqlite` (15 MB): `words` (83,668 rows — every word of the
  Quran) with multiple script variants (Uthmani / QPC / IndoPak / etc.) plus
  page-layout tables.
- `reciter-audio-timing.sqlite` (21 MB): `ayah_timing` (93,540 rows) with
  per-word timestamps powering word-highlight follow-along.
- `search-data.sqlite` (25 MB): FTS trigram + unicode search indexes.

**Open substitutes used by Iqra:**
- Word text/scripts: Quran.com / tanzil.net (Uthmani), QPC/IndoPak open sets.
- Audio: open reciters (everyayah.com-style `{reciter}/{surah}{ayah}.mp3`).
- Word timings: generated **offline** by running the on-device engine over
  reciter audio once — zero licensing risk.

## 5. Feature catalog (Tarteel free vs premium → Iqra policy)

| Feature | Tarteel | Iqra |
|---|---|---|
| Follow-along word highlight | Free | **Free** |
| Audio listening + reciters | Free | **Free** |
| Voice search ("Shazam for Quran") | Free | **Free** |
| Mistake detection (word-level, live) | **Paid** | **Free (offline)** |
| Hidden / Peek verses | **Paid** | **Free** |
| Goals / Streaks / History / Analytics | **Paid\*** | **Free** |
| Spaced repetition (FSRS) | present | **Free** |

\* some analytics free; premium gates the rest. Iqra makes all of it free.

## 6. The "brain" decision

Tarteel's model is server-side and absent from the APK, so Iqra's brain is the
**open on-device engine Tilawa** (formerly *offline-tarteel*, MIT):

- Model: `fastconformer` CTC; 16 kHz mono PCM → `surah:ayah` + word progress.
- Runs via ONNX Runtime (web / node / RN / Android).
- Word-level correctness is computed in-app by aligning the recognized token
  sequence to the *known* target verse's token sequence → each word marked
  `correct` / `skipped` / `wrong`. This reproduces Tarteel's paid mistake
  detection, fully offline.

Optional "enhanced" mode (larger model / self-hosted) is possible, but offline
always works and is the default.

## 7. Guardrails

- The APK was analyzed only to learn behavior, UX, and data shapes.
- No Tarteel code, cloud model, proprietary SQLite data, or "Tarteel"
  name/branding is used. "Iqra" is a generic Quranic term (no trademark).
- Engine = MIT Tilawa; text = open Quran datasets; audio = open reciters.
- Result: a takedown-resistant, zero-cost, offline-first app.
