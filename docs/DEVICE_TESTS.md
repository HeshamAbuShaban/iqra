# Device tests — the evidence only your voice can produce

Four defects and one feature are waiting on a recitation, not a build. Each
test below says what to do, what to watch, and what the record must show
afterwards — pull the record (`adb shell run-as com.iqra.quran cat
files/sessions/<file>.json`) and the tools name the rest.

Run them on the newest build (versionCode = the commit count; confirm with
`dumpsys package com.iqra.quran`). One recitation can cover several tests if
it follows the shape.

## 1. The reader bounce (page publish reversing direction)

Seen in every 19-October record: a page publish that goes forward, then back
to the lock's page, then forward again — the page-turn fight you reported.

- **Do**: recite across a page boundary (finish a page, continue onto the
  next) at your normal pace, and once recite slowly near the boundary.
- **Watch**: the page during the seconds around the turn — does it hold, or
  flick back to the previous page before settling?
- **Record must show**: the frame ring's page publishes (tag 2/3 rows) and
  the walk. `device_blame.py` prints the verdict timeline; the bounce reads
  as a lock reversal inside the frames.

## 2. The two-surah page walk (the continuous version)

The piecewise sessions behaved; the continuous walk with the page turn at
the boundary is the real test.

- **Do**: start mid-page on a page where one surah ends and the next begins
  (e.g. page 106, finishing surah 4 into Quraysh), and recite continuously
  across the boundary without stopping.
- **Watch**: does the lock follow you into the second surah, or wait?
- **Record must show**: the sibling-escape tag (3) firing at the boundary,
  and no accusation on the first word of the second surah
  (`unanchoredNeverAccuses` guard).

## 3. Repeat-to-refresh

Backtracking was exercised on 19 October (2 reversals, no false
accusation); REPEAT is not.

- **Do**: recite an ayah, then REPEAT it two or three times (the memoriser's
  consolidation pattern), then move on.
- **Watch**: does the verdict stay earned (no red flash on the repeated
  ayah), and does the re-emission read as hesitation rather than a stall?
- **Record must show**: `reversals` counted, no WRONG on the repeated ayah's
  words, and the standing word holding its verdict.

## 4. The gated long jump (2 measured events)

The jump gate skipped an ayah twice on record (nextCov = 0.50 both times).

- **Do**: recite two ayat, then SKIP one ayah deliberately and continue (a
  real lost-place moment), twice in one session.
- **Watch**: after the skip, which ayah does the lock land on — the skipped
  one or the one you are reciting?
- **Record must show**: the jump classification events and the ayah the
  lock actually advanced to; `evidence_window_parity`'s jump re-judge keeps
  the skipped ayah's verdict honest.

## 5. The kind split, live on your own mistakes

Already verified on one session; every new session feeds it.

- **Do**: recite with two deliberate mistakes (one mid-word, one skipped
  word).
- **Watch**: the report's wrong rows — tap one open: the kind chip, the
  split (units said differently / never said), the heard beads, the clip.
- **Record must show**: `wsub >= 1` on every WRONG row, `wmiss` carrying the
  skipped word's units, and the zero-substitution guard refusing a
  contradictory row.
