#!/usr/bin/env python3
"""Index-arithmetic tests for AudioRecorder's primitive ring buffer.

The recorder was rewritten from an unbounded boxed ArrayList<Float> to a fixed
ring read by absolute sample index. The wrap-around arithmetic is the easy part
to get subtly wrong, and the failure is silent: audio is duplicated, dropped or
reordered, and the recogniser just looks inaccurate.

Two traps this test exists to avoid, both hit while writing it:

  - Python's `%` is floored and always non-negative, while Kotlin's is
    truncated and keeps the sign of the dividend. Modelling the Kotlin `%` with
    Python's silently accepts broken wrap-around, which is precisely why the
    Kotlin needs its `((x % cap) + cap) % cap` fixup. So the model uses jmod().
  - A negative Python list index wraps from the end, which coincidentally
    produces the same element the broken expression wanted. A real FloatArray
    throws instead, so the index is bounds-checked rather than merely compared.

The ring capacity is parsed out of AudioRecorder.kt so the two cannot drift.

Run:  engine/.venv-replay/bin/python engine/replay/ring_buffer.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
KT = ROOT / "android/app/src/main/java/com/iqra/quran/audio/AudioRecorder.kt"


def jmod(a: int, b: int) -> int:
    """Java/Kotlin `%`: truncated division, result keeps the dividend's sign."""
    r = abs(a) % b
    return r if a >= 0 else -r


class Ring:
    """Mirror of AudioRecorder's storage, same expressions, same order."""

    def __init__(self, cap):
        self.cap = cap
        self.buf = [0.0] * cap
        self.write = 0
        self.filled = 0
        self.total = 0

    def reset(self):
        self.write = 0
        self.filled = 0
        self.total = 0

    def total_count(self):
        return self.total

    def _at(self, i):
        if not 0 <= i < self.cap:
            raise IndexError(
                f"ring index {i} out of range [0,{self.cap}) - the Kotlin "
                "((x % cap) + cap) % cap fixup is required"
            )
        return self.buf[i]

    def append(self, values):
        for v in values:
            self.buf[self.write] = v
            self.write = jmod(self.write + 1, self.cap)
        if self.filled < self.cap:
            self.filled = min(self.cap, self.filled + len(values))
        self.total += len(values)

    def read_since(self, since):
        oldest = self.total - self.filled
        if since < oldest:
            return None
        if since >= self.total:
            return []
        frm = since - oldest
        n = min(self.total - since, self.filled - frm)
        out = [0.0] * n
        i = (jmod(self.write - self.filled + frm, self.cap) + self.cap) % self.cap
        for k in range(n):
            out[k] = self._at(i)
            i = jmod(i + 1, self.cap)
        return out

    def current_samples(self):
        out = [0.0] * self.filled
        i = (jmod(self.write - self.filled, self.cap) + self.cap) % self.cap
        for k in range(self.filled):
            out[k] = self._at(i)
            i = jmod(i + 1, self.cap)
        return out


def parse_cap():
    src = KT.read_text(encoding="utf-8")
    m = re.search(r"private val cap = sampleRate \* (\d+)", src)
    if not m:
        return None
    return 16000 * int(m.group(1))


def main() -> int:
    cap = parse_cap()
    if cap is None:
        print("FAIL: could not read `cap` out of AudioRecorder.kt")
        return 1

    failures = []
    r = Ring(cap)
    ref = []          # every sample ever appended
    cursor = 0        # absolute index already consumed by the session loop

    def consume(label):
        """Mimic the session loop: read everything new since the cursor."""
        nonlocal cursor
        got = r.read_since(cursor)
        if got is None:
            # Overflow: the loop resyncs to now and drops the gap.
            cursor = r.total_count()
            return None
        if got:
            expected = ref[cursor:r.total_count()]
            if got != expected:
                failures.append(f"{label}: readSince returned the wrong samples")
            cursor += len(got)
        return got

    # 1. Below capacity: exact, in order, nothing duplicated.
    for chunk in range(4):
        vals = [len(ref) + 0.5 + i for i in range(1000)]
        r.append(vals)
        ref.extend(vals)
        consume(f"append {chunk}")
    if r.current_samples() != ref:
        failures.append("currentSamples diverged from the reference below capacity")
    if r.total_count() != len(ref):
        failures.append("totalCount diverged from the number of samples captured")

    # 2. A caught-up cursor yields nothing and must not stall the loop.
    if r.read_since(cursor) != []:
        failures.append("a caught-up cursor must return an empty delta, not data")

    # 3. Read the whole retained window from its oldest edge, which is the case
    #    that makes the ring index expression go negative.
    oldest = r.total - r.filled
    if r.read_since(oldest) != ref[oldest:]:
        failures.append("reading from the oldest retained sample was wrong")

    # 4. Overfill: an over-old cursor must be reported, not silently gap-filled.
    vals = [len(ref) + 0.5 + i for i in range(cap + 900)]
    r.append(vals)
    ref.extend(vals)
    got = r.read_since(cursor)
    if got is not None:
        failures.append(
            "an over-old cursor must return null so the caller resyncs, "
            f"got {len(got)} samples"
        )
    else:
        cursor = r.total_count()
    if r.current_samples() != ref[-cap:]:
        failures.append("after overflow currentSamples must equal the newest cap samples")
    # currentSamples() also goes negative after a wrap; make sure it is sane.
    oldest = r.total - r.filled
    if r.read_since(oldest) != ref[oldest:]:
        failures.append("reading from the oldest retained sample after overflow was wrong")

    # 5. Steady state: the cursor must keep up exactly, never ahead.
    for chunk in range(6):
        vals = [len(ref) + 0.5 + i for i in range(4000)]
        r.append(vals)
        ref.extend(vals)
        before = cursor
        got = consume(f"steady {chunk}")
        if got is None:
            cursor = r.total_count()
        elif got != ref[before:before + len(got)]:
            failures.append(f"steady {chunk}: delta diverged from the reference")
    if cursor > r.total_count():
        failures.append("cursor overran totalCount")
    if cursor != r.total_count():
        failures.append(
            f"cursor {cursor} did not keep up with totalCount {r.total_count()}"
        )

    # 6. reset() puts the recorder back to a clean, empty state.
    r.reset()
    if r.total_count() != 0 or r.current_samples() != [] or r.read_since(0) != []:
        failures.append("reset() did not return the recorder to an empty state")
    r.append([1.0, 2.0, 3.0])
    if r.read_since(0) != [1.0, 2.0, 3.0]:
        failures.append("reading after reset() returned the wrong samples")

    print(f"ring capacity       : {cap} samples ({cap / 16000:.0f}s)")
    print(f"samples appended    : {len(ref)}")
    print(f"consumed by cursor  : {cursor}")

    if failures:
        print("\nFAIL")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("\nPASS: ring wrap-around, eviction and resync behave as the loop expects")
    return 0


if __name__ == "__main__":
    sys.exit(main())
