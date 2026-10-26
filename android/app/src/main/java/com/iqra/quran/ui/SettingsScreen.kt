package com.iqra.quran.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.iqra.quran.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqra.quran.BuildConfig
import com.iqra.quran.data.AssetPaths
import com.iqra.quran.data.AudioPrefs
import com.iqra.quran.data.AudioStore
import com.iqra.quran.data.PracticeLog
import com.iqra.quran.data.Reciters
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import com.iqra.quran.ui.theme.IqraTheme
import com.iqra.quran.ui.theme.IqraShape
import com.iqra.quran.ui.theme.IqraPalettes
import com.iqra.quran.ui.theme.LocalIqraColors
import com.iqra.quran.ui.theme.liveColorsFor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi

/**
 * The app's settings screen.
 *
 * It did not have one. Three settings existed - night mode and the two
 * brightness sliders - and all three were reachable only from inside the reader:
 * night from an icon tap, the sliders from a long-press on that same icon,
 * which opens a 296dp popover floating over the page. So the only way to change
 * how the text looked was to open a surah, start reading, and long-press a
 * moon. Nothing else in the app was configurable, and there was no place a
 * first-time user would look to find out.
 *
 * Those controls are deliberately still reachable from the reader. Settings that
 * only exist on a settings screen are settings most people never change, and
 * tuning brightness mid-session is exactly when you want it. This screen is the
 * discoverable copy, not the replacement.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenData: () -> Unit = {},
    onOpenProgress: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
    onOpenDiag: () -> Unit = {},
    onOpenConsole: () -> Unit = {},
) {
    val ctx = LocalContext.current
    // The store's change tick, COLLECTED FOR REAL.
    //
    // This line used to read `ReaderPrefs.tick.collectAsStateWithLifecycle()`
    // and throw the result away, which is the worst possible no-op: Compose
    // only recomposes a composable when the state it reads is actually read
    // during composition, so subscribing and discarding subscribed to nothing.
    // Every control below reads its value through a plain getter
    // (`ReaderPrefs.fontScalePercent(ctx)`), so a write changed the store and
    // nothing recomposed - the label, the live preview and the slider thumb all
    // disagreed with what had been written. It read as "the sliders are stuck".
    //
    // So the tick is now both collected and used: every getter below is keyed
    // on it through `remember(tick)`, which is what re-reads the store and
    // invalidates on a write.
    val tick by ReaderPrefs.tick.collectAsStateWithLifecycle()

    var confirmClear by remember { mutableStateOf(false) }
    val log = remember { PracticeLog.load(ctx) }
    // Same write-then-reread pattern as `tick`, for the audio prefs in their
    // own store: the getters below are keyed on this, and each control bumps
    // it, which is what makes a write visible without leaving the screen.
    var audioTick by remember { mutableStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SettingsSection(stringResource(R.string.language)) {
                LanguageRow(tick)
            }

            SettingsSection(stringResource(R.string.settings_section_reading)) {
                TextSizeRow(tick)
                SettingDivider()
                NightSettings(tick)
                SettingDivider()
                // RTL belongs here rather than in its own section: it is a reading
                // preference like text size, it mirrors every screen at once, and the
                // recitation screen's control order depends on it.
                SwitchRow(
                    title = stringResource(R.string.settings_rtl_title),
                    subtitle = stringResource(R.string.settings_rtl_subtitle),
                    checked = remember(tick) { ReaderPrefs.rightToLeft(ctx) },
                    onChange = { ReaderPrefs.setRightToLeft(ctx, it) },
                )
            }

            // Scoped separately from the app theme on purpose. The recitation screen
            // has the only continuously moving element in the app, and someone who
            // wants that still off while reading a page does not want it switched on
            // for a practice session either.
            SettingsSection(stringResource(R.string.settings_section_theme)) {
                ThemeChooser(tick)
            }

            // The recitation screen gets its own theme, because it is not a page.
            // It is one glowing element on a dark ground, and its palette is
            // derived rather than shared, so the app theme was never really
            // deciding it - it was only supplying the seed. Someone reading
            // tafseer in daylight and reciting in a dim room at night can want
            // two different answers, and before this there was one knob for both.
            SettingsSection(stringResource(R.string.settings_section_recitation)) {
                LiveThemeChooser(tick)
                SettingDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_live_animation_title),
                    subtitle = stringResource(R.string.settings_live_animation_subtitle),
                    checked = remember(tick) { ReaderPrefs.liveAnimation(ctx) },
                    onChange = { ReaderPrefs.setLiveAnimation(ctx, it) },
                )
                SettingDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_vibrate_title),
                    subtitle = stringResource(R.string.settings_vibrate_subtitle),
                    checked = remember(tick) { ReaderPrefs.vibrateOnWrong(ctx) },
                    onChange = { ReaderPrefs.setVibrateOnWrong(ctx, it) },
                )
                SettingDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_keep_awake_title),
                    subtitle = stringResource(R.string.settings_keep_awake_subtitle),
                    checked = remember(tick) { ReaderPrefs.keepAwake(ctx) },
                    onChange = { ReaderPrefs.setKeepAwake(ctx, it) },
                )
            }

            // ----------------------------------------------------------------
            // Recognition
            //
            // This section answers a question the app could not previously
            // answer at all: what is this engine actually able to judge?
            //
            // The numbers in the model card are measured, not estimated, from
            // the tajweed register over all 51,395 madd sites - see
            // docs/TAJWEED_AND_MEMORISATION.md. They are here because a tool that
            // can accuse someone of misreading their scripture owes them the
            // list of things it cannot see.
            // ----------------------------------------------------------------
            SettingsSection(stringResource(R.string.settings_section_recognition)) {
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            stringResource(R.string.settings_model_card_title),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        ModelCardGap(
                            stringResource(R.string.settings_madd_title),
                            stringResource(R.string.settings_gap_madd_detail),
                        )
                        ModelCardGap(
                            stringResource(R.string.settings_waqf_title),
                            stringResource(R.string.settings_gap_waqf_detail),
                        )
                        ModelCardGap(
                            stringResource(R.string.settings_gap_idgham_title),
                            stringResource(R.string.settings_gap_idgham_detail),
                        )
                        ModelCardGap(
                            stringResource(R.string.settings_gap_silent_title),
                            stringResource(R.string.settings_gap_silent_detail),
                        )
                    }
                }

                Text(
                    stringResource(R.string.settings_never_accuse_on),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                SwitchRow(
                    title = stringResource(R.string.settings_madd_title),
                    subtitle = stringResource(R.string.settings_madd_never_subtitle),
                    checked = remember(tick) { ReaderPrefs.maddNeverAccuses(ctx) },
                    onChange = { ReaderPrefs.setMaddNeverAccuses(ctx, it) },
                )
                SettingDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_waqf_title),
                    subtitle = stringResource(R.string.settings_waqf_never_subtitle),
                    checked = remember(tick) { ReaderPrefs.waqfNeverAccuses(ctx) },
                    onChange = { ReaderPrefs.setWaqfNeverAccuses(ctx, it) },
                )
                SettingDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_unanchored_title),
                    subtitle = stringResource(R.string.settings_unanchored_subtitle),
                    checked = remember(tick) { ReaderPrefs.unanchoredNeverAccuses(ctx) },
                    onChange = { ReaderPrefs.setUnanchoredNeverAccuses(ctx, it) },
                )
                SettingDivider()
                Text(
                    stringResource(R.string.settings_engine_may_tell),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                val alarmOptions = ReaderPrefs.AdvisoryAlarm.entries.map { stringResource(it.labelRes) }
                ChoiceRow(
                    label = stringResource(R.string.settings_engine_note_label),
                    options = alarmOptions,
                    selected = stringResource(remember(tick) { ReaderPrefs.advisoryAlarm(ctx).labelRes }),
                    onSelect = { label ->
                        val i = alarmOptions.indexOf(label)
                        if (i >= 0) ReaderPrefs.setAdvisoryAlarm(ctx, ReaderPrefs.AdvisoryAlarm.entries[i])
                    },
                )
                Text(
                    stringResource(remember(tick) { ReaderPrefs.advisoryAlarm(ctx).explainRes }),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
                val displayOptions = ReaderPrefs.AdvisoryDisplay.entries.map { stringResource(it.labelRes) }
                ChoiceRow(
                    label = stringResource(R.string.settings_engine_notes_where),
                    options = displayOptions,
                    selected = stringResource(remember(tick) { ReaderPrefs.advisoryDisplay(ctx).labelRes }),
                    onSelect = { label ->
                        val i = displayOptions.indexOf(label)
                        if (i >= 0) ReaderPrefs.setAdvisoryDisplay(ctx, ReaderPrefs.AdvisoryDisplay.entries[i])
                    },
                )
                Text(
                    stringResource(R.string.settings_how_strict),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                val strictnessOptions = ReaderPrefs.Strictness.entries.map { stringResource(it.labelRes) }
                ChoiceRow(
                    label = stringResource(R.string.settings_evidence_label),
                    options = strictnessOptions,
                    selected = stringResource(remember(tick) { ReaderPrefs.strictness(ctx).labelRes }),
                    onSelect = { label ->
                        val i = strictnessOptions.indexOf(label)
                        if (i >= 0) ReaderPrefs.setStrictness(ctx, ReaderPrefs.Strictness.entries[i])
                    },
                )
                Text(
                    stringResource(
                        R.string.settings_heard_floor_explain,
                        remember(tick) { (ReaderPrefs.heardFloor(ctx) * 100).toInt() },
                    ),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                SettingDivider()
                Text(
                    stringResource(R.string.settings_accuracy_means),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                val metricOptions = ReaderPrefs.Metric.entries.map { stringResource(it.labelRes) }
                ChoiceRow(
                    label = stringResource(R.string.settings_metric_shown_in),
                    options = metricOptions,
                    selected = stringResource(remember(tick) { ReaderPrefs.metric(ctx).labelRes }),
                    onSelect = { label ->
                        val i = metricOptions.indexOf(label)
                        if (i >= 0) ReaderPrefs.setMetric(ctx, ReaderPrefs.Metric.entries[i])
                    },
                )
                Text(
                    stringResource(remember(tick) { ReaderPrefs.metric(ctx).explainRes }),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                SettingDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_diagnostics_title),
                    subtitle = stringResource(R.string.settings_diagnostics_subtitle),
                    checked = remember(tick) { ReaderPrefs.diagnostics(ctx) },
                    onChange = { ReaderPrefs.setDiagnostics(ctx, it) },
                )
            }

            // The reference recitation used to be one hardcoded reciter,
            // streamed fresh on every tap and thrown away. This section is the
            // control surface for what replaced it: who recites, whether the
            // audio is kept, and what the transport does with it.
            SettingsSection(stringResource(R.string.settings_section_reference_audio)) {
                ReciterRow(audioTick) { audioTick++ }
                SettingDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_keep_copy_title),
                    subtitle = stringResource(R.string.settings_keep_copy_subtitle),
                    checked = remember(audioTick) { AudioPrefs.keepOffline(ctx) },
                    onChange = { AudioPrefs.setKeepOffline(ctx, it); audioTick++ },
                )
                // Dependent rows say why they are inert rather than sitting
                // there as dead switches: with saving off, a Wi-Fi rule has
                // nothing to govern, and a control that does nothing is a lie
                // about the app's state.
                if (remember(audioTick) { AudioPrefs.keepOffline(ctx) }) {
                    SettingDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_wifi_only_title),
                        subtitle = stringResource(R.string.settings_wifi_only_subtitle),
                        checked = remember(audioTick) { AudioPrefs.wifiOnly(ctx) },
                        onChange = { AudioPrefs.setWifiOnly(ctx, it); audioTick++ },
                    )
                } else {
                    Text(
                        stringResource(R.string.settings_wifi_rule_off_note),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                SettingDivider()
                val speedOptions = listOf(75, 100, 125, 150)
                ChoiceRow(
                    label = stringResource(R.string.settings_playback_speed),
                    options = speedOptions.map { speedPctLabel(it) },
                    selected = remember(audioTick) {
                        speedPctLabel(AudioPrefs.speedPercent(ctx))
                    },
                    onSelect = { label ->
                        speedOptions.firstOrNull { speedPctLabel(it) == label }?.let {
                            AudioPrefs.setSpeedPercent(ctx, it)
                            audioTick++
                        }
                    },
                )
                SettingDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_auto_next_title),
                    subtitle = stringResource(R.string.settings_auto_next_subtitle),
                    checked = remember(audioTick) { AudioPrefs.autoNext(ctx) },
                    onChange = { AudioPrefs.setAutoNext(ctx, it); audioTick++ },
                )
                SettingDivider()
                val downloads = remember(audioTick) { AudioStore.list(ctx) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_downloaded_audio), fontSize = 15.sp)
                        Text(
                            when (downloads.size) {
                                0 -> stringResource(R.string.settings_nothing_saved)
                                1 -> stringResource(
                                    R.string.settings_one_surah_size,
                                    fmtMb(downloads.sumOf { it.bytes }),
                                )
                                else -> stringResource(
                                    R.string.settings_n_surahs_size,
                                    downloads.size,
                                    fmtMb(downloads.sumOf { it.bytes }),
                                )
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    // Always enabled: the library's own empty state explains
                    // what saving is, and it was unreachable in exactly that
                    // state - the one time a reader needed the explanation.
                    TextButton(onClick = onOpenLibrary) {
                        Text(stringResource(R.string.settings_manage))
                    }
                }
            }

            SettingsSection(stringResource(R.string.settings_section_your_data)) {
                val statuses = remember { AssetPaths.allStatuses(ctx) }
                val missing = statuses.filter { !it.present }
                val presentBytes = statuses.filter { it.present }.sumOf { it.actualBytes }

                Text(
                    if (missing.isEmpty()) {
                        stringResource(
                            R.string.settings_data_all_present,
                            statuses.size,
                            fmtMb(presentBytes),
                        )
                    } else {
                        stringResource(
                            R.string.settings_data_missing_count,
                            missing.size,
                            statuses.size,
                        )
                    },
                    fontSize = 13.sp,
                    color = if (missing.isEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenData, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_manage_data_files))
                }

                SettingDivider()

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_practice_history), fontSize = 15.sp)
                        Text(
                            when (log.size) {
                                0 -> stringResource(R.string.settings_nothing_recorded)
                                1 -> stringResource(R.string.settings_one_session)
                                else -> stringResource(R.string.settings_n_sessions, log.size)
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    if (log.isNotEmpty()) {
                        TextButton(onClick = onOpenProgress) { Text(stringResource(R.string.settings_view)) }
                        TextButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Filled.Delete, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.settings_clear), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                SettingDivider()

                // The retention cap was invisible: the engine silently kept the
                // last 10 records and deleted everything older, and the only way
                // to learn it was to lose a session. The count is now a choice,
                // and the deletion rule is stated where the choice is made.
                val dataTick = remember { mutableIntStateOf(0) }
                val sessionsPrefs = remember { ctx.getSharedPreferences("iqra_sessions", 0) }
                val keepChoices = listOf(5, 10, 25, 50, 100)
                ChoiceRow(
                    label = stringResource(R.string.settings_sessions_kept_label),
                    options = keepChoices.map { "$it" },
                    selected = remember(dataTick.intValue) {
                        val k = sessionsPrefs.getInt("keep", 10)
                        keepChoices.firstOrNull { it == k }?.toString() ?: "10"
                    },
                    onSelect = { label ->
                        sessionsPrefs.edit()
                            .putInt("keep", label.toIntOrNull() ?: 10)
                            .apply()
                        dataTick.intValue++
                    },
                )
                Text(
                    stringResource(R.string.settings_retention_note),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = stringResource(R.string.settings_keep_audio_title),
                    subtitle = stringResource(R.string.settings_keep_audio_subtitle),
                    checked = remember(dataTick.intValue) { sessionsPrefs.getBoolean("capture", true) },
                    onChange = {
                        sessionsPrefs.edit().putBoolean("capture", it).apply()
                        dataTick.intValue++
                    },
                )
                val wavStats = remember(dataTick.intValue) {
                    val dir = java.io.File(ctx.filesDir, "sessions")
                    val wavs = dir.listFiles { _, n -> n.endsWith(".wav") }
                    (wavs?.size ?: 0) to (wavs?.sumOf { it.length() } ?: 0L)
                }
                Text(
                    when {
                        wavStats.first == 0 -> stringResource(R.string.settings_no_session_audio)
                        wavStats.first == 1 -> stringResource(
                            R.string.settings_session_audio_disk_one,
                            fmtMb(wavStats.second),
                        )
                        else -> stringResource(
                            R.string.settings_session_audio_disk,
                            fmtMb(wavStats.second),
                            wavStats.first,
                        )
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            SettingsSection(stringResource(R.string.settings_section_about)) {
                // Read, not written. "0.1.0" was a hardcoded string, so the
                // About screen showed the same version for every install while
                // the record's build tag moved independently - two claims about
                // which build was running, neither checkable against the other.
                KeyValue(
                    stringResource(R.string.settings_version),
                    "${BuildConfig.VERSION_NAME} · #${BuildConfig.VERSION_CODE}",
                )
                KeyValue(stringResource(R.string.settings_mushaf), stringResource(R.string.settings_mushaf_value))
                KeyValue(
                    stringResource(R.string.settings_section_recognition),
                    stringResource(R.string.settings_ondevice_value),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.settings_privacy_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(6.dp))
                // The engine surfaces moved here from the home header: a
                // labelled row says what they are where a fifth unlabeled
                // icon did not. The console is the live dashboard; the raw
                // check is the engine-side instrument.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenConsole)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.MonitorHeart,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.console_title),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenDiag)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Receipt,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.reader_diag_title),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.settings_clear_history_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_clear_history_text, log.size)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PracticeLog.clear(ctx)
                    confirmClear = false
                }) { Text(stringResource(R.string.settings_clear), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

// ---- rows ----------------------------------------------------------------

@Composable
private fun TextSizeRow(tick: Int) {
    val ctx = LocalContext.current
    val percent = remember(tick) { ReaderPrefs.fontScalePercent(ctx) }
    val scale = percent / 100f

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.settings_text_size), fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(
            "$percent%",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
    Slider(
        value = percent.toFloat(),
        onValueChange = { ReaderPrefs.setFontScalePercent(ctx, (it / 10).toInt() * 10) },
        valueRange = ReaderPrefs.FONT_MIN.toFloat()..ReaderPrefs.FONT_MAX.toFloat(),
        // 10 discrete steps, matching the quantisation in the setter. Without
        // this the thumb snaps to a continuous value that the setter then rounds,
        // so the displayed number and the drag position disagree at the ends.
        steps = (ReaderPrefs.FONT_MAX - ReaderPrefs.FONT_MIN) / ReaderPrefs.FONT_STEP - 1,
    )
    Spacer(Modifier.height(4.dp))
    // Live preview at the real size. The point of a size control is to see the
    // size, and a slider whose only feedback is a number is a guess.
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ",
            fontFamily = quranFont,
            fontSize = (17 * scale).sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
        )
    }
}

@Composable
private fun NightSettings(tick: Int) {
    val ctx = LocalContext.current
    val night = remember(tick) { ReaderPrefs.nightMode(ctx) }
    SwitchRow(
        title = stringResource(R.string.settings_night_mode),
        subtitle = stringResource(R.string.settings_night_mode_subtitle),
        checked = night,
        onChange = { ReaderPrefs.setNightMode(ctx, it) },
    )
    // The sliders only mean anything in night mode - in day mode they are not
    // read at all (NightPalette returns fixed colours). Showing them anyway
    // would be two dead controls.
    //
    // Gated on `night` read fresh from the store, which is why the argument
    // exists: without it the switch appears to do nothing, because the two
    // sliders below it are decided by a value that is only re-read when
    // something forces a recomposition.
    if (night) {
        Spacer(Modifier.height(4.dp))
        NumberSlider(
            stringResource(R.string.settings_text_brightness),
            remember(tick) { ReaderPrefs.textBrightness(ctx) },
            0..255,
        ) { ReaderPrefs.setTextBrightness(ctx, it) }
        NumberSlider(
            stringResource(R.string.settings_bg_brightness),
            remember(tick) { ReaderPrefs.backgroundBrightness(ctx) },
            0..64,
        ) { ReaderPrefs.setBackgroundBrightness(ctx, it) }
    }
}

@Composable
private fun NumberSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            "$value",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        // One step per unit, so the displayed number always matches the value
        // actually stored - which is what the page behind will render.
        steps = (range.last - range.first - 1).coerceAtLeast(0),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // The Switch below is decorative and takes no clicks of its own, so
            // the whole row carries the toggle. Without this the row reads as
            // tappable - it has the right affordance and the right role - and
            // does nothing when you press the label, which is the part people
            // actually press.
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * The reciter chooser: current choice on the row, the full list in a dialog.
 * Ten names would not survive as chips - the names are long enough to fill
 * three FlowRow lines and bury the rest of the section - so the list gets its
 * own scrolled dialog and the row stays one line, like every other control.
 */
@Composable
private fun ReciterRow(tick: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var pick by remember { mutableStateOf(false) }
    val current = remember(tick) { Reciters.byId(AudioPrefs.reciter(ctx)) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { pick = true }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_reciter), fontSize = 15.sp)
            Text(
                current.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(
            stringResource(R.string.action_change),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    if (pick) {
        AlertDialog(
            onDismissRequest = { pick = false },
            title = { Text(stringResource(R.string.settings_reciter)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Reciters.ALL.forEach { r ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AudioPrefs.setReciter(ctx, r.id)
                                    pick = false
                                    onChanged()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = r.id == current.id,
                                onClick = {
                                    AudioPrefs.setReciter(ctx, r.id)
                                    pick = false
                                    onChanged()
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(r.name, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pick = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun speedPctLabel(percent: Int): String {
    val v = percent / 100f
    return if (v == v.toInt().toFloat()) "${v.toInt()}×" else "$v×"
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            k,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.weight(1f),
        )
        Text(v, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

// ---- layout --------------------------------------------------------------

private val SectionShape = RoundedCornerShape(16.dp)

/** One "the engine cannot judge this" line: a bold claim and why it is bounded. */
@Composable
private fun ModelCardGap(title: String, detail: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            detail,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A small horizontal set of mutually exclusive choices, as chips.
 *
 * Used for the metric and strictness settings, where a slider would be wrong:
 * these are discrete named choices, and the label matters more than the position.
 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        if (label.isNotEmpty()) {
            Text(
                label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
        }
        // A running row, not a Row.
        //
        // The old `Row` gave its pills the width that was left after the ones
        // before them took their natural width, so the last pill in a four-option
        // row was handed almost nothing and soft-wrapped to many lines - one tall
        // tower of text growing the whole Section Surface around it. Three of the
        // four ChoiceRows had at least one option long enough to trigger it, which
        // is why the section looked deformed rather than merely full.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { opt ->
                val on = opt == selected
                Box(
                    Modifier
                        .clip(IqraShape.chip)
                        .background(
                            if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onSelect(opt) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        opt,
                        fontSize = 12.sp,
                        color = if (on) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * The app language. Keyed on the stored tag rather than the displayed label,
 * because one of the labels is a native name that must not change shape with
 * the locale. On 33+ the system store is the operative locale and the system
 * recreates the activity itself; below that [AppLocales.apply] says whether
 * the recreate is owed here.
 */
@Composable
private fun LanguageRow(tick: Int) {
    val ctx = LocalContext.current
    val current = remember(tick) { AppLocales.stored(ctx) }
    val options = listOf(
        AppLocales.SYSTEM to stringResource(R.string.language_system),
        AppLocales.ENGLISH to stringResource(R.string.language_english),
        AppLocales.ARABIC to stringResource(R.string.language_arabic),
    )
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            stringResource(R.string.language_subtitle),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (tag, native) ->
                val on = tag == current
                Box(
                    Modifier
                        .clip(IqraShape.chip)
                        .background(
                            if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable {
                            val owesRecreate = AppLocales.apply(ctx, tag)
                            if (owesRecreate) (ctx as? android.app.Activity)?.recreate()
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Text(
                        native,
                        fontSize = 12.sp,
                        color = if (on) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp, start = 4.dp),
    )
    Surface(shape = SectionShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), content = content)
    }
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

private fun fmtMb(b: Long): String =
    if (b >= 1_000_000) String.format("%.1f MB", b / 1_000_000.0)
    else String.format("%.0f KB", b / 1_000.0)

/**
 * Four themes, each previewed with its own colours rather than described.
 *
 * A row of names cannot tell you whether "Ink and silver" is too cold for reading
 * at night, and the only honest way to choose between four palettes is to see them.
 * Each swatch is that theme's ground, surface, accent and gold, with a line of
 * Arabic set in the real typeface - so the choice is made on the thing that will
 * actually be on screen.
 */
/**
 * The recitation screen's theme, with "follows the app" as a real option.
 *
 * "Same as the app" is a row like any other rather than an absence of one,
 * because the alternative - a Live screen silently repainting when the app theme
 * changes - is how a setting becomes untrustworthy. Choosing it writes the
 * sentinel; the app theme is then genuinely inherited, not merely unset.
 *
 * The swatches show the LIVE palette, derived, because that is the palette the
 * reciter will actually be looking at. A swatch built from the app palette would
 * be a promise the screen does not keep.
 */
@Composable
private fun LiveThemeChooser(tick: Int) {
    val ctx = LocalContext.current
    val liveId = remember(tick) { ReaderPrefs.liveThemeId(ctx) }
    val appTheme = remember(tick) { ReaderPrefs.theme(ctx) }
    val live = LocalIqraColors.current

    Column {
        val follow = liveId == ReaderPrefs.NO_LIVE_OVERRIDE
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (follow) live.accent.copy(alpha = 0.12f) else Color.Transparent
                )
                .toggleable(
                    value = follow,
                    onValueChange = {
                        if (it) ReaderPrefs.setLiveThemeId(ctx, ReaderPrefs.NO_LIVE_OVERRIDE)
                    },
                    role = Role.RadioButton,
                )
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 52.dp, height = 52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(IqraPalettes.of(appTheme).ground),
                contentAlignment = Alignment.Center,
            ) {
                Text("A", fontSize = 17.sp, color = live.inkFaint)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_live_follow_app),
                    fontSize = 14.sp, color = live.ink,
                    fontWeight = if (follow) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    stringResource(R.string.settings_live_follows_theme, appTheme.name),
                    fontSize = 11.sp, color = live.inkFaint,
                )
            }
        }

        IqraTheme.entries.forEach { t ->
            val pal = liveColorsFor(IqraPalettes.of(t))
            val on = t.id == liveId
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) pal.crestHot.copy(alpha = 0.12f) else Color.Transparent)
                    .toggleable(
                        value = on,
                        onValueChange = { if (it) ReaderPrefs.setLiveThemeId(ctx, t.id) },
                        role = Role.RadioButton,
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(width = 52.dp, height = 52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(pal.ground),
                ) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(14.dp)
                            .background(pal.crestHot),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(t.labelRes),
                        fontSize = 14.sp, color = live.ink,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(stringResource(t.blurbRes), fontSize = 11.sp, color = live.inkFaint)
                }
            }
        }
    }
}

@Composable
private fun ThemeChooser(tick: Int) {
    val ctx = LocalContext.current
    val current = remember(tick) { ReaderPrefs.theme(ctx) }
    val live = LocalIqraColors.current

    Column {
        IqraTheme.entries.forEach { t ->
            val pal = IqraPalettes.of(t)
            val on = t == current
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) pal.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .toggleable(
                        value = on,
                        onValueChange = { if (it) ReaderPrefs.setThemeId(ctx, t.id) },
                        role = Role.RadioButton,
                    )
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The swatch: this theme's own ground, surface, accent and gold.
                Box(
                    Modifier
                        .size(width = 52.dp, height = 52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(pal.ground),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(pal.surface),
                    ) {
                        Box(
                            Modifier
                                .padding(horizontal = 6.dp, vertical = 5.dp)
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(pal.accent),
                        )
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 6.dp, bottom = 6.dp)
                                .width(16.dp).height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(pal.gold),
                        )
                    }
                    if (on) {
                        Box(
                            Modifier.fillMaxSize().background(pal.ground.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "\u2713",
                                color = pal.ink,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(t.labelRes),
                        fontSize = 14.sp,
                        color = live.ink,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(stringResource(t.blurbRes), fontSize = 11.sp, color = live.inkFaint)
                }
            }
        }
    }
}
