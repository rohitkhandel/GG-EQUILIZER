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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Original custom Canvas rotary control with local drag state and a 20 ms DSP dispatch cap. */
@Composable
fun RotaryKnob(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    defaultValue: Float,
    valueText: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF42D9F5),
    knobSize: Dp = 145.dp,
    resetOnDoubleTap: Boolean = true
) {
    val range = max(0.0001f, valueRange.endInclusive - valueRange.start)
    val localValue = remember(valueRange.start, valueRange.endInclusive) { mutableFloatStateOf(value) }
    val dragging = remember { mutableStateOf(false) }
    val callback = rememberUpdatedState(onValueChange)

    LaunchedEffect(value) {
        if (!dragging.value) localValue.floatValue = value
    }

    val normalized = ((localValue.floatValue - valueRange.start) / range).coerceIn(0f, 1f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(valueRange.start, valueRange.endInclusive, defaultValue) {
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
                            val next = (startValue + accumulated / 210f * range)
                                .coerceIn(valueRange.start, valueRange.endInclusive)
                            localValue.floatValue = next
                            val now = SystemClock.uptimeMillis()
                            if (lastDispatch == 0L || now - lastDispatch >= 20L) {
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
                                localValue.floatValue = defaultValue
                                callback.value(defaultValue)
                                dragging.value = false
                            }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(knobSize)) {
                val radius = size.minDimension * 0.43f
                val c = center
                drawCircle(Color.Black.copy(alpha = 0.48f), radius, c + Offset(0f, 2.dp.toPx()))
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFF65717E), Color(0xFF2B343F), Color(0xFF0D1117)),
                        center = c,
                        radius = radius
                    ),
                    radius * 0.91f,
                    c
                )
                drawCircle(
                    Brush.linearGradient(
                        listOf(Color(0xFF9EA9B5), Color(0xFF37424E), Color(0xFF121820))
                    ),
                    radius * 0.91f,
                    c,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    Color(0xFF8794A2).copy(alpha = 0.44f),
                    radius * 0.78f,
                    c,
                    style = Stroke(width = 1.dp.toPx())
                )

                val arcSize = radius * 1.82f
                val arcTop = Offset(c.x - arcSize / 2f, c.y - arcSize / 2f)
                val arcStroke = (size.minDimension * 0.045f).coerceAtLeast(3.dp.toPx())
                drawArc(
                    Color(0xFF28323D),
                    135f, 270f, false,
                    arcTop, Size(arcSize, arcSize),
                    style = Stroke(arcStroke, cap = StrokeCap.Round)
                )
                drawArc(
                    accent.copy(alpha = 0.20f),
                    135f, 270f * normalized, false,
                    arcTop, Size(arcSize, arcSize),
                    style = Stroke(arcStroke + 5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    accent,
                    135f, 270f * normalized, false,
                    arcTop, Size(arcSize, arcSize),
                    style = Stroke(arcStroke, cap = StrokeCap.Round)
                )

                for (tick in 0..30) {
                    val progress = tick / 30f
                    val angle = (135.0 + progress * 270.0) * PI / 180.0
                    val outerRadius = radius * 1.10f
                    val innerRadius = radius * if (tick % 3 == 0) 0.97f else 1.01f
                    val outer = Offset(
                        c.x + cos(angle).toFloat() * outerRadius,
                        c.y + sin(angle).toFloat() * outerRadius
                    )
                    val inner = Offset(
                        c.x + cos(angle).toFloat() * innerRadius,
                        c.y + sin(angle).toFloat() * innerRadius
                    )
                    drawLine(
                        if (progress <= normalized) accent else Color(0xFF6A7684),
                        inner,
                        outer,
                        if (tick % 3 == 0) 1.8.dp.toPx() else 0.9.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                val needle = (135.0 + normalized * 270.0) * PI / 180.0
                val a = Offset(
                    c.x + cos(needle).toFloat() * radius * 0.20f,
                    c.y + sin(needle).toFloat() * radius * 0.20f
                )
                val b = Offset(
                    c.x + cos(needle).toFloat() * radius * 0.63f,
                    c.y + sin(needle).toFloat() * radius * 0.63f
                )
                drawLine(accent.copy(alpha = 0.32f), a, b, 7.dp.toPx(), StrokeCap.Round)
                drawLine(accent, a, b, 2.dp.toPx(), StrokeCap.Round)
                drawCircle(Color(0xFFC5CDD5), radius * 0.055f, c)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    valueText,
                    color = Color(0xFFF5F8FB),
                    fontSize = if (knobSize >= 120.dp) 22.sp else 12.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Text(
                    "LEVEL",
                    color = Color(0xFF8593A2),
                    fontSize = if (knobSize >= 120.dp) 7.sp else 6.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Text(
            label.uppercase(),
            color = Color(0xFFD0D8E0),
            fontSize = if (knobSize >= 120.dp) 10.sp else 7.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
