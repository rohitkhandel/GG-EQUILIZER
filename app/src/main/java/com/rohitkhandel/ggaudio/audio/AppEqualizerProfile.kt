package com.rohitkhandel.ggaudio.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AppEqualizerProfile(
    val packageName: String,
    val gainsDb: List<Float>,
    val preampDb: Float = 0f,
    val bassBoostPercent: Int = 0,
    val stereoWidthPercent: Int = 0
) {
    init {
        require(packageName.length in 3..255 && PACKAGE_PATTERN.matches(packageName)) {
            "A valid Android package name is required."
        }
        require(gainsDb.size == EqualizerDefaults.frequencies.size) {
            "An app profile must contain exactly 10 EQ bands."
        }
        require(gainsDb.all { it.isFinite() && it in -12f..12f }) {
            "Each app EQ band must be between -12 dB and +12 dB."
        }
        require(preampDb.isFinite() && preampDb in -24f..12f) {
            "App preamp must be between -24 dB and +12 dB."
        }
        require(bassBoostPercent in 0..100) { "App bass boost must be between 0 and 100." }
        require(stereoWidthPercent in 0..100) { "App stereo width must be between 0 and 100." }
    }

    fun toBands(): List<EqualizerBand> = EqualizerDefaults.flat().mapIndexed { index, band ->
        band.copy(gainDb = gainsDb[index])
    }

    fun toDspSettings(): DspSettingsSnapshot = DspSettingsSnapshot(
        bands = toBands(),
        preampDb = preampDb,
        bassBoostPercent = bassBoostPercent,
        stereoWidthPercent = stereoWidthPercent
    )

    companion object {
        private val PACKAGE_PATTERN =
            Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

        fun fromCurrentSettings(
            packageName: String,
            bands: List<EqualizerBand>,
            preampDb: Float,
            bassBoostPercent: Int,
            stereoWidthPercent: Int
        ): AppEqualizerProfile = AppEqualizerProfile(
            packageName = packageName,
            gainsDb = bands.map { it.gainDb },
            preampDb = preampDb,
            bassBoostPercent = bassBoostPercent,
            stereoWidthPercent = stereoWidthPercent
        )
    }
}

object AppEqualizerProfileJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(profiles: List<AppEqualizerProfile>): String = json.encodeToString(profiles)

    fun decode(value: String): List<AppEqualizerProfile> {
        if (value.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<AppEqualizerProfile>>(value)
                .distinctBy { it.packageName }
                .take(100)
        }.getOrDefault(emptyList())
    }
}

/** Selects a package-specific profile without modifying fallback settings for other apps. */
object AppEqualizerProfileResolver {
    fun resolve(
        packageName: String,
        defaultSettings: DspSettingsSnapshot,
        profiles: Map<String, DspSettingsSnapshot>
    ): DspSettingsSnapshot = profiles[packageName] ?: defaultSettings
}
