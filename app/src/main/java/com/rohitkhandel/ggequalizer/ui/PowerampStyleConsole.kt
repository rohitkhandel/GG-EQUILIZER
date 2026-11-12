package com.rohitkhandel.ggequalizer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge
import com.rohitkhandel.ggequalizer.presets.PresetEntity
import com.rohitkhandel.ggequalizer.ui.components.FrequencyResponseCanvas
import com.rohitkhandel.ggequalizer.ui.components.RotaryKnob
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

private val StudioBlack = Color(0xFF050608)
private val StudioSurface = Color(0xFF15191F)
private val StudioLine = Color(0xFF343B45)
private val StudioText = Color(0xFFF1F3F5)
private val StudioMuted = Color(0xFF89929D)
private val StudioRed = Color(0xFFFF5265)
private val StudioBlue = Color(0xFF51B8FF)

@Composable
fun StudioTopBar(
    state: EqUiState,
    accent: Color,
    onPower: (Boolean) -> Unit,
    onGraphicMode: () -> Unit,
    onParametricMode: () -> Unit,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit,
    onSelectPreset: (PresetEntity) -> Unit
) {
    var presetMenu by rememberSaveable { mutableStateOf(false) }
    var actionMenu by rememberSaveable { mutableStateOf(false) }
    val presetName = state.presets.firstOrNull { it.presetId == state.selectedPresetId }?.presetName ?: "Flat"

    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).background(StudioBlack).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.width(75.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                Modifier.size(31.dp).clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2A3038), Color(0xFF101318))))
                    .border(1.dp, Color(0xFF46505B), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(7, 14, 10).forEachIndexed { i, h ->
                        Box(Modifier.width(3.dp).height(h.dp).clip(CircleShape).background(if (i == 1) accent else Color.LightGray))
                    }
                }
            }
            Text("GG", color = StudioText, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp)
        }

        Box(
            Modifier.size(31.dp).clip(CircleShape)
                .background(if (state.masterEnabled) accent.copy(alpha = 0.16f) else StudioSurface)
                .border(1.dp, if (state.masterEnabled) accent else StudioLine, CircleShape)
                .clickable { onPower(!state.masterEnabled) },
            contentAlignment = Alignment.Center
        ) {
            Text("⏻", color = if (state.masterEnabled) accent else StudioMuted, fontSize = 17.sp)
        }

        Spacer(Modifier.weight(1f))
        Box {
            Column(
                Modifier.clip(RoundedCornerShape(18.dp)).background(StudioSurface)
                    .border(1.dp, Color(0xFF363E48), RoundedCornerShape(18.dp))
                    .clickable { presetMenu = true }.padding(horizontal = 12.dp, vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(presetName.uppercase(Locale.US), color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, maxLines = 1)
                Text("PRESET  ▾", color = StudioMuted, fontSize = 6.sp, letterSpacing = 1.sp)
            }
            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                state.presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.presetName) },
                        onClick = { presetMenu = false; onSelectPreset(preset) }
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))

        Row(
            Modifier.clip(RoundedCornerShape(18.dp)).background(StudioSurface).padding(horizontal = 7.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(Modifier.size(6.dp).background(if (state.activePlayerName != "No player detected") Color(0xFF50D890) else StudioMuted, CircleShape))
            Text(
                studioPlayerName(state.activePlayerName),
                color = if (state.activePlayerName == "No player detected") StudioMuted else StudioText,
                fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            Text("⋮", color = StudioText, fontSize = 22.sp, modifier = Modifier.clickable { actionMenu = true }.padding(start = 10.dp))
            DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                DropdownMenuItem(text = { Text("Graphic equalizer") }, onClick = { actionMenu = false; onGraphicMode() })
                DropdownMenuItem(text = { Text("Parametric equalizer") }, onClick = { actionMenu = false; onParametricMode() })
                DropdownMenuItem(text = { Text("Save preset") }, onClick = { actionMenu = false; onSave() })
                DropdownMenuItem(text = { Text("Import AutoEQ") }, onClick = { actionMenu = false; onImport() })
                DropdownMenuItem(text = { Text("Reset equalizer") }, onClick = { actionMenu = false; onReset() })
            }
        }
    }
}

@Composable
fun StudioBottomDock(selected: String, accent: Color, onSelect: (String) -> Unit) {
    val destinations = listOf("EQUALIZER" to "▥", "TONE" to "◉", "PRESETS" to "▦", "SETTINGS" to "⚙")
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 9.dp, vertical = 4.dp)
            .height(54.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF17191D))
            .border(1.dp, Color(0xFF282D34), RoundedCornerShape(28.dp)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        destinations.forEach { (key, icon) ->
            val active = selected == key
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(22.dp)).background(if (active) Color(0xFF30353D) else Color.Transparent)
                    .clickable { onSelect(key) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(icon, color = if (active) accent else Color(0xFF8B929A), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.padding(top = 1.dp).width(if (active) 12.dp else 3.dp).height(2.dp).clip(CircleShape).background(if (active) accent else Color.Transparent))
            }
        }
    }
}

@Composable
fun StudioEqualizerWorkspace(state: EqUiState, viewModel: EqViewModel, accent: Color, onVisualizerToggle: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().background(StudioBlack)) {
        Row(
            Modifier.fillMaxWidth().height(37.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("EQUALIZER", color = StudioText, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text(if (state.bandCount == 32) "PARAMETRIC · 32 FILTERS" else "${state.bandCount} BAND GRAPHIC", color = StudioMuted, fontSize = 7.sp, letterSpacing = 0.8.sp)
            }
            StudioModeButton("GRAPHIC", state.bandCount != 32, accent) { viewModel.setBandCount(10) }
            Spacer(Modifier.width(5.dp))
            StudioModeButton("PARAM", state.bandCount == 32, accent) { viewModel.setBandCount(32) }
            if (state.bandCount != 32) {
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.bandCount == 15) "15" else "10",
                    color = accent, fontSize = 8.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(accent.copy(alpha = 0.14f))
                        .clickable { viewModel.setBandCount(if (state.bandCount == 10) 15 else 10) }.padding(horizontal = 7.dp, vertical = 6.dp)
                )
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (state.bandCount == 32) StudioParametricBands(state, viewModel, accent)
            else StudioGraphicBands(state, viewModel, accent)
        }

        StudioSpectrumStrip(state, accent) { onVisualizerToggle(!state.visualizerEnabled) }
        StudioBassTrebleRow(state, viewModel)
    }
}

@Composable
private fun StudioModeButton(label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (active) accent.copy(alpha = 0.17f) else StudioSurface)
            .border(1.dp, if (active) accent.copy(alpha = 0.65f) else StudioLine, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (active) accent else StudioMuted, fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.4.sp)
    }
}

@Composable
private fun StudioGraphicBands(state: EqUiState, viewModel: EqViewModel, accent: Color) {
    val bands = remember(state.bands, state.bandCount) { studioGraphicBands(state.bands, state.bandCount) }
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxSize().padding(start = 7.dp, end = 7.dp, top = 3.dp, bottom = 5.dp)) {
        StudioGainFader(
            value = state.preampGainDb, label = "PRE", accent = accent, selected = false, compact = false,
            modifier = Modifier.width(39.dp).fillMaxHeight(), onValueChange = viewModel::setPreampGain
        )
        Box(Modifier.padding(horizontal = 3.dp, vertical = 10.dp).width(1.dp).fillMaxHeight().background(StudioLine))
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            Box(
                Modifier.width((bands.size * 31).dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF1B2027), Color(0xFF090B0F))))
                    .border(1.dp, Color(0xFF242B33), RoundedCornerShape(12.dp))
            ) {
                if (state.showCurveOnSliders) {
                    FrequencyResponseCanvas(
                        points = state.responsePoints, bands = state.bands, selectedBandIndex = state.selectedBandIndex,
                        onBandSelected = viewModel::selectBand, accent = accent, modifier = Modifier.fillMaxSize(),
                        interactive = false, showGrid = true
                    )
                }
                Row(Modifier.fillMaxSize().padding(horizontal = 1.dp)) {
                    bands.forEach { item ->
                        val band = state.bands.getOrNull(item.index) ?: return@forEach
                        StudioGainFader(
                            value = band.gainDb, label = item.label, accent = accent,
                            selected = item.index == state.selectedBandIndex, compact = true,
                            modifier = Modifier.width(31.dp).fillMaxHeight(),
                            onValueChange = { viewModel.selectBand(item.index); viewModel.setBandGain(item.index, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioParametricBands(state: EqUiState, viewModel: EqViewModel, accent: Color) {
    val density = LocalDensity.current
    val initial = remember(state.selectedBandIndex, density) { (state.selectedBandIndex * 178f * density.density).toInt() }
    val scroll = rememberScrollState(initial)
    Row(Modifier.fillMaxSize().padding(start = 7.dp, end = 7.dp, top = 3.dp, bottom = 5.dp)) {
        StudioGainFader(
            value = state.preampGainDb, label = "PRE", accent = accent, selected = false, compact = false,
            modifier = Modifier.width(39.dp).fillMaxHeight(), onValueChange = viewModel::setPreampGain
        )
        Box(Modifier.padding(horizontal = 3.dp, vertical = 10.dp).width(1.dp).fillMaxHeight().background(StudioLine))
        Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            state.bands.forEachIndexed { index, band ->
                StudioBandEditor(
                    index = index, band = band, selected = index == state.selectedBandIndex, accent = accent,
                    modifier = Modifier.width(174.dp).fillMaxHeight(),
                    onSelect = { viewModel.selectBand(index) },
                    onGain = { viewModel.selectBand(index); viewModel.setBandGain(index, it) },
                    onFrequency = { viewModel.selectBand(index); viewModel.setBandFrequency(index, it) },
                    onQ = { viewModel.selectBand(index); viewModel.setBandQ(index, it) },
                    onFilter = { viewModel.selectBand(index); viewModel.setBandFilterType(index, it) },
                    onEnabled = { viewModel.setBandEnabled(index, it) }
                )
            }
        }
    }
}

@Composable
private fun StudioBandEditor(
    index: Int, band: EqBandUiState, selected: Boolean, accent: Color, modifier: Modifier,
    onSelect: () -> Unit, onGain: (Float) -> Unit, onFrequency: (Float) -> Unit,
    onQ: (Float) -> Unit, onFilter: (Int) -> Unit, onEnabled: (Boolean) -> Unit
) {
    val tint = when (index % 4) {
        0 -> Color(0xFF274A78)
        1 -> Color(0xFF24583F)
        2 -> Color(0xFF71404B)
        else -> Color(0xFF51427C)
    }
    val edge = if (selected) accent else tint
    Column(
        modifier.clip(RoundedCornerShape(15.dp))
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = if (selected) 0.58f else 0.34f), Color(0xFF171A20), Color(0xFF0B0D11))))
            .border(if (selected) 1.4.dp else 0.7.dp, edge.copy(alpha = if (selected) 0.9f else 0.4f), RoundedCornerShape(15.dp))
            .clickable(onClick = onSelect).padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("BAND ${index + 1}", color = StudioText, fontSize = 8.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (band.enabled) accent else StudioMuted).clickable { onEnabled(!band.enabled) })
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioGainFader(
                value = band.gainDb, label = "GAIN", accent = if (selected) accent else StudioRed,
                selected = selected, compact = false, modifier = Modifier.width(42.dp).fillMaxHeight(), onValueChange = onGain
            )
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                Box(
                    Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0xFF080A0D))
                        .border(1.dp, edge.copy(alpha = 0.55f), RoundedCornerShape(7.dp))
                        .clickable {
                            onFilter(when (band.filterType) {
                                NativeDspBridge.FILTER_PEAKING -> NativeDspBridge.FILTER_LOW_SHELF
                                NativeDspBridge.FILTER_LOW_SHELF -> NativeDspBridge.FILTER_HIGH_SHELF
                                else -> NativeDspBridge.FILTER_PEAKING
                            })
                        }.padding(horizontal = 7.dp, vertical = 5.dp)
                ) {
                    Text(studioFilterName(band.filterType), color = edge, fontSize = 7.sp, fontWeight = FontWeight.Black)
                }

                RotaryKnob(
                    label = "Q FACTOR", value = studioQProgress(band.qFactor), valueRange = 0f..1f,
                    defaultValue = studioQProgress(1f), valueText = String.format(Locale.US, "%.2f", band.qFactor),
                    onValueChange = { onQ(studioProgressQ(it)) }, accent = StudioRed, knobSize = 65.dp, modifier = Modifier.fillMaxWidth()
                )

                RotaryKnob(
                    label = "FREQUENCY", value = studioFrequencyProgress(band.frequencyHz), valueRange = 0f..1f,
                    defaultValue = studioFrequencyProgress(1_000f), valueText = studioFormatHz(band.frequencyHz),
                    onValueChange = { onFrequency(studioProgressFrequency(it)) }, accent = StudioBlue, knobSize = 65.dp, modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF080A0D))
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(String.format(Locale.US, "%+.1f dB", band.gainDb), color = StudioText, fontSize = 7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(studioFormatHz(band.frequencyHz), color = StudioMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StudioGainFader(
    value: Float,
    label: String,
    accent: Color,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onValueChange: (Float) -> Unit
) {
    val callback = rememberUpdatedState(onValueChange)
    val local = remember { mutableFloatStateOf(value) }
    val dragging = remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging.value) local.floatValue = value }
    val min = -12f
    val max = 12f
    val range = max - min

    Column(modifier.padding(horizontal = if (compact) 0.dp else 1.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(String.format(Locale.US, "%+.1f", local.floatValue), color = if (selected) accent else StudioText, fontSize = if (compact) 6.sp else 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) {
                    fun valueAt(y: Float): Float = (min + (1f - y / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f) * range).coerceIn(min, max)
                    detectDragGestures(
                        onDragStart = { p -> dragging.value = true; local.floatValue = valueAt(p.y); callback.value(local.floatValue) },
                        onDrag = { change, _ -> change.consume(); local.floatValue = valueAt(change.position.y); callback.value(local.floatValue) },
                        onDragEnd = { callback.value(local.floatValue); dragging.value = false },
                        onDragCancel = { callback.value(local.floatValue); dragging.value = false }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p -> local.floatValue = (min + (1f - p.y / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f) * range); callback.value(local.floatValue) },
                        onDoubleTap = { local.floatValue = 0f; callback.value(0f) }
                    )
                }
        ) {
            val top = 7.dp.toPx()
            val bottom = size.height - 7.dp.toPx()
            val usable = (bottom - top).coerceAtLeast(1f)
            val center = size.width * 0.5f
            val valueFraction = ((local.floatValue - min) / range).coerceIn(0f, 1f)
            val zeroFraction = 0.5f
            val yValue = bottom - valueFraction * usable
            val yZero = bottom - zeroFraction * usable
            val track = if (compact) 2.dp.toPx() else 3.dp.toPx()
            val capWidth = (size.width * 0.78f).coerceAtLeast(if (compact) 17.dp.toPx() else 24.dp.toPx())
            val capHeight = if (compact) 9.dp.toPx() else 15.dp.toPx()

            drawLine(Color(0xFF353C45), Offset(center, top), Offset(center, bottom), track, StrokeCap.Round)
            drawLine(Color(0xFF7C8793).copy(alpha = 0.48f), Offset(center - 7.dp.toPx(), yZero), Offset(center + 7.dp.toPx(), yZero), 0.8.dp.toPx())
            drawLine(accent.copy(alpha = 0.19f), Offset(center, yZero), Offset(center, yValue), if (selected) 8.dp.toPx() else 5.dp.toPx(), StrokeCap.Round)
            drawLine(accent.copy(alpha = if (selected) 0.95f else 0.65f), Offset(center, yZero), Offset(center, yValue), track, StrokeCap.Round)

            for (tick in 0..12) {
                val y = top + usable * tick / 12f
                val tickLength = if (tick % 3 == 0) 5.dp.toPx() else 2.5.dp.toPx()
                drawLine(Color(0xFF89939E).copy(alpha = if (tick % 3 == 0) 0.58f else 0.25f), Offset(center + 4.dp.toPx(), y), Offset(center + 4.dp.toPx() + tickLength, y), 0.7.dp.toPx())
            }

            val capTop = yValue - capHeight / 2f
            drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset(center - capWidth / 2f, capTop + 1.5.dp.toPx()), Size(capWidth, capHeight + 1.dp.toPx()), CornerRadius(capHeight / 2f))
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF0F3F5), Color(0xFF9DA7B1), Color(0xFF414A54))), Offset(center - capWidth / 2f, capTop), Size(capWidth, capHeight), CornerRadius(capHeight / 2f))
            drawRoundRect(Color(0xFF252C34), Offset(center - capWidth / 2f, capTop), Size(capWidth, capHeight), CornerRadius(capHeight / 2f), style = Stroke(0.8.dp.toPx()))
            drawLine(Color.White.copy(alpha = 0.82f), Offset(center - capWidth * 0.22f, yValue), Offset(center + capWidth * 0.22f, yValue), if (compact) 1.dp.toPx() else 1.7.dp.toPx(), StrokeCap.Round)
        }
        Text(label, color = if (selected) accent else StudioMuted, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

@Composable
private fun StudioSpectrumStrip(state: EqUiState, accent: Color, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(59.dp).padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(11.dp)).background(Color(0xFF171A1F))
            .border(1.dp, Color(0xFF303740), RoundedCornerShape(11.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.width(79.dp).fillMaxHeight().clickable(onClick = onToggle).padding(start = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("SPECTRUM", color = StudioText, fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(if (state.visualizerEnabled) Color(0xFF53DC96) else StudioMuted, CircleShape))
                Text(if (state.visualizerEnabled) "LIVE" else "OFF", color = if (state.visualizerEnabled) Color(0xFF53DC96) else StudioMuted, fontSize = 6.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(end = 5.dp).clip(RoundedCornerShape(8.dp))) {
            FrequencyResponseCanvas(
                points = state.responsePoints, bands = state.bands, selectedBandIndex = state.selectedBandIndex,
                onBandSelected = {}, spectrumBars = if (state.visualizerEnabled) state.spectrumBars else emptyList(),
                accent = accent, modifier = Modifier.fillMaxSize(), interactive = false,
                spectrumStyle = state.visualizerStyle, showGrid = false
            )
        }
    }
}

@Composable
private fun StudioBassTrebleRow(state: EqUiState, viewModel: EqViewModel) {
    Row(
        Modifier.fillMaxWidth().height(141.dp).padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RotaryKnob(
            label = "BASS", value = ((state.bassGainDb + 12f) / 24f).coerceIn(0f, 1f), valueRange = 0f..1f,
            defaultValue = 0.5f, valueText = String.format(Locale.US, "%+.1f dB", state.bassGainDb),
            onValueChange = { viewModel.setBassGain(it * 24f - 12f) }, accent = StudioRed, knobSize = 109.dp, modifier = Modifier.weight(1f)
        )
        RotaryKnob(
            label = "TREBLE", value = ((state.trebleGainDb + 12f) / 24f).coerceIn(0f, 1f), valueRange = 0f..1f,
            defaultValue = 0.5f, valueText = String.format(Locale.US, "%+.1f dB", state.trebleGainDb),
            onValueChange = { viewModel.setTrebleGain(it * 24f - 12f) }, accent = StudioBlue, knobSize = 109.dp, modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun StudioTonePage(state: EqUiState, viewModel: EqViewModel, accent: Color) {
    Column(Modifier.fillMaxSize().background(StudioBlack).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth().height(27.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TONE & STEREO", color = StudioText, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
            Spacer(Modifier.weight(1f))
            Text("DOUBLE TAP A DIAL TO RESET", color = StudioMuted, fontSize = 6.sp)
        }

        Row(Modifier.fillMaxWidth().weight(0.43f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RotaryKnob(
                label = "BASS", value = ((state.bassGainDb + 12f) / 24f).coerceIn(0f, 1f), valueRange = 0f..1f, defaultValue = 0.5f,
                valueText = String.format(Locale.US, "%+.1f dB", state.bassGainDb), onValueChange = { viewModel.setBassGain(it * 24f - 12f) },
                accent = StudioRed, knobSize = 128.dp, modifier = Modifier.weight(1f)
            )
            RotaryKnob(
                label = "TREBLE", value = ((state.trebleGainDb + 12f) / 24f).coerceIn(0f, 1f), valueRange = 0f..1f, defaultValue = 0.5f,
                valueText = String.format(Locale.US, "%+.1f dB", state.trebleGainDb), onValueChange = { viewModel.setTrebleGain(it * 24f - 12f) },
                accent = StudioBlue, knobSize = 128.dp, modifier = Modifier.weight(1f)
            )
        }

        Row(Modifier.fillMaxWidth().weight(0.28f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            StudioLogDial("BASS FREQ", state.bassFrequencyHz, 20f, 250f, StudioRed, viewModel::setBassFrequency)
            StudioLinearDial("BASS Q", state.bassQFactor, 0.5f..2f, 0.8f, StudioRed, viewModel::setBassQ)
            StudioLogDial("TREBLE FREQ", state.trebleFrequencyHz, 4_000f, 16_000f, StudioBlue, viewModel::setTrebleFrequency)
            StudioLinearDial("TREBLE Q", state.trebleQFactor, 0.5f..2f, 0.8f, StudioBlue, viewModel::setTrebleQ)
        }

        Row(Modifier.fillMaxWidth().weight(0.25f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            RotaryKnob(
                label = "STEREO WIDTH", value = state.stereoWidthPercent, valueRange = 0f..200f, defaultValue = 100f,
                valueText = "${state.stereoWidthPercent.toInt()}%", onValueChange = viewModel::setStereoWidthPercent,
                accent = accent, knobSize = 80.dp, modifier = Modifier.weight(1f)
            )
            RotaryKnob(
                label = "BALANCE", value = state.stereoBalance, valueRange = -1f..1f, defaultValue = 0f,
                valueText = studioBalance(state.stereoBalance), onValueChange = viewModel::setStereoBalance,
                accent = accent, knobSize = 80.dp, modifier = Modifier.weight(1f)
            )
        }

        Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioToggle("LIMITER", state.limiterEnabled, accent, Modifier.weight(1f), viewModel::setLimiterEnabled)
            StudioToggle("DVC", state.dvcEnabled, accent, Modifier.weight(1f), viewModel::setDvcEnabled)
            StudioToggle("AVOID CLIP", state.avoidOverloading, accent, Modifier.weight(1f), viewModel::setAvoidOverloading)
        }
    }
}

@Composable
private fun StudioLogDial(label: String, frequency: Float, minHz: Float, maxHz: Float, accent: Color, onChange: (Float) -> Unit) {
    val progress = (ln(frequency.coerceIn(minHz, maxHz) / minHz) / ln(maxHz / minHz)).coerceIn(0f, 1f)
    RotaryKnob(
        label = label, value = progress, valueRange = 0f..1f, defaultValue = 0.5f, valueText = studioFormatHz(frequency),
        onValueChange = { onChange(minHz * (maxHz / minHz).pow(it.coerceIn(0f, 1f))) },
        accent = accent, knobSize = 64.dp, modifier = Modifier.width(88.dp)
    )
}

@Composable
private fun StudioLinearDial(label: String, value: Float, range: ClosedFloatingPointRange<Float>, default: Float, accent: Color, onChange: (Float) -> Unit) {
    RotaryKnob(
        label = label, value = value, valueRange = range, defaultValue = default,
        valueText = String.format(Locale.US, "%.2f", value), onValueChange = onChange,
        accent = accent, knobSize = 64.dp, modifier = Modifier.width(88.dp)
    )
}

@Composable
private fun StudioToggle(label: String, checked: Boolean, accent: Color, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.height(34.dp).clip(RoundedCornerShape(18.dp))
            .background(if (checked) accent.copy(alpha = 0.13f) else StudioSurface)
            .border(1.dp, if (checked) accent.copy(alpha = 0.50f) else StudioLine, RoundedCornerShape(18.dp))
            .clickable { onChange(!checked) }.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (checked) accent else StudioMuted, fontSize = 7.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(22.dp, 12.dp).clip(CircleShape).background(if (checked) accent else Color(0xFF414952)).padding(2.dp), contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size(8.dp).background(Color.White, CircleShape))
        }
    }
}

private data class StudioGraphicBand(val index: Int, val label: String)

private fun studioGraphicBands(bands: List<EqBandUiState>, count: Int): List<StudioGraphicBand> {
    if (bands.isEmpty()) return emptyList()
    val targets = if (count == 15) {
        listOf(25f to "25", 40f to "40", 63f to "63", 100f to "100", 160f to "160", 250f to "250", 400f to "400", 630f to "630", 1_000f to "1k", 1_600f to "1.6k", 2_500f to "2.5k", 4_000f to "4k", 6_300f to "6.3k", 10_000f to "10k", 16_000f to "16k")
    } else {
        listOf(31f to "31", 62f to "62", 125f to "125", 250f to "250", 500f to "500", 1_000f to "1k", 2_000f to "2k", 4_000f to "4k", 8_000f to "8k", 16_000f to "16k")
    }
    return targets.map { (hz, label) ->
        val index = bands.indices.minByOrNull { abs(ln(bands[it].frequencyHz.coerceAtLeast(1f) / hz)) } ?: 0
        StudioGraphicBand(index, label)
    }.distinctBy(StudioGraphicBand::index)
}

private fun studioFrequencyProgress(hz: Float): Float = (ln(hz.coerceIn(20f, 20_000f) / 20f) / ln(1_000f)).coerceIn(0f, 1f)
private fun studioProgressFrequency(p: Float): Float = (20f * 1_000f.pow(p.coerceIn(0f, 1f))).coerceIn(20f, 20_000f)
private fun studioQProgress(q: Float): Float = (ln(q.coerceIn(0.35f, 12f) / 0.35f) / ln(12f / 0.35f)).coerceIn(0f, 1f)
private fun studioProgressQ(p: Float): Float = (0.35f * (12f / 0.35f).pow(p.coerceIn(0f, 1f))).coerceIn(0.35f, 12f)

private fun studioFormatHz(value: Float): String {
    if (value < 1_000f) return String.format(Locale.US, "%.0f Hz", value)
    val khz = value / 1_000f
    return if (abs(khz - khz.toInt()) < 0.05f) String.format(Locale.US, "%.0f kHz", khz) else String.format(Locale.US, "%.1f kHz", khz)
}

private fun studioFilterName(type: Int): String = when (type) {
    NativeDspBridge.FILTER_LOW_SHELF -> "LOW SHELF"
    NativeDspBridge.FILTER_HIGH_SHELF -> "HIGH SHELF"
    else -> "PEAK"
}

private fun studioPlayerName(name: String): String = when (name) {
    "No player detected" -> "NO PLAYER"
    "YouTube Music" -> "YOUTUBE MUSIC"
    else -> name.uppercase(Locale.US).take(14)
}

private fun studioBalance(value: Float): String = when {
    abs(value) < 0.04f -> "CENTER"
    value < 0f -> "L ${(abs(value) * 100f).toInt()}"
    else -> "R ${(value * 100f).toInt()}"
}
