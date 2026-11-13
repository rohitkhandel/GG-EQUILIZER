package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoWidthProcessorTest {
    @Test
    fun centerSignalRemainsCentered() {
        val pcm = floatArrayOf(0.25f, 0.25f, -0.4f, -0.4f)
        StereoWidthProcessor(channelCount = 2, strengthPercent = 100).processInPlace(pcm)
        assertEquals(0.25f, pcm[0], 0.00001f)
        assertEquals(0.25f, pcm[1], 0.00001f)
        assertEquals(-0.4f, pcm[2], 0.00001f)
        assertEquals(-0.4f, pcm[3], 0.00001f)
    }

    @Test
    fun sideSignalWidensWithoutChangingItsPolarity() {
        val pcm = floatArrayOf(0.2f, -0.2f)
        StereoWidthProcessor(channelCount = 2, strengthPercent = 100).processInPlace(pcm)
        assertTrue(pcm[0] > 0.32f)
        assertTrue(pcm[1] < -0.32f)
        assertEquals(pcm[0], -pcm[1], 0.00001f)
    }

    @Test
    fun monoSignalIsUnchanged() {
        val pcm = floatArrayOf(0.1f, -0.2f, 0.3f)
        StereoWidthProcessor(channelCount = 1, strengthPercent = 100).processInPlace(pcm)
        assertEquals(0.1f, pcm[0], 0f)
        assertEquals(-0.2f, pcm[1], 0f)
        assertEquals(0.3f, pcm[2], 0f)
    }
}
