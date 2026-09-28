package com.iqra.quran.ui

import android.content.Context
import androidx.compose.ui.graphics.Color

/**
 * Reader display settings, persisted.
 *
 * The night-mode maths is a direct port of quran_android's HighlightingImageView:
 *   adjusted = 50 * ln1p(backgroundBrightness) + textBrightness   (clamped to 255)
 *   page pixel out = adjusted - in                               (per channel)
 * The ln1p term exists so raising the background above pure black lifts the
 * text with it and the page keeps its contrast. Defaults are text 255,
 * background 0, which is a full inversion on black.
 */
object ReaderPrefs {
    private const val F = "iqra_reader"

    fun nightMode(ctx: Context): Boolean = prefs(ctx).getBoolean("night", false)
    fun setNightMode(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("night", v).apply()

    fun textBrightness(ctx: Context): Int = prefs(ctx).getInt("textB", 255)
    fun setTextBrightness(ctx: Context, v: Int) = prefs(ctx).edit().putInt("textB", v).apply()

    fun backgroundBrightness(ctx: Context): Int = prefs(ctx).getInt("bgB", 0)
    fun setBackgroundBrightness(ctx: Context, v: Int) = prefs(ctx).edit().putInt("bgB", v).apply()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(F, Context.MODE_PRIVATE)
}

object NightPalette {
    /** 50 * ln1p(bg) + text, clamped - identical to the reference formula. */
    fun adjustedTextBrightness(text: Int, bg: Int): Int =
        (50.0 * kotlin.math.ln1p(bg.toDouble()) + text).toInt().coerceIn(0, 255)

    /** Mat behind the page: flat grey at the user's background brightness. */
    fun mat(ctx: Context): Color {
        if (!ReaderPrefs.nightMode(ctx)) return Color(0xFFF4EAD3)
        val b = ReaderPrefs.backgroundBrightness(ctx)
        return Color(b, b, b)
    }

    /**
     * The mat actually painted. Day mode uses the reference's tiled paper
     * gradient (QuranDisplayHelper.getShaderFactory):
     *   DCDAD5 -> FDFDF4 -> FFFFFF -> FDFBEF  at stops 0 / 0.18 / 0.48 / 1
     * tiled across the screen width, which is what gives the reference its
     * subtle sheen instead of a flat slab. Night mode stays flat, because a
     * gradient behind an inverted page just looks dirty.
     */
    fun pageGradient(): List<Pair<Float, Color>> = listOf(
        0f to Color(0xFFDCDAD5),
        0.18f to Color(0xFFFDFDF4),
        0.48f to Color(0xFFFFFFFF.toInt()),
        1f to Color(0xFFFDFBEF),
    )

    /** 1px fold line colour, matching the reference's alternate-page rule. */
    fun foldColor(night: Boolean): Color =
        if (night) Color(0x33FFFFFF) else Color(0x22000000)

    /**
     * Ink colour for text drawn ON the page (header/footer overlay, ayah
     * markers). The page's own black becomes `adjusted` after inversion, so
     * this must be the same value or the page stops reading as one image.
     * Day mode uses the reference's flat overlay grey #ff505050.
     */
    fun pageInk(ctx: Context): Color {
        if (!ReaderPrefs.nightMode(ctx)) return Color(0xFF505050)
        val a = adjustedTextBrightness(ReaderPrefs.textBrightness(ctx), ReaderPrefs.backgroundBrightness(ctx))
        return Color(a, a, a)
    }

    /** UI chrome colour (buttons, labels) on the mat. */
    fun chrome(ctx: Context): Color =
        if (ReaderPrefs.nightMode(ctx)) Color(0xFFB9BDC6) else Color(0xFF2B2B2B)
}
