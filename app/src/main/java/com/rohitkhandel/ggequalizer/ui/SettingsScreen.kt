package com.rohitkhandel.ggequalizer.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge

@Composable
fun SettingsScreen(
    state: EqUiState,
    accentKey: String,
    onAccentChanged: (String) -> Unit,
    onMasterChanged: (Boolean) -> Unit,
    onDvcChanged: (Boolean) -> Unit,
    onVisualizerChanged: (Boolean) -> Unit,
    onVisualizerRefreshRateChanged: (Int) -> Unit,
    onOpenAccessHelper: () -> Unit,
    onBackupPresets: () -> Unit,
    onRestorePresets: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
    var showPlayerName by rememberPreference(prefs, PREF_SHOW_PLAYER, true)
    var showProcessingStatus by rememberPreference(prefs, PREF_SHOW_PROCESSING, true)
    val accent = if (accentKey == "cyan") Cyan else Amber

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SETTINGS", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black)
        Text("Fine tune the audio engine and app behaviour", color = Muted, fontSize = 11.sp)

        SettingsCard(title = "AUDIO ENGINE", accent = accent) {
            SettingSwitch(
                title = "Master equalizer",
                description = "Enable or bypass the active audio-session effects",
                checked = state.masterEnabled,
                onCheckedChange = onMasterChanged
            )
            HorizontalDivider(color = DividerColor)
            SettingSwitch(
                title = "DVC headroom",
                description = "Monitor media volume and reserve digital headroom",
                checked = state.dvcEnabled,
                onCheckedChange = onDvcChanged
            )
            HorizontalDivider(color = DividerColor)
            SettingValue(
                title = "Native DSP core",
                value = runCatching { NativeDspBridge.nativeVersion() }.getOrDefault("Native core unavailable")
            )
            SettingValue(
                title = "Native DSP parameters",
                value = "32-band parametric engine  ·  48 kHz default"
            )
            SettingValue(
                title = "Detected sessions",
                value = "${state.activeSessionCount}  ·  ${state.attachmentSummary}"
            )
            SettingValue(
                title = "Output buffer",
                value = "Managed by Android and the active player"
            )
            Text(
                "The native C++ engine exposes a PCM-block processing API; this app does not yet own a continuous playback PCM stream. Third-party player audio is modified only through compatible Android audio sessions; a normal app cannot replace the system-wide output mixer.",
                color = Muted,
                fontSize = 10.sp
            )
            Text(
                "Android does not expose a safe global buffer-size control for these attached audio effects, so GG does not show a non-functional buffer selector.",
                color = Muted,
                fontSize = 10.sp
            )
        }

        SettingsCard(title = "VISUALIZER", accent = accent) {
            SettingSwitch(
                title = "Live spectrum",
                description = "Show 64 logarithmic spectrum bars behind the response curve",
                checked = state.visualizerEnabled,
                onCheckedChange = onVisualizerChanged
            )
            Text("Refresh rate", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60).forEach { rate ->
                    AccentChoice(
                        label = "$rate Hz",
                        color = accent,
                        selected = state.visualizerRefreshRateHz == rate,
                        onClick = { onVisualizerRefreshRateChanged(rate) }
                    )
                }
            }
            Text(
                "Requested FFT refresh rate. Android and the device audio effect may cap the actual callback rate.",
                color = Muted,
                fontSize = 10.sp
            )
            Text(
                "Android may limit Visualizer capture or output-mix access on some devices.",
                color = Muted,
                fontSize = 10.sp
            )
        }

        SettingsCard(title = "APPEARANCE", accent = accent) {
            Text("Accent colour", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentChoice(
                    label = "AMBER",
                    color = Amber,
                    selected = accentKey == "amber",
                    onClick = { onAccentChanged("amber") }
                )
                AccentChoice(
                    label = "CYAN",
                    color = Cyan,
                    selected = accentKey == "cyan",
                    onClick = { onAccentChanged("cyan") }
                )
            }
            Text(
                "Dark surfaces use #121212 and #1E1E1E. Numerical readouts use tabular mono styling.",
                color = Muted,
                fontSize = 10.sp
            )
        }

        SettingsCard(title = "NOTIFICATION PLAYER", accent = accent) {
            SettingSwitch(
                title = "Show active player name",
                description = "Display the detected player in the persistent monitor notification",
                checked = showPlayerName,
                onCheckedChange = {
                    showPlayerName = it
                    prefs.edit().putBoolean(PREF_SHOW_PLAYER, it).apply()
                }
            )
            HorizontalDivider(color = DividerColor)
            SettingSwitch(
                title = "Show engine status",
                description = "Display DVC and effect-session status in the notification",
                checked = showProcessingStatus,
                onCheckedChange = {
                    showProcessingStatus = it
                    prefs.edit().putBoolean(PREF_SHOW_PROCESSING, it).apply()
                }
            )
        }

        SettingsCard(title = "BACKUP & RESTORE", accent = accent) {
            Text(
                "Export all built-in and custom preset records, including band frequencies, gains, Q, filters and stereo settings.",
                color = Muted,
                fontSize = 10.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBackupPresets, modifier = Modifier.weight(1f)) {
                    Text("BACK UP")
                }
                Button(onClick = onRestorePresets, modifier = Modifier.weight(1f)) {
                    Text("RESTORE")
                }
            }
        }

        SettingsCard(title = "PERMISSIONS & DEVICE ACCESS", accent = accent) {
            Text(
                "Review Notification Listener, Usage Access, battery optimisation and protected DUMP status. Android may restrict some permissions to system apps.",
                color = Muted,
                fontSize = 10.sp
            )
            Button(onClick = onOpenAccessHelper, modifier = Modifier.fillMaxWidth()) {
                Text("ADB / SHIZUKU PERMISSION HELPER")
            }
        }

        SettingsCard(title = "ABOUT", accent = accent) {
            SettingValue(title = "Application", value = "GG Equalizer")
            SettingValue(title = "Interface", value = "GG Audiophile Dark")
            SettingValue(title = "Build", value = "Android · Jetpack Compose")
        }
        Spacer(Modifier.height(10.dp))
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Text(title, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.4.sp)
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Muted, fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingValue(title: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = Muted, fontSize = 9.sp)
        Text(value, color = Color(0xFFE5E5E5), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AccentChoice(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .background(
                if (selected) color.copy(alpha = 0.14f) else Color(0xFF2A2A2A),
                RoundedCornerShape(30.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Spacer(
            Modifier
                .size(9.dp)
                .background(color, CircleShape)
        )
        Text(label, color = if (selected) color else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun rememberPreference(
    preferences: SharedPreferences,
    key: String,
    defaultValue: Boolean
): androidx.compose.runtime.MutableState<Boolean> {
    return remember(key) {
        mutableStateOf(preferences.getBoolean(key, defaultValue))
    }
}

private const val PREFERENCES_NAME = "gg_equalizer_ui_preferences"
private const val PREF_SHOW_PLAYER = "notification_show_player"
private const val PREF_SHOW_PROCESSING = "notification_show_processing"
private val Background = Color(0xFF121212)
private val Surface = Color(0xFF1E1E1E)
private val DividerColor = Color(0xFF343434)
private val Muted = Color(0xFF9C9C9C)
private val Amber = Color(0xFFFFB547)
private val Cyan = Color(0xFF42D9F5)
