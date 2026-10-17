#!/usr/bin/env python3
"""
The record must say WHERE a word was blamed, or the blame cannot be argued with.

The whole-corpus sweep and the device disagree about which words are accused - the
first word of an ayah, 42% in the harness against 10% on the phone - and neither
figure can say where the mismatch sits. A boundary artefact and a wrong phoneme
produce the same verdict. `wu` in the record is the fraction of the word's own
units at which the contradiction begins; this check keeps the writer, the reader
and the bucketing honest.

Four things, each with a fault that must turn it red:

  1. PracticeViewModel writes `wu` for a WRONG word, and only for one.
  2. PhonemeMapper exposes the fraction it is derived from.
  3. device_blame.py buckets a synthetic record correctly.
  4. The buckets do not collapse: first unit, middle and last unit are distinct.

Run: python3 engine/replay/device_blame_parity.py
"""

import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
VM = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt"
MAP = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ml/PhonemeMapper.kt"
TOOL = HERE / "device_blame.py"


def code(path):
    out = []
    for l in Path(path).read_text(encoding="utf-8").splitlines():
        s = l.strip()
        if s.startswith("//"):
            continue
        out.append(s.split("//")[0])
    return "\n".join(out)


def main() -> int:
    vm = code(VM)
    mp = code(MAP)
    bad = []

    raw = VM.read_text(encoding="utf-8")
    checks = [
        ("the record writes a blame position for WRONG words",
         re.search(r'append\(",\\"wu\\"', vm) is not None),
        ("the value is only read for a WRONG verdict",
         re.search(r"WordStatus\.WRONG\s*->\s*wrongStartOf", vm) is not None),
        ("the value is gated to words that were blamed",
         re.search(r"if \(frac >= 0\)", vm) is not None),
        ("the fraction is recorded when the verdict is WRONG",
         re.search(r"wrongStartFrac\.getOrNull\(i\)", vm) is not None),
        ("the fraction is stored under the word key",
         re.search(r"wrongStartOf\[key\] = it", vm) is not None),
        # Verdict latency: the user asks for colour "at the exact time I finish
        # a word", and nothing recorded when a word was actually decided, so the
        # complaint could not be checked at all.
        ("the record times each word's first verdict",
         re.search(r'append\(",\\\"wt\\\":"', vm) is not None),
        ("the time is taken when the verdict first becomes terminal",
         re.search(r"sessionTerminalAt\[key\] = System\.currentTimeMillis\(\) - sessionStartedAtMs", vm) is not None),
        ("a word that is re-judged does not get a new time",
         re.search(r"if \(prev != WordStatus\.CORRECT && prev != WordStatus\.WRONG\) \{[\s\S]{0,400}?sessionTerminalCount\+\+[\s\S]{0,400}?sessionTerminalAt\[key\]", vm) is not None),
        # WHAT was heard. `wu` says where in a word the blame landed and `wt`
        # says when; neither says what the model emitted, and that absence is
        # what left 71% of a real noisy session's false accusations unnamed.
        # Plain substring tests, not regex: the Kotlin source spells these keys
        # with escaped quotes, and a regex over that is a thing that can silently
        # match nothing while the check reports green.
        ("the record carries the emitted phonemes",
         'append(",\\"emissions\\":[")' in raw),
        ("the emitted stream is indexed by an absolute base",
         'append(",\\"emissionBase\\":")' in raw),
        ("the per-ayah arrivals are written",
         'append(",\\"arrivals\\":{")' in raw),
        ("the stream is bounded by the live log's cap",
         "val cap = EMISSION_LOG_CAP" in raw),
        # The arrivals must be filtered to the retained log. Without that filter
        # the replay filled a missing neighbour with a default, produced a window
        # spanning the entire session, and "explained" 10 of 14 false accusations
        # with 1,200 symbols of the wrong ayah. A record that cannot rebuild its
        # own window must refuse, not invent.
        ("the arrivals are filtered to the retained log",
         re.search(r"if \(v < emissionBase \|\| v > logEnd\) continue", raw) is not None),
        # The span ends the trim reads must survive into the record, or the
        # replay rebuilds only the RAW window while the app judged the TRIMMED
        # one - a charge made against audio the trim removed could never be
        # replayed, which is what left the 2:2:2 charge in the 16 October
        # session unrecoverable ("all five units match in the wide window").
        ("the per-ayah span ends are written",
         'append(",\\"spans\\":{")' in raw),
        ("the span ends come from the map the trim reads",
         "for ((k, v) in ayahSpanEnd)" in raw),
        ("the span ends are filtered to the retained log",
         "if (v < emissionBase || v > logEnd) continue" in
         raw[raw.find('append(",\\"spans\\":{")'):raw.find(',\\"words\\":[')]),
        ("the reader applies the recorded span end",
         re.search(r"spans\.get\(", TOOL.read_text(encoding="utf-8")) is not None),
        ("the reader refuses a window it cannot rebuild",
         re.search(r"no arrival in the retained log", TOOL.read_text(encoding="utf-8")) is not None),
        # the source of the value
        ("PhonemeMapper declares the field on Alignment",
         re.search(r"val wrongStartFrac: FloatArray", mp) is not None),
        ("the fraction is derived inside align, not guessed later",
         re.search(r"wrongStartFrac\[owner\]\s*=\s*pos", mp) is not None),
    ]
    for name, ok in checks:
        if not ok:
            bad.append(name)

    # 3. the reader: a synthetic record passes through the tool correctly
    rec = {
        "v": 1, "words": [
            {"key": "18:2:1", "st": "WRONG", "wu": 0},
            {"key": "18:2:5", "st": "WRONG", "wu": 500},
            {"key": "18:2:9", "st": "WRONG", "wu": 1000},
            {"key": "18:3:2", "st": "WRONG", "wu": -1},
            {"key": "18:4:1", "st": "CORRECT", "wt": 8123},
            {"key": "18:4:2", "st": "CORRECT", "wt": 9600},
            {"key": "18:4:3", "st": "WRONG", "wu": 1000},
        ],
        "emissionBase": 10,
        "emissions": ["نَ", "م", "قِ"],
        "arrivals": {"18:1": 10, "18:2": 10, "18:3": 11, "18:4": 11, "18:5": 13},
    }
    with tempfile.TemporaryDirectory() as d:
        p = Path(d) / "s.json"
        p.write_text(json.dumps(rec, ensure_ascii=False), encoding="utf-8")
        r = subprocess.run([sys.executable, str(TOOL), str(p)], capture_output=True, text=True)
        out = r.stdout
        if r.returncode != 0:
            bad.append("device_blame.py failed on a synthetic record: " + r.stderr[-140:])
        else:
            if "WRONG words with a recorded blame position: 5" not in out:
                bad.append("the tool does not count the WRONG words it was given")
            # 4. the buckets must not collapse
            if "first unit" not in out or "middle" not in out or "last unit" not in out:
                bad.append("first unit, middle and last unit collapse into one bucket")
            if "no blame recorded" not in out:
                bad.append("a WRONG word with no recorded position is not reported")
            if "Verdict latency" not in out or "median" not in out:
                bad.append("the tool does not report the verdict latency")
            if "8123" not in out:
                bad.append("the recorded verdict time is not shown")
            if "Emission stream" not in out:
                bad.append("the tool does not report the emitted phonemes")
            if "نَ" not in out:
                bad.append("the emitted symbols are not shown, so a WRONG word's "
                           "cause cannot be named from a record")

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: the record says where a word was blamed, and the reader buckets it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
