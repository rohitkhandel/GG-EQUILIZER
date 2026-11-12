package com.rohitkhandel.ggequalizer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    spectrumBars: List<Float> = emptyList(),
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .background(Color(0xFF10151D), RoundedCornerShape(18.dp))
    ) {
        if (spectrumBars.isNotEmpty()) {
            SpectrumVisualizerCanvas(
                bars = spectrumBars,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .pointerInput(bands) {
                    detectTapGestures { tap ->
                        if (bands.isNotEmpty() && size.width > 0f) {
                            val normalized = (tap.x / size.width).coerceIn(0.0f, 1.0f)
                            val tappedHz = (20.0 * 1000.0.pow(normalized.toDouble())).toFloat()
                            val nearest = bands.indices.minByOrNull { index ->
                                abs(ln(
                                    bands[index].frequencyHz.coerceAtLeast(1.0f) /
                                        tappedHz.coerceAtLeast(1.0f)
                                ))
                            }
                            nearest?.let(onBandSelected)
                        }
                    }
                }
        ) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas

            val left = 12f
            val right = size.width - 12f
            val top = 12f
            val bottom = size.height - 14f
            val plotWidth = right - left
            val plotHeight = bottom - top

            fun xForFrequency(frequencyHz: Float): Float {
                val normalized = ln(
                    frequencyHz.coerceIn(20f, 20_000f) / 20f
                ) / ln(1000f)
                return left + normalized * plotWidth
            }

            fun yForGain(gainDb: Float): Float {
                val normalized = (gainDb.coerceIn(-24f, 24f) + 24f) / 48f
                return bottom - normalized * plotHeight
            }

            val grid = Color(0xFF303A49)
            val zeroLine = Color(0xFF64748B)
            val frequencyMarks = listOf(20f, 50f, 100f, 200f, 500f, 1_000f, 2_000f, 5_000f, 10_000f, 20_000f)
            frequencyMarks.forEach { frequency ->
                val x = xForFrequency(frequency)
                drawLine(
                    color = grid,
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 1f
                )
            }

            listOf(-24f, -12f, 0f, 12f, 24f).forEach { gain ->
                val y = yForGain(gain)
                drawLine(
                    color = if (gain == 0f) zeroLine else grid,
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = if (gain == 0f) 1.5f else 1f
                )
            }

            if (points.size > 1) {
                val curve = Path().apply {
                    val first = points.first()
                    moveTo(left, yForGain(first.gainDb))
                    var previous = Offset(left, yForGain(first.gainDb))
                    points.drop(1).forEach { point ->
                        val current = Offset(
                            xForFrequency(point.frequencyHz),
                            yForGain(point.gainDb)
                        )
                        val dx = (current.x - previous.x) / 3f
                        cubicTo(
                            previous.x + dx,
                            previous.y,
                            current.x - dx,
                            current.y,
                            current.x,
                            current.y
                        )
                        previous = current
                    }
                }

                val area = Path().apply {
                    val first = points.first()
                    moveTo(left, bottom)
                    lineTo(left, yForGain(first.gainDb))
                    var previous = Offset(left, yForGain(first.gainDb))
                    points.drop(1).forEach { point ->
                        val current = Offset(
                            xForFrequency(point.frequencyHz),
                            yForGain(point.gainDb)
                        )
                        val dx = (current.x - previous.x) / 3f
                        cubicTo(
                            previous.x + dx,
                            previous.y,
                            current.x - dx,
                            current.y,
                            current.x,
                            current.y
                        )
                        previous = current
                    }
                    lineTo(right, bottom)
                    close()
                    fillType = PathFillType.NonZero
                }

                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF35D6FF).copy(alpha = 0.30f),
                            Color(0xFF35D6FF).copy(alpha = 0.015f),
                            Color.Transparent
                        ),
                        startY = top,
                        endY = bottom
                    )
                )
                drawPath(
                    path = curve,
                    color = Color(0xFF38D8FF),
                    style = Stroke(width = 3f, cap = StrokeCap.Round)
                )
            }

            bands.forEachIndexed { index, band ->
                val position = Offset(
                    xForFrequency(band.frequencyHz),
                    yForGain(band.gainDb)
                )
                if (index == selectedBandIndex) {
                    drawCircle(
                        color = Color(0xFF49E1FF).copy(alpha = 0.22f),
                        radius = 12f,
                        center = position
                    )
                    drawCircle(
                        color = Color(0xFF8AF0FF),
                        radius = 6f,
                        center = position,
                        style = Stroke(width = 2f)
                    )
                } else {
                    drawCircle(
                        color = if (band.enabled) Color(0xFFD8F8FF) else Color(0xFF64748B),
                        radius = 3.5f,
                        center = position
                    )
                }
            }
        }
    }
}
