package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetJsonCodecTest {
    @Test
    fun savedPresetRoundTripsAllGainsAndPreamp() {
        val presets = listOf(
            SavedEqualizerPreset(
                id = "preset-1",
                name = "My warm sound",
                gainsDb = listOf(2f, 3f, 1f, 0f, -1f, -2f, 0f, 1f, 2f, 3f),
                preampDb = -2.5f
            )
        )

        val decoded = PresetJsonCodec.decode(PresetJsonCodec.encode(presets))

        assertEquals(presets, decoded)
        assertEquals(10, decoded.single().toBands().size)
        assertEquals(-2.5f, decoded.single().preampDb)
    }

    @Test
    fun malformedSavedPresetDataFallsBackToEmptyList() {
        assertTrue(PresetJsonCodec.decode("{this is not valid JSON").isEmpty())
    }

    @Test
    fun duplicateBuiltInProfilesAreNotLoadedAsCustomPresets() {
        val encoded = """[{"id":"1","name":"Balanced","gainsDb":[0,0,0,0,0,0,0,0,0,0],"preampDb":0}]"""
        assertTrue(PresetJsonCodec.decode(encoded).isEmpty())
    }

    @Test
    fun presetRejectsWrongBandCount() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SavedEqualizerPreset("bad", "Bad", listOf(0f, 1f), 0f)
        }
    }
}
