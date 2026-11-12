package com.rohitkhandel.ggequalizer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.rohitkhandel.ggequalizer.presets.AutoEqParser
import com.rohitkhandel.ggequalizer.presets.PresetEntity
import com.rohitkhandel.ggequalizer.ui.components.FrequencyResponseCanvas
import com.rohitkhandel.ggequalizer.ui.components.PermissionSetupSheet
import com.rohitkhandel.ggequalizer.ui.components.RotaryKnob
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

private enum class GgPage(val title: String, val glyph: String) {
    EQ("EQ", "≋"),
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
    var page by rememberSaveable { mutableStateOf(GgPage.EQ) }
    var accentKey by rememberSaveable {
        mutableStateOf(preferences.getString("accent_key", "cyan") ?: "cyan")
    }
    var showAccessHelper by rememberSaveable { mutableStateOf(false) }
    val accent = if (accentKey == "amber") Amber else Cyan

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
                    Toast.makeText(
                        context,
                        error.message ?: "Preset import failed",
                        Toast.LENGTH_LONG
                    ).show()
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
            CarbonBottomBar(selected = page, accent = accent, onSelect = { page = it })
        }
    ) { scaffoldPadding ->
        AnimatedContent(
            targetState = page,
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .background(Carbon),
            transitionSpec = {
                fadeIn(tween(130)) togetherWith fadeOut(tween(90))
            },
            label = "GG full-screen page"
        ) { destination ->
            when (destination) {
                GgPage.EQ -> EqualizerScreen(
                    state = state,
                    viewModel = viewModel,
                    accent = accent,
                    onOpenTone = { page = GgPage.TONE },
                    onVisualizerToggle = onVisualizerToggle
                )
                GgPage.TONE -> ToneScreen(
                    state = state,
                    viewModel = viewModel,
                    accent = accent,
                    onOpenEq = { page = GgPage.EQ }
                )
                GgPage.PRESETS -> PresetsScreen(
                    state = state,
                    accent = accent,
                    onApply = viewModel::applyPreset,
                    onSave = { viewModel.saveCurrentPreset("Custom profile") },
                    onImport = {
                        importLauncher.launch(
                            arrayOf("text/plain", "application/json", "application/octet-stream")
                        )
                    },
                    onExport = { exportLauncher.launch("GG Equalizer preset.json") },
                    onBackup = { backupLauncher.launch("GG Equalizer backup.json") },
                    onRestore = {
                        restoreLauncher.launch(arrayOf("application/json", "text/plain"))
                    }
                )
                GgPage.SETTINGS -> SettingsStatusScreen(
                    state = state,
                    accent = accent,
                    accentKey = accentKey,
                    onAccentChanged = { next ->
                        accentKey = next
                        preferences.edit().putString("accent_key", next).apply()
                    },
                    onMasterChanged = viewModel::setMasterEnabled,
                    onOpenAccessHelper = { showAccessHelper = true },
                    context = context
                )
            }
        }
    }

    if (showAccessHelper) {
        PermissionSetupSheet(onDismiss = { showAccessHelper = false })
    }
}

@Composable
private fun EqualizerScreen(
    state: EqUiState,
    viewModel: EqViewModel,
    accent: Color,
    onOpenTone: () -> Unit,
    onVisualizerToggle: (Boolean) -> Unit
) {
    var presetMenu by rememberSaveable { mutableStateOf(false) }
    var bandMode by rememberSaveable { mutableStateOf("10") }
    val selectedPreset = state.presets.firstOrNull { it.presetId == state.selectedPresetId }
    val presetLabel = selectedPreset?.presetName ?: "Bass Extreme"
    val graphicBands = remember(state.bands, bandMode) {
        buildGraphicBands(state.bands, bandMode)
    }

    LaunchedEffect(bandMode) {
        if (bandMode != "32") {
            buildGraphicBands(viewModel.uiState.value.bands, bandMode)
                .forEach { viewModel.setBandQ(it.index, 1.41f) }
        }
    }

    Column(Modifier.fillMaxSize().background(Carbon)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1.12f)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Panel)
                        .clickable { presetMenu = true }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        presetLabel,
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("  ▾", color = Muted, fontSize = 10.sp)
                }
                DropdownMenu(
                    expanded = presetMenu,
                    onDismissRequest = { presetMenu = false }
                ) {
                    state.presets.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.presetName) },
                            onClick = {
                                presetMenu = false
                                viewModel.applyPreset(preset)
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .weight(1.05f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Panel)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                QuickMode("EQ", true, accent) {}
                QuickMode("TONE", false, accent, onOpenTone)
            }

            Row(
                modifier = Modifier.weight(0.90f).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(
                            if (state.activePlayerName != "No player detected") Green else Muted,
                            CircleShape
                        )
                )
                Text(
                    if (state.activePlayerName == "No player detected") "No player" else state.activePlayerName,
                    color = if (state.activePlayerName == "No player detected") Muted else Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 5.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.27f)
                .padding(horizontal = 5.dp, vertical = 2.dp)
        ) {
            FrequencyResponseCanvas(
                points = state.responsePoints,
                bands = state.bands,
                selectedBandIndex = state.selectedBandIndex,
                onBandSelected = viewModel::selectBand,
                onBandGainChanged = viewModel::setBandGain,
                spectrumBars = if (state.visualizerEnabled) state.spectrumBars else emptyList(),
                accent = accent,
                modifier = Modifier.fillMaxSize()
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xD90B0D10))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf("10", "15", "32").forEach { mode ->
                    Text(
                        mode,
                        color = if (bandMode == mode) accent else Muted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { bandMode = mode }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                Text(
                    "FFT",
                    color = if (state.visualizerEnabled) accent else Muted,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onVisualizerToggle(!state.visualizerEnabled) }
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.73f)
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PowerampFader(
                value = state.preampGainDb,
                valueRange = -24f..12f,
                label = "PRE",
                accent = accent,
                selected = false,
                modifier = Modifier.width(60.dp).fillMaxHeight(),
                onValueChange = viewModel::setPreampGain
            )
            Spacer(
                Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .padding(vertical = 12.dp)
                    .background(Color(0xFF303944))
            )
            LazyRow(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(horizontal = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(
                    items = graphicBands,
                    key = { "graphic_${bandMode}_${it.index}" }
                ) { item ->
                    val band = state.bands[item.index]
                    PowerampFader(
                        value = band.gainDb,
                        valueRange = -12f..12f,
                        label = item.label,
                        accent = accent,
                        selected = item.index == state.selectedBandIndex,
                        modifier = Modifier.width(if (bandMode == "32") 50.dp else 56.dp).fillMaxHeight(),
                        onValueChange = { gain ->
                            viewModel.selectBand(item.index)
                            viewModel.setBandGain(item.index, gain)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickMode(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Text(
        text,
        color = if (selected) Color(0xFF081116) else Muted,
        fontSize = 9.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
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
    val latestCallback = androidx.compose.runtime.rememberUpdatedState(onValueChange)
    val localValue = remember { mutableFloatStateOf(value) }
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
            color = if (selected) accent else Color(0xFFD6DEE8),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
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
                            val fraction = (1f - y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                            val next = (valueRange.start + fraction * range)
                                .coerceIn(valueRange.start, valueRange.endInclusive)
                            localValue.floatValue = next
                            val now = android.os.SystemClock.uptimeMillis()
                            if (lastDispatch == 0L || now - lastDispatch >= 20L) {
                                latestCallback.value(next)
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
                            latestCallback.value(localValue.floatValue)
                            dragging.value = false
                        }
                    }
                }
        ) {
            val top = 8.dp.toPx()
            val bottom = size.height - 8.dp.toPx()
            val usable = (bottom - top).coerceAtLeast(1f)
            val fraction = ((localValue.floatValue - valueRange.start) / range).coerceIn(0f, 1f)
            val zeroFraction = ((0f - valueRange.start) / range).coerceIn(0f, 1f)
            val yValue = bottom - fraction * usable
            val yZero = bottom - zeroFraction * usable
            val centerX = size.width / 2f
            val trackWidth = 5.dp.toPx()
            val capWidth = (size.width * 0.78f).coerceAtLeast(30.dp.toPx())
            val capHeight = 15.dp.toPx()

            drawRoundRect(
                Color(0xFF242C35),
                Offset(centerX - trackWidth / 2f, top),
                Size(trackWidth, usable),
                CornerRadius(trackWidth)
            )
            drawRoundRect(
                accent.copy(alpha = 0.72f),
                Offset(centerX - trackWidth / 2f, minOf(yValue, yZero)),
                Size(trackWidth, abs(yZero - yValue).coerceAtLeast(1f)),
                CornerRadius(trackWidth)
            )
            drawLine(
                Color(0xFF718092),
                Offset(centerX - 10.dp.toPx(), yZero),
                Offset(centerX + 10.dp.toPx(), yZero),
                1.dp.toPx()
            )
            for (tick in 0..8) {
                val y = top + usable * tick / 8f
                drawLine(
                    Color(0xFF536171),
                    Offset(centerX + 7.dp.toPx(), y),
                    Offset(centerX + 11.dp.toPx(), y),
                    1.dp.toPx()
                )
            }
            val capTop = yValue - capHeight / 2f
            drawRoundRect(
                accent.copy(alpha = if (selected) 0.25f else 0.14f),
                Offset(centerX - capWidth / 2f - 3.dp.toPx(), capTop - 3.dp.toPx()),
                Size(capWidth + 6.dp.toPx(), capHeight + 6.dp.toPx()),
                CornerRadius(5.dp.toPx())
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFF0F3F6), Color(0xFF8995A2), Color(0xFF3C4652))
                ),
                topLeft = Offset(centerX - capWidth / 2f, capTop),
                size = Size(capWidth, capHeight),
                cornerRadius = CornerRadius(4.dp.toPx())
            )
            drawRoundRect(
                Color(0xFF202832),
                Offset(centerX - capWidth / 2f, capTop),
                Size(capWidth, capHeight),
                CornerRadius(4.dp.toPx()),
                style = Stroke(width = 0.8.dp.toPx())
            )
            drawLine(
                accent.copy(alpha = 0.38f),
                Offset(centerX - capWidth * 0.34f, yValue),
                Offset(centerX + capWidth * 0.34f, yValue),
                5.dp.toPx()
            )
            drawLine(
                accent,
                Offset(centerX - capWidth * 0.30f, yValue),
                Offset(centerX + capWidth * 0.30f, yValue),
                1.5.dp.toPx()
            )
        }
        Text(
            label,
            color = if (selected) accent else Color(0xFF9AA8B8),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
private fun ToneScreen(
    state: EqUiState,
    viewModel: EqViewModel,
    accent: Color,
    onOpenEq: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().background(Carbon),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TONE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            QuickMode("EQ", false, accent, onOpenEq)
            QuickMode("TONE", true, accent) {}
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            RotaryKnob(
                label = "BASS",
                value = (state.bassGainDb.coerceIn(0f, 12f) / 12f),
                valueRange = 0f..1f,
                defaultValue = 0f,
                valueText = "${(state.bassGainDb.coerceIn(0f, 12f) / 12f * 100f).toInt()}%",
                onValueChange = { viewModel.setBassGain(it * 12f) },
                modifier = Modifier.weight(1f),
                accent = accent,
                knobSize = 150.dp
            )
            RotaryKnob(
                label = "TREBLE",
                value = (state.trebleGainDb.coerceIn(0f, 12f) / 12f),
                valueRange = 0f..1f,
                defaultValue = 0f,
                valueText = "${(state.trebleGainDb.coerceIn(0f, 12f) / 12f * 100f).toInt()}%",
                onValueChange = { viewModel.setTrebleGain(it * 12f) },
                modifier = Modifier.weight(1f),
                accent = accent,
                knobSize = 150.dp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(126.dp).padding(horizontal = 10.dp),
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
                modifier = Modifier.weight(1f),
                accent = accent,
                knobSize = 82.dp
            )
            RotaryKnob(
                label = "PREAMP",
                value = state.preampGainDb,
                valueRange = -24f..12f,
                defaultValue = 0f,
                valueText = String.format(Locale.US, "%+.1f dB", state.preampGainDb),
                onValueChange = viewModel::setPreampGain,
                modifier = Modifier.weight(1f),
                accent = accent,
                knobSize = 82.dp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PowerampPillSwitch(
                "LIMITER",
                state.limiterEnabled,
                accent,
                Modifier.weight(1f),
                viewModel::setLimiterEnabled
            )
            PowerampPillSwitch(
                "DVC",
                state.dvcEnabled,
                accent,
                Modifier.weight(1f),
                viewModel::setDvcEnabled
            )
        }
    }
}

@Composable
private fun PowerampPillSwitch(
    label: String,
    checked: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (checked) accent.copy(alpha = 0.16f) else Panel)
            .clickable { onChange(!checked) }
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = if (checked) accent else Muted, fontSize = 10.sp, fontWeight = FontWeight.Black)
        Box(
            Modifier
                .size(width = 28.dp, height = 15.dp)
                .clip(CircleShape)
                .background(if (checked) accent else Color(0xFF48515D))
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.size(11.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun PresetsScreen(
    state: EqUiState,
    accent: Color,
    onApply: (PresetEntity) -> Unit,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(Carbon)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("PRESETS", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text("${state.presets.size} profiles", color = Muted, fontSize = 9.sp)
            }
            TextButton(onClick = onSave) { Text("SAVE CURRENT", color = accent, fontSize = 9.sp) }
        }
        state.presetMessage?.let {
            Text(it, color = accent, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 2.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            items(state.presets, key = { it.presetId }) { preset ->
                PresetCard(
                    preset = preset,
                    selected = preset.presetId == state.selectedPresetId,
                    accent = accent,
                    onClick = { onApply(preset) }
                )
            }
            if (state.presets.isEmpty()) {
                item {
                    Text(
                        "Your saved presets will appear here.",
                        color = Muted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PresetAction("IMPORT", accent, Modifier.weight(1f), onImport)
                PresetAction("EXPORT", accent, Modifier.weight(1f), onExport)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                PresetAction("BACKUP", accent, Modifier.weight(1f), onBackup)
                PresetAction("RESTORE", accent, Modifier.weight(1f), onRestore)
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: PresetEntity,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF18252C) else Panel
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).background(if (selected) accent else Color(0xFF4C5866), CircleShape))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(preset.presetName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(
                    preset.description,
                    color = Muted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(if (preset.isBuiltIn) "BUILT-IN" else "CUSTOM", color = accent, fontSize = 8.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun PresetAction(
    label: String,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Panel)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun SettingsStatusScreen(
    state: EqUiState,
    accent: Color,
    accentKey: String,
    onAccentChanged: (String) -> Unit,
    onMasterChanged: (Boolean) -> Unit,
    onOpenAccessHelper: () -> Unit,
    context: Context
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Carbon),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SETTINGS & STATUS", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Text(
                    if (state.masterEnabled) "ENGINE ON" else "BYPASS",
                    color = if (state.masterEnabled) accent else Muted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        item {
            SettingsCard(title = "PLAYER / AUDIO SESSION", accent = accent) {
                StatusLine("Player", state.activePlayerName)
                StatusLine("Package", state.activePlayerPackage ?: "—")
                StatusLine("Session ID", state.activeSessionId?.toString() ?: "Not attached")
                StatusLine("Attached effects", state.activeSessionCount.toString())
                StatusLine("Engine", if (state.masterEnabled) "Enabled" else "Bypass")
                StatusLine("Effect path", state.attachmentSummary)
                Text("Master processing", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
                PowerampPillSwitch(
                    "MASTER DSP",
                    state.masterEnabled,
                    accent,
                    Modifier.fillMaxWidth(),
                    onMasterChanged
                )
            }
        }
        item {
            SettingsCard(title = "ACCESS & PERMISSIONS", accent = accent) {
                SettingsAction("Notification listener access") {
                    openSettingsPage(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
                SettingsAction("Usage access") {
                    openSettingsPage(context, Settings.ACTION_USAGE_ACCESS_SETTINGS)
                }
                SettingsAction("App permissions") {
                    openSettingsPage(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                }
                SettingsAction("Battery optimisation") {
                    openSettingsPage(context, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                }
                SettingsAction("Guided access setup") { onOpenAccessHelper() }
            }
        }
        item {
            SettingsCard(title = "DEVELOPER / ADB", accent = accent) {
                val commands = "adb shell dumpsys media_session\nadb shell dumpsys media.audio_flinger"
                Text("Inspect active players and audio sessions from a connected computer.", color = Muted, fontSize = 9.sp)
                Text(commands, color = Color(0xFFB9C9D8), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("GG Equalizer ADB", commands))
                        Toast.makeText(context, "ADB commands copied", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("COPY ADB COMMANDS", color = accent, fontSize = 9.sp) }
            }
        }
        item {
            SettingsCard(title = "APPEARANCE", accent = accent) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsAction("CYAN") { onAccentChanged("cyan") }
                    SettingsAction("AMBER") { onAccentChanged("amber") }
                }
                StatusLine("Accent", accentKey.uppercase(Locale.US))
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    accent: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(13.dp),
        colors = CardDefaults.cardColors(containerColor = Panel)
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Muted, fontSize = 9.sp, modifier = Modifier.weight(0.8f))
        Text(
            value,
            color = Color(0xFFDCE4EC),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1.2f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFFE7EDF4),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF202A34))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 10.dp)
    )
}

private fun openSettingsPage(context: Context, action: String, uri: Uri? = null) {
    runCatching {
        val intent = Intent(action).apply {
            if (uri != null) data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(context, "This settings page is unavailable on this device", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun CarbonBottomBar(
    selected: GgPage,
    accent: Color,
    onSelect: (GgPage) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(58.dp)
            .background(Color(0xFF090B0E))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GgPage.entries.forEach { item ->
            val active = selected == item
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) accent.copy(alpha = 0.11f) else Color.Transparent)
                    .clickable { onSelect(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(item.glyph, color = if (active) accent else Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(item.title, color = if (active) accent else Muted, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            }
        }
    }
}

private fun buildGraphicBands(bands: List<EqBandUiState>, mode: String): List<GraphicBand> {
    if (bands.isEmpty()) return emptyList()
    if (mode == "32") {
        return bands.mapIndexed { index, band -> GraphicBand(index, compactFrequency(band.frequencyHz)) }
    }
    val targets = if (mode == "15") {
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

private fun compactFrequency(frequencyHz: Float): String {
    if (frequencyHz < 1000f) return String.format(Locale.US, "%.0f", frequencyHz)
    val khz = frequencyHz / 1000f
    return if (abs(khz - khz.toInt()) < 0.05f) {
        String.format(Locale.US, "%.0fk", khz)
    } else {
        String.format(Locale.US, "%.1fk", khz)
    }
}

private val Carbon = Color(0xFF0B0D10)
private val Panel = Color(0xFF171C22)
private val Muted = Color(0xFF82909E)
private val Cyan = Color(0xFF42D9F5)
private val Amber = Color(0xFFFFB547)
private val Green = Color(0xFF39D98A)
