package com.iqra.quran.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /**
     * Bumped once per schema change.
     *
     * There was no versioning here, which is only safe while every setting has a
     * default that reproduces the old behaviour. That stops being true the
     * moment a default is ever retuned: an existing install keeps its stored
     * value, a fresh install gets the new one, and the two behave differently
     * with no way to tell which is which. Recording the version lets
     * [migrate] do the conversion once, explicitly, instead of leaving it to
     * whichever key happens to be missing.
     */
    private const val VERSION = 1
    private const val K_VERSION = "ver"

    fun nightMode(ctx: Context): Boolean = prefs(ctx).getBoolean("night", false)
    fun setNightMode(ctx: Context, v: Boolean) = write(ctx) { putBoolean("night", v) }

    fun textBrightness(ctx: Context): Int = prefs(ctx).getInt("textB", 255)
    fun setTextBrightness(ctx: Context, v: Int) = write(ctx) { putInt("textB", v) }

    fun backgroundBrightness(ctx: Context): Int = prefs(ctx).getInt("bgB", 0)
    fun setBackgroundBrightness(ctx: Context, v: Int) = write(ctx) { putInt("bgB", v) }

    // ---- type size -------------------------------------------------------
    //
    // The page image is fixed - it is the printed mushaf bitmap, and scaling it
    // would resample the glyphs and make them soft. So this scales only the
    // app's OWN text: the expected-words line in the recite bar, the search
    // result rows, and the fallback line renderer used when a page image is
    // missing. That last one is the important one, because it is the only path
    // where mushaf text is drawn by the app rather than by the page image.
    //
    // Stored as an integer percent rather than a float: SharedPreferences has
    // no float type that is stable across the XML and DataStore writers, and
    // every consumer wants a discrete step anyway.

    /** Bounds are the point where Amiri stops being legible or stops fitting. */
    const val FONT_MIN = 80
    const val FONT_MAX = 200
    const val FONT_STEP = 10
    const val FONT_DEFAULT = 100

    fun fontScalePercent(ctx: Context): Int =
        prefs(ctx).getInt("fontPct", FONT_DEFAULT).coerceIn(FONT_MIN, FONT_MAX)

    fun setFontScalePercent(ctx: Context, v: Int) =
        write(ctx) { putInt("fontPct", v.coerceIn(FONT_MIN, FONT_MAX)) }

    /** The multiplier to hand `sp`/`TextUnit` sizes. 100 -> 1.0f. */
    fun fontScale(ctx: Context): Float = fontScalePercent(ctx) / 100f

    /** One-line summary for the settings screen, e.g. "100% · default". */
    fun fontScaleLabel(ctx: Context): String {
        val p = fontScalePercent(ctx)
        return if (p == FONT_DEFAULT) "$p% · default" else "$p%"
    }

    // ---- appearance -----------------------------------------------------

    /**
     * The chosen theme id, stored as an int because SharedPreferences has no
     * stable enum type and the enum order is not a storage contract.
     */
    fun themeId(ctx: Context): Int =
        prefs(ctx).getInt("themeId", 0)

    fun setThemeId(ctx: Context, v: Int) = write(ctx) { putInt("themeId", v) }

    fun theme(ctx: Context): com.iqra.quran.ui.theme.IqraTheme =
        com.iqra.quran.ui.theme.IqraTheme.from(themeId(ctx))

    /**
     * Whether the recitation screen may animate at all.
     *
     * Separate from the app-wide motion question because this is the one screen
     * with a continuously moving element, and someone who finds that distracting
     * reading a page does not want it switched on for a practice session either.
     */
    fun liveAnimation(ctx: Context): Boolean = prefs(ctx).getBoolean("liveAnim", true)

    fun setLiveAnimation(ctx: Context, v: Boolean) = write(ctx) { putBoolean("liveAnim", v) }

    // ---- recognition ------------------------------------------------------
    //
    // These are not cosmetics. Each one decides whether the app is ALLOWED to
    // accuse the reciter, and the defaults are the conservative choice rather
    // than the flattering one.

    /**
     * Never mark a word WRONG because of how long a madd was held.
     *
     * Measured from the tajweed register over all 51,395 madd sites: **9,706 are
     * free choice** (madd al-'aridh, munfasil, muttasil, silah, leen), where the
     * register itself lists the permitted counts and the shipped table asserts
     * only the canonical one. 41,543 are fixed at 2 and 146 at 6, where there is
     * no legal alternative.
     *
     * So for one madd site in five, holding a different length than the table
     * says is CORRECT recitation. Accusing there is a false accusation by
     * construction, not a tunable aggressiveness. Hence a hard prohibition
     * rather than a threshold.
     *
     * Default true. Turning it off cannot make madd length accusable - the DP
     * cannot separate "held 4 instead of 2" from a wrong consonant anyway - it
     * only restores single-realisation matching, which is useful for measuring
     * but not for judging.
     */
    fun maddNeverAccuses(ctx: Context): Boolean =
        prefs(ctx).getBoolean("recNoMadd", true)

    fun setMaddNeverAccuses(ctx: Context, v: Boolean) = write(ctx) { putBoolean("recNoMadd", v) }

    /**
     * Never mark a word WRONG because the reciter stopped on a waqf mark.
     *
     * The expected table is ONE realisation per ayah: waqf at every ayah end,
     * wasl everywhere inside. The mushaf carries 9,950 mid-ayah stop marks in
     * 3,970 ayat. Stopping on one is a different and equally legal realisation,
     * and the table cannot represent both. At 1,409 junctions a legitimate stop
     * currently produces a mismatch at the START of the NEXT word.
     *
     * Default true, because a reciter who stops is not making a mistake.
     */
    fun waqfNeverAccuses(ctx: Context): Boolean =
        prefs(ctx).getBoolean("recNoWaqf", true)

    fun setWaqfNeverAccuses(ctx: Context, v: Boolean) = write(ctx) { putBoolean("recNoWaqf", v) }

    /**
     * What "accuracy" means in the session report.
     *
     * SKIPPED is the ABSENCE of a verdict - no evidence covered the word. Counting
     * it as judged made a session that tested nothing report a large "words
     * judged" with a percentage derived from it, which reads as a score and is in
     * fact the population. Hence the choice is explicit.
     */
    enum class Metric(val id: Int, val label: String, val explain: String) {
        /** Share of decided words marked CORRECT. Skipped words are not in the denominator. */
        ACCURACY(0, "Accuracy", "correct ÷ decided words. A session that tested nothing shows —, not 0%."),

        /** How much of the recitation was actually judged. */
        COVERAGE(1, "Coverage", "decided ÷ words reached. Low means the app saw little, not that you erred."),

        /** Decided words as a count. The only figure with no denominator to flatter it. */
        DECIDED(2, "Words decided", "How many words the engine had evidence for. No percentage."),

        /** Correct against wrong only, ignoring everything undecided. */
        NET(3, "Correct vs wrong", "correct and wrong as absolute counts, side by side.");

        companion object {
            fun of(id: Int): Metric = entries.firstOrNull { it.id == id } ?: ACCURACY
        }
    }

    fun metric(ctx: Context): Metric = Metric.of(prefs(ctx).getInt("recMetric", 0))

    fun setMetric(ctx: Context, m: Metric) = write(ctx) { putInt("recMetric", m.id) }

    /**
     * Show the recognition diagnostics panel: window health, decoder state,
     * evidence counters.
     *
     * Default off, but the failure this exists for is invisible by design - a
     * collapsed evidence window produces UNKNOWN everywhere, which renders as
     * silence. A reader has no way to tell "the engine could not hear me" from
     * "I recited it wrong", and silence is not a diagnosis.
     */
    fun diagnostics(ctx: Context): Boolean = prefs(ctx).getBoolean("recDiag", false)

    fun setDiagnostics(ctx: Context, v: Boolean) = write(ctx) { putBoolean("recDiag", v) }

    /**
     * How hard the engine works to accuse.
     *
     * This is the one setting that must not be raised casually: every unit of
     * [WRONG_MIN_HEARD_COVERAGE] is a chance to accuse a reciter on thinner
     * evidence. `Cautious` is the default and `Strict` is offered only because a
     * user may want it, with the consequence stated.
     */
    enum class Strictness(val id: Int, val label: String) {
        CAUTIOUS(0, "Cautious"),
        BALANCED(1, "Balanced"),
        STRICT(2, "Strict");

        companion object {
            fun of(id: Int): Strictness = entries.firstOrNull { it.id == id } ?: CAUTIOUS
        }
    }

    fun strictness(ctx: Context): Strictness = Strictness.of(prefs(ctx).getInt("recStrict", 0))

    fun setStrictness(ctx: Context, s: Strictness) = write(ctx) { putInt("recStrict", s.id) }

    /**
     * Fraction of a word's units that must be heard before a mismatch can be
     * called WRONG. See [strictness]; lower means more accusations.
     *
     * 0.80 is the validated value and is CAUTIOUS. It is exposed so the setting
     * is visible and reversible rather than buried, not because it is
     * recommended to change it.
     */
    fun heardFloor(ctx: Context): Float =
        when (strictness(ctx)) {
            Strictness.CAUTIOUS -> 0.80f
            Strictness.BALANCED -> 0.60f
            Strictness.STRICT -> 0.40f
        }

    // ---- practice --------------------------------------------------------

    /**
     * Hold the screen awake while reciting.
     *
     * Android's default is to start dimming after ~30 s, and a recitation is
     * routinely longer than that with the phone flat on a table - the screen
     * goes dark mid-ayah and the session looks finished. Default on, because
     * the failure it prevents (a lost session) is worse than the battery it
     * costs.
     */
    fun keepAwake(ctx: Context): Boolean = prefs(ctx).getBoolean("keepAwake", true)
    fun setKeepAwake(ctx: Context, v: Boolean) = write(ctx) { putBoolean("keepAwake", v) }

    // ---- change notification --------------------------------------------

    /**
     * Incremented on every write, so Compose can observe the settings.
     *
     * Every reader here is a plain synchronous getter, which meant a setting
     * changed on one screen had no way to reach another: `NightTuningPanel`
     * wrote `bgB` on every slider tick, and because the panel keeps its own
     * local copy of the value its own preview updated - but the page behind it
     * kept the old brightness until something else happened to recompose it.
     * The reader is a StateFlow of an Int rather than the value itself because
     * it only ever has to mean "something moved"; consumers key a `remember`
     * on it and re-read what they need.
     */
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick.asStateFlow()

    private fun write(ctx: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(ctx).edit().apply(block).apply()
        _tick.value = _tick.value + 1
    }

    /**
     * Bring an existing install up to the current schema. Safe to call often.
     *
     * v0 -> v1 added `fontPct` and `keepAwake`. Neither needs converting: a
     * missing key already reads as the intended default, so this only records
     * that the upgrade ran. The step exists so the next one has somewhere to
     * go.
     */
    fun migrate(ctx: Context) {
        val p = prefs(ctx)
        if (p.getInt(K_VERSION, 0) >= VERSION) return
        p.edit()
            .putInt("fontPct", p.getInt("fontPct", FONT_DEFAULT).coerceIn(FONT_MIN, FONT_MAX))
            .putBoolean("keepAwake", p.getBoolean("keepAwake", true))
            .putInt(K_VERSION, VERSION)
            .apply()
        _tick.value = _tick.value + 1
    }

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
