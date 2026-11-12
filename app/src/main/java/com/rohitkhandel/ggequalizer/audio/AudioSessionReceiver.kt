package com.rohitkhandel.ggequalizer.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log

/**
 * Receives the standard audio-effect session lifecycle broadcasts emitted by
 * compatible player applications and forwards their session information.
 */
class AudioSessionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val serviceAction = when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                AudioInterceptorService.ACTION_AUDIO_SESSION_OPEN

            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                AudioInterceptorService.ACTION_AUDIO_SESSION_CLOSE

            else -> return
        }

        val audioSessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val clientPackage = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()

        if (audioSessionId < 0 || clientPackage.isBlank()) {
            Log.d(TAG, "Ignoring audio-effect broadcast with missing session metadata")
            return
        }

        val serviceIntent = Intent(context, AudioInterceptorService::class.java).apply {
            action = serviceAction
            putExtra(AudioInterceptorService.EXTRA_AUDIO_SESSION_ID, audioSessionId)
            putExtra(AudioInterceptorService.EXTRA_CLIENT_PACKAGE, clientPackage)
        }

        try {
            context.startForegroundService(serviceIntent)
        } catch (error: RuntimeException) {
            // Android may reject a background foreground-service start in restricted states.
            Log.w(TAG, "Unable to start the audio interceptor for this session event", error)
        }
    }

    private companion object {
        const val TAG = "AudioSessionReceiver"
    }
}
