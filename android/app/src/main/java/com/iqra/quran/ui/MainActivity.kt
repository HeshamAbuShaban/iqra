package com.iqra.quran.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.iqra.quran.data.Verse
import com.iqra.quran.data.WordStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val model: PracticeViewModel = viewModel()
                App(model)
            }
        }
    }
}

sealed class Screen {
    object SurahList : Screen()
    data class VerseList(val surah: Int) : Screen()
    data class Practice(val surah: Int, val ayah: Int) : Screen()
}

@Composable
fun App(model: PracticeViewModel) {
    val loading by model.loading.collectAsStateWithLifecycle()
    val data by model.data.collectAsStateWithLifecycle()

    if (loading || data == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    var stack by remember { mutableStateOf(listOf<Screen>(Screen.SurahList)) }
    val current = stack.last()
    when (current) {
        Screen.SurahList -> SurahListScreen(data!!, onSurah = { stack = stack + Screen.VerseList(it) })
        is Screen.VerseList -> VerseListScreen(
            data!!,
            current.surah,
            onVerse = { s, a -> stack = stack + Screen.Practice(s, a) },
            onBack = { stack = stack.dropLast(1) },
        )
        is Screen.Practice -> PracticeScreen(
            model,
            current.surah,
            current.ayah,
            onBack = { stack = stack.dropLast(1) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurahListScreen(data: com.iqra.quran.data.QuranData, onSurah: (Int) -> Unit) {
    val surahs = remember { data.surahList() }
    Scaffold(topBar = { TopAppBar(title = { Text("Iqra — Quran") }) }) { pad ->
        LazyColumn(modifier = Modifier.padding(pad)) {
            items(surahs) { s ->
                OutlinedButton(
                    onClick = { onSurah(s.number) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        "${s.number}. ${s.name}  (${s.nameEn}) — ${s.ayahCount} ayah",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerseListScreen(
    data: com.iqra.quran.data.QuranData,
    surah: Int,
    onVerse: (Int, Int) -> Unit,
    onBack: () -> Unit,
) {
    val verses = remember(surah) { data.getSurah(surah) }
    val info = verses.firstOrNull()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${surah}. ${info?.surahNameEn ?: ""}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(modifier = Modifier.padding(pad)) {
            itemsIndexed(verses) { _, v ->
                OutlinedButton(
                    onClick = { onVerse(v.surah, v.ayah) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${v.surah}:${v.ayah}", fontSize = 12.sp, color = Color.Gray)
                        Text(v.textUthmani, fontSize = 20.sp, textAlign = TextAlign.Start)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PracticeScreen(model: PracticeViewModel, surah: Int, ayah: Int, onBack: () -> Unit) {
    val recording by model.recording.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val result by model.result.collectAsStateWithLifecycle()
    val data by model.data.collectAsStateWithLifecycle()
    val verse: Verse? = remember(surah, ayah, data) { data?.getVerse(surah, ayah) }

    val recordLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) model.startRecording() else model.clearStatus("Microphone permission denied")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Practice $surah:$ayah") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                verse?.textUthmani ?: "",
                fontSize = 28.sp,
                textAlign = TextAlign.Center,
                lineHeight = 44.sp,
            )

            OutlinedButton(
                onClick = {
                    if (recording) {
                        model.stopAndProcess(surah, ayah)
                    } else {
                        val perm = ContextCompat.checkSelfPermission(
                            androidx.compose.ui.platform.LocalContext.current,
                            Manifest.permission.RECORD_AUDIO,
                        )
                        if (perm == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            model.startRecording()
                        } else {
                            recordLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                Text(if (recording) "■ Stop & check" else "● Recite")
            }

            Text(status, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))

            val words = result?.words
            if (!words.isNullOrEmpty()) {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    words.forEach { (word, st) ->
                        val color = when (st) {
                            WordStatus.CORRECT -> Color(0xFF2E7D32)
                            WordStatus.WRONG -> Color(0xFFE65100)
                            WordStatus.SKIPPED -> Color(0xFFC62828)
                            WordStatus.EXTRA -> Color.Gray
                        }
                        Text(
                            word,
                            fontSize = 26.sp,
                            color = color,
                            textAlign = TextAlign.Center,
                            textDecoration = if (st == WordStatus.SKIPPED) TextDecoration.LineThrough else null,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}
