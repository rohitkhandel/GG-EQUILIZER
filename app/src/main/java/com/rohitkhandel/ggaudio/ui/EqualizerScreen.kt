package com.rohitkhandel.ggaudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohitkhandel.ggaudio.audio.EqualizerBand
import com.rohitkhandel.ggaudio.audio.EqualizerDefaults
import com.rohitkhandel.ggaudio.audio.EqualizerPresetLibrary
import com.rohitkhandel.ggaudio.audio.SavedEqualizerPreset
import com.rohitkhandel.ggaudio.ui.theme.*
import com.rohitkhandel.ggaudio.ui.theme.Surface as CardSurface

private val profiles = EqualizerPresetLibrary.builtIn.associate { it.name to it.gainsDb }

@Composable
fun EqualizerScreen(
    bands: List<EqualizerBand>,
    presetName: String,
    preamp: Float,
    onPreampChange: (Float) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    savedPresets: List<SavedEqualizerPreset>,
    onToneClick: () -> Unit,
    onSaveClick: () -> Unit,
    onResetClick: () -> Unit,
    onPresetChange: (String, List<EqualizerBand>, Float) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("AUDIO PROCESSING", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                Text("Equalizer", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Surface(
                color = Color(0xFF14291D),
                shape = RoundedCornerShape(5.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF315B40))
            ) {
                Text("LOCAL DSP", color = AccentSoft, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("PRESET", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Bold)
            Box {
                Surface(
                    onClick = { menuOpen = true },
                    color = CardSurface,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Outline)
                ) {
                    Text(presetName + "  ▾", color = TextPrimary, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    profiles.forEach { (name, values) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = {
                                menuOpen = false
                                onPresetChange(name, bands.mapIndexed { index, band -> band.copy(gainDb = values[index]) }, 0f)
                            }
                        )
                    }
                    if (savedPresets.isNotEmpty()) {
                        Text("SAVED PRESETS", color = TextMuted, fontSize = 8.sp, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp))
                        savedPresets.forEach { saved ->
                            DropdownMenuItem(
                                text = { Text(saved.name) },
                                onClick = {
                                    menuOpen = false
                                    onPresetChange(saved.name, saved.toBands(), saved.preampDb)
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("MANAGE", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onSaveClick).padding(8.dp))
        }

        Surface(
            Modifier.fillMaxWidth(),
            color = CardSurface,
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Outline)
        ) {
            Column(Modifier.padding(horizontal = 9.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("GRAPHIC EQUALIZER", color = TextPrimary, fontSize = 8.sp, letterSpacing = 1.0.sp, fontWeight = FontWeight.Bold)
                        Text("10-BAND  ·  ±12 dB", color = TextMuted, fontSize = 7.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Text((if (preamp > 0f) "+" else "") + "%.1f".format(preamp) + " dB", color = AccentSoft, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    VerticalValueControl(
                        label = "PRE",
                        value = preamp,
                        modifier = Modifier.weight(1f),
                        contentDescription = "Preamp gain",
                        onValueChange = onPreampChange
                    )
                    bands.forEachIndexed { index, band ->
                        VerticalValueControl(
                            label = band.label,
                            value = band.gainDb,
                            modifier = Modifier.weight(1f),
                            contentDescription = band.label + " hertz gain",
                            onValueChange = { onBandChange(index, it) }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("−12 dB", color = TextMuted, fontSize = 7.sp)
                    Text("0 dB", color = TextMuted, fontSize = 7.sp)
                    Text("+12 dB", color = TextMuted, fontSize = 7.sp)
                }
            }
        }

        Surface(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            color = CardSurface,
            shape = RoundedCornerShape(7.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Outline)
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("FREQUENCY RESPONSE", color = TextPrimary, fontSize = 7.sp, letterSpacing = 1.0.sp, fontWeight = FontWeight.Bold)
                    Text(presetName.uppercase() + " CURVE", color = Accent, fontSize = 7.sp)
                }
                ResponseGraph(bands, Modifier.fillMaxWidth().height(58.dp).padding(top = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("31 Hz", "125 Hz", "500 Hz", "2 kHz", "8 kHz", "16 kHz").forEach {
                        Text(it, color = TextMuted, fontSize = 6.sp)
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            ActionChip("EQU", selected = true, modifier = Modifier.weight(1f), onClick = null)
            ActionChip("TONE", selected = false, modifier = Modifier.weight(1f), onClick = onToneClick)
            ActionChip("LIMIT · −1 dB", selected = true, modifier = Modifier.weight(1.25f), onClick = null)
            ActionChip("SAVE", selected = false, modifier = Modifier.weight(1f), onClick = onSaveClick)
            ActionChip("RESET", selected = false, modifier = Modifier.weight(1f), onClick = onResetClick)
        }
        Text(
            "DSP processes audio played inside GG Audio. Other apps need a supported Android audio-effect session.",
            color = TextMuted,
            fontSize = 8.sp,
            lineHeight = 12.sp,
            modifier = Modifier.padding(top = 5.dp, bottom = 6.dp)
        )
    }
}

@Composable
private fun ActionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: (() -> Unit)?
) {
    val actionModifier = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Surface(
        actionModifier.height(31.dp),
        color = if (selected) Color(0xFF14291D) else CardSurface,
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color(0xFF426B50) else Outline)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) AccentSoft else TextMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun ResponseGraph(bands: List<EqualizerBand>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (bands.isEmpty()) return@Canvas
        val left = 5.dp.toPx()
        val right = size.width - left
        val width = right - left
        val baseline = size.height / 2f
        for (i in 0..4) {
            val y = size.height * i / 4f
            drawLine(Outline, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
        }
        val path = Path()
        bands.forEachIndexed { index, band ->
            val x = left + width * index / (bands.size - 1).coerceAtLeast(1)
            val y = baseline - (band.gainDb / 12f) * (size.height * .42f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        bands.forEachIndexed { index, band ->
            val x = left + width * index / (bands.size - 1).coerceAtLeast(1)
            val y = baseline - (band.gainDb / 12f) * (size.height * .42f)
            drawCircle(AccentSoft, radius = 2.4.dp.toPx(), center = Offset(x, y))
        }
    }
}

@Composable
private fun VerticalValueControl(
    label: String,
    value: Float,
    modifier: Modifier = Modifier,
    contentDescription: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier.padding(horizontal = 1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            (if (value > 0f) "+" else "") + "%.1f".format(value),
            color = AccentSoft,
            fontSize = 6.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Canvas(
            Modifier.fillMaxWidth().height(112.dp).padding(vertical = 4.dp)
                .semantics {
                    this.contentDescription = contentDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(value, -12f..12f)
                }
                .pointerInput(value) {
                    detectDragGestures { change, _ ->
                        val next = (1f - change.position.y / size.height) * 24f - 12f
                        onValueChange(((next * 2f).toInt() / 2f).coerceIn(-12f, 12f))
                    }
                }
                .pointerInput(value) {
                    detectTapGestures { point ->
                        val next = (1f - point.y / size.height) * 24f - 12f
                        onValueChange(((next * 2f).toInt() / 2f).coerceIn(-12f, 12f))
                    }
                }
        ) {
            val x = size.width / 2f
            val zero = size.height / 2f
            val knob = size.height * (1f - (value.coerceIn(-12f, 12f) + 12f) / 24f)
            drawLine(Outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Accent, Offset(x, zero), Offset(x, knob), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Outline, Offset(0f, zero), Offset(size.width, zero), strokeWidth = 1.dp.toPx())
            drawCircle(Accent, 6.dp.toPx(), Offset(x, knob))
            drawCircle(Background, 2.dp.toPx(), Offset(x, knob))
        }
        Text(label, color = TextPrimary, fontSize = 7.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
