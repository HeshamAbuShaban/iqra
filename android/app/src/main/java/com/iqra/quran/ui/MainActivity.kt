package com.iqra.quran.ui

import com.iqra.quran.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.Refresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Share
import android.content.Intent
import android.view.WindowManager
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.shadow
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqra.quran.data.AudioPrefs
import com.iqra.quran.data.AyahBookmarks
import com.iqra.quran.data.AyahTimings
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.MushafPage
import com.iqra.quran.data.MushafWord
import com.iqra.quran.data.HighlightLayer
import com.iqra.quran.data.PracticeLog
import com.iqra.quran.data.Reciters
import com.iqra.quran.data.WordStatus
import com.iqra.quran.data.GlyphCoords
import androidx.compose.runtime.CompositionLocalProvider
import com.iqra.quran.ui.theme.IqraPalettes
import com.iqra.quran.ui.theme.LocalIqraColors
import com.iqra.quran.ui.theme.LocalLiveColors
import com.iqra.quran.ui.theme.iqraScheme
import com.iqra.quran.ui.theme.liveColorsFor

class MainActivity : ComponentActivity() {
    private val vm by lazy {
        ViewModelProvider(this)[PracticeViewModel::class.java]
    }
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingStart?.invoke() else pendingStart = null
    }
    private var pendingStart: (() -> Unit)? = null

    // The app's chosen UI language, applied below API 33 where the system has
    // no per-app locale store. On 33+ the LocaleManager owns it and this is a
    // no-op pass-through. See AppLocales.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first composition reads a setting, so a migrated value is
        // never briefly the old one on screen.
        ReaderPrefs.migrate(this)
        setContent {
            // The theme is read here, above everything, so a change repaints the
            // whole app and every CompositionLocal below it - including the charts
            // and the horizon, which are drawn on a Canvas and never consult
            // MaterialTheme for themselves.
            val themeState = ReaderPrefs.tick.collectAsStateWithLifecycle()
            val t = remember(themeState.value) { ReaderPrefs.theme(this) }

            // The screen stays on for the whole app.
            //
            // This used to be three unrelated decisions: a view-local
            // `keepScreenOn = true` inside LiveMode, and a preference read by
            // the reader but only applied while `recording`. So the one screen
            // where it mattered most - a recitation, with the phone on a table
            // and the reciter walking around the room - was the one screen whose
            // behaviour could not be turned off, while every other screen ran
            // against the phone's own timeout. A recitation whose screen dimmed
            // mid-ayah does not read as a dimmed screen. It reads as the session
            // having ended.
            //
            // `FLAG_KEEP_SCREEN_ON` is set on the window rather than on a view so
            // there is exactly one place that owns it, and so it covers screens
            // that have no view of their own to hang it on. The preference is
            // still honoured and still defaults to on: someone who deliberately
            // reads a long passage on a battery they are trying to protect has
            // asked for something specific, and silently overriding that would be
            // its own kind of surprise.
            val keepAwake = remember(themeState.value) { ReaderPrefs.keepAwake(this) }
            LaunchedEffect(keepAwake) {
                if (keepAwake) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val cols = remember(t) { IqraPalettes.of(t) }
            // The recitation screen may paint with a theme of its own. Derived
            // from its own palette, not from `cols`, so the override is a real
            // palette change rather than a tint of the app's - which is what
            // makes it worth having on a screen that is one glowing element on
            // a dark ground.
            val liveTheme = remember(themeState.value) { ReaderPrefs.liveTheme(this) }
            val liveCols = remember(liveTheme) { liveColorsFor(IqraPalettes.of(liveTheme)) }
            CompositionLocalProvider(
                LocalIqraColors provides cols,
                LocalLiveColors provides liveCols,
            ) {
            MaterialTheme(colorScheme = iqraScheme(cols), shapes = mushafShapes()) {
                App(
                    vm,
                    onRequestMic = { block ->
                        if (ContextCompat.checkSelfPermission(
                                this, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            block()
                        } else {
                            pendingStart = block
                            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }
            }
        }
    }
}

/** Day mat: the reference's tiled paper gradient; night: flat grey. */
private fun pageMat(ctx: android.content.Context, night: Boolean): Color =
    if (night) NightPalette.mat(ctx) else Color(0xFFF6F1E3)

/**
 * The face for Quranic text.
 *
 * Amiri is a Naskh revival, which is not the same thing as the Uthmani script the
 * mushaf is written in. It carries the whole Unicode Arabic block so nothing goes
 * missing, but its `ccmp` has no rule for the tatweel + superscript-alef pair
 * (U+0640 U+0670) that 5,805 mushaf words use for the dagger alef, so
 * `ٱلرَّحْمَـٰنِ` draws a stray baseline kashida with a small mark floating above it
 * instead of one Uthmani dagger alef. The text data is already correct Uthmani
 * orthography in plain codepoints, so this was purely a letterform problem.
 *
 * DigitalKhatt Old Madina is Uthmani-style calligraphic and is what this font
 * family leads with. Amiri stays in the chain because DigitalKhatt lacks exactly
 * two codepoints the text uses - U+06EA and U+06EB, the empty-centre stop marks,
 * one occurrence of each in the whole Quran - and a missing glyph is a tofu box on
 * a page of scripture, which is not a trade worth making for the sake of two
 * characters.
 *
 * SIL Open Font License 1.1. The KFGQPC Uthmani faces were rejected: their
 * embedded EULA reserves all rights and forbids modification, which collides with
 * Play's automatic font subsetting.
 */
internal val quranFont = FontFamily(
    Font(R.font.oldmadina),
    Font(R.font.amiri),
)
internal val accentColor = Color(0xFF2BB6A0)

/**
 * Minimum glyph box width, in the DB's 1024-wide page space, for a box to
 * count as a word body. Superscript and diacritic marks are stored as their
 * own glyph rows and are far smaller (median height 21px vs 70px).
 */
private const val MIN_WORD_BOX = 20f

/**
 * How far hide mode's mask is grown beyond a word's own box, in the DB's
 * 1024-wide page space.
 *
 * Word diacritics overhang their glyph box by a few px: measured on page 14,
 * 855 px of ink sat outside all 121 word rects, in clusters of 3-7px strokes,
 * which stayed visible as specks of a word the user was trying to memorise.
 * 3px covers the observed overhang and is far below the gap between adjacent
 * word bodies, so no neighbour is clipped. Applied to the paper fill and the
 * clip identically - if they disagreed the fill would no longer match its hole.
 */
private const val HIDDEN_OUTSET_PX = 3f

/**
 * Minimum glyph box HEIGHT for the same test, and the reason it is separate
 * from the width. Measured over all 88,246 rows of ayahinfo_1024.db the height
 * is cleanly bimodal: 4,359 rows are 23px tall or less (the marks), NO row is
 * between 24 and 26, and the word bodies climb back to a mode at 57-58px. So
 * the height separates the two populations across a real gap rather than a
 * tuned constant. The width cannot do that job: 4,090 of the mark rows are
 * 20-23px tall but narrower than [MIN_WORD_BOX], so the width is what used to
 * reject them - and once the inverted rows are repaired (see [pairAyahBoxes])
 * 37 more marks are 20-29px WIDE and only the height still rejects them.
 */
private const val MIN_WORD_H = 24f
internal val wrongColor = Color(0xFFE0625A)
internal val goldColor = Color(0xFFD9B36B)
private val PAGE_MASK = Color(0xFFF3ECD9) // parchment, used to hide words on light page images
private object Chrome {
    val Bar = Color(0xFF1F1F26).copy(alpha = 0.96f)
    val HeaderTop = Color(0xFF1F1F26)
    val HeaderMid = Color(0xCC1F1F26)
    val OnChrome = Color(0xFFF2E8D5)
    val OnChromeMuted = Color(0xB3F2E8D5)
}

private fun lightReaderScheme(night: Boolean, mat: Color, ink: Color, chrome: Color) = lightColorScheme(
    primary = accentColor,
    secondary = goldColor,
    background = mat,
    surface = mat,
    surfaceVariant = if (night) Color(0xFF1A1A1C) else Color(0xFFEFE6CF),
    onBackground = chrome,
    onSurface = chrome,
    onPrimary = if (night) Color(0xFF101012) else Color(0xFF06231F),
    outline = if (night) Color(0x33FFFFFF) else Color(0x22000000),
    // keep the API-wide behaviour predictable when ink differs from onSurface
    onSurfaceVariant = if (night) Color(0xFF8A8D95) else Color(0xFF5A5A60),
    error = wrongColor,
)

private val Pill = RoundedCornerShape(50)
private val CardRadius = RoundedCornerShape(16.dp)

private fun mushafShapes() = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * A compact progress strip over the ayah of the surah in scope.
 *
 * Each ayah is a cell, filled by the share of its words the engine has heard.
 * This is the memoriser's answer to "how far through am I", which the reader
 * bar - the locked ayah alone - could not give. It is a bar, not a number,
 * because a percentage here would read as a score of the reciter, and it is not
 * one: it is how far the LOCK has come.
 */
@Composable
private fun ScopeProgressStrip(
    ayahs: List<ScopeAyah>,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Canvas(modifier.height(14.dp)) {
        val n = ayahs.size
        if (n == 0) return@Canvas
        val gap = if (n > 120) 0.5f else 1.2f
        val cell = (size.width - gap * (n - 1)) / n
        ayahs.forEachIndexed { i, a ->
            val x = i * (cell + gap)
            val frac = if (a.words > 0) a.heard.toFloat() / a.words else 0f
            if (a.heard > 0) {
                drawRect(
                    cs.onSurface.copy(alpha = 0.12f),
                    androidx.compose.ui.geometry.Offset(x, 0f),
                    androidx.compose.ui.geometry.Size(cell, size.height),
                )
            }
            if (frac > 0f) {
                drawRect(
                    cs.onSurface.copy(alpha = 0.42f),
                    androidx.compose.ui.geometry.Offset(x, size.height * (1f - frac)),
                    androidx.compose.ui.geometry.Size(cell, size.height * frac),
                )
            }
            if (a.isLocked) {
                drawRect(
                    cs.primary,
                    androidx.compose.ui.geometry.Offset(x, -1f),
                    androidx.compose.ui.geometry.Size(cell, 2f),
                )
            }
        }
    }
}

/**
 * In-reader search over the whole mushaf.
 *
 * The magnifier used to open a "go to page" box, which is a different job: a
 * reader looking for a word wants the word, not a number. This lists live
 * matches as they type and jumps to the ayah on tap.
 *
 * Three things it does that a bare list does not:
 *  - it only queries once the query is long enough to be selective (the engine
 *    needs two characters), so a single tap does not dump the mushaf;
 *  - a match shows the ayah text with the matched span marked, so the result is
 *    recognisable without opening it;
 *  - tapping jumps the reader to that ayah, which is the whole point of
 *    searching from inside the mushaf.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSheet(
    query: (String) -> List<com.iqra.quran.data.AyahSearch.Hit>,
    onGoToPage: () -> Unit,
    onDismiss: () -> Unit,
    onPick: (Int, Int) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val results = remember(text) { if (text.trim().length >= 2) query(text.trim()) else emptyList() }
    // The app theme, not the reader's light scheme: this sheet opens over the
    // page but it is app chrome, and a cream panel over a dark theme read as
    // a different app wearing the same name.
    val c = LocalIqraColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceHigh,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Search the mushaf") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            // Page jump lives here now: the magnifier searches for a WORD, and
            // this keeps the other job one tap away instead of leaving a
            // dialog nothing can open.
            TextButton(onClick = onGoToPage, modifier = Modifier.align(Alignment.Start)) {
                Text("Go to page…", fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            if (text.trim().length < 2) {
                Text(
                    "Type at least two letters.",
                    fontSize = 13.sp,
                    color = c.inkMuted,
                )
            } else if (results.isEmpty()) {
                Text(
                    "No ayah matches \u2018$text\u2019.",
                    fontSize = 13.sp,
                    color = c.inkMuted,
                )
            } else {
                Text(
                    "${results.size} ${if (results.size == 1) "ayah" else "ayat"}",
                    fontSize = 12.sp,
                    color = c.inkMuted,
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(results.size) { i ->
                        val hit = results[i]
                        val body = ayahSnippet(hit)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(hit.surah, hit.ayah)
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                "${hit.surah}:${hit.ayah}",
                                fontSize = 11.sp,
                                color = c.accent,
                            )
                            Text(body, fontFamily = quranFont, fontSize = 17.sp, lineHeight = 24.sp, color = c.ink)
                        }
                        HorizontalDivider(
                            color = c.hairline,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The ayah text behind a search hit, for the result row.
 *
 * [QuranData.load] is suspending, so the text is produced on a coroutine and
 * the row renders whatever has arrived. A result row that shows its ayah number
 * for a frame and then its text is the normal shape of this, and it is why the
 * list is driven by the engine's hits rather than by a blocking lookup.
 */
@Composable
private fun ayahSnippet(hit: com.iqra.quran.data.AyahSearch.Hit): String {
    val ctx = LocalContext.current
    val text by produceState("", hit) {
        value = withContext(Dispatchers.IO) {
            runCatching { QuranData.load(ctx).getVerse(hit.surah, hit.ayah)?.textClean ?: "" }
                .getOrDefault("")
        }
    }
    return text
}

@Composable
private fun ExpectedWordsLine(
    words: List<MushafWord>,
    statusMap: Map<String, WordStatus>,
    currentKey: String?,
    playOrder: Map<String, Int>,
    playHead: Int,
    modifier: Modifier = Modifier,
    advisories: Map<String, Advisory> = emptyMap(),
    hide: Boolean,
) {
    // Hide mode: this bar printed the standing ayah's words in full, and it
    // is the one place in the reader where a hidden verse's text was drawn
    // outside the page, so the page's mask could not cover it.
    if (hide) return
    if (words.isEmpty()) return
    // Mushaf text the app draws itself scales with the user's text-size setting.
    // The page image does not and cannot: it is the printed bitmap, and scaling
    // it would resample the glyphs. So this bar and the fallback line renderer
    // are the two places the setting applies, and they must agree.
    val scale = ReaderPrefs.fontScale(LocalContext.current)
    val b = AnnotatedString.Builder()
    words.forEachIndexed { i, w ->
        val key = "${w.surah}:${w.verse}:${w.wordInVerse}"
        val st = statusMap[key]
        val gi = playOrder[key]
        val isPlayed = gi != null && gi <= playHead
        val isHead = gi != null && gi == playHead
        val isCur = key == currentKey
        val hasNote = advisories[key] != null &&
            ReaderPrefs.advisoryDisplay(LocalContext.current) == ReaderPrefs.AdvisoryDisplay.IN_FLOW
        val fg = when {
            st == null && hasNote -> amberColor
            st == null -> Chrome.OnChrome
            st == WordStatus.WRONG -> wrongColor
            isCur -> accentColor
            isPlayed || isHead -> goldColor
            st == WordStatus.UNKNOWN && hasNote -> amberColor
            st == WordStatus.SKIPPED && hasNote -> amberColor
            st == WordStatus.SKIPPED -> Chrome.OnChrome.copy(alpha = 0.45f)
            else -> Chrome.OnChrome
        }
        b.pushStyle(
            SpanStyle(
                color = fg,
                background = when {
                    isCur -> accentColor.copy(alpha = 0.30f)
                    st == WordStatus.WRONG -> wrongColor.copy(alpha = 0.25f)
                    hasNote -> amberColor.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
                fontWeight = if (isCur || isHead) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
        b.append(w.text)
        b.pop()
        if (i < words.lastIndex) b.append(" ")
    }
    Text(
        b.toAnnotatedString(),
        fontFamily = quranFont,
        fontSize = (15 * scale).sp,
        maxLines = 2,
        modifier = modifier,
    )
}

@Composable
private fun PulseDot(color: Color = wrongColor, label: String? = null) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "a",
    )
    Box(
        Modifier
            .size(10.dp)
            .background(color.copy(alpha = a), CircleShape)
            .then(
                if (label != null) {
                    Modifier.semantics { contentDescription = label }
                } else {
                    Modifier
                },
            ),
    )
}

@Composable
fun SplashScreen() {
    val t = rememberInfiniteTransition(label = "splash")
    val a by t.animateFloat(
        0.3f, 1f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "splash-a",
    )
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Iqra",
                color = goldColor,
                fontFamily = quranFont,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "ٱقْرَأْ",
                color = goldColor.copy(alpha = a),
                fontFamily = quranFont,
                fontSize = 26.sp,
            )
            Spacer(Modifier.height(20.dp))
            LinearProgressIndicator(
                modifier = Modifier.width(120.dp),
                color = accentColor,
                trackColor = accentColor.copy(alpha = 0.2f),
            )
        }
    }
}

@Composable
fun App(vm: PracticeViewModel, onRequestMic: (() -> Unit) -> Unit) {
    val loading by vm.loading.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val mushaf by vm.mushaf.collectAsStateWithLifecycle()
    val lastRead by vm.lastRead.collectAsStateWithLifecycle()
    // rememberSaveable, not remember: `screen` was plain remember, so rotating
    // the phone mid-recitation threw the user back to the surah list. That is
    // the single most common way a reader is interrupted, and Android does it
    // on every rotation and on every keyboard open.
    var screenName by rememberSaveable { mutableStateOf("picker") }
    var screenSurah by rememberSaveable { mutableStateOf(0) }
    var screenPage by rememberSaveable { mutableStateOf(-1) }
    var screenAnchor by rememberSaveable { mutableStateOf(-1) }
    var screenCount by rememberSaveable { mutableStateOf(7) }
    // Session record file name for the single-session report route.
    var screenReport by rememberSaveable { mutableStateOf("") }
    // Where the report was opened from, so its back button returns there. It
    // used to hard-code "progress": finishing a recitation (the report opens
    // straight from Live) and pressing back dumped the reciter into the
    // history list, two contexts away from where they stood.
    var reportFrom by rememberSaveable { mutableStateOf("progress") }
    // Captured once, because the screen-dispatch lambdas below are plain lambdas
    // and cannot read a composition local.
    val appCtx = LocalContext.current

    if (loading || data == null) {
        SplashScreen()
        return
    }
    // Rebuilt from the four saveable fields rather than being itself saved: a
    // sealed object is not a type rememberSaveable can put in a Bundle without
    // a custom Saver, and four primitives are both cheaper and restore exactly.
    val screen: Screen = when (screenName) {
        "data" -> Screen.Data
        "settings" -> Screen.Settings
        "progress" -> Screen.Progress
        "diag" -> Screen.Diag
        "reader" -> Screen.Reader(
            screenSurah,
            screenPage.takeIf { it > 0 },
            screenAnchor.takeIf { it > 0 },
        )
        "live" -> Screen.Live(screenSurah, screenPage.coerceAtLeast(1), screenCount)
        "report" -> Screen.Report(screenReport)
        "observer" -> Screen.Observer(screenReport)
        "choose" -> Screen.Choose
        "library" -> Screen.Library
        else -> Screen.Picker
    }
    val goReader: (Int, Int?, Int?) -> Unit = { s, p, a ->
        screenSurah = s; screenPage = p ?: -1; screenAnchor = a ?: -1
        screenName = "reader"
    }

    // System back mirrors the screen's own back affordance instead of leaving
    // the app from a child screen. Leaving the reader or the live screen while
    // the mic is up stops the session first: a back press must never leave a
    // hot microphone behind a screen the user can no longer see.
    BackHandler(enabled = screenName != "picker") {
        when (screenName) {
            "reader" -> {
                if (vm.recording.value) vm.stopRecite("back out of reader")
                screenName = "picker"
            }
            "live" -> {
                if (vm.recording.value) vm.stopRecite("live exit")
                screenName = "picker"
            }
            "report" -> screenName = reportFrom
            "observer" -> screenName = "report"
            "library" -> screenName = "settings"
            else -> screenName = "picker"
        }
    }

    when (screen) {
        Screen.Data -> DataSetupScreen { screenName = "picker" }
        Screen.Settings -> SettingsScreen(
            onBack = { screenName = "picker" },
            onOpenData = { screenName = "data" },
            onOpenProgress = { screenName = "progress" },
            onOpenLibrary = { screenName = "library" },
            onOpenDiag = { screenName = "diag" },
        )
        Screen.Library -> AudioLibraryScreen(
            onBack = { screenName = "settings" },
            onPlay = { surah, reciterId ->
                // The row's file and the row's reciter are one choice: seat
                // the reciter first so playback resolves to the file that was
                // tapped, then open its mushaf page with the transport up.
                AudioPrefs.setReciter(appCtx, reciterId)
                AudioPrefs.setLastPlayedSurah(appCtx, surah)
                goReader(surah, null, null)
                vm.togglePlaySurah(surah)
            },
        )
        // The practice surface. Replaces the old ProgressScreen, which reported
        // lock advances and audio polls as though they were a score.
        is Screen.Progress -> data?.let { d ->
            PracticeScreen(
                data = d,
                onOpen = { s, p -> goReader(s, p, null) },
                onOpenSession = { name -> screenReport = name; reportFrom = "progress"; screenName = "report" },
            ) { reportFrom = "progress"; screenName = "picker" }
        }
        is Screen.Choose -> data?.let { d ->
            ChooseScreen(
                data = d,
                lastRead = lastRead,
                onBegin = { s, a, n ->
                    screenSurah = s; screenPage = a; screenCount = n
                    screenName = "live"
                },
                onBack = { screenName = "picker" },
            )
        }
        is Screen.Report -> data?.let { d ->
            SessionReportScreen(
                d, screenReport,
                onOpenObserver = { screenName = "observer" },
            ) { screenName = reportFrom }
        }
        // The observer reads the same record file as the report, back into it.
        is Screen.Observer -> data?.let { d ->
            SessionObserverScreen(d, screenReport) { screenName = "report" }
        }
        Screen.Picker -> HomeScreen(vm, lastRead,
            onOpen = { surah, page -> goReader(surah, page, null) },
            onOpenAyah = { surah, page, ayah -> goReader(surah, page, ayah) },
            onSettings = { screenName = "settings" },
            onProgress = { screenName = "progress" },
            onRecite = { s, p, a -> goReader(s, p, a) },
            onListen = { s, p -> goReader(s, p, null) },
            // Opens the door, not the session. Choosing what to train on is a
            // question this card should not answer on the reader's behalf.
            onTest = { _, _ -> screenName = "choose" },
            onResume = {
                lastRead?.let { (s, p) ->
                    vm.resumeLastRead()
                    goReader(s, p, null)
                }
            },
        )
        is Screen.Live -> data?.let { d ->
            LiveModeScreen(
                vm = vm,
                data = d,
                surah = screen.surah,
                startAyah = screen.startAyah,
                ayahCount = if (screen.count <= 0) {
                    (d.surahInfo(screen.surah)?.ayahCount ?: 7) - screen.startAyah + 1
                } else screen.count,
                // onRequestMic takes the block to run once permission is
                // granted, so asking for the mic and starting on that surah are
                // one gesture - which is what a tap on the orb means.
                onRequestMic = {
                    val pg = d.surahInfo(screen.surah)?.startPage ?: 1
                    onRequestMic { vm.startRecite(pg, screen.surah to screen.startAyah) }
                },
                onExit = { screenName = "picker" },
                // Finishing a session goes straight to its report. `stopRecite`
                // flushes the record synchronously before tearing the session down,
                // so the file is on disk by the time this runs. Landing on a list
                // of 114 surahs instead is how a report screen goes unused.
                onFinished = {
                    val name = PracticeLog.latestRecordName(appCtx)
                    if (name != null) {
                        // Opened from a session that just ended, so back goes
                        // home - returning "live" would re-enter a dead screen.
                        screenReport = name; reportFrom = "picker"; screenName = "report"
                    }
                    else screenName = "progress"
                },
            )
        }
        is Screen.Diag -> DiagScreen(vm) { screenName = "picker" }
        is Screen.Reader -> {
            val pages = mushaf
            if (pages == null) {
                SplashScreen()
            } else {
                ReaderScreen(
                    vm = vm,
                    surah = screenSurah,
                    startPage = screenPage.takeIf { it > 0 },
                    initialAnchorAyah = screenAnchor.takeIf { it > 0 },
                    // Leaving the reader ends a running session - the same
                    // rule system back follows below. The recite bar's Stop
                    // is still the way to finish deliberately (its record and
                    // report follow), but no exit may leave the mic hot.
                    onBack = {
                        if (vm.recording.value) vm.stopRecite("back out of reader")
                        screenName = "picker"
                    },
                    onRequestMic = onRequestMic,
                )
            }
        }
    }
}

sealed interface Screen {
    data object Picker : Screen
    data class Reader(val surah: Int, val page: Int? = null, val anchorAyah: Int? = null) : Screen
    data object Diag : Screen
    data object Data : Screen
    data object Settings : Screen
    /** The offline reference-audio library, reached from Settings. */
    data object Library : Screen
    data object Progress : Screen
    /** One session in full, by record file name. */
    data class Report(val name: String) : Screen
    /** The same record, replayed on a timeline instead of reported. */
    data class Observer(val name: String) : Screen
    /** Standalone door for the recitation test. Asks what to train on. */
    data object Choose : Screen
    /** Recitation test from memory. Count 0 means "to the end of the surah". */
    data class Live(val surah: Int, val startAyah: Int, val count: Int) : Screen
}

enum class HomeTab { Surahs, Juz, Bookmarks }
enum class SurahView { List, Grid }

@Composable
fun DiagScreen(vm: PracticeViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val engineLabel by vm.engineLabel.collectAsStateWithLifecycle()
    val lastMatch by vm.lastMatch.collectAsStateWithLifecycle()
    val wpm by vm.wpmFlow.collectAsStateWithLifecycle()
    val gate by vm.gateReason.collectAsStateWithLifecycle()
    val decoder by vm.decoderState.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val activeVerse by vm.activeVerse.collectAsStateWithLifecycle()
    // The live ring is the common case, but it is empty after process death -
    // which is exactly when the persisted log is the only evidence there is.
    // derivedStateOf reads from disk only while the live ring stays empty, and
    // caches the moment this process emits anything, so it does not hit the
    // filesystem on every recomposition.
    val log by remember {
        derivedStateOf { vm.diagLog.value.ifEmpty { vm.persistedDiag() } }
    }
    val live by vm.policyLive.collectAsStateWithLifecycle()
    val needs = vm.policyNeeds()
    var snap by remember { mutableStateOf(vm.engineFilesInfo()) }
    var micDb by remember { mutableStateOf(0f) }
    var micN by remember { mutableStateOf(0) }
    var micStalled by remember { mutableStateOf(false) }
    var streamInfo by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(recording) {
        var last = -1
        while (true) {
            micDb = vm.micLevel()
            val n = vm.micSampleCount()
            micStalled = recording && n == last && n > 0
            last = n
            micN = n
            streamInfo = vm.streamStats()
            delay(300)
        }
    }
    fun hint(): String? {
        if (snap.any { !it.present }) return ctx.getString(R.string.reader_hint_missing_files)
        if (recording && micN > 0 && micDb < 0.005f) return ctx.getString(R.string.reader_hint_silence)
        if (micStalled) return ctx.getString(R.string.reader_hint_frozen)
        // The gate is fed by SherpaVad.feedAndDetect() (or the RMS fallback)
        // and its per-frame verdict is folded straight into gateReason, never
        // published, so name the gate that is actually running instead of
        // pointing at a VAD row that does not exist.
        if (recording && gate == "silence") {
            val which = if (engineLabel.endsWith("/VAD")) "Silero VAD" else "RMS"
            return ctx.getString(R.string.reader_hint_gate_silence, which)
        }
        if (recording && lastMatch == null) return ctx.getString(R.string.reader_hint_no_match)
        return null
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(stringResource(R.string.reader_diag_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { snap = vm.engineFilesInfo() }) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.reader_diag_refresh), tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) {
                Icon(Icons.Filled.ContentCopy, stringResource(R.string.reader_diag_copy_log))
            }
            // "Copy log" hands over the event lines alone, which on their own are
            // close to unreadable: they say the lock moved and not what it was
            // looking at when it decided not to. This builds the whole picture -
            // engine, files, mic, matcher, thresholds and the ring buffer - into
            // one block, and shares it rather than only pasting it, because the
            // person who can read it is rarely the person holding the phone.
            IconButton(onClick = {
                val text = buildEngineReport(ctx, vm, snap, micDb, micN, micStalled, log, streamInfo)
                clipboard.setText(AnnotatedString(text))
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.reader_diag_report_subject))
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.reader_diag_share_via)))
            }) {
                Icon(Icons.Filled.Share, stringResource(R.string.reader_diag_share_report))
            }
            // The frame ring is what a lock-policy question is actually answered
            // from: per frame it records the lock plus every coverage the
            // decision was made on, including the ones that were rejected. Its
            // header carries the thresholds, so a dump never has to hard-code
            // the policy it was recorded under.
            IconButton(onClick = { clipboard.setText(AnnotatedString(vm.frameRingDump())) }) {
                Icon(Icons.Filled.Receipt, stringResource(R.string.reader_diag_copy_ring))
            }
            // Without this the rotated logs accumulate for the life of the
            // install and "the log" stops meaning one debugging session.
            IconButton(onClick = { vm.clearPersistedDiag() }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.reader_diag_clear_log))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                DiagSection(
                    if (engineLabel.isNotEmpty()) ctx.getString(R.string.reader_diag_engine, engineLabel)
                    else ctx.getString(R.string.reader_diag_engine_idle)
                ) {
                    snap.forEach { f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(10.dp).background(
                                    if (f.present) accentColor else wrongColor,
                                    CircleShape,
                                ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                Text(
                                    f.detail + (f.fix?.let { " — $it" } ?: ""),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
            item {
                DiagSection(ctx.getString(R.string.reader_diag_mic, "%.3f".format(micDb), micN)) {
                    DiagRow("Recording", if (recording) "yes" else "no")
                    DiagRow("Stalled", if (micStalled) "YES — restart recitation" else "no")
                    // engineLabel is the only endpointing state the app
                    // publishes: PracticeViewModel sets it once per session to
                    // "zipformer/VAD" when SherpaVad.ensure() succeeded and
                    // "zipformer/RMS" when it fell back. A per-frame VAD
                    // readout would need feedAndDetect()'s Boolean? exposed as
                    // its own StateFlow (it is consumed inline at
                    // PracticeViewModel.kt:996 and discarded), and that file is
                    // owned elsewhere.
                    // The per-frame VAD verdict, published from the gate itself
                    // rather than a session-level label. The old comment here
                    // said the verdict "is consumed inline and discarded" and
                    // that the file owning it was unavailable; it is not any
                    // more, so this is a real readout rather than a stopgap.
                    DiagRow(
                        "Endpointing",
                        when (live.vadSpeech) {
                            null -> engineLabel.ifEmpty { "—" }
                            true -> "VAD: speech"
                            false -> "VAD: silence"
                        },
                    )
                }
            }
            item {
                DiagSection(stringResource(R.string.reader_diag_matcher)) {
                    DiagRow("Lock", activeVerse?.toString() ?: "—")
                    DiagRow("Last match", lastMatch?.let { "${it.first} @ ${"%.2f".format(it.second)}" } ?: "—")
                    if (live.next >= 0f) {
                        DiagRow("Next (need ${"%.2f".format(needs.first)})", "%.2f".format(live.next))
                        DiagRow("Here (need < ${"%.2f".format(needs.second)})", "%.2f".format(live.here))
                        DiagRow("Back (need ${"%.2f".format(needs.third)})", "%.2f".format(live.back))
                        if (live.held.isNotEmpty()) DiagRow("Holding", live.held)
                    }
                    if (live.gateClosed > 0) {
                        DiagRow("Gate closed", "${live.gateClosed} frames, none reached the decoder")
                    }
                    if (live.stallNote.isNotEmpty()) {
                        DiagRow("Stalled", live.stallNote)
                    }
                    DiagRow("WPM", "%.0f".format(wpm))
                    DiagRow("Gate", gate.ifEmpty { "—" })
                    DiagRow("Decoder", decoder.ifEmpty { "—" })
                    DiagRow("Stream", streamInfo.ifEmpty { "—" })
                    hint()?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, fontSize = 13.sp, color = goldColor)
                    }
                }
            }
            item {
                DiagSection(stringResource(R.string.reader_diag_lock_policy)) {
                    // The gates, spelled out, because "the lock did not move" is
                    // the most common report and it has a small number of causes
                    // that look identical from the outside. Each of these is a
                    // distinct reason the lock is sitting still right now.
                    DiagRow("Advance", "next ayah coverage >= ${"%.2f".format(needs.first)}")
                    DiagRow("Back", "previous ayah coverage >= ${"%.2f".format(needs.third)}")
                    DiagRow("Frames held", live.held.ifEmpty { "—" })
                    DiagRow("Stall", if (live.stallNote.isEmpty()) "none" else live.stallNote)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.reader_diag_four_states, "%.2f".format(needs.first)),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The full gate set lives in PracticeViewModel.kt's companion " +
                            "object; the three above are the ones the app publishes. " +
                            "engine/replay/lock_trace.py replays the same policy over " +
                            "recorded audio and is where thresholds are changed.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
            item {
                DiagSection(stringResource(R.string.reader_diag_events, log.size)) {
                    if (log.isEmpty()) {
                        Text(
                            stringResource(R.string.reader_diag_no_events),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    } else {
                        log.takeLast(60).reversed().forEach {
                            Text(
                                it,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagSection(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = CardRadius,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = goldColor)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/**
 * The whole engine state as one plain-text block.
 *
 * This exists because the pieces were already on screen but not obtainable
 * together. The event log records what the lock did; the header records which
 * files loaded; the matcher rows record the coverages it decided on. A report
 * containing only the log is a list of moves with no way to tell a wrong gate
 * from a wrong threshold from a model that never heard the ayah - which are
 * three different bugs with three different fixes, and the log alone reads
 * identically for all three.
 *
 * Plain text and no JSON on purpose: this gets pasted into an issue tracker by
 * a person, and a person should be able to read it.
 */
private fun buildEngineReport(
    ctx: android.content.Context,
    vm: PracticeViewModel,
    files: List<PracticeViewModel.EngineFileInfo>,
    micDb: Float,
    micSamples: Int,
    micStalled: Boolean,
    log: List<String>,
    streamInfo: String,
): String {
    val live = vm.policyLive.value
    val needs = vm.policyNeeds()
    val b = StringBuilder()

    fun section(t: String) {
        b.append("\n== ").append(t).append(" ==\n")
    }

    b.append("Iqra engine report\n")
    b.append("generated: ").append(
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US)
            .format(java.util.Date())
    ).append('\n')

    section("Engine")
    b.append("label      : ").append(vm.engineLabel.value.ifEmpty { "idle" }).append('\n')
    b.append("gate       : ").append(vm.gateReason.value.ifEmpty { "-" }).append('\n')
    b.append("decoder    : ").append(vm.decoderState.value.ifEmpty { "-" }).append('\n')
    b.append("recording  : ").append(vm.recording.value).append('\n')
    b.append("stream     : ").append(streamInfo.ifEmpty { "-" }).append('\n')

    section("Files")
    files.forEach {
        b.append("  ").append(it.toString()).append('\n')
    }

    section("Mic")
    b.append("level      : ").append("%.5f".format(micDb)).append('\n')
    b.append("samples    : ").append(micSamples).append('\n')
    b.append("stalled    : ").append(micStalled).append('\n')

    section("Matcher")
    b.append("lock       : ").append(vm.activeVerse.value ?: "-").append('\n')
    b.append("last match : ").append(
        vm.lastMatch.value?.let { "${it.first} @ ${"%.3f".format(it.second)}" } ?: "-"
    ).append('\n')
    b.append("wpm        : ").append("%.0f".format(vm.wpmFlow.value)).append('\n')

    section("Policy thresholds")
    b.append("advance    : ").append("%.2f".format(needs.first)).append('\n')
    b.append("back       : ").append("%.2f".format(needs.third)).append('\n')
    b.append("current next coverage : ").append("%.3f".format(live.next)).append('\n')
    b.append("current here coverage : ").append("%.3f".format(live.here)).append('\n')
    b.append("frames held           : ").append(live.held.ifEmpty { "-" }).append('\n')
    b.append("stall note            : ").append(live.stallNote.ifEmpty { "-" }).append('\n')
    b.append(
        "note: the remaining gates (STRONG/WEAK/STUCK/JUMP/HANDOFF and the " +
            "pinned-lock escape) are compile-time constants in PracticeViewModel.kt " +
            "and are not published as state, so they cannot be read from here.\n",
    )

    section("Practice summary")
    val log1 = runCatching { PracticeLog.summarise(ctx).let {
        "sessions=${it.sessions} words=${it.judgedWords} " +
            "accuracy=${it.accuracy?.let { a -> "%.1f%%".format(a * 100) } ?: "n/a"} " +
            "streak=${it.currentStreak} records=${it.recordsIngested} " +
            "hardest=${it.hardest.size}"
    } }.getOrDefault("unavailable")
    b.append(log1).append('\n')

    section("Frame ring (last 400 frames)")
    b.append(runCatching { vm.frameRingDump() }.getOrDefault("unavailable")).append('\n')

    section("Events (last ${minOf(log.size, 120)})")
    log.takeLast(120).forEach { b.append(it).append('\n') }

    return b.toString()
}

@Composable
private fun DiagRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            k,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(110.dp),
        )
        Text(fontSize = 13.sp, text = v)
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

/**
 * Verdicts the reciter has actually earned, kept by the READER.
 *
 * `PracticeViewModel.statusMap` is the only per-word paint source the UI is
 * handed, and it is rebuilt every poll out of a window of lockedAyah +/- 2: the
 * retained verdicts are expired outside it (PracticeViewModel.kt:2844) and the
 * whole map is emptied on a surah load, a jump, an anchor and a new recitation.
 * So a word coloured and then went blank again two ayat later, which is what
 * "the colours just stay shortly" describes - while `resolveWordStyle`'s
 * hide-mode comment claimed the verdict "is now carried permanently in the
 * paint map for the whole session", which that pruning denies. `scopeProgress`
 * is the archive-backed, never-pruned half of the truth and is ayah-granular;
 * this is the per-word half, built from the one per-word feed the reader is
 * given.
 *
 * Contract copied from `archiveVerdict()` and from the `sessionStatuses`
 * retention: the FIRST terminal verdict wins and nothing overwrites it. A
 * later UNKNOWN or SKIPPED means the evidence window has moved on, not that the
 * verdict was withdrawn, so it must not be able to take one back.
 */
private class EarnedVerdicts {
    private val byWord = LinkedHashMap<String, WordStatus>()

    /**
     * Bumped only when something actually changes, because the page draw is
     * remembered on it - `byWord` is one instance for the whole session and
     * would compare equal to itself as a `remember` key.
     */
    var version by mutableIntStateOf(0)
        private set

    /**
     * The furthest word judged so far, as (surah, ayah, wordInVerse) in reading
     * order. Tracked as a running maximum while folding rather than scanned on
     * demand: the scan would be over the whole session's map, and it would call
     * `standWordsFor` per entry, on a key that changes several times a second.
     *
     * A back-move cannot lower it - a repetition of earlier words yields
     * verdicts behind the furthest point reached - which is what stops a repeat
     * from dragging the page backwards. Reading order, not page order: a handoff
     * takes the session into the next surah, and the next surah is further on.
     */
    var judgedPosition: Triple<Int, Int, Int>? = null
        private set

    /**
     * The verdicts themselves. ONE instance for the whole session, so a caller
     * cannot tell it changed by comparing it - that is what [version] is for.
     */
    val words: Map<String, WordStatus> get() = byWord

    fun fold(live: Map<String, WordStatus>) {
        var changed = false
        for ((k, v) in live) {
            if (v != WordStatus.CORRECT && v != WordStatus.WRONG) continue
            val prev = byWord[k]
            if (prev == WordStatus.CORRECT || prev == WordStatus.WRONG) continue
            byWord[k] = v
            val p = k.split(':')
            if (p.size == 3) {
                val s = p[0].toIntOrNull()
                val a = p[1].toIntOrNull()
                val wi = p[2].toIntOrNull()
                if (s != null && a != null && wi != null) {
                    val cur = judgedPosition
                    // Triple is not Comparable, so the order is spelled out.
                    if (cur == null || s > cur.first ||
                        (s == cur.first && a > cur.second) ||
                        (s == cur.first && a == cur.second && wi > cur.third)
                    ) {
                        judgedPosition = Triple(s, a, wi)
                    }
                }
            }
            changed = true
        }
        if (changed) version++
    }

    /**
     * Forget everything, driven by the engine clearing its own retained
     * verdicts - a new surah, a jump, an anchor, a new recitation, which is
     * what the session epoch is bumped for. Teardown is deliberately NOT one
     * of those: it clears the paint map only, so the verdicts the reciter
     * earned stay on the page until the next session starts.
     */
    fun clear() {
        if (byWord.isEmpty()) return
        byWord.clear()
        judgedPosition = null
        version++
    }
}

/** Whether the session archive holds a verdict for any word of this ayah. */
private fun ScopeAyah?.judged(): Boolean = (this?.decided ?: 0) > 0

/**
 * Whether [word] is exactly the word a verdict key named.
 *
 * `standWordsFor(ayah)` answers for the session's ACTIVE surah, so after a
 * handoff it returns a different surah's words for the same ayah number. The
 * ayah and the word index alone would then match a word on some unrelated page
 * and turn the reader there, so all three parts of the key are compared.
 */
private fun isSameWord(word: MushafWord, surah: Int, ayah: Int, wordInVerse: Int): Boolean =
    word.surah == surah && word.verse == ayah && word.wordInVerse == wordInVerse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: PracticeViewModel,
    surah: Int,
    startPage: Int? = null,
    initialAnchorAyah: Int? = null,
    onBack: () -> Unit,
    onRequestMic: (() -> Unit) -> Unit,
) {
    val mushaf = vm.mushaf.collectAsStateWithLifecycle().value ?: return
    val data = vm.data.collectAsStateWithLifecycle().value
    val hide by vm.hideVerse.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    var showPlaySheet by remember { mutableStateOf(false) }
    val statusMap by vm.statusMap.collectAsStateWithLifecycle()
    val sessionEpoch by vm.sessionEpoch.collectAsStateWithLifecycle()
    val advisoryMap by vm.advisoryMap.collectAsStateWithLifecycle()
    val currentKey by vm.currentKey.collectAsStateWithLifecycle()
    val currentPage by vm.currentPage.collectAsStateWithLifecycle()
    val playingSurah by vm.playingSurah.collectAsStateWithLifecycle()
    val playLoading by vm.playLoading.collectAsStateWithLifecycle()
    val playError by vm.playError.collectAsStateWithLifecycle()
    val seekNote by vm.seekNote.collectAsStateWithLifecycle()
    val playPaused by vm.playPaused.collectAsStateWithLifecycle()
    val playPosMs by vm.playPosMs.collectAsStateWithLifecycle()
    val playDurMs by vm.playDurMs.collectAsStateWithLifecycle()
    val cacheProgress by vm.cacheProgress.collectAsStateWithLifecycle()
    val cacheNote by vm.cacheNote.collectAsStateWithLifecycle()
    val engineLabel by vm.engineLabel.collectAsStateWithLifecycle()
    val sessionSound by vm.sessionSound.collectAsStateWithLifecycle()
    val preparing by vm.preparing.collectAsStateWithLifecycle()
    val activeVerse by vm.activeVerse.collectAsStateWithLifecycle()
    val activeWindow by vm.activeWindow.collectAsStateWithLifecycle()
    val scopeSurah by vm.scopeSurah.collectAsStateWithLifecycle()
    val scopeProgress by vm.scopeProgress.collectAsStateWithLifecycle()
    val selectedAyah by vm.selectedAyah.collectAsStateWithLifecycle()
    val selectedRange by vm.selectedRange.collectAsStateWithLifecycle()
    val repeatKey by vm.repeatAyahKey.collectAsStateWithLifecycle()
    val repeatLeft by vm.repeatLeft.collectAsStateWithLifecycle()
    val playIndex by vm.playIndex.collectAsStateWithLifecycle()
    val playHead by vm.playHead.collectAsStateWithLifecycle()
    val engineHint by vm.engineHint.collectAsStateWithLifecycle()
    val wpm by vm.wpmFlow.collectAsStateWithLifecycle()
    // Words of the active ayah, taken from the ViewModel's per-surah map rather
    // than rebuilt here. The old version was
    // `remember(activeVerse, mushaf) { mushaf.flatMap { all 604 pages }.filter { it.surah == surah } }`:
    // `surah` was read in the body but missing from the keys, so travelling
    // between surahs on the same ayah number left the control bar showing the
    // PREVIOUS surah while the colouring tracked the new one correctly. It also
    // flattened every word on every page and recomputed it on every lock move.
    // keysVersion changes whenever the ViewModel rebuilds its per-surah word map,
    // so this cannot outlive a surah change.
    val wordsVersion by vm.wordsVersion.collectAsStateWithLifecycle()
    val standWords = remember(activeVerse, wordsVersion) {
        val av = activeVerse ?: return@remember emptyList<MushafWord>()
        vm.standWordsFor(av)
    }
    // The session's decided words, held here because the engine's paint map
    // forgets them: it is pruned to the lock +/- 2 every poll. Fed on every
    // emission. Emptying is keyed on the engine's session epoch, which is
    // bumped exactly where the engine clears its own retained verdicts - a
    // new surah, a jump, an anchor, a new recitation. It is deliberately NOT
    // keyed on an empty paint map any more: the engine also empties the PAINT
    // on teardown (stopRecite) without touching the verdicts, so keying on
    // that wiped the colours the moment the reciter stopped - "they just
    // stay shortly".
    val earned = remember { EarnedVerdicts() }
    LaunchedEffect(sessionEpoch) { earned.clear() }
    LaunchedEffect(statusMap) { if (statusMap.isNotEmpty()) earned.fold(statusMap) }
    // scopeProgress indexed by ayah, for the paint. It is built from the DURABLE
    // session archive and never pruned, so unlike the per-word map it still
    // knows after a rotation that an ayah three ayat back was judged.
    val judgedByAyah = remember(scopeProgress) { scopeProgress.associateBy { it.ayah } }
    val bookmarkPages by vm.bookmarks.collectAsStateWithLifecycle()
    var showGoto by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    // The ayah the play sheet opens at: 1 from the header's play affordance,
    // the selected ayah from the sheet's "Listen from this ayah". Every play
    // passes through the sheet so the reciter is a choice, not an assumption.
    var playSheetAyah by remember { mutableIntStateOf(1) }

    // The reader records in place: the Recite button and the ayah sheet's
    // "Practice from here" both start a session without leaving the mushaf, so
    // the wrong-verdict buzz is composed here too. It is the same session as
    // one on the live screen; a buzz that fires on one surface and stays
    // silent on the other makes the toggle a lies display on the other.
    WrongVerdictBuzz(vm)

    val active = recording || statusMap.isNotEmpty()
    val startIdx = remember(surah, startPage) { (startPage ?: Mushaf_firstPage(mushaf, surah)) - 1 }
    val pagerState = rememberPagerState(initialPage = startIdx, pageCount = { mushaf.size })
    // The page actually on screen.
    //
    // `currentPage` is what the ENGINE publishes and it follows the lock, so it
    // no longer describes the page the reader is looking at whenever the reciter
    // has read past an ayah boundary the lock has not reached - which is exactly
    // the case the follow below exists for. The poll rewrites it at poll rate,
    // so reading the header off it would make the page number flicker between
    // two pages while the reader sits on one. The pager's settled page is the
    // truth about what is in front of the reader, so everything the reader is
    // told uses it.
    val shownPage = pagerState.currentPage + 1
    // The header must describe the page currently on screen. It used to be
    // remember(data, surah) - frozen at the surah you opened - so the title and
    // page indicator stayed on the original surah after swiping into another.
    val headerSurah = data?.surahAtPage(shownPage)?.number ?: surah
    val surahInfo = remember(data, headerSurah) {
        data?.surahList()?.firstOrNull { it.number == headerSurah }
    }
    // Whether the CURRENT settled page came from a drag or from a programmatic
    // scroll. The intent handoff must only fire for the former: a swipe is the
    // reciter choosing, while animateScrollToPage/scrollToPage are the app
    // moving the reader (lock follow, search deep link, handoff latch). Treating
    // those as a swipe meant the app could arm its own surah change.
    var userTurned by remember { mutableStateOf(false) }

    LaunchedEffect(startPage) {
        if (startPage != null) vm.jumpToPage(startPage)
    }
    // ---- which page the reader should be on -----------------------------
    //
    // `currentPage` is the page of the LOCKED ayah's FIRST word. Two ways that
    // leaves the reader behind the reciter, both reported on the phone:
    //
    //  - an ayah that spans a page break: `versePage` names the page the ayah
    //    BEGINS on (PracticeViewModel.kt:1550), so for the rest of that ayah
    //    the words being recited are printed on a page nobody is looking at;
    //  - the lock only leaves an ayah once the NEXT one is ADVANCE_COVERAGE
    //    (0.60) covered, so across a page break the reader is held on the old
    //    page for the whole opening ayah of the new one. The page-turn fight
    //    the user described is this: the app turns when the lock reaches a
    //    coordinate, and the reciter is already on the next one.
    //
    // So the target is the page carrying the last word that has EARNED a
    // verdict - the reciter's actual position, not the lock's.
    val judgedPos = earned.judgedPosition
    val judgedPage = remember(earned.version, judgedPos, wordsVersion) {
        val p = judgedPos ?: return@remember null
        // standWordsFor is the ACTIVE surah's word list, so a word key from a
        // surah the session has left names no page here and this is null - the
        // lock's own page is used instead. See isSameWord.
        vm.standWordsFor(p.second).firstOrNull { isSameWord(it, p.first, p.second, p.third) }?.page
    }
    // Archive-backed fallback for the case `earned` cannot cover: a reader
    // rebuilt mid-recitation (rotation, or leaving and re-entering) starts with
    // an empty per-word record while `scopeProgress` still knows which ayat hold
    // verdicts. Ayah-granular, so it names the page of the last RECITED ayah
    // rather than of its last word - never further on than judgedPage is.
    val recitedPage = remember(scopeProgress, wordsVersion) {
        val last = scopeProgress.lastOrNull { it.judged() } ?: return@remember null
        vm.standWordsFor(last.ayah).firstOrNull { it.wordInVerse == 1 }?.page
    }
    // The lock's page still leads, so everything that publishes `currentPage` -
    // a jump, a double-tap anchor, the surah-end latch and the handoff - keeps
    // working exactly as before; this only adds a turn the lock's coordinate
    // cannot produce on its own.
    //
    // While RECITING there is a floor at the page the reader is actually on,
    // and that is the guard against the page fighting both sides of this: the
    // poll rewrites `currentPage` back to the lock's page at poll rate, so
    // taking it literally dragged the reader off a page they had swiped to,
    // and a lock retreat over a repetition dragged them off a page they were
    // reading. Neither can be undone from under them any more.
    //
    // NOT while reading: a search hit, a jump and an anchor are explicit
    // navigation and must be able to take the reader anywhere, backwards
    // included - that is the opposite case, and flooring it would strand a
    // reader who picked a word from the far end of the mushaf.
    val followPage = remember(currentPage, judgedPage, recitedPage, recording, shownPage) {
        maxOf(
            if (recording) shownPage else 0,
            currentPage ?: 0,
            if (recording) judgedPage ?: 0 else 0,
            if (recording) recitedPage ?: 0 else 0,
        ).takeIf { it in 1..mushaf.size }
    }
    LaunchedEffect(followPage, pagerState.isScrollInProgress) {
        val target = followPage ?: (currentPage ?: (startIdx + 1))
        val targetIdx = target - 1
        // Never turn out from under a drag. The pager reports a settled page
        // only between gestures, so an in-progress scroll means the reader's
        // finger owns this page; the effect re-runs when the drag settles.
        if (!pagerState.isScrollInProgress && targetIdx != pagerState.currentPage) {
            pagerState.animateScrollToPage(targetIdx)
        }
        // Saved as the page actually followed, not the lock's page, so "resume"
        // reopens the page the reader was left on.
        vm.saveLastRead(target)
    }
    // USER-driven page turn only. This effect also fires for programmatic
    // scrolls (scrollToPage from the lock, a search deep link, the handoff
    // latch), and treating those as a swipe would arm the intent handoff from
    // the app's own navigation - which is how "resume hinders the next surah"
    // and a surah changing without the reciter asking for it both happened.
    //
    // pagerState.currentPage is derived from the settled page, so the guard is
    // "was this scroll requested by the user": the pager reports the settled
    // page only after a drag, and programmatic scrolls are issued from a
    // LaunchedEffect on a different key.
    // Set while the user's finger drives the pager. Settles back to false after
    // the page has changed, so one drag arms exactly one turn.
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) userTurned = true
    }
    LaunchedEffect(pagerState.currentPage) {
        val page = pagerState.currentPage + 1
        val byUser = userTurned
        userTurned = false
        vm.setCurrentPage(page)
        if (byUser) vm.onUserPageTurn(page)
    }

    val density = LocalDensity.current

    // A deep link from search can pin the opening ayah.
    LaunchedEffect(initialAnchorAyah) {
        initialAnchorAyah?.let { vm.anchorToVerse(surah, it) }
    }

    // ---- Reader chrome, ported from quran_android's PagerActivity ----------
    // Chrome starts HIDDEN and auto-hides 2s after the window regains focus;
    // a single tap anywhere on the page toggles it, sliding the top bar off the
    // top and the bottom bar off the bottom together in 250ms.
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // The screen is held on for the whole app by `FLAG_KEEP_SCREEN_ON`, set once
    // in `onCreate`'s composition. This block used to be the reader's own,
    // conditional on `recording`, and it is gone for the same reason the one in
    // LiveMode is: three owners meant the phone dimmed on whichever screen was
    // not thinking about it. A recitation and a reading are the same phone on the
    // same table, and the screen going dark mid-ayah reads as the session ending.

    // ---- record the result ------------------------------------------------
    //
    // This used to snapshot `statusMap` when the session ended. That reads the
    // wrong thing: the engine prunes verdicts to lock+/-2 every frame and clears
    // them outright on a surah change, so by the time a session ends the map
    // holds the last five ayat - the ones after the recitation stopped. Four
    // recorded sessions came out as 89 SKIPPED and 1 CORRECT, which looks like
    // "you recited almost nothing right" and is mostly an artefact.
    //
    // So the tally holds its own copy and is fed on every emission, keeping only
    // words whose verdict actually changed. Nothing is lost to pruning, and a
    // word that passes through UNKNOWN -> SKIPPED -> CORRECT is counted once.
    val tally = remember { PracticeLog.Tally() }
    var livePeak by remember { mutableStateOf(0) }
    LaunchedEffect(recording, statusMap, activeVerse) {
        if (!recording) return@LaunchedEffect
        tally.fold(statusMap)
        livePeak = maxOf(livePeak, activeVerse ?: 0)
    }
    LaunchedEffect(recording) {
        if (recording) { tally.reset(); livePeak = 0; return@LaunchedEffect }
        // The surah comes from the verdict keys rather than from a captured
        // value, because a captured one goes stale the moment a handoff lands.
        val surahOf = statusMap.keys.firstOrNull()
            ?.substringBefore(':')?.toIntOrNull() ?: 0
        val s = tally.foldAll(statusMap, surahOf, livePeak, wpm)
        if (s != null && PracticeLog.isWorthRecording(s)) {
            // Disk write, off the composition thread. The log is rewritten whole
            // on append, so this is not something to do while laying out.
            scope.launch(Dispatchers.IO) { PracticeLog.append(ctx, s) }
        }
        tally.reset()
    }

    var chromeVisible by remember { mutableStateOf(false) }
    val chromeOffset by animateFloatAsState(
        targetValue = if (chromeVisible) 0f else 1f,
        animationSpec = tween(250),
        label = "chrome",
    )
    var night by remember { mutableStateOf(ReaderPrefs.nightMode(ctx)) }
    // Keyed on the settings store's change tick as well as on `night`, because
    // the two brightness sliders write the preference on every drag and the
    // page behind the panel has to follow them. It used to key on `night` only,
    // so tuning brightness adjusted the panel's own preview and left the page
    // unchanged until something unrelated recomposed it.
    val prefTick by ReaderPrefs.tick.collectAsStateWithLifecycle()
    val mat = remember(night, prefTick) { pageMat(ctx, night) }
    val pageInk = remember(night, prefTick) { NightPalette.pageInk(ctx) }
    val chromeInk = remember(night, prefTick) { NightPalette.chrome(ctx) }

    fun showChrome() {
        chromeVisible = true
    }

    LaunchedEffect(Unit) {
        // quran_android: DEFAULT_HIDE_AFTER_TIME = 2000
        kotlinx.coroutines.delay(2000)
        chromeVisible = false
    }
    LaunchedEffect(Unit) { showChrome() }

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = remember(night, mat, chromeInk) {
            lightReaderScheme(night, mat, pageInk, chromeInk)
        }) {
        val matBrush = remember(night) {
            if (night) null
            else Brush.horizontalGradient(
                NightPalette.pageGradient().map { it.second },
                startX = 0f,
                endX = 4000f,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                matBrush ?: androidx.compose.ui.graphics.SolidColor(mat)
            )
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { idx ->
                MushafPageView(
                    mushaf[idx], statusMap, hide, currentKey, active, activeVerse, playIndex, playHead,
                    onAnchorAyah = vm::anchorToVerse,
                    onSelectAyah = { s, a -> vm.selectAyah(s, a) },
                    onToggleSelectAyah = { s, a -> vm.toggleSelectAyah(s, a) },
                    onSeekAyah = { s, a -> vm.playFromAyah(s, a) },
                    playActive = playingSurah >= 0,
                    selectionOpen = selectedAyah != null,
                    selectedAyah = selectedAyah, activeWindow = activeWindow,
                    scopeSurah = scopeSurah,
                    advisories = advisoryMap,
                    earned = earned.words, earnedVersion = earned.version, judgedAyahs = judgedByAyah,
                    night = night, pageInk = pageInk, mat = mat,
                    chromeVisible = chromeVisible, onTapChrome = { chromeVisible = !chromeVisible },
                )
            }
            // Immersive reader header
            ReaderHeader(
                info = surahInfo,
                page = shownPage,
                playing = playingSurah == surah,
                loading = playLoading && playingSurah != surah,
                bookmarked = bookmarkPages.contains(shownPage),
                // The play affordance opens the pre-play sheet - reciter, start
                // ayah and speed are checked BEFORE anything sounds, per call.
                // One-tap play survives in the ayah sheet's "Listen from this
                // ayah" and in the audio library's rows.
                onPlayToggle = { playSheetAyah = 1; showPlaySheet = true },
                onToggleBookmark = { vm.toggleBookmark(shownPage) },
                onBack = onBack,
                onToggleNight = { night = !night; ReaderPrefs.setNightMode(ctx, night) },
                night = night,
                offset = chromeOffset,
            )
            if (showPlaySheet) {
                // headerSurah, not `surah`: the sheet must target the surah
                // actually on screen - `surah` is the one the reader OPENED
                // with, and after a swipe the two differ.
                ReferencePlaySheet(
                    vm, headerSurah,
                    ayahCount = surahInfo?.ayahCount ?: 1,
                    startAtAyah = playSheetAyah,
                ) { showPlaySheet = false }
            }
            // A failed reference-audio start used to stop silently, and an ayah
            // seek on a reciter without timings would too. The pill names which
            // it was; it dismisses itself or on tap.
            val notice = playError ?: seekNote
            notice?.let { msg ->
                Surface(
                    shape = Pill,
                    color = Chrome.Bar,
                    shadowElevation = 8.dp,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 84.dp, start = 24.dp, end = 24.dp)
                        .clickable {
                            vm.clearPlayError()
                            vm.clearSeekNote()
                        },
                ) {
                    Text(
                        msg,
                        fontSize = 12.sp,
                        color = Chrome.OnChrome,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            LaunchedEffect(notice) {
                if (notice != null) {
                    kotlinx.coroutines.delay(8000)
                    vm.clearPlayError()
                    vm.clearSeekNote()
                }
            }
            // Floating recitation bar - off the bottom while chrome is hidden
            // quran_android slides the bottom bar off the BOTTOM with
            // translationY(+height). Adding padding instead only grows the
            // surface, so the bar stayed on screen.
            val bottomShift = with(density) { (chromeOffset * 200.dp.toPx()).toDp() }
            val playerActive = playingSurah >= 0 || playLoading
            var playSpeedPct by remember { mutableStateOf(AudioPrefs.speedPercent(ctx)) }
            Surface(
                shape = Pill,
                color = Chrome.Bar,
                shadowElevation = 10.dp,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .offset(y = bottomShift)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp, start = 12.dp, end = 12.dp)
                    .then(if (playerActive) Modifier.fillMaxWidth() else Modifier),
            ) {
                // Listening and reciting are two different modes. While the
                // reference audio is up the bar is a transport; it cannot be
                // the recite bar, because a mic session running under the
                // speaker would judge the reciter for what the speaker said.
                if (playerActive) {
                    ReferencePlayerBar(
                        loading = playLoading && playingSurah < 0,
                        paused = playPaused,
                        posMs = playPosMs,
                        durMs = playDurMs,
                        speedPercent = playSpeedPct,
                        cacheProgress = cacheProgress,
                        cacheNote = cacheNote,
                        onPlayPause = vm::togglePlayPause,
                        onSeek = vm::seekPlay,
                        onPrev = { vm.playAdjacent(-1) },
                        onNext = { vm.playAdjacent(1) },
                        onCycleSpeed = {
                            playSpeedPct = if (playSpeedPct >= AudioPrefs.SPEED_MAX) {
                                AudioPrefs.SPEED_MIN
                            } else {
                                playSpeedPct + AudioPrefs.SPEED_STEP
                            }
                            vm.setPlaySpeed(playSpeedPct)
                        },
                        onDismissNote = vm::clearCacheNote,
                    )
                } else {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.toggleHide() }) {
                        Icon(
                            if (hide) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            "Hide verses",
                            tint = if (hide) accentColor else Chrome.OnChrome,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { showSearch = true }) {
                        Icon(
                            Icons.Filled.Search,
                            "Search the mushaf",
                            tint = Chrome.OnChrome,
                        )
                    }
                    if (recording && standWords.isEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        // The engine label is diagnostic, not reader-facing: it
                        // belongs in Diagnostics. Rendering it here made the row
                        // long enough to wrap, which grew the bar's height and
                        // squashed the Recite button into a vertical block.
                        PulseDot(
                            // REC says the session's audio is being KEPT (on
                            // this device only) - not just heard. The engine
                            // label stays diagnostic; the dot row is the one
                            // place a reciter can see the mic is recording.
                            label = "Listening" +
                                (if (sessionSound) " · REC" else "") +
                                if (engineLabel.isNotEmpty()) ", $engineLabel" else "",
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Listening…",
                            fontSize = 12.sp,
                            color = Chrome.OnChromeMuted,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            if (recording) vm.stopRecite()
                            else onRequestMic { vm.startRecite(shownPage) }
                        },
                        shape = Pill,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        if (preparing) {
                            // Spinner only. A bare "N%" used to render here
                            // from _modelProgress, which is never assigned (it
                            // stays -1), so it could only ever show a stale
                            // number that looked like an unexplained glitch.
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(if (recording) Icons.Filled.Close else Icons.Filled.Mic, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (recording) "Stop" else "Recite")
                        }
                    }
                    if (recording && standWords.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            ExpectedWordsLine(standWords, statusMap, currentKey, playIndex, playHead, Modifier.fillMaxWidth(), advisories = advisoryMap, hide = hide)
                            // How far through the surah the recitation has come.
                            // Was hide-only; a reciter with the words visible has
                            // the same question (where am I in this surah) and
                            // the strip answers it without a percentage, which
                            // would read as a score of the reciter.
                            if (scopeProgress.isNotEmpty()) {
                                Spacer(Modifier.height(3.dp))
                                ScopeProgressStrip(scopeProgress, Modifier.fillMaxWidth())
                            }
                        }
                    } else if (!recording && engineHint != null) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            engineHint ?: "",
                            fontSize = 11.sp,
                            color = wrongColor.copy(alpha = 0.85f),
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                }
            }
            if (showSearch) {
                SearchSheet(
                    query = { qq -> vm.searchAyat(qq) },
                    onGoToPage = { showSearch = false; showGoto = true },
                    onDismiss = { showSearch = false },
                    onPick = { s2, a2 ->
                        showSearch = false
                        vm.anchorToVerse(s2, a2)
                    },
                )
            }
            if (showGoto) {
                // Move to, in the reference app's shape: three linked pickers,
                // whichever you touch becomes the driver and the other two
                // follow. A page names the surah that lives on it; a surah
                // names its first page; an ayah names the page that carries
                // it. Plain on purpose - this is a navigation instrument, and
                // the value is the reach, not the decoration.
                val c = LocalIqraColors.current
                val surahs = remember { data?.surahList().orEmpty() }
                var jSurah by remember { mutableIntStateOf(headerSurah.coerceIn(1, 114)) }
                var jAyah by remember { mutableIntStateOf(1) }
                var jPage by remember { mutableIntStateOf(shownPage.coerceIn(1, mushaf.size)) }

                fun ayahCount(s: Int) = surahs.firstOrNull { it.number == s }?.ayahCount ?: 3
                fun firstAyahOnPage(surah: Int, page: Int): Int =
                    mushaf.getOrNull(page - 1)?.lines
                        ?.flatMap { it.words.orEmpty() }
                        ?.firstOrNull { it.surah == surah }?.verse ?: 1

                fun pickSurah(n: Int) {
                    jSurah = n
                    jAyah = 1
                    surahs.firstOrNull { it.number == n }?.let { jPage = it.startPage }
                }
                fun pickAyah(a: Int) {
                    jAyah = a.coerceIn(1, ayahCount(jSurah))
                    vm.pageOfVerse(jSurah, jAyah)?.let { jPage = it }
                }
                fun pickPage(p: Int) {
                    jPage = p
                    data?.surahAtPage(p)?.number?.let { s2 ->
                        jSurah = s2
                        jAyah = firstAyahOnPage(s2, p)
                    }
                }
                val go = {
                    if (jPage in 1..mushaf.size) {
                        vm.jumpToPage(jPage)
                        showGoto = false
                    }
                }
                // App chrome, not the reader's light scheme.
                MaterialTheme(colorScheme = iqraScheme(c)) {
                AlertDialog(
                    onDismissRequest = { showGoto = false },
                    confirmButton = { TextButton(onClick = go) { Text("Go") } },
                    dismissButton = { TextButton(onClick = { showGoto = false }) { Text("Cancel") } },
                    title = { Text("Move to") },
                    text = {
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                JumpPicker(
                                    modifier = Modifier.weight(1.5f),
                                    label = "Surah",
                                    value = "$jSurah · " + (surahs.firstOrNull { it.number == jSurah }?.nameEn ?: ""),
                                    count = surahs.size,
                                    selected = jSurah - 1,
                                    onPick = { i -> pickSurah(i + 1) },
                                    labeler = { i -> "${i + 1} · ${surahs[i].nameEn}" },
                                )
                                JumpPicker(
                                    modifier = Modifier.weight(0.9f),
                                    label = "Ayah",
                                    value = "$jAyah",
                                    count = ayahCount(jSurah),
                                    selected = jAyah - 1,
                                    onPick = { i -> pickAyah(i + 1) },
                                    labeler = { i -> "${i + 1}" },
                                )
                                JumpPicker(
                                    modifier = Modifier.weight(0.9f),
                                    label = "Page",
                                    value = "$jPage",
                                    count = mushaf.size,
                                    selected = jPage - 1,
                                    onPick = { i -> pickPage(i + 1) },
                                    labeler = { i -> "${i + 1}" },
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            val info = surahs.firstOrNull { it.number == jSurah }
                            Text(
                                buildString {
                                    append(info?.nameEn ?: "Surah $jSurah")
                                    append("  $jSurah:$jAyah")
                                    append("  ·  page $jPage")
                                    if (info != null) append("  ·  Juz ${info.juz}")
                                },
                                fontSize = 12.sp,
                                color = c.inkMuted,
                            )
                        }
                    },
                )
                }
            }
            if (repeatKey != null) {
                Surface(
                    shape = Pill,
                    color = Chrome.Bar,
                    shadowElevation = 8.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Repeating $repeatKey · $repeatLeft left",
                            fontSize = 12.sp,
                            color = Chrome.OnChrome,
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = { vm.cancelRepeat() },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(Icons.Filled.Close, "Cancel repeat", tint = Chrome.OnChrome, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (selectedAyah != null) {
                val selParts = selectedAyah!!.split(":")
                val selSurah = selParts[0].toInt()
                val selAyah = selParts[1].toInt()
                val selPage = remember(selectedAyah) { vm.pageOfVerse(selSurah, selAyah) }
                val selText = remember(selectedAyah) { vm.ayahText(selSurah, selAyah) }
                val selBookmarked = selPage?.let { bookmarkPages.contains(it) } ?: false
                // A long-press range is a first-class selection, not a hidden
                // field. When one is open the sheet names WHICH passage and
                // shows its text, so a multi-ayah selection is as visible and
                // copyable as a single ayah. Without this the range existed and
                // nothing could see it.
                val rangeEndAyah = selectedRange?.second?.split(":")?.getOrNull(1)?.toIntOrNull()
                val rangeIsMulti = rangeEndAyah != null && rangeEndAyah != selAyah
                val rangeText = remember(selectedRange, selSurah) {
                    if (rangeIsMulti && rangeEndAyah != null) {
                        generateSequence(selAyah) { it + 1 }.takeWhile { it <= rangeEndAyah }
                            .joinToString(" ") { vm.ayahText(selSurah, it) }
                    } else null
                }
                var repeatCount by remember(selectedAyah) { mutableStateOf(5) }
                var ayahTick by remember(selectedAyah) { mutableStateOf(0) }
                val clipboard = LocalClipboardManager.current
                val sheetCtx = LocalContext.current
                val selAyahBookmarked = remember(selectedAyah, ayahTick) {
                    AyahBookmarks.isBookmarked(sheetCtx, selSurah, selAyah)
                }
                val c = LocalIqraColors.current
                ModalBottomSheet(
                    onDismissRequest = { vm.clearSelection() },
                    containerColor = c.surfaceHigh,
                ) {
                    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                        Text(
                            if (rangeIsMulti && rangeEndAyah != null) "Ayahs $selAyah–$rangeEndAyah"
                            else "Ayah $selSurah:$selAyah",
                            fontWeight = FontWeight.Bold, fontSize = 18.sp,
                            color = c.ink,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            rangeText ?: selText,
                            fontFamily = quranFont,
                            fontSize = 20.sp,
                            textAlign = TextAlign.End,
                            color = c.ink,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                        SheetAction(Icons.Filled.Mic, "Practice from here") {
                            vm.clearSelection()
                            vm.anchorToVerse(selSurah, selAyah)
                            onRequestMic {
                                vm.startRecite(
                                    selPage ?: shownPage,
                                    selSurah to selAyah,
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Repeat", fontSize = 16.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { if (repeatCount > 1) repeatCount-- }) {
                                Text("−", fontSize = 20.sp)
                            }
                            Text(
                                "$repeatCount×",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            IconButton(onClick = { if (repeatCount < 20) repeatCount++ }) {
                                Text("+", fontSize = 20.sp)
                            }
                            Button(
                                onClick = {
                                    vm.clearSelection()
                                    vm.startRepeat(selSurah, selAyah, repeatCount)
                                },
                                shape = Pill,
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            ) {
                                Text("Start")
                            }
                        }
                        // Listen opens the play sheet pre-set to this ayah
                        // rather than streaming at once: reference audio can
                        // cost internet the first time it plays, and the
                        // reciter, start point and speed are the reader's
                        // choice to make before anything sounds.
                        SheetAction(Icons.Filled.PlayArrow, "Listen from this ayah") {
                            vm.clearSelection()
                            playSheetAyah = selAyah
                            showPlaySheet = true
                        }
                        SheetDivider()
                        SheetSectionHeader("Save")
                        BookmarkRow(
                            filled = selAyahBookmarked,
                            title = "This ayah",
                            detail = "Ayah $selSurah:$selAyah",
                        ) {
                            AyahBookmarks.toggle(sheetCtx, selSurah, selAyah)
                            ayahTick++
                        }
                        BookmarkRow(
                            filled = selBookmarked,
                            title = "This page",
                            detail = "Page ${selPage ?: shownPage}",
                        ) {
                            selPage?.let { vm.toggleBookmark(it) }
                        }
                        SheetDivider()
                        SheetSectionHeader("Share")
                        SheetAction(Icons.Filled.Share, "Share text") {
                            vm.clearSelection()
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, selText)
                            }
                            sheetCtx.startActivity(Intent.createChooser(send, null))
                        }
                        SheetAction(Icons.Filled.ContentCopy, "Copy text") {
                            vm.clearSelection()
                            clipboard.setText(AnnotatedString(selText))
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accentColor)
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 15.sp, color = LocalIqraColors.current.ink)
    }
}

/** A sheet section label, in the same idiom as every panel title in the app. */
@Composable
private fun SheetSectionHeader(label: String) {
    Text(
        label,
        fontSize = 10.sp,
        letterSpacing = 1.2.sp,
        color = LocalIqraColors.current.inkFaint,
        modifier = Modifier.padding(top = 8.dp, bottom = 1.dp),
    )
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        color = LocalIqraColors.current.hairline,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * A save target with its state visible: the filled bookmark says saved before
 * the row is read, the detail names exactly where the save lands, and the
 * trailing state word is the only thing that has to change on tap.
 */
@Composable
private fun BookmarkRow(
    filled: Boolean,
    title: String,
    detail: String,
    onToggle: () -> Unit,
) {
    val c = LocalIqraColors.current
    Row(
        Modifier.fillMaxWidth().clickable { onToggle() }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (filled) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
            null,
            tint = if (filled) goldColor else c.inkFaint,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = c.ink)
            Text(detail, fontSize = 11.sp, color = c.inkMuted)
        }
        Text(
            if (filled) "Saved" else "Save",
            fontSize = 12.sp,
            color = if (filled) accentColor else c.inkMuted,
        )
    }
}

/**
 * One linked picker of the Move-to dialog: a label, the current choice as a
 * tappable value, and a scrollable menu. Plain by intent - the convenience is
 * that 604 pages and 114 surahs are two taps away with the destination
 * previewed before the jump commits.
 */
@Composable
private fun JumpPicker(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    count: Int,
    selected: Int,
    onPick: (Int) -> Unit,
    labeler: (Int) -> String,
) {
    val c = LocalIqraColors.current
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, fontSize = 10.sp, letterSpacing = 1.sp, color = c.inkFaint)
        Spacer(Modifier.height(3.dp))
        Box {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = c.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, c.hairline),
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    value,
                    fontSize = 13.sp,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.heightIn(max = 340.dp),
                containerColor = c.surfaceHigh,
            ) {
                LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceAtMost(count - 1))) {
                    items(count) { i ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    labeler(i),
                                    fontSize = 13.sp,
                                    color = if (i == selected) c.accent else c.ink,
                                )
                            },
                            onClick = {
                                onPick(i)
                                open = false
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The pre-play sheet: who recites, where it starts, how fast - checked before
 * anything sounds, per call. The header's play button used to stream the
 * Settings reciter from the first ayah with no chance to look; the sheet is
 * the "specify and check" step, with one-tap play still available from the
 * ayah sheet and the library. Playing this surah already turns the primary
 * action into Stop, so the sheet is also the honest way to see what is
 * sounding and stop it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReferencePlaySheet(
    vm: PracticeViewModel,
    surah: Int,
    ayahCount: Int,
    startAtAyah: Int = 1,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val playingSurah by vm.playingSurah.collectAsStateWithLifecycle()
    var reciterId by remember { mutableStateOf(AudioPrefs.reciter(ctx)) }
    var ayah by remember { mutableIntStateOf(startAtAyah.coerceIn(1, ayahCount)) }
    var speed by remember { mutableIntStateOf(AudioPrefs.speedPercent(ctx)) }
    val seekable = remember(reciterId) { AyahTimings.isSupported(reciterId) }
    val c = LocalIqraColors.current

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surfaceHigh) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Listen to the recitation", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Spacer(Modifier.height(4.dp))
            Text(
                "Reference recitation of surah $surah - a reciter's voice, not yours. " +
                    "Nothing you play here is judged as your recitation.",
                fontSize = 11.sp, color = c.inkMuted,
            )
            Spacer(Modifier.height(14.dp))

            Text("Reciter", fontSize = 12.sp, color = c.inkMuted)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Reciters.ALL.forEach { r ->
                    val on = r.id == reciterId
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (on) c.accent
                                else c.surface
                            )
                            .clickable { reciterId = r.id }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            r.name,
                            fontSize = 12.sp,
                            color = if (on) c.ground
                            else c.inkMuted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start from ayah", fontSize = 12.sp, color = c.inkMuted)
                    Text(
                        if (ayah == 1) "the beginning" else "ayah $ayah",
                        fontSize = 15.sp,
                    )
                }
                OutlinedIconButton(onClick = { ayah = (ayah - 1).coerceIn(1, ayahCount) }) {
                    Text("−", fontSize = 20.sp)
                }
                Spacer(Modifier.width(10.dp))
                OutlinedIconButton(onClick = { ayah = (ayah + 1).coerceIn(1, ayahCount) }) {
                    Text("+", fontSize = 20.sp)
                }
            }
            Text(
                if (seekable) "This reciter has per-ayah timings, so playback starts at the chosen ayah."
                else "This reciter has no per-ayah timings, so playback starts at the surah's beginning.",
                fontSize = 11.sp, color = c.inkMuted,
            )
            Spacer(Modifier.height(14.dp))

            Text("Speed", fontSize = 12.sp, color = c.inkMuted)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(75, 100, 125, 150).forEach { pct ->
                    val on = pct == speed
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (on) c.accent
                                else c.surface
                            )
                            .clickable { speed = pct }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            if (pct % 100 == 0) "${pct / 100}×" else "${pct / 100.0}×",
                            fontSize = 12.sp,
                            color = if (on) c.ground
                            else c.inkMuted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            val isPlayingThis = playingSurah == surah
            Button(
                onClick = {
                    AudioPrefs.setReciter(ctx, reciterId)
                    AudioPrefs.setSpeedPercent(ctx, speed)
                    AudioPrefs.setLastPlayedSurah(ctx, surah)
                    when {
                        isPlayingThis -> vm.togglePlaySurah(surah)
                        ayah == 1 -> vm.togglePlaySurah(surah)
                        else -> vm.playFromAyah(surah, ayah)
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isPlayingThis) "Stop the recitation"
                    else if (ayah == 1) "Play surah $surah from the beginning"
                    else "Play surah $surah from ayah $ayah",
                )
            }
        }
    }
}

@Composable
fun ReaderHeader(
    info: com.iqra.quran.data.SurahInfo?,
    page: Int,
    playing: Boolean,
    loading: Boolean = false,
    bookmarked: Boolean,
    onPlayToggle: () -> Unit,
    onToggleBookmark: () -> Unit,
    onBack: () -> Unit,
    onToggleNight: () -> Unit = {},
    night: Boolean = false,
    offset: Float = 0f,
) {
    // quran_android slides the whole bar off the top over 250ms; offset 1 = hidden.
    val density = LocalDensity.current
    val shift = with(density) { (offset * -160.dp.toPx()).toDp() }
    var tuneNight by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxWidth()
            .offset(y = shift)
            .background(
                // Three stops faded the scrim to nothing by the time it reached
                // the header's last text line, so that line sat on the page's own
                // paper with the page's top border striking through it. The
                // extra stop holds the scrim through the text and only then
                // fades, so the reference fade survives and the line is legible.
                Brush.verticalGradient(
                    0f to Chrome.HeaderTop,
                    0.55f to Chrome.HeaderMid,
                    0.80f to Chrome.HeaderMid.copy(alpha = 0.72f),
                    1f to Color.Transparent,
                )
            )
            .statusBarsPadding()
            .padding(top = 6.dp, bottom = 18.dp),
    ) {
        IconButton(onClick = onBack, Modifier.align(Alignment.TopStart).padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Chrome.OnChrome)
        }
        Row(Modifier.align(Alignment.TopEnd)) {
            // tap toggles night mode; long-press opens the brightness
            // controls, which are a separate concern from the toggle
            Box(
                Modifier
                    .size(48.dp)
                    .combinedClickable(onClick = onToggleNight, onLongClick = { tuneNight = !tuneNight })
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (night) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    if (night) "Day mode" else "Night mode",
                    tint = Chrome.OnChrome,
                )
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    "Bookmark page",
                    tint = if (bookmarked) goldColor else Chrome.OnChrome,
                )
            }
            IconButton(onClick = onPlayToggle) {
                // Preparing a stream over the network takes a moment; the
                // button used to keep showing "play" as if the tap missed.
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Chrome.OnChrome,
                    )
                } else {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play recitation",
                        tint = if (playing) accentColor else Chrome.OnChrome,
                    )
                }
            }
        }
        // Sibling of the action Row: align() is a BoxScope modifier and has no
        // receiver inside a Row.
        if (tuneNight) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Chrome.Bar,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 6.dp)
                    .width(296.dp),
            ) { NightTuningPanel() }
        }
        // The title is painted after the icon rows, so anything wider than the
        // gap between them would slide underneath and be hidden. Cap it
        // explicitly rather than relying on padding around wrap-content text.
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 6.dp)
                .width(150.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The full "سُورَةُ X" form is long enough to run under the
            // action buttons even with clearance, so show the surah name on its
            // own, single line, and let it ellipsise rather than overlap.
            Text(
                info?.name?.removePrefix("سُورَةُ")?.trim() ?: "",
                fontFamily = quranFont,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = goldColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                buildString {
                    append(info?.nameEn ?: "")
                    info?.let {
                        append("  ·  ${it.revelationType}")
                        append("  ·  Juz ${it.juz}")
                        append("  ·  pp ${it.startPage}–${it.endPage}")
                    }
                    append("  ·  Page $page")
                },
                fontSize = 11.sp,
                color = Chrome.OnChromeMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

fun Mushaf_firstPage(pages: List<MushafPage>, surah: Int): Int =
    com.iqra.quran.data.Mushaf.firstPageOfSurah(pages, surah)

private object PageImageCache {
    private const val MAX = 4
    private val map = LinkedHashMap<Int, ImageBitmap>(MAX, 0.75f, true)

    @Synchronized
    fun get(page: Int): ImageBitmap? = map[page]

    @Synchronized
    fun put(page: Int, bmp: ImageBitmap) {
        map[page] = bmp
        while (map.size > MAX) {
            map.remove(map.keys.first())
        }
    }
}

private data class WordDraw(val rect: RectF, val style: WordStyle, val mask: RectF = rect)

/**
 * A word's own box, and the marks the glyph DB split off from it.
 *
 * The DB stores a word as several rows when a letter does not fit one box:
 * a stem, an ascender tip, a fragment of the previous word's tail. Those rows
 * are too small to be word bodies, so the pairing drops them - and a mask
 * built from the body alone leaves them standing.
 */
private data class WordMask(val body: RectF, val marks: List<RectF>)

/**
 * A word as the reader knows it before the mask is drawn: its box, the
 * style it paints with, and the marks that belong to it.
 */
private data class MaskedWord(val rect: RectF, val style: WordStyle, val marks: List<RectF>)

/**
 * U+06DE ARABIC START OF RUB EL HIZB (۞) and U+06E9 ARABIC PLACE OF SAJDAH
 * (۩): the only two signs the layout folds into a word's text that the glyph
 * DB also gives a full-size box of its own. 199 ayat carry a ۞ (always ahead
 * of word 1) and 15 carry a ۩ (always behind its word). They are furniture,
 * like the ayah-end roundel, so they belong to no word: never hidden, never
 * highlighted.
 */
private const val ORNAMENT_RUB_EL_HIZB = 0x06DE
private const val ORNAMENT_SAJDAH = 0x06E9

/** A printed token ([SLOT_TOKEN]) or a recitation sign, in page reading order. */
private const val SLOT_TOKEN = 0
private const val SLOT_ORNAMENT = 1
private const val SLOT_WAQF = 2

private class PageSlot(val word: Int, val kind: Int)

/**
 * True when [t] is one of the recitation signs and not a printed token.
 *
 * mushaf.json splits a word's text on whitespace, and the nine sign codepoints
 * U+06D6..U+06E9 only ever appear as a whitespace-separated part of their own
 * - never inside a printed token (verified across all 77,429 words). So the
 * whitespace split alone separates tokens from signs, with no guesswork about
 * which Arabic codepoints are letters. Format characters (RLM, ZWNBSP, ALM)
 * are ignored: 27:26 word 8 is "ٱلْعَظِيمِ ۩‏", with a trailing RLM glued to
 * the ۩.
 */
private fun isSignToken(t: String): Boolean {
    var sawSign = false
    for (c in t) {
        val v = c.code
        if (v in 0x200B..0x200F || v == 0xFEFF || v == 0x061C) continue
        if (v !in 0x06D6..0x06E9) return false
        sawSign = true
    }
    return sawSign
}

/**
 * Every printed token and sign of an ayah's words, in reading order.
 *
 * A word is not always one printed token: 'إِلْ يَاسِينَ' (37:130) and
 * 'بَعْدَ مَا' (2:89 w3, 8:6 w4) are single verse words drawn as two, and the
 * DB gives each its own box, so those words take two slots and their rect is
 * the union.
 */
private fun pageSlots(texts: List<String>): List<PageSlot> {
    val out = ArrayList<PageSlot>(texts.size)
    for (wi in texts.indices) {
        for (part in texts[wi].split(' ', '\t', '\n', '\u00A0', '\u2007', '\u202F')) {
            if (part.isEmpty()) continue
            out.add(
                PageSlot(
                    wi,
                    when {
                        !isSignToken(part) -> SLOT_TOKEN
                        part.any { it.code == ORNAMENT_RUB_EL_HIZB || it.code == ORNAMENT_SAJDAH } -> SLOT_ORNAMENT
                        else -> SLOT_WAQF
                    },
                ),
            )
        }
    }
    return out
}

/**
 * Pair an ayah's words with the glyph DB boxes for that ayah, using the
 * printed structure rather than box geometry.
 *
 * The DB stores one box per printed token in reading order, and the layout
 * says what those tokens are, so the pairing is a straight zip. The only
 * judgement is the count, and each branch below asserts it exactly:
 *
 *  - the ayah-end roundel is always the ayah's LAST box, so it is dropped
 *    unconditionally - that can never remove a word body;
 *  - `tokens + 1` boxes (the common case, 6,233 of 6,236 ayat): the DB boxed
 *    exactly the ornaments, which belong to no word, so they are skipped;
 *  - `tokens + 1 + signs` boxes: the DB also boxed every waqf sign. Measured
 *    on exactly one ayah, 38:26, whose ۚ U+06DA got a 53x27px box;
 *  - `tokens` boxes: the layout split a word the DB did not (5:52 w52
 *    'دَآئِرَ ةٌۭ ۚ' and 13:37 w52), so the count is one short and the plain
 *    positional zip is exact.
 *
 * Any other count means the DB and the layout disagree and nothing can be
 * proven, so this returns null and the caller leaves the whole ayah unboxed.
 * A word with no box is never hidden - it leaks, which is the safe direction:
 * the alternative is guessing, and hide clips rects[i] per word, so one wrong
 * box erases the word being recited and leaves the previous word lit.
 *
 * The area-ranked tiebreak this replaces dropped a real word box on 193 ayat
 * (181 with a ۞, 12 with a ۩) and shifted 3,975 words.
 */
private fun pairAyahBoxes(
    rows: List<Pair<Int, RectF>>,
    slots: List<PageSlot>,
    words: Int,
): List<WordMask?>? {
    // 2,190 of the DB's 88,246 rows store max_x < min_x, so the width comes
    // out negative and the size test threw the row away. Repair the box before
    // testing it. What the repair actually turns up is not lost words: 2,153
    // of the 2,190 stay under the size test either way, and the 37 that now
    // pass hold 20px of ink each against 1,111px for a real word body, i.e.
    // they are diacritics. So the swap is what makes 37 marks newly eligible,
    // and [MIN_WORD_H] is what keeps them out - without it those 37 leave 37
    // ayat one box short.
    val boxes = ArrayList<RectF>(rows.size)
    val boxLines = ArrayList<Int>(rows.size)
    val marks = ArrayList<Pair<Int, RectF>>()
    for ((line, r) in rows) {
        val x0 = min(r.left, r.right)
        val x1 = max(r.left, r.right)
        val y0 = min(r.top, r.bottom)
        val y1 = max(r.top, r.bottom)
        if (x1 - x0 >= MIN_WORD_BOX && y1 - y0 >= MIN_WORD_H) {
            boxes.add(RectF(x0, y0, x1, y1))
            boxLines.add(line)
        } else {
            // Too small to be a word body: a fragment the DB split off, an
            // ascender tip, or a sign. 4,363 of them, and they overhang
            // their word by up to 42px - far outside the mask's 3px outset,
            // which is why a hidden word still showed specks of itself.
            marks.add(line to RectF(x0, y0, x1, y1))
        }
    }
    if (boxes.isEmpty()) return null
    val body = boxes.subList(0, boxes.size - 1)
    val bodyLines = boxLines.subList(0, boxLines.size - 1)
    var tokens = 0
    var ornaments = 0
    var signs = 0
    for (s in slots) {
        when (s.kind) {
            SLOT_TOKEN -> tokens++
            SLOT_ORNAMENT -> ornaments++
            else -> signs++
        }
    }
    val extra = body.size - tokens - ornaments
    val seq = when {
        extra == 0 -> slots.filter { it.kind != SLOT_WAQF }
        extra == signs -> slots
        // The layout split a word the DB did not; body.size == words means the
        // positional zip is still exact and still drops only the roundel.
        extra < 0 && body.size == words -> List(words) { PageSlot(it, SLOT_TOKEN) }
        else -> return null
    }
    if (seq.size != body.size) return null
    val out = arrayOfNulls<RectF>(words)
    val bodyWord = IntArray(body.size) { -1 }
    for (i in seq.indices) {
        val s = seq[i]
        if (s.kind != SLOT_TOKEN || s.word >= words) continue
        val r = body[i]
        bodyWord[i] = s.word
        val prev = out[s.word]
        out[s.word] = if (prev == null) {
            RectF(r)
        } else {
            RectF(
                min(prev.left, r.left), min(prev.top, r.top),
                max(prev.right, r.right), max(prev.bottom, r.bottom),
            )
        }
    }
    // Each mark belongs to the word it sits on: the same printed line, and
    // the smallest horizontal gap to that word's body. Over the whole DB the
    // gap never exceeds 15px and no mark lands on another line, so this is
    // an attachment and not a guess.
    val marksOf = Array(words) { mutableListOf<RectF>() }
    for ((line, m) in marks) {
        var best = -1
        var bestGap = Float.MAX_VALUE
        for (i in body.indices) {
            if (bodyLines[i] != line) continue
            val w = bodyWord[i]
            if (w < 0) continue
            val b = body[i]
            val gap = when {
                m.right < b.left -> b.left - m.right
                m.left > b.right -> m.left - b.right
                else -> 0f
            }
            if (gap < bestGap) {
                bestGap = gap
                best = w
            }
        }
        if (best >= 0) marksOf[best].add(m)
    }
    val result = ArrayList<WordMask?>(words)
    for (w in 0 until words) {
        val b = out[w]
        result.add(if (b == null) null else WordMask(b, marksOf[w]))
    }
    return result
}

/**
 * The paper a hidden word is covered with: its own box, outset for the
 * diacritics that overhang it, and every mark the DB split off from it.
 *
 * The outset alone left specks. Measured over all 88,246 rows of the glyph
 * DB: 4,363 of them are too small to be word bodies, 96% of those overhang
 * their word by more than the 3px outset, and the worst overhang is 42px.
 * On page 4 the union of the two took the last 90px of mark ink off the
 * page and hid nothing else - a single bigger outset would not do it, since
 * covering 99% of the marks needs 42px, and at 42px the masks of 131,166
 * word pairs reach into a neighbour's body.
 *
 * [orphanInk] adds the ink the DB does not describe at all, and
 * [revealed] keeps it away from the words the app is showing as recited.
 */
private fun maskRectOf(
    body: RectF,
    marks: List<RectF>,
    orphanInk: List<RectF> = emptyList(),
    revealed: List<RectF> = emptyList(),
): RectF {
    val m = RectF(
        body.left - HIDDEN_OUTSET_PX, body.top - HIDDEN_OUTSET_PX,
        body.right + HIDDEN_OUTSET_PX, body.bottom + HIDDEN_OUTSET_PX,
    )
    for (k in marks) m.union(k)
    for (o in orphanInk) {
        // Only the ink close to this word: the orphan ink of a far-away word
        // is not this word's to hide.
        if (o.left > body.right + ORPHAN_NEAR_PX || o.right < body.left - ORPHAN_NEAR_PX ||
            o.top > body.bottom + ORPHAN_NEAR_PX || o.bottom < body.top - ORPHAN_NEAR_PX
        ) continue
        // And never the ink of a word the app is showing as recited: taking
        // it would damage a word the reciter got right.
        var claimed = false
        for (rv in revealed) {
            if (o.left <= rv.right + ORPHAN_REVEALED_GUARD_PX &&
                o.right >= rv.left - ORPHAN_REVEALED_GUARD_PX &&
                o.top <= rv.bottom + ORPHAN_REVEALED_GUARD_PX &&
                o.bottom >= rv.top - ORPHAN_REVEALED_GUARD_PX
            ) {
                claimed = true
                break
            }
        }
        if (!claimed) m.union(o)
    }
    return m
}

/**
 * How far an orphan ink cluster may be from a word and still be that word's.
 */
private const val ORPHAN_NEAR_PX = 10f

/**
 * How close an orphan ink cluster may be to a REVEALED word before it is
 * taken to be that word's own ink.
 */
private const val ORPHAN_REVEALED_GUARD_PX = 4f

/**
 * The largest orphan ink cluster a mask may swallow. A bigger one is
 * furniture - a surah frame, its title, the basmala under a heading - and
 * masking furniture would take a line of the Qur'an off the page.
 */
private const val ORPHAN_CAP_PX = 256

/**
 * The ink of a page that no glyph row describes, as small boxes.
 *
 * The DB boxes every word it knows and most of the strokes attached to it,
 * but not every stroke of the print: 1,349px of ink on page 4 lies outside
 * all 88,246 rows' boxes, in clusters of 1-21px, and those clusters are the
 * specks a hidden word still showed. No rectangle reaches them: the only rect
 * rule that does, a 12px outset, bites 17,450px out of 124 of 134 words.
 *
 * So the mask grows by the ink itself - the components of the ink no row
 * describes, and only the small ones. Measured on the two pages checked, the
 * rule leaves ZERO specks while the ink it erases from words the app shows
 * as recited is exactly what the mask already erased before it.
 *
 * The fill walks only the ink outside every row box: 1,349px on page 4
 * (0.08% of the page) and 38,321px on the page that carries a surah frame
 * (2.3%), of which the frame itself is 89%.
 */
private fun pageOrphanInk(image: ImageBitmap, rows: List<RectF>): List<RectF> {
    val bmp = image.asAndroidBitmap()
    val w = bmp.width
    val h = bmp.height
    if (w <= 0 || h <= 0) return emptyList()
    // 0 = no ink, 1 = ink inside a glyph row, 2 = ink no row describes,
    // 3 = an orphan already visited.
    val state = ByteArray(w * h)
    val sx = w / 1024f
    val sy = h / 1656f
    for (r in rows) {
        val x0 = (min(r.left, r.right) * sx).toInt().coerceIn(0, w - 1)
        val x1 = (max(r.left, r.right) * sx).toInt().coerceIn(0, w - 1)
        val y0 = (min(r.top, r.bottom) * sy).toInt().coerceIn(0, h - 1)
        val y1 = (max(r.top, r.bottom) * sy).toInt().coerceIn(0, h - 1)
        for (y in y0..y1) {
            val base = y * w
            for (x in x0..x1) state[base + x] = 1
        }
    }
    val buf = IntArray(w)
    for (y in 0 until h) {
        bmp.getPixels(buf, 0, w, 0, y, w, 1)
        val base = y * w
        for (x in 0 until w) {
            if (state[base + x] != 0.toByte()) continue
            if ((buf[x] ushr 24) != 0) state[base + x] = 2
        }
    }
    val out = ArrayList<RectF>()
    var stack = IntArray(4096)
    for (y in 0 until h) {
        val base = y * w
        for (x in 0 until w) {
            if (state[base + x] != 2.toByte()) continue
            var sp = 0
            stack[sp++] = base + x
            state[base + x] = 3
            var pixels = 0
            var l = x
            var r = x
            var t = y
            var b = y
            while (sp > 0) {
                val p = stack[--sp]
                val py = p / w
                val px = p - py * w
                pixels++
                if (px < l) l = px
                if (px > r) r = px
                if (py < t) t = py
                if (py > b) b = py
                for (dy in -1..1) {
                    val ny = py + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = px + dx
                        if (nx < 0 || nx >= w) continue
                        val np = ny * w + nx
                        if (state[np] != 2.toByte()) continue
                        state[np] = 3
                        if (sp == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[sp++] = np
                    }
                }
            }
            if (pixels <= ORPHAN_CAP_PX) {
                out.add(RectF(l / sx, t / sy, (r + 1) / sx, (b + 1) / sy))
            }
        }
    }
    return out
}

private data class WordStyle(
    val fg: Color,
    val bg: Color,
    val tint: Color,
    val tintAlpha: Float,
    val bold: Boolean,
    val strike: Boolean,
    val hidden: Boolean = false,
    val outline: Boolean = false,
)

private val amberColor = Color(0xFFE09112)

/**
 * The colour a masked word has to be filled with so the hole is invisible.
 *
 * Hide mode clips the hidden word rects OUT of the page, so those pixels end
 * up showing whatever is UNDER the page rather than the page's own paper. A
 * hardcoded fill cannot be right for both themes, so this samples the page.
 *
 * Two things were measured over the 604 runtime PNGs (murtraja images_1024,
 * which is what AssetPaths.pageUrl fetches): they are palette images whose
 * background is FULLY TRANSPARENT - on page 005, 1,497,455 of 1,695,744 pixels
 * have alpha 0 - and their modal opaque colour is the black ink
 * (0xFF000000), not paper. So "the dominant colour of the bitmap" is ink, and
 * taking it literally would paint the mask black. What the page actually shows
 * as paper is whatever its transparent pixels let through, so:
 *
 *  - if the page paints no paper of its own (the shipped PNGs), the paper IS
 *    the page layer's own background, passed in as [under];
 *  - if it does paint paper (an opaque-render variant of the same images), the
 *    modal sampled colour is that page's paper, and in night mode it is put
 *    through the same inversion the ink filter uses so the fill matches the
 *    page it sits on instead of glowing white in a dark room.
 */
private fun pagePaper(bmp: ImageBitmap, under: Color, night: Boolean, ctx: android.content.Context): Color {
    val grid = 24
    val b = bmp.asAndroidBitmap()
    val px = IntArray(grid * grid)
    var opaque = 0
    var i = 0
    for (gy in 0 until grid) {
        val y = (gy * (b.height - 1)) / (grid - 1)
        for (gx in 0 until grid) {
            val x = (gx * (b.width - 1)) / (grid - 1)
            val p = b.getPixel(x, y)
            px[i++] = p
            if ((p ushr 24) >= 128) opaque++
        }
    }
    if (opaque * 2 <= grid * grid) return under
    // 5 bits per channel: enough to group the paper together while keeping
    // the ink, which is orders of magnitude darker, in its own bucket.
    val hist = HashMap<Int, Int>(256)
    for (p in px) {
        if ((p ushr 24) < 128) continue
        hist[((p ushr 18) and 0x1F shl 10) or ((p ushr 10) and 0x1F shl 5) or ((p ushr 2) and 0x1F)] =
            (hist[((p ushr 18) and 0x1F shl 10) or ((p ushr 10) and 0x1F shl 5) or ((p ushr 2) and 0x1F)] ?: 0) + 1
    }
    val key = hist.maxByOrNull { it.value }?.key ?: return under
    var r = 0
    var g = 0
    var bl = 0
    var n = 0
    for (p in px) {
        if ((p ushr 24) < 128) continue
        val pr = (p ushr 16) and 0xFF
        val pg = (p ushr 8) and 0xFF
        val pb = p and 0xFF
        if (((pr shr 2) shl 10) or ((pg shr 2) shl 5) or (pb shr 2) != key) continue
        r += pr
        g += pg
        bl += pb
        n++
    }
    if (n == 0) return under
    val a = NightPalette.adjustedTextBrightness(
        ReaderPrefs.textBrightness(ctx), ReaderPrefs.backgroundBrightness(ctx),
    )
    fun ch(v: Int) = (if (night) a - v else v).coerceIn(0, 255)
    return Color(ch(r / n), ch(g / n), ch(bl / n))
}

private fun resolveLayer(
    st: WordStatus,
    isCurrent: Boolean,
    isPlayed: Boolean,
    isPlayHead: Boolean,
    inActiveAyah: Boolean,
    isSelected: Boolean,
    // Set when the word holds a verdict that the recitation window no longer
    // covers, which is the normal case for an ayah more than one behind the
    // lock: the engine expires those, the reader's own record does not.
    //
    // It earns the settled wash and nothing more. WRONG deliberately stays
    // gated on the window above: this record is first-verdict-wins, so a word
    // the reciter has since put right would otherwise keep a red it no longer
    // deserves, and a stale red is the one failure this app exists to prevent.
    judged: Boolean = false,
): HighlightLayer = when {
    st == WordStatus.WRONG && inActiveAyah -> HighlightLayer.WRONG
    isCurrent -> HighlightLayer.RECITATION_WORD
    isSelected -> HighlightLayer.SELECTION
    isPlayHead -> HighlightLayer.AUDIO_WORD
    isPlayed -> HighlightLayer.AUDIO
    inActiveAyah -> HighlightLayer.RECITATION_AYAH
    judged && st == WordStatus.CORRECT -> HighlightLayer.RECITATION_AYAH
    else -> HighlightLayer.NONE
}

/**
 * The layer a word paints with, from the two things the reader knows about it:
 * the engine's live paint map (fresh, but pruned to the lock +/- 2) and the
 * reader's own record of the verdicts it has seen this session.
 *
 * The two new branches are both gaps rather than new behaviour. `st == null`
 * used to short-circuit straight to UNSTARTED before [resolveLayer] was ever
 * consulted, so the one word the engine had explicitly placed the reciter on -
 * `currentKey` - was the one word guaranteed never to be highlighted whenever
 * the pruned map had forgotten it, and an ayah the archive could prove was
 * judged went back to looking untouched as soon as the lock passed it.
 */
private fun resolveWordLayer(
    status: WordStatus?,
    isCurrent: Boolean,
    isPlayed: Boolean,
    isPlayHead: Boolean,
    inActiveAyah: Boolean,
    isSelected: Boolean,
    hide: Boolean,
    judgedAyah: Boolean,
): HighlightLayer = when {
    status != null -> resolveLayer(
        status, isCurrent, isPlayed, isPlayHead, inActiveAyah, isSelected, judged = true,
    )
    // The word being spoken right now. Not while hiding: `currentKey` is also
    // published for a lock that was merely anchored or jumped to, and a mask
    // must not be broken by where the reader is looking rather than by what
    // the reciter said.
    isCurrent && !hide -> HighlightLayer.RECITATION_WORD
    // No verdict in hand, but the durable archive holds one for this ayah. Only
    // reachable for a reader rebuilt mid-session, where its own record starts
    // empty while `scopeProgress` does not forget. Both of these stay out of
    // hide mode, which may only ever be opened by this word's own verdict.
    !hide && judgedAyah -> HighlightLayer.RECITATION_AYAH
    else -> HighlightLayer.UNSTARTED
}

private fun resolveWordStyle(
    layer: HighlightLayer,
    st: WordStatus,
    hide: Boolean,
    onSurface: Color,
    background: Color,
    advisory: Advisory? = null,
    advisoryCue: Boolean = false,
    // This word's ayah was judged but its own verdict is not in hand. It only
    // ever selects the settled wash, and only outside hide mode: the archive
    // counts verdicts per ayah, so it can say the ayah was judged and not that
    // THIS word was, and the reader has no proof for anything stronger.
    judgedAyah: Boolean = false,
): WordStyle {
    if (hide) {
        // `judgedAyah` is deliberately not read below. An ayah being judged
        // says nothing about any one word in it, so it must never open a mask;
        // only a verdict carried by this word can.
        // Hide mode reveals a word on its OWN verdict, not on where the lock
        // happens to be.
        //
        // It used to reveal through `HighlightLayer.RECITATION_AYAH`, which
        // `resolveLayer` only produces inside the active window. So a word you
        // had recited correctly was masked again the instant the lock moved past
        // its ayah, and the user's report was the honest one: "in hide they
        // remain revealed - that isn't the case".
        //
        // A memoriser reveals a word by reciting it. That fact does not expire
        // because the reader has moved on, so it is keyed on the verdict, which
        // the reader now carries for the whole session - the paint map alone
        // expired each verdict two ayat after the lock passed it, which is the
        // mask re-appearing the user reported.
        // UNKNOWN and SKIPPED are the absence of a verdict and stay masked:
        // revealing those would show the answer without being asked for it.
        when (st) {
            WordStatus.CORRECT ->
                return WordStyle(onSurface, Color.Transparent, Color.Transparent,
                    0f, false, false)
            WordStatus.WRONG ->
                return WordStyle(wrongColor, Color.Transparent, wrongColor, 0.6f, true, false)
            else -> Unit
        }
        // Every layer below is a POSITION, not a verdict: where the lock is,
        // where the play-head is, what the reader selected, which ayah is
        // active. A position must never open a mask. The standing word is the
        // very word the reciter has NOT said yet, so painting it in blue
        // printed the answer the mode exists to withhold; the play-head and
        // the selection did the same for whichever word they sat on.
        //
        // The amber outline on RECITATION_AYAH went with them: it traced the
        // box of every hidden word, so the mask was invisible but the word
        // boundaries were not - the shape of the answer, if not its letters.
        return when (layer) {
            // A verdict, so a reveal. The WordStatus arms above return first,
            // so this is unreachable today; it is kept so that a future layer
            // cannot make a wrong word vanish.
            HighlightLayer.WRONG -> WordStyle(wrongColor, Color.Transparent, wrongColor, 0.6f, true, false)
            HighlightLayer.AUDIO_WORD,
            HighlightLayer.AUDIO,
            HighlightLayer.RECITATION_WORD,
            HighlightLayer.SELECTION,
            HighlightLayer.RECITATION_AYAH,
            HighlightLayer.NONE,
            HighlightLayer.UNSTARTED,
            -> WordStyle(background, Color.Transparent, Color.Transparent, 0f, false, false, hidden = true)
        }
    }
    return when (layer) {
        HighlightLayer.AUDIO_WORD -> WordStyle(goldColor, goldColor.copy(alpha = 0.25f), goldColor, 0.7f, true, false)
        HighlightLayer.AUDIO -> WordStyle(goldColor, goldColor.copy(alpha = 0.20f), goldColor, 0.30f, false, false)
        HighlightLayer.WRONG -> WordStyle(wrongColor, wrongColor.copy(alpha = 0.25f), wrongColor, 0.40f, true, false)
        HighlightLayer.RECITATION_WORD -> WordStyle(accentColor, accentColor.copy(alpha = 0.40f), accentColor, 0.55f, true, false)
        HighlightLayer.SELECTION -> WordStyle(accentColor, accentColor.copy(alpha = 0.30f), accentColor, 0.30f, true, false)
        // SKIPPED means "no evidence for this word" - the reciter has not got
        // to it, or the audio window has moved past it. It is the ABSENCE of a
        // judgement, and it used to paint wrongColor with a strikethrough: a
        // red wall over ayah the reciter had not even started, which reads as
        // "you got this wrong" and is the opposite of the truth. Red is now
        // reserved for a real WRONG verdict, which resolveLayer only produces
        // for WordStatus.WRONG. No evidence is simply muted.
        HighlightLayer.RECITATION_AYAH ->
            if (judgedAyah) WordStyle(
                // Judged in the archive, detail not in hand: the settled wash a
                // decided word gets, and deliberately not more. No amber (that
                // is the advisory mark, and there is no note here) and no red -
                // an accusation needs this word's own verdict, which is exactly
                // what is missing.
                onSurface, accentColor.copy(alpha = 0.10f), accentColor, 0.10f, false, false,
            )
            else if (st == WordStatus.SKIPPED || st == WordStatus.UNKNOWN) WordStyle(
                onSurface.copy(alpha = 0.45f),
                // The advisory mark: an amber wash on a word the engine flagged
                // it could not judge. NEVER on hide-mode words - that would leak
                // which word the app suspects. It never sits under WRONG or
                // CORRECT, because a verdict already settled that word.
                if (advisoryCue && advisory != null) amberColor.copy(alpha = 0.12f) else Color.Transparent,
                if (advisoryCue && advisory != null) amberColor else Color.Transparent,
                if (advisoryCue && advisory != null) 0.12f else 0f,
                false,
                false,
            )
            else WordStyle(onSurface, accentColor.copy(alpha = 0.10f), accentColor, 0.10f, false, false)
        HighlightLayer.NONE,
        HighlightLayer.UNSTARTED,
        -> WordStyle(onSurface, Color.Transparent, Color.Transparent, 0f, false, false)
    }
}

private fun fmtClock(ms: Int): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun speedLabel(percent: Int): String {
    val v = percent / 100f
    return if (v == v.toInt().toFloat()) "${v.toInt()}×" else "$v×"
}

/**
 * The transport that replaces the recite bar while reference audio is up.
 * Prev/next walk surahs (wrapping the cache on disk or the stream), the
 * slider scrubs, the chip cycles the saved speed, and the thin row under it
 * reports the background offline save honestly rather than silently failing.
 */
@Composable
fun ReferencePlayerBar(
    loading: Boolean,
    paused: Boolean,
    posMs: Int,
    durMs: Int,
    speedPercent: Int,
    cacheProgress: Float?,
    cacheNote: String?,
    onPlayPause: () -> Unit,
    onSeek: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCycleSpeed: () -> Unit,
    onDismissNote: () -> Unit,
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shownPos = scrub?.toInt() ?: posMs
    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, enabled = !loading, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.SkipPrevious, "Previous surah", tint = Chrome.OnChrome)
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = Chrome.OnChrome,
                )
            } else {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(38.dp)) {
                    Icon(
                        if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        if (paused) "Resume" else "Pause",
                        tint = Chrome.OnChrome,
                    )
                }
            }
            IconButton(onClick = onNext, enabled = !loading, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.SkipNext, "Next surah", tint = Chrome.OnChrome)
            }
            Text(
                fmtClock(shownPos),
                fontSize = 11.sp,
                color = Chrome.OnChromeMuted,
                modifier = Modifier.padding(start = 2.dp),
            )
            Slider(
                value = if (durMs > 0) scrub ?: posMs.toFloat() else 0f,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { onSeek(it.toInt()) }
                    scrub = null
                },
                enabled = durMs > 0,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(fmtClock(durMs), fontSize = 11.sp, color = Chrome.OnChromeMuted)
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = Pill,
                color = Chrome.OnChrome.copy(alpha = 0.12f),
                modifier = Modifier.clickable(onClick = onCycleSpeed),
            ) {
                Text(
                    speedLabel(speedPercent),
                    fontSize = 11.sp,
                    color = Chrome.OnChrome,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (cacheProgress != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
            ) {
                CircularProgressIndicator(
                    progress = { cacheProgress },
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = Chrome.OnChromeMuted,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Saving this surah for replay (${(cacheProgress * 100).toInt()}%)",
                    fontSize = 11.sp,
                    color = Chrome.OnChromeMuted,
                )
            }
        } else if (cacheNote != null) {
            Text(
                cacheNote,
                fontSize = 11.sp,
                color = wrongColor.copy(alpha = 0.85f),
                modifier = Modifier
                    .padding(start = 6.dp, top = 2.dp, bottom = 2.dp)
                    .clickable(onClick = onDismissNote),
            )
        }
    }
}

@Composable
fun MushafPageView(
    page: MushafPage,
    statusMap: Map<String, WordStatus>,
    hide: Boolean,
    currentKey: String?,
    active: Boolean,
    activeVerse: Int?,
    playOrder: Map<String, Int>,
    playHead: Int,
    onAnchorAyah: (Int, Int) -> Unit = { _, _ -> },
    onSelectAyah: (Int, Int) -> Unit = { _, _ -> },
    onToggleSelectAyah: (Int, Int) -> Unit = { _, _ -> },
    selectionOpen: Boolean = false,
    selectedAyah: String? = null,
    activeWindow: List<Int> = emptyList(),
    scopeSurah: Int = 1,
    advisories: Map<String, Advisory> = emptyMap(),
    // Verdicts the reader has seen this session (see [EarnedVerdicts], passed as
    // its map) and the archive-backed per-ayah verdict counts. Both are consulted
    // only to fill a gap the pruned paint map has left; the live map always wins.
    // `earnedVersion` is a separate argument because the map is one instance for
    // the whole session and cannot be compared to detect a change.
    earned: Map<String, WordStatus> = emptyMap(),
    earnedVersion: Int = 0,
    judgedAyahs: Map<Int, ScopeAyah> = emptyMap(),
    night: Boolean = false,
    pageInk: Color = Color(0xFF505050),
    mat: Color = Color(0xFFF4EAD3),
    chromeVisible: Boolean = true,
    onTapChrome: () -> Unit = {},
    playActive: Boolean = false,
    onSeekAyah: (Int, Int) -> Unit = { _, _ -> },
) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val lineGroups = remember(page.page) {
        GlyphCoords.ensure(ctx)
        GlyphCoords.lineGroups(page.page)
    }
    var loadFailed by remember(page.page) { mutableStateOf(false) }
    val bmpState by produceState<ImageBitmap?>(initialValue = PageImageCache.get(page.page), page.page) {
        if (value == null && !loadFailed) {
            value = withContext(Dispatchers.IO) {
                try {
                    // Pages live in the shared folder (or are bundled); both
                    // are reached through AssetPaths so the APK stays small.
                    val f = com.iqra.quran.data.AssetPaths.pageFile(ctx, page.page)
                    val bmp = if (f.isFile) {
                        BitmapFactory.decodeFile(f.absolutePath)
                    } else {
                        ctx.assets.open("pages/%03d.png".format(page.page)).use { BitmapFactory.decodeStream(it) }
                    }
                    bmp?.asImageBitmap()?.also { PageImageCache.put(page.page, it) }
                } catch (e: Exception) { null }
            }
            if (value == null) loadFailed = true
        }
    }
    val bmp: ImageBitmap? = bmpState
    if (bmp == null) {
        if (loadFailed) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 56.dp),
        ) {
            for (line in page.lines) {
                when (line.type) {
                    "surah-header" -> SurahHeader(line.text ?: "")
                    "basmala" -> Basmala()
                    "text" -> LineText(line.words ?: emptyList(), statusMap, hide, currentKey, active, activeVerse, playOrder, playHead, selectedAyah, activeWindow, scopeSurah, advisories, earned, judgedAyahs)
                }
            }
        }
        } else {
            Box(
                Modifier.fillMaxSize().background(PAGE_MASK),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = goldColor)
            }
        }
        return
    }

    val allWords = remember(page.page) { page.lines.flatMap { it.words ?: emptyList() } }
    // Printed tokens per ayah, from the layout text. Keyed on the page alone:
    // the token list only changes when the page does, so it must not be rebuilt
    // on every status tick like `draws` below.
    val slotsByAyah = remember(page.page, allWords) {
        allWords.groupBy { "${it.surah}:${it.verse}" }
            .mapValues { (_, ws) -> pageSlots(ws.sortedBy { it.wordInVerse }.map { it.text }) }
    }
    val cs = MaterialTheme.colorScheme
    // Colour a masked word has to be filled with, sampled from the page itself
    // so it is right for all 604 pages and both themes.
    val paper = remember(page.page, bmp, night, cs.background) {
        pagePaper(bmp, cs.background, night, ctx)
    }
    // The ink no glyph row describes, per page: the last of the specks a
    // hidden word showed. Computed only in hide mode, and only once a page.
    val orphanInk = remember(page.page, hide, bmp) {
        if (hide) pageOrphanInk(bmp, lineGroups.values.flatten()) else emptyList()
    }
    // Page-only geometry, cached per page: the ayah→boxes join and the
    // word→box pairing are functions of the page's own data (DB rects, layout
    // tokens, word list) and of no verdict. They used to sit inside the
    // verdict-keyed `draws` remember, so live recitation re-paired every box
    // on the page and re-grouped every word list several times a second -
    // the hide-mode lag the user felt.
    val wordsAndMasksByAyah = remember(page.page, allWords, lineGroups, slotsByAyah) {
        val byAyah = LinkedHashMap<String, MutableList<Pair<Int, RectF>>>()
        val orderedKeys = lineGroups.keys.mapNotNull { k ->
            val pp = k.split(":")
            if (pp.size != 3) null
            else Triple(pp[0].toInt(), pp[1].toInt(), pp[2].toInt()) to k
        }.sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third }))
        for ((_, gk) in orderedKeys) {
            val ak = gk.substringBeforeLast(":")
            val line = gk.substringAfterLast(":").toIntOrNull() ?: 0
            byAyah.getOrPut(ak) { mutableListOf() }
                .addAll((lineGroups[gk] ?: emptyList()).map { line to it })
        }
        val wordsByAyah = allWords.groupBy { "${it.surah}:${it.verse}" }
            .mapValues { (_, ws) -> ws.sortedBy { it.wordInVerse } }
        buildMap {
            for ((ak, rectsAll) in byAyah) {
                val words = wordsByAyah[ak] ?: continue
                // One box per printed token, zipped against the layout's own
                // token list: the roundel, the ۞ and the ۩ are then excluded
                // because the layout says they are not words, never because
                // they looked small. Returns exactly words.size rects, or null
                // when the counts cannot be reconciled - see pairAyahBoxes.
                val masks = pairAyahBoxes(rectsAll, slotsByAyah[ak] ?: emptyList(), words.size)
                    ?: continue
                put(ak, words to masks)
            }
        }
    }
    // Each word's own orphan ink, chosen once per page by the very near-test
    // maskRectOf applies. maskRectOf used to scan the page-wide orphan list
    // for every word on every verdict tick; the per-word list is a handful of
    // rects. The revealed-word guard still runs LIVE against the current
    // revealed set inside maskRectOf, so the output is byte-identical.
    val orphansByWord = remember(page.page, hide, orphanInk, wordsAndMasksByAyah) {
        if (!hide || orphanInk.isEmpty()) emptyMap()
        else buildMap {
            for ((ak, pair) in wordsAndMasksByAyah) {
                val (words, masks) = pair
                for (i in words.indices) {
                    val wm = masks[i] ?: continue
                    val b = wm.body
                    val near = orphanInk.filter { o ->
                        !(o.left > b.right + ORPHAN_NEAR_PX || o.right < b.left - ORPHAN_NEAR_PX ||
                            o.top > b.bottom + ORPHAN_NEAR_PX || o.bottom < b.top - ORPHAN_NEAR_PX)
                    }
                    if (near.isNotEmpty()) {
                        put("${words[i].surah}:${words[i].verse}:${words[i].wordInVerse}", near)
                    }
                }
            }
        }
    }
    val draws = remember(
        page.page, statusMap, advisories, currentKey, playOrder, playHead, activeVerse, hide, selectedAyah, activeWindow, scopeSurah,
        wordsAndMasksByAyah, orphansByWord,
        // earnedVersion rather than earned: the map is the same object for the
        // whole session and would compare equal to itself, so the draw list
        // would never rebuild when a new verdict arrives.
        earned, earnedVersion, judgedAyahs,
    ) {
        buildList {
            val items = ArrayList<Pair<String, MaskedWord>>()
            for ((ak, pair) in wordsAndMasksByAyah) {
                val (words, masks) = pair
                for (i in words.indices) {
                    // Null only if a word ended up with no token at all, which
                    // the count branches rule out; keep the guard so an
                    // unboxed word leaks rather than being drawn with a
                    // neighbour's box.
                    val wm = masks[i] ?: continue
                    val rect = wm.body
                    val w = words[i]
                    val key = "${w.surah}:${w.verse}:${w.wordInVerse}"
                    // The live map first, the reader's own record only to fill
                    // what it has expired. Never the other way round: the live
                    // map is the one that knows a word is being re-judged now.
                    val st = statusMap[key] ?: earned[key]
                    val isCur = key == currentKey
                    val gi = playOrder[key]
                    val isPlayed = gi != null && gi <= playHead
                    val isPlayHead = gi != null && gi == playHead
                    // The wash compares the standing scope's surah too:
                    // ayah numbers repeat in every surah, and on a page
                    // holding three surahs the unqualified compare lit the
                    // neighbouring surahs' ayat in sync with the lock.
                    // key is "$surah:$verse:$word"; a scope-qualified
                    // compare on it is exact and needs no member read.
                    val inScope = key.startsWith("$scopeSurah:")
                    val inActive = if (activeWindow.isEmpty()) activeVerse != null && w.verse == activeVerse else activeWindow.contains(w.verse)
                    val layer = resolveWordLayer(
                        st, isCur, isPlayed, isPlayHead, inActive && inScope, key == selectedAyah,
                        hide, inScope && judgedAyahs[w.verse].judged(),
                    )
                    val style = resolveWordStyle(
                        layer, st ?: WordStatus.SKIPPED, hide, cs.onSurface, cs.background,
                        advisory = advisories[key],
                        advisoryCue = !hide && ReaderPrefs.advisoryDisplay(ctx) == ReaderPrefs.AdvisoryDisplay.IN_FLOW,
                        judgedAyah = st == null && !hide && inScope && judgedAyahs[w.verse].judged(),
                    )
                    items.add(key to MaskedWord(rect, style, wm.marks))
                }
            }
            // A word the app shows as recited keeps its ink: the orphan ink
            // near it is not this word's, and taking it would damage a word
            // the reciter got right.
            val revealed = items.filter { !it.second.style.hidden }.map { it.second.rect }
            for ((key, it) in items) {
                add(WordDraw(it.rect, it.style, maskRectOf(it.rect, it.marks, orphansByWord[key].orEmpty(), revealed)))
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(
            Modifier.align(Alignment.Center).fillMaxSize().padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val pageW = minOf(maxWidth, maxHeight * 1024f / 1656f)
            val imgH = pageW * 1656f / 1024f
                // Reference page chrome: 7dp + 11dp margins, 13dp side
                // borders, and a 1px centre fold on alternate pages
                // (QuranPageLayout.onMeasure / updateView). The fold marks
                // which half of a two-page spread this is.
                val isLeftHalf = page.page % 2 == 0
                // Reference chrome (QuranPageLayout): 13dp side borders, with
                // the fold line on the leading edge so the two halves of a
                // spread read as a book rather than two loose sheets.
                val fold = NightPalette.foldColor(night)
                Box(
                    Modifier.width(pageW).height(imgH)
                        .drawBehind {
                            val bw = 13.dp.toPx()
                            val line = 1.dp.toPx()
                            val side = if (isLeftHalf) 1 else 0
                            drawRect(
                                color = fold,
                                topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                                size = androidx.compose.ui.geometry.Size(line, size.height),
                            )
                            if (side == 0) {
                                drawRect(
                                    color = fold,
                                    topLeft = androidx.compose.ui.geometry.Offset(size.width - line, 0f),
                                    size = androidx.compose.ui.geometry.Size(line, size.height),
                                )
                            }
                            drawRect(
                                color = fold.copy(alpha = 0.10f),
                                topLeft = androidx.compose.ui.geometry.Offset(bw, 0f),
                                size = androidx.compose.ui.geometry.Size(size.width - bw * 2, size.height),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = line),
                            )
                        }
                        .shadow(elevation = 6.dp, shape = RoundedCornerShape(6.dp), clip = false)
                        .pointerInput(page.page, lineGroups) {
                            fun hit(px: Float, py: Float): Pair<Int, Int>? {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                if (w <= 0f || h <= 0f) return null
                                val gx = px / w * 1024f
                                val gy = py / h * 1656f
                                for ((groupKey, rects) in lineGroups) {
                                    for (r in rects) {
                                        if (gx in r.left..r.right && gy in r.top..r.bottom) {
                                            val pp = groupKey.split(":")
                                            return pp[0].toInt() to pp[1].toInt()
                                        }
                                    }
                                }
                                return null
                            }
                            // Gesture map - one meaning per gesture, no overlap:
                            //   single tap  -> toggle the chrome; on an ayah it
                            //                  collapses an open selection to that
                            //                  ayah, and while the reference
                            //                  audio is up it seeks there instead
                            //   long press  -> select that ayah / extend a range
                            //   double tap  -> pull the recitation lock to that ayah
                            detectTapGestures(
                                onTap = { offset ->
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    // The modes are state, so the branches never
                                    // fight over one gesture: an open selection
                                    // captures the tap first, then active audio.
                                    val tapped = hit(offset.x, offset.y)
                                    if (tapped != null && selectionOpen) {
                                        onSelectAyah(tapped.first, tapped.second)
                                    } else if (tapped != null && playActive) {
                                        onSeekAyah(tapped.first, tapped.second)
                                    } else {
                                        onTapChrome()
                                    }
                                },
                                onLongPress = { offset ->
                                    hit(offset.x, offset.y)?.let { (s, a) ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // Long-press SELECTS and extends a range.
                                        // Moving the lock is a double-tap now, so
                                        // the two gestures stop competing.
                                        onToggleSelectAyah(s, a)
                                    }
                                },
                                onDoubleTap = { offset ->
                                    hit(offset.x, offset.y)?.let { (s, a) ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onAnchorAyah(s, a)
                                    }
                                },
                            )
                        },
                ) {
                Canvas(Modifier.fillMaxSize()) {
                    val sx = size.width / 1024f
                    val sy = size.height / 1656f
                    // Hide mode: like quran_android's HighlightingImageView, the
                    // page is drawn ONCE with every hidden word rect clipped
                    // OUT, so the ink is genuinely gone rather than covered -
                    // which is why the holes then have to be repainted with the
                    // page's own paper below. Ayah markers, surah headers and
                    // the ۞/۩ ornaments are not word rects, so they stay
                    // printed as indicators.
                    val dst = IntSize(size.width.toInt(), size.height.toInt())
                    // quran_android applies a single ColorMatrixColorFilter to the
                    // page: out = adjusted - in per channel, alpha preserved.
                    // High filter quality matters because the default is a
                    // bilinear downscale, which visibly softens the glyphs.
                    val inkF = if (night) {
                        val a = NightPalette.adjustedTextBrightness(
                            ReaderPrefs.textBrightness(ctx), ReaderPrefs.backgroundBrightness(ctx))
                        ColorFilter.colorMatrix(ColorMatrix(
                            floatArrayOf(
                                -1f, 0f, 0f, 0f, a.toFloat(),
                                0f, -1f, 0f, 0f, a.toFloat(),
                                0f, 0f, -1f, 0f, a.toFloat(),
                                0f, 0f, 0f, 1f, 0f,
                            )
                        ))
                    } else null
                    if (hide) {
                        // The Difference clip removes the page from every hidden
                        // word rect, so those pixels are left showing whatever
                        // is UNDER the page - which is the window colour, not
                        // this page's paper, and in day mode that left a cream
                        // rectangle the exact shape of every masked word. Paint
                        // the page's own paper into each hole FIRST, outside the
                        // clip, and the mask becomes invisible. Per rect rather
                        // than one full-page fill because the page chrome the
                        // Modifier's drawBehind laid down (the 1dp fold line and
                        // the border stroke) lives in the page margins and has
                        // to stay visible.
                        // The mask is the word's own box OUTSET by
                        // HIDDEN_OUTSET_PX, together with every mark the DB
                        // split off from it (see maskRectOf). The outset alone
                        // was not enough: over the whole DB, 96% of the marks
                        // overhang their word by more than 3px and the worst
                        // overhang is 42px, so a hidden word still showed the
                        // strokes above it - the specks. The fill and the clip
                        // must use the same rect, or the fill no longer matches
                        // its own hole.
                        for (d in draws) {
                            if (!d.style.hidden) continue
                            val r = d.mask
                            drawRect(
                                color = paper,
                                topLeft = Offset(r.left * sx, r.top * sy),
                                size = Size(r.width() * sx, r.height() * sy),
                            )
                        }
                        // Then the page itself, minus the holes, then overlays
                        // unclipped below. Markers, headers and the ۞/۩ are not
                        // word rects, so they stay printed.
                        withTransform({
                            for (d in draws) {
                                if (!d.style.hidden) continue
                                val r = d.mask
                                clipRect(
                                    r.left * sx,
                                    r.top * sy,
                                    r.right * sx,
                                    r.bottom * sy,
                                    ClipOp.Difference,
                                )
                            }
                        }) {
                            drawImage(bmp, dstSize = dst, filterQuality = FilterQuality.High, colorFilter = inkF)
                        }
                    } else {
                        drawImage(bmp, dstSize = dst, filterQuality = FilterQuality.High, colorFilter = inkF)
                    }
                    for (d in draws) {
                        if (d.style.tintAlpha <= 0f) continue
                        val off = Offset(d.rect.left * sx, d.rect.top * sy)
                        val sz = Size(d.rect.width() * sx, d.rect.height() * sy)
                        if (d.style.outline) {
                            drawRect(d.style.tint, off, sz, alpha = d.style.tintAlpha, style = Stroke(width = 3f))
                        } else {
                            drawRect(d.style.tint, off, sz, alpha = d.style.tintAlpha)
                        }
                    }
                    // The play-head word is already painted by the loop above:
                    // resolveLayer gives it HighlightLayer.AUDIO_WORD, which
                    // resolveWordStyle tints gold at 0.70 with an outline in
                    // both themes. The old drawPlayHeadWord() added a second,
                    // un-clipped gold rect at 0.55 on top of that, and it could
                    // never fire anyway: it looked the boxes up as
                    // "s:q:${w.line}" while w.line is a 0-based text ordinal
                    // and lineGroups is keyed by the DB's printed line_number
                    // (min 1, no 0 rows), so the lookup missed on every page.
                    // Any replacement has to read from `draws`, which carries
                    // the corrected pairing, not from lineGroups, which would
                    // reintroduce the box sequence this file just fixed.
                    drawRect(Color.Black.copy(alpha = 0.06f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
                }
            }
        }
    }
    val juzNum = remember(page.page) {
        val starts = com.iqra.quran.data.QuranData.JUZ_START_PAGES
        val idx = starts.indexOfFirst { it > page.page }
        if (idx == -1) 30 else idx
    }
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            modifier = Modifier.padding(bottom = 72.dp),
        ) {
            Text(
                "Page ${page.page} · Juz $juzNum",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
fun LineText(
    words: List<MushafWord>,
    statusMap: Map<String, WordStatus>,
    hide: Boolean,
    currentKey: String?,
    active: Boolean,
    activeVerse: Int?,
    playOrder: Map<String, Int> = emptyMap(),
    playHead: Int = -1,
    selectedAyah: String? = null,
    activeWindow: List<Int> = emptyList(),
    scopeSurah: Int = 1,
    advisories: Map<String, Advisory> = emptyMap(),
    // Same two sources the page draw uses. This renderer has to agree with it:
    // it exists only when a page bitmap will not load, and a reader who gets
    // one page with the old colouring and the next with the new one would read
    // that as the engine changing its mind.
    earned: Map<String, WordStatus> = emptyMap(),
    judgedAyahs: Map<Int, ScopeAyah> = emptyMap(),
) {
    if (words.isEmpty()) return
    // This is the only renderer where the app draws the mushaf text itself, so
    // it is the one place the user's text-size setting really lands - the
    // normal path is a page bitmap that cannot be scaled. lineHeight scales with
    // the glyph size because at 23/38 the leading is what makes the mushaf's
    // own line rhythm; hold it fixed and larger text collides with the row above.
    val scale = ReaderPrefs.fontScale(LocalContext.current)
    val roundelVerses = words.filter { it.isVerseEnd }.map { it.verse }
    val inlineContent = roundelVerses.associate { v ->
        "rdl_$v" to InlineTextContent(
            Placeholder((20 * scale).sp, (20 * scale).sp, PlaceholderVerticalAlign.Center),
        ) { VerseRoundel(v) }
    }
    val builder = AnnotatedString.Builder()
    words.forEachIndexed { i, w ->
        val key = "${w.surah}:${w.verse}:${w.wordInVerse}"
        val st = statusMap[key] ?: earned[key]
        val isCur = key == currentKey
        val inScope = key.startsWith("$scopeSurah:")
        val inActiveAyah = inScope && if (activeWindow.isEmpty()) activeVerse != null && w.verse == activeVerse else activeWindow.contains(w.verse)
        val gi = playOrder[key]
        val isPlayed = gi != null && gi <= playHead
        val isPlayHead = gi != null && gi == playHead
        val cs = MaterialTheme.colorScheme
        val layer = resolveWordLayer(
            st, isCur, isPlayed, isPlayHead, inActiveAyah, "${w.surah}:${w.verse}" == selectedAyah,
            hide, inScope && judgedAyahs[w.verse].judged(),
        )
        val style = resolveWordStyle(
            layer, st ?: WordStatus.SKIPPED, hide, cs.onSurface, cs.background,
            advisory = advisories[key],
            advisoryCue = !hide && ReaderPrefs.advisoryDisplay(LocalContext.current) == ReaderPrefs.AdvisoryDisplay.IN_FLOW,
            judgedAyah = st == null && !hide && inScope && judgedAyahs[w.verse].judged(),
        )
        builder.pushStyle(
            SpanStyle(
                // This fallback renders over the reader's tiled paper gradient
                // (Reader's matBrush), NOT over colorScheme.background, so
                // painting a hidden word in `background` left a cream block
                // that stayed faintly legible. style.fg is only ever used for
                // the glyphs, and hidden means no glyphs: keep the word's slot
                // so the line stays justified and the word keeps its place in
                // the recitation order, but draw it transparent.
                color = if (style.hidden) Color.Transparent else style.fg,
                background = style.bg,
                fontWeight = if (style.bold) FontWeight.SemiBold else FontWeight.Normal,
                textDecoration = if (style.strike) TextDecoration.LineThrough else null,
            ),
        )
        builder.append(w.text)
        builder.pop()
        if (w.isVerseEnd) {
            builder.append(" ")
            builder.appendInlineContent("rdl_${w.verse}", " ")
        }
        if (i < words.lastIndex) builder.append(" ")
    }
    Text(
        builder.toAnnotatedString(),
        fontFamily = quranFont,
        fontSize = (23 * scale).sp,
        lineHeight = (38 * scale).sp,
        textAlign = TextAlign.Justify,
        modifier = Modifier.fillMaxWidth(),
        inlineContent = inlineContent,
    )
}

@Composable
fun VerseRoundel(number: Int) {
    Box(
        Modifier.padding(horizontal = 4.dp).size(26.dp)
            .border(1.5.dp, goldColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", fontSize = 12.sp, color = goldColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SurahHeader(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("﷽", fontSize = 26.sp, color = goldColor)
        Spacer(Modifier.height(4.dp))
        Text(
            text,
            fontSize = 22.sp,
            fontFamily = quranFont,
            fontWeight = FontWeight.Bold,
            color = goldColor,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth(0.6f).height(1.5.dp).background(goldColor))
    }
}

@Composable
fun Basmala() {
    Text(
        "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ",
        fontSize = 22.sp,
        fontFamily = quranFont,
        color = goldColor,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
