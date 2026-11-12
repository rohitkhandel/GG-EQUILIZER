package com.rohitkhandel.ggequalizer.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohitkhandel.ggequalizer.presets.AutoEqParser
import com.rohitkhandel.ggequalizer.ui.components.FrequencyResponseCanvas
import com.rohitkhandel.ggequalizer.ui.components.PermissionSetupSheet
import com.rohitkhandel.ggequalizer.ui.components.RotaryKnob
import java.util.Locale

private enum class GgDestination(val title: String, val glyph: String) {
    EQUALIZER("EQ", "≋"),
    TONE("TONE", "◉"),
    PRESETS("PRESETS", "▤"),
    SETTINGS("SETTINGS", "⚙")
}

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
    var destination by rememberSaveable { mutableStateOf(GgDestination.EQUALIZER) }
    var accentKey by rememberSaveable {
        mutableStateOf(preferences.getString("accent_key", "amber") ?: "amber")
    }
    var showPermissionSheet by remember { mutableStateOf(false) }
    val accent = if (accentKey == "cyan") CyanAccent else AmberAccent

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
                        error.message ?: "Could not import this preset",
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        TopAudiophileBar(
            activePlayer = state.activePlayerName,
            packageName = state.activePlayerPackage,
            sessionCount = state.activeSessionCount,
            accent = accent,
            enabled = state.masterEnabled,
            onMasterChanged = viewModel::setMasterEnabled
        )

        AnimatedContent(
            targetState = destination,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            transitionSpec = {
                (fadeIn(tweenDuration(190)) + slideInHorizontally { it / 12 }) togetherWith
                    (fadeOut(tweenDuration(130)) + slideOutHorizontally { -it / 16 })
            },
            label = "GG destination transition"
        ) { target ->
            when (target) {
                GgDestination.EQUALIZER -> EqualizerPage(
                    state = state,
                    accent = accent,
                    viewModel = viewModel,
                    onVisualizerToggle = onVisualizerToggle,
                    onImport = {
                        importLauncher.launch(arrayOf("text/plain", "application/json", "application/octet-stream"))
                    },
                    onExport = { exportLauncher.launch("GG Equalizer preset.json") }
                )
                GgDestination.TONE -> TonePage(
                    state = state,
                    accent = accent,
                    viewModel = viewModel
                )
                GgDestination.PRESETS -> PresetsPage(
                    state = state,
                    accent = accent,
                    onApply = viewModel::applyPreset,
                    onSave = { viewModel.saveCurrentPreset("Custom profile") },
                    onImport = {
                        importLauncher.launch(arrayOf("text/plain", "application/json", "application/octet-stream"))
                    },
                    onExport = { exportLauncher.launch("GG Equalizer preset.json") },
                    onBackup = { backupLauncher.launch("GG Equalizer backup.json") },
                    onRestore = {
                        restoreLauncher.launch(arrayOf("application/json", "text/plain"))
                    }
                )
                GgDestination.SETTINGS -> SettingsScreen(
                    state = state,
                    accentKey = accentKey,
                    onAccentChanged = { value ->
                        accentKey = value
                        preferences.edit().putString("accent_key", value).apply()
                    },
                    onMasterChanged = viewModel::setMasterEnabled,
                    onDvcChanged = viewModel::setDvcEnabled,
                    onVisualizerChanged = onVisualizerToggle,
                    onOpenAccessHelper = { showPermissionSheet = true },
                    onBackupPresets = { backupLauncher.launch("GG Equalizer backup.json") },
                    onRestorePresets = {
                        restoreLauncher.launch(arrayOf("application/json", "text/plain"))
                    }
                )
            }
        }

        BottomAudiophileNavigation(
            selected = destination,
            accent = accent,
            onSelect = { destination = it }
        )
    }

    if (showPermissionSheet) {
        PermissionSetupSheet(onDismiss = { showPermissionSheet = false })
    }
}

@Composable
private fun TopAudiophileBar(
    activePlayer: String,
    packageName: String?,
    sessionCount: Int,
    accent: Color,
    enabled: Boolean,
    onMasterChanged: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF202020), Color(0xFF151515))
                )
            )
            .padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "GG EQUALIZER",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.1.sp
                )
                Text(
                    "AUDIO ENGINE  /  32-BAND DSP",
                    color = accent,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp
                )
            }
            Text(
                if (enabled) "ACTIVE" else "BYPASS",
                color = if (enabled) accent else MutedText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .background(accent.copy(alpha = 0.10f), RoundedCornerShape(30.dp))
                    .padding(horizontal = 9.dp, vertical = 6.dp)
            )
            Switch(checked = enabled, onCheckedChange = onMasterChanged)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF121212))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accent, CircleShape)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 9.dp)
            ) {
                Text(activePlayer, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    packageName ?: "Waiting for a player audio session",
                    color = MutedText,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "$sessionCount SESS",
                color = accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun EqualizerPage(
    state: EqUiState,
    accent: Color,
    viewModel: EqViewModel,
    onVisualizerToggle: (Boolean) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    var multiSelect by rememberSaveable { mutableStateOf(false) }
    var selectedBands by rememberSaveable { mutableStateOf(setOf(state.selectedBandIndex)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("EQUALIZER CURVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                Text("32 parametric bands · overlapping response", color = MutedText, fontSize = 9.sp)
            }
            Text("FFT", color = MutedText, fontSize = 9.sp)
            Switch(checked = state.visualizerEnabled, onCheckedChange = onVisualizerToggle)
        }

        Card(
            modifier = Modifier.padding(horizontal = 14.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FrequencyResponseCanvas(
                    points = state.responsePoints,
                    bands = state.bands,
                    selectedBandIndex = state.selectedBandIndex,
                    onBandSelected = { index ->
                        viewModel.selectBand(index)
                        selectedBands = if (multiSelect) {
                            if (index in selectedBands) selectedBands - index else selectedBands + index
                        } else {
                            setOf(index)
                        }
                    },
                    onBandGainChanged = viewModel::setBandGain,
                    spectrumBars = if (state.visualizerEnabled) state.spectrumBars else emptyList(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("20", "100", "1k", "10k", "20k").forEach {
                        Text(it, color = MutedText, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SMOOTH BAND OVERLAP",
                        color = accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("MULTI", color = MutedText, fontSize = 9.sp)
                    Switch(checked = multiSelect, onCheckedChange = { multiSelect = it })
                }
                Text(
                    "Drag the response curve to adjust gain. Tap a point to select a band.",
                    color = MutedText,
                    fontSize = 9.sp
                )
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("32-BAND EDITOR", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Text(
                "B${state.selectedBandIndex + 1}  /  ${formatHz(state.bands.getOrNull(state.selectedBandIndex)?.frequencyHz ?: 1000f)}",
                color = accent,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            itemsIndexed(state.bands) { index, band ->
                val selected = index == state.selectedBandIndex || index in selectedBands
                Column(
                    modifier = Modifier
                        .width(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color(0xFF2B261D) else Surface)
                        .padding(vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        String.format(Locale.US, "%+.1f", band.gainDb),
                        color = if (selected) accent else Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Box(
                        modifier = Modifier.size(width = 48.dp, height = 132.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Slider(
                            value = band.gainDb,
                            onValueChange = { gain ->
                                viewModel.selectBand(index)
                                if (multiSelect && selectedBands.isNotEmpty()) {
                                    selectedBands.forEach { target ->
                                        val start = state.bands.getOrNull(target)?.gainDb ?: 0f
                                        if (target == index) viewModel.setBandGain(target, gain)
                                        else viewModel.setBandGain(target, (start + (gain - band.gainDb)).coerceIn(-12f, 12f))
                                    }
                                } else {
                                    viewModel.setBandGain(index, gain)
                                }
                            },
                            valueRange = -12f..12f,
                            steps = 47,
                            modifier = Modifier
                                .requiredSize(width = 130.dp, height = 40.dp)
                                .graphicsLayer { rotationZ = 270f },
                            colors = SliderDefaults.colors(
                                thumbColor = if (selected) accent else Color(0xFFD6D6D6),
                                activeTrackColor = accent,
                                inactiveTrackColor = Color(0xFF4A4A4A)
                            )
                        )
                    }
                    Text(formatHz(band.frequencyHz), color = MutedText, fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                    Text("B${index + 1}", color = if (selected) accent else MutedText, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onImport, modifier = Modifier.weight(1f)) { Text("AUTOEQ IMPORT", fontSize = 9.sp) }
            Button(onClick = onExport, modifier = Modifier.weight(1f)) { Text("EXPORT JSON", fontSize = 9.sp) }
        }
        Text(
            state.attachmentSummary,
            modifier = Modifier.padding(horizontal = 17.dp),
            color = MutedText,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun TonePage(
    state: EqUiState,
    accent: Color,
    viewModel: EqViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("TONE & DYNAMICS", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
        Text("Precision controls with double-tap reset", color = MutedText, fontSize = 10.sp)

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TONE SHAPING", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.3.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    RotaryKnob("Bass", state.bassGainDb, -12f..12f, 0f, String.format(Locale.US, "%+.1f dB", state.bassGainDb), viewModel::setBassGain, accent = accent)
                    RotaryKnob("Treble", state.trebleGainDb, -12f..12f, 0f, String.format(Locale.US, "%+.1f dB", state.trebleGainDb), viewModel::setTrebleGain, accent = accent)
                    RotaryKnob("Preamp", state.preampGainDb, -24f..12f, 0f, String.format(Locale.US, "%+.1f dB", state.preampGainDb), viewModel::setPreampGain, accent = accent)
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ANTI-OVERLOAD PROTECTION", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MAX VOLUME SAFETY LIMITER", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Soft-knee limiting plus automatic preamp headroom compensation", color = MutedText, fontSize = 9.sp)
                    }
                    Switch(checked = state.limiterEnabled, onCheckedChange = viewModel::setLimiterEnabled)
                }
                Text(
                    if (state.limiterEnabled) "SAFETY ACTIVE  ·  Boost peaks reserve digital headroom before the limiter." else "SAFETY BYPASSED  ·  High EQ boosts may cause clipping at high output levels.",
                    color = if (state.limiterEnabled) accent else Color(0xFFFF8A75),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RotaryKnob("Threshold", state.limiterThresholdDb, -12f..0f, -1f, String.format(Locale.US, "%.1f dB", state.limiterThresholdDb), viewModel::setLimiterThreshold, accent = Color(0xFFFFB547))
                    RotaryKnob("Release", state.limiterReleaseMs, 10f..300f, 80f, String.format(Locale.US, "%.0f ms", state.limiterReleaseMs), viewModel::setLimiterRelease, accent = Color(0xFFFFB547))
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("STEREO IMAGE", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RotaryKnob("Width", state.stereoWidthPercent, 0f..200f, 100f, String.format(Locale.US, "%.0f%%", state.stereoWidthPercent), viewModel::setStereoWidthPercent, accent = accent)
                    RotaryKnob("Balance", state.stereoBalance, -1f..1f, 0f, String.format(Locale.US, "%+.2f", state.stereoBalance), viewModel::setStereoBalance, accent = accent)
                }
                Text("0% width = mono  ·  100% = original  ·  200% = expanded sides", color = MutedText, fontSize = 9.sp)
            }
        }

        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Surface)) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DVC / MASTER", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black)
                SettingInlineSwitch("Dynamic volume compensation", state.dvcEnabled, viewModel::setDvcEnabled)
                SettingInlineSwitch("Master equalizer", state.masterEnabled, viewModel::setMasterEnabled)
                Text(
                    if (state.dvcSnapshot.clippingProtectionActive) "DVC headroom ${String.format(Locale.US, "%.1f dB", state.dvcSnapshot.preampCompensationDb)}" else "DVC monitors the media stream volume level.",
                    color = MutedText,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun SettingInlineSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PresetsPage(
    state: EqUiState,
    accent: Color,
    onApply: (com.rohitkhandel.ggequalizer.presets.PresetEntity) -> Unit,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("PRESET LIBRARY", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Black)
                Text("${state.presets.size} stored profiles", color = MutedText, fontSize = 10.sp)
            }
            Button(onClick = onSave) { Text("SAVE CURRENT", fontSize = 9.sp) }
        }
        state.presetMessage?.let { Text(it, color = accent, fontSize = 10.sp) }
        state.presets.forEach { preset ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (preset.presetId == state.selectedPresetId) Color(0xFF33291B) else Surface
                ),
                onClick = { onApply(preset) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(preset.presetName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(preset.description, color = MutedText, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (preset.isBuiltIn) "BUILT-IN" else "CUSTOM",
                        color = accent,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = onImport, modifier = Modifier.weight(1f)) { Text("IMPORT", fontSize = 9.sp) }
            Button(onClick = onExport, modifier = Modifier.weight(1f)) { Text("EXPORT", fontSize = 9.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Button(onClick = onBackup, modifier = Modifier.weight(1f)) { Text("BACKUP ALL", fontSize = 9.sp) }
            Button(onClick = onRestore, modifier = Modifier.weight(1f)) { Text("RESTORE ALL", fontSize = 9.sp) }
        }
    }
}

@Composable
private fun BottomAudiophileNavigation(
    selected: GgDestination,
    accent: Color,
    onSelect: (GgDestination) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(Color(0xFF1B1B1B))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GgDestination.entries.forEach { item ->
            val active = item == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) accent.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextButton(onClick = { onSelect(item) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(item.glyph, color = if (active) accent else MutedText, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(item.title, color = if (active) accent else MutedText, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
                    }
                }
            }
        }
    }
}

private fun formatHz(value: Float): String =
    if (value >= 1000f) String.format(Locale.US, "%.1fk", value / 1000f)
    else String.format(Locale.US, "%.0f", value)

private fun tweenDuration(duration: Int) = androidx.compose.animation.core.tween<androidx.compose.ui.graphics.TransformOrigin>(
    durationMillis = duration
)

private val Background = Color(0xFF121212)
private val Surface = Color(0xFF1E1E1E)
private val MutedText = Color(0xFF999999)
private val AmberAccent = Color(0xFFFFB547)
private val CyanAccent = Color(0xFF42D9F5)
