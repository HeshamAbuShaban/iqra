#!/usr/bin/env python3
"""Pull session records off the phone and summarise what the lock actually did.

The app writes one JSON per session into filesDir/sessions/, flushed every ~25 s
as well as at session start and end. This reads them over adb and prints the
three things that have been impossible to check until now:

  * every lock move, with the reason it happened and the coverage it was made on
  * every stall, and whether the lock was pinned in the dead band while it lasted
  * every WRONG / UNKNOWN word, which is the evidence the colouring was judged on

Stalls are derived from the frame ring rather than from a separate counter,
because that is what a stall IS: consecutive frames where the lock did not move.
A counter that only measured gaps *between* moves reported surah 55's final 99
seconds as "no stalls at all".

Usage:
  engine/.venv-replay/bin/python engine/replay/session_report.py [--list]
  engine/.venv-replay/bin/python engine/replay/session_report.py [index]
"""
import argparse
import json
import re
import subprocess
import sys

PKG = "com.iqra.quran"
DIR = "files/sessions"
ADB = ["adb", "-s", "35d5637e", "shell", "run-as", PKG, "ls", DIR]
STALL_SEC = 25.0


def sh(*args, binary=False):
    r = subprocess.run(list(args), capture_output=True)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def list_sessions():
    out = sh(*ADB)
    names = [n for n in re.findall(r"s-\d+-[^\s]+\.json", out)]
    return names


def read(name):
    raw = sh("adb", "-s", "35d5637e", "shell", "run-as", PKG, "cat", f"{DIR}/{name}",
             binary=True)
    try:
        return json.loads(raw.decode("utf-8", "replace"))
    except Exception as exc:  # noqa: BLE001
        print(f"could not parse {name}: {exc}")
        print(raw[:400])
        return None


def stalls(frames):
    """Contiguous runs where the lock did not move, from the ring itself."""
    out = []
    if not frames:
        return out
    cur = None
    for f in frames:
        s = f.get("s")
        if cur is None or f["t"] - cur["end"] > 1000 or (s, f["lock"]) != (cur["s"], cur["lock"]):
            if cur:
                out.append(cur)
            cur = {"s": s, "lock": f["lock"], "start": f["t"], "end": f["t"]}
        else:
            cur["end"] = f["t"]
    if cur:
        out.append(cur)
    res = []
    for c in out:
        sec = (c["end"] - c["start"]) / 1000.0
        if sec >= STALL_SEC:
            c["sec"] = round(sec, 1)
            res.append(c)
    return res


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--stall-sec", type=float, default=STALL_SEC)
    args = ap.parse_args()

    names = list_sessions()
    if not names:
        print(f"no session records in {DIR} - record one on the phone first")
        return 1
    if args.list or len(sys.argv) == 1:
        print(f"{len(names)} session record(s), newest last:")
        for n in sorted(names):
            print("   ", n)
        return 0

    idx = int(args.list) if isinstance(args.list, int) else None
    name = sorted(names)[idx if idx is not None else -1]
    rec = read(name)
    if not rec:
        return 1

    th = rec.get("thresholds", {})
    c = rec.get("counters", {})
    frames = rec.get("frames", [])
    print(f"=== {name} ===")
    print(f"  build {rec.get('build')}  ended={rec.get('ended')}  "
          f"reason={rec.get('reason') or '-'}")
    print(f"  surah {rec.get('surahStart')} -> {rec.get('surahNow')}:{rec.get('lockNow')}  "
          f"wpmEma={rec.get('wpmEma')}  frames={len(frames)}")
    print(f"  counters: fed={c.get('fedSess')} gateClosed={c.get('gateClosed')} "
          f"moves={c.get('moves')} reversals={c.get('reversals')} "
          f"evaluations={c.get('evaluations')}")
    print("  thresholds: " + " ".join(f"{k}={v}" for k, v in sorted(th.items())))

    print("\n  lock moves (from the diag tail):")
    moves = [l for l in rec.get("diagTail", []) if "lock=" in l]
    if not moves:
        print("     (none recorded)")
    for l in moves[-30:]:
        print("    ", l.strip()[:110])

    st = stalls(frames)
    st.sort(key=lambda x: -x["sec"])
    print(f"\n  stalls >= {args.stall_sec:.0f}s (derived from the frame ring):")
    if not st:
        print("     none")
    for x in st[:10]:
        print(f"     {x['s']}:{x['lock']}  {x['sec']}s")

    ay = rec.get("ayahStatus", [])
    if ay:
        # The "some ayat are not masked" report: SKIPPED renders as untouched, so
        # an ayah low on CORRECT and high on SKIPPED is the shape to look for.
        ay_sorted = sorted(ay, key=lambda x: -(x.get("skipped", 0)))
        print("\n  per-ayah verdicts (most SKIPPED first) - SKIPPED renders unmasked:")
        print(f"     {'ayah':>9} {'correct':>8} {'wrong':>6} {'skipped':>8} {'unknown':>8}")
        for x in ay_sorted[:15]:
            flag = "  <-- mostly unmasked" if x.get("correct", 0) == 0 and x.get("skipped", 0) > 0 else ""
            print(f"     {x['ayah']:>9} {x.get('correct',0):>8} {x.get('wrong',0):>6} "
                  f"{x.get('skipped',0):>8} {x.get('unknown',0):>8}{flag}")

    words = rec.get("words", [])
    if words:
        from collections import Counter
        cnt = Counter(w["st"] for w in words)
        print(f"\n  non-CORRECT words: {dict(cnt)}")
        for w in words[:15]:
            print("     ", w["key"], w["st"])
    else:
        print("\n  non-CORRECT words: none")
    return 0


if __name__ == "__main__":
    sys.exit(main())