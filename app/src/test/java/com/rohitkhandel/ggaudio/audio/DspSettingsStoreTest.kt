package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DspSettingsStoreTest {
    @Test
    fun updatedSettingsAreVisibleToTheServiceOwnedProcessorStore() {
        val store = DspSettingsStore()
        val updatedBands = EqualizerDefaults.flat().mapIndexed { index, band ->
            if (index == 5) band.copy(gainDb = 4f) else band
        }

        store.update(updatedBands, -2f)

        val current = store.snapshot()
        assertEquals(updatedBands, current.bands)
        assertEquals(-2f, current.preampDb)
    }

    @Test
    fun eachSnapshotIsAnImmutableValueSnapshot() {
        val store = DspSettingsStore()
        val initial = store.snapshot()
        val replacement = EqualizerDefaults.flat().map { it.copy(gainDb = 1f) }

        store.update(replacement, 1f)

        assertNotSame(initial, store.snapshot())
        assertEquals(0f, initial.preampDb)
        assertEquals(1f, store.snapshot().preampDb)
    }

    @Test
    fun playbackSettingsCanBeUpdatedWithoutAnActivityOwnedPlayer() {
        val expected = EqualizerDefaults.balanced()
        PlaybackDspSettings.update(expected, -1f)

        val current = PlaybackDspSettings.store.snapshot()

        assertEquals(expected, current.bands)
        assertEquals(-1f, current.preampDb)
        assertTrue(current.bands.size == 10)
    }
}
