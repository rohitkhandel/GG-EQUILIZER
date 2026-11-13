package com.rohitkhandel.ggaudio.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PlaybackQueueTrack(
    val uri: String,
    val title: String
) {
    init {
        require(uri.startsWith("content://") || uri.startsWith("file://")) {
            "Only local audio document URIs can be restored."
        }
        require(title.isNotBlank() && title.length <= 512) {
            "Track title must contain 1 to 512 characters."
        }
    }
}

@Serializable
data class PlaybackQueueSnapshot(
    val tracks: List<PlaybackQueueTrack> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L
) {
    init {
        require(tracks.size <= 500) { "A saved queue can contain at most 500 tracks." }
        require(currentIndex >= 0 && (tracks.isEmpty() || currentIndex < tracks.size)) {
            "Saved playback index is outside the queue."
        }
        require(positionMs >= 0L) { "Saved playback position cannot be negative." }
    }
}

object PlaybackQueueJsonCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(snapshot: PlaybackQueueSnapshot): String = json.encodeToString(snapshot)

    fun decode(value: String): PlaybackQueueSnapshot {
        if (value.isBlank()) return PlaybackQueueSnapshot()
        return runCatching { json.decodeFromString<PlaybackQueueSnapshot>(value) }
            .getOrDefault(PlaybackQueueSnapshot())
    }
}
