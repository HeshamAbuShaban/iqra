# Iqra explainer video — production brief + prompt kit

The companion to `tools/icon/ICON_BRIEF.md`: how to explain how Iqra works
in a video or other visual format, without producing AI slop. The core rule
comes before anything else:

> **The app is the footage.** Every UI moment in this video is a real screen
> recording of the real app. AI-generated video is used only for three
> conceptual metaphors, clearly stylised, never impersonating the UI.
> A viewer who later opens the app must find everything the video showed.

## 1. What the video must explain (the honest pipeline)

One sentence per stage, in the order the audio actually flows:

1. **You recite from memory** — no teacher present, the page hidden.
2. **The phone listens** — mic audio, 16 kHz, on-device only.
3. **Silence is ruled out** — a voice-activity gate decides what is speech.
4. **Sound becomes symbols** — a small on-device model turns each moment of
   audio into phoneme symbols (the sounds of Arabic, not letters).
5. **Symbols meet expectation** — a dynamic-programming aligner lays the
   heard symbols against the expected units of the ayah, from the app's own
   phoneme table.
6. **A word is judged** — matched, or charged, and *where in the word* the
   charge begins; unheard words are never counted against you.
7. **The record keeps the evidence** — every verdict with its time, the
   symbol stream, and the session's own audio, kept on the device only.
8. **You replay the moment** — hear the 2.5 seconds before any verdict and
   see where in the word the blame landed.
9. **Privacy is the promise** — no account, no network at runtime, nothing
   leaves the phone.

## 2. Beat board (~90 seconds, the recommended cut)

| # | Beat | Visual | Seconds |
|---|------|--------|---------|
| 1 | The problem | Real phone, hide-mode on, someone reciting alone | 0–8 |
| 2 | The promise | A word turns red, then the report's "where blame lands" card | 8–16 |
| 3 | The pipeline | **AI metaphor shot M1** (sound → symbols) | 16–26 |
| 4 | The alignment | **AI metaphor shot M2** (two lines of units knitting together) | 26–36 |
| 5 | The lens | Real screen: the engine lens toggled on mid-session — pipeline chips, coverage bars crossing gold ticks | 36–52 |
| 6 | The evidence | Real screen: report → hear-it on a wrong word; the mistake-map ticks | 52–66 |
| 7 | The honesty rules | Title cards over real stills: red only for a measured substitution; unheard is not wrong; nothing leaves the device | 66–80 |
| 8 | Close | The launcher icon, the one-liner: "recite, and be followed." | 80–90 |

The **engine lens** (LiveMode, the heart icon) is the video's best asset:
it exists precisely to make the invisible pipeline visible, live, in Arabic.
Beat 5 should be the longest real-screen moment.

## 3. The three AI metaphor shots — paste-ready prompts

Style frame for all three: the app's own palette — near-black teal field
`#070B0C`, single teal accent `#2BB6A0`, warm gold `#D9B36B`, ink `#EAF3EE`.
Motion is slow, deliberate, instrument-like. Never show a phone, a UI, a
mosque, or legible Quran text in these shots (the real app carries those).

**M1 — sound becomes symbols (8 s):**

> Cinematic macro shot on a near-black teal background #070B0C. A single
> thin golden soundwave line enters from the left, travels rightward, and
> as it crosses the frame it crystallises into a sequence of small glowing
> gold glyph-tiles, evenly spaced, each tile a distinct abstract Arabic-
> calligraphy-inspired unit — abstract strokes, NOT legible words. The
> wave behind fades as the tiles settle into a clean horizontal row. Slow,
> precise, instrument-like motion. Flat colour planes, soft film grain,
> no UI, no phone, no text captions, no lens flare, no neon glow.

**M2 — the alignment (10 s):**

> On the same near-black teal field, two horizontal rows of small gold
> glyph-tiles: the top row sits complete; the bottom row assembles itself
> left to right, tile by tile, each bottom tile sliding until it locks
> directly beneath its matching top tile, drawing a thin teal connector
> line on contact. One position does not find a match and stays empty,
> marked with a faint amber outline that pulses once. Slow, methodical,
> weaving-loom rhythm. No UI, no text captions, no faces, no glow bloom.

**M3 — the boundary (6 s, optional):**

> A vertical hairline of teal light sweeps slowly across a field of
> settling gold glyph-tiles, and tiles on one side of the line dim to
> near-invisible while tiles on the other side stay lit — a quiet visual
> of "this audio belongs to the previous ayah". Same palette and rhythm,
> no text.

**Negative prompt (all shots):** legible Quran text, Arabic words, UI
elements, phone frames, hands, mosque, crescent, neon glow, purple-blue
gradient, fast flashy transitions, cinematic zoom bursts, watermark.

**Recommended models:** Google Veo (or Nano Banana stills → image-to-video)
for the palette fidelity and prompt obedience; Qwen video as the alternate.
Generate 3–5 takes per shot; the shots must sit seamlessly between real
screen recordings, so prefer the *dullest* take that reads clearly.

## 4. Real-screen capture checklist

- Record on the phone at 1080p+; enable "show taps" only if a tap matters.
- Shoot beats 1, 5, 6 in **both** languages if the video is bilingual — the
  app is fully localised; pick the audience's language per cut.
- The report's mistake-map ticks and the "hear it" clip are the money shot
  of beat 6 — have a real session with 3–5 wrong words prepared (recite
  Al-Fatiha with two deliberate mistakes).
- Captions: hard captions in the video's language; Arabic captions set in
  the app's Quran typeface, Latin captions in the UI face; keep lines
  under 42 characters.
- Music: none, or a single slow oud/phrygian pad under -24 LUFS. The app's
  promise is quiet.

## 5. Honesty rules for the edit

- Never script a "wrong" verdict the engine did not produce — make the
  mistake for real and record it for real.
- If a feature is shown, it exists in the shipped build the video is cut
  from; pin the build number in the description.
- Latency is shown as it is: the verdict lands when it lands (median under
  a second on recent builds — do not cut the delay to fake instant).
- UNKNOWN/SKIPPED states must be shown muted, never as errors.

## 6. Rough edges the video must not lie about

(Filled from the bug sweep of the current source; update per release.
Anything listed here is either fixed before the video or explicitly
excluded from the script.)

- **FOUND AND FIXED in the sweep (26 Oct):** the reference-audio download
  URL was built with an unpinned `%03d` — under the Arabic locale it
  rendered Eastern Arabic digits and would break every reciter download
  (lesson 14's class, in a sibling path). Pinned to `Locale.US`.
- **Known cosmetic, exclude from close-ups:** diag lines use unpinned
  `%.2f` numerals; under Arabic they print Eastern Arabic digits. No tool
  currently parses them numerically, but keep them out of readable
  close-ups until pinned.
- The lens, resume and move-to rounds otherwise swept clean on the
  surface-logic pass; the locale/format class is the one to re-grep after
  every localisation round.

## 7. Formats to deliver

- 16:9 master (90 s), 9:16 vertical cut (60 s), and a 30 s teaser built
  from beats 1–2–6–8 only.
- Silent-safe edit: every beat must communicate with captions alone
  (most views are muted).
- Thumbnail: the launcher icon on the near-black field, one word — the
  app name in Arabic.
