package com.rohitkhandel.ggaudio.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the ExoPlayer instance so playback can continue when the Activity is backgrounded.
 * Media3 publishes and updates the media-style notification from this session.
 */
@UnstableApi
class GgAudioPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val externalSessionEffects = AppAudioSessionEffects()
    private var audioEffectReceiverRegistered = false

    private val audioEffectSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val incoming = intent ?: return
            val event = AppAudioEffectSessionEventParser.parse(
                action = incoming.action,
                packageName = incoming.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME),
                sessionId = incoming.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            ) ?: return

            if (event.packageName == this@GgAudioPlaybackService.packageName) return

            // Android 14+ exposes the real sender identity. Match it to the standard package extra.
            if (Build.VERSION.SDK_INT >= 34) {
                val senderPackage = sentFromPackage ?: return
                if (senderPackage != event.packageName) return
            }

            when (event) {
                is AppAudioEffectSessionEvent.Open -> {
                    externalSessionEffects.openSession(event.packageName, event.sessionId)
                }
                is AppAudioEffectSessionEvent.Close -> {
                    externalSessionEffects.closeSession(event.packageName, event.sessionId)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        PlaybackDspSettings.attachExternalSessionEffects(externalSessionEffects)
        registerAudioEffectSessionReceiver()

        val dspProcessor = DspAudioProcessor(settingsStore = PlaybackDspSettings.store)
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setAudioProcessors(arrayOf(dspProcessor))
                    .build()
            }
        }

        val player = ExoPlayer.Builder(this, renderersFactory).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
        }

        mediaSession = MediaSession.Builder(this, player).build()
    }

    private fun registerAudioEffectSessionReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                audioEffectSessionReceiver,
                filter,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                null,
                Context.RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(
                audioEffectSessionReceiver,
                filter,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                null
            )
        }
        audioEffectReceiverRegistered = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        if (audioEffectReceiverRegistered) {
            runCatching { unregisterReceiver(audioEffectSessionReceiver) }
            audioEffectReceiverRegistered = false
        }
        PlaybackDspSettings.detachExternalSessionEffects(externalSessionEffects)
        externalSessionEffects.close()

        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
