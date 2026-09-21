package com.iqra.quran.data

import android.content.Context
import android.util.Log
import com.iqra.quran.ml.ArabicNormalizer
import com.iqra.quran.ml.TextCtcDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads and indexes the open Quran text + Tilawa CTC token tables from assets.
 * Everything is bundled locally — no network, no cloud.
 */
class QuranData private constructor(
    val verses: List<Verse>,
    val vocab: Map<Int, String>,
    val blankId: Int,
    val vocabSize: Int,
    private var singleVerseTokens: Map<Int, IntArray>,
    private var reference: Map<Int, String>,
    private var refNoSpace: List<String>,
    private var ngram2: List<Set<String>>,
    private var ngram3: List<Set<String>>,
    private val metaByNumber: Map<Int, JSONObject>,
) {
    private val byRef = verses.associateBy { it.surah * 1000 + it.ayah }
    private val bySurah = verses.groupBy { it.surah }
    private val wordTokensCache = mutableMapOf<Int, List<IntArray>>()
    private val decoder = TextCtcDecoder(vocab, blankId)
    @Volatile private var indexReady = false

    /** Builds the heavy recognition index (CTC tables + reference + n-grams)
     *  once, in background. Recognition safely falls back to text alignment
     *  until ready, so startup never waits on the 12 MB token file. */
    suspend fun ensureIndex(context: Context) = withContext(Dispatchers.IO) {
        if (indexReady) return@withContext
        val assets = context.assets
        fun read(name: String): String =
            assets.open(name).bufferedReader().use { it.readText() }
        val ctcRaw = JSONObject(read("quran_ctc_tokens.json"))
        val tokens = mutableMapOf<Int, IntArray>()
        val ctcKeys = ctcRaw.keys()
        while (ctcKeys.hasNext()) {
            val key = ctcKeys.next()
            val parts = key.split(":")
            if (parts.size != 3) continue
            val surah = parts[0].toIntOrNull() ?: continue
            val ayah = parts[1].toIntOrNull() ?: continue
            val end = parts[2].toIntOrNull() ?: continue
            if (end != ayah) continue
            val arr = ctcRaw.getJSONArray(key)
            val ids = IntArray(arr.length()) { arr.getInt(it) }
            tokens[surah * 1000 + ayah] = ids
        }
        val ref = mutableMapOf<Int, String>()
        val noSpace = mutableListOf<String>()
        val ng2 = mutableListOf<Set<String>>()
        val ng3 = mutableListOf<Set<String>>()
        for (v in verses) {
            val key = v.surah * 1000 + v.ayah
            val r = if (tokens.containsKey(key)) {
                decoder.tokenIdsToText(tokens[key]!!)
            } else {
                ArabicNormalizer.normalize(v.textClean)
            }
            ref[key] = r
            val ns = r.replace(" ", "")
            noSpace.add(ns)
            val s2 = mutableSetOf<String>()
            val s3 = mutableSetOf<String>()
            for (i in 0..ns.length - 2) s2.add(ns.substring(i, i + 2))
            for (i in 0..ns.length - 3) s3.add(ns.substring(i, i + 3))
            ng2.add(s2)
            ng3.add(s3)
        }
        singleVerseTokens = tokens
        reference = ref
        refNoSpace = noSpace
        ngram2 = ng2
        ngram3 = ng3
        wordTokensCache.clear()
        indexReady = true
        Log.i("QuranData", "index ready: ctc=${tokens.size}")
    }

    fun getVerse(surah: Int, ayah: Int): Verse? = byRef[surah * 1000 + ayah]
    fun getSurah(surah: Int): List<Verse> = bySurah[surah] ?: emptyList()

    fun surahList(): List<SurahInfo> {
        val meta = metaByNumber
        return bySurah.map { (num, vs) ->
            val m = meta[num]
            SurahInfo(
                number = num,
                name = vs.first().surahName,
                nameEn = vs.first().surahNameEn,
                ayahCount = vs.size,
                revelationType = m?.optString("revelationType") ?: "Meccan",
                startPage = m?.optInt("startPage", 1) ?: 1,
                endPage = m?.optInt("endPage", 1) ?: 1,
                juz = m?.optInt("juz", 1) ?: 1,
            )
        }
    }

    fun surahInfo(number: Int): SurahInfo? = surahList().firstOrNull { it.number == number }

    /** The surah that contains a given Mushaf page (for Juz / bookmark jumps). */
    fun surahAtPage(page: Int): SurahInfo? =
        surahList().firstOrNull { page in it.startPage..it.endPage }

    fun juzList(): List<JuzInfo> {
        val surahs = surahList()
        return JUZ_START_PAGES.mapIndexed { i, p ->
            val s = surahs.firstOrNull { p in it.startPage..it.endPage }
            JuzInfo(i + 1, p, s?.name ?: "", s?.nameEn ?: "")
        }
    }

    fun referenceText(v: Verse): String? = reference[v.surah * 1000 + v.ayah]
    fun referenceText(surah: Int, ayah: Int): String? = reference[surah * 1000 + ayah]

    fun getWordTokens(surah: Int, ayah: Int): List<IntArray> {
        val key = surah * 1000 + ayah
        return wordTokensCache.getOrPut(key) {
            val ids = singleVerseTokens[key] ?: return@getOrPut emptyList()
            decoder.splitWords(ids)
        }
    }

    /** Arabic text for a verse reconstructed from its CTC token table. */
    fun verseText(surah: Int, ayah: Int): String {
        val key = surah * 1000 + ayah
        val ids = singleVerseTokens[key]
        return if (ids != null) decoder.tokenIdsToText(ids)
        else getVerse(surah, ayah)?.textUthmani ?: ""
    }

    /** N-gram shortlist mirroring @tilawa/core _jointCandidateVerses. */
    fun shortlist(noSpace: String, maxCandidates: Int = 950): List<Verse> {
        if (noSpace.length < 4) return verses
        val qb = ngrams(noSpace, 2)
        val qt = ngrams(noSpace, 3)
        if (qb.isEmpty() && qt.isEmpty()) return verses

        val scored = mutableListOf<Pair<Int, Int>>()
        for (i in verses.indices) {
            val ov = intersectionSize(qb, ngram2[i]) + (0.48 * intersectionSize(qt, ngram3[i])).toInt()
            if (ov > 0) scored.add(ov to i)
        }
        if (scored.size < 80) return verses
        scored.sortByDescending { it.first }
        return scored.take(maxCandidates).map { verses[it.second] }
    }

    private fun ngrams(s: String, n: Int): Set<String> {
        val out = mutableSetOf<String>()
        if (s.length < n) return out
        for (i in 0..s.length - n) out.add(s.substring(i, i + n))
        return out
    }

    private fun intersectionSize(a: Set<String>, b: Set<String>): Int {
        var c = 0
        for (x in a) if (b.contains(x)) c++
        return c
    }

    companion object {
        val JUZ_START_PAGES = listOf(
            1, 22, 42, 62, 82, 102, 121, 142, 162, 182,
            201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
            402, 422, 442, 462, 482, 502, 522, 542, 562, 582,
        )

        private var cache: QuranData? = null

        /** Fast path: text + metadata only (home screen). See [QuranData.ensureIndex]. */
        suspend fun loadFast(context: Context): QuranData = withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val assets = context.assets

            val vocabRaw = JSONObject(readAsset(assets, "vocab.json"))
            val vocab = mutableMapOf<Int, String>()
            var maxId = 0
            val keys = vocabRaw.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val id = k.toIntOrNull() ?: return@withContext throw IllegalStateException("bad vocab key $k")
                vocab[id] = vocabRaw.getString(k)
                if (id > maxId) maxId = id
            }
            val blankId = if (vocabRaw.has("1024")) 1024 else maxId
            val vocabSize = vocab.size

            val decoder = TextCtcDecoder(vocab, blankId)

            val quranRaw = JSONArray(readAsset(assets, "quran.json"))
            val verses = mutableListOf<Verse>()
            for (i in 0 until quranRaw.length()) {
                val o = quranRaw.getJSONObject(i)
                verses.add(
                    Verse(
                        surah = o.getInt("surah"),
                        ayah = o.getInt("ayah"),
                        textUthmani = o.optString("text_uthmani", ""),
                        textClean = o.optString("text_clean", o.optString("text_uthmani", "")),
                        surahName = o.optString("surah_name", ""),
                        surahNameEn = o.optString("surah_name_en", ""),
                    ),
                )
            }

            val metaMap = mutableMapOf<Int, JSONObject>()
            try {
                val metaRaw = JSONArray(readAsset(assets, "surah_meta.json"))
                for (i in 0 until metaRaw.length()) {
                    val o = metaRaw.getJSONObject(i)
                    metaMap[o.getInt("number")] = o
                }
            } catch (e: Exception) {
                Log.w("QuranData", "surah_meta.json missing/invalid; using defaults", e)
            }

            Log.i("QuranData", "fast load: ${verses.size} verses, vocab=$vocabSize")
            QuranData(
                verses, vocab, blankId, vocabSize, mutableMapOf(),
                mutableMapOf(), mutableListOf(), mutableListOf(), mutableListOf(), metaMap,
            ).also { cache = it }
        }

        suspend fun load(context: Context): QuranData = withContext(Dispatchers.IO) {
            val d = loadFast(context)
            d.ensureIndex(context)
            d
        }

        private fun readAsset(assets: android.content.res.AssetManager, name: String): String {
            return assets.open(name).bufferedReader().use { it.readText() }
        }
    }
}
