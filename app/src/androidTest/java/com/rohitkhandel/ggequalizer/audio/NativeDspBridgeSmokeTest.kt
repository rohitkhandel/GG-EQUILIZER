package com.rohitkhandel.ggequalizer.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class NativeDspBridgeSmokeTest {

    @Test
    fun nativeEngineLoadsAndProcessesStereoAudio() {
        val version = NativeDspBridge.nativeVersion()
        assertTrue("The native DSP library must load and report a version", version.isNotBlank())

        val handle = NativeDspBridge.nativeCreate(48_000)
        assertTrue("The native DSP engine must create a valid handle", handle > 0L)

        try {
            assertTrue(NativeDspBridge.nativeSetSampleRate(handle, 48_000))
            assertTrue(
                NativeDspBridge.nativeSetBand(
                    handle = handle,
                    index = 0,
                    type = NativeDspBridge.FILTER_PEAKING,
                    frequencyHz = 1_000f,
                    gainDb = 6f,
                    qFactor = 1f,
                    enabled = true
                )
            )
            assertTrue(
                "A zero-look-ahead limiter configuration should be accepted",
                NativeDspBridge.nativeSetLimiter(
                    handle = handle,
                    enabled = false,
                    thresholdDb = -1f,
                    kneeDb = 6f,
                    lookAheadMs = 0f,
                    releaseMs = 80f
                )
            )

            val frameCount = 2_400
            val samples = FloatArray(frameCount * 2)
            for (frame in 0 until frameCount) {
                val sample = (0.2 * sin(2.0 * PI * 1_000.0 * frame / 48_000.0)).toFloat()
                samples[frame * 2] = sample
                samples[frame * 2 + 1] = sample
            }

            assertTrue(
                "The native DSP engine must process the supplied stereo frames",
                NativeDspBridge.nativeProcess(handle, samples, frameCount)
            )
            assertTrue("DSP output must not contain NaN or infinity", samples.all { it.isFinite() })

            val outputPeak = samples.maxOf { abs(it) }
            assertTrue("DSP output should contain non-silent audio", outputPeak > 0.05f)
            assertTrue("Zero look-ahead should report zero latency", NativeDspBridge.nativeGetLatencyFrames(handle) == 0)
        } finally {
            NativeDspBridge.nativeDestroy(handle)
        }
    }
    @Test
    fun nativeEngineAcceptsAllSixFilterTypesAndToneParameters() {
        val handle = NativeDspBridge.nativeCreate(48_000)
        assertTrue("The native DSP engine must create a valid handle", handle > 0L)
        try {
            assertTrue(
                "The native tone stage must accept the UI frequency and Q settings",
                NativeDspBridge.nativeSetToneParameters(
                    handle = handle,
                    bassFrequencyHz = 85f,
                    bassQFactor = 0.8f,
                    trebleFrequencyHz = 10_000f,
                    trebleQFactor = 0.8f
                )
            )
            for (filterType in 0..5) {
                assertTrue(
                    "Native filter type $filterType must be supported",
                    NativeDspBridge.nativeSetBand(
                        handle = handle,
                        index = 0,
                        type = filterType,
                        frequencyHz = 1_000f,
                        gainDb = 0f,
                        qFactor = 0.707f,
                        enabled = true
                    )
                )
            }
        } finally {
            NativeDspBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun nativeHighPassAttenuatesLowFrequencyStereoSignal() {
        val handle = NativeDspBridge.nativeCreate(48_000)
        assertTrue("The native DSP engine must create a valid handle", handle > 0L)
        try {
            assertTrue(
                NativeDspBridge.nativeSetLimiter(
                    handle = handle,
                    enabled = false,
                    thresholdDb = -1f,
                    kneeDb = 6f,
                    lookAheadMs = 0f,
                    releaseMs = 80f
                )
            )
            assertTrue(
                NativeDspBridge.nativeSetBand(
                    handle = handle,
                    index = 0,
                    type = NativeDspBridge.FILTER_HIGH_PASS,
                    frequencyHz = 1_000f,
                    gainDb = 0f,
                    qFactor = 0.707f,
                    enabled = true
                )
            )

            val frameCount = 9_600
            val samples = FloatArray(frameCount * 2)
            for (frame in 0 until frameCount) {
                val sample = (0.2 * sin(2.0 * PI * 100.0 * frame / 48_000.0)).toFloat()
                samples[frame * 2] = sample
                samples[frame * 2 + 1] = sample
            }

            assertTrue(NativeDspBridge.nativeProcess(handle, samples, frameCount))
            var energy = 0.0
            var sampleCount = 0
            for (frame in 2_400 until frameCount) {
                val left = samples[frame * 2].toDouble()
                val right = samples[frame * 2 + 1].toDouble()
                energy += left * left + right * right
                sampleCount += 2
            }
            val outputRms = sqrt(energy / sampleCount.toDouble())
            assertTrue(
                "A 1 kHz high-pass should strongly attenuate 100 Hz input; measured RMS=$outputRms",
                outputRms < 0.02
            )
        } finally {
            NativeDspBridge.nativeDestroy(handle)
        }
    }

}
