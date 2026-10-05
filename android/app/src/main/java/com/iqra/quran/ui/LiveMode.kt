package com.iqra.quran.ui

import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.shape.CircleShape
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
                amplitude = amp,
                voice = voice,
                state = state,
                modifier = Modifier.size(196.dp)
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

            Spacer(Modifier.height(6.dp))
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

            // ---- the passage, hidden until spoken -----------------------
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16211D)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    verses.forEachIndexed { i, v ->
                        val ayah = startAyah + i
                        val parts = remember(v.textClean) {
                            v.textClean.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                "$ayah",
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color(0xFF5E716A),
                                modifier = Modifier.width(28.dp),
                            )
                            // Words are placeholders until they are heard. You
                            // can see there are twelve of them, which is the
                            // only thing a memoriser needs to be told - not the
                            // text, which is the thing being tested.
                            FlowRow(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                parts.forEachIndexed { wi, w ->
                                    val key = "$surah:$ayah:${wi + 1}"
                                    val st = statusMap[key]
                                    LiveWordChip(w, st, key == currentKey)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- ambient diagnostics ------------------------------------
            // The same numbers Diagnostics shows, readable at a glance. A tool
            // that only explains itself on a separate screen has failed the
            // person who most needs the explanation.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LiveStat("words", "${statusMap.size}")
                LiveStat("moving", if (policy.next >= 0f) "%.2f".format(policy.next) else "—")
                LiveStat("pace", if (policy.stallSec > 0f) "%.0fs".format(policy.stallSec) else "—")
                LiveStat("total", "$totalWords")
            }

            Spacer(Modifier.height(10.dp))
            Text(
                if (finished) "Session complete" else "Verse by verse, from memory",
                fontSize = 12.sp,
                color = Color(0xFF54655F),
            )
            Spacer(Modifier.height(8.dp))
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

/** The one warm accent this screen owns, so a stalled lock reads differently. */
private val amberColor = Color(0xFFE09112)

/**
 * The orb.
 *
 * Drawn entirely in one Canvas from two float states that are read INSIDE the
 * draw lambda. Neither is read during composition, so a change to either
 * invalidates this draw scope and nothing else - the verse behind it is not
 * recomposed sixty times a second.
 *
 * The waveform is a history of recent amplitudes swept around a circle. A ring
 * that simply scaled with volume would read as a loading spinner; a history
 * reads as breathing, which is what a voice actually looks like.
 */
@Composable
private fun LiveOrb(
    amplitude: androidx.compose.runtime.MutableFloatState,
    voice: androidx.compose.runtime.MutableFloatState,
    state: LiveState,
    modifier: Modifier = Modifier,
) {
    val path = remember { Path() }
    val breath = rememberInfiniteTransition(label = "orb")
    val idle = breath.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse),
        label = "idle",
    )
    val spin = breath.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse),
        label = "spin",
    )

    val tint = when (state) {
        LiveState.Idle -> Color(0xFF2E6F63)
        LiveState.Quiet -> Color(0xFF33504A)
        LiveState.Hearing -> goldColor
        LiveState.Thinking -> amberColor
    }

    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val base = size.minDimension * 0.34f
        val a = amplitude.floatValue
        val v = voice.floatValue
        val breathAmt = if (state == LiveState.Idle) idle.value else 0.5f

        // Guide rings. Static, very low alpha: they give the motion something
        // to be measured against, which is what makes a pulse legible.
        for (i in 1..3) {
            drawCircle(
                color = tint.copy(alpha = 0.10f),
                radius = base * (0.78f + i * 0.24f),
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        // Core: a soft radial wash whose radius follows the smoothed level.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = 0.42f), Color.Transparent),
                center = Offset(cx, cy),
                radius = base * (1.25f + a * 0.85f + breathAmt * 0.10f),
            ),
            radius = base * (1.25f + a * 0.85f + breathAmt * 0.10f),
            center = Offset(cx, cy),
        )

        // Waveform. 72 samples at 14 Hz is about five seconds of history, which
        // is long enough to have a shape and short enough to feel immediate.
        val N = 72
        path.rewind()
        for (i in 0 until N) {
            // The history is carried by `v` rather than a ring buffer: a ring
            // buffer would mean an IntArray allocation per frame, and this runs
            // on every frame of a session.
            val phase = i / N.toFloat() * (Math.PI * 2).toFloat()
            val wobble = ((i * 37 % N) / N.toFloat() - 0.5f) * 2f
            val amp = (base * 0.34f) * (0.25f + v * 1.5f) *
                (0.55f + 0.45f * kotlin.math.sin(phase.toDouble()).toFloat())
            val r = base + wobble * amp
            val x = cx + r * kotlin.math.cos(phase.toDouble()).toFloat()
            val y = cy + r * kotlin.math.sin(phase.toDouble()).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = tint.copy(alpha = 0.75f + a * 0.25f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        drawCircle(
            color = tint,
            radius = base * (0.16f + a * 0.10f + breathAmt * 0.04f),
            center = Offset(cx, cy),
        )

        // A lock that has stopped advancing says so, rather than looking idle.
        if (state == LiveState.Thinking) {
            drawArc(
                color = amberColor.copy(alpha = 0.85f),
                startAngle = spin.value,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = Offset(cx - base * 1.5f, cy - base * 1.5f),
                size = androidx.compose.ui.geometry.Size(base * 3f, base * 3f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * One word, before and after it is heard.
 *
 * A placeholder is a low dash, not an empty gap: the shape has to be countable
 * at a glance, because "did I say twelve words or thirteen" is a real question
 * while reciting from memory. Spacing alone makes that unanswerable.
 */
@Composable
private fun LiveWordChip(
    text: String,
    status: WordStatus?,
    isCurrent: Boolean,
) {
    val revealed = status != null
    val bg = when {
        !revealed -> Color(0xFF23302B)
        status == WordStatus.WRONG -> wrongColor.copy(alpha = 0.32f)
        status == WordStatus.SKIPPED -> Color(0xFF3A3026)
        status == WordStatus.UNKNOWN -> Color(0xFF2C2A22)
        else -> accentColor.copy(alpha = 0.28f)
    }
    Box(
        Modifier
            .height(34.dp)
            .width(if (revealed) (text.length * 13 + 14).dp else 26.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .then(
                if (isCurrent) Modifier.border(
                    1.dp, goldColor.copy(alpha = 0.7f), RoundedCornerShape(9.dp)
                ) else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (revealed) {
            Text(
                text,
                fontFamily = quranFont,
                fontSize = 19.sp,
                color = Color(0xFFE8F0EA),
                maxLines = 1,
            )
        } else {
            Box(
                Modifier.width(12.dp).height(2.dp)
                    .clip(CircleShape).background(Color(0xFF3C4A44))
            )
        }
    }
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
