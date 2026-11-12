package com.rohitkhandel.ggequalizer.presets

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rohitkhandel.ggequalizer.audio.EqAudioProfile
import com.rohitkhandel.ggequalizer.audio.EqBandParameter
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge
import org.json.JSONArray
import org.json.JSONObject

@Entity(
    tableName = "presets",
    indices = [Index(value = ["isBuiltIn"]), Index(value = ["presetName"])]
)
data class PresetEntity(
    @PrimaryKey
    val presetId: String,
    val presetName: String,
    val description: String,
    val isBuiltIn: Boolean,
    val bandsJson: String,
    val preampGainDb: Float,
    val bassGainDb: Float,
    val trebleGainDb: Float,
    val limiterEnabled: Boolean,
    val limiterThresholdDb: Float,
    val limiterReleaseMs: Float,
    val stereoWidthPercent: Float,
    val stereoBalance: Float,
    val dvcEnabled: Boolean,
    val boundOutputDeviceType: String?,
    val boundOutputDeviceName: String?,
    val updatedAtEpochMs: Long
) {
    fun toAudioProfile(): EqAudioProfile {
        val decodedBands = PresetBandJson.decode(bandsJson)
        val defaults = EqAudioProfile.defaults()
        val bands = if (decodedBands.size == NativeDspBridge.MAX_PARAMETRIC_BANDS) {
            decodedBands
        } else {
            defaults.bands
        }
        return defaults.copy(
            bands = bands,
            preampGainDb = preampGainDb,
            bassGainDb = bassGainDb,
            trebleGainDb = trebleGainDb,
            limiterEnabled = limiterEnabled,
            limiterThresholdDb = limiterThresholdDb,
            limiterReleaseMs = limiterReleaseMs
        ).sanitized()
    }

    companion object {
        fun fromProfile(
            presetId: String,
            presetName: String,
            description: String,
            profile: EqAudioProfile,
            isBuiltIn: Boolean = false,
            stereoWidthPercent: Float = 100.0f,
            stereoBalance: Float = 0.0f,
            dvcEnabled: Boolean = false,
            boundOutputDeviceType: String? = null,
            boundOutputDeviceName: String? = null
        ): PresetEntity {
            val clean = profile.sanitized()
            return PresetEntity(
                presetId = presetId,
                presetName = presetName.trim().ifBlank { "Custom preset" },
                description = description,
                isBuiltIn = isBuiltIn,
                bandsJson = PresetBandJson.encode(clean.bands),
                preampGainDb = clean.preampGainDb,
                bassGainDb = clean.bassGainDb,
                trebleGainDb = clean.trebleGainDb,
                limiterEnabled = clean.limiterEnabled,
                limiterThresholdDb = clean.limiterThresholdDb,
                limiterReleaseMs = clean.limiterReleaseMs,
                stereoWidthPercent = stereoWidthPercent.coerceIn(0.0f, 200.0f),
                stereoBalance = stereoBalance.coerceIn(-1.0f, 1.0f),
                dvcEnabled = dvcEnabled,
                boundOutputDeviceType = boundOutputDeviceType,
                boundOutputDeviceName = boundOutputDeviceName,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        }
    }
}

object PresetBandJson {
    fun encode(bands: List<EqBandParameter>): String {
        val array = JSONArray()
        bands.take(NativeDspBridge.MAX_PARAMETRIC_BANDS).forEach { band ->
            val clean = band.sanitized()
            array.put(
                JSONObject()
                    .put("frequencyHz", clean.frequencyHz.toDouble())
                    .put("gainDb", clean.gainDb.toDouble())
                    .put("qFactor", clean.qFactor.toDouble())
                    .put("filterType", clean.filterType)
                    .put("enabled", clean.enabled)
            )
        }
        return array.toString()
    }

    fun decode(json: String): List<EqBandParameter> {
        return runCatching {
            val array = JSONArray(json)
            buildList {
                for (index in 0 until minOf(array.length(), NativeDspBridge.MAX_PARAMETRIC_BANDS)) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        EqBandParameter(
                            frequencyHz = item.optDouble("frequencyHz", 1000.0).toFloat(),
                            gainDb = item.optDouble("gainDb", 0.0).toFloat(),
                            qFactor = item.optDouble("qFactor", 1.0).toFloat(),
                            filterType = item.optInt("filterType", NativeDspBridge.FILTER_PEAKING),
                            enabled = item.optBoolean("enabled", true)
                        ).sanitized()
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}

object BuiltInPresetFactory {
    fun createAll(): List<PresetEntity> {
        val base = EqAudioProfile.defaults().bands
        val definitions = listOf(
            Definition(
                id = "builtin_flat",
                name = "Flat",
                description = "Uncoloured response with all 32 bands at zero gain",
                gains = emptyMap(),
                preamp = 0.0f
            ),
            Definition(
                id = "builtin_bass_extreme",
                name = "Bass Extreme",
                description = "Strong low-frequency emphasis with reserved preamp headroom",
                gains = mapOf(
                    0 to 7.0f, 1 to 7.0f, 2 to 6.0f, 3 to 5.5f,
                    4 to 4.5f, 5 to 3.5f, 6 to 2.0f, 7 to 1.0f
                ),
                preamp = -7.0f
            ),
            Definition(
                id = "builtin_vocal_boost",
                name = "Vocal Boost",
                description = "Gentle low cut with a clear presence lift",
                gains = mapOf(
                    0 to -2.0f, 1 to -2.0f, 2 to -1.5f, 13 to 1.0f,
                    14 to 2.0f, 15 to 3.0f, 16 to 3.0f, 17 to 2.0f,
                    18 to 1.0f, 25 to -1.0f
                ),
                preamp = -1.5f
            ),
            Definition(
                id = "builtin_harman_target",
                name = "Harman Target",
                description = "A restrained consumer target-inspired tonal balance",
                gains = mapOf(
                    0 to 2.5f, 1 to 2.5f, 2 to 2.0f, 3 to 1.5f,
                    4 to 1.0f, 13 to -0.5f, 14 to -1.0f, 15 to -1.5f,
                    16 to -1.5f, 17 to -0.5f, 26 to 1.0f, 27 to 1.5f
                ),
                preamp = -2.5f
            ),
            Definition(
                id = "builtin_gaming_footsteps",
                name = "Gaming Footsteps",
                description = "Reduces deep bass and raises upper-mid detail",
                gains = mapOf(
                    0 to -5.0f, 1 to -4.0f, 2 to -3.0f, 3 to -2.0f,
                    8 to 1.0f, 12 to 2.0f, 13 to 3.0f, 14 to 3.5f,
                    15 to 3.0f, 16 to 2.5f, 17 to 1.5f, 24 to -0.5f
                ),
                preamp = -3.5f
            )
        )

        return definitions.map { definition ->
            val bands = base.mapIndexed { index, band ->
                band.copy(gainDb = definition.gains[index] ?: 0.0f)
            }
            val profile = EqAudioProfile.defaults().copy(
                bands = bands,
                preampGainDb = definition.preamp
            )
            PresetEntity.fromProfile(
                presetId = definition.id,
                presetName = definition.name,
                description = definition.description,
                profile = profile,
                isBuiltIn = true
            )
        }
    }

    private data class Definition(
        val id: String,
        val name: String,
        val description: String,
        val gains: Map<Int, Float>,
        val preamp: Float
    )
}
