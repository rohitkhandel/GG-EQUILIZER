package com.rohitkhandel.ggequalizer.audio

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.log10

/**
 * Observes Android music-stream volume and applies conservative preamp
 * attenuation based on positive EQ/tone boosts.
 *
 * AudioManager exposes volume indices, not raw DAC gain or analog headroom.
 * estimatedHardwareAttenuationDb is telemetry only; clipping protection uses
 * a conservative software boost budget and does not assume a hardware curve.
 */
class DvcManager(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(DvcSnapshot) -> Unit>()

    private var observerRegistered = false
    private var dvcEnabled = false
    private var profile = GlobalEqController.currentProfile()

    @Volatile
    private var lastSnapshot = buildSnapshot()

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            refresh()
        }
    }

    init {
        activeInstance = this
    }

    fun start() {
        if (observerRegistered) return
        try {
            appContext.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
            observerRegistered = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not observe stream-volume settings", error)
        }
        refresh()
    }

    fun stop() {
        if (observerRegistered) {
            runCatching {
                appContext.contentResolver.unregisterContentObserver(volumeObserver)
            }
            observerRegistered = false
        }
        if (activeInstance === this) activeInstance = null
    }

    fun setEnabled(enabled: Boolean) {
        if (dvcEnabled == enabled) return
        dvcEnabled = enabled
        recomputeCompensation()
        refresh()
    }

    fun isEnabled(): Boolean = dvcEnabled

    fun updateProfile(updatedProfile: EqAudioProfile) {
        val clean = updatedProfile.sanitized()
        if (profile == clean) return
        profile = clean
        recomputeCompensation()
        refresh()
    }

    fun snapshot(): DvcSnapshot = lastSnapshot

    fun addListener(listener: (DvcSnapshot) -> Unit) {
        listeners.add(listener)
        runCatching { listener(lastSnapshot) }
    }

    fun removeListener(listener: (DvcSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun refresh() {
        val current = buildSnapshot()
        lastSnapshot = current
        listeners.forEach { listener -> runCatching { listener(current) } }
    }

    private fun recomputeCompensation() {
        // Reserve only the estimated peak of the combined response, not the
        // arithmetic sum of every positive band (which caused weak output).
        val requestedBoostDb = GlobalEqController.estimatedPeakBoostDb()
        val compensationDb = if (dvcEnabled) {
            -requestedBoostDb.coerceIn(0.0f, MAX_SOFTWARE_COMPENSATION_DB)
        } else {
            0.0f
        }
        GlobalEqController.setDvcCompensationDb(compensationDb)
    }

    private fun buildSnapshot(): DvcSnapshot {
        val current = runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(0)
        val maximum = runCatching {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(0)

        val fraction = if (maximum > 0) {
            (current.toFloat() / maximum.toFloat()).coerceIn(0.0f, 1.0f)
        } else {
            0.0f
        }
        val estimatedAttenuationDb = if (fraction <= 0.0001f) {
            -80.0f
        } else {
            (20.0 * log10(fraction.toDouble())).toFloat().coerceIn(-80.0f, 0.0f)
        }

        val boostDb = GlobalEqController.estimatedPeakBoostDb()

        val compensation = if (dvcEnabled) {
            -boostDb.coerceIn(0.0f, MAX_SOFTWARE_COMPENSATION_DB)
        } else {
            0.0f
        }

        return DvcSnapshot(
            enabled = dvcEnabled,
            musicVolumeIndex = current,
            musicVolumeMaximum = maximum,
            musicVolumeFraction = fraction,
            estimatedHardwareAttenuationDb = estimatedAttenuationDb,
            estimatedBoostBudgetDb = boostDb,
            preampCompensationDb = compensation,
            clippingProtectionActive = dvcEnabled && compensation < 0.0f
        )
    }

    companion object {
        private const val TAG = "DvcManager"
        private const val MAX_SOFTWARE_COMPENSATION_DB = 24.0f

        @Volatile
        private var activeInstance: DvcManager? = null

        fun setGlobalEnabled(enabled: Boolean) {
            activeInstance?.setEnabled(enabled)
        }

        fun globalSnapshot(): DvcSnapshot? = activeInstance?.snapshot()

        fun addGlobalListener(listener: (DvcSnapshot) -> Unit) {
            activeInstance?.addListener(listener)
        }

        fun removeGlobalListener(listener: (DvcSnapshot) -> Unit) {
            activeInstance?.removeListener(listener)
        }
    }
}

data class DvcSnapshot(
    val enabled: Boolean,
    val musicVolumeIndex: Int,
    val musicVolumeMaximum: Int,
    val musicVolumeFraction: Float,
    val estimatedHardwareAttenuationDb: Float,
    val estimatedBoostBudgetDb: Float,
    val preampCompensationDb: Float,
    val clippingProtectionActive: Boolean
)
