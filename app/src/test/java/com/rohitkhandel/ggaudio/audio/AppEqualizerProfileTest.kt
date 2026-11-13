package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEqualizerProfileTest {
    @Test
    fun profileRoundTripPreservesEveryControl() {
        val profile = AppEqualizerProfile(
            packageName = "com.example.music",
            gainsDb = listOf(1f, 2f, 3f, 4f, 5f, -1f, -2f, -3f, 0f, 1f),
            preampDb = -4.5f,
            bassBoostPercent = 63,
            stereoWidthPercent = 28
        )
        val decoded = AppEqualizerProfileJsonCodec.decode(
            AppEqualizerProfileJsonCodec.encode(listOf(profile))
        )
        assertEquals(listOf(profile), decoded)
        assertEquals(10, decoded.single().toBands().size)
        assertEquals(63, decoded.single().toDspSettings().bassBoostPercent)
        assertEquals(28, decoded.single().toDspSettings().stereoWidthPercent)
    }

    @Test
    fun malformedProfileDataFallsBackToEmptyList() {
        assertTrue(AppEqualizerProfileJsonCodec.decode("{ broken json").isEmpty())
    }

    @Test
    fun duplicatePackagesKeepOnlyFirstProfile() {
        val first = AppEqualizerProfile("com.example.music", List(10) { 1f }, bassBoostPercent = 10)
        val second = first.copy(gainsDb = List(10) { 5f }, bassBoostPercent = 90)
        val decoded = AppEqualizerProfileJsonCodec.decode(
            AppEqualizerProfileJsonCodec.encode(listOf(first, second))
        )
        assertEquals(1, decoded.size)
        assertEquals(first, decoded.single())
    }

    @Test
    fun invalidPackageAndAudioValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AppEqualizerProfile("not-a-package", List(10) { 0f })
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppEqualizerProfile("com.example.music", List(10) { 13f })
        }
        assertThrows(IllegalArgumentException::class.java) {
            AppEqualizerProfile("com.example.music", List(10) { 0f }, stereoWidthPercent = 101)
        }
    }

    @Test
    fun resolvingOneAppDoesNotChangeAnotherAppsProfile() {
        val defaults = DspSettingsSnapshot(EqualizerDefaults.flat(), 0f)
        val music = DspSettingsSnapshot(
            EqualizerDefaults.flat().mapIndexed { index, band ->
                if (index == 0) band.copy(gainDb = 6f) else band
            },
            -2f, 55, 10
        )
        val profiles = mapOf("com.example.music" to music)
        assertEquals(music, AppEqualizerProfileResolver.resolve("com.example.music", defaults, profiles))
        assertEquals(defaults, AppEqualizerProfileResolver.resolve("com.example.video", defaults, profiles))
        assertNotEquals(
            AppEqualizerProfileResolver.resolve("com.example.music", defaults, profiles),
            AppEqualizerProfileResolver.resolve("com.example.video", defaults, profiles)
        )
    }
}
