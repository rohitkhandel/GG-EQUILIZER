package com.rohitkhandel.ggaudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppAudioEffectSessionEventParserTest {
    @Test
    fun parsesOpenSession() {
        val event = AppAudioEffectSessionEventParser.parse(
            AppAudioEffectSessionEventParser.ACTION_OPEN,
            "com.example.music",
            42
        )
        assertEquals(AppAudioEffectSessionEvent.Open("com.example.music", 42), event)
    }

    @Test
    fun parsesCloseSession() {
        val event = AppAudioEffectSessionEventParser.parse(
            AppAudioEffectSessionEventParser.ACTION_CLOSE,
            "com.example.music",
            42
        )
        assertEquals(AppAudioEffectSessionEvent.Close("com.example.music", 42), event)
    }

    @Test
    fun rejectsMissingOrInvalidSessionData() {
        assertNull(AppAudioEffectSessionEventParser.parse(
            AppAudioEffectSessionEventParser.ACTION_OPEN, null, 42
        ))
        assertNull(AppAudioEffectSessionEventParser.parse(
            AppAudioEffectSessionEventParser.ACTION_OPEN, "com.example.music", 0
        ))
        assertNull(AppAudioEffectSessionEventParser.parse(
            "com.example.unknown.ACTION", "com.example.music", 42
        ))
        assertNull(AppAudioEffectSessionEventParser.parse(
            AppAudioEffectSessionEventParser.ACTION_OPEN, "not-a-package", 42
        ))
    }
}
