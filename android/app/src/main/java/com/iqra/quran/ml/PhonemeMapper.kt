package com.iqra.quran.ml

import android.util.Log
import com.iqra.quran.data.WordStatus
import org.json.JSONObject
import java.io.File

/**
 * Maps zipformer phoneme emissions onto mushaf words using the canonical
 * phonemisation table (ordered_quran_phonemes.json: "S:A" ->
 * {"aya_phonemes_list": [...]}). Pure logic, no audio, no Android UI.
 *
 * CRITICAL: the table's `aya_phonemes_list` entries are per-WORD phoneme
 * STRINGS ("بِسمِ"), but the model emits per-PHONEME UNITS ("بِ", "س", "مِ").
 * Comparing them directly yields coverage of exactly 0.00 - no ayah can ever
 * be detected. Every expected word is therefore EXPLODED into the model's own
 * unit inventory (tokens.txt) before alignment, with a unit->word map kept so
 * per-word verdicts still work.
 */
object PhonemeMapper {
    private const val TAG = "PhonemeMapper"
    @Volatile private var table: Map<String, List<String>>? = null

    /** Model unit inventory, longest-first for greedy matching. */
    @Volatile private var unitsByLength: List<String>? = null
    @Volatile private var unitSet: Set<String>? = null
    private val expectedCache = HashMap<String, Expected>()

    /** Idempotent; ~5 MB JSON parsed once per process. */
    fun ensureTable(file: File): Boolean {
        if (table != null) return true
        return try {
            if (!file.exists() || file.length() == 0L) return false
            val root = JSONObject(file.bufferedReader().use { it.readText() })
            val map = HashMap<String, List<String>>(7000)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val o = root.optJSONObject(k) ?: continue
                val arr = o.optJSONArray("aya_phonemes_list") ?: continue
                val words = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) words.add(arr.optString(i))
                map[k] = words
            }
            table = map
            Log.i(TAG, "phoneme table ready (${map.size} ayat)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "phoneme table load failed", t)
            false
        }
    }

    /**
     * Load the acoustic model's unit inventory from tokens.txt. Without this
     * the expected side cannot be expressed in the model's own alphabet.
     */
    fun ensureUnits(tokensFile: File): Boolean {
        if (unitsByLength != null) return true
        return try {
            if (!tokensFile.exists() || tokensFile.length() == 0L) return false
            val set = HashSet<String>(512)
            tokensFile.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val s = line.trimEnd('\n', '\r')
                    if (s.isEmpty()) continue
                    val sp = s.lastIndexOf(' ')
                    if (sp <= 0) continue
                    val sym = s.substring(0, sp)
                    if (sym.isNotEmpty()) set.add(sym)
                }
            }
            if (set.isEmpty()) return false
            unitSet = set
            // Longest first: units are multi-codepoint and ambiguous if
            // scanned shortest-first ("ا" vs "اا" vs "اااااا").
            unitsByLength = set.sortedByDescending { it.length }
            Log.i(TAG, "model unit inventory ready (${set.size} units)")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "unit inventory load failed", t)
            false
        }
    }

    fun isReady(): Boolean = table != null && unitsByLength != null

    fun tableSize(): Int = table?.size ?: 0

    fun unitCount(): Int = unitSet?.size ?: 0

    /**
     * An ayah expressed in the model's own alphabet: the phoneme UNITS to
     * align against, plus the unit->word map used for per-word verdicts.
     */
    data class Expected(
        val wordCount: Int,
        val units: List<String>,
        /** units[i] belongs to word unitWord[i]. */
        val unitWord: IntArray,
    ) {
        @Volatile private var ids: Map<String, Int>? = null

        /**
         * Unit -> dense id, built once per ayah and reused by every alignment
         * against it. The lock policy aligns the same candidate ayat several
         * times a second, so this must not be rebuilt per call. The lazy init is
         * a benign race: two threads may both build it, both produce identical
         * content, and the last write wins.
         */
        val symbolIds: Map<String, Int>
            get() {
                var m = ids
                if (m == null) {
                    m = HashMap<String, Int>(units.size * 2)
                    for (u in units) if (!m.containsKey(u)) m[u] = m.size
                    ids = m
                }
                return m
            }
    }

    /** Split a word's phoneme string into model units (greedy longest match). */
    private fun explode(wordPhonemes: String): List<String> {
        val ordered = unitsByLength ?: return wordPhonemes.split(" ").filter { it.isNotEmpty() }
        val out = ArrayList<String>(wordPhonemes.length)
        var i = 0
        val n = wordPhonemes.length
        while (i < n) {
            var matched = false
            for (u in ordered) {
                if (u.length <= n - i && wordPhonemes.regionMatches(i, u, 0, u.length)) {
                    out.add(u)
                    i += u.length
                    matched = true
                    break
                }
            }
            if (!matched) i++ // codepoint the model does not emit
        }
        return out
    }

    /** Expected units for an ayah, cached. Null if the table has no entry. */
    @Synchronized
    fun expected(surah: Int, ayah: Int): Expected? {
        val key = "$surah:$ayah"
        expectedCache[key]?.let { return it }
        val words = table?.get(key) ?: return null
        if (words.isEmpty()) return null
        val units = ArrayList<String>(words.size * 4)
        val unitWord = ArrayList<Int>(words.size * 4)
        for (wi in words.indices) {
            for (u in explode(words[wi])) {
                units.add(u)
                unitWord.add(wi)
            }
        }
        if (units.isEmpty()) return null
        val e = Expected(words.size, units, unitWord.toIntArray())
        if (expectedCache.size > 8000) expectedCache.clear()
        expectedCache[key] = e
        return e
    }

    data class Alignment(
        val statuses: List<WordStatus>,
        val emitWord: IntArray,
        /** Mean chosen-token probability per expected word (-1 = no evidence). */
        val wordProb: FloatArray,
        /** Expected units aligned 1:1 with an emission. */
        val unitsMatched: Int = 0,
        val unitsTotal: Int = 0,
    ) {
        /** Fraction of this ayah's phonemes the emission accounts for. */
        val coverage: Float
            get() = if (unitsTotal == 0) 0f else unitsMatched.toFloat() / unitsTotal
    }

    /**
     * Unit-level edit DP of emitted phonemes against an ayah's expected units.
     * Insertions (extra sounds) are ignored, so leading noise such as
     * istiaadha or basmala cannot reduce coverage. A word is CORRECT only when
     * every one of its units matches, WRONG on substitution, SKIPPED when none
     * match. emitWord maps each emission index to its expected word index (-1).
     */
    fun align(
        emitted: List<String>,
        expected: Expected,
        probs: FloatArray? = null,
    ): Alignment {
        val m = expected.wordCount
        val flat = expected.units
        val wordOf = expected.unitWord
        val n = emitted.size
        val len = flat.size
        if (len == 0 || n == 0) {
            return Alignment(
                if (len == 0) List(m) { WordStatus.SKIPPED } else emptyList(),
                IntArray(n) { -1 },
                FloatArray(m) { -1f },
                0, len,
            )
        }
        // Units are interned to ints once per ayah, so the inner loop compares
        // ints instead of Arabic strings. Value-identical: an emitted symbol
        // outside this ayah's vocabulary becomes -1, which can never equal a ref
        // id (every expected unit is in the map by construction), so it stays a
        // substitution exactly as the string compare was. Two emission-only
        // symbols also compare equal to each other, but they are only ever
        // compared against ref, never against each other.
        val ids = expected.symbolIds
        val ref = IntArray(len) { ids[flat[it]] ?: -1 }
        val qry = IntArray(n) { ids[emitted[it]] ?: -1 }

        // One flat ByteArray for the direction table. The shipped version built
        // Array(n+1){IntArray(len+1)} to hold only the values 0, 1 and 2 - four
        // bytes and n+1 allocations for three states. dp becomes two rolling
        // rows, because the traceback reads dir and never dp.
        //
        // No banding. A provably safe band half-width for edit distance is
        // bounded by the trivial alignment cost max(n, len), which spans the
        // entire grid - so banding buys nothing here while making the backtrace
        // index fiddly enough to get wrong. A proposal that banded anyway was
        // rejected: its fill wrote dir at column j+off while its backtrace read
        // column j-i+off, and its boundary write indexed a negative slot.
        //
        // Proven value-identical to the previous implementation by
        // engine/replay/dp_equivalence.py: 5,473 alignments, 112 of them on the
        // recorded surahs, plus an exhaustive sweep of every sequence pair up to
        // length 4 which is what pins the tie-breaking.
        val width = len + 1
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
                // Tie-break order is load-bearing: substitution, then deletion,
                // then insertion. Reordering can pick a different path through
                // an equal-cost alignment, which changes the per-word verdicts
                // even though the edit distance is unchanged.
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

        val matched = BooleanArray(len)
        val wrong = BooleanArray(len)
        val emitWord = IntArray(n) { -1 }
        var unitsMatched = 0
        var i = n
        var j = len
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && dir[i * width + j] == 0.toByte()) {
                if (qry[i - 1] == ref[j - 1]) {
                    matched[j - 1] = true
                    unitsMatched++
                } else {
                    wrong[j - 1] = true
                }
                emitWord[i - 1] = wordOf[j - 1]
                i--
                j--
            } else if (i > 0 && j > 0 && dir[i * width + j] == 1.toByte()) {
                i--
            } else if (i > 0 && j > 0 && dir[i * width + j] == 2.toByte()) {
                j--
            } else if (i > 0) {
                i--
            } else {
                j--
            }
        }
        val statuses = List(m) { wi ->
            var total = 0
            var ok = 0
            var bad = 0
            for (k in flat.indices) {
                if (wordOf[k] != wi) continue
                total++
                if (matched[k]) ok++
                if (wrong[k]) bad++
            }
            when {
                total == 0 -> WordStatus.SKIPPED
                ok == total -> WordStatus.CORRECT
                // Barely covered means it was not said. Coverage, not mismatch
                // count, is what separates a genuinely skipped word from the
                // innocent neighbour the alignment shifts onto: on Al-Asr 3:3,
                // skipping word 1 leaves word 0 at 1 of 2 units and word 1 at 1
                // of 4, and only the ratio tells them apart.
                ok * 2 < total -> WordStatus.SKIPPED
                // Some units landed on the wrong phonemes. The caller still
                // gates this on model confidence and a frame streak.
                bad > 0 -> WordStatus.WRONG
                // Partly covered, nothing contradicted: no verdict. This used
                // to be WRONG, which is what made a skipped word paint its
                // neighbour red.
                else -> WordStatus.UNKNOWN
            }
        }
        val wordProb = FloatArray(m) { -1f }
        if (probs != null) {
            val sum = FloatArray(m)
            val cnt = IntArray(m)
            for (k in emitted.indices) {
                val wi = emitWord[k]
                if (wi in 0 until m && k < probs.size) {
                    sum[wi] += probs[k]
                    cnt[wi]++
                }
            }
            for (wi in 0 until m) {
                if (cnt[wi] > 0) wordProb[wi] = sum[wi] / cnt[wi]
            }
        }
        return Alignment(statuses, emitWord, wordProb, unitsMatched, len)
    }

    /** Word holding the most recent emission within [recencySec] of now. */
    fun timedWord(
        emitWord: IntArray,
        timestamps: FloatArray,
        audioSec: Float,
        recencySec: Float = 2.5f,
    ): Int? {
        if (emitWord.isEmpty() || timestamps.isEmpty()) return null
        var best = -1
        var bestTs = -1f
        val lim = minOf(emitWord.size, timestamps.size)
        for (k in 0 until lim) {
            if (emitWord[k] >= 0 && timestamps[k] >= bestTs) {
                bestTs = timestamps[k]
                best = emitWord[k]
            }
        }
        if (best < 0 || bestTs < 0) return null
        return if (audioSec - bestTs <= recencySec) best else null
    }
}
