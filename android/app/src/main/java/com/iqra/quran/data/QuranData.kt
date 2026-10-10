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

    // Built once. surahList() used to rebuild all 114 SurahInfo objects on every
    // call, and its callers are not occasional: surahInfo() and surahAtPage()
    // each called it, and surahAtPage() is on the reader's header render path,
    // which runs on every page turn and every recomposition. That is 114
    // allocations plus 114 JSON reads per frame of a swipe. Nothing here changes
    // after load - verses and metadata are both immutable once parsed.
    private val surahs: List<SurahInfo> = buildList {
        val meta = metaByNumber
        bySurah.forEach { (num, vs) ->
            val m = meta[num]
            add(
                SurahInfo(
                    number = num,
                    name = vs.first().surahName,
                    nameEn = vs.first().surahNameEn,
                    ayahCount = vs.size,
                    revelationType = m?.optString("revelationType") ?: "Meccan",
                    startPage = m?.optInt("startPage", 1) ?: 1,
                    endPage = m?.optInt("endPage", 1) ?: 1,
                    juz = m?.optInt("juz", 1) ?: 1,
                ),
            )
        }
    }.sortedBy { it.number }

    private val surahsByNumber: Map<Int, SurahInfo> = surahs.associateBy { it.number }

    /**
     * Page -> surah, resolved once at load.
     *
     * First surah to claim a page keeps it, and that ordering is load-bearing
     * rather than incidental: the page ranges in `surah_meta.json` overlap 66
     * times, because a surah can begin part-way down a page its predecessor
     * also starts on. Page 604 alone is claimed by surahs 112, 113 and 114.
     * The scan this replaces returned the FIRST match, so plain `put` in a
     * buildMap would have silently changed the reader's header on the last page
     * of the mushaf from surah 112 to surah 114.
     */
    private val surahByPage: Map<Int, SurahInfo> = buildMap {
        surahs.forEach { s ->
            for (p in s.startPage..s.endPage) putIfAbsent(p, s)
        }
    }

    fun getVerse(surah: Int, ayah: Int): Verse? = byRef[surah * 1000 + ayah]
    fun surahList(): List<SurahInfo> = surahs

    fun surahInfo(number: Int): SurahInfo? = surahsByNumber[number]

    /** The surah that contains a given Mushaf page (for Juz / bookmark jumps). */
    fun surahAtPage(page: Int): SurahInfo? =
        surahByPage[page] ?: surahs.firstOrNull { page in it.startPage..it.endPage }

    fun juzList(): List<JuzInfo> = JUZ_START_PAGES.mapIndexed { i, p ->
        val s = surahAtPage(p)
        JuzInfo(i + 1, p, s?.name ?: "", s?.nameEn ?: "")
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
