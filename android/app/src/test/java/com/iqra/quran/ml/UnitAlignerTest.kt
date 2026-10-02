package com.iqra.quran.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The alignment kernel, executed by CI.
 *
 * This test exists because the kernel was previously unreachable from CI: it sat
 * inside an Android class that needed the framework and a gated model to load,
 * so every proposal to change it arrived unreviewable and two of them were
 * broken. Two out of two. The honest response is not "review harder", it is to
 * make the change executable before it is proposed.
 *
 * Failures here are diagnosable, not just a number: the digest assertion is
 * last, and the structural invariants run first.
 */
class UnitAlignerTest {

    // ------------------------------------------------------- readable semantics

    @Test
    fun `exact match aligns every expected unit to itself`() {
        val ref = intArrayOf(1, 2, 3, 1, 2)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), UnitAligner.refToQuery(ref, ref))
    }

    @Test
    fun `an empty expectation aligns nothing and never throws`() {
        assertArrayEquals(intArrayOf(), UnitAligner.refToQuery(intArrayOf(1, 2, 3), intArrayOf()))
        assertArrayEquals(intArrayOf(-1, -1), UnitAligner.refToQuery(intArrayOf(), intArrayOf(7, 8)))
        assertArrayEquals(intArrayOf(-1, -1, -1), UnitAligner.refToQuery(intArrayOf(), intArrayOf(7, 8, 9)))
    }

    @Test
    fun `substitution aligns but is not a match`() {
        // [1,9,3] against [1,2,3]: the 9 must claim the middle expected unit
        // rather than being slid past as noise. Sliding would cost a deletion
        // AND an insertion (2) where one substitution costs 1, so the DP never
        // prefers it - and the word map can then blame the right word.
        val ref = intArrayOf(1, 2, 3)
        val qry = intArrayOf(1, 9, 3)
        val r2q = UnitAligner.refToQuery(qry, ref)
        assertArrayEquals(intArrayOf(0, 1, 2), r2q)
        // "Aligned" is not "matched": that distinction is the WRONG verdict.
        assertEquals("matched", listOf(0, 2), (0..2).filter { r2q[it] >= 0 && qry[r2q[it]] == ref[it] })
        assertEquals("substituted", listOf(1), (0..2).filter { r2q[it] >= 0 && qry[r2q[it]] != ref[it] })
    }

    @Test
    fun `extra sound is absorbed and does not dilute coverage`() {
        // The case that makes leading istiaadha and basmala harmless: 4
        // emissions against 3 expected units. All 3 expected must still align,
        // because insertions consume emission columns with no expected column.
        val r2q = UnitAligner.refToQuery(intArrayOf(9, 9, 1, 2, 3), intArrayOf(1, 2, 3))
        assertEquals(3, r2q.count { it >= 0 })
    }

    @Test
    fun `the traceback is a strictly increasing path`() {
        // refToQuery is consumed as "expected unit j met emission i", so the
        // pairs must be strictly increasing in both coordinates. A duplicate or
        // out-of-order index means one emission was spent twice, which would
        // double-count coverage - the exact class of bug a score-based check
        // cannot see, since the total still looks plausible.
        var seed = 0x2F6E2B1L
        fun rnd(bits: Int): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and ((1L shl bits) - 1)).toInt()
        }
        repeat(400) {
            val q = IntArray(rnd(6) % 24)
            val r = IntArray(rnd(6) % 24)
            for (i in q.indices) q[i] = rnd(2)
            for (i in r.indices) r[i] = rnd(2)
            val r2q = UnitAligner.refToQuery(q, r)
            assertEquals("length", r.size, r2q.size)
            var lastQ = -1
            for (j in r2q.indices) {
                val qi = r2q[j]
                if (qi >= 0) {
                    assertTrue("emission $qi out of range", qi < q.size)
                    assertTrue("emission $qi reused at expected $j (prev $lastQ)", qi > lastQ)
                    lastQ = qi
                }
            }
        }
    }

    @Test
    fun `the tie-break order is substitution then deletion then insertion`() {
        // ref=[b] against qry=[a,b]. Both paths cost 1:
        //   substitution first -> b claims emission 1, and emission 0 is noise.
        //   deletion first      -> b is deleted, and emission 1 is noise.
        // Same edit distance, completely different meaning: in the second case
        // the expected unit aligned to nothing at all, so it could not be marked
        // WRONG, and extra recitation would silently read as coverage. Pinned
        // rather than left to a future reader's judgement; dp_source_parity.py
        // guards the same ordering in the source.
        assertArrayEquals(intArrayOf(1), UnitAligner.refToQuery(intArrayOf(0, 1), intArrayOf(1)))
    }

    // ------------------------------------------------------------ the pinned seam

    @Test
    fun `kernels agree with the python reference over the canonical space`() {
        val cases = UnitAligner.canonicalCases()
        assertEquals("vector space size must match the python side", 1153, cases.size)
        val d = UnitAligner.digest(cases)
        assertEquals(
            "kernel digest drifted from engine/replay/dp_equivalence.py; " +
                "re-run it and update this constant ONLY if the change was intended " +
                "(digest was 0x743fce71b4fbecd0)",
            8376640819695447248L,
            d,
        )
        assertEquals("0x743fce71b4fbecd0", String.format("0x%016x", d))
    }

    // ---------------------------------------------------------------- the seam itself

    @Test
    fun `the kernel stays free of android and of the framework`() {
        // The whole reason CI can run this file. If someone adds an android.* or
        // sherpa.* import, this test cannot even load - which is the intent.
        val f = File("src/main/java/com/iqra/quran/ml/UnitAligner.kt")
        assertTrue("UnitAligner.kt missing at $f", f.isFile)
        val src = f.readText()
        for (forbidden in listOf("import android.", "import com.iqra.quran.data.",
                                 "import sherpa.", "Log.", "System.out")) {
            assertTrue("UnitAligner.kt must not contain '$forbidden' - the kernel " +
                "has to stay runnable on a bare JVM", !src.contains(forbidden))
        }
    }
}