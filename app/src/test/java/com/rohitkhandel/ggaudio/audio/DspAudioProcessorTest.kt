package com.rohitkhandel.ggaudio.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@OptIn(UnstableApi::class)
class DspAudioProcessorTest {
    @Test
    fun pcm16FlatPathPreservesSamples() {
        val processor = DspAudioProcessor()
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        input.putShort(8192)
        input.putShort(-4096)
        input.putShort(16384)
        input.putShort(-16384)
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        assertEquals(8192.toShort(), output.short)
        assertEquals((-4096).toShort(), output.short)
        assertEquals(16384.toShort(), output.short)
        assertEquals((-16384).toShort(), output.short)
    }

    @Test
    fun pcm16ProcessorAppliesConfiguredBoostToTone() {
        val bands = EqualizerDefaults.flat().mapIndexed { index, band ->
            if (index == 5) band.copy(gainDb = 6f) else band
        }
        val processor = DspAudioProcessor(bands)
        val format = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val frameCount = 48_000
        val input = ByteBuffer.allocateDirect(frameCount * 2).order(ByteOrder.nativeOrder())
        repeat(frameCount) { index ->
            val sample = sin(2.0 * PI * 1_000 * index / 48_000) * 0.2
            input.putShort((sample * 32767.0).toInt().toShort())
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        var index = 0
        while (output.remaining() >= 2) {
            val raw = output.short.toDouble() / 32768.0
            val original = sin(2.0 * PI * 1_000 * index / 48_000) * 0.2
            if (index > 12_000) {
                inputEnergy += original * original
                outputEnergy += raw * raw
            }
            index++
        }
        assertTrue("EQ processor should boost the selected frequency.", outputEnergy > inputEnergy * 2.5)
    }
    @Test
    fun pcm16OutputPeakIsLimitedAfterEqAndPreampBoost() {
        val bands = EqualizerDefaults.flat().mapIndexed { index, band ->
            if (index == 5) band.copy(gainDb = 12f) else band
        }
        val processor = DspAudioProcessor(bands, initialPreampDb = 12f)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val frameCount = 48_000
        val input = ByteBuffer.allocateDirect(frameCount * 2).order(ByteOrder.nativeOrder())
        repeat(frameCount) { index ->
            val sample = sin(2.0 * PI * 1_000 * index / 48_000) * 0.6
            input.putShort((sample * 32767.0).toInt().toShort())
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        var maximumPeak = 0f
        while (output.remaining() >= 2) {
            val sample = output.short.toFloat() / 32768f
            maximumPeak = maxOf(maximumPeak, kotlin.math.abs(sample))
        }

        assertTrue("Boosted PCM output must stay under the -1 dBFS limiter ceiling.", maximumPeak <= 0.8915f)
        assertTrue("Limiter should preserve a strong output level instead of muting the signal.", maximumPeak > 0.75f)
    }

    @Test
    fun pcm16ProcessorAppliesBassShelfFromSharedSettings() {
        val store = DspSettingsStore(
            initialBands = EqualizerDefaults.flat(),
            initialPreampDb = 0f,
            initialBassBoostPercent = 100
        )
        val processor = DspAudioProcessor(settingsStore = store)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val frameCount = 48_000
        val input = ByteBuffer.allocateDirect(frameCount * 2).order(ByteOrder.nativeOrder())
        repeat(frameCount) { index ->
            val sample = sin(2.0 * PI * 80.0 * index / 48_000) * 0.1
            input.putShort((sample * 32767.0).toInt().toShort())
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        var inputEnergy = 0.0
        var outputEnergy = 0.0
        var index = 0
        while (output.remaining() >= 2) {
            val sample = output.short.toDouble() / 32768.0
            val original = sin(2.0 * PI * 80.0 * index / 48_000) * 0.1
            if (index > 12_000) {
                inputEnergy += original * original
                outputEnergy += sample * sample
            }
            index++
        }
        assertTrue("Shared bass setting must change the local PCM output.", outputEnergy > inputEnergy * 2.0)
    }

    @Test
    fun pcm16ProcessorAppliesStereoWidthFromSharedSettings() {
        val store = DspSettingsStore(
            initialBands = EqualizerDefaults.flat(),
            initialPreampDb = 0f,
            initialStereoWidthPercent = 100
        )
        val processor = DspAudioProcessor(settingsStore = store)
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        repeat(4) {
            input.putShort(6_553)
            input.putShort((-6_553).toShort())
        }
        input.flip()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())
        val left = output.short.toFloat() / 32768f
        val right = output.short.toFloat() / 32768f

        assertTrue("Stereo width must expand the side signal.", left > 0.30f)
        assertTrue("Stereo width must preserve left/right polarity.", right < -0.30f)
    }

}
