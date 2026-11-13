package com.rohitkhandel.ggaudio.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
        loadQueue(listOf(LocalAudioFile(uri, uri.lastPathSegment ?: "Audio")))
    }

    fun loadQueue(tracks: List<LocalAudioFile>, startIndex: Int = 0) {
        require(tracks.isNotEmpty()) { "The playback queue cannot be empty." }
        val mediaItems = tracks.map { track ->
            MediaItem.Builder()
                .setUri(track.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .build()
                )
                .build()
        }
        val safeIndex = startIndex.coerceIn(0, mediaItems.lastIndex)
        player.setMediaItems(mediaItems, safeIndex, 0L)
        player.prepare()
    }

    fun selectTrack(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        player.seekTo(index, 0L)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    fun skipToNext() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun skipToPrevious() {
        if (player.currentPosition > 3_000L) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else {
            player.seekTo(0L)
        }
    }

    fun play() {
        if (player.playbackState == Player.STATE_IDLE && player.currentMediaItem != null) {
            player.prepare()
        }
        player.play()
    }

    fun pause() = player.pause()

    fun stop() = player.stop()

    fun updateEqualizer(bands: List<EqualizerBand>, preampDb: Float = 0f) {
        dspProcessor.updateSettings(bands, preampDb)
    }

    override fun close() {
        player.release()
    }
}
