package com.iqra.quran.ml

/**
 * The unit-level alignment kernel, and nothing else.
 *
 * Split out of [PhonemeMapper] for one reason: it must be **executable by CI**.
 * The recogniser, the Android framework and the gated model cannot run in a
 * hosted runner, but pure Kotlin can - so this file is where the matcher is
 * pinned. That matters because the kernel is the one part of this app that is
 * pure arithmetic on a numeric grid, which is exactly the kind of code that
 * looks correct, reads correctly, and indexes an array at -8.
 *
 * No `android.*`, no `Log`, no [com.iqra.quran.data.WordStatus]. If a future
 * change adds any of those, the JVM test stops compiling and CI says so.
 *
 * The interface is deliberately the smallest thing that is still useful:
 * for each expected unit, which emission index it aligned to, or -1. Everything
 * else the caller needs - matched flags, wrong flags, the word map, coverage -
 * is DERIVED from that, so there is exactly one traceback in the codebase and
 * the two cannot disagree.
 */
object UnitAligner {

    /**
     * Align [qry] (emitted units) against [ref] (expected units).
     *
     * Returns [refToQuery], where result[j] is the index in [qry] that expected
     * unit j was matched to, or -1 if it was deleted. Insertions consume a
     * [qry] index with no [ref] index, which is why [qry] may be longer than
     * [ref] - and that is deliberate: extra sound is how leading istiaadha and
     * basmala are prevented from depressing coverage.
     *
     * The tie-break order is **substitution, then deletion, then insertion**,
     * and it is load-bearing rather than cosmetic. Two alignments with equal
     * edit distance can trace different paths, and a different path means
     * different per-word verdicts. Reordering these three silently changes
     * every verdict in the app; `engine/replay/dp_source_parity.py` exists to
     * make sure nobody does it by accident.
     */
    fun refToQuery(qry: IntArray, ref: IntArray): IntArray {
        val n = qry.size
        val len = ref.size
        val out = IntArray(len) { -1 }
        if (len == 0 || n == 0) return out

        val width = len + 1
        // One flat ByteArray for the direction table. It holds only the values
        // 0, 1 and 2, so a byte each; the previous Array(n+1){IntArray(len+1)}
        // spent four bytes and n+1 allocations for three states. dp is two
        // rolling rows, because the traceback reads dir and never dp.
        val dir = ByteArray((n + 1) * width)
        var prev = IntArray(width) { it } // dp[0][j] = j
        var cur = IntArray(width)
        for (i in 1..n) {
            cur[0] = i // dp[i][0] = i
            val qi = qry[i - 1]
            val row = i * width
            for (j in 1..len) {
                val cost = if (qi == ref[j - 1]) 0 else 1
                val sub = prev[j - 1] + cost
                val del = prev[j] + 1
                val ins = cur[j - 1] + 1
                var best = sub
                var d = 0
                if (del < best) {
                    best = del
                    d = 1
                }
                if (ins < best) {
                    best = ins
                    d = 2
                }
                cur[j] = best
                dir[row + j] = d.toByte()
            }
            val t = prev
            prev = cur
            cur = t
        }

        var i = n
        var j = len
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0) {
                when (dir[i * width + j]) {
                    0.toByte() -> {
                        out[j - 1] = i - 1
                        i--
                        j--
                    }
                    1.toByte() -> i--
                    2.toByte() -> j--
                    // Unreachable for a well-formed table. Returning early is
                    // better than looping forever if one ever is not.
                    else -> return out
                }
            } else if (i > 0) {
                i--
            } else {
                j--
            }
        }
        return out
    }

    /**
     * Fold a whole set of alignments into one 64-bit digest, so Kotlin and the
     * Python reference in `engine/replay/dp_equivalence.py` can be pinned to the
     * same constant by comparing a single number.
     *
     * FNV-1a over the little-endian bytes of each case's (n, len, refToQuery).
     * Every case feeds the bytes 0xFF,0xFF,0xFF,0xFF for a -1, so a length
     * change cannot silently shift the stream into looking identical.
     */
    fun digest(cases: List<Triple<IntArray, IntArray, IntArray>>): Long {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325 as a signed Long
        fun mix(b: Int) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        fun mixInt(v: Int) {
            mix(v); mix(v ushr 8); mix(v ushr 16); mix(v ushr 24)
        }
        for ((qry, ref, r2q) in cases) {
            mixInt(qry.size)
            mixInt(ref.size)
            for (v in r2q) mixInt(v)
        }
        return h
    }

    /**
     * The canonical vector space both languages walk: every sequence pair up to
     * length 4 over a two-symbol alphabet (961 cases - this is what pins the
     * tie-breaking, because equal-cost paths are dense at these sizes), plus
     * four larger shapes per length up to 48 (192 cases) covering the asymmetry
     * the app actually sees: a slice longer than the ayah, a substitution, and
     * noise at both ends.
     *
     * Both sides derive the alphabet as `i % 2`, so nothing depends on a
     * literal in two files agreeing.
     */
    fun canonicalCases(): List<Triple<IntArray, IntArray, IntArray>> {
        val cases = ArrayList<Triple<IntArray, IntArray, IntArray>>(1200)
        fun seq(pattern: Int, len: Int) = IntArray(len) { (pattern ushr it) and 1 }

        for (lq in 0..4) {
            for (q in 0 until (1 shl lq)) {
                val query = seq(q, lq)
                for (lr in 0..4) {
                    for (r in 0 until (1 shl lr)) {
                        val ref = seq(r, lr)
                        cases.add(Triple(query, ref, refToQuery(query, ref)))
                    }
                }
            }
        }
        for (len in 1..48) {
            val ref = IntArray(len) { it % 2 }
            // 1: query is the expected minus its first unit
            cases.add(Triple(IntArray(len - 1) { ref[it + 1] }, ref,
                refToQuery(IntArray(len - 1) { ref[it + 1] }, ref)))
            // 2: one substitution in the middle
            val sub = ref.copyOf(); sub[len / 2] = 1 - sub[len / 2]
            cases.add(Triple(sub, ref, refToQuery(sub, ref)))
            // 3: noise at both ends
            val noisy = IntArray(len + 6)
            for (i in 0 until len) noisy[i + 3] = ref[i]
            cases.add(Triple(noisy, ref, refToQuery(noisy, ref)))
            // 4: the expected, reversed
            val rev = IntArray(len) { ref[len - 1 - it] }
            cases.add(Triple(rev, ref, refToQuery(rev, ref)))
        }
        return cases
    }
}
