package com.rohitkhandel.ggaudio.audio

import android.net.Uri

data class LocalAudioFile(
    val uri: Uri,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null
) {
    init {
        require(title.isNotBlank() && title.length <= 512) {
            "Track title must contain 1 to 512 characters."
        }
        require(artist == null || artist.length <= 256) {
            "Artist name cannot exceed 256 characters."
        }
        require(album == null || album.length <= 256) {
            "Album name cannot exceed 256 characters."
        }
        require(durationMs == null || durationMs >= 0L) {
            "Track duration cannot be negative."
        }
    }
}
