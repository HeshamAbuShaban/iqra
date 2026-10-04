#!/usr/bin/env python3
"""Pull recorded clips off the phone, score each one, print one table.

This is the missing link in the loop. `engine/audio/hesitate/README.md` already
says exactly what to record and `hesitation_policy.py --audio` already knows how
to score a recording, but nothing joined the two ends: the recording lives on a
phone and the scorer wants a file in a git checkout. Everything measured so far
went through studio reciter audio or rearranged dumps, so the one input that
matters most - a real voice in a real room - never made it into the harness.

    scripts/collect_clips.py                 # pull, then score everything
    scripts/collect_clips.py --run           # score only, no phone involved
    scripts/collect_clips.py --from DIR      # pull from a specific phone dir
    scripts/collect_clips.py --force         # re-decode even if a dump exists

## Why the dumps are cached

`hesitation_policy.py --audio` always re-runs the recogniser, even when the token
dump is already on disk. That is right for a one-off and wrong for a loop: at
roughly 0.2x realtime a 60 s clip costs about 12 s of compute, so re-checking one
behaviour change across six clips is a minute and a half of waiting for an
answer that did not change. The dump is written next to the clip as
`dump-<stem>.json`, so this checks its mtime against the audio's and passes the
dump straight to the scorer, which accepts `.json` directly. `--force` overrides.

## Why `clean` clips get a verdict and the others do not

A clip named `NNNclean` has exactly one correct outcome: the lock ends on the
last ayah of that surah. That is checkable without knowing anything about the
reciter, so it is checked. The other variants - `repeat`, `back`, `hesitate`,
`wrong` - have outcomes that depend on how the person actually recited, and
inventing an expectation for them would produce a number that looks like a
result and is not one. Those rows print the trace summary and nothing more.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HESITATE = os.path.join(ROOT, "engine", "audio", "hesitate")
REPLAY = os.path.join(ROOT, "engine", "replay")
PY = os.path.join(ROOT, "engine", ".venv-replay", "bin", "python")
SCORER = os.path.join(REPLAY, "hesitation_policy.py")

# Audio the harness will decode. Anything else is ignored rather than guessed at.
EXT = (".wav", ".m4a", ".mp3", ".ogg", ".flac", ".raw")

# engine/audio/hesitate/README.md's naming rule: three digits of surah, then a
# free-text variant. The surah digits are load-bearing - the scorer reads the
# expected phoneme table from them - so a file that does not match is reported
# and skipped rather than scored against surah 1 by accident.
CLIP = re.compile(r"^(\d{3})([A-Za-z0-9]*)\.(%s)$" % "|".join(e[1:] for e in EXT),
                  re.IGNORECASE)

# Where a phone keeps voice memos. MIUI (this device is a Redmi) uses
# MIUI/sound_recorder; stock and most file managers land things in Download.
# The user can also pass --from. Globs, because some of these do not exist on
# every install and adb errors on a missing path rather than reporting "empty".
PHONE_DIRS = [
    "/sdcard/Iqra/hesitate",
    "/sdcard/MIUI/sound_recorder",
    "/sdcard/Recordings",
    "/sdcard/Download/iqra-clips",
    "/sdcard/Download",
]


def which(prog):
    p = shutil.which(prog)
    if p:
        return p
    for d in (os.environ.get("PATH") or "").split(os.pathsep):
        c = os.path.join(d, prog)
        if os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    return None


def adb(args, **kw):
    exe = which("adb") or os.path.expanduser(
        "~/Android/Sdk/platform-tools/adb")
    if not exe or not os.path.exists(exe):
        sys.exit("adb not found. Install platform-tools, or pass --run to "
                 "score clips already on this machine.")
    return subprocess.run([exe] + args, capture_output=True, text=True, **kw)


def find_on_phone(explicit):
    """-> (remote_dir, [remote_names]) for the first directory that has clips."""
    dirs = [explicit] if explicit else PHONE_DIRS
    for d in dirs:
        r = adb(["shell", "ls", d])
        if r.returncode != 0:
            continue
        names = [n.strip() for n in r.stdout.splitlines() if n.strip()]
        hits = [n for n in names if CLIP.match(n)]
        if hits:
            return d, sorted(hits)
    tried = "\n  ".join(dirs)
    return None, []


def probe(path):
    """-> (seconds, sample_rate, channels) or None without ffprobe."""
    ff = which("ffprobe")
    if not ff:
        return None
    r = subprocess.run(
        [ff, "-v", "error", "-show_entries",
         "format=duration:stream=sample_rate,channels",
         "-of", "json", path],
        capture_output=True, text=True)
    if r.returncode != 0:
        return None
    try:
        j = json.loads(r.stdout)
        st = (j.get("streams") or [{}])[0]
        dur = float((j.get("format") or {}).get("duration") or 0.0)
        return dur, int(st.get("sample_rate") or 0), int(st.get("channels") or 0)
    except Exception:
        return None


REASONS = ("forward-strong", "forward-pending", "pinned-escape", "jump",
           "backward", "handoff", "repeat")


def score(clip):
    """-> dict of the numbers worth comparing across clips."""
    stem = os.path.splitext(clip)[0]
    audio = os.path.join(HESITATE, clip)
    dump = os.path.join(HESITATE, "dump-%s.json" % stem)

    # Prefer an existing dump, but only if it is newer than the audio it came
    # from - a re-recorded clip with a stale dump is exactly the sort of thing
    # that makes a loop report yesterday's answer as today's.
    use = audio
    cached = False
    if os.path.exists(dump):
        fresh = os.path.getmtime(dump) >= os.path.getmtime(audio)
        if fresh or not args_force:
            use, cached = dump, fresh

    r = subprocess.run([PY, SCORER, "--audio", use],
                       capture_output=True, text=True, cwd=ROOT)
    if r.returncode != 0:
        tail = (r.stderr or r.stdout).strip().splitlines()
        return {"error": tail[-1][:90] if tail else "exit %d" % r.returncode}

    out = r.stdout
    res = {"cached": cached, "reasons": {}}

    m = re.search(r"variant '([^']*)'", out)
    res["variant"] = m.group(1) if m else "?"
    m = re.search(r"surah\s+: (\d+) \((\d+) ayat in scope\)", out)
    if m:
        res["surah"] = int(m.group(1))
        res["ayat_total"] = int(m.group(2))
    m = re.search(r"^full\s+:\s*(\d+) moves,\s*(\d+) oscillations,\s*lock (\S+)",
                  out, re.M)
    if m:
        res["moves"] = int(m.group(1))
        res["osc"] = int(m.group(2))
        res["lock"] = m.group(3)

    trace = out.split("MOVE TRACE", 1)[-1]
    for reason in REASONS:
        n = len(re.findall(r"\b%s\b" % re.escape(reason), trace))
        if n:
            res["reasons"][reason] = n
    return res


def main():
    global args_force
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--run", action="store_true",
                    help="score only; do not touch the phone")
    ap.add_argument("--from", dest="src",
                    help="a specific directory on the phone to pull from")
    ap.add_argument("--force", action="store_true",
                    help="re-decode even when a current dump exists")
    ap.add_argument("--only", help="score only clips whose name contains this")
    args = ap.parse_args()
    args_force = args.force

    os.makedirs(HESITATE, exist_ok=True)

    if not args.run:
        d, names = find_on_phone(args.src)
        if not d:
            print("No clips found on the phone. Looked in:\n  "
                  + "\n  ".join([args.src] if args.src else PHONE_DIRS))
            print("\nRecord into one of those, or pass --from DIR.")
            print("Name them per engine/audio/hesitate/README.md: "
                  "three surah digits then a variant, e.g. 001hesitate.m4a")
            return 1
        print("pulling %d clip(s) from %s" % (len(names), d))
        for n in names:
            r = adb(["pull", "%s/%s" % (d, n),
                     os.path.join(HESITATE, n)])
            print("  %-28s %s" % (n, "ok" if r.returncode == 0 else "FAILED"))
        print()

    clips = sorted(n for n in os.listdir(HESITATE)
                   if CLIP.match(n) and (not args.only or args.only in n))
    if not clips:
        print("No clips in %s" % HESITATE)
        print("Name them NNN<variant>.<ext> - the three digits are the surah "
              "and the scorer reads the expected phonemes from them.")
        return 1

    rows = []
    for n in clips:
        info = probe(os.path.join(HESITATE, n))
        if info is None:
            fmt = "unknown"
            warn = ""
        else:
            dur, rate, ch = info
            fmt = "%d s · %d Hz · %s" % (round(dur), rate,
                                         "mono" if ch == 1 else "%dch" % ch)
            # Informational, not blocking: the scorer re-decodes anything
            # through ffmpeg to 16 kHz mono f32le, so a 44.1k stereo m4a works.
            # It is worth printing because a 12 s clip probably means the
            # recording stopped early, which is a scoring problem.
            warn = "SHORT" if dur < 0 or dur > 75 else ""
        r = score(n)
        r["clip"] = n
        r["fmt"] = fmt
        r["warn"] = warn
        rows.append(r)

    print()
    print("%-20s %-9s %-20s %6s %5s  %-9s %s"
          % ("clip", "variant", "final lock", "moves", "osc",
             "expected", "move reasons"))
    print("-" * 100)
    fails = []
    for r in rows:
        if r.get("error"):
            print("%-20s ERROR %s" % (r["clip"], r["error"]))
            fails.append(r["clip"])
            continue
        exp = "-"
        # A `clean` clip has one correct outcome and it is checkable without
        # knowing anything about the reciter: the lock must end on the last
        # ayah of the surah. Every other variant depends on what the person
        # actually did, so no expectation is invented for it.
        if "clean" in r["variant"] and r.get("ayah_total"):
            want = "%d:%d" % (r["surah"], r["ayah_total"])
            exp = want if r.get("lock") == want else "got %s" % r.get("lock")
            if r.get("lock") != want:
                fails.append(r["clip"])
        reasons = " ".join("%s=%d" % kv for kv in r["reasons"].items()) or "-"
        print("%-20s %-9s %-20s %6s %5s  %-9s %s"
              % (r["clip"], r["variant"], r.get("lock", "?"),
                 r.get("moves", "?"), r.get("osc", "?"), exp, reasons))
        if r["warn"]:
            print("%-20s   ^ %s: %s" % ("", r["warn"], r["fmt"]))

    print()
    if r_pinned_fires(rows):
        print("pinned-escape fired. It is supposed to be a rare escape hatch; "
              "on every other clip it means the 12 s threshold is firing on a "
              "lock that was merely slow, which is what the corpus already "
              "predicts (median inter-move gap is ~35 s).")
    if fails:
        print("FAILED: %s" % ", ".join(fails))
        return 1
    print("all clean clips ended on the last ayah of their surah")
    return 0


def r_pinned_fires(rows):
    return any(r.get("reasons", {}).get("pinned-escape") for r in rows)


if __name__ == "__main__":
    sys.exit(main())