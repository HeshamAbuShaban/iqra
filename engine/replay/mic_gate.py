#!/usr/bin/env python3
"""
The speech-admission path, which nothing else in this repository tests.

Every other check in the gate exercises judgement: alignment, lock policy,
windows, evidence, records. None of them models the microphone. `noiseFloor`,
`SILENCE_RMS`, `rms()` and the VAD appear nowhere in the replay harness, so the
code that decides whether a reciter's voice reaches the recogniser at all is
unmeasured - and it is the one place where a plausible-looking simplification
destroys the app silently.

The gate admits audio when EITHER the level clears the floor OR the VAD hears
speech:

    quiet     = rms(fresh) < noiseFloor
    vadSilent = if (vadReady) SherpaVad.feedAndDetect(fresh)?.not() else null
    if (quiet && vadSilent != false) { gate closes }

The VAD is what rescues a quiet reciter. A whisper sits under the calibrated
floor by definition - that is what whispering IS, acoustically - so with no VAD
the only thing left is RMS, and a quiet reciter is gated shut. Rewriting the
close condition as `if (quiet)` looks like a harmless simplification and kills
whispering outright. No check would catch it, because no check models this.

The other half is the null. `vadSilent` must be `null` when the VAD is
unavailable, never `true`. `null` means "no opinion", which lets RMS decide.
`true` means "it was silent", which closes the gate on every frame of a session
that simply had no Silero model - the engine appearing deaf for a reason that
has nothing to do with the room.

So this check does not measure anything. It pins the shape of a path that is
deliberately unmeasured, so the day it is changed, the change has to say so.
"""
import re
import sys
from pathlib import Path

VM = Path("android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt").read_text(
    encoding="utf-8")

bad = []


def require(cond, msg):
    if not cond:
        bad.append(msg)


def code_only(src):
    """Strip comments. Describing the fix in prose must not satisfy a check."""
    return "\n".join(l.split("//")[0] for l in src.splitlines()
                     if not l.strip().startswith("//"))


def check(src):
    del bad[:]
    body = code_only(src)

    close = re.search(r"val quiet = rms\(fresh\) < noiseFloor", body)
    require(close is not None, "the level test no longer compares rms to noiseFloor")
    vad = re.search(r"val vadSilent = (.+)", body)
    require(vad is not None, "vadSilent is gone: the VAD no longer has a vote")
    gate = re.search(r"if \(quiet && (vadSilent[^)]*)\)", body)
    require(gate is not None,
            "the gate close condition no longer mentions vadSilent: requiring BOTH "
            "level and VAD silence kills whispering, and nothing else tests this")
    require(gate is not None and "!=" in gate.group(1),
            "the gate requires vadSilent to be literally false; a null - no VAD - "
            "would then read as silence and close the gate for the whole session")

    if vad is not None:
        expr = vad.group(1)
        require("else null" in expr,
                "vadSilent no longer degrades to null when the VAD is unavailable; "
                "a missing detector must mean 'no opinion', not 'silence'")
        require("vadReady" in expr,
                "vadSilent no longer depends on vadReady")

    require(re.search(r"maxOf\(SILENCE_RMS,\s*rms\(floorProbe\)", body) is not None,
            "the calibrated floor lost its hard lower bound: a silent room would "
            "calibrate to ~0 and then let constant noise through to the decoder")
    # The lookbehind is load-bearing. `private var noiseFloor = SILENCE_RMS`
    # declares the field and also contains this text, so a plain match is
    # satisfied by the declaration and would have sat green with the per-session
    # reset deleted.
    require(re.search(r"(?<!var )\bnoiseFloor\s*=\s*SILENCE_RMS\b", body) is not None,
            "noiseFloor is no longer re-seeded per session, so one session's "
            "loud-room floor can gate the next session shut")

    for field in ('\\"noiseFloor\\":', '\\"vad\\":'):
        require(field in body,
                f"the session record no longer carries {field.strip(chr(92))}: a "
                "session that heard nothing has to be diagnosable from evidence")

    # Resuming from pause must not hand the VAD back a blind history.
    pause = re.search(r"if \(listeningPaused\) \{(.*?)\n\s*\}", body, re.S)
    require(pause is not None, "the pause branch is gone")
    if pause is not None:
        require("feedAndDetect" in pause.group(1),
                "the pause branch stops feeding the VAD: a hole in its input is a "
                "wrong answer rather than a neutral one, and resuming would hand "
                "the detector a blind stretch")

    return list(bad)


failures = check(VM)
if failures:
    print("FAIL mic_gate")
    for f in failures:
        print("   -", f)
    sys.exit(1)

MUTANTS = {
    "gate requires level AND VAD silence (whispering dies)":
        lambda s: s.replace("if (quiet && vadSilent != false)",
                            "if (quiet)", 1),
    "an absent VAD reads as silence (engine goes deaf)":
        lambda s: s.replace("SherpaVad.feedAndDetect(fresh)?.not() else null",
                            "SherpaVad.feedAndDetect(fresh)?.not() ?: true", 1),
    "the floor loses its lower bound":
        lambda s: s.replace("maxOf(SILENCE_RMS, rms(floorProbe) * 3f)",
                            "rms(floorProbe) * 3f", 1),
    "the floor is never re-seeded between sessions":
        lambda s: s.replace("noiseFloor = SILENCE_RMS\n        sessionVad = false",
                            "sessionVad = false", 1),
    "the record stops carrying the VAD flag":
        lambda s: s.replace('.append(",\\"vad\\":").append(sessionVad)', '.append("")', 1),
    "pause stops feeding the VAD":
        lambda s: s.replace("if (vadReady) SherpaVad.feedAndDetect(fresh)\n", "", 1),
}

survived = []
for label, mutate in MUTANTS.items():
    try:
        mutated = mutate(VM)
    except Exception as exc:                       # pragma: no cover
        survived.append(f"{label}: mutation raised {exc!r}")
        continue
    if mutated == VM:
        survived.append(f"{label}: mutation changed nothing")
        continue
    if not check(mutated):
        survived.append(f"{label}: SURVIVED")

if survived:
    print("FAIL mic_gate (assertions that cannot fail)")
    for s in survived:
        print("   -", s)
    sys.exit(1)

print("PASS mic_gate")
