# ANSWERED - and not in the way this asked

## What this asked for

A `micBands()` accessor on the view model, on the grounds that the engine's sample
buffers are private and no spectrum can be read from the UI side.

That premise was wrong, and asking for it was the mistake.

## What was actually needed

Nothing from the engine. The audio reaches the poll loop as `fresh`, on its way to the
recogniser, and a voice's spectral balance is a property of those samples - not of
who holds them. `micLevel()` is the wrong place to read a spectrum from, but the
samples it summarises were never out of reach; they are simply in transit elsewhere.

So `PracticeViewModel.measureTone(fresh)` splits each poll of audio that is actually
being judged into a low and a high band with a one-pole low-pass, and publishes
`toneHigh: StateFlow<Float>` - the high band's share of the total. The crest's
brightness reads that instead of words-per-minute.

## What this got right anyway

- Measured from `fresh`, not from the recorder. The recorder would add the room, the
  page turn and the silence between ayat, none of which is the reciter's voice.
- On the branch where audio is going to the recogniser, so audio the engine has
  declined to judge has no say in how that voice is drawn.
- Reported as a **ratio**, not two absolute levels. Absolute band energy mostly tracks
  how far the phone is from the mouth, which is the one thing the crest must not
  depict.
- One-pole, not an FFT. It runs on the poll thread - the loop that decides how fast
  audio reaches a model - and a transform there would put its own cost into that
  loop's cadence.
- Amplitude still drives the wave's height. Tone drives only the brightness.

`tone_measurement` holds all five in place, each shown able to fail.

## The standing lesson

The surface available to you is not always the surface the question is about. The
buffers were private; the audio was not.
