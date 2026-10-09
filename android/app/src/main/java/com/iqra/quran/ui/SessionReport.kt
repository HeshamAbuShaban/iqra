package com.iqra.quran.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.data.PracticeLog
import com.iqra.quran.data.QuranData
import com.iqra.quran.ui.theme.AccuracyRing
import com.iqra.quran.ui.theme.AyahStrip
import com.iqra.quran.ui.theme.CompareBars
import com.iqra.quran.ui.theme.IqraColors
import com.iqra.quran.ui.theme.LocalIqraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One session, in full.
 *
 * Most of this screen could not be drawn before, which is worth stating because it
 * is the reason the data layer was reworked:
 *
 *  - `Record` kept only `frames.length()` from the frame ring and discarded every
 *    timestamp, so there was no pace, no consistency and nothing to plot.
 *  - `ayahStatus` was collapsed into four session totals and the `surah:ayah` key
 *    thrown away, so a flawless session and one with three bad ayat in a row
 *    produced identical numbers. [AyahStrip] needs that key, so the parser now
 *    keeps it.
 *  - `words` holds only misses now. Before cf9a2bb's record change it was filtered
 *    to WRONG/UNKNOWN by the writer; after it, every verdict is written, so a
 *    filter had to move to the reader or "words you got wrong" would list the
 *    words you got right.
 *
 * UNKNOWN is reported separately everywhere it appears, and never counted against
 * the reciter. A word the model failed to produce a phoneme for is a fact about the
 * model.
 */
@Composable
fun SessionReportScreen(
    data: QuranData,
    recordName: String,
    onBack: () -> Unit,
) {
    val c = LocalIqraColors.current
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var rec by remember { mutableStateOf<PracticeLog.Record?>(null) }
    var loaded by remember { mutableStateOf(false) }
    // The Recognition "diagnostics" switch promises to show engine state in the
    // report; make it true by default there. A pref that nothing reads is a
    // silent no-op, and the report is the promised place for it.
    var showDetail by remember { mutableStateOf(ReaderPrefs.diagnostics(ctx)) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(recordName) {
        rec = withContext(Dispatchers.IO) { PracticeLog.record(ctx, recordName) }
        loaded = true
    }

    Scaffold(
        containerColor = c.ground,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.inkMuted)
                }
                Column(Modifier.weight(1f)) {
                    Text("Session report", fontSize = 17.sp, color = c.ink,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    val r0 = rec
                    if (r0 != null) {
                        val pos = r0.safePosition
                        Text(
                            pos?.let {
                                val nm = data.surahInfo(it.first)?.nameEn ?: "Surah ${it.first}"
                                "$nm · ayah ${it.second}"
                            } ?: "Spanned a surah boundary",
                            fontSize = 11.sp, color = c.inkFaint,
                        )
                    }
                }
            }
        },
    ) { pad ->
        val r = rec
        when {
            !loaded -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Reading the record...", color = c.inkFaint, fontSize = 13.sp)
            }

            r == null -> Box(Modifier.fillMaxSize().padding(36.dp), Alignment.Center) {
                Text(
                    "That session is no longer on the phone.\n" +
                        "Only the ten most recent are kept.",
                    color = c.inkMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { VerdictCard(r, c) }
                if (r.perAyah.isNotEmpty()) item { AyahCard(r, c) }
                item { BreakdownCard(r, c) }
                item { MissesCard(r, data, c) }
                item {
                    Panel(c) {
                        TextButton(onClick = {
                            clip.setText(AnnotatedString(reportText(r, data)))
                            copied = true
                        }) {
                            Text(if (copied) "Copied" else "Copy summary",
                                color = c.accent, fontSize = 14.sp)
                        }
                    }
                }
                item {
                    Panel(c) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showDetail = !showDetail },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Engine detail",
                                fontSize = 11.sp, color = c.inkMuted,
                                letterSpacing = 1.1.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (showDetail) "hide" else "show",
                                fontSize = 11.sp, color = c.accent,
                            )
                        }
                        if (showDetail) {
                            Spacer(Modifier.height(10.dp))
                            DetailLine(c, "ended properly", if (r.ended) "yes" else "no, ended early")
                            DetailLine(c, "reason", r.reason.ifEmpty { "-" })
                            DetailLine(c, "lock advances", "${r.moves}")
                            DetailLine(c, "lock reversals", "${r.reversals}")
                            DetailLine(c, "audio polls", "${r.evaluations}")
                            DetailLine(c, "unjudgeable ayat", "${r.unjudgeable}")
                            // The failure that presents as silence. Every one of
                            // these words renders UNKNOWN - muted, no highlight -
                            // which looks exactly like a word the reciter has not
                            // reached. Saying so is the whole point of the panel.
                            if (r.noWindowWords > 0) {
                                DetailLine(
                                    c,
                                    "words the engine could not hear",
                                    "${r.noWindowWords} (${r.emptyWindows} empty windows)",
                                )
                            }
                            DetailLine(c, "trace frames", "${r.frames}")
                            // Two different numbers, deliberately not merged:
                            // `recorded` is every word the engine gave a status
                            // to (including "no verdict"), `judged` is how many it
                            // actually decided. Labelling the first as "judged"
                            // is what made a session that tested nothing report a
                            // large score.
                            DetailLine(c, "words with a status", "${r.recorded}")
                            DetailLine(c, "words decided", "${r.judged}")
                            // An advisory is the engine saying "I noticed this but
                            // cannot judge it" - information for the reciter, never
                            // an accusation, so it lists apart from the score.
                            //
                            // OFF hides them here too. QUIET and IN_FLOW differ
                            // only in whether the mushaf also shows a mark; a
                            // value of this enum that behaved the same as
                            // another would be a switch that does nothing.
                            val showNotes = ReaderPrefs.advisoryDisplay(ctx) !=
                                ReaderPrefs.AdvisoryDisplay.OFF
                            if (showNotes && r.advisories.isNotEmpty()) {
                                val byKind = r.advisories.groupingBy { it.kind }.eachCount()
                                byKind.keys.sorted().forEach { kind ->
                                    val n = r.advisories.filter { it.kind == kind }.sumOf { it.count }
                                    val label = AdvisoryKind.entries.firstOrNull { it.name == kind }?.label ?: kind
                                    DetailLine(c, label, "$n")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "\"Audio polls\" is how many times the engine looked at " +
                                    "the microphone, not a score. It is here because it " +
                                    "diagnoses a session that produced nothing.",
                                fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(c: IqraColors, k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, fontSize = 11.sp, color = c.inkFaint, modifier = Modifier.weight(1f))
        Text(v, fontSize = 11.sp, color = c.inkMuted, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Panel(c: IqraColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(c.surface).padding(16.dp),
    ) { content() }
}

/**
 * The headline number, chosen by the reader.
 *
 * The metric is a setting because no single figure is honest for everyone. A
 * session where the engine judged nothing is not a 0% session and not a 100%
 * one, and which of those it looks like depends entirely on the denominator:
 * counting SKIPPED as judged made an untested session report a large score; as a
 * fraction of CORRECT it reads as total failure. Both are wrong in opposite
 * directions, which is the tell that the choice belongs to the reader.
 *
 * Null is always rendered "—", never 0%, because an empty numerator and a failed
 * test are different events.
 */
@Composable
private fun VerdictCard(r: PracticeLog.Record, c: IqraColors) {
    val ctx = LocalContext.current
    val tick by ReaderPrefs.tick.collectAsStateWithLifecycle()
    val metric = remember(tick) { ReaderPrefs.metric(ctx) }

    val reached = r.judged + r.unknown
    val coverage = if (reached > 0) r.judged.toFloat() / reached else null
    val headlineValue: String
    val headlineLabel: String
    val headlineFraction: Float?
    when (metric) {
        ReaderPrefs.Metric.ACCURACY -> {
            headlineValue = r.accuracy?.let { pct1(it) } ?: "—"
            headlineLabel = "accuracy"
            headlineFraction = r.accuracy
        }
        ReaderPrefs.Metric.COVERAGE -> {
            headlineValue = coverage?.let { pct1(it) } ?: "—"
            headlineLabel = "coverage"
            headlineFraction = coverage
        }
        ReaderPrefs.Metric.DECIDED -> {
            headlineValue = "${r.judged}"
            headlineLabel = "words decided"
            headlineFraction = null
        }
        ReaderPrefs.Metric.NET -> {
            headlineValue = "${r.correct}/${r.wrong}"
            headlineLabel = "correct / wrong"
            headlineFraction = null
        }
    }

    Panel(c) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccuracyRing(
                fraction = headlineFraction,
                accent = c.accent,
                track = c.chartEmpty,
                caption = headlineValue,
                captionColor = c.ink,
                accentColor = c.ink,
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(headlineLabel, fontSize = 11.sp, color = c.inkMuted)
                Spacer(Modifier.height(4.dp))
                Stat(c.ink, "${r.judged}", "words decided")
                Spacer(Modifier.height(10.dp))
                Stat(c.ink, r.durationSec?.let { formatSecs(it) } ?: "—", "duration")
                Spacer(Modifier.height(10.dp))
                Stat(
                    c.gold,
                    if (r.wpm > 1.0 && r.judged > 0) "%.0f".format(r.wpm) else "—",
                    "words per minute",
                )
            }
        }
        val full = r.accuracyWithUnknown
        val acc0 = r.accuracy
        if (full != null && acc0 != null && acc0 - full > 0.005f) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
            Spacer(Modifier.height(12.dp))
            Text(
                "The engine never heard ${r.unknown} of these words. " +
                    "Counting those, accuracy is ${pct1(full)}.",
                fontSize = 12.sp, lineHeight = 17.sp, color = c.inkMuted,
            )
        }
    }
}

@Composable
private fun AyahCard(r: PracticeLog.Record, c: IqraColors) {
    // In recitation order, one outcome per ayah: the ayah's WORST verdict, because a
    // single mistake inside a mostly-correct ayah is the thing worth seeing.
    // Sort by surah FIRST, then ayah. The key is "surah:ayah", and a session
    // that spanned a handoff legitimately holds both 2:286 and 3:1 - sorting on
    // the substring after the colon alone put 3:1 between 2:1 and 2:2 and drew
    // the strip out of recitation order. The header next door already detects
    // the handoff; the strip has to agree with it.
    val entries = r.perAyah.entries.sortedWith(
        compareBy({ it.key.substringBefore(':').toIntOrNull() ?: 0 },
                  { it.key.substringAfter(':').toIntOrNull() ?: 0 })
    )
    val outcomes = entries.map { (_, v) ->
        when {
            v[1] > 0 -> 1
            v[3] > 0 && v[0] == 0 && v[1] == 0 && v[2] == 0 -> 2
            v[2] > 0 -> 2
            else -> 0
        }
    }
    Panel(c) {
        Text("Ayah by ayah", fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(12.dp))
        AyahStrip(
            outcomes = outcomes,
            good = c.wordGood,
            bad = c.chartBad,
            unknown = c.wordUnknown,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Legend(c, c.wordGood, "clean", outcomes.count { it == 0 })
        Legend(c, c.chartBad, "a word wrong", outcomes.count { it == 1 })
        Legend(c, c.wordUnknown, "partly unheard", outcomes.count { it == 2 })
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
        Spacer(Modifier.height(10.dp))
        entries.forEach { (key, v) ->
            val ayah = key.substringAfter(':').toIntOrNull() ?: 0
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text("ayah $ayah", fontSize = 11.sp, color = c.inkMuted,
                    modifier = Modifier.width(64.dp))
                Counts(c, v)
            }
        }
    }
}

@Composable
private fun Legend(c: IqraColors, colour: Color, label: String, n: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(9.dp).height(9.dp).clip(RoundedCornerShape(3.dp)).background(colour))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 11.sp, color = c.inkMuted, modifier = Modifier.weight(1f))
        Text("$n", fontSize = 11.sp, color = c.inkFaint, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Counts(c: IqraColors, v: IntArray) {
    Text(
        buildString {
            fun add(t: String) { if (isNotEmpty()) append(" · "); append(t) }
            if (v[0] > 0) add("${v[0]} right")
            if (v[1] > 0) add("${v[1]} wrong")
            if (v[3] > 0) add("${v[3]} unheard")
            if (v[2] > 0) add("${v[2]} passed over")
            if (isEmpty()) append("nothing judged")
        },
        fontSize = 11.sp, color = c.inkFaint,
    )
}

@Composable
private fun BreakdownCard(r: PracticeLog.Record, c: IqraColors) {
    val acc = r.accuracy
    val full = r.accuracyWithUnknown
    val accF = acc
    Panel(c) {
        Text("Verdicts", fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(10.dp))
        if (acc != null) {
            CompareBars(
                rows = listOfNotNull(
                    accF?.takeIf { it > 0f }?.let { "Judged words" to it },
                    full?.takeIf { it > 0f && (acc == null || it < acc - 0.001f) }
                        ?.let { "Including unheard" to it },
                ),
                valueFmt = { pct1(it) },
                primary = c.accent,
                secondary = c.inkFaint,
                track = c.chartEmpty,
                labelColor = c.ink,
            )
        } else {
            Text("Nothing was judged in this session.", fontSize = 12.sp, color = c.inkMuted)
        }
        Spacer(Modifier.height(14.dp))
        Row {
            Stat(c.wordGood, "${r.correct}", "correct")
            Spacer(Modifier.width(20.dp))
            Stat(c.chartBad, "${r.wrong}", "wrong")
            Spacer(Modifier.width(20.dp))
            Stat(c.inkFaint, "${r.skipped}", "passed over")
            Spacer(Modifier.width(20.dp))
            Stat(c.inkFaint, "${r.unknown}", "unheard")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "\"Passed over\" means the lock moved without judging the word. " +
                "\"Unheard\" means the model produced no phoneme for it. Neither " +
                "is counted against you.",
            fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
        )
    }
}

@Composable
private fun MissesCard(r: PracticeLog.Record, data: QuranData, c: IqraColors) {
    val misses = r.hardWords.entries
        .sortedByDescending { it.value[0] }
        .take(14)
    if (misses.isEmpty()) {
        Panel(c) {
            Text(
                // Not "nothing came out wrong". On a session where every word was
                // passed over, that is praise for a session nobody attempted - the
                // most misleading kind of report, because it reads as success.
                if (r.judged > 0) "Nothing came out wrong" else "Nothing to report",
                fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (r.judged > 0) "No word in this session was judged wrong."
                else "No word was judged in this session, so there is nothing to " +
                    "score. ${r.skipped} passed over as the lock advanced and " +
                    "${r.unknown} were not heard by the model.",
                fontSize = 13.sp, lineHeight = 19.sp, color = c.ink,
            )
        }
        return
    }
    Panel(c) {
        Text("Words that slipped", fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(10.dp))
        misses.forEach { (key, v) ->
            val surah = key.substringBefore(':').toIntOrNull() ?: 0
            val ayah = key.split(":").getOrNull(1)?.toIntOrNull() ?: 0
            val idx = key.split(":").getOrNull(2)?.toIntOrNull() ?: 0
            val parts = data.getVerse(surah, ayah)?.textClean
                ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
            val word = parts.getOrNull(idx - 1)
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(
                    word ?: "(ayah $ayah)",
                    fontSize = 16.sp, fontFamily = quranFont,
                    color = if (word != null) c.wordBad else c.inkMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    data.surahInfo(surah)?.nameEn ?: "Surah $surah",
                    fontSize = 11.sp, color = c.inkFaint,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Words the model could not place at all are listed separately as " +
                "unheard, and are not counted as mistakes.",
            fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
        )
    }
}

@Composable
private fun Stat(colour: Color, value: String, label: String) {
    Column {
        Text(value, fontSize = 20.sp, lineHeight = 24.sp, color = colour,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        Text(label, fontSize = 10.sp, lineHeight = 13.sp, color = colour.copy(alpha = 0.55f))
    }
}

/** Plain text, for pasting somewhere. No share intent: nothing here needs one. */
fun reportText(r: PracticeLog.Record, data: QuranData): String = buildString {
    val pos = r.safePosition
    val name = pos?.let { data.surahInfo(it.first)?.nameEn ?: "Surah ${it.first}" }
        ?: "Surah boundary"
    appendLine("$name — ${shortDate(r.dayKey)}")
    val a = r.accuracy
    val fu = r.accuracyWithUnknown
    a?.let { appendLine("Accuracy: ${pct1(it)}") }
    if (fu != null && a != null && a - fu > 0.005f) {
        appendLine("Including unheard: ${pct1(fu)}")
    }
    appendLine("Words judged: ${r.judged}")
    r.durationSec?.let { appendLine("Duration: ${formatSecs(it)}") }
    if (r.wpm > 1.0) appendLine("Pace: %.0f wpm".format(r.wpm))
    appendLine("Correct ${r.correct} · wrong ${r.wrong} · passed over ${r.skipped} · unheard ${r.unknown}")
}
