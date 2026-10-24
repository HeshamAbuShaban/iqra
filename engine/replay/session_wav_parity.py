#!/usr/bin/env python3
"""
The session's own sound: the WAV the phone keeps beside the record must be
the audio the record says it is, and it must never outlive its record.

The record names the audio with a strictly-additive triple - `audioFile`,
`audioStartMs` (wall time of the WAV's FIRST sample, on the same clock as
`wt`/`emMs`), `audioSamples` - and the WAV is written by a tee in
AudioRecorder.append, continuous where the ring is 30 s deep. This check
holds the whole chain honest:

  1. the tee is fed in append, OUTSIDE the ring lock (file I/O must not stall
     a recogniser poll);
  2. the WAV opens before the crash-window header flush and closes before the
     final flush, so `audioSamples` equals the WAV's frames exactly;
  3. prune deletes a record's WAV with the record, and the orphan sweep at
     session start removes crash leftovers WITHOUT touching a WAV whose
     record still exists;
  4. the first-sample stamp is taken at the first write, not at open - the
     mic starts after the session does;
  5. the header is patched on close (a crash leaves sizes at 0, and the
     reader must be told, not handed a silent lie);
  6. wav_check() cross-reads a real WAV against its record - header against
     file size, frames against `audioSamples` - and this file self-tests it
     on a synthesized pair, with faults it must catch.

Run: python3 engine/replay/session_wav_parity.py            (the gate)
     python3 engine/replay/session_wav_parity.py --verify <record>.json [<wav>]
"""

import json
import re
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
VM = ROOT / "android/app/src/main/java/com/iqra/quran/ui/PracticeViewModel.kt"
REC = ROOT / "android/app/src/main/java/com/iqra/quran/audio/AudioRecorder.kt"
WAV = ROOT / "android/app/src/main/java/com/iqra/quran/audio/SessionWavWriter.kt"
MAIN = ROOT / "android/app/src/main/java/com/iqra/quran/ui/MainActivity.kt"
EXPORT = HERE / "replay_export.py"


def code(path):
    out = []
    for l in Path(path).read_text(encoding="utf-8").splitlines():
        s = l.strip()
        if s.startswith("//"):
            continue
        out.append(s.split("//")[0])
    return "\n".join(out)


def wav_check(record, wav_path):
    """Cross-read a session WAV against the record that names it.

    Returns a list of problems; empty means the WAV is the audio the record
    says it is. Refuses rather than guesses: a record with no audioFile is
    only a problem when a WAV sits beside it (an orphan the sweep missed).
    """
    bad = []
    name = record.get("audioFile")
    wav_path = Path(wav_path)
    if not name:
        if wav_path.exists():
            bad.append("a WAV sits beside a record that names no audio")
        return bad
    if not wav_path.exists():
        bad.append("record names audio %s but the file is absent" % name)
        return bad
    b = wav_path.read_bytes()
    if len(b) < 44 or b[0:4] != b"RIFF" or b[8:12] != b"WAVE":
        bad.append("not a RIFF/WAVE file")
        return bad
    riff_size = struct.unpack("<I", b[4:8])[0]
    if riff_size != len(b) - 8:
        bad.append("RIFF size %d != file size - 8 (%d): header unpatched or "
                   "file truncated" % (riff_size, len(b) - 8))
    if b[12:16] != b"fmt ":
        bad.append("no fmt chunk where one is required")
        return bad
    fmt_len = struct.unpack("<I", b[16:20])[0]
    pcm, ch, rate, _brate, _align, bits = struct.unpack("<HHIIHH", b[20:36])
    if pcm != 1:
        bad.append("not PCM (audio format %d)" % pcm)
    if ch != 1:
        bad.append("%d channels; the recorder is mono" % ch)
    if rate != 16000:
        bad.append("sample rate %d; the recorder runs 16000" % rate)
    if bits != 16:
        bad.append("%d bits per sample; the recorder runs 16" % bits)
    data_at = 20 + fmt_len
    if b[data_at:data_at + 4] != b"data":
        bad.append("no data chunk directly after fmt (extension bytes?)")
        return bad
    data_size = struct.unpack("<I", b[data_at + 4:data_at + 8])[0]
    if data_size != len(b) - data_at - 8:
        bad.append("data size %d != actual payload %d" % (data_size, len(b) - data_at - 8))
    frames = (len(b) - data_at - 8) // 2
    samples = record.get("audioSamples")
    if samples is None:
        bad.append("audioFile without audioSamples: the count is the parity")
    elif samples != frames:
        bad.append("record says audioSamples=%d but the WAV holds %d frames"
                   % (samples, frames))
    if not record.get("audioStartMs"):
        bad.append("audioFile without a usable audioStartMs: the WAV cannot "
                   "be aligned to wt")
    return bad


def synth_wav(path, frames, rate=16000, data_size=None, riff_size=None,
              synth_rate=None, channels=None):
    """A WAV shaped like the phone's, with deliberate header faults optional."""
    payload = b"".join(struct.pack("<h", (i % 200) - 100) for i in range(frames))
    hdr = bytearray()
    hdr += b"RIFF" + struct.pack("<I", riff_size if riff_size is not None else 36 + len(payload))
    hdr += b"WAVE" + b"fmt " + struct.pack("<I", 16)
    hdr += struct.pack("<HHIIHH", 1, channels or 1, synth_rate or rate,
                       (synth_rate or rate) * 2, 2, 16)
    hdr += b"data" + struct.pack("<I", data_size if data_size is not None else len(payload))
    Path(path).write_bytes(bytes(hdr) + payload)


def main() -> int:
    args = [a for a in sys.argv[1:]]
    if args and args[0] == "--verify":
        rec = json.load(open(args[1], encoding="utf-8"))
        wav = Path(args[2]) if len(args) > 2 else Path(args[1]).parent / rec.get("audioFile", "")
        bad = wav_check(rec, wav)
        for b in bad:
            print("FAIL:", b)
        if not bad:
            print("ok: %s and its WAV agree" % Path(args[1]).name)
        return 1 if bad else 0

    vm = code(VM)
    rec_src = code(REC)
    wav_src = code(WAV)
    main_src = code(MAIN)
    raw_vm = VM.read_text(encoding="utf-8")
    export_src = EXPORT.read_text(encoding="utf-8")
    bad = []

    checks = [
        # 1. the tee is fed, and fed outside the lock
        ("the tee is fed from append",
         re.search(r"tee\?\.write\(src, n\)", rec_src) is not None),
        ("the tee write is outside the ring lock",
         re.search(r"tee\?\.write\(src, n\)\s*synchronized\(lock\)", rec_src)
         is not None),
        ("the tee is stopped by the recorder that opened it",
         re.search(r"fun stopTee\(\): SessionWavWriter\?", rec_src) is not None),
        # 2. lifecycle order: open before the crash-window flush, close before
        #    the final one. If a sample can land between the final flush and
        #    recorder.stop(), audioSamples stops equaling the WAV's frames and
        #    every pulled record fails --verify.
        ("the WAV opens before the crash-window header flush",
         re.search(r"startSessionTee\(\)[\s\S]{0,2500}?flushSessionRecord\(ended = false\)",
                   vm) is not None),
        ("the WAV closes before the final flush",
         re.search(r"stopSessionTee\(\)[\s\S]{0,200}?flushSessionRecord\(ended = true",
                   vm) is not None),
        ("the record names the audio triple",
         'sb.append(",\\"audioFile\\":\\"")' in raw_vm),
        ("audioSamples reads the live tee or the frozen count",
         re.search(r"live\?\.samples \?: sessionAudioSamples", vm) is not None),
        # 3. deletion: the audio goes with its record, and the sweep never
        #    takes audio a record still names.
        ("prune deletes a record's WAV with the record",
         re.search(r'removeSuffix\("\.json"\) \+ "\.wav"\)\.delete\(\)', vm)
         is not None),
        ("the orphan sweep skips WAVs whose record exists",
         re.search(r'File\(dir, w\.name\.removeSuffix\("\.wav"\) \+ "\.json"\)\.exists\(\)\) continue',
                   vm) is not None),
        # 4. the first-sample stamp is a first-write stamp
        ("the clock anchor is stamped at the first write",
         re.search(r"if \(firstSampleAtMs == 0L\)", wav_src) is not None),
        # 5. the header is patched on close, at both size fields
        ("close patches the data size",
         re.search(r"seek\(40\)", wav_src) is not None),
        ("close patches the RIFF size",
         re.search(r"seek\(4\)", wav_src) is not None),
        # the indicator tells the reciter the mic is being KEPT
        ("the listening row marks capture when the tee is live",
         'if (sessionSound) " · REC"' in MAIN.read_text(encoding="utf-8")),
        # the exporter copies the WAV when present and says so when not
        ("the replay exporter carries the WAV beside the record",
         "shutil.copy(wav_src, out_dir / \"session.wav\")" in export_src),
        ("the replay exporter states a missing WAV rather than hiding it",
         '"missing": wav_name' in export_src),
    ]
    for name, ok in checks:
        if not ok:
            bad.append(name)

    # 6. wav_check on a synthesized pair, plus faults it must catch. The
    #    faults are the point: a checker that cannot fail measures nothing.
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        good_rec = {"audioFile": "s.wav", "audioStartMs": 1792500000000,
                    "audioSamples": 16000, "startedAt": 1792499999000}
        synth_wav(d / "s.wav", 16000)
        if wav_check(good_rec, d / "s.wav"):
            bad.append("wav_check rejects an agreeing pair")

        off_by_one = dict(good_rec, audioSamples=16001)
        if not any("audioSamples=16001" in p for p in wav_check(off_by_one, d / "s.wav")):
            bad.append("wav_check misses a one-frame disagreement")

        # the crash shape: sizes left at 0 while the payload is present
        synth_wav(d / "crash.wav", 100, data_size=0, riff_size=36)
        crash = dict(good_rec, audioFile="crash.wav", audioSamples=100)
        probs = wav_check(crash, d / "crash.wav")
        if not any("unpatched" in p for p in probs) or \
           not any("data size 0" in p for p in probs):
            bad.append("wav_check does not name an unpatched crash header")

        synth_wav(d / "rate.wav", 100, synth_rate=8000)
        rate_rec = dict(good_rec, audioFile="rate.wav", audioSamples=100)
        if not any("sample rate 8000" in p for p in wav_check(rate_rec, d / "rate.wav")):
            bad.append("wav_check misses a sample-rate disagreement")

        if wav_check({}, d / "absent.wav"):
            bad.append("a record with no audio and no WAV must be clean")

        orphan = {}
        synth_wav(d / "orphan.wav", 10)
        if not any("names no audio" in p for p in wav_check(orphan, d / "orphan.wav")):
            bad.append("a WAV beside an audio-less record goes unreported")

    if bad:
        for b in bad:
            print("FAIL:", b)
        return 1
    print("ok: the session WAV is written, named, aligned, and dies with its record")
    return 0


if __name__ == "__main__":
    sys.exit(main())
