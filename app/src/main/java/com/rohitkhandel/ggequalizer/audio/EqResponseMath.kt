package com.rohitkhandel.ggequalizer.audio

import com.rohitkhandel.ggequalizer.audio.NativeDspBridge.FILTER_HIGH_SHELF
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge.FILTER_LOW_SHELF
import com.rohitkhandel.ggequalizer.audio.NativeDspBridge.FILTER_PEAKING
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Frequency-response math shared by the UI graph and Android session-effect mapper.
 *
 * Uses the same Audio EQ Cookbook biquad coefficients as BiquadFilter.cpp. Android's
 * public DynamicsProcessing.EqBand has no Q parameter, so the external-session path
 * samples this response into its 32 logarithmic EQ bands instead of pretending that
 * its hardware EQ bands are the app's native parametric filters.
 */
object EqResponseMath {
    private const val DEFAULT_SAMPLE_RATE_HZ = 48_000.0
    private const val MIN_MAGNITUDE = 1.0e-9

    fun profileGainDb(
        profile: EqAudioProfile,
        frequencyHz: Float,
        includePreamp: Boolean = false
    ): Float {
        if (!frequencyHz.isFinite() || frequencyHz <= 0f) return 0f

        var gain = if (includePreamp) profile.preampGainDb.toDouble() else 0.0
        profile.bands.forEach { band ->
            if (!band.enabled) return@forEach
            gain += filterGainDb(
                frequencyHz = frequencyHz,
                centerFrequencyHz = band.frequencyHz,
                gainDb = band.gainDb,
                qFactor = band.qFactor,
                filterType = band.filterType
            )
        }

        if (abs(profile.bassGainDb) > 0.0001f) {
            gain += filterGainDb(
                frequencyHz = frequencyHz,
                centerFrequencyHz = profile.bassFrequencyHz,
                gainDb = profile.bassGainDb,
                qFactor = profile.bassQFactor,
                filterType = FILTER_LOW_SHELF
            )
        }
        if (abs(profile.trebleGainDb) > 0.0001f) {
            gain += filterGainDb(
                frequencyHz = frequencyHz,
                centerFrequencyHz = profile.trebleFrequencyHz,
                gainDb = profile.trebleGainDb,
                qFactor = profile.trebleQFactor,
                filterType = FILTER_HIGH_SHELF
            )
        }
        return gain.coerceIn(-48.0, 48.0).toFloat()
    }

    fun bandsGainDb(
        bands: List<EqBandParameter>,
        frequencyHz: Float
    ): Float {
        if (!frequencyHz.isFinite() || frequencyHz <= 0f) return 0f
        val gain = bands.asSequence()
            .filter { it.enabled }
            .sumOf { band ->
                filterGainDb(
                    frequencyHz = frequencyHz,
                    centerFrequencyHz = band.frequencyHz,
                    gainDb = band.gainDb,
                    qFactor = band.qFactor,
                    filterType = band.filterType
                )
            }
        return gain.coerceIn(-48.0, 48.0).toFloat()
    }

    fun filterGainDb(
        frequencyHz: Float,
        centerFrequencyHz: Float,
        gainDb: Float,
        qFactor: Float,
        filterType: Int,
        sampleRateHz: Double = DEFAULT_SAMPLE_RATE_HZ
    ): Double {
        if (!frequencyHz.isFinite() || !centerFrequencyHz.isFinite() ||
            !gainDb.isFinite() || !qFactor.isFinite() ||
            frequencyHz <= 0f || centerFrequencyHz <= 0f || qFactor <= 0f
        ) return 0.0

        val sampleRate = sampleRateHz.coerceIn(8_000.0, 192_000.0)
        val center = centerFrequencyHz.toDouble().coerceIn(10.0, sampleRate * 0.49)
        val evaluated = frequencyHz.toDouble().coerceIn(1.0, sampleRate * 0.499)
        val gain = gainDb.toDouble().coerceIn(-24.0, 24.0)
        val q = qFactor.toDouble().coerceIn(0.1, 12.0)
        if (abs(gain) < 0.0001) return 0.0

        val amplitude = 10.0.pow(gain / 40.0)
        val omega = 2.0 * PI * center / sampleRate
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = sine / (2.0 * q)

        var b0 = 1.0
        var b1 = 0.0
        var b2 = 0.0
        var a0 = 1.0
        var a1 = 0.0
        var a2 = 0.0

        when (filterType) {
            FILTER_PEAKING -> {
                b0 = 1.0 + alpha * amplitude
                b1 = -2.0 * cosine
                b2 = 1.0 - alpha * amplitude
                a0 = 1.0 + alpha / amplitude
                a1 = -2.0 * cosine
                a2 = 1.0 - alpha / amplitude
            }

            FILTER_LOW_SHELF, FILTER_HIGH_SHELF -> {
                val shelfSlope = (1.0 / q).coerceIn(0.1, 1.0)
                val radicand =
                    (amplitude + 1.0 / amplitude) * (1.0 / shelfSlope - 1.0) + 2.0
                val shelfAlpha = (sine / 2.0) * sqrt(max(radicand, 0.0))
                val beta = 2.0 * sqrt(amplitude) * shelfAlpha

                if (filterType == FILTER_LOW_SHELF) {
                    b0 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine + beta)
                    b1 = 2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cosine)
                    b2 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine - beta)
                    a0 = (amplitude + 1.0) + (amplitude - 1.0) * cosine + beta
                    a1 = -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cosine)
                    a2 = (amplitude + 1.0) + (amplitude - 1.0) * cosine - beta
                } else {
                    b0 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + beta)
                    b1 = -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine)
                    b2 = amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - beta)
                    a0 = (amplitude + 1.0) - (amplitude - 1.0) * cosine + beta
                    a1 = 2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine)
                    a2 = (amplitude + 1.0) - (amplitude - 1.0) * cosine - beta
                }
            }

            else -> return 0.0
        }

        if (!a0.isFinite() || abs(a0) < 1.0e-12) return 0.0
        val inverseA0 = 1.0 / a0
        val nb0 = b0 * inverseA0
        val nb1 = b1 * inverseA0
        val nb2 = b2 * inverseA0
        val na1 = a1 * inverseA0
        val na2 = a2 * inverseA0

        val evaluatedOmega = 2.0 * PI * evaluated / sampleRate
        val c1 = cos(evaluatedOmega)
        val s1 = sin(evaluatedOmega)
        val c2 = cos(2.0 * evaluatedOmega)
        val s2 = sin(2.0 * evaluatedOmega)

        val numeratorReal = nb0 + nb1 * c1 + nb2 * c2
        val numeratorImaginary = -nb1 * s1 - nb2 * s2
        val denominatorReal = 1.0 + na1 * c1 + na2 * c2
        val denominatorImaginary = -na1 * s1 - na2 * s2

        val numeratorMagnitude = hypot(numeratorReal, numeratorImaginary)
        val denominatorMagnitude = hypot(denominatorReal, denominatorImaginary)
            .coerceAtLeast(MIN_MAGNITUDE)
        val magnitude = (numeratorMagnitude / denominatorMagnitude).coerceAtLeast(MIN_MAGNITUDE)
        return (20.0 * ln(magnitude) / ln(10.0)).coerceIn(-48.0, 48.0)
    }
}
