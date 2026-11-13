package com.rohitkhandel.ggaudio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohitkhandel.ggaudio.audio.AudioBackendStatus
import com.rohitkhandel.ggaudio.audio.EqualizerBand
import com.rohitkhandel.ggaudio.audio.EqualizerDefaults
import com.rohitkhandel.ggaudio.audio.GlobalAudioEffectsController
import com.rohitkhandel.ggaudio.audio.LocalAudioFile
import com.rohitkhandel.ggaudio.audio.LocalAudioPlayer
import com.rohitkhandel.ggaudio.audio.EqualizerPresetLibrary
import com.rohitkhandel.ggaudio.audio.SavedEqualizerPreset
import com.rohitkhandel.ggaudio.audio.SavedEqualizerPresetRepository
import com.rohitkhandel.ggaudio.audio.formatPlaybackTime
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    var queue by remember { mutableStateOf<List<LocalAudioFile>>(emptyList()) }
    var currentTrackIndex by remember { mutableIntStateOf(0) }
    var localPlayerReady by remember { mutableStateOf(false) }
    var localPlayerPlaying by remember { mutableStateOf(false) }
    var playbackPositionMs by remember { mutableLongStateOf(0L) }
    var playbackDurationMs by remember { mutableLongStateOf(0L) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var presetMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val presetRepository = remember(context) { SavedEqualizerPresetRepository(context) }
    val savedPresets by presetRepository.presets.collectAsState(initial = emptyList())
    val presetScope = rememberCoroutineScope()
    val localPlayer = remember(context) { LocalAudioPlayer(context) }
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val chosenTracks = uris.distinctBy { it.toString() }.map { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some document providers grant temporary read access only.
                }
                LocalAudioFile(uri, queryDisplayName(context, uri))
            }
            queue = chosenTracks
            currentTrackIndex = 0
            selectedTrack = chosenTracks.firstOrNull()?.title
            localPlayerReady = false
            localPlayerPlaying = false
            playbackPositionMs = 0L
            playbackDurationMs = 0L
            playbackError = null
            localPlayer.loadQueue(chosenTracks)
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

            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                currentTrackIndex = localPlayer.player.currentMediaItemIndex.coerceAtLeast(0)
                selectedTrack = mediaItem?.mediaMetadata?.title?.toString() ?: selectedTrack
                playbackPositionMs = 0L
                playbackDurationMs = localPlayer.player.duration.coerceAtLeast(0L)
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
                    savedPresets = savedPresets,
                    onManagePresets = { selectedTab = AppTab.Presets },
                    onPresetChange = { name, updated, updatedPreamp ->
                        presetName = name
                        bands = updated
                        preamp = updatedPreamp
                    }
                )
                AppTab.Player -> LocalPlayerScreen(
                    trackName = selectedTrack,
                    ready = localPlayerReady,
                    playing = localPlayerPlaying,
                    positionMs = playbackPositionMs,
                    durationMs = playbackDurationMs,
                    playbackError = playbackError,
                    queueNames = queue.map { it.title },
                    currentTrackIndex = currentTrackIndex,
                    onOpenAudio = { audioPicker.launch(arrayOf("audio/*")) },
                    onSelectTrack = { index -> localPlayer.selectTrack(index) },
                    onPrevious = { localPlayer.skipToPrevious() },
                    onNext = { localPlayer.skipToNext() },
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
                AppTab.Presets -> PresetsScreen(
                    currentPresetName = presetName,
                    currentBands = bands,
                    currentPreamp = preamp,
                    savedPresets = savedPresets,
                    message = presetMessage,
                    onMessageDismiss = { presetMessage = null },
                    onApplyPreset = { name, updatedBands, updatedPreamp ->
                        presetName = name
                        bands = updatedBands
                        preamp = updatedPreamp
                        selectedTab = AppTab.Equalizer
                    },
                    onSavePreset = { name ->
                        presetScope.launch {
                            presetMessage = try {
                                val saved = presetRepository.savePreset(name, bands, preamp)
                                "Saved ${saved.name}."
                            } catch (error: Exception) {
                                error.message ?: "Could not save this preset."
                            }
                        }
                    },
                    onDeletePreset = { id ->
                        presetScope.launch {
                            presetMessage = try {
                                presetRepository.deletePreset(id)
                                "Preset deleted."
                            } catch (error: Exception) {
                                error.message ?: "Could not delete this preset."
                            }
                        }
                    }
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
    queueNames: List<String>,
    currentTrackIndex: Int,
    onOpenAudio: () -> Unit,
    onSelectTrack: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
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
        Text("Build a queue and play it through GG Audio's software DSP.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
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
                    Text("ADD AUDIO FILES", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Button(
                        onClick = onPrevious,
                        enabled = queueNames.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text("PREV", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onPlayPause,
                        enabled = ready,
                        modifier = Modifier.weight(1.25f),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(if (playing) "PAUSE" else "PLAY", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onNext,
                        enabled = currentTrackIndex < queueNames.lastIndex,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(9.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text("NEXT", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(
                    onClick = onStop,
                    enabled = queueNames.isNotEmpty(),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("STOP PLAYBACK", color = TextMuted, fontSize = 8.sp)
                }
            }
        }
        if (queueNames.isNotEmpty()) {
            Surface(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                color = CardSurface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Outline)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("PLAY QUEUE", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold)
                        Text("${queueNames.size} TRACKS", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 184.dp).padding(top = 6.dp)) {
                        itemsIndexed(queueNames) { index, name ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (index == currentTrackIndex) Color(0xFF30271F) else Color.Transparent)
                                    .clickable { onSelectTrack(index) }
                                    .padding(horizontal = 9.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text((index + 1).toString().padStart(2, '0'), color = TextMuted, fontSize = 8.sp)
                                Text(
                                    name,
                                    color = if (index == currentTrackIndex) Accent else TextPrimary,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f).padding(start = 9.dp)
                                )
                                if (index == currentTrackIndex) {
                                    Text("NOW", color = Accent, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
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
private fun PresetsScreen(
    currentPresetName: String,
    currentBands: List<EqualizerBand>,
    currentPreamp: Float,
    savedPresets: List<SavedEqualizerPreset>,
    message: String?,
    onMessageDismiss: () -> Unit,
    onApplyPreset: (String, List<EqualizerBand>, Float) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit
) {
    var saveDialogOpen by remember { mutableStateOf(false) }
    var presetNameInput by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SavedEqualizerPreset?>(null) }
    val duplicateName = presetNameInput.trim().let { candidate ->
        candidate.isNotEmpty() && (
            EqualizerPresetLibrary.reservedNames.contains(candidate.lowercase()) ||
                savedPresets.any { it.name.equals(candidate, ignoreCase = true) }
            )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 18.dp)) {
        Text("PERSONAL SOUND PROFILES", color = Accent, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Text("Presets", color = TextPrimary, fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp))
        Text("Save your own 10-band curves on this phone.", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        Surface(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            color = CardSurface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Outline)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("CURRENT EQUALIZER", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                Text(currentPresetName, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 5.dp))
                Text("${currentBands.count { it.gainDb != 0f }} active bands  ·  Preamp ${if (currentPreamp >= 0f) "+" else ""}${"%.1f".format(currentPreamp)} dB", color = TextMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                Button(
                    onClick = { presetNameInput = ""; saveDialogOpen = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF633D25), contentColor = Color(0xFFFFC18B)),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Text("SAVE CURRENT PRESET", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (message != null) {
            Text(message, color = if (message.startsWith("Saved")) Success else TextMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 10.dp).clickable(onClick = onMessageDismiss))
        }
        Text("BUILT-IN PROFILES", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 7.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(EqualizerPresetLibrary.builtIn, key = { it.name }) { preset ->
                Surface(
                    onClick = { onApplyPreset(preset.name, preset.toBands(), 0f) },
                    color = CardSurface,
                    shape = RoundedCornerShape(11.dp),
                    border = BorderStroke(1.dp, if (currentPresetName == preset.name) Accent else Outline)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (currentPresetName == preset.name) "CURRENT PROFILE" else "10-BAND PROFILE", color = TextMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Text("APPLY  ›", color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (savedPresets.isNotEmpty()) {
                item {
                    Text("MY SAVED PROFILES", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 1.dp))
                }
                items(savedPresets, key = { it.id }) { preset ->
                    Surface(
                        color = CardSurface,
                        shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, if (currentPresetName == preset.name) Accent else Outline)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(start = 13.dp, end = 7.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onApplyPreset(preset.name, preset.toBands(), preset.preampDb) }.padding(vertical = 3.dp)) {
                                Text(preset.name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "PREAMP ${if (preset.preampDb >= 0f) "+" else ""}${"%.1f".format(preset.preampDb)} dB",
                                    color = TextMuted,
                                    fontSize = 8.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            TextButton(onClick = { deleteTarget = preset }) {
                                Text("DELETE", color = Color(0xFFE69B8F), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (saveDialogOpen) {
        AlertDialog(
            onDismissRequest = { saveDialogOpen = false },
            title = { Text("Save equalizer preset") },
            text = {
                Column {
                    Text("Give this sound profile a name. It will be stored only on this device.", fontSize = 12.sp)
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { if (it.length <= 32) presetNameInput = it },
                        singleLine = true,
                        label = { Text("Preset name") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    if (duplicateName) {
                        Text("This name is already in use.", color = Color(0xFFE69B8F), fontSize = 10.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSavePreset(presetNameInput)
                        saveDialogOpen = false
                    },
                    enabled = presetNameInput.trim().isNotEmpty() && !duplicateName
                ) {
                    Text("SAVE")
                }
            },
            dismissButton = {
                TextButton(onClick = { saveDialogOpen = false }) { Text("CANCEL") }
            }
        )
    }

    deleteTarget?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete preset?") },
            text = { Text("Remove ${preset.name} from this device?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePreset(preset.id)
                        deleteTarget = null
                    }
                ) { Text("DELETE", color = Color(0xFFE69B8F)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("CANCEL") }
            }
        )
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

