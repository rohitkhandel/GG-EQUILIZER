package com.rohitkhandel.ggaudio.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.roundToInt

data class AudioBackendStatus(
    val connected: Boolean,
    val message: String,
    val hardwareBandCount: Int = 0,
    val bassBoostAvailable: Boolean = false,
    val virtualizerAvailable: Boolean = false
)

/**
 * Best-effort adapter for the device's global output effects.
 *
 * Android deprecates insert effects on session 0. This adapter therefore
 * reports what the device actually granted and never treats construction alone
 * as proof that processing is active. Session-scoped local-player processing
 * remains the preferred path for consistent support.
 */
class GlobalAudioEffectsController : Closeable {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gg-audio-effects").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var equalizer: Equalizer? = null
    @Volatile private var bassBoost: BassBoost? = null
    @Volatile private var virtualizer: Virtualizer? = null
    @Volatile private var disposed = false

    fun connect(onComplete: (AudioBackendStatus) -> Unit) {
        dispatch {
            releaseEffects()
            val result = try {
                // Session 0 targets the global output mix where the device permits it.
                val candidate = Equalizer(0, 0)
                if (!candidate.hasControl()) {
                    candidate.release()
                    AudioBackendStatus(
                        connected = false,
                        message = "The system Equalizer exists, but GG Audio did not receive control."
                    )
                } else {
                    candidate.enabled = true
                    if (!candidate.enabled) {
                        candidate.release()
                        AudioBackendStatus(
                            connected = false,
                            message = "The device did not enable the global Equalizer."
                        )
                    } else {
                        equalizer = candidate
                        bassBoost = createOptionalEffect { BassBoost(0, 0) }
                        virtualizer = createOptionalEffect { Virtualizer(0, 0) }
                        AudioBackendStatus(
                            connected = true,
                            message = "System Equalizer attached. Other effects depend on device support.",
                            hardwareBandCount = candidate.numberOfBands.toInt(),
                            bassBoostAvailable = bassBoost?.hasControl() == true,
                            virtualizerAvailable = virtualizer?.hasControl() == true
                        )
                    }
                }
            } catch (exception: Exception) {
                releaseEffects()
                AudioBackendStatus(
                    connected = false,
                    message = exception.message?.take(180)
                        ?: "This device does not allow a global Equalizer session."
                )
            }
            mainHandler.post { onComplete(result) }
        }
    }

    fun applyBandGains(bands: List<EqualizerBand>) {
        dispatch {
            val eq = equalizer ?: return@dispatch
            if (!eq.hasControl()) return@dispatch

            val range = eq.bandLevelRange
            if (range.size < 2) return@dispatch
            val minMillibels = range[0].toInt()
            val maxMillibels = range[1].toInt()
            val hardwareBandCount = eq.numberOfBands.toInt()

            for (index in 0 until hardwareBandCount) {
                val centerFrequencyHz = (eq.getCenterFreq(index.toShort()) / 1000)
                    .coerceAtLeast(1)
                val gainDb = interpolateGain(bands, centerFrequencyHz)
                val millibels = (gainDb * 100f).roundToInt()
                    .coerceIn(minMillibels, maxMillibels)
                eq.setBandLevel(index.toShort(), millibels.toShort())
            }
        }
    }

    fun setBassStrength(percent: Int) {
        val strength = (percent.coerceIn(0, 100) * 10).toShort()
        dispatch {
            val effect = bassBoost ?: return@dispatch
            if (effect.hasControl()) {
                effect.setStrength(strength)
                effect.enabled = percent > 0
            }
        }
    }

    fun setVirtualizerStrength(percent: Int) {
        val strength = (percent.coerceIn(0, 100) * 10).toShort()
        dispatch {
            val effect = virtualizer ?: return@dispatch
            if (effect.hasControl()) {
                effect.setStrength(strength)
                effect.enabled = percent > 0
            }
        }
    }

    fun disconnect(onComplete: ((AudioBackendStatus) -> Unit)? = null) {
        dispatch {
            releaseEffects()
            onComplete?.let {
                mainHandler.post {
                    it(AudioBackendStatus(false, "System audio effects disconnected."))
                }
            }
        }
    }

    private fun <T : AudioEffect> createOptionalEffect(factory: () -> T): T? {
        return try {
            factory().also { effect ->
                if (!effect.hasControl()()) {
                    effect.release()
                } else {
                    effect.enabled = false
                }
            }.takeIf { it.hasControl() }
        } catch (_: Exception) {
            null
        }
    }

    private fun interpolateGain(bands: List<EqualizerBand>, frequencyHz: Int): Float {
        if (bands.isEmpty()) return 0f
        val ordered = bands.sortedBy { it.frequencyHz }
        if (frequencyHz <= ordered.first().frequencyHz) return ordered.first().gainDb
        if (frequencyHz >= ordered.last().frequencyHz) return ordered.last().gainDb

        val upperIndex = ordered.indexOfFirst { it.frequencyHz >= frequencyHz }
        val lower = ordered[upperIndex - 1]
        val upper = ordered[upperIndex]
        val frequency = ln(frequencyHz.toDouble())
        val lowerFrequency = ln(lower.frequencyHz.toDouble())
        val upperFrequency = ln(upper.frequencyHz.toDouble())
        val fraction = ((frequency - lowerFrequency) / (upperFrequency - lowerFrequency)).toFloat()
        return lower.gainDb + (upper.gainDb - lower.gainDb) * fraction
    }

    private fun releaseEffects() {
        try { virtualizer?.release() } catch (_: Exception) { }
        try { bassBoost?.release() } catch (_: Exception) { }
        try { equalizer?.release() } catch (_: Exception) { }
        virtualizer = null
        bassBoost = null
        equalizer = null
    }

    private inline fun dispatch(crossinline action: () -> Unit) {
        if (disposed) return
        try {
            worker.execute {
                if (!disposed) {
                    try {
                        action()
                    } catch (_: Exception) {
                        // A device/vendor effect may reject a runtime parameter.
                        // Connection state remains honest; later diagnostics can expose details.
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A queued UI event after close is harmless.
        }
    }

    override fun close() {
        if (disposed) return
        disposed = true
        try {
            worker.execute { releaseEffects() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
        worker.shutdown()
    }
}
