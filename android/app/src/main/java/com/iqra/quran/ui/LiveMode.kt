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
    // The orb's memory. A plain FloatArray, written by the poll and read inside
    // the draw lambda - never touched during composition, so it costs one
    // canvas redraw rather than a recomposition.
    val history = remember { FloatArray(96) }
    var head by remember { mutableIntStateOf(0) }

    // Amplitude poll. 70 ms is about 14 Hz, fast enough that the orb reads as
    // continuous and slow enough that it is 14 wake-ups a second rather than
    // 60. The smoothing is what makes speech look like speech instead of a
    // strobe.
    LaunchedEffect(recording) {
        if (!recording) { amp.floatValue = 0f; return@LaunchedEffect }
        var last = 0f
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
    LaunchedEffect(started, recording, startedAt) {
        if (started && !recording && startedAt > 0L) {
            delay(700)          // let the last verdict land before we judge
            finished = true
        }
    }

    Scaffold(
        containerColor = Color(0xFF0E1512),
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { showExit = true }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Leave",
                        tint = Color(0xFF9FB3AC))
                }
                Text(
                    "Surah $surah  ·  $ayahCount ${if (ayahCount == 1) "ayah" else "ayat"}",
                    fontSize = 15.sp,
                    color = Color(0xFF9FB3AC),
                    modifier = Modifier.weight(1f),
                )
                if (started && !finished) {
                    TextButton(onClick = { vm.stopRecite("live done") }) {
                        Text("Finish", color = goldColor)
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))

            LiveOrb(
                history = history,
                level = voice,
                state = state,
                modifier = Modifier.size(188.dp)
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

            Spacer(Modifier.height(14.dp))
            Text(
                when (state) {
                    LiveState.Idle -> if (!started) "Tap to begin" else "Listening"
                    LiveState.Quiet -> "I can't hear you"
                    LiveState.Hearing -> "Go ahead"
                    LiveState.Thinking -> "Let me think…"
                },
                fontSize = 14.sp,
                color = when (state) {
                    LiveState.Thinking -> amberColor
                    LiveState.Quiet -> Color(0xFF8A9A94)
                    else -> Color(0xFFB9CCC5)
                },
            )

            Spacer(Modifier.height(20.dp))

            // ---- the passage -------------------------------------------
            //
            // Scrollable and given the leftover height, because a test can be a
            // whole surah and the first version ran the words off the bottom of
            // the screen with nothing to reach them.
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
            //
            // No card. A panel with a border and a fill turns recitation into
            // form-filling; the words need to be the only thing on the ground,
            // which is why the orb dims behind them rather than sitting above a
            // box. Unheard words sit at a whisper rather than vanishing, so the
            // shape of the ayah is countable - "did I say twelve or thirteen" is
            // a real question while reciting from memory.
            verses.forEachIndexed { i, v ->
                val ayah = startAyah + i
                val parts = remember(v.textClean) {
                    v.textClean.split(Regex("\\s+")).filter { it.isNotEmpty() }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Text(
                        "$ayah",
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF3C4A44),
                        modifier = Modifier.width(26.dp).padding(top = 6.dp),
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
            }

            Spacer(Modifier.height(14.dp))

            // ---- ambient diagnostics ------------------------------------
            // The same numbers Diagnostics shows, readable at a glance. A tool
            // that only explains itself on a separate screen has failed the
            // person who most needs the explanation.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LiveStat("heard", "${statusMap.size}")
                LiveStat("moving", if (policy.next >= 0f) "%.2f".format(policy.next) else "—")
                LiveStat("pace", if (policy.stallSec > 0f) "%.0fs".format(policy.stallSec) else "—")
                LiveStat("in passage", "$totalWords")
            }

            Spacer(Modifier.height(10.dp))
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
                            "heard. Your results are on the Practice tab."
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

/**
 * The sphere.
 *
 * A filled core, a soft bloom, and four rings whose radius is modulated around
 * their circumference by a HISTORY of recent microphone levels rather than the
 * instantaneous one. That distinction is the whole difference between a sphere
 * and a progress spinner: a ring that scales with the current level jitters,
 * while a ring carrying five seconds of history has a shape that travels around
 * it, so speech looks like speech.
 *
 * The history lives in a plain FloatArray that the amplitude poll writes and
 * this reads INSIDE the draw lambda. Neither is touched during composition, so
 * sixty frames a second repaints one canvas and never recomposes the verse
 * behind it.
 */
@Composable
private fun LiveOrb(
    history: FloatArray,
    level: androidx.compose.runtime.MutableFloatState,
    state: LiveState,
    modifier: Modifier = Modifier,
) {
    val p1 = remember { Path() }
    val p2 = remember { Path() }
    val p3 = remember { Path() }
    val p4 = remember { Path() }
    val breath = rememberInfiniteTransition(label = "orb")
    val idle = breath.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse),
        label = "idle",
    )
    val spin = breath.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse),
        label = "spin",
    )

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val R = size.minDimension * 0.30f
        val lvl = level.floatValue
        val breathAmt = if (state == LiveState.Idle) idle.value * 0.10f else 0.03f
        val N = history.size

        // Colour follows state, and the ring hues walk a small spectrum so the
        // sphere reads as alive rather than as one accent colour pulsing.
        val core = when (state) {
            LiveState.Idle -> Color(0xFF35E0C0)
            LiveState.Quiet -> Color(0xFF4E7F76)
            LiveState.Hearing -> Color(0xFFFFC46B)
            LiveState.Thinking -> Color(0xFFFFA23A)
        }
        // Each ring takes a fixed hue of its own rather than a tint of the
        // state colour. Mixing them towards `core` made all four read teal, and
        // four teal circles is a loading spinner. The STATE still decides the
        // centre, so the sphere changes character when the lock stalls.
        val ringHues = listOf(
            core,
            lerp(core, Color(0xFFFF6E9C), 0.72f),
            lerp(core, Color(0xFF9B7BFF), 0.66f),
            lerp(core, Color(0xFF52E0FF), 0.74f),
        )

        // Bloom, then core. Two passes rather than one, because a single
        // gradient either has a bright middle or a soft edge - not both, and the
        // soft edge is what stops it looking like a flat sticker.
        val bloom = R * (2.15f + lvl * 0.55f)
        drawCircle(
            brush = Brush.radialGradient(
                0f to core.copy(alpha = 0.30f + lvl * 0.18f),
                0.45f to core.copy(alpha = 0.10f),
                1f to Color.Transparent,
                center = Offset(cx, cy),
                radius = bloom,
            ),
            radius = bloom,
            center = Offset(cx, cy),
        )
        val coreR = R * (0.72f + lvl * 0.20f + breathAmt)
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.92f),
                0.28f to core.copy(alpha = 0.80f),
                0.75f to core.copy(alpha = 0.34f),
                1f to core.copy(alpha = 0.05f),
                center = Offset(cx, cy),
                radius = coreR,
            ),
            radius = coreR,
            center = Offset(cx, cy),
        )

        // Rings. Each reads the same history at a different phase, so the
        // wobble travels outward instead of every ring pulsing in unison.
        val paths = listOf(p1, p2, p3, p4)
        paths.forEachIndexed { idx, path ->
            val base = R * (1.12f + idx * 0.26f)
            val gain = (1.0f - idx * 0.14f) * (0.22f + lvl * 1.55f)
            val phase = spin.value * (if (idx % 2 == 0) 0.9f else -1.1f) + idx * 71f
            path.rewind()
            val steps = 128
            for (i in 0..steps) {
                val th = i / steps.toFloat() * (Math.PI * 2).toFloat()
                // Where in the history this angle samples: the head is the
                // newest sample, so the tail of the array is the past.
                val h = history[(i * N / steps) % N]
                val wob = kotlin.math.sin(th * 3f + phase * 0.02f) * 0.55f +
                    kotlin.math.sin(th * 5f - phase * 0.013f) * 0.30f +
                    kotlin.math.sin(th * 2f + phase * 0.007f) * 0.35f
                val r = base + h * gain * wob + breathAmt * base * 0.5f
                val x = cx + r * kotlin.math.cos(th)
                val y = cy + r * kotlin.math.sin(th)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(
                path = path,
                color = ringHues[idx].copy(alpha = 0.34f + lvl * 0.40f),
                style = Stroke(width = (2.4f - idx * 0.35f).dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // A stalled lock says so, rather than looking like it is still working.
        if (state == LiveState.Thinking) {
            drawArc(
                color = amberColor.copy(alpha = 0.9f),
                startAngle = spin.value * 2f,
                sweepAngle = 64f,
                useCenter = false,
                topLeft = Offset(cx - bloom * 0.72f, cy - bloom * 0.72f),
                size = androidx.compose.ui.geometry.Size(bloom * 1.44f, bloom * 1.44f),
                style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * One word, arriving.
 *
 * Not a chip. A chip is a form field, and this is a word in a sentence. Unheard,
 * it is the text at a whisper so the ayah's shape is readable as a whole; heard,
 * it fades up and takes the colour of its verdict. The fade is animated because
 * the moment a word resolves IS the feedback, and a hard cut throws it away.
 *
 * A word the engine could not hear stays UNKNOWN and is drawn cool and dim. It
 * is never counted against the reciter - the model failing to produce a phoneme
 * is a fact about the model.
 */
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
        else -> Color(0xFF5F7A72)
    }
    Text(
        text,
        fontFamily = quranFont,
        fontSize = 30.sp,
        lineHeight = 46.sp,
        color = fg.copy(alpha = 0.16f + reveal * 0.84f),
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
        Text(value, fontSize = 17.sp, color = Color(0xFFDCE8E2),
            fontWeight = FontWeight.Medium)
        Text(label, fontSize = 10.sp, color = Color(0xFF5E716A),
            letterSpacing = 0.6.sp)
    }
}
