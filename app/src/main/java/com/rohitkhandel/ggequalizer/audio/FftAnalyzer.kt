package com.rohitkhandel.ggequalizer.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Best-effort post-mix spectrum reader using Android Visualizer.
 *
 * RECORD_AUDIO permission is required by Android even though Visualizer is
 * limited to low-quality visualization data. The app first tries the supplied
 * audio session ID, then session 0 if the platform permits output-mix capture.
 * Capture callbacks are driven by AudioFlinger and are not guaranteed to arrive
 * at 60 frames per second on every device.
 */
class FftAnalyzer(context: Context) {
    private val appContext = context.applicationContext
    private var visualizer: Visualizer? = null
    private var callback: ((List<Float>) -> Unit)? = null
    private var smoothLevels = FloatArray(BAR_COUNT)
    private var captureRate = 0
    var attachedSessionId: Int? = null
        private set

    @Synchronized
    fun start(
        audioSessionId: Int,
        requestedRefreshRateHz: Int = 30,
        onSpectrum: (List<Float>) -> Unit
    ): Boolean {
        stop()
        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val candidates = if (audioSessionId > 0) {
            listOf(audioSessionId, 0).distinct()
        } else {
            listOf(0)
        }

        for (candidateSessionId in candidates) {
            val candidate = try {
                Visualizer(candidateSessionId)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Visualizer could not attach to session $candidateSessionId", error)
                continue
            }

            val configured = runCatching {
                val range = Visualizer.getCaptureSizeRange()
                val desiredSize = TARGET_CAPTURE_SIZE.coerceIn(range[0], range[1])
                val legalSize = Integer.highestOneBit(desiredSize)
                    .coerceAtLeast(range[0])
                check(candidate.setCaptureSize(legalSize) == Visualizer.SUCCESS)

                val requestedRate = requestedRefreshRateHz.coerceIn(MIN_CAPTURE_RATE_HZ, MAX_CAPTURE_RATE_HZ) * 1000
                val rate = Visualizer.getMaxCaptureRate().coerceAtMost(requestedRate)
                val listener = object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) = Unit

                    override fun onFftDataCapture(
                        visualizer: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (fft == null) return
                        val bars = convertFft(fft, candidate.getCaptureSize(), samplingRate)
                        val smoothed = smooth(bars)
                        callback?.invoke(smoothed)
                    }
                }

                check(
                    candidate.setDataCaptureListener(listener, rate, false, true) == Visualizer.SUCCESS
                ) {
                    "Android rejected FFT data capture"
                }
                check(candidate.setEnabled(true) == Visualizer.SUCCESS) {
                    "Android could not enable the Visualizer"
                }
                captureRate = rate
                true
            }.getOrElse { error ->
                Log.w(TAG, "Visualizer setup failed for session $candidateSessionId", error)
                runCatching { candidate.release() }
                false
            }

            if (configured) {
                visualizer = candidate
                callback = onSpectrum
                attachedSessionId = candidateSessionId
                Log.i(TAG, "Spectrum visualizer attached to session $candidateSessionId at $captureRate mHz")
                return true
            }
        }
        return false
    }

    @Synchronized
    fun stop() {
        val current = visualizer
        visualizer = null
        callback = null
        attachedSessionId = null
        captureRate = 0
        smoothLevels.fill(0.0f)
        if (current != null) {
            runCatching { current.setEnabled(false) }
            runCatching { current.setDataCaptureListener(null, 0, false, false) }
            runCatching { current.release() }
        }
    }

    @Synchronized
    private fun smooth(target: List<Float>): List<Float> {
        val result = ArrayList<Float>(BAR_COUNT)
        for (index in 0 until BAR_COUNT) {
            val previous = smoothLevels[index]
            val next = target.getOrElse(index) { 0.0f }.coerceIn(0.0f, 1.0f)
            val smoothed = if (next > previous) {
                previous + (next - previous) * ATTACK
            } else {
                max(next, previous * RELEASE)
            }
            smoothLevels[index] = smoothed
            result.add(smoothed)
        }
        return result
    }

    private fun convertFft(
        fft: ByteArray,
        captureSize: Int,
        samplingRateMilliHz: Int
    ): List<Float> {
        if (captureSize < 4 || fft.size < captureSize) return List(BAR_COUNT) { 0.0f }

        val sampleRateHz = if (samplingRateMilliHz > 0) {
            samplingRateMilliHz / 1000.0
        } else {
            FALLBACK_SAMPLE_RATE_HZ
        }
        val nyquistHz = (sampleRateHz * 0.5).coerceAtMost(MAX_VISUAL_FREQUENCY_HZ)
        val binWidthHz = sampleRateHz / captureSize.toDouble()
        val maximumBin = minOf(captureSize / 2 - 1, fft.size / 2 - 1)
        if (maximumBin < 1) return List(BAR_COUNT) { 0.0f }

        return List(BAR_COUNT) { barIndex ->
            val lowFraction = barIndex.toDouble() / BAR_COUNT.toDouble()
            val highFraction = (barIndex + 1).toDouble() / BAR_COUNT.toDouble()
            val lowHz = MIN_VISUAL_FREQUENCY_HZ *
                (nyquistHz / MIN_VISUAL_FREQUENCY_HZ).let { it.pow(lowFraction) }
            val highHz = MIN_VISUAL_FREQUENCY_HZ *
                (nyquistHz / MIN_VISUAL_FREQUENCY_HZ).let { it.pow(highFraction) }

            val firstBin = floor(lowHz / binWidthHz).toInt().coerceIn(1, maximumBin)
            val lastBin = ceil(highHz / binWidthHz).toInt().coerceIn(firstBin, maximumBin)
            var peakMagnitude = 0.0

            for (bin in firstBin..lastBin) {
                val realIndex = bin * 2
                val imaginaryIndex = realIndex + 1
                if (imaginaryIndex >= fft.size) continue
                val real = fft[realIndex].toInt().toDouble()
                val imaginary = fft[imaginaryIndex].toInt().toDouble()
                val magnitude = sqrt(real * real + imaginary * imaginary) / 128.0
                if (magnitude > peakMagnitude) peakMagnitude = magnitude
            }

            val decibels = 20.0 * log10(peakMagnitude.coerceAtLeast(1.0e-6))
            ((decibels - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0.0f, 1.0f)
        }
    }

    companion object {
        private const val TAG = "GGEqFftAnalyzer"
        const val BAR_COUNT = 64
        private const val TARGET_CAPTURE_SIZE = 1024
        private const val MIN_CAPTURE_RATE_HZ = 15
        private const val MAX_CAPTURE_RATE_HZ = 60
        private const val ATTACK = 0.62f
        private const val RELEASE = 0.88f
        private const val FLOOR_DB = -72.0
        private const val MIN_VISUAL_FREQUENCY_HZ = 20.0
        private const val MAX_VISUAL_FREQUENCY_HZ = 20_000.0
        private const val FALLBACK_SAMPLE_RATE_HZ = 48_000.0
    }
}
