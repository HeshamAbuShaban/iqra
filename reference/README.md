# Reference material (vendored for study)

Two open-source projects were cloned locally (under `reference/`, git-ignored to
keep the repo light) to harden Iqra using proven expertise:

- `reference/tarteel-ml/` — **TarteelAI/tarteel-ml** (MIT, archived). Pre-training
  / preprocessing scripts for the Tilawa-style CTC model.
- `reference/quran_android/` — **quran/quran_android** (GPL-3, 2.4k★). The
  reference "well-made" Quran *presentation* experience.

## Key takeaways

### Recognition front-end (from tarteel-ml)
- Audio spec is **16 kHz, 16-bit, mono WAV** — exactly what `AudioRecorder`
  already produces. No mel/pre-emphasis is needed client-side; the ONNX graph
  bakes in feature extraction (confirmed in `TilawaEngine`).
- The CTC vocabulary is **character-grapheme** level (`generate_alphabet.py`
  builds `alphabet.txt` from the unique Arabic characters). Our
  `ArabicNormalizer` already folds alef/hamza/taa-marbuta variants and strips
  harakat, which is what lets the decoded transcript align to the reference.
- The runtime recognition/detection logic is NOT in this repo (it lives in the
  closed app / the Tilawa engine). Our alignment logic (`WordAligner`) + the
  local forward-window tracker in `PracticeViewModel` is the equivalent.

### Presentation (from quran_android) — the authentic target
quran_android renders the **Madani page as an image** and overlays
**per-word highlight rectangles** using a coordinate model:
- `GlyphCoords(glyph: AyahGlyph, line: Int, bounds: RectF)` — each word glyph
  has a `line` number and a bounding `RectF` (left/top/right/bottom), tied to
  `(sura, ayah, word)` via `AyahGlyph.WordGlyph`.
- `PageGlyphsCoords` expands per-line bounds to full page width and adjacent
  lines, then draws highlight rects for the current recitation position.
- Data source: `quran/quran.com-images` (page PNGs + `coordinates/` JSON) and
  `quran/quran-data`. Highlighting is done on the *image*, not by re-flowing
  text — this is what makes the layout "strictly preserved".

## Planned next phase: authentic image + coordinate overlay
Adopt quran_android's method as the display foundation:
1. Lazy-download (cache offline, like `ModelManager`) the Madani page image and
   its coordinate JSON for the visible page.
2. Render the image fit-to-width; scale `RectF` glyph boxes to the displayed
   rect.
3. Overlay highlight rects keyed by `(surah, verse, wordInVerse)`:
   current word = accent, mistake = red, recited = subtle, hidden (hide mode) =
   mask with paper color.
4. Graceful fallback to the current `AnnotatedString` text renderer if image/
   coordinate data is unavailable, so the app never regresses.

This keeps per-word live highlighting (Iqra's core feature) while matching
quran_android's faithful page presentation.
