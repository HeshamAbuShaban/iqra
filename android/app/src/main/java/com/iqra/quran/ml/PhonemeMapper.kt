package com.iqra.quran.ml

import android.util.Log
import com.iqra.quran.data.WordStatus
import org.json.JSONObject
import java.io.File

/**
 * Maps zipformer phoneme emissions onto mushaf words using the canonical
 * phonemisation table (ordered_quran_phonemes.json: "S:A" ->
 * {"aya_phonemes_list": [...]}). Pure logic, no audio, no Android UI.
 */
object PhonemeMapper {
    private const val TAG = "PhonemeMapper"
    @Volatile private var table: Map<String, List<String>>? = null

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

    fun phonemeWords(surah: Int, ayah: Int): List<String> =
        table?.get("$surah:$ayah") ?: emptyList()

    data class Alignment(val statuses: List<WordStatus>, val emitWord: IntArray)

    /**
     * Symbol-level edit DP of emitted phonemes against expected phoneme
     * words. Insertions (extra sounds) are ignored; a word is CORRECT only
     * when every symbol matches, WRONG on substitution, SKIPPED on deletion.
     * emitWord maps each emission index to its expected word index (-1).
     */
    fun alignToWords(emitted: List<String>, expectedWords: List<String>): Alignment {
        val m = expectedWords.size
        if (m == 0) return Alignment(emptyList(), IntArray(emitted.size) { -1 })
        // Flatten expected symbols with word boundaries.
        val flat = ArrayList<String>()
        val wordOf = ArrayList<Int>()
        for (wi in expectedWords.indices) {
            for (sym in expectedWords[wi].split(" ")) {
                if (sym.isEmpty()) continue
                flat.add(sym)
                wordOf.add(wi)
            }
        }
        // Symbols may themselves contain spaces? No: aya_phonemes_list items
        // are whole words like "بِسمِ". Split above is a no-op safeguard.
        val n = emitted.size
        val len = flat.size
        if (len == 0) {
            return Alignment(List(m) { WordStatus.SKIPPED }, IntArray(n) { -1 })
        }
        val dp = Array(n + 1) { IntArray(len + 1) }
        val dir = Array(n + 1) { IntArray(len + 1) } // 0=sub,1=del-from-expected,2=ins
        for (i in 0..n) dp[i][0] = i
        for (j in 0..len) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..len) {
                val cost = if (emitted[i - 1] == flat[j - 1]) 0 else 1
                val sub = dp[i - 1][j - 1] + cost
                val del = dp[i - 1][j] + 1
                val ins = dp[i][j - 1] + 1
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
                dp[i][j] = best
                dir[i][j] = d
            }
        }
        val matched = BooleanArray(len)
        val wrong = BooleanArray(len)
        val emitWord = IntArray(n) { -1 }
        var i = n
        var j = len
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && dir[i][j] == 0) {
                if (emitted[i - 1] == flat[j - 1]) matched[j - 1] = true
                else wrong[j - 1] = true
                emitWord[i - 1] = wordOf[j - 1]
                i--
                j--
            } else if (i > 0 && j > 0 && dir[i][j] == 1) {
                i--
            } else if (i > 0 && j > 0 && dir[i][j] == 2) {
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
                bad > 0 -> WordStatus.WRONG
                ok == total -> WordStatus.CORRECT
                ok == 0 -> WordStatus.SKIPPED
                else -> WordStatus.WRONG
            }
        }
        return Alignment(statuses, emitWord)
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

    /** Closed-vocab nearest-ayah retrieval over canonical phoneme strings. */
    fun matchAyah(
        emittedJoined: String,
        candidates: List<Pair<Int, String>>,
    ): Pair<Int, Double>? {
        if (emittedJoined.isBlank() || candidates.isEmpty()) return null
        var best: Pair<Int, Double>? = null
        for ((ayah, ref) in candidates) {
            if (ref.isBlank()) continue
            val score = Levenshtein.ratio(emittedJoined, ref)
            if (best == null || score > best.second) best = ayah to score
        }
        return best
    }
}
