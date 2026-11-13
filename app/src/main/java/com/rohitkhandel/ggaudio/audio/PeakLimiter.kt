package com.rohitkhandel.ggaudio.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Linked-channel, sample-peak limiter for interleaved Float PCM.
 *
 * Attack is immediate so no sample can exceed the configured ceiling. Gain
 * recovery is smoothed to reduce abrupt level changes after a loud peak.
 * This is a safety limiter, not a look-ahead true-peak limiter.
 */
class PeakLimiter(
    private val sampleRateHz: Int,
    private val channelCount: Int,
    ceilingDb: Float = -1f,
    releaseMs: Float = 80f
) {
    private val ceilingLinear: Float
    private val releaseCoefficient: Double
    private var currentGain = 1.0

    init {
        require(sampleRateHz >= 8_000) { "Sample rate must be at least 8000 Hz." }
        require(channelCount in 1..8) { "Channel count must be between 1 and 8." }
        require(ceilingDb in -12f..-0.1f) { "Limiter ceiling must be between -12 dBFS and -0.1 dBFS." }
        require(releaseMs in 1f..1_000f) { "Limiter release must be between 1 and 1000 ms." }

        ceilingLinear = 10.0.pow(ceilingDb.toDouble() / 20.0).toFloat()
        val releaseSeconds = releaseMs.toDouble() / 1_000.0
        releaseCoefficient = exp(-1.0 / (releaseSeconds * sampleRateHz))
    }

    /** Limits an interleaved PCM buffer in place and returns the same array. */
    fun processInPlace(pcm: FloatArray): FloatArray {
        require(pcm.size % channelCount == 0) {
            "PCM sample count must be divisible by the configured channel count."
        }

        for (frameStart in pcm.indices step channelCount) {
            var framePeak = 0.0
            for (channel in 0 until channelCount) {
                val sample = pcm[frameStart + channel]
                if (sample.isFinite()) {
                    framePeak = maxOf(framePeak, abs(sample.toDouble()))
                } else {
                    pcm[frameStart + channel] = 0f
                }
            }

            val targetGain = if (framePeak > ceilingLinear) {
                ceilingLinear.toDouble() / framePeak
            } else {
                1.0
            }

            currentGain = if (targetGain < currentGain) {
                targetGain
            } else {
                1.0 - (1.0 - currentGain) * releaseCoefficient
            }

            for (channel in 0 until channelCount) {
                val index = frameStart + channel
                val limited = pcm[index].toDouble() * currentGain
                pcm[index] = limited.coerceIn(
                    -ceilingLinear.toDouble(),
                    ceilingLinear.toDouble()
                ).toFloat()
            }
        }
        return pcm
    }

    /** Clears gain reduction, for example when a playback stream is restarted. */
    fun reset() {
        currentGain = 1.0
    }

    fun currentGainReductionDb(): Float {
        return (20.0 * kotlin.math.log10(currentGain.coerceAtLeast(1e-12))).toFloat()
    }

    fun ceiling(): Float = ceilingLinear
}
