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
    private val singleVerseTokens: Map<Int, IntArray>,
    private val reference: Map<Int, String>,
    private val refNoSpace: List<String>,
    private val ngram2: List<Set<String>>,
    private val ngram3: List<Set<String>>,
) {
    private val byRef = verses.associateBy { it.surah * 1000 + it.ayah }
    private val bySurah = verses.groupBy { it.surah }
    private val wordTokensCache = mutableMapOf<Int, List<IntArray>>()
    private val decoder = TextCtcDecoder(vocab, blankId)

    fun getVerse(surah: Int, ayah: Int): Verse? = byRef[surah * 1000 + ayah]
    fun getSurah(surah: Int): List<Verse> = bySurah[surah] ?: emptyList()

    fun surahList(): List<SurahInfo> = bySurah.map { (num, vs) ->
        SurahInfo(num, vs.first().surahName, vs.first().surahNameEn, vs.size)
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
        suspend fun load(context: Context): QuranData = withContext(Dispatchers.IO) {
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

            val ctcRaw = JSONObject(readAsset(assets, "quran_ctc_tokens.json"))
            val singleVerseTokens = mutableMapOf<Int, IntArray>()
            val ctcKeys = ctcRaw.keys()
            while (ctcKeys.hasNext()) {
                val key = ctcKeys.next()
                val parts = key.split(":")
                if (parts.size != 3) continue
                val surah = parts[0].toIntOrNull() ?: continue
                val ayah = parts[1].toIntOrNull() ?: continue
                val end = parts[2].toIntOrNull() ?: continue
                if (end != ayah) continue // keep only single-verse spans (S:A:A)
                val arr = ctcRaw.getJSONArray(key)
                val ids = IntArray(arr.length()) { arr.getInt(it) }
                singleVerseTokens[surah * 1000 + ayah] = ids
            }

            val reference = mutableMapOf<Int, String>()
            val refNoSpace = mutableListOf<String>()
            val ngram2 = mutableListOf<Set<String>>()
            val ngram3 = mutableListOf<Set<String>>()
            for (v in verses) {
                val key = v.surah * 1000 + v.ayah
                val ref = if (singleVerseTokens.containsKey(key)) {
                    decoder.tokenIdsToText(singleVerseTokens[key]!!)
                } else {
                    ArabicNormalizer.normalize(v.textClean)
                }
                reference[key] = ref
                val ns = ref.replace(" ", "")
                refNoSpace.add(ns)
                val ng2 = mutableSetOf<String>()
                val ng3 = mutableSetOf<String>()
                for (i in 0..ns.length - 2) ng2.add(ns.substring(i, i + 2))
                for (i in 0..ns.length - 3) ng3.add(ns.substring(i, i + 3))
                ngram2.add(ng2)
                ngram3.add(ng3)
            }

            Log.i("QuranData", "loaded ${verses.size} verses, vocab=$vocabSize, ctc=${singleVerseTokens.size}")
            QuranData(
                verses, vocab, blankId, vocabSize, singleVerseTokens,
                reference, refNoSpace, ngram2, ngram3,
            )
        }

        private fun readAsset(assets: android.content.res.AssetManager, name: String): String {
            return assets.open(name).bufferedReader().use { it.readText() }
        }
    }
}
