package com.iqra.quran.ui

import androidx.annotation.StringRes
import com.iqra.quran.R

/**
 * How many times a kind is worth counting.
 *
 * A recurring one is a per-frame condition, not an event: a word whose audio
 * is slightly ambiguous stays ambiguous on every frame it is judged, so
 * accumulating it produced 1,455 "occurrences" across 85 words in one 476 s
 * session. That number reads like a measurement of something and measures
 * only how long the microphone was open. An advisory is a property of the
 * WORD, so the honest count of a recurring advisory is one per word.
 *
 * The rare ones are events - a pipeline reset happens, a window comes up empty
 * - and those are worth a tally that can show a repeated trigger.
 */
enum class AdvisoryCounting {
    /** Count each occurrence; the condition is event-shaped. */
    PER_EVENT,
    /** Count each word once; the condition is frame-shaped. */
    ONCE_PER_WORD,
}

data class Advisory(
    val kind: AdvisoryKind,
    val count: Int = 1,
) {
    /** Whether another sighting of [other] should raise this tally. */
    fun shouldCount(other: Advisory): Boolean =
        when (kind.counting) {
            AdvisoryCounting.PER_EVENT -> true
            AdvisoryCounting.ONCE_PER_WORD -> other.kind == kind
        }

    fun merge(other: Advisory): Advisory {
        val next = if (other.kind.severity > kind.severity) other.kind else kind
        return Advisory(next, count + other.count)
    }
}

/**
 * An advisory is NOT a verdict. It says "the engine saw something it cannot
 * decide, or a ruling it cannot model may have happened". It must never be
 * painted as an error, never count as WRONG, and never advance the mistake
 * streak. The single rule of the alarm channel: an advisory informs, it does
 * not accuse.
 *
 * Severity decides which kind the user is shown when a word collects several;
 * it must never decide whether the word is blamed.
 *
 * The label is a resource id, not a string: these sentences reach the screen
 * (session report) and the app speaks two languages. The kind's NAME stays the
 * wire identity - records, replay tools and the engine all match on it.
 */
enum class AdvisoryKind(
    @StringRes val labelRes: Int,
    val severity: Int,
    val counting: AdvisoryCounting,
) {
    /** A marked stop boundary was crossed while scoring — a legal stop may have occurred. */
    MISSED_RULING_POSSIBLE(
        R.string.advisory_missed_ruling,
        4,
        AdvisoryCounting.PER_EVENT,
    ),
    /** A word carries a final letter the register does not require to be sounded. */
    UNMODELLED_FINAL(
        R.string.advisory_unmodelled_final,
        4,
        AdvisoryCounting.PER_EVENT,
    ),
    /** Audio matched neither the expected nor any supported competing form. */
    UNSUPPORTED_REALISATION(
        R.string.advisory_unsupported_realisation,
        3,
        AdvisoryCounting.PER_EVENT,
    ),
    /** The audio window held nothing usable for the words it covered. */
    NO_AUDIO_WINDOW(
        R.string.advisory_no_audio_window,
        2,
        AdvisoryCounting.PER_EVENT,
    ),
    /** Audio was contradictory, but below the evidence floor to call WRONG. */
    LOW_EVIDENCE(
        R.string.advisory_low_evidence,
        2,
        AdvisoryCounting.ONCE_PER_WORD,
    ),
    /** No previous ayah's arrival anchors the window, so its leading audio is unattributable. */
    UNANCHORED_AUDIO(
        R.string.advisory_unanchored_audio,
        3,
        AdvisoryCounting.ONCE_PER_WORD,
    ),
    /** The decoder was starved, so the frame cannot speak for the word. */
    DECODER_STARVATION(
        R.string.advisory_decoder_starvation,
        1,
        AdvisoryCounting.ONCE_PER_WORD,
    ),
}
