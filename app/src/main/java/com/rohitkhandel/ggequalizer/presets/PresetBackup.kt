package com.rohitkhandel.ggequalizer.presets

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, human-readable backup format for complete Room preset records.
 * The bands JSON is retained intact so filter type, Q, frequency and enabled
 * state round-trip without lossy conversion.
 */
object PresetBackup {
    private const val FORMAT = "gg-equalizer-preset-backup"
    private const val VERSION = 1

    fun encode(presets: List<PresetEntity>): String {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject()
                    .put("presetId", preset.presetId)
                    .put("presetName", preset.presetName)
                    .put("description", preset.description)
                    .put("isBuiltIn", preset.isBuiltIn)
                    .put("bandsJson", preset.bandsJson)
                    .put("preampGainDb", preset.preampGainDb.toDouble())
                    .put("bassGainDb", preset.bassGainDb.toDouble())
                    .put("trebleGainDb", preset.trebleGainDb.toDouble())
                    .put("limiterEnabled", preset.limiterEnabled)
                    .put("limiterThresholdDb", preset.limiterThresholdDb.toDouble())
                    .put("limiterReleaseMs", preset.limiterReleaseMs.toDouble())
                    .put("stereoWidthPercent", preset.stereoWidthPercent.toDouble())
                    .put("stereoBalance", preset.stereoBalance.toDouble())
                    .put("dvcEnabled", preset.dvcEnabled)
                    .put("boundOutputDeviceType", preset.boundOutputDeviceType)
                    .put("boundOutputDeviceName", preset.boundOutputDeviceName)
                    .put("updatedAtEpochMs", preset.updatedAtEpochMs)
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("presets", array)
            .toString(2)
    }

    fun decode(json: String): List<PresetEntity> {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) {
            "This file is not a GG Equalizer preset backup."
        }
        require(root.optInt("version", -1) in 1..VERSION) {
            "This backup version is not supported by this app."
        }
        val array = root.optJSONArray("presets")
            ?: throw IllegalArgumentException("The backup contains no preset list.")
        require(array.length() <= MAX_PRESETS) {
            "The backup contains too many presets."
        }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val presetId = item.optString("presetId").trim()
                val presetName = item.optString("presetName").trim()
                val bandsJson = item.optString("bandsJson")
                if (presetId.isBlank() || presetName.isBlank() || bandsJson.isBlank()) continue

                val validatedBands = PresetBandJson.decode(bandsJson)
                if (validatedBands.isEmpty()) continue
                add(
                    PresetEntity(
                        presetId = presetId,
                        presetName = presetName,
                        description = item.optString("description"),
                        isBuiltIn = item.optBoolean("isBuiltIn", false),
                        bandsJson = PresetBandJson.encode(validatedBands),
                        preampGainDb = item.optDouble("preampGainDb", 0.0).toFloat(),
                        bassGainDb = item.optDouble("bassGainDb", 0.0).toFloat(),
                        trebleGainDb = item.optDouble("trebleGainDb", 0.0).toFloat(),
                        limiterEnabled = item.optBoolean("limiterEnabled", true),
                        limiterThresholdDb = item.optDouble("limiterThresholdDb", -1.0).toFloat(),
                        limiterReleaseMs = item.optDouble("limiterReleaseMs", 80.0).toFloat(),
                        stereoWidthPercent = item.optDouble("stereoWidthPercent", 100.0).toFloat(),
                        stereoBalance = item.optDouble("stereoBalance", 0.0).toFloat(),
                        dvcEnabled = item.optBoolean("dvcEnabled", false),
                        boundOutputDeviceType = item.optString("boundOutputDeviceType").takeIf(String::isNotBlank),
                        boundOutputDeviceName = item.optString("boundOutputDeviceName").takeIf(String::isNotBlank),
                        updatedAtEpochMs = item.optLong("updatedAtEpochMs", System.currentTimeMillis())
                    )
                )
            }
        }.also { require(it.isNotEmpty()) { "No valid presets were found in this backup." } }
    }

    private const val MAX_PRESETS = 500
}
