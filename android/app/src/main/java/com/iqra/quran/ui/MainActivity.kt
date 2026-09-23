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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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

private val quranFont = FontFamily(Font(R.font.amiri))
private val accentColor = Color(0xFF2BB6A0)
private val wrongColor = Color(0xFFE0625A)
private val goldColor = Color(0xFFD9B36B)
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

private fun lightReaderScheme() = lightColorScheme(
    primary = accentColor,
    secondary = goldColor,
    background = ParchmentScaffold,
    surface = ParchmentScaffold,
    surfaceVariant = Color(0xFFEFE6CF),
    onBackground = Color(0xFF1B1B1F),
    onSurface = Color(0xFF1B1B1F),
    onPrimary = Color(0xFF06231F),
    outline = Color(0x22000000),
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
private fun PulseDot(color: Color = wrongColor) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(750), RepeatMode.Reverse),
        label = "a",
    )
    Box(Modifier.size(10.dp).background(color.copy(alpha = a), CircleShape))
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
        Screen.Picker -> HomeScreen(vm, lastRead,
            onOpen = { surah, page -> screen = Screen.Reader(surah, page) },
            onDiag = { screen = Screen.Diag },
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
                    onBack = { screen = Screen.Picker },
                    onRequestMic = onRequestMic,
                )
            }
        }
    }
}

sealed interface Screen {
    data object Picker : Screen
    data class Reader(val surah: Int, val page: Int? = null) : Screen
    data object Diag : Screen
}

enum class HomeTab { Surahs, Juz, Bookmarks }
enum class SurahView { List, Grid }

@Composable
fun HomeScreen(
    vm: PracticeViewModel,
    lastRead: Pair<Int, Int>?,
    onOpen: (Int, Int) -> Unit,
    onDiag: () -> Unit = {},
) {
    val data = vm.data.collectAsStateWithLifecycle().value ?: return
    val buildTag = remember { vm.buildTag }
    var tab by remember { mutableStateOf(HomeTab.Surahs) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(goldColor.copy(alpha = 0.14f), Color.Transparent)))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Iqra", fontFamily = quranFont, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = goldColor)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Memorize with live recitation feedback",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Text(
                        "build $buildTag · auto feedback can err — a teacher's ear is the authority",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    )
                }
            }
        }
        HomeTabRow(tab) { tab = it }
        TextButton(onClick = onDiag, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
            Text(
                "Engine check",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        when (tab) {
            HomeTab.Surahs -> SurahIndex(vm, lastRead, data, onOpen)
            HomeTab.Juz -> JuzList(vm, data, onOpen)
            HomeTab.Bookmarks -> BookmarkList(vm, data, onOpen)
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
    var query by remember { mutableStateOf("") }
    var view by remember { mutableStateOf(SurahView.List) }
    val filtered = remember(query) {
        if (query.isBlank()) {
            surahs
        } else {
            surahs.filter {
                it.number.toString().contains(query) ||
                    it.name.contains(query) ||
                    it.nameEn.contains(query, ignoreCase = true)
            }
        }
    }
    val meccan = remember(filtered) { filtered.filter { it.revelationType == "Meccan" } }
    val madani = remember(filtered) { filtered.filter { it.revelationType == "Madani" } }
    val gridRows = remember(filtered) { filtered.chunked(3) }
    val focusManager = LocalFocusManager.current
    val continueInfo = remember(lastRead, surahs) {
        lastRead?.let { (num, page) -> surahs.firstOrNull { it.number == num }?.let { it to page } }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search surah name or number") },
                singleLine = true,
                shape = Pill,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                Icons.Filled.Close,
                                "Clear search",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            Spacer(Modifier.width(8.dp))
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
                        item { EmptyHint("No surah matches \"$query\"") }
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
@Composable
fun DiagScreen(vm: PracticeViewModel, onBack: () -> Unit) {
    val engineLabel by vm.engineLabel.collectAsStateWithLifecycle()
    val lastMatch by vm.lastMatch.collectAsStateWithLifecycle()
    val wpm by vm.wpmFlow.collectAsStateWithLifecycle()
    val gate by vm.gateReason.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val activeVerse by vm.activeVerse.collectAsStateWithLifecycle()
    val log by vm.diagLog.collectAsStateWithLifecycle()
    var snap by remember { mutableStateOf(vm.engineFilesInfo()) }
    var micDb by remember { mutableStateOf(0f) }
    var micN by remember { mutableStateOf(0) }
    var micStalled by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(recording) {
        var last = -1
        while (true) {
            micDb = vm.micLevel()
            val n = vm.micSampleCount()
            micStalled = recording && n == last && n > 0
            last = n
            micN = n
            delay(300)
        }
    }
    fun hint(): String? {
        if (snap.any { !it.present }) return "Missing engine files — push them via adb (commands on each row), then restart recitation."
        if (recording && micN > 0 && micDb < 0.005f) return "Mic delivers near-silence — check gain, distance, or another app holding the mic."
        if (micStalled) return "Mic stream frozen — stop and start recitation again."
        if (recording && gate == "silence") return "Gate hears silence — recite louder or check VAD state above."
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
                }
            }
            item {
                DiagSection("Matcher") {
                    DiagRow("Lock", activeVerse?.toString() ?: "—")
                    DiagRow("Last match", lastMatch?.let { "${it.first} @ ${"%.2f".format(it.second)}" } ?: "—")
                    DiagRow("WPM", "%.0f".format(wpm))
                    DiagRow("Gate", gate.ifEmpty { "—" })
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
    val modelProgress by vm.modelProgress.collectAsStateWithLifecycle()
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

    val surahInfo = remember(data, surah) {
        data?.surahList()?.firstOrNull { it.number == surah }
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
        vm.saveLastRead(surah, currentPage ?: (startIdx + 1))
    }
    LaunchedEffect(pagerState.currentPage) {
        vm.setCurrentPage(pagerState.currentPage + 1)
    }

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = lightReaderScheme()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { idx ->
                MushafPageView(mushaf[idx], statusMap, hide, currentKey, active, activeVerse, playIndex, playHead, onAnchorAyah = vm::anchorToVerse, onSelectAyah = { s, a -> vm.selectAyah(s, a) }, selectedAyah = selectedAyah, activeWindow = activeWindow)
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
            )
            // Floating recitation bar
            Surface(
                shape = Pill,
                color = Chrome.Bar,
                shadowElevation = 10.dp,
                modifier = Modifier.align(Alignment.BottomCenter)
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
                        PulseDot()
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (engineLabel.isNotEmpty()) "Listening… · $engineLabel" else "Listening…",
                            fontSize = 12.sp,
                            color = Chrome.OnChromeMuted,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            if (recording) vm.stopRecite()
                            else onRequestMic { vm.startRecite(surah, currentPage ?: (startIdx + 1)) }
                        },
                        shape = Pill,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        if (preparing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            if (modelProgress in 0..99) {
                                Spacer(Modifier.width(6.dp))
                                Text("$modelProgress%", fontSize = 12.sp)
                            }
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
                            onRequestMic { vm.startRecite(selSurah, selPage ?: (currentPage ?: (startIdx + 1))) }
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
) {
    Box(
        Modifier.fillMaxWidth()
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
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 6.dp, start = 56.dp, end = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                info?.name ?: "",
                fontFamily = quranFont,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = goldColor,
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
                    val name = "pages/%03d.png".format(page.page)
                    ctx.assets.open(name).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
                        ?.also { PageImageCache.put(page.page, it) }
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
    val cs = MaterialTheme.colorScheme
    val draws = remember(
        page.page, statusMap, currentKey, playOrder, playHead, activeVerse, hide, allWords, lineGroups, selectedAyah, activeWindow,
    ) {
        buildList {
            for ((groupKey, rects) in lineGroups) {
                val pp = groupKey.split(":")
                val s = pp[0].toInt(); val a = pp[1].toInt(); val l = pp[2].toInt()
                val lineWords = allWords.filter { it.line == l && it.surah == s && it.verse == a }
                val n = lineWords.size; val m = rects.size
                for (i in 0 until n) {
                    val rect: RectF = when {
                        m == n -> rects[i]
                        m == n + 1 -> rects[i]
                        i < m -> rects[i]
                        else -> null
                    } ?: continue
                    val w = lineWords[i]
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
                Box(
                    Modifier.width(pageW).height(imgH)
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
                            detectTapGestures(
                                onTap = { offset ->
                                    hit(offset.x, offset.y)?.let { (s, a) ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onAnchorAyah(s, a)
                                    }
                                },
                                onLongPress = { offset ->
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
                    // Hide mode: two-pass like quran_android's HighlightingImageView.
                    // Pass 1 clips every hidden word rect OUT, then draws the page,
                    // so holes show the parchment scaffold = truly invisible text,
                    // never a white cover. Ayah markers and surah headers are not
                    // word rects, so they stay printed as indicators.
                    val dst = IntSize(size.width.toInt(), size.height.toInt())
                    if (hide) {
                        // Clip hidden word rects OUT, then draw the page: those
                        // pixels are never drawn (holes show the parchment),
                        // then overlays draw unclipped below. Markers/headers
                        // are not word rects, so they stay printed.
                        withTransform({
                            for (d in draws) {
                                if (!d.style.hidden) continue
                                val r = d.rect
                                clipRect(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy, ClipOp.Difference)
                            }
                        }) {
                            drawImage(bmp, dstSize = dst)
                        }
                    } else {
                        drawImage(bmp, dstSize = dst)
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
                    if (!hide) {
                        playOrder.entries.firstOrNull { it.value == playHead }?.key
                            ?.let { drawPlayHeadWord(it, allWords, lineGroups, sx, sy) }
                    }
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

private fun DrawScope.drawPlayHeadWord(
    key: String,
    allWords: List<MushafWord>,
    lineGroups: Map<String, List<RectF>>,
    sx: Float,
    sy: Float,
) {
    val w = allWords.firstOrNull { "${it.surah}:${it.verse}:${it.wordInVerse}" == key } ?: return
    val lineKey = "${w.surah}:${w.verse}:${w.line}"
    val rects = lineGroups[lineKey] ?: return
    val lineWords = allWords.filter { it.line == w.line && it.surah == w.surah && it.verse == w.verse }
    val idx = lineWords.indexOf(w)
    if (idx < 0) return
    val r = if (idx < rects.size) rects[idx] else rects.last()
    drawRect(
        goldColor,
        Offset(r.left * sx, r.top * sy),
        Size(r.width() * sx, r.height() * sy),
        alpha = 0.55f,
    )
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
                color = style.fg,
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
