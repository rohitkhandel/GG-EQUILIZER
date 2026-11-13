package com.rohitkhandel.ggaudio.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Stateful peaking-EQ processor for interleaved Float PCM.
 *
 * This class is an actual software DSP core; it does not capture other apps'
 * audio by itself. A caller must supply PCM buffers from an owned playback or
 * processing pipeline.
 */
class ParametricEqualizerProcessor(
    private val sampleRateHz: Int,
    private val channelCount: Int,
    bands: List<EqualizerBand>,
    preampDb: Float = 0f,
    private val qualityFactor: Float = 1.0f
) {
    private data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    )

    private class Filter(
        private val coefficients: Coefficients,
        channels: Int
    ) {
        private val x1 = DoubleArray(channels)
        private val x2 = DoubleArray(channels)
        private val y1 = DoubleArray(channels)
        private val y2 = DoubleArray(channels)

        fun process(input: Double, channel: Int): Double {
            val output = coefficients.b0 * input +
                coefficients.b1 * x1[channel] +
                coefficients.b2 * x2[channel] -
                coefficients.a1 * y1[channel] -
                coefficients.a2 * y2[channel]

            x2[channel] = x1[channel]
            x1[channel] = input
            y2[channel] = y1[channel]
            y1[channel] = output
            return output
        }
    }

    private var filters: List<Filter> = emptyList()
    private var preampLinear: Double = 1.0

    init {
        require(sampleRateHz >= 8_000) { "Sample rate must be at least 8000 Hz." }
        require(channelCount in 1..8) { "Channel count must be between 1 and 8." }
        require(qualityFactor in 0.1f..10f) { "Q must be between 0.1 and 10." }
        setPreamp(preampDb)
        updateBands(bands)
    }

    fun setPreamp(gainDb: Float) {
        require(gainDb in -24f..12f) { "Preamp must be between -24 dB and +12 dB." }
        preampLinear = 10.0.pow(gainDb.toDouble() / 20.0)
    }

    fun updateBands(bands: List<EqualizerBand>) {
        require(bands.size <= 32) { "At most 32 EQ bands are supported." }
        filters = bands
            .filter { it.gainDb != 0f }
            .map { band ->
                val safeFrequency = band.frequencyHz.coerceIn(10, (sampleRateHz * 0.49f).toInt())
                Filter(
                    coefficients = peakingCoefficients(
                        sampleRateHz = sampleRateHz,
                        frequencyHz = safeFrequency,
                        gainDb = band.gainDb,
                        q = qualityFactor
                    ),
                    channels = channelCount
                )
            }
    }

    /** Processes an interleaved PCM buffer in place and returns the same array. */
    fun processInPlace(pcm: FloatArray): FloatArray {
        require(pcm.size % channelCount == 0) {
            "PCM sample count must be divisible by the configured channel count."
        }

        for (frameStart in pcm.indices step channelCount) {
            for (channel in 0 until channelCount) {
                var sample = pcm[frameStart + channel].toDouble() * preampLinear
                for (filter in filters) {
                    sample = filter.process(sample, channel)
                }
                pcm[frameStart + channel] = sample.toFloat()
            }
        }
        return pcm
    }

    fun reset() {
        // Rebuilding filters clears each filter's delay state.
        filters = emptyList()
    }

    private fun peakingCoefficients(
        sampleRateHz: Int,
        frequencyHz: Int,
        gainDb: Float,
        q: Float
    ): Coefficients {
        val amplitude = 10.0.pow(gainDb.toDouble() / 40.0)
        val omega = 2.0 * PI * frequencyHz.toDouble() / sampleRateHz.toDouble()
        val alpha = sin(omega) / (2.0 * q.toDouble())
        val cosine = cos(omega)
        val a0 = 1.0 + alpha / amplitude

        return Coefficients(
            b0 = (1.0 + alpha * amplitude) / a0,
            b1 = (-2.0 * cosine) / a0,
            b2 = (1.0 - alpha * amplitude) / a0,
            a1 = (-2.0 * cosine) / a0,
            a2 = (1.0 - alpha / amplitude) / a0
        )
    }
}
