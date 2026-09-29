#!/usr/bin/env python3
"""Regression test for the dead-stream bug: only the first Recite ever worked.

ensureVoice() used to begin with `if (zipformerOn) return true`, and
zipformerOn was never cleared. stopRecite() releases the native stream, and
jumpToPage() calls stopRecite(), so from the second session onwards the app
reported itself ready for a stream that no longer existed: accept() failed
silently, fedTotal still climbed, and the lock sat on a correct anchor that
could never advance.

The assertions read the Kotlin source rather than restating it, so the fix
cannot be undone quietly, and then model the session lifecycle to show the
second session now gets a stream.

Run:  engine/.venv-replay/bin/python engine/replay/session_stream.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VM = ROOT / "android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt"
SH = ROOT / "android/app/src/main/java/com/iqra/quran/ml/SherpaZipformer.kt"


class Recognizer:
    """Model of the global native recogniser/stream pair."""

    def __init__(self):
        self.recognizer_loaded = False
        self.stream = None
        self.accepted = 0
        self.released = 0
        self.fed_total = 0          # what the ViewModel believed it fed
        self.last_op_error = None

    def start_stream(self):
        if not self.recognizer_loaded:
            return False
        if self.stream is not None:
            self.released += 1     # startStream releases the old one
        self.stream = object()
        return True

    def close_stream(self):
        if self.stream is not None:
            self.released += 1     # closeStream must release, not just drop it
        self.stream = None

    def has_stream(self):
        return self.stream is not None

    def accept(self, samples):
        if self.stream is None:
            self.last_op_error = "accept: no live stream"
            return False
        self.accepted += samples
        return True


def check_sources(failures):
    vm = VM.read_text(encoding="utf-8")
    sh = SH.read_text(encoding="utf-8")

    # 1. The readiness guard must ask about a live stream, not just the flag.
    #    Non-greedy: hasStream() brings its own parentheses.
    guard = re.search(r"if \(zipformerOn.*?\) return true", vm)
    if not guard:
        failures.append("could not find the ensureVoice() readiness guard")
    else:
        if "hasStream()" not in guard.group(0):
            failures.append(
                f"readiness guard {guard.group(0)!r} does not check for a live "
                "stream - the dead-stream bug returns"
            )
        if guard.group(0) == "if (zipformerOn) return true":
            failures.append("readiness guard is still the bare `if (zipformerOn)`")

    # 2. Nothing may clear the flag as a substitute for asking about the stream.
    #    (The declaration itself initialises it to false; that is not a clear.)
    for line in vm.splitlines():
        s = line.strip()
        if re.match(r"(private\s+)?(var|val)\s+zipformerOn\b", s):
            continue
        if re.search(r"\bzipformerOn\s*=\s*false\b", s):
            failures.append(
                f"zipformerOn is cleared by hand ({s!r}); readiness should be "
                "decided by hasStream(), not by a flag that can drift"
            )

    # 3. A released stream must actually release, or every session leaks one.
    close = re.search(r"fun closeStream\(\) \{(.*?)\n    \}", sh, re.S)
    if not close or "release()" not in close.group(1):
        failures.append("closeStream() does not release() the native stream")
    start = re.search(r"fun startStream\(\): Boolean \{(.*?)\n    \}", sh, re.S)
    if not start or "release()" not in start.group(1):
        failures.append("startStream() does not release() the stream it replaces")

    # 4. fedTotal must only advance on audio the recogniser really took.
    if not re.search(r"fun accept\(samples: FloatArray\): Boolean", sh):
        failures.append("accept() does not report whether audio was accepted")
    frame = re.search(r"val fedThisFrame = SherpaZipformer\.accept\(fresh\)", vm)
    if not frame:
        failures.append("fedTotal is no longer gated on accept() succeeding")


def simulate(failures):
    """The exact reported sequence, against the model."""
    r = Recognizer()
    r.recognizer_loaded = True
    zipformer_on = False

    def ensure_voice():
        # Mirrors the fixed guard: short-circuit only when a stream is live.
        nonlocal zipformer_on
        if zipformer_on and r.has_stream():
            return True
        ok = r.start_stream()          # createStream
        zipformer_on = ok
        return ok

    def run_frame(n):
        fed_this_frame = r.accept(n)   # accept() is the single source of truth
        if fed_this_frame:
            r.fed_total += n
        return fed_this_frame

    def session(label):
        if not ensure_voice():
            failures.append(f"{label}: ensureVoice() refused to start")
            return
        got = sum(1 for _ in range(4) if run_frame(4000))
        if got == 0:
            failures.append(
                f"{label}: 0 of 4 frames reached the recogniser "
                "(the dead-stream symptom)"
            )

    session("session 1 (fresh launch)")

    # The user taps Stop. This is what killed every later session.
    r.close_stream()
    session("session 2 (after Stop)")
    # A page swipe / "Go to page" goes through jumpToPage -> stopRecite.
    r.close_stream()
    session("session 3 (after swipe)")

    # Two stopRecite-equivalents, so exactly two streams must be released.
    # startStream() only releases when it replaces a live one, and after a
    # close there is nothing to replace - so two, not three.
    if r.released != 2:
        failures.append(
            f"expected 2 native stream releases, saw {r.released} - streams leak"
        )


def main() -> int:
    failures = []
    check_sources(failures)
    simulate(failures)

    if failures:
        print("FAIL")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("PASS: every session gets a live stream, and the log cannot claim")
    print("      audio was fed when the recogniser took none")
    return 0


if __name__ == "__main__":
    sys.exit(main())
