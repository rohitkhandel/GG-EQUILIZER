package com.rohitkhandel.ggequalizer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import android.os.SystemClock
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

private val StudioBlack = Color(0xFF000000)
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
    val destinations = listOf("EQUALIZER", "TONE", "PRESETS", "SETTINGS")
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 9.dp, vertical = 4.dp)
            .height(54.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF17191D))
            .border(1.dp, Color(0xFF282D34), RoundedCornerShape(28.dp)),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        destinations.forEach { key ->
            val active = selected == key
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(22.dp)).background(if (active) Color(0xFF30353D) else Color.Transparent)
                    .clickable { onSelect(key) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                StudioDockGlyph(key, active, accent)
                Text(
                    when (key) {
                        "EQUALIZER" -> "EQ"
                        "TONE" -> "TONE"
                        "PRESETS" -> "PRESETS"
                        else -> "SETTINGS"
                    },
                    color = if (active) accent else Color(0xFF8B929A),
                    fontSize = 6.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.15.sp,
                    maxLines = 1
                )
                Box(Modifier.padding(top = 1.dp).width(if (active) 12.dp else 3.dp).height(2.dp).clip(CircleShape).background(if (active) accent else Color.Transparent))
            }
        }
    }
}

@Composable
private fun StudioDockGlyph(destination: String, active: Boolean, accent: Color) {
    val tint = if (active) accent else Color(0xFF8B929A)
    Canvas(Modifier.size(23.dp)) {
        val w = size.width
        val h = size.height
        when (destination) {
            "EQUALIZER" -> {
                val heights = listOf(0.38f, 0.78f, 0.56f, 0.92f)
                heights.forEachIndexed { index, fraction ->
                    val barWidth = w * 0.14f
                    val x = w * (0.10f + index * 0.23f)
                    val barHeight = h * fraction
                    drawRoundRect(
                        tint,
                        Offset(x, h - barHeight),
                        Size(barWidth, barHeight),
                        CornerRadius(barWidth / 2f)
                    )
                }
            }
            "TONE" -> {
                drawCircle(tint.copy(alpha = 0.20f), w * 0.43f, center)
                drawCircle(tint, w * 0.38f, center, style = Stroke(width = 2.dp.toPx()))
                val needleEnd = Offset(center.x + w * 0.20f, center.y - h * 0.21f)
                drawLine(tint, center, needleEnd, 2.4.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(tint, w * 0.055f, center)
            }
            "PRESETS" -> {
                drawRoundRect(tint, Offset(w * 0.12f, h * 0.10f), Size(w * 0.76f, h * 0.80f), CornerRadius(3.dp.toPx()), style = Stroke(width = 1.8.dp.toPx()))
                for (index in 0..2) {
                    val y = h * (0.30f + index * 0.22f)
                    drawCircle(tint, w * 0.045f, Offset(w * 0.28f, y))
                    drawLine(tint, Offset(w * 0.40f, y), Offset(w * 0.77f, y), 1.8.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            else -> {
                for (index in 0..2) {
                    val y = h * (0.25f + index * 0.25f)
                    drawLine(tint, Offset(w * 0.14f, y), Offset(w * 0.86f, y), 3.2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
fun StudioEqualizerWorkspace(
    state: EqUiState,
    viewModel: EqViewModel,
    accent: Color,
    onVisualizerToggle: (Boolean) -> Unit,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit,
    onOpenPresets: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(StudioBlack)) {
        StudioConsoleModeBar(state, viewModel, accent)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            StudioBandWorkspace(state, viewModel, accent)
        }
        StudioSpectrumStrip(state, accent) { onVisualizerToggle(!state.visualizerEnabled) }
        StudioPlayerBadge(state)
        StudioEqActionRack(state, accent, viewModel, onSave, onImport, onReset, onOpenPresets)
        StudioBassTrebleRow(state, viewModel)
    }
}

@Composable
private fun StudioConsoleModeBar(state: EqUiState, viewModel: EqViewModel, accent: Color) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("GG EQUALIZER", color = StudioText, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.15.sp)
            Text(
                if (state.bandCount == 32) "PARAMETRIC  ·  ${state.parametricBandIndices.size}/32 FILTERS"
                else "GRAPHIC  ·  ${state.bandCount} BANDS",
                color = StudioMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioModeChip("10", state.bandCount == 10, accent) { viewModel.setBandCount(10) }
            StudioModeChip("15", state.bandCount == 15, accent) { viewModel.setBandCount(15) }
            StudioModeChip("PARA", state.bandCount == 32, accent) { viewModel.setBandCount(32) }
        }
    }
}

@Composable
private fun StudioModeChip(label: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.width(if (label == "PARA") 53.dp else 38.dp).height(31.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else StudioSurface)
            .border(1.dp, if (selected) accent.copy(alpha = 0.78f) else StudioLine, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) accent else StudioMuted, fontSize = 9.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun StudioBandWorkspace(state: EqUiState, viewModel: EqViewModel, accent: Color) {
    val parametricMode = state.bandCount == 32
    val visibleBands = remember(state.bands, state.bandCount, state.parametricBandIndices) {
        if (parametricMode) {
            state.parametricBandIndices
                .filter { it in state.bands.indices }
                .sortedBy { state.bands[it].frequencyHz }
                .map { index -> StudioGraphicBand(index, studioFormatHz(state.bands[index].frequencyHz)) }
        } else {
            studioGraphicBands(state.bands, state.bandCount)
        }
    }
    val scroll = rememberLazyListState()
    Row(
        Modifier.fillMaxSize().padding(start = 7.dp, end = 7.dp, top = 4.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier.width(43.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF11151A))
                .border(1.dp, StudioLine, RoundedCornerShape(12.dp))
                .padding(horizontal = 3.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("PRE", color = StudioMuted, fontSize = 8.sp, fontWeight = FontWeight.Black)
            StudioGainFader(
                value = state.preampGainDb, valueRange = -24f..12f, valueMode = state.equalizerValueMode,
                label = "PRE", accent = StudioBlue, selected = false,
                compact = true, modifier = Modifier.weight(1f).fillMaxWidth(), fillFromTop = true,
                onValueChange = viewModel::setPreampGain
            )
        }
        Spacer(Modifier.width(5.dp))
        LazyRow(
            state = scroll,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(if (parametricMode) 7.dp else 1.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(visibleBands, key = { _, item -> item.index }) { _, item ->
                val band = state.bands.getOrNull(item.index)
                if (band != null) {
                    if (parametricMode) {
                        StudioBandPanel(
                            index = item.index,
                            band = band,
                            selected = item.index == state.selectedBandIndex,
                            valueMode = state.equalizerValueMode,
                            modifier = Modifier.width(168.dp).fillMaxHeight(),
                            onSelect = { viewModel.selectBand(item.index) },
                            onGain = { viewModel.setBandGain(item.index, it) },
                            onFrequency = { viewModel.setBandFrequency(item.index, it) },
                            onQ = { viewModel.setBandQ(item.index, it) },
                            onFilter = { viewModel.setBandFilterType(item.index, it) },
                            onEnabled = { viewModel.setBandEnabled(item.index, it) },
                            onDelete = { viewModel.removeParametricBand(item.index) }
                        )
                    } else {
                        StudioGraphicFaderPanel(
                            index = item.index,
                            label = item.label,
                            band = band,
                            selected = item.index == state.selectedBandIndex,
                            valueMode = state.equalizerValueMode,
                            width = if (state.bandCount == 15) 23.dp else 30.dp,
                            accent = accent,
                            onSelect = { viewModel.selectBand(item.index) },
                            onGain = { viewModel.setBandGain(item.index, it) },
                            onEnabled = { viewModel.setBandEnabled(item.index, it) }
                        )
                    }
                }
            }
            if (parametricMode) {
                item(key = "add-parametric-band") {
                    StudioParametricAddPanel(
                        activeCount = state.parametricBandIndices.size,
                        hasSelectedBand = state.selectedBandIndex in state.parametricBandIndices,
                        accent = accent,
                        onAdd = viewModel::addParametricBand,
                        onRemoveSelected = viewModel::removeSelectedParametricBand
                    )
                }
            }
        }
    }
}

@Composable
private fun StudioGraphicFaderPanel(
    index: Int,
    label: String,
    band: EqBandUiState,
    selected: Boolean,
    valueMode: String,
    width: androidx.compose.ui.unit.Dp,
    accent: Color,
    onSelect: () -> Unit,
    onGain: (Float) -> Unit,
    onEnabled: (Boolean) -> Unit
) {
    Column(
        Modifier.width(width).fillMaxHeight().clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color(0xFF1D2730) else Color(0xFF101419))
            .border(1.dp, if (selected) accent else Color(0xFF28313A), RoundedCornerShape(9.dp))
            .padding(horizontal = 2.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = if (selected) accent else StudioText, fontSize = 7.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(String.format(Locale.US, "%02d", index + 1), color = StudioMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        StudioGainFader(
            value = band.gainDb,
            valueRange = -15f..15f,
            valueMode = valueMode,
            label = "GAIN",
            accent = accent,
            selected = selected,
            compact = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            onTouchStart = onSelect,
            onValueChange = onGain
        )
        Box(
            Modifier.size(12.dp).clip(CircleShape)
                .background(if (band.enabled) accent else Color(0xFF4A535D))
                .clickable { onEnabled(!band.enabled) },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(4.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun StudioParametricAddPanel(
    activeCount: Int,
    hasSelectedBand: Boolean,
    accent: Color,
    onAdd: () -> Unit,
    onRemoveSelected: () -> Unit
) {
    Column(
        Modifier.width(139.dp).fillMaxHeight().clip(RoundedCornerShape(15.dp))
            .background(Color(0xFF11161C))
            .border(1.dp, StudioLine, RoundedCornerShape(15.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("FILTER BANK", color = StudioText, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
        Text("$activeCount / 32 ADDED", color = StudioMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(15.dp))
        StudioActionPill("+ ADD BAND", accent, Modifier.fillMaxWidth(), onAdd)
        Spacer(Modifier.height(8.dp))
        StudioActionPill(
            "REMOVE SELECTED",
            if (hasSelectedBand) Color(0xFFFF7B83) else StudioMuted,
            Modifier.fillMaxWidth(),
            onRemoveSelected
        )
        Spacer(Modifier.height(14.dp))
        Text("Add filters one at a time. Select a card to edit its gain, frequency and Q.", color = StudioMuted, fontSize = 8.sp, lineHeight = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("MAX 32 FILTERS", color = accent, fontSize = 8.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun StudioBandPanel(
    index: Int,
    band: EqBandUiState,
    selected: Boolean,
    valueMode: String,
    modifier: Modifier,
    onSelect: () -> Unit,
    onGain: (Float) -> Unit,
    onFrequency: (Float) -> Unit,
    onQ: (Float) -> Unit,
    onFilter: (Int) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val edge = if (selected) Color(0xFFFFD36D) else StudioLine
    val levelLabel = if (
        band.filterType == NativeDspBridge.FILTER_LOW_PASS ||
        band.filterType == NativeDspBridge.FILTER_HIGH_PASS ||
        band.filterType == NativeDspBridge.FILTER_BAND_PASS
    ) "TRIM" else "GAIN"
    Column(
        modifier = modifier.clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF1A2027) else Color(0xFF101419))
            .border(if (selected) 1.5.dp else 1.dp, edge, RoundedCornerShape(16.dp))
            .padding(horizontal = 7.dp, vertical = 7.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).clickable(onClick = onSelect),
                verticalArrangement = Arrangement.Center
            ) {
                Text("FILTER ${index + 1}", color = if (selected) Color(0xFFFFD36D) else StudioText, fontSize = 10.sp, fontWeight = FontWeight.Black)
                Text(studioFormatHz(band.frequencyHz), color = StudioMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
            StudioBlackPill(studioFilterShort(band.filterType), Modifier) {
                onSelect()
                onFilter(when (band.filterType) {
                    NativeDspBridge.FILTER_PEAKING -> NativeDspBridge.FILTER_LOW_SHELF
                    NativeDspBridge.FILTER_LOW_SHELF -> NativeDspBridge.FILTER_HIGH_SHELF
                    NativeDspBridge.FILTER_HIGH_SHELF -> NativeDspBridge.FILTER_LOW_PASS
                    NativeDspBridge.FILTER_LOW_PASS -> NativeDspBridge.FILTER_HIGH_PASS
                    NativeDspBridge.FILTER_HIGH_PASS -> NativeDspBridge.FILTER_BAND_PASS
                    else -> NativeDspBridge.FILTER_PEAKING
                })
            }
            Spacer(Modifier.width(4.dp))
            StudioBlackPill("×", Modifier, onDelete)
        }
        Row(
            Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            StudioGainFader(
                value = band.gainDb,
                valueRange = -15f..15f,
                valueMode = valueMode,
                label = levelLabel,
                accent = Color(0xFFFF6675),
                selected = selected,
                compact = false,
                modifier = Modifier.width(48.dp).fillMaxHeight(),
                onTouchStart = onSelect,
                onValueChange = onGain
            )
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RotaryKnob(
                        label = "Q",
                        value = studioQProgress(band.qFactor),
                        valueRange = 0f..1f,
                        defaultValue = studioQProgress(1f),
                        valueText = "",
                        onValueChange = { onQ(studioProgressQ(it)) },
                        onInteractionStart = onSelect,
                        accent = Color(0xFFFF6675),
                        knobSize = 70.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                    StudioValueBadge("Q", String.format(Locale.US, "%.2f", band.qFactor))
                }
                RotaryKnob(
                    label = "FREQ",
                    value = studioFrequencyProgress(band.frequencyHz),
                    valueRange = 0f..1f,
                    defaultValue = studioFrequencyProgress(1_000f),
                    valueText = "",
                    onValueChange = { onFrequency(studioProgressFrequency(it)) },
                    onInteractionStart = onSelect,
                    accent = Color(0xFF63BFFF),
                    knobSize = 70.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().height(34.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StudioValueBadge(levelLabel, String.format(Locale.US, "%+.1f dB", band.gainDb))
            Box(
                Modifier.size(24.dp).clip(CircleShape)
                    .background(if (band.enabled) Color(0xFF133E31) else Color(0xFF333A43))
                    .border(1.dp, if (band.enabled) Color(0xFF48D69A) else StudioLine, CircleShape)
                    .clickable { onEnabled(!band.enabled) },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (band.enabled) Color(0xFF48D69A) else StudioMuted))
            }
        }
    }
}

@Composable
private fun StudioBandSelectorPanel(state: EqUiState, accent: Color, onSelect: (Int) -> Unit, onShowAll: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.width(132.dp).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Color(0xFF0C0F13))
            .border(1.dp, Color(0xFF29313A), RoundedCornerShape(18.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("FILTERS", color = StudioText, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        StudioActionPill("+ ALL BANDS", accent, Modifier.fillMaxWidth(), onShowAll)
        Spacer(Modifier.height(8.dp))
        Box {
            StudioActionPill("SELECT", accent, Modifier.fillMaxWidth()) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                state.bands.forEachIndexed { index, band ->
                    DropdownMenuItem(
                        text = { Text("Band ${index + 1} · ${studioFormatHz(band.frequencyHz)}") },
                        onClick = { menu = false; onSelect(index) }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("${state.bands.count { it.enabled }} ACTIVE", color = StudioMuted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StudioBlackPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF080A0D))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black, maxLines = 1) }
}

@Composable
private fun StudioValueBadge(label: String, value: String) {
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF080A0D)).padding(horizontal = 7.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black)
        Text(value, color = Color(0xFFC4CBD3), fontSize = 7.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun StudioEqActionRack(
    state: EqUiState, accent: Color, viewModel: EqViewModel,
    onSave: () -> Unit, onImport: () -> Unit, onReset: () -> Unit, onOpenPresets: () -> Unit
) {
    var presetMenu by rememberSaveable { mutableStateOf(false) }
    var moreMenu by rememberSaveable { mutableStateOf(false) }
    val selectedPreset = state.presets.firstOrNull { it.presetId == state.selectedPresetId }?.presetName ?: "Flat"
    Row(Modifier.fillMaxWidth().height(71.dp).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(62.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StudioActionPill(if (state.masterEnabled) "EQU" else "OFF", if (state.masterEnabled) accent else StudioMuted, Modifier.fillMaxWidth()) {
                viewModel.setMasterEnabled(!state.masterEnabled)
            }
            StudioActionPill(if (state.limiterEnabled) "LIMIT ✓" else "LIMIT", if (state.limiterEnabled) accent else StudioMuted, Modifier.fillMaxWidth()) {
                viewModel.setLimiterEnabled(!state.limiterEnabled)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color(0xFF101318))
                    .border(1.dp, Color(0xFF5A6068), RoundedCornerShape(26.dp)).clickable { presetMenu = true }
                    .padding(vertical = 10.dp, horizontal = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(selectedPreset.uppercase(Locale.US), color = StudioText, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("PRESET  ▾", color = StudioMuted, fontSize = 6.sp, letterSpacing = 1.sp)
            }
            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                state.presets.forEach { preset ->
                    DropdownMenuItem(text = { Text(preset.presetName) }, onClick = { presetMenu = false; viewModel.applyPreset(preset) })
                }
                DropdownMenuItem(text = { Text("Browse preset library…") }, onClick = { presetMenu = false; onOpenPresets() })
            }
        }
        Box {
            Text("⋮", color = StudioText, fontSize = 23.sp, modifier = Modifier.clickable { moreMenu = true }.padding(horizontal = 10.dp))
            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                DropdownMenuItem(text = { Text("10-band graphic") }, onClick = { moreMenu = false; viewModel.setBandCount(10) })
                DropdownMenuItem(text = { Text("15-band graphic") }, onClick = { moreMenu = false; viewModel.setBandCount(15) })
                DropdownMenuItem(text = { Text("32-band parametric") }, onClick = { moreMenu = false; viewModel.setBandCount(32) })
                DropdownMenuItem(text = { Text("Save preset") }, onClick = { moreMenu = false; onSave() })
                DropdownMenuItem(text = { Text("Import AutoEQ") }, onClick = { moreMenu = false; onImport() })
                DropdownMenuItem(text = { Text("Reset equalizer") }, onClick = { moreMenu = false; onReset() })
            }
        }
    }
}

@Composable
private fun StudioActionPill(label: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(29.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF191D22))
            .border(1.dp, Color(0xFF333A43), RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = tint, fontSize = 8.sp, fontWeight = FontWeight.Black, maxLines = 1) }
}

@Composable
private fun StudioPlayerBadge(state: EqUiState) {
    Row(Modifier.fillMaxWidth().height(25.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF111419)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(5.dp).background(if (state.activePlayerName == "No player detected") StudioMuted else Color(0xFF51D890), CircleShape))
            Text(studioPlayerName(state.activePlayerName), color = if (state.activePlayerName == "No player detected") StudioMuted else StudioText, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, modifier = Modifier.padding(start = 5.dp))
        }
    }
}

@Composable
private fun StudioGainFader(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = -12f..12f,
    valueMode: String = "DB",
    label: String,
    accent: Color,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier,
    onTouchStart: () -> Unit = {},
    fillFromTop: Boolean = false,
    onValueChange: (Float) -> Unit
) {
    val callback = rememberUpdatedState(onValueChange)
    val local = remember { mutableFloatStateOf(value) }
    val dragging = remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!dragging.value) local.floatValue = value }
    val min = valueRange.start
    val max = valueRange.endInclusive
    val range = (max - min).coerceAtLeast(0.001f)
    Column(modifier.padding(horizontal = if (compact) 0.dp else 1.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (valueMode != "HIDDEN") {
            Text(
                studioGainValueText(local.floatValue, valueMode),
                color = if (selected) accent else StudioText,
                fontSize = if (compact) 6.sp else 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        } else {
            Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
        }
        Canvas(
            Modifier.weight(1f).fillMaxWidth()
                .pointerInput(Unit) {
                    var lastDispatch = 0L
                    fun valueAt(y: Float): Float = (min + (1f - y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) * range).coerceIn(min, max)
                    fun dispatch(force: Boolean = false) {
                        val now = SystemClock.uptimeMillis()
                        if (force || lastDispatch == 0L || now - lastDispatch >= 16L) {
                            callback.value(local.floatValue)
                            lastDispatch = now
                        }
                    }
                    detectDragGestures(
                        onDragStart = { p -> dragging.value = true; onTouchStart(); lastDispatch = 0L; local.floatValue = valueAt(p.y); dispatch() },
                        onDrag = { change, _ -> change.consume(); local.floatValue = valueAt(change.position.y); dispatch() },
                        onDragEnd = { dispatch(force = true); dragging.value = false },
                        onDragCancel = { dispatch(force = true); dragging.value = false }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { p -> onTouchStart(); local.floatValue = min + (1f - p.y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) * range; callback.value(local.floatValue) },
                        onDoubleTap = { onTouchStart(); local.floatValue = 0f; callback.value(0f) }
                    )
                }
        ) {
            val top = 7.dp.toPx()
            val bottom = size.height - 7.dp.toPx()
            val usable = (bottom - top).coerceAtLeast(1f)
            val center = size.width * 0.5f
            val valueFraction = ((local.floatValue - min) / range).coerceIn(0f, 1f)
            val yValue = bottom - valueFraction * usable
            val zeroFraction = ((0f - min) / range).coerceIn(0f, 1f)
            val yZero = bottom - zeroFraction * usable
            val track = if (compact) 2.dp.toPx() else 3.dp.toPx()
            val capWidth = if (compact) 17.dp.toPx() else 25.dp.toPx()
            val capHeight = if (compact) 9.dp.toPx() else 46.dp.toPx()
            drawLine(Color(0xFF353C45), Offset(center, top), Offset(center, bottom), track, StrokeCap.Round)
            drawLine(Color(0xFF7C8793).copy(alpha = 0.48f), Offset(center - 7.dp.toPx(), yZero), Offset(center + 7.dp.toPx(), yZero), 0.8.dp.toPx())
            val fillStart = if (fillFromTop) top else yValue
            val fillEnd = if (fillFromTop) yValue else bottom
            drawLine(accent.copy(alpha = 0.24f), Offset(center, fillStart), Offset(center, fillEnd), 8.dp.toPx(), StrokeCap.Round)
            drawLine(accent, Offset(center, fillStart), Offset(center, fillEnd), track, StrokeCap.Round)
            for (tick in 0..12) {
                val y = top + usable * tick / 12f
                val length = if (tick % 3 == 0) 5.dp.toPx() else 2.5.dp.toPx()
                drawLine(Color(0xFF89939E).copy(alpha = if (tick % 3 == 0) 0.58f else 0.25f), Offset(center + 4.dp.toPx(), y), Offset(center + 4.dp.toPx() + length, y), 0.7.dp.toPx())
            }
            val capTop = yValue - capHeight / 2f
            drawRoundRect(Color.Black.copy(alpha = 0.52f), Offset(center - capWidth / 2f, capTop + 1.5.dp.toPx()), Size(capWidth, capHeight + 1.dp.toPx()), CornerRadius(capWidth / 2f))
            drawRoundRect(
                Brush.verticalGradient(listOf(Color(0xFF3B424B), Color(0xFF191D22), Color(0xFF2D333B))),
                Offset(center - capWidth / 2f, capTop),
                Size(capWidth, capHeight),
                CornerRadius(capWidth / 2f)
            )
            drawRoundRect(
                Color(0xFF68727D),
                Offset(center - capWidth / 2f, capTop),
                Size(capWidth, capHeight),
                CornerRadius(capWidth / 2f),
                style = Stroke(1.dp.toPx())
            )
            drawLine(Color(0xFFE2E7EC), Offset(center - capWidth * 0.22f, yValue), Offset(center + capWidth * 0.22f, yValue), if (compact) 1.dp.toPx() else 2.dp.toPx(), StrokeCap.Round)
        }
        Text(label, color = if (selected) accent else StudioMuted, fontSize = if (compact) 6.sp else 7.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

@Composable
private fun StudioSpectrumStrip(state: EqUiState, accent: Color, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(11.dp)).background(Color(0xFF171A1F))
            .border(1.dp, Color(0xFF303740), RoundedCornerShape(11.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(76.dp).fillMaxHeight().clickable(onClick = onToggle).padding(start = 7.dp), verticalArrangement = Arrangement.Center) {
            Text("EQ RESPONSE", color = StudioText, fontSize = 7.sp, fontWeight = FontWeight.Black)
            Text(if (state.visualizerEnabled) "LIVE SPECTRUM  ●" else "RESPONSE", color = if (state.visualizerEnabled) Color(0xFF53DC96) else StudioMuted, fontSize = 6.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f).fillMaxHeight().padding(end = 5.dp).clip(RoundedCornerShape(8.dp))) {
            FrequencyResponseCanvas(
                points = state.responsePoints, bands = state.bands, selectedBandIndex = state.selectedBandIndex,
                onBandSelected = {}, spectrumBars = if (state.visualizerEnabled) state.spectrumBars else emptyList(),
                accent = accent, modifier = Modifier.fillMaxSize(), interactive = false, spectrumStyle = state.visualizerStyle, showGrid = true
            )
        }
    }
}

@Composable
private fun StudioBassTrebleRow(state: EqUiState, viewModel: EqViewModel) {
    Row(Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        RotaryKnob(
            label = "BASS", value = ((state.bassGainDb + 12f) / 24f).coerceIn(0f, 1f), valueRange = 0f..1f, defaultValue = 0.5f,
            valueText = studioToneValueText(state.bassGainDb, state.toneValueMode), onValueChange = { viewModel.setBassGain(it * 24f - 12f) },
            accent = StudioRed, knobSize = 64.dp, modifier = Modifier.weight(1f)
        )
        RotaryKnob(
            label = "TREBLE", value = ((state.trebleGainDb + 12f) / 24f).coerceIn(0f, 1f), valueRange = 0f..1f, defaultValue = 0.5f,
            valueText = studioToneValueText(state.trebleGainDb, state.toneValueMode), onValueChange = { viewModel.setTrebleGain(it * 24f - 12f) },
            accent = StudioRed, knobSize = 64.dp, modifier = Modifier.weight(1f)
        )
    }
}

private fun studioGainValueText(
    value: Float,
    mode: String
): String = when (mode) {
    "PERCENT" -> String.format(Locale.US, "%.0f%%", 100.0 * 10.0.pow(value.toDouble() / 20.0))
    else -> String.format(Locale.US, "%+.1f", value)
}

private fun studioToneValueText(valueDb: Float, mode: String): String = when (mode) {
    "DB" -> String.format(Locale.US, "%+.1f dB", valueDb)
    "HIDDEN" -> ""
    else -> String.format(Locale.US, "%.0f%%", 100.0 * 10.0.pow(valueDb.toDouble() / 20.0))
}

private fun studioFilterShort(type: Int): String = when (type) {
    NativeDspBridge.FILTER_LOW_SHELF -> "LS"
    NativeDspBridge.FILTER_HIGH_SHELF -> "HS"
    NativeDspBridge.FILTER_LOW_PASS -> "LP"
    NativeDspBridge.FILTER_HIGH_PASS -> "HP"
    NativeDspBridge.FILTER_BAND_PASS -> "BP"
    else -> "PEAK"
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
                valueText = studioToneValueText(state.bassGainDb, state.toneValueMode), onValueChange = { viewModel.setBassGain(it * 24f - 12f) },
                accent = StudioRed, knobSize = 128.dp, modifier = Modifier.weight(1f)
            )
            RotaryKnob(
                label = "TREBLE", value = ((state.trebleGainDb + 12f) / 24f).coerceIn(0f, 1f), valueRange = 0f..1f, defaultValue = 0.5f,
                valueText = studioToneValueText(state.trebleGainDb, state.toneValueMode), onValueChange = { viewModel.setTrebleGain(it * 24f - 12f) },
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
            Column(
                Modifier.weight(1f).padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("STEREO WIDTH", color = StudioMuted, fontSize = 9.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(7.dp))
                Text(
                    "Unavailable for external-player sessions",
                    color = StudioMuted.copy(alpha = 0.78f),
                    fontSize = 8.sp,
                    lineHeight = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
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
