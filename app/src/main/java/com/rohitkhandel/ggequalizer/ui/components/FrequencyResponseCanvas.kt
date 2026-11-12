package com.rohitkhandel.ggequalizer.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

@Composable
fun FrequencyResponseCanvas(
    points: List<FrequencyResponsePoint>,
    bands: List<EqBandUiState>,
    selectedBandIndex: Int,
    onBandSelected: (Int) -> Unit,
    onBandGainChanged: (Int, Float) -> Unit = { _, _ -> },
    spectrumBars: List<Float> = emptyList(),
    accent: Color = Color(0xFF42D9F5),
    modifier: Modifier = Modifier
) {
    val currentBands by rememberUpdatedState(bands)
    val currentOnBandSelected by rememberUpdatedState(onBandSelected)
    val currentOnBandGainChanged by rememberUpdatedState(onBandGainChanged)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0E141B))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val current = currentBands
                        if (current.isNotEmpty() && size.width > 0) {
                            val frequency = frequencyAtX(tap.x, size.width.toFloat())
                            val nearest = current.indices.minByOrNull { index ->
                                abs(ln(current[index].frequencyHz.coerceAtLeast(1f) / frequency.coerceAtLeast(1f)))
                            }
                            nearest?.let(currentOnBandSelected)
                        }
                    }
                }
                .pointerInput(Unit) {
                    var activeBand = -1
                    var pendingGain = 0f
                    var lastDispatchMs = 0L
                    detectDragGestures(
                        onDragStart = { point ->
                            val current = currentBands
                            if (current.isEmpty()) return@detectDragGestures
                            val frequency = frequencyAtX(point.x, size.width.toFloat())
                            activeBand = current.indices.minByOrNull { index ->
                                abs(ln(current[index].frequencyHz.coerceAtLeast(1f) / frequency.coerceAtLeast(1f)))
                            } ?: -1
                            if (activeBand >= 0) {
                                pendingGain = current[activeBand].gainDb
                                lastDispatchMs = 0L
                                currentOnBandSelected(activeBand)
                            }
                        },
                        onDrag = { change, _ ->
                            if (activeBand >= 0) {
                                val top = 10f
                                val bottom = size.height - 10f
                                val normalized = ((change.position.y - top) / (bottom - top).coerceAtLeast(1f))
                                    .coerceIn(0f, 1f)
                                pendingGain = (12f - normalized * 24f).coerceIn(-12f, 12f)
                                val now = SystemClock.uptimeMillis()
                                if (lastDispatchMs == 0L || now - lastDispatchMs >= 20L) {
                                    currentOnBandGainChanged(activeBand, pendingGain)
                                    lastDispatchMs = now
                                }
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (activeBand >= 0) currentOnBandGainChanged(activeBand, pendingGain)
                            activeBand = -1
                        },
                        onDragCancel = {
                            if (activeBand >= 0) currentOnBandGainChanged(activeBand, pendingGain)
                            activeBand = -1
                        }
                    )
                }
        ) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas

            val left = 7f
            val right = size.width - 7f
            val top = 7f
            val bottom = size.height - 7f
            val plotWidth = (right - left).coerceAtLeast(1f)
            val plotHeight = (bottom - top).coerceAtLeast(1f)

            fun xForFrequency(frequencyHz: Float): Float {
                val normalized = ln(frequencyHz.coerceIn(20f, 20_000f) / 20f) / ln(1000f)
                return left + normalized * plotWidth
            }

            fun yForGain(gainDb: Float): Float {
                val normalized = (gainDb.coerceIn(-24f, 24f) + 24f) / 48f
                return bottom - normalized * plotHeight
            }

            val zeroY = yForGain(0f)
            val gridColor = Color(0xFF25303C)
            val frequencyMarks = listOf(20f, 31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f, 20_000f)
            frequencyMarks.forEachIndexed { index, frequency ->
                val x = xForFrequency(frequency)
                drawLine(
                    gridColor.copy(alpha = if (index % 2 == 0) 0.9f else 0.5f),
                    Offset(x, top),
                    Offset(x, bottom),
                    if (index % 2 == 0) 1.dp.toPx() else 0.6.dp.toPx()
                )
            }
            listOf(-24f, -12f, 0f, 12f, 24f).forEach { gain ->
                val y = yForGain(gain)
                drawLine(
                    if (gain == 0f) accent.copy(alpha = 0.34f) else gridColor,
                    Offset(left, y),
                    Offset(right, y),
                    if (gain == 0f) 1.4.dp.toPx() else 0.7.dp.toPx()
                )
            }

            if (spectrumBars.isNotEmpty()) {
                val count = spectrumBars.size.coerceAtLeast(1)
                spectrumBars.forEachIndexed { index, magnitude ->
                    val barWidth = plotWidth / count
                    val height = magnitude.coerceIn(0f, 1f) * plotHeight * 0.68f
                    val x = left + index * barWidth
                    drawRoundRect(
                        color = accent.copy(alpha = 0.15f + magnitude.coerceIn(0f, 1f) * 0.20f),
                        topLeft = Offset(x + barWidth * 0.22f, bottom - height),
                        size = Size((barWidth * 0.56f).coerceAtLeast(1f), height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                    )
                }
            }

            if (points.size > 1) {
                val curve = Path().apply {
                    val first = points.first()
                    moveTo(xForFrequency(first.frequencyHz), yForGain(first.gainDb))
                    var previous = Offset(xForFrequency(first.frequencyHz), yForGain(first.gainDb))
                    points.drop(1).forEach { point ->
                        val current = Offset(xForFrequency(point.frequencyHz), yForGain(point.gainDb))
                        val dx = (current.x - previous.x) / 3f
                        cubicTo(previous.x + dx, previous.y, current.x - dx, current.y, current.x, current.y)
                        previous = current
                    }
                }
                val area = Path().apply {
                    val firstX = xForFrequency(points.first().frequencyHz)
                    val firstY = yForGain(points.first().gainDb)
                    moveTo(firstX, zeroY)
                    lineTo(firstX, firstY)
                    var previous = Offset(firstX, firstY)
                    points.drop(1).forEach { point ->
                        val current = Offset(xForFrequency(point.frequencyHz), yForGain(point.gainDb))
                        val dx = (current.x - previous.x) / 3f
                        cubicTo(previous.x + dx, previous.y, current.x - dx, current.y, current.x, current.y)
                        previous = current
                    }
                    lineTo(xForFrequency(points.last().frequencyHz), zeroY)
                    close()
                    fillType = PathFillType.NonZero
                }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0.25f), accent.copy(alpha = 0.01f), Color.Transparent),
                        startY = top,
                        endY = bottom
                    )
                )
                drawPath(curve, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            }

            bands.forEachIndexed { index, band ->
                if (index == selectedBandIndex) {
                    val point = Offset(xForFrequency(band.frequencyHz), yForGain(band.gainDb))
                    drawCircle(accent.copy(alpha = 0.18f), 9.dp.toPx(), point)
                    drawCircle(accent, 3.8.dp.toPx(), point)
                }
            }
        }
    }
}

private fun frequencyAtX(x: Float, width: Float): Float {
    val normalized = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return (20.0 * 1000.0.pow(normalized.toDouble())).toFloat()
}
