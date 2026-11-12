package com.rohitkhandel.ggequalizer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Lightweight FFT spectrum backdrop. The caller places this before the EQ
 * response canvas in a Box so the curve and band nodes stay visually on top.
 */
@Composable
fun SpectrumVisualizerCanvas(
    bars: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f || bars.isEmpty()) return@Canvas

        val count = bars.size
        val gap = 2.dp.toPx()
        val barWidth = max(1f, (size.width - gap * (count - 1)) / count)
        val maxHeight = size.height * 0.88f

        bars.forEachIndexed { index, level ->
            val normalized = level.coerceIn(0.0f, 1.0f)
            val height = max(2f, maxHeight * normalized)
            val left = index * (barWidth + gap)
            val top = size.height - height
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF46DFFF).copy(alpha = 0.09f + normalized * 0.18f),
                        Color(0xFF279FCB).copy(alpha = 0.06f + normalized * 0.13f),
                        Color(0xFF1C5F83).copy(alpha = 0.02f + normalized * 0.07f)
                    ),
                    startY = top,
                    endY = size.height
                ),
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.4f)
            )
        }

        val baseline = size.height - 1.dp.toPx()
        drawLine(
            color = Color(0xFF42D9F5).copy(alpha = 0.12f),
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = 1.dp.toPx()
        )
    }
}
