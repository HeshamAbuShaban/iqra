#!/usr/bin/env python3
"""A record is written only for a session that ran - and a session's header
describes its own session.

The stopRecite final write used to be unconditional, and flushSessionRecord's
guards (`!ended && ...`) can never stop an ended=true write - so a page jump
with no session running flushed a zero-identity corpse onto the device:
`s-0-0-#<code>.json`, startedAt 0, surahStart 0, empty rings, live surahNow /
lockNow. Found on the phone 17 October beside nine real sessions, and the
session list counted it as a session.

The startRecite header used to be written before the counters, the frame ring
and the generation were reset, so a crash in the session's first seconds left
the PREVIOUS session's frame tail and counters under the NEW session's name
and timestamp - every field plausible, the record a lie.

  1. the final write is guarded on the session having run,
  2. no unguarded final write exists anywhere,
  3. the start header is written after the counters, the ring and the
     generation were reset.

`sessionRan` is the right predicate, not `_recording`: a mic-start failure
never sets `_recording` but must still leave a record (ran=yes durMs=3 fed=0),
and `endSession` resets `sessionRan` at every session's end, which is what
stops a post-session jump from rewriting the old record in place.
"""

import os
import sys

VMS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..",
                   "android", "app", "src", "main", "java", "com", "iqra",
                   "quran", "ui", "PracticeViewModel.kt")


def main():
    vm = open(VMS, encoding="utf-8").read()
    failures = []

    if "if (sessionRan) runCatching { flushSessionRecord(ended = true" not in vm:
        failures.append("the final write is not guarded on sessionRan - a "
                        "jump with no session running flushes a corpse")
    for ln in vm.splitlines():
        if "flushSessionRecord(ended = true" in ln and (
                "sessionRan" not in ln or "if (" not in ln):
            failures.append("an unguarded ended=true write exists: " + ln.strip())

    start = vm.index("fun startRecite(")
    end = vm.index("\n    fun ", start + 1)
    body = vm[start:end]
    flush = body.index("flushSessionRecord(ended = false)")
    for anchor in ("resetSessionCounters()", "frameRing.clear()", "++sessionGen"):
        if body.index(anchor) > flush:
            failures.append("the start header is written before %s - a crash "
                            "in the first seconds leaves the previous "
                            "session's tail under the new name" % anchor)

    for line in failures:
        print("FAIL record_flush_guard - " + line)
    print("(none of the failures hold)" if not failures else "")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
