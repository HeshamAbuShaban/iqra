package com.iqra.quran.ui

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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqra.quran.data.MushafPage
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
            MaterialTheme(colorScheme = lightMushafScheme()) {
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
private val accentColor = Color(0xFF0E7C66)
private val wrongColor = Color(0xFFB00020)
private val goldColor = Color(0xFF8A6D3B)

private fun lightMushafScheme() = lightColorScheme(
    primary = goldColor,
    secondary = Color(0xFFB89968),
    background = Color(0xFFF7EFDC),
    surface = Color(0xFFFBF5E6),
    onBackground = Color(0xFF3B2F1E),
    onSurface = Color(0xFF3B2F1E),
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
    val initial = remember(surah) { Mushaf_firstPage(mushaf, surah) }
    val pagerState = rememberPagerState(initialPage = initial, pageCount = { mushaf.size })

    LaunchedEffect(currentPage) {
        if (currentPage != null && currentPage != pagerState.currentPage) {
            pagerState.scrollToPage(currentPage!!)
        }
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
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        for (line in page.lines) {
            when (line.type) {
                "surah-header" -> SurahHeader(line.text ?: "")
                "basmala" -> Basmala()
                "text" -> {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (w in line.words ?: emptyList()) {
                            val key = "${w.surah}:${w.verse}:${w.wordInVerse}"
                            val st = statusMap[key] ?: WordStatus.SKIPPED
                            WordView(w.text, st, hide, key == currentKey, active)
                            if (w.isVerseEnd) VerseRoundel(w.verse)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WordView(text: String, st: WordStatus, hide: Boolean, isCurrent: Boolean, active: Boolean) {
    val blank = active && hide && st == WordStatus.SKIPPED
    val shown = if (blank) "ـــ" else text
    val color = when {
        isCurrent -> accentColor
        st == WordStatus.WRONG -> wrongColor
        else -> MaterialTheme.colorScheme.onSurface
    }
    val bg = if (isCurrent) accentColor.copy(alpha = 0.15f) else Color.Transparent
    Text(
        shown,
        fontFamily = quranFont,
        fontSize = 26.sp,
        color = color,
        background = bg,
        modifier = Modifier.padding(horizontal = 1.dp),
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
