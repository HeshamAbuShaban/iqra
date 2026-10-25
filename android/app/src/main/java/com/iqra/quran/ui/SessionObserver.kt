package com.iqra.quran.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.R
import com.iqra.quran.data.PracticeLog
import com.iqra.quran.data.QuranData
import com.iqra.quran.ui.theme.AyahStrip
import com.iqra.quran.ui.theme.IqraColors
import com.iqra.quran.ui.theme.LocalIqraColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The observer seat, on the phone.
 *
 * The desktop replay (`engine/replay/replay_export.py`) reads a record and lets
 * one re-watch the session; this is the same seat, built on the record file
 * itself because the fold that feeds the report drops exactly the timed walk
 * this screen exists to show: the frame ring's timestamps, the word verdicts at
 * `wt`, and the model's emissions with their arrival times (`emMs`).
 *
 * What it can honestly draw is bounded by what the record writes, and the
 * screen says so rather than filling the gaps:
 *
 *  - Verdicts and lock moves carry times, so they place on the timeline.
 *  - Advisories do not (the writer stores key, kind and count), so they are
 *    named as untimed instead of being invented a moment.
 *  - Emission arrival times are poll-granular by their own design (`emMs` is
 *    one time per retained emission), so the heard stream advances in steps.
 *  - Records written before `emMs` existed have no arrival times at all; the
 *    heard panel refuses for them, per the refuse-rather-than-invent rule.
 *
 * It replays, it does not re-judge: nothing here can change a verdict. The
 * colours at time t are the verdicts decided by t, which is what the reader
 * saw at t.
 */
@Composable
fun SessionObserverScreen(
    data: QuranData,
    recordName: String,
    onBack: () -> Unit,
) {
    val c = LocalIqraColors.current
    val ctx = LocalContext.current
    var obs by remember { mutableStateOf<ObsRecord?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var t by rememberSaveable { mutableLongStateOf(0L) }
    var playing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(recordName) {
        val raw = withContext(Dispatchers.IO) { PracticeLog.rawRecord(ctx, recordName) }
        obs = raw?.let { parseObsRecord(it) }
        loaded = true
    }

    // The transport. Real time, 60 ms a tick: a session plays back at the pace
    // it happened. Scrubbing pauses, because holding a slider while it fights
    // the playhead is not a gesture anyone can use.
    LaunchedEffect(playing, obs) {
        val d = obs ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        if (t >= d.durationMs) return@LaunchedEffect
        while (true) {
            delay(60)
            val nt = (t + 60).coerceAtMost(d.durationMs)
            t = nt
            if (nt >= d.durationMs) { playing = false; return@LaunchedEffect }
        }
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
                    Text(stringResource(R.string.obs_title), fontSize = 17.sp, color = c.ink,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Text(
                        recordName.removeSuffix(".json"),
                        fontSize = 11.sp, color = c.inkFaint,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        },
    ) { pad ->
        val d = obs
        when {
            !loaded -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(stringResource(R.string.obs_loading), color = c.inkFaint, fontSize = 13.sp)
            }
            d == null -> Box(Modifier.fillMaxSize().padding(36.dp), Alignment.Center) {
                Text(
                    stringResource(R.string.obs_gone),
                    color = c.inkMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }
            d.frames.isEmpty() && d.words.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(36.dp), Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.obs_no_walk),
                    color = c.inkMuted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }
            else -> Box(Modifier.fillMaxSize().padding(pad)) {
                ObserverBody(d, data, t, playing, c,
                    onSeek = { playing = false; t = it },
                    onTogglePlay = { playing = !playing },
                )
            }
        }
    }
}

@Composable
private fun ObserverBody(
    d: ObsRecord,
    data: QuranData,
    t: Long,
    playing: Boolean,
    c: IqraColors,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
) {
    val cur = remember(d, t) { d.frameAt(t) }
    val ayatIndex = remember(d) { d.ayat.withIndex().associate { (i, a) -> a.key to i } }
    val currentIndex = cur?.let { ayatIndex["${it.surah}:${it.lock}"] } ?: -1
    // Word text is looked up once per record, not once per tick: during
    // playback this composition runs every 60 ms.
    val wordText = remember(d, data) {
        d.words.associate { w ->
            val parts = data.getVerse(w.surah, w.ayah)?.textClean
                ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }
            w.key to parts?.getOrNull(w.idx - 1)
        }
    }
    val heard = remember(d, t) { d.units.filter { it.second <= t } }
    val events = remember(d, t) { d.feedAt(t) }
    val decisions = events.count { it.kind == "word" }
    val movesSoFar = events.count { it.kind == "move" }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Panel(c) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onTogglePlay, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) stringResource(R.string.obs_pause)
                            else stringResource(R.string.obs_play),
                            tint = c.accent, modifier = Modifier.size(30.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "%.1f s".format(t / 1000f) + "  /  %.0f s".format(d.durationMs / 1000f),
                            fontSize = 15.sp, color = c.ink,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            stringResource(R.string.obs_playback_note),
                            fontSize = 10.sp, color = c.inkFaint,
                        )
                    }
                }
                Slider(
                    value = t.coerceIn(0L, d.durationMs).toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..d.durationMs.coerceAtLeast(1L).toFloat(),
                )
                Row {
                    Text(stringResource(R.string.obs_decisions_so_far, decisions),
                        fontSize = 10.sp, color = c.inkFaint)
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.obs_moves_line, movesSoFar, d.movesCounter),
                        fontSize = 10.sp, color = c.inkFaint)
                }
            }
        }

        item {
            Panel(c) {
                Text(stringResource(R.string.obs_lock_title), fontSize = 11.sp, color = c.inkMuted,
                    letterSpacing = 1.1.sp)
                Spacer(Modifier.height(8.dp))
                if (cur != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.obs_lock_pos, cur.surah, cur.lock),
                            fontSize = 18.sp, color = c.ink,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(14.dp))
                        // -1 is the frame ring's "not computed", not a coverage.
                        Text(
                            stringResource(R.string.obs_next) + " " +
                                (if (cur.next >= 0f) pct1(cur.next) + "%" else "—") +
                                " · " + stringResource(R.string.obs_here) + " " +
                                (if (cur.here >= 0f) pct1(cur.here) + "%" else "—"),
                            fontSize = 11.sp, color = c.inkMuted,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                } else {
                    // Before the first frame the engine had not yet looked at a
                    // window; saying nothing is honest, a lock of 1:1 is not.
                    Text(stringResource(R.string.obs_no_frame), fontSize = 13.sp, color = c.inkMuted)
                }
                Spacer(Modifier.height(12.dp))
                if (d.ayat.isNotEmpty()) {
                    val outcomes = d.ayat.map { a ->
                        val ws = d.words.filter { it.ayah == a.ayah && it.surah == a.surah && it.wt <= t }
                        when {
                            ws.any { it.st == "WRONG" } -> 1
                            ws.any { it.st == "CORRECT" } -> 0
                            else -> 2
                        }
                    }
                    AyahStrip(
                        outcomes = outcomes,
                        good = c.wordGood,
                        bad = c.chartBad,
                        unknown = c.wordUnknown,
                        currentIndex = currentIndex,
                        current = c.accent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    // The strip's colours are as-of-now states, not the
                    // report's final outcomes, so they are named rather than
                    // assumed: a reader who has seen the report's strip would
                    // otherwise read "grey" as "unjudged at all".
                    LegendRow(c, c.wordGood, stringResource(R.string.obs_legend_clean), outcomes.count { it == 0 })
                    LegendRow(c, c.chartBad, stringResource(R.string.obs_legend_wrong), outcomes.count { it == 1 })
                    LegendRow(c, c.wordUnknown, stringResource(R.string.obs_legend_pending), outcomes.count { it == 2 })
                }
            }
        }

        item {
            Panel(c) {
                Text(stringResource(R.string.obs_decisions_title), fontSize = 11.sp, color = c.inkMuted,
                    letterSpacing = 1.1.sp)
                Text(
                    stringResource(R.string.obs_decisions_sub),
                    fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
                )
                Spacer(Modifier.height(10.dp))
                if (events.isEmpty()) {
                    Text(stringResource(R.string.obs_nothing_decided), fontSize = 12.sp,
                        color = c.inkMuted)
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        itemsIndexed(events.take(200)) { _, e ->
                            if (e.kind == "move") {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text("→", fontSize = 12.sp, color = c.accent,
                                        modifier = Modifier.padding(end = 8.dp))
                                    Text(e.meta, fontSize = 13.sp, color = c.accent,
                                        modifier = Modifier.weight(1f))
                                    Text("%.1f s".format(e.t / 1000f), fontSize = 10.sp,
                                        color = c.inkFaint, fontFamily = FontFamily.Monospace)
                                }
                            } else {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            when (e.st) {
                                                "CORRECT" -> "✓"
                                                "WRONG" -> "✕"
                                                "UNKNOWN" -> "?"
                                                else -> "—"
                                            },
                                            fontSize = 13.sp,
                                            color = when (e.st) {
                                                "CORRECT" -> c.wordGood
                                                "WRONG" -> c.wordBad
                                                "UNKNOWN" -> c.inkMuted
                                                else -> c.inkFaint
                                            },
                                            modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                                        )
                                        Text(
                                            wordText[e.key]
                                                ?: stringResource(R.string.obs_no_text, e.key),
                                            fontSize = 16.sp, fontFamily = quranFont,
                                            color = when (e.st) {
                                                "WRONG" -> c.wordBad
                                                "CORRECT" -> c.ink
                                                else -> c.inkMuted
                                            },
                                        )
                                    }
                                    Text(
                                        "%.1f s".format(e.t / 1000f) + " · " + e.key,
                                        fontSize = 10.sp, color = c.inkFaint,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Panel(c) {
                Text(stringResource(R.string.obs_heard_title), fontSize = 11.sp, color = c.inkMuted,
                    letterSpacing = 1.1.sp)
                Spacer(Modifier.height(8.dp))
                if (!d.hasEmTimes) {
                    Text(
                        stringResource(R.string.obs_no_em_times, d.units.size),
                        fontSize = 12.sp, lineHeight = 17.sp, color = c.inkMuted,
                    )
                } else if (heard.isEmpty()) {
                    Text(stringResource(R.string.obs_nothing_heard), fontSize = 12.sp, color = c.inkMuted)
                } else {
                    Text(
                        heard.takeLast(48).joinToString(" ") { it.first },
                        fontSize = 20.sp, lineHeight = 34.sp, fontFamily = quranFont,
                        color = c.inkMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.obs_units_heard, heard.size, d.units.size),
                        fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
                    )
                }
            }
        }

        item {
            Panel(c) {
                Text(stringResource(R.string.obs_limits_title), fontSize = 11.sp, color = c.inkMuted,
                    letterSpacing = 1.1.sp)
                Spacer(Modifier.height(8.dp))
                // stringResource only resolves in composable context, so the three
                // sentences are resolved before the (non-composable) buildString.
                val limitsAudio = stringResource(R.string.obs_limits_audio)
                val limitsAdvisories = stringResource(
                    R.string.obs_limits_advisories, d.advisoryCount,
                )
                val limitsCoverage = stringResource(R.string.obs_limits_coverage)
                Text(
                    limitsAudio + limitsAdvisories + limitsCoverage,
                    fontSize = 10.sp, lineHeight = 14.sp, color = c.inkFaint,
                )
            }
        }
    }
}

@Composable
private fun LegendRow(c: IqraColors, colour: androidx.compose.ui.graphics.Color,
                      label: String, n: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(9.dp).height(9.dp)
            .clip(RoundedCornerShape(3.dp)).background(colour))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 11.sp, color = c.inkMuted, modifier = Modifier.weight(1f))
        Text("$n", fontSize = 11.sp, color = c.inkFaint, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Panel(c: IqraColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(c.surface).padding(16.dp),
    ) { content() }
}

// ---- the record, read for the walk ----------------------------------------

private class ObsFrame(
    val t: Long, val surah: Int, val lock: Int,
    val next: Float, val here: Float, val tag: Int,
)

private class ObsWord(
    val key: String, val surah: Int, val ayah: Int, val idx: Int,
    val st: String, val wt: Long,
)

private class ObsAyah(val key: String, val surah: Int, val ayah: Int)

/** One feed line: a verdict at its `wt`, or a lock move at its frame time. */
private class ObsEvent(
    val t: Long, val kind: String,
    val key: String = "", val st: String = "", val meta: String = "",
)

private class ObsRecord(
    val durationMs: Long,
    val frames: List<ObsFrame>,
    val words: List<ObsWord>,
    val units: List<Pair<String, Long>>,
    val hasEmTimes: Boolean,
    val ayat: List<ObsAyah>,
    val movesCounter: Int,
    val advisoryCount: Int,
) {
    /** The last frame at or before [t]; null before the first frame. */
    fun frameAt(t: Long): ObsFrame? = frames.lastOrNull { it.t <= t }

    /**
     * Everything with a time at or before [t], newest first: word verdicts
     * (wt) and lock moves (frames where the lock or surah changed).
     */
    fun feedAt(t: Long): List<ObsEvent> {
        val out = ArrayList<ObsEvent>(words.size / 4 + 64)
        words.forEach { w ->
            if (w.wt in 0..t) {
                out.add(ObsEvent(w.wt, "word", key = w.key, st = w.st))
            }
        }
        var prev: ObsFrame? = null
        frames.forEach { f ->
            val p = prev
            if (p != null && f.t <= t && (f.lock != p.lock || f.surah != p.surah)) {
                val label = when (f.tag) {
                    2 -> "surah handoff → ${f.surah}:${f.lock}"
                    3 -> "page escape → ${f.surah}:${f.lock}"
                    else -> "lock → ${f.surah}:${f.lock}"
                }
                out.add(ObsEvent(f.t, "move", meta = label))
            }
            prev = f
        }
        out.sortByDescending { it.t }
        return out
    }
}

private fun parseObsRecord(json: String): ObsRecord? = try {
    val o = JSONObject(json)
    val started = o.optLong("startedAt", 0L)
    val flushed = o.optLong("flushedAt", 0L)

    val frames = ArrayList<ObsFrame>()
    o.optJSONArray("frames")?.let { fa ->
        for (i in 0 until fa.length()) {
            val f = fa.optJSONObject(i) ?: continue
            frames.add(
                ObsFrame(
                    t = f.optLong("t", -1L),
                    surah = f.optInt("s", 0),
                    lock = f.optInt("lock", 0),
                    next = f.optDouble("next", -1.0).toFloat(),
                    here = f.optDouble("here", -1.0).toFloat(),
                    tag = f.optInt("tag", 0),
                ),
            )
        }
    }
    frames.removeAll { it.t < 0L }

    val words = ArrayList<ObsWord>()
    o.optJSONArray("words")?.let { wa ->
        for (i in 0 until wa.length()) {
            val w = wa.optJSONObject(i) ?: continue
            val key = w.optString("key")
            val parts = key.split(":")
            words.add(
                ObsWord(
                    key = key,
                    surah = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                    ayah = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                    idx = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                    st = w.optString("st"),
                    wt = if (w.has("wt")) w.optLong("wt", -1L) else -1L,
                ),
            )
        }
    }

    val emissions = o.optJSONArray("emissions")
    val emMs = o.optJSONArray("emMs")
    val units = ArrayList<Pair<String, Long>>()
    if (emissions != null && emMs != null) {
        val n = minOf(emissions.length(), emMs.length())
        for (i in 0 until n) {
            units.add(emissions.optString(i) to emMs.optLong(i, -1L))
        }
    }

    val ayat = ArrayList<ObsAyah>()
    o.optJSONArray("ayahStatus")?.let { aa ->
        for (i in 0 until aa.length()) {
            val a = aa.optJSONObject(i) ?: continue
            val key = a.optString("ayah")
            val parts = key.split(":")
            ayat.add(
                ObsAyah(
                    key = key,
                    surah = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                    ayah = parts.getOrNull(1)?.toIntOrNull() ?: 0,
                ),
            )
        }
    }
    ayat.sortWith(compareBy({ it.surah }, { it.ayah }))

    val advisoryCount = o.optJSONArray("advisories")?.let { adv ->
        (0 until adv.length()).sumOf { adv.optJSONObject(it)?.optInt("count", 0) ?: 0 }
    } ?: 0
    val moves = o.optJSONObject("counters")?.optInt("moves", 0) ?: 0

    // The session's own span. Wall clock (flushed - started) and the monotonic
    // clocks the walk uses (frames, wt, emMs) are different instruments and can
    // disagree by a second; the slider spans the furthest anything is known to
    // have happened, so nothing with a time is ever off the end of it.
    var dur = (flushed - started).coerceAtLeast(0L)
    frames.forEach { dur = maxOf(dur, it.t) }
    words.forEach { if (it.wt > 0) dur = maxOf(dur, it.wt) }
    units.forEach { if (it.second > 0) dur = maxOf(dur, it.second) }

    ObsRecord(
        durationMs = dur,
        frames = frames,
        words = words,
        units = units,
        hasEmTimes = units.isNotEmpty(),
        ayat = ayat,
        movesCounter = moves,
        advisoryCount = advisoryCount,
    )
} catch (e: Exception) {
    null
}
