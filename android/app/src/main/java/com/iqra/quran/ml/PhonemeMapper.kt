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
/**
 * Fraction of a word's own units that must be heard before a WRONG verdict is
 * claimed; below it a contradiction yields UNKNOWN, meaning no verdict.
 *
 * A word cannot be pronounced wrongly on the part of it nobody heard, and the DP
 * will attribute a neighbour's phonemes to a word it only caught part of -
 * especially once a slice rebase lands mid-word, which happens on every lock
 * move. Measured on 20 Al-Dosari surahs (34059 CORRECT, 1454 WRONG): 40 % of the
 * WRONG verdicts sat on words heard below 0.80 of their own units, and were
 * reported on device as the app "wronging" the reciter. It is the same threshold
 * as the existing "barely covered means it was not said" rule, applied to the
 * other side of the word.
 */
internal const val WRONG_MIN_HEARD_COVERAGE = 0.80f

object PhonemeMapper {
    /** The WRONG evidence floor, so a session record carries the threshold the
     *  verdicts in that session were actually produced under. */
    const val WRONG_MIN_HEARD = WRONG_MIN_HEARD_COVERAGE

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
        /**
         * Per expected word, the fraction of ITS OWN units that were matched -
         * how much of that word the emission accounts for.
         *
         * The per-word ok/total was always computed to build `statuses` and then
         * discarded. It is the evidence needed to tell "this word was said
         * wrongly" from "we only caught part of this word": both produce
         * `bad > 0`, and the WRONG verdict used to be claimed on either. A word
         * cannot be pronounced wrongly on the part of it nobody heard.
         *
         * -1 means the word has no units, so no evidence either way.
         */
        val wordCoverage: FloatArray = FloatArray(0),
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
        // Units are interned to ints once per ayah, so the hot loop compares
        // ints instead of Arabic strings. Value-identical: an emitted symbol
        // outside this ayah's vocabulary becomes -1, which can never equal a ref
        // id (every expected unit is in the map by construction), so it stays a
        // substitution exactly as the string compare was.
        val ids = expected.symbolIds
        val ref = IntArray(len) { ids[flat[it]] ?: -1 }
        val qry = IntArray(n) { ids[emitted[it]] ?: -1 }

        // The traceback itself lives in UnitAligner so CI can execute it on the
        // JVM. Everything below is DERIVED from that one path, so the flags, the
        // word map and the coverage cannot drift from the alignment that
        // produced them - there is only one traceback in the codebase.
        val refToQuery = UnitAligner.refToQuery(qry, ref)

        val matched = BooleanArray(len)
        val wrong = BooleanArray(len)
        val emitWord = IntArray(n) { -1 }
        var unitsMatched = 0
        for (j in ref.indices) {
            val qi = refToQuery[j]
            if (qi < 0) continue
            if (qry[qi] == ref[j]) {
                matched[j] = true
                unitsMatched++
            } else {
                wrong[j] = true
            }
            emitWord[qi] = wordOf[j]
        }

        val wordHeard = FloatArray(m) { -1f }
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
            if (total > 0) wordHeard[wi] = ok / total.toFloat()
            when {
                total == 0 -> WordStatus.SKIPPED
                ok == total -> WordStatus.CORRECT
                // Barely covered means it was not said. Coverage, not mismatch
                // count, is what separates a genuinely skipped word from the
                // innocent neighbour the alignment shifts onto: on Al-Asr 3:3,
                // skipping word 1 leaves word 0 at 1 of 2 units and word 1 at 1
                // of 4, and only the ratio tells them apart.
                ok * 2 < total -> WordStatus.SKIPPED
                // Contradicted, but only after hearing most of the word - see
                // WRONG_MIN_HEARD_COVERAGE. Below the floor this falls through
                // to UNKNOWN, which already means "no verdict", and the caller's
                // streak never accumulates because UNKNOWN is not WRONG.
                bad > 0 && ok >= (total * WRONG_MIN_HEARD_COVERAGE) -> WordStatus.WRONG
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
        return Alignment(statuses, emitWord, wordProb, unitsMatched, len, wordHeard)
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
