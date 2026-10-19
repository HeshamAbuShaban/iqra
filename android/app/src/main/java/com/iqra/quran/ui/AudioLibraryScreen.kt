package com.iqra.quran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.data.AudioStore
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.Reciters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AudioSort(val label: String) {
    Surah("Surah"), Reciter("Reciter"), Newest("Newest"), Largest("Largest")
}

/**
 * Everything the offline-audio setting has actually saved, with the two
 * decisions the user asked to be able to make about it: what to play, and
 * what to keep. Playing a row seats that row's reciter first - the file and
 * the reciter are the same choice here, and silently playing a different
 * reciter than the row names would make the screen a lie.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AudioLibraryScreen(
    onBack: () -> Unit,
    onPlay: (surah: Int, reciterId: String) -> Unit,
) {
    val ctx = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var sort by remember { mutableStateOf(AudioSort.Surah) }
    var confirmDeleteEntry by remember { mutableStateOf<AudioStore.Entry?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val entries by produceState(emptyList<AudioStore.Entry>(), refresh) {
        value = withContext(Dispatchers.IO) { AudioStore.list(ctx) }
    }
    val quran by produceState<QuranData?>(null) {
        value = QuranData.load(ctx)
    }
    val sorted = remember(entries, sort) {
        when (sort) {
            AudioSort.Surah -> entries.sortedWith(compareBy({ it.surah }, { it.reciterId }))
            AudioSort.Reciter -> entries.sortedWith(compareBy({ it.reciterId }, { it.surah }))
            AudioSort.Newest -> entries.sortedByDescending { it.modified }
            AudioSort.Largest -> entries.sortedByDescending { it.bytes }
        }
    }
    val totalBytes = entries.sumOf { it.bytes }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Downloaded audio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            if (entries.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Nothing saved yet. Press play on a surah and the audio " +
                        "saves itself here, so replaying it never downloads " +
                        "it a second time.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            } else {
                Text(
                    "${entries.size} surahs · ${fmtLibBytes(totalBytes)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AudioSort.entries.forEach { s ->
                        val on = s == sort
                        Text(
                            s.label,
                            fontSize = 12.sp,
                            color = if (on) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { sort = s }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                    Text(
                        "Delete all",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { confirmDeleteAll = true }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(sorted, key = { it.reciterId + ":" + it.surah }) { e ->
                        val name = quran?.surahInfo(e.surah)?.nameEn ?: "Surah ${e.surah}"
                        val reciter = Reciters.byId(e.reciterId)
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { onPlay(e.surah, e.reciterId) }) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    "Play $name",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    name,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${reciter.name} · ${fmtLibBytes(e.bytes)} · " +
                                        fmtLibDate(e.modified),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { confirmDeleteEntry = e }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    "Delete $name",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    confirmDeleteEntry?.let { e ->
        val name = quran?.surahInfo(e.surah)?.nameEn ?: "Surah ${e.surah}"
        AlertDialog(
            onDismissRequest = { confirmDeleteEntry = null },
            title = { Text("Delete $name?") },
            text = {
                Text(
                    "${fmtLibBytes(e.bytes)} will be removed. Playing the surah " +
                        "again will stream it and save a fresh copy.",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AudioStore.delete(ctx, e)
                    confirmDeleteEntry = null
                    refresh++
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteEntry = null }) { Text("Keep") }
            },
        )
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all ${entries.size} saved surahs?") },
            text = {
                Text(
                    "${fmtLibBytes(totalBytes)} will be removed. A surah played " +
                        "afterwards streams and saves again.",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    AudioStore.deleteAll(ctx)
                    confirmDeleteAll = false
                    refresh++
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("Keep") }
            },
        )
    }
}

private fun fmtLibBytes(b: Long): String =
    if (b >= 1_000_000) "%.1f MB".format(b / 1_000_000.0)
    else "%.0f KB".format(b / 1000.0)

private fun fmtLibDate(ms: Long): String =
    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))
