package com.rohitkhandel.ggequalizer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge
import java.util.Locale

private val HubBlack = Color(0xFF050608)
private val HubPanel = Color(0xFF11151B)
private val HubPanelRaised = Color(0xFF191E25)
private val HubBorder = Color(0xFF2B333D)
private val HubText = Color(0xFFF1F4F7)
private val HubMuted = Color(0xFF8B96A2)
private val HubGreen = Color(0xFF55D99A)

private enum class SettingsRoute(val title: String, val subtitle: String) {
    OVERVIEW("Settings", "Configure your audio experience"),
    EQUALIZER("Equalizer", "Processing, players and EQ behaviour"),
    LOOK_FEEL("Look & Feel", "Appearance and value display"),
    VISUALIZATION("Visualization", "Spectrum and response display"),
    UTILS("Utilities", "Import, export and audio access"),
    ABOUT("About", "GG Equalizer and audio engine"),
    KNOWN_PLAYERS("Known Players", "Choose which detected players GG Equalizer can process"),
    DVC("Direct Volume Control", "Volume-aware headroom compensation"),
    GRAPHIC_BANDS("Graphic Equalizer Bands", "Choose the active band layout"),
    TONE("Tone", "Bass and treble filter controls"),
    VALUES("Equalizer Values", "Choose how gain values are displayed"),
    LIMITER("Limiter", "Peak protection and release behaviour")
}

private data class KnownPlayerOption(val name: String, val packageName: String, val detail: String)

private val knownPlayerOptions = listOf(
    KnownPlayerOption("Spotify", "com.spotify.music", "Music streaming"),
    KnownPlayerOption("YouTube Music", "com.google.android.apps.youtube.music", "Music streaming"),
    KnownPlayerOption("YouTube", "com.google.android.youtube", "Video playback"),
    KnownPlayerOption("VLC", "org.videolan.vlc", "Local media player"),
    KnownPlayerOption("Poweramp", "com.maxmpz.audioplayer", "Local music player"),
    KnownPlayerOption("Musicolet", "in.krosbits.musicolet", "Offline music player"),
    KnownPlayerOption("Amazon Music", "com.amazon.mp3", "Music streaming"),
    KnownPlayerOption("JioSaavn", "com.jio.media.jiobeats", "Music streaming"),
    KnownPlayerOption("Wynk Music", "com.bsbportal.music", "Music streaming"),
    KnownPlayerOption("BGMI", "com.pubg.imobile", "Game audio"),
    KnownPlayerOption("PUBG Mobile", "com.tencent.ig", "Game audio")
)

@Composable
fun SettingsHub(
    state: EqUiState,
    accent: Color,
    onMasterEnabled: (Boolean) -> Unit,
    onAccent: (String) -> Unit,
    onBandCount: (Int) -> Unit,
    onShowCurve: (Boolean) -> Unit,
    onVisualizerStyle: (String) -> Unit,
    onVisualizerRefreshRate: (Int) -> Unit,
    onSmooth: (Boolean) -> Unit,
    onOverlapEnabled: (Boolean) -> Unit,
    onOverlapPercent: (Float) -> Unit,
    onAvoidOverloading: (Boolean) -> Unit,
    onLimiterEnabled: (Boolean) -> Unit,
    onLimiterThreshold: (Float) -> Unit,
    onLimiterRelease: (Float) -> Unit,
    onDvc: (Boolean) -> Unit,
    onTracking: (Boolean) -> Unit,
    onKnownPlayerEnabled: (String, Boolean) -> Unit,
    onIsKnownPlayerEnabled: (String) -> Boolean,
    equalizerValueMode: String,
    toneValueMode: String,
    onEqualizerValueMode: (String) -> Unit,
    onToneValueMode: (String) -> Unit,
    onBassFrequency: (Float) -> Unit,
    onBassQ: (Float) -> Unit,
    onTrebleFrequency: (Float) -> Unit,
    onTrebleQ: (Float) -> Unit,
    onOpenAccess: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    var route by rememberSaveable { mutableStateOf(SettingsRoute.OVERVIEW) }
    var forward by rememberSaveable { mutableStateOf(true) }

    fun navigate(next: SettingsRoute) {
        forward = true
        route = next
    }

    fun back() {
        forward = false
        route = when (route) {
            SettingsRoute.KNOWN_PLAYERS,
            SettingsRoute.DVC,
            SettingsRoute.GRAPHIC_BANDS,
            SettingsRoute.TONE,
            SettingsRoute.VALUES,
            SettingsRoute.LIMITER -> SettingsRoute.EQUALIZER
            SettingsRoute.OVERVIEW -> SettingsRoute.OVERVIEW
            else -> SettingsRoute.OVERVIEW
        }
    }

    Column(Modifier.fillMaxSize().background(HubBlack)) {
        SettingsHeader(route, accent, ::back)
        AnimatedContent(
            targetState = route,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val direction = if (forward) 1 else -1
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth * direction },
                    animationSpec = tween(230)
                ) + fadeIn(tween(190))) togetherWith
                    (slideOutHorizontally(
                        targetOffsetX = { fullWidth -> -fullWidth * direction },
                        animationSpec = tween(190)
                    ) + fadeOut(tween(150)))
            },
            label = "settings navigation"
        ) { page ->
            when (page) {
                SettingsRoute.OVERVIEW -> SettingsOverview(state, accent, ::navigate)
                SettingsRoute.EQUALIZER -> EqualizerSettingsPage(
                    state, accent, onMasterEnabled, onSmooth, onOverlapEnabled,
                    onOverlapPercent, onAvoidOverloading, onLimiterEnabled, onTracking, ::navigate
                )
                SettingsRoute.LOOK_FEEL -> LookAndFeelPage(
                    state, accent, equalizerValueMode, toneValueMode,
                    onAccent, onShowCurve, onEqualizerValueMode, onToneValueMode
                )
                SettingsRoute.VISUALIZATION -> VisualizationPage(
                    state, accent, onVisualizerStyle, onVisualizerRefreshRate, onShowCurve
                )
                SettingsRoute.UTILS -> UtilitiesPage(
                    accent, state, onOpenAccess, onCopyDiagnostics,
                    onImport, onExport, onBackup, onRestore
                )
                SettingsRoute.ABOUT -> AboutPage(accent)
                SettingsRoute.KNOWN_PLAYERS -> KnownPlayersPage(
                    accent, onIsKnownPlayerEnabled, onKnownPlayerEnabled
                )
                SettingsRoute.DVC -> DvcPage(state, accent, onDvc)
                SettingsRoute.GRAPHIC_BANDS -> GraphicBandsPage(state, accent, onBandCount)
                SettingsRoute.TONE -> ToneSettingsPage(
                    state, accent, onBassFrequency, onBassQ, onTrebleFrequency, onTrebleQ
                )
                SettingsRoute.VALUES -> ValueFormatPage(
                    accent, equalizerValueMode, toneValueMode, onEqualizerValueMode, onToneValueMode
                )
                SettingsRoute.LIMITER -> LimiterPage(
                    state, accent, onLimiterEnabled, onLimiterThreshold, onLimiterRelease
                )
            }
        }
    }
}

@Composable
private fun SettingsHeader(route: SettingsRoute, accent: Color, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (route != SettingsRoute.OVERVIEW) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(HubPanelRaised)
                    .border(1.dp, HubBorder, CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Text("‹", color = HubText, fontSize = 27.sp, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.width(11.dp))
        } else {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Text("⚙", color = accent, fontSize = 18.sp) }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(route.title.uppercase(Locale.US), color = HubText, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
            Text(route.subtitle, color = HubMuted, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (route == SettingsRoute.OVERVIEW) accent else HubGreen))
    }
}

@Composable
private fun SettingsOverview(state: EqUiState, accent: Color, onNavigate: (SettingsRoute) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(HubPanel)
                    .border(1.dp, HubBorder, RoundedCornerShape(15.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (state.masterEnabled) "AUDIO PROCESSING ON" else "AUDIO PROCESSING OFF", color = if (state.masterEnabled) HubGreen else HubMuted, fontSize = 8.sp, fontWeight = FontWeight.Black)
                    Text(state.activePlayerName, color = HubText, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${state.activeSessionCount} audio sessions · ${state.attachmentSummary}", color = HubMuted, fontSize = 7.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(35.dp).clip(CircleShape).background(if (state.masterEnabled) accent.copy(alpha = 0.16f) else HubPanelRaised), contentAlignment = Alignment.Center) {
                    Text(if (state.masterEnabled) "✓" else "Ⅱ", color = if (state.masterEnabled) accent else HubMuted, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        item { SettingsGroupLabel("MAIN SETTINGS", accent) }
        item { SettingsNavigationRow("Equalizer", "Audio processing, players, DVC and EQ controls", "≋", accent) { onNavigate(SettingsRoute.EQUALIZER) } }
        item { SettingsNavigationRow("Look & Feel", "Colours, value format and interface preferences", "◉", accent) { onNavigate(SettingsRoute.LOOK_FEEL) } }
        item { SettingsNavigationRow("Visualization", "Spectrum style, refresh rate and response curve", "▥", accent) { onNavigate(SettingsRoute.VISUALIZATION) } }
        item { SettingsNavigationRow("Utilities", "Permissions, diagnostics, import and backups", "⚒", accent) { onNavigate(SettingsRoute.UTILS) } }
        item { SettingsNavigationRow("About", "Version and audio-engine information", "ⓘ", accent) { onNavigate(SettingsRoute.ABOUT) } }
        item {
            Text(
                "Settings are grouped into separate pages. Changes apply to the active UI or DSP controls immediately.",
                color = HubMuted, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun EqualizerSettingsPage(
    state: EqUiState,
    accent: Color,
    onMasterEnabled: (Boolean) -> Unit,
    onSmooth: (Boolean) -> Unit,
    onOverlapEnabled: (Boolean) -> Unit,
    onOverlapPercent: (Float) -> Unit,
    onAvoidOverloading: (Boolean) -> Unit,
    onLimiterEnabled: (Boolean) -> Unit,
    onTracking: (Boolean) -> Unit,
    onNavigate: (SettingsRoute) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item { SettingsSwitchRow("Enable Audio Processing", "Enable or bypass effects attached to supported player sessions", state.masterEnabled, accent, onMasterEnabled) }
        item {
            SettingsInfoCard("AUDIO INFO", listOf(
                "Active player" to state.activePlayerName,
                "Session ID" to (state.activeSessionId?.toString() ?: "Not attached"),
                "Effects" to state.attachmentSummary,
                "Player sessions" to state.activeSessionCount.toString()
            ))
        }
        item { SettingsGroupLabel("PLAYBACK", accent) }
        item { SettingsNavigationRow("Known Players", "Enable or disable processing for supported players", "♫", accent) { onNavigate(SettingsRoute.KNOWN_PLAYERS) } }
        item { SettingsNavigationRow("Direct Volume Control", "Volume monitoring and headroom compensation", "◖", accent) { onNavigate(SettingsRoute.DVC) } }
        item { SettingsSwitchRow("Advanced Player Tracking", "Use playback callbacks and session tracking to discover players", state.activePlayerTracking, accent, onTracking) }
        item { SettingsGroupLabel("EQUALIZER", accent) }
        item { SettingsNavigationRow("Graphic Equalizer Bands", "10-band, 15-band or 32-band parametric mode", "▤", accent) { onNavigate(SettingsRoute.GRAPHIC_BANDS) } }
        item { SettingsNavigationRow("Tone", "Bass and treble frequency / Q controls", "◉", accent) { onNavigate(SettingsRoute.TONE) } }
        item { SettingsNavigationRow("Equalizer Values", "Show numeric values as hidden, dB or percent", "％", accent) { onNavigate(SettingsRoute.VALUES) } }
        item { SettingsNavigationRow("Limiter", "Threshold, release and peak protection", "▰", accent) { onNavigate(SettingsRoute.LIMITER) } }
        item { SettingsGroupLabel("SIGNAL SHAPING", accent) }
        item { SettingsSwitchRow("Smooth Gain Changes", "Smooth band interactions and response updates", state.smoothGains, accent, onSmooth) }
        item { SettingsSwitchRow("Bands Overlap", "Blend adjacent bands for a wider response", state.bandsOverlapEnabled, accent, onOverlapEnabled) }
        item { SettingsSliderRow("Overlap Amount", "${state.bandsOverlapPercent.toInt()}%", state.bandsOverlapPercent, 0f..100f, accent, onOverlapPercent) }
        item { SettingsSwitchRow("Avoid Overloading", "Reserve headroom for positive EQ boosts", state.avoidOverloading, accent, onAvoidOverloading) }
        item { SettingsSwitchRow("Session Limiter", "Apply peak protection in the external session effect", state.limiterEnabled, accent, onLimiterEnabled) }
    }
}

@Composable
private fun LookAndFeelPage(
    state: EqUiState,
    accent: Color,
    equalizerValueMode: String,
    toneValueMode: String,
    onAccent: (String) -> Unit,
    onShowCurve: (Boolean) -> Unit,
    onEqualizerValueMode: (String) -> Unit,
    onToneValueMode: (String) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SettingsGroupLabel("ACCENT COLOUR", accent) }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(HubPanel).border(1.dp, HubBorder, RoundedCornerShape(13.dp)).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("cyan" to Color(0xFF42D9F5), "amber" to Color(0xFFFFB547), "purple" to Color(0xFFB58CFF), "matrix" to Color(0xFF4BE38A)).forEach { (key, tint) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (state.accentKey == key) tint.copy(alpha = 0.14f) else HubPanelRaised)
                            .clickable { onAccent(key) }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(tint))
                        Text(key.uppercase(Locale.US), color = if (state.accentKey == key) tint else HubMuted, fontSize = 7.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        item { SettingsGroupLabel("VALUE PRESENTATION", accent) }
        item { SettingsSegmentRow("Equalizer values", equalizerValueMode, listOf("HIDDEN", "DB", "PERCENT"), accent, onEqualizerValueMode) }
        item { SettingsSegmentRow("Tone values", toneValueMode, listOf("DB", "PERCENT"), accent, onToneValueMode) }
        item { SettingsSwitchRow("Response Curve", "Draw the combined frequency response on the EQ panel", state.showCurveOnSliders, accent, onShowCurve) }
    }
}

@Composable
private fun VisualizationPage(
    state: EqUiState,
    accent: Color,
    onStyle: (String) -> Unit,
    onRefreshRate: (Int) -> Unit,
    onShowCurve: (Boolean) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SettingsSegmentRow("Visualizer style", state.visualizerStyle, listOf("BARS", "WAVE", "OFF"), accent, onStyle) }
        item { SettingsSegmentRow("Refresh rate", state.visualizerRefreshRateHz.toString(), listOf("15", "30", "60"), accent) { onRefreshRate(it.toInt()) } }
        item { SettingsSwitchRow("Frequency Response Curve", "Overlay the response curve on the EQ editor", state.showCurveOnSliders, accent, onShowCurve) }
        item { SettingsInfoCard("VISUALIZER STATUS", listOf("Capture" to if (state.visualizerEnabled) "Running" else "Stopped", "Audio session" to (state.activeSessionId?.toString() ?: "Unavailable"), "Refresh" to "${state.visualizerRefreshRateHz} Hz")) }
    }
}

@Composable
private fun KnownPlayersPage(
    accent: Color,
    enabled: (String) -> Boolean,
    onChange: (String, Boolean) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                "Disabled players are not given an EQ effect when Android reports their audio session. Players must expose a usable session ID and permit session effects.",
                color = HubMuted, fontSize = 8.sp, modifier = Modifier.padding(horizontal = 3.dp, vertical = 4.dp)
            )
        }
        items(knownPlayerOptions, key = { it.packageName }) { player ->
            var checked by rememberSaveable(player.packageName) { mutableStateOf(enabled(player.packageName)) }
            SettingsSwitchRow(player.name, player.detail, checked, accent) {
                checked = it
                onChange(player.packageName, it)
            }
        }
    }
}

@Composable
private fun DvcPage(state: EqUiState, accent: Color, onEnabled: (Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SettingsSwitchRow("Enable Direct Volume Control", "Observe Android media volume and reserve software headroom when needed", state.dvcEnabled, accent, onEnabled) }
        item {
            SettingsInfoCard("VOLUME INFORMATION", listOf(
                "Music volume" to "${state.dvcSnapshot.musicVolumeIndex} / ${state.dvcSnapshot.musicVolumeMaximum}",
                "Volume fraction" to "${(state.dvcSnapshot.musicVolumeFraction * 100f).toInt()}%",
                "Estimated attenuation" to String.format(Locale.US, "%.1f dB", state.dvcSnapshot.estimatedHardwareAttenuationDb),
                "Peak boost budget" to String.format(Locale.US, "%.1f dB", state.dvcSnapshot.estimatedBoostBudgetDb),
                "Preamp compensation" to String.format(Locale.US, "%.1f dB", state.dvcSnapshot.preampCompensationDb)
            ))
        }
        item {
            SettingsStatusCard(
                if (state.dvcSnapshot.clippingProtectionActive) "HEADROOM COMPENSATION ACTIVE" else "HEADROOM STATUS",
                if (state.dvcEnabled && state.limiterEnabled) "The session limiter already reserves peak headroom; DVC avoids applying the same compensation twice."
                else if (state.dvcEnabled) "DVC is monitoring media volume and can apply conservative digital preamp compensation."
                else "DVC is disabled. No volume-based compensation is being requested.",
                accent
            )
        }
        item {
            Text(
                "Android exposes the media-volume index, not exact analogue amplifier gain. The attenuation figure is an estimate, not a hardware measurement.",
                color = HubMuted, fontSize = 8.sp, modifier = Modifier.padding(4.dp)
            )
        }
    }
}

@Composable
private fun GraphicBandsPage(state: EqUiState, accent: Color, onBandCount: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SettingsSegmentRow("Active EQ layout", state.bandCount.toString(), listOf("10", "15", "32"), accent) { onBandCount(it.toInt()) } }
        item { SettingsInfoCard("LAYOUT DETAILS", listOf("10-band" to "Classic graphic EQ", "15-band" to "Extended graphic EQ", "32-band" to "Full parametric response with Gain, Q and Frequency")) }
        item { Text("Switching layout changes the active EQ bands sent to the session effect. Saved preset data for other bands is preserved.", color = HubMuted, fontSize = 8.sp, modifier = Modifier.padding(4.dp)) }
    }
}

@Composable
private fun ToneSettingsPage(
    state: EqUiState,
    accent: Color,
    onBassFrequency: (Float) -> Unit,
    onBassQ: (Float) -> Unit,
    onTrebleFrequency: (Float) -> Unit,
    onTrebleQ: (Float) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SettingsGroupLabel("BASS FILTER", accent) }
        item { SettingsSliderRow("Bass frequency", String.format(Locale.US, "%.0f Hz", state.bassFrequencyHz), state.bassFrequencyHz, 20f..250f, accent, onBassFrequency) }
        item { SettingsSliderRow("Bass Q", String.format(Locale.US, "%.2f", state.bassQFactor), state.bassQFactor, 0.5f..2f, accent, onBassQ) }
        item { SettingsGroupLabel("TREBLE FILTER", accent) }
        item { SettingsSliderRow("Treble frequency", String.format(Locale.US, "%.1f kHz", state.trebleFrequencyHz / 1_000f), state.trebleFrequencyHz, 4_000f..16_000f, accent, onTrebleFrequency) }
        item { SettingsSliderRow("Treble Q", String.format(Locale.US, "%.2f", state.trebleQFactor), state.trebleQFactor, 0.5f..2f, accent, onTrebleQ) }
    }
}

@Composable
private fun ValueFormatPage(
    accent: Color,
    equalizerMode: String,
    toneMode: String,
    onEqualizerMode: (String) -> Unit,
    onToneMode: (String) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SettingsSegmentRow("Equalizer gain values", equalizerMode, listOf("HIDDEN", "DB", "PERCENT"), accent, onEqualizerMode) }
        item { SettingsSegmentRow("Bass / Treble values", toneMode, listOf("DB", "PERCENT"), accent, onToneMode) }
        item { SettingsInfoCard("VALUE GUIDE", listOf("Hidden" to "Hide live numeric labels", "dB" to "Show gain in decibels", "Percent" to "100% represents 0 dB / neutral")) }
    }
}

@Composable
private fun LimiterPage(
    state: EqUiState,
    accent: Color,
    onEnabled: (Boolean) -> Unit,
    onThreshold: (Float) -> Unit,
    onRelease: (Float) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SettingsSwitchRow("Enable Limiter", "Keep peaks under the configured session-effect threshold", state.limiterEnabled, accent, onEnabled) }
        item { SettingsSliderRow("Threshold", String.format(Locale.US, "%.1f dB", state.limiterThresholdDb), state.limiterThresholdDb, -12f..0f, accent, onThreshold) }
        item { SettingsSliderRow("Release time", String.format(Locale.US, "%.0f ms", state.limiterReleaseMs), state.limiterReleaseMs, 10f..300f, accent, onRelease) }
        item { Text("The system session effect may approximate limiter behaviour according to the Android audio implementation on the device.", color = HubMuted, fontSize = 8.sp, modifier = Modifier.padding(4.dp)) }
    }
}

@Composable
private fun UtilitiesPage(
    accent: Color,
    state: EqUiState,
    onOpenAccess: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SettingsNavigationRow("Notification & Audio Session Access", "Open Android access setup", "⌁", accent, onOpenAccess) }
        item { SettingsNavigationRow("Copy ADB Diagnostics", "Copy media-session and audio-flinger commands", "⌘", accent, onCopyDiagnostics) }
        item { SettingsGroupLabel("PRESETS & BACKUPS", accent) }
        item { SettingsNavigationRow("Import AutoEQ", "Load a supported AutoEQ / JSON profile", "↓", accent, onImport) }
        item { SettingsNavigationRow("Export Preset", "Export current EQ settings as JSON", "↑", accent, onExport) }
        item { SettingsNavigationRow("Backup Presets", "Save all user presets to a file", "▣", accent, onBackup) }
        item { SettingsNavigationRow("Restore Presets", "Import saved preset backup", "↻", accent, onRestore) }
        item {
            SettingsInfoCard("SESSION STATUS", listOf(
                "Player" to state.activePlayerName,
                "Package" to (state.activePlayerPackage ?: "—"),
                "Session" to (state.activeSessionId?.toString() ?: "Not attached"),
                "Effect" to state.attachmentSummary
            ))
        }
    }
}

@Composable
private fun AboutPage(accent: Color) {
    val nativeVersion = remember { runCatching { NativeDspBridge.nativeVersion() }.getOrDefault("Unavailable") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        item { SettingsStatusCard("GG EQUALIZER", "Personal Android audio equalizer · version 1.0.0", accent) }
        item { SettingsInfoCard("AUDIO ENGINE", listOf("Native DSP" to nativeVersion, "External players" to "Android session effects", "Parametric mapping" to "32 logarithmic response zones")) }
        item {
            Text(
                "External player audio is processed through Android session effects when a usable session is exposed. Android's public session EQ bands do not expose a per-band Q parameter, so the app maps its parametric response into available frequency zones.",
                color = HubMuted, fontSize = 8.sp, modifier = Modifier.padding(4.dp)
            )
        }
        item { Text("GG Equalizer is an independent implementation and is not affiliated with Poweramp.", color = HubMuted, fontSize = 8.sp, modifier = Modifier.padding(4.dp)) }
    }
}

@Composable
private fun SettingsGroupLabel(text: String, accent: Color) {
    Text(text.uppercase(Locale.US), color = accent, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.15.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 1.dp))
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    glyph: String,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(HubPanel)
            .border(1.dp, HubBorder, RoundedCornerShape(13.dp))
            .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(33.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Text(glyph, color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = HubText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = HubMuted, fontSize = 7.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = HubMuted, fontSize = 23.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(HubPanel)
            .border(1.dp, HubBorder, RoundedCornerShape(13.dp))
            .clickable { onChange(!checked) }.padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = HubText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = HubMuted, fontSize = 7.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF071116),
                checkedTrackColor = accent,
                uncheckedThumbColor = HubMuted,
                uncheckedTrackColor = Color(0xFF343B45)
            )
        )
    }
}

@Composable
private fun SettingsSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(HubPanel)
            .border(1.dp, HubBorder, RoundedCornerShape(13.dp)).padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = HubText, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(valueLabel, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range
        )
    }
}

@Composable
private fun SettingsSegmentRow(
    title: String,
    selected: String,
    options: List<String>,
    accent: Color,
    onSelect: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(HubPanel)
            .border(1.dp, HubBorder, RoundedCornerShape(13.dp)).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = HubText, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            options.forEach { value ->
                val active = selected.equals(value, ignoreCase = true)
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (active) accent else HubPanelRaised)
                        .border(1.dp, if (active) accent else HubBorder, RoundedCornerShape(8.dp))
                        .clickable { onSelect(value) }.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(value, color = if (active) Color(0xFF071116) else HubMuted, fontSize = 7.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SettingsInfoCard(title: String, rows: List<Pair<String, String>>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(HubPanel)
            .border(1.dp, HubBorder, RoundedCornerShape(13.dp)).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(title, color = HubMuted, fontSize = 7.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
        rows.forEach { (label, value) ->
            Row(verticalAlignment = Alignment.Top) {
                Text(label, color = HubMuted, fontSize = 8.sp, modifier = Modifier.weight(0.82f))
                Text(value, color = HubText, fontSize = 8.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.18f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SettingsStatusCard(title: String, detail: String, accent: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = 0.09f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(13.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(title, color = accent, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 0.8.sp)
        Text(detail, color = HubText, fontSize = 8.sp)
    }
}
