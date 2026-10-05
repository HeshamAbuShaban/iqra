# Ask for the view model: one method, so the light can answer tone

## What is wanted

The recitation screen's horizon reacts sharply to speech. It reads four signals that
are already public: `micLevel()` (amplitude), `policyLive.vadSpeech`, `policyLive.stallSec`
and `wpmFlow`. Amplitude is loudness. None of it can tell a high voice from a low one.

The brightness of the crest currently follows `wpm` as a stand-in, and the code says
so at the point of use. That is a placeholder for a measurement, not a measurement.

## Why it cannot be done from the UI side

The public audio surface on `PracticeViewModel` is exactly:

```
fun micLevel(): Float          // RMS over the last 8000 samples
fun micSampleCount(): Int      // recorder.sampleCount()
```

There is no sample accessor, so there is no spectrum, so there is no pitch. Reading
`recorder` directly is not possible either - it is private, and the view model is
owned by the other agent.

## What already exists

`micLevel()` shows the buffer is already being read:

```kotlin
fun micLevel(): Float {
    val s = recorder.currentSamples()
    if (s.isEmpty()) return 0f
    // Mean over the samples actually present. Dividing by a fixed 8000
    // under-reported the level by the ratio of missing samples, so a
    // "near-silence" hint could fire on a perfectly healthy mic during the
    // first half-second of a session.
    val tail = s.takeLast(8000)
    ...
}
```

So this is a matter of exposing a second derived quantity from a buffer that is
already in hand, not of adding a capture path.

## Proposed

```kotlin
/**
 * Low and high band energy of the last 200 ms, as fractions in 0..1.
 *
 * Returns [low, high] where low is the energy of a smoothed signal - the body of
 * the voice, its vowels and sonorants - and high is the energy of the first
 * difference of the signal - sibilance and the attack transient.
 *
 * WHY NOT ZERO-CROSSING RATE
 * ZCR is the obvious cheap pitch proxy and it is wrong for this job. Arabic
 * recitation is dense with س، ص، ط and ش, which have high ZCR and no pitch at
 * all, so a ZCR-driven visual would flicker constantly on Arabic specifically -
 * exactly where it has to be stable. Splitting by first-difference energy tracks
 * brightness without tracking fricatives as pitch.
 *
 * WHY NOT AN FFT
 * No allocation, no windowing, no scratch buffer, and no new dependency, for a
 * number that is driving a colour ramp. A four-tap running mean and a first
 * difference is the whole thing.
 *
 * @return [low, high], each 0..1, or [0f, 0f] when there are no samples.
 */
fun micBands(): FloatArray {
    val s = recorder.currentSamples()
    if (s.isEmpty()) return floatArrayOf(0f, 0f)
    val tail = s.takeLast(3200)          // 200 ms at 16 kHz
    var low = 0.0
    var prev = tail[0].toDouble()
    var lp = 0.0
    var hp = 0.0
    for (i in tail.indices) {
        val x = tail[i].toDouble()
        lp = lp * 0.75 + x * 0.25          // 4-tap low pass, roughly
        low += lp * lp
        val d = x - prev
        hp += d * d
        prev = x
    }
    val n = tail.size
    val lo = kotlin.math.sqrt(low / n).toFloat().coerceIn(0f, 1f)
    val hi = kotlin.math.sqrt(hp / n).toFloat().coerceIn(0f, 1f)
    return floatArrayOf(lo, hi)
}
```

Nothing above needs to be taken on trust. If the bands turn out to be too noisy to
drive anything at 14 Hz, say so and the UI will keep the `wpm` stand-in rather than
guessing - a flat ramp from an unvalidated signal is worse than an honest one.

## What the UI does with it, for checking it against

Read once per 70 ms poll, alongside the amplitude that is already being read, into a
`MutableFloatState` so nothing is touched during composition:

- `brightness = high / (low + high + eps)` - drives the crest from the theme's gold
  toward `goldBright`. A low voice keeps the gold; a brighter voice gets the hotter
  edge.
- `onward` (the existing envelope-attack spike) is unchanged, and multiplies on top.
- Smoothed asymmetrically: fast attack, slow release, same as the amplitude, because
  speech has onsets and not offsets.

There is a Settings switch for the light's motion already
(`ReaderPrefs.liveAnimation`), so a still image is available to anyone who wants it.

## Not asked for

Nothing else on `PracticeViewModel`, and no change to `PhonemeMapper`, the lock
policy, the thresholds, or the record format. This is read-only, additive, and one
method.
