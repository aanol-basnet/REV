package app.rev.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.format.TextStyle

/**
 * One series of bars from a shared baseline. Bars are drawn in one hue; the selected bar is full
 * strength and the rest are lighter. Tapping a bar selects it — the details show below the chart.
 */
@Composable
internal fun BarChart(
    values: List<Long>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    labels: List<String?>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
) {
    val bar = MaterialTheme.colorScheme.primary
    val rest = lerp(bar, MaterialTheme.colorScheme.surface, 0.55f)
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = description }
                .pointerInput(values.size) {
                    detectTapGestures { offset ->
                        if (values.isEmpty()) return@detectTapGestures
                        val index = (offset.x / size.width * values.size).toInt().coerceIn(0, values.size - 1)
                        onSelect(index)
                    }
                },
        ) {
            if (values.isEmpty()) return@Canvas
            val gap = 2.dp.toPx()
            val slot = size.width / values.size
            val width = (slot - gap).coerceAtLeast(1f)
            val radius = minOf(4.dp.toPx(), width / 2)
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = (v.toFloat() / max * size.height).coerceAtLeast(radius * 2)
                val left = i * slot + gap / 2
                val color = if (selected == null || selected == i) bar else rest
                // Rounded at the data end, square on the baseline.
                drawRoundRect(color, Offset(left, size.height - h), Size(width, h), CornerRadius(radius))
                drawRect(color, Offset(left, size.height - radius), Size(width, radius))
            }
            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach { label ->
                Text(
                    label.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Plays by weekday (rows, Monday first) and hour (columns): one hue, lighter to darker with more
 * plays; an empty hour is the plain surface. Tapping a cell selects it.
 */
@Composable
internal fun Heatmap(
    heatmap: List<List<Int>>,
    selected: Pair<Int, Int>?,
    onSelect: (Pair<Int, Int>) -> Unit,
    format: StatsFormat,
    description: String,
    modifier: Modifier = Modifier,
) {
    val hue = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    val low = lerp(hue, MaterialTheme.colorScheme.surface, 0.8f)
    val outline = MaterialTheme.colorScheme.onSurface
    val max = heatmap.maxOfOrNull { row -> row.maxOrNull() ?: 0 }?.coerceAtLeast(1) ?: 1
    val rowHeight = 22.dp
    val labelWidth = 28.dp
    Column(modifier) {
        Row {
            Column(Modifier.width(labelWidth)) {
                repeat(7) { day ->
                    Box(Modifier.height(rowHeight)) {
                        Text(
                            format.weekday(day, TextStyle.SHORT).take(2),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(rowHeight * 7)
                    .semantics { contentDescription = description }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val hour = (offset.x / size.width * 24).toInt().coerceIn(0, 23)
                            val day = (offset.y / size.height * 7).toInt().coerceIn(0, 6)
                            onSelect(day to hour)
                        }
                    },
            ) {
                val gap = 2.dp.toPx()
                val cellW = size.width / 24
                val cellH = size.height / 7
                val radius = CornerRadius(2.dp.toPx())
                for (day in 0 until 7) {
                    for (hour in 0 until 24) {
                        val v = heatmap.getOrNull(day)?.getOrNull(hour) ?: 0
                        val color = if (v == 0) empty else lerp(low, hue, v.toFloat() / max)
                        val topLeft = Offset(hour * cellW + gap / 2, day * cellH + gap / 2)
                        val cell = Size(cellW - gap, cellH - gap)
                        drawRoundRect(color, topLeft, cell, radius)
                        if (selected == day to hour) {
                            drawRoundRect(outline, topLeft, cell, radius, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
            }
        }
        // Each label starts under its hour's column and covers the six hours after it.
        Row(Modifier.fillMaxWidth().padding(start = labelWidth, top = 4.dp)) {
            listOf(0, 6, 12, 18).forEach { hour ->
                Text(
                    format.hour(hour),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** A horizontal share bar: the filled part is [fraction] (0–1) of the track. */
@Composable
internal fun ShareBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(modifier.height(6.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (fraction > 0f) {
            drawRoundRect(color, size = Size((size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(size.height), size.height), cornerRadius = radius)
        }
    }
}
