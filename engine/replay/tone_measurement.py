#!/usr/bin/env python3
"""
The crest shows tone, and tone is measured rather than guessed.

Until now the crest's brightness followed words-per-minute. That was honest
about being a stand-in - it is commented as one, in the screen - but it is still
a stand-in. RMS is loudness. Two people reciting the same ayah at the same
volume sit at opposite ends of the pitch range and report identical levels, so
loudness cannot tell you whether a voice is high or low, and reading speed is not
a substitute for knowing.

`docs/ASK_mic_bands.md` asked the engine for a `micBands()` accessor, on the
grounds that its sample buffers are private. That was the wrong question. The
audio reaches the poll loop as `fresh` on its way to the recogniser, and a
voice's spectral balance is a property of those samples, not of who holds them -
so no native change was needed for something that was never out of reach.

What is asserted here, because each of these has a plausible wrong version:

  1. Tone is measured from `fresh`, the audio being judged. Measuring from the
     recorder instead would read the room, the page-turn sound and the silence
     between ayat, none of which is the reciter's voice.
  2. It is measured on the branch where audio is actually going to the
     recogniser, not while the gate is shut and not while paused. The crest
     depicts the voice being judged; audio the app has declined to judge has no
     say in how that voice is drawn.
  3. The split is a RATIO of low to high, not two absolute levels. Absolute band
     energy mostly tracks how far the phone is from the mouth, which is the one
     thing the crest should not be depicting.
  4. The filter is one-pole, not an FFT. It runs on the poll thread - the loop
     that decides how fast audio reaches a model - and a transform there would
     put its own cost into that loop's cadence.
  5. Amplitude still drives the wave's height and tone only its brightness.
     Collapsing them back into one reading is how this regressed in the first
     place.
"""
import re
import sys
from pathlib import Path

VM = Path("android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt").read_text(
    encoding="utf-8")
LIVE = Path("android/app/src/main/java/com/iqra/quran/ui/LiveMode.kt").read_text(
    encoding="utf-8")


def code(src):
    return "\n".join(l.split("//")[0] for l in src.splitlines()
                     if not l.strip().startswith("//"))


def check(vm=VM, live=LIVE):
    bad = []

    def need(cond, msg):
        if not cond:
            bad.append(msg)

    v, l = code(vm), code(live)

    need("_toneHigh" in vm and "val toneHigh: StateFlow<Float>" in vm,
         "the view model does not publish a tone reading")

    fn = re.search(r"private fun measureTone\(x: FloatArray\) \{.*?\n    \}",
                   v, re.S)
    need(fn is not None, "measureTone not found")
    if fn:
        body = fn.group(0)
        need("lp += a * (v - lp)" in body,
             "measureTone no longer low-passes with a one-pole filter: a transform "
             "on this thread would put its own cost into the poll cadence")
        need("lp" in body and "v - lp" in body,
             "the high band is not derived as the signal minus its low-pass")
        need(re.search(r"toneHighEma / total", body) is not None,
             "tone is reported as an absolute level rather than a ratio of high to "
             "low; absolute band energy mostly tracks how far the phone is from "
             "the mouth, which is the one thing the crest must not depict")
        need(re.search(r"total > 1e-5f", body) is not None,
             "silence has no tone and the reading must say so rather than report a "
             "ratio of noise")

    # Measured from the samples being judged, not from the recorder. Every call
    # site, and never the declaration - `fun measureTone(x: FloatArray)` is a
    # match for `measureTone(...)` too, and asserting against it checks nothing.
    calls = [m.group(1).strip()
             for m in re.finditer(r"(?<!fun )measureTone\(([^)]*)\)", v)]
    need(calls, "measureTone is never called")
    # Exactly one call site. A second one under a `listeningPaused` guard reads
    # as "measure on pause too" and is invisible to a check that only inspects
    # the argument.
    need(len(calls) == 1,
         f"measureTone has {len(calls)} call sites, not 1: a second one is almost "
         "certainly a paused branch, which is exactly where it must not be")
    for arg in calls:
        need(arg == "fresh",
             f"measureTone is called with {arg!r}, not the audio being judged - the "
             "recorder would add the room, the page turns and the silence between "
             "ayat, none of which is the reciter's voice")

    # On the decoding branch only: after the gate, and after the pause check.
    decoding = re.search(r'_gateReason\.value = "decoding"', v)
    need(decoding is not None and "measureTone(fresh)" in v[decoding.end():decoding.end() + 400],
         "tone is not measured on the branch where audio reaches the recogniser")
    # The one call site must not be guarded. Counting cannot see this: the
    # plausible edit guards the EXISTING call rather than adding a second one, so
    # there is still exactly one.
    site = re.search(r"(?<!fun )measureTone\(", v)
    if site is not None:
        before = v[max(0, site.start() - 90):site.start()]
        need("listeningPaused" not in before and "if (paused)" not in before,
             "the measureTone call sits under a pause guard: the crest must not "
             "reflect a voice the engine has been told to ignore")

    paused_at = v.find("if (listeningPaused)")
    need(paused_at == -1 or (decoding and paused_at < decoding.start()),
         "tone is measured before the pause check: the crest would reflect a voice "
         "the engine has been told to ignore")

    pause = re.search(r"if \(listeningPaused\) \{(.*?)\n                \}", v, re.S)
    need(pause is not None and "measureTone" not in pause.group(1),
         "tone is measured while paused: audio the engine is deliberately not "
         "judging must not decide how that voice is drawn")

    # Amplitude keeps the height; tone only the brightness.
    need(re.search(r"val brightness = remember\(toneHigh, paused\)", l) is not None,
         "the crest's brightness no longer reads tone")
    need("if (paused) 0f else toneHigh" in l,
         "brightness holds the last tone through a pause, so a paused crest "
         "remains lit by a voice the engine has stopped judging")
    need(re.search(r"brightness = brightness,", l) is not None,
         "brightness is not reaching the crest")
    # The whole argument, not a prefix of it: `level = voice * (0.5f + toneHigh)`
    # contains "level = voice" and is precisely the collapse this forbids.
    need(re.search(r"level = voice,", l) is not None,
         "the wave's height is no longer amplitude alone: tone driving height as "
         "well as brightness is the two-properties-become-one this separates them "
         "to prevent")
    need("val toneHigh by vm.toneHigh" in l,
         "LiveMode does not collect the tone reading")
    need(re.search(r"wpm", l) is None,
         "LiveMode still reads words-per-minute: that stand-in is what tone "
         "replaced, and leaving it in makes the old behaviour unreachable rather "
         "than gone")

    return bad


failures = check()
if failures:
    print("FAIL tone_measurement")
    for f in failures:
        print("   -", f)
    sys.exit(1)

MUTANTS = [
    ("live", "brightness goes back to reading speed",
     lambda s: s.replace("val brightness = remember(toneHigh, paused) {\n"
                         "        if (paused) 0f else toneHigh.coerceIn(0f, 1f)\n    }",
                         "val brightness = remember(wpm) {\n"
                         "        ((wpm - 40.0) / 90.0).toFloat().coerceIn(0f, 1f)\n    }", 1)),
    ("live", "tone drives the wave's height too",
     lambda s: s.replace("level = voice,", "level = voice * (0.5f + toneHigh),", 1)),
    ("live", "paused screens keep the last tone",
     lambda s: s.replace("if (paused) 0f else toneHigh.coerceIn(0f, 1f)",
                         "toneHigh.coerceIn(0f, 1f)", 1)),
    ("vm", "tone is measured from the recorder, not the judged audio",
     lambda s: s.replace("measureTone(fresh)", "measureTone(recorder.currentSamples())", 1)),
    ("vm", "tone is measured while paused",
     lambda s: s.replace("                measureTone(fresh)",
                         "                if (listeningPaused) measureTone(fresh)", 1)),
    ("vm", "absolute band energy instead of a ratio",
     lambda s: s.replace("(toneHighEma / total).coerceIn(0f, 1f)", "toneHighEma", 1)),
    ("vm", "the filter stops being one-pole",
     lambda s: s.replace("lp += a * (v - lp)", "lp = v", 1)),
]
FILES = {"live": LIVE, "vm": VM}
survived = []
for target, label, mutate in MUTANTS:
    original = FILES[target]
    mutated = mutate(original)
    if mutated == original:
        survived.append(f"{label}: mutation changed nothing")
        continue
    got = check(mutated if target == "vm" else VM,
                mutated if target == "live" else LIVE)
    if not got:
        survived.append(f"{label}: SURVIVED")

if survived:
    print("FAIL tone_measurement (assertions that cannot fail)")
    for s in survived:
        print("   -", s)
    sys.exit(1)

print("PASS tone_measurement")