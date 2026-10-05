package com.iqra.quran.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iqra.quran.data.AssetPaths
import com.iqra.quran.data.PracticeLog

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenData: () -> Unit = {},
    onOpenProgress: () -> Unit = {},
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SettingsSection("Reading") {
                TextSizeRow(tick)
                SettingDivider()
                NightSettings(tick)
            }

            SettingsSection("Practice") {
                SwitchRow(
                    title = "Keep the screen on while reciting",
                    subtitle = "A recitation usually runs past the ~30 s before " +
                        "Android dims the screen. Turn off to save battery.",
                    checked = remember(tick) { ReaderPrefs.keepAwake(ctx) },
                    onChange = { ReaderPrefs.setKeepAwake(ctx, it) },
                )
            }

            SettingsSection("Your data") {
                val statuses = remember { AssetPaths.allStatuses(ctx) }
                val missing = statuses.filter { !it.present }
                val presentBytes = statuses.filter { it.present }.sumOf { it.actualBytes }

                Text(
                    if (missing.isEmpty()) {
                        "All ${statuses.size} data files are present · " +
                            "${fmtMb(presentBytes)}"
                    } else {
                        "${missing.size} of ${statuses.size} data files missing"
                    },
                    fontSize = 13.sp,
                    color = if (missing.isEmpty()) {
                        accentColor
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenData, modifier = Modifier.fillMaxWidth()) {
                    Text("Manage data files")
                }

                SettingDivider()

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Practice history", fontSize = 15.sp)
                        Text(
                            when (log.size) {
                                0 -> "Nothing recorded yet"
                                1 -> "1 session"
                                else -> "${log.size} sessions kept"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                    if (log.isNotEmpty()) {
                        TextButton(onClick = onOpenProgress) { Text("View") }
                        TextButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Filled.Delete, null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Clear", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            SettingsSection("About") {
                KeyValue("Version", "0.1.0")
                KeyValue("Mushaf", "604 pages · Madinah")
                KeyValue("Recognition", "on-device only")
                Spacer(Modifier.height(10.dp))
                Text(
                    "Nothing you recite leaves the device. There is no account, " +
                        "no server and no network access at runtime.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear practice history?") },
            text = {
                Text(
                    "This deletes the ${log.size} recorded sessions and the " +
                        "statistics built from them. It cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PracticeLog.clear(ctx)
                    confirmClear = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
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
        Text("Text size", fontSize = 15.sp, modifier = Modifier.weight(1f))
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
        title = "Night mode",
        subtitle = "Inverts the page so the mushaf is light text on dark.",
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
            "Text brightness",
            remember(tick) { ReaderPrefs.textBrightness(ctx) },
            0..255,
        ) { ReaderPrefs.setTextBrightness(ctx, it) }
        NumberSlider(
            "Background brightness",
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

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = accentColor,
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
