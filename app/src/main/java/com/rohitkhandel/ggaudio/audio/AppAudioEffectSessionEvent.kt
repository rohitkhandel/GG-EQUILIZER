package com.rohitkhandel.ggaudio.audio

/**
 * Parsed lifecycle events sent by media players that implement Android's
 * standard audio-effect control-session protocol.
 */
sealed interface AppAudioEffectSessionEvent {
    val packageName: String
    val sessionId: Int

    data class Open(
        override val packageName: String,
        override val sessionId: Int
    ) : AppAudioEffectSessionEvent

    data class Close(
        override val packageName: String,
        override val sessionId: Int
    ) : AppAudioEffectSessionEvent
}

object AppAudioEffectSessionEventParser {
    const val ACTION_OPEN = "android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION"
    const val ACTION_CLOSE = "android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION"

    fun parse(action: String?, packageName: String?, sessionId: Int): AppAudioEffectSessionEvent? {
        val cleanPackage = packageName?.trim().orEmpty()
        if (!isValidPackageName(cleanPackage) || sessionId <= 0) return null

        return when (action) {
            ACTION_OPEN -> AppAudioEffectSessionEvent.Open(cleanPackage, sessionId)
            ACTION_CLOSE -> AppAudioEffectSessionEvent.Close(cleanPackage, sessionId)
            else -> null
        }
    }

    private fun isValidPackageName(value: String): Boolean {
        if (value.length !in 3..255 || value.startsWith('.') || value.endsWith('.')) return false
        val segments = value.split('.')
        if (segments.size < 2) return false
        return segments.all { segment ->
            segment.isNotEmpty() &&
                (segment.first().isLetter() || segment.first() == '_') &&
                segment.all { it.isLetterOrDigit() || it == '_' }
        }
    }
}
