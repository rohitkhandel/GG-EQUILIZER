package com.rohitkhandel.ggaudio.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohitkhandel.ggaudio.audio.EqualizerBand
import com.rohitkhandel.ggaudio.audio.EqualizerDefaults
import com.rohitkhandel.ggaudio.ui.theme.*
import com.rohitkhandel.ggaudio.ui.theme.Surface as CardSurface

private enum class AppTab(val label: String, val symbol: String) {
    Equalizer("Equalizer", "≋"), Effects("Effects", "✦"), Presets("Presets", "▶"), Settings("Settings", "⚙")
}

@Composable
fun GgAudioApp() {
    var selectedTab by remember { mutableStateOf(AppTab.Equalizer) }
    var bands by remember { mutableStateOf(EqualizerDefaults.balanced()) }
    var presetName by remember { mutableStateOf("Balanced") }
    var demoEnabled by remember { mutableStateOf(false) }
    var preamp by remember { mutableFloatStateOf(0f) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Background,
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().background(CardSurface).padding(horizontal = 4.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppTab.entries.forEach { tab ->
                    TextButton(onClick = { selectedTab = tab }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(tab.symbol, color = if (selectedTab == tab) Accent else TextMuted, fontSize = 17.sp)
                            Text(tab.label, color = if (selectedTab == tab) Accent else TextMuted, fontSize = 8.sp)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().background(Background).padding(padding)) {
            AppHeader(demoEnabled, onDemoToggle = { demoEnabled = it })
            when (selectedTab) {
                AppTab.Equalizer -> EqualizerScreen(
                    bands = bands,
                    presetName = presetName,
                    preamp = preamp,
                    onPreampChange = { preamp = it },
                    onBandChange = { index, value ->
                        bands = bands.mapIndexed { i, band -> if (i == index) band.copy(gainDb = value) else band }
                        presetName = "Custom"
                    },
                    onPresetChange = { name, updated ->
                        presetName = name
                        bands = updated
                    }
                )
                AppTab.Effects -> PlaceholderScreen("Sound effects", "Bass enhancement and stereo controls will be connected in the audio-engine phase.")
                AppTab.Presets -> PlaceholderScreen("Presets", "Preset saving, import and backup will be added after the equalizer state model is tested.")
                AppTab.Settings -> PlaceholderScreen("Settings", "Device compatibility, permissions and audio diagnostics will appear here.")
            }
        }
    }
}

@Composable
private fun AppHeader(demoEnabled: Boolean, onDemoToggle: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(40.dp), shape = RoundedCornerShape(12.dp), color = Color(0xFF2A211B), border = BorderStroke(1.dp, Color(0xFF59412D))) {
                Box(contentAlignment = Alignment.Center) { Text("GG", color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("GG AUDIO", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.2.sp)
                Text("PRECISION SOUND ENGINE", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.1.sp)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(if (demoEnabled) "UI DEMO ON" else "DSP STANDBY", color = if (demoEnabled) Accent else TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text("ENGINE NOT CONNECTED", color = TextMuted, fontSize = 7.sp)
            }
        }
        Surface(Modifier.fillMaxWidth().padding(top = 16.dp), color = CardSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
            Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("♫", color = Accent, fontSize = 23.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("System audio", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Global output · capability check pending", color = TextMuted, fontSize = 9.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("NOT LINKED", color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("UI DEMO SWITCH", color = TextMuted, fontSize = 8.sp)
            Spacer(Modifier.width(7.dp))
            Switch(checked = demoEnabled, onCheckedChange = onDemoToggle)
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("GG AUDIO  /  CONTROL", color = Accent, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text(title, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
        Text(subtitle, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
        Surface(Modifier.fillMaxWidth().padding(top = 22.dp), color = Surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
            Text("This section is intentionally staged. It will be wired to real app functionality before being marked complete.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
        }
    }
}
