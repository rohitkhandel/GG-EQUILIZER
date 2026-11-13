package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueJsonCodecTest {
    @Test
    fun queueRoundTripKeepsOrderIndexAndPosition() {
        val queue = PlaybackQueueSnapshot(
            tracks = listOf(
                PlaybackQueueTrack("content://media/track/1", "First"),
                PlaybackQueueTrack("content://media/track/2", "Second")
            ),
            currentIndex = 1,
            positionMs = 87_500L
        )

        assertEquals(queue, PlaybackQueueJsonCodec.decode(PlaybackQueueJsonCodec.encode(queue)))
    }

    @Test
    fun malformedOrEmptyQueueFallsBackToEmpty() {
        assertEquals(PlaybackQueueSnapshot(), PlaybackQueueJsonCodec.decode(""))
        assertEquals(PlaybackQueueSnapshot(), PlaybackQueueJsonCodec.decode("not json"))
    }

    @Test
    fun rejectsRemoteUrisAndInvalidIndexes() {
        assertTrue(runCatching { PlaybackQueueTrack("https://example.com/audio.mp3", "Track") }.isFailure)
        assertTrue(runCatching {
            PlaybackQueueSnapshot(
                tracks = listOf(PlaybackQueueTrack("content://media/1", "Track")),
                currentIndex = 2
            )
        }.isFailure)
    }
}
