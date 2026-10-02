#!/usr/bin/env python3
"""The pinned-lock escape, proved on the audio that motivated it.

Ar-Rahman 55 deadlocked and nothing in the lock policy could leave the state:

  forward   needs next_cov >= ADVANCE (0.60)
  decay     needs next_cov <  WEAK   (0.40)
  jump      needs here_cov < STUCK   (0.35)
  backward  needs here_cov < STUCK   (0.35)

next_cov sat at 0.375-0.500 while here_cov was pinned at 1.000, so none of them
could ever fire, and the lock stayed on 55:63 for the rest of the surah. The
reason here_cov never fell is the point of the whole thing: 55:63's expected
units are BYTE-IDENTICAL to 55:65's and 55:67's - the refrain takes every other
ayah - so as the reciter moves on, the observation slice keeps containing a
complete copy of the locked ayah. A fully covered lock is exactly what makes it
unrecoverable, because high here_cov is what holds the jump and backward gates
shut.

This runs the same dump with the escape enabled and disabled. With it off the
lock must stall at 63 - reproducing the original defect - and with it on the
surah must complete. Both directions matter: an escape that fires on everything
would pass the second half alone.

It also pins the two properties that keep the escape honest:
  - it only fires from the dead band, where no other branch can act;
  - it steps ONE ayah, never skipping to better-matching evidence.

Needs the corpus dumps, so it SKIPs in CI like the other model-gated checks.

Run: engine/.venv-replay/bin/python engine/replay/pinned_escape.py
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "engine" / "replay"))

import lock_trace as L  # noqa: E402

SURAH, N_AYAT = 55, 78
STALLS_AT = 63          # where the lock froze without the escape


def main() -> int:
    src = ROOT / f"engine/corpus/out/{SURAH:03d}.json"
    if not src.is_file():
        print(f"SKIP: no dump at {src}; this needs the model-gated corpus run")
        return 0
    dump = json.load(open(src, encoding="utf-8"))
    table = L.load_table()
    tok = L.make_tokenizer(L.load_units())
    exp = L.build_exp(table, tok, [(SURAH, N_AYAT)])

    def run(**kw):
        return L.run_dump(dump, [(SURAH, N_AYAT)], policy=L.LockPolicy(**kw),
                          exp=exp, table=table, tok=tok)

    on = run()
    off = run(pinned_escape_sec=0.0)

    print(f"surah {SURAH}, {N_AYAT} ayat, {dump.get('audio_sec', 0):.0f}s audio")
    print(f"  escape OFF: final {off.final_lock}  moves {len(off.moves)}  "
          f"stuck_polls {len(off.stuck)}")
    print(f"  escape ON : final {on.final_lock}  moves {len(on.moves)}  "
          f"escapes {len([m for m in on.moves if m.reason == 'pinned-escape'])}")
    for m in on.moves:
        if m.reason == "pinned-escape":
            print(f"    escape {m.from_ayah} -> {m.to_ayah} at t={m.t:.0f}s "
                  f"(next_cov {m.coverage:.3f})")

    # The premise. If the refrain ever stops being identical the diagnosis
    # above is wrong and this test is asserting the wrong thing.
    refrains_identical = (exp[SURAH][STALLS_AT][0] == exp[SURAH][STALLS_AT + 2][0])

    failures = []
    if not refrains_identical:
        failures.append(
            f"{SURAH}:{STALLS_AT} and {SURAH}:{STALLS_AT + 2} are no longer "
            "identical - the refrain premise of this test has changed")
    if off.final_ayah != STALLS_AT:
        failures.append(
            f"with the escape disabled the lock reached {off.final_lock}, not "
            f"{SURAH}:{STALLS_AT}; this test no longer reproduces the deadlock")
    if on.final_ayah != N_AYAT:
        failures.append(
            f"with the escape enabled the lock reached {on.final_lock}, not "
            f"{SURAH}:{N_AYAT}")
    if len(off.stuck) == 0:
        failures.append("with the escape disabled no stall was recorded, so the "
                        "deadlock is not being detected by the stuck probe")
    for m in on.moves:
        if m.reason == "pinned-escape" and m.to_ayah != m.from_ayah + 1:
            failures.append(
                f"escape moved {m.from_ayah} -> {m.to_ayah}; it must step exactly "
                "one ayah, never skip to better-matching evidence")
    # It must be doing something, and it must be rare: it is an escape hatch,
    # not a normal path. Four escapes across 114 surahs was the corpus figure.
    n_esc = len([m for m in on.moves if m.reason == "pinned-escape"])
    if n_esc == 0:
        failures.append("the escape never fired, so the surah completed by "
                        "another route and this proves nothing")
    if n_esc > len(on.moves) * 0.25:
        failures.append(f"{n_esc} escapes in {len(on.moves)} moves - an escape "
                        "hatch that fires this often is not an escape hatch")

    if failures:
        print("\nFAIL")
        for f in failures:
            print("  -", f)
        return 1
    print("\nPASS: with the escape off the lock freezes at "
          f"{SURAH}:{STALLS_AT}; with it on the surah completes, one step at a "
          "time")
    return 0


if __name__ == "__main__":
    sys.exit(main())