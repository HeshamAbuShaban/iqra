#!/usr/bin/env python3
"""Dump the sherpa-onnx streaming token stream for a 16 kHz f32le .raw file.

This is the AUDIO -> TOKENS front end only. All matching, scoring and lock
logic lives in the Kotlin engine-core module, which consumes the JSON this
writes. Nothing about recognition policy is duplicated here.

Mirrors the app's SherpaZipformer config exactly:
  OnlineModelConfig(zipformer2Ctc=..., tokens=..., numThreads=max(1, procs/2))
  OnlineRecognizerConfig(decodingMethod="greedy_search", enableEndpoint=false)

Two cadences are supported so the "decoder starved" hypothesis can be tested:
  --frame-ms 250  replicates the app's 4 Hz poll (one decode per frame)
  --frame-ms  20  high-rate ingest (the Phase 2 fix)

PER-TOKEN PROBABILITY - MEASURED, NOT AVAILABLE
----------------------------------------------
Every emission used to carry `"prob": -1.0`, which looked like a short read
of `ys_probs` but is not: sherpa-onnx 1.13.8 returns an EMPTY ys_probs for
this recogniser. Verified three ways:

  * `recognizer.get_result_all(stream).ys_probs == []` and
    `lm_probs == []` after a full decode, while `tokens` and `timestamps`
    are both populated; `as_json_string()` serialises `"ys_probs": []`.
  * `OnlineRecognizer.from_zipformer2_ctc` documents
    `decoding_method: "The only valid value is greedy_search"`
    (online_recognizer.py:582), so the beam searchers that populate
    ys_probs are not reachable for this model.
  * The C API's streaming result struct `SherpaOnnxOnlineRecognizerResult`
    (c-api.h:403-431) has `text`, `tokens`, `timestamps`, `count`, `json` -
    no probability array at all. Only the OFFLINE result carries
    `ys_log_probs` (c-api.h:1512).

So `"prob"` is now written as JSON `null`, with a top-level
`ys_probs_available: false` and a reason string, instead of a `-1.0` that
reads like a number. Nothing in this repo consumed it.

CONSEQUENCE FOR THE APP (reported, not worked around here): the app gates
WRONG on `wordProb` (PracticeViewModel.kt:968-975). `PhonemeMapper` leaves
wordProb at -1f when probs is empty, and `conf < 0.5f` is then always true,
so the gate can never pass and WRONG is unreachable with this recogniser.
"""
import argparse
import json
import os
import sys
import time

import numpy as np
import sherpa_onnx

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_WEIGHTS = os.path.join(HERE, "..", "shootout", "weights", "zipformer")

YS_PROBS_REASON = (
    "sherpa-onnx returns an empty ys_probs for streaming CTC greedy_search: "
    "the OnlineRecognizerResult C struct has no probability array (only the "
    "offline result does), and from_zipformer2_ctc accepts no decoding_method "
    "other than greedy_search")


def read_raw(path):
    """Read 16 kHz mono float32 little-endian PCM."""
    data = np.fromfile(path, dtype="<f4")
    if data.size == 0:
        raise SystemExit("empty raw file: %s" % path)
    return data


def read_result(recognizer, stream):
    """(tokens, timestamps, ys_probs) for the current hypothesis."""
    toks = list(recognizer.tokens(stream))
    tss = list(recognizer.timestamps(stream))
    probs = list(recognizer.ys_probs(stream))
    return toks, tss, probs


def _prob_at(probs, i):
    """Per-token chosen probability, or None when the recogniser does not
    expose one. Never a sentinel number: -1.0 was read downstream as a real
    score."""
    if i < len(probs):
        return float(probs[i])
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("raw", help="16k f32le .raw file")
    ap.add_argument("--model", default=os.path.join(DEFAULT_WEIGHTS, "model.int8.onnx"))
    ap.add_argument("--tokens", default=os.path.join(DEFAULT_WEIGHTS, "tokens.txt"))
    ap.add_argument("--frame-ms", type=int, default=250)
    ap.add_argument("--out", required=True)
    ap.add_argument("--label", default=None)
    ap.add_argument("--threads", type=int, default=max(1, (os.cpu_count() or 2) // 2))
    ap.add_argument("--require-probs", action="store_true",
                    help="exit non-zero if the recogniser exposes no "
                         "per-token probability")
    args = ap.parse_args()

    samples = read_raw(args.raw)
    total_sec = samples.size / 16000.0

    recognizer = sherpa_onnx.OnlineRecognizer.from_zipformer2_ctc(
        tokens=args.tokens,
        model=args.model,
        num_threads=args.threads,
        decoding_method="greedy_search",
        enable_endpoint_detection=False,  # matches the app: sherpa never auto-resets
    )
    stream = recognizer.create_stream()

    frame_samples = int(16000 * args.frame_ms / 1000)
    fed = 0
    frames = 0
    decode_calls = 0
    ready_calls = 0
    emitted = []          # every token the model ever produced, in order
    last_count = 0
    max_probs_seen = 0
    t0 = time.time()
    last_token_wall = t0

    for start in range(0, samples.size, frame_samples):
        chunk = samples[start:start + frame_samples]
        stream.accept_waveform(16000, chunk)
        fed += chunk.size
        frames += 1

        # Exactly the app's policy: at most ONE decode per poll, only if ready.
        ready_calls += 1
        if recognizer.is_ready(stream):
            decode_calls += 1
            recognizer.decode_stream(stream)
            # The Python binding exposes tokens/timestamps/probs as separate
            # accessors (Kotlin's getResult returns them as one object).
            toks, tss, probs = read_result(recognizer, stream)
            max_probs_seen = max(max_probs_seen, len(probs))
            # enableEndpoint=false => these are the WHOLE hypothesis since the
            # last reset, so novelty is a length comparison.
            if len(toks) != last_count:
                for i in range(last_count, len(toks)):
                    emitted.append(
                        {
                            "i": i,
                            "frame": frames,
                            "audio_sec": round(fed / 16000.0, 3),
                            "symbol": toks[i],
                            "ts": float(tss[i]) if i < len(tss) else None,
                            "prob": _prob_at(probs, i),
                        }
                    )
                last_count = len(toks)
                last_token_wall = time.time()

    # Flush the tail: 0.5s of zeros then a final eager decode, so the last
    # words of the surah are not lost to the streaming right-context.
    stream.accept_waveform(16000, np.zeros(int(0.5 * 16000), dtype=np.float32))
    if recognizer.is_ready(stream):
        decode_calls += 1
        recognizer.decode_stream(stream)
        toks, tss, probs = read_result(recognizer, stream)
        max_probs_seen = max(max_probs_seen, len(probs))
        if len(toks) != last_count:
            for i in range(last_count, len(toks)):
                emitted.append(
                    {
                        "i": i,
                        "frame": frames,
                        "audio_sec": round(fed / 16000.0, 3),
                        "symbol": toks[i],
                        "ts": float(tss[i]) if i < len(tss) else None,
                        "prob": _prob_at(probs, i),
                    }
                )
            last_count = len(toks)
    wall = time.time() - t0

    # Longest gap between consecutive emissions: the "decoder starved" metric.
    gaps = []
    prev = None
    for e in emitted:
        if prev is not None:
            gaps.append(e["audio_sec"] - prev)
        prev = e["audio_sec"]

    out = {
        "label": args.label or os.path.basename(args.raw),
        "raw": os.path.abspath(args.raw),
        "model": os.path.abspath(args.model),
        "frame_ms": args.frame_ms,
        "sample_rate": 16000,
        "audio_sec": round(total_sec, 3),
        "frames": frames,
        "ready_calls": ready_calls,
        "decode_calls": decode_calls,
        "ready_ratio": round(decode_calls / ready_calls, 4) if ready_calls else 0.0,
        "tokens": len(emitted),
        "tokens_per_sec": round(len(emitted) / total_sec, 3) if total_sec else 0.0,
        "max_token_gap_sec": round(max(gaps), 3) if gaps else 0.0,
        "tokens_at_or_after_80pct": sum(1 for e in emitted if e["audio_sec"] >= 0.8 * total_sec),
        "realtime_factor": round(wall / total_sec, 4) if total_sec else 0.0,
        "wall_sec": round(wall, 2),
        "ys_probs_available": max_probs_seen > 0,
        "ys_probs_max_len": max_probs_seen,
        "ys_probs_reason": None if max_probs_seen else YS_PROBS_REASON,
        "emissions": emitted,
    }
    with open(args.out, "w") as f:
        json.dump(out, f, ensure_ascii=False)

    print(
        "%-10s frame=%3dms  tokens=%4d  tps=%5.2f  ready=%.3f  maxgap=%6.2fs  tail20%%=%4d  rtf=%.3f"
        % (
            out["label"],
            args.frame_ms,
            out["tokens"],
            out["tokens_per_sec"],
            out["ready_ratio"],
            out["max_token_gap_sec"],
            out["tokens_at_or_after_80pct"],
            out["realtime_factor"],
        )
    )
    if not out["ys_probs_available"]:
        print("  note: no per-token probability from this recogniser "
              "(ys_probs empty) - every emission has prob=null", file=sys.stderr)
    if args.require_probs and not out["ys_probs_available"]:
        return 2
    if not emitted:
        print("  !! ZERO TOKENS for the whole file", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
