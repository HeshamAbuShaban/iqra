package com.iqra.quran.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Verse texts + surah metadata, bundled locally — no network, no cloud.
 *
 * Deliberately recognition-free: the voice engine (zipformer phonemes +
 * canonical phoneme tables) carries all matching now, so the 12 MB CTC
 * token tables, vocab and n-gram index are gone with the Tilawa era.
 */
class QuranData private constructor(
    val verses: List<Verse>,
    private val metaByNumber: Map<Int, JSONObject>,
) {
    private val byRef = verses.associateBy { it.surah * 1000 + it.ayah }
    private val bySurah = verses.groupBy { it.surah }

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

    companion object {
        val JUZ_START_PAGES = listOf(
            1, 22, 42, 62, 82, 102, 121, 142, 162, 182,
            201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
            402, 422, 442, 462, 482, 502, 522, 542, 562, 582,
        )

        private var cache: QuranData? = null

        suspend fun load(context: Context): QuranData = withContext(Dispatchers.IO) {
            cache?.let { return@withContext it }
            val assets = context.assets

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

            Log.i("QuranData", "loaded ${verses.size} verses")
            QuranData(verses, metaMap).also { cache = it }
        }

        private fun readAsset(assets: android.content.res.AssetManager, name: String): String {
            return assets.open(name).bufferedReader().use { it.readText() }
        }
    }
}
