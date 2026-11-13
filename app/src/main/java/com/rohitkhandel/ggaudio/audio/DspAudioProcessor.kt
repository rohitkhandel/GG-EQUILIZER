package com.rohitkhandel.ggaudio.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

data class DspSettingsSnapshot(
    val bands: List<EqualizerBand>,
    val preampDb: Float
)

class DspSettingsStore(
    initialBands: List<EqualizerBand> = EqualizerDefaults.flat(),
    initialPreampDb: Float = 0f
) {
    private val settings = AtomicReference(
        DspSettingsSnapshot(initialBands.toList(), initialPreampDb)
    )

    fun update(bands: List<EqualizerBand>, preampDb: Float) {
        require(preampDb in -24f..12f) { "Preamp must be between -24 dB and +12 dB." }
        require(bands.size <= 32) { "At most 32 EQ bands are supported." }
        settings.set(DspSettingsSnapshot(bands.toList(), preampDb))
    }

    fun snapshot(): DspSettingsSnapshot = settings.get()
}

/** Shared process-local bridge from Compose controls to the service-owned PCM processor. */
object PlaybackDspSettings {
    val store = DspSettingsStore(EqualizerDefaults.balanced(), 0f)

    fun update(bands: List<EqualizerBand>, preampDb: Float) {
        store.update(bands, preampDb)
    }
}

/**
 * Processes PCM16 audio inside GG Audio's Media3 player pipeline.
 * It does not capture or modify audio emitted by unrelated apps.
 */
@UnstableApi
class DspAudioProcessor(
    initialBands: List<EqualizerBand> = EqualizerDefaults.flat(),
    initialPreampDb: Float = 0f,
    private val settingsStore: DspSettingsStore = DspSettingsStore(initialBands, initialPreampDb)
) : BaseAudioProcessor() {

    private var appliedSettings: DspSettingsSnapshot? = null
    private var pcmProcessor: ParametricEqualizerProcessor? = null
    private var scratch = FloatArray(0)
    private var configuredSampleRate = 0
    private var configuredChannels = 0

    fun updateSettings(bands: List<EqualizerBand>, preampDb: Float = 0f) {
        settingsStore.update(bands, preampDb)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(
                "GG Audio PCM processor currently requires 16-bit PCM.",
                inputAudioFormat
            )
        }
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount !in 1..8) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        configuredSampleRate = inputAudioFormat.sampleRate
        configuredChannels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        rebuildProcessor(settingsStore.snapshot())
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val byteCount = inputBuffer.remaining()
        if (byteCount == 0) return
        require(byteCount % 2 == 0) { "PCM16 input must contain complete samples." }

        val sampleCount = byteCount / 2
        if (scratch.size != sampleCount) scratch = FloatArray(sampleCount)

        val input = inputBuffer.order(ByteOrder.nativeOrder())
        for (index in 0 until sampleCount) {
            scratch[index] = input.short.toFloat() / 32768f
        }
        inputBuffer.position(inputBuffer.limit())

        val requested = settingsStore.snapshot()
        if (requested != appliedSettings || pcmProcessor == null) {
            rebuildProcessor(requested)
        }
        pcmProcessor?.processInPlace(scratch)

        val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        for (index in 0 until sampleCount) {
            val sample = scratch[index].coerceIn(-1f, 1f)
            val pcm16 = (sample * 32768f).roundToInt().coerceIn(-32768, 32767)
            output.putShort(pcm16.toShort())
        }
        output.flip()
    }

    private fun rebuildProcessor(settings: DspSettingsSnapshot) {
        if (configuredSampleRate <= 0 || configuredChannels <= 0) return
        pcmProcessor = ParametricEqualizerProcessor(
            sampleRateHz = configuredSampleRate,
            channelCount = configuredChannels,
            bands = settings.bands,
            preampDb = settings.preampDb
        )
        appliedSettings = settings
    }

    override fun onReset() {
        pcmProcessor = null
        appliedSettings = null
        scratch = FloatArray(0)
        configuredSampleRate = 0
        configuredChannels = 0
    }
}
