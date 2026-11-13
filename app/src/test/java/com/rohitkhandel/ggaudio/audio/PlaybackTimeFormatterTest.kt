package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeFormatterTest {
    @Test
    fun formatsZeroAndNegativePositions() {
        assertEquals("00:00", formatPlaybackTime(0L))
        assertEquals("00:00", formatPlaybackTime(-1_000L))
    }

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("01:05", formatPlaybackTime(65_000L))
        assertEquals("59:59", formatPlaybackTime(3_599_000L))
    }

    @Test
    fun formatsHoursWhenNeeded() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000L))
        assertEquals("1:02:03", formatPlaybackTime(3_723_000L))
    }
}
