package com.iqra.quran.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqra.quran.R
import com.iqra.quran.data.WordStatus
import com.iqra.quran.ui.theme.IqraColors
import com.iqra.quran.ui.theme.IqraShape
import com.iqra.quran.ui.theme.LocalIqraColors
import kotlinx.coroutines.delay

/**
 * The engine console: what the recogniser heard, what it decided, and what it
 * is waiting for - as one live surface, at poll cadence.
 *
 * THE CONTRACT. This screen reads only published state. Every value below is
 * a StateFlow or a plain getter on [PracticeViewModel]; nothing polls the
 * engine's internals and nothing here can block the poll loop. That is what
 * makes the screen engine-portable: a future recogniser that keeps the same
 * narrow surface (heardUnits, statusMap, advisoryMap, policyLive, gateReason,
 * decoderState, diagLog) keeps this whole dashboard working with zero changes
 * here. The one piece built for it is `heardUnits` - the model's own phoneme
 * tail, published per poll.
 *
 * LATENCY. Everything updates at the poll's own cadence because the flows
 * themselves update there; the console adds no sampling, no timers, no work
 * between the recogniser and the screen.
 *
 * THE TAJWEED SPOTS. The engine does not yet carry a letter-to-class table
 * (docs/TAJWEED_AND_MEMORISATION.md tracks that gap), so the spots shown here
 * are the ones the engine has MEASURED and already reports: waqf junctions it
 * declined to judge, word-final letters the register does not require
 * sounded, windows it could not anchor, evidence below the floor. They are
 * coloured on the words they belong to - honest amber, never red.
 */
@Composable
fun EngineConsoleScreen(
    vm: PracticeViewModel,
    onBack: () -> Unit,
) {
    val lcG = LocalIqraColors.current
    val ctx = LocalContext.current
    val recording by vm.recording.collectAsStateWithLifecycle()
    val sessionSound by vm.sessionSound.collectAsStateWithLifecycle()
    val heard by vm.heardUnits.collectAsStateWithLifecycle()
    val statusMap by vm.statusMap.collectAsStateWithLifecycle()
    val advisoryMap by vm.advisoryMap.collectAsStateWithLifecycle()
    val scopeProgress by vm.scopeProgress.collectAsStateWithLifecycle()
    val currentKey by vm.currentKey.collectAsStateWithLifecycle()
    val policy by vm.policyLive.collectAsStateWithLifecycle()
    val gateReason by vm.gateReason.collectAsStateWithLifecycle()
    val decoderState by vm.decoderState.collectAsStateWithLifecycle()
    val engineLabel by vm.engineLabel.collectAsStateWithLifecycle()
    val wpm by vm.wpmFlow.collectAsStateWithLifecycle()
    val diag by vm.diagLog.collectAsStateWithLifecycle()
    val activeVerse by vm.activeVerse.collectAsStateWithLifecycle()
    val scopeSurah by vm.scopeSurah.collectAsStateWithLifecycle()
    val needs = remember { vm.policyNeeds() }
    var micDb by remember { mutableStateOf(0f) }
    var micN by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            micDb = vm.micLevel()
            micN = vm.micSampleCount()
            delay(250)
        }
    }
    // The scope's own words, for the reading column: keyed on the words
    // version so a surah handoff re-reads, and frozen between versions.
    val wordsVersion by vm.wordsVersion.collectAsStateWithLifecycle()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.console_title), fontSize = 17.sp,
                        color = lcG.ink, fontWeight = FontWeight.SemiBold)
                    Text(
                        engineLabel.ifEmpty { stringResource(R.string.lens_idle) } +
                            if (sessionSound) "  ·  " + stringResource(R.string.console_rec) else "",
                        fontSize = 11.sp, color = if (sessionSound) lcG.gold else lcG.inkFaint,
                    )
                }
                PulseDot(
                    color = if (recording) accentColor else lcG.inkFaint,
                    label = if (recording) stringResource(R.string.console_listening) else null,
                )
                Spacer(Modifier.width(12.dp))
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // ---- what the model heard ----
                item {
                    ConsolePanel(lcG, stringResource(R.string.console_heard_title)) {
                        if (heard.units.isEmpty()) {
                            Text(stringResource(R.string.console_heard_empty),
                                fontSize = 12.sp, color = lcG.inkFaint)
                        } else {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                heard.units.forEachIndexed { i, u ->
                                    Text(
                                        u,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (i == heard.units.lastIndex) lcG.gold else lcG.inkMuted,
                                        modifier = Modifier
                                            .background(
                                                if (i == heard.units.lastIndex) lcG.gold.copy(alpha = 0.12f)
                                                else Color.Transparent,
                                                RoundedCornerShape(5.dp),
                                            )
                                            .padding(horizontal = 3.dp, vertical = 1.dp),
                                    )
                                }
                            }
                            Text(
                                stringResource(R.string.console_heard_meta, heard.total, heard.base, heard.newestMs),
                                fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = lcG.inkFaint,
                            )
                        }
                    }
                }

                // ---- the reading, with its spots ----
                item {
                    ConsolePanel(lcG, stringResource(R.string.console_reading_title)) {
                        if (scopeProgress.isEmpty()) {
                            Text(stringResource(R.string.console_no_session),
                                fontSize = 12.sp, color = lcG.inkFaint)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val limit = scopeProgress.takeLast(6)
                                limit.forEach { sa ->
                                    val isCurrent = sa.ayah == activeVerse
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${sa.ayah}",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isCurrent) lcG.gold else lcG.inkFaint,
                                            modifier = Modifier.width(22.dp),
                                        )
                                        WordRow(lcG, vm, wordsVersion, sa, scopeSurah, statusMap, advisoryMap)
                                    }
                                    if (isCurrent && sa.words > 0) {
                                        Canvas(
                                            Modifier.fillMaxWidth().padding(start = 22.dp).height(3.dp)
                                        ) {
                                            val frac = (sa.heard.toFloat() / sa.words).coerceIn(0f, 1f)
                                            drawRoundRect(
                                                lcG.surfaceHigh, Offset.Zero, Size(size.width, size.height),
                                                CornerRadius(1.5f, 1.5f),
                                            )
                                            drawRoundRect(
                                                lcG.accent, Offset.Zero,
                                                Size(size.width * frac, size.height),
                                                CornerRadius(1.5f, 1.5f),
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            AdvisoryLegend(lcG)
                        }
                    }
                }

                // ---- the calculation ----
                item {
                    ConsolePanel(lcG, stringResource(R.string.console_calc_title)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LensBar(lcG, stringResource(R.string.lens_next), policy.next, needs.first, Modifier.weight(1f), tick = true)
                            LensBar(lcG, stringResource(R.string.lens_here), policy.here, null, Modifier.weight(1f), tick = false)
                            LensBar(lcG, stringResource(R.string.lens_back), policy.back, needs.third, Modifier.weight(1f), tick = true)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.lens_policy_note,
                                "%.2f".format(needs.first), "%.2f".format(needs.third)),
                            fontSize = 9.sp, color = lcG.inkFaint,
                        )
                        if (policy.stallNote.isNotEmpty() || policy.held.isNotEmpty() || gateReason.isNotEmpty()) {
                            Text(
                                listOfNotNull(
                                    gateReason.takeIf { it.isNotEmpty() },
                                    policy.held.takeIf { it.isNotEmpty() },
                                    policy.stallNote.takeIf { it.isNotEmpty() },
                                ).joinToString("  ·  "),
                                fontSize = 10.sp, color = lcG.gold, maxLines = 2,
                            )
                        }
                    }
                }

                // ---- the event ring ----
                item {
                    ConsolePanel(lcG, stringResource(R.string.console_events_title)) {
                        if (diag.isEmpty()) {
                            Text(stringResource(R.string.console_heard_empty),
                                fontSize = 12.sp, color = lcG.inkFaint)
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                diag.takeLast(12).reversed().forEach { line ->
                                    Text(
                                        line,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = lcG.inkMuted,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                // ---- counts ----
                item {
                    ConsolePanel(lcG, stringResource(R.string.console_counts_title)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LensCount(lcG, "✓", "${statusMap.count { it.value == WordStatus.CORRECT }}", lcG.wordGood, Modifier.weight(1f))
                            LensCount(lcG, "✕", "${statusMap.count { it.value == WordStatus.WRONG }}", lcG.chartBad, Modifier.weight(1f))
                            LensCount(lcG, "؟", "${statusMap.count { it.value == WordStatus.UNKNOWN }}", lcG.inkMuted, Modifier.weight(1f))
                            LensCount(lcG, stringResource(R.string.lens_heard),
                                "${scopeProgress.sumOf { it.heard }}/${scopeProgress.sumOf { it.words }}",
                                lcG.ink, Modifier.weight(1.4f))
                            LensCount(lcG, stringResource(R.string.practice_wpm_short),
                                "%.0f".format(wpm), lcG.ink, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** One scope ayah's words, verdict-coloured, with the advisory spots marked. */
@Composable
private fun WordRow(
    c: IqraColors,
    vm: PracticeViewModel,
    wordsVersion: Int,
    sa: ScopeAyah,
    scopeSurah: Int,
    statusMap: Map<String, WordStatus>,
    advisoryMap: Map<String, Advisory>,
) {
    val words = remember(sa.ayah, wordsVersion) { vm.standWordsFor(sa.ayah) }
    // Word-level colouring in the reader's grammar, plus the amber spot an
    // advisory puts on the exact word it belongs to.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        words.forEach { w ->
            val key = "$scopeSurah:${sa.ayah}:${w.wordInVerse}"
            val st = statusMap[key]
            val spot = advisoryMap[key]
            Text(
                w.text,
                fontFamily = quranFont,
                fontSize = 19.sp,
                color = when {
                    spot != null -> c.gold
                    st == WordStatus.CORRECT -> c.ink
                    st == WordStatus.WRONG -> c.chartBad
                    else -> c.inkMuted
                },
            )
        }
    }
    // The spots: one dot per advisory class present on this ayah.
    val spots = advisoryMap.entries
        .filter { it.key.startsWith("$scopeSurah:${sa.ayah}:") }
        .map { it.value.kind }
        .distinct()
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        spots.forEach { k ->
            Box(
                Modifier.size(7.dp).background(
                    when (k) {
                        AdvisoryKind.MISSED_RULING_POSSIBLE -> c.gold
                        AdvisoryKind.UNMODELLED_FINAL -> c.gold.copy(alpha = 0.7f)
                        AdvisoryKind.LOW_EVIDENCE -> c.inkMuted
                        else -> c.inkFaint
                    },
                    CircleShape,
                ),
            )
        }
    }
}

@Composable
private fun AdvisoryLegend(c: IqraColors) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LegendDot(c, c.gold, stringResource(R.string.console_spot_waqf))
        LegendDot(c, c.gold.copy(alpha = 0.7f), stringResource(R.string.console_spot_final))
        LegendDot(c, c.inkMuted, stringResource(R.string.console_spot_low))
    }
}

@Composable
private fun LegendDot(c: IqraColors, colour: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(colour, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 9.sp, color = c.inkMuted)
    }
}

@Composable
private fun ConsolePanel(c: IqraColors, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(IqraShape.card).background(c.surface).padding(12.dp),
    ) {
        Text(title, fontSize = 10.sp, letterSpacing = 1.1.sp, color = c.inkFaint)
        Spacer(Modifier.height(8.dp))
        content()
    }
}
