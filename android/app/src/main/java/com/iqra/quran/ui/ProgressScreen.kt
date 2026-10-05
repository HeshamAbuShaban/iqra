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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.data.PracticeLog
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.SurahInfo
import android.content.Context
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
 * Load the durable aggregate off the composition thread.
 *
 * `summarise` reads and folds session records: directory listing, JSON parse of
 * files that are up to half a megabyte each, and a rewrite of the aggregate. None
 * of that belongs on the thread that is laying out a list. It is also why the
 * result is a nullable state rather than a `remember` of the value - `remember`
 * computes during composition, which is precisely the wrong place.
 *
 * Keyed on [refresh] so clearing the history, or coming back from a recitation,
 * re-reads. `data` is in the key because a surah list change means the screen
 * is showing something new.
 */
@Composable
private fun rememberSummary(
    ctx: Context,
    data: QuranData,
    refresh: Any? = null,
): State<PracticeLog.Summary?> {
    val state = remember(ctx, data, refresh) { mutableStateOf<PracticeLog.Summary?>(null) }
    LaunchedEffect(ctx, data, refresh) {
        state.value = withContext(Dispatchers.IO) { PracticeLog.summarise(ctx) }
    }
    return state
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
    // Read `.value` into a local rather than using `by`: a delegated property
    // is not smart-cast in Kotlin, so `if (summary == null) return` would leave
    // every later use nullable and force `!!` all down the composable. A local
    // val smart-casts normally, which is the whole point of checking once.
    val loaded = rememberSummary(ctx, data).value

    if (loaded == null) {
        // A spinner, not an empty state. The empty state is a CLAIM - "you have
        // not recited anything" - and showing it before the read finishes says
        // that to someone who has recited plenty. Which is exactly what it did:
        // the first version read the files synchronously inside `remember`, on
        // the composition thread, and rendered its "no data" branch for a frame
        // before the numbers arrived.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(strokeWidth = 2.dp)
        }
        return
    }
    val s = loaded

    if (s.isEmpty) {
        EmptyHint(
            "Nothing recorded yet.\n\nRecite a few verses with the " +
                "microphone on and your results will appear here."
        )
        return
    }

    // Sessions exist but not one word was judged. That is not an empty state and
    // must not be dressed as one: it means the engine moved and recorded, and
    // the verdicts were lost before they were written. Saying "nothing recorded
    // yet" here would blame the user for our bug, and saying nothing at all
    // would leave them staring at zeros.
    val recordedButSilent = s.sessions > 0 && s.judgedWords == 0

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { StreakCard(s) }

        if (recordedButSilent) {
            item { SilentRecordCard(s) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(
                    "Accuracy",
                    s.accuracy?.let { "${(it * 100).toInt()}%" } ?: "—",
                    "${s.judgedWords} words judged",
                    Modifier.weight(1f),
                )
                StatTile(
                    "Sessions",
                    "${s.sessions}",
                    "${s.activeDays} active days · ${s.ayahs} ayat",
                    Modifier.weight(1f),
                )
            }
        }

        if (s.days.size > 1) {
            item { ActivityStrip(s) }
        }

        item { SectionHeader("By surah", s.surahs.size, false) }
        items(s.surahs.size) { i ->
            val p = s.surahs[i]
            val info: SurahInfo? = data.surahInfo(p.surah)
            SurahProgressRow(p, info) { onOpen(p.surah, info?.startPage ?: 1) }
        }

        if (s.hardest.isNotEmpty()) {
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionHeader("Hardest words", s.hardest.size, false) }
            items(s.hardest.size) { i -> HardWordRow(s.hardest[i], data) }
        }

        if (s.recent.size > 1) {
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionHeader("Recent", s.recent.size, false) }
            items(s.recent.size) { i ->
                val s = s.recent[i]
                RecentRow(s, data.surahInfo(s.surahStart))
            }
        }
    }
}

/**
 * Says the true thing when sessions were recorded but no verdict survived.
 *
 * Every field here comes from the engine's own record, so this is a statement
 * about what happened rather than a guess: the lock moved, frames were kept, and
 * the per-word array came back empty. The cause is known and is not the user's
 * fault - `PracticeViewModel.loadSurah` clears the verdict map, and a surah
 * handoff is exactly when a session ends - so the card names it instead of
 * suggesting they recite differently.
 */
@Composable
private fun SilentRecordCard(s: PracticeLog.Summary) {
    val moves = s.recent.sumOf { it.moves }
    val evals = s.recent.sumOf { it.evaluations }
    val unjudged = s.recent.sumOf { it.unjudgeable }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "${s.sessions} sessions recorded, 0 words judged",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "The lock moved $moves times over $evals evaluations, so the " +
                    "recitation was heard. The word-level results were lost " +
                    "before they could be written - a known defect, not " +
                    "something you did. $unjudged ayat were unjudgeable in " +
                    "total.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * The words that keep coming out wrong, with somewhere to go from each one.
 *
 * This is the list that changes behaviour: a surah-level accuracy figure tells
 * a user they are "at 87%", which is true and useless. "2:255 word 12, four
 * times" tells them what to practise. The engine's `words` array holds only
 * WRONG and UNKNOWN by design, which is exactly the set this is made of.
 */
@Composable
private fun HardWordRow(w: PracticeLog.HardWord, data: QuranData) {
    // The word is looked up in the verse's own text, not in the mushaf page
    // model, and the index is bounds-checked. Showing the WRONG word is the one
    // failure this app exists to prevent, so an index that does not land inside
    // the verse falls back to the reference rather than guessing. `wordInAyah`
    // is the mushaf's own word index; where the two segmentations disagree the
    // guard is what keeps the row honest.
    val word = remember(w.key) {
        val text = data.getVerse(w.surah, w.ayah)?.textClean
        val parts = text?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
        parts.getOrNull(w.wordInAyah - 1)
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                word ?: "ayah ${w.ayah}, word ${w.wordInAyah}",
                fontFamily = quranFont,
                fontSize = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${w.surah}:${w.ayah}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        // Misses as discrete pips rather than a number: the question is "how bad
        // is this", and three marks read faster than "3" and do not pretend to
        // be a precise quantity.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(minOf(w.misses, 5)) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(wrongColor.copy(alpha = 0.85f))
                )
            }
            if (w.misses > 5) {
                Text(
                    "+${w.misses - 5}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
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
        val today = PracticeLog.dayKeyOf(System.currentTimeMillis())
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
    return PracticeLog.dayKeyOf(c.timeInMillis)
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
                            // `reached` is distinct ayat in this surah, which is
                            // what the number always claimed to be. `ayahs` was a
                            // sum of max-ayah-NUMBERs, so someone reciting 2:255
                            // twenty times saw "20 sessions · 255 ayat".
                            if (p.reached > 0) append(" · ${p.reached} ayat")
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
private fun RecentRow(s: PracticeLog.Record, info: SurahInfo?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // Only when start and current surah agree - otherwise the pair is a
            // position that does not exist. See Record.safePosition.
            s.safePosition?.let { "${it.first}:${it.second}" } ?: "${s.surahStart}·",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = goldColor,
            modifier = Modifier.width(56.dp),
        )
        Text(
            info?.nameEn ?: "Surah ${s.surahStart}",
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
