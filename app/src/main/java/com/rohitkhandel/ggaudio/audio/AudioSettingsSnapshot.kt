package com.rohitkhandel.ggaudio.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AudioSettingsSnapshot(
    val activePresetName: String = "Balanced",
    val gainsDb: List<Float> = listOf(2f, 3f, 2f, 1f, 0f, -1f, 0f, 1f, 2f, 1f),
    val preampDb: Float = 0f,
    val bassBoostPercent: Int = 0,
    val virtualizerPercent: Int = 0
) {
    init {
        require(activePresetName.isNotBlank() && activePresetName.length <= 32) {
            "Active preset name must contain 1 to 32 characters."
        }
        require(gainsDb.size == EqualizerDefaults.frequencies.size) {
            "Saved settings must contain exactly 10 EQ bands."
        }
        require(gainsDb.all { it.isFinite() && it in -12f..12f }) {
            "Saved band gains must be between -12 dB and +12 dB."
        }
        require(preampDb.isFinite() && preampDb in -12f..12f) {
            "Saved preamp must be between -12 dB and +12 dB."
        }
        require(bassBoostPercent in 0..100) { "Bass strength must be between 0 and 100." }
        require(virtualizerPercent in 0..100) { "Virtualizer strength must be between 0 and 100." }
    }

    fun toBands(): List<EqualizerBand> = EqualizerDefaults.flat().mapIndexed { index, band ->
        band.copy(gainDb = gainsDb[index])
    }
}

object AudioSettingsJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(settings: AudioSettingsSnapshot): String =
        json.encodeToString(settings)

    fun decode(value: String): AudioSettingsSnapshot {
        if (value.isBlank()) return AudioSettingsSnapshot()
        return runCatching {
            json.decodeFromString<AudioSettingsSnapshot>(value)
        }.getOrDefault(AudioSettingsSnapshot())
    }
}
