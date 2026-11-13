package com.rohitkhandel.ggaudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
    onManagePresets: () -> Unit,
    onPresetChange: (String, List<EqualizerBand>, Float) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Background).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 15.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("TONE CONTROL", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                Text("Equalizer", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("Shape your sound with precision.", color = TextMuted, fontSize = 11.sp)
            }
            Text("RESET", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(CardSurface).padding(horizontal = 12.dp, vertical = 9.dp)
                    .pointerInput(Unit) { detectTapGestures { onPresetChange("Flat", EqualizerDefaults.flat(), 0f) } })
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 11.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("CURRENT PRESET", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                Box {
                    Surface(onClick = { menuOpen = true }, color = CardSurface, shape = RoundedCornerShape(9.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline)) {
                        Text(presetName + "  ▾", color = TextPrimary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        profiles.forEach { (name, values) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = {
                                menuOpen = false
                                onPresetChange(name, bands.mapIndexed { index, band -> band.copy(gainDb = values[index]) }, 0f)
                            })
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
            }
            Text(
                "✦  MANAGE",
                color = Accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onManagePresets).padding(horizontal = 8.dp, vertical = 8.dp)
            )
        }
        Surface(Modifier.fillMaxWidth(), color = CardSurface, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(13.dp)) {
                Text("FREQUENCY RESPONSE", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                Text(presetName.uppercase() + "  ·  PREVIEW CURVE", color = TextPrimary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
                ResponseGraph(bands, Modifier.fillMaxWidth().height(112.dp).padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("31 Hz","125 Hz","500 Hz","2 kHz","8 kHz","16 kHz").forEach { Text(it, color = TextMuted, fontSize = 7.sp) }
                }
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 11.dp), color = CardSurface, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(horizontal = 11.dp, vertical = 13.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("10-BAND GRAPHIC EQ", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                        Text("Fine tune each frequency", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("±12 dB", color = Accent, fontSize = 8.sp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    bands.forEachIndexed { index, band ->
                        VerticalBandControl(band, Modifier.width(27.dp)) { onBandChange(index, it) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("−12 dB", color = TextMuted, fontSize = 8.sp)
                    Text("0 dB", color = TextMuted, fontSize = 8.sp)
                    Text("+12 dB", color = TextMuted, fontSize = 8.sp)
                }
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 18.dp), color = CardSurface, shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline)) {
            Column(Modifier.padding(13.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("OUTPUT LEVEL  ·  PREAMP", color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text((if (preamp > 0) "+" else "") + "%.1f".format(preamp) + " dB", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Slider(value = preamp, onValueChange = onPreampChange, valueRange = -12f..12f)
                Text("Preamp is applied to local playback inside GG Audio.", color = TextMuted, fontSize = 8.sp, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
private fun ResponseGraph(bands: List<EqualizerBand>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val left = 8.dp.toPx()
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
        drawPath(path, Accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        bands.forEachIndexed { index, band ->
            val x = left + width * index / (bands.size - 1).coerceAtLeast(1)
            val y = baseline - (band.gainDb / 12f) * (size.height * .42f)
            drawCircle(Accent, radius = 3.dp.toPx(), center = Offset(x, y))
        }
    }
}

@Composable
private fun VerticalBandControl(band: EqualizerBand, modifier: Modifier = Modifier, onValueChange: (Float) -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text((if (band.gainDb > 0) "+" else "") + "%.1f".format(band.gainDb), color = if (band.gainDb < 0) Blue else Accent, fontSize = 7.sp, fontWeight = FontWeight.SemiBold)
        Canvas(
            Modifier.fillMaxWidth().height(112.dp).padding(vertical = 4.dp)
                .semantics {
                    contentDescription = band.label + " Hz gain"
                    progressBarRangeInfo = ProgressBarRangeInfo(band.gainDb, -12f..12f)
                }
                .pointerInput(band.gainDb) {
                    detectDragGestures { change, _ ->
                        val next = (1f - change.position.y / size.height) * 24f - 12f
                        onValueChange(((next * 2f).toInt() / 2f).coerceIn(-12f, 12f))
                    }
                }
                .pointerInput(band.gainDb) {
                    detectTapGestures { point ->
                        val next = (1f - point.y / size.height) * 24f - 12f
                        onValueChange(((next * 2f).toInt() / 2f).coerceIn(-12f, 12f))
                    }
                }
        ) {
            val x = size.width / 2f
            val zero = size.height / 2f
            val knob = size.height * (1f - (band.gainDb + 12f) / 24f)
            drawLine(Outline, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Accent, Offset(x, zero), Offset(x, knob), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(Accent, 6.dp.toPx(), Offset(x, knob))
            drawCircle(Background, 2.dp.toPx(), Offset(x, knob))
        }
        Text(band.label, color = TextPrimary, fontSize = 7.sp, fontWeight = FontWeight.SemiBold)
    }
}
