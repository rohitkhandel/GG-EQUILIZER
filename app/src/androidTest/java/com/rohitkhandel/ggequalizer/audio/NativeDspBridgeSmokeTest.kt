package com.rohitkhandel.ggequalizer.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

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
            assertTrue("DSP output must not contain NaN or infinity", samples.all(Float::isFinite))

            val outputPeak = samples.maxOf { abs(it) }
            assertTrue("DSP output should contain non-silent audio", outputPeak > 0.05f)
            assertTrue("Zero look-ahead should report zero latency", NativeDspBridge.nativeGetLatencyFrames(handle) == 0)
        } finally {
            NativeDspBridge.nativeDestroy(handle)
        }
    }
}
