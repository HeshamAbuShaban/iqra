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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            MaterialTheme(colorScheme = darkMushafScheme()) {
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

private fun darkMushafScheme() = darkColorScheme(
    primary = accentColor,
    secondary = goldColor,
    background = Color(0xFF15151A),
    surface = Color(0xFF1F1F26),
    onBackground = Color(0xFFF2E8D5),
    onSurface = Color(0xFFF2E8D5),
    onPrimary = Color(0xFF06231F),
)

@Composable
fun App(vm: PracticeViewModel, onRequestMic: (() -> Unit) -> Unit) {
    val loading by vm.loading.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val mushaf by vm.mushaf.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.Picker) }

    if (loading || data == null || mushaf == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    when (val s = screen) {
        Screen.Picker -> PickerScreen(vm) { screen = Screen.Reader(it) }
        is Screen.Reader -> ReaderScreen(
            vm = vm,
            surah = s.surah,
            onBack = { screen = Screen.Picker },
            onRequestMic = onRequestMic,
        )
    }
}

sealed interface Screen {
    data object Picker : Screen
    data class Reader(val surah: Int) : Screen
}

@Composable
fun PickerScreen(vm: PracticeViewModel, onPick: (Int) -> Unit) {
    val data = vm.data.collectAsStateWithLifecycle().value ?: return
    val surahs = remember { data.surahList() }
    var query by remember { mutableStateOf("") }
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search surah name or number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(filtered, key = { it.number }) { s ->
                Card(
                    Modifier.padding(6.dp).fillMaxWidth().clickable { onPick(s.number) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(
                        Modifier.padding(14.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            s.name,
                            fontFamily = quranFont,
                            fontSize = 24.sp,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                        Text(s.nameEn, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Text(
                            "${s.ayahCount} verses",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderScreen(
    vm: PracticeViewModel,
    surah: Int,
    onBack: () -> Unit,
    onRequestMic: (() -> Unit) -> Unit,
) {
    val mushaf = vm.mushaf.collectAsStateWithLifecycle().value ?: return
    val hide by vm.hideVerse.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val statusMap by vm.statusMap.collectAsStateWithLifecycle()
    val currentKey by vm.currentKey.collectAsStateWithLifecycle()
    val currentPage by vm.currentPage.collectAsStateWithLifecycle()
    val preparing by vm.preparing.collectAsStateWithLifecycle()
    val modelProgress by vm.modelProgress.collectAsStateWithLifecycle()

    val active = recording || statusMap.isNotEmpty()
    val startIdx = remember(surah) { Mushaf_firstPage(mushaf, surah) - 1 }
    val pagerState = rememberPagerState(initialPage = startIdx, pageCount = { mushaf.size })

    LaunchedEffect(currentPage) {
        val target = (currentPage ?: (startIdx + 1)) - 1
        if (target != pagerState.currentPage) {
            pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        vm.setCurrentPage(pagerState.currentPage + 1)
    }

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { idx ->
                MushafPageView(mushaf[idx], statusMap, hide, currentKey, active)
            }
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.toggleHide() }) {
                    Icon(
                        if (hide) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        "Hide verses",
                    )
                }
                Spacer(Modifier.width(16.dp))
                if (preparing) {
                    LinearProgressIndicator(
                        progress = { if (modelProgress < 0) 0f else modelProgress / 100f },
                        modifier = Modifier.width(140.dp),
                    )
                } else {
                    Button(
                        onClick = {
                            if (recording) vm.stopRecite()
                            else onRequestMic { vm.startRecite(surah) }
                        },
                    ) {
                        Icon(if (recording) Icons.Filled.Close else Icons.Filled.Mic, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (recording) "Stop" else "Recite")
                    }
                }
            }
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
) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        for (line in page.lines) {
            when (line.type) {
                "surah-header" -> SurahHeader(line.text ?: "")
                "basmala" -> Basmala()
                "text" -> LineText(line.words ?: emptyList(), statusMap, hide, currentKey, active)
            }
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
        val hidden = active && hide && st == WordStatus.SKIPPED
        val color = when {
            isCur -> accentColor
            st == WordStatus.WRONG -> wrongColor
            hidden -> Color.Transparent
            else -> MaterialTheme.colorScheme.onSurface
        }
        builder.pushStyle(
            SpanStyle(
                color = color,
                background = if (isCur) accentColor.copy(alpha = 0.18f) else Color.Transparent,
                fontWeight = if (isCur) FontWeight.SemiBold else FontWeight.Normal,
            ),
        )
        builder.append(if (hidden) "ـــ" else w.text)
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
        textAlign = TextAlign.Right,
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
