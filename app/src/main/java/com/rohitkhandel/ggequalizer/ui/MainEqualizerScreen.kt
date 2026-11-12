package com.rohitkhandel.ggequalizer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohitkhandel.ggequalizer.audio.EqAudioProfile
import com.rohitkhandel.ggequalizer.audio.EqBandParameter
import com.rohitkhandel.ggequalizer.presets.AutoEqParser
import com.rohitkhandel.ggequalizer.presets.PresetEntity
import com.rohitkhandel.ggequalizer.ui.components.FrequencyResponseCanvas
import com.rohitkhandel.ggequalizer.ui.components.PermissionSetupSheet
import com.rohitkhandel.ggequalizer.ui.components.RotaryKnob
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

private enum class ConsolePage(val caption: String, val icon: String) {
    EQUALIZER("EQU", "≋"),
    TONE("TONE", "◉"),
    PRESETS("PRESETS", "▤"),
    SETTINGS("SETTINGS", "⚙")
}

private data class GraphicBand(val index: Int, val label: String)

@Composable
fun MainEqualizerScreen(
    viewModel: EqViewModel = viewModel(),
    onVisualizerToggle: (Boolean) -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("gg_equalizer_ui_preferences", Context.MODE_PRIVATE)
    }
    var page by rememberSaveable { mutableStateOf(ConsolePage.EQUALIZER) }
    var showPermissionHelper by rememberSaveable { mutableStateOf(false) }
    val accent = accentFor(state.accentKey)

    LaunchedEffect(Unit) {
        preferences.getString("console_accent", null)?.let(viewModel::setAccentKey)
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching { AutoEqParser.parseUri(context, uri) }
                .onSuccess { imported ->
                    viewModel.applyImportedProfile(imported.name, imported.profile)
                    Toast.makeText(context, "Imported ${imported.name}", Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
                    Toast.makeText(context, error.message ?: "AutoEQ import failed", Toast.LENGTH_LONG).show()
                }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                AutoEqParser.exportPowerampJson(
                    context = context,
                    uri = uri,
                    presetName = "GG Equalizer",
                    profile = viewModel.currentAudioProfile(),
                    stereoWidthPercent = state.stereoWidthPercent,
                    stereoBalance = state.stereoBalance,
                    dvcEnabled = state.dvcEnabled
                )
            }.onSuccess {
                Toast.makeText(context, "Preset exported", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(context, error.message ?: "Export failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) viewModel.exportPresetBackup(context, uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importPresetBackup(context, uri)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Carbon,
        bottomBar = {
            StudioBottomDock(
                selected = page.name,
                accent = accent,
                onSelect = { destination -> page = ConsolePage.valueOf(destination) }
            )
        }
    ) { inset ->
        AnimatedContent(
            targetState = page,
            modifier = Modifier
                .fillMaxSize()
                .padding(inset)
                .background(Carbon),
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(100)) },
            label = "console page swap"
        ) { destination ->
            when (destination) {
                ConsolePage.EQUALIZER -> StudioEqualizerWorkspace(
                    state = state,
                    viewModel = viewModel,
                    accent = accent,
                    onVisualizerToggle = {
                        viewModel.setVisualizerEnabled(it)
                        onVisualizerToggle(it)
                    },
                    onSave = { viewModel.saveCurrentPreset("Console preset") },
                    onImport = {
                        importLauncher.launch(arrayOf("text/plain", "application/json", "application/octet-stream"))
                    },
                    onReset = viewModel::resetAll
                )
                ConsolePage.TONE -> StudioTonePage(
                    state = state,
                    viewModel = viewModel,
                    accent = accent
                )
                ConsolePage.PRESETS -> PresetLibrary(
                    state = state,
                    accent = accent,
                    onApply = viewModel::applyPreset,
                    onSave = { viewModel.saveCurrentPreset("Console preset") },
                    onImport = {
                        importLauncher.launch(arrayOf("text/plain", "application/json", "application/octet-stream"))
                    },
                    onExport = { exportLauncher.launch("GG Equalizer preset.json") },
                    onBackup = { backupLauncher.launch("GG Equalizer backup.json") },
                    onRestore = { restoreLauncher.launch(arrayOf("application/json", "text/plain")) }
                )
                ConsolePage.SETTINGS -> ConsoleSettings(
                    state = state,
                    accent = accent,
                    context = context,
                    onAccent = { key ->
                        viewModel.setAccentKey(key)
                        preferences.edit().putString("console_accent", key).apply()
                    },
                    onBandCount = viewModel::setBandCount,
                    onShowCurve = viewModel::setShowCurveOnSliders,
                    onVisualizerStyle = viewModel::setVisualizerStyle,
                    onSmooth = viewModel::setSmoothGains,
                    onOverlapEnabled = viewModel::setBandsOverlapEnabled,
                    onOverlapPercent = viewModel::setBandsOverlapPercent,
                    onAvoidOverloading = viewModel::setAvoidOverloading,
                    onLimiter = viewModel::setLimiterEnabled,
                    onDvc = viewModel::setDvcEnabled,
                    onDvcMode = viewModel::setDvcMode,
                    onBlockSize = viewModel::setAudioBlockSize,
                    onTracking = viewModel::setActivePlayerTracking,
                    onOpenAccess = { showPermissionHelper = true }
                )
            }
        }
    }

    if (showPermissionHelper) {
        PermissionSetupSheet(onDismiss = { showPermissionHelper = false })
    }
}

@Composable
private fun ConsoleTopBar(
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
    val presetName = state.presets.firstOrNull { it.presetId == state.selectedPresetId }?.presetName ?: "Bass & Treble"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Color(0xFF0A0B0E))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1.14f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "EQU",
                color = if (state.masterEnabled) Color(0xFF071116) else SoftText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (state.masterEnabled) accent else Well)
                    .clickable { onPower(!state.masterEnabled) }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "G",
                    color = if (state.bandCount != 32) accent else Muted,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.clickable(onClick = onGraphicMode)
                )
                Text(
                    "P",
                    color = if (state.bandCount == 32) accent else Muted,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.clickable(onClick = onParametricMode)
                )
            }
        }

        Box(
            modifier = Modifier.weight(1.30f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "[$presetName  ▾]",
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Well)
                    .clickable { presetMenu = true }
                    .padding(horizontal = 9.dp, vertical = 7.dp)
            )
            DropdownMenu(
                expanded = presetMenu,
                onDismissRequest = { presetMenu = false }
            ) {
                state.presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text(preset.presetName) },
                        onClick = {
                            presetMenu = false
                            onSelectPreset(preset)
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.weight(1.18f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            RackIconAction("↓", "Save preset", onSave)
            RackIconAction("⇧", "Import AutoEQ", onImport)
            RackIconAction("↺", "Reset all", onReset)
            Box(
                Modifier
                    .size(6.dp)
                    .background(
                        if (state.activePlayerName != "No player detected") Green else Muted,
                        CircleShape
                    )
            )
            Text(
                activePlayerShortName(state.activePlayerName),
                color = if (state.activePlayerName == "No player detected") Muted else Color(0xFFE6EDF4),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@Composable
private fun RackIconAction(label: String, description: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFF9AA8B6),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    )
}

@Composable
private fun EqualizerConsole(
    state: EqUiState,
    viewModel: EqViewModel,
    accent: Color,
    onVisualizerToggle: (Boolean) -> Unit
) {
    val graphicBands = remember(state.bands, state.bandCount) {
        resolveGraphicBands(state.bands, state.bandCount)
    }
    val contentWidth = (graphicBands.size * 52).dp
    val horizontalState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Carbon)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(25.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                when (state.bandCount) { 15 -> "15 BAND GRAPHIC"; 32 -> "32 BAND PARAMETRIC"; else -> "10 BAND GRAPHIC" },
                color = Color(0xFF9BAAB8),
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f)
            )
            listOf(10, 15, 32).forEach { count ->
                Text(
                    count.toString(),
                    color = if (state.bandCount == count) accent else Muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (state.bandCount == count) accent.copy(alpha = 0.13f) else Color.Transparent)
                        .clickable { viewModel.setBandCount(count) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Text(
                if (state.visualizerEnabled) "●" else "○",
                color = if (state.visualizerEnabled) accent else Muted,
                fontSize = 10.sp,
                modifier = Modifier
                    .clickable { onVisualizerToggle(!state.visualizerEnabled) }
                    .padding(start = 7.dp)
            )
        }

        Box(Modifier.fillMaxSize()) {
            ConsoleSpectrumBackdrop(
                spectrum = if (state.visualizerEnabled) state.spectrumBars else emptyList(),
                accent = accent,
                style = state.visualizerStyle
            )

            Row(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(56.dp)
                        .fillMaxHeight()
                        .padding(horizontal = 2.dp)
                ) {
                    PowerampFader(
                        value = state.preampGainDb.coerceIn(-12f, 12f),
                        valueRange = -12f..12f,
                        label = "PRE",
                        accent = accent,
                        selected = false,
                        modifier = Modifier.fillMaxSize(),
                        onValueChange = viewModel::setPreampGain
                    )
                }

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp)
                        .background(Color(0xFF2C3540))
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(horizontalState)
                ) {
                    Box(
                        modifier = Modifier
                            .width(contentWidth)
                            .fillMaxHeight()
                    ) {
                        if (state.showCurveOnSliders) {
                            FrequencyResponseCanvas(
                                points = state.responsePoints,
                                bands = state.bands,
                                selectedBandIndex = state.selectedBandIndex,
                                onBandSelected = viewModel::selectBand,
                                spectrumBars = if (state.visualizerEnabled) state.spectrumBars else emptyList(),
                                accent = accent,
                                modifier = Modifier.fillMaxSize(),
                                interactive = false,
                                spectrumStyle = state.visualizerStyle,
                                showGrid = true
                            )
                        }
                        Row(Modifier.fillMaxSize()) {
                            graphicBands.forEach { graphicBand ->
                                val band = state.bands[graphicBand.index]
                                PowerampFader(
                                    value = band.gainDb,
                                    valueRange = -12f..12f,
                                    label = graphicBand.label,
                                    accent = accent,
                                    selected = graphicBand.index == state.selectedBandIndex,
                                    modifier = Modifier.width(52.dp).fillMaxHeight(),
                                    onValueChange = { gain ->
                                        viewModel.selectBand(graphicBand.index)
                                        viewModel.setBandGain(graphicBand.index, gain)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsoleSpectrumBackdrop(
    spectrum: List<Float>,
    accent: Color,
    style: String
) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0xFF0E1218), Color(0xFF080A0D), Color(0xFF0D1117))
            )
        )
        for (index in 0..12) {
            val y = size.height * index / 12f
            drawLine(
                Color(0xFF26313D).copy(alpha = 0.28f),
                Offset(0f, y),
                Offset(size.width, y),
                0.7.dp.toPx()
            )
        }
        if (spectrum.isNotEmpty() && style != "OFF") {
            if (style == "WAVE") {
                val wave = androidx.compose.ui.graphics.Path()
                spectrum.forEachIndexed { index, level ->
                    val x = size.width * index / (spectrum.size - 1).coerceAtLeast(1)
                    val y = size.height * (0.74f - level.coerceIn(0f, 1f) * 0.18f)
                    if (index == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
                }
                drawPath(
                    wave,
                    accent.copy(alpha = 0.20f),
                    style = Stroke(width = 6.dp.toPx())
                )
                drawPath(
                    wave,
                    accent.copy(alpha = 0.42f),
                    style = Stroke(width = 1.4.dp.toPx())
                )
            } else {
                val count = spectrum.size
                spectrum.forEachIndexed { index, level ->
                    val barWidth = size.width / count
                    val barHeight = level.coerceIn(0f, 1f) * size.height * 0.28f
                    drawRoundRect(
                        accent.copy(alpha = 0.04f + level.coerceIn(0f, 1f) * 0.13f),
                        Offset(index * barWidth + barWidth * 0.25f, size.height - barHeight),
                        Size((barWidth * 0.5f).coerceAtLeast(1f), barHeight),
                        CornerRadius(2.dp.toPx())
                    )
                }
            }
        }
    }
}

@Composable
private fun PowerampFader(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    label: String,
    accent: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onValueChange: (Float) -> Unit
) {
    val latest = androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val localValue = remember(valueRange.start, valueRange.endInclusive) { mutableFloatStateOf(value) }
    val dragging = remember { mutableStateOf(false) }
    val range = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)

    LaunchedEffect(value) {
        if (!dragging.value) localValue.floatValue = value
    }

    Column(
        modifier = modifier.padding(horizontal = 1.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            String.format(Locale.US, "%+.1f", localValue.floatValue),
            color = if (selected) accent else Color(0xFFD8E0E8),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(Well.copy(alpha = 0.92f))
                .padding(horizontal = 4.dp, vertical = 3.dp)
        )

        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(valueRange.start, valueRange.endInclusive) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging.value = true
                        var lastDispatch = 0L

                        fun update(y: Float) {
                            val progress = (1f - y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val next = (valueRange.start + progress * range)
                                .coerceIn(valueRange.start, valueRange.endInclusive)
                            localValue.floatValue = next
                            val now = android.os.SystemClock.uptimeMillis()
                            if (lastDispatch == 0L || now - lastDispatch >= 16L) {
                                latest.value(next)
                                lastDispatch = now
                            }
                        }

                        try {
                            update(down.position.y)
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.pressed) update(change.position.y)
                                change.consume()
                                pressed = change.pressed
                            }
                        } finally {
                            latest.value(localValue.floatValue)
                            dragging.value = false
                        }
                    }
                }
                .pointerInput(valueRange.start, valueRange.endInclusive) {
                    detectTapGestures(
                        onDoubleTap = {
                            localValue.floatValue = 0f.coerceIn(valueRange.start, valueRange.endInclusive)
                            latest.value(localValue.floatValue)
                        }
                    )
                }
        ) {
            val top = 7.dp.toPx()
            val bottom = size.height - 7.dp.toPx()
            val usable = (bottom - top).coerceAtLeast(1f)
            val fraction = ((localValue.floatValue - valueRange.start) / range).coerceIn(0f, 1f)
            val zeroFraction = ((0f - valueRange.start) / range).coerceIn(0f, 1f)
            val yValue = bottom - fraction * usable
            val yZero = bottom - zeroFraction * usable
            val centerX = size.width * 0.5f
            val track = 5.dp.toPx()
            val capWidth = (size.width * 0.78f).coerceAtLeast(28.dp.toPx())
            val capHeight = 14.dp.toPx()

            drawRoundRect(
                Color(0xFF26303A),
                Offset(centerX - track / 2f, top),
                Size(track, usable),
                CornerRadius(track)
            )
            drawRoundRect(
                accent.copy(alpha = if (selected) 0.85f else 0.46f),
                Offset(centerX - track / 2f, minOf(yValue, yZero)),
                Size(track, abs(yZero - yValue).coerceAtLeast(1f)),
                CornerRadius(track)
            )
            for (tick in 0..10) {
                val y = top + usable * tick / 10f
                drawLine(
                    Color(0xFF71808E).copy(alpha = 0.65f),
                    Offset(centerX + 7.dp.toPx(), y),
                    Offset(centerX + 11.dp.toPx(), y),
                    0.8.dp.toPx()
                )
            }

            val capTop = yValue - capHeight / 2f
            drawRoundRect(
                accent.copy(alpha = 0.15f),
                Offset(centerX - capWidth / 2f - 3.dp.toPx(), capTop - 3.dp.toPx()),
                Size(capWidth + 6.dp.toPx(), capHeight + 6.dp.toPx()),
                CornerRadius(5.dp.toPx())
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFF3F5F7), Color(0xFF9AA6B2), Color(0xFF4B5662))
                ),
                topLeft = Offset(centerX - capWidth / 2f, capTop),
                size = Size(capWidth, capHeight),
                cornerRadius = CornerRadius(4.dp.toPx())
            )
            drawRoundRect(
                Color(0xFF1E2630),
                Offset(centerX - capWidth / 2f, capTop),
                Size(capWidth, capHeight),
                CornerRadius(4.dp.toPx()),
                style = Stroke(width = 0.9.dp.toPx())
            )
            drawLine(
                accent.copy(alpha = 0.36f),
                Offset(centerX - capWidth * 0.31f, yValue),
                Offset(centerX + capWidth * 0.31f, yValue),
                5.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawLine(
                accent,
                Offset(centerX - capWidth * 0.30f, yValue),
                Offset(centerX + capWidth * 0.30f, yValue),
                1.4.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
        }

        Text(
            label,
            color = if (selected) accent else Color(0xFF9BA9B7),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
private fun ToneStereoRack(
    state: EqUiState,
    viewModel: EqViewModel,
    accent: Color
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Carbon)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.65f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToneChannel(
                modifier = Modifier.weight(1f),
                title = "BASS",
                value = state.bassGainDb / 12f,
                percent = (state.bassGainDb / 12f * 100f).toInt(),
                frequency = state.bassFrequencyHz,
                q = state.bassQFactor,
                accent = accent,
                onLevel = { viewModel.setBassGain(it * 12f) },
                onFrequency = viewModel::setBassFrequency,
                onQ = viewModel::setBassQ,
                frequencyRange = 20f..250f
            )
            ToneChannel(
                modifier = Modifier.weight(1f),
                title = "TREBLE",
                value = state.trebleGainDb / 12f,
                percent = (state.trebleGainDb / 12f * 100f).toInt(),
                frequency = state.trebleFrequencyHz,
                q = state.trebleQFactor,
                accent = accent,
                onLevel = { viewModel.setTrebleGain(it * 12f) },
                onFrequency = viewModel::setTrebleFrequency,
                onQ = viewModel::setTrebleQ,
                frequencyRange = 4_000f..16_000f
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.22f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                label = "STEREO EXPAND",
                value = state.stereoWidthPercent,
                valueRange = 0f..200f,
                defaultValue = 100f,
                valueText = "${state.stereoWidthPercent.toInt()}%",
                onValueChange = viewModel::setStereoWidthPercent,
                accent = accent,
                knobSize = 82.dp,
                modifier = Modifier.weight(1f)
            )
            RotaryKnob(
                label = "BALANCE",
                value = state.stereoBalance,
                valueRange = -1f..1f,
                defaultValue = 0f,
                valueText = balanceText(state.stereoBalance),
                onValueChange = viewModel::setStereoBalance,
                accent = accent,
                knobSize = 82.dp,
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.13f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RackToggle("LIMITER", state.limiterEnabled, accent, Modifier.weight(1f), viewModel::setLimiterEnabled)
                RackToggle("DVC", state.dvcEnabled, accent, Modifier.weight(1f), viewModel::setDvcEnabled)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RackToggle("BANDS OVERLAP", state.bandsOverlapEnabled, accent, Modifier.weight(1f), viewModel::setBandsOverlapEnabled)
                RackToggle("AVOID OVERLOADING", state.avoidOverloading, accent, Modifier.weight(1f), viewModel::setAvoidOverloading)
            }
        }
    }
}

@Composable
private fun ToneChannel(
    modifier: Modifier,
    title: String,
    value: Float,
    percent: Int,
    frequency: Float,
    q: Float,
    accent: Color,
    onLevel: (Float) -> Unit,
    onFrequency: (Float) -> Unit,
    onQ: (Float) -> Unit,
    frequencyRange: ClosedFloatingPointRange<Float>
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        RotaryKnob(
            label = title,
            value = value.coerceIn(0f, 1f),
            valueRange = 0f..1f,
            defaultValue = 0f,
            valueText = "$percent%",
            onValueChange = onLevel,
            accent = accent,
            knobSize = 145.dp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                label = "FREQ",
                value = frequency,
                valueRange = frequencyRange,
                defaultValue = if (title == "BASS") 85f else 10_000f,
                valueText = compactHz(frequency),
                onValueChange = onFrequency,
                accent = accent,
                knobSize = 58.dp
            )
            RotaryKnob(
                label = "Q",
                value = q,
                valueRange = 0.5f..2f,
                defaultValue = 0.8f,
                valueText = String.format(Locale.US, "%.1f", q),
                onValueChange = onQ,
                accent = accent,
                knobSize = 58.dp
            )
        }
    }
}

@Composable
private fun RackToggle(
    label: String,
    checked: Boolean,
    accent: Color,
    modifier: Modifier,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (checked) accent.copy(alpha = 0.14f) else Well)
            .clickable { onChange(!checked) }
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (checked) accent else Muted,
            fontSize = 8.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            Modifier
                .size(width = 24.dp, height = 13.dp)
                .clip(CircleShape)
                .background(if (checked) accent else Color(0xFF3D4854))
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.size(9.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun PresetLibrary(
    state: EqUiState,
    accent: Color,
    onApply: (PresetEntity) -> Unit,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val recipes = remember(state.bands) { createConsolePresets(state) }
    val allPresets = (state.presets + recipes).distinctBy { it.presetName.lowercase(Locale.US) }
    val visible = allPresets.filter {
        it.presetName.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
    }

    Column(Modifier.fillMaxSize().background(Carbon)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("PRESET LIBRARY", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Text(
                state.presetMessage ?: "${visible.size} profiles",
                color = accent,
                fontSize = 8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(50.dp),
            singleLine = true,
            placeholder = { Text("Search presets…", color = Muted, fontSize = 11.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = Color(0xFF303A45),
                cursorColor = accent
            )
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(visible, key = { it.presetId }) { preset ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (preset.presetId == state.selectedPresetId) Well else Color.Transparent)
                        .clickable { onApply(preset) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(6.dp).background(if (preset.presetId == state.selectedPresetId) accent else Color(0xFF485460), CircleShape))
                    Column(Modifier.weight(1f).padding(start = 9.dp)) {
                        Text(preset.presetName, color = Color(0xFFE5ECF3), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(preset.description, color = Muted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(if (preset.isBuiltIn) "FACTORY" else "USER", color = accent, fontSize = 7.sp, fontWeight = FontWeight.Black)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF202831)))
            }
            if (visible.isEmpty()) {
                item { Text("No matching preset", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(12.dp)) }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RackAction("SAVE", accent, Modifier.weight(1f), onSave)
            RackAction("IMPORT", accent, Modifier.weight(1f), onImport)
            RackAction("EXPORT", accent, Modifier.weight(1f), onExport)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            RackAction("BACKUP", accent, Modifier.weight(1f), onBackup)
            RackAction("RESTORE", accent, Modifier.weight(1f), onRestore)
        }
    }
}

private fun createConsolePresets(state: EqUiState): List<PresetEntity> {
    data class Recipe(
        val id: String,
        val name: String,
        val description: String,
        val bass: Float,
        val treble: Float,
        val preamp: Float,
        val shape: (Float) -> Float
    )

    fun bell(frequency: Float, center: Float, width: Float): Float {
        val octaves = ln((frequency / center).coerceAtLeast(0.0001f)) / 0.69314718056f
        return kotlin.math.exp(-0.5f * (octaves / width).pow(2))
    }

    val recipes = listOf(
        Recipe("console_harman", "Harman Target", "Balanced consumer tuning", 2f, 2f, -2f) { hz ->
            2.2f * bell(hz, 60f, 1.2f) - 1.2f * bell(hz, 2_500f, 0.65f) + 1.8f * bell(hz, 11_000f, 0.8f)
        },
        Recipe("console_bass", "Bass Extreme", "Deep low-end with warm punch", 9f, 1f, -6f) { hz ->
            6f * bell(hz, 55f, 1.1f) + 2.2f * bell(hz, 120f, 0.75f) - 1f * bell(hz, 700f, 1.2f)
        },
        Recipe("console_sub", "Sub-Bass Monster", "Sub-focused curve with controlled mids", 12f, 0f, -8f) { hz ->
            8f * bell(hz, 38f, 0.8f) + 5f * bell(hz, 75f, 0.8f) - 1.2f * bell(hz, 500f, 1.1f)
        },
        Recipe("console_vocal", "Clear Vocals", "Presence lift with softened low bass", 0.5f, 2f, -2f) { hz ->
            -2f * bell(hz, 80f, 1.1f) + 3.5f * bell(hz, 3_000f, 0.65f) + 1.5f * bell(hz, 8_000f, 0.8f)
        },
        Recipe("console_rock", "Rock", "Punchy low end and forward guitars", 4f, 3f, -4f) { hz ->
            3.5f * bell(hz, 90f, 1.0f) - 1.7f * bell(hz, 400f, 0.7f) + 3f * bell(hz, 2_800f, 0.8f) + 1f * bell(hz, 9_000f, 0.9f)
        },
        Recipe("console_cinema", "Dolby Cinema", "Wide cinematic bass and clean dialogue", 5f, 2.5f, -4f) { hz ->
            4f * bell(hz, 55f, 1.2f) + 2f * bell(hz, 120f, 0.9f) + 2.2f * bell(hz, 3_500f, 0.9f) + 1f * bell(hz, 10_000f, 0.9f)
        },
        Recipe("console_gaming", "Gaming", "Footstep clarity and controlled sub bass", 0f, 2f, -3f) { hz ->
            -4.5f * bell(hz, 60f, 1.0f) + 3.4f * bell(hz, 2_000f, 0.7f) + 2.5f * bell(hz, 4_500f, 0.75f)
        }
    )

    return recipes.map { recipe ->
        val parameters = state.bands.map { band ->
            EqBandParameter(
                frequencyHz = band.frequencyHz,
                gainDb = recipe.shape(band.frequencyHz).coerceIn(-10f, 10f),
                qFactor = 1.1f,
                filterType = band.filterType,
                enabled = true
            )
        }
        val profile = EqAudioProfile.defaults().copy(
            bands = parameters,
            preampGainDb = recipe.preamp,
            bassGainDb = recipe.bass,
            trebleGainDb = recipe.treble
        )
        PresetEntity.fromProfile(
            presetId = recipe.id,
            presetName = recipe.name,
            description = recipe.description,
            profile = profile,
            isBuiltIn = true,
            stereoWidthPercent = state.stereoWidthPercent,
            stereoBalance = state.stereoBalance,
            dvcEnabled = state.dvcEnabled
        )
    }
}

@Composable
private fun ConsoleSettings(
    state: EqUiState,
    accent: Color,
    context: Context,
    onAccent: (String) -> Unit,
    onBandCount: (Int) -> Unit,
    onShowCurve: (Boolean) -> Unit,
    onVisualizerStyle: (String) -> Unit,
    onSmooth: (Boolean) -> Unit,
    onOverlapEnabled: (Boolean) -> Unit,
    onOverlapPercent: (Float) -> Unit,
    onAvoidOverloading: (Boolean) -> Unit,
    onLimiter: (Boolean) -> Unit,
    onDvc: (Boolean) -> Unit,
    onDvcMode: (String) -> Unit,
    onBlockSize: (Int) -> Unit,
    onTracking: (Boolean) -> Unit,
    onOpenAccess: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Carbon),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { SectionHeader("LOOK & FEEL", accent) }
        item {
            Text("ACCENT COLOR", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    "cyan" to Color(0xFF42D9F5),
                    "amber" to Color(0xFFFFB547),
                    "purple" to Color(0xFFB58CFF),
                    "matrix" to Color(0xFF4BE38A)
                ).forEach { (key, tint) ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (state.accentKey == key) tint.copy(alpha = 0.17f) else Well)
                            .clickable { onAccent(key) }
                            .padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).background(tint, CircleShape))
                        Text(
                            when (key) { "cyan" -> "CYBER"; "amber" -> "AMBER"; "purple" -> "PURPLE"; else -> "MATRIX" },
                            color = if (state.accentKey == key) tint else SoftText,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 5.dp)
                        )
                    }
                }
            }
        }
        item { SettingsToggleRow("Show frequency curve on faders", state.showCurveOnSliders, accent, onShowCurve) }
        item {
            Text("VISUALIZER STYLE", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            ChoiceChips(
                values = listOf("BARS", "WAVE", "OFF"),
                selected = state.visualizerStyle,
                accent = accent,
                onSelect = onVisualizerStyle
            )
        }

        item { SectionHeader("EQUALIZER & TONE", accent) }
        item {
            Text("GRAPHIC BAND COUNT", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            ChoiceChips(listOf("10", "15", "32"), state.bandCount.toString(), accent) {
                onBandCount(it.toInt())
            }
        }
        item { SettingsToggleRow("Smooth equalizer / tone gains", state.smoothGains, accent, onSmooth) }
        item { SettingsToggleRow("Bands overlap", state.bandsOverlapEnabled, accent, onOverlapEnabled) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OVERLAP AMOUNT", color = SoftText, fontSize = 9.sp, modifier = Modifier.weight(1f))
                Text("${state.bandsOverlapPercent.toInt()}%", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = state.bandsOverlapPercent,
                onValueChange = onOverlapPercent,
                valueRange = 0f..100f
            )
        }
        item { SettingsToggleRow("Avoid overloading to maximum volume", state.avoidOverloading, accent, onAvoidOverloading) }
        item { SettingsToggleRow("Session limiter", state.limiterEnabled, accent, onLimiter) }

        item { SectionHeader("AUDIO ENGINE & DVC", accent) }
        item {
            SettingsToggleRow("Dynamic volume compensation", state.dvcEnabled, accent, onDvc)
            Text("DVC MODE", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            ChoiceChips(listOf("SMART", "CLASSIC"), state.dvcMode, accent, onDvcMode)
        }
        item {
            Text("AUDIO BLOCK PREFERENCE", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            ChoiceChips(listOf("2048", "4096", "8192"), state.audioBlockSize.toString(), accent) {
                onBlockSize(it.toInt())
            }
            Text(
                "This is a saved engine preference. Android's external session-effect API does not expose a guaranteed processing block-size control.",
                color = Muted,
                fontSize = 8.sp
            )
        }
        item { SettingsToggleRow("Active player tracking", state.activePlayerTracking, accent, onTracking) }
        item {
            Text(
                "PLAYER ACCESS / ADB",
                color = Muted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
            SettingsAction("NOTIFICATION + AUDIO SESSION ACCESS", accent, onOpenAccess)
            SettingsAction("COPY MEDIA SESSION ADB COMMAND", accent) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText(
                        "GG Equalizer diagnostics",
                        "adb shell dumpsys media_session\nadb shell dumpsys media.audio_flinger"
                    )
                )
                Toast.makeText(context, "ADB command copied", Toast.LENGTH_SHORT).show()
            }
            StatusLine("Active player", state.activePlayerName)
            StatusLine("Package", state.activePlayerPackage ?: "—")
            StatusLine("Audio session", state.activeSessionId?.toString() ?: "Not attached")
            StatusLine("Session effects", state.attachmentSummary)
        }
    }
}

@Composable
private fun SectionHeader(title: String, accent: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(title, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF28313A)))
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    accent: Color,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = SoftText, fontSize = 10.sp, modifier = Modifier.weight(1f))
        RackToggle(" ", checked, accent, Modifier.width(54.dp), onChange)
    }
}

@Composable
private fun ChoiceChips(
    values: List<String>,
    selected: String,
    accent: Color,
    onSelect: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEach { value ->
            Text(
                value,
                color = if (selected.equals(value, true)) Color(0xFF071116) else SoftText,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (selected.equals(value, true)) accent else Well)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun SettingsAction(label: String, accent: Color, onClick: () -> Unit) {
    Text(
        label,
        color = accent,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(Well)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    )
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 8.sp, modifier = Modifier.weight(0.75f))
        Text(value, color = SoftText, fontSize = 8.sp, modifier = Modifier.weight(1.25f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RackAction(label: String, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Well)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accent, fontSize = 8.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ConsoleBottomBar(
    selected: ConsolePage,
    accent: Color,
    onSelect: (ConsolePage) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(56.dp)
            .background(Color(0xFF080A0D))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConsolePage.entries.forEach { page ->
            val active = selected == page
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(page) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(page.icon, color = if (active) accent else Muted, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(page.caption, color = if (active) accent else Muted, fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.45.sp)
            }
        }
    }
}

private fun resolveGraphicBands(bands: List<EqBandUiState>, count: Int): List<GraphicBand> {
    if (bands.isEmpty()) return emptyList()
    if (count >= 32) return bands.mapIndexed { index, band -> GraphicBand(index, compactHz(band.frequencyHz)) }

    val targets = if (count == 15) {
        listOf(
            25f to "25", 40f to "40", 63f to "63", 100f to "100", 160f to "160",
            250f to "250", 400f to "400", 630f to "630", 1_000f to "1k",
            1_600f to "1.6k", 2_500f to "2.5k", 4_000f to "4k",
            6_300f to "6.3k", 10_000f to "10k", 16_000f to "16k"
        )
    } else {
        listOf(
            31f to "31", 62f to "62", 125f to "125", 250f to "250", 500f to "500",
            1_000f to "1k", 2_000f to "2k", 4_000f to "4k", 8_000f to "8k", 16_000f to "16k"
        )
    }
    return targets.map { (frequency, label) ->
        val index = bands.indices.minByOrNull { candidate ->
            abs(ln(bands[candidate].frequencyHz.coerceAtLeast(1f) / frequency))
        } ?: 0
        GraphicBand(index, label)
    }.distinctBy(GraphicBand::index)
}

private fun activePlayerShortName(name: String): String = when (name) {
    "YouTube Music" -> "YT Music"
    "No player detected" -> "—"
    else -> name.take(10)
}

private fun compactHz(value: Float): String {
    if (value < 1_000f) return String.format(Locale.US, "%.0f", value)
    val khz = value / 1_000f
    return if (abs(khz - khz.toInt()) < 0.05f) {
        String.format(Locale.US, "%.0fk", khz)
    } else {
        String.format(Locale.US, "%.1fk", khz)
    }
}

private fun balanceText(value: Float): String = when {
    abs(value) < 0.04f -> "C"
    value < 0f -> "L${(-value * 100f).toInt()}"
    else -> "R${(value * 100f).toInt()}"
}

private fun accentFor(key: String): Color = when (key) {
    "amber" -> Color(0xFFFFB547)
    "purple" -> Color(0xFFB58CFF)
    "matrix" -> Color(0xFF4BE38A)
    else -> Color(0xFF42D9F5)
}

private val Carbon = Color(0xFF0A0B0E)
private val Well = Color(0xFF14171D)
private val Muted = Color(0xFF82909E)
private val SoftText = Color(0xFFD4DCE5)
private val Green = Color(0xFF4AE18B)
