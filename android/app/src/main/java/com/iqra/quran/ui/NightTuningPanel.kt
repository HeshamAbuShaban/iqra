package com.iqra.quran.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Night-mode brightness controls.
 *
 * Ported from quran_android's SeekBarTextBrightnessPreference and
 * SeekBarBackgroundBrightnessPreference, including their ranges (text 0-255
 * default 255, background 0-64 default 0) and the adjusted-brightness formula
 * 50*ln1p(background) + text. Live previews, as the reference has.
 */
@Composable
fun NightTuningPanel() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableFloatStateOf(ReaderPrefs.textBrightness(ctx).toFloat()) }
    var bg by remember { mutableFloatStateOf(ReaderPrefs.backgroundBrightness(ctx).toFloat()) }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val adjusted = NightPalette.adjustedTextBrightness(text.roundToInt(), bg.roundToInt())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Night mode", fontSize = 15.sp, color = cs.onSurface)
                Text(
                    "inverted page · text brightness ${text.roundToInt()} · background ${bg.roundToInt()}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = cs.onSurface.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // live text preview at the computed brightness
        Row(
            Modifier.fillMaxWidth()
                .background(Color(bg.roundToInt(), bg.roundToInt(), bg.roundToInt()), RoundedCornerShape(10.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "بِسْمِ ٱللَّهِ",
                fontFamily = quranFont,
                fontSize = 22.sp,
                color = Color(adjusted, adjusted, adjusted),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "effective $adjusted",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(adjusted, adjusted, adjusted).copy(alpha = 0.7f),
            )
        }

        Spacer(Modifier.height(12.dp))
        LabelledSlider("Text brightness", text, 0f, 255f) { v ->
            text = v
            ReaderPrefs.setTextBrightness(ctx, v.roundToInt())
        }
        Spacer(Modifier.height(6.dp))
        LabelledSlider("Background brightness", bg, 0f, 64f) { v ->
            bg = v
            ReaderPrefs.setBackgroundBrightness(ctx, v.roundToInt())
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.8f))
            Text(
                value.roundToInt().toString(),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurface.copy(alpha = 0.6f),
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}
