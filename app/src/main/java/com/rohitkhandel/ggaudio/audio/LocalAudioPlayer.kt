package com.rohitkhandel.ggaudio.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.io.Closeable

/**
 * A session-scoped local playback path that runs the software DSP before audio
 * reaches Android's AudioTrack. Source URIs should be granted by the system file picker.
 */
@UnstableApi
class LocalAudioPlayer(context: Context) : Closeable {
    val dspProcessor = DspAudioProcessor()

    private val appContext = context.applicationContext
    private val renderersFactory = object : DefaultRenderersFactory(appContext) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setAudioProcessors(arrayOf(dspProcessor))
                .build()
        }
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext, renderersFactory).build()

    val audioSessionId: Int
        get() = player.audioSessionId

    fun load(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    fun play() = player.play()

    fun pause() = player.pause()

    fun stop() = player.stop()

    fun updateEqualizer(bands: List<EqualizerBand>, preampDb: Float = 0f) {
        dspProcessor.updateSettings(bands, preampDb)
    }

    override fun close() {
        player.release()
    }
}
