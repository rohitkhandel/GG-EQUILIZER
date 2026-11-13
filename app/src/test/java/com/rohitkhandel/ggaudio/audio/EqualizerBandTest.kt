package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EqualizerBandTest {
    @Test
    fun flatPresetContainsTenZeroGainBands() {
        val bands = EqualizerDefaults.flat()
        assertEquals(10, bands.size)
        assertEquals(List(10) { 0f }, bands.map { it.gainDb })
    }

    @Test
    fun balancedPresetUsesExpectedFrequenciesAndGains() {
        val bands = EqualizerDefaults.balanced()
        assertEquals(listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000), bands.map { it.frequencyHz })
        assertEquals(listOf(2f, 3f, 2f, 1f, 0f, -1f, 0f, 1f, 2f, 1f), bands.map { it.gainDb })
    }

    @Test
    fun gainOutsideSupportedUiRangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            EqualizerBand(frequencyHz = 1000, label = "1K", gainDb = 13f)
        }
    }
}
