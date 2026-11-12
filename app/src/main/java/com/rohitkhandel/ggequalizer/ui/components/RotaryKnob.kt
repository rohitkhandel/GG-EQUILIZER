package com.rohitkhandel.ggequalizer.ui.components

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Custom rotary control used by the EQ and tone racks.
 * Vertical drag changes the value; double tap restores the supplied default.
 * UI state is kept locally while dragging and DSP updates are capped to one per frame.
 */
@Composable
fun RotaryKnob(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    valueText: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFFF5367),
    knobSize: Dp = 112.dp,
    resetOnDoubleTap: Boolean = true
) {
    val range = max(0.0001f, valueRange.endInclusive - valueRange.start)
    val localValue = remember(valueRange.start, valueRange.endInclusive) { mutableFloatStateOf(value) }
    val dragging = remember { mutableStateOf(false) }
    val callback = rememberUpdatedState(onValueChange)
    val normalized = ((localValue.floatValue - valueRange.start) / range).coerceIn(0f, 1f)

    LaunchedEffect(value) {
        if (!dragging.value) localValue.floatValue = value
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(valueRange.start, valueRange.endInclusive) {
                    var startValue = valueRange.start
                    var accumulated = 0f
                    var lastDispatch = 0L
                    detectDragGestures(
                        onDragStart = {
                            dragging.value = true
                            startValue = localValue.floatValue
                            accumulated = 0f
                            lastDispatch = 0L
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            accumulated -= amount.y
                            val next = (startValue + accumulated / 205f * range)
                                .coerceIn(valueRange.start, valueRange.endInclusive)
                            localValue.floatValue = next
                            val now = SystemClock.uptimeMillis()
                            if (lastDispatch == 0L || now - lastDispatch >= 16L) {
                                callback.value(next)
                                lastDispatch = now
                            }
                        },
                        onDragEnd = {
                            callback.value(localValue.floatValue)
                            dragging.value = false
                        },
                        onDragCancel = {
                            callback.value(localValue.floatValue)
                            dragging.value = false
                        }
                    )
                }
                .pointerInput(defaultValue, resetOnDoubleTap) {
                    if (resetOnDoubleTap) {
                        detectTapGestures(
                            onDoubleTap = {
                                localValue.floatValue = defaultValue.coerceIn(valueRange.start, valueRange.endInclusive)
                                callback.value(localValue.floatValue)
                                dragging.value = false
                            }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(knobSize)) {
                val radius = size.minDimension * 0.365f
                val c = center
                val stroke = (size.minDimension * 0.045f).coerceAtLeast(2.4.dp.toPx())

                drawCircle(Color.Black.copy(alpha = 0.72f), radius * 1.10f, c + Offset(0f, 2.dp.toPx()))
                drawCircle(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF4B535E), Color(0xFF262C34), Color(0xFF111419)),
                        center = c - Offset(radius * 0.22f, radius * 0.25f),
                        radius = radius * 1.55f
                    ),
                    radius * 1.02f,
                    c
                )
                drawCircle(
                    Brush.linearGradient(
                        listOf(Color(0xFF929BA5), Color(0xFF414A54), Color(0xFF171B20))
                    ),
                    radius * 1.02f,
                    c,
                    style = Stroke(width = 1.7.dp.toPx())
                )
                drawCircle(
                    Color(0xFF050608),
                    radius * 0.90f,
                    c,
                    style = Stroke(width = 1.dp.toPx())
                )

                val arcDiameter = radius * 2.32f
                val arcTopLeft = Offset(c.x - arcDiameter / 2f, c.y - arcDiameter / 2f)
                drawArc(
                    Color(0xFF353C45),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = Size(arcDiameter, arcDiameter),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (normalized > 0.001f) {
                    drawArc(
                        accent.copy(alpha = 0.18f),
                        startAngle = 135f,
                        sweepAngle = 270f * normalized,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = Size(arcDiameter, arcDiameter),
                        style = Stroke(width = stroke + 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                    drawArc(
                        accent,
                        startAngle = 135f,
                        sweepAngle = 270f * normalized,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = Size(arcDiameter, arcDiameter),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }

                for (tick in 0..16) {
                    val progress = tick / 16f
                    val angle = (135.0 + 270.0 * progress) * PI / 180.0
                    val outerRadius = radius * 1.23f
                    val innerRadius = radius * if (tick % 2 == 0) 1.08f else 1.13f
                    val outer = Offset(
                        c.x + cos(angle).toFloat() * outerRadius,
                        c.y + sin(angle).toFloat() * outerRadius
                    )
                    val inner = Offset(
                        c.x + cos(angle).toFloat() * innerRadius,
                        c.y + sin(angle).toFloat() * innerRadius
                    )
                    drawLine(
                        if (progress <= normalized) accent.copy(alpha = 0.88f) else Color(0xFF737D88),
                        inner,
                        outer,
                        if (tick % 2 == 0) 1.5.dp.toPx() else 0.8.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                val needleAngle = (135.0 + normalized * 270.0) * PI / 180.0
                val needleStart = Offset(
                    c.x + cos(needleAngle).toFloat() * radius * 0.22f,
                    c.y + sin(needleAngle).toFloat() * radius * 0.22f
                )
                val needleEnd = Offset(
                    c.x + cos(needleAngle).toFloat() * radius * 0.68f,
                    c.y + sin(needleAngle).toFloat() * radius * 0.68f
                )
                drawLine(accent.copy(alpha = 0.22f), needleStart, needleEnd, 7.dp.toPx(), StrokeCap.Round)
                drawLine(Color(0xFFE9EDF1), needleStart, needleEnd, 2.dp.toPx(), StrokeCap.Round)
                drawCircle(Color(0xFFD8DEE5), radius * 0.055f, c)
            }

            Text(
                valueText,
                color = Color(0xFFF4F6F8),
                fontSize = if (knobSize >= 105.dp) 13.sp else 8.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }

        Text(
            label.uppercase(),
            color = Color(0xFFD4DBE2),
            fontSize = if (knobSize >= 105.dp) 8.sp else 6.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
