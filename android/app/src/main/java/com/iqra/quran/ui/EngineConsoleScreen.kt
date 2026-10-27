package com.iqra.quran.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqra.quran.R
import com.iqra.quran.data.WordStatus
import com.iqra.quran.ui.theme.IqraColors
import com.iqra.quran.ui.theme.IqraShape
import com.iqra.quran.ui.theme.LocalIqraColors
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * The engine console v2 — the confirmed design: the reciter's view first.
 *
 * ليل الجلسة is the timeline: the recitation's own envelope as a breathing
 * wave, each verdict a star lit at its true decision instant, the lock's walk
 * a dotted moon-path, gate-closed stretches red mist. Everything is
 * interactive against the data that actually exists: the scrubber replays the
 * accumulated session, the envelope and lock trace are sampled by THIS screen
 * from published state, the stars come from the engine's own verdict timeline.
 *
 * The judgment hero reads one ayah's real alignment (expected units from the
 * word-aligned table, heard units from the ayah's window, the mapper's own
 * per-unit charge) — the DP made visible, with the honest empty state when no
 * window exists. The transcript stitches the same heard units into words as
 * they are matched, so the segmented stream reads as recitation.
 *
 * Engine contract: every value here is published state or a read-only
 * accessor (`verdictTimeline`, `heardUnits`, `ayahAlignmentFor`,
 * `policyLive`, `gateReason`, `decoderState`, `diagLog`). Nothing here can
 * block the poll loop, and a future engine that keeps this narrow surface
 * keeps the whole dashboard.
 */
/**
 * The night's data, from whichever truth is open: the running session's
 * samples and verdict flow, or a finished session's own record. What the
 * record does not carry (mic envelope, lock walk, gate history) is absent in
 * record mode; its envelope is the model's own emission density - what it
 * actually received per second, which is the truer activity anyway.
 */
private data class NightData(
    val spanMs: Long,
    val env: List<Pair<Long, Float>>,
    val walk: List<Pair<Long, Int>>?,
    val mist: List<Pair<Long, Boolean>>?,
    val stars: List<Triple<Long, WordStatus, String>>,
)

@Composable
fun EngineConsoleScreen(
    vm: PracticeViewModel,
    recordName: String? = null,
    onBack: () -> Unit,
) {
    val lcG = LocalIqraColors.current
    val ctx = LocalContext.current
    // TWO modes, one screen. LIVE watches the running session's published
    // state; RECORD replays a finished session from its own record - the
    // console is reachable from the report, and a report's session is over:
    // its live state is cleared, its record is the truth. What the record
    // cannot replay (the lock walk, the gate history) is honestly absent
    // rather than invented.
    val rec = remember(recordName) {
        recordName?.let { com.iqra.quran.data.PracticeLog.record(ctx, it) }
    }
    val statusMapR = remember(rec) {
        rec?.wordRows?.filter { it.st != "SKIPPED" }?.associate { it.key to it.st } ?: emptyMap()
    }
    val advisoryMapR = remember(rec) {
        rec?.advisories?.associate { it.key to it } ?: emptyMap()
    }

    val recording by vm.recording.collectAsStateWithLifecycle()
    val sessionSound by vm.sessionSound.collectAsStateWithLifecycle()
    val heard by vm.heardUnits.collectAsStateWithLifecycle()
    val liveStatus by vm.statusMap.collectAsStateWithLifecycle()
    val liveAdvisory by vm.advisoryMap.collectAsStateWithLifecycle()
    val statusOf: Map<String, WordStatus> = if (rec != null) {
        statusMapR.mapValues { runCatching { WordStatus.valueOf(it.value) }.getOrDefault(WordStatus.UNKNOWN) }
    } else liveStatus
    val spots: Set<String> = if (rec != null) advisoryMapR.keys else liveAdvisory.keys
    val scopeProgress by vm.scopeProgress.collectAsStateWithLifecycle()
    val currentKey by vm.currentKey.collectAsStateWithLifecycle()
    val activeVerse by vm.activeVerse.collectAsStateWithLifecycle()
    val scopeSurah by vm.scopeSurah.collectAsStateWithLifecycle()
    val policy by vm.policyLive.collectAsStateWithLifecycle()
    val gateReason by vm.gateReason.collectAsStateWithLifecycle()
    val decoderState by vm.decoderState.collectAsStateWithLifecycle()
    val engineLabel by vm.engineLabel.collectAsStateWithLifecycle()
    val wpm by vm.wpmFlow.collectAsStateWithLifecycle()
    val verdicts by vm.verdictTimeline.collectAsStateWithLifecycle()
    val diag by vm.diagLog.collectAsStateWithLifecycle()
    val needs = remember { vm.policyNeeds() }

    // ---- console-side session sampling: the night's own instrument ----
    var nowMs by remember { mutableLongStateOf(0L) }
    var micNow by remember { mutableFloatStateOf(0f) }
    val env = remember { mutableStateListOf<Pair<Long, Float>>() }
    val lockTrace = remember { mutableStateListOf<Pair<Long, Int>>() }
    val gateTrace = remember { mutableStateListOf<Pair<Long, Boolean>>() }
    LaunchedEffect(Unit) {
        while (true) {
            val t = vm.sessionNowMs()
            nowMs = t
            micNow = if (recording) vm.micLevel() else 0f
            if (recording && (env.isEmpty() || t - env.last().first >= 250)) {
                env.add(t to micNow)
                if (env.size > 1400) env.removeAt(0)
                lockTrace.add(t to (activeVerse ?: 0))
                if (lockTrace.size > 1400) lockTrace.removeAt(0)
                gateTrace.add(t to gateReason.isNotEmpty())
                if (gateTrace.size > 1400) gateTrace.removeAt(0)
            }
            delay(120)
        }
    }

    val night: NightData = if (rec != null) {
        remember(rec) {
            val ems = rec.emMs
            val span = ems.lastOrNull() ?: 1L
            val bins = IntArray(((span / 250) + 1).toInt().coerceAtLeast(1))
            ems.forEach { ms ->
                val b = (ms / 250).toInt()
                if (b in bins.indices) bins[b]++
            }
            val maxB = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
            NightData(
                spanMs = span,
                env = bins.mapIndexed { i, n -> Pair(i * 250L, n.toFloat() / maxB) },
                walk = null, mist = null,
                stars = rec.wordRows.mapNotNull { r ->
                    r.wt?.let { Triple(it.toLong(),
                        runCatching { WordStatus.valueOf(r.st) }.getOrDefault(WordStatus.UNKNOWN), r.key) }
                },
            )
        }
    } else {
        NightData(
            spanMs = maxOf(1L, nowMs),
            env = env.toList(),
            walk = lockTrace.toList(),
            mist = gateTrace.toList(),
            stars = verdicts.map { Triple(it.atMs, it.status, it.key) },
        )
    }
    val nightSrc = if (rec != null) "record: emissions · wordRows[].wt" else "verdictTimeline · mic envelope"

    var selKey by remember { mutableStateOf<String?>(null) }
    val mushaf by vm.mushaf.collectAsStateWithLifecycle()
    val recordSurah = rec?.surahNow ?: 0
    val scopeAyahs: List<Int> = if (rec != null) {
        remember(rec) {
            rec.wordRows.mapNotNull { it.key.split(":").getOrNull(1)?.toIntOrNull() }.distinct()
        }
    } else scopeProgress.map { it.ayah }
    val wordsByAyah: Map<Int, List<com.iqra.quran.data.MushafWord>> = if (rec != null) {
        // The engine's active word list is empty when idle; the record mode
        // reads its words straight off the mushaf pages of the record's surah.
        remember(rec, mushaf, recordSurah) {
            val pages = mushaf.orEmpty()
            if (pages.isEmpty()) emptyMap()
            else com.iqra.quran.data.Mushaf.wordsForSurah(pages, recordSurah)
                .groupBy { it.verse }
        }
    } else {
        scopeProgress.associate { it.ayah to vm.standWordsFor(it.ayah) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- header ----
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back),
                            tint = lcG.ink)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                !recording -> stringResource(R.string.console_idle_state)
                                gateReason.isNotEmpty() -> stringResource(R.string.console_state_gate)
                                else -> stringResource(R.string.console_state_listening)
                            },
                            fontSize = 16.sp, color = if (recording) lcG.ink else lcG.inkMuted,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            engineLabel.ifEmpty { stringResource(R.string.lens_idle) } +
                                if (sessionSound) "  ·  " + stringResource(R.string.console_rec) else "",
                            fontSize = 11.sp,
                            color = if (sessionSound) lcG.gold else lcG.inkFaint,
                        )
                    }
                    BreathingDot(recording)
                    Spacer(Modifier.width(12.dp))
                }
            }

            // ---- the reading: words landing ----
            item {
                ConsoleBand(lcG, stringResource(R.string.console_reading_title), "statusMap + advisoryMap") {
                    if (scopeAyahs.isEmpty()) {
                        EmptyNote(lcG, stringResource(R.string.console_no_session))
                    } else {
                        val lineSurah = if (rec != null) recordSurah else scopeSurah
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            scopeAyahs.takeLast(5).forEach { ayah ->
                                val ws = wordsByAyah[ayah].orEmpty()
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "$ayah",
                                        fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                        color = if (ayah == activeVerse) lcG.gold else lcG.inkFaint,
                                        modifier = Modifier.width(26.dp),
                                    )
                                    ws.forEach { w ->
                                        val key = "$lineSurah:$ayah:${w.wordInVerse}"
                                        val st = statusOf[key]
                                        val spot = key in spots
                                        val sel = key == selKey
                                        Text(
                                            w.text,
                                            fontFamily = quranFont,
                                            fontSize = 23.sp,
                                            color = when {
                                                spot -> lcG.gold
                                                st == WordStatus.WRONG -> lcG.wordBad
                                                st == WordStatus.CORRECT -> lcG.ink
                                                key == currentKey -> lcG.goldBright
                                                else -> lcG.inkMuted.copy(alpha = 0.55f)
                                            },
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(9.dp))
                                                .clickable { selKey = if (sel) null else key }
                                                .background(
                                                    when {
                                                        sel -> lcG.gold.copy(alpha = 0.14f)
                                                        key == currentKey -> lcG.gold.copy(alpha = 0.06f)
                                                        else -> Color.Transparent
                                                    },
                                                )
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                            }
                        }
                        LegendRow(lcG)
                    }
                }
            }

            // ---- the judgment hero ----
            item {
                val sel = selKey
                ConsoleBand(lcG, stringResource(R.string.console_judge_title), "word_aligned_phonemes × ayah window") {
                    if (sel == null) {
                        EmptyNote(lcG, stringResource(R.string.console_pick_word))
                    } else {
                        val parts = sel.split(":")
                        if (rec != null) {
                            // RECORD: the window is the stream as of the word's
                            // own decision instant; the stitch is the console's
                            // light prefix walk, labelled as such.
                            val row = rec.wordRows.firstOrNull { it.key == sel }
                            val alR = remember(sel) {
                                runCatching {
                                    // The table loads with the engine's first
                                    // session; a console opened straight from a
                                    // report (fresh process) loads it here. All
                                    // idempotent, none start a stream.
                                    val app = ctx.applicationContext
                                    com.iqra.quran.ml.PhonemeMapper.ensureTable(
                                        com.iqra.quran.data.AssetPaths.file(app, "ordered_quran_phonemes.json"))
                                    runCatching {
                                        com.iqra.quran.ml.PhonemeMapper.ensureWordTable(
                                            app.assets.open("word_aligned_phonemes.json"))
                                    }
                                    com.iqra.quran.ml.PhonemeMapper.ensureUnits(
                                        com.iqra.quran.data.AssetPaths.file(app, "tokens.txt"))
                                    val exp = com.iqra.quran.ml.PhonemeMapper.expected(
                                        parts[0].toInt(), parts[1].toInt())
                                    if (exp == null) null else {
                                        val wtMs = (row?.wt ?: 0).toLong()
                                        val heardSlice = rec.emissions
                                            .zip(rec.emMs)
                                            .filter { it.second <= wtMs }
                                            .map { it.first }
                                        val matched = BooleanArray(heardSlice.size)
                                        var ei = 0
                                        for (j in heardSlice.indices) {
                                            if (ei < exp.units.size && heardSlice[j] == exp.units[ei]) {
                                                matched[j] = true; ei++
                                            }
                                        }
                                        PracticeViewModel.AyahAlignment(
                                            exp.units, exp.unitWord, heardSlice,
                                            IntArray(heardSlice.size) { -1 },
                                            emptyList(), FloatArray(0),
                                        )
                                    }
                                }.getOrNull()
                            }
                            JudgementHeroRecord(lcG, sel, row, alR)
                        } else {
                            val al = remember(sel, heard, scopeProgress) {
                                runCatching {
                                    vm.ayahAlignmentFor(parts[0].toInt(), parts[1].toInt())
                                }.getOrNull()
                            }
                            val wordIdx = parts.getOrNull(2)?.toIntOrNull() ?: 0
                            JudgementHero(lcG, sel, al, wordIdx)
                        }
                    }
                }
            }

            // ---- pulse: ring + trio + lanterns + counts ----
            item {
                ConsoleBand(lcG, stringResource(R.string.console_pulse_title), "scopeProgress · policyLive") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccuracyRing(lcG, statusOf, Modifier.size(88.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            CoverageBar(lcG, stringResource(R.string.lens_next), policy.next, needs.first, true)
                            CoverageBar(lcG, stringResource(R.string.lens_here), policy.here, null, false)
                            CoverageBar(lcG, stringResource(R.string.lens_back), policy.back, needs.third, true)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.lens_policy_note,
                            "%.2f".format(needs.first), "%.2f".format(needs.third)),
                        fontSize = 9.sp, color = lcG.inkFaint,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Lantern(lcG, stringResource(R.string.lens_gate),
                            gateReason.ifEmpty { stringResource(R.string.lens_open) },
                            gateReason.isEmpty(), lcG.wordGood, lcG.wordBad)
                        Lantern(lcG, stringResource(R.string.lens_vad),
                            when (policy.vadSpeech) {
                                true -> stringResource(R.string.lens_speech)
                                false -> stringResource(R.string.lens_silence)
                                null -> stringResource(R.string.lens_idle)
                            },
                            policy.vadSpeech == true, lcG.wordGood, lcG.inkMuted)
                        Lantern(lcG, stringResource(R.string.lens_decoder),
                            decoderState.ifEmpty { stringResource(R.string.lens_idle) },
                            decoderState.isEmpty(), lcG.inkMuted, lcG.inkMuted)
                        Lantern(
                            lcG, stringResource(R.string.practice_wpm_short),
                            "%.0f".format(wpm), recording, lcG.inkMuted, lcG.inkMuted,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    CountsRow(lcG, statusOf, scopeProgress)
                }
            }

            // ---- breath + transcript ----
            item {
                ConsoleBand(lcG, stringResource(R.string.console_breath_title), "heardUnits × alignment") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        heard.units.forEachIndexed { i, u ->
                            val newest = i == heard.units.lastIndex
                            Text(
                                u,
                                fontFamily = quranFont,
                                fontSize = 17.sp,
                                color = if (newest) lcG.goldBright else lcG.inkMuted.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .background(
                                        if (newest) lcG.gold.copy(alpha = 0.12f) else Color.Transparent,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                        if (heard.units.isEmpty()) {
                            Text(stringResource(R.string.console_heard_empty),
                                fontSize = 12.sp, color = lcG.inkFaint)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (rec != null) {
                        // RECORD: the raw tail is the record's own last units,
                        // and the transcript is every word the session landed,
                        // in recitation order.
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            rec.emissions.takeLast(24).forEach { u ->
                                Text(u, fontFamily = quranFont, fontSize = 15.sp,
                                    color = lcG.inkMuted.copy(alpha = 0.7f))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rec.wordRows.forEach { r ->
                                val st = runCatching { WordStatus.valueOf(r.st) }.getOrDefault(WordStatus.UNKNOWN)
                                Text(
                                    // the expected word, from the table when it can be
                                    vmTextOf(vm, r.key, st),
                                    fontFamily = quranFont,
                                    fontSize = 21.sp,
                                    color = when (st) {
                                        WordStatus.CORRECT -> lcG.ink
                                        WordStatus.WRONG -> lcG.wordBad
                                        else -> lcG.inkMuted.copy(alpha = 0.5f)
                                    },
                                )
                            }
                        }
                    } else {
                        val activeAyah = activeVerse
                        if (activeAyah != null && recording) {
                            val al = remember(activeAyah, heard, scopeProgress) {
                                runCatching { vm.ayahAlignmentFor(scopeSurah, activeAyah) }.getOrNull()
                            }
                            if (al != null) {
                                TranscriptRow(lcG, al)
                            } else {
                                EmptyNote(lcG, stringResource(R.string.console_heard_empty))
                            }
                        } else {
                            EmptyNote(lcG, stringResource(R.string.console_heard_empty))
                        }
                    }
                }
            }

            // ---- the night ----
            item {
                ConsoleBand(lcG, stringResource(R.string.console_night_title), nightSrc) {
                    var scrubT by remember { mutableLongStateOf(0L) }
                    var playing by remember { mutableStateOf(false) }
                    LaunchedEffect(playing) {
                        while (playing) {
                            scrubT = (scrubT + 700).coerceAtMost(night.spanMs)
                            if (scrubT >= night.spanMs && rec != null) playing = false
                            delay(70)
                        }
                    }
                    val viewT = if (scrubT in 1..(night.spanMs - 1)) scrubT else night.spanMs
                    NightCanvas(lcG, night, viewT, Modifier.fillMaxWidth().height(150.dp))
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { playing = !playing }, Modifier.size(34.dp)) {
                            Icon(
                                if (playing) Icons.Filled.Close else Icons.Filled.PlayArrow,
                                stringResource(R.string.live_resume),
                                tint = lcG.inkMuted,
                            )
                        }
                        Slider(
                            value = if (night.spanMs > 0) (viewT.toFloat() / night.spanMs) else 0f,
                            onValueChange = { scrubT = (it * night.spanMs).toLong() },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "%.1f s".format(viewT / 1000f),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = lcG.gold,
                        )
                    }
                    if (rec != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.console_record_note),
                            fontSize = 9.sp, color = lcG.inkFaint)
                    }
                }
            }
        }
    }
}

// ---- hero ----

@Composable
private fun JudgementHero(
    c: IqraColors,
    key: String,
    al: PracticeViewModel.AyahAlignment?,
    wordIdx: Int,
) {
    if (al == null) {
        EmptyNote(c, stringResource(R.string.console_heard_empty))
        return
    }
    // The key's word index is 1-based; the table's wordOf and the mapper's
    // emitWord are 0-based.
    val wi = (wordIdx - 1).coerceAtLeast(0)
    val st = al.statuses.getOrElse(wi) { WordStatus.UNKNOWN }
    val cov = al.coverage.getOrElse(wi) { -1f }
    val ringC = when (st) {
        WordStatus.CORRECT -> c.wordGood
        WordStatus.WRONG -> c.wordBad
        else -> c.inkMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val frac by animateFloatAsState(
            if (cov < 0f) 0.45f else cov.coerceIn(0f, 1f),
            tween(900), label = "seal",
        )
        Canvas(Modifier.size(74.dp)) {
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false,
                style = Stroke(6f, cap = StrokeCap.Round))
            drawArc(ringC, 0f, 360f * frac, false,
                style = Stroke(6f, cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(key, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.inkFaint)
            Spacer(Modifier.height(4.dp))
            Text(
                when (st) {
                    WordStatus.CORRECT -> "✓ " + stringResource(R.string.console_v_ok)
                    WordStatus.WRONG -> "✕ " + stringResource(R.string.console_v_bad)
                    else -> "؟ " + stringResource(R.string.console_v_unknown)
                },
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ringC,
                modifier = Modifier.background(ringC.copy(alpha = 0.12f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
            if (cov >= 0f) {
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.console_units_share, "%.0f".format(cov * 100f)),
                    fontSize = 10.sp, color = c.inkMuted,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val lo = al.wordOf.indexOf(wi).takeIf { it >= 0 } ?: 0
    var hi = al.wordOf.size
    for (i in lo until al.wordOf.size) {
        if (al.wordOf[i] != wi) { hi = i; break }
    }
    val expUnits = al.expected.subList(lo, min(hi, al.wordOf.size))
    val heardIdx = al.emitWord.indices.filter { al.emitWord.getOrNull(it) == wi }
    val heardUnits = heardIdx.map { al.heard[it] }
    BeadRow(c, stringResource(R.string.console_expected), expUnits, c.ink, false)
    Spacer(Modifier.height(7.dp))
    BeadRow(c, stringResource(R.string.console_heard_lbl), heardUnits, c.inkMuted, true)
}

@Composable
private fun BeadRow(c: IqraColors, label: String, units: List<String>, tint: Color, muted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.inkFaint,
            modifier = Modifier.width(66.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (units.isEmpty()) Text("—", fontSize = 15.sp, color = c.inkFaint)
            units.forEach { u ->
                Text(
                    u,
                    fontFamily = quranFont,
                    fontSize = 18.sp,
                    color = tint.copy(alpha = if (muted) 0.8f else 1f),
                    modifier = Modifier
                        .background(c.surfaceHigh, RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * The record-mode hero: expected units from the word-aligned table, the
 * heard tail as of the word's own decision instant, and the console's light
 * prefix stitch - labelled as an approximation, because the engine's own
 * traceback lives in the replay tools, not in the record.
 */
@Composable
private fun JudgementHeroRecord(
    c: IqraColors,
    key: String,
    row: com.iqra.quran.data.PracticeLog.Record.WordRow?,
    al: PracticeViewModel.AyahAlignment?,
) {
    if (al == null) {
        EmptyNote(c, stringResource(R.string.console_heard_empty))
        return
    }
    val st: WordStatus = if (row == null) WordStatus.UNKNOWN
    else runCatching { WordStatus.valueOf(row.st) }.getOrDefault(WordStatus.UNKNOWN)
    val ringC = when (st) {
        WordStatus.CORRECT -> c.wordGood
        WordStatus.WRONG -> c.wordBad
        else -> c.inkMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        val frac by animateFloatAsState(
            if (st == WordStatus.CORRECT) 1f else if (st == WordStatus.WRONG) 0.8f else 0.45f,
            tween(900), label = "sealR",
        )
        Canvas(Modifier.size(74.dp)) {
            drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false,
                style = Stroke(6f, cap = StrokeCap.Round))
            drawArc(ringC, 0f, 360f * frac, false,
                style = Stroke(6f, cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(key, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.inkFaint)
            Spacer(Modifier.height(4.dp))
            Text(
                when (st) {
                    WordStatus.CORRECT -> "✓ " + stringResource(R.string.console_v_ok)
                    WordStatus.WRONG -> "✕ " + stringResource(R.string.console_v_bad)
                    else -> "؟ " + stringResource(R.string.console_v_unknown)
                },
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ringC,
                modifier = Modifier.background(ringC.copy(alpha = 0.12f), RoundedCornerShape(9.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
            row?.wu?.let { wu ->
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.console_blame_unit, if (wu == 0) 1 else 2),
                    fontSize = 10.sp, color = c.inkMuted,
                )
            }
            row?.wt?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.console_decided_at, it / 1000f),
                    fontSize = 10.sp, color = c.gold,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // expected run: the word's own units via wordOf ownership (0-based)
    val wi = ((key.split(":").getOrNull(2)?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0)
    val lo = al.wordOf.indexOf(wi).takeIf { it >= 0 } ?: 0
    var hi = al.wordOf.size
    for (i in lo until al.wordOf.size) {
        if (al.wordOf[i] != wi) { hi = i; break }
    }
    val expUnits = al.expected.subList(lo, min(hi, al.wordOf.size))
    // The heard neighbourhood: the record has no ayah window, so find the
    // word's own first unit in the stream (last occurrence) and show around
    // it - the sounds the word came out of, not the whole session's tail.
    val heardShown = if (expUnits.isEmpty() || al.heard.isEmpty()) al.heard.takeLast(8)
    else {
        val first = expUnits.first()
        val j = al.heard.indexOfLast { it == first }
        if (j < 0) al.heard.takeLast(8)
        else al.heard.subList(
            maxOf(0, j - 1),
            minOf(al.heard.size, j + expUnits.size + 1),
        )
    }
    BeadRow(c, stringResource(R.string.console_expected), expUnits, c.ink, false)
    Spacer(Modifier.height(7.dp))
    BeadRow(c, stringResource(R.string.console_heard_lbl), heardShown, c.inkMuted, true)
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.console_stitch_note), fontSize = 9.sp, color = c.inkFaint)
}

@Composable
private fun vmTextOf(vm: PracticeViewModel, key: String, st: WordStatus): String {
    val p = key.split(":")
    val surah = p.getOrNull(0)?.toIntOrNull() ?: return "…"
    val ayah = p.getOrNull(1)?.toIntOrNull() ?: return "…"
    val wiv = p.getOrNull(2)?.toIntOrNull() ?: return "…"
    val w = remember(key) { runCatching { vm.standWordsFor(ayah) }.getOrNull() }
    return w?.firstOrNull { it.wordInVerse == wiv && it.surah == surah }?.text
        ?: st.let { "…" }
}

// ---- transcript ----

@Composable
private fun TranscriptRow(c: IqraColors, al: PracticeViewModel.AyahAlignment) {
    val words = remember(al) {
        val byWord = LinkedHashMap<Int, StringBuilder>()
        al.expected.forEachIndexed { i, u ->
            val w = al.wordOf.getOrElse(i) { -1 }
            byWord.getOrPut(w) { StringBuilder() }.append(u)
        }
        byWord
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        words.forEach { (wi, sb) ->
            val st = al.statuses.getOrElse(wi) { WordStatus.UNKNOWN }
            Text(
                sb.toString(),
                fontFamily = quranFont,
                fontSize = 22.sp,
                color = when (st) {
                    WordStatus.CORRECT -> c.ink
                    WordStatus.WRONG -> c.wordBad
                    else -> c.inkMuted.copy(alpha = 0.5f)
                },
            )
        }
    }
}

// ---- meters ----

@Composable
private fun AccuracyRing(c: IqraColors, statusMap: Map<String, WordStatus>, size: Modifier) {
    val decided = statusMap.count { it.value == WordStatus.CORRECT || it.value == WordStatus.WRONG }
    val good = statusMap.count { it.value == WordStatus.CORRECT }
    val frac = if (decided == 0) 0f else good.toFloat() / decided
    val anim by animateFloatAsState(frac, tween(900), label = "acc")
    Box(size, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 7f
            drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(c.accent, 0f, 360f * anim, false, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "%.0f%%".format(frac * 100f),
                fontSize = 18.sp, color = c.ink, fontFamily = FontFamily.Monospace,
            )
            Text(stringResource(R.string.metric_accuracy), fontSize = 8.5.sp, color = c.inkFaint)
        }
    }
}

@Composable
private fun CoverageBar(c: IqraColors, label: String, coverage: Float, threshold: Float?, tick: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.inkFaint, modifier = Modifier.width(52.dp))
        Box(Modifier.weight(1f).height(7.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRoundRect(c.surfaceHigh, Offset.Zero, size, CornerRadius(3.5f, 3.5f))
                if (coverage >= 0f) {
                    drawRoundRect(
                        c.accent, Offset.Zero,
                        Size(size.width * coverage.coerceIn(0f, 1f), size.height),
                        CornerRadius(3.5f, 3.5f),
                    )
                }
                if (tick && threshold != null) {
                    drawRoundRect(
                        c.gold, Offset(size.width * threshold - 1f, -2f),
                        Size(2f, size.height + 4f), CornerRadius(1f, 1f),
                    )
                }
            }
        }
        Text(
            if (coverage < 0f) "—" else "%.0f%%".format(coverage * 100f),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            color = if (threshold != null && coverage >= threshold) c.gold else c.ink,
            modifier = Modifier.width(38.dp),
        )
    }
}

@Composable
private fun Lantern(
    c: IqraColors, label: String, value: String, healthy: Boolean,
    good: Color, dim: Color, modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(10.dp)
                .then(if (healthy) Modifier.border(2.dp, good.copy(alpha = 0.25f), CircleShape) else Modifier)
                .background(if (healthy) good else dim, CircleShape),
        )
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 10.sp, color = c.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 8.sp, color = c.inkFaint, maxLines = 1)
    }
}

@Composable
private fun CountsRow(
    c: IqraColors,
    statusMap: Map<String, WordStatus>,
    scopeProgress: List<ScopeAyah>,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LensCount(c, "✓", "${statusMap.count { it.value == WordStatus.CORRECT }}", c.wordGood, Modifier.weight(1f))
        LensCount(c, "✕", "${statusMap.count { it.value == WordStatus.WRONG }}", c.wordBad, Modifier.weight(1f))
        LensCount(c, "؟", "${statusMap.count { it.value == WordStatus.UNKNOWN }}", c.inkMuted, Modifier.weight(1f))
        LensCount(
            c, stringResource(R.string.lens_heard),
            "${scopeProgress.sumOf { it.heard }}/${scopeProgress.sumOf { it.words }}",
            c.ink, Modifier.weight(1.4f),
        )
    }
}

// ---- the night ----

@Composable
private fun NightCanvas(
    c: IqraColors,
    night: NightData,
    viewT: Long,
    modifier: Modifier = Modifier,
) {
    val env = night.env
    val span = night.spanMs
    Canvas(modifier) {
        val h = size.height
        val w = size.width
        val xOf = { t: Long -> (t.toFloat() / span) * w }
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to c.accent.copy(alpha = 0.05f),
                1f to c.accent.copy(alpha = 0.12f),
            ),
            topLeft = Offset(0f, h * 0.45f), size = Size(w, h * 0.55f),
        )
        // gate mist (live only; a record carries no gate history)
        night.mist?.let { mist ->
            var mistStart = -1L
            mist.forEach { (t, closed) ->
                if (closed && mistStart < 0) mistStart = t
                if (!closed && mistStart >= 0) {
                    drawRect(
                        c.wordBad.copy(alpha = 0.07f),
                        topLeft = Offset(xOf(mistStart), 0f),
                        size = Size(xOf(t) - xOf(mistStart), h),
                    )
                    mistStart = -1L
                }
            }
        }
        // envelope wave up to viewT
        val pts = env.filter { it.first <= viewT }
        if (pts.isNotEmpty()) {
            val fill = androidx.compose.ui.graphics.Path()
            fill.moveTo(0f, h)
            pts.forEach { (t, lv) -> fill.lineTo(xOf(t), h * 0.74f - lv * h * 0.3f) }
            fill.lineTo(xOf(pts.last().first), h); fill.close()
            drawPath(fill, c.accent.copy(alpha = 0.14f))
            val crest = androidx.compose.ui.graphics.Path()
            pts.forEachIndexed { i, (t, lv) ->
                val p = Offset(xOf(t), h * 0.74f - lv * h * 0.3f)
                if (i == 0) crest.moveTo(p.x, p.y) else crest.lineTo(p.x, p.y)
            }
            drawPath(crest, c.accent.copy(alpha = 0.5f), style = Stroke(1.5f))
        }
        // lock path (live only; the record carries no frame walk)
        night.walk?.let { walk ->
            val lockPts = walk.filter { it.first <= viewT }
            val maxAyah = (walk.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
            var prev: Offset? = null
            lockPts.forEach { (t, ayah) ->
                val p = Offset(xOf(t), h * 0.5f - (ayah.toFloat() / maxAyah) * h * 0.26f)
                prev?.let { drawLine(c.gold.copy(alpha = 0.4f), it, p, strokeWidth = 1.2f) }
                drawCircle(c.gold.copy(alpha = 0.7f), 1.8f, p)
                prev = p
            }
        }
        // verdict stars
        night.stars.filter { it.first <= viewT }.forEach { (at, status, _) ->
            val x = xOf(at)
            val y = h * 0.24f - (at % 977) / 977f * h * 0.1f
            drawCircle(
                when (status) {
                    WordStatus.CORRECT -> c.goldBright.copy(alpha = 0.9f)
                    WordStatus.WRONG -> c.wordBad
                    else -> c.inkMuted.copy(alpha = 0.6f)
                },
                if (status == WordStatus.WRONG) 3.4f else 2f,
                Offset(x, y),
            )
        }
        // now cursor
        val nx = xOf(viewT)
        drawLine(c.goldBright.copy(alpha = 0.8f), Offset(nx, 0f), Offset(nx, h), strokeWidth = 1f)
        drawCircle(c.goldBright, 3f, Offset(nx, h * 0.74f))
    }
}

// ---- furniture ----

@Composable
private fun ConsoleBand(c: IqraColors, title: String, src: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(IqraShape.card).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontSize = 10.sp, letterSpacing = 1.2.sp, color = c.inkFaint)
            Text(src, fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = c.inkFaint.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(9.dp))
        content()
    }
}

@Composable
private fun EmptyNote(c: IqraColors, text: String) {
    Text(text, fontSize = 12.sp, color = c.inkFaint)
}

@Composable
private fun LegendRow(c: IqraColors) {
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.console_legend), fontSize = 9.sp, color = c.inkFaint)
}

@Composable
private fun BreathingDot(active: Boolean) {
    val a by animateFloatAsState(if (active) 1f else 0.35f, tween(600), label = "dot")
    Box(
        Modifier
            .size(11.dp)
            .graphicsLayer { alpha = a }
            .background(
                if (active) Color(0xFF2BB6A0) else Color(0xFF5E716A),
                CircleShape,
            )
            .border(2.dp, if (active) Color(0x332BB6A0) else Color.Transparent, CircleShape),
    )
}
