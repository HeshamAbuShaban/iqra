<h1 align="center">Iqra — recite, and be followed</h1>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/licence-MIT-2BB6A0?style=flat-square" alt="MIT licence"></a>
  <img src="https://img.shields.io/badge/platform-Android_8.0%2B-0F1A1A?style=flat-square" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/mushaf-Madinah_604_pages-D9B36B?style=flat-square" alt="604-page Madinah mushaf">
  <a href="https://github.com/HeshamAbuShaban/iqra/actions/workflows/build.yml"><img src="https://github.com/HeshamAbuShaban/iqra/actions/workflows/build.yml/badge.svg" alt="CI build"></a>
  <img src="https://img.shields.io/badge/gate-44_checks-2BB6A0?style=flat-square" alt="44-check recognition gate">
</p>

<p align="center">
  <img src="docs/img/icon.png" width="120" alt="The Iqra icon: a mushaf page whose lines resolve into a recitation wave, gold on near-black">
</p>

<p align="center">
  A study aid for those memorising the Qur'an. It listens while you recite and
  shows you, verse by verse and word by word, what it heard. It runs entirely
  on your phone: free, offline, no account, and nothing you recite ever
  leaves the device.
</p>

<p align="center"><a href="#بالعربية">العربية</a> · <a href="#how-it-hears-you">How it works</a> · <a href="#building">Build</a> · <a href="#for-contributors--hijra-ila-al-code">Contribute</a></p>

---

## Why this exists

Learning to recite from memory is one of the most demanding things a person can
do. You are holding a text in your head, and every word has to come out in the
right order, at the right rhythm, without the page in front of you. For most of
that journey the only feedback available is a teacher — which is exactly what it
should be, and also a scarce thing, unevenly available, and expensive.

So what is missing is not a teacher. It is a **second pair of eyes for the hours
you practise alone**.

Iqra is built for that hour. You start reciting, it follows, and afterwards you
can see where you drifted: the word you skipped, the word that came out wrong,
the verse you lost your place in and had to find again. It does not tell you that
your recitation is correct. It shows you what it heard so you can compare that
against what you meant.

Two things make it possible to offer that honestly:

- **It runs entirely on the device.** No account, no subscription, no network at
  runtime, nothing uploaded anywhere. While you recite, the app keeps a session
  log — and, so a mistake can be replayed and argued with, the session's own
  audio — in the app's private storage on this phone. It is deleted with the
  session, never leaves the device, and the REC marker in the recite bar shows
  when audio is being kept. A tool that listens to your recitation is not a
  tool that should phone home.
- **It is free, and it stays free.** Nothing here is metered, gated, or waiting
  on a subscription.

> This project is not affiliated with Tarteel. Its behaviour and data shapes were
> studied to build something independent and private — see
> [docs/REVERSE_ENGINEERING.md](docs/REVERSE_ENGINEERING.md) and
> [NOTICE](NOTICE).

---

## What it does

**Follows you through the mushaf.** All 604 pages of the standard Madinah
mushaf, verse by verse, as you read or recite from memory. The lock moves with
you, and the reader stays where the recitation is.

**Judges word by word, with an honesty rule.** A word is marked correct or
wrong only when the evidence is there; a word the engine could not hear is
left **unheard** — a muted state that never counts against you, because a
model that failed to listen is a fact about the model, not a mistake by the
reciter. Where a wrong word's charge begins inside the word is recorded too,
as a fraction of its own sounds: first letter, middle, or last.

**Keeps the evidence, so a verdict can be argued with.** Every session is
recorded — each word's verdict and the moment it was decided, the sounds the
model actually heard, and the session's own audio, written to the phone beside
the record. The session report shows the walk-through: where blame landed
across your wrong words, and, on any of them, a button that plays the two and
a half seconds before the verdict — the very audio the engine judged. A
mistake map plots the whole session's wrong words on the recording's
timeline. All of it stays on the device, and it is deleted with the session.

**Hides verses for memorisation.** In hide mode nothing is painted over the
words you have not reached: not your position, not the recite bar, not a
highlight. Only your own verdicts open a mask. Test yourself honestly, and
still see the coverage strip that says how far you have come.

**Shows you where you are.** A lost place is a glance rather than a
re-reading, and the move-to sheet (surah, ayah, or page — whichever you
touch drives the other two) gets you anywhere in the mushaf with a preview
before you go.

**A window into the engine, live.** The recite screen carries a lens — one
toggle — that makes the machinery visible while it runs: which acoustic model
is loaded, whether the gate is open or closed, how much of the next, current
and previous verse has been heard against their thresholds, and every verdict
the moment it lands. When the lock sits still, the lens shows you why.

**Speaks your language.** The entire interface is localised: Arabic and
English today, built so a new language is a set of strings, not a code
change. The recite screen renders its numbers and captions properly for
right-to-left reading.

<p align="center">
  <img src="docs/img/report_ar.png" width="270" alt="The session report in Arabic: the accuracy ring, the note that the engine did not hear 70 words, and the session audio with its mistake-map ticks">
  &nbsp;&nbsp;
  <img src="docs/img/log_ar.png" width="270" alt="The recitation log in Arabic: a wrong word with where the charge began, what was heard beside what was expected, and the play button for the audio before the verdict">
</p>
<p align="center"><sub>Real sessions, shown as the app showed them: the report that did not hear 70 words says so, and the wrong word's charge names where it began.</sub></p>

## What it will not do

- It is **not a judge of your recitation.** Tajweed, meaning and correctness in
  the sense a teacher means it are not what this measures. It compares what it
  heard against the written text.
- It **cannot hear a word the acoustic model has never learned.** A word that is
  near-silent in training will read as unrecognised, and the honest output is
  "I did not hear this" rather than a confident error.
- It **is not a substitute for a teacher.** It is a practice partner for the
  hours between lessons.

---

## How it hears you

<p align="center">
  <img src="docs/img/pipeline.svg" width="720" alt="The pipeline: microphone, VAD gate, streaming acoustic model, heard phoneme symbols compared against per-word expected units, DP alignment, per-word verdicts, and the on-device session record">
</p>

The second box is the one that matters, and the one that took the longest to get
right. Comparing the model's **phoneme units** against the table's **word
strings** can never match — the coverage of every candidate is identically
`0.00`, and roughly ten rounds of threshold tuning had already been spent on that
constant before it was found. See
[docs/RECOGNITION_ROOT_CAUSE.md](docs/RECOGNITION_ROOT_CAUSE.md).

Three rules keep the verdicts honest, and each one earned its place:

- A word only partly covered, with nothing contradicted, is **unheard** — not
  wrong. An earlier rule called it wrong, which made a skipped word paint its
  neighbour red, because a global alignment absorbs a deletion as
  substitutions in the words around it.
- A word-final consonant cannot decide a verdict. A reciter who says the case
  vowel and a reciter who pauses on the last letter are saying the same word.
- Stopping at a marked pause, a legal length choice on a madd, and a few
  other legal readings are never accusations. Every one of these rules only
  ever *removes* blame — that is the shape of a safe rule here.

## What this project learned

The engineering is documented as it happened, because the interesting part is not
the code — it is what was nearly shipped, and what turned out to be a bug in the
measuring instrument rather than in the app.

**[docs/LESSONS.md](docs/LESSONS.md)** — the fifteen things that would each have
saved weeks. **[docs/DEFECTS_AND_METHOD.md](docs/DEFECTS_AND_METHOD.md)** — every
defect, the four instruments built to find them, and the traps that cost the most
time.

---

## Data on the device

Heavy data is not in the APK. It lives in a shared folder so the app stays small
and updates are cheap.

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

On first run a **Data setup** screen lists every file with its size and
integrity, and offers to download whatever is missing straight from the original
sources. The acoustic model is gated by its authors and cannot be fetched
automatically, so that one file is the user's to copy in — via USB or a file
manager. It only has to be done once.

Copying the folder with `adb push` will **not** work: Android creates those
directories root-owned with no traversal permission, so the app is denied access.
Use the file manager or a USB cable.

---

## Building

Requires JDK 17+ and the Android SDK (platform 36, build-tools 35).

```bash
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build uses a committed debug keystore so successive builds install over
one another instead of demanding an uninstall. Debug signing only; release
signing is unaffected.

Every push to `main` builds a debug APK and publishes it under
[Releases](https://github.com/HeshamAbuShaban/iqra/releases) — versionCode is
the commit count and the tag carries the same number. The three newest
releases are kept; older ones are pruned automatically.

`scripts/fetch_assets.sh` populates `android/app/src/main/assets` with the text
tables. Page images and the glyph database are **not** bundled — they are staged
to `.iqra-stage/` for copying to the phone.

---

## Measuring changes

Recognition is only trustworthy if it is measured, so the policy is a faithful
Python port that replays real audio through the real recogniser, with no device
and no download. The matching kernel is pure Kotlin with no Android imports, which
is what lets CI **execute** it rather than only compile it.

```bash
cd engine
uv venv .venv-replay --python 3.12
uv pip install --python .venv-replay/bin/python sherpa-onnx numpy

# the gate — 44 checks; the model-free subset runs in CI in seconds,
# the full local run (with the corpus dumps) takes about two minutes
python3 engine/replay/run_checks.py

# score a whole-Quran corpus from existing token dumps, no re-recognition
engine/.venv-replay/bin/python engine/replay/run_corpus.py --source dosari --rescore
```

Current result across the Al-Dosari corpus — 114 surahs, 6,236 ayat, 26 hours of
audio: **113/114 complete**, up from 101 before the harness was corrected. The
one holdout is surah 94, which stops at 7 of 8 ayat on audio that runs 3.3 s per
verse against a corpus median of 10.9 — a verse the recording does not appear to
contain. That is reported rather than tuned away, because no threshold creates
evidence that is not in the audio.

Every check must be shown able to fail before its result is believed — a check
that has never gone red is decoration. New checks are fault-injected as a
matter of course: the thing they guard is broken on purpose, the check must go
red, then the break is reverted.

### Trying it with your own voice

The corpus behind the numbers is recorded audio; the app has also been carried
through real recitations on a phone — hesitation, repeats, backtracking,
background noise — and those device sessions are the freshest regression
corpus. The fastest way to add your own voice to that evidence: recite, then
open the session report — every wrong word carries the moment's audio and what
the model heard.

```bash
scripts/collect_clips.py          # pull recordings off the device and score them
```

See [`engine/audio/hesitate/README.md`](engine/audio/hesitate/README.md) for what
to record.

## Still honestly unmeasured

- deliberate mispronunciation under controlled conditions (the sessions
  carry real mistakes, but nobody has labeled them word by word yet)
- ambiguous repeated words (e.g. الرحمٰن الرحيم in 1:3 and 1:4) priced on
  device
- far-field audio and multi-reciter voices

Auto feedback is a study aid. A teacher's ear is the authority.

### On what these marks mean

This is said plainly, because the subject is the Book of Allah and a person's
standing with it. The marks this app paints are one instrument's reading of one
microphone's audio — nothing more. A red word is not a proof that you sinned,
and a green page is not a certificate that you recited well; the engine has
been wrong before, the places where it is weak are documented in this
repository, and its rules exist to *remove* accusations rather than add them.
What the app claims is narrower: here is what was heard, here is the recording,
hear it and judge for yourself — and take the verdict to a teacher when it
matters. If anything in this README ever reads as more than that, the README
is wrong and should be corrected.

---

## For contributors — hijra ila al-code

This is a tool built for Muslims first, and many of its contributors will be
Muslims who program. The code is yours to read, change, and build on (MIT,
below) — and the project's whole discipline is written down so you never have
to reverse-engineer why it is shaped the way it is.

One rule governs everything, and it is worth reading before your first commit:

> **A verdict is an accusation.** Painting a word red tells someone they
> recited the Book of Allah wrongly. Never let a false accusation ship, and
> never let a non-verdict be drawn as one. A wrong verdict is worse than no
> verdict.

Where to start, in order:

| Read | What it gives you |
|---|---|
| [docs/CORE_LOGIC.md](docs/CORE_LOGIC.md) | The pipeline in plain language: detect, decode, judge, move, paint, record |
| [docs/MAINTAINING.md](docs/MAINTAINING.md) | How to change the app and PROVE the change: the gate, how to write a check, how to add a tajweed rule |
| [AGENTS.md](AGENTS.md) | The standing rules of this tree — stage by path, fault-inject every check, refuse rather than invent |
| [docs/TODO.md](docs/TODO.md) | The live backlog, with the measured negatives marked so nothing is re-attempted blind |

Adding a rule to the engine has one safe shape: **a rule that only removes
blame is safe by construction.** The waqf, optional-final and qalqalah-form
guards are that family; `docs/TAJWEED_AND_MEMORISATION.md` maps what the
engine cannot yet judge, with the site counts.

## بالعربية

التطبيق يعمل بالعربية بالكامل: الواجهة، شاشة التسميع، تقرير الجلسة، والعدسة
المحرّكية. إضافة لغة جديدة لا تحتاج تغييراً في الشيفرة — فقط ملفات نصوص. سيّد
العمل هنا قاعدة واحدة: الحُكم على كلمة بالخطأ أمانة، فلا يُعرض كلمة بالخطأ إلا
بالدليل، وما لم يُسمَع لا يُحسب على القارئ.

---

## Licence

App code MIT — see [LICENSE](LICENSE) and [NOTICE](NOTICE). Page images and glyph
data originate from the quran_android Madinah data set; see
[reference/README.md](reference/README.md).
