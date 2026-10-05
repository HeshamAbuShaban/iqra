package com.iqra.quran.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Charts, drawn rather than imported.
//
// The surface this app reports on had no charts at all. Accuracy was an integer
// percentage, activity was fourteen identical boxes filled or not, and the only
// visualisation of a surah was a bar whose formula happened to be
// accuracy x ln(ayahs) - which means it was mostly accuracy, presented as though
// it were coverage. A chart is an argument about what matters, so it is not a
// detail to be added at the end; these exist because the numbers were unreadable.
//
// All of it is on a Canvas. A charting dependency would have cost several hundred
// kilobytes for the privilege of not owning the drawing, and these need to follow
// the theme, which a library would have to be taught separately.

/**
 * One point on a trend line.
 *
 * [label] is only used on the first and last, because a label under every point is
 * the standard way charts are made unreadable.
 */
data class TrendPoint(val x: Float, val y: Float, val label: String)

/**
 * A line chart with a gradient under it.
 *
 * The line is smoothed with a Catmull-Rom spline converted to cubic segments. That
 * is a real choice with a real cost: smoothing implies continuity that the data
 * does not have, so with one session per day it will draw a graceful curve through
 * two points that are simply unrelated. It is on because accuracy does not teleport
 * between sessions - it moves because you got better - and a jagged polyline makes
 * ordinary improvement look like noise. With very few points the spline is
 * effectively a straight line anyway, so the lie is smallest exactly where the data
 * is thinnest.
 */
@Composable
fun TrendLine(
    points: List<TrendPoint>,
    line: Color,
    fill: Color,
    modifier: Modifier = Modifier,
    yMin: Float = 0f,
    yMax: Float = 1f,
    /** Gridlines and their labels. Null hides them. */
    gridLines: Int = 3,
    gridColor: Color = Color(0x14FFFFFF),
    labelColor: Color = Color(0xFF93A8A0),
    /** Drawn under each x position, e.g. a session count. */
    bars: List<Float> = emptyList(),
    barColor: Color = Color(0x1AFFFFFF),
    strokeWidth: Float = 2.5f,
    /** Sweeps in on first composition. */
    animate: Boolean = true,
) {
    val measurer = rememberTextMeasurer()
    // Sweep in from the left edge on first composition, so the line reads as
    // being drawn rather than simply appearing.
    var drawn by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { if (animate) drawn = 1f else drawn = 1f }
    val sweep by animateFloatAsState(drawn, tween(900), label = "sweep")
    val grid = gridLines.coerceAtLeast(0)
    val gridStyle = TextStyle(fontSize = 9.sp, color = labelColor)

    Canvas(modifier) {
        if (points.isEmpty() || yMax <= yMin) return@Canvas
        fun mapY(v: Float): Float {
            val t = ((v - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
            return size.height - t * size.height
        }

        if (grid > 0) {
            repeat(grid) { i ->
                val t = i / (grid - 1f).coerceAtLeast(1f)
                val y = size.height * t
                drawLine(
                    gridColor, Offset(0f, y), Offset(size.width, y),
                    strokeWidth = 1f,
                    pathEffect = if (i == grid - 1) null
                    else PathEffect.dashPathEffect(floatArrayOf(3f, 5f)),
                )
                val txt = measurer.measure(
                    ((yMax - (yMax - yMin) * t) * 100).toInt().toString() + "%",
                    gridStyle,
                )
                drawText(txt, topLeft = Offset(size.width - txt.size.width, y - txt.size.height - 1f))
            }
        }

        if (bars.isNotEmpty() && bars.size > 1) {
            val maxB = bars.max().coerceAtLeast(1f)
            val w = size.width / bars.size
            bars.forEachIndexed { i, v ->
                val h = (v / maxB) * size.height * 0.28f
                if (h > 0.5f) {
                    drawRoundRect(
                        barColor,
                        topLeft = Offset(i * w + w * 0.22f, size.height - h),
                        size = Size(w * 0.56f, h),
                        cornerRadius = CornerRadius(w * 0.2f),
                    )
                }
            }
        }

        val pts = points.map { Offset(it.x, mapY(it.y) * sweep + size.height * (1f - sweep)) }
        if (pts.size == 1) {
            drawCircle(line, 5f, pts[0])
            return@Canvas
        }

        val line_ = smoothPath(pts)
        val area = Path().apply {
            addPath(line_)
            lineTo(pts.last().x, size.height)
            lineTo(pts.first().x, size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(
                listOf(fill.copy(alpha = fill.alpha * 0.45f), fill.copy(alpha = 0f)),
            ),
        )
        drawPath(line_, line, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        pts.forEachIndexed { i, p ->
            val last = i == pts.size - 1
            drawCircle(line.copy(alpha = if (last) 1f else 0.55f), if (last) 5.5f else 3f, p)
            if (last) drawCircle(Color(0xFF070B0C), 2.2f, p)
        }

        // Only the ends are labelled. A label per point is how a five-session chart
        // becomes a stripe of text.
        listOf(0, pts.size - 1).distinct().forEach { i ->
            val lbl = points[i].label
            if (lbl.isEmpty()) return@forEach
            val txt = measurer.measure(lbl, gridStyle)
            val x = (pts[i].x - txt.size.width / 2f)
                .coerceIn(0f, size.width - txt.size.width)
            drawText(txt, topLeft = Offset(x, size.height - txt.size.height - 2f))
        }
    }
}

/** Catmull-Rom through the points, as cubic segments. */
private fun smoothPath(pts: List<Offset>): Path {
    val p = Path()
    if (pts.isEmpty()) return p
    p.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 1) return p
    for (i in 0 until pts.size - 1) {
        val p0 = pts[if (i == 0) 0 else i - 1]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[if (i + 2 > pts.size - 1) pts.size - 1 else i + 2]
        p.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
    return p
}

/**
 * A single number, drawn as a ring.
 *
 * The old surface showed accuracy as text. A ring is not decoration here: it is
 * the one place the user's eye goes first, and it carries the fraction visually so
 * the number below it can be read as a claim rather than as the whole story.
 */
@Composable
fun AccuracyRing(
    fraction: Float?,
    accent: Color,
    track: Color,
    caption: String?,
    diameter: androidx.compose.ui.unit.Dp = 116.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 10.dp,
    captionColor: Color = Color(0xFF93A8A0),
    accentColor: Color = Color(0xFFEAF3EE),
) {
    val measurer = rememberTextMeasurer()
    val target = fraction ?: 0f
    val sweep by animateFloatAsState(target, tween(750), label = "ring")
    val capStyle = TextStyle(fontSize = 24.sp, color = accentColor)
    val capSmall = TextStyle(fontSize = 10.sp, color = captionColor)

    Canvas(Modifier.size(diameter)) {
        val sw = strokeWidth.toPx()
        val inset = sw / 2f
        val d = minOf(size.width, size.height) - sw
        val topLeft = Offset(inset, inset)
        val arcSize = Size(d, d)

        drawArc(
            track, 0f, 360f, false,
            topLeft = topLeft, size = arcSize,
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        if (sweep > 0f) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(accent.copy(alpha = 0.55f), accent, accent.copy(alpha = 0.55f)),
                ),
                startAngle = -90f, sweepAngle = 360f * sweep, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
        }
        if (caption != null) {
            val t = measurer.measure(caption, capStyle)
            drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, (size.height - t.size.height) / 2f - 4f))
        }
    }
}

/**
 * One cell per day, laid out in weeks.
 *
 * Columns are weeks and rows are weekdays, the arrangement GitHub uses and the one
 * people already know how to read. The previous strip was fourteen fixed-width
 * boxes filled or not, which cannot show a thirty-one-day streak on the same screen
 * that claims a thirty-one-day streak - and whose equal-width filled blocks
 * promised an intensity scale that did not exist behind them.
 *
 * [volume] is words judged per day key. An empty map is still a valid heatmap - it
 * draws the empty grid, which is itself information.
 */
@Composable
fun ActivityHeatmap(
    days: List<Long>,
    volume: Map<Long, Int>,
    weeks: Int = 26,
    accent: Color,
    empty: Color,
    frame: Color,
    todayKey: Long,
    modifier: Modifier = Modifier,
    cell: androidx.compose.ui.unit.Dp = 11.dp,
    gap: androidx.compose.ui.unit.Dp = 3.dp,
) {
    val measurer = rememberTextMeasurer()
    val monthStyle = TextStyle(fontSize = 9.sp, color = frame)

    Canvas(modifier) {
        val cs = cell.toPx()
        val gp = gap.toPx()
        val weekW = cs * 7 + gp * 8
        val totalW = weeks * weekW
        // Right-align so the newest week is at the right edge and the grid grows
        // leftwards as history accumulates, instead of shifting every time.
        val x0 = size.width - totalW
        val maxV = volume.values.maxOrNull()?.coerceAtLeast(1) ?: 1

        fun dayAt(week: Int, weekday: Int): Long? {
            val idx = days.size - weeks * 7 + week * 7 + weekday
            return if (idx in days.indices) days[idx] else null
        }
        fun stepFor(k: Long): Float {
            val v = volume[k] ?: return 0f
            // Four visible levels. A continuous ramp on a 11dp cell reads as noise,
            // because the eye cannot rank two adjacent cells that differ by 4%.
            return when {
                v <= 0 -> 0f
                v < maxV * 0.25f -> 0.34f
                v < maxV * 0.55f -> 0.58f
                v < maxV * 0.82f -> 0.80f
                else -> 1f
            }
        }

        var lastMonth = -1
        var lastMonthX = -1f
        for (w in 0 until weeks) {
            for (d in 0 until 7) {
                val x = x0 + w * weekW + d * (cs + gp)
                val y = d * (cs + gp)
                val k = dayAt(w, d)
                if (k == null) {
                    drawRoundRect(
                        empty.copy(alpha = empty.alpha * 0.35f),
                        Offset(x, y), Size(cs, cs),
                        CornerRadius(cs * 0.22f),
                    )
                    continue
                }
                val t = stepFor(k)
                drawRoundRect(
                    if (t <= 0f) empty else accent.copy(alpha = 0.20f + 0.80f * t),
                    Offset(x, y), Size(cs, cs),
                    CornerRadius(cs * 0.22f),
                )
                if (k == todayKey) {
                    drawRoundRect(
                        accent, Offset(x - 1.5f, y - 1.5f), Size(cs + 3f, cs + 3f),
                        CornerRadius(cs * 0.28f), style = Stroke(width = 1.5f),
                    )
                }
                // Month label on the first day of a new month, when there is room.
                val mm = ((k / 100) % 100).toInt()
                if (d == 0 && mm != lastMonth && x > x0 + 2f) {
                    val nm = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").getOrNull(mm - 1)
                    if (nm != null && x - lastMonthX > 26f) {
                        drawText(measurer.measure(nm, monthStyle), topLeft = Offset(x, -10f))
                        lastMonth = mm
                        lastMonthX = x
                    }
                }
            }
        }
    }
}

/**
 * One mark per ayah, in order, coloured by how that ayah went.
 *
 * This is the single most useful picture in the whole report and it was the one
 * thing the app could not draw, because [com.iqra.quran.data.PracticeLog.Record]
 * used to collapse `ayahStatus` into four session totals and throw the
 * surah:ayah key away. A hundred-percent session and one with three bad ayat in a
 * row produced identical numbers.
 *
 * @param outcomes per ayah, in recitation order: 0 clean, 1 wrong, 2 unjudged.
 */
@Composable
fun AyahStrip(
    outcomes: List<Int>,
    good: Color,
    bad: Color,
    unknown: Color,
    current: Color = Color.Transparent,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 26.dp,
) {
    Canvas(modifier.height(height)) {
        if (outcomes.isEmpty()) return@Canvas
        val n = outcomes.size
        // Cap the bar so a whole surah does not become a smear of 1dp slivers: past
        // a point extra width carries no information and only makes it harder to
        // see which marks are bad.
        val gap = if (n > 120) 0f else if (n > 40) 1f else 2f
        val w = (size.width - gap * (n - 1)) / n
        outcomes.forEachIndexed { i, o ->
            val x = i * (w + gap)
            val c = when (o) {
                0 -> good
                1 -> bad
                else -> unknown
            }
            drawRoundRect(
                c.copy(alpha = if (o == 1) 0.95f else 0.42f),
                Offset(x, 0f), Size(w, size.height),
                CornerRadius(w.coerceAtMost(4f) * 0.35f),
            )
            if (o == 1) {
                drawRoundRect(
                    c, Offset(x, 0f), Size(w, size.height),
                    CornerRadius(w.coerceAtMost(4f) * 0.35f),
                )
            }
        }
    }
}

/**
 * Horizontal bars for a ranked quantity - pace per ayah, difficulty per word.
 *
 * Ranked rather than chronological on purpose: the question is "which one", and a
 * chronological bar chart makes you read every value to find the tallest.
 */
@Composable
fun RankedBars(
    entries: List<Pair<String, Float>>,
    accent: Color,
    track: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
    rowHeight: androidx.compose.ui.unit.Dp = 22.dp,
    max: Float = 0f,
) {
    val measurer = rememberTextMeasurer()
    val lbl = TextStyle(fontSize = 11.sp, color = labelColor)
    val valStyle = TextStyle(fontSize = 10.sp, color = labelColor.copy(alpha = 0.8f))

    Column(modifier) {
        val top = max.takeIf { it > 0f } ?: entries.maxOfOrNull { it.second } ?: 1f
        entries.forEach { (name, v) ->
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
            ) {
                val nameT = measurer.measure(name, lbl)
                val valT = measurer.measure(fmt1(v), valStyle)
                val textW = maxOf(nameT.size.width, valT.size.width) + 10f
                val trackX = textW + 6f
                val trackW = (size.width - trackX).coerceAtLeast(10f)
                val cy = size.height / 2f

                drawText(nameT, topLeft = Offset(0f, cy - nameT.size.height / 2f))
                drawText(valT, topLeft = Offset(size.width - valT.size.width, cy - valT.size.height / 2f))
                drawRoundRect(
                    track, Offset(trackX, cy - 3f),
                    Size(trackW, 6f), CornerRadius(3f),
                )
                val f = (v / top).coerceIn(0f, 1f)
                if (f > 0.001f) {
                    drawRoundRect(
                        accent, Offset(trackX, cy - 3f),
                        Size(trackW * f, 6f), CornerRadius(3f),
                    )
                }
            }
        }
    }
}

private fun fmt1(v: Float): String =
    if (v >= 100f) v.toInt().toString() else if (v >= 10f) v.toInt().toString()
    else "%.1f".format(v)

/**
 * A bare sparkline. For a number that needs a shape but not an axis.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Float = 2f,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 1e-4f } ?: 1f
        val pts = values.mapIndexed { i, v ->
            Offset(
                x = size.width * i / (values.size - 1f),
                y = size.height - ((v - lo) / span) * size.height * 0.86f - size.height * 0.07f,
            )
        }
        drawPath(smoothPath(pts), color, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        drawCircle(color, strokeWidth * 1.4f, pts.last())
    }
}

/**
 * A comparison bar: this session against your best or against the last one.
 *
 * Two bars, never one. A single bar has nothing to be measured against, and a
 * figure with no baseline is the thing this whole screen exists to stop showing.
 */
@Composable
fun CompareBars(
    rows: List<Pair<String, Float>>,
    valueFmt: (Float) -> String,
    primary: Color,
    secondary: Color,
    track: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
    rowHeight: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val measurer = rememberTextMeasurer()
    val name = TextStyle(fontSize = 12.sp, color = labelColor)
    val big = TextStyle(fontSize = 15.sp, color = labelColor)
    val small = TextStyle(fontSize = 10.sp, color = labelColor.copy(alpha = 0.75f))

    Column(modifier) {
        val top = rows.maxOfOrNull { it.second }?.coerceAtLeast(0.0001f) ?: 1f
        rows.forEachIndexed { idx, (label, v) ->
            Canvas(Modifier.fillMaxWidth().height(rowHeight)) {
                val n = measurer.measure(label, name)
                drawText(n, topLeft = Offset(0f, 0f))
                val isPrimary = idx == 0
                val vT = measurer.measure(
                    valueFmt(v), if (isPrimary) big else small,
                )
                drawText(
                    vT,
                    topLeft = Offset(size.width - vT.size.width, if (isPrimary) 2f else 4f),
                )
                val barY = if (isPrimary) n.size.height + 7f else n.size.height + 5f
                val bh = if (isPrimary) 9f else 6f
                val full = size.width
                drawRoundRect(track, Offset(0f, barY), Size(full, bh), CornerRadius(bh / 2f))
                val f = (v / top).coerceIn(0f, 1f)
                drawRoundRect(
                    if (isPrimary) primary else secondary,
                    Offset(0f, barY), Size(full * f, bh), CornerRadius(bh / 2f),
                )
            }
        }
    }
}
