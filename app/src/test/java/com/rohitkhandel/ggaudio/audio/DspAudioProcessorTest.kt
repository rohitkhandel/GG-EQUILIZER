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

}
