#!/usr/bin/env python3
"""
The decision-model referee pilot: can a "decision model" (JEV / clef-flash /
kev-4b class - prefill-only, calibrated probability per option) second-guess
the engine's WRONG verdicts, given the record's own evidence?

Why this exists. The user's proposal: feed a decision model the inputs and a
set of comparisons, let general knowledge judge. The category's contract is
real and measured (model cards, October 2026): `state` (text/JSON facts) +
`question` + `options` -> one forward pass -> calibrated probability per
option, no free-text generation, no output parsing. That is the right
primitive for "was this charge real?" - better than a chat LLM, because the
answer is a distribution, not prose to parse.

What this tool does. From each pulled session record it extracts every WRONG
word's evidence exactly as device_blame.py sees it - the word's expected
units, the symbols heard in the judged window (verdict instant, app-key
costing), the charge positions, `wu` - and formats each case into the decide
contract. Each case carries a REFERENCE label from our own replay, which is
the ground truth a decision model must agree with before anything else
matters:

  supported        the re-align charges a unit of this word against a real
                   heard symbol inside the word's own window
  unsupported      the window is reconstructable but no unit is charged -
                   the borrow/artefact class (18 of the morning-trio's 18
                   reproduced, yet 5 named no pair; 67:28:14 matched on every
                   unit and was still judged WRONG)
  unreconstructable  the record cannot rebuild the window; the case is
                   excluded, never guessed

The measurable experiment, once a model is served (`--endpoint`): does the
model's "supported" probability separate the two labelled classes? Agreement
against ~100+ labelled charges is the number that decides whether the
category has a future here. The second question (which rule explains a
supported mismatch - gemination halved, harakat, madd length, idgham merge,
waqf, borrow, segmentation) has no ground truth yet; its answers are
qualitative, for a human to judge.

Honest limits, stated where they bite:
  - every model in this class is ENGLISH-validated (kev-4b: non-English
    explicitly out of scope); the state carries Arabic unit symbols, so the
    experiment is exactly the unvalidated case - which is why it must be
    measured, not assumed either way;
  - ~7% of answers flip with option order (JEV card): judge on one order,
    re-check disputes on the other;
  - no endpoint is contacted unless given one; the default run is a DRY
    RUN that builds the labelled case set and prints one verbatim request.

Run:
  python3 engine/replay/decision_referee.py <record.json> [more.json ...]
  python3 engine/replay/decision_referee.py --endpoint http://h:8000 \\
      --api jev <record.json> ...
Cases land in engine/replay/out/decision_referee/cases-<record>.jsonl
"""

import argparse
import json
import sys
import urllib.request
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import device_blame as db  # noqa: E402
import word_verdicts as wv  # noqa: E402

TABLE = (HERE.parents[1] / "android/app/src/main/assets"
         / "word_aligned_phonemes.json")

# The binary question with ground truth. Phrased so "true"/first option is
# the outcome the probability is read against (JEV card guidance).
SUPPORT_QUESTION = (
    "A Quran recitation checker accused this word of being read wrongly. "
    "Given the word's expected sound units and the symbols its recogniser "
    "actually heard in the audio window it judged against, is the accusation "
    "supported by that evidence?"
)
SUPPORT_OPTIONS = [
    ("supported",
     "At least one expected unit contradicts what was heard - a real "
     "mismatch between expected and heard sounds."),
    ("unsupported",
     "The heard symbols match the expected word, or the word's own audio is "
     "absent from the window - the accusation is an engine artefact."),
]

# The qualitative question: which rule explains a supported mismatch.
EXPLAIN_QUESTION = (
    "A word was read wrongly, or differs for a reason the rules of Quranic "
    "recitation or the checker's own limits explain. Which explanation fits "
    "the expected units and heard symbols best?"
)
EXPLAIN_OPTIONS = [
    ("gemination", "A doubled letter (shadda) was read with one half only, "
                   "or a single letter was doubled."),
    ("vowel", "A short vowel (harakat) or nasal ending differs - the "
              "consonant is right, the vowel is not."),
    ("madd", "A lengthening (madd) held for a different legal count."),
    ("merge", "A sound merged across words (idgham / ikhfa / iqlab), so the "
              "expected units never appear separately."),
    ("stop", "The reciter stopped or paused at a legal stop mark."),
    ("letter", "A different consonant was read - a genuine wrong letter."),
    ("borrow", "The heard symbols belong to the neighbouring ayah's audio, "
               "not this word."),
    ("segmentation", "The checker's word segmentation disagrees with the "
                     "printed mushaf for this ayah."),
]


def extract_cases(record):
    """Every WRONG word as a referee case, with the replay's reference label.

    Mirrors device_blame.show_cause's reconstruction - same table, same
    judged_span, same app_key costing - but returns data instead of prose.
    """
    o = record
    ems = o.get("emissions")
    if not ems:
        return [], "no emission stream in this record"
    table = json.load(open(TABLE, encoding="utf-8"))
    try:
        unit_set = set(wv.load_units())
    except FileNotFoundError:
        return [], "no token table fetched: the re-align is refused"
    cases = []
    for w in o.get("words", []):
        if w.get("st") != "WRONG":
            continue
        s_, a_, i_ = (int(v) for v in w["key"].split(":"))
        wlist = table.get("%d:%d" % (s_, a_))
        if not wlist or i_ > len(wlist):
            continue
        lo, hi, why = db.judged_span(o, s_, a_, w.get("wt"))
        if why:
            cases.append({
                "key": w["key"], "wu": w.get("wu"),
                "label": "unreconstructable", "why": why,
                "expected": [], "heard": [], "charges": [],
            })
            continue
        span = ems[lo - o.get("emissionBase", 0):hi - o.get("emissionBase", 0)]
        flat = [u for wd in wlist for u in wd]
        uw2 = [j for j, wd in enumerate(wlist) for _ in wd]
        exp_units = [flat[k] for k in range(len(flat)) if uw2[k] == i_ - 1]
        got = [e for e in span if e in unit_set]
        charges = []
        try:
            matched, wrong, r2q, _e, _h, _nw = wv.align(
                [db.app_key(e) for e in got], [db.app_key(u) for u in flat], uw2)
            for k in range(len(flat)):
                if uw2[k] != i_ - 1:
                    continue
                q = r2q[k]
                heard = got[q] if q is not None and q >= 0 else None
                if wrong[k]:
                    charges.append({"expected": flat[k], "heard": heard})
        except Exception as e:  # the alignment must not fabricate
            cases.append({
                "key": w["key"], "wu": w.get("wu"),
                "label": "unreconstructable", "why": "re-align: %s" % e,
                "expected": exp_units, "heard": got, "charges": [],
            })
            continue
        label = "supported" if any(c["heard"] for c in charges) else "unsupported"
        cases.append({
            "key": w["key"], "wu": w.get("wu"), "label": label,
            "expected": exp_units, "heard": got, "charges": charges,
        })
    return cases, None


def case_state(c):
    """The `state` a decision model is fed: the record's own facts, JSON."""
    return json.dumps({
        "expected_units_of_the_word": c["expected"],
        "heard_symbols_in_the_judged_window": c["heard"],
        "engine_charges": [
            {"expected_unit": ch["expected"],
             "heard_instead": ch["heard"] or "(nothing aligned)"}
            for ch in c["charges"]
        ],
        "blame_position": (
            "first unit of the word" if c["wu"] == 0
            else "last unit of the word" if (c["wu"] or 0) >= 1000
            else "unit %d%% into the word" % ((c["wu"] or 0) / 10)
        ),
    }, ensure_ascii=False)


def ask_jev(endpoint, state, question, options):
    body = json.dumps({
        "kind": "choice", "state": state,
        "question": question,
        "options": [o for _, o in options],
    }).encode("utf-8")
    req = urllib.request.Request(
        endpoint.rstrip("/") + "/v1/decide", data=body,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def ask_clef(endpoint, model, state):
    body = json.dumps({
        "model": model, "state": state,
        "questions": {
            "supported": {"type": "choice",
                          "instructions": SUPPORT_QUESTION,
                          "criteria": {k: d for k, d in SUPPORT_OPTIONS}},
            "explanation": {"type": "choice",
                            "instructions": EXPLAIN_QUESTION,
                            "criteria": {k: d for k, d in EXPLAIN_OPTIONS}},
        },
    }).encode("utf-8")
    req = urllib.request.Request(
        endpoint.rstrip("/") + "/v1/systemone", data=body,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("records", nargs="+")
    ap.add_argument("--endpoint", default=None,
                    help="base URL of a served decision model; without it "
                         "the run is a dry run that builds the case set")
    ap.add_argument("--api", choices=["jev", "clef"], default="jev")
    ap.add_argument("--model", default="Cloudflare/clef-flash")
    ap.add_argument("--limit", type=int, default=0,
                    help="cap cases per record (0 = all)")
    a = ap.parse_args()

    out_dir = HERE / "out" / "decision_referee"
    out_dir.mkdir(parents=True, exist_ok=True)
    totals = Counter()
    example_printed = False

    for path in a.records:
        rec = json.load(open(path, encoding="utf-8"))
        name = Path(path).stem.replace("#", "_")
        cases, why = extract_cases(rec)
        if why:
            print("%s: skipped - %s" % (Path(path).name, why))
            continue
        if a.limit:
            cases = cases[:a.limit]
        labels = Counter(c["label"] for c in cases)
        totals.update(labels)
        out = out_dir / ("cases-%s.jsonl" % name)
        with open(out, "w", encoding="utf-8") as f:
            for c in cases:
                f.write(json.dumps(c, ensure_ascii=False) + "\n")
        print("%s: %d WRONG words -> %s  (%s)"
              % (Path(path).name, len(cases), out.name,
                 ", ".join("%s %d" % kv for kv in labels.most_common())))

        supported = [c for c in cases if c["label"] == "supported"]
        if supported and not example_printed and not a.endpoint:
            c = supported[0]
            print("\nExample of what a served model would be asked "
                  "(state is the record's own evidence):")
            print("  state  = %s" % case_state(c)[:400] + (" ..." if len(case_state(c)) > 400 else ""))
            print("  binary = %r / options %s"
                  % (SUPPORT_QUESTION, [k for k, _ in SUPPORT_OPTIONS]))
            print("  explain= %r / options %s"
                  % (EXPLAIN_QUESTION, [k for k, _ in EXPLAIN_OPTIONS]))
            example_printed = True

        if a.endpoint:
            agree = agree_n = 0
            for c in cases:
                if c["label"] == "unreconstructable":
                    continue
                state = case_state(c)
                try:
                    if a.api == "jev":
                        r = ask_jev(a.endpoint, state, SUPPORT_QUESTION,
                                    SUPPORT_OPTIONS)
                        p_sup = r["probabilities"][0]
                    else:
                        r = ask_clef(a.endpoint, a.model, state)
                        ans = r["answers"]["supported"]
                        keys = list(ans.get("probabilities", {}).keys())
                        p_sup = ans["probabilities"].get("supported") if \
                            isinstance(ans.get("probabilities"), dict) else None
                        if p_sup is None and keys:
                            p_sup = ans.get("confidence")
                    want = c["label"]
                    got = "supported" if (p_sup or 0) >= 0.5 else "unsupported"
                    agree_n += 1
                    agree += got == want
                    c["p_supported"] = p_sup
                    c["model_choice"] = got
                except Exception as e:
                    c["model_error"] = str(e)[:200]
            if agree_n:
                print("  endpoint agreement vs replay labels: %d/%d = %.0f%%"
                      % (agree, agree_n, 100.0 * agree / agree_n))
            errs = sum(1 for c in cases if c.get("model_error"))
            if errs:
                print("  %d cases errored against the endpoint" % errs)
            with open(out, "w", encoding="utf-8") as f:
                for c in cases:
                    f.write(json.dumps(c, ensure_ascii=False) + "\n")

    print("\nAll records: %s" % ", ".join(
        "%s %d" % kv for kv in totals.most_common()))
    if not a.endpoint:
        print("Dry run: no model contacted. Serve one (e.g. JEV via vLLM, "
              "clef-flash via SGLang) and re-run with --endpoint --api to "
              "measure agreement against these labels. The decisive number "
              "is the supported/unsupported split - it is the class the "
              "per-word borrow test and the harakat weighting both need "
              "labelled.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
