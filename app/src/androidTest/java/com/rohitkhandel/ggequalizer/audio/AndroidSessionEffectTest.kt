package com.rohitkhandel.ggequalizer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses a real Android AudioTrack session to exercise the per-session
 * Android effect attachment API used by external media players.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSessionEffectTest {

    @Test
    fun audioTrackSessionIsRegisteredAndReportsItsEffectRoute() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampleRate = 48_000
        val channelMask = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        assertTrue("The emulator must support stereo PCM AudioTrack", minBufferSize > 0)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBufferSize, 8_192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val sessionId = track.audioSessionId
        try {
            assertTrue("AudioTrack must be initialized", track.state == AudioTrack.STATE_INITIALIZED)
            assertTrue("AudioTrack must provide a non-negative session ID", sessionId >= 0)

            GlobalEqController.initialize(context)
            val route = GlobalEqController.attachSession(sessionId, context.packageName)
            val status = GlobalEqController.snapshot().sessions
                .firstOrNull { it.sessionId == sessionId }

            assertTrue("The session must be registered in GG Equalizer", status != null)
            assertTrue("The attach operation must return a diagnostic route", route.isNotBlank())
            assertTrue("The route must match the attached session status", status?.attachment == route)
            assertTrue(
                "Route should report DynamicsProcessing, legacy EQ, or no compatible effect",
                route in setOf(
                    "dynamics · 32-zone EQ",
                    "legacy graphic EQ",
                    "session observed; no compatible effect"
                )
            )
        } finally {
            GlobalEqController.detachSession(sessionId)
            track.release()
        }
    }
}
