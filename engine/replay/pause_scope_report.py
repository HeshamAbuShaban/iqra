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
MAIN_SRC = (UI / "MainActivity.kt").read_text(encoding="utf-8")
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


def live_check(live=LIVE_SRC, vm=VM_SRC, log=LOG_SRC, act=MAIN_SRC):
    del bad[:]

    # ---- pause -------------------------------------------------------------
    set_paused = body_of(vm, r"fun setPaused\b")
    require(set_paused != "", "setPaused not found")
    require("stopRecite" not in set_paused,
            "setPaused calls stopRecite - pause would end the session again")
    require(re.search(r"fun setPaused\(paused: Boolean\)", vm) is not None,
            "setPaused does not take the state it sets. A parameter named for what "
            "the ENGINE should do ('listen') forces every caller to pass a "
            "negation, and the one that did was inverted: pressing Pause asked the "
            "engine to keep listening, matched the state already held, hit the "
            "early return and did nothing at all.")
    require(re.search(r"if \(listeningPaused == paused\) return", set_paused) is not None,
            "setPaused lost its idempotence guard")
    # Shadowing is the quiet version of the same inversion: a local named like
    # the parameter, holding its negation, leaves the signature and the guard
    # reading exactly right while the comparison is against the opposite state.
    require(re.search(r"\bval paused\b", set_paused) is None,
            "setPaused declares a local named `paused`: if it holds the negation, "
            "the signature and the guard both read correctly while every "
            "comparison is against the opposite state")
    require(re.search(r"vm\.setPaused\(!paused\)", live) is not None,
            "the pause control does not pass `!paused`: asking setPaused for the "
            "state already held is a no-op, which is how Pause came to do nothing "
            "on every press")
    require(re.search(r"vm\.setPaused\(paused\)", live) is None,
            "the pause control passes `paused` directly, so pressing it asks for "
            "the state that is already set and the guard swallows the call")

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
    # The verse-ending word IS a word. `isVerseEnd` marks the last real word of
    # an ayah - the one the ayah number is printed after - and stripVerseNumber
    # has already taken the number off its text. Excluding it drops one genuine
    # word per ayah: Al-Fatiha's 29 read as 22, while scopeProgress (which counts
    # verseWords[a].size) stayed on 29. That is the "23 of 29" family of reports
    # reached from the other side, and it is asserted here against the real
    # mushaf so the number cannot drift again.
    for src, label in ((live, "LiveMode"), (body_of(vm, r"fun loadSurah\b"),
                                            "the record writer")):
        code = "\n".join(l.split("//")[0] for l in src.splitlines()
                         if not l.strip().startswith("//"))
        require("!it.isVerseEnd" not in code,
                f"{label} subtracts the verse-ending word from the denominator; it "
                "is a real word that merely carries the ayah number, and dropping it "
                "loses one word per ayah")

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
    require('optInt("words"' in log,
            "PracticeLog no longer reads the `words` field the record writes")
    require(re.search(r"Mushaf\.wordsForSurah", live) is not None,
            "LiveMode lost its pre-session mushaf word count")

    # ---- keep the screen on ------------------------------------------------
    # One owner. Three of them meant the phone dimmed on whichever screen was
    # not thinking about it, and the recitation screen - the one where a dark
    # screen reads as the session ending - was the only one that could not be
    # opted out of.
    require(re.search(r"addFlags\(WindowManager\.LayoutParams\.FLAG_KEEP_SCREEN_ON\)", act)
            is not None,
            "no screen holds FLAG_KEEP_SCREEN_ON, so the phone dims mid-ayah")
    require(re.search(r"clearFlags\(WindowManager\.LayoutParams\.FLAG_KEEP_SCREEN_ON\)", act)
            is not None,
            "the keep-screen-on flag is set but never cleared: the setting could "
            "not be turned off again without leaving the app")
    for src, label in ((live, "LiveMode"), (act, "MainActivity")):
        # Comments stripped first, for the reason the whole file works this way:
        # describing the fix in prose must not satisfy the assertion for it.
        code = "\n".join(l.split("//")[0] for l in src.splitlines()
                         if not l.strip().startswith("//"))
        require("keepScreenOn" not in code,
                f"{label} still sets keepScreenOn on a view; a view-local flag and "
                "a window flag are two owners of one behaviour")

    return list(bad)


# Measured against the shipped mushaf rather than trusted. The denominator has
# to agree with the word list the statuses are keyed by, or "N of M" is two
# populations wearing one label.
import json
_mushaf = json.load(open("android/app/src/main/assets/mushaf.json", encoding="utf-8"))
_al_fatiha = 0
for _pg in _mushaf:
    for _ln in _pg.get("lines", []):
        if _ln.get("type") != "text":
            continue
        for _w in (_ln.get("words") or []):
            if _w["location"].startswith("1:"):
                _al_fatiha += 1
# The traditional word count of Al-Fatiha, and the number the app must agree with.
failures = live_check()
if _al_fatiha != 29:
    failures.append(
        f"the shipped mushaf has {_al_fatiha} words in Al-Fatiha, not the 29 the "
        "denominator is asserted against - the data moved, the assertion did not")
if failures:
    print("FAIL pause_scope_report")
    for f in failures:
        print("   -", f)
    sys.exit(1)

# Each assertion has to be able to fail, or it is decoration. Break one thing at
# a time and confirm exactly one more failure appears.
# (file, label, mutation). The file is explicit, never inferred from the label:
# a mutant aimed at the wrong file changes nothing and reports itself as
# "cannot fail", which is a true statement about the wrong thing.
MUTANTS = [
    ("vm", "pause no longer skips judging",
     lambda s: s.replace("if (listeningPaused) {", "if (false) {", 1)),
    ("vm", "pause check moved below the decoder feed",
     lambda s: s.replace("if (listeningPaused) {", "// moved\n        if (false) {", 1)),
    ("live", "the pause button asks for the state it already holds",
     lambda s: s.replace("vm.setPaused(!paused)", "vm.setPaused(paused)", 1)),
    ("vm", "setPaused returns the inverse of what it is given",
     lambda s: s.replace("fun setPaused(paused: Boolean) {",
                         "fun setPaused(paused: Boolean) { val paused = !paused;", 1)),
    ("live", "amplitude reads a real level while paused",
     lambda s: s.replace("if (paused) 0f else vm.micLevel()", "vm.micLevel()", 1)),
    ("live", "Paused state removed",
     lambda s: s.replace("LiveState.Paused", "LiveState.Idle")),
    ("live", "Paused ranked below the engine states",
     lambda s: s.replace("paused -> LiveState.Paused\n", "", 1)),
    ("live", "scope normalisation removed",
     lambda s: s.replace("if (ayahCount <= 0)", "if (false)", 1)),
    ("live", "scope built from the raw count",
     lambda s: s.replace("(firstAyah until firstAyah + scopeCount)",
                         "(startAyah until startAyah + ayahCount)", 1)),
    ("live", "the live denominator subtracts the verse-ending word",
     lambda s: s.replace("vm.standWordsFor(a).count { it.surah == surah }",
                         "vm.standWordsFor(a).count { it.surah == surah && !it.isVerseEnd }", 1)),
    ("live", "the pre-session mushaf count subtracts the verse-ending word",
     lambda s: s.replace(".count { it.verse in inScope && it.surah == surah }",
                         ".count { it.verse in inScope && it.surah == surah && !it.isVerseEnd }", 1)),
    ("vm", "record stops writing words",
     lambda s: s.replace('.append(",\\"words\\":").append(expected)', '.append("")', 1)),
    ("vm", "record subtracts the verse-ending word",
     lambda s: s.replace("surahAyahWordCount[surah] = byAyah.mapValues { (_, ws) -> ws.size }",
                         "surahAyahWordCount[surah] = byAyah.mapValues { (_, ws) -> ws.count { !it.isVerseEnd } }", 1)),
    ("main", "the window stops holding the screen on",
     lambda s: s.replace(
         "window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)", "Unit", 1)),
    ("main", "a view takes the keep-awake duty back",
     lambda s: s.replace("val themeState =", "keepScreenOn = true\n            val themeState =", 1)),
]

undetected = []
FILES = {"live": LIVE_SRC, "vm": VM_SRC, "main": MAIN_SRC}
for target, label, mutate in MUTANTS:
    original = FILES[target]
    try:
        mutated = mutate(original)
    except Exception as exc:                       # pragma: no cover
        undetected.append(f"{label}: mutation raised {exc!r}")
        continue
    if mutated == original:
        undetected.append(f"{label}: mutation changed nothing - the assertion is "
                          f"matching text the fix does not control")
        continue
    got = live_check(
        mutated if target == "live" else LIVE_SRC,
        mutated if target == "vm" else VM_SRC,
        LOG_SRC,
        mutated if target == "main" else MAIN_SRC,
    )
    if not got:
        undetected.append(f"{label}: SURVIVED - no assertion caught it")

if undetected:
    print("FAIL pause_scope_report (assertions that cannot fail)")
    for u in undetected:
        print("   -", u)
    sys.exit(1)

print("PASS pause_scope_report")
