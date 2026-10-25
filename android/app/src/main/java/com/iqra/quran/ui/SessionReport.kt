package com.iqra.quran.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.res.stringResource
import com.iqra.quran.R
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    onOpenObserver: () -> Unit,
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

    // The session's own audio, when the record names it and the WAV is still
    // on this device. One player for the whole screen - two rows sounding at
    // once would be noise, not review. `hear` plays a window of the WAV whose
    // position is computed from the record's own clock: the WAV's first
    // sample sits at `audioStartMs`, so record-time t sounds at
    // t - (audioStartMs - startedAt) into the file.
    val wavFile = remember(rec) {
        val f = rec?.audioFile?.let { File(ctx.filesDir, "sessions/$it") }
        if (f != null && f.isFile) f else null
    }
    val player = remember { android.media.MediaPlayer() }
    var playingKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val hear: (String, Int, Int) -> Unit = hear@{ key, startMs, durMs ->
        if (playingKey == key) {
            runCatching { player.stop() }
            playingKey = null
            return@hear
        }
        val f = wavFile ?: return@hear
        val maxMs = ((rec?.audioSamples ?: 0L) / 16L).toInt()
        scope.launch(Dispatchers.IO) {
            runCatching {
                player.reset()
                player.setDataSource(f.absolutePath)
                player.prepare()
                player.seekTo(startMs.coerceIn(0, maxMs.coerceAtLeast(0)))
                player.start()
            }.onSuccess {
                playingKey = key
                delay(durMs.toLong())
                if (playingKey == key) {
                    runCatching { player.stop() }
                    playingKey = null
                }
            }.onFailure {
                playingKey = null
            }
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    // The dashboard's live position, polled only while the whole session
    // plays. A clip ("hear it") does not move the session scrubber: it is a
    // window into the middle of the recording, not the transport.
    var sessionPosMs by remember { mutableStateOf(0) }
    LaunchedEffect(playingKey) {
        if (playingKey == "session") {
            while (isActive) {
                sessionPosMs = runCatching { player.currentPosition }.getOrDefault(0)
                delay(200)
            }
        } else {
            sessionPosMs = 0
        }
    }
    val sessionDurMs = ((rec?.audioSamples ?: 0L) / 16L).toInt()
    // Scrub from the dashboard: seek the running transport, or start one from
    // the chosen moment - a control that does nothing when stopped is a lie.
    val seekSession: (Int) -> Unit = { ms ->
        if (playingKey == "session") runCatching { player.seekTo(ms) }
        else hear("session", ms, (sessionDurMs - ms).coerceAtLeast(0) + 500)
    }

    Scaffold(
        containerColor = c.ground,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = c.inkMuted)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.report_title), fontSize = 17.sp, color = c.ink,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    val r0 = rec
                    if (r0 != null) {
                        val pos = r0.safePosition
                        Text(
                            pos?.let {
                                val nm = data.surahInfo(it.first)?.nameEn
                                    ?: stringResource(R.string.report_surah_fallback, it.first)
                                stringResource(R.string.report_position, nm, it.second)
                            } ?: stringResource(R.string.report_spanned_boundary),
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
                Text(stringResource(R.string.report_loading), color = c.inkFaint, fontSize = 13.sp)
            }

            r == null -> Box(Modifier.fillMaxSize().padding(36.dp), Alignment.Center) {
                Text(
                    stringResource(R.string.report_gone),
                    color = c.inkMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }

            else -> LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { VerdictCard(r, c) }
                // The sound dashboard sits directly under the verdicts: the
                // user's first question after the score is "play me my
                // recitation", and the ticks show where the mistakes live in
                // the recording.
                if (r.audioFile != null) {
                    item {
                        SessionAudioCard(
                            r, c, wavFile, playingKey, hear, sessionPosMs, sessionDurMs, seekSession,
                        )
                    }
                }
                if (r.frames > 0 || r.wordRows.any { it.wt != null }) {
                    item { ObserverCard(c, onOpenObserver) }
                }
                if (r.perAyah.isNotEmpty()) item { AyahCard(r, c) }
                item { BreakdownCard(r, c) }
                item { MissesCard(r, data, c) }
                if (r.v >= 2 && r.wordRows.any { it.st == "WRONG" && it.wu != null }) {
                    item { BlameBandsCard(r, c) }
                }
                if (r.wordRows.isNotEmpty()) item { RecitationLogCard(r, data, c, wavFile != null, playingKey, hear) }
                item {
                    Panel(c) {
                        TextButton(onClick = {
                            clip.setText(AnnotatedString(reportText(r, data)))
                            copied = true
                        }) {
                            Text(
                                if (copied) stringResource(R.string.report_copied)
                                else stringResource(R.string.report_copy_summary),
                                color = c.accent, fontSize = 14.sp,
                            )
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
                                stringResource(R.string.report_engine_detail),
                                fontSize = 11.sp, color = c.inkMuted,
                                letterSpacing = 1.1.sp,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (showDetail) stringResource(R.string.report_hide)
                                else stringResource(R.string.report_show),
                                fontSize = 11.sp, color = c.accent,
                            )
                        }
                        if (showDetail) {
                            Spacer(Modifier.height(10.dp))
                            DetailLine(
                                c, stringResource(R.string.report_detail_ended),
                                if (r.ended) stringResource(R.string.report_yes)
                                else stringResource(R.string.report_no_ended_early),
                            )
                            DetailLine(c, stringResource(R.string.report_detail_reason), r.reason.ifEmpty { "-" })
                            DetailLine(c, stringResource(R.string.report_detail_advances), "${r.moves}")
                            DetailLine(c, stringResource(R.string.report_detail_reversals), "${r.reversals}")
                            DetailLine(c, stringResource(R.string.report_detail_polls), "${r.evaluations}")
                            DetailLine(c, stringResource(R.string.report_detail_unjudgeable), "${r.unjudgeable}")
                            // The failure that presents as silence. Every one of
                            // these words renders UNKNOWN - muted, no highlight -
                            // which looks exactly like a word the reciter has not
                            // reached. Saying so is the whole point of the panel.
                            if (r.noWindowWords > 0) {
                                DetailLine(
                                    c,
                                    stringResource(R.string.report_detail_no_window),
                                    stringResource(
                                        R.string.report_no_window_value,
                                        r.noWindowWords, r.emptyWindows,
                                    ),
                                )
                            }
                            DetailLine(c, stringResource(R.string.report_detail_frames), "${r.frames}")
                            // Two different numbers, deliberately not merged:
                            // `recorded` is every word the engine gave a status
                            // to (including "no verdict"), `judged` is how many it
                            // actually decided. Labelling the first as "judged"
                            // is what made a session that tested nothing report a
                            // large score.
                            DetailLine(c, stringResource(R.string.report_detail_recorded), "${r.recorded}")
                            DetailLine(c, stringResource(R.string.report_stat_decided), "${r.judged}")
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
                                    val label = AdvisoryKind.entries.firstOrNull { it.name == kind }
                                        ?.let { stringResource(it.labelRes) } ?: kind
                                    DetailLine(c, label, "$n")
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.report_audio_polls_note),
                                fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The door to the observer seat.
 *
 * Separate from the report's own cards because it is a different mode of
 * reading the same record: the cards answer "how did it go", the observer
 * replays "what happened, in order, at what time".
 */
@Composable
private fun ObserverCard(c: IqraColors, onOpen: () -> Unit) {
    Panel(c) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.report_watch_again), fontSize = 13.sp, color = c.ink,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                Text(
                    stringResource(R.string.report_watch_again_blurb),
                    fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
                )
            }
            TextButton(onClick = onOpen) {
                Text(stringResource(R.string.report_open), color = c.accent, fontSize = 14.sp)
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

    // The words in the scope, summed from each ayah's own word count where the
    // record carries one. Derived from the record rather than passed in, so a
    // report read months later still knows what it was a report OF.
    //
    // `getOrNull(4)`, not `[4]`: parseRecord builds every per-ayah entry as
    // IntArray(4) - [correct, wrong, skipped, unknown] - so a subscript here
    // throws and takes the entire report down with it, on every record written
    // so far. A sum of zero therefore means "this record does not say", which is
    // not the same claim as "a scope of zero words"; `judgedLine` prints the
    // bare count in that case rather than an invented denominator.
    val scopeWords = remember(r.perAyah) { r.perAyah.values.sumOf { it.getOrNull(4) ?: 0 } }
    val judgedLine = if (scopeWords > 0) stringResource(R.string.report_judged_of, r.judged, scopeWords) else "${r.judged}"

    val reached = r.judged + r.unknown
    val coverage = if (reached > 0) r.judged.toFloat() / reached else null
    val headlineValue: String
    val headlineLabel: String
    val headlineFraction: Float?
    when (metric) {
        ReaderPrefs.Metric.ACCURACY -> {
            headlineValue = r.accuracy?.let { pct1(it) } ?: "—"
            headlineLabel = stringResource(R.string.report_metric_accuracy)
            headlineFraction = r.accuracy
        }
        ReaderPrefs.Metric.COVERAGE -> {
            headlineValue = coverage?.let { pct1(it) } ?: "—"
            headlineLabel = stringResource(R.string.report_metric_coverage)
            headlineFraction = coverage
        }
        ReaderPrefs.Metric.DECIDED -> {
            headlineValue = judgedLine
            headlineLabel = stringResource(R.string.report_stat_decided)
            headlineFraction = null
        }
        ReaderPrefs.Metric.NET -> {
            headlineValue = "${r.correct} / ${r.wrong}"
            headlineLabel = stringResource(R.string.report_metric_net)
            headlineFraction = null
        }
    }

    Panel(c) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A ring is a fraction, so it only ever carries one. DECIDED and NET
            // have no fraction to draw, and putting their bare counts inside the
            // dial claimed a score the session never earned: "23" in a
            // percentage ring reads as 23%. Those metrics draw an empty
            // decorative ring and put the number in the column beside it, where
            // its unit is stated rather than implied.
            AccuracyRing(
                fraction = headlineFraction,
                accent = c.accent,
                track = c.chartEmpty,
                caption = headlineFraction?.let { "$headlineValue%" },
                captionColor = c.ink,
                accentColor = c.ink,
            )
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                // The headline is named exactly once. It used to be printed as a
                // label above a Stat that repeated the same words below it, so the
                // DECIDED metric read "words decided" twice in a row. With a
                // fraction the ring already shows the number and the column only
                // names it; without one the pair moves down here.
                if (headlineFraction != null) {
                    Text(headlineLabel, fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp)
                } else {
                    Stat(c.ink, headlineValue, headlineLabel)
                }
                Spacer(Modifier.height(10.dp))
                // The denominator, finally. "23 words decided" answers nothing on
                // its own - 23 out of a 29-word surah is a good session and 23 out
                // of a 600-word one is barely started, and the report could not tell
                // those apart. `scopeWords` is the scope the session was chosen for,
                // and is omitted rather than faked when the record does not carry it.
                if (metric != ReaderPrefs.Metric.DECIDED) {
                    Stat(c.ink, judgedLine, stringResource(R.string.report_stat_decided))
                    Spacer(Modifier.height(10.dp))
                }
                Stat(c.ink, r.durationSec?.let { formatSecs(it) } ?: "—",
                    stringResource(R.string.report_stat_duration))
                Spacer(Modifier.height(10.dp))
                Stat(
                    c.gold,
                    if (r.wpm > 1.0 && r.judged > 0) "%.0f".format(r.wpm) else "—",
                    stringResource(R.string.report_stat_wpm),
                )
            }
        }
        val full = r.accuracyWithUnknown
        val acc0 = r.accuracy
        // Only where there is an accuracy to revise. Without one there is nothing
        // to say about unheard words, and "accuracy is —" is not a sentence.
        // pct1 carries no unit, so the percent sign is spelled out here: the
        // sentence used to close on a bare "72.4.", which reads as a count.
        if (acc0 != null && full != null && acc0 - full > 0.005f) {
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.report_unheard_note, r.unknown, "${pct1(full)}"),
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
        Text(stringResource(R.string.report_ayah_by_ayah), fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(12.dp))
        AyahStrip(
            outcomes = outcomes,
            good = c.wordGood,
            bad = c.chartBad,
            unknown = c.wordUnknown,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Legend(c, c.wordGood, stringResource(R.string.report_legend_clean), outcomes.count { it == 0 })
        Legend(c, c.chartBad, stringResource(R.string.report_legend_wrong), outcomes.count { it == 1 })
        Legend(c, c.wordUnknown, stringResource(R.string.report_legend_unheard), outcomes.count { it == 2 })
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.hairline))
        Spacer(Modifier.height(10.dp))
        entries.forEach { (key, v) ->
            val ayah = key.substringAfter(':').toIntOrNull() ?: 0
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(stringResource(R.string.report_ayah_n, ayah), fontSize = 11.sp, color = c.inkMuted,
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
            if (v[0] > 0) add(stringResource(R.string.report_counts_right, v[0]))
            if (v[1] > 0) add(stringResource(R.string.report_counts_wrong, v[1]))
            if (v[3] > 0) add(stringResource(R.string.report_counts_unheard, v[3]))
            if (v[2] > 0) add(stringResource(R.string.report_counts_passed, v[2]))
            if (isEmpty()) append(stringResource(R.string.report_counts_none))
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
        Text(stringResource(R.string.report_verdicts), fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(10.dp))
        if (acc != null) {
            CompareBars(
                rows = listOfNotNull(
                    accF?.takeIf { it > 0f }?.let { stringResource(R.string.report_bar_judged) to it },
                    full?.takeIf { it > 0f && (acc == null || it < acc - 0.001f) }
                        ?.let { stringResource(R.string.report_bar_incl_unheard) to it },
                ),
                valueFmt = { "${pct1(it)}%" },
                primary = c.accent,
                secondary = c.inkFaint,
                track = c.chartEmpty,
                labelColor = c.ink,
            )
        } else {
            Text(stringResource(R.string.report_nothing_judged), fontSize = 12.sp, color = c.inkMuted)
        }
        Spacer(Modifier.height(14.dp))
        Row {
            Stat(c.wordGood, "${r.correct}", stringResource(R.string.report_stat_correct))
            Spacer(Modifier.width(20.dp))
            Stat(c.chartBad, "${r.wrong}", stringResource(R.string.report_stat_wrong))
            Spacer(Modifier.width(20.dp))
            Stat(c.inkFaint, "${r.skipped}", stringResource(R.string.report_stat_passed))
            Spacer(Modifier.width(20.dp))
            Stat(c.inkFaint, "${r.unknown}", stringResource(R.string.report_stat_unheard))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.report_verdicts_note),
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
                if (r.judged > 0) stringResource(R.string.report_misses_none_wrong)
                else stringResource(R.string.report_misses_none),
                fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (r.judged > 0) stringResource(R.string.report_misses_none_body)
                else stringResource(R.string.report_misses_none_body2, r.skipped, r.unknown),
                fontSize = 13.sp, lineHeight = 19.sp, color = c.ink,
            )
        }
        return
    }
    Panel(c) {
        Text(stringResource(R.string.report_misses_title), fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp)
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
                    word ?: stringResource(R.string.report_ayah_fallback, ayah),
                    fontSize = 16.sp, fontFamily = quranFont,
                    color = if (word != null) c.wordBad else c.inkMuted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    data.surahInfo(surah)?.nameEn
                        ?: stringResource(R.string.report_surah_fallback, surah),
                    fontSize = 11.sp, color = c.inkFaint,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.report_misses_note),
            fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
        )
    }
}


/**
 * The sound dashboard: the session's own audio, kept on this device only.
 * The transport follows the observer's playhead card (48.dp icon button, one
 * accent tint, a Monospace clock), the scrubber caches its drag like the
 * reference bar's, and above them the mistake map: one tick per wrong
 * verdict at the instant it was decided, gold-capped when the blame sat on
 * the word's first letter - the shape the borrow charges take. The playhead
 * is drawn only while the whole session plays: a marker that marks nothing
 * reads as "nothing is current". A record that predates the capture shows
 * no card at all; a record that names audio whose file is gone says so
 * rather than offering a button that does nothing.
 */
@Composable
private fun SessionAudioCard(
    r: PracticeLog.Record,
    c: IqraColors,
    wavFile: File?,
    playingKey: String?,
    hear: (String, Int, Int) -> Unit,
    posMs: Int,
    durMs: Int,
    onSeek: (Int) -> Unit,
) {
    Panel(c) {
        Text(stringResource(R.string.report_audio_title), fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(6.dp))
        if (wavFile == null) {
            Text(
                stringResource(R.string.report_audio_missing, r.audioFile ?: ""),
                fontSize = 11.sp, color = c.inkFaint,
            )
            return@Panel
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { hear("session", 0, durMs + 500) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    if (playingKey == "session") Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playingKey == "session") stringResource(R.string.report_pause)
                    else stringResource(R.string.report_play),
                    tint = c.accent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "%.1f s / %.0f s".format(posMs / 1000f, durMs / 1000f),
                    fontSize = 15.sp, color = c.ink, fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.report_audio_caption),
                    fontSize = 10.sp, color = c.inkFaint,
                )
            }
        }
        if (durMs > 0) {
            val anchor = r.audioStartMs - r.startedAt
            val wrongs = r.wordRows.filter { it.st == "WRONG" && it.wt != null }
                .map {
                    Triple(
                        (it.wt!!.toLong() - anchor).coerceIn(0, durMs.toLong()).toInt(),
                        it.wu == 0,
                        it.key,
                    )
                }
            // The mistake map. A display element, like ScopeProgressStrip:
            // scrubbing belongs to the slider below, which carries the 48.dp
            // target this strip should not duplicate.
            val playing = playingKey == "session"
            // stringResource is composable-only, so resolve the description here,
            // before the semantics block (which is not a composable context).
            val mistakeMapDesc = stringResource(
                R.string.report_mistake_map_cd, wrongs.size, durMs / 1000,
            )
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.hairline)
                    .semantics {
                        contentDescription = mistakeMapDesc
                    },
            ) {
                for ((t, firstLetter, _) in wrongs) {
                    val x = t / durMs.toFloat() * size.width
                    drawRect(
                        c.wordBad,
                        topLeft = Offset(x - 1.5.dp.toPx(), 0f),
                        size = Size(3.dp.toPx(), size.height),
                    )
                    if (firstLetter) {
                        // Gold caps the first-letter blames: the borrow shape
                        // the desktop replay marks the same way.
                        drawRect(
                            c.gold,
                            topLeft = Offset(x - 1.5.dp.toPx(), 0f),
                            size = Size(3.dp.toPx(), 3.dp.toPx()),
                        )
                    }
                }
                if (playing) {
                    val x = posMs.coerceIn(0, durMs) / durMs.toFloat() * size.width
                    drawRect(
                        c.accent,
                        topLeft = Offset(x - 1.dp.toPx(), 0f),
                        size = Size(2.dp.toPx(), size.height),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            var scrub by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = (scrub ?: posMs.toFloat()).coerceIn(0f, durMs.toFloat()),
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { onSeek(it.toInt()) }
                    scrub = null
                },
                valueRange = 0f..durMs.toFloat(),
                enabled = true,
            )
        }
    }
}


/**
 * Where in their words the blame landed, on a v2 record - the card the
 * binary writer made impossible. A charge on the first letter reads
 * differently from one at the last: the first is "wrong word or wrong
 * start", the last is "finished the word, then slipped" - and the
 * fractions between say how deep into the word the recitation diverged.
 * v1 records carry no finer position than first/past-first, so they get
 * no card here: the per-row labels in the log are the honest v1 view.
 */
@Composable
private fun BlameBandsCard(r: PracticeLog.Record, c: IqraColors) {
    val fr = r.wordRows.mapNotNull { if (it.st == "WRONG") it.wu else null }
    val bands = listOf(
        stringResource(R.string.report_band_first) to fr.count { it == 0 },
        stringResource(R.string.report_band_near_start) to fr.count { it in 1..200 },
        stringResource(R.string.report_band_early) to fr.count { it in 201..400 },
        stringResource(R.string.report_band_middle) to fr.count { it in 401..600 },
        stringResource(R.string.report_band_late) to fr.count { it in 601..800 },
        stringResource(R.string.report_band_near_end) to fr.count { it in 801..999 },
        stringResource(R.string.report_band_last) to fr.count { it >= 1000 },
    ).filter { it.second > 0 }
    if (bands.isEmpty()) return
    Panel(c) {
        Text(stringResource(R.string.report_blame_title), fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        ) {
            bands.forEach { (_, n) ->
                Box(
                    Modifier
                        .weight(n.toFloat())
                        .fillMaxSize()
                        .background(c.wordBad.copy(alpha = 0.45f)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        bands.forEach { (label, n) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                Text(label, fontSize = 12.sp, color = c.ink, modifier = Modifier.weight(1f))
                Text(
                    stringResource(
                        if (n == 1) R.string.report_word_one else R.string.report_word_many, n,
                    ),
                    fontSize = 12.sp, color = c.inkFaint, fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.report_blame_footer, fr.size),
            fontSize = 10.sp, color = c.inkFaint,
        )
    }
}


/**
 * The recitation log — the words as the engine saw them, in the order it
 * decided them.
 *
 * This is idea A (a list capturing the recited words against the actual words
 * of the ayah), on the data a session record honestly holds: the record does
 * not keep a transcript of what was said - it keeps, per expected word, the
 * verdict with the moment it was decided (`wt`) and, for wrong words, where
 * in the word the blame began (`wu`). That is the walk-through the reader can
 * follow: the ayah's own word beside the verdict and the time.
 *
 * Row order is decision order (wt), not mushaf order - a repeat or a
 * backtrack then reads as a repeat. Rows without a decision time (words that
 * received a no-verdict state without a round of the wrong path) follow in
 * record order, under their own heading, so times are never invented.
 *
 * Where the record is silent it says so: suburbs beyond the log (what was
 * actually heard, sound by sound) are the desktop replay's cause panel.
 */
@Composable
private fun RecitationLogCard(
    r: PracticeLog.Record,
    data: QuranData,
    c: IqraColors,
    audioAvailable: Boolean,
    playingKey: String?,
    hear: (String, Int, Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // The log's control: a review pass usually wants the misses; the full
    // walk stays one tap away. Off by default, so the card opens exactly as
    // it always did.
    var wrongOnly by remember { mutableStateOf(false) }
    val timedAll = r.wordRows.filter { it.wt != null }.sortedBy { it.wt }
    val untimedAll = r.wordRows.filter { it.wt == null }
    val timed = if (wrongOnly) timedAll.filter { it.st == "WRONG" } else timedAll
    val untimed = if (wrongOnly) untimedAll.filter { it.st == "WRONG" } else untimedAll
    val rows = if (expanded) timed + untimed else (timed.take(24) + untimed.take(6))

    Panel(c) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.report_log_title), fontSize = 11.sp, color = c.inkMuted, letterSpacing = 1.1.sp)
                Text(
                    stringResource(R.string.report_log_subtitle, r.wordRows.size),
                    fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
                )
            }
            if (r.wordRows.size > rows.size || (timed.size + untimed.size) > 30) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        if (expanded) stringResource(R.string.report_show_first)
                        else stringResource(R.string.report_show_all),
                        fontSize = 11.sp, color = c.accent,
                    )
                }
            }
            TextButton(onClick = { wrongOnly = !wrongOnly }) {
                Text(
                    if (wrongOnly) stringResource(R.string.report_all_words)
                    else stringResource(R.string.report_wrong_only),
                    fontSize = 11.sp, color = c.accent,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
            itemsIndexed(rows) { _, row ->
                val parts = row.key.split(":")
                val surah = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val ayah = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val idx = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val expected = data.getVerse(surah, ayah)?.textClean
                    ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.getOrNull(idx - 1)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (row.st) {
                                "CORRECT" -> "✓"
                                "WRONG" -> "✕"
                                "UNKNOWN" -> "?"
                                else -> "—"
                            },
                            fontSize = 13.sp,
                            color = when (row.st) {
                                "CORRECT" -> c.wordGood
                                "WRONG" -> c.wordBad
                                "UNKNOWN" -> c.inkMuted
                                else -> c.inkFaint
                            },
                            modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                expected ?: stringResource(R.string.report_no_text, surah, ayah),
                                fontSize = 16.sp, fontFamily = quranFont,
                                color = when (row.st) {
                                    "WRONG" -> c.wordBad; "CORRECT" -> c.ink
                                    else -> c.inkMuted
                                },
                            )
                            // For a wrong word, where the mismatch began - the
                            // same bands device_blame uses, so the phone and the
                            // read-back name the same place. Only a v2 record
                            // may claim the last letter or a half: v1's `wu` was
                            // binary, and 1000 meant merely "past the first
                            // unit" - rendering that as a position invented one.
                            if (row.st == "WRONG" && row.wu != null) {
                                Text(
                                    when {
                                        row.wu == 0 -> stringResource(R.string.report_blame_first)
                                        r.v < 2 -> stringResource(R.string.report_blame_past_first)
                                        row.wu >= 1000 -> stringResource(R.string.report_blame_last)
                                        row.wu <= 500 -> stringResource(R.string.report_blame_half1)
                                        else -> stringResource(R.string.report_blame_half2)
                                    },
                                    fontSize = 10.sp, color = c.inkFaint,
                                )
                            }
                            // WHAT the model heard around this verdict, from
                            // the record's own emission stream - not a
                            // transcript, the phoneme symbols in the 2.5 s
                            // before the decision, the same window the
                            // "hear it" clip plays. Absent on records that
                            // predate the emission capture. Label and
                            // symbols are separate texts: mixing Latin and
                            // Arabic in one run scrambles the bidi order,
                            // and the symbols are the content here, so they
                            // set in the Qur'an face, RTL, at readable size.
                            if (row.st == "WRONG" && row.wt != null && r.emMs.isNotEmpty()) {
                                val heard = ArrayList<String>()
                                for (i in r.emMs.indices) {
                                    if (r.emMs[i] >= row.wt - 2500 && r.emMs[i] <= row.wt + 250
                                        && i < r.emissions.size
                                    ) {
                                        heard.add(r.emissions[i])
                                    }
                                }
                                if (heard.isNotEmpty()) {
                                    Row(Modifier.padding(top = 2.dp)) {
                                        Text(
                                            stringResource(R.string.report_heard),
                                            fontSize = 10.sp, color = c.inkFaint,
                                            modifier = Modifier.padding(top = 3.dp),
                                        )
                                        Text(
                                            heard.take(12).joinToString(" ") +
                                                if (heard.size > 12) " …" else "",
                                            fontSize = 15.sp, color = c.inkMuted,
                                            fontFamily = quranFont,
                                            style = androidx.compose.ui.text.TextStyle(
                                                textDirection = TextDirection.Rtl,
                                            ),
                                        )
                                    }
                                }
                            }
                            // Hear what the model heard: the 2.5 s of room
                            // audio before this verdict - the audio the judged
                            // window was built from. WRONG rows only; the
                            // dashboard above plays the whole recitation.
                            if (audioAvailable && row.st == "WRONG" && row.wt != null) {
                                val key = "w:" + row.key
                                val into = ((row.wt - 2500) - (r.audioStartMs - r.startedAt))
                                    .toInt().coerceAtLeast(0)
                                IconButton(
                                    onClick = { hear(key, into, 2600) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        if (playingKey == key) Icons.Filled.Pause
                                        else Icons.Filled.PlayArrow,
                                        if (playingKey == key) stringResource(R.string.report_stop_clip)
                                        else stringResource(R.string.report_hear_clip),
                                        tint = c.accent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            if (row.wt == null) {
                                Text(stringResource(R.string.report_no_time), fontSize = 10.sp, color = c.inkFaint)
                            }
                        }
                    }
                    Text(
                        (row.wt?.let { "%.1f s".format(it / 1000f) } ?: "—") +
                            " · " + surah + ":" + ayah,
                        fontSize = 10.sp, color = c.inkFaint,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.report_log_footer),
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
    a?.let { appendLine("Accuracy: ${pct1(it)}%") }
    if (fu != null && a != null && a - fu > 0.005f) {
        appendLine("Including unheard: ${pct1(fu)}%")
    }
    appendLine("Words judged: ${r.judged}")
    r.durationSec?.let { appendLine("Duration: ${formatSecs(it)}") }
    if (r.wpm > 1.0) appendLine("Pace: %.0f wpm".format(r.wpm))
    appendLine("Correct ${r.correct} · wrong ${r.wrong} · passed over ${r.skipped} · unheard ${r.unknown}")
}
