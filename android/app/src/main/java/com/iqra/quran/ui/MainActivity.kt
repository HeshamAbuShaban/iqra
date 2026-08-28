package com.iqra.quran.ui

import com.iqra.quran.R
import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqra.quran.data.MushafPage
import com.iqra.quran.data.MushafWord
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
fun App(vm: PracticeViewModel, onRequestMic: (() -> Unit) -> Unit) {
    val loading by vm.loading.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val mushaf by vm.mushaf.collectAsStateWithLifecycle()
    val lastRead by vm.lastRead.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.Picker) }

    if (loading || data == null || mushaf == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = accentColor)
        }
        return
    }
    when (val s = screen) {
        Screen.Picker -> HomeScreen(vm, lastRead) { surah, page -> screen = Screen.Reader(surah, page) }
        is Screen.Reader -> ReaderScreen(
            vm = vm,
            surah = s.surah,
            startPage = s.page,
            onBack = { screen = Screen.Picker },
            onRequestMic = onRequestMic,
        )
    }
}

sealed interface Screen {
    data object Picker : Screen
    data class Reader(val surah: Int, val page: Int? = null) : Screen
}

enum class HomeTab { Surahs, Juz, Bookmarks }
enum class SurahView { List, Grid }

@Composable
fun HomeScreen(
    vm: PracticeViewModel,
    lastRead: Pair<Int, Int>?,
    onOpen: (Int, Int) -> Unit,
) {
    val data = vm.data.collectAsStateWithLifecycle().value ?: return
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
                Text(
                    "Memorize with live recitation feedback",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
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
fun HomeTabRow(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    val tabs = HomeTab.values()
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        tabs.forEach { t ->
            val sel = t == selected
            Box(
                Modifier.weight(1f).padding(4.dp).clip(Pill)
                    .background(if (sel) goldColor.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(t) }.padding(vertical = 10.dp),
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
    val meccan = filtered.filter { it.revelationType == "Meccan" }
    val madani = filtered.filter { it.revelationType == "Madani" }
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
                    items(filtered.chunked(3), key = { it.first().number }) { row ->
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
                    "${info.nameEn}  ·  Page $page",
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
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
    ) {
        items(juz, key = { it.number }) { j ->
            val surah = data.surahAtPage(j.startPage)
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
                            "Starts at page ${j.startPage}" + if (j.surahNameEn.isNotEmpty()) " · ${j.surahNameEn}" else "",
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
    if (sorted.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No bookmarks yet.\nTap the bookmark icon while reading to save a page.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
    ) {
        items(sorted, key = { it }) { page ->
            val info = data.surahAtPage(page)
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
                }
            }
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

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
    val preparing by vm.preparing.collectAsStateWithLifecycle()
    val modelProgress by vm.modelProgress.collectAsStateWithLifecycle()
    val activeVerse by vm.activeVerse.collectAsStateWithLifecycle()
    val recognized by vm.recognizedText.collectAsStateWithLifecycle()
    val playIndex by vm.playIndex.collectAsStateWithLifecycle()
    val playHead by vm.playHead.collectAsStateWithLifecycle()
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
            pagerState.scrollToPage(target)
        }
        vm.saveLastRead(surah, currentPage ?: (startIdx + 1))
    }
    LaunchedEffect(pagerState.currentPage) {
        vm.setCurrentPage(pagerState.currentPage + 1)
    }

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { idx ->
                MushafPageView(mushaf[idx], statusMap, hide, currentKey, active, activeVerse, playIndex, playHead)
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
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 8.dp,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp, start = 16.dp, end = 16.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.toggleHide() }) {
                        Icon(
                            if (hide) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            "Hide verses",
                            tint = if (hide) accentColor else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { showGoto = true }) {
                        Icon(
                            Icons.Filled.Search,
                            "Go to page",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (recording) vm.stopRecite()
                            else onRequestMic { vm.startRecite(surah, currentPage ?: (startIdx + 1)) }
                        },
                        shape = Pill,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        if (preparing) {
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
                    if (recognized.isNotEmpty()) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            recognized,
                            fontSize = 13.sp,
                            fontFamily = quranFont,
                            color = accentColor,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (showGoto) {
                AlertDialog(
                    onDismissRequest = { showGoto = false },
                    confirmButton = {
                        TextButton(onClick = {
                            val p = gotoText.toIntOrNull()
                            if (p != null && p in 1..mushaf.size) {
                                vm.jumpToPage(p)
                                gotoText = ""
                                showGoto = false
                            }
                        }) { Text("Go") }
                    },
                    dismissButton = { TextButton(onClick = { showGoto = false }) { Text("Cancel") } },
                    title = { Text("Go to page") },
                    text = {
                        OutlinedTextField(
                            value = gotoText,
                            onValueChange = { gotoText = it.filter { c -> c.isDigit() }.take(3) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            placeholder = { Text("1 – ${mushaf.size}") },
                        )
                    },
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
    bookmarked: Boolean,
    onPlayToggle: () -> Unit,
    onToggleBookmark: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                        Color.Transparent,
                    )
                )
            )
            .padding(top = 8.dp, bottom = 18.dp),
    ) {
        IconButton(onClick = onBack, Modifier.align(Alignment.TopStart).padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                tint = MaterialTheme.colorScheme.onBackground)
        }
        Row(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = onToggleBookmark) {
                Icon(
                    if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    "Bookmark page",
                    tint = if (bookmarked) goldColor else MaterialTheme.colorScheme.onBackground,
                )
            }
            IconButton(onClick = onPlayToggle) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play recitation",
                    tint = if (playing) accentColor else MaterialTheme.colorScheme.onBackground,
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
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

fun Mushaf_firstPage(pages: List<MushafPage>, surah: Int): Int =
    com.iqra.quran.data.Mushaf.firstPageOfSurah(pages, surah)

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
) {
    val ctx = LocalContext.current
    val glyphs = remember(page.page) {
        GlyphCoords.ensure(ctx)
        GlyphCoords.lineBoxes(page.page)
    }
    val bmp = remember(page.page) {
        try {
            ctx.assets.open("pages/${page.page}.webp").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        } catch (e: Exception) {
            null
        }
    }
    if (bmp == null) {
        // Fallback to synthetic text if the page image asset is missing.
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 56.dp),
        ) {
            for (line in page.lines) {
                when (line.type) {
                    "surah-header" -> SurahHeader(line.text ?: "")
                    "basmala" -> Basmala()
                    "text" -> LineText(line.words ?: emptyList(), statusMap, hide, currentKey, active, activeVerse, playOrder, playHead)
                }
            }
        }
        return
    }

    val allWords = remember(page.page) { page.lines.flatMap { it.words ?: emptyList() } }
    val activeKey = allWords.firstOrNull { it.verse == activeVerse }?.let { "${it.surah}:${it.verse}" }
        ?: currentKey?.let { it.substringBeforeLast(":").substringBeforeLast(":") }?.let { "${it.split(":")[0]}:${it.split(":")[1]}" }
    val currentAyahKey = currentKey?.let { val p = it.split(":"); "${p[0]}:${p[1]}" }
    val playedAyahKeys = remember(playOrder, playHead) {
        playOrder.filterValues { it <= playHead }.keys.map { val p = it.split(":"); "${p[0]}:${p[1]}" }.toSet()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(Modifier.align(Alignment.Center).fillMaxWidth()) {
            val imgH = maxWidth * 1053f / 776f
            Box(Modifier.width(maxWidth).height(imgH)) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                Canvas(Modifier.fillMaxSize()) {
                    val sx = size.width / 776f
                    val sy = size.height / 1053f
                    // 1) Ayah/line-level highlights (accurate boxes from the DB).
                    for ((ayahKey, boxes) in glyphs) {
                        val isActive = ayahKey == activeKey
                        val isCurrent = ayahKey == currentAyahKey
                        val isPlayed = ayahKey in playedAyahKeys
                        val (fill, alpha) = when {
                            hide && (isActive || isPlayed) -> reciteBlue to 0.18f
                            hide -> PAGE_MASK to 1f
                            isCurrent -> accentColor to 0.40f
                            isPlayed -> goldColor to 0.28f
                            isActive -> accentColor to 0.14f
                            else -> Color.Transparent to 0f
                        }
                        if (alpha > 0f) {
                            for ((_, r) in boxes) {
                                drawRect(fill, Offset(r.left * sx, r.top * sy), Size(r.width() * sx, r.height() * sy), alpha = alpha)
                            }
                        }
                    }
                    // 2) Word-level subdivision for the CURRENT (teal) and play-head
                    //    (gold) words, so live progress lands on the exact word.
                    if (!hide) {
                        currentKey?.let { drawWordSegment(it, allWords, glyphs, sx, sy, accentColor, 0.55f) }
                    }
                    playOrder.entries.firstOrNull { it.value == playHead }?.key
                        ?.let { drawWordSegment(it, allWords, glyphs, sx, sy, goldColor, 0.5f) }
                }
            }
        }
    }
}

private fun DrawScope.drawWordSegment(
    key: String,
    allWords: List<MushafWord>,
    glyphs: Map<String, List<Pair<Int, android.graphics.RectF>>>,
    sx: Float,
    sy: Float,
    color: Color,
    alpha: Float,
) {
    val word = allWords.firstOrNull { "${it.surah}:${it.verse}:${it.wordInVerse}" == key } ?: return
    val ayahKey = "${word.surah}:${word.verse}"
    val boxes = glyphs[ayahKey] ?: return
    val lineBox = boxes.firstOrNull { it.first == word.line }?.second ?: boxes.first().second
    val lineWords = allWords.filter { it.line == word.line }
    if (lineWords.isEmpty()) return
    val weights = lineWords.map { w -> (w.text.count { ch -> ch.isLetter() }.toFloat()).coerceAtLeast(0.5f) }
    val total = weights.sum()
    val width = lineBox.width()
    var xRight = lineBox.right
    for (i in lineWords.indices) {
        val segW = width * weights[i] / total
        val xLeft = xRight - segW
        if (lineWords[i] == word) {
            drawRect(color, Offset(xLeft * sx, lineBox.top * sy), Size(segW * sx, lineBox.height() * sy), alpha = alpha)
            return
        }
        xRight = xLeft
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
        val st = statusMap[key] ?: WordStatus.SKIPPED
        val isCur = key == currentKey
        val inActiveAyah = activeVerse != null && w.verse == activeVerse
        val gi = playOrder[key]
        val isPlayed = gi != null && gi <= playHead
        val isPlayHead = gi != null && gi == playHead

        // HIDE MODE: only the ACTIVE ayah is revealed in blue. Everything else
        // is blanked (real text drawn in bg color -> width kept, roundels fixed).
        // While the reference audio plays, already-sung words are revealed too.
        val (color, bg) = if (hide) {
            when {
                inActiveAyah && st == WordStatus.WRONG -> reciteBlue to wrongColor.copy(alpha = 0.22f)
                inActiveAyah -> reciteBlue to Color.Transparent
                isPlayed -> reciteBlue to Color.Transparent
                else -> MaterialTheme.colorScheme.background to Color.Transparent
            }
        } else {
            // NORMAL MODE: light highlight over the WHOLE active ayah (we are
            // here) + stronger follow-highlight on the current word (teal = YOUR
            // recitation). The reference-audio follow-along is GOLD (teal != gold,
            // so the two modes never confuse).
            val background = when {
                st == WordStatus.WRONG -> wrongColor.copy(alpha = 0.22f)
                inActiveAyah && isCur -> accentColor.copy(alpha = 0.32f)
                inActiveAyah -> accentColor.copy(alpha = 0.14f)
                isPlayHead -> goldColor.copy(alpha = 0.18f)
                else -> Color.Transparent
            }
            val fg = when {
                st == WordStatus.WRONG -> wrongColor
                isCur -> accentColor
                isPlayed -> goldColor
                else -> MaterialTheme.colorScheme.onSurface
            }
            fg to background
        }
        builder.pushStyle(
            SpanStyle(
                color = color,
                background = bg,
                fontWeight = if ((isCur && !hide) || isPlayHead) FontWeight.SemiBold else FontWeight.Normal,
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
