package com.iqra.quran.data

data class Verse(
    val surah: Int,
    val ayah: Int,
    val textUthmani: String,
    val textClean: String,
    val surahName: String,
    val surahNameEn: String,
)

data class SurahInfo(
    val number: Int,
    val name: String,
    val nameEn: String,
    val ayahCount: Int,
    val revelationType: String = "Meccan",
    val startPage: Int = 1,
    val endPage: Int = 1,
    val juz: Int = 1,
)

data class JuzInfo(
    val number: Int,
    val startPage: Int,
    val surahName: String,
    val surahNameEn: String,
)

data class WordResult(
    val arabic: String,
    val status: WordStatus,
)

/**
 * Per-word recitation verdict.
 *
 * UNKNOWN is the state the matcher had to grow: a word can be partly covered
 * with no disagreement at all, which is NOT evidence of an error. Calling that
 * WRONG (as an earlier rule did) turned a word red whenever a NEIGHBOUR was
 * skipped, because the alignment shifts and the neighbour absorbs the deletion
 * as substitutions. UNKNOWN is the neutral reading: not accused, not revealed.
 */
enum class WordStatus { CORRECT, SKIPPED, WRONG, EXTRA, UNKNOWN }

/**
 * Typed highlight layers, borrowed in adapted form from quran_android's
 * SELECTION / AUDIO / AUDIO_WORD / BOOKMARK grammar. Lower ordinal wins
 * when several layers claim the same word, so recitation, selection and
 * reference-audio visuals never fight each other.
 */
enum class HighlightLayer {
    WRONG,
    RECITATION_WORD,
    SELECTION,
    AUDIO_WORD,
    AUDIO,
    RECITATION_AYAH,
    NONE,
    /** Word never judged yet: clean in normal mode, masked in hide mode. */
    UNSTARTED,
}
