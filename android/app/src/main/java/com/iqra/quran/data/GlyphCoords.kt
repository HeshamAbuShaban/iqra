package com.iqra.quran.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF
import java.io.File

/**
 * Loads the standard Madinah Mushaf "ayahinfo" database from
 * murtraja/quran-android-images-helper at width 1024. Each row is a visual
 * glyph on the printed page (one per word, plus end-of-ayah markers etc.) in
 * the 1024x1656 page space, so the reader can highlight the EXACT word the
 * user is reciting, and the EXACT word in the reference-audio play-head.
 */
object GlyphCoords {
    const val PAGE_W = 1024
    const val PAGE_H = 1656
    private const val DB_ASSET = "ayahinfo_1024.db"
    private var db: SQLiteDatabase? = null

    fun ensure(context: Context) {
        if (db != null) return
        val out = File(context.getDatabasePath(DB_ASSET).path)
        if (!out.exists() || out.length() < 4096) {
            out.parentFile?.mkdirs()
            context.assets.open(DB_ASSET).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        db = SQLiteDatabase.openDatabase(out.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    /**
     * @return map of "sura:ayah:line" -> list of word bounding boxes for that
     *         line on the given page, in reading order (position 1..N).
     *         Boxes are in the 1024x1656 page space.
     */
    fun lineGroups(page: Int): Map<String, List<RectF>> {
        val d = db ?: return emptyMap()
        val map = mutableMapOf<String, MutableList<RectF>>()
        d.rawQuery(
            "SELECT sura_number,ayah_number,line_number,min_x,min_y,max_x,max_y " +
                "FROM glyphs WHERE page_number=? ORDER BY sura_number,ayah_number,line_number,position",
            arrayOf(page.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val key = "${c.getInt(0)}:${c.getInt(1)}:${c.getInt(2)}"
                map.getOrPut(key) { mutableListOf() }.add(
                    RectF(
                        c.getInt(3).toFloat(),
                        c.getInt(4).toFloat(),
                        c.getInt(5).toFloat(),
                        c.getInt(6).toFloat(),
                    )
                )
            }
        }
        return map
    }
}
