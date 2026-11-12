package com.rohitkhandel.ggequalizer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
    accent: Color = Color(0xFF43D9F5)
) {
    val range = max(0.0001f, valueRange.endInclusive - valueRange.start)
    val normalized = ((value - valueRange.start) / range).coerceIn(0f, 1f)
    val animatedNormalized by animateFloatAsState(normalized, animationSpec = tween(120), label = "knob-progress")

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(94.dp)
                .pointerInput(valueRange, value) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val delta = -dragAmount.y / 210f * range
                        onValueChange((value + delta).coerceIn(
                            valueRange.start,
                            valueRange.endInclusive
                        ))
                    }
                }
                .pointerInput(defaultValue) {
                    detectTapGestures(
                        onDoubleTap = { onValueChange(defaultValue) }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(94.dp)) {
                val strokeWidth = 7.dp.toPx()
                val arcSize = size.minDimension - strokeWidth * 2.4f
                val topLeft = Offset(
                    (size.width - arcSize) / 2f,
                    (size.height - arcSize) / 2f
                )

                drawArc(
                    color = Color(0xFF273342),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    color = accent.copy(alpha = 0.16f),
                    startAngle = 135f,
                    sweepAngle = 270f * animatedNormalized,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidth + 5.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = accent,
                    startAngle = 135f,
                    sweepAngle = 270f * normalized,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                val tickRadius = size.minDimension * 0.43f
                val tickInner = size.minDimension * 0.37f
                for (index in 0..24) {
                    val angle = (135.0 + index * (270.0 / 24.0)) * PI / 180.0
                    val outer = Offset(
                        center.x + cos(angle).toFloat() * tickRadius,
                        center.y + sin(angle).toFloat() * tickRadius
                    )
                    val inner = Offset(
                        center.x + cos(angle).toFloat() * tickInner,
                        center.y + sin(angle).toFloat() * tickInner
                    )
                    drawLine(
                        color = if (index / 24f <= animatedNormalized) accent.copy(alpha = 0.90f)
                        else Color(0xFF536173),
                        start = inner,
                        end = outer,
                        strokeWidth = if (index % 3 == 0) 1.8.dp.toPx() else 1.dp.toPx()
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = valueText,
                    color = Color(0xFFF0F6FC),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "DBL TAP",
                    color = Color(0xFF75859A),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Text(
            text = label.uppercase(),
            color = Color(0xFFB9C7D8),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}
