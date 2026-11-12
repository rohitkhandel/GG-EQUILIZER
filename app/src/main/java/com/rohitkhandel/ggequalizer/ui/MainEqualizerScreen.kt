package com.rohitkhandel.ggequalizer.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohitkhandel.ggequalizer.ui.components.FrequencyResponseCanvas
import com.rohitkhandel.ggequalizer.ui.components.RotaryKnob
import com.rohitkhandel.ggequalizer.ui.components.PermissionSetupSheet
import java.util.Locale
import kotlin.math.log10

private val ScreenBackground = Color(0xFF090C11)
private val PanelBackground = Color(0xFF111821)
private val Accent = Color(0xFF42D9F5)
private val MutedText = Color(0xFF8292A6)
private val MainText = Color(0xFFEAF3FA)

@Composable
fun MainEqualizerScreen(
    viewModel: EqViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPermissionSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBackground)
            .padding(horizontal = 14.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showPermissionSheet = true }) {
                Text("AUDIO PERMISSIONS", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (showPermissionSheet) {
            PermissionSetupSheet(onDismiss = { showPermissionSheet = false })
        }

        HeaderPanel(
            playerName = state.activePlayerName,
            packageName = state.activePlayerPackage,
            sessionCount = state.activeSessionCount,
            dvcEnabled = state.dvcEnabled,
            masterEnabled = state.masterEnabled,
            onDvcChanged = viewModel::setDvcEnabled,
            onMasterChanged = viewModel::setMasterEnabled
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = PanelBackground),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "FREQUENCY RESPONSE",
                        color = MainText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Text(
                        text = "20 Hz — 20 kHz",
                        color = MutedText,
                        fontSize = 10.sp
                    )
                }

                FrequencyResponseCanvas(
                    points = state.responsePoints,
                    bands = state.bands,
                    selectedBandIndex = state.selectedBandIndex,
                    onBandSelected = viewModel::selectBand
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    listOf("20", "100", "1k", "10k", "20k").forEach { label ->
                        Text(label, color = MutedText, fontSize = 9.sp)
                    }
                }
            }
        }

        BandEditorPanel(
            state = state,
            onSelectBand = viewModel::selectBand,
            onGainChanged = viewModel::setBandGain,
            onBandEnabled = viewModel::setBandEnabled,
            onQChanged = viewModel::setBandQ,
            onFilterTypeChanged = viewModel::setBandFilterType
        )

        ToneAndDynamicsPanel(
            state = state,
            onBassChanged = viewModel::setBassGain,
            onTrebleChanged = viewModel::setTrebleGain,
            onPreampChanged = viewModel::setPreampGain,
            onLimiterThresholdChanged = viewModel::setLimiterThreshold,
            onLimiterReleaseChanged = viewModel::setLimiterRelease,
            onLimiterEnabled = viewModel::setLimiterEnabled
        )

        Text(
            text = state.attachmentSummary,
            color = MutedText,
            fontSize = 9.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, bottom = 10.dp)
        )
    }
}

@Composable
private fun HeaderPanel(
    playerName: String,
    packageName: String?,
    sessionCount: Int,
    dvcEnabled: Boolean,
    masterEnabled: Boolean,
    onDvcChanged: (Boolean) -> Unit,
    onMasterChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = PanelBackground)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GG  EQUALIZER",
                        color = MainText,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "NATIVE AUDIO CONTROL",
                        color = Accent,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
                Text(
                    text = "32 BAND",
                    color = Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Color(0xFF17313A), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0C121A), RoundedCornerShape(14.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playerName,
                        color = MainText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = packageName ?: "Open a player with an exposed audio session",
                        color = MutedText,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = "$sessionCount SESSION",
                    color = Accent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToggleBlock(
                    title = "DVC HEADROOM",
                    subtitle = "Auto preamp reserve",
                    checked = dvcEnabled,
                    onCheckedChange = onDvcChanged
                )
                ToggleBlock(
                    title = "MASTER EQ",
                    subtitle = if (masterEnabled) "Processing active" else "Bypassed",
                    checked = masterEnabled,
                    onCheckedChange = onMasterChanged
                )
            }
        }
    }
}

@Composable
private fun ToggleBlock(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.padding(end = 6.dp)) {
            Text(title, color = MainText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MutedText, fontSize = 8.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF06212A),
                checkedTrackColor = Accent,
                uncheckedThumbColor = Color(0xFF9AA8B7),
                uncheckedTrackColor = Color(0xFF354252)
            )
        )
    }
}

@Composable
private fun BandEditorPanel(
    state: EqUiState,
    onSelectBand: (Int) -> Unit,
    onGainChanged: (Int, Float) -> Unit,
    onBandEnabled: (Int, Boolean) -> Unit,
    onQChanged: (Int, Float) -> Unit,
    onFilterTypeChanged: (Int, Int) -> Unit
) {
    val selected = state.bands.getOrNull(state.selectedBandIndex)

    Card(
        colors = CardDefaults.cardColors(containerColor = PanelBackground),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "PARAMETRIC BANDS",
                        color = MainText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "Drag a band slider · tap the curve to select",
                        color = MutedText,
                        fontSize = 9.sp
                    )
                }
                Text(
                    text = selected?.let { "B${state.selectedBandIndex + 1}  ${formatFrequency(it.frequencyHz)}" }
                        ?: "—",
                    color = Accent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(state.bands) { index, band ->
                    Column(
                        modifier = Modifier
                            .width(48.dp)
                            .background(
                                if (index == state.selectedBandIndex) Color(0xFF172A35)
                                else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = String.format(Locale.US, "%+.1f", band.gainDb),
                            color = if (index == state.selectedBandIndex) Accent else MainText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier.size(width = 48.dp, height = 130.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Slider(
                                value = band.gainDb,
                                onValueChange = {
                                    onSelectBand(index)
                                    onGainChanged(index, it)
                                },
                                valueRange = -12.0f..12.0f,
                                steps = 47,
                                modifier = Modifier
                                    .requiredSize(width = 126.dp, height = 42.dp)
                                    .graphicsLayer { rotationZ = 270f },
                                colors = SliderDefaults.colors(
                                    thumbColor = if (index == state.selectedBandIndex) Accent else Color(0xFFB5C5D5),
                                    activeTrackColor = Accent,
                                    inactiveTrackColor = Color(0xFF354252)
                                )
                            )
                        }
                        Text(
                            text = formatFrequency(band.frequencyHz),
                            color = MutedText,
                            fontSize = 8.sp,
                            maxLines = 1
                        )
                        Text(
                            text = "B${index + 1}",
                            color = if (index == state.selectedBandIndex) Accent else Color(0xFF6E7D90),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (selected != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Selected band · Q ${String.format(Locale.US, "%.2f", selected.qFactor)}",
                        color = MutedText,
                        fontSize = 9.sp
                    )
                    Switch(
                        checked = selected.enabled,
                        onCheckedChange = { onBandEnabled(state.selectedBandIndex, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF06212A),
                            checkedTrackColor = Accent
                        )
                    )
                }
                Slider(
                    value = selected.qFactor,
                    onValueChange = { onQChanged(state.selectedBandIndex, it) },
                    valueRange = 0.1f..10.0f,
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color(0xFF354252)
                    )
                )
            }
        }
    }
}

@Composable
private fun ToneAndDynamicsPanel(
    state: EqUiState,
    onBassChanged: (Float) -> Unit,
    onTrebleChanged: (Float) -> Unit,
    onPreampChanged: (Float) -> Unit,
    onLimiterThresholdChanged: (Float) -> Unit,
    onLimiterReleaseChanged: (Float) -> Unit,
    onLimiterEnabled: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PanelBackground),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "TONE  /  PREAMP  /  LIMITER",
                    color = MainText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LIMIT", color = MutedText, fontSize = 9.sp)
                    Switch(
                        checked = state.limiterEnabled,
                        onCheckedChange = onLimiterEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF06212A),
                            checkedTrackColor = Accent
                        )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                RotaryKnob(
                    label = "Bass",
                    value = state.bassGainDb,
                    valueRange = -12f..12f,
                    defaultValue = 0f,
                    valueText = String.format(Locale.US, "%+.1f dB", state.bassGainDb),
                    onValueChange = onBassChanged
                )
                RotaryKnob(
                    label = "Treble",
                    value = state.trebleGainDb,
                    valueRange = -12f..12f,
                    defaultValue = 0f,
                    valueText = String.format(Locale.US, "%+.1f dB", state.trebleGainDb),
                    onValueChange = onTrebleChanged
                )
                RotaryKnob(
                    label = "Preamp",
                    value = state.preampGainDb,
                    valueRange = -24f..12f,
                    defaultValue = 0f,
                    valueText = String.format(Locale.US, "%+.1f dB", state.preampGainDb),
                    onValueChange = onPreampChanged
                )
                RotaryKnob(
                    label = "Threshold",
                    value = state.limiterThresholdDb,
                    valueRange = -12f..0f,
                    defaultValue = -1f,
                    valueText = String.format(Locale.US, "%.1f dB", state.limiterThresholdDb),
                    onValueChange = onLimiterThresholdChanged,
                    accent = Color(0xFFFFB454)
                )
                RotaryKnob(
                    label = "Release",
                    value = state.limiterReleaseMs,
                    valueRange = 10f..300f,
                    defaultValue = 80f,
                    valueText = String.format(Locale.US, "%.0f ms", state.limiterReleaseMs),
                    onValueChange = onLimiterReleaseChanged,
                    accent = Color(0xFFFFB454)
                )
            }

            Text(
                text = if (state.dvcSnapshot.clippingProtectionActive) {
                    "HEADROOM RESERVE  ${String.format(Locale.US, "%.1f dB", state.dvcSnapshot.preampCompensationDb)}"
                } else {
                    "DVC monitors media volume and reserves preamp headroom when needed"
                },
                color = if (state.dvcSnapshot.clippingProtectionActive) Accent else MutedText,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun formatFrequency(frequencyHz: Float): String =
    if (frequencyHz >= 1000f) {
        String.format(Locale.US, "%.1fk", frequencyHz / 1000f)
    } else {
        String.format(Locale.US, "%.0f", frequencyHz)
    }
