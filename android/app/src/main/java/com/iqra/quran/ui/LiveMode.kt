package com.iqra.quran.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Paint
import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas

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
    val recording by vm.recording.collectAsStateWithLifecycle()
    val statusMap by vm.statusMap.collectAsStateWithLifecycle()
    val currentKey by vm.currentKey.collectAsStateWithLifecycle()
    val activeVerse by vm.activeVerse.collectAsStateWithLifecycle()
    val policy by vm.policyLive.collectAsStateWithLifecycle()

    var started by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var showExit by remember { mutableStateOf(false) }

    // The verse text, split on whitespace. The mushaf's own per-word boxes are
    // not needed here: this screen counts words and shows them one at a time,
    // and the word INDEX it feeds statusMap with is the index in this list.
    val verses = remember(surah, startAyah, ayahCount) {
        (startAyah until startAyah + ayahCount).mapNotNull { data.getVerse(surah, it) }
    }
    val totalWords = remember(verses) {
        verses.sumOf { it.textClean.split(Regex("\\s+")).count { w -> w.isNotEmpty() } }
    }

    val amp = remember { mutableFloatStateOf(0f) }
    val voice = remember { mutableFloatStateOf(0f) }
    var startedAt by remember { mutableLongStateOf(0L) }
    // Public on the view model; the engine keeps the raw field private.
    val wpm by vm.wpmFlow.collectAsState()
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
                    "Surah $surah  ·  $ayahCount ${if (ayahCount == 1) "ayah" else "ayat"}",
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
            HorizonWave(
                history = history,
                head = headState,
                level = voice,
                onset = onset,
                pace = (wpm / 160.0).toFloat(),
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
                        LiveState.Thinking -> waveAmber
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
                        val parts = remember(v.textUthmani) { uthmaniWords(v.textUthmani) }
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
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                // The same numbers Diagnostics shows, readable at a glance, and a
                // tool that only explains itself on another screen has failed the
                // person who most needs the explanation.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    LiveStat("heard", "${statusMap.size}")
                    LiveStat("moving", if (policy.next >= 0f) "%.2f".format(policy.next) else "—")
                    LiveStat("pace", if (policy.stallSec > 0f) "%.0fs".format(policy.stallSec) else "—")
                    LiveStat("in passage", "$totalWords")
                }

                Spacer(Modifier.height(14.dp))

                // Controls live in a floating bar on the light, not in a top row.
                Surface(
                    color = Color(0xFF111A2E),
                    shape = RoundedCornerShape(30.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!started) {
                            TextButton(
                                onClick = {
                                    startedAt = System.currentTimeMillis()
                                    started = true
                                    onRequestMic()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Begin reciting", color = waveCyan, fontSize = 16.sp) }
                        } else {
                            Text(
                                "${statusMap.size} of $totalWords heard",
                                fontSize = 13.sp,
                                color = Color(0xFF8FA0BE),
                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                            )
                            TextButton(onClick = { vm.stopRecite("live done") }) {
                                Text("End", color = waveCyan, fontSize = 16.sp)
                            }
                        }
                    }
                }
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

    if (finished) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Done") },
            text = {
                val judged = statusMap.size
                Text(
                    if (judged == 0) {
                        "Nothing was judged. The microphone may not have been " +
                            "granted, or the words were too quiet to hear."
                    } else {
                        "$judged ${if (judged == 1) "word" else "words"} " +
                            "heard. Your report is ready."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { onFinished() }) { Text("Done") }
            },
        )
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

// The horizon's palette, taken from the references: a blue core, violet at the
// shoulders, cyan where the light is strongest, over near-black ground.
private val waveBlue   = Color(0xFF2B57F0)
private val waveViolet = Color(0xFF7B4BEE)
private val waveCyan   = Color(0xFF3FD8F0)
private val waveAmber  = Color(0xFFE0A23C)

/**
 * The horizon.
 *
 * Not a sphere, and not a ball with rings around it. Every reference this is
 * built from puts the light at the BOTTOM of the frame with dark empty ground
 * above it, and that is not a stylistic detail - a centred orb fights the text
 * for the middle of the eye, whereas a low horizon leaves the words somewhere
 * quiet to land.
 *
 * Five things make it read as light rather than as a filled shape:
 *
 *  1. The crest carries the microphone HISTORY, sampled newest-first at a phase
 *     that varies with x. That detail is the whole difference. A curve driven by
 *     the current amplitude pulses in place and looks like a meter; the same
 *     amplitude carried across the width travels sideways, so speech looks like
 *     speech.
 *  2. The fill's gradient is anchored to the crest BAND, not to the box, and is
 *     transparent above it. A gradient tied to the box leaves the moving crest
 *     swimming inside a slab, which is exactly what the first attempt looked
 *     like - a blue rectangle with a ruler on top.
 *  3. The crest is stroked twice: once blurred and wide for the glow, once crisp
 *     for the ridge. Compositing on a soft edge, without a real blur, gives an
 *     edge but no light.
 *  4. Six large low-alpha radial blobs drift inside the mass. Mist is what makes
 *     these references look expensive and no amount of stroking produces it.
 *  5. At rest the crest still moves, on two periods that share no common factor
 *     so the surface never repeats a pattern you could learn and stop seeing.
 *
 * The history is a plain FloatArray written by the amplitude poll and read HERE,
 * inside the draw lambda. Neither is touched during composition, so sixty frames
 * a second repaints one canvas and never recomposes the verse behind it.
 */
@Composable
private fun HorizonWave(
    history: FloatArray,
    head: androidx.compose.runtime.MutableIntState,
    level: androidx.compose.runtime.MutableFloatState,
    onset: androidx.compose.runtime.MutableFloatState,
    pace: Float,
    stall: Float,
    state: LiveState,
    modifier: Modifier = Modifier,
) {
    val mass = remember { Path() }
    val crest = remember { Path() }
    val flow = rememberInfiniteTransition(label = "flow")
    val drift by flow.animateFloat(
        0f, 6.2831855f,
        infiniteRepeatable(tween(11000, easing = LinearEasing)), label = "drift")
    val mist by flow.animateFloat(
        0f, 6.2831855f,
        infiniteRepeatable(tween(17300, easing = LinearEasing)), label = "mist")

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val n = history.size
        val hd = head.intValue
        val lv = level.floatValue.coerceIn(0f, 1f)
        val on = onset.floatValue.coerceIn(0f, 1f)
        // A stalled lock visibly loses its light. Silence should read as "not
        // hearing you" before any words on the screen can tell you.
        val dull = (stall / 5f).coerceIn(0f, 1f)
        val live = if (state == LiveState.Idle) 0.72f else 1f
        val clear = (1f - 0.45f * dull) * (if (state == LiveState.Thinking) 0.72f else 1f)

        // While the engine is thinking the light dims and cools rather than
        // turning amber. Amber reads as a warning on this ground and turns the
        // bottom of the screen brown; the waiting state belongs inside the same
        // blue-violet family, and the amber in the label above already says it.
        val tint = when (state) {
            LiveState.Thinking -> lerp(waveViolet, waveBlue, 0.45f)
            else -> lerp(waveViolet, waveCyan, (pace * 0.55f + on * 0.45f).coerceIn(0f, 1f))
        }
        val core = lerp(waveBlue, waveCyan, on * 0.6f)

        // Crest geometry. `restY` is the mean, `lift` how far the crest can climb
        // above it. Both are fractions of the box so the form is identical on any
        // screen. The resting lift is deliberately large: a wave that only exists
        // while someone is talking is not a wave, it is an indicator.
        val restY = h * 0.52f
        val lift = h * 0.30f * live * clear * (0.52f + 0.48f * lv + 0.38f * on)

        mass.reset(); crest.reset()
        mass.moveTo(0f, h); crest.moveTo(0f, restY)
        val steps = 88
        var crestTop = h
        for (i in 0..steps) {
            val x = w * i / steps
            val u = i / steps.toFloat()
            var shape = 0.50f
            shape += 0.30f * sin(drift + u * 2.1f)
            shape += 0.20f * sin(drift * 1.7f + u * 4.7f + 1.1f)
            shape += 0.12f * sin(drift * 2.3f + u * 9.3f + 2.4f)
            val k = (u * 0.88f * n).toInt().coerceIn(0, n - 1)
            val hv = history[((hd - 1 - k) % n + n) % n].coerceIn(0f, 1f)
            val y = restY - lift * (0.26f + 0.42f * shape + 0.58f * hv)
            if (y < crestTop) crestTop = y
            mass.lineTo(x, y)
            crest.lineTo(x, y)
        }
        mass.lineTo(w, h); mass.close()

        // The crest band, as fractions of the box. The gradient is anchored here
        // so the fade always happens where the light actually is.
        val bandTop = (crestTop / h)
        val peakA = (0.46f + 0.30f * lv + 0.22f * on) * clear * live

        drawPath(
            mass,
            Brush.verticalGradient(
                0.00f to waveViolet.copy(alpha = 0f),
                (bandTop * 0.55f).coerceIn(0f, 0.9f) to waveViolet.copy(alpha = 0f),
                bandTop to tint.copy(alpha = peakA * 0.34f),
                (bandTop + 0.13f).coerceAtMost(1f) to tint.copy(alpha = peakA * 0.72f),
                1.00f to core.copy(alpha = (peakA * 0.98f).coerceAtMost(0.94f)),
            ),
        )

        // --- drifting mist inside the mass ------------------------------
        for (i in 0 until 6) {
            val ph = mist + i * 1.9f
            val cx = w * (0.5f + 0.42f * sin(ph * 0.7f + i))
            val cy = h * (0.78f + 0.18f * sin(ph * 1.1f + i * 2.1f))
            val r = h * (0.36f + 0.11f * sin(ph + i * 0.7f))
            val c = androidx.compose.ui.geometry.Offset(cx, cy)
            val t2 = if (i % 2 == 0) tint else core
            drawCircle(
                brush = Brush.radialGradient(
                    0f to t2.copy(alpha = 0.30f * live * clear),
                    1f to t2.copy(alpha = 0f),
                    center = c, radius = r,
                ),
                radius = r, center = c,
            )
        }

        // --- the crest: a blurred glow, then the ridge ------------------
        // A blur filter is what separates "light" from "edge". Without it the
        // crest is a drawn line sitting on a fill and the whole thing looks flat.
        drawIntoCanvas { canvas ->
            val blur = Paint().asFrameworkPaint().apply {
                maskFilter = BlurMaskFilter(26f + 22f * on, BlurMaskFilter.Blur.NORMAL)
                color = lerp(tint, waveCyan, on * 0.7f)
                    .copy(alpha = (0.42f + 0.42f * on).coerceAtMost(0.85f))
                    .toArgb()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 5f + 5f * on
            }
            canvas.nativeCanvas.drawPath(crest.asAndroidPath(), blur)
        }
        drawPath(
            crest,
            color = lerp(tint, waveCyan, 0.4f + 0.5f * on)
                .copy(alpha = (0.34f + 0.50f * on + 0.20f * lv) * clear * live),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f + 2.4f * on),
        )

        // --- bloom sitting on the crest ---------------------------------
        val gx = w * 0.5f
        val gy = crestTop + (restY - crestTop) * 0.45f
        val gr = h * (0.46f + 0.18f * on)
        val g = androidx.compose.ui.geometry.Offset(gx, gy)
        drawCircle(
            brush = Brush.radialGradient(
                0f to lerp(core, waveCyan, on * 0.6f).copy(alpha = (0.26f + 0.24f * on) * live),
                1f to waveBlue.copy(alpha = 0f),
                center = g, radius = gr,
            ),
            radius = gr, center = g,
        )
    }
}

@Composable
private fun LiveWord(text: String, status: WordStatus?, isCurrent: Boolean) {
    val reveal by animateFloatAsState(
        targetValue = if (status == null) 0f else 1f,
        animationSpec = tween(420, easing = LinearEasing),
        label = "reveal",
    )
    val fg = when (status) {
        null -> Color(0xFF6E807A)
        WordStatus.CORRECT -> Color(0xFFF2EFE4)
        WordStatus.WRONG -> Color(0xFFF08A80)
        WordStatus.SKIPPED -> Color(0xFF8A7F63)
        else -> Color(0xFF4E5A78)
    }
    Text(
        text,
        fontFamily = quranFont,
        fontSize = 30.sp,
        lineHeight = 46.sp,
        // Invisible until the engine has actually heard it. A whisper of grey
        // is still the whole ayah on screen, which defeats the point of
        // reciting from memory - the passage has to arrive word by word.
        color = fg.copy(alpha = reveal),
        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
        modifier = if (isCurrent && status != null) {
            Modifier.drawBehind {
                drawRoundRect(
                    color = goldColor.copy(alpha = 0.16f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
                )
            }
        } else Modifier,
    )
}

@Composable
private fun LiveStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 17.sp, color = Color(0xFFDCE6F7),
            fontWeight = FontWeight.Medium)
        Text(label, fontSize = 10.sp, color = Color(0xFF5E716A),
            letterSpacing = 0.6.sp)
    }
}

/**
 * The verse's words, in reading order, from the Uthmani text.
 *
 * Two things this has to get right, and the obvious implementation gets both
 * wrong. `textClean` carries NO harakat at all - 2:7 is `خَتَمَ ٱللَّهُ عَلَىٰ` in
 * Uthmani and `ختم الله علي` in clean - so rendering the clean text silently
 * strips exactly what a reciter is reading. And the Uthmani text splits bare
 * waqf marks into their own whitespace tokens: 2:7 yields fourteen tokens for
 * twelve words, because `ۖ` appears twice on its own.
 *
 * So a token counts as a word only if it contains an Arabic letter. Marks are
 * attached to the word they follow, which is also where a mushaf prints them.
 */
internal fun uthmaniWords(text: String): List<String> {
    val out = ArrayList<String>()
    for (raw in text.split(' ')) {
        if (raw.isEmpty()) continue
        val hasLetter = raw.any { it.code in 0x0621..0x064A || it.code in 0x0671..0x06D3 }
        if (hasLetter) out.add(raw)
        else if (out.isNotEmpty()) out[out.lastIndex] = out.lastIndex.let { out[it] } + " " + raw
    }
    return out
}
