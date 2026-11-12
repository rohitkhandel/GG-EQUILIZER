package com.rohitkhandel.ggequalizer.ui

import com.rohitkhandel.ggequalizer.audio.DvcSnapshot
import com.rohitkhandel.ggequalizer.audio.EqBandParameter
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge
import com.rohitkhandel.ggequalizer.presets.PresetEntity
import kotlin.math.pow

data class EqBandUiState(
    val frequencyHz: Float,
    val gainDb: Float = 0f,
    val qFactor: Float = 1f,
    val filterType: Int = NativeDspBridge.FILTER_PEAKING,
    val enabled: Boolean = true
) {
    fun toAudioParameter() = EqBandParameter(
        frequencyHz = frequencyHz,
        gainDb = gainDb,
        qFactor = qFactor,
        filterType = filterType,
        enabled = enabled
    )
}

data class FrequencyResponsePoint(
    val frequencyHz: Float,
    val gainDb: Float
)

data class EqUiState(
    val bands: List<EqBandUiState> = defaultEqBandUiStates(),
    val selectedBandIndex: Int = 15,
    val preampGainDb: Float = 0f,
    val bassGainDb: Float = 0f,
    val trebleGainDb: Float = 0f,
    val bassFrequencyHz: Float = 85f,
    val bassQFactor: Float = 0.8f,
    val trebleFrequencyHz: Float = 10_000f,
    val trebleQFactor: Float = 0.8f,
    val limiterEnabled: Boolean = true,
    val limiterThresholdDb: Float = -0.5f,
    val limiterReleaseMs: Float = 40f,
    val avoidOverloading: Boolean = true,
    val smoothGains: Boolean = true,
    val bandsOverlapEnabled: Boolean = true,
    val bandsOverlapPercent: Float = 35f,
    val stereoWidthPercent: Float = 100f,
    val stereoBalance: Float = 0f,
    val dvcEnabled: Boolean = false,
    val dvcSnapshot: DvcSnapshot = DvcSnapshot(
        enabled = false,
        musicVolumeIndex = 0,
        musicVolumeMaximum = 0,
        musicVolumeFraction = 0f,
        estimatedHardwareAttenuationDb = -80f,
        estimatedBoostBudgetDb = 0f,
        preampCompensationDb = 0f,
        clippingProtectionActive = false
    ),
    val masterEnabled: Boolean = true,
    val activePlayerPackage: String? = null,
    val activePlayerName: String = "No player detected",
    val activeSessionCount: Int = 0,
    val activeSessionId: Int? = null,
    val attachmentSummary: String = "Waiting for an audio session",
    val responsePoints: List<FrequencyResponsePoint> = emptyList(),
    val visualizerEnabled: Boolean = false,
    val visualizerStyle: String = "BARS",
    val visualizerRefreshRateHz: Int = 30,
    val spectrumBars: List<Float> = List(64) { 0f },
    val bandCount: Int = 10,
    val showCurveOnSliders: Boolean = true,
    val accentKey: String = "cyan",
    val dvcMode: String = "SMART",
    val audioBlockSize: Int = 4096,
    val activePlayerTracking: Boolean = true,
    val presets: List<PresetEntity> = emptyList(),
    val selectedPresetId: String? = null,
    val presetMessage: String? = null
)

private fun defaultEqBandUiStates(): List<EqBandUiState> =
    List(NativeDspBridge.MAX_PARAMETRIC_BANDS) { index ->
        val position = index.toDouble() /
            (NativeDspBridge.MAX_PARAMETRIC_BANDS - 1).toDouble()
        EqBandUiState(frequencyHz = (20.0 * 1000.0.pow(position)).toFloat())
    }
