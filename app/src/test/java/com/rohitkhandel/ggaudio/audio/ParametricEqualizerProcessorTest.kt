package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class ParametricEqualizerProcessorTest {
    @Test
    fun flatBandsLeavePcmUnchanged() {
        val input = FloatArray(4096) { index -> sin(index * 0.07).toFloat() }
        val expected = input.copyOf()
        val processor = ParametricEqualizerProcessor(
            sampleRateHz = 48_000,
            channelCount = 1,
            bands = EqualizerDefaults.flat()
        )

        processor.processInPlace(input)
        assertTrue(input.indices.all { kotlin.math.abs(input[it] - expected[it]) < 0.000001f })
    }

    @Test
    fun positiveGainRaisesEnergyNearBandCenter() {
        val processor = processorWithGain(6f)
        val input = sineWave(frequencyHz = 1_000, sampleRateHz = 48_000, seconds = 1)
        val originalRms = rms(input, startIndex = 12_000)
        processor.processInPlace(input)
        val processedRms = rms(input, startIndex = 12_000)

        assertTrue("Expected a boost at the selected band center.", processedRms > originalRms * 1.6)
    }

    @Test
    fun negativeGainReducesEnergyNearBandCenter() {
        val processor = processorWithGain(-6f)
        val input = sineWave(frequencyHz = 1_000, sampleRateHz = 48_000, seconds = 1)
        val originalRms = rms(input, startIndex = 12_000)
        processor.processInPlace(input)
        val processedRms = rms(input, startIndex = 12_000)

        assertTrue("Expected a cut at the selected band center.", processedRms < originalRms * 0.7)
    }

    @Test
    fun stereoChannelsAreProcessedIndependently() {
        val bands = EqualizerDefaults.flat().mapIndexed { index, band ->
            if (index == 5) band.copy(gainDb = 4f) else band
        }
        val pcm = FloatArray(2_000) { index -> if (index % 2 == 0) 0f else 0.4f }
        val processor = ParametricEqualizerProcessor(48_000, 2, bands)
        processor.processInPlace(pcm)

        assertTrue((pcm.indices step 2).all { kotlin.math.abs(pcm[it]) < 0.000001f })
        assertTrue((1 until pcm.size step 2).any { kotlin.math.abs(pcm[it]) > 0.001f })
    }

    @Test
    fun preampChangesOverallLevel() {
        val pcm = FloatArray(512) { 0.1f }
        val processor = ParametricEqualizerProcessor(
            sampleRateHz = 48_000,
            channelCount = 1,
            bands = EqualizerDefaults.flat(),
            preampDb = 6f
        )
        processor.processInPlace(pcm)
        assertTrue(pcm.all { it > 0.19f && it < 0.21f })
    }

    @Test
    fun rejectsMalformedInterleavedBuffers() {
        val processor = ParametricEqualizerProcessor(48_000, 2, EqualizerDefaults.flat())
        try {
            processor.processInPlace(floatArrayOf(0f, 0f, 0f))
            throw AssertionError("Expected malformed PCM buffer to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertEquals(
                "PCM sample count must be divisible by the configured channel count.",
                expected.message
            )
        }
    }

    private fun processorWithGain(gainDb: Float): ParametricEqualizerProcessor {
        val bands = EqualizerDefaults.flat().mapIndexed { index, band ->
            if (index == 5) band.copy(gainDb = gainDb) else band
        }
        return ParametricEqualizerProcessor(48_000, 1, bands, qualityFactor = 1.0f)
    }

    private fun sineWave(frequencyHz: Int, sampleRateHz: Int, seconds: Int): FloatArray {
        return FloatArray(sampleRateHz * seconds) { index ->
            sin(2.0 * PI * frequencyHz * index / sampleRateHz).toFloat() * 0.2f
        }
    }

    private fun rms(samples: FloatArray, startIndex: Int): Double {
        val meanSquare = samples.drop(startIndex).map { it.toDouble() * it.toDouble() }.average()
        return sqrt(meanSquare)
    }
}
