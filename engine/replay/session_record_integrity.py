#!/usr/bin/env python3
"""
The record-integrity bug the user's own sessions exposed.

s-1791438643998-67 reported, for a 406 s recitation of Al-Mulk at 105 wpm:
    333 words evaluated, 322 CORRECT, 0 judged words, 0 moves, fed 0
The lock had plainly moved (diagTail: "lock 14 -> 15", "lock 15 -> 16").

endSession() called resetSessionCounters(), and endSession also runs for every
page jump (`reason=preparing-cancelled`, `ran=no`). Jumping around after a
recitation therefore zeroed the headline counters of the record still being
written, while sessionArchive and sessionEvaluations - not in that reset -
survived. The record stayed internally contradictory and still looked real.

This asserts the two halves of the fix:
  1. endSession no longer calls resetSessionCounters();
  2. every counter the record writes is reset by startRecite alone, so a
     session's numbers cannot be zeroed by anything but its own beginning.
"""
import re, sys
from pathlib import Path

VM = Path("android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt").read_text(encoding="utf-8")

def fn_body(name, src=VM):
    m = re.search(r"(private )?fun %s\b.*?\n    \}\n" % name, src, re.S)
    if not m:
        return ""
    out = []
    for l in m.group(0).splitlines():
        s = l.strip()
        if s.startswith("//"):
            continue
        out.append(s.split("//")[0])
    return "\n".join(out)

bad = []

if "resetSessionCounters()" in fn_body("endSession"):
    bad.append("endSession still calls resetSessionCounters()")

# Every counter the record writes must be cleared by the session START path -
# either inline in startRecite or inside the helper it calls. Nothing else may
# clear them, or a record's headline numbers can be zeroed by something that is
# not the session they describe.
WRITTEN = ["sessionMoves", "sessionReversals", "sessionEvaluations", "sessionTerminalCount",
           "sessionNoWindowWords", "sessionEmptyWindows", "sessionAdvisories", "sessionFed"]
start = fn_body("startRecite")
helper = fn_body("resetSessionCounters")
cleared_at_start = start + "\n" + helper

if "resetSessionCounters()" not in start:
    bad.append("startRecite does not call resetSessionCounters()")

for name in WRITTEN:
    if f"{name} = 0" not in cleared_at_start and f"{name}.clear()" not in cleared_at_start:
        bad.append("no session-start path resets %s, so it can survive into the next record" % name)

# The other end of the contract: only the start path may clear them.
for fn in ("stopRecite", "loadSurah", "jumpToPage", "anchorToVerse", "selectAyah", "endSession"):
    body = fn_body(fn)
    for name in WRITTEN:
        if f"{name} = 0" in body or f"{name}.clear()" in body:
            bad.append("%s() clears %s, which can belong to a different session" % (fn, name))

if bad:
    for b in bad:
        print("FAIL:", b)
    sys.exit(1)
print("ok: a session's counters survive browsing; only the next startRecite clears them")
