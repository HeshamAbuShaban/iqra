#!/usr/bin/env python3
"""Score the lock policy across a whole recitation corpus, checkpointing per surah.

Runs the real sherpa-onnx recogniser over each surah and scores the app's lock
policy, appending one JSON line per completed surah. That is the whole point of
the file existing: the run is long, so it must never be all-or-nothing. Kill it
at any moment and the report holds every surah already scored.

Why this corpus exists: the lock policy had been calibrated on 28 ayat from six
short surahs - 0.45% of the Quran - and every one of those surahs has between 3
and 7 ayat. A defect found at that scale (the gated long jump firing on Al-Fatiha
1:3, whose units are a subsequence of 1:1's) is exactly the kind that a corpus
of short surahs cannot rule out elsewhere.

Cost: the recogniser runs at ~0.123x realtime on this machine, so the 26.2 hours
of audio in a full-Quran corpus is ~3.2 hours of compute. Nothing here touches
the network once the audio is on disk.

Results are NOT a pass/fail gate. A failure on a corpus whose reciter differs
from the one the app streams by default is ambiguous: it can be a policy defect
or a model/reciter mismatch. This reports; it does not accuse.

Run:  engine/.venv-replay/bin/python engine/replay/run_corpus.py
      engine/.venv-replay/bin/python engine/replay/run_corpus.py --status
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPLAY = ROOT / "engine" / "replay"
sys.path.insert(0, str(REPLAY))

import lock_trace as L  # noqa: E402

CORPUS = ROOT / "engine" / "corpus"
RAW = CORPUS / "raw"
DUMPS = CORPUS / "out"
REPORT = CORPUS / "report.jsonl"
AUDIO = ROOT / "engine" / "audio"          # the pinned Husary baseline
WEIGHTS = ROOT / "engine" / "shootout" / "weights" / "zipformer"

SURAH_SECONDS = 3.2 / 26.23               # corpus share of the 3.2 h estimate


def log(msg):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def ayah_counts():
    """Authoritative per-surah ayah counts, from the bundled mushaf text."""
    vs = json.load(open(ROOT / "android/app/src/main/assets/quran.json",
                        encoding="utf-8"))
    n = {}
    for v in vs:
        n[v["surah"]] = n.get(v["surah"], 0) + 1
    return n


def transcode(src: Path, dst: Path) -> bool:
    """16 kHz mono f32le - exactly what dump_tokens.py reads."""
    if dst.exists() and dst.stat().st_size > 32000:      # >=2s of audio
        return True
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(".part")
    r = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", str(src),
         "-ar", "16000", "-ac", "1", "-f", "f32le", str(tmp)],
        capture_output=True, text=True)
    if r.returncode != 0 or not tmp.exists() or tmp.stat().st_size < 32000:
        log(f"  TRANSCODE FAILED {src.name}: {r.stderr.strip()[:200]}")
        tmp.unlink(missing_ok=True)
        return False
    tmp.rename(dst)
    return True


def dump_tokens(src: Path, dst: Path) -> bool:
    if dst.exists() and dst.stat().st_size > 1000:
        return True
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_suffix(".part")
    cmd = [sys.executable, str(REPLAY / "dump_tokens.py"),
           "--out", str(tmp), "--model", str(WEIGHTS / "model.int8.onnx"),
           "--tokens", str(WEIGHTS / "tokens.txt"), str(src)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0 or not tmp.exists():
        log(f"  DUMP FAILED {src.name}: {r.stderr.strip()[:200]}")
        tmp.unlink(missing_ok=True)
        return False
    tmp.rename(dst)
    return True


def score(dump_path: Path, surah: int, n_ayat: int, table, tok):
    """One surah: run the policy and summarise it, keeping the failing frames."""
    dump = json.load(open(dump_path, encoding="utf-8"))
    plan = [(surah, n_ayat)]
    exp = L.build_exp(table, tok, plan)
    res = L.run_dump(dump, plan, exp=exp, table=table, tok=tok)

    moves = res.moves
    # An expected forward walk visits 1..n in order. Anything else is a defect
    # candidate: a reversal, a jump over an ayah, or a stall.
    forward = [m for m in moves if m.direction > 0 and m.reason != "handoff"]
    backward = [m for m in moves if m.direction < 0]
    jumps = [m for m in moves if m.reason == "jump"]
    handoffs = [m for m in moves if m.reason == "handoff"]

    # The lock starts ON ayah 1, so a clean walk produces moves to 2..n. The
    # first ayah is never "moved to", and counting it made every surah look
    # non-sequential.
    expected_seq = list(range(2, n_ayat + 1)) if n_ayat > 1 else []
    actual_seq = [m.to_ayah for m in forward]
    sequential = actual_seq == expected_seq
    reached = res.final_ayah

    # Stalls: a gap of more than 25s (100 polls) between consecutive moves means
    # the lock sat still for a long time. On a slow reciter that is legitimate,
    # so this is reported, never failed.
    stall_gaps = []
    for a, b in zip(moves, moves[1:]):
        gap = b.t - a.t
        if gap > 25.0:
            stall_gaps.append({"after_ayah": a.to_ayah, "gap_sec": round(gap, 1)})

    # The trailing stall - audio after the LAST move - was invisible, because
    # the loop above only measures gaps *between* moves. Surah 55 sat on one
    # ayah for the final 99 seconds of a 530 s surah and the report scored it as
    # having no stalls at all. A stall that runs to end of file is the one that
    # most needs reporting.
    trailing = None
    if moves:
        trailing = round(dump["audio_sec"] - moves[-1].t, 1)

    # Why the lock stopped, when it stopped: how long it was pinned, and how
    # often it was sitting in the dead band [weak, advance) where the forward
    # gate and the jump gate are both shut.
    stuck = getattr(res, "stuck", [])
    dead = sum(1 for x in stuck if x.get("in_dead_band"))

    # Classify each jump: was the ayah it skipped actually recited?
    #
    # A jump is non-sequential BY DEFINITION, so a correct jump - one over an
    # ayah the reciter genuinely skipped - was being counted as a failure. Four
    # of the six jumps the first full run produced were the policy working.
    # Plain coverage cannot tell the cases apart (it returns 1.00 for the
    # skipped ayah in 5 of 6, because the units do appear scattered across
    # neighbours), so this uses the contiguous read plus a null baseline of the
    # same ayah over same-length windows elsewhere in the surah.
    #
    # Borderline cases are reported as AMBIGUOUS with their margin rather than
    # forced to one side. Surah 8 sits at 0.59 against a 0.60 threshold, and
    # pretending that is a verdict would be the same mistake as the one this
    # whole function exists to remove.
    bad_frames = []
    if not sequential:
        # Report the moves that deviate from a clean walk, with their coverage.
        for m in moves:
            if m.reason in ("jump", "backward") or (
                    m.direction > 0 and m.reason not in ("forward-strong", "forward-pending")):
                bad_frames.append({
                    "t": round(m.t, 2), "from": f"{m.from_surah}:{m.from_ayah}",
                    "to": f"{m.to_surah}:{m.to_ayah}", "reason": m.reason,
                    "coverage": round(m.coverage, 3),
                })

    JUMP_ABSENT, JUMP_RECITED, JUMP_AMBIGUOUS = 0.40, 0.60, 0.15
    # `jumps` holds Move objects (__slots__, no item assignment); the dicts to
    # annotate are bad_frames, which are populated just above.
    for mv in bad_frames:
        if mv.get("reason") == "jump":
            mv["jump_verdict"] = "UNKNOWN"
    if any(mv.get("reason") == "jump" for mv in bad_frames):
        by_t = sorted(moves, key=lambda m: m.t)
        for idx, mv in enumerate(bad_frames):
            if mv.get("reason") != "jump":
                continue
            fs, fa = (int(x) for x in str(mv["from"]).split(":"))
            ts, ta = (int(x) for x in str(mv["to"]).split(":"))
            skipped = list(range(fa + 1, ta))
            if not skipped or fs != ts:
                continue
            t = mv["t"]
            # bad_frames is a FILTERED subset of moves, so its index is not the
            # move's index - indexing by_t with it picked an unrelated pair of
            # moves and produced an empty window, which left every jump
            # UNKNOWN. Find the actual position instead.
            pos = next((k for k, m in enumerate(by_t)
                        if abs(m.t - t) < 1e-6 and m.reason == "jump"), None)
            if pos is None:
                continue
            prev_t = by_t[pos - 1].t if pos > 0 else max(0.0, t - 10.0)
            next_t = by_t[pos + 1].t if pos + 1 < len(by_t) else t + 10.0
            span = max(1.0, next_t - prev_t)
            win = [e["symbol"] for e in dump["emissions"]
                   if prev_t <= e["audio_sec"] <= next_t]
            # exp is {surah: {ayah: (units, unit_word, nwords)}}; fs == ts here.
            aya = exp.get(fs) or {}
            if not win or skipped[0] not in aya:
                continue
            ref = aya[skipped[0]][0]
            loc = L.local_coverage(win, ref)[0]
            nulls = []
            dur = dump["audio_sec"]
            for k in range(6):
                c0 = (k * 1.7) % max(1.0, dur - span)
                w = [e["symbol"] for e in dump["emissions"]
                     if c0 <= e["audio_sec"] <= c0 + span]
                nulls.append(L.local_coverage(w, ref)[0])
            null = max(nulls) if nulls else 0.0
            mv["jump_skipped"] = [f"{fs}:{a}" for a in skipped]
            mv["jump_local_cov"] = round(loc, 3)
            mv["jump_null_cov"] = round(null, 3)
            if loc < JUMP_ABSENT:
                mv["jump_verdict"] = "GENUINELY-ABSENT"
            elif loc >= JUMP_RECITED and loc > null + JUMP_AMBIGUOUS:
                mv["jump_verdict"] = "RECITED-BUT-MISSED"
            else:
                mv["jump_verdict"] = "AMBIGUOUS"


    return {
        "surah": surah,
        "ayat": n_ayat,
        "audio_sec": round(dump.get("audio_sec", 0), 1),
        "tokens": dump.get("tokens", 0),
        "polls_evaluated": res.polls_evaluated,
        "n_polls": res.n_polls,
        "moves": len(moves),
        "forward": len(forward),
        "backward": len(backward),
        "jumps": len(jumps),
        "handoffs": len(handoffs),
        "oscillations": res.oscillations,
        "sequential": sequential,
        "reached_ayah": reached,
        "complete": reached == n_ayat,
        "stall_gaps": stall_gaps,
        "trailing_stall_sec": trailing,
        "stuck_polls": len(stuck),
        "stuck_dead_band_polls": dead,
        "deviating_moves": bad_frames,
        "wpm": round(res.wpm, 1),
    }


def status():
    if not REPORT.exists():
        log("no report yet")
        return
    rows = [json.loads(l) for l in open(REPORT, encoding="utf-8") if l.strip()]
    ok = sum(1 for r in rows if r["complete"] and r["sequential"])
    log(f"scored {len(rows)} surahs | fully sequential and complete: {ok}")
    for r in sorted(rows, key=lambda x: x["surah"]):
        flag = "ok " if (r["complete"] and r["sequential"]) else ">> "
        log(f"  {flag}{r['surah']:3d}:{r['ayat']:<4d} moves={r['moves']:<4d}"
            f" osc={r['oscillations']} back={r['backward']} jump={r['jumps']}"
            f" reached={r['reached_ayah']}/{r['ayat']}"
            f" sequential={r['sequential']}"
            + (f" stalls={len(r['stall_gaps'])}" if r["stall_gaps"] else ""))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--status", action="store_true", help="print the report and exit")
    ap.add_argument("--first", type=int, default=1)
    ap.add_argument("--last", type=int, default=114)
    ap.add_argument("--rescore", action="store_true",
                    help="re-run only the policy over existing token dumps; "
                         "no ffmpeg and no recogniser")
    ap.add_argument("--source", default="dosari",
                    help="dosari (the local dos_6.zip) or husary (engine/audio)")
    args = ap.parse_args()

    if args.status:
        status()
        return 0

    counts = ayah_counts()
    table = L.load_table()
    tok = L.make_tokenizer(L.load_units())  # re-exported from word_verdicts

    CORPUS.mkdir(parents=True, exist_ok=True)
    already = set()
    if REPORT.exists():
        already = {json.loads(l)["surah"]
                   for l in open(REPORT, encoding="utf-8") if l.strip()}

    # The pinned baseline, if requested, is the app's own six Husary clips.
    if args.source == "husary":
        # Prefer an existing 16 kHz raw; fall back to the mp3 and transcode.
        stems = sorted({int(p.stem) for p in AUDIO.glob("*.raw")}
                       | {int(p.stem) for p in AUDIO.glob("*.mp3")})
        pairs = [(s, counts.get(s, 7)) for s in stems]
    else:
        # The archive has no directory entries, so it extracts FLAT into its
        # own parent - not into a dos_6/ subdirectory. Looking in the
        # subdirectory skipped all 114 surahs without a single message.
        zip_dir = Path("/home/oldbrain_exe/WorkingOn/Use/Quran")
        if not (zip_dir / "001.mp3").is_file():
            log("extracting dos_6.zip (1.4 GB) - one-off")
            CORPUS.mkdir(parents=True, exist_ok=True)
            r = subprocess.run(["unzip", "-q", "-o",
                                "/home/oldbrain_exe/WorkingOn/Use/Quran/dos_6.zip",
                                "-d", str(zip_dir)], capture_output=True, text=True)
            if r.returncode != 0:
                log(f"unzip failed: {r.stderr.strip()[:200]}")
                return 1
            log("extracted")
        pairs = [(s, counts.get(s, 7)) for s in range(args.first, args.last + 1)]

    log(f"corpus={args.source} surahs={len(pairs)} already_scored={len(already)}")
    t0 = time.time()
    done = 0
    for surah, n_ayat in pairs:
        if surah in already:
            continue
        dump_path = DUMPS / f"{surah:03d}.json"

        if args.rescore:
            # Scoring reads only the token dump. Re-running ffmpeg over 26 h of
            # mp3 - let alone the recogniser - to recompute a POLICY decision is
            # pure waste, and the dumps are already on disk. This is how a
            # harness fix gets applied to a finished corpus run.
            if not dump_path.is_file():
                log(f"  {surah:3d}: no dump to rescore at {dump_path}")
                continue
        else:
            if args.source == "husary":
                raw_src = AUDIO / f"{surah:03d}.raw"
                src = raw_src if raw_src.is_file() else AUDIO / f"{surah:03d}.mp3"
            else:
                src = zip_dir / f"{surah:03d}.mp3"
                if not src.is_file():
                    log(f"  {surah:3d}: audio missing at {src}")
                    continue
            # The Husary clips in engine/audio are ALREADY 16 kHz mono f32le,
            # so they must not go through ffmpeg - it rejects a raw stream with
            # no container. The Dosari mp3s do need the transcode.
            if src.suffix == ".raw":
                raw = src
            else:
                raw = RAW / f"{surah:03d}.raw"
                if not transcode(src, raw):
                    continue
            if not dump_tokens(raw, dump_path):
                continue
        try:
            row = score(dump_path, surah, n_ayat, table, tok)
        except Exception as exc:  # noqa: BLE001
            log(f"  SCORE FAILED {surah}: {exc}")
            continue
        with open(REPORT, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(row) + "\n")
            fh.flush()
        done += 1
        el = time.time() - t0
        rate = el / max(done, 1)
        flag = "ok " if (row["complete"] and row["sequential"]) else ">> "
        log(f"  {flag}{surah:3d}:{n_ayat:<4d} moves={row['moves']:<4d}"
            f" osc={row['oscillations']} back={row['backward']}"
            f" jump={row['jumps']} reached={row['reached_ayah']}/{n_ayat}"
            f" seq={row['sequential']}  [{el/60:.0f}m, {rate:.0f}s/surah]")

    log(f"finished: {done} new surahs in {(time.time() - t0)/60:.1f} min")
    return 0


if __name__ == "__main__":
    sys.exit(main())
