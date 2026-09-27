package com.iqra.quran.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iqra.quran.data.AssetPaths
import com.iqra.quran.data.DataFetcher
import kotlinx.coroutines.launch

/**
 * First-run data setup. Heavy files live in a shared folder instead of the APK,
 * so this screen is the replacement for "push files with adb": anything
 * redistributable is fetched from its original source on demand, and the one
 * gated file (the acoustic model) is the user's to copy in.
 */
@Composable
fun DataSetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf(AssetPaths.allStatuses(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var frac by remember { mutableStateOf(0f) }
    var permGranted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        permGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            android.os.Environment.isExternalStorageManager()
        statuses = AssetPaths.allStatuses(ctx)
    }

    val cs = MaterialTheme.colorScheme
    val gate = statuses.firstOrNull { !it.present && it.spec.kind == AssetPaths.Kind.GATED }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Set up your mushaf", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "Page images and data are downloaded once and kept in a shared folder, so app updates stay small. The recitation model is supplied by you, once.",
            fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(18.dp))

        statuses.forEach { st ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box2(st.present, cs)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.spec.key, fontSize = 14.sp, color = cs.onSurface)
                    val detail = when {
                        !st.present && st.spec.kind == AssetPaths.Kind.GATED ->
                            "required · copy into ${AssetPaths.sharedRoot().absolutePath}"
                        !st.present -> "will download (${fmt(st.spec.bytes)})"
                        else -> "${fmt(st.actualBytes)} · ${st.actualPath}"
                    }
                    Text(
                        detail,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        if (gate != null) {
            Card2(cs) {
                Text("One file you must supply", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    "model.int8.onnx is gated by its authors, so it cannot be downloaded automatically. " +
                        "Copy it over USB into:\n${AssetPaths.sharedRoot().absolutePath}\n" +
                        "then press Re-check.",
                    fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.75f)
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        if (!permGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            OutlinedButton(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${ctx.packageName}"),
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Allow shared-folder access (optional)") }
            Spacer(Modifier.height(8.dp))
        }

        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(progress, fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f))
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        DataFetcher.fetchAll(ctx) { p ->
                            progress = "${p.label}  ${fmt(p.bytesThisFile)}" +
                                if (p.fileTotal > 0) " / ${fmt(p.fileTotal)}" else ""
                            frac = if (p.total > 0) p.done.toFloat() / p.total else 0f
                        }
                    }
                    busy = false
                    progress = runCatching { "" }.getOrElse { "some downloads failed - press Re-check" }
                    statuses = AssetPaths.allStatuses(ctx)
                }
            },
            enabled = !busy && statuses.any { !it.present && it.spec.kind == AssetPaths.Kind.FETCHABLE },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = goldColor)
        ) { Text("Download what's missing", color = Color.White) }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { statuses = AssetPaths.allStatuses(ctx) },
                modifier = Modifier.weight(1f)
            ) { Text("Re-check") }
            Button(
                onClick = onDone,
                enabled = statuses.all { it.present },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
            ) { Text("Continue", color = Color.White) }
        }
        if (statuses.any { !it.present }) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Continue unlocks once every file is present.",
                fontSize = 11.sp,
                color = cs.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun Box2(ok: Boolean, cs: androidx.compose.material3.ColorScheme) {
    val good = Color(0xFF2E7D32)
    val bad = Color(0xFFC62828)
    Spacer(
        Modifier
            .size(10.dp)
            .background(if (ok) good else bad, RoundedCornerShape(5.dp))
    )
}

@Composable
private fun Card2(cs: androidx.compose.material3.ColorScheme, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(cs.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) { content() }
}

private fun fmt(b: Long): String =
    if (b >= 1_048_576) "%.1f MB".format(b / 1048576.0) else "${b / 1024} KB"
