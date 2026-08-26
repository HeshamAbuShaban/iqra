package com.iqra.quran.ml

/**
 * Arabic text normalization, ported from @tilawa/core/src/normalizer.ts.
 * Strips diacritics, tatweel, BOM, and folds common letter variants so that
 * the CTC-decoded transcript and the reference verse text live in the same space.
 */
object ArabicNormalizer {
    private val DIACRITICS =
        Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06DE\\u06DF-\\u06ED\\u0640]")

    private val NORM_MAP = mapOf(
        '\u0623' to '\u0627', // أ -> ا
        '\u0625' to '\u0627', // إ -> ا
        '\u0622' to '\u0627', // آ -> ا
        '\u0671' to '\u0627', // ٱ -> ا
        '\u0629' to '\u0647', // ة -> ه
        '\u0649' to '\u064A', // ى -> ي
    )

    fun normalize(text: String): String {
        var t = text.replace("\uFEFF", "")
        t = DIACRITICS.replace(t, "")
        val sb = StringBuilder(t.length)
        for (ch in t) sb.append(NORM_MAP[ch] ?: ch)
        return sb.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
