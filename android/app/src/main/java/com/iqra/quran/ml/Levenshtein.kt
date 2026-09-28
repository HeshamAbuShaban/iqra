package com.iqra.quran.ml

/**
 * Levenshtein-based string similarity, ported from @tilawa/core/src/levenshtein.ts.
 * Mirrors python-Levenshtein's `ratio()` so matching behavior matches the
 * reference Tilawa engine.
 */
object Levenshtein {
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val (s, l) = if (a.length <= b.length) a to b else b to a
        val m = s.length
        val n = l.length
        var prev = IntArray(m + 1) { it }
        var curr = IntArray(m + 1)

        for (j in 1..n) {
            curr[0] = j
            for (i in 1..m) {
                val cost = if (s[i - 1] == l[j - 1]) 0 else 1
                curr[i] = minOf(prev[i] + 1, curr[i - 1] + 1, prev[i - 1] + cost)
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return prev[m]
    }

    fun ratio(a: String, b: String): Double {
        val lenSum = a.length + b.length
        if (lenSum == 0) return 1.0
        return (lenSum - distance(a, b)).toDouble() / lenSum
    }
}
