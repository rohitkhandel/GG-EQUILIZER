package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueJsonCodecTest {
    @Test
    fun queueRoundTripKeepsOrderIndexAndPosition() {
        val queue = PlaybackQueueSnapshot(
            tracks = listOf(
                PlaybackQueueTrack(
                    uri = "content://media/track/1",
                    title = "First",
                    artist = "Artist One",
                    album = "Album One",
                    durationMs = 214_000L
                ),
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
    @Test
    fun oldQueueJsonWithoutOptionalTagsStillLoads() {
        val oldJson = """{"tracks":[{"uri":"content://media/track/1","title":"Older track"}],"currentIndex":0,"positionMs":4200}"""

        val decoded = PlaybackQueueJsonCodec.decode(oldJson)

        assertEquals("Older track", decoded.tracks.single().title)
        assertEquals(null, decoded.tracks.single().artist)
        assertEquals(null, decoded.tracks.single().album)
        assertEquals(null, decoded.tracks.single().durationMs)
    }

}
