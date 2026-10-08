package com.iqra.quran.ui

/**
 * An advisory is NOT a verdict. It says "the engine saw something it cannot
 * decide, or a ruling it cannot model may have happened". It must never be
 * painted as an error, never count as WRONG, and never advance the mistake
 * streak. The single rule of the alarm channel: an advisory informs, it does
 * not accuse.
 *
 * Confidence ordering matters because a word can pick up several advisories
 * over a session: the worst-looking one is what the user sees, while every
 * occurrence is counted.
 */
enum class AdvisoryKind(val label: String, val severity: Int) {
    /** A marked stop boundary was crossed while scoring — a legal stop may have occurred. */
    MISSED_RULING_POSSIBLE("A stop here may have been legal; the engine could not model the join", 4),
    /** Audio matched neither the expected nor any supported competing form. */
    UNSUPPORTED_REALISATION("A reading the engine cannot represent may have occurred", 3),
    /** The audio window held nothing usable for the words it covered. */
    NO_AUDIO_WINDOW("The engine had no audio for this word", 2),
    /** Audio was contradictory, but below the evidence floor to call WRONG. */
    LOW_EVIDENCE("The engine heard a contradiction it would not stand behind", 2),
    /** The decoder was starved, so the frame cannot speak for the word. */
    DECODER_STARVATION("The audio stream reset before the engine heard this", 1),
}

data class Advisory(val kind: AdvisoryKind, val count: Int = 1) {
    fun merge(other: Advisory): Advisory =
        if (other.kind.severity > kind.severity) Advisory(other.kind, count + other.count)
        else Advisory(kind, count + other.count)
}
