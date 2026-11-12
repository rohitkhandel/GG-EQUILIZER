package com.rohitkhandel.ggequalizer.ui

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.rohitkhandel.ggequalizer.audio.DvcManager
import com.rohitkhandel.ggequalizer.audio.DvcSnapshot
import com.rohitkhandel.ggequalizer.audio.EqAudioProfile
import com.rohitkhandel.ggequalizer.audio.EqBandParameter
import com.rohitkhandel.ggequalizer.audio.FftAnalyzer
import com.rohitkhandel.ggequalizer.audio.GlobalEqController
import com.rohitkhandel.ggequalizer.audio.GlobalEqSnapshot
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge
import com.rohitkhandel.ggequalizer.presets.EqDatabase
import com.rohitkhandel.ggequalizer.presets.PresetBackup
import com.rohitkhandel.ggequalizer.presets.PresetEntity
import com.rohitkhandel.ggequalizer.presets.AutoEqParser
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/** Coordinates UI-local gestures, throttled DSP profile writes, presets and spectrum capture. */
class EqViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val AUDIO_UPDATE_INTERVAL_MS = 20L
        const val RESPONSE_UPDATE_INTERVAL_MS = 24L
        const val LN_2 = 0.69314718056f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(EqUiState())
    val uiState: StateFlow<EqUiState> = mutableState.asStateFlow()

    private val audioProfileUpdates = Channel<Unit>(Channel.CONFLATED)
    private val responseInvalidations = Channel<Unit>(Channel.CONFLATED)
    private val fftAnalyzer = FftAnalyzer(application)
    private val presetDao = EqDatabase.getInstance(application).presetDao()

    private val globalListener: (GlobalEqSnapshot) -> Unit = { snapshot ->
        val nextSession = snapshot.sessions.firstOrNull()
        val previousSessionId = mutableState.value.activeSessionId
        val packageName = snapshot.activePlayers.firstOrNull() ?: nextSession?.packageName
        mutableState.update { current ->
            current.copy(
                masterEnabled = snapshot.masterEnabled,
                activePlayerPackage = packageName,
                activePlayerName = resolvePlayerName(packageName),
                activeSessionCount = snapshot.sessions.size,
                activeSessionId = nextSession?.sessionId,
                attachmentSummary = snapshot.sessions
                    .groupBy { it.attachment }
                    .entries
                    .joinToString(" · ") { "${it.key}: ${it.value.size}" }
                    .ifBlank { "Waiting for an audio session" }
            )
        }
        if (mutableState.value.visualizerEnabled && previousSessionId != nextSession?.sessionId) {
            startSpectrumCapture(nextSession?.sessionId ?: 0)
        }
    }

    private val dvcListener: (DvcSnapshot) -> Unit = { snapshot ->
        mutableState.update { current ->
            current.copy(dvcEnabled = snapshot.enabled, dvcSnapshot = snapshot)
        }
    }

    init {
        GlobalEqController.initialize(application)
        GlobalEqController.addListener(globalListener)
        DvcManager.addGlobalListener(dvcListener)
        DvcManager.globalSnapshot()?.let(dvcListener)

        scope.launch {
            presetDao.observeAll().collect { presets ->
                mutableState.update { it.copy(presets = presets) }
            }
        }

        scope.launch(Dispatchers.Default) {
            var lastApplied = GlobalEqController.currentProfile()
            for (ignored in audioProfileUpdates) {
                delay(AUDIO_UPDATE_INTERVAL_MS)
                while (audioProfileUpdates.tryReceive().isSuccess) Unit
                val latest = mutableState.value.toEffectiveAudioProfile()
                applyAudioProfileDelta(lastApplied, latest)
                lastApplied = latest
            }
        }

        scope.launch(Dispatchers.Default) {
            for (ignored in responseInvalidations) {
                delay(RESPONSE_UPDATE_INTERVAL_MS)
                while (responseInvalidations.tryReceive().isSuccess) Unit
                val points = EqResponseCalculator.calculate(mutableState.value)
                mutableState.update { it.copy(responsePoints = points) }
            }
        }

        // Apply the selected 10-band default profile to any existing global DSP session.
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun selectBand(index: Int) {
        if (index !in mutableState.value.bands.indices) return
        mutableState.update { it.copy(selectedBandIndex = index) }
    }

    fun setBandGain(index: Int, gainDb: Float) {
        updateBand(index) { it.copy(gainDb = gainDb.coerceIn(-15f, 15f)) }
    }

    fun setBandFrequency(index: Int, frequencyHz: Float) {
        updateBand(index) { it.copy(frequencyHz = frequencyHz.coerceIn(20f, 20_000f)) }
    }

    fun setBandQ(index: Int, qFactor: Float) {
        updateBand(index) { it.copy(qFactor = qFactor.coerceIn(0.1f, 12f)) }
    }

    fun setBandFilterType(index: Int, filterType: Int) {
        updateBand(index) { it.copy(filterType = filterType.coerceIn(0, 2)) }
    }

    fun setBandEnabled(index: Int, enabled: Boolean) {
        updateBand(index) { it.copy(enabled = enabled) }
    }

    private fun updateBand(index: Int, transform: (EqBandUiState) -> EqBandUiState) {
        if (index !in mutableState.value.bands.indices) return
        val updated = mutableState.value.bands[index].let(transform)
        mutableState.update { current ->
            val changed = current.bands.toMutableList()
            changed[index] = updated
            current.copy(bands = changed)
        }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setPreampGain(gainDb: Float) {
        mutableState.update { it.copy(preampGainDb = gainDb.coerceIn(-24f, 12f)) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setBassGain(gainDb: Float) {
        mutableState.update { it.copy(bassGainDb = gainDb.coerceIn(-12f, 12f)) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setTrebleGain(gainDb: Float) {
        mutableState.update { it.copy(trebleGainDb = gainDb.coerceIn(-12f, 12f)) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setBassFrequency(value: Float) {
        mutableState.update { it.copy(bassFrequencyHz = value.coerceIn(20f, 250f)) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setBassQ(value: Float) {
        mutableState.update { it.copy(bassQFactor = value.coerceIn(0.5f, 2f)) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setTrebleFrequency(value: Float) {
        mutableState.update { it.copy(trebleFrequencyHz = value.coerceIn(4_000f, 16_000f)) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setTrebleQ(value: Float) {
        mutableState.update { it.copy(trebleQFactor = value.coerceIn(0.5f, 2f)) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setLimiterEnabled(enabled: Boolean) {
        mutableState.update { it.copy(limiterEnabled = enabled) }
        audioProfileUpdates.trySend(Unit)
    }

    fun setLimiterThreshold(thresholdDb: Float) {
        mutableState.update { it.copy(limiterThresholdDb = thresholdDb.coerceIn(-12f, 0f)) }
        audioProfileUpdates.trySend(Unit)
    }

    fun setLimiterRelease(releaseMs: Float) {
        mutableState.update { it.copy(limiterReleaseMs = releaseMs.coerceIn(10f, 300f)) }
        audioProfileUpdates.trySend(Unit)
    }

    fun setAvoidOverloading(enabled: Boolean) {
        mutableState.update { it.copy(avoidOverloading = enabled) }
        audioProfileUpdates.trySend(Unit)
    }

    fun setSmoothGains(enabled: Boolean) {
        mutableState.update { it.copy(smoothGains = enabled) }
        audioProfileUpdates.trySend(Unit)
    }

    fun setBandsOverlapEnabled(enabled: Boolean) {
        mutableState.update { it.copy(bandsOverlapEnabled = enabled) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setBandsOverlapPercent(percent: Float) {
        mutableState.update { it.copy(bandsOverlapPercent = percent.coerceIn(0f, 100f)) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setStereoWidthPercent(widthPercent: Float) {
        mutableState.update { it.copy(stereoWidthPercent = widthPercent.coerceIn(0f, 200f)) }
        audioProfileUpdates.trySend(Unit)
    }

    fun setStereoBalance(balance: Float) {
        mutableState.update { it.copy(stereoBalance = balance.coerceIn(-1f, 1f)) }
        audioProfileUpdates.trySend(Unit)
    }

    fun setDvcEnabled(enabled: Boolean) {
        mutableState.update { it.copy(dvcEnabled = enabled) }
        scope.launch(Dispatchers.Default) {
            DvcManager.setGlobalEnabled(enabled)
            DvcManager.globalSnapshot()?.let { snapshot ->
                withContext(Dispatchers.Main.immediate) { dvcListener(snapshot) }
            }
        }
    }

    fun setDvcMode(mode: String) {
        mutableState.update { it.copy(dvcMode = mode) }
    }

    fun setBandCount(count: Int) {
        val safeCount = when {
            count >= 32 -> 32
            count >= 15 -> 15
            else -> 10
        }
        if (mutableState.value.bandCount == safeCount) return
        mutableState.update { it.copy(bandCount = safeCount) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun setShowCurveOnSliders(enabled: Boolean) {
        mutableState.update { it.copy(showCurveOnSliders = enabled) }
    }

    fun setAccentKey(key: String) {
        mutableState.update { it.copy(accentKey = key) }
    }

    fun setAudioBlockSize(size: Int) {
        mutableState.update { it.copy(audioBlockSize = size.coerceIn(2048, 8192)) }
    }

    fun setActivePlayerTracking(enabled: Boolean) {
        mutableState.update { it.copy(activePlayerTracking = enabled) }
    }

    fun setVisualizerStyle(style: String) {
        val clean = style.uppercase()
        mutableState.update { it.copy(visualizerStyle = clean) }
        if (clean == "OFF") {
            setVisualizerEnabled(false)
        } else if (!mutableState.value.visualizerEnabled) {
            setVisualizerEnabled(true)
        }
    }

    fun setVisualizerEnabled(enabled: Boolean) {
        if (!enabled) {
            fftAnalyzer.stop()
            mutableState.update {
                it.copy(
                    visualizerEnabled = false,
                    visualizerStyle = if (it.visualizerStyle == "OFF") "OFF" else it.visualizerStyle,
                    spectrumBars = List(FftAnalyzer.BAR_COUNT) { 0f }
                )
            }
            return
        }
        if (mutableState.value.visualizerStyle == "OFF") {
            mutableState.update { it.copy(visualizerStyle = "BARS") }
        }
        startSpectrumCapture(mutableState.value.activeSessionId ?: 0)
    }

    fun setVisualizerRefreshRate(rateHz: Int) {
        mutableState.update { it.copy(visualizerRefreshRateHz = rateHz.coerceIn(15, 60)) }
        if (mutableState.value.visualizerEnabled) {
            startSpectrumCapture(mutableState.value.activeSessionId ?: 0)
        }
    }

    private fun startSpectrumCapture(sessionId: Int) {
        val rate = mutableState.value.visualizerRefreshRateHz
        val started = fftAnalyzer.start(sessionId, rate) { bars ->
            scope.launch(Dispatchers.Main.immediate) {
                mutableState.update { current ->
                    if (current.visualizerEnabled) current.copy(spectrumBars = bars) else current
                }
            }
        }
        mutableState.update { current ->
            current.copy(
                visualizerEnabled = started,
                spectrumBars = if (started) current.spectrumBars else List(FftAnalyzer.BAR_COUNT) { 0f },
                presetMessage = if (started) current.presetMessage
                else "Spectrum unavailable: check audio visualizer permission/session access."
            )
        }
    }

    fun applyPreset(preset: PresetEntity) {
        val profile = preset.toAudioProfile().copy(
            stereoWidthPercent = preset.stereoWidthPercent,
            stereoBalance = preset.stereoBalance
        ).sanitized()
        mutableState.update { current ->
            current.copy(
                bands = profile.bands.map { band ->
                    EqBandUiState(
                        frequencyHz = band.frequencyHz,
                        gainDb = band.gainDb,
                        qFactor = band.qFactor,
                        filterType = band.filterType,
                        enabled = band.enabled
                    )
                },
                preampGainDb = profile.preampGainDb,
                bassGainDb = profile.bassGainDb,
                trebleGainDb = profile.trebleGainDb,
                limiterEnabled = profile.limiterEnabled,
                limiterThresholdDb = profile.limiterThresholdDb,
                limiterReleaseMs = profile.limiterReleaseMs,
                stereoWidthPercent = profile.stereoWidthPercent,
                stereoBalance = profile.stereoBalance,
                dvcEnabled = preset.dvcEnabled,
                selectedPresetId = preset.presetId,
                presetMessage = "Loaded ${preset.presetName}"
            )
        }
        audioProfileUpdates.trySend(Unit)
        scope.launch(Dispatchers.Default) { DvcManager.setGlobalEnabled(preset.dvcEnabled) }
        recalculateResponse()
    }

    fun applyImportedProfile(name: String, importedProfile: EqAudioProfile) {
        val current = mutableState.value
        val preset = PresetEntity.fromProfile(
            presetId = "custom_${UUID.randomUUID()}",
            presetName = name,
            description = "Imported AutoEQ / JSON profile",
            profile = importedProfile,
            stereoWidthPercent = current.stereoWidthPercent,
            stereoBalance = current.stereoBalance,
            dvcEnabled = current.dvcEnabled
        )
        applyPreset(preset)
        scope.launch { presetDao.upsert(preset) }
    }

    fun importAutoEq(context: Context, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching { AutoEqParser.parseUri(context, uri) }
            result.onSuccess { parsed ->
                val current = mutableState.value
                val preset = PresetEntity.fromProfile(
                    presetId = "autoeq_${UUID.randomUUID()}",
                    presetName = parsed.name,
                    description = "Imported AutoEQ profile",
                    profile = parsed.profile,
                    stereoWidthPercent = current.stereoWidthPercent,
                    stereoBalance = current.stereoBalance,
                    dvcEnabled = current.dvcEnabled
                )
                presetDao.upsert(preset)
                withContext(Dispatchers.Main.immediate) { applyPreset(preset) }
            }.onFailure { error ->
                withContext(Dispatchers.Main.immediate) {
                    mutableState.update { it.copy(presetMessage = error.message ?: "AutoEQ import failed") }
                }
            }
        }
    }

    fun saveCurrentPreset(name: String) {
        val current = mutableState.value
        val preset = PresetEntity.fromProfile(
            presetId = "custom_${UUID.randomUUID()}",
            presetName = name,
            description = "Saved from the current GG audio console",
            profile = current.toAudioProfile(),
            stereoWidthPercent = current.stereoWidthPercent,
            stereoBalance = current.stereoBalance,
            dvcEnabled = current.dvcEnabled
        )
        scope.launch {
            presetDao.upsert(preset)
            mutableState.update {
                it.copy(selectedPresetId = preset.presetId, presetMessage = "Saved ${preset.presetName}")
            }
        }
    }

    fun exportPresetBackup(context: Context, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val allPresets = mutableState.value.presets
                require(allPresets.isNotEmpty()) { "There are no presets to back up yet." }
                val json = PresetBackup.encode(allPresets)
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(json)
                } ?: throw IOException("Android could not open the backup destination.")
                allPresets.size
            }
            withContext(Dispatchers.Main.immediate) {
                mutableState.update { current ->
                    current.copy(
                        presetMessage = result.fold(
                            onSuccess = { "Backed up $it presets" },
                            onFailure = { it.message ?: "Preset backup failed" }
                        )
                    )
                }
            }
        }
    }

    fun importPresetBackup(context: Context, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                } ?: throw IOException("Android could not open the selected backup.")
                val imported = PresetBackup.decode(json)
                presetDao.upsertAll(imported)
                imported.size
            }
            withContext(Dispatchers.Main.immediate) {
                mutableState.update { current ->
                    current.copy(
                        presetMessage = result.fold(
                            onSuccess = { "Restored $it presets" },
                            onFailure = { it.message ?: "Preset restore failed" }
                        )
                    )
                }
            }
        }
    }

    fun currentAudioProfile(): EqAudioProfile = mutableState.value.toAudioProfile()

    fun setMasterEnabled(enabled: Boolean) {
        mutableState.update { it.copy(masterEnabled = enabled) }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun resetBand(index: Int) {
        if (index !in mutableState.value.bands.indices) return
        updateBand(index) { EqUiState().bands[index] }
    }

    fun resetToneAndPreamp() {
        mutableState.update {
            it.copy(
                preampGainDb = 0f,
                bassGainDb = 0f,
                trebleGainDb = 0f,
                bassFrequencyHz = 85f,
                bassQFactor = 0.8f,
                trebleFrequencyHz = 10_000f,
                trebleQFactor = 0.8f,
                stereoWidthPercent = 100f,
                stereoBalance = 0f
            )
        }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    fun resetAll() {
        mutableState.update {
            it.copy(
                bands = EqUiState().bands,
                selectedBandIndex = 15,
                preampGainDb = 0f,
                bassGainDb = 0f,
                trebleGainDb = 0f,
                bassFrequencyHz = 85f,
                bassQFactor = 0.8f,
                trebleFrequencyHz = 10_000f,
                trebleQFactor = 0.8f,
                stereoWidthPercent = 100f,
                stereoBalance = 0f,
                selectedPresetId = null,
                presetMessage = "Reset to flat"
            )
        }
        audioProfileUpdates.trySend(Unit)
        recalculateResponse()
    }

    private fun resolvePlayerName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "No player detected"
        when (packageName) {
            "com.spotify.music" -> return "Spotify"
            "com.google.android.apps.youtube.music" -> return "YouTube Music"
            "com.google.android.youtube" -> return "YouTube"
            "com.pubg.imobile" -> return "BGMI"
            "com.tencent.ig" -> return "PUBG MOBILE"
        }
        return runCatching {
            val manager = getApplication<Application>().packageManager
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                manager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                manager.getApplicationInfo(packageName, 0)
            }
            manager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun recalculateResponse() {
        responseInvalidations.trySend(Unit)
    }

    private fun applyAudioProfileDelta(previous: EqAudioProfile, next: EqAudioProfile) {
        if (previous == next) return
        val changedBands = next.bands.indices.filter { index ->
            previous.bands.getOrNull(index) != next.bands.getOrNull(index)
        }

        if (changedBands.size > 2 || previous.bands.size != next.bands.size) {
            GlobalEqController.setProfile(next)
            return
        }
        if (previous.enabled != next.enabled) GlobalEqController.setMasterEnabled(next.enabled)
        changedBands.forEach { index -> GlobalEqController.setBand(index, next.bands[index]) }

        if (previous.bassGainDb != next.bassGainDb || previous.trebleGainDb != next.trebleGainDb) {
            GlobalEqController.setTone(next.bassGainDb, next.trebleGainDb)
        }
        if (previous.bassFrequencyHz != next.bassFrequencyHz ||
            previous.bassQFactor != next.bassQFactor ||
            previous.trebleFrequencyHz != next.trebleFrequencyHz ||
            previous.trebleQFactor != next.trebleQFactor
        ) {
            GlobalEqController.setToneParameters(
                next.bassFrequencyHz,
                next.bassQFactor,
                next.trebleFrequencyHz,
                next.trebleQFactor
            )
        }
        if (previous.smoothGains != next.smoothGains ||
            previous.bandsOverlapEnabled != next.bandsOverlapEnabled ||
            previous.bandsOverlapPercent != next.bandsOverlapPercent ||
            previous.avoidOverloading != next.avoidOverloading
        ) {
            GlobalEqController.setDspOptions(
                next.smoothGains,
                next.bandsOverlapEnabled,
                next.bandsOverlapPercent,
                next.avoidOverloading
            )
        }
        if (previous.preampGainDb != next.preampGainDb) {
            GlobalEqController.setPreampGain(next.preampGainDb)
        }
        if (previous.limiterEnabled != next.limiterEnabled ||
            previous.limiterThresholdDb != next.limiterThresholdDb ||
            previous.limiterReleaseMs != next.limiterReleaseMs
        ) {
            GlobalEqController.setLimiter(
                next.limiterEnabled,
                next.limiterThresholdDb,
                next.limiterReleaseMs
            )
        }
        if (previous.stereoWidthPercent != next.stereoWidthPercent) {
            GlobalEqController.setStereoWidthPercent(next.stereoWidthPercent)
        }
        if (previous.stereoBalance != next.stereoBalance) {
            GlobalEqController.setStereoBalance(next.stereoBalance)
        }
    }

    override fun onCleared() {
        audioProfileUpdates.close()
        responseInvalidations.close()
        fftAnalyzer.stop()
        GlobalEqController.removeListener(globalListener)
        DvcManager.removeGlobalListener(dvcListener)
        scope.cancel()
        super.onCleared()
    }

    private fun EqUiState.toAudioProfile(): EqAudioProfile = EqAudioProfile(
        enabled = masterEnabled,
        bands = bands.map(EqBandUiState::toAudioParameter),
        preampGainDb = preampGainDb,
        bassGainDb = bassGainDb,
        trebleGainDb = trebleGainDb,
        bassFrequencyHz = bassFrequencyHz,
        bassQFactor = bassQFactor,
        trebleFrequencyHz = trebleFrequencyHz,
        trebleQFactor = trebleQFactor,
        smoothGains = smoothGains,
        bandsOverlapEnabled = bandsOverlapEnabled,
        bandsOverlapPercent = bandsOverlapPercent,
        avoidOverloading = avoidOverloading,
        limiterEnabled = limiterEnabled,
        limiterThresholdDb = limiterThresholdDb,
        limiterReleaseMs = limiterReleaseMs,
        stereoWidthPercent = stereoWidthPercent,
        stereoBalance = stereoBalance
    ).sanitized()

    /** Build the live DSP profile without deleting inactive bands from saved presets. */
    private fun EqUiState.toEffectiveAudioProfile(): EqAudioProfile {
        val active = activeBandIndices()
        val profile = toAudioProfile()
        return profile.copy(
            bands = profile.bands.mapIndexed { index, band ->
                band.copy(enabled = band.enabled && index in active)
            }
        ).sanitized()
    }
}

private object EqResponseCalculator {
    fun calculate(state: EqUiState): List<FrequencyResponsePoint> {
        val sampleCount = 220
        val activeIndices = state.activeBandIndices()
        val ordered = state.bands.withIndex()
            .filter { it.index in activeIndices }
            .map { it.value }
            .sortedBy { it.frequencyHz }
        val coupling = if (state.bandsOverlapEnabled) state.bandsOverlapPercent / 100f * 0.35f else 0f
        return List(sampleCount) { index ->
            val t = index.toDouble() / (sampleCount - 1).toDouble()
            val frequency = (20.0 * 1000.0.pow(t)).toFloat()
            var gain = if (state.masterEnabled) state.preampGainDb else 0f

            if (state.masterEnabled) {
                val sigmaBase = if (state.smoothGains) 0.24f else 0.12f
                ordered.forEachIndexed { bandIndex, band ->
                    if (!band.enabled) return@forEachIndexed
                    val sigma = (
                        sigmaBase / band.qFactor.coerceIn(0.1f, 12f).coerceIn(0.55f, 1.8f) +
                            coupling * 0.36f
                        ).coerceIn(0.09f, 0.62f)
                    val distance = ln((frequency / band.frequencyHz).coerceAtLeast(0.0001f)) / 0.69314718056f
                    var weight = exp(-0.5f * (distance / sigma).pow(2))
                    if (coupling > 0f) {
                        ordered.getOrNull(bandIndex - 1)?.let { prev ->
                            val prevD = ln((frequency / prev.frequencyHz).coerceAtLeast(0.0001f)) / 0.69314718056f
                            weight += coupling * exp(-0.5f * (prevD / (sigma * 1.1f)).pow(2))
                        }
                        ordered.getOrNull(bandIndex + 1)?.let { next ->
                            val nextD = ln((frequency / next.frequencyHz).coerceAtLeast(0.0001f)) / 0.69314718056f
                            weight += coupling * exp(-0.5f * (nextD / (sigma * 1.1f)).pow(2))
                        }
                    }
                    gain += band.gainDb * weight
                }

                val bassOctaves = ln((frequency / state.bassFrequencyHz).coerceAtLeast(0.0001f)) / 0.69314718056f
                val bassSlope = (0.62f / state.bassQFactor).coerceIn(0.30f, 1.30f)
                gain += state.bassGainDb / 12f * 16f /
                    (1f + exp(bassOctaves / bassSlope))

                val trebleOctaves = ln((frequency / state.trebleFrequencyHz).coerceAtLeast(0.0001f)) / 0.69314718056f
                val trebleSlope = (0.62f / state.trebleQFactor).coerceIn(0.30f, 1.30f)
                gain += state.trebleGainDb / 12f * 12f /
                    (1f + exp(-trebleOctaves / trebleSlope))
            }

            FrequencyResponsePoint(frequency, gain.coerceIn(-36f, 36f))
        }
    }
}
