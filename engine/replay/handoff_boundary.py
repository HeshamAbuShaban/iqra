#!/usr/bin/env python3
"""Surah handoff, across a real boundary.

`lock_trace.py` has carried this note in its header for some time:

    The surah handoff branch is implemented and ordered correctly, but no dump
    on disk contains a surah boundary, so it is UNEXERCISED.

That is not a theoretical gap. The branch waits for the NEXT surah's first ayah
to be recited before the lock will move, while the reader only turns the page
when the lock moves - so on a page ending exactly on a surah's last ayah the text
handoff is waiting for is never displayed. The user cannot read it, so they
cannot produce it. Reported on device as "it requires me to recite abit from the
new aya of the next page so that it allows the automatic swipe and dosent force
me back", and worse for a memoriser, because the words they would recite from
memory are exactly the ones they cannot see. `run_corpus.py` builds single-surah
plans, so 114 surahs of scoring could not have reached it either.

Two halves, because the defect spans presentation and policy:

POLICY - replayed here on a synthetic dump built from two real surahs.
  * handoff must NOT fire when the next surah's opening was never recited;
  * it MUST fire when it was, and the lock must land on the next surah's 1;
  * the pending forward/back counters are keyed on an AYAH NUMBER, so a stale
    pendingNextFrames from surah A's ayah 2 would otherwise complete on surah B's
    ayah 2 on a single qualifying frame. Both must be cleared across the change.

PRESENTATION - the page has to be able to advance BEFORE the lock does, or the
policy half is unreachable no matter how correct it is. The harness models the
lock, not the page, so this asserts the decoupling rule is present in the
PracticeViewModel rather than pretending to simulate Compose. A missing rule here
is a silent regression of the only part the user can see.

Run: engine/.venv-replay/bin/python engine/replay/handoff_boundary.py
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "engine" / "replay"))

import lock_trace as L  # noqa: E402
from hesitation_policy import ayah_spans  # noqa: E402

VM = ROOT / "android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt"
FRAME = 0.25
# Two adjacent short surahs, both present in the corpus dumps.
SURAH_A, N_A = 93, 11
SURAH_B, N_B = 94, 8


def _emissions_from(spans, ayahs, start_frame):
    """Real emission dicts for these ayahs, renumbered onto a frame timeline.

    The frame counter is threaded through rather than reset per surah: resetting
    it made surah B's emissions reuse surah A's frame numbers, so the two runs
    interleaved and the lock stalled at 93:3. That is the failure mode this test
    exists to prevent, reproduced in its own harness - a test that passes (or
    fails) for the wrong reason is worse than no test, so it was fixed rather
    than tolerated.
    """
    out, frame = [], start_frame
    for a in ayahs:
        for e in spans.get(a, []):
            out.append({"i": len(out), "frame": frame,
                        "audio_sec": round((frame + 1) * FRAME, 3),
                        "symbol": e["symbol"],
                        "ts": round((frame + 1) * FRAME, 3), "prob": None})
            frame += 1
    return out, frame


def build(spans_a, spans_b, include_opening):
    """A's ayat then B's opening. With include_opening=False, B's first ayah is
    replaced by a repeat of A's first ayah, so the recogniser cannot be
    responding to B's text at all."""
    a_em, f = _emissions_from(spans_a, range(1, N_A + 1), 0)
    if include_opening:
        b_em, f = _emissions_from(spans_b, range(1, 3), f)
    else:
        b_em, f = _emissions_from(spans_a, [1, 2], f)
    em = a_em + b_em
    return {"label": "handoff", "frame_ms": 250, "sample_rate": 16000,
            "frames": em[-1]["frame"] + 10, "emissions": em}


def main() -> int:
    pa = ROOT / f"engine/corpus/out/{SURAH_A:03d}.json"
    pb = ROOT / f"engine/corpus/out/{SURAH_B:03d}.json"
    if not (pa.is_file() and pb.is_file()):
        print("SKIP: corpus dumps missing; this needs the model-gated corpus run")
        return 0

    da = json.load(open(pa, encoding="utf-8"))
    db = json.load(open(pb, encoding="utf-8"))
    table = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    spans_a = ayah_spans(da, table, tok, SURAH_A, N_A)
    spans_b = ayah_spans(db, table, tok, SURAH_B, N_B)
    if len(spans_a) < N_A or len(spans_b) < 2:
        print(f"SKIP: could not resolve spans (A={len(spans_a)} B={len(spans_b)})")
        return 0

    plan = [(SURAH_A, N_A), (SURAH_B, N_B)]
    exp = L.build_exp(table, tok, plan)

    def run(dump):
        return L.run_dump(dump, plan, exp=exp, table=table, tok=tok)

    with_open = run(build(spans_a, spans_b, True))
    without = run(build(spans_a, spans_b, False))

    def handoffs(r):
        return [m for m in r.moves if m.reason == "handoff"]

    print(f"synthetic boundary {SURAH_A}:{N_A} -> {SURAH_B}:1, from real emissions")
    print(f"  with B's opening recited : final {with_open.final_lock}  "
          f"handoffs {len(handoffs(with_open))}  moves {len(with_open.moves)}")
    print(f"  with B's opening absent   : final {without.final_lock}  "
          f"handoffs {len(handoffs(without))}  moves {len(without.moves)}")
    for m in handoffs(with_open):
        print(f"      handoff {m.from_surah}:{m.from_ayah} -> "
              f"{m.to_surah}:{m.to_ayah} at t={m.t:.1f}s cov={m.coverage:.3f}")

    if not handoffs(with_open):
        failures.append("handoff never fired even with the next surah's opening "
                        "recited - the branch is still unreachable")
    else:
        h = handoffs(with_open)[0]
        if (h.to_surah, h.to_ayah) != (SURAH_B, 1):
            failures.append(f"handoff landed on {h.to_surah}:{h.to_ayah}, "
                            f"expected {SURAH_B}:1")
    if handoffs(without):
        failures.append("handoff fired WITHOUT the next surah's opening being "
                        "recited, so the surah change is not evidence-gated")
    # Counters must not survive the surah change.
    if handoffs(with_open):
        ht = handoffs(with_open)[0].t
        # Only moves AFTER the handoff. An earlier version compared every move
        # to the handoff time, so all the moves that preceded it had a negative
        # difference, satisfied "< 0.75", and were counted as leaks - failing a
        # run that was actually clean.
        after = [m for m in with_open.moves
                 if m.reason in ("forward-strong", "forward-pending")
                 and 0 <= (m.t - ht) < 0.75]
        if after:
            failures.append(
                f"{len(after)} forward move(s) within 0.75 s of the handoff - a "
                "pending counter survived the surah change, which is exactly the "
                "bug the counter reset in the handoff branch prevents")

    failures = []

    # ---- the surah-being-left gate, which is what the device never had
    #
    # The policy above proves a handoff CAN fire when the next surah's opening is
    # recited. It says nothing about the gate added after the phone reported
    # `handoff -> s=2:1 coverage=0.63` firing 35 s into Al-Fatiha: that gate
    # requires the surah being LEFT to be judged complete in its own window. So
    # it gets the same treatment as the page-advance half - the constant, the
    # condition that uses it, and the intent escape are each asserted, because a
    # gate whose constant exists but is unused passes a substring check while the
    # rule is dead.
    src0 = VM.read_text(encoding="utf-8")
    src0_nc = re.sub(r"//[^\n]*", "", src0)
    print()
    print("  outgoing-surah gate:")
    gates = {
        "HANDOFF_SURAH_DONE is defined":
            re.search(r"HANDOFF_SURAH_DONE = [0-9.]+f", src0_nc) is not None,
        "the condition combines completeness with intent":
            "val surahDone = byIntent || hereDone >= HANDOFF_SURAH_DONE" in src0_nc,
        "completeness is measured in the surah's OWN window":
            "lastAyahObsCoverage(activeSurah, lastAyah)" in src0_nc,
        "intent only applies to the NEXT surah":
            re.search(r"val byIntent = intentHandoffAyah == activeSurah \+ 1", src0_nc) is not None,
        "intent needs a frame streak, not one frame":
            "handoffFrames >= if (byIntent) INTENT_FRAMES else HANDOFF_FRAMES" in src0_nc,
        "the handoff clears arrivals for the new surah":
            "ayahArrival.clear()" in src0_nc.split("if (next != null && surahDone)")[-1][:2600],
        "the handoff clears the stale anchor":
            "pendingAnchor = null" in src0_nc.split("if (next != null && surahDone)")[-1][:2600],
        "a user page turn can arm intent":
            "fun onUserPageTurn(" in src0_nc,
    }
    for name, ok in gates.items():
        print(f"      {name}: {'yes' if ok else 'NO'}")
        if not ok:
            failures.append(
                f"PracticeViewModel: the outgoing-surah gate is incomplete - "
                f"{name}. Without it a surah change can fire while the surah "
                "being left is still being recited, which is what the reciter "
                "had to fight.")

    # And the intent path must be reachable ONLY from a user turn.
    ma = ROOT / "android/app/src/main/java/com/iqra/quran/ui/MainActivity.kt"
    ma_nc = re.sub(r"//[^\n]*", "", ma.read_text(encoding="utf-8"))
    if "if (byUser) vm.onUserPageTurn(page)" not in ma_nc:
        failures.append(
            "MainActivity: onUserPageTurn is not gated on a user drag, so the "
            "app's own scrolls arm the intent handoff and a surah can change "
            "without the reciter asking.")

    # ---- presentation half
    src = VM.read_text(encoding="utf-8")
    src_nc = re.sub(r"//[^\n]*", "", src)
    # Fault injection exposed the weak version of this: deleting the CALL SITE
    # left the helper function defined, so a substring check still passed while
    # the rule was gone and the helper was dead code. So this requires the call
    # with its surah argument, not merely the name.
    has_call = re.search(r"firstPageOfSurah\(\s*activeSurah\s*\+\s*1\s*\)", src_nc) is not None
    # Why this no longer greps for a variable called `atScopeEnd`: that name was
    # deleted when it turned out to be a copy of `atSurahEnd`, and the check
    # failed for a name rather than for behaviour - the same weakness its own
    # comment describes below. What matters is that the block is guarded on the
    # SURAH boundary and on there being a next surah, either of which being
    # absent means the page never turns.
    has_gate = ("atSurahEnd" in src_nc
                and re.search(r"lockedAyah\s*>=\s*verseWords\.size", src_nc) is not None
                and re.search(r"atSurahEnd\s*&&\s*activeSurah\s*<\s*114", src_nc) is not None)
    advances_before_lock = has_call and has_gate
    print(f"  page advances before the lock at a surah boundary: "
          f"{'yes' if advances_before_lock else 'NO'}")
    if not advances_before_lock:
        failures.append(
            "PracticeViewModel has no page-advance at the surah boundary. The "
            "policy half can then never fire in the app: the reader will not turn "
            "to the next surah until the lock moves, and the lock will not move "
            "until the next surah is recited.")

    if failures:
        print("\nFAIL")
        for f in failures:
            print("  -", f)
        return 1
    print("\nPASS: handoff is evidence-gated, fires on a real boundary, carries "
          "no stale counters, and the reader can show what it is waiting for")
    return 0


if __name__ == "__main__":
    sys.exit(main())