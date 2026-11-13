package com.rohitkhandel.ggaudio.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stateful RBJ low-shelf processor for interleaved Float PCM.
 *
 * Strength maps to 0..+9 dB at the low end. This is software processing for
 * GG Audio's own playback and is independent of Android's optional AudioEffect APIs.
 */
class BassBoostProcessor(
    sampleRateHz: Int,
    private val channelCount: Int,
    strengthPercent: Int,
    shelfFrequencyHz: Float = 90f
) {
    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    )

    private val x1 = DoubleArray(channelCount)
    private val x2 = DoubleArray(channelCount)
    private val y1 = DoubleArray(channelCount)
    private val y2 = DoubleArray(channelCount)
    private val coefficients: Coefficients

    init {
        require(sampleRateHz >= 8_000) { "Sample rate must be at least 8000 Hz." }
        require(channelCount in 1..8) { "Channel count must be between 1 and 8." }
        require(strengthPercent in 0..100) { "Strength must be between 0 and 100 percent." }
        require(shelfFrequencyHz in 20f..(sampleRateHz * 0.4f)) {
            "Shelf frequency must be between 20 Hz and 40 percent of the sample rate."
        }
        coefficients = lowShelfCoefficients(
            sampleRateHz = sampleRateHz,
            frequencyHz = shelfFrequencyHz,
            gainDb = strengthPercent * 0.09f
        )
    }

    fun processInPlace(pcm: FloatArray): FloatArray {
        require(pcm.size % channelCount == 0) {
            "PCM sample count must be divisible by the configured channel count."
        }
        for (frameStart in pcm.indices step channelCount) {
            for (channel in 0 until channelCount) {
                val input = pcm[frameStart + channel].toDouble()
                val output = coefficients.b0 * input +
                    coefficients.b1 * x1[channel] +
                    coefficients.b2 * x2[channel] -
                    coefficients.a1 * y1[channel] -
                    coefficients.a2 * y2[channel]

                x2[channel] = x1[channel]
                x1[channel] = input
                y2[channel] = y1[channel]
                y1[channel] = output
                pcm[frameStart + channel] = output.toFloat()
            }
        }
        return pcm
    }

    private fun lowShelfCoefficients(
        sampleRateHz: Int,
        frequencyHz: Float,
        gainDb: Float
    ): Coefficients {
        if (gainDb == 0f) return Coefficients(1.0, 0.0, 0.0, 0.0, 0.0)

        val amplitude = 10.0.pow(gainDb.toDouble() / 40.0)
        val omega = 2.0 * PI * frequencyHz.toDouble() / sampleRateHz.toDouble()
        val cosine = cos(omega)
        val sine = sin(omega)
        val slope = 1.0
        val alpha = sine / 2.0 * sqrt((amplitude + 1.0 / amplitude) * (1.0 / slope - 1.0) + 2.0)
        val beta = 2.0 * sqrt(amplitude) * alpha

        val b0 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine + beta)
        val b1 = 2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cosine)
        val b2 = amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine - beta)
        val a0 = (amplitude + 1.0) + (amplitude - 1.0) * cosine + beta
        val a1 = -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cosine)
        val a2 = (amplitude + 1.0) + (amplitude - 1.0) * cosine - beta

        return Coefficients(
            b0 / a0,
            b1 / a0,
            b2 / a0,
            a1 / a0,
            a2 / a0
        )
    }
}
