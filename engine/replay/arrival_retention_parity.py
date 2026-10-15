#!/usr/bin/env python3
"""
The arrival retention window and the emission log retention must agree.

They did not, and the consequence was not a leak but a lie. `emissionLog` kept
4,000 symbols while `ayahArrival` kept `lockedAyah +- 3`. A session recites
hundreds of ayat, so almost every arrival had been dropped by the time the record
was written, and the replay that reads a WRONG word back found no neighbour to
define its window with. Its fallback filled the gap - `ayah-1` defaulted to 0,
`ayah+1` defaulted to the end of the log - and produced a window spanning the
entire session: 1,200 symbols of the wrong ayah, formatted as data, "explaining"
10 of the user's 26 false accusations.

The tool now refuses such a window, which is right but not sufficient - the
record still could not answer the question it was written to answer. This pins
the agreement that makes it able to:

  1. the arrival span must cover every ayah whose audio can still be in the log,
     which is the symbol cap divided by the ayat it holds;
  2. the record must not write arrivals pointing outside the retained log;
  3. the reader must refuse a window it cannot rebuild rather than invent one.

Run: python3 engine/replay/arrival_retention_parity.py
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
VM = HERE.parent.parent / "android" / "app" / "src" / "main" / "java" / "com" / "iqra" / "quran" / "ui" / "PracticeViewModel.kt"
TOOL = HERE / "device_blame.py"

# An ayah averages well under 40 symbols across the whole Quran; the longest
# surah's average is the number that matters, not its longest ayah.
SYMBOLS_PER_AYAH = 40


def main() -> int:
    vm = VM.read_text(encoding="utf-8")
    tool = TOOL.read_text(encoding="utf-8")
    bad = []

    m = re.search(r"private const val EMISSION_LOG_CAP = (\d+)", vm)
    span = re.search(r"private const val RETENTION_AYAH_SPAN = (\d+)", vm)
    if not m or not span:
        bad.append("one of EMISSION_LOG_CAP / RETENTION_AYAH_SPAN is gone; the "
                   "retention agreement has no source")
    else:
        cap = int(m.group(1))
        keep = int(span.group(1))
        if keep * SYMBOLS_PER_AYAH < cap:
            bad.append("the arrival span keeps %d ayat, which is %d symbols - "
                       "less than the %d-symbol log, so ayat whose audio is "
                       "still retained will have no arrival"
                       % (keep, keep * SYMBOLS_PER_AYAH, cap))

    # The span must be USED by the pruner, not merely declared. A file-level
    # substring test passes when the constant exists and the pruner still says
    # ±3, which is how this check would have gone vacuous on its own bug.
    prune = re.search(r"private fun pruneAyahWindows.*?\n    \}", vm, re.S)
    if not prune:
        bad.append("pruneAyahWindows is gone")
    elif prune.group(0).count("RETENTION_AYAH_SPAN") < 2:
        bad.append("pruneAyahWindows does not derive BOTH edges of the retained "
                   "span from RETENTION_AYAH_SPAN; the ±3 value can come back "
                   "while the constant still exists")

    if not re.search(r"if \(v < emissionBase \|\| v > logEnd\) continue", vm):
        bad.append("the record does not filter arrivals to the retained log, so "
                   "an arrival can point at symbols that no longer exist")

    if "no arrival in the retained log" not in tool:
        bad.append("the reader has no refusal path, so it will invent a window "
                   "rather than say it cannot rebuild one")
    if re.search(r"return None, None, \"no arrival in the retained log\"", tool) \
            and "return None, None, \"no arrival in the retained log\"" not in tool:
        bad.append("unreachable")

    if bad:
        for x in bad:
            print("FAIL:", x)
        return 1
    print("ok: the arrival retention spans every ayah whose audio is still in "
          "the log, and the reader refuses a window it cannot rebuild")
    return 0


if __name__ == "__main__":
    sys.exit(main())