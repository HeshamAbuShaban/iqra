package com.iqra.quran.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF
import java.io.File

/**
 * Loads the quran_android "ayahinfo" database (per ayah/line bounding boxes in
 * the 776x1053 Madinah page space) so the reader can draw recitation
 * highlights on the REAL page images instead of synthetic text.
 *
 * Source: quran_android's madani ayahinfo data (android.quran.com/data), the
 * same mushaf our bundled pages/ images depict.
 */
object GlyphCoords {
    private var db: SQLiteDatabase? = null

    fun ensure(context: Context) {
        if (db != null) return
        val out = File(context.getDatabasePath("ayahinfo.db").path)
        if (!out.exists() || out.length() < 4096) {
            out.parentFile?.mkdirs()
            context.assets.open("ayahinfo.db").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        db = SQLiteDatabase.openDatabase(out.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    /**
     * @return map of "sura:ayah" -> list of (lineNumber, bounding box) for one page,
     *         boxes in the 776x1053 reference space.
     */
    fun lineBoxes(page: Int): Map<String, List<Pair<Int, RectF>>> {
        val d = db ?: return emptyMap()
        val map = mutableMapOf<String, MutableList<Pair<Int, RectF>>>()
        d.rawQuery(
            "SELECT sura_number,ayah_number,line_number,min_x,min_y,max_x,max_y " +
                "FROM glyphs WHERE page_number=?",
            arrayOf(page.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val key = "${c.getInt(0)}:${c.getInt(1)}"
                val line = c.getInt(2)
                val r = RectF(c.getInt(3).toFloat(), c.getInt(4).toFloat(), c.getInt(5).toFloat(), c.getInt(6).toFloat())
                map.getOrPut(key) { mutableListOf() }.add(line to r)
            }
        }
        return map
    }
}
