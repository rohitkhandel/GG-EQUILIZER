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
    knobSize: Dp = 150.dp
) {
    val range = max(0.0001f, valueRange.endInclusive - valueRange.start)
    val localValue = remember(valueRange.start, valueRange.endInclusive) { mutableFloatStateOf(value) }
    val dragging = remember { mutableStateOf(false) }
    val currentCallback = rememberUpdatedState(onValueChange)

    LaunchedEffect(value) {
        if (!dragging.value) localValue.floatValue = value
    }

    val normalized = ((localValue.floatValue - valueRange.start) / range).coerceIn(0f, 1f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(knobSize)
                .pointerInput(valueRange.start, valueRange.endInclusive, defaultValue) {
                    var dragStartValue = valueRange.start
                    var accumulatedDrag = 0f
                    var lastDispatchMs = 0L
                    detectDragGestures(
                        onDragStart = {
                            dragging.value = true
                            dragStartValue = localValue.floatValue
                            accumulatedDrag = 0f
                            lastDispatchMs = 0L
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumulatedDrag -= dragAmount.y
                            val next = (dragStartValue + accumulatedDrag / 205f * range)
                                .coerceIn(valueRange.start, valueRange.endInclusive)
                            localValue.floatValue = next
                            val now = SystemClock.uptimeMillis()
                            if (lastDispatchMs == 0L || now - lastDispatchMs >= 20L) {
                                currentCallback.value(next)
                                lastDispatchMs = now
                            }
                        },
                        onDragEnd = {
                            currentCallback.value(localValue.floatValue)
                            dragging.value = false
                        },
                        onDragCancel = {
                            currentCallback.value(localValue.floatValue)
                            dragging.value = false
                        }
                    )
                }
                .pointerInput(defaultValue, valueRange.start, valueRange.endInclusive) {
                    detectTapGestures(
                        onDoubleTap = {
                            localValue.floatValue = defaultValue
                            currentCallback.value(defaultValue)
                            dragging.value = false
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(knobSize)) {
                val radius = size.minDimension * 0.46f
                val centerPoint = Offset(center.x, center.y)
                drawCircle(Color(0xFF000000).copy(alpha = 0.46f), radius, centerPoint + Offset(0f, 2.dp.toPx()))
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFF56616D), Color(0xFF202832), Color(0xFF0B0F14)),
                        center = centerPoint,
                        radius = radius
                    ),
                    radius * 0.88f,
                    centerPoint
                )
                drawCircle(
                    Brush.linearGradient(
                        listOf(Color(0xFF8E9AA6), Color(0xFF303A45), Color(0xFF11161D))
                    ),
                    radius * 0.88f,
                    centerPoint,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    Color(0xFF74808D).copy(alpha = 0.50f),
                    radius * 0.76f,
                    centerPoint,
                    style = Stroke(width = 1.dp.toPx())
                )

                val arcSize = radius * 1.70f
                val arcTopLeft = Offset(center.x - arcSize / 2f, center.y - arcSize / 2f)
                val arcStroke = (size.minDimension * 0.052f).coerceAtLeast(4.dp.toPx())
                drawArc(
                    Color(0xFF27323E),
                    135f,
                    270f,
                    false,
                    arcTopLeft,
                    Size(arcSize, arcSize),
                    style = Stroke(arcStroke, cap = StrokeCap.Round)
                )
                drawArc(
                    accent.copy(alpha = 0.22f),
                    135f,
                    270f * normalized,
                    false,
                    arcTopLeft,
                    Size(arcSize, arcSize),
                    style = Stroke(arcStroke + 5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    accent,
                    135f,
                    270f * normalized,
                    false,
                    arcTopLeft,
                    Size(arcSize, arcSize),
                    style = Stroke(arcStroke, cap = StrokeCap.Round)
                )

                val tickOuter = radius * 1.10f
                val tickInner = radius * 0.98f
                for (index in 0..30) {
                    val progress = index / 30f
                    val angle = (135.0 + progress * 270.0) * PI / 180.0
                    val outer = Offset(
                        center.x + cos(angle).toFloat() * tickOuter,
                        center.y + sin(angle).toFloat() * tickOuter
                    )
                    val inner = Offset(
                        center.x + cos(angle).toFloat() * tickInner,
                        center.y + sin(angle).toFloat() * tickInner
                    )
                    drawLine(
                        if (progress <= normalized) accent else Color(0xFF697583),
                        inner,
                        outer,
                        if (index % 3 == 0) 1.8.dp.toPx() else 0.9.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }

                val needleAngle = (135.0 + normalized * 270.0) * PI / 180.0
                val needleStart = Offset(
                    center.x + cos(needleAngle).toFloat() * radius * 0.26f,
                    center.y + sin(needleAngle).toFloat() * radius * 0.26f
                )
                val needleEnd = Offset(
                    center.x + cos(needleAngle).toFloat() * radius * 0.61f,
                    center.y + sin(needleAngle).toFloat() * radius * 0.61f
                )
                drawLine(accent.copy(alpha = 0.34f), needleStart, needleEnd, 7.dp.toPx(), StrokeCap.Round)
                drawLine(accent, needleStart, needleEnd, 2.dp.toPx(), StrokeCap.Round)
                drawCircle(Color(0xFFB9C2CA), radius * 0.055f, centerPoint)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    valueText,
                    color = Color(0xFFF4F7FA),
                    fontSize = if (knobSize >= 120.dp) 23.sp else 13.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                Text(
                    "LEVEL",
                    color = Color(0xFF8392A2),
                    fontSize = if (knobSize >= 120.dp) 7.sp else 6.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
        Text(
            label.uppercase(),
            color = Color(0xFFCAD4DE),
            fontSize = if (knobSize >= 120.dp) 10.sp else 8.sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
