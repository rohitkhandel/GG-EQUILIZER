package com.rohitkhandel.ggaudio.audio

import android.net.Uri

data class LocalAudioFile(
    val uri: Uri,
    val title: String
) {
    init {
        require(title.isNotBlank()) { "Track title cannot be blank." }
    }
}
