package com.iqra.quran.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            MaterialTheme(
                colorScheme = lightMushafScheme()
            ) {
                App(vm,
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

private fun lightMushafScheme() = lightColorScheme(
    primary = Color(0xFF8A6D3B),
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
    var screen by remember { mutableStateOf<Screen>(Screen.List) }

    if (loading || data == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val q = data!!
    when (val s = screen) {
        Screen.List -> SurahList(q, onPick = { screen = Screen.Reader(it) })
        is Screen.Reader -> ReaderScreen(
            vm = vm,
            surah = s.surah,
            onBack = { screen = Screen.List },
            onRequestMic = onRequestMic,
        )
    }
}

sealed interface Screen {
    data object List : Screen
    data class Reader(val surah: Int) : Screen
}

@Composable
fun SurahList(q: com.iqra.quran.data.QuranData, onPick: (Int) -> Unit) {
    val surahs = remember { q.surahList() }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Iqra · القرآن") }) }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp)) {
            items(surahs) { s ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .clickable { onPick(s.number) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${s.number}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(40.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(s.nameEn, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${s.ayahCount} verses",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Text(s.name, fontSize = 22.sp, textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f))
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
    val data by vm.data.collectAsStateWithLifecycle()
    val hide by vm.hideVerse.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val focused by vm.focusedVerse.collectAsStateWithLifecycle()
    val displayWords by vm.displayWords.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val preparing by vm.preparing.collectAsStateWithLifecycle()
    val modelProgress by vm.modelProgress.collectAsStateWithLifecycle()

    val q = data ?: return
    val pages = remember(surah) { buildPages(q, surah) }
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { pages.size })

    val practiceVerse = focused

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val s = q.surahList().first { it.number == surah }
                    Text("${s.number}. ${s.nameEn}")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    Text(
                        "Page ${pagerState.currentPage + 1}/${pages.size}",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
            )
        },
        bottomBar = {
            BottomAppBar(containerColor = MaterialTheme.colorScheme.surface) {
                IconButton(onClick = { vm.toggleHide() }) {
                    Icon(
                        if (hide) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        "Hide verses"
                    )
                }
                Spacer(Modifier.weight(1f))
                if (preparing) {
                    LinearProgressIndicator(
                        progress = { if (modelProgress < 0) 0f else modelProgress / 100f },
                        modifier = Modifier.fillMaxWidth(0.5f)
                    )
                } else {
                    Button(
                        onClick = {
                            val target = practiceVerse ?: (surah to pages[pagerState.currentPage].first())
                            if (recording) {
                                vm.stopAndProcess()
                            } else {
                                onRequestMic {
                                    vm.startRecording(target.first, target.second)
                                }
                            }
                        },
                        enabled = !preparing,
                    ) {
                        Icon(
                            if (recording) Icons.Filled.Close else Icons.Filled.Mic,
                            null
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (recording) "Stop" else "Recite")
                    }
                }
            }
        }
    ) { pad ->
        if (practiceVerse != null) {
            PracticeView(
                vm = vm,
                surah = practiceVerse!!.first,
                ayah = practiceVerse!!.second,
                modifier = Modifier.padding(pad).fillMaxSize(),
                onExit = { vm.selectVerse(-1, -1) }
            )
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.padding(pad).fillMaxSize()
            ) { page ->
                val ayahs = pages[page]
                val showHeader = ayahs.first() == 1
                MushafPage(
                    q = q,
                    surah = surah,
                    ayahs = ayahs,
                    showHeader = showHeader,
                    onVerseClick = { a -> vm.selectVerse(surah, a) }
                )
            }
        }
        if (status.isNotEmpty() && !recording) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.BottomCenter) {
                Surface(
                    tonalElevation = 4.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(status, Modifier.padding(12.dp))
                }
            }
        }
    }
}

@Composable
fun MushafPage(
    q: com.iqra.quran.data.QuranData,
    surah: Int,
    ayahs: List<Int>,
    showHeader: Boolean,
    onVerseClick: (Int) -> Unit,
) {
    val s = q.surahList().first { it.number == surah }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        if (showHeader) {
            SurahHeader(s.name, s.number)
            Spacer(Modifier.height(10.dp))
        }
        val annotated = buildAnnotatedString {
            for (a in ayahs) {
                pushStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold))
                append(" ﴿${a}﴾ ")
                pop()
                append(inlineWords(q, surah, a))
                append("  ")
            }
        }
        Text(
            annotated,
            fontSize = 26.sp,
            lineHeight = 44.sp,
            textAlign = TextAlign.Right,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Tappable verse-number strip for selecting a verse to practice
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().wrapContentHeight(), horizontalArrangement = Arrangement.Center) {
            ayahs.forEach { a ->
                Box(
                    Modifier.padding(4.dp).size(34.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f))
                        .clickable { onVerseClick(a) },
                    contentAlignment = Alignment.Center
                ) {
                    Text("$a", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private fun inlineWords(q: com.iqra.quran.data.QuranData, surah: Int, ayah: Int): String {
    return q.verseText(surah, ayah)
}

@Composable
fun SurahHeader(nameArabic: String, index: Int) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("﷽", fontSize = 30.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            "سورة $nameArabic",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth(0.6f).height(2.dp)
                .background(MaterialTheme.colorScheme.secondary)
        )
    }
}

@Composable
fun PracticeView(
    vm: PracticeViewModel,
    surah: Int,
    ayah: Int,
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
) {
    val data by vm.data.collectAsStateWithLifecycle()
    val hide by vm.hideVerse.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val displayWords by vm.displayWords.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val q = data ?: return

    val words = remember(surah, ayah, hide) {
        vm.verseWords(surah, ayah)
    }

    Column(modifier.padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Verse $surah:$ayah", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onExit) { Text("Done") }
        }
        Spacer(Modifier.height(18.dp))

        // Big centered verse with Tarteel-style live follow-along + mistake colors.
        val accent = Color(0xFF0E7C66)
        val currentIdx = if (recording) displayWords.indexOfLast { it.status != WordStatus.SKIPPED } else -1
        val annotated = buildAnnotatedString {
            pushStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold))
            append("﴿$ayah﴾  ")
            pop()
            words.forEachIndexed { i, w ->
                val st = displayWords.getOrNull(i)?.status ?: WordStatus.CORRECT
                val isCurrent = i == currentIdx
                val blank = recording && hide && st == WordStatus.SKIPPED
                val text = if (blank) "ـــ" else w
                val color = when {
                    st == WordStatus.WRONG -> Color(0xFFB00020)
                    st == WordStatus.SKIPPED && !recording -> Color(0xFF777777)
                    isCurrent -> accent
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val bg = if (isCurrent) accent.copy(alpha = 0.15f) else Color.Transparent
                pushStyle(
                    SpanStyle(
                        color = color,
                        background = bg,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    )
                )
                append("$text ")
                pop()
            }
        }
        Text(
            annotated,
            fontSize = 32.sp,
            lineHeight = 52.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))

        if (result != null && !recording) {
            val r = result!!
            val correct = r.words.count { it.second == WordStatus.CORRECT }
            val wrong = r.words.count { it.second == WordStatus.WRONG }
            val skipped = r.words.count { it.second == WordStatus.SKIPPED }
            val total = r.words.size.coerceAtLeast(1)
            Surface(
                tonalElevation = 3.dp, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Result", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("✓ correct: $correct", color = Color(0xFF2E7D32))
                    Text("✗ wrong: $wrong", color = Color(0xFFB00020))
                    Text("⊘ skipped: $skipped", color = Color(0xFF777777))
                    Text("Accuracy: ${(correct * 100 / total)}%")
                    if (r.match != null) {
                        Text("Detected verse: ${r.match.surah}:${r.match.ayah}  (${(r.match.score * 100).toInt()}%)",
                            fontSize = 13.sp)
                    }
                }
            }
        }

        if (recording) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    color = Color(0xFFB00020).copy(alpha = 0.12f),
                    shape = CircleShape, modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Mic, "recording", tint = Color(0xFFB00020))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (hide) "Recite — words reveal as you say them" else "Recite — tap Stop when done",
                Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
        }
    }
}

/** Paginate a surah into pages by accumulated word count (≈ fills a screen). */
fun buildPages(q: com.iqra.quran.data.QuranData, surah: Int): List<List<Int>> {
    val counts = (1..q.surahList().first { it.number == surah }.ayahCount).map { a ->
        a to q.getWordTokens(surah, a).size
    }
    val pages = mutableListOf<MutableList<Int>>()
    var cur = mutableListOf<Int>()
    var total = 0
    val threshold = 120
    for ((a, w) in counts) {
        if (cur.isEmpty()) { cur.add(a); total = w; continue }
        if (total + w > threshold) {
            pages.add(cur); cur = mutableListOf(a); total = w
        } else {
            cur.add(a); total += w
        }
    }
    if (cur.isNotEmpty()) pages.add(cur)
    return pages
}
