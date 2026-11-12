package com.rohitkhandel.ggequalizer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A purpose-built vertical EQ fader. Its gesture coroutine is keyed only by
 * the range, not the changing value, so value updates cannot cancel a drag.
 */
@Composable
fun BandSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val normalized = ((value - valueRange.start) / range).coerceIn(0f, 1f)

    Canvas(
        modifier = modifier.pointerInput(valueRange) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun updateFromY(y: Float) {
                    val fraction = (1f - y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    latestOnValueChange(valueRange.start + fraction * range)
                }
                updateFromY(down.position.y)
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.pressed) updateFromY(change.position.y)
                    change.consume()
                    pressed = change.pressed
                }
            }
        }
    ) {
        val centerX = size.width * 0.5f
        val top = 7.dp.toPx()
        val bottom = size.height - 7.dp.toPx()
        val usableHeight = (bottom - top).coerceAtLeast(1f)
        val zeroY = top + usableHeight * 0.5f
        val valueY = bottom - normalized * usableHeight
        val trackWidth = 7.dp.toPx()
        val thumbWidth = size.width * 0.66f
        val thumbHeight = 10.dp.toPx()
        val activeColor = if (selected) accent else accent.copy(alpha = 0.62f)

        drawRoundRect(
            color = Color(0xFF333A43),
            topLeft = Offset(centerX - trackWidth / 2f, top),
            size = Size(trackWidth, usableHeight),
            cornerRadius = CornerRadius(trackWidth)
        )
        drawRoundRect(
            color = activeColor.copy(alpha = if (selected) 0.82f else 0.48f),
            topLeft = Offset(centerX - trackWidth / 2f, minOf(valueY, zeroY)),
            size = Size(trackWidth, kotlin.math.abs(zeroY - valueY).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(trackWidth)
        )
        drawLine(
            color = Color(0xFF788390),
            start = Offset(centerX - 12.dp.toPx(), zeroY),
            end = Offset(centerX + 12.dp.toPx(), zeroY),
            strokeWidth = 1.dp.toPx()
        )
        for (tick in 0..4) {
            val y = top + usableHeight * (tick / 4f)
            drawLine(
                color = Color(0xFF687482).copy(alpha = 0.68f),
                start = Offset(centerX + 8.dp.toPx(), y),
                end = Offset(centerX + 12.dp.toPx(), y),
                strokeWidth = 1.dp.toPx()
            )
        }
        drawRoundRect(
            color = if (selected) accent else Color(0xFFD5D9DE),
            topLeft = Offset(centerX - thumbWidth / 2f, valueY - thumbHeight / 2f),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbHeight / 2f)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.26f),
            topLeft = Offset(centerX - thumbWidth / 2f, valueY - thumbHeight / 2f),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbHeight / 2f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
        )
    }
}
