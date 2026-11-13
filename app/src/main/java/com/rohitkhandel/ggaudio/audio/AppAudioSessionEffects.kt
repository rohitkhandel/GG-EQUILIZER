package com.rohitkhandel.ggaudio.audio

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.ln
import kotlin.math.roundToInt

object AppAudioSessionStatus {
    private val mutableActivePackages = MutableStateFlow<Set<String>>(emptySet())

    val activePackages: StateFlow<Set<String>> = mutableActivePackages.asStateFlow()

    internal fun update(packages: Set<String>) {
        mutableActivePackages.value = packages
    }
}

/**
 * Holds insert effects for external players that explicitly announce their audio sessions
 * using AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.
 *
 * This is not a global mixer. The player must publish a usable session ID, and the device
 * audio policy must grant effect control for that session.
 */
class AppAudioSessionEffects(
    initialSettings: DspSettingsSnapshot = PlaybackDspSettings.store.snapshot()
) : Closeable {
    private data class ActiveSession(
        val packageName: String,
        val sessionId: Int,
        val equalizer: Equalizer,
        val bassBoost: BassBoost?,
        val virtualizer: Virtualizer?
    )

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gg-app-audio-effects").apply { isDaemon = true }
    }
    private val sessions = mutableMapOf<Int, ActiveSession>()

    @Volatile
    private var latestSettings = initialSettings

    @Volatile
    private var disposed = false

    fun openSession(packageName: String, sessionId: Int) {
        if (packageName.isBlank() || sessionId <= 0) return
        dispatch {
            val previous = sessions[sessionId]
            if (previous?.packageName == packageName) {
                applySettings(previous, latestSettings)
                return@dispatch
            }
            if (previous != null) {
                sessions.remove(sessionId)
                releaseSession(previous)
                publishActivePackages()
            }

            val equalizer = try {
                Equalizer(sessionId, 0)
            } catch (_: Exception) {
                return@dispatch
            }

            if (!runCatching { equalizer.hasControl() }.getOrDefault(false)) {
                runCatching { equalizer.release() }
                return@dispatch
            }

            val bassBoost = createOptionalEffect { BassBoost(sessionId, 0) }
            val virtualizer = createOptionalEffect { Virtualizer(sessionId, 0) }
            val active = ActiveSession(
                packageName = packageName,
                sessionId = sessionId,
                equalizer = equalizer,
                bassBoost = bassBoost,
                virtualizer = virtualizer
            )
            sessions[sessionId] = active
            publishActivePackages()
            applySettings(active, latestSettings)
        }
    }

    fun closeSession(packageName: String, sessionId: Int) {
        if (packageName.isBlank() || sessionId <= 0) return
        dispatch {
            val active = sessions[sessionId] ?: return@dispatch
            if (active.packageName == packageName) {
                sessions.remove(sessionId)
                releaseSession(active)
                publishActivePackages()
            }
        }
    }

    fun updateSettings(settings: DspSettingsSnapshot) {
        latestSettings = settings.copy(bands = settings.bands.toList())
        dispatch {
            sessions.values.toList().forEach { active ->
                applySettings(active, latestSettings)
            }
        }
    }

    private fun applySettings(active: ActiveSession, settings: DspSettingsSnapshot) {
        runCatching {
            val equalizer = active.equalizer
            if (equalizer.hasControl()) {
                val range = equalizer.bandLevelRange
                if (range.size >= 2) {
                    val minimum = range[0].toInt()
                    val maximum = range[1].toInt()
                    val count = equalizer.numberOfBands.toInt()
                    for (index in 0 until count) {
                        val frequencyHz = (equalizer.getCenterFreq(index.toShort()) / 1000).coerceAtLeast(1)
                        val gainDb = interpolateGain(settings.bands, frequencyHz)
                        val millibels = (gainDb * 100f).roundToInt().coerceIn(minimum, maximum)
                        equalizer.setBandLevel(index.toShort(), millibels.toShort())
                    }
                }
                equalizer.enabled = true
            }
        }

        val bassPercent = settings.bassBoostPercent.coerceIn(0, 100)
        active.bassBoost?.let { effect ->
            runCatching {
                if (effect.hasControl()) {
                    effect.setStrength((bassPercent * 10).toShort())
                    effect.enabled = bassPercent > 0
                }
            }
        }

        val widthPercent = settings.stereoWidthPercent.coerceIn(0, 100)
        active.virtualizer?.let { effect ->
            runCatching {
                if (effect.hasControl()) {
                    effect.setStrength((widthPercent * 10).toShort())
                    effect.enabled = widthPercent > 0
                }
            }
        }
    }

    private fun publishActivePackages() {
        AppAudioSessionStatus.update(sessions.values.mapTo(mutableSetOf()) { it.packageName })
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

    private fun <T : android.media.audiofx.AudioEffect> createOptionalEffect(factory: () -> T): T? {
        return try {
            factory().also { effect ->
                if (!effect.hasControl()) {
                    effect.release()
                } else {
                    effect.enabled = false
                }
            }.takeIf { it.hasControl() }
        } catch (_: Exception) {
            null
        }
    }

    private fun releaseSession(active: ActiveSession) {
        runCatching { active.virtualizer?.release() }
        runCatching { active.bassBoost?.release() }
        runCatching { active.equalizer.release() }
    }

    private inline fun dispatch(crossinline action: () -> Unit) {
        if (disposed) return
        try {
            worker.execute {
                if (!disposed) runCatching { action() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // A late event after service shutdown is harmless.
        }
    }

    override fun close() {
        if (disposed) return
        disposed = true
        try {
            worker.execute {
                sessions.values.toList().forEach(::releaseSession)
                sessions.clear()
                publishActivePackages()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
        worker.shutdown()
    }
}
