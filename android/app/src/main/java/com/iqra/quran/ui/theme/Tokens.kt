package com.iqra.quran.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.R

/**
 * The app's design tokens, in one place.
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The tokens used to live as top-level `val`s inside a 2,865-line MainActivity,
 * and three colour systems had grown alongside them:
 *
 *   1. `darkMushafScheme()`  - the app-wide scheme, always dark
 *   2. `lightReaderScheme()` - a separate light scheme for the reader only
 *   3. twenty literals local to LiveMode.kt
 *
 * So the recitation screen was violet and cyan while the rest of the app was teal
 * and gold, and the same five word statuses were rendered from five different
 * literals in two files. Nothing about that looked like a deliberate choice,
 * because it was not one.
 *
 * The palette itself is not new. Teal and gold on near-black was already the app
 * - `accentColor` and `goldColor` - and it is the register the subject belongs to:
 * illuminated manuscript, gold leaf on a dark ground, a colour of night. The fix
 * was never a better colour. It was one colour.
 *
 * Themes are described declaratively and resolved by [iqraTheme], so a theme is a
 * value that can be chosen in Settings and previewed rather than a constant that
 * has to be recompiled to change.
 */

/** The named themes a user can pick. Order is the order they appear in Settings. */
enum class IqraTheme(val id: Int, val label: String, val blurb: String) {
    /** Teal and gold on near-black. The original identity, and the default. */
    NightGarden(0, "Night garden", "Teal and gold on near-black"),

    /** Cooler and quieter: the same light, further away. */
    InkAndSilver(1, "Ink and silver", "Cool slate with pale gold lettering"),

    /** Warmer, more parchment. Gentler contrast for evening reading. */
    Parchment(2, "Parchment", "Warm low-contrast, easy on the eyes at night"),

    /** Highest contrast. For bright light or a screen you are squinting at. */
    HighContrast(3, "High contrast", "Maximum separation for bright rooms"),
    ;

    companion object {
        fun from(id: Int): IqraTheme = entries.firstOrNull { it.id == id } ?: NightGarden
    }
}

/**
 * Everything that can change with the theme.
 *
 * Kept as one flat set of plain values rather than as a Material `ColorScheme`,
 * because two thirds of the interface - the charts, the horizon, the word colours -
 * are drawn on a `Canvas` and never consult `MaterialTheme` at all. A scheme that
 * only carried scheme colours would have left every hand-drawn surface unable to
 * follow the theme.
 */
data class IqraColors(
    /** The page itself. The darkest thing in the app. */
    val ground: Color,
    /** A panel sitting on the ground. One step up. */
    val surface: Color,
    /** A panel sitting on a panel. */
    val surfaceHigh: Color,
    /** A hairline. Must stay subtle; a bright divider is a drawn box. */
    val hairline: Color,

    /** The single accent. Used for progress, focus and the word "you". */
    val accent: Color,
    /** A dimmer form of [accent], for fills behind accent text. */
    val accentSoft: Color,
    /** The second accent: gold. Scripture, marks, illumination. */
    val gold: Color,
    /** A brighter gold, for the lit edge of something. */
    val goldBright: Color,

    /** Primary text. */
    val ink: Color,
    /** Secondary text: labels, units, captions. */
    val inkMuted: Color,
    /** Tertiary: axis ticks, disabled affordances. */
    val inkFaint: Color,

    /** A word was heard and matched. */
    val wordGood: Color,
    /** A word was heard and was wrong. */
    val wordBad: Color,
    /** The model declined to judge. Never counted against the reciter. */
    val wordUnknown: Color,
    /** The word being recited right now. */
    val wordCurrent: Color,

    /** Chart line 1 - accuracy. */
    val series1: Color,
    /** Chart line 2 - pace. */
    val series2: Color,
    /** A good thing in a chart. */
    val chartGood: Color,
    /** A bad thing in a chart. */
    val chartBad: Color,
    /** The empty half of a heatmap cell. */
    val chartEmpty: Color,
) {
    /**
     * Word colour by verdict, in one place.
     *
     * This existed twice - once in the reader's `resolveWordStyle` and once in
     * `LiveWord` - as two sets of five literals that had already drifted apart. A
     * verdict is one fact about a word and must not be able to be two colours.
     */
    fun forVerdict(verdict: Int): Color = when (verdict) {
        0 -> wordGood
        1 -> wordBad
        2 -> wordUnknown
        else -> wordCurrent
    }
}

/**
 * Shapes. Every corner in the app comes from here.
 *
 * These are `Shape`s and not bare radii, because that is what every call site
 * actually wants - a `RoundedCornerShape`, not a `Dp` it has to wrap itself. The
 * first version of this object held Dp values, mirroring the old
 * `CardRadius = RoundedCornerShape(16.dp)` / `Pill = RoundedCornerShape(50)`
 * constants it replaced, and every consumer failed to compile with
 * "actual type is Dp, but Shape was expected".
 */
object IqraShape {
    val card = RoundedCornerShape(18.dp)
    val panel = RoundedCornerShape(14.dp)
    /** 50% on a shape makes a stadium, which is what "pill" has to mean. */
    val pill = RoundedCornerShape(percent = 50)
    val sheet = RoundedCornerShape(28.dp)
    val circle = CircleShape
}

/**
 * Type scale.
 *
 * The project had no `Typography` object at all - every size was a hardcoded `sp`
 * at its point of use, which is why two screens could both say 12sp and mean
 * different things. Sizes here are the scale; screens name the role.
 *
 * Arabic is set in Amiri with generous line height. Amiri is a naskh face whose
 * marks stack high above and below the baseline, and at a normal 1.2 line height
 * the fatha of the line above collides with the alif of the one below. That
 * collision is not a style opinion, it is a legibility failure, so scripture sizes
 * carry their own lineHeight rather than inheriting it.
 */
object IqraType {
    val scriptLarge = TextStyle(
        fontFamily = FontFamily(Font(R.font.amiri)), fontSize = 34.sp,
        lineHeight = 62.sp, fontWeight = FontWeight.Normal,
    )
    val scriptMedium = TextStyle(
        fontFamily = FontFamily(Font(R.font.amiri)), fontSize = 24.sp,
        lineHeight = 46.sp, fontWeight = FontWeight.Normal,
    )
    val scriptSmall = TextStyle(
        fontFamily = FontFamily(Font(R.font.amiri)), fontSize = 18.sp,
        lineHeight = 34.sp, fontWeight = FontWeight.Normal,
    )
    val display = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Light)
    val title = TextStyle(fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
    /** Numbers that have to line up in a column or be compared at a glance. */
    val metric = TextStyle(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
}

/**
 * Motion.
 *
 * Springs, not tweens, for anything a finger is waiting on. A tween has a fixed
 * duration, so at 120 ms it feels sluggish on a fast interaction and at 300 ms it
 * feels broken on a slow one; a spring settles as fast as the physics allow and
 * carries velocity through, which is what makes a gesture feel connected to what
 * it caused.
 */
object IqraMotion {
    /** A word resolving. Stiff, so it arrives rather than drifts. */
    val wordIn: SpringSpec<Float> = spring(dampingRatio = 0.72f, stiffness = 700f)
    /** Panels, sheets, anything that changes size. */
    val settle: SpringSpec<Float> = spring(dampingRatio = 0.86f, stiffness = 380f)
    /** The horizon responding to the voice. Softer, because it never fully rests. */
    val flow: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 120f)
    /** Values that should not wobble at all, like a progress bar. */
    val crisp: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 900f)

    /** Decelerate, for something entering. */
    val enter: CubicBezierEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    /** Accelerate, for something leaving. */
    val exit: CubicBezierEasing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}

/** Every theme's colours, resolved once here. */
object IqraPalettes {

    val nightGarden = IqraColors(
        ground = Color(0xFF070B0C),
        surface = Color(0xFF0F1A1A),
        surfaceHigh = Color(0xFF162424),
        hairline = Color(0x14FFFFFF),
        accent = Color(0xFF2BB6A0),
        accentSoft = Color(0x1F2BB6A0),
        gold = Color(0xFFD9B36B),
        goldBright = Color(0xFFF2DDA8),
        ink = Color(0xFFEAF3EE),
        inkMuted = Color(0xFF93A8A0),
        inkFaint = Color(0xFF5E716A),
        wordGood = Color(0xFFF2EFE4),
        wordBad = Color(0xFFF08A80),
        wordUnknown = Color(0xFF7E8C86),
        wordCurrent = Color(0xFFF2DDA8),
        series1 = Color(0xFF2BB6A0),
        series2 = Color(0xFFD9B36B),
        chartGood = Color(0xFF2BB6A0),
        chartBad = Color(0xFFE0625A),
        chartEmpty = Color(0x14FFFFFF),
    )

    val inkAndSilver = IqraColors(
        ground = Color(0xFF0A0C10),
        surface = Color(0xFF131720),
        surfaceHigh = Color(0xFF1B202B),
        hairline = Color(0x16FFFFFF),
        accent = Color(0xFF7FA8C9),
        accentSoft = Color(0x1F7FA8C9),
        gold = Color(0xFFD8DEE8),
        goldBright = Color(0xFFF4F7FB),
        ink = Color(0xFFEDF1F7),
        inkMuted = Color(0xFF97A3B4),
        inkFaint = Color(0xFF5F6A7A),
        wordGood = Color(0xFFEDF1F7),
        wordBad = Color(0xFFE58C82),
        wordUnknown = Color(0xFF7C8899),
        wordCurrent = Color(0xFFF4F7FB),
        series1 = Color(0xFF7FA8C9),
        series2 = Color(0xFFD8DEE8),
        chartGood = Color(0xFF7FA8C9),
        chartBad = Color(0xFFE58C82),
        chartEmpty = Color(0x14FFFFFF),
    )

    val parchment = IqraColors(
        ground = Color(0xFF12100C),
        surface = Color(0xFF1D1A13),
        surfaceHigh = Color(0xFF262117),
        hairline = Color(0x14FFFFFF),
        accent = Color(0xFFC79A5B),
        accentSoft = Color(0x22C79A5B),
        gold = Color(0xFFE3C079),
        goldBright = Color(0xFFF7E7BE),
        ink = Color(0xFFEFE6D4),
        inkMuted = Color(0xFFA99B84),
        inkFaint = Color(0xFF726A5C),
        wordGood = Color(0xFFEFE6D4),
        wordBad = Color(0xFFDD9084),
        wordUnknown = Color(0xFF8C8371),
        wordCurrent = Color(0xFFF7E7BE),
        series1 = Color(0xFFC79A5B),
        series2 = Color(0xFFE3C079),
        chartGood = Color(0xFFC79A5B),
        chartBad = Color(0xFFDD9084),
        chartEmpty = Color(0x14FFFFFF),
    )

    val highContrast = IqraColors(
        ground = Color(0xFF000000),
        surface = Color(0xFF0C1416),
        surfaceHigh = Color(0xFF152024),
        hairline = Color(0x33FFFFFF),
        accent = Color(0xFF5BE8CE),
        accentSoft = Color(0x335BE8CE),
        gold = Color(0xFFFFD98A),
        goldBright = Color(0xFFFFF0C8),
        ink = Color(0xFFFFFFFF),
        inkMuted = Color(0xFFC3D0CE),
        inkFaint = Color(0xFF93A5A2),
        wordGood = Color(0xFFFFFFFF),
        wordBad = Color(0xFFFF9E92),
        wordUnknown = Color(0xFFB4C0BD),
        wordCurrent = Color(0xFFFFD98A),
        series1 = Color(0xFF5BE8CE),
        series2 = Color(0xFFFFD98A),
        chartGood = Color(0xFF5BE8CE),
        chartBad = Color(0xFFFF9E92),
        chartEmpty = Color(0x1FFFFFFF),
    )

    fun of(theme: IqraTheme): IqraColors = when (theme) {
        IqraTheme.NightGarden -> nightGarden
        IqraTheme.InkAndSilver -> inkAndSilver
        IqraTheme.Parchment -> parchment
        IqraTheme.HighContrast -> highContrast
    }
}

/**
 * The colours the recitation screen draws with.
 *
 * Separate from [IqraColors] on purpose, and this is the correction of the violet
 * mistake. The recite screen is the one place in the app with no page, no text
 * block and no list - just a ground and one luminous form - so it needs its own
 * values for the *light*, while the words and chrome keep following the theme.
 *
 * It used to declare violet, cyan and blue of its own, which is why that screen
 * looked like it belonged to a different app. Everything below is derived from the
 * theme's own accent and gold, so a theme change moves the light with it.
 */
data class LiveColors(
    val ground: Color,
    /** The body of the form: a deep, dark version of the accent. */
    val body: Color,
    /** The body where it is thickest. */
    val bodyDeep: Color,
    /** The crest. Gold, because that is what a lit edge looks like. */
    val crest: Color,
    /** The crest at its brightest, when the voice is loud and high. */
    val crestHot: Color,
    /** The mist inside the form. */
    val mist: Color,
    /** The sacred-geometry lattice behind everything. Barely there on purpose. */
    val lattice: Color,
    /** The light's colour while the engine is thinking. */
    val waiting: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    /** The floating control bar. */
    val bar: Color,
)

/**
 * Build [LiveColors] from a theme.
 *
 * The hue relationships are fixed and the values are tuned per theme rather than
 * derived arithmetically, because "accent darkened to 12%" is not the same hue
 * relationship in teal as it is in slate and the difference is visible at exactly
 * the size this form occupies.
 */
fun liveColorsFor(c: IqraColors): LiveColors = LiveColors(
    ground = c.ground,
    body = mix(c.accent, c.ground, 0.78f),
    bodyDeep = mix(c.accent, c.ground, 0.88f),
    crest = c.gold,
    crestHot = c.goldBright,
    mist = mix(c.accent, c.gold, 0.35f),
    // 3.5% is deliberate, and it was 7% first. The lattice is a texture, not a
    // pattern. At 7% on a near-black ground it read as geometric wallpaper and
    // dominated the screen - the exact opposite of a background that recedes.
    lattice = c.gold.copy(alpha = 0.035f),
    waiting = mix(c.accent, c.gold, 0.20f),
    ink = c.ink,
    inkMuted = c.inkMuted,
    inkFaint = c.inkFaint,
    bar = c.surfaceHigh.copy(alpha = 0.92f),
)

/** Linear mix in ARGB. Short enough that a colour library would not earn its keep. */
fun mix(a: Color, b: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * u,
        green = a.green + (b.green - a.green) * u,
        blue = a.blue + (b.blue - a.blue) * u,
        alpha = a.alpha + (b.alpha - a.alpha) * u,
    )
}

// ---- wiring --------------------------------------------------------------

/**
 * The active theme, and the light the recitation screen draws with.
 *
 * A `staticCompositionLocalOf` rather than reading `MaterialTheme.colorScheme`,
 * because most of the interesting surfaces in this app are drawn on a `Canvas` and
 * never consult the scheme. CompositionLocal means a chart, the horizon and a word
 * all read the same object, so a theme change moves all of them together.
 */
val LocalIqraColors = androidx.compose.runtime.staticCompositionLocalOf {
    IqraPalettes.nightGarden
}
val LocalLiveColors = androidx.compose.runtime.staticCompositionLocalOf {
    liveColorsFor(IqraPalettes.nightGarden)
}

/** Material's own scheme, derived from the same colours, for stock components. */
fun iqraScheme(c: IqraColors): androidx.compose.material3.ColorScheme =
    androidx.compose.material3.darkColorScheme(
        primary = c.accent,
        onPrimary = c.ground,
        secondary = c.gold,
        onSecondary = c.ground,
        background = c.ground,
        onBackground = c.ink,
        surface = c.surface,
        onSurface = c.ink,
        surfaceVariant = c.surfaceHigh,
        // AlertDialog and the sheet group read these, not `surface`. Left unset
        // they fall back to M3 defaults, which is why the dialog over the recite
        // screen was grey-purple on a warm theme.
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceHigh,
        surfaceContainerHighest = c.surfaceHigh,
        surfaceContainerLow = c.surface,
        surfaceContainerLowest = c.ground,
        surfaceBright = c.surfaceHigh,
        surfaceDim = c.ground,
        surfaceTint = c.accent,
        // The container roles. Leaving these unset does not make them neutral - it
        // makes them M3's defaults, which are purple. The Continue card uses
        // secondaryContainer, so with these missing it came out grey-purple on a
        // warm theme and looked like a bug rather than an oversight.
        secondaryContainer = c.accent.copy(alpha = 0.14f),
        onSecondaryContainer = c.accent,
        tertiary = c.gold,
        onTertiary = c.ground,
        tertiaryContainer = c.gold.copy(alpha = 0.16f),
        onTertiaryContainer = c.gold,
        errorContainer = c.chartBad.copy(alpha = 0.16f),
        onErrorContainer = c.chartBad,
        inverseSurface = c.ink,
        inverseOnSurface = c.ground,
        inversePrimary = c.accent,
        scrim = Color.Black,
        onSurfaceVariant = c.inkMuted,
        outline = c.hairline,
        outlineVariant = c.hairline,
        error = c.chartBad,
        onError = c.ground,
    )
