package com.rohitkhandel.ggaudio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohitkhandel.ggaudio.audio.AudioBackendStatus
import com.rohitkhandel.ggaudio.audio.EqualizerDefaults
import com.rohitkhandel.ggaudio.audio.GlobalAudioEffectsController
import com.rohitkhandel.ggaudio.audio.LocalAudioPlayer
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import com.rohitkhandel.ggaudio.ui.theme.*
import com.rohitkhandel.ggaudio.ui.theme.Surface as CardSurface

private enum class AppTab(val label: String, val symbol: String) {
    Equalizer("Equalizer", "≋"), Player("Player", "▶"), Effects("Effects", "✦"), Presets("Presets", "▤"), Settings("Settings", "⚙")
}

@Composable
fun GgAudioApp() {
    var selectedTab by remember { mutableStateOf(AppTab.Equalizer) }
    var bands by remember { mutableStateOf(EqualizerDefaults.balanced()) }
    var presetName by remember { mutableStateOf("Balanced") }
    var preamp by remember { mutableFloatStateOf(0f) }
    var bassStrength by remember { mutableFloatStateOf(0f) }
    var virtualizerStrength by remember { mutableFloatStateOf(0f) }
    var connecting by remember { mutableStateOf(false) }
    var backendStatus by remember {
        mutableStateOf(AudioBackendStatus(false, "Not connected. Tap Connect to check device support."))
    }
    var selectedTrack by remember { mutableStateOf<String?>(null) }
    var localPlayerReady by remember { mutableStateOf(false) }
    var localPlayerPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableLongStateOf(0L) }
    var playbackDurationMs by remember { mutableLongStateOf(0L) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val localPlayer = remember(context) { LocalAudioPlayer(context) }
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some document providers grant temporary read access only.
            }
            selectedTrack = queryDisplayName(context, uri)
            localPlayerReady = false
            localPlayerPlaying = false
            playbackPositionMs = 0L
            playbackDurationMs = 0L
            playbackError = null
            localPlayer.load(uri)
        }
    }

    DisposableEffect(localPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                localPlayerReady = playbackState == Player.STATE_READY
                if (playbackState == Player.STATE_READY) {
                    playbackDurationMs = localPlayer.player.duration.coerceAtLeast(0L)
                    playbackPositionMs = localPlayer.player.currentPosition.coerceAtLeast(0L)
                    playbackError = null
                }
                if (playbackState == Player.STATE_ENDED) {
                    playbackPositionMs = playbackDurationMs
                    localPlayerPlaying = false
                } else if (playbackState == Player.STATE_IDLE) {
                    localPlayerPlaying = false
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                localPlayerPlaying = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                localPlayerPlaying = false
                localPlayerReady = false
                playbackError = error.message ?: error.errorCodeName
            }
        }
        localPlayer.player.addListener(listener)
        onDispose {
            localPlayer.player.removeListener(listener)
            localPlayer.close()
        }
    }

    LaunchedEffect(localPlayerReady, localPlayerPlaying, selectedTrack) {
        while (selectedTrack != null && localPlayerReady) {
            playbackPositionMs = localPlayer.player.currentPosition.coerceAtLeast(0L)
            playbackDurationMs = localPlayer.player.duration.coerceAtLeast(0L)
            delay(300)
        }
    }

    LaunchedEffect(bands, preamp) {
        localPlayer.updateEqualizer(bands, preamp)
    }

    val controller = remember { GlobalAudioEffectsController() }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }

    LaunchedEffect(bands, backendStatus.connected) {
        if (backendStatus.connected) controller.applyBandGains(bands)
    }
    LaunchedEffect(bassStrength, virtualizerStrength, backendStatus.connected) {
        if (backendStatus.connected) {
            controller.setBassStrength(bassStrength.toInt())
            controller.setVirtualizerStrength(virtualizerStrength.toInt())
        }
    }

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
                    TextButton(onClick = { selectedTab = tab }, modifier = Modifier.testTag("nav_" + tab.name.lowercase())) {
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
            AppHeader(
                status = backendStatus,
                connecting = connecting,
                onConnect = {
                    connecting = true
                    controller.connect { result ->
                        backendStatus = result
                        connecting = false
                        if (result.connected) {
                            controller.applyBandGains(bands)
                            controller.setBassStrength(bassStrength.toInt())
                            controller.setVirtualizerStrength(virtualizerStrength.toInt())
                        }
                    }
                },
                onDisconnect = {
                    connecting = true
                    controller.disconnect { result ->
                        backendStatus = result
                        connecting = false
                    }
                }
            )
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
                AppTab.Player -> LocalPlayerScreen(
                    trackName = selectedTrack,
                    ready = localPlayerReady,
                    playing = localPlayerPlaying,
                    positionMs = playbackPositionMs,
                    durationMs = playbackDurationMs,
                    playbackError = playbackError,
                    onOpenAudio = { audioPicker.launch(arrayOf("audio/*")) },
                    onSeek = { positionMs -> localPlayer.player.seekTo(positionMs.coerceAtLeast(0L)) },
                    onPlayPause = {
                        if (localPlayerPlaying) localPlayer.pause() else localPlayer.play()
                    },
                    onStop = {
                        localPlayer.stop()
                        localPlayerPlaying = false
                        playbackPositionMs = 0L
                    }
                )
                AppTab.Effects -> EffectsScreen(
                    status = backendStatus,
                    bassStrength = bassStrength,
                    virtualizerStrength = virtualizerStrength,
                    onBassChange = { bassStrength = it },
                    onVirtualizerChange = { virtualizerStrength = it }
                )
                AppTab.Presets -> PlaceholderScreen(
                    "Presets",
                    "The built-in profiles are ready. Saving, importing and exporting custom presets is the next milestone."
                )
                AppTab.Settings -> SettingsScreen(backendStatus)
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameColumn >= 0) {
                cursor.getString(nameColumn)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Selected audio"
    } catch (_: Exception) {
        uri.lastPathSegment?.substringAfterLast('/') ?: "Selected audio"
    }
}

@Composable
private fun AppHeader(
    status: AudioBackendStatus,
    connecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2A211B),
                border = BorderStroke(1.dp, Color(0xFF59412D))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("GG", color = Accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text("GG AUDIO", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.2.sp)
                Text("PRECISION SOUND ENGINE", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.1.sp)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (status.connected) "SYSTEM EQ ATTACHED" else "DSP STANDBY",
                    color = if (status.connected) Success else TextMuted,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(if (connecting) "CHECKING DEVICE" else "LIVE STATUS", color = TextMuted, fontSize = 7.sp)
            }
        }
        Surface(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            color = CardSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Outline)
        ) {
            Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("♫", color = Accent, fontSize = 23.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("System output", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (status.connected) "${status.hardwareBandCount} device EQ bands detected"
                            else "Check global audio-effect support",
                            color = TextMuted,
                            fontSize = 9.sp
                        )
                    }
                    Button(
                        onClick = if (status.connected) onDisconnect else onConnect,
                        enabled = !connecting,
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (status.connected) Color(0xFF3A2520) else Color(0xFF633D25),
                            contentColor = Color(0xFFFFC18B)
                        )
                    ) {
                        Text(
                            when {
                                connecting -> "WAIT"
                                status.connected -> "DISCONNECT"
                                else -> "CONNECT"
                            },
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(status.message, color = if (status.connected) Success else TextMuted, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 8.dp))
                if (!status.connected) {
                    Text(
                        "Android may block global session effects on some devices. A successful check is required before controls are sent.",
                        color = TextMuted,
                        fontSize = 8.sp,
                        lineHeight = 12.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalPlayerScreen(
    trackName: String?,
    ready: Boolean,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    playbackError: String?,
    onOpenAudio: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit
) {
    var seekFraction by remember(trackName) { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf(false) }
    val safeDuration = durationMs.coerceAtLeast(1L)
    LaunchedEffect(positionMs, durationMs, scrubbing) {
        if (!scrubbing) {
            seekFraction = if (durationMs > 0L) (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f) else 0f
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp)) {
        Text("LOCAL PCM PLAYBACK", color = Accent, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text("Player", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
        Text("Play a local audio file through GG Audio's software DSP.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        Surface(
            Modifier.fillMaxWidth().padding(top = 19.dp),
            color = CardSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Outline)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("NOW PLAYING", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                Text(
                    trackName ?: "No audio file selected",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    when {
                        playing -> "PLAYING · SOFTWARE DSP ACTIVE"
                        ready -> "READY · DSP ARMED"
                        trackName != null -> "PREPARING AUDIO"
                        else -> "CHOOSE A LOCAL FILE TO START"
                    },
                    color = if (ready) Success else TextMuted,
                    fontSize = 8.sp,
                    letterSpacing = .5.sp,
                    modifier = Modifier.padding(top = 5.dp)
                )
                if (playbackError != null) {
                    Text(
                        playbackError,
                        color = Color(0xFFFF8B80),
                        fontSize = 9.sp,
                        lineHeight = 13.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Slider(
                    value = seekFraction,
                    onValueChange = {
                        scrubbing = true
                        seekFraction = it
                    },
                    onValueChangeFinished = {
                        if (durationMs > 0L) onSeek((durationMs * seekFraction).toLong())
                        scrubbing = false
                    },
                    valueRange = 0f..1f,
                    enabled = ready && durationMs > 0L,
                    modifier = Modifier.padding(top = 11.dp)
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatPlaybackTime(positionMs), color = TextMuted, fontSize = 8.sp)
                    Text(formatPlaybackTime(durationMs), color = TextMuted, fontSize = 8.sp)
                }
                Button(
                    onClick = onOpenAudio,
                    modifier = Modifier.fillMaxWidth().padding(top = 17.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF633D25),
                        contentColor = Color(0xFFFFC18B)
                    )
                ) {
                    Text("OPEN AUDIO FILE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Button(
                        onClick = onPlayPause,
                        enabled = ready,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (playing) "PAUSE" else "PLAY", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onStop,
                        enabled = trackName != null,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF303A47),
                            contentColor = TextPrimary
                        )
                    ) {
                        Text("STOP", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Surface(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            color = CardSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Outline)
        ) {
            Column(Modifier.padding(15.dp)) {
                Text("PROCESSING CHAIN", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                DiagnosticRow("10-band peaking EQ", "ENABLED")
                DiagnosticRow("Software preamp", "ENABLED")
                DiagnosticRow("PCM format", "16-bit")
                Text(
                    "These controls process audio played inside GG Audio. They do not capture or modify unrelated apps.",
                    color = TextMuted,
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    modifier = Modifier.padding(top = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun EffectsScreen(
    status: AudioBackendStatus,
    bassStrength: Float,
    virtualizerStrength: Float,
    onBassChange: (Float) -> Unit,
    onVirtualizerChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 20.dp)) {
        Text("SPATIAL & DYNAMIC", color = Accent, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text("Sound effects", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
        Text("Optional Android audio effects. Availability depends on the phone.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        Surface(
            Modifier.fillMaxWidth().padding(top = 19.dp),
            color = CardSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Outline)
        ) {
            Column(Modifier.padding(15.dp)) {
                EffectControl(
                    title = "Bass enhancement",
                    description = "Android BassBoost effect",
                    value = bassStrength,
                    available = status.connected && status.bassBoostAvailable,
                    onValueChange = onBassChange
                )
                Spacer(Modifier.height(19.dp))
                EffectControl(
                    title = "Stereo virtualizer",
                    description = "Android Virtualizer effect",
                    value = virtualizerStrength,
                    available = status.connected && status.virtualizerAvailable,
                    onValueChange = onVirtualizerChange
                )
            }
        }
        Text(
            if (!status.connected) "Connect the system audio backend first."
            else "Unavailable effects remain disabled; GG Audio will not fake their activation.",
            color = TextMuted,
            fontSize = 9.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun EffectControl(
    title: String,
    description: String,
    value: Float,
    available: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(description, color = TextMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Text(
                if (available) "${value.toInt()}%" else "UNAVAILABLE",
                color = if (available) Accent else TextMuted,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..100f, enabled = available)
    }
}

@Composable
private fun SettingsScreen(status: AudioBackendStatus) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("SYSTEM DIAGNOSTICS", color = Accent, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text("Settings", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Surface(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            color = CardSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Outline)
        ) {
            Column(Modifier.padding(15.dp)) {
                DiagnosticRow("Global Equalizer", if (status.connected) "ATTACHED" else "NOT CONNECTED")
                DiagnosticRow("Hardware EQ bands", if (status.connected) status.hardwareBandCount.toString() else "—")
                DiagnosticRow("BassBoost", if (status.bassBoostAvailable) "AVAILABLE" else "NOT AVAILABLE")
                DiagnosticRow("Virtualizer", if (status.virtualizerAvailable) "AVAILABLE" else "NOT AVAILABLE")
                Text(status.message, color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        Text(
            "Global output session 0 is deprecated by Android and may not affect every app. A session-scoped player route is the compatibility fallback.",
            color = TextMuted,
            fontSize = 9.sp,
            lineHeight = 14.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextMuted, fontSize = 10.sp)
        Text(value, color = TextPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PlaceholderScreen(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Text("GG AUDIO  /  CONTROL", color = Accent, fontSize = 9.sp, letterSpacing = 1.5.sp)
        Text(title, color = TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
        Text(subtitle, color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
        Surface(Modifier.fillMaxWidth().padding(top = 22.dp), color = CardSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Outline)) {
            Text("This section is being built in stages. Only completed and verified functions will be shown as active.", color = TextMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp))
        }
    }
}


private fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0L) / 1_000L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
