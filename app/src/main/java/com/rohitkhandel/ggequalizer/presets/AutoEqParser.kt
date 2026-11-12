package com.rohitkhandel.ggequalizer.presets

import android.content.Context
import android.net.Uri
import com.rohitkhandel.ggequalizer.audio.EqAudioProfile
import com.rohitkhandel.ggequalizer.audio.EqBandParameter
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.math.ln

enum class AutoEqFormat {
    GRAPHIC_EQ_TEXT,
    PARAMETRIC_TEXT,
    POWERAMP_JSON
}

data class ParsedAutoEqPreset(
    val name: String,
    val profile: EqAudioProfile,
    val format: AutoEqFormat,
    val filterCount: Int
)

/**
 * Imports and exports AutoEQ GraphicEQ/Filter text and a documented JSON
 * interchange format. Unknown JSON keys are ignored so older preset files
 * remain importable. A SAF Uri is always opened through ContentResolver.
 */
object AutoEqParser {
    private val graphicPairRegex = Regex(
        """([0-9]+(?:\.[0-9]+)?)\s+([+-]?[0-9]+(?:\.[0-9]+)?)\s*(?:dB)?""",
        RegexOption.IGNORE_CASE
    )
    private val parametricFilterRegex = Regex(
        """(?im)^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+(PK|LS|HS)\s+Fc\s*=?\s*([0-9]+(?:\.[0-9]+)?)\s*Hz\s+Gain\s*=?\s*([+-]?[0-9]+(?:\.[0-9]+)?)\s*dB\s+Q\s*=?\s*([0-9]+(?:\.[0-9]+)?)"""
    )
    private val preampRegex = Regex(
        """(?im)^\s*Preamp\s*:\s*([+-]?[0-9]+(?:\.[0-9]+)?)\s*dB"""
    )

    fun parseText(text: String, presetName: String = "Imported AutoEQ"): ParsedAutoEqPreset {
        require(text.isNotBlank()) { "The selected file is empty." }
        val preamp = preampRegex.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0.0f
        val graphicHeader = Regex("""(?im)^\s*GraphicEQ\s*:""").find(text)

        if (graphicHeader != null) {
            val afterHeader = text.substring(graphicHeader.range.last + 1)
            val graphicSection = afterHeader
                .split(Regex("""(?im)\n\s*(?=Filter\s*\d*\s*:|Preamp\s*:)"""), limit = 2)
                .firstOrNull()
                .orEmpty()
            val samples = graphicPairRegex.findAll(graphicSection).mapNotNull { match ->
                val frequency = match.groupValues[1].toFloatOrNull()
                val gain = match.groupValues[2].toFloatOrNull()
                if (frequency == null || gain == null || frequency <= 0.0f) null
                else frequency to gain
            }.sortedBy { it.first }.distinctBy { it.first }.toList()

            require(samples.size >= 2) {
                "GraphicEQ header found, but no usable frequency/gain pairs were parsed."
            }

            val defaultBands = EqAudioProfile.defaults().bands
            val mappedBands = defaultBands.map { band ->
                band.copy(gainDb = interpolateLogFrequency(samples, band.frequencyHz))
            }
            return ParsedAutoEqPreset(
                name = presetName.ifBlank { "Imported GraphicEQ" },
                profile = EqAudioProfile.defaults().copy(
                    bands = mappedBands,
                    preampGainDb = preamp
                ).sanitized(),
                format = AutoEqFormat.GRAPHIC_EQ_TEXT,
                filterCount = samples.size
            )
        }

        val filters = parametricFilterRegex.findAll(text).mapNotNull { match ->
            val enabled = match.groupValues[1].equals("ON", ignoreCase = true)
            val type = when (match.groupValues[2].uppercase(Locale.US)) {
                "LS" -> NativeDspBridge.FILTER_LOW_SHELF
                "HS" -> NativeDspBridge.FILTER_HIGH_SHELF
                else -> NativeDspBridge.FILTER_PEAKING
            }
            val frequency = match.groupValues[3].toFloatOrNull() ?: return@mapNotNull null
            val gain = match.groupValues[4].toFloatOrNull() ?: return@mapNotNull null
            val q = match.groupValues[5].toFloatOrNull() ?: return@mapNotNull null
            EqBandParameter(
                frequencyHz = frequency,
                gainDb = gain,
                qFactor = q,
                filterType = type,
                enabled = enabled
            ).sanitized()
        }.sortedBy { it.frequencyHz }.toList()

        require(filters.isNotEmpty()) {
            "No supported GraphicEQ data or PK/LS/HS Filter entries were found."
        }
        val defaults = EqAudioProfile.defaults().bands
        val mapped = filters.take(NativeDspBridge.MAX_PARAMETRIC_BANDS).toMutableList()
        while (mapped.size < NativeDspBridge.MAX_PARAMETRIC_BANDS) {
            mapped.add(defaults[mapped.size].copy(enabled = false))
        }

        return ParsedAutoEqPreset(
            name = presetName.ifBlank { "Imported Parametric EQ" },
            profile = EqAudioProfile.defaults().copy(
                bands = mapped,
                preampGainDb = preamp
            ).sanitized(),
            format = AutoEqFormat.PARAMETRIC_TEXT,
            filterCount = filters.size
        )
    }

    fun parsePowerampJson(json: String, presetName: String = "Imported Poweramp preset"): ParsedAutoEqPreset {
        val root = JSONObject(json)
        val name = root.optString("presetName", root.optString("name", presetName))
        val sourceBands = root.optJSONArray("bands")
            ?: root.optJSONArray("eqBands")
            ?: root.optJSONArray("filters")
            ?: JSONArray()

        val defaults = EqAudioProfile.defaults().bands
        val parsedBands = ArrayList<EqBandParameter>(NativeDspBridge.MAX_PARAMETRIC_BANDS)

        for (index in 0 until minOf(sourceBands.length(), NativeDspBridge.MAX_PARAMETRIC_BANDS)) {
            val item = sourceBands.opt(index)
            val fallback = defaults[index]
            val band = when (item) {
                is JSONObject -> EqBandParameter(
                    frequencyHz = item.optDouble(
                        "frequencyHz",
                        item.optDouble("frequency", fallback.frequencyHz.toDouble())
                    ).toFloat(),
                    gainDb = item.optDouble(
                        "gainDb",
                        item.optDouble("gain", item.optDouble("level", 0.0))
                    ).toFloat(),
                    qFactor = item.optDouble("qFactor", item.optDouble("q", 1.0)).toFloat(),
                    filterType = item.optInt("filterType", NativeDspBridge.FILTER_PEAKING),
                    enabled = item.optBoolean("enabled", true)
                ).sanitized()
                is Number -> fallback.copy(gainDb = item.toFloat().coerceIn(-24.0f, 24.0f))
                else -> fallback.copy(enabled = false)
            }
            parsedBands.add(band)
        }

        if (parsedBands.isEmpty()) {
            val graphic = root.optString("GraphicEQ", "")
            if (graphic.isNotBlank()) {
                return parseText("GraphicEQ: $graphic", name)
            }
        }

        while (parsedBands.size < NativeDspBridge.MAX_PARAMETRIC_BANDS) {
            parsedBands.add(defaults[parsedBands.size].copy(enabled = false))
        }

        val profile = EqAudioProfile.defaults().copy(
            bands = parsedBands,
            preampGainDb = root.optDouble("preampGainDb", root.optDouble("preamp", 0.0)).toFloat(),
            bassGainDb = root.optDouble("bassGainDb", root.optDouble("bass", 0.0)).toFloat(),
            trebleGainDb = root.optDouble("trebleGainDb", root.optDouble("treble", 0.0)).toFloat(),
            limiterEnabled = root.optBoolean("limiterEnabled", true),
            limiterThresholdDb = root.optDouble("limiterThresholdDb", -1.0).toFloat(),
            limiterReleaseMs = root.optDouble("limiterReleaseMs", 80.0).toFloat()
        ).sanitized()

        return ParsedAutoEqPreset(
            name = name.ifBlank { presetName },
            profile = profile,
            format = AutoEqFormat.POWERAMP_JSON,
            filterCount = parsedBands.count { it.enabled }
        )
    }

    fun parseUri(
        context: Context,
        uri: Uri,
        fallbackName: String = "Imported AutoEQ"
    ): ParsedAutoEqPreset {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: throw IOException("Android could not open the selected preset file.")

        val trimmed = text.trimStart()
        return if (trimmed.startsWith("{")) {
            parsePowerampJson(text, fallbackName)
        } else {
            parseText(text, fallbackName)
        }
    }

    fun exportPowerampJson(
        context: Context,
        uri: Uri,
        presetName: String,
        profile: EqAudioProfile,
        stereoWidthPercent: Float = 100.0f,
        stereoBalance: Float = 0.0f,
        dvcEnabled: Boolean = false
    ) {
        val clean = profile.sanitized()
        val bands = JSONArray()
        clean.bands.forEach { band ->
            bands.put(
                JSONObject()
                    .put("frequencyHz", band.frequencyHz.toDouble())
                    .put("gainDb", band.gainDb.toDouble())
                    .put("qFactor", band.qFactor.toDouble())
                    .put("filterType", band.filterType)
                    .put("enabled", band.enabled)
            )
        }
        val root = JSONObject()
            .put("format", "gg-equalizer-poweramp-compatible-v1")
            .put("presetName", presetName)
            .put("preampGainDb", clean.preampGainDb.toDouble())
            .put("bassGainDb", clean.bassGainDb.toDouble())
            .put("trebleGainDb", clean.trebleGainDb.toDouble())
            .put("limiterEnabled", clean.limiterEnabled)
            .put("limiterThresholdDb", clean.limiterThresholdDb.toDouble())
            .put("limiterReleaseMs", clean.limiterReleaseMs.toDouble())
            .put("stereoWidthPercent", stereoWidthPercent.toDouble())
            .put("stereoBalance", stereoBalance.toDouble())
            .put("dvcEnabled", dvcEnabled)
            .put("bands", bands)

        writeText(context, uri, root.toString(2))
    }

    fun exportGraphicEqText(
        context: Context,
        uri: Uri,
        profile: EqAudioProfile
    ) {
        val clean = profile.sanitized()
        val line = clean.bands.joinToString("; ") { band ->
            String.format(Locale.US, "%.2f %.2f", band.frequencyHz, band.gainDb)
        }
        val text = buildString {
            appendLine(String.format(Locale.US, "Preamp: %.2f dB", clean.preampGainDb))
            append("GraphicEQ: ")
            append(line)
            appendLine()
        }
        writeText(context, uri, text)
    }

    private fun writeText(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(text)
        } ?: throw IOException("Android could not open the destination file.")
    }

    private fun interpolateLogFrequency(
        samples: List<Pair<Float, Float>>,
        targetFrequencyHz: Float
    ): Float {
        val targetLog = ln(targetFrequencyHz.coerceAtLeast(1.0f).toDouble())
        if (targetFrequencyHz <= samples.first().first) return samples.first().second
        if (targetFrequencyHz >= samples.last().first) return samples.last().second

        for (index in 0 until samples.lastIndex) {
            val lower = samples[index]
            val upper = samples[index + 1]
            if (targetFrequencyHz in lower.first..upper.first) {
                val lowLog = ln(lower.first.toDouble())
                val highLog = ln(upper.first.toDouble())
                val fraction = ((targetLog - lowLog) / (highLog - lowLog)).toFloat()
                return lower.second + (upper.second - lower.second) * fraction
            }
        }
        return 0.0f
    }
}
