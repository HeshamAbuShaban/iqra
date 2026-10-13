package com.iqra.quran.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.data.MushafWord
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.WordStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sin
import com.iqra.quran.ui.theme.LiveColors
import com.iqra.quran.ui.theme.mix
import com.iqra.quran.ui.theme.LocalIqraColors
import com.iqra.quran.ui.theme.LocalLiveColors
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable

/**
 * Recitation test: the verse is hidden, you recite it from memory, and each
 * word resolves as you say it.
 *
 * ## Why the orb is not decoration
 *
 * It is driven by the engine's own live state - `micLevel()` for the amplitude,
 * `policyLive.vadSpeech` for whether anything is being heard, `stallSec` for a
 * lock that has stopped advancing. That is the whole design decision, and it is
 * what keeps the screen calm instead of busy: the motion is a readout, so it
 * cannot contradict what is happening, and it cannot drift out of sync with a
 * state the user can feel but not see.
 *
 * A canned animation here would be worse than nothing, because it would look
 * responsive whether or not the app was hearing anything.
 *
 * ## Why it costs the main feature nothing
 *
 * The amplitude lives in a `mutableFloatStateOf` that is read ONLY inside the
 * orb's draw lambda. Writing it invalidates that one draw scope, so a 60 fps
 * orb repaints a canvas and never recomposes the verse behind it. No state read
 * during composition changes at frame rate.
 *
 * ## What it does about honesty
 *
 * A word the engine could not hear stays UNKNOWN and is drawn faintly. It is
 * never counted against the reciter: the model failing to produce a phoneme is a
 * fact about the model, and 4,116 ayat currently cannot be word-judged at all.
 * See docs/WORD_ALIGNMENT_4116.md.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LiveModeScreen(
    vm: PracticeViewModel,
    data: QuranData,
    surah: Int,
    startAyah: Int,
    ayahCount: Int,
    onRequestMic: () -> Unit,
    onExit: () -> Unit,
    onFinished: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val lcG = LocalIqraColors.current
    val lc = LocalLiveColors.current
    val animOn = remember { ReaderPrefs.liveAnimation(ctx) }
    // Tone needs band energy the engine does not publish - micLevel() is RMS and
    // micSampleCount() is a count, so there is no spectrum to read. Until the
    // view model exposes one, brightness follows pace and the crest's own onset,
    // which is honest about being a stand-in rather than pretending to measure a
    // pitch that is not available.
    val recording by vm.recording.collectAsStateWithLifecycle()
    val statusMap by vm.statusMap.collectAsStateWithLifecycle()
    // The count of words actually judged this session, per ayah, from the durable
    // store rather than the live paint map.
    //
    // This is the fix for "16 of 29 heard" after completing Al-Fatiha. The live map
    // (`statusMap`) is pruned to lockedAyah +/- 2 on every poll - the paint behind
    // the lock is deliberately thrown away, because stale paint can never freeze.
    // So on the last ayah of a surah only ayah 5..9 can survive, which is
    // 4 + 3 + 9 = 16 of Fatiha's 29 words. Every other word was deleted from the
    // map as the lock advanced. That is a truncated trailing window rather than an
    // accuracy figure, and by the same arithmetic Al-Baqarah finishes near 1.7%.
    //
    // `scopeProgress` is already built from `sessionArchive`, the store cf9a2bb
    // added precisely because the paint map forgets. It records every verdict and
    // is never pruned, so summing it sums the session instead of the window.
    val scopeProgress by vm.scopeProgress.collectAsStateWithLifecycle()
    val currentKey by vm.currentKey.collectAsStateWithLifecycle()
    val activeVerse by vm.activeVerse.collectAsStateWithLifecycle()
    val policy by vm.policyLive.collectAsStateWithLifecycle()

    // The lattice breathes on a period coprime with the horizon's, so the two never
    // visibly lock into a shared rhythm.
    val latticeFlow = rememberInfiniteTransition(label = "lattice")
    val driftPhase by latticeFlow.animateFloat(
        0f, 6.2831855f,
        infiniteRepeatable(tween(19000, easing = LinearEasing)),
        label = "latticePhase",
    )

    var started by remember { mutableStateOf(false) }
    // Listening is live from the moment the tray appears - see the tray note.
    var paused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }

    // The verse text, split on whitespace. The mushaf's own per-word boxes are
    // not needed here: this screen counts words and shows them one at a time,
    // and the word INDEX it feeds statusMap with is the index in this list.
    // The denominator is a MUSHARAF word count, from the same list the statuses
    // are keyed by - never a whitespace split of `textClean`, whose token count
    // differs from the mushaf's word index from the first multi-word token
    // onward. Counting one and displaying the other made "N of M heard" compare
    // two different populations.
    //
    // And it is FROZEN, not re-keyed on `wordsVersion`. `wordsVersion` is bumped by
    // loadSurah, and a surah handoff calls loadSurah(surah + 1) - so the old
    // `remember(verses, wordsVersion)` re-read the NEXT surah's words while the
    // header still said "Al-Fatiha . 7 ayat" and the words on screen became
    // Baqarah's. The scope is chosen in ChooseScreen and must not move under the
    // reciter mid-session, so neither the count nor the text keys on it.
    val scopeVerses = remember(surah, startAyah, ayahCount) {
        (startAyah until startAyah + ayahCount).mapNotNull { data.getVerse(surah, it) }
    }
    val verses = scopeVerses
    val totalWords = remember(scopeVerses) {
        scopeVerses.sumOf { vm.standWordsFor(it.ayah).size }
    }

    val amp = remember { mutableFloatStateOf(0f) }
    val voice = remember { mutableFloatStateOf(0f) }
    var startedAt by remember { mutableLongStateOf(0L) }
    // Public on the view model; the engine keeps the raw field private.
    val wpm by vm.wpmFlow.collectAsState()
    val brightness = remember(wpm) { ((wpm - 40.0) / 90.0).toFloat().coerceIn(0f, 1f) }
    // The orb's memory. A plain FloatArray, written by the poll and read inside
    // the draw lambda - never touched during composition, so it costs one
    // canvas redraw rather than a recomposition.
    val history = remember { FloatArray(96) }
    val headState = remember { mutableIntStateOf(0) }
    var head by headState
    // Speech-onset spike. The smoothed level alone is too lazy to feel like a
    // reaction: it rises over ~4 polls, which reads as a swell rather than an
    // answer. Comparing the instant level against a slowly-falling floor finds
    // the moment a syllable actually begins, and that is what the horizon snaps
    // on. Read inside the draw lambda, never in composition.
    val onset = remember { mutableFloatStateOf(0f) }

    // Amplitude poll. 70 ms is about 14 Hz, fast enough that the orb reads as
    // continuous and slow enough that it is 14 wake-ups a second rather than
    // 60. The smoothing is what makes speech look like speech instead of a
    // strobe.
    LaunchedEffect(recording) {
        if (!recording) { amp.floatValue = 0f; return@LaunchedEffect }
        var last = 0f
        var floor = 0.04f
        while (isActive) {
            val raw = withContext(Dispatchers.IO) { vm.micLevel() }
            // 0.12 is roughly where a close-mic voice sits on this recorder's
            // scale; anything above that is treated as "as loud as it gets" so a
            // loud room does not pin the orb open.
            val target = (raw / 0.12f).coerceIn(0f, 1f)
            val next = last + (target - last) * if (target > last) 0.55f else 0.12f
            amp.floatValue = next
            voice.floatValue += (target - voice.floatValue) * 0.25f
            // A little of the old sample bleeds forward so the ring has
            // continuity between polls; without it the shape flickers at 14 Hz
            // instead of undulating.
            // Attack against a floor that creeps down slowly, so a sustained
            // tone cannot keep re-triggering it.
            if (floor > target) floor = target else floor += 0.0035f
            if (target > floor * 2.0f + 0.018f) onset.floatValue = 1f
            onset.floatValue *= 0.86f
            val blended = next * 0.72f + history[(head - 1 + history.size) % history.size] * 0.28f
            history[head] = blended
            head = (head + 1) % history.size
            last = next
            delay(70)
        }
    }

    val state = when {
        !started || finished -> LiveState.Idle
        !recording -> LiveState.Idle
        policy.vadSpeech == false -> LiveState.Quiet
        policy.stallSec > 4f -> LiveState.Thinking
        else -> LiveState.Hearing
    }

    // ---- finish -----------------------------------------------------------
    // A session can only END if it ever began. `started` flips the instant the
    // orb is tapped, while `recording` waits on the permission dialog, so the
    // previous test - started && !recording - was true within a frame of the
    // tap and finished the session before the microphone ever opened.
    var everRecorded by remember { mutableStateOf(false) }
    LaunchedEffect(recording) { if (recording) everRecorded = true }
    LaunchedEffect(everRecorded, recording, startedAt) {
        if (everRecorded && !recording && startedAt > 0L) {
            delay(700)          // let the last verdict land before we judge
            finished = true
        }
    }

    Scaffold(
        containerColor = Color(0xFF05070D),
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { showExit = true }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Leave",
                        tint = Color(0xFF8FA0BE))
                }
                Text(
                    // The name, not the number. "Surah 1" is a lookup; the name
                    // is what the reciter is holding in their head.
                    listOfNotNull(
                        data.surahInfo(surah)?.nameEn,
                        "$ayahCount ${if (ayahCount == 1) "ayah" else "ayat"}",
                    ).joinToString("  ·  "),
                    fontSize = 14.sp,
                    color = Color(0xFF8FA0BE),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.width(48.dp))
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            // Drawn first and anchored to the bottom edge, so the ground above it
            // stays empty. Tapping the light is what starts the session, which is
            // why the gesture lives on the wave and nowhere else.
            GirihLattice(
                colour = lc.lattice,
                phase = if (animOn) driftPhase else 0.6f,
                brighten = (voice.floatValue + onset.floatValue * 0.6f).coerceIn(0f, 1f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
                    // Canvas does not clip to its own bounds, and without this the
                    // pattern ran past its box and into the light.
                    .clipToBounds(),
            )
            HorizonWave(
                lc = lc,
                history = history,
                head = headState,
                level = voice,
                onset = onset,
                brightness = brightness,
                animate = animOn,
                stall = policy.stallSec,
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.46f)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (!started) {
                                startedAt = System.currentTimeMillis()
                                started = true
                                onRequestMic()
                            }
                        }
                    },
            )

            Column(
                Modifier.fillMaxSize().padding(horizontal = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(6.dp))
                Text(
                    when (state) {
                        LiveState.Idle -> if (started) "Listening" else "Tap the light to begin"
                        LiveState.Quiet -> "I can't hear you"
                        LiveState.Hearing -> "Go ahead"
                        LiveState.Thinking -> "Let me think…"
                    },
                    fontSize = 13.sp,
                    color = when (state) {
                        LiveState.Thinking -> lc.crestHot
                        LiveState.Quiet -> Color(0xFF64748B)
                        else -> Color(0xFF8FA0BE)
                    },
                )

                // The passage. Scrollable and given the leftover height, because
                // a test can be a whole surah. No card, no panel, no placeholder
                // text: the words arrive only once they have been said.
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    verses.forEachIndexed { i, v ->
                        val ayah = startAyah + i
                                // The words come from the mushaf, not from a
                                // whitespace split of the uthmani text.
                                //
                                // statusMap is keyed by MushafWord.wordInVerse,
                                // and the mushaf's own word is not always one
                                // whitespace token: ٱلذَّـٰلَّكِ' appears in the
                                // mushaf as two words printed as one, and
                                // 'بَعْدَ مَا' (2:89 w3, 8:6 w4) is one mushaf
                                // word drawn as two. Splitting on spaces
                                // therefore shifts every index from the first
                                // such word onward and lights up the WRONG
                                // WORD - the one failure this app exists to
                                // prevent.
                                val parts = remember(v, vm.wordsVersion.collectAsStateWithLifecycle().value) {
                                    vm.standWordsFor(ayah).map { it.text }
                                }
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Text(
                                "$ayah",
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF4A5A80),
                                modifier = Modifier.width(26.dp).padding(top = 8.dp),
                            )
                            FlowRow(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                parts.forEachIndexed { wi, w ->
                                    LiveWord(
                                        w,
                                        statusMap["$surah:$ayah:${wi + 1}"],
                                        "$surah:$ayah:${wi + 1}" == currentKey,
                                    )
                                }                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // No diagnostics row here. "moving 0.50", "pace 16s" and
                // "in passage 29" were parked in the middle of a devotional screen,
                // and they are engineering state, not feedback: 0.50 is the lock's
                // normalised position, 16s is how long it has been stalled. They
                // belong on the Diagnostics screen and in the session report, where
                // someone who wants them can find them. What the reciter needs
                // mid-recitation is the words and the light.
                Spacer(Modifier.height(14.dp))

                // The feature tray: circular controls, evenly spaced, centred, at
                // the bottom of the dark ground.
                //
                // This is not decoration, it is the accommodation for three
                // situations a single Begin button cannot serve: a phone left on a
                // table while the reciter walks around the room, a crowded room
                // where the honest move is to stop listening for a minute, and a
                // reciter who simply needs a break without ending the session.
                //
                // So: listening from the moment the tray appears, a Pause toggle
                // (tap, not hold - holding to recite would break the walk-around
                // case outright), and an End that leaves the tray entirely.
                // Progress. Present because the reciter needs to know how far in
                // they are, and fixed because it used to be wrong: it read the live
                // paint map, which is pruned to the lock's last three ayat, so a
                // finished surah always read about 55%.
                //
                // Counted from the archive now, so it is complete. `judged` here is
                // CORRECT + WRONG - a verdict, never UNKNOWN or SKIPPED.
                val judged = scopeProgress.sumOf { it.decided }
                Text(
                    "$judged of $totalWords heard",
                    fontSize = 12.sp,
                    color = lcG.inkMuted,
                )
                Spacer(Modifier.height(12.dp))
                TrayButton(
                    icon = Icons.Filled.Mic,
                    tint = if (recording && !paused) lcG.accent else lcG.inkFaint,
                    track = lcG.surfaceHigh,
                    caption = if (recording && !paused) "Listening" else "Paused",
                    onClick = {
                        paused = !paused
                        if (paused) vm.stopRecite("paused")
                        else onRequestMic()
                    },
                )
                Spacer(Modifier.width(28.dp))
                TrayButton(
                    icon = Icons.Filled.Stop,
                    tint = lcG.chartBad,
                    track = lcG.chartBad.copy(alpha = 0.16f),
                    caption = "End",
                    onClick = { vm.stopRecite("live done"); finished = true },
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showExit) {
        AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("Leave this session?") },
            text = { Text("What you have recited so far is still counted.") },
            confirmButton = {
                TextButton(onClick = {
                    showExit = false
                    if (recording) vm.stopRecite("live exit")
                    onExit()
                }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { showExit = false }) { Text("Stay") }
            },
        )
    }

    // No "Done" dialog. A session that judged nothing needs more than a sentence in
    // a box, and one that went well deserves the report. Finishing navigates
    // straight to the session report, which is the only surface that can show what
    // actually happened across the whole scope.
    if (finished) {
        LaunchedEffect(finished) { onFinished() }
    }
}

private enum class LiveState { Idle, Quiet, Hearing, Thinking }

/** Linear colour mix in ARGB. Short enough to not be worth a dependency. */
private fun lerp(a: Color, b: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * u,
        green = a.green + (b.green - a.green) * u,
        blue = a.blue + (b.blue - a.blue) * u,
        alpha = a.alpha + (b.alpha - a.alpha) * u,
    )
}

/** The one warm accent this screen owns, so a stalled lock reads differently. */
private val amberColor = Color(0xFFE09112)

// The light's colours come from LocalLiveColors, which derives them from whichever
// theme is active. The violet and cyan that used to live here were taken from a
// screenshot of a generic voice assistant, which is the wrong object entirely: a
// mushaf is an illuminated manuscript, and its register is gold leaf on a dark
// ground, not a neon gradient. Worse, they were a THIRD palette - the app was
// teal and gold, the reader had its own light scheme, and this screen had
// twenty literals of its own - so the same five word verdicts were two different
// colours in two different files.

/**
 * The light.
 *
 * A rounded rectangle of luminous colour whose PERIMETER deforms with the voice.
 *
 * That distinction is the whole form. The earlier version drew a mass anchored to
 * the bottom of the screen with a wavy top edge - a horizon. Every reference puts
 * the light in a floating, fully-rounded card shape with dark ground around it on
 * all four sides, which is a different object: the silhouette itself has to wobble
 * and breathe, not merely the fill inside it. A wavy top edge on a filled block is
 * the difference between a light and a container.
 *
 * Four things make it read as light rather than as a drawn shape:
 *
 *  1. The perimeter is a closed path of points pushed inward and outward by
 *     several sines at different frequencies, plus the microphone history read at a
 *     phase that varies around the perimeter. Because the history is sampled at
 *     different positions around the edge rather than globally, a single amplitude
 *     makes the shape breathe unevenly instead of swelling in and out - which is
 *     what makes it look alive rather than pulsing.
 *  2. Nothing about it is drawn with a crisp stroke. The previous version put a 2dp
 *     line along the crest and that line is the single clearest tell of something
 *     drawn rather than emitted.
 *  3. A blurred halo pass drawn behind the silhouette, wider for loud speech and
 *     hotter for a high voice, so the light appears to spill past its own edge.
 *  4. The fill is a radial gradient anchored to the lattice's centre, brightest at
 *     the middle and fading at the rim, which is how a glowing body actually
 *     shades.
 *
 * Colours come from `LiveColors`, derived from whatever theme is active, so this
 * form follows the app's palette rather than carrying a second one.
 */
@Composable
private fun HorizonWave(
    lc: LiveColors,
    history: FloatArray,
    head: androidx.compose.runtime.MutableIntState,
    level: androidx.compose.runtime.MutableFloatState,
    onset: androidx.compose.runtime.MutableFloatState,
    /** 0..1 tone brightness. Follows pace until the engine publishes band energy. */
    brightness: Float,
    /** Whether to animate at all. Off leaves an honest still image. */
    animate: Boolean,
    stall: Float,
    state: LiveState,
    modifier: Modifier = Modifier,
) {
    val shape = remember { Path() }
    val flow = rememberInfiniteTransition(label = "flow")
    val drift by flow.animateFloat(
        0f, 6.2831855f,
        infiniteRepeatable(tween(11000, easing = LinearEasing)), label = "drift")
    val mist by flow.animateFloat(
        0f, 6.2831855f,
        infiniteRepeatable(tween(17300, easing = LinearEasing)), label = "mist")
    val phD = if (animate) drift else 0.9f
    val phM = if (animate) mist else 2.4f

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val n = history.size
        val hd = head.intValue
        val lv = level.floatValue.coerceIn(0f, 1f)
        val on = onset.floatValue.coerceIn(0f, 1f)
        val dull = (stall / 5f).coerceIn(0f, 1f)
        val clear = (1f - 0.45f * dull) *
            (if (state == LiveState.Thinking) 0.72f else 1f)

        val hot = (brightness * 0.6f + on * 0.4f).coerceIn(0f, 1f)
        val body = if (state == LiveState.Thinking) mix(lc.body, lc.waiting, 0.35f)
                   else lc.body
        val core = if (state == LiveState.Thinking) lc.waiting
                   else lerp(lc.bodyDeep, lc.crest, hot)

        // The card. Wide and short, centred horizontally, sitting in the lower
        // third - the proportions and placement the references use.
        val cardW = w * 0.78f
        val cardH = h * (0.56f + 0.13f * lv + 0.10f * on) * clear
        val cx = w * 0.5f
        val cy = h * (0.62f + 0.05f * lv + 0.03f * on) * clear
        val rad = minOf(cardW, cardH) * 0.40f

        // Depth of the perimeter deformation. Breathing at rest, and more under the
        // voice - but never so much that the rounded corners stop reading as rounded.
        val amp = minOf(cardW, cardH) * (0.030f + 0.070f * lv + 0.055f * on) * clear

        // Build the perimeter as one closed Catmull-Rom loop through 33 points, so
        // the shape is smooth and continuous instead of faceted.
        val pts = ArrayList<androidx.compose.ui.geometry.Offset>(33)
        for (i in 0 until 33) {
            val a = (i / 33f) * 6.2831855f
            // History sampled at a phase that varies WITH the angle: this is what
            // keeps a single loud syllable from swelling the whole shape uniformly.
            val k = ((i.toFloat() / 33f) * n).toInt().coerceIn(0, n - 1)
            val hv = history[((hd - 1 - k) % n + n) % n].coerceIn(0f, 1f)
            var shape01 = 0.5f
            shape01 += 0.30f * sin(phD * 1.0f + a * 2f)
            shape01 += 0.16f * sin(phD * 1.7f + a * 3f + 1.1f)
            shape01 += 0.09f * sin(phD * 2.3f + a * 5f + 2.4f)
            val r = 1f + (shape01 - 0.5f) * 0.42f * (0.35f + 0.65f * (hv + lv))
            pts.add(
                androidx.compose.ui.geometry.Offset(
                    cx + androidAppCos(a) * (cardW * 0.5f + amp * r),
                    cy + androidAppSin(a) * (cardH * 0.5f + amp * r),
                )
            )
        }
        shape.reset()
        shape.moveTo(pts[0].x, pts[0].y)
        for (i in 0 until 33) {
            val p0 = pts[if (i == 0) 32 else i - 1]
            val p1 = pts[i]
            val p2 = pts[(i + 1) % 33]
            val p3 = pts[(i + 2) % 33]
            shape.cubicTo(
                p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
                p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
                p2.x, p2.y,
            )
        }
        shape.close()

        // --- halo, behind and wider, so the light appears to spill out ------
        val haloR = minOf(cardW, cardH) * (1.2f + 0.5f * lv + 0.4f * on)
        drawCircle(
            brush = Brush.radialGradient(
                0f to core.copy(alpha = (0.16f + 0.20f * lv + 0.18f * on) * clear),
                0.55f to core.copy(alpha = (0.06f + 0.10f * lv + 0.08f * on) * clear),
                1f to body.copy(alpha = 0f),
                center = androidx.compose.ui.geometry.Offset(cx, cy),
                radius = haloR,
            ),
            radius = haloR,
            center = androidx.compose.ui.geometry.Offset(cx, cy),
        )

        // --- the body: a radial fill, brightest at the core, fading at the rim --
        drawPath(
            shape,
            Brush.radialGradient(
                0.00f to core.copy(alpha = (0.52f + 0.34f * lv + 0.16f * on) * clear),
                0.55f to body.copy(alpha = (0.40f + 0.28f * lv) * clear),
                1.00f to body.copy(alpha = (0.34f + 0.26f * lv) * clear),
                center = androidx.compose.ui.geometry.Offset(cx, cy - cardH * 0.15f),
                radius = minOf(cardW, cardH) * 0.95f,
            ),
        )

        // --- drifting mist, clipped to the shape so it never escapes it -----
        clipPath(shape) {
            for (i in 0 until 6) {
                val ph = phM + i * 1.9f
                val mx = cx + (cardW * 0.30f) * sin(ph * 0.7f + i)
                val my = cy + (cardH * 0.22f) * sin(ph * 1.1f + i * 2.1f)
                val mr = minOf(cardW, cardH) * (0.42f + 0.13f * sin(ph + i * 0.7f))
                val c2 = androidx.compose.ui.geometry.Offset(mx, my)
                val t2 = if (i % 2 == 0) mix(body, lc.mist, 0.5f)
                         else mix(core, lc.crest, 0.35f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to t2.copy(alpha = (0.24f + 0.16f * lv) * clear),
                        1f to t2.copy(alpha = 0f),
                        center = c2, radius = mr,
                    ),
                    radius = mr, center = c2,
                )
            }
        }

        // A faint rim so the silhouette still reads as a card while its perimeter
        // deforms - present, but nowhere near enough to look like an outline.
        drawRoundRect(
            color = core.copy(
                alpha = (0.05f + 0.05f * on) * clear,
            ),
            topLeft = androidx.compose.ui.geometry.Offset(cx - cardW / 2f, cy - cardH / 2f),
            size = androidx.compose.ui.geometry.Size(cardW, cardH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(rad, rad),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = (1.5f + 2.5f * on).dp.toPx(),
            ),
        )
    }
}

/** cos/sin on floats, without importing kotlin.math at every call site. */
private fun androidAppCos(v: Float): Float = kotlin.math.cos(v)
private fun androidAppSin(v: Float): Float = kotlin.math.sin(v)

@Composable
private fun LiveWord(text: String, status: WordStatus?, isCurrent: Boolean) {
    val reveal = remember { Animatable(0f) }
    val tick = LocalHapticFeedback.current

    // Ink, not a fade.
    //
    // A cross-fade from nothing is the weakest possible arrival, and the arrival
    // IS the feedback here - the moment a word resolves is the only thing telling
    // you the engine heard you. So the word rises a few pixels and settles, which
    // reads as ink meeting paper, rather than simply becoming visible. It also
    // springs, so it carries the velocity of the syllable that produced it instead
    // of running on a fixed clock.
    LaunchedEffect(status) {
        if (status != null) {
            reveal.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 520f))
            // A light tick on every word, and a firmer one on a mistake. In a
            // screen where you are looking at your memory rather than at the
            // phone, haptics is the channel that reaches you without taking your
            // eyes off the words.
            if (status == WordStatus.WRONG) {
                tick.performHapticFeedback(HapticFeedbackType.LongPress)
            } else {
                tick.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        } else {
            reveal.snapTo(0f)
        }
    }
    val t = reveal.value
    val c = LocalIqraColors.current

    val fg = when (status) {
        null -> c.ink.copy(alpha = 0f)          // unheard: absent, not a whisper
        WordStatus.CORRECT -> c.wordGood
        WordStatus.WRONG -> c.wordBad
        WordStatus.SKIPPED -> c.wordUnknown.copy(alpha = 0.45f)
        else -> c.wordUnknown
    }
    Text(
        text,
        fontFamily = quranFont,
        fontSize = 30.sp,
        lineHeight = 46.sp,
        color = fg.copy(alpha = fg.alpha * t),
        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .graphicsLayer {
                // Rises and sharpens as it arrives.
                translationY = (1f - t) * 9f
                alpha = t
            }
            .then(
                if (isCurrent && status != null) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = c.gold.copy(alpha = 0.16f * t),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
                        )
                    }
                } else Modifier,
            ),
    )
}

/**
 * The passage now takes its words from `vm.standWordsFor(ayah)`, which are
 * `MushafWord`s already keyed by `wordInVerse` - the same index the verdict maps
 * use.
 *
 * The whitespace splitter that was here is gone deliberately. It carried two
 * right-by-construction notes about `textClean` stripping harakat and bare waqf
 * marks splitting into their own tokens, and still indexed the words wrongly:
 * the mushaf prints 'بَعْدَ مَا' (2:89 w3, 8:6 w4) as ONE word across a space,
 * so from the first such word onward every whitespace index points at a
 * different word than the verdict map does. Deriving the list from the mushaf is
 * the only way the two can agree, and the engine already publishes it.
 */


/**
 * Sacred geometry behind the passage.
 *
 * The recite screen is the one place in the app with no page, no text block and no
 * list - just a ground and one luminous form. That left the upper two thirds of the
 * screen empty, which is most of why the first version of this screen read as
 * unfinished rather than calm.
 *
 * An eight-pointed star per cell - two squares, one turned 45 degrees - tiled with
 * alternate cells offset by half a cell so the stars interlock. 7% opacity, drawn in
 * the theme's gold. Both numbers are deliberate: a lattice is a texture and at any
 * higher alpha it competes with the Arabic, which is the one thing on this screen
 * that must always win. It brightens toward the light, so the pattern belongs to
 * the same object rather than floating behind it.
 */
@Composable
private fun GirihLattice(
    colour: Color,
    phase: Float,
    brighten: Float,
    modifier: Modifier = Modifier,
) {
    val p = remember { Path() }
    Canvas(modifier) {
        val cell = size.minDimension / 8.5f
        if (cell <= 2f) return@Canvas
        val step = cell * 1.10f
        val cols = (size.width / step).toInt() + 2
        val rows = (size.height / step).toInt() + 2
        val stroke = (cell * 0.014f).coerceAtLeast(0.6f)

        // Cells nearer the bottom sit closer to the light, so they read slightly
        // stronger. Without the gradient the pattern looks like wallpaper.
        fun depthAt(y: Float): Float = (y / size.height).coerceIn(0f, 1f)

        for (r in -1..rows) {
            // Alternate ROWS, not alternate cells. Offsetting individual cells
            // diagonally scatters the stars - it reads as loose diamonds rather
            // than as a pattern, because nothing lines up with its neighbours.
            // A half-step on alternate rows is the running bond that makes the
            // stars connect into one continuous lattice.
            val off = if ((r and 1) == 0) 0f else step * 0.5f
            for (cc in -1..cols) {
                val cxp = cc * step + off + step * 0.5f
                val cyp = r * step + step * 0.5f
                // Stronger low down, where it approaches the light, then faded
                // right out over the last fifth so the pattern dissolves into the
                // glow instead of being cut off by it.
                val d = depthAt(cyp)
                val fade = if (d > 0.84f) ((1f - d) / 0.16f).coerceIn(0f, 1f) else 1f
                val a = colour.copy(
                    alpha = colour.alpha * (0.5f + 0.5f * d) * fade *
                        (1f + brighten * 0.45f),
                )
                val rad = cell * 0.44f
                // Square one, axis aligned.
                p.reset()
                p.moveTo(cxp - rad, cyp - rad)
                p.lineTo(cxp + rad, cyp - rad)
                p.lineTo(cxp + rad, cyp + rad)
                p.lineTo(cxp - rad, cyp + rad)
                p.close()
                // Square two, turned 45 degrees, which with the first makes the star.
                val q = rad * 0.7071f
                val wob = 1f + 0.02f * kotlin.math.sin(phase + (cc + r) * 0.7f)
                p.moveTo(cxp, cyp - q * wob)
                p.lineTo(cxp + q * wob, cyp)
                p.lineTo(cxp, cyp + q * wob)
                p.lineTo(cxp - q * wob, cyp)
                p.close()
                drawPath(p, a, style = Stroke(width = stroke))
            }
        }
    }
}

/**
 * One circular control in the feature tray.
 *
 * Round, because the tray is a row of round buttons and a pill in the middle of
 * them reads as a different kind of thing. The caption sits under the circle
 * rather than inside it, which is what keeps the hit target generous without the
 * glyph needing to shrink.
 */
@Composable
private fun TrayButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    track: Color,
    caption: String,
    onClick: () -> Unit,
) {
    val lcG = LocalIqraColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(track)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = caption, tint = tint,
                modifier = Modifier.size(25.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(caption, fontSize = 11.sp, color = lcG.inkMuted)
    }
}
