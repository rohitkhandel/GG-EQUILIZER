package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioSettingsJsonCodecTest {
    @Test
    fun activeSettingsRoundTripPreservesAllTenBandsAndEffects() {
        val settings = AudioSettingsSnapshot(
            activePresetName = "My custom curve",
            gainsDb = listOf(-4f, -2f, 0f, 1f, 2f, 3f, 4f, 2f, 0f, -1f),
            preampDb = -3.5f,
            bassBoostPercent = 42,
            virtualizerPercent = 27
        )

        val decoded = AudioSettingsJsonCodec.decode(AudioSettingsJsonCodec.encode(settings))

        assertEquals(settings, decoded)
        assertEquals(10, decoded.toBands().size)
    }

    @Test
    fun emptyOrMalformedSettingsUseSafeDefaults() {
        assertEquals(AudioSettingsSnapshot(), AudioSettingsJsonCodec.decode(""))
        assertEquals(AudioSettingsSnapshot(), AudioSettingsJsonCodec.decode("{ broken settings"))
    }

    @Test
    fun invalidGainAndEffectLevelsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AudioSettingsSnapshot(gainsDb = List(10) { 13f })
        }
        assertThrows(IllegalArgumentException::class.java) {
            AudioSettingsSnapshot(bassBoostPercent = 101)
        }
    }
}
