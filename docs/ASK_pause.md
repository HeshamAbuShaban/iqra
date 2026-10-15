# ANSWERED - see CORE_LOGIC.md, "Pausing"

## What this asked for

A pause that suspends judging without ending the session, instead of calling
`stopRecite("paused")` - the same call as End, which flushed the record, released the
`AudioRecord`, closed the decoder and auto-finished into the report.

## Why there was nothing to reach for instead

- `audio/AudioRecorder.kt` exposes `start` / `stop` / `reset` and nothing between them.
  `stop()` releases the `AudioRecord`, and `start()` calls `reset()`, which rewinds the
  absolute sample cursor while `fedAbs` keeps its old value - so `readSince` returns
  null for ever. A stop/start pause is not slow, it is broken.
- The VAD cannot be deafened: sherpa-onnx has no `SetThreshold`, and `noiseFloor`
  cannot help because the gate is `quiet && vadSilent != false`.

So the pause is enforced at the poll loop's handoff to the recogniser, which is the
only seam that can carry it. The reasoning, the three rules that make it safe, and the
invariant that pausing cannot cost a verdict already earned are all in `CORE_LOGIC.md`.

## What this doc did not predict

Two bugs, both found later, neither visible in the design above:

1. **The visible state.** The logic was correct while the screen still said "Listening"
   with a moving wave, because the engine genuinely is still listening. A reciter who
   asks for silence was told the opposite by the only screen they were looking at.
   `LiveState.Paused` had to outrank the engine-derived states.

2. **The button did nothing at all.** `setListening(listen: Boolean)` computed
   `pause = !listen` internally, and the call site passed the negation inverted, so
   pressing Pause asked the engine to keep listening, matched the state already held,
   and hit the idempotence guard. Every press. The engine work was never at fault.

Both are held by `pause_scope_report`.

The general lesson is the second one. A correct design document is not evidence that
the thing was built, and a passing unit test on the engine half is not evidence that
the button is wired to it.
