package com.iqra.quran.ui

import com.iqra.quran.R
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material.icons.filled.Refresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Share
import android.content.Intent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.outlined.History
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
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
import com.iqra.quran.data.MushafPage
import com.iqra.quran.data.MushafWord
import com.iqra.quran.data.HighlightLayer
import com.iqra.quran.data.WordStatus
import com.iqra.quran.data.GlyphCoords

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkMushafScheme(), shapes = mushafShapes()) {
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

/** Day mat: the reference's tiled paper gradient; night: flat grey. */
private fun pageMat(ctx: android.content.Context, night: Boolean): Color =
    if (night) NightPalette.mat(ctx) else Color(0xFFF6F1E3)

internal val quranFont = FontFamily(Font(R.font.amiri))
internal val accentColor = Color(0xFF2BB6A0)

/**
 * Minimum glyph box width, in the DB's 1024-wide page space, for a box to
 * count as a word body. Superscript and diacritic marks are stored as their
 * own glyph rows and are far smaller (median height 21px vs 70px).
 */
private const val MIN_WORD_BOX = 20f

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
private val reciteBlue = Color(0xFF4A9EFF)
private val PAGE_MASK = Color(0xFFF3ECD9) // parchment, used to hide words on light page images
private val ParchmentScaffold = Color(0xFFE6DDC4) // warm dim parchment that frames the page

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

private fun darkMushafScheme() = darkColorScheme(
    primary = accentColor,
    secondary = goldColor,
    background = Color(0xFF15151A),
    surface = Color(0xFF1F1F26),
    surfaceVariant = Color(0xFF2A2A33),
    secondaryContainer = accentColor.copy(alpha = 0.15f),
    onSecondaryContainer = accentColor,
    outline = goldColor.copy(alpha = 0.25f),
    onBackground = Color(0xFFF2E8D5),
    onSurface = Color(0xFFF2E8D5),
    onPrimary = Color(0xFF06231F),
)

private fun mushafShapes() = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
private fun ExpectedWordsLine(
    words: List<MushafWord>,
    statusMap: Map<String, WordStatus>,
    currentKey: String?,
    playOrder: Map<String, Int>,
    playHead: Int,
    modifier: Modifier = Modifier,
) {
    if (words.isEmpty()) return
    val b = AnnotatedString.Builder()
    words.forEachIndexed { i, w ->
        val key = "${w.surah}:${w.verse}:${w.wordInVerse}"
        val st = statusMap[key]
        val gi = playOrder[key]
        val isPlayed = gi != null && gi <= playHead
        val isHead = gi != null && gi == playHead
        val isCur = key == currentKey
        val fg = when {
            st == null -> Chrome.OnChrome
            st == WordStatus.WRONG -> wrongColor
            isCur -> accentColor
            isPlayed || isHead -> goldColor
            st == WordStatus.SKIPPED -> Chrome.OnChrome.copy(alpha = 0.45f)
            else -> Chrome.OnChrome
        }
        b.pushStyle(
            SpanStyle(
                color = fg,
                background = if (isCur) accentColor.copy(alpha = 0.30f)
                else if (st == WordStatus.WRONG) wrongColor.copy(alpha = 0.25f)
                else Color.Transparent,
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
        fontSize = 15.sp,
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
    var screen by remember { mutableStateOf<Screen>(Screen.Picker) }

    if (loading || data == null) {
        SplashScreen()
        return
    }
    when (val s = screen) {
        Screen.Data -> DataSetupScreen { screen = Screen.Picker }
        Screen.Picker -> HomeScreen(vm, lastRead,
            onOpen = { surah, page -> screen = Screen.Reader(surah, page) },
            onOpenAyah = { surah, page, ayah -> screen = Screen.Reader(surah, page, ayah) },
            onDiag = { screen = Screen.Diag },
            onData = { screen = Screen.Data },
            onResume = {
                lastRead?.let { (s, p) ->
                    vm.resumeLastRead()
                    screen = Screen.Reader(s, p)
                }
            },
        )
        is Screen.Diag -> DiagScreen(vm) { screen = Screen.Picker }
        is Screen.Reader -> {
            val pages = mushaf
            if (pages == null) {
                SplashScreen()
            } else {
                ReaderScreen(
                    vm = vm,
                    surah = s.surah,
                    startPage = s.page,
                    initialAnchorAyah = s.anchorAyah,
                    onBack = { screen = Screen.Picker },
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
}

enum class HomeTab { Surahs, Juz, Bookmarks }
enum class SurahView { List, Grid }

@Composable
fun HomeScreen(
    vm: PracticeViewModel,
    lastRead: Pair<Int, Int>?,
    onOpen: (Int, Int) -> Unit,
    onOpenAyah: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onDiag: () -> Unit = {},
    onData: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    val data = vm.data.collectAsStateWithLifecycle().value ?: return
    var tab by remember { mutableStateOf(HomeTab.Surahs) }
    val surahs = remember { data.surahList() }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(cs.background)) {
        // Deliberately quiet: no gradient slab, no tagline stack, no 9sp
        // disclaimer. One wordmark, one search field, one tab underline.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Iqra",
                fontFamily = quranFont,
                fontSize = 24.sp,
                color = cs.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (lastRead != null) {
                // Resuming is a navigation decision, so it lives here - on
                // home - rather than inside the reading surface.
                IconButton(onClick = onResume) {
                    Icon(Icons.Outlined.History, "Back to last read", tint = cs.onSurface.copy(alpha = 0.55f))
                }
            }
            IconButton(onClick = onData) {
                Icon(Icons.Outlined.FolderOpen, "Data files", tint = cs.onSurface.copy(alpha = 0.55f))
            }
            IconButton(onClick = onDiag) {
                Icon(Icons.Outlined.MonitorHeart, "Engine check", tint = cs.onSurface.copy(alpha = 0.55f))
            }
        }

        // One search field for both surah names and ayah text.
        var q by remember { mutableStateOf("") }
        val focus = LocalFocusManager.current
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            placeholder = {
                Text(
                    "Search a surah, or a word from a verse",
                    fontSize = 14.sp,
                    color = cs.onSurface.copy(alpha = 0.45f),
                )
            },
            singleLine = true,
            shape = Pill,
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = cs.onSurface.copy(alpha = 0.5f)) },
            trailingIcon = {
                if (q.isNotEmpty()) {
                    IconButton(onClick = { q = "" }) {
                        Icon(Icons.Outlined.Close, "Clear", tint = cs.onSurface.copy(alpha = 0.5f))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor,
                unfocusedBorderColor = cs.onSurface.copy(alpha = 0.18f),
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val trimmed = q.trim()
        val surahHits = remember(trimmed, surahs) {
            if (trimmed.isBlank()) emptyList() else surahs.filter {
                it.number.toString() == trimmed ||
                    it.name.contains(trimmed) ||
                    it.nameEn.contains(trimmed, ignoreCase = true) ||
                    it.nameEn.startsWith(trimmed, ignoreCase = true)
            }
        }
        val ayahHits = remember(trimmed) { if (trimmed.isBlank()) emptyList() else vm.searchAyat(trimmed) }

        if (trimmed.isNotBlank()) {
            SearchResults(
                surahHits = surahHits,
                ayahHits = ayahHits,
                data = data,
                onOpen = onOpen,
                onOpenAyah = onOpenAyah,
            )
            return@Column
        }

        HomeTabRow(tab) { tab = it }
        when (tab) {
            HomeTab.Surahs -> SurahIndex(vm, lastRead, data, onOpen)
            HomeTab.Juz -> JuzList(vm, data, onOpen)
            HomeTab.Bookmarks -> BookmarkList(vm, data, onOpen)
        }
    }
}

@Composable
private fun SearchResults(
    surahHits: List<com.iqra.quran.data.SurahInfo>,
    ayahHits: List<com.iqra.quran.data.AyahSearch.Hit>,
    data: com.iqra.quran.data.QuranData,
    onOpen: (Int, Int) -> Unit,
    onOpenAyah: (Int, Int, Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val lazy = rememberLazyListState()
    LaunchedEffect(surahHits.size, ayahHits.size) { lazy.scrollToItem(0) }
    LazyColumn(
        state = lazy,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (surahHits.isNotEmpty()) {
            item { SectionHeader("Surahs", surahHits.size, false) }
            items(surahHits, key = { "s${it.number}" }) { s ->
                SurahRow(s) { n, p -> onOpen(n, p) }
            }
        }
        if (ayahHits.isNotEmpty()) {
            item { SectionHeader("Verses", ayahHits.size, false) }
            items(ayahHits, key = { "a${it.surah}:${it.ayah}" }) { h ->
                val v = data.getVerse(h.surah, h.ayah)
                val surah = data.surahInfo(h.surah)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val p = surah?.let { if (h.ayah in it.startPage..it.endPage) it.startPage else 1 }
                                ?: 1
                            onOpenAyah(h.surah, p, h.ayah)
                        }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${h.surah}:${h.ayah}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = goldColor,
                        modifier = Modifier.width(52.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            v?.textUthmani?.take(90) ?: "",
                            fontFamily = quranFont,
                            fontSize = 17.sp,
                            color = cs.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            surah?.nameEn ?: "",
                            fontSize = 10.sp,
                            color = cs.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
                HorizontalDivider(color = cs.onSurface.copy(alpha = 0.07f))
            }
        }
        if (surahHits.isEmpty() && ayahHits.isEmpty()) {
            item { EmptyHint("Nothing matched.") }
        }
    }
}

@Composable
fun HomeTabRow(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val tabs = HomeTab.values()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        tabs.forEach { t ->
            val sel = t == selected
            Box(
                Modifier.weight(1f).padding(4.dp).clip(Pill)
                    .background(if (sel) goldColor.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(t) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (t) {
                        HomeTab.Surahs -> "Surahs"
                        HomeTab.Juz -> "Juz"
                        HomeTab.Bookmarks -> "Bookmarks"
                    },
                    fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (sel) goldColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
fun SurahIndex(
    vm: PracticeViewModel,
    lastRead: Pair<Int, Int>?,
    data: com.iqra.quran.data.QuranData,
    onOpen: (Int, Int) -> Unit,
) {
    val surahs = remember { data.surahList() }
    var view by remember { mutableStateOf(SurahView.List) }
    // Filtering is driven by the single search field on HomeScreen; this
    // composable used to carry a second one and home rendered two boxes.
    val filtered = surahs
    val meccan = remember(filtered) { filtered.filter { it.revelationType == "Meccan" } }
    val madani = remember(filtered) { filtered.filter { it.revelationType == "Madani" } }
    val gridRows = remember(filtered) { filtered.chunked(3) }
    val continueInfo = remember(lastRead, surahs) {
        lastRead?.let { (num, page) -> surahs.firstOrNull { it.number == num }?.let { it to page } }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { view = if (view == SurahView.List) SurahView.Grid else SurahView.List }) {
                Icon(
                    if (view == SurahView.List) Icons.Filled.ViewModule else Icons.Filled.ViewList,
                    "Toggle layout",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            if (view == SurahView.List) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    continueInfo?.let { (info, page) ->
                        item { ContinueCard(info, page) { onOpen(info.number, page) } }
                    }
                    if (meccan.isNotEmpty()) {
                        item { SectionHeader("Meccan", meccan.size, false) }
                        items(meccan, key = { it.number }) { SurahRow(it, onOpen) }
                    }
                    if (madani.isNotEmpty()) {
                        item { SectionHeader("Madani", madani.size, true) }
                        items(madani, key = { it.number }) { SurahRow(it, onOpen) }
                    }
                    if (filtered.isEmpty()) {
                        item { EmptyHint("No surah matches") }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp, top = 4.dp),
                ) {
                    continueInfo?.let { (info, page) ->
                        item { ContinueCard(info, page) { onOpen(info.number, page) } }
                    }
                    items(gridRows, key = { it.first().number }) { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { s ->
                                Box(Modifier.weight(1f)) { SurahGridCell(s, onOpen) }
                            }
                            repeat(3 - row.size) { Box(Modifier.weight(1f)) {} }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContinueCard(info: com.iqra.quran.data.SurahInfo, page: Int, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onClick),
        shape = CardRadius,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Continue", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "${info.nameEn}  ·  Page $page  ·  ${info.ayahCount} verses",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, count: Int, madani: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = (if (madani) accentColor else goldColor).copy(alpha = 0.2f), modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text("· $count", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
fun SurahRow(s: com.iqra.quran.data.SurahInfo, onOpen: (Int, Int) -> Unit) {
    val madani = s.revelationType == "Madani"
    val accent = if (madani) accentColor else goldColor
    Card(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onOpen(s.number, s.startPage) },
        shape = CardRadius,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = accent.copy(alpha = 0.16f), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${s.number}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, fontFamily = quranFont, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(2.dp))
                Text(s.nameEn, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                Spacer(Modifier.height(4.dp))
                Text(
                    "${if (madani) "Madani" else "Meccan"} · ${s.ayahCount} verses · pp ${s.startPage}–${s.endPage} · Juz ${s.juz}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            Surface(shape = CircleShape, color = accent, modifier = Modifier.size(10.dp)) {}
        }
    }
}

@Composable
fun SurahGridCell(s: com.iqra.quran.data.SurahInfo, onOpen: (Int, Int) -> Unit) {
    val madani = s.revelationType == "Madani"
    val accent = if (madani) accentColor else goldColor
    Column(
        Modifier.padding(6.dp).fillMaxWidth().clickable { onOpen(s.number, s.startPage) }
            .clip(CardRadius).background(MaterialTheme.colorScheme.surface).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(34.dp).background(accent.copy(alpha = 0.16f), CircleShape),
        ) { Text("${s.number}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent) }
        Spacer(Modifier.height(6.dp))
        Text(s.name, fontFamily = quranFont, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, maxLines = 1)
        Text(s.nameEn, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun JuzList(
    vm: PracticeViewModel,
    data: com.iqra.quran.data.QuranData,
    onOpen: (Int, Int) -> Unit,
) {
    val juz = remember { data.juzList() }
    val surahs = remember { data.surahList() }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
    ) {
        itemsIndexed(juz, key = { _, j -> j.number }) { idx, j ->
            val surah = surahs.firstOrNull { j.startPage in it.startPage..it.endPage }
            val endPage = juz.getOrNull(idx + 1)?.startPage?.minus(1) ?: 604
            Card(
                Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    .clickable { onOpen(surah?.number ?: 1, j.startPage) },
                shape = CardRadius,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = accentColor.copy(alpha = 0.16f), modifier = Modifier.size(42.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${j.number}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accentColor)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Juz ${j.number}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "pp ${j.startPage}–$endPage" + if (j.surahNameEn.isNotEmpty()) " · ${j.surahNameEn}" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarkList(
    vm: PracticeViewModel,
    data: com.iqra.quran.data.QuranData,
    onOpen: (Int, Int) -> Unit,
) {
    val pages by vm.bookmarks.collectAsStateWithLifecycle()
    val sorted = pages.sorted()
    val surahs = remember { data.surahList() }
    if (sorted.isEmpty()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.BookmarkBorder,
                null,
                tint = goldColor.copy(alpha = 0.7f),
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "No bookmarks yet",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap the bookmark icon while reading to save a page.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
    ) {
        items(sorted, key = { it }) { page ->
            val info = surahs.firstOrNull { page in it.startPage..it.endPage }
            Card(
                Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onOpen(info?.number ?: 1, page) },
                shape = CardRadius,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bookmark, null, tint = goldColor)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Page $page", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            info?.let { "${it.nameEn} · Juz ${it.juz}" } ?: "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    IconButton(onClick = { vm.toggleBookmark(page) }) {
                        Icon(
                            Icons.Filled.Delete,
                            "Remove bookmark",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiagScreen(vm: PracticeViewModel, onBack: () -> Unit) {
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
        if (snap.any { !it.present }) return "Missing engine files — push them via adb (commands on each row), then restart recitation."
        if (recording && micN > 0 && micDb < 0.005f) return "Mic delivers near-silence — check gain, distance, or another app holding the mic."
        if (micStalled) return "Mic stream frozen — stop and start recitation again."
        // The gate is fed by SherpaVad.feedAndDetect() (or the RMS fallback)
        // and its per-frame verdict is folded straight into gateReason, never
        // published, so name the gate that is actually running instead of
        // pointing at a VAD row that does not exist.
        if (recording && gate == "silence") {
            val which = if (engineLabel.endsWith("/VAD")) "Silero VAD" else "RMS"
            return "Gate hears silence — the $which gate is closed, so nothing reaches the decoder. Recite louder."
        }
        if (recording && lastMatch == null) return "No ayah matched yet — recite the locked ayah clearly."
        return null
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("Engine check", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { snap = vm.engineFilesInfo() }) {
                Icon(Icons.Filled.Refresh, "Refresh", tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(log.joinToString("\n"))) }) {
                Icon(Icons.Filled.ContentCopy, "Copy log")
            }
            // The frame ring is what a lock-policy question is actually answered
            // from: per frame it records the lock plus every coverage the
            // decision was made on, including the ones that were rejected. Its
            // header carries the thresholds, so a dump never has to hard-code
            // the policy it was recorded under.
            IconButton(onClick = { clipboard.setText(AnnotatedString(vm.frameRingDump())) }) {
                Icon(Icons.Filled.Receipt, "Copy frame ring")
            }
            // Without this the rotated logs accumulate for the life of the
            // install and "the log" stops meaning one debugging session.
            IconButton(onClick = { vm.clearPersistedDiag() }) {
                Icon(Icons.Filled.Delete, "Clear saved log")
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                DiagSection("Engine · " + if (engineLabel.isNotEmpty()) engineLabel else "idle") {
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
                DiagSection("Mic · ${"%.3f".format(micDb)} · $micN samples") {
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
                    DiagRow("Endpointing", engineLabel.ifEmpty { "—" })
                }
            }
            item {
                DiagSection("Matcher") {
                    DiagRow("Lock", activeVerse?.toString() ?: "—")
                    DiagRow("Last match", lastMatch?.let { "${it.first} @ ${"%.2f".format(it.second)}" } ?: "—")
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
                DiagSection("Events (${log.size})") {
                    if (log.isEmpty()) {
                        Text(
                            "No events yet — start a recitation.",
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
    val statusMap by vm.statusMap.collectAsStateWithLifecycle()
    val currentKey by vm.currentKey.collectAsStateWithLifecycle()
    val currentPage by vm.currentPage.collectAsStateWithLifecycle()
    val playingSurah by vm.playingSurah.collectAsStateWithLifecycle()
    val engineLabel by vm.engineLabel.collectAsStateWithLifecycle()
    val preparing by vm.preparing.collectAsStateWithLifecycle()
    val activeVerse by vm.activeVerse.collectAsStateWithLifecycle()
    val activeWindow by vm.activeWindow.collectAsStateWithLifecycle()
    val selectedAyah by vm.selectedAyah.collectAsStateWithLifecycle()
    val repeatKey by vm.repeatAyahKey.collectAsStateWithLifecycle()
    val repeatLeft by vm.repeatLeft.collectAsStateWithLifecycle()
    val playIndex by vm.playIndex.collectAsStateWithLifecycle()
    val playHead by vm.playHead.collectAsStateWithLifecycle()
    val engineHint by vm.engineHint.collectAsStateWithLifecycle()
    val standWords = remember(activeVerse, mushaf) {
        val av = activeVerse ?: return@remember emptyList<MushafWord>()
        mushaf.flatMap { pg -> pg.lines.flatMap { it.words ?: emptyList() } }
            .filter { it.surah == surah && it.verse == av }
            .sortedBy { it.wordInVerse }
    }
    val bookmarkPages by vm.bookmarks.collectAsStateWithLifecycle()
    var showGoto by remember { mutableStateOf(false) }
    var gotoText by remember { mutableStateOf("") }

    // The header must describe the page currently on screen. It used to be
    // remember(data, surah) - frozen at the surah you opened - so the title and
    // page indicator stayed on the original surah after swiping into another.
    val headerSurah = data?.surahAtPage(currentPage ?: (startPage ?: 1))?.number ?: surah
    val surahInfo = remember(data, headerSurah) {
        data?.surahList()?.firstOrNull { it.number == headerSurah }
    }
    val active = recording || statusMap.isNotEmpty()
    val startIdx = remember(surah, startPage) { (startPage ?: Mushaf_firstPage(mushaf, surah)) - 1 }
    val pagerState = rememberPagerState(initialPage = startIdx, pageCount = { mushaf.size })

    LaunchedEffect(startPage) {
        if (startPage != null) vm.jumpToPage(startPage)
    }
    LaunchedEffect(currentPage) {
        val target = (currentPage ?: (startIdx + 1)) - 1
        if (target != pagerState.currentPage) {
            pagerState.animateScrollToPage(target)
        }
        vm.saveLastRead(currentPage ?: (startIdx + 1))
    }
    LaunchedEffect(pagerState.currentPage) {
        vm.setCurrentPage(pagerState.currentPage + 1)
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
    var chromeVisible by remember { mutableStateOf(false) }
    val chromeOffset by animateFloatAsState(
        targetValue = if (chromeVisible) 0f else 1f,
        animationSpec = tween(250),
        label = "chrome",
    )
    var night by remember { mutableStateOf(ReaderPrefs.nightMode(ctx)) }
    val mat = remember(night) { pageMat(ctx, night) }
    val pageInk = remember(night) { NightPalette.pageInk(ctx) }
    val chromeInk = remember(night) { NightPalette.chrome(ctx) }

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
                    selectedAyah = selectedAyah, activeWindow = activeWindow,
                    night = night, pageInk = pageInk, mat = mat,
                    chromeVisible = chromeVisible, onTapChrome = { chromeVisible = !chromeVisible },
                )
            }
            // Immersive reader header
            ReaderHeader(
                info = surahInfo,
                page = (currentPage ?: (startIdx + 1)),
                playing = playingSurah == surah,
                bookmarked = bookmarkPages.contains(currentPage ?: -1),
                onPlayToggle = { vm.togglePlaySurah(surah) },
                onToggleBookmark = { vm.toggleBookmark(currentPage ?: (startIdx + 1)) },
                onBack = onBack,
                onToggleNight = { night = !night; ReaderPrefs.setNightMode(ctx, night) },
                night = night,
                offset = chromeOffset,
            )
            // Floating recitation bar - off the bottom while chrome is hidden
            // quran_android slides the bottom bar off the BOTTOM with
            // translationY(+height). Adding padding instead only grows the
            // surface, so the bar stayed on screen.
            val bottomShift = with(density) { (chromeOffset * 200.dp.toPx()).toDp() }
            Surface(
                shape = Pill,
                color = Chrome.Bar,
                shadowElevation = 10.dp,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .offset(y = bottomShift)
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp, start = 12.dp, end = 12.dp),
            ) {
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
                    IconButton(onClick = { showGoto = true }) {
                        Icon(
                            Icons.Filled.Search,
                            "Go to page",
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
                            label = "Listening" +
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
                            else onRequestMic { vm.startRecite(currentPage ?: (startIdx + 1)) }
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
                        ExpectedWordsLine(standWords, statusMap, currentKey, playIndex, playHead, Modifier.weight(1f))
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
            if (showGoto) {
                val go = {
                    val p = gotoText.toIntOrNull()
                    if (p != null && p in 1..mushaf.size) {
                        vm.jumpToPage(p)
                        gotoText = ""
                        showGoto = false
                    }
                }
                AlertDialog(
                    onDismissRequest = { showGoto = false },
                    confirmButton = {
                        TextButton(onClick = go) { Text("Go") }
                    },
                    dismissButton = { TextButton(onClick = { showGoto = false }) { Text("Cancel") } },
                    title = { Text("Go to page") },
                    text = {
                        Column {
                            Text(
                                "Page ${currentPage ?: (startIdx + 1)} of ${mushaf.size}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = gotoText,
                                onValueChange = { gotoText = it.filter { c -> c.isDigit() }.take(3) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { go() }),
                                placeholder = { Text("1 – ${mushaf.size}") },
                            )
                        }
                    },
                )
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
                var repeatCount by remember(selectedAyah) { mutableStateOf(5) }
                val clipboard = LocalClipboardManager.current
                val sheetCtx = LocalContext.current
                ModalBottomSheet(
                    onDismissRequest = { vm.clearSelection() },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                        Text("Ayah $selSurah:$selAyah", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            selText,
                            fontFamily = quranFont,
                            fontSize = 20.sp,
                            textAlign = TextAlign.End,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        SheetAction(Icons.Filled.Mic, "Practice from here") {
                            vm.clearSelection()
                            vm.anchorToVerse(selSurah, selAyah)
                            onRequestMic {
                                vm.startRecite(
                                    selPage ?: (currentPage ?: (startIdx + 1)),
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
                        SheetAction(Icons.Filled.PlayArrow, "Listen from here (surah audio)") {
                            vm.clearSelection()
                            vm.togglePlaySurah(selSurah)
                        }
                        SheetAction(
                            if (selBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            if (selBookmarked) "Remove bookmark (p $selPage)" else "Bookmark page $selPage",
                        ) {
                            selPage?.let { vm.toggleBookmark(it) }
                        }
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
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accentColor)
        Spacer(Modifier.width(14.dp))
        Text(label, fontSize = 16.sp)
    }
}

@Composable
fun ReaderHeader(
    info: com.iqra.quran.data.SurahInfo?,
    page: Int,
    playing: Boolean,
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
                Brush.verticalGradient(
                    listOf(Chrome.HeaderTop, Chrome.HeaderMid, Color.Transparent)
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
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play recitation",
                    tint = if (playing) accentColor else Chrome.OnChrome,
                )
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

private data class WordDraw(val rect: RectF, val style: WordStyle)

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
private fun pairAyahBoxes(all: List<RectF>, slots: List<PageSlot>, words: Int): List<RectF?>? {
    // 2,190 of the DB's 88,246 rows store max_x < min_x, so the width comes
    // out negative and the size test threw the row away. Repair the box before
    // testing it. What the repair actually turns up is not lost words: 2,153
    // of the 2,190 stay under the size test either way, and the 37 that now
    // pass hold 20px of ink each against 1,111px for a real word body, i.e.
    // they are diacritics. So the swap is what makes 37 marks newly eligible,
    // and [MIN_WORD_H] is what keeps them out - without it those 37 leave 37
    // ayat one box short.
    val boxes = ArrayList<RectF>(all.size)
    for (r in all) {
        val x0 = min(r.left, r.right)
        val x1 = max(r.left, r.right)
        val y0 = min(r.top, r.bottom)
        val y1 = max(r.top, r.bottom)
        if (x1 - x0 >= MIN_WORD_BOX && y1 - y0 >= MIN_WORD_H) boxes.add(RectF(x0, y0, x1, y1))
    }
    if (boxes.isEmpty()) return null
    val body = boxes.subList(0, boxes.size - 1)
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
    for (i in seq.indices) {
        val s = seq[i]
        if (s.kind != SLOT_TOKEN || s.word >= words) continue
        val r = body[i]
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
    return out.asList()
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
): HighlightLayer = when {
    st == WordStatus.WRONG && inActiveAyah -> HighlightLayer.WRONG
    isCurrent -> HighlightLayer.RECITATION_WORD
    isSelected -> HighlightLayer.SELECTION
    isPlayHead -> HighlightLayer.AUDIO_WORD
    isPlayed -> HighlightLayer.AUDIO
    inActiveAyah -> HighlightLayer.RECITATION_AYAH
    else -> HighlightLayer.NONE
}

private fun resolveWordStyle(
    layer: HighlightLayer,
    st: WordStatus,
    hide: Boolean,
    onSurface: Color,
    background: Color,
): WordStyle {
    if (hide) {
        return when (layer) {
            HighlightLayer.AUDIO_WORD -> WordStyle(reciteBlue, Color.Transparent, reciteBlue, 0.9f, true, false)
            HighlightLayer.AUDIO -> WordStyle(reciteBlue, Color.Transparent, reciteBlue, 0.55f, false, false)
            HighlightLayer.RECITATION_WORD -> WordStyle(reciteBlue, Color.Transparent, reciteBlue, 0.9f, true, false)
            HighlightLayer.WRONG -> WordStyle(wrongColor, Color.Transparent, wrongColor, 0.6f, true, false)
            HighlightLayer.SELECTION -> WordStyle(reciteBlue, Color.Transparent, reciteBlue, 0.7f, true, false)
            HighlightLayer.RECITATION_AYAH ->
                if (st == WordStatus.CORRECT) WordStyle(onSurface, Color.Transparent, Color.Transparent, 0f, false, false)
                else WordStyle(amberColor, Color.Transparent, amberColor, 1f, false, false, hidden = true, outline = true)
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
        HighlightLayer.RECITATION_AYAH ->
            if (st == WordStatus.SKIPPED) WordStyle(
                onSurface.copy(alpha = 0.55f),
                wrongColor.copy(alpha = 0.12f),
                wrongColor,
                0.15f,
                false,
                true,
            )
            else WordStyle(onSurface, accentColor.copy(alpha = 0.10f), accentColor, 0.10f, false, false)
        HighlightLayer.NONE,
        HighlightLayer.UNSTARTED,
        -> WordStyle(onSurface, Color.Transparent, Color.Transparent, 0f, false, false)
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
    selectedAyah: String? = null,
    activeWindow: List<Int> = emptyList(),
    night: Boolean = false,
    pageInk: Color = Color(0xFF505050),
    mat: Color = Color(0xFFF4EAD3),
    chromeVisible: Boolean = true,
    onTapChrome: () -> Unit = {},
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
                    "text" -> LineText(line.words ?: emptyList(), statusMap, hide, currentKey, active, activeVerse, playOrder, playHead, selectedAyah, activeWindow)
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
    val draws = remember(
        page.page, statusMap, currentKey, playOrder, playHead, activeVerse, hide, allWords, lineGroups, selectedAyah, activeWindow, slotsByAyah,
    ) {
        buildList {
            // Join on (sura, ayah, position) — NEVER on line numbers. Mushaf
            // word lines are 0-based text ordinals while glyph lines are
            // printed line numbers, so line-equality paired every box with
            // the wrong ayah's words. Lines are ordering-only here.
            val byAyah = LinkedHashMap<String, MutableList<RectF>>()
            val orderedKeys = lineGroups.keys.mapNotNull { k ->
                val pp = k.split(":")
                if (pp.size != 3) null
                else Triple(pp[0].toInt(), pp[1].toInt(), pp[2].toInt()) to k
            }.sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third }))
            for ((_, gk) in orderedKeys) {
                val ak = gk.substringBeforeLast(":")
                byAyah.getOrPut(ak) { mutableListOf() }.addAll(lineGroups[gk] ?: emptyList())
            }
            val wordsByAyah = allWords.groupBy { "${it.surah}:${it.verse}" }
                .mapValues { (_, ws) -> ws.sortedBy { it.wordInVerse } }
            for ((ak, rectsAll) in byAyah) {
                val words = wordsByAyah[ak] ?: continue
                // One box per printed token, zipped against the layout's own
                // token list: the roundel, the ۞ and the ۩ are then excluded
                // because the layout says they are not words, never because
                // they looked small. Returns exactly words.size rects, or null
                // when the counts cannot be reconciled - see pairAyahBoxes.
                val rects = pairAyahBoxes(rectsAll, slotsByAyah[ak] ?: emptyList(), words.size)
                    ?: continue
                for (i in words.indices) {
                    // Null only if a word ended up with no token at all, which
                    // the count branches rule out; keep the guard so an
                    // unboxed word leaks rather than being drawn with a
                    // neighbour's box.
                    val rect = rects[i] ?: continue
                    val w = words[i]
                    val key = "${w.surah}:${w.verse}:${w.wordInVerse}"
                    val st = statusMap[key]
                    val isCur = key == currentKey
                    val gi = playOrder[key]
                    val isPlayed = gi != null && gi <= playHead
                    val isPlayHead = gi != null && gi == playHead
                    val inActive = if (activeWindow.isEmpty()) activeVerse != null && w.verse == activeVerse else activeWindow.contains(w.verse)
                    val layer = if (st == null) HighlightLayer.UNSTARTED
                    else resolveLayer(st, isCur, isPlayed, isPlayHead, inActive, "${w.surah}:${w.verse}" == selectedAyah)
                    val style = resolveWordStyle(layer, st ?: WordStatus.SKIPPED, hide, cs.onSurface, cs.background)
                    add(WordDraw(rect, style))
                }
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
                            // Gesture map, matching quran_android's PagerActivity:
                            //   single tap  -> toggle the chrome
                            //   long press  -> anchor the recitation lock to that ayah
                            //   double tap  -> ayah actions sheet
                            detectTapGestures(
                                onTap = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onTapChrome()
                                },
                                onLongPress = { offset ->
                                    hit(offset.x, offset.y)?.let { (s, a) ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onAnchorAyah(s, a)
                                    }
                                },
                                onDoubleTap = { offset ->
                                    hit(offset.x, offset.y)?.let { (s, a) ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onSelectAyah(s, a)
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
                        for (d in draws) {
                            if (!d.style.hidden) continue
                            val r = d.rect
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
                                val r = d.rect
                                clipRect(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy, ClipOp.Difference)
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
) {
    if (words.isEmpty()) return
    val roundelVerses = words.filter { it.isVerseEnd }.map { it.verse }
    val inlineContent = roundelVerses.associate { v ->
        "rdl_$v" to InlineTextContent(
            Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.Center),
        ) { VerseRoundel(v) }
    }
    val builder = AnnotatedString.Builder()
    words.forEachIndexed { i, w ->
        val key = "${w.surah}:${w.verse}:${w.wordInVerse}"
        val st = statusMap[key]
        val isCur = key == currentKey
        val inActiveAyah = if (activeWindow.isEmpty()) activeVerse != null && w.verse == activeVerse else activeWindow.contains(w.verse)
        val gi = playOrder[key]
        val isPlayed = gi != null && gi <= playHead
        val isPlayHead = gi != null && gi == playHead
        val cs = MaterialTheme.colorScheme
        val layer = if (st == null) HighlightLayer.UNSTARTED
        else resolveLayer(st, isCur, isPlayed, isPlayHead, inActiveAyah, "${w.surah}:${w.verse}" == selectedAyah)
        val style = resolveWordStyle(layer, st ?: WordStatus.SKIPPED, hide, cs.onSurface, cs.background)
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
        fontSize = 23.sp,
        lineHeight = 38.sp,
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
