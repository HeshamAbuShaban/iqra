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
  5. The record times each retained emission (`emMs`), the trim and the clear
     keep the times in lockstep with the log, and the tool splits the verdict
     lag with them - refusing, not guessing, when they are absent.
  6. The fraction is real, not binary: the writer divides by the word's FULL
     unit count and stamps the record "v":2, and every reader branches on that
     stamp - a v1 1000 stays "the second unit or later", never "the last unit".

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
SR = HERE.parent.parent / "android/app/src/main/java/com/iqra/quran/ui/SessionReport.kt"
# The report's user-facing prose lives in resources since the Arabic
# translation landed; the behaviour under test is "the admission reaches the
# screen", so the check reads the resource file the code points at.
SR_RES = HERE.parent.parent / "android/app/src/main/res/values/strings_report.xml"
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
    sr = code(SR)
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
         re.search(r"val atMs = System\.currentTimeMillis\(\) - sessionStartedAtMs[\s\S]{0,120}sessionTerminalAt\[key\] = atMs", vm) is not None),
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
        # The emission times, so a late colour can be split into a slow engine
        # and a late paint - the unresolved half of the 2.65s floor. The trim
        # and the clear must keep the times in lockstep with the log, or the
        # times index symbols that no longer exist.
        ("the record times each retained emission",
         'append(",\\"emMs\\":[")' in raw),
        ("the times are appended in lockstep with the symbols",
         re.search(r"emissionLog\.add\(symbols\[i\]\)\s*\n\s*emissionMs\.add\(nowMs\)", raw) is not None),
        ("the trim keeps the times in lockstep with the log",
         re.search(r"emissionMs\.clear\(\)\s*\n\s*emissionMs\.addAll\(keptMs\)", raw) is not None),
        ("the clear keeps the times in lockstep with the log",
         re.search(r"fun clearEmissionLog\(\)[\s\S]{0,200}?emissionMs\.clear\(\)", raw) is not None),
        ("the reader splits the verdict lag with the times",
         "o.get(\"emMs\")" in TOOL.read_text(encoding="utf-8")),
        ("the reader refuses a lag it cannot split",
         "cannot be split into engine time and paint time" in TOOL.read_text(encoding="utf-8")),
        # the source of the value
        ("PhonemeMapper declares the field on Alignment",
         re.search(r"val wrongStartFrac: FloatArray", mp) is not None),
        ("the fraction is derived inside align, not guessed later",
         re.search(r"wrongStartFrac\[owner\]\s*=\s*pos", mp) is not None),
        # v2: the fraction is of the word's FULL unit count. The v1 writer
        # counted only the units up to the charged one, so the division was
        # pos/pos and every `wu` came out 0 or 1000 - binary, unable to say
        # where in a word the mismatch began (all ten device records through
        # #258 measured exactly {0: 40, 1000: 71}). The stamp is what lets
        # every reader branch instead of guessing which normalisation wrote
        # the value it is holding.
        ("the record stamps schema v2",
         'sb.append("{\\"v\\":2,' in raw),
        ("the fraction's denominator is the word's full unit count",
         re.search(r"val wrongStartFrac[\s\S]{0,900}?for \(j in flat\.indices\) \{\s*"
                   r"if \(wordOf\[j\] != owner\) continue", mp) is not None),
        ("the old charge-position denominator is gone",
         re.search(r"for \(j in 0\.\.k\)", mp) is None),
        ("the tool branches on the record's version",
         re.search(r'o\.get\("v", 1\) >= 2', TOOL.read_text(encoding="utf-8"))
         is not None),
        # SessionReport renders the bands on device; its v1 branch must say
        # the record cannot place the charge rather than dress a binary 1000
        # up as "the last letter" - the mislabel the 19 October pull caught.
        # The sentence itself moved to strings_report.xml with the Arabic
        # translation, so the behaviour is: the v1 branch points at the
        # resource, and the resource carries the admission.
        ("the report admits a v1 record has no finer position",
         re.search(r'r\.v < 2 -> stringResource\(R\.string\.report_blame_past_first\)', sr) is not None
         and "no finer position" in SR_RES.read_text(encoding="utf-8")),
        ("the report's last-letter band is gated on v2",
         re.search(r"r\.v < 2[\s\S]{0,200}?row\.wu >= 1000", sr) is not None),
        # The report as a testing surface: what was heard near a wrong
        # verdict comes from the record's own emission stream, and the
        # blame-bands card must refuse to claim fractions from a v1 record,
        # whose wu has no finer position than first/past-first.
        ("the report says what was heard near a wrong verdict",
         re.search(r'row\.st == "WRONG" && row\.wt != null && r\.emMs\.isNotEmpty\(\)', sr)
         is not None),
        ("the blame-bands card is gated on v2 records",
         re.search(r"r\.v >= 2 && r\.wordRows\.any \{ it\.st == \"WRONG\" && it\.wu != null \}", sr)
         is not None),
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
            {"key": "18:4:3", "st": "WRONG", "wu": 1000, "wt": 9700},
        ],
        "emissionBase": 10,
        "emissions": ["نَ", "م", "قِ"],
        "emMs": [5000, 5000, 7900],
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
            if ("first unit" not in out or "middle" not in out
                    or "second unit or later" not in out):
                bad.append("the blame buckets collapse into one")
            if "last unit" in out:
                bad.append("the tool still calls wu=1000 the last unit; the "
                           "writer records only 0 or 1000, and 1000 means "
                           "'not the first unit'")
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
            # 18:4:1 was decided at 8123 ms and the last symbol of its judged
            # window (index 12) arrived at 7900 ms: engine lag 223 ms.
            if "Verdict lag" not in out or "223 ms" not in out:
                bad.append("the tool does not split the verdict lag with the "
                           "emission times")
            # The stamps are 5000, 5000, 7900: the only positive gap is 2900.
            if "Feed cadence" not in out or "2900 ms" not in out:
                bad.append("the tool does not report the feed cadence")

        # A record that predates the times must get the refusal, not a crash
        # and not a guessed split.
        rec_old = {k: v for k, v in rec.items() if k != "emMs"}
        p2 = Path(d) / "s_old.json"
        p2.write_text(json.dumps(rec_old, ensure_ascii=False), encoding="utf-8")
        r2 = subprocess.run([sys.executable, str(TOOL), str(p2)], capture_output=True, text=True)
        if r2.returncode != 0:
            bad.append("device_blame.py crashes on a record without emission times")
        elif "cannot be split into engine time and paint time" not in r2.stdout:
            bad.append("a record without `emMs` does not get the refusal it needs")

        # A v2 record: `wu` is now a true fraction of the word's own units.
        # The tool must name the last unit exactly, place a mid-word fraction
        # in its band, and never print the v1 binary caveat or the v1 bucket
        # label for a record that does carry finer positions.
        rec2 = dict(rec, v=2, words=[
            {"key": "18:2:1", "st": "WRONG", "wu": 0},
            {"key": "18:2:5", "st": "WRONG", "wu": 333},
            {"key": "18:2:9", "st": "WRONG", "wu": 1000},
        ])
        p3 = Path(d) / "s_v2.json"
        p3.write_text(json.dumps(rec2, ensure_ascii=False), encoding="utf-8")
        r3 = subprocess.run([sys.executable, str(TOOL), str(p3)], capture_output=True, text=True)
        out3 = r3.stdout
        if r3.returncode != 0:
            bad.append("device_blame.py failed on a v2 record: " + r3.stderr[-140:])
        else:
            if "WRONG words with a recorded blame position: 3" not in out3:
                bad.append("the tool does not count a v2 record's WRONG words")
            if "last unit" not in out3:
                bad.append("a v2 wu=1000 is not named the word's last unit")
            if "early (20-40%)" not in out3:
                bad.append("a v2 mid-word fraction (333) does not land in its band")
            if "no finer position" in out3 or "precision the" in out3:
                bad.append("the v1 binary caveat is printed for a v2 record")
            if "second unit or later" in out3:
                bad.append("the v1 bucket label appears for a v2-only record")

    # The judged window must be the window at the VERDICT instant: the record's
    # final arrivals are later than what the app had heard. Word 18:4:2 was
    # decided at 6000 ms; only the symbols stamped 5000 had arrived then, so
    # the judged window ends at 12, while the final window ends at 13.
    sys.path.insert(0, str(HERE))
    import device_blame as db  # noqa: E402
    rec_d = rec
    lo, hi, why = db.judged_span(rec_d, 18, 4, 6000)
    if why is None and (lo, hi) != (11, 12):
        bad.append("judged_span is not clamped at the verdict instant: got "
                   "%s..%s, expected 11..12" % (lo, hi))
    _lo2, hi2, _w2 = db.judged_span(rec_d, 18, 4)
    if hi2 != 13:
        bad.append("judged_span without a verdict time no longer reports the "
                   "final window")

    # The replay must cost an alignment the way the phone did. The harness
    # refuses a bare/vowelled pair mid-word (position-aware); the app's
    # canonicalId does not (global). Both facts are pinned, because the whole
    # point of app_key is that it is the app's rule and not the harness's.
    if db.wv.unit_match("ن", "نَ"):
        bad.append("the harness now matches a bare/vowelled pair mid-word; it "
                   "is supposed to be the position-aware rule")
    if db.app_key("ن") != db.app_key("نَ"):
        bad.append("app_key does not collapse a bare/vowelled pair; the replay "
                   "would charge a word the phone matched")
    if db.app_key("نَ") == db.app_key("مَ"):
        bad.append("app_key collapses two different consonants")
    m1, w1, *_ = db.wv.align(
        [db.app_key("ن"), db.app_key("مَ")],
        [db.app_key("نَ"), db.app_key("مَ")], [0, 0])
    if not m1[0] or w1[0]:
        bad.append("app_key did not make a mid-word bare/vowelled pair match; "
                   "the re-align still costs it as a substitution")
    m2, w2, *_ = db.wv.align(["ن", "مَ"], ["نَ", "مَ"], [0, 0])
    if not w2[0]:
        bad.append("the raw-cost DP no longer charges the pair, so the "
                   "app_key test proves nothing")
    # The helpers above are worth nothing unless the re-align CALLS them: the
    # first version of this test proved app_key worked while the tool still
    # costed by raw equality. Inspect the call site, not a mention of it.
    tool_src = TOOL.read_text(encoding="utf-8")
    if ("[app_key(e) for e in got]" not in tool_src
            or "[app_key(u) for u in flat]" not in tool_src):
        bad.append("the re-align does not cost through app_key; a raw-cost DP "
                   "charges words the phone matched")

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: the record says where a word was blamed, and the reader buckets it")
    return 0


if __name__ == "__main__":
    sys.exit(main())
