package com.iqra.quran.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.data.PracticeLog
import com.iqra.quran.data.QuranData
import com.iqra.quran.data.SurahInfo
import com.iqra.quran.ui.theme.AccuracyRing
import com.iqra.quran.ui.theme.ActivityHeatmap
import com.iqra.quran.ui.theme.IqraColors
import com.iqra.quran.ui.theme.LocalIqraColors
import com.iqra.quran.ui.theme.TrendLine
import com.iqra.quran.ui.theme.TrendPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything you have practised, told as a practice history rather than a
 * diagnostics dump.
 *
 * WHAT THIS REPLACES, AND WHY IT HAD TO BE REPLACED
 * -------------------------------------------------
 * The previous version of this surface was, in order: a streak card, a card that
 * said "the lock moved N times over M evaluations, so the recitation was heard",
 * two integer percentages, fourteen identical boxes filled or not, a list of
 * surahs with a bar whose formula was accuracy x ln(ayahs), a list of words drawn
 * with red error pips that were mostly words the user had got RIGHT, and a list of
 * recent sessions.
 *
 * Almost none of that was a style complaint. Three of those items were wrong:
 *
 *  - "evaluations" is a 4 Hz audio poll count. Quoting a sampling rate to someone
 *    reciting from memory is not information.
 *  - The words list showed correct words as errors, because the parser counted
 *    every verdict the engine recorded as a miss. The engine started recording
 *    CORRECT in cf9a2bb; the filter did not move with it.
 *  - "N ayat" was a sum of distinct-surah counts.
 *
 * And the omission mattered more than the errors: there was no trend, no
 * comparison, no baseline and no date anywhere. A number with nothing to be
 * measured against is a number you cannot act on, which is the whole reason to
 * collect it.
 */
@Composable
fun PracticeScreen(
    data: QuranData,
    onOpen: (Int, Int) -> Unit,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalIqraColors.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tick by ReaderPrefs.tick.collectAsStateWithLifecycle()
    val summary = rememberSummary(ctx, data, tick)

    Scaffold(
        containerColor = c.ground,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.inkMuted)
                }
                Text(
                    "Practice",
                    fontSize = 19.sp,
                    color = c.ink,
                    modifier = Modifier.weight(1f),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
        },
    ) { pad ->
        when {
            summary == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }

            summary.isEmpty -> EmptyPractice(c)

            else -> {
                val s = summary
                LazyColumn(
                    Modifier.fillMaxSize().padding(pad),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item("hero") { HeadlineCard(s, c) }
                    item("trend") { AccuracyTrendCard(s, c) }
                    item("pace") { PaceTrendCard(s, c) }
                    item("activity") { ActivityCard(s, c) }
                    if (s.surahs.isNotEmpty()) item("surahs") { SurahMasteryCard(s, data, c, onOpen) }
                    if (s.hardest.isNotEmpty()) item("hard") { HardestWordsCard(s, data, c) }
                    // Was gated on `size > 1`, so anyone with one session - which is
                    // everyone who has just started - saw no session list at all.
                    if (s.recent.isNotEmpty()) item("recent") {
                        RecentSessionsCard(s, data, c, onOpenSession)
                    }
                    item("foot") { Text(
                        "Accuracy counts words the engine judged. " +
                            "Words it never heard are shown separately.",
                        fontSize = 11.sp, lineHeight = 16.sp, color = c.inkFaint,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
            }
        }
    }
}

/** Read the summary off the main thread; the aggregate rewrite makes this IO. */
@Composable
private fun rememberSummary(ctx: android.content.Context, data: QuranData, tick: Int):
    PracticeLog.Summary? {
    var st: PracticeLog.Summary? by remember { mutableStateOf(null) }
    LaunchedEffect(ctx, tick) {
        st = withContext(Dispatchers.IO) { PracticeLog.summarise(ctx, data) }
    }
    return st
}

@Composable
private fun EmptyPractice(c: IqraColors) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(
            Modifier.padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(32.dp))
                    .background(c.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("۞", fontSize = 26.sp, color = c.accent)
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Nothing practised yet",
                fontSize = 17.sp, color = c.ink,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Recite a passage from memory and it will appear here - " +
                    "how much you got right, which ayat are slow, and which " +
                    "words keep slipping.",
                fontSize = 13.sp, lineHeight = 19.sp, color = c.inkMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- cards ---------------------------------------------------------------

@Composable
private fun Panel(c: IqraColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(c.surface)
            .padding(16.dp),
    ) { content() }
}

@Composable
private fun PanelTitle(title: String, sub: String?, c: IqraColors) {
    Text(
        title,
        fontSize = 11.sp, color = c.inkMuted,
        letterSpacing = 1.1.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
    )
    if (sub != null) {
        Spacer(Modifier.height(3.dp))
        Text(sub, fontSize = 11.sp, lineHeight = 15.sp, color = c.inkFaint)
    }
}

@Composable
private fun HeadlineCard(s: PracticeLog.Summary, c: IqraColors) {
    val acc by animateFloatAsState(s.accuracy ?: 0f, tween(700), label = "acc")
    Panel(c) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccuracyRing(
                fraction = s.accuracy,
                accent = c.accent,
                track = c.chartEmpty,
                caption = s.accuracy?.let { pct1(it) } ?: "—",
                captionColor = c.ink,
                accentColor = c.ink,
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Metric(c.ink, "${s.judgedWords}", "words judged", "all time")
                Spacer(Modifier.height(12.dp))
                Metric(
                    c.ink, "${s.currentStreak}",
                    if (s.currentStreak == 1) "day in a row" else "days in a row",
                    if (s.longestStreak > s.currentStreak)
                        "best ${s.longestStreak}" else "best ${s.longestStreak}",
                )
            }
        }

        // The disclosure the headline needs. Accuracy drops UNKNOWN from both
        // sides, which is right for "how well did you read" and wrong for "how much
        // did the app understand" - so the second figure is shown rather than the
        // first being quietly flattering.
        val full = s.accuracyWithUnknown
        if (full != null && s.accuracy != null && s.accuracy - full > 0.02f) {
            Spacer(Modifier.height(14.dp))
            Hairline(c)
            Spacer(Modifier.height(12.dp))
            Text(
                "Counting the ${(100 - (full * 100).toInt())}% of words the " +
                    "engine never heard, this is ${pct1(full)}.",
                fontSize = 12.sp, lineHeight = 17.sp, color = c.inkMuted,
            )
        }
        // A comparison needs two things to compare. With one session there is no
        // previous, and the old wording claimed "level with your previous session"
        // against a session that does not exist.
        val withAcc = s.series.filter { it.accuracy != null }
        if (withAcc.size >= 2) {
            Spacer(Modifier.height(14.dp))
            val d = (withAcc.last().accuracy ?: 0f) - (withAcc[withAcc.size - 2].accuracy ?: 0f)
            Text(
                when {
                    d > 0.005f -> "Up ${pct1(d)} on your previous session"
                    d < -0.005f -> "Down ${pct1(-d)} on your previous session"
                    else -> "Level with your previous session"
                },
                fontSize = 12.sp,
                color = if (d > 0.005f) c.chartGood else if (d < -0.005f) c.chartBad else c.inkMuted,
            )
        } else if (withAcc.size == 1) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Your first session with a judged word. Recite once more and " +
                    "this becomes a trend.",
                fontSize = 12.sp, lineHeight = 17.sp, color = c.inkFaint,
            )
        }
    }
}

@Composable
private fun AccuracyTrendCard(s: PracticeLog.Summary, c: IqraColors) {
    val withAcc = s.series.filter { it.accuracy != null }
    Panel(c) {
        PanelTitle(
            "Accuracy over time",
            if (withAcc.size < 2) "Needs at least two sessions"
            else "${withAcc.size} sessions",
            c,
        )
        if (withAcc.size >= 2) {
            Spacer(Modifier.height(14.dp))
            TrendLine(
                points = withAcc.mapIndexed { i, p ->
                    TrendPoint(
                        i / (withAcc.size - 1f),
                        p.accuracy ?: 0f,
                        shortDate(p.dayKey),
                    )
                },
                line = c.series1,
                fill = c.series1,
                bars = withAcc.map { it.judged.toFloat() },
                barColor = c.series1.copy(alpha = 0.10f),
                labelColor = c.inkFaint,
                gridColor = c.hairline,
                modifier = Modifier.fillMaxWidth().height(132.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Height is accuracy. The faint columns are words judged, so a " +
                    "line drawn through one word is visibly thin.",
                fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
            )
        }
    }
}

@Composable
private fun PaceTrendCard(s: PracticeLog.Summary, c: IqraColors) {
    val paced = s.series.filter { it.wpm > 1.0 }
    Panel(c) {
        PanelTitle("Pace", if (paced.isEmpty()) "No pace recorded yet" else null, c)
        if (paced.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row {
                val last = paced.last()
                Metric(c.ink, "%.0f".format(last.wpm), "words per minute", "most recent")
                Spacer(Modifier.width(24.dp))
                val best = paced.maxByOrNull { it.wpm }
                Metric(
                    c.gold, "%.0f".format(best?.wpm ?: 0.0), "fastest", "best",
                )
            }
            if (paced.size >= 3) {
                Spacer(Modifier.height(14.dp))
                TrendLine(
                    points = paced.mapIndexed { i, p ->
                        TrendPoint(i / (paced.size - 1f), p.wpm.toFloat(), shortDate(p.dayKey))
                    },
                    line = c.series2,
                    fill = c.series2,
                    yMin = 20f, yMax = 180f,
                    labelColor = c.inkFaint, gridColor = c.hairline,
                    gridLines = 0,
                    modifier = Modifier.fillMaxWidth().height(80.dp),
                    strokeWidth = 2f,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Pace is a smoothed estimate from the lock's own gate timing, " +
                    "not a stopwatch. Treat it as a direction, not a number.",
                fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
            )
        }
    }
}

@Composable
private fun ActivityCard(s: PracticeLog.Summary, c: IqraColors) {
    val vol = remember(s.series) {
        s.series.groupBy { it.dayKey }
            .mapValues { (_, v) -> v.sumOf { it.judged } }
    }
    Panel(c) {
        PanelTitle("Last 26 weeks", "${s.activeDays} active days", c)
        Spacer(Modifier.height(18.dp))
        ActivityHeatmap(
            days = s.days,
            volume = vol,
            weeks = 26,
            accent = c.accent,
            empty = c.chartEmpty,
            frame = c.inkFaint,
            todayKey = todayKey(),
            modifier = Modifier.fillMaxWidth().height(120.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Darker means more words judged that day. The outlined cell is today.",
            fontSize = 10.sp, color = c.inkFaint,
        )
    }
}

@Composable
private fun SurahMasteryCard(
    s: PracticeLog.Summary,
    data: QuranData,
    c: IqraColors,
    onOpen: (Int, Int) -> Unit,
) {
    val rows = s.surahs.filter { it.judged > 0 }.take(10)
    Panel(c) {
        PanelTitle(
            "By surah",
            if (s.surahs.size > 10) "Top 10 of ${s.surahs.size}" else null,
            c,
        )
        Spacer(Modifier.height(12.dp))
        rows.forEach { p ->
            val info: SurahInfo? = data.surahInfo(p.surah)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp).clickable {
                    onOpen(p.surah, info?.startPage ?: 1)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${p.surah}",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = c.gold, modifier = Modifier.width(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        info?.nameEn ?: "Surah ${p.surah}",
                        fontSize = 13.sp, color = c.ink,
                    )
                    Text(
                        buildString {
                            p.accuracy?.let { append(pct1(it)) }
                            if (p.reached > 0 && p.ayahCount > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("reached ${p.reached} of ${p.ayahCount}")
                            }
                            if (p.sessions > 1) append(" · ${p.sessions} sessions")
                        },
                        fontSize = 10.sp, color = c.inkFaint,
                    )
                }
                CoverageBar(p.mastery, c)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "The bar is accuracy weighted by how much of the surah you have " +
                "covered, so one perfect ayah is not a mastered surah.",
            fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
        )
    }
}

@Composable
private fun CoverageBar(v: Float, c: IqraColors) {
    val a by animateFloatAsState(v.coerceIn(0f, 1f), tween(600), label = "cov")
    Box(
        Modifier.width(58.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
            .background(c.chartEmpty),
    ) {
        Box(
            Modifier.fillMaxWidth(a).height(5.dp).clip(RoundedCornerShape(3.dp))
                .background(c.accent),
        )
    }
}

@Composable
private fun HardestWordsCard(s: PracticeLog.Summary, data: QuranData, c: IqraColors) {
    val words = s.hardest.take(12)
    Panel(c) {
        PanelTitle("Words to work on", "Counted from misses only", c)
        Spacer(Modifier.height(12.dp))
        words.forEach { w ->
            val verse = data.getVerse(w.surah, w.ayah)
            // The engine's word index is its own segmentation. If it disagrees with
            // the page's, indexing blindly prints the WRONG WORD in the middle of a
            // list of words you got wrong - so the lookup is checked and the row
            // falls back to naming the ayah.
            val text = verse?.let {
                val parts = it.textClean.split(Regex("\\s+")).filter { p -> p.isNotEmpty() }
                parts.getOrNull(w.wordInAyah - 1)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text ?: "ayah ${w.ayah}",
                    fontSize = 17.sp,
                    fontFamily = quranFont,
                    color = if (text != null) c.ink else c.inkMuted,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                val info = data.surahInfo(w.surah)
                Text(
                    "${info?.nameEn ?: "Surah ${w.surah}"} ${w.ayah}",
                    fontSize = 11.sp, color = c.inkFaint,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "×${w.misses}",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = c.chartBad,
                )
            }
        }
    }
}

@Composable
private fun RecentSessionsCard(
    s: PracticeLog.Summary,
    data: QuranData,
    c: IqraColors,
    onOpen: (String) -> Unit,
) {
    Panel(c) {
        PanelTitle("Recent sessions", "Tap one for the full report", c)
        Spacer(Modifier.height(10.dp))
        s.recent.forEach { r ->
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpen(r.name) }
                    .padding(vertical = 9.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val pos = r.safePosition
                    Text(
                        pos?.let {
                        "${data.surahInfo(it.first)?.nameEn ?: "Surah ${it.first}"} · ${shortDate(r.dayKey)}"
                    } ?: "Session · ${shortDate(r.dayKey)}",
                        fontSize = 13.sp, color = c.ink,
                    )
                    Text(
                        buildString {
                            append("${r.judged} words")
                            r.durationSec?.let { append(" · ${it}s") }
                            if (r.ended) append(" · finished") else append(" · ended early")
                        },
                        fontSize = 10.sp, color = c.inkFaint,
                    )
                }
                val a = r.accuracy
                Text(
                    a?.let { pct1(it) } ?: "—",
                    fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                    color = when {
                        a == null -> c.inkFaint
                        a >= 0.95f -> c.chartGood
                        a >= 0.8f -> c.ink
                        else -> c.chartBad
                    },
                )
            }
        }
    }
}

// ---- shared bits ---------------------------------------------------------

@Composable
private fun Metric(
    colour: Color,
    value: String,
    label: String,
    hint: String? = null,
) {
    // Its own Column, because this is called directly inside Row scopes. Three
    // bare Text children in a Row come out as one line reading "64words per
    // minutemost recent".
    Column {
        Text(value, fontSize = 26.sp, lineHeight = 30.sp, color = colour,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        Text(label, fontSize = 11.sp, lineHeight = 14.sp, color = colour.copy(alpha = 0.62f))
        if (hint != null) Text(hint, fontSize = 10.sp, lineHeight = 13.sp,
            color = colour.copy(alpha = 0.42f))
    }
}

@Composable
private fun Hairline(c: IqraColors) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
}

/** "87.4" not "87". A denominator of one word looks identical to one of 400. */
fun pct1(v: Float): String = if (v >= 0.995f) "100" else "%.1f".format(v * 100)

fun shortDate(dayKey: Long): String {
    val y = dayKey / 10000
    val m = (dayKey / 100) % 100
    val d = dayKey % 100
    val mm = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        .getOrNull((m - 1).toInt()) ?: "?"
    return "$d $mm"
}

fun todayKey(): Long {
    val c = java.util.Calendar.getInstance()
    return (c.get(java.util.Calendar.YEAR) * 10000L +
        (c.get(java.util.Calendar.MONTH) + 1) * 100L +
        c.get(java.util.Calendar.DAY_OF_MONTH))
}
