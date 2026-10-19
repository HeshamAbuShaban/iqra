package com.iqra.quran.data

import android.content.Context
import android.content.SharedPreferences

/** Playback and offline-audio settings, persisted in the ReaderPrefs style. */
object AudioPrefs {
    private const val F = "iqra_audio"

    fun reciter(ctx: Context): String =
        prefs(ctx).getString("reciter", Reciters.DEFAULT.id) ?: Reciters.DEFAULT.id
    fun setReciter(ctx: Context, id: String) = write(ctx) { putString("reciter", id) }

    /** Keep every streamed surah on disk so replays never re-download. */
    fun keepOffline(ctx: Context): Boolean = prefs(ctx).getBoolean("keep", true)
    fun setKeepOffline(ctx: Context, v: Boolean) = write(ctx) { putBoolean("keep", v) }

    /** Only cache files while on Wi-Fi; playback itself always streams. */
    fun wifiOnly(ctx: Context): Boolean = prefs(ctx).getBoolean("wifiOnly", true)
    fun setWifiOnly(ctx: Context, v: Boolean) = write(ctx) { putBoolean("wifiOnly", v) }

    const val SPEED_MIN = 75
    const val SPEED_MAX = 150
    const val SPEED_STEP = 25
    const val SPEED_DEFAULT = 100

    fun speedPercent(ctx: Context): Int =
        prefs(ctx).getInt("speedPct", SPEED_DEFAULT).coerceIn(SPEED_MIN, SPEED_MAX)
    fun setSpeedPercent(ctx: Context, v: Int) =
        write(ctx) { putInt("speedPct", v.coerceIn(SPEED_MIN, SPEED_MAX)) }

    /** Roll into the next surah when one finishes. */
    fun autoNext(ctx: Context): Boolean = prefs(ctx).getBoolean("autoNext", false)
    fun setAutoNext(ctx: Context, v: Boolean) = write(ctx) { putBoolean("autoNext", v) }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(F, Context.MODE_PRIVATE)

    private fun write(ctx: Context, block: SharedPreferences.Editor.() -> Unit) {
        val e = prefs(ctx).edit()
        e.block()
        e.apply()
    }
}
