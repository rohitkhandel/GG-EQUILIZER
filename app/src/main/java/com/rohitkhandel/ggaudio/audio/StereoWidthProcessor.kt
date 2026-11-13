package com.rohitkhandel.ggaudio.audio

/**
 * Mid/side stereo-width processor for interleaved Float PCM.
 *
 * A strength of 0 preserves the original stereo image; 100 increases the side
 * channel by 65 percent. Mono streams are left unchanged.
 */
class StereoWidthProcessor(
    private val channelCount: Int,
    strengthPercent: Int
) {
    private val width = 1.0f + (strengthPercent.coerceIn(0, 100) / 100f) * 0.65f

    init {
        require(channelCount in 1..8) { "Channel count must be between 1 and 8." }
        require(strengthPercent in 0..100) { "Strength must be between 0 and 100 percent." }
    }

    fun processInPlace(pcm: FloatArray): FloatArray {
        require(pcm.size % channelCount == 0) {
            "PCM sample count must be divisible by the configured channel count."
        }
        if (channelCount < 2 || width == 1f) return pcm

        for (frameStart in pcm.indices step channelCount) {
            val left = pcm[frameStart]
            val right = pcm[frameStart + 1]
            val mid = (left + right) * 0.5f
            val side = (left - right) * 0.5f * width
            pcm[frameStart] = mid + side
            pcm[frameStart + 1] = mid - side
        }
        return pcm
    }
}
