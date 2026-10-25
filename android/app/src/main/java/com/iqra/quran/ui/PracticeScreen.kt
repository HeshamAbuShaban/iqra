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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.R
import com.iqra.quran.data.Mushaf
import com.iqra.quran.data.MushafPage
import com.iqra.quran.data.MushafWord
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
    val pages = rememberMushafPages(ctx)

    Scaffold(
        containerColor = c.ground,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = c.inkMuted)
                }
                Text(
                    stringResource(R.string.practice_title),
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
                // Words to work on, keyed as the engine keys them. The text
                // must come from the mushaf table: a hard word's `key` uses the
                // mushaf word index, and the whitespace split this replaces
                // disagreed with it from the first multi-word token - printing
                // the wrong word at the top of "words you got wrong". A word
                // whose text cannot be resolved falls back to naming its ayah,
                // never to inventing text (rule 10).
                val hardWords = remember(pages, s.hardest) {
                    val bySurah = HashMap<Int, List<MushafWord>>()
                    s.hardest.associate { w ->
                        val inSurah = bySurah.getOrPut(w.surah) {
                            pages?.let { Mushaf.wordsForSurah(it, w.surah) }.orEmpty()
                        }
                        w.key to inSurah
                            .firstOrNull { it.verse == w.ayah && it.wordInVerse == w.wordInAyah }
                            ?.text
                    }
                }
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
                    if (s.hardest.isNotEmpty()) item("hard") { HardestWordsCard(s, data, c, hardWords) }
                    // Was gated on `size > 1`, so anyone with one session - which is
                    // everyone who has just started - saw no session list at all.
                    if (s.recent.isNotEmpty()) item("recent") {
                        RecentSessionsCard(s, data, c, onOpenSession)
                    }
                    item("foot") { Text(
                        stringResource(R.string.practice_footnote),
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

/**
 * The mushaf word table, loaded off the main thread; [Mushaf.load] caches it
 * process-wide, so this is one heavy read and then cheap. The screen needs it
 * because a hard word's key is the mushaf word index, which need not match a
 * whitespace position in the verse text.
 */
@Composable
private fun rememberMushafPages(ctx: android.content.Context): List<MushafPage>? {
    var pages: List<MushafPage>? by remember { mutableStateOf(null) }
    LaunchedEffect(ctx) {
        pages = Mushaf.load(ctx)
    }
    return pages
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
                stringResource(R.string.practice_empty_title),
                fontSize = 17.sp, color = c.ink,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.practice_empty_body),
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
                Metric(
                    c.ink, "${s.judgedWords}",
                    stringResource(R.string.practice_words_judged),
                    stringResource(R.string.practice_all_time),
                )
                Spacer(Modifier.height(12.dp))
                Metric(
                    c.ink, "${s.currentStreak}",
                    if (s.currentStreak == 1) stringResource(R.string.practice_streak_day)
                    else stringResource(R.string.practice_streak_days),
                    // Both arms rendered "best ${s.longestStreak}": the test was
                    // decoration, a comparison that could not change the answer,
                    // which is worse than no comparison because it looks like
                    // one. If the intent was "best vs current", the two numbers
                    // must differ, and until they do this says the one true
                    // thing.
                    stringResource(R.string.practice_streak_best, s.longestStreak),
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
                stringResource(
                    R.string.practice_accuracy_with_unknown,
                    100 - (full * 100).toInt(), pct1(full),
                ),
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
                    d > 0.005f -> stringResource(R.string.practice_trend_up, pct1(d))
                    d < -0.005f -> stringResource(R.string.practice_trend_down, pct1(-d))
                    else -> stringResource(R.string.practice_trend_level)
                },
                fontSize = 12.sp,
                color = if (d > 0.005f) c.chartGood else if (d < -0.005f) c.chartBad else c.inkMuted,
            )
        } else if (withAcc.size == 1) {
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.practice_first_session),
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
            stringResource(R.string.practice_panel_accuracy),
            if (withAcc.size < 2) stringResource(R.string.practice_needs_two)
            else stringResource(R.string.practice_n_sessions, withAcc.size),
            c,
        )
        if (withAcc.size >= 2) {
            Spacer(Modifier.height(14.dp))
            TrendLine(
                points = withAcc.mapIndexed { i, p ->
                    TrendPoint(
                        com.iqra.quran.ui.theme.trendX(i, withAcc.size),
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
                stringResource(R.string.practice_accuracy_chart_hint),
                fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
            )
        }
    }
}

@Composable
private fun PaceTrendCard(s: PracticeLog.Summary, c: IqraColors) {
    // A session where nothing was judged has no pace. Pace is a rate, and a rate
    // over zero recited words is not a slow session - it is no session. Showing
    // "70 words per minute" beside "0 words judged" was reporting a stopwatch
    // reading for a run that never happened.
    val paced = s.series.filter { it.wpm > 1.0 && it.judged > 0 }
    Panel(c) {
        PanelTitle(
            stringResource(R.string.practice_panel_pace),
            if (paced.isEmpty()) stringResource(R.string.practice_needs_judged) else null,
            c,
        )
        if (paced.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row {
                val last = paced.last()
                Metric(
                    c.ink, "%.0f".format(last.wpm),
                    stringResource(R.string.practice_wpm), stringResource(R.string.practice_most_recent),
                )
                Spacer(Modifier.width(24.dp))
                val best = paced.maxByOrNull { it.wpm }
                Metric(
                    c.gold, "%.0f".format(best?.wpm ?: 0.0),
                    stringResource(R.string.practice_fastest), stringResource(R.string.practice_best),
                )
            }
            if (paced.size >= 2) {
                Spacer(Modifier.height(14.dp))
                TrendLine(
                    points = paced.mapIndexed { i, p ->
                        TrendPoint(
                            com.iqra.quran.ui.theme.trendX(i, paced.size),
                            p.wpm.toFloat(), shortDate(p.dayKey),
                        )
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
                stringResource(R.string.practice_pace_hint),
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
        PanelTitle(
            stringResource(R.string.practice_activity_title),
            stringResource(R.string.practice_active_days, s.activeDays),
            c,
        )
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
            stringResource(R.string.practice_activity_hint),
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
            stringResource(R.string.practice_by_surah),
            if (s.surahs.size > 10) stringResource(R.string.practice_top_of, s.surahs.size) else null,
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
                        info?.nameEn ?: stringResource(R.string.home_surah_n, p.surah),
                        fontSize = 13.sp, color = c.ink,
                    )
                    // "reached N of M" and "N sessions" are composed from
                    // resources so Arabic can reorder; the " · " separators
                    // between the three parts stay in code.
                    val reachedTxt =
                        if (p.reached > 0 && p.ayahCount > 0)
                            stringResource(R.string.practice_reached, p.reached, p.ayahCount)
                        else null
                    val sessionsTxt =
                        if (p.sessions > 1) stringResource(R.string.practice_n_sessions, p.sessions) else null
                    Text(
                        buildString {
                            p.accuracy?.let { append(pct1(it)) }
                            if (reachedTxt != null) {
                                if (isNotEmpty()) append(" · ")
                                append(reachedTxt)
                            }
                            if (sessionsTxt != null) append(" · $sessionsTxt")
                        },
                        fontSize = 10.sp, color = c.inkFaint,
                    )
                }
                CoverageBar(p.mastery, c)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.practice_mastery_hint),
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
private fun HardestWordsCard(
    s: PracticeLog.Summary,
    data: QuranData,
    c: IqraColors,
    hardWords: Map<String, String?>,
) {
    val words = s.hardest.take(12)
    Panel(c) {
        PanelTitle(
            stringResource(R.string.practice_hard_title),
            stringResource(R.string.practice_hard_sub),
            c,
        )
        Spacer(Modifier.height(12.dp))
        words.forEach { w ->
            val text = hardWords[w.key]
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text ?: stringResource(R.string.practice_ayah_n, w.ayah),
                    fontSize = 17.sp,
                    fontFamily = quranFont,
                    color = if (text != null) c.ink else c.inkMuted,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start,
                )
                val info = data.surahInfo(w.surah)
                Text(
                    stringResource(
                        R.string.practice_word_ref,
                        info?.nameEn ?: stringResource(R.string.home_surah_n, w.surah), w.ayah,
                    ),
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
        PanelTitle(
            stringResource(R.string.practice_recent_title),
            stringResource(R.string.practice_recent_sub),
            c,
        )
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
                            // With the denominator where the record carries
                            // one. "29 words" is a number with nothing to be
                            // a number OF - the same session's list showed
                            // "13 words" for a 43-word scope, which reads as
                            // a short session rather than a barely-decided
                            // one. The report screen learned this first; the
                            // list follows it.
                            val scope = r.perAyah.values.sumOf { it.getOrNull(4) ?: 0 }
                            if (scope > 0) append("${r.judged} of $scope words")
                            else append("${r.judged} words")
                            val dur = r.durationSec
                            append(if (dur != null) " · ${formatSecs(dur)}" else " · length unknown")
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
    // A record with no start time has no day key, and dayKeyOf(0) is 0, which
    // formatted as "0 ?" - a date made entirely of placeholders.
    if (dayKey <= 0L) return "no date"
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

/** "45s" under a minute, "2m 05s" above. Used wherever a duration is shown. */
fun formatSecs(s: Int): String =
    if (s < 60) "${s}s" else "${s / 60}m ${"%02d".format(s % 60)}s"
