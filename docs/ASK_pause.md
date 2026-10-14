# Ask for the view model: a pause that does not end the session

## The bug

`ui/LiveMode.kt` wires the Pause control to `vm.stopRecite("paused")` — the **same call as
End**. So Pause ends the session, flushes the record, releases the `AudioRecord`, closes
the decoder, and the 700 ms auto-finish then navigates to the session report. It cannot be
undone: `startRecite` refuses to run twice (`PracticeViewModel.kt:1959`), and
`sessionArchive` was already cleared on the way in (`:1977`).

## Why there is nothing to reach for instead

- `audio/AudioRecorder.kt` exposes `start` / `stop` / `reset` and **nothing between them**.
  No `pause`, `mute`, or `suspend`.
- `stop()` **releases** the `AudioRecord` (`:90-92`). `start()` calls `reset()`, which
  rewinds the absolute cursor to 0 while `PracticeViewModel.fedAbs` (`:898`) keeps the old
  value — so `readSince(fedAbs)` returns null forever and the loop resyncs every poll
  (`:2091-2097`). A stop/start pause is not heavy, it is **broken**.
- `SherpaVad` has **no `SetThreshold`** in the sherpa-onnx C++ API. Raising it means
  rebuilding the detector, which destroys its timeline — and that continuity is the point
  (`SherpaVad.kt:88-94`).
- `noiseFloor` cannot substitute: the gate is `quiet && vadSilent != false`
  (`:2107`), so a Silero speech detection opens the gate no matter how high the floor.

## What is wanted

One public method plus one guarded branch. Both are read-only with respect to session
state.

```kotlin
/** True while the reader has asked the engine to stop listening. */
@Volatile private var listeningPaused = false
private val _paused = MutableStateFlow(false)
/** True means "session live, nothing being judged". */
val paused: StateFlow<Boolean> = _paused

/**
 * Stop judging without ending the session.
 *
 * Pause used to call stopRecite - the same call as End - so pressing Pause ended the
 * recitation, flushed the record and closed the decoder, and the UI auto-finished
 * 700 ms later. There is no recorder-level pause to use instead: stop() RELEASES the
 * AudioRecord, and start() calls reset(), which rewinds the absolute cursor while fedAbs
 * keeps the old value, so readSince would return null on every poll forever.
 *
 * So the pause is enforced at the only seam that can be: the poll loop's handoff to the
 * recogniser. The loop keeps ticking, the ring keeps filling, the session stays armed,
 * and every poll while paused returns before the decoder is touched.
 *
 * Read-only with respect to session state: sessionActive, sessionGen, lockedAyah,
 * sessionArchive and sessionStatuses are all left exactly as they were, so pausing
 * cannot cost a single verdict already earned. Safe to call repeatedly.
 */
fun setListening(listen: Boolean) {
    val pause = !listen
    if (listeningPaused == pause) return
    listeningPaused = pause
    _paused.value = pause
    diag(if (pause) "listening paused (session live)" else "listening resumed")
}
```

And inside the poll loop, **before** the decoder is fed (`~:2100`):

```kotlin
if (listeningPaused) {
    // Keep the VAD's timeline continuous across the pause. It answers "is there speech
    // NOW", so a hole in its input is a wrong answer rather than a neutral one.
    if (vadReady) SherpaVad.feedAndDetect(fresh)
    _gateReason.value = "paused"
    // gateClosedFrames is deliberately NOT incremented: endSession reports it as
    // gateClosed, where it means "the room was too quiet and the engine dropped it".
    // Counting a reader's deliberate pause there makes a report read as an engine fault
    // the reader did not cause.
    continue
}
```

## Placement matters — one line lower is a bug

The starvation watchdog (`idleSec >= 4`, `~:2186`) lives **inside** `runZipformerFrame`,
gated on `fedThisFrame`. Because the pause branch `continue`s **before** that call, a long
pause cannot trigger the 10-second reset loop, the `resetAudioPipeline()` that stamps
`DECODER_STARVATION`, or the `MAX_STARVATION_RECOVERIES` escalation that tells the user
*"Voice decoder stopped responding… Tap Recite to start a new session"*. Placing the
pause check one line lower would manufacture exactly that fault from the user's thumb.

## What it must NOT touch

`_recording` (kills the loop **and** triggers the UI auto-finish), `sessionGen` (retires
the loop), `sessionActive`, `sessionArchive` (cleared exactly once per session, gated by
`engine/replay/archive_parity.py:131`), `sessionStatuses`, `lockedAyah`, `frameRing`, any
counter, `recorder.stop()`, `closeStream()`, and `followArmedIntentIfIdle()` (which must
stay below the pause check, or pausing could move the lock by following the page).

## Two behaviours worth stating rather than discovering

1. **Pause longer than 30 s** — the ring overflows and the loop takes the existing resync
   path, logging `recorder ring overflow — resynced to sample N`. Benign by construction;
   should not be mistaken for a fault.
2. **First lock move after resume** — `tailBuf` only accumulates on fed frames, so the
   1.5 s tail replay is pre-pause audio. Identical to a natural inter-ayah gap, which the
   pipeline already handles by design.

## Wiring

`LiveMode.kt`: replace the local `paused` state with `vm.paused.collectAsStateWithLifecycle()`,
and the control becomes `onClick = { vm.setListening(!paused) }`. `onRequestMic()` is
dropped there deliberately — permission is already granted mid-session and re-invoking
the launcher risks a second prompt.

## Standing rule

This touches the poll loop, so `docs/CORE_LOGIC.md` should be updated in the same commit,
and the new branch should be fault-injected to confirm it cannot be reached with
`sessionArchive` already cleared.
