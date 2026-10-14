#!/usr/bin/env python3
"""
Three defects that all had the same shape: a screen that looked right while
meaning something else.

PAUSE, AND WHY IT "DID NOT WORK"

`setListening` suspended judging in the poll loop, and every one of those lines
was correct. The screen still said "Listening" and the wave still undulated,
because the recorder is deliberately left running - that is how a session
survives a pause at all - so `micLevel()` keeps returning real levels and the
engine-derived states keep reporting normally. A reciter tapping Pause was told,
by the status line and by a moving wave, that they were being listened to.

Material3's `Text` has no `textDirection`, so nothing about this is fixable at
the text layer either; the fix is a `LiveState.Paused` that outranks the
engine-derived states, and an amplitude that reads zero rather than a real
level.

THE DEFAULT CHOICE CRASHED THE SCREEN

ChooseScreen sends `count = 0` for "The whole surah" - the chip it opens on.
`startAyah until startAyah + 0` is empty, so `scopeAyahs.last()` in the
auto-finish threw NoSuchElementException on the first frame, and the
denominator was 0. Picking the obvious option crashed before the screen drew.

A DENOMINATOR NOBODY WROTE DOWN

The session record emitted `correct/wrong/skipped/unknown` per ayah and nothing
else, while `PracticeLog` was already reading a `words` field. The report had
nothing to divide by, so it printed the bare judged count: honest, and useless,
because "did I finish this scope" is a question only a denominator can answer.

It must also agree with the live counter. Both exclude the verse-end marker -
the mushaf appends the ayah number to the last word of each ayah, and it is not
a word anyone said - because two numbers called "words" that differ by one per
ayah put the screen and the report permanently out of step.
"""
import re
import sys
from pathlib import Path

UI = Path("android/app/src/main/java/com/iqra/quran/ui")
VM_SRC = (UI / "PracticeViewModel.kt").read_text(encoding="utf-8")
LIVE_SRC = (UI / "LiveMode.kt").read_text(encoding="utf-8")
LOG_SRC = (UI.parent / "data/PracticeLog.kt").read_text(encoding="utf-8")

bad = []


def require(cond, msg):
    if not cond:
        bad.append(msg)


def body_of(src, signature):
    """Source of a function body, comments stripped, so a fix cannot live in
    prose and satisfy a grep."""
    m = re.search(r"(private )?%s.*?\n    \}\n" % signature, src, re.S)
    if not m:
        return ""
    out = []
    for line in m.group(0).splitlines():
        s = line.strip()
        if s.startswith("//"):
            continue
        out.append(s.split("//")[0])
    return "\n".join(out)


def live_check(live=LIVE_SRC, vm=VM_SRC, log=LOG_SRC):
    del bad[:]

    # ---- pause -------------------------------------------------------------
    set_listening = body_of(vm, r"fun setListening\b")
    require(set_listening != "", "setListening not found")
    require("stopRecite" not in set_listening,
            "setListening calls stopRecite - pause would end the session again")

    loop = re.search(r"while \(_recording\.value && gen == sessionGen\) \{", vm)
    require(loop is not None, "the poll loop was not found to place pause in")
    if loop is not None:
        after = vm[loop.end():]
        pause_at = after.find("if (listeningPaused)")
        feed_at = after.find("runZipformerFrame(")
        require(pause_at != -1,
                "the poll loop never checks listeningPaused: pause does not suspend judging")
        require(feed_at != -1, "the poll loop no longer feeds the recogniser")
        if pause_at != -1 and feed_at != -1:
            require(pause_at < feed_at,
                    "the pause check comes AFTER the decoder is fed: a paused poll "
                    "still judges, and a long pause can still trip the starvation "
                    "watchdog and stamp DECODER_STARVATION at the reader's thumb")
            branch = after[pause_at:after.find("}", pause_at)]
            require("continue" in branch,
                    "the pause branch does not `continue`: it falls through into judging")
            require("gateClosedFrames" not in branch,
                    "the pause branch counts gateClosedFrames: a deliberate pause "
                    "would be reported to the user as a room too quiet")

    require("LiveState.Paused" in live,
            "LiveState has no Paused case: a paused screen still says it is listening")
    paused_rank = live.find("paused -> LiveState.Paused")
    vad_rank = live.find("policy.vadSpeech == false -> LiveState.Quiet")
    require(paused_rank != -1 and vad_rank != -1 and paused_rank < vad_rank,
            "LiveState.Paused is derived BELOW the engine states: the microphone is "
            "still open while paused, so the screen would argue with the reciter "
            "who asked for silence")

    mic = re.search(r"vm\.micLevel\(\)", live)
    require(mic is not None, "LiveMode no longer reads micLevel")
    if mic is not None:
        window = live[max(0, mic.start() - 220):mic.start()]
        require("paused" in window,
                "the amplitude still reads a real level while paused: the wave "
                "keeps undulating for audio the engine is deliberately ignoring")

    # ---- scope -------------------------------------------------------------
    require(re.search(r"if \(ayahCount <= 0\)", live) is not None,
            "LiveMode does not resolve ayahCount == 0, so ChooseScreen's default "
            "'The whole surah' leaves the scope empty")
    require(re.search(r"val scopeAyahs\s*=\s*remember\([^)]*\)\s*\{\s*\n\s*\(firstAyah until firstAyah \+ scopeCount\)",
                      live) is not None,
            "scopeAyahs is not built from the normalised scopeCount")
    require("until startAyah + ayahCount" not in live,
            "the raw ayahCount still builds a range: count == 0 makes it empty")

    # ---- denominator -------------------------------------------------------
    writer = body_of(vm, r"fun flushSessionRecord\b")
    require(writer != "", "flushSessionRecord not found")
    # Scoped to the per-ayah object on purpose. The record ALREADY had a
    # top-level `"words":[...]` array (the per-word detail), so a bare grep for
    # `"words":` passes on the unfixed writer - which is precisely the assertion
    # that would have sat green over this bug.
    ayah_obj = re.search(r"for \(\(k, a\) in byAyah\) \{.*?\n        \}", vm, re.S)
    require(ayah_obj is not None, "the per-ayah status loop was not found")
    if ayah_obj is not None:
        obj = ayah_obj.group(0)
        require('\\"words\\":' in obj or '"words":' in obj,
                "the session record still does not write a per-ayah `words` field, "
                "so the report has no denominator to show")
        require("expected" in obj,
                "the per-ayah `words` field is not written from the counted value")
    require("isVerseEnd" in body_of(vm, r"fun loadSurah\b"),
            "the per-ayah word count does not exclude the verse-end marker")
    require('optInt("words"' in log,
            "PracticeLog no longer reads the `words` field the record writes")
    for src, label in ((live, "LiveMode"), (body_of(vm, r"fun loadSurah\b"),
                                            "the record writer")):
        require("isVerseEnd" in src,
                f"{label} counts the verse-end marker as a word; the live counter "
                "and the report would disagree by one per ayah")
    require(re.search(r"Mushaf\.wordsForSurah", live) is not None,
            "LiveMode lost its pre-session mushaf word count")

    return list(bad)


failures = live_check()
if failures:
    print("FAIL pause_scope_report")
    for f in failures:
        print("   -", f)
    sys.exit(1)

# Each assertion has to be able to fail, or it is decoration. Break one thing at
# a time and confirm exactly one more failure appears.
MUTANTS = {
    "pause no longer skips judging": (
        lambda s: s.replace("if (listeningPaused) {", "if (false) {", 1)),
    "pause check moved below the decoder feed": (
        lambda s: s.replace("if (listeningPaused) {", "// moved\n        if (false) {", 1)),
    "pause after the feed, by reordering the branch": (
        lambda s: s.replace("if (listeningPaused) {", "if (false) {", 1)
                   .replace("// PAUSE - suspend judging",
                            "if (listeningPaused) { _gateReason.value = \"paused\" }// PAUSE", 1)),
    "Paused state removed": (
        lambda s: s.replace("LiveState.Paused", "LiveState.Idle")),
    "Paused ranked below the engine states": (
        lambda s: s.replace("paused -> LiveState.Paused\n", "", 1)),
    "amplitude reads a real level while paused": (
        lambda s: s.replace("if (paused) 0f else vm.micLevel()", "vm.micLevel()", 1)),
    "scope normalisation removed": (
        lambda s: s.replace("if (ayahCount <= 0)", "if (false)", 1)),
    "scope built from the raw count": (
        lambda s: s.replace("(firstAyah until firstAyah + scopeCount)",
                            "(startAyah until startAyah + ayahCount)", 1)),
    "record stops writing words": (
        lambda s: s.replace('.append(",\\"words\\":").append(expected)', '.append("")', 1)),
    "record counts the verse-end marker": (
        lambda s: s.replace("ws.count { !it.isVerseEnd }", "ws.size", 1)),
}

undetected = []
for label, mutate in MUTANTS.items():
    target = LIVE_SRC if "Live" in label or "amplitude" in label or "Paused" in label \
        or "scope" in label else VM_SRC
    try:
        mutated = mutate(target)
    except Exception as exc:                      # pragma: no cover
        undetected.append(f"{label}: mutation raised {exc!r}")
        continue
    if mutated == target:
        undetected.append(f"{label}: mutation changed nothing - the assertion is "
                          f"matching text the fix does not control")
        continue
    got = live_check(LIVE_SRC if target is not LIVE_SRC else mutated,
                     VM_SRC if target is not VM_SRC else mutated,
                     LOG_SRC)
    if not got:
        undetected.append(f"{label}: SURVIVED - no assertion caught it")

if undetected:
    print("FAIL pause_scope_report (assertions that cannot fail)")
    for u in undetected:
        print("   -", u)
    sys.exit(1)

print("PASS pause_scope_report")
