package com.iqra.quran.ui

/**
 * Forces a string to be read left-to-right, whatever direction its paragraph is.
 *
 * THE DEFECT THIS EXISTS FOR
 *
 * Live and Choose are wrapped in `LocalLayoutDirection = Rtl`. That part is
 * deliberate and correct: their content is Arabic, so the gutter has to move.
 * The surah number becomes the first child of its Row and lands on the right,
 * and the controls swap ends. Every Arabic string on both screens reads properly
 * as a result.
 *
 * It also had a cost nobody priced in. Compose resolves a paragraph's reading
 * direction from the layout direction the screen provides, so each English
 * string inherited RTL - and Compose's `Text` offers no way to disagree, at any
 * version, because Material3's overload has no `textDirection` parameter. Only
 * the lower-level `BasicText` does.
 *
 * For a pure-Latin line the inherited direction is usually invisible: the whole
 * line collapses into a single left-to-right run, which is merely right-aligned.
 * It stops being invisible at the END of the string. A trailing full stop or
 * question mark is a NEUTRAL character, and with no strong character after it to
 * belong to, it takes the paragraph's direction - and a paragraph set
 * right-to-left lays its trailing neutral out on the LEFT. So the header read
 * "?Which part", and a caption reading "The whole surah. 120 ayat, starting from
 * the first." opened with a full stop floating in front of it.
 *
 * The two invisible marks below are the Unicode bidi isolates, LRI and PDI
 * (U+2066, U+2069). They bracket a run and give that run its own direction,
 * independent of the paragraph's, which is precisely the missing capability.
 * Inside them a trailing neutral has a strong character to resolve against, so
 * it stays on the right. This is the standard mechanism for the problem and it
 * works through `Text`, `BasicText`, a `TextField`, an IME, or anything else -
 * which is why it is used here instead of swapping thirty-two call sites over to
 * a different composable and inheriting all of that composable's other
 * differences in styling.
 *
 * WHY THE STRINGS STAY RIGHT-ALIGNED
 *
 * Deliberately untouched. Alignment is `textAlign`, which resolves against the
 * LAYOUT direction, not the reading direction. These Latin strings therefore
 * remain flush right against the mirrored layout, where they belong in an
 * Arabic screen. Only the order of the glyphs inside the line box is corrected.
 * Dropping the RTL wrapper would have fixed the punctuation by un-mirroring the
 * whole screen, which is the opposite of what the screen is for.
 *
 * WHY ONLY LATIN
 *
 * Quranic text is not wrapped. In a right-to-left paragraph Arabic already
 * renders correctly, including the trailing ayah number the mushaf appends -
 * that is the single most common case in the entire format and Android's text
 * stack handles it. Wrapping it would achieve nothing and would put invisible
 * characters around the words being recited.
 *
 * Latin needs no special case either, which is the reason this is safe: with
 * RTL switched OFF these strings sit in a left-to-right paragraph, and an LTR
 * isolate around an LTR run is a no-op. One call site, correct in both states.
 */
private const val LRI = '\u2066'
private const val PDI = '\u2069'

/**
 * Reads [s] left-to-right. See the file comment for why.
 *
 * Content-aware since the Arabic translation: when the resolved string already
 * reads right-to-left - Arabic script anywhere in it - the isolate is dropped
 * rather than applied. An LRI around an Arabic run forces it into an LTR
 * paragraph, which mirrors exactly the punctuation defect this function exists
 * to prevent, one direction over. English strings keep the isolate; Arabic
 * strings pass through and the RTL paragraph resolves them correctly on its
 * own.
 */
fun ltr(s: String): String =
    if (s.any { it in '\u0600'..'\u08FF' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }) s
    else "$LRI$s$PDI"


/**
 * Folds Arabic text for matching, not for display.
 *
 * The surah names are stored fully vocalised (سُورَةُ ٱلْفَاتِحَةِ), so a
 * substring match against what a person types — الفاتحة — never fires: every
 * haraka and the wasla form of the alef stand between the query and the name.
 * This strips the marks, folds the alef variants and the hamza carriers onto
 * their base letters, and folds alif maqsura onto ya. Both sides of a match
 * go through it; the displayed text is never replaced by its fold.
 */
fun arabicFold(s: String): String = buildString(s.length) {
    for (raw in s) {
        val c = when (raw) {
            '\u064E', '\u064F', '\u0650', '\u0651', '\u0652', '\u0653',
            '\u0654', '\u0655', '\u0656', '\u0657', '\u0658', '\u0659',
            '\u065A', '\u065B', '\u065C', '\u065D', '\u065E', '\u065F',
            '\u0670', '\u06D6', '\u06D7', '\u06D8', '\u06D9', '\u06DA',
            '\u06DB', '\u06DC', '\u06DF', '\u06E0', '\u06E1', '\u06E2',
            '\u06E3', '\u06E4', '\u06E7', '\u06E8', '\u06EA', '\u06EB',
            '\u06EC', '\u06ED', '\u0640' -> null
            '\u0623', '\u0624', '\u0625', '\u0622', '\u0671' -> '\u0627'
            '\u0626' -> '\u064A'
            '\u0649' -> '\u064A'
            '\u0629' -> '\u0647'
            else -> raw
        }
        if (c != null) append(c)
    }
}
