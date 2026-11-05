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
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.launch
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
    var deleteCandidate by remember { mutableStateOf<String?>(null) }
    var manageFor by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
                    // The sessions open the screen. This is the reciter's own
                    // log - the list is what a review visit comes for, and it
                    // sat five screens of aggregate below the fold; the trends
                    // and the headline are the context, not the destination.
                    item("plan") { RevisionPlanCard(s, data, pages, c, onOpen) }
                    if (s.recent.isNotEmpty()) item("recent") {
                        RecentSessionsCard(s, data, c, onOpenSession, onManage = { manageFor = it })
                    }
                    item("hero") { HeadlineCard(s, c) }
                    item("trend") { AccuracyTrendCard(s, c) }
                    item("pace") { PaceTrendCard(s, c) }
                    item("activity") { ActivityCard(s, c) }
                    if (s.surahs.isNotEmpty()) item("surahs") { SurahMasteryCard(s, data, c, onOpen) }
                    if (s.hardest.isNotEmpty()) item("hard") { HardestWordsCard(s, data, c, hardWords, pages, onOpen) }
                    item("foot") { Text(
                        stringResource(R.string.practice_footnote),
                        fontSize = 11.sp, lineHeight = 16.sp, color = c.inkFaint,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                    }
                }
                manageFor?.let { name ->
                    // The friendly title, from the same summary the row read -
                    // a raw file name told the reciter nothing about which
                    // recitation they were holding.
                    val held = summary?.recent?.firstOrNull { it.name == name }
                    val lang = uiLanguage(LocalConfiguration.current)
                    val heldTitle = held?.let { r ->
                        r.safePosition?.let { (sn, _) ->
                            data.surahInfo(sn)?.displayName(LocalConfiguration.current)
                                ?: stringResource(R.string.home_surah_n, n(sn))
                        } ?: stringResource(R.string.practice_row_session, shortDateLocalized(r.dayKey, lang))
                    } ?: name
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { manageFor = null },
                        title = { Text(heldTitle, fontSize = 15.sp) },
                        text = null,
                        confirmButton = {
                            TextButton(onClick = {
                                manageFor = null
                                onOpenSession(name)
                            }) { Text(stringResource(R.string.practice_manage_report), color = c.accent) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                manageFor = null
                                deleteCandidate = name
                            }) { Text(stringResource(R.string.practice_manage_delete), color = c.wordBad) }
                        },
                        containerColor = c.surface,
                    )
                }
                deleteCandidate?.let { name ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { deleteCandidate = null },
                        title = { Text(stringResource(R.string.practice_delete_title)) },
                        text = { Text(stringResource(R.string.practice_delete_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                deleteCandidate = null
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    PracticeLog.deleteSession(ctx, name)
                                    ReaderPrefs.refresh()
                                }
                            }) { Text(stringResource(R.string.practice_delete_yes), color = c.wordBad) }
                        },
                        dismissButton = {
                            TextButton(onClick = { deleteCandidate = null }) {
                                Text(stringResource(R.string.practice_delete_no))
                            }
                        },
                        containerColor = c.surface,
                    )
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
                    stringResource(R.string.practice_streak_best, n(s.longestStreak)),
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
                    n(100 - (full * 100).toInt()), pct1(full),
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
            else stringResource(R.string.practice_n_sessions, n(withAcc.size)),
            c,
        )
        if (withAcc.size >= 2) {
            Spacer(Modifier.height(14.dp))
            // The y-range fits the data, not the 0..100 sky: a history living
            // between 60 and 97% on a full-scale axis draws as a flat line in
            // the clouds and the chart reads as broken. Padded a little so the
            // best and worst sessions keep air around them.
            val accs = withAcc.mapNotNull { it.accuracy }
            val lo = ((accs.minOrNull() ?: 0f) - 0.04f).coerceAtLeast(0f)
            val hi = ((accs.maxOrNull() ?: 1f) + 0.04f).coerceAtMost(1f)
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
                yMin = lo,
                yMax = hi,
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
                    c.ink, java.lang.String.format(java.util.Locale.US, "%.0f", last.wpm),
                    stringResource(R.string.practice_wpm), stringResource(R.string.practice_most_recent),
                )
                Spacer(Modifier.width(24.dp))
                val best = paced.maxByOrNull { it.wpm }
                Metric(
                    c.gold, java.lang.String.format(java.util.Locale.US, "%.0f", best?.wpm ?: 0.0),
                    stringResource(R.string.practice_fastest), stringResource(R.string.practice_best),
                )
            }
            if (paced.size >= 2) {
                Spacer(Modifier.height(14.dp))
                // The range fits the data, as the accuracy chart does: a
                // reciter pacing 60-90 wpm on a 20-180 axis draws a flat
                // line and learns nothing from it.
                val wpms = paced.map { it.wpm.toFloat() }
                val lo = ((wpms.minOrNull() ?: 20f) - 10f).coerceAtLeast(0f)
                val hi = (wpms.maxOrNull() ?: 180f) + 10f
                TrendLine(
                    points = paced.mapIndexed { i, p ->
                        TrendPoint(
                            com.iqra.quran.ui.theme.trendX(i, paced.size),
                            p.wpm.toFloat(), shortDate(p.dayKey),
                        )
                    },
                    line = c.series2,
                    fill = c.series2,
                    yMin = lo, yMax = hi,
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
            stringResource(R.string.practice_active_days, n(s.activeDays)),
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
            if (s.surahs.size > 10) stringResource(R.string.practice_top_of, n(s.surahs.size)) else null,
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
                        info?.displayName(LocalConfiguration.current)
                            ?: stringResource(R.string.home_surah_n, n(p.surah)),
                        fontSize = 13.sp, color = c.ink,
                    )
                    // "reached N of M" and "N sessions" are composed from
                    // resources so Arabic can reorder; the " · " separators
                    // between the three parts stay in code.
                    val reachedTxt =
                        if (p.reached > 0 && p.ayahCount > 0)
                            stringResource(R.string.practice_reached, n(p.reached), n(p.ayahCount))
                        else null
                    val sessionsTxt =
                        if (p.sessions > 1) stringResource(R.string.practice_n_sessions, n(p.sessions)) else null
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

/**
 * The revision plan: the app's first forward-looking surface. Every card so
 * far looked backward - what happened; this one turns the accumulated miss
 * counts into WHAT TO RECITE NEXT, ranked by misses and by how long the ayah
 * has gone without a clean pass. The data is the durable aggregate's own;
 * nothing here is invented, and nothing here accuses - an ayah that improved
 * simply leaves the list.
 */
@Composable
private fun RevisionPlanCard(
    s: PracticeLog.Summary,
    data: QuranData,
    pages: List<com.iqra.quran.data.MushafPage>?,
    c: IqraColors,
    onOpen: (Int, Int) -> Unit,
) {
    // One row per AYAH: the words of an ayah share its fate, and the reciter
    // revises ayat, not word indices. Ranked by misses, then by how long ago
    // the ayah was last practised - the stale wound rises.
    val plan = remember(s.hardest) {
        s.hardest.groupBy { "${it.surah}:${it.ayah}" }
            .map { (key, ws) ->
                val first = ws.first()
                val lastSeen = ws.maxOf { it.lastAt }
                Triple(first.surah, first.ayah, Pair(ws.sumOf { it.misses }, lastSeen))
            }
            .sortedWith(
                compareByDescending<Triple<Int, Int, Pair<Int, Long>>> { it.third.first }
                    .thenBy { it.third.second },
            )
            .take(5)
    }
    if (plan.isEmpty()) return
    val lang = uiLanguage(LocalConfiguration.current)
    Panel(c) {
        PanelTitle(
            stringResource(R.string.plan_title),
            stringResource(R.string.plan_sub),
            c,
        )
        Spacer(Modifier.height(10.dp))
        plan.forEach { (surah, ayah, burden) ->
            val verse = data.getVerse(surah, ayah)
            val page = pages?.indexOfFirst { pg ->
                pg.lines.any { ln ->
                    ln.words?.any { it.surah == surah && it.verse == ayah } == true
                } == true
            }?.plus(1) ?: 0
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.ground)
                    .clickable(enabled = page > 0) { onOpen(surah, page) }
                    .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        verse?.textClean
                            ?: stringResource(R.string.report_surah_fallback, n(surah)),
                        fontFamily = quranFont, fontSize = 16.sp, color = c.ink,
                        maxLines = 2,
                    )
                    Text(
                        (data.surahInfo(surah)?.displayName(LocalConfiguration.current)
                            ?: stringResource(R.string.home_surah_n, n(surah)))
                            + " · " + stringResource(R.string.plan_ayah, n(ayah))
                            + " · " + stringResource(R.string.plan_misses, n(burden.first))
                            + " · " + shortDateLocalized(missDayKey(burden.second), lang),
                        fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text("←", fontSize = 14.sp, color = c.accent)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** The wall-clock of a miss, folded to the day key the dates render from. */
private fun missDayKey(epochMs: Long): Long {
    if (epochMs <= 0L) return 0L
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    return (cal.get(java.util.Calendar.YEAR) * 100L + cal.get(java.util.Calendar.MONTH) + 1L) * 100L +
        cal.get(java.util.Calendar.DAY_OF_MONTH)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HardestWordsCard(
    s: PracticeLog.Summary,
    data: QuranData,
    c: IqraColors,
    hardWords: Map<String, String?>,
    pages: List<com.iqra.quran.data.MushafPage>?,
    onOpen: (Int, Int) -> Unit,
) {
    val words = s.hardest.take(12)
    // Grouped by surah, folded: the surah row carries the burden (how many
    // words, how many misses), and a tap unfolds its words in the Qur'an face
    // - each one a door to its own ayah. A flat list of twelve unrelated
    // words made the reciter do the grouping in their head.
    val groups = remember(words, hardWords) {
        words.groupBy { it.surah }.entries
            .map { (surah, ws) ->
                Triple(surah, ws, ws.sumOf { it.misses })
            }
            .sortedByDescending { it.third }
    }
    var openGroup by rememberSaveable { mutableStateOf<Int?>(null) }
    Panel(c) {
        PanelTitle(
            stringResource(R.string.practice_hard_title),
            stringResource(R.string.practice_hard_sub),
            c,
        )
        Spacer(Modifier.height(12.dp))
        groups.forEach { (surah, ws, misses) ->
            val info = data.surahInfo(surah)
            val isOpen = openGroup == surah
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.ground)
                    .clickable { openGroup = if (isOpen) null else surah }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        info?.displayName(LocalConfiguration.current)
                            ?: stringResource(R.string.home_surah_n, n(surah)),
                        fontSize = 12.sp, color = if (isOpen) c.accent else c.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.practice_group_meta, n(ws.size), n(misses)),
                        fontSize = 10.sp, color = c.inkFaint,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isOpen) "▴" else "▾", fontSize = 11.sp, color = c.inkFaint)
                }
                if (isOpen) {
                    Spacer(Modifier.height(4.dp))
                    ws.forEach { w ->
                        val text = hardWords[w.key]
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .clickable {
                                    val page = pages?.indexOfFirst { pg ->
                                        pg.lines.any { ln ->
                                            ln.words?.any { it.surah == surah && it.verse == w.ayah } == true
                                        } == true
                                    }?.plus(1) ?: 0
                                    if (page > 0) onOpen(surah, page)
                                }
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text ?: stringResource(R.string.practice_ayah_n, n(w.ayah)),
                                fontFamily = quranFont,
                                fontSize = 16.sp,
                                color = if (text != null) c.ink else c.inkMuted,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.practice_word_ref,
                                    info?.displayName(LocalConfiguration.current)
                                        ?: stringResource(R.string.home_surah_n, n(w.surah)), n(w.ayah)),
                                fontSize = 10.sp, color = c.inkFaint,
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                "×" + n(w.misses),
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                                color = c.chartBad,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun RecentSessionsCard(
    s: PracticeLog.Summary,
    data: QuranData,
    c: IqraColors,
    onOpen: (String) -> Unit,
    onManage: (String) -> Unit,
) {
    Panel(c) {
        PanelTitle(
            stringResource(R.string.practice_recent_title),
            stringResource(R.string.practice_recent_sub),
            c,
        )
        Spacer(Modifier.height(10.dp))
        s.recent.forEach { r ->
            // Each session is its own small instrument, recessed into the
            // panel: the accuracy ring carries the same number the report
            // would show, and the night strip below is the session's own
            // timeline - every verdict a mark at the instant it was decided,
            // the heard-density wave beneath it. Both draw only what the
            // record holds; a record without times simply has no strip.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.ground)
                    .combinedClickable(
                        onClick = { onOpen(r.name) },
                        // The management gesture: a held session names itself
                        // for deletion, with its audio, behind a confirmation.
                        onLongClick = { onManage(r.name) },
                    )
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val a = r.accuracy
                    AccuracyRoundel(
                        fraction = a,
                        label = a?.let { pct1(it) } ?: "—",
                        colour = when {
                            a == null -> c.inkFaint
                            a >= 0.95f -> c.chartGood
                            a >= 0.8f -> c.accent
                            else -> c.chartBad
                        },
                        c = c,
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        val pos = r.safePosition
                        val lang = uiLanguage(LocalConfiguration.current)
                        Text(
                            pos?.let {
                                "${data.surahInfo(it.first)?.displayName(LocalConfiguration.current)
                                    ?: stringResource(R.string.home_surah_n, n(it.first))} · ${shortDateLocalized(r.dayKey, lang)}"
                            } ?: stringResource(R.string.practice_row_session, shortDateLocalized(r.dayKey, lang)),
                            fontSize = 13.sp, color = c.ink,
                        )
                        // The notes are part of what the session found, and
                        // this list is where a recitation plan is chosen
                        // from - a row that scores but hides its advisories
                        // sends the reader into the report to learn the one
                        // thing they came for. Counted as WORDS AFFECTED,
                        // not events: a per-event kind like LOW_EVIDENCE can
                        // fire per poll, and record #272's 2,750 events sit
                        // on 85 words - the events measure the microphone,
                        // the words are the recitation plan. The report's
                        // detail lines keep the event counts per kind.
                        val notedWords = r.advisories.map { it.key }.distinct().size
                        Text(
                            buildString {
                                // With the denominator where the record carries
                                // one. "29 words" is a number with nothing to be
                                // a number OF - the same session's list showed
                                // "13 words" for a 43-word scope, which reads as
                                // a short session rather than a barely-decided
                                // one. Every fragment here is a resource: an
                                // Arabic row reading "294 of 359 words · 5m ٠٦s ·
                                // finished · ملاحظات..." was three languages in
                                // one line.
                                val scope = r.perAyah.values.sumOf { it.getOrNull(4) ?: 0 }
                                if (scope > 0) append(stringResource(R.string.practice_row_words_of, n(r.judged), n(scope)))
                                else append(stringResource(R.string.practice_row_words, n(r.judged)))
                                val dur = r.durationSec
                                append(" · " + (dur?.let { formatSecs(it) }
                                    ?: stringResource(R.string.practice_row_dur_unknown)))
                                append(if (r.ended) " · " + stringResource(R.string.practice_row_finished)
                                       else " · " + stringResource(R.string.practice_row_ended_early))
                                if (notedWords > 0) append(" · " + stringResource(R.string.practice_row_notes, n(notedWords)))
                            },
                            fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
                        )
                    }
                }
                SessionNightStrip(r, c)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The accuracy ring: the same number the report shows, drawn as its arc. */
@Composable
private fun AccuracyRoundel(fraction: Float?, label: String, colour: Color, c: IqraColors) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        // The arc sweeps in with the composition - the rest of this screen's
        // instruments move; a ring that pops static reads as a placeholder.
        val shown by animateFloatAsState(
            (fraction ?: 0f).coerceIn(0f, 1f), tween(700), label = "roundel",
        )
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(
                c.hairline, 0f, 360f, false,
                topLeft = Offset(inset, inset), size = arc,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            if (shown > 0f) {
                drawArc(
                    colour, 0f, 360f * shown, false,
                    topLeft = Offset(inset, inset), size = arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(label, fontSize = 11.sp, color = c.ink)
    }
}

/**
 * The session's own night, one strip tall: the heard-density wave beneath
 * (what reached the engine per 250 ms bin, from the record's `emMs`), every
 * decided word a mark at its `wt` instant - gold where the verdict is WRONG,
 * the rest quiet. Drawn only from what the record carries; a record without
 * emission times draws nothing rather than inventing a timeline.
 */
@Composable
private fun SessionNightStrip(r: PracticeLog.Record, c: IqraColors) {
    val span = r.emMs.lastOrNull() ?: return
    if (span <= 0L) return
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(c.ground),
    ) {
        val w = size.width
        val h = size.height
        // the density wave: emissions per 250 ms bin, normalised to the
        // loudest bin - the same envelope the console's night draws
        val bins = IntArray(((span / 250L) + 1).toInt().coerceAtLeast(1))
        r.emMs.forEach { ms ->
            val b = (ms / 250L).toInt()
            if (b in bins.indices) bins[b]++
        }
        val maxB = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
        val waveBottom = h
        val waveTop = h * 0.42f
        val path = Path()
        path.moveTo(0f, waveBottom)
        bins.forEachIndexed { i, n ->
            val x = (i + 0.5f) / bins.size * w
            val y = waveBottom - (n.toFloat() / maxB) * (waveBottom - waveTop)
            path.lineTo(x, y)
        }
        path.lineTo(w, waveBottom)
        path.close()
        drawPath(path, c.accent.copy(alpha = 0.16f))
        // the baseline the verdicts sit on
        drawLine(
            c.hairline, Offset(0f, h * 0.52f), Offset(w, h * 0.52f),
            strokeWidth = 1f,
        )
        // every decided word at its own instant
        r.wordRows.forEach { row ->
            val wt = row.wt?.toFloat() ?: return@forEach
            val x = (wt / span) * w
            when (row.st) {
                "WRONG" -> {
                    drawCircle(
                        c.gold.copy(alpha = 0.28f), radius = 4.5f, center = Offset(x, h * 0.30f),
                    )
                    drawCircle(c.gold, radius = 2.2f, center = Offset(x, h * 0.30f))
                }
                "CORRECT" -> drawCircle(
                    c.accent.copy(alpha = 0.62f), radius = 1.4f, center = Offset(x, h * 0.36f),
                )
                else -> drawCircle(
                    c.inkMuted.copy(alpha = 0.28f), radius = 1.2f, center = Offset(x, h * 0.42f),
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

/** "87.4" not "87". A denominator of one word looks identical to one of 400. Latin digits, always. */
fun pct1(v: Float): String = if (v >= 0.995f) "100" else n1(v * 100)

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
@Composable
fun formatSecs(s: Int): String =
    if (s < 60) stringResource(R.string.practice_secs, n(s))
    else stringResource(R.string.practice_min_secs, n(s / 60), "%02d".format(java.util.Locale.US, s % 60))
