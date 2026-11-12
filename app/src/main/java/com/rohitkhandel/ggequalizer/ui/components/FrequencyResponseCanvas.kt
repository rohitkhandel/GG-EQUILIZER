package com.rohitkhandel.ggequalizer.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.rohitkhandel.ggequalizer.ui.EqBandUiState
import com.rohitkhandel.ggequalizer.ui.FrequencyResponsePoint
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/** Response curve and spectrum layer; it can be laid over faders without intercepting their touch input. */
@Composable
fun FrequencyResponseCanvas(
    points: List<FrequencyResponsePoint>,
    bands: List<EqBandUiState>,
    selectedBandIndex: Int,
    onBandSelected: (Int) -> Unit,
    onBandGainChanged: (Int, Float) -> Unit = { _, _ -> },
    spectrumBars: List<Float> = emptyList(),
    accent: Color = Color(0xFF42D9F5),
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    spectrumStyle: String = "BARS",
    showGrid: Boolean = true
) {
    val currentBands by rememberUpdatedState(bands)
    val currentSelect by rememberUpdatedState(onBandSelected)
    val currentGain by rememberUpdatedState(onBandGainChanged)

    Box(modifier = modifier) {
        Canvas(
            modifier = if (interactive) {
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { tap ->
                            val current = currentBands
                            if (current.isNotEmpty()) {
                                val hz = frequencyAtX(tap.x, size.width.toFloat())
                                val nearest = current.indices.minByOrNull { index ->
                                    abs(ln(current[index].frequencyHz.coerceAtLeast(1f) / hz.coerceAtLeast(1f)))
                                }
                                nearest?.let(currentSelect)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        var activeBand = -1
                        var pendingGain = 0f
                        var lastDispatch = 0L
                        detectDragGestures(
                            onDragStart = { point ->
                                val current = currentBands
                                val hz = frequencyAtX(point.x, size.width.toFloat())
                                activeBand = current.indices.minByOrNull { index ->
                                    abs(ln(current[index].frequencyHz.coerceAtLeast(1f) / hz.coerceAtLeast(1f)))
                                } ?: -1
                                if (activeBand >= 0) {
                                    pendingGain = current[activeBand].gainDb
                                    lastDispatch = 0L
                                    currentSelect(activeBand)
                                }
                            },
                            onDrag = { change, _ ->
                                if (activeBand >= 0) {
                                    val progress = ((change.position.y - 8f) / (size.height - 16f).coerceAtLeast(1f))
                                        .coerceIn(0f, 1f)
                                    pendingGain = (12f - progress * 24f).coerceIn(-12f, 12f)
                                    val now = SystemClock.uptimeMillis()
                                    if (lastDispatch == 0L || now - lastDispatch >= 20L) {
                                        currentGain(activeBand, pendingGain)
                                        lastDispatch = now
                                    }
                                    change.consume()
                                }
                            },
                            onDragEnd = {
                                if (activeBand >= 0) currentGain(activeBand, pendingGain)
                                activeBand = -1
                            },
                            onDragCancel = {
                                if (activeBand >= 0) currentGain(activeBand, pendingGain)
                                activeBand = -1
                            }
                        )
                    }
            } else Modifier.fillMaxSize()
        ) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            val left = 4f
            val right = size.width - 4f
            val top = 5f
            val bottom = size.height - 5f
            val width = (right - left).coerceAtLeast(1f)
            val height = (bottom - top).coerceAtLeast(1f)

            fun xFor(hz: Float): Float =
                left + ln(hz.coerceIn(20f, 20_000f) / 20f) / ln(1000f) * width

            fun yFor(db: Float): Float {
                val t = (db.coerceIn(-12f, 12f) + 12f) / 24f
                return bottom - t * height
            }

            if (showGrid) {
                val grid = Color(0xFF34404D).copy(alpha = 0.45f)
                listOf(31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f).forEach { hz ->
                    val x = xFor(hz)
                    drawLine(grid, Offset(x, top), Offset(x, bottom), 0.7.dp.toPx())
                }
                listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
                    val y = yFor(db)
                    drawLine(
                        if (db == 0f) accent.copy(alpha = 0.26f) else grid,
                        Offset(left, y),
                        Offset(right, y),
                        if (db == 0f) 1.dp.toPx() else 0.6.dp.toPx()
                    )
                }
            }

            if (spectrumBars.isNotEmpty() && spectrumStyle != "OFF") {
                if (spectrumStyle == "WAVE") {
                    val wave = Path()
                    spectrumBars.forEachIndexed { index, magnitude ->
                        val x = left + width * index / (spectrumBars.size - 1).coerceAtLeast(1)
                        val y = bottom - magnitude.coerceIn(0f, 1f) * height * 0.38f
                        if (index == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
                    }
                    drawPath(
                        wave,
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.16f), accent.copy(alpha = 0.7f), accent.copy(alpha = 0.16f))
                        ),
                        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                    )
                } else {
                    val count = spectrumBars.size.coerceAtLeast(1)
                    spectrumBars.forEachIndexed { index, magnitude ->
                        val barWidth = width / count
                        val barHeight = magnitude.coerceIn(0f, 1f) * height * 0.52f
                        val x = left + index * barWidth
                        drawRoundRect(
                            accent.copy(alpha = 0.12f + magnitude.coerceIn(0f, 1f) * 0.25f),
                            Offset(x + barWidth * 0.22f, bottom - barHeight),
                            Size((barWidth * 0.56f).coerceAtLeast(1f), barHeight),
                            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                        )
                    }
                }
            }

            if (points.size > 1) {
                val curve = Path().apply {
                    val first = points.first()
                    moveTo(xFor(first.frequencyHz), yFor(first.gainDb))
                    var previous = Offset(xFor(first.frequencyHz), yFor(first.gainDb))
                    points.drop(1).forEach { point ->
                        val next = Offset(xFor(point.frequencyHz), yFor(point.gainDb))
                        val dx = (next.x - previous.x) / 3f
                        cubicTo(
                            previous.x + dx, previous.y,
                            next.x - dx, next.y,
                            next.x, next.y
                        )
                        previous = next
                    }
                }
                val area = Path().apply {
                    val first = points.first()
                    moveTo(xFor(first.frequencyHz), yFor(0f))
                    lineTo(xFor(first.frequencyHz), yFor(first.gainDb))
                    var previous = Offset(xFor(first.frequencyHz), yFor(first.gainDb))
                    points.drop(1).forEach { point ->
                        val next = Offset(xFor(point.frequencyHz), yFor(point.gainDb))
                        val dx = (next.x - previous.x) / 3f
                        cubicTo(previous.x + dx, previous.y, next.x - dx, next.y, next.x, next.y)
                        previous = next
                    }
                    lineTo(xFor(points.last().frequencyHz), yFor(0f))
                    close()
                    fillType = PathFillType.NonZero
                }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.15f), accent.copy(alpha = 0.015f), Color.Transparent),
                        startY = top,
                        endY = bottom
                    )
                )
                drawPath(
                    curve,
                    accent.copy(alpha = 0.9f),
                    style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            bands.forEachIndexed { index, band ->
                if (index == selectedBandIndex) {
                    val point = Offset(xFor(band.frequencyHz), yFor(band.gainDb))
                    drawCircle(accent.copy(alpha = 0.2f), 8.dp.toPx(), point)
                    drawCircle(accent, 3.dp.toPx(), point)
                }
            }
        }
    }
}

private fun frequencyAtX(x: Float, width: Float): Float {
    val t = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return (20.0 * 1000.0.pow(t.toDouble())).toFloat()
}
