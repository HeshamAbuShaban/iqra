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

---

## 8. The client half of Tarteel's recognition protocol (static, from the shipped APK)

This section records what the Tarteel Android client does on the wire: the
endpoints it names, the JSON frames it sends and receives, the mistake taxonomy
it renders, the hiding and peeking rules, the session-audio contract, the
scheduler it embeds, and the flags that switch parts of it on.

**Evidence basis.** Decompiled Hermes bundle from the public APK
(`re/hbc/listing.hasm`); behaviour study only. No Tarteel code, model, data or
branding is used in Iqra — see the guardrails section below (§7). Claims marked
*inference* are mine, not the bundle's; everything else is quoted from the
listing, and every claim carries the listing line it came from.

### 8.1 Endpoints and transport

| Purpose | Value | Line |
|---|---|---|
| REST API, prod | `https://api.tarteel.io` | [listing:658246] |
| REST API, dev | `api-dev.tarteel.io` (paired with the above in one array) | [listing:253061] |
| AAD schedule | `https://api.tarteel.io/v1/aad/schedule/` | [listing:658258] |
| Voice server, prod (HTTPS) | `https://voice-v2.tarteel.io` | [listing:658382] |
| Voice server, dev (HTTPS) | `https://voice-v2-dev.tarteel.io` | [listing:658384] |
| Voice server, prod (WebSocket) | `wss://voice-v2.tarteel.io` | [listing:2021142] |
| Voice server, dev (WebSocket) | `wss://voice-v2-dev.tarteel.io` | [listing:2021140] |
| Mistakes REST route | `/v1/profile/mistakes/` (`MISTAKES_ROUTE`) | [listing:658358] |
| Session-audio URL route | `/v1/profile/user_session_audio/` (`USER_SESSION_AUDIO_ROUTE`) | [listing:658372] |
| Recordings delete route | `/v1/profile/recordings/` (`DELETE_RECORDINGS_ROUTE`) | [listing:658336] |
| SRS onboarding | `https://srs-onboarding.worker.tarteel.ai/v1` | [listing:658388] |

Prod versus dev is a **store flag, not a build flag**: `isUsingProdVoiceServer` is a Redux slice
[listing:1260044] read inside the socket client [listing:2021139] to pick the URL, and it has a
`toggleVoiceServerProduction` action [listing:1260037]. The WebSocket itself is not in JavaScript.
`MicStream` is a native hybrid object; the JS only picks the URL, builds a config object, calls
`MicStream.initialize(url, config, AUDIO_CONFIG)` and then speaks JSON text frames to it. *Inference:*
the raw audio framing, PCM packing and any binary chunking live in native code and are not observable
here.

### 8.2 The message envelope

Every outbound frame is `JSON.stringify` of one object [listing:2020430] whose shape is always
`{ event, data }` — `event` and `data` are the two own properties of the envelope [listing:2020517],
[listing:2020523]. The inbound side parses with `JSON.parse` and switches on `msg.event`
[listing:2021668].

**Init config.** The socket is opened with a single JSON object carrying identity, the task and the
capture format [listing:2021176]: `appVersion` [listing:2021251], `audioConfig` [listing:2021252],
`authToken` [listing:2021258], `deviceId` [listing:2021262], `devicePlatform` [listing:2021264],
`isMemorization` [listing:2021266], `isDiacritized` [listing:2021268], `isDualModel` [listing:2021276],
`isInitialTargetRangeStrict` [listing:2021278], `isNewSttServer` [listing:2021284],
`isStartPositionStrict` [listing:2021286], `startPosition` [listing:2021287], `endPosition` [listing:2021288],
`mistakeReportingTimeLag` [listing:2021298], `recitationMode` [listing:2021300], `sessionId` [listing:2021302],
`sessionGroupId` [listing:2021304], `shouldCollectAudio` [listing:2021310], `shouldLabelAudio`
[listing:2021315], `userId` [listing:2021326], `isEphemeral` [listing:2021328], `modelName`
[listing:2021239] and `languageCode` [listing:2021244]. The auth token rides inside this config, not in
an HTTP header.

**Capture format.** `AUDIO_CONFIG` is a fixed object — `{'bufferSize': 512, 'sampleRate': 16000,
'bitsPerChannel': 16, 'channelsPerFrame': 1}` [listing:1259796] — i.e. 16 kHz, mono, 16-bit PCM in
512-sample (32 ms) buffers, passed to the native module alongside the URL and the config
[listing:2021344], [listing:2021349].

### 8.3 Events

The two event sets are two objects: the inbound set [listing:1709091] and the outbound set
[listing:1709093].

**Outbound** (client → server):

| Event | `data` fields | Line |
|---|---|---|
| `NEW_ROUND` | `sessionId`, `startPosition`, `endPosition`, `isMemorization` | [listing:2020519] |
| `NEW_ROUND` (session record side) | `sessionId`, `sessionGroupId`, `recitationMode`, `hasAudio` | [listing:2020533] |
| `ADD_MISTAKE` | `id`, `mistakeType`, `expectedTranscript`, `receivedTranscript`, `positions`, `ayah`, `stateIndexStart`, `stateIndexEnd`, `startTimeMs`, `endTimeMs`, `createdAt` | [listing:2321645] |
| `REMOVE_MISTAKE` | the mistake record itself, with `removedAt` and `removedBy` set | [listing:1709062] |
| `UPDATE_CONFIG` | `isMemorization` | [listing:2578182] |

`hasAudio` is derived from `selectShouldCollectAudio` [listing:2020541]. The `ADD_MISTAKE` frame is
sent alongside a local `batchUpdateStates` call [listing:2321694] carrying `isLost`, `numFinalStates`,
`newStates`, `stateIndexOffset` and `mistakeUpdates` [listing:2321697].

**Inbound** (server → client):

| Event | `data` shape | Line |
|---|---|---|
| `SEARCH_LOADING` | no fields; drives the `onLoading` callback | [listing:2021691] |
| `SEARCH_RESULT` | `{ queryText, matches: [{ surahNumber, ayahNumber }] }` | [listing:2019823] |
| `PARTIAL_TRANSCRIPT` | `{ queryText }` | [listing:2019815] |
| `STATES_UPDATE` | an array of per-word state objects, each with `isLost` and `type`/`position` | [listing:2019922] |
| `NEW_ROUND` | the server's round data, passed straight to `onNewRound` | [listing:2021719] |
| `ERROR` | `{ message, isStreaming }` | [listing:2021737] |

The `SEARCH_RESULT` shape needs one correction to the obvious reading: the **wire** field names are
`surahNum` and `ayahNum`, and the client renames them to `surahNumber` and `ayahNumber` in a mapping
function [listing:2019873], [listing:2019876]. An empty match set produces "No search result matches"
[listing:2019859]; the local `searches` slice keeps the same `queryText` / `matches` pair
[listing:521545] and derives a `matchCount` [listing:521609]. Errors use one of two codes,
`API_ERROR_SPECIFIC` when the frame carries a message [listing:2021746] and `API_ERROR_GENERAL`
otherwise [listing:2021855].

### 8.4 The mistake taxonomy

Five kinds, defined as one object [listing:929322]: `INCORRECT_WORDS`,
`MISSED_WORDS`, `EXTRA_WORDS`, `INCORRECT_TASHKEEL`, `PEEKED_WORDS`. A
parallel set, `COVERAGE_MISTAKE_TYPES` [listing:929319], covers every type
except `EXTRA_WORDS`. Three client-side classifiers consume them:
`isWordMistakeType` [listing:1693080], `isTashkeelMistakeType`
[listing:1693116] and `isPeekMistakeType` [listing:1693136]; a fourth,
`pickMistakeType` [listing:1693156], picks the still-uncorrected type.

**Local mistake table.** The persisted columns are visible in the SQL the client runs
[listing:2225421]: `id`, `sessionId`, `mistakeType`, `positions`, `ayah`, `correctedUpToWordNumber`,
`stateIndexStart`, `stateIndexEnd`, `startTimeMs`, `endTimeMs`, `createdAt`, `updatedAt`, `removedAt`,
`createdBy`, `removedBy`.

**Rendering rules.** A word is coloured by looking up its mistake and calling `colorMistakeWord` for a
word mistake [listing:1728588] or `colorMistakeTashkeel` for a tashkeel mistake [listing:1728585].
**A peeked word is drawn in no colour at all** — the peek branch returns without a colour
[listing:1728560], so a peek is invisible in the text even though it is recorded. A word the user has
not reached yet is also left uncoloured when `visiblePos >= wordIndex` [listing:1728533] and the
session is a memorisation session [listing:1728536].

**Budget.** The number of mistakes allowed is a pair, `{minMistakes, maxMistakes}` [listing:2248435],
rendered by one function [listing:2248430]: `maxMistakes === null` → `≤` plus the minimum
[listing:2248483]; `maxMistakes === 0` → the `srsMistakesClean` label [listing:2248491];
`minMistakes === maxMistakes` → the single number; otherwise the two joined by an en dash
[listing:2248455]. The numbers themselves are computed server-side and arrive with the SRS preview
(`baseWindowMistakes`, `attemptMode`) [listing:2234228].

### 8.5 Hide verses and peeking

The setting is a Redux slice named `isMemorizationMode` with `initialState: false` [listing:1612058].
Verses are hidden automatically whenever the app is in the `TEST` experience: the effective rule is
`isMemorizationMode` **OR** current experience is `TEST` [listing:1612075], and `TEST` is one of four
`QURAN_INTERACTION_MODES` (`CONTEXT`, `LISTEN`, `RECITE`, `TEST`) [listing:926587]. The UI label is
"Hide Verses" [listing:544943].

Peeking has two distinct scopes — one word and one ayah — delivered as two separate callbacks `onPeek`
and `onPeekAyah` [listing:2595753]. The hidden text is drawn as **redaction bars** rather than removed:
the renderer works from `minHiddenX` and `maxHiddenX` against the word's rectangle [listing:2645565].
A peek is never free: it writes a `PEEKED_WORDS` mistake [listing:2321650] with `expectedTranscript`
set to the peeked words [listing:2321654] and `receivedTranscript` set to the empty string
[listing:2321656].

### 8.6 Session audio

The client never holds the audio file. Whether the session is recorded at all is a **server-side
decision**: the flags `shouldCollectAudio` and `shouldLabelAudio` arrive with the user's settings
[listing:522109] and are passed into the socket config [listing:2021310]. Playback works the other way
round — the client asks the server for a URL and caches it per session (`audioUrl`, `startedAt`,
`completedAt`, `error`) [listing:1612918], against the `/v1/profile/user_session_audio/` route
[listing:658372]. A delete route exists for the stored recordings [listing:658336], which confirms the
file lives on the server. Mistake clips use the same mechanism: they are the session audio sliced to
the mistake's `startTimeMs` and `endTimeMs` [listing:2321674], [listing:2321683], part of the same
mistake record that `ADD_MISTAKE` puts on the wire [listing:2321645].

### 8.7 Spaced repetition

The bundle embeds **fsrs.js 4.7.0 running FSRS-5.0** [listing:1758056]. The grades are `Again`, `Hard`,
`Good`, `Easy` [listing:1758025]. Target retention is `0.9` [listing:1758043] and the maximum interval
is `36500` days [listing:1758045]. The default weight vector has 19 entries [listing:1758047].

The **rubric** is a separate, server-supplied array: the settings slice exports
`mistakeThresholdToRubric` [listing:966888], `selectSrsGradeRubric` [listing:966891],
`selectSrsMaximumIntervalDays` [listing:966893], `selectSrsTargetRetention` [listing:966896] and
`selectSrsWeights` [listing:966897], with local defaults `targetRetention: 0.9` and `maximumInterval: 90`
[listing:966952]. The threshold-to-rubric function [listing:967047] turns a user-chosen mistake
threshold into a triple of per-grade weights — `{ easy: rubric[t], good: rubric[t+1], hard: rubric[t+2] }`
— falling back to the last three entries. The threshold itself is a user setting, default `0.85`,
stored beside `isEnabled` and `highlightHiddenWords` [listing:1237009]. In production the arithmetic
is delegated to the native `SrsManager` [listing:711373] — `calculateSrs` and `getPageReviewPreview` are
native calls [listing:2234175]; the embedded FSRS-5.0 code serves the local `SRS_JS` experimental path
and the rubric policy. A repeat recitation only counts after a cooldown: the English string is "You can
continue revising it, but it won't count until after 5 minutes." [listing:544943].

### 8.8 Feature flags

Two independent systems. **Remote (GrowthBook)**: constructed with a tracking callback that forwards
experiments to Mixpanel with `$source: 'growthbook'` [listing:659300]; feature payloads are cached under
the key `features` [listing:658598]; the flag names the client actually reads include `beta-models`
[listing:1638124]. **Local (`experimentalFeatures`)**: a slice of 40 named toggles [listing:815846],
among them `DUAL_MODEL`, `SRS`, `SRS_JS`, `SPOT_TESTING`, `CORRECTED_MISTAKES`,
`MISTAKE_REPORTING_TIME_LAG`, `MISTAKE_HISTORY`, `NEW_STT_SERVER` and `AUDIO_DEBUG`. Several feed the
socket config directly: `isNewSttServer` [listing:2021284], `isDualModel` [listing:2021276],
`mistakeReportingTimeLag` [listing:2021298].

**"Preview Models" ⇄ "Detect mistakes".** The two settings are kept consistent with each other, and the
app says why. Turning on "Preview Models" dispatches `setCorrectionIsEnabled(true)` [listing:2366602]
and shows *"Detect mistakes" has also been enabled since "Preview Models" are enabled.*
[listing:544943]. Turning off "Detect mistakes" while Preview Models are on disables them and shows the
inverse [listing:2655133]. The model list itself comes from the `beta-models` GrowthBook flag
[listing:1638124].

**Tajweed.** The client ships tajweed **colouring**, not tajweed mistake detection. `tajweedColors` is a
settings slice with `initialState: { isEnabled: false }` [listing:1613275], and the colouring is gated on
`isTajweedColorsEnabled` [listing:1688933]. The colours are theme keys — `colorTajweedGhunna`,
`colorTajweedTafkhim`, `colorTajweedSilent`, `colorTajweedQalqalah`, `colorTajweedMadd2`,
`colorTajweedMadd4`, `colorTajweedMadd5`, `colorTajweedMadd6` [listing:1767214], with light and dark
palettes [listing:1540697]. The rule detection is done on-device with a set of regular expressions over
the Arabic text, in `applyTajweedForText` [listing:1690165], using named capture groups such as
`tafkhim6` [listing:1690403], `gray3` [listing:1690441], `tanween1` [listing:1690568], `madd5`
[listing:1690705], `madd1` [listing:1690744] and `madd3` [listing:1690815]. The app states in plain
words that it does **not** detect tajweed mistakes: the English string is "Tarteel does not detect Tajweed
mistakes but this feature is in development inshaAllah." [listing:544943], rendered in the mistake-detection
upsell [listing:1635326]. The comparison table still lists a "Tajweed Mistakes" row [listing:2315574],
which is a premium-marketing table and not evidence of detection.

### 8.9 What the server still hides

None of the following is recoverable from this bundle, because the logic lives
in the native `MicStream` / `SrsManager` code or on the server:

1. **The acoustic model.** The bundle carries only the model's *name* (`modelName`) and a `languageCode`
   [listing:2021239], [listing:2021244]; decoding and acoustic scoring are server-side.
2. **How a word is judged wrong.** The client only receives finished word states and a `mistakeType`
   label [listing:2019922]; the alignment algorithm, the edit-distance metric, the diacritic tolerance
   and the confidence thresholds are not in the bundle — the classifier helpers [listing:1693080]
   consume the server's verdict.
3. **The numeric mistake thresholds.** `minMistakes` / `maxMistakes` arrive with the SRS preview
   [listing:2234228]; the client only formats them [listing:2248430].
4. **The production scheduling arithmetic.** FSRS-5.0 is embedded [listing:1758056], but the live path
   is the native `calculateSrs` and `getPageReviewPreview` [listing:2234175].
5. **The rubric contents and the live weights.** `selectSrsGradeRubric` [listing:966891] is populated by
   a server payload [listing:966903]; only a hard-coded default array ships in the bundle
   [listing:966960].
6. **The wire format of the audio frames.** Binary framing, chunk size, jitter buffer, VAD and any PCM
   packing live in the native `MicStream`; the JS knows only the capture format [listing:1259796].
7. **The mistake-reporting lag in production.** `mistakeReportingTimeLag` is an experimental-feature
   value [listing:2021298] whose production value is server-supplied.
8. **Whether the audio was collected, and where it is stored.** `shouldCollectAudio` /
   `shouldLabelAudio` are server flags [listing:522109]; signed-URL minting, retention and entitlement
   rules are server-side.
9. **The GrowthBook payload.** Only the keys the client reads are in the bundle [listing:1638124]; targeting rules and remote values come from the GrowthBook CDN at runtime.
10. **The full user state.** Whether mistake detection or Preview Models is on for a given user, and whether the user is premium, are server-side.

### 8.10 Behaviours worth mirroring in Iqra

1. Keep one small, symmetric event set over one channel [listing:1709091] — Iqra has no socket; the engine is in-process, so this is not in Iqra yet.
2. Put identity, task and capture format in one config object rather than in scattered headers [listing:2021251] — not in Iqra yet; Iqra has a session record but no handshake.
3. Publish the capture format explicitly and fix it [listing:1259796] — Iqra already captures 16 kHz mono PCM on device.
4. Model the verdict as a small stable enum [listing:929322] — Iqra has `CORRECT`/`WRONG`/`SKIPPED`/`UNKNOWN` plus `ADVISORY`; it has no `MISSED`/`EXTRA`/`TASHKEEL`/`PEEKED` kinds.
5. Never highlight a peeked word, but always log the peek [listing:2321650] — Iqra's hide mode reveals on a word's own verdict and records no peek as a mistake.
6. Suppress colouring for words the user has not reached yet [listing:1728533] — Iqra already refuses to paint ahead of the lock; the rule is enforced by the window, not by a position field.
7. Make peeking two scopes, one word and one ayah, and draw hidden text as redaction bars [listing:2595753] — Iqra hides by clipping word rects out of the page; there is no one-word / one-ayah peek gesture.
8. Hide verses automatically in test mode rather than behind a separate toggle [listing:1612075] — Iqra's Hide toggle is explicit and persists across visits; there is no automatic test mode.
9. Render the allowed-mistake budget honestly as a range [listing:2248430] — not in Iqra yet; Iqra has no mistake budget.
10. Turn the mistake tolerance into per-grade weights [listing:967047] — not in Iqra yet; Iqra has no spaced repetition.
11. Apply a re-read cooldown before a repeat recitation counts [listing:544943] — not in Iqra yet.
12. Couple dependent settings and say why in the message [listing:2655133] — Iqra's Settings toggles are consumed by the engine, but there is no cross-toggle rule yet.

### 8.11 Limits

- The evidence is **Hermes bytecode v96, disassembled** with `hermes-dec` 0.1.8, not original source.
  Identifiers are recovered, not read.
- The native `MicStream` and `SrsManager` modules are **not visible** in the bundle, so the audio
  framing and the production scheduler are dark.
- **No network traffic was captured.** Every statement about the wire comes from the code that builds
  and reads the frames, not from a packet capture.
- Behaviour was inferred from **client code only**. Nothing here measures the server, and no claim
  about server-side accuracy, thresholds or model quality follows from it.
