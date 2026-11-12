package com.rohitkhandel.ggequalizer.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.IOException
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import com.rohitkhandel.ggequalizer.audio.DvcManager
import com.rohitkhandel.ggequalizer.audio.DvcSnapshot
import com.rohitkhandel.ggequalizer.audio.EqAudioProfile
import com.rohitkhandel.ggequalizer.audio.EqBandParameter
import com.rohitkhandel.ggequalizer.audio.GlobalEqController
import com.rohitkhandel.ggequalizer.audio.GlobalEqSnapshot
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge
import com.rohitkhandel.ggequalizer.audio.FftAnalyzer
import com.rohitkhandel.ggequalizer.presets.EqDatabase
import com.rohitkhandel.ggequalizer.presets.PresetEntity
import com.rohitkhandel.ggequalizer.presets.PresetBackup
import kotlinx.coroutines.flow.collect
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Owns screen state and forwards every live control change to the session
 * controller, which synchronizes both DynamicsProcessing and the native DSP
 * parameter engine. Frequency response is calculated using the same Cookbook
 * filter coefficient equations as the C++ biquad implementation.
 */
class EqViewModel(application: Application) : AndroidViewModel(application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(EqUiState())
    val uiState: StateFlow<EqUiState> = mutableState.asStateFlow()

    private var responseJob: Job? = null
    private val fftAnalyzer = FftAnalyzer(application)
    private val presetDao = EqDatabase.getInstance(application).presetDao()

    private val globalListener: (GlobalEqSnapshot) -> Unit = { snapshot ->
        val nextSessionId = snapshot.sessions.firstOrNull()?.sessionId
        val previousSessionId = mutableState.value.activeSessionId
        mutableState.update { current ->
            val packageName = snapshot.activePlayers.firstOrNull()
                ?: snapshot.sessions.firstOrNull()?.packageName
            current.copy(
                masterEnabled = snapshot.masterEnabled,
                activePlayerPackage = packageName,
                activePlayerName = resolvePlayerName(packageName),
                activeSessionCount = snapshot.sessions.size,
                activeSessionId = nextSessionId,
                attachmentSummary = snapshot.sessions
                    .groupBy { it.attachment }
                    .entries
                    .joinToString(" · ") { "${it.key}: ${it.value.size}" }
                    .ifBlank { "Waiting for an audio session" }
            )
        }
        if (mutableState.value.visualizerEnabled && previousSessionId != nextSessionId) {
            startSpectrumCapture(nextSessionId ?: 0)
        }
    }

    private val dvcListener: (DvcSnapshot) -> Unit = { snapshot ->
        mutableState.update { current ->
            current.copy(
                dvcEnabled = snapshot.enabled,
                dvcSnapshot = snapshot
            )
        }
    }

    init {
        GlobalEqController.initialize(application)
        GlobalEqController.addListener(globalListener)
        DvcManager.addGlobalListener(dvcListener)
        DvcManager.globalSnapshot()?.let(dvcListener)
        scope.launch {
            presetDao.observeAll().collect { presets ->
                mutableState.update { current -> current.copy(presets = presets) }
            }
        }
        recalculateResponse()
    }

    fun selectBand(index: Int) {
        if (index !in mutableState.value.bands.indices) return
        mutableState.update { it.copy(selectedBandIndex = index) }
    }

    fun setBandGain(index: Int, gainDb: Float) {
        updateBand(index) { it.copy(gainDb = gainDb.coerceIn(-12.0f, 12.0f)) }
    }

    fun setBandFrequency(index: Int, frequencyHz: Float) {
        updateBand(index) { it.copy(frequencyHz = frequencyHz.coerceIn(20.0f, 20_000.0f)) }
    }

    fun setBandQ(index: Int, qFactor: Float) {
        updateBand(index) { it.copy(qFactor = qFactor.coerceIn(0.1f, 10.0f)) }
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
            val bands = current.bands.toMutableList()
            bands[index] = updated
            current.copy(bands = bands)
        }
        GlobalEqController.setBand(index, updated.toAudioParameter())
        recalculateResponse()
    }

    fun setPreampGain(gainDb: Float) {
        val value = gainDb.coerceIn(-24.0f, 12.0f)
        mutableState.update { it.copy(preampGainDb = value) }
        GlobalEqController.setPreampGain(value)
        recalculateResponse()
    }

    fun setBassGain(gainDb: Float) {
        val value = gainDb.coerceIn(-12.0f, 12.0f)
        mutableState.update { it.copy(bassGainDb = value) }
        GlobalEqController.setTone(value, mutableState.value.trebleGainDb)
        recalculateResponse()
    }

    fun setTrebleGain(gainDb: Float) {
        val value = gainDb.coerceIn(-12.0f, 12.0f)
        mutableState.update { it.copy(trebleGainDb = value) }
        GlobalEqController.setTone(mutableState.value.bassGainDb, value)
        recalculateResponse()
    }

    fun setLimiterEnabled(enabled: Boolean) {
        mutableState.update { it.copy(limiterEnabled = enabled) }
        val state = mutableState.value
        GlobalEqController.setLimiter(
            enabled,
            state.limiterThresholdDb,
            state.limiterReleaseMs
        )
    }

    fun setLimiterThreshold(thresholdDb: Float) {
        val value = thresholdDb.coerceIn(-12.0f, 0.0f)
        mutableState.update { it.copy(limiterThresholdDb = value) }
        val state = mutableState.value
        GlobalEqController.setLimiter(
            state.limiterEnabled,
            value,
            state.limiterReleaseMs
        )
    }

    fun setLimiterRelease(releaseMs: Float) {
        val value = releaseMs.coerceIn(10.0f, 300.0f)
        mutableState.update { it.copy(limiterReleaseMs = value) }
        val state = mutableState.value
        GlobalEqController.setLimiter(
            state.limiterEnabled,
            state.limiterThresholdDb,
            value
        )
    }

    fun setDvcEnabled(enabled: Boolean) {
        DvcManager.setGlobalEnabled(enabled)
        DvcManager.globalSnapshot()?.let { dvcListener(it) }
            ?: mutableState.update { it.copy(dvcEnabled = enabled) }
    }

    fun setStereoWidthPercent(widthPercent: Float) {
        val value = widthPercent.coerceIn(0.0f, 200.0f)
        mutableState.update { it.copy(stereoWidthPercent = value) }
        GlobalEqController.setStereoWidthPercent(value)
    }

    fun setStereoBalance(balance: Float) {
        val value = balance.coerceIn(-1.0f, 1.0f)
        mutableState.update { it.copy(stereoBalance = value) }
        GlobalEqController.setStereoBalance(value)
    }

    fun setVisualizerEnabled(enabled: Boolean) {
        if (!enabled) {
            fftAnalyzer.stop()
            mutableState.update {
                it.copy(
                    visualizerEnabled = false,
                    spectrumBars = List(FftAnalyzer.BAR_COUNT) { 0.0f }
                )
            }
            return
        }
        startSpectrumCapture(mutableState.value.activeSessionId ?: 0)
    }

    fun setVisualizerRefreshRate(rateHz: Int) {
        val sanitized = rateHz.coerceIn(15, 60)
        mutableState.update { it.copy(visualizerRefreshRateHz = sanitized) }
        if (mutableState.value.visualizerEnabled) {
            startSpectrumCapture(mutableState.value.activeSessionId ?: 0)
        }
    }

    private fun startSpectrumCapture(sessionId: Int) {
        val requestedRefreshRate = mutableState.value.visualizerRefreshRateHz
        val started = fftAnalyzer.start(sessionId, requestedRefreshRate) { bars ->
            scope.launch(Dispatchers.Main.immediate) {
                mutableState.update { current ->
                    if (current.visualizerEnabled) current.copy(spectrumBars = bars)
                    else current
                }
            }
        }
        mutableState.update { current ->
            current.copy(
                visualizerEnabled = started,
                activeSessionId = fftAnalyzer.attachedSessionId ?: current.activeSessionId,
                spectrumBars = if (started) current.spectrumBars
                else List(FftAnalyzer.BAR_COUNT) { 0.0f },
                presetMessage = if (started) current.presetMessage
                else "Spectrum capture unavailable. Check microphone permission or audio-session access."
            )
        }
    }

    fun applyPreset(preset: PresetEntity) {
        val profile = preset.toAudioProfile().copy(
            stereoWidthPercent = preset.stereoWidthPercent,
            stereoBalance = preset.stereoBalance
        ).sanitized()
        GlobalEqController.setProfile(profile)
        DvcManager.setGlobalEnabled(preset.dvcEnabled)
        DvcManager.globalSnapshot()?.let(dvcListener)
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

    fun saveCurrentPreset(name: String) {
        val current = mutableState.value
        val preset = PresetEntity.fromProfile(
            presetId = "custom_${UUID.randomUUID()}",
            presetName = name,
            description = "Saved from current GG Equalizer settings",
            profile = current.toAudioProfile(),
            stereoWidthPercent = current.stereoWidthPercent,
            stereoBalance = current.stereoBalance,
            dvcEnabled = current.dvcEnabled
        )
        scope.launch {
            presetDao.upsert(preset)
            mutableState.update { it.copy(selectedPresetId = preset.presetId, presetMessage = "Saved ${preset.presetName}") }
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
                            onFailure = { error -> error.message ?: "Preset backup failed" }
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
                            onFailure = { error -> error.message ?: "Preset restore failed" }
                        )
                    )
                }
            }
        }
    }

    fun currentAudioProfile(): EqAudioProfile = mutableState.value.toAudioProfile()

    fun setMasterEnabled(enabled: Boolean) {
        GlobalEqController.setMasterEnabled(enabled)
        mutableState.update { it.copy(masterEnabled = enabled) }
        recalculateResponse()
    }

    fun resetBand(index: Int) {
        if (index !in mutableState.value.bands.indices) return
        val defaults = EqUiState().bands[index]
        updateBand(index) { defaults }
    }

    fun resetToneAndPreamp() {
        mutableState.update {
            it.copy(preampGainDb = 0.0f, bassGainDb = 0.0f, trebleGainDb = 0.0f)
        }
        GlobalEqController.setPreampGain(0.0f)
        GlobalEqController.setTone(0.0f, 0.0f)
        recalculateResponse()
    }

    fun resetAllBands() {
        val defaults = EqUiState().bands
        mutableState.update { it.copy(bands = defaults, selectedBandIndex = 15) }
        GlobalEqController.setProfile(
            EqAudioProfile(
                enabled = mutableState.value.masterEnabled,
                bands = defaults.map(EqBandUiState::toAudioParameter),
                preampGainDb = mutableState.value.preampGainDb,
                bassGainDb = mutableState.value.bassGainDb,
                trebleGainDb = mutableState.value.trebleGainDb,
                limiterEnabled = mutableState.value.limiterEnabled,
                limiterThresholdDb = mutableState.value.limiterThresholdDb,
                limiterReleaseMs = mutableState.value.limiterReleaseMs
            )
        )
        recalculateResponse()
    }

    private fun resolvePlayerName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "No player detected"
        val knownName = when (packageName) {
            "com.spotify.music" -> "Spotify"
            "com.google.android.apps.youtube.music" -> "YouTube Music"
            "com.google.android.youtube" -> "YouTube"
            "com.pubg.imobile" -> "BGMI"
            "com.tencent.ig" -> "PUBG MOBILE"
            else -> null
        }
        if (knownName != null) return knownName
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
        responseJob?.cancel()
        val state = mutableState.value
        responseJob = scope.launch {
            val points = withContext(Dispatchers.Default) {
                EqResponseCalculator.calculate(state)
            }
            mutableState.update { current -> current.copy(responsePoints = points) }
        }
    }

    override fun onCleared() {
        fftAnalyzer.stop()
        GlobalEqController.removeListener(globalListener)
        DvcManager.removeGlobalListener(dvcListener)
        scope.cancel()
        super.onCleared()
    }
}

/** Cookbook IIR transfer-function response for a cascade of biquad sections. */
private fun EqUiState.toAudioProfile(): EqAudioProfile = EqAudioProfile(
    enabled = masterEnabled,
    bands = bands.map(EqBandUiState::toAudioParameter),
    preampGainDb = preampGainDb,
    bassGainDb = bassGainDb,
    trebleGainDb = trebleGainDb,
    limiterEnabled = limiterEnabled,
    limiterThresholdDb = limiterThresholdDb,
    limiterReleaseMs = limiterReleaseMs,
    stereoWidthPercent = stereoWidthPercent,
    stereoBalance = stereoBalance
).sanitized()

private object EqResponseCalculator {
    private const val SAMPLE_RATE = 48_000.0
    private const val PI_D = PI

    private data class Coeff(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    )

    fun calculate(state: EqUiState): List<FrequencyResponsePoint> {
        val sampleCount = 220
        val enabledBands = state.bands.filter { it.enabled }.map { band ->
            coefficients(
                band.filterType,
                band.frequencyHz.toDouble(),
                band.gainDb.toDouble(),
                band.qFactor.toDouble()
            )
        }
        val bass = coefficients(1, 120.0, state.bassGainDb.toDouble(), 1.0)
        val treble = coefficients(2, 8_000.0, state.trebleGainDb.toDouble(), 1.0)

        return List(sampleCount) { index ->
            val progress = index.toDouble() / (sampleCount - 1).toDouble()
            val frequency = 20.0 * 1000.0.pow(progress)
            var gainDb = if (state.masterEnabled) state.preampGainDb.toDouble() else 0.0

            if (state.masterEnabled) {
                enabledBands.forEach { gainDb += magnitudeDb(it, frequency) }
                gainDb += magnitudeDb(bass, frequency)
                gainDb += magnitudeDb(treble, frequency)
            }

            FrequencyResponsePoint(
                frequencyHz = frequency.toFloat(),
                gainDb = gainDb.coerceIn(-30.0, 30.0).toFloat()
            )
        }
    }

    private fun coefficients(
        filterType: Int,
        frequencyHz: Double,
        gainDb: Double,
        qFactor: Double
    ): Coeff {
        if (abs(gainDb) < 0.0001) return Coeff(1.0, 0.0, 0.0, 0.0, 0.0)

        val frequency = frequencyHz.coerceIn(10.0, SAMPLE_RATE * 0.49)
        val gain = gainDb.coerceIn(-24.0, 24.0)
        val q = qFactor.coerceIn(0.1, 10.0)
        val amplitude = 10.0.pow(gain / 40.0)
        val omega = 2.0 * PI_D * frequency / SAMPLE_RATE
        val cosine = cos(omega)
        val sine = sin(omega)

        var b0: Double
        var b1: Double
        var b2: Double
        var a0: Double
        var a1: Double
        var a2: Double

        when (filterType) {
            NativeDspBridge.FILTER_LOW_SHELF,
            NativeDspBridge.FILTER_HIGH_SHELF -> {
                val shelfSlope = (1.0 / q).coerceIn(0.1, 1.0)
                val radicand =
                    (amplitude + 1.0 / amplitude) * (1.0 / shelfSlope - 1.0) + 2.0
                val shelfAlpha = sine / 2.0 * sqrt(radicand.coerceAtLeast(0.0))
                val beta = 2.0 * sqrt(amplitude) * shelfAlpha

                if (filterType == NativeDspBridge.FILTER_LOW_SHELF) {
                    b0 = amplitude * ((amplitude + 1.0) -
                        (amplitude - 1.0) * cosine + beta)
                    b1 = 2.0 * amplitude * ((amplitude - 1.0) -
                        (amplitude + 1.0) * cosine)
                    b2 = amplitude * ((amplitude + 1.0) -
                        (amplitude - 1.0) * cosine - beta)
                    a0 = (amplitude + 1.0) +
                        (amplitude - 1.0) * cosine + beta
                    a1 = -2.0 * ((amplitude - 1.0) +
                        (amplitude + 1.0) * cosine)
                    a2 = (amplitude + 1.0) +
                        (amplitude - 1.0) * cosine - beta
                } else {
                    b0 = amplitude * ((amplitude + 1.0) +
                        (amplitude - 1.0) * cosine + beta)
                    b1 = -2.0 * amplitude * ((amplitude - 1.0) +
                        (amplitude + 1.0) * cosine)
                    b2 = amplitude * ((amplitude + 1.0) +
                        (amplitude - 1.0) * cosine - beta)
                    a0 = (amplitude + 1.0) -
                        (amplitude - 1.0) * cosine + beta
                    a1 = 2.0 * ((amplitude - 1.0) -
                        (amplitude + 1.0) * cosine)
                    a2 = (amplitude + 1.0) -
                        (amplitude - 1.0) * cosine - beta
                }
            }
            else -> {
                val alpha = sine / (2.0 * q)
                b0 = 1.0 + alpha * amplitude
                b1 = -2.0 * cosine
                b2 = 1.0 - alpha * amplitude
                a0 = 1.0 + alpha / amplitude
                a1 = -2.0 * cosine
                a2 = 1.0 - alpha / amplitude
            }
        }

        if (abs(a0) < 1.0e-12) return Coeff(1.0, 0.0, 0.0, 0.0, 0.0)
        val inverse = 1.0 / a0
        return Coeff(
            b0 * inverse,
            b1 * inverse,
            b2 * inverse,
            a1 * inverse,
            a2 * inverse
        )
    }

    private fun magnitudeDb(coeff: Coeff, frequencyHz: Double): Double {
        val omega = 2.0 * PI_D * frequencyHz / SAMPLE_RATE
        val c1 = cos(omega)
        val s1 = sin(omega)
        val c2 = cos(2.0 * omega)
        val s2 = sin(2.0 * omega)

        val numeratorReal = coeff.b0 + coeff.b1 * c1 + coeff.b2 * c2
        val numeratorImaginary = -coeff.b1 * s1 - coeff.b2 * s2
        val denominatorReal = 1.0 + coeff.a1 * c1 + coeff.a2 * c2
        val denominatorImaginary = -coeff.a1 * s1 - coeff.a2 * s2

        val numeratorMagnitude = sqrt(
            numeratorReal * numeratorReal +
                numeratorImaginary * numeratorImaginary
        )
        val denominatorMagnitude = sqrt(
            denominatorReal * denominatorReal +
                denominatorImaginary * denominatorImaginary
        ).coerceAtLeast(1.0e-12)

        val magnitude = (numeratorMagnitude / denominatorMagnitude).coerceAtLeast(1.0e-6)
        return 20.0 * log10(magnitude)
    }
}
