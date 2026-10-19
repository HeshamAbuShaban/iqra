package com.iqra.quran.data

import android.content.Context

/**
 * Ayah-level bookmarks, "surah:ayah", separate from the page bookmarks.
 * A page bookmark answers "where was I"; this one answers "which ayah".
 */
object AyahBookmarks {
    private const val F = "iqra_ayah_bookmarks"
    private const val K = "keys"

    fun all(ctx: Context): Set<String> =
        ctx.getSharedPreferences(F, Context.MODE_PRIVATE)
            .getStringSet(K, emptySet())!!.toSet()

    fun isBookmarked(ctx: Context, surah: Int, ayah: Int): Boolean =
        all(ctx).contains("$surah:$ayah")

    /** Returns true when this call added the bookmark. */
    fun toggle(ctx: Context, surah: Int, ayah: Int): Boolean {
        val cur = all(ctx).toMutableSet()
        val key = "$surah:$ayah"
        val added = if (cur.remove(key)) false else {
            cur.add(key)
            true
        }
        ctx.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
            .putStringSet(K, cur)
            .apply()
        return added
    }
}
