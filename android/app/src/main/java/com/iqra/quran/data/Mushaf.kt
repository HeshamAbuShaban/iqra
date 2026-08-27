package com.iqra.quran.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Loads the authentic Madani Mushaf layout (604 pages) from `mushaf.json`.
 * Source: zonetecde/mushaf-layout (per-word `location` = surah:verse:wordIndex,
 * Arabic text with full harakat). Used to render a real paged mushaf and to
 * map recognition results onto display words.
 */
object Mushaf {
    private var cache: List<MushafPage>? = null

    suspend fun load(context: Context): List<MushafPage> = withContext(Dispatchers.IO) {
        cache ?: run {
            val raw = context.assets.open("mushaf.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(raw)
            val pages = ArrayList<MushafPage>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val pageNum = p.getInt("page")
                val linesArr = p.getJSONArray("lines")
                val tmpLines = ArrayList<MutableList<WordTmp>>()
                val flat = ArrayList<WordTmp>()
                for (li in 0 until linesArr.length()) {
                    val line = linesArr.getJSONObject(li)
                    val type = line.getString("type")
                    if (type == "text") {
                        val wordsArr = line.getJSONArray("words")
                        val tmp = ArrayList<WordTmp>()
                        for (wi in 0 until wordsArr.length()) {
                            val w = wordsArr.getJSONObject(wi)
                            val loc = w.getString("location").split(":")
                            val surah = loc[0].toInt()
                            val verse = loc[1].toInt()
                            val wiv = loc[2].toInt()
                            val rawWord = w.getString("word")
                            val t = WordTmp(surah, verse, wiv, rawWord, lineIdx = tmpLines.size)
                            tmp.add(t)
                            flat.add(t)
                        }
                        tmpLines.add(tmp)
                    }
                }
                // Compute isVerseEnd across the flat (page-order) sequence.
                for (idx in flat.indices) {
                    val cur = flat[idx]
                    val nxt = flat.getOrNull(idx + 1)
                    val end = nxt == null || nxt.surah != cur.surah || nxt.verse != cur.verse
                    cur.isVerseEnd = end
                    cur.text = stripVerseNumber(cur.rawWord)
                }
                val outLines = ArrayList<MushafLine>(linesArr.length())
                var textLineIdx = 0
                for (li in 0 until linesArr.length()) {
                    val line = linesArr.getJSONObject(li)
                    val type = line.getString("type")
                    when (type) {
                        "text" -> {
                            val words = tmpLines[textLineIdx].map {
                                MushafWord(it.surah, it.verse, it.wiv, it.text, it.isVerseEnd, pageNum, line = textLineIdx)
                            }
                            outLines.add(MushafLine("text", null, words))
                            textLineIdx++
                        }
                        "surah-header" -> outLines.add(MushafLine("surah-header", line.optString("text"), null))
                        else -> outLines.add(MushafLine("basmala", null, null))
                    }
                }
                pages.add(MushafPage(pageNum, outLines))
            }
            Log.i("Mushaf", "loaded ${pages.size} pages")
            pages.also { cache = it }
        }
    }

    private data class WordTmp(
        val surah: Int,
        val verse: Int,
        val wiv: Int,
        val rawWord: String,
        val lineIdx: Int,
        var text: String = "",
        var isVerseEnd: Boolean = false,
    )

    private fun stripVerseNumber(raw: String): String {
        // The layout appends the Arabic-Indic verse number to the last word of
        // each verse (e.g. "ٱلرَّحِيمِ ١"). Drop a trailing space + digits.
        return raw.replace(Regex("[\\s﻿]*[٠-٩١-٩]+$"), "")
    }

    /** 0-based pager index of the first page that contains the given surah. */
    fun firstPageOfSurah(pages: List<MushafPage>, surah: Int): Int {
        for (p in pages) {
            for (line in p.lines) {
                if (line.type == "text") {
                    for (w in line.words ?: emptyList()) {
                        if (w.surah == surah) return p.page - 1
                    }
                } else if (line.type == "surah-header" && line.text != null) {
                    // header text like "سورة البقرة" — map via surah list instead
                }
            }
        }
        return 0
    }

    /** All text words of a surah, in recitation order, for recognition alignment. */
    fun wordsForSurah(pages: List<MushafPage>, surah: Int): List<MushafWord> {
        val out = ArrayList<MushafWord>()
        for (p in pages) {
            for (line in p.lines) {
                if (line.type == "text") {
                    for (w in line.words ?: emptyList()) {
                        if (w.surah == surah) out.add(w)
                    }
                }
            }
        }
        return out
    }
}

data class MushafWord(
    val surah: Int,
    val verse: Int,
    val wordInVerse: Int,
    val text: String,
    val isVerseEnd: Boolean,
    val page: Int,
    val line: Int,
)

data class MushafLine(
    val type: String, // "surah-header" | "basmala" | "text"
    val text: String?,
    val words: List<MushafWord>?,
)

data class MushafPage(
    val page: Int,
    val lines: List<MushafLine>,
)
