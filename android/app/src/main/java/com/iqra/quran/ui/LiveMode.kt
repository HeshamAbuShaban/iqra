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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.data.Mushaf
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.WordStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sin
import com.iqra.quran.ui.theme.LiveColors
import com.iqra.quran.ui.theme.LocalIqraColors
import com.iqra.quran.ui.theme.LocalLiveColors
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.border
import com.iqra.quran.ui.theme.IqraColors
import androidx.compose.ui.geometry.Size

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
    val prefTick by ReaderPrefs.tick.collectAsStateWithLifecycle()
    val rtl = remember(ctx, prefTick) { ReaderPrefs.rightToLeft(ctx) }
    // Wraps the whole screen, top bar included: a mirrored title beside an
    // unmirrored word list would be the same mismatch in a smaller place.
    CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        LiveModeContent(vm, data, surah, startAyah, ayahCount, onRequestMic, onExit, onFinished)
    }
}

@Composable
private fun LiveModeContent(
    vm: PracticeViewModel,
    data: QuranData,
    surah: Int,
    startAyah: Int,
    ayahCount: Int,
    onRequestMic: () -> Unit,
    onExit: () -> Unit,
    onFinished: () -> Unit,
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
    // The ENGINE's pause, not a local flag. `vm.paused` is written by
    // `setListening` and read back by the poll loop, so the icon and the thing
    // that is actually not judging can never disagree.
    val paused by vm.paused.collectAsStateWithLifecycle()
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
    // NORMALISE THE SCOPE ONCE, HERE.
    //
    // ChooseScreen sends `count = 0` for its "The whole surah" chip - which is the
    // chip it opens on, the default, the option with the least reading attached.
    // Left unnormalised, `startAyah until startAyah + 0` is an EMPTY range: the
    // scope had no ayat, the denominator was 0, and `scopeAyahs.last()` in the
    // auto-finish threw NoSuchElementException on the first frame. Picking the
    // obvious option crashed before the screen had drawn anything.
    //
    // So `0` is resolved here, once, to "to the end of the surah", and every
    // downstream use reads the resolved number. Both bounds are clamped, because
    // the same screen is reachable by a restored `rememberSaveable` count from a
    // previous surah, and a range that runs past the last ayah would put the
    // denominator and the ayat on screen out of step again.
    val surahAyat = data.surahInfo(surah)?.ayahCount ?: 7
    val firstAyah = startAyah.coerceIn(1, surahAyat)
    val scopeCount = if (ayahCount <= 0) surahAyat - firstAyah + 1
        else ayahCount.coerceIn(1, surahAyat - firstAyah + 1)
    val scopeVerses = remember(surah, firstAyah, scopeCount) {
        (firstAyah until firstAyah + scopeCount).mapNotNull { data.getVerse(surah, it) }
    }
    val verses = scopeVerses
    val wordsVersion by vm.wordsVersion.collectAsStateWithLifecycle()

    // Latched, and that is the whole point.
    //
    // `standWordsFor` reads the ENGINE's active surah, which is empty until
    // startRecite calls loadSurah - so a plain `remember` captured 0 and the bar
    // read "0 of 0 heard" for the entire pre-roll. But re-keying on wordsVersion
    // is what let a handoff silently re-key the denominator to the next surah.
    //
    // So the value is latched the first time it is real and never recomputed.
    // That happens when the microphone opens, which is before any handoff could,
    // so it captures this scope's words and then refuses to be moved by anything
    // the engine does afterwards.
    // The SCOPE, and only the scope.
    //
    // Three separate things used to be called "the scope" and none of them was the
    // range the user picked:
    //
    //  - `standWordsFor` reads the ENGINE's active surah, so a test of 2:255 alone
    //    latched Al-Baqarah's 6,116 words.
    //  - `scopeProgress` is built from every ayah of the active surah
    //    (PracticeViewModel.rebuildScopeProgress), so its totals run past the end
    //    of the chosen range whenever the lock walks on.
    //  - after a handoff, both describe the NEXT surah entirely.
    //
    // So the denominator is counted from QuranData over exactly the chosen ayat,
    // once, and never moved; and the numerator is filtered back into that same
    // range before it is summed. A count can then never exceed its denominator,
    // because the two are drawn from the same set of ayat.
    // The ayah being recited, with a fallback for before the engine has said.
    val lockedHint = scopeProgress.firstOrNull { it.isLocked }?.ayah
    val scopeAyahs = remember(surah, firstAyah, scopeCount) {
        (firstAyah until firstAyah + scopeCount).toList()
    }
    // The denominator is the SAME word population the statuses are keyed by -
    // `standWordsFor`, which is what the status keys are built from - with one
    // guard added on top.
    //
    // The guard is the whole fix. `standWordsFor(ayah)` is scoped to whatever surah
    // the ENGINE has loaded, so after a handoff it answers about the next surah
    // entirely; each `MushafWord` carries its own `surah`, so filtering by it
    // makes the count describe the scope the user chose and nothing else. Counting
    // the verse-end marker as a word is the other half - the mushaf appends the
    // ayah number to the last word of each ayah, and it is not a word anyone said.
    //
    // Before the engine has loaded the surah this reads empty, so the mushaf pages
    // are used as the pre-session figure: the same words from the same source,
    // counted once the engine catches up.
    val mushafPages by vm.mushaf.collectAsStateWithLifecycle()
    val liveScopeWords = remember(scopeAyahs, vm.wordsVersion.collectAsStateWithLifecycle().value) {
        scopeAyahs.sumOf { a ->
            vm.standWordsFor(a).count { it.surah == surah && !it.isVerseEnd }
        }
    }
    val totalWords = remember(scopeAyahs, liveScopeWords, mushafPages) {
        if (liveScopeWords > 0) liveScopeWords
        else {
            val pages = mushafPages ?: return@remember 0
            val inScope = scopeAyahs.toHashSet()
            Mushaf.wordsForSurah(pages, surah)
                .count { it.verse in inScope && it.surah == surah && !it.isVerseEnd }
        }
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

    // The screen no longer sleeps, and it is not this screen's job any more:
    // `MainActivity` holds `FLAG_KEEP_SCREEN_ON` for the whole app, because a
    // recitation, a reading and a walk-around are the same phone on the same
    // table. This block used to be unconditional, which meant the one screen
    // where the reciter most needed it was also the only screen they could not
    // opt out of - while every other screen ran against the phone's own timeout.

    // Always listening. The references have no Begin button at all - the
    // microphone is live from the moment the screen appears and the controls only
    // offer pause and end. That is also the honest model for the three cases the
    // tray serves: the phone can sit on a table while the reciter walks the room,
    // and nobody has to find and press anything first.
    LaunchedEffect(Unit) {
        if (!started) {
            startedAt = System.currentTimeMillis()
            started = true
            onRequestMic()
        }
    }

    // Amplitude poll. 70 ms is about 14 Hz, fast enough that the orb reads as
    // continuous and slow enough that it is 14 wake-ups a second rather than
    // 60. The smoothing is what makes speech look like speech instead of a
    // strobe.
    LaunchedEffect(recording) {
        if (!recording) { amp.floatValue = 0f; return@LaunchedEffect }
        var last = 0f
        var floor = 0.04f
        while (isActive) {
            // Zero while paused, and not merely scaled down afterwards. The
            // recorder is deliberately left running - that is how the session
            // survives a pause at all - so a real reading here would keep the
            // wave undulating and keep re-arming the onset detector for audio
            // the engine is deliberately not judging. The reciter taps Pause and
            // the screen has to go quiet, because the one thing they asked for
            // is not being listened to.
            val raw = withContext(Dispatchers.IO) { if (paused) 0f else vm.micLevel() }
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

    // Paused sits ABOVE the engine-derived states, not below them, and the
    // ordering is the fix.
    //
    // The engine keeps running while paused - that is deliberate, see
    // `setListening` - so `vadSpeech` and `stallSec` both keep reporting
    // normally. Derived below them, a paused screen would still say "Go ahead"
    // or "Let me think..." with the words appearing as if nothing had happened,
    // because the microphone is genuinely still open. The reciter had asked for
    // silence and the screen argued with them.
    val state = when {
        !started || finished -> LiveState.Idle
        !recording -> LiveState.Idle
        paused -> LiveState.Paused
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
                    ltr(// The name, not the number. "Surah 1" is a lookup; the name
                    // is what the reciter is holding in their head.
                    listOfNotNull(
                        data.surahInfo(surah)?.nameEn,
                        "$scopeCount ${if (scopeCount == 1) "ayah" else "ayat"}",
                    ).joinToString("  ·  ")),
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
                colours = lcG,
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
                        // Says what Pause did and how to undo it. "Listening"
                        // here was the actual complaint: the engine really is
                        // still open, so every other string on this screen was
                        // true and still read as "you are being judged".
                        LiveState.Paused -> "Paused — tap ▶ to resume"
                    },
                    fontSize = 13.sp,
                    color = when (state) {
                        LiveState.Thinking -> lc.crestHot
                        LiveState.Quiet -> Color(0xFF64748B)
                        LiveState.Paused -> lcG.ink
                        else -> Color(0xFF8FA0BE)
                    },
                )

                // The passage. TWO ayat on screen: the one being recited, and the
                // next as a ghost.
                //
                // Rendering the whole range means a surah test puts 286 ayah
                // numbers on the screen at once, and each becomes a visible skeleton
                // of unhinted placeholders the moment its lock window opens - the
                // reciter can see how much is LEFT, which is both useless and
                // discouraging. It is the same failure as showing a verse before it
                // has been said, one level up.
                //
                // The ghost carries no words at all until the ayah before it is
                // finished, at which point it materialises and becomes the live one.
                // Anything further ahead is never composed, so it cannot leak.
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    val focus = activeVerse ?: lockedHint ?: scopeAyahs.first()
                    listOf(focus, focus + 1).forEach { ayah ->
                        val reached = ayah <= focus
                        if (!reached) {
                            // A thin rule and the ayah number only: the place this
                            // passage continues, with nothing of it shown.
                            Row(
                                Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    ltr("$ayah"),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = lcG.inkFaint.copy(alpha = 0.35f),
                                    modifier = Modifier.width(26.dp),
                                )
                                Box(
                                    Modifier.weight(1f).height(1.dp)
                                        .background(lcG.hairline),
                                )
                            }
                            return@forEach
                        }
                        val parts = remember(ayah, vm.wordsVersion.collectAsStateWithLifecycle().value) {
                            vm.standWordsFor(ayah).map { it.text }
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                            Text(
                                ltr("$ayah"),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = lcG.inkFaint,
                                modifier = Modifier.width(26.dp).padding(top = 8.dp),
                            )
                            FlowRow(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                parts.forEachIndexed { wi, w ->
                                    val key = "$surah:$ayah:${wi + 1}"
                                    LiveWord(
                                        w,
                                        statusMap[key],
                                        key == currentKey,
                                    )
                                }
                            }
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
                // paint map, pruned to the lock's last three ayat, so a finished
                // surah always read about 55%.
                //
                // Counted from the archive now, so it is complete. `judged` is
                // CORRECT + WRONG - a verdict, never UNKNOWN or SKIPPED.
                // Filtered to the chosen ayat AND the active surah, so a handoff or
                // a lock that walked past the range cannot inflate the count past
                // its own denominator.
                val inScope = scopeProgress.filter { it.ayah in scopeAyahs }
                val judged = minOf(inScope.sumOf { it.decided }, totalWords)
                Text(ltr("$judged of $totalWords heard"), fontSize = 12.sp, color = lcG.inkMuted)
                Spacer(Modifier.height(16.dp))

                // The controls: a pair, close together, on the dark ground below
                // the light - the same arrangement as the references. Dark circle
                // for pause, red circle with a cross for end. Not a tray of
                // options: two things, the only two things worth deciding mid-
                // recitation.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundControl(
                        glyph = if (recording && !paused) Icons.Filled.Pause
                                else Icons.Filled.PlayArrow,
                        tint = if (paused) lcG.gold else lcG.ink,
                        // While paused this control is not "pause" any more, it is
                        // "resume", and it is the only thing on the screen that
                        // does anything. Filling it says so before it is read.
                        track = if (paused) lcG.gold.copy(alpha = 0.22f)
                                else lcG.surfaceHigh,
                        border = if (paused) lcG.gold.copy(alpha = 0.55f)
                                 else Color.Transparent,
                        onClick = {
                            // This used to call `stopRecite("paused")` - the same
                            // call as End - so tapping Pause ended the recitation,
                            // flushed the record, and the auto-finish took the user
                            // to the report. `setListening` suspends judging without
                            // touching any session state; see its KDoc for why there
                            // is no stop/start alternative.
                            vm.setListening(!paused)
                        },
                    )
                    RoundControl(
                        glyph = Icons.Filled.Close,
                        tint = lcG.chartBad,
                        // The end control is the one red thing on the screen, and
                        // it is filled rather than tinted: ending a session is the
                        // only irreversible action here, and it should look it.
                        track = lcG.chartBad.copy(alpha = 0.18f),
                        border = lcG.chartBad.copy(alpha = 0.5f),
                        onClick = { vm.stopRecite("live done"); finished = true },
                    )
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

    // The test is over when the passage is.
    //
    // Reciting the final ayah to the end is the natural end of a test, and asking
    // someone to then find and press End is asking them to do a gesture that has
    // nothing left to do. Coverage is measured on the FINAL ayah of the scope only,
    // and "covered" means every word carries a verdict - correct or wrong. A wrong
    // word still counts as covered: the reciter said it, the engine heard it, and
    // insisting on correctness here would mean a test could never end while the
    // person still had something to work on, which is the opposite of what the
    // report is for.
    //
    // End stays available throughout, so nothing is taken away - only the one
    // extra tap is removed.
    val lastAyah = scopeAyahs.last()
    val lastProgress = scopeProgress.firstOrNull { it.ayah == lastAyah }
    LaunchedEffect(lastAyah, lastProgress?.decided, lastProgress?.words) {
        val w = lastProgress?.words ?: 0
        if (w > 0 && (lastProgress?.decided ?: 0) >= w) {
            if (recording) vm.stopRecite("scope complete")
            finished = true
        }
    }

    // No "Done" dialog. A session that judged nothing needs more than a sentence in
    // a box, and one that went well deserves the report. Finishing navigates
    // straight to the session report, which is the only surface that can show what
    // actually happened across the whole scope.
    if (finished) {
        LaunchedEffect(finished) { onFinished() }
    }
}

private enum class LiveState { Idle, Quiet, Hearing, Thinking, Paused }


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
 * The wave.
 *
 * A wide band across the bottom whose TOP edge undulates. Not a blob, not a card,
 * not an ellipse - an earlier attempt built the perimeter from `cos`/`sin` around a
 * centre, which is literally an ellipse and read as a bubble sitting on the
 * screen. A horizontal wave has no closed silhouette to give away: it runs off
 * both edges and into the bottom of the frame, so there is nothing that could be
 * mistaken for a contained shape.
 *
 * The edge carries the microphone history read newest-first at a phase that varies
 * with x, so one syllable makes the crest travel sideways instead of the whole
 * band swelling in and out. Two sine octaves underneath keep it alive in silence.
 *
 * COLOUR
 * ------
 * Several strong colours at once, which is what gives the references their look
 * and what a single tint can never do. Google's version is blue with red and
 * white blooming through it; ours takes its colours from the active theme - the
 * accent, the gold, the lit gold, and the chart's second series - laid in as
 * overlapping soft blooms along the width and clipped to the band. Change the
 * theme and the wave changes with it, which is the whole reason the colours are
 * derived rather than declared.
 *
 * There is no outline. A stroke is the clearest possible signal that a shape was
 * drawn rather than emitted, and it was the single worst thing about the previous
 * version.
 */
@Composable
private fun HorizonWave(
    lc: LiveColors,
    colours: IqraColors,
    history: FloatArray,
    head: androidx.compose.runtime.MutableIntState,
    level: androidx.compose.runtime.MutableFloatState,
    onset: androidx.compose.runtime.MutableFloatState,
    brightness: Float,
    animate: Boolean,
    stall: Float,
    state: LiveState,
    modifier: Modifier = Modifier,
) {
    val band = remember { Path() }
    val flow = rememberInfiniteTransition(label = "flow")
    val drift by flow.animateFloat(
        0f, 6.2831855f,
        infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "drift")
    val bloom by flow.animateFloat(
        0f, 6.2831855f,
        infiniteRepeatable(tween(15000, easing = LinearEasing)), label = "bloom")
    val phD = if (animate) drift else 1.1f
    val phB = if (animate) bloom else 2.2f

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val n = history.size
        val hd = head.intValue
        val lv = level.floatValue.coerceIn(0f, 1f)
        val on = onset.floatValue.coerceIn(0f, 1f)
        val dull = (stall / 5f).coerceIn(0f, 1f)
        // Paused dims the band rather than hiding it. The wave stays on screen
        // so the screen does not jump when it comes back - a flat crest that
        // brightens on resume reads as "it woke up", which is exactly what
        // happened. Hiding it would read as a crash and a relayout.
        val clear = (1f - 0.4f * dull) *
            (if (state == LiveState.Thinking) 0.75f else 1f) *
            (if (state == LiveState.Paused) 0.45f else 1f)

        // The crest rides high when quiet and stays high when loud: the band is
        // always present, and the VOICE adds height on top of it rather than
        // summoning the whole thing into existence.
        val baseY = h * 0.34f
        val lift = h * (0.16f + 0.20f * lv + 0.15f * on) * clear

        band.reset()
        band.moveTo(0f, h)
        band.lineTo(0f, baseY - lift)
        val steps = 96
        val crestY = FloatArray(steps + 1)
        for (i in 0..steps) {
            val x = w * i / steps
            val u = i / steps.toFloat()
            var shape = 0.5f
            shape += 0.30f * sin(phD + u * 5.2f)
            shape += 0.16f * sin(phD * 1.7f + u * 9.1f + 1.1f)
            shape += 0.09f * sin(phD * 2.4f + u * 15.3f + 2.4f)
            val k = (u * 0.9f * n).toInt().coerceIn(0, n - 1)
            val hv = history[((hd - 1 - k) % n + n) % n].coerceIn(0f, 1f)
            val y = baseY - lift * (0.34f + 0.42f * shape + 0.72f * hv)
            crestY[i] = y
            band.lineTo(x, y)
        }
        band.lineTo(w, h)
        band.close()

        // The glow the band throws above itself, so the light is not confined to
        // its own silhouette.
        val glowTop = crestY.min()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                ((glowTop / h) * 0.75f).coerceIn(0.02f, 0.95f) to
                    colours.accent.copy(alpha = 0.10f * clear),
                1f to colours.accent.copy(alpha = 0.03f * clear),
            ),
            topLeft = Offset(0f, glowTop * 0.4f),
            size = Size(w, h - glowTop * 0.4f),
        )

        // Depth: bright along the crest, deepening downward.
        drawPath(
            band,
            Brush.verticalGradient(
                0.00f to colours.accent.copy(alpha = (0.55f + 0.30f * lv) * clear),
                0.30f to colours.accent.copy(alpha = (0.72f + 0.20f * lv) * clear),
                1.00f to colours.ground.copy(alpha = 0.95f),
            ),
        )

        // The several strong colours: overlapping blooms along the width, clipped
        // to the band so they belong to it.
        clipPath(band) {
            // Four tints, and they have to stay DISTINCT or the wave is a wash.
            // The deepest and the lit end of the theme's own ramp, pushed apart in
            // lightness so they separate on a dark ground even when the theme's
            // accent is itself pale - Ink-and-Silver's gold is nearly white, and
            // an unforced list of [accent, gold, goldBright] is three of the same
            // colour, which is why this read as monochrome.
            val tints = listOf(
                colours.accent,
                colours.goldBright,
                colours.series2,
                colours.accent.copy(
                    red = (colours.accent.red * 0.45f).coerceIn(0f, 1f),
                    green = (colours.accent.green * 0.55f).coerceIn(0f, 1f),
                    blue = (colours.accent.blue * 0.85f).coerceIn(0f, 1f),
                ),
            )
            for (i in tints.indices) {
                val ph = phB + i * 1.7f
                val cxp = w * (0.12f + 0.25f * i + 0.08f * sin(ph))
                val cyp = h * (0.55f + 0.18f * sin(ph * 1.3f + i))
                val r = w * (0.26f + 0.10f * sin(ph * 0.9f + i * 1.1f))
                val c = androidx.compose.ui.geometry.Offset(cxp, cyp)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to tints[i].copy(alpha = (0.55f + 0.30f * lv) * clear),
                        0.5f to tints[i].copy(alpha = (0.24f + 0.20f * lv) * clear),
                        1f to tints[i].copy(alpha = 0f),
                        center = c, radius = r,
                    ),
                    radius = r, center = c,
                )
            }
        }
    }
}


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
 * One circular control.
 *
 * A plain filled circle with a glyph in it and nothing else. An earlier version
 * put a caption underneath, which made it a two-line button rather than the
 * single round object the references use, and made the pair look like a tray
 * rather than two controls.
 */
@Composable
private fun RoundControl(
    glyph: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    track: Color,
    border: Color = Color.Transparent,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(if (enabled) track else track.copy(alpha = 0.4f))
            .then(
                if (border == Color.Transparent) Modifier
                else Modifier.border(1.dp, border, CircleShape)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            glyph, contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(24.dp),
        )
    }
}
