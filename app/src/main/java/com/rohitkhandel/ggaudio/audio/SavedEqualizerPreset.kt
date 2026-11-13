package com.rohitkhandel.ggaudio.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SavedEqualizerPreset(
    val id: String,
    val name: String,
    val gainsDb: List<Float>,
    val preampDb: Float = 0f
) {
    init {
        require(id.isNotBlank()) { "Preset ID cannot be blank." }
        require(name == name.trim() && name.length in 1..32) {
            "Preset name must contain 1 to 32 characters with no leading or trailing spaces."
        }
        require(name.none { it.isISOControl() }) { "Preset name contains unsupported control characters." }
        require(gainsDb.size == EqualizerDefaults.frequencies.size) { "A preset must contain exactly 10 EQ bands." }
        require(gainsDb.all { it.isFinite() && it in -12f..12f }) { "Each band gain must be between -12 dB and +12 dB." }
        require(preampDb.isFinite() && preampDb in -12f..12f) { "Preamp must be between -12 dB and +12 dB." }
    }

    fun toBands(): List<EqualizerBand> = EqualizerDefaults.flat().mapIndexed { index, band ->
        band.copy(gainDb = gainsDb[index])
    }
}

data class BuiltInEqualizerPreset(
    val name: String,
    val gainsDb: List<Float>
) {
    fun toBands(): List<EqualizerBand> = EqualizerDefaults.flat().mapIndexed { index, band ->
        band.copy(gainDb = gainsDb[index])
    }
}

object EqualizerPresetLibrary {
    val builtIn = listOf(
        BuiltInEqualizerPreset("Balanced", listOf(2f, 3f, 2f, 1f, 0f, -1f, 0f, 1f, 2f, 1f)),
        BuiltInEqualizerPreset("Bass boost", listOf(6f, 5f, 4f, 2f, 0f, -1f, 0f, 1f, 1f, 1f)),
        BuiltInEqualizerPreset("Vocal", listOf(-2f, -1f, 0f, 2f, 4f, 4f, 3f, 1f, 0f, -1f)),
        BuiltInEqualizerPreset("Treble", listOf(-1f, 0f, 0f, -1f, 0f, 1f, 2f, 4f, 5f, 5f)),
        BuiltInEqualizerPreset("Flat", List(10) { 0f })
    )

    val reservedNames: Set<String> = builtIn.map { it.name.lowercase() }.toSet()
}

object PresetJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(presets: List<SavedEqualizerPreset>): String =
        json.encodeToString(presets)

    fun decode(value: String): List<SavedEqualizerPreset> {
        if (value.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SavedEqualizerPreset>>(value)
                .distinctBy { it.id }
                .filterNot { it.name.lowercase() in EqualizerPresetLibrary.reservedNames }
        }.getOrDefault(emptyList())
    }
}
