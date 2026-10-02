package com.iqra.quran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.data.PracticeLog
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.SurahInfo
import java.util.Calendar

/**
 * What you have practised.
 *
 * Every number here comes from [PracticeLog] aggregates, never from a stored
 * per-word verdict. That is a deliberate limit: a verdict is a judgement about
 * one attempt at one word, and showing yesterday's "WRONG" next to today's word
 * would invite reading it as a property of the word. A rate over many attempts
 * is a fair summary; a single stale label is not.
 *
 * Accuracy is shown as "no data yet" rather than 0% wherever nothing was
 * judged. Those are different facts - reciting perfectly and being recorded
 * without resolving a single word both produce an empty numerator - and only
 * one of them is a failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    data: QuranData,
    onOpen: (Int, Int) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Your practice") },
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
        Box(Modifier.fillMaxSize().padding(pad)) {
            PracticeOverview(data, onOpen)
        }
    }
}

/**
 * The practice content itself, with no Scaffold of its own.
 *
 * Split out because this is a home tab, not only a destination: hosting it
 * directly means the numbers are one tap from the surah list instead of behind
 * a back arrow, and the top-level [ProgressScreen] is then just a frame around
 * it for the case where it is opened on its own.
 */
@Composable
fun PracticeOverview(
    data: QuranData,
    onOpen: (Int, Int) -> Unit,
) {
    val ctx = LocalContext.current
    val summary = remember(ctx, data) { PracticeLog.summarise(PracticeLog.load(ctx)) }

    if (summary.sessions == 0) {
        EmptyHint(
            "Nothing recorded yet.\n\nRecite a few verses with the " +
                "microphone on and your results will appear here."
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { StreakCard(summary) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Accuracy",
                    summary.accuracy?.let { "${(it * 100).toInt()}%" } ?: "—",
                    "${summary.judgedWords} words judged",
                    Modifier.weight(1f),
                )
                StatTile(
                    "Sessions",
                    "${summary.sessions}",
                    "${summary.activeDays} active days · ${summary.ayahs} ayat",
                    Modifier.weight(1f),
                )
            }
        }

        if (summary.days.size > 1) {
            item { ActivityStrip(summary) }
        }

        item { SectionHeader("By surah", summary.surahs.size, false) }
        items(summary.surahs.size) { i ->
            val p = summary.surahs[i]
            val info: SurahInfo? = data.surahInfo(p.surah)
            SurahProgressRow(p, info) { onOpen(p.surah, info?.startPage ?: 1) }
        }

        if (summary.recent.size > 1) {
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionHeader("Recent", summary.recent.size, false) }
            items(summary.recent.size) { i ->
                val s = summary.recent[i]
                RecentRow(s, data.surahInfo(s.surah))
            }
        }
    }
}

@Composable
private fun StreakCard(s: PracticeLog.Summary) = Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
    ),
    modifier = Modifier.fillMaxWidth(),
) {
    Row(
        Modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.currentStreak > 0) {
            Icon(
                Icons.Filled.LocalFireDepartment, null,
                tint = Color(0xFFE09112),
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(
                when {
                    s.currentStreak == 0 -> "No streak running"
                    s.currentStreak == 1 -> "1 day in a row"
                    else -> "${s.currentStreak} days in a row"
                },
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "Longest run ${s.longestStreak} " +
                    if (s.longestStreak == 1) "day" else "days",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                label.uppercase(),
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                sub,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 2,
            )
        }
    }
}

/**
 * The last 14 days as a row of marks.
 *
 * A streak count says "you have not broken it"; a strip shows the shape of the
 * practice instead - whether it is every day, or four days and a gap. The strip
 * is fixed at 14 marks rather than scaled to the data so it does not silently
 * change width as the history grows.
 */
@Composable
private fun ActivityStrip(s: PracticeLog.Summary) {
    val days = remember(s.days, s.sessions) {
        val set = s.days.toSet()
        val today = PracticeLog.dayKey(System.currentTimeMillis())
        (13 downTo 0).map { back ->
            val key = shiftBack(today, back)
            key to set.contains(key)
        }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Last 14 days",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                days.forEach { (_, active) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                if (active) accentColor
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f)
                            ),
                    )
                }
            }
        }
    }
}

/** Walk back [back] local days from [today], expressed as a PracticeLog day key. */
private fun shiftBack(today: Long, back: Int): Long {
    val c = Calendar.getInstance()
    c.clear()
    c.set(
        (today / 10_000).toInt(),
        ((today / 100) % 100 - 1).toInt(),
        (today % 100).toInt(),
    )
    c.add(Calendar.DAY_OF_MONTH, -back)
    return PracticeLog.dayKey(c.timeInMillis)
}

@Composable
private fun SurahProgressRow(p: PracticeLog.SurahProgress, info: SurahInfo?, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${p.surah}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = goldColor,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        info?.nameEn ?: "Surah ${p.surah}",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        buildString {
                            append("${p.sessions} ")
                            append(if (p.sessions == 1) "session" else "sessions")
                            append(" · ${p.ayahs} ayat")
                            val a = p.accuracy
                            if (a != null) append(" · ${(a * 100).toInt()}% correct")
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
            Spacer(Modifier.height(9.dp))
            // A plain track rather than a M3 LinearProgressIndicator, whose
            // default stop indicator draws a dot on the track that reads as a
            // second, smaller value.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(p.mastery.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(accentColor),
                )
            }
        }
    }
}

@Composable
private fun RecentRow(s: PracticeLog.Session, info: SurahInfo?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${s.surah}:${s.ayahs}",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = goldColor,
            modifier = Modifier.width(56.dp),
        )
        Text(
            info?.nameEn ?: "Surah ${s.surah}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        val a = s.accuracy
        Text(
            if (a != null) "${(a * 100).toInt()}%" else "—",
            fontSize = 13.sp,
            color = if (a == null) {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            } else {
                accentColor
            },
        )
    }
}
