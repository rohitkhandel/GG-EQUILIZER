package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PeakLimiterTest {
    @Test
    fun leavesSamplesBelowCeilingUnchanged() {
        val input = floatArrayOf(0.25f, -0.5f, 0.7f, -0.1f)
        val expected = input.copyOf()
        val limiter = PeakLimiter(sampleRateHz = 48_000, channelCount = 1)

        limiter.processInPlace(input)

        assertTrue(input.indices.all { abs(input[it] - expected[it]) < 0.000001f })
        assertEquals(0f, limiter.currentGainReductionDb(), 0.0001f)
    }

    @Test
    fun limitsLoudSamplesBelowConfiguredCeiling() {
        val limiter = PeakLimiter(sampleRateHz = 48_000, channelCount = 1)
        val input = FloatArray(512) { index -> if (index % 2 == 0) 2f else -2f }

        limiter.processInPlace(input)

        assertTrue(input.all { abs(it) <= limiter.ceiling() + 0.000001f })
        assertTrue(input.any { abs(it) > 0.88f })
    }

    @Test
    fun stereoChannelsShareGainReductionAndKeepTheirBalance() {
        val limiter = PeakLimiter(sampleRateHz = 48_000, channelCount = 2)
        val input = FloatArray(200) { index -> if (index % 2 == 0) 2f else 1f }

        limiter.processInPlace(input)

        for (frame in 0 until input.size / 2) {
            val left = input[frame * 2]
            val right = input[frame * 2 + 1]
            assertEquals(left / 2f, right, 0.00001f)
            assertTrue(abs(left) <= limiter.ceiling() + 0.000001f)
        }
    }

    @Test
    fun gainRecoversSmoothlyAfterLoudPeak() {
        val limiter = PeakLimiter(sampleRateHz = 48_000, channelCount = 1, releaseMs = 80f)
        val impulse = floatArrayOf(1.5f)
        limiter.processInPlace(impulse)
        assertTrue(limiter.currentGainReductionDb() < -4f)

        val quietSignal = FloatArray(24_000) { 0.5f }
        limiter.processInPlace(quietSignal)

        assertTrue(quietSignal.last() > quietSignal.first())
        assertTrue(quietSignal.last() > 0.49f)
        assertEquals(0f, limiter.currentGainReductionDb(), 0.05f)
    }

    @Test
    fun nonFiniteSamplesAreSilenced() {
        val limiter = PeakLimiter(sampleRateHz = 48_000, channelCount = 1)
        val input = floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 0.25f)

        limiter.processInPlace(input)

        assertEquals(0f, input[0], 0f)
        assertEquals(0f, input[1], 0f)
        assertEquals(0.25f, input[2], 0.000001f)
    }

    @Test
    fun rejectsIncompleteInterleavedFrames() {
        val limiter = PeakLimiter(sampleRateHz = 48_000, channelCount = 2)
        try {
            limiter.processInPlace(floatArrayOf(0.1f, 0.2f, 0.3f))
            throw AssertionError("Expected incomplete interleaved frame to be rejected.")
        } catch (expected: IllegalArgumentException) {
            assertEquals(
                "PCM sample count must be divisible by the configured channel count.",
                expected.message
            )
        }
    }
}
