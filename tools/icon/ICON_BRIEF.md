# Iqra app icon — professional brief + image-model prompt kit

Use this file when generating candidates with an image model (Google Nano
Banana / Gemini image, Qwen-Image, or similar). Everything a model needs to
produce a professional result and everything a reviewer needs to reject a
slop one is here. The current shipped mark is `tools/icon/iqra.svg` (v6, an
open mushaf on a warm field) — this brief reopens the question deliberately.

## 1. Brand facts (bake these into every prompt)

- **Name:** Iqra (اِقْرَأ — "recite/read", the first revealed word).
- **What it is:** an offline Quran-recitation tutor for memorisers. It
  listens while you recite and shows, word by word, what it heard.
- **One-line soul:** "recite, and be followed."
- **Palette (the app's own tokens, use these exact values):**
  - Ground near-black teal `#070B0C`, surface `#0F1A1A`
  - Single accent teal `#2BB6A0` (progress, focus, "you")
  - Gold `#D9B36B` / bright gold `#F2DDA8` (scripture, marks, illumination)
  - Ink `#EAF3EE`
- **Tone words:** quiet, precise, devotional, instrument-like. A teacher's
  pen, not a game.
- **Cultural register:** the mushaf (Madani script), Islamic geometric
  tradition, recitation (sound) — but restrained. No clichés.

## 2. Technical constraints (non-negotiable)

- **Android adaptive icon:** design on a 108×108 dp canvas; the visible safe
  zone is the inner ~72 dp circle — nothing critical within ~18% of any edge
  (masks crop to circle, squircle or rounded square per launcher).
- Must stay legible at **48 dp** (launcher) and **24 dp** (notification,
  settings) — test by shrinking, not by squinting.
- Two layers for the adaptive icon: a **background** (solid or very subtle
  field) and a **foreground** (the mark), shipped as separate assets.
- **No baked-in text or lettering of the app name** (the launcher already
  labels the app). If a calligraphic glyph is the mark itself, it must be a
  single confident shape, not a sentence.
- No baked drop shadows or outer glows (the launcher composites its own).
- Flat, crisp edges; vector-look. Gradients only inside the subject if they
  describe form (page curvature), never as decoration.

## 3. What "AI slop" means for icons — the reject list

Reject any candidate that has: a purple-to-blue "AI gradient"; a glowing
neon outline; fake 3D bevel or glass shine; a lens flare; a crescent +
star cliché; an open book with a light beam rising from it; gears or
circuit boards fused with books; sparkles "quality dust"; more than two
typefaces or any legible word besides the mark; drop shadow under a flat
graphic; busy detail that dies at 48 dp. If it looks like a stock
"e-learning app icon", it is slop.

## 4. Concept directions (pick one, don't blend)

**A — The calligraphic iqra mark.** A single bold stroke or masterful
settled composition of the word اِقْرَأ (or its first letter قاف as a
monogram), drawn in the app's gold on the near-black ground, one teal
element maximum (e.g., the mark a reciter's eye follows). Highest ceiling,
hardest to get right: bad Arabic calligraphy is worse than no calligraphy.
Ask the model for "clean thuluth-inspired geometric simplification, museum
logo quality" and iterate hard.

**B — The mushaf page that is also a wave.** The current v6 idea matured:
one page (not two — the two-page book reads as "notebook", that failure is
documented), its ruling lines resolving into a recitation waveform, the
line being followed picked out in gold. Ties sound + scripture + the
product's core mechanic ("recite, and be followed") in one shape.

**C — The geometric star with one living line.** An eight-fold Islamic
geometric rosette, quiet ink-on-near-black, with a single gold arc crossing
it — the recitation line. Most "pro logo" of the three; least specific.

Recommendation: **B** for distinctiveness-with-safety, **A** if the
calligraphy lands in three iterations or fewer.

## 5. Paste-ready prompts

### 5a. Primary — Google Nano Banana (Gemini image)

**First pass:**

> App icon design, flat vector style, for a Quran recitation tutor called
> Iqra. A single stylised mushaf page seen at a slight angle, its ruling
> lines flowing out of the page's right edge and resolving into a smooth
> audio waveform, the waveform's single active line in warm gold #D9B36B,
> the page and remaining lines in soft ink #EAF3EE at reduced weight, on a
> near-black teal field #070B0C. Minimal, quiet, precise — a scholarly
> instrument, not a toy. Centered composition occupying the middle 66% of
> the canvas (Android adaptive-icon safe zone). Crisp clean edges, flat
> colour planes, no gradients except a subtle page curvature shade, no
> text, no letters other than the mark itself, no drop shadow, no glow, no
> 3D bevel, no sparkles, no crescent, no stock e-learning look. Square 1:1.

**Iterate with ( Nano Banana's strength is editing while keeping
composition):**

- "Same composition, simplify: reduce the waveform to three undulations."
- "Same composition, make the gold line thicker so it reads at 48 pixels."
- "Same composition, increase the page's lean by 4 degrees so it feels
  recited, not displayed."
- "Give me the same mark on a deep teal field #0F1A1A and on white, for
  contrast testing."

### 5b. Alternate — Qwen-Image

Qwen follows flat-poster/vector instructions well and is stronger at
keeping colour fields exact. Use the same brief verbatim, but prepend:

> Flat geometric logo sheet, 1:1, vector-style, exactly three candidate
> marks on one dark #070B0C field, generous margins, no mockups, no hands,
> no phone frames:

and after generation, ask it to isolate and refine the best candidate.
Reject anything that gains a gradient wash or glow during refinement.

### 5c. Negative prompt (both models)

> text, watermark, letters, book stack, open two-page notebook, crescent
> moon, star and crescent, prayer beads, mosque silhouette, gradient
> background, neon glow, drop shadow, bevel, 3D render, glassmorphism,
> sparkles, lens flare, purple blue gradient, busy detail, thin hairlines
> that vanish at small size, cluttered composition, elements touching the
> canvas edge

## 6. Acceptance checklist (before any candidate is "the one")

1. Shrink to 48 px and 24 px: the mark still reads, the gold line is still
   findable.
2. Mask test: circle, squircle and rounded-square crops — nothing critical
   clipped.
3. Grayscale test: the mark still separates from its field without colour.
4. Sits next to WhatsApp/YouTube/Google Maps in a launcher row without
   shouting or vanishing.
5. Arabic elements (if any) drawn correctly — have a reader verify, bad
   calligraphy is disqualifying.
6. Two-layer split possible (background field vs foreground mark) for the
   adaptive icon.

## 7. After the pick (mechanical, ask the coding agent)

1. Cut out / flatten to a 1024 px master PNG.
2. Split into adaptive-icon layers: `ic_launcher_foreground.webp` (mark on
   transparent, content within the 72 dp zone) + background layer (field).
3. Export the mipmap set (48/72/96/144/192 dp) and round
   variants; keep `tools/icon/iqra.svg` in sync or supersede it with the
   new master + provenance note.
4. Install on device, view in the launcher over light and dark wallpapers,
   and only then commit.
