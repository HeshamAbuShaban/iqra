package com.iqra.quran.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Per-ayah start times for the reference audio.
 *
 * The data is quran_android's own timing databases (reached through
 * android.quran.com, served from files.quran.app) and it is used only for the
 * reciters where the reference app pairs that exact database with the exact
 * mp3 we stream from download.quranicaudio.com - Al-Husary and al-Minshawi.
 * For anyone else an ayah tap has no honest answer, and says so.
 *
 * Key 0 is the basmala where a dataset carries one (Minshawi does, Husary
 * does not); keys 1..N are the quran ayah numbers. The times are boundary
 * points, not speech onsets: each sits ~0.5-0.8 s inside the pause before
 * its ayah speaks. Verified against the actual file, sura 1 on Husary -
 * db 5664 ms vs speech resume 6500 ms, same relationship for all seven
 * entries - so a seek lands in silence just ahead of the words.
 */
object AyahTimings {
    private val DB_NAMES = mapOf(
        "husaree" to "husary",
        "minshawi" to "minshawi_murattal",
    )

    fun isSupported(reciterId: String): Boolean = DB_NAMES.containsKey(reciterId)

    fun dbFile(ctx: Context, reciterId: String): File? {
        val name = DB_NAMES[reciterId] ?: return null
        return File(File(ctx.filesDir, "timings"), "$name.db")
    }

    /** Download and unpack the timing database once. Blocking; call on IO. */
    fun ensure(ctx: Context, reciterId: String): File? {
        val name = DB_NAMES[reciterId] ?: return null
        val target = File(File(ctx.filesDir, "timings"), "$name.db")
        if (target.isFile && target.length() > 0) return target
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        val conn = (URL("https://android.quran.com/data/databases/audio/$name.zip")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { input ->
                ZipInputStream(input).use { zip ->
                    if (zip.nextEntry == null) return null
                    part.outputStream().use { out -> zip.copyTo(out) }
                }
            }
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return null
            }
            return target
        } catch (t: Throwable) {
            part.delete()
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Start times in ms indexed by ayah, 0 = basmala; -1 where the dataset
     * has no entry. Null when there is no usable database. Blocking; IO.
     */
    fun startsFor(ctx: Context, reciterId: String, surah: Int): LongArray? {
        val db = dbFile(ctx, reciterId)?.takeIf { it.isFile } ?: return null
        return try {
            SQLiteDatabase.openDatabase(
                db.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { dbh ->
                dbh.rawQuery(
                    "select ayah, time from timings where sura = ?",
                    arrayOf(surah.toString()),
                ).use { c ->
                    val out = LongArray(300) { -1L }
                    var found = false
                    while (c.moveToNext()) {
                        val ayah = c.getInt(0)
                        if (ayah in 0..299) {
                            out[ayah] = c.getLong(1)
                            found = true
                        }
                    }
                    if (found) out else null
                }
            }
        } catch (t: Throwable) {
            null
        }
    }
}
