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


def last_ayah_uses_page_scope(src: str) -> bool:
    """The handoff boundary must be the surah's last ayah, not the page's."""
    m = re.search(r"val lastAyah\s*=\s*(.+)", src)
    if not m:
        return True
    # Strip a trailing comment and inspect only what is evaluated.
    expr = m.group(1).split("//")[0].strip()
    return "scopeEndAyah" in expr


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

# Every timing constant that thinks in SECONDS must be derived from the MEASURED
# poll interval, not the declared one. The declared FEED_POLL_SEC is 250 ms; the
# 1,428 s Al-Kahf session delivered a median of 563 ms, so wrongLatchFrames and
# the words-per-minute estimate were both wrong by 2.25x - and wpmEma's clamp
# hid the inflation. A session record must also carry both numbers so a later
# reader can tell which one was in force.
# wrongLatchFrames is expression-bodied, so match the line, not a body.
wrong_latch = re.search(r"private fun wrongLatchFrames\(\): Int =\s*\n?\s*(.+)", VM)
if not wrong_latch:
    bad.append("wrongLatchFrames() is gone; the WRONG persistence rule has no source")
elif "FEED_POLL_SEC" in wrong_latch.group(1):
    bad.append("wrongLatchFrames still derives seconds from the declared cadence")

m = re.search(r"val dtSec = speechFramesSinceAdvance \* ([\w.]+)", VM)
if not m:
    bad.append("advanceLockTo no longer computes a words-per-minute interval")
elif "FEED_POLL_SEC" in m.group(1):
    bad.append("words-per-minute still uses the declared cadence (%s)" % m.group(1))

# The measurement must be CALLED, not merely defined: a definition with no call
# site is exactly the kind of dead code that reads as a fix and is not one.
if VM.count("notePollDuration(") < 2:
    bad.append("the loop never calls the poll-duration measurement "
               "(%d site(s), definition plus call site needs 2)" % VM.count("notePollDuration("))
if '\\"pollSec\\":' not in VM or '\\"declaredPollSec\\":' not in VM:
    bad.append("the record does not carry both the measured and declared cadence")

# The handoff boundary is the surah's last ayah, never a page boundary.
if last_ayah_uses_page_scope(VM):
    bad.append("the handoff boundary is still a PAGE boundary (18:27 -> 19:1)")
if "private fun surahEndAyah" not in VM:
    bad.append("surahEndAyah() is gone; the surah boundary has no single source")


if bad:
    for b in bad:
        print("FAIL:", b)
    sys.exit(1)
print("ok: counters survive browsing, the surah is not left at a page break, "
      "and seconds are derived from the measured poll interval")
