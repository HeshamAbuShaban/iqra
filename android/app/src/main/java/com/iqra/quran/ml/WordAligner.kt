package com.iqra.quran.ml

import com.iqra.quran.data.WordStatus

/**
 * Token-level alignment between a recited clip (greedy CTC token ids) and the
 * target verse's reference token sequence. Produces per-word correctness so the
 * UI can highlight what the user got right, skipped, or mispronounced.
 */
object WordAligner {

    /**
     * @param predicted greedy-decoded token ids of the recitation (no blanks)
     * @param targetWords per-word reference token-id lists of the target verse
     * @param targetArabic the verse's Arabic words (for display), index-aligned to [targetWords]
     * @return per-word status, aligned to [targetArabic]
     */
    fun align(
        predicted: IntArray,
        targetWords: List<IntArray>,
        targetArabic: List<String>,
    ): List<Pair<String, WordStatus>> {
        val p = predicted
        val flat = targetWords.flatMap { it.toList() }.toIntArray()
        val wordOf = targetWords.mapIndexed { wi, w -> List(w.size) { wi } }.flatten().toIntArray()

        val n = p.size
        val m = flat.size
        if (m == 0) return targetArabic.map { it to WordStatus.SKIPPED }

        // DP edit distance with backtrace pointers encoded as direction codes.
        val INF = Int.MAX_VALUE / 2
        val dp = Array(n + 1) { IntArray(m + 1) }
        val dir = Array(n + 1) { IntArray(m + 1) } // 0=match/sub, 1=gap-in-p (del from target), 2=gap-in-t (ins)
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val cost = if (p[i - 1] == flat[j - 1]) 0 else 1
                val sub = dp[i - 1][j - 1] + cost
                val del = dp[i - 1][j] + 1
                val ins = dp[i][j - 1] + 1
                var best = sub
                var d = 0
                if (del < best) { best = del; d = 1 }
                if (ins < best) { best = ins; d = 2 }
                dp[i][j] = best
                dir[i][j] = d
            }
        }

        // Backtrace.
        var i = n
        var j = m
        val matched = BooleanArray(m)
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && dir[i][j] == 0) {
                if (p[i - 1] == flat[j - 1]) matched[j - 1] = true
                i--; j--
            } else if (i > 0 && j > 0 && dir[i][j] == 1) {
                i-- // target token deleted (skipped)
            } else if (i > 0 && j > 0 && dir[i][j] == 2) {
                j-- // predicted token inserted (extra)
            } else if (i > 0) {
                i--
            } else {
                j--
            }
        }

        // Aggregate per word.
        val out = MutableList(targetArabic.size) { targetArabic[it] to WordStatus.CORRECT }
        for (wi in targetWords.indices) {
            val start = wordOf.indexOfFirst { it == wi }
            val end = wordOf.indexOfLast { it == wi }
            if (start < 0) continue
            val total = end - start + 1
            val correct = (start..end).count { matched[it] }
            val status = when {
                correct == 0 -> WordStatus.SKIPPED
                correct == total -> WordStatus.CORRECT
                else -> WordStatus.WRONG
            }
            out[wi] = targetArabic.getOrElse(wi) { "" } to status
        }
        return out
    }
}
