package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BassBoostProcessorTest {
    @Test
    fun boostsLowFrequencyMoreThanMidrange() {
        val sampleRate = 48_000
        val frameCount = sampleRate
        val bass = FloatArray(frameCount) { index ->
            sin(2.0 * PI * 80.0 * index / sampleRate).toFloat() * 0.1f
        }
        val midrange = FloatArray(frameCount) { index ->
            sin(2.0 * PI * 2_000.0 * index / sampleRate).toFloat() * 0.1f
        }
        val originalBassEnergy = meanSquare(bass, sampleRate / 2)
        val originalMidEnergy = meanSquare(midrange, sampleRate / 2)

        BassBoostProcessor(sampleRate, 1, 100).processInPlace(bass)
        BassBoostProcessor(sampleRate, 1, 100).processInPlace(midrange)

        val bassRatio = meanSquare(bass, sampleRate / 2) / originalBassEnergy
        val midRatio = meanSquare(midrange, sampleRate / 2) / originalMidEnergy

        assertTrue("Bass shelf should make a clear low-frequency change.", bassRatio > 2.0)
        assertTrue("Bass shelf should leave the upper midrange close to unity.", midRatio in 0.90..1.10)
    }

    private fun meanSquare(samples: FloatArray, start: Int): Double {
        var total = 0.0
        for (index in start until samples.size) {
            total += samples[index] * samples[index]
        }
        return total / (samples.size - start)
    }
}
