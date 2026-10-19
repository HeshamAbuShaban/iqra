package com.iqra.quran.data

import android.content.Context
import java.io.File

/** The on-disk surah audio library: filesDir/audio/<reciterId>/<NNN>.mp3. */
object AudioStore {
    data class Entry(val reciterId: String, val surah: Int, val bytes: Long, val modified: Long)

    fun file(ctx: Context, reciterId: String, surah: Int): File =
        File(File(root(ctx), reciterId), "%03d.mp3".format(surah))

    fun exists(ctx: Context, reciterId: String, surah: Int): Boolean =
        file(ctx, reciterId, surah).isFile

    fun list(ctx: Context): List<Entry> =
        root(ctx).listFiles().orEmpty().flatMap { dir ->
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".mp3") }
                .mapNotNull { f ->
                    val n = f.name.removeSuffix(".mp3").toIntOrNull() ?: return@mapNotNull null
                    Entry(dir.name, n, f.length(), f.lastModified())
                }
        }

    fun bytes(ctx: Context): Long = list(ctx).sumOf { it.bytes }

    fun delete(ctx: Context, entry: Entry): Boolean =
        file(ctx, entry.reciterId, entry.surah).delete()

    fun deleteAll(ctx: Context): Int = list(ctx).count { delete(ctx, it) }

    private fun root(ctx: Context): File = File(ctx.filesDir, "audio")
}
