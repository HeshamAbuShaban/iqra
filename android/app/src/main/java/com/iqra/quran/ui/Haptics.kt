package com.iqra.quran.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqra.quran.data.WordStatus

/** At least this long between two buzzes, so a cluster of wrongs is one signal. */
private const val MIN_GAP_MS = 1500L

/** Short enough to read as a tick, long enough to feel on a table. */
private const val BUZZ_MS = 45L

/**
 * Buzz once when a word's status first becomes WRONG in a session.
 *
 * The reciter's eyes are on the page or on nothing while reciting, so colour is
 * not a channel that reaches them. This is the channel that does, and the rules
 * below are what keep it from accusing anyone falsely:
 *
 *  - Only WRONG fires. UNKNOWN and SKIPPED are the absence of evidence and
 *    advisories are notes, so none of them is allowed to feel like a mistake.
 *  - A word buzzes once per session. The set is keyed on `sessionEpoch`, so a
 *    new session re-arms it, and WRONG -> something else -> WRONG does not
 *    buzz twice. That is why this watches the map as a whole rather than
 *    living beside a word's paint: a word composable runs many times and would
 *    fire on each pass.
 *  - A cluster is one buzz. Verdicts can land several at a time and a phone
 *    vibrating five times in two seconds reads as a fault; suppressed words
 *    are still marked seen so they cannot buzz late.
 *  - Nothing fires while no session records. `statusMap` retains paint from
 *    the previous session until the new one clears it, and stale red is not a
 *    new accusation.
 *
 * Called by the Live screen; the reader screen can call it too.
 */
@Composable
fun WrongVerdictBuzz(vm: PracticeViewModel) {
    val ctx = LocalContext.current
    val tick by ReaderPrefs.tick.collectAsStateWithLifecycle()
    val enabled = remember(ctx, tick) { ReaderPrefs.vibrateOnWrong(ctx) }
    val statusMap by vm.statusMap.collectAsStateWithLifecycle()
    val epoch by vm.sessionEpoch.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val vibrator = remember(ctx) { systemVibrator(ctx) }
    val buzzed = remember(epoch) { mutableSetOf<String>() }
    // Deliberately not keyed on the epoch: a handoff clears the seen set and
    // the debounce still has to hold across it.
    var lastAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(statusMap, epoch, recording, enabled) {
        if (!recording) return@LaunchedEffect
        var fresh = false
        statusMap.forEach { (key, st) ->
            if (st == WordStatus.WRONG && buzzed.add(key)) fresh = true
        }
        if (!fresh || !enabled || vibrator == null) return@LaunchedEffect
        val now = SystemClock.elapsedRealtime()
        if (now - lastAt < MIN_GAP_MS) return@LaunchedEffect
        lastAt = now
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(BUZZ_MS)
            }
        }
    }
}

/** The phone's default vibrator, or null when it has none. */
private fun systemVibrator(ctx: Context): Vibrator? = runCatching {
    val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    v?.takeIf { it.hasVibrator() }
}.getOrNull()
