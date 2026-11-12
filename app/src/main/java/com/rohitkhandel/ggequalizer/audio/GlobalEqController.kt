package com.rohitkhandel.ggequalizer.audio

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.exp

/**
 * Owns per-session Android effects and synchronizes their profile with the
 * native DSP core. A session can be modified only when the player exposes an
 * audio session ID and the platform/OEM effect framework allows attachment.
 *
 * Android DynamicsProcessing EQ bands are cutoff-based graphic bands. The API
 * does not expose Q or peaking/shelf type; those detailed parameters remain
 * available to GG-owned streams through NativeDspBridge.
 */
object GlobalEqController {
    private const val TAG = "GlobalEqController"
    private const val SAMPLE_RATE_HZ = 48_000
    private const val CHANNEL_COUNT = 2
    private const val BAND_COUNT = NativeDspBridge.MAX_PARAMETRIC_BANDS
    private const val MBC_BAND_COUNT = 3
    private const val LN_2 = 0.69314718056f

    private data class SessionEffects(
        val sessionId: Int,
        val packageName: String,
        var dynamics: DynamicsProcessing? = null,
        var equalizer: Equalizer? = null,
        var bassBoost: BassBoost? = null,
        var loudnessEnhancer: LoudnessEnhancer? = null,
        var attachment: String = "unavailable"
    ) {
        fun release() {
            runCatching { dynamics?.release() }
            runCatching { equalizer?.release() }
            runCatching { bassBoost?.release() }
            runCatching { loudnessEnhancer?.release() }
            dynamics = null
            equalizer = null
            bassBoost = null
            loudnessEnhancer = null
        }

        fun setEnabled(enabled: Boolean) {
            runCatching { dynamics?.enabled = enabled }
            runCatching { equalizer?.enabled = enabled }
            runCatching { bassBoost?.enabled = enabled }
            runCatching { loudnessEnhancer?.enabled = enabled }
        }
    }

    private val lock = Any()
    private val sessions = LinkedHashMap<Int, SessionEffects>()
    private val listeners = CopyOnWriteArraySet<(GlobalEqSnapshot) -> Unit>()

    private var applicationContext: Context? = null
    private var nativeHandle = 0L
    private var activePlayerPackages: List<String> = emptyList()
    private var profile = EqAudioProfile.defaults()
    private var dvcCompensationDb = 0.0f

    fun initialize(context: Context) {
        synchronized(lock) {
            applicationContext = context.applicationContext
            ensureNativeEngineLocked()
            applyProfileToAllSessionsLocked()
        }
        publishSnapshot()
    }

    fun snapshot(): GlobalEqSnapshot = synchronized(lock) { snapshotLocked() }

    fun currentProfile(): EqAudioProfile = synchronized(lock) {
        profile.copy(bands = profile.bands.toList())
    }

    /** Estimated maximum positive response used by DVC and anti-overload headroom. */
    fun estimatedPeakBoostDb(): Float = synchronized(lock) {
        estimatedPeakBoostDbLocked()
    }

    fun addListener(listener: (GlobalEqSnapshot) -> Unit) {
        listeners.add(listener)
        runCatching { listener(snapshot()) }
    }

    fun removeListener(listener: (GlobalEqSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun updateActivePlayers(packages: List<String>) {
        synchronized(lock) {
            activePlayerPackages = packages.filter(String::isNotBlank).distinct().take(16)
        }
        publishSnapshot()
    }

    fun attachSession(sessionId: Int, packageName: String): String {
        if (sessionId < 0 || packageName.isBlank()) return "invalid"

        val attachment = synchronized(lock) {
            sessions[sessionId]?.let { existing ->
                if (existing.packageName == packageName) {
                    return@synchronized existing.attachment
                }
                existing.release()
                sessions.remove(sessionId)
            }

            ensureNativeEngineLocked()
            val effects = SessionEffects(sessionId, packageName)
            if (profile.enabled && !attachDynamicsLocked(effects)) {
                attachFallbackEffectsLocked(effects)
            }

            effects.attachment = when {
                effects.dynamics != null -> "dynamics-processing"
                effects.equalizer != null || effects.bassBoost != null ||
                    effects.loudnessEnhancer != null -> "legacy-effects"
                else -> "session-observed-no-compatible-effect"
            }
            sessions[sessionId] = effects
            Log.i(TAG, "Audio session $sessionId ($packageName): ${effects.attachment}")
            effects.attachment
        }

        publishSnapshot()
        return attachment
    }

    fun detachSession(sessionId: Int) {
        synchronized(lock) { sessions.remove(sessionId)?.release() }
        publishSnapshot()
    }

    fun detachAllSessions() {
        synchronized(lock) {
            sessions.values.forEach(SessionEffects::release)
            sessions.clear()
        }
        publishSnapshot()
    }

    fun setMasterEnabled(enabled: Boolean) {
        synchronized(lock) {
            profile = profile.copy(enabled = enabled)
            if (enabled) {
                sessions.values.forEach { effects ->
                    if (effects.dynamics == null && effects.equalizer == null &&
                        effects.bassBoost == null && effects.loudnessEnhancer == null
                    ) {
                        if (!attachDynamicsLocked(effects)) attachFallbackEffectsLocked(effects)
                    }
                    effects.setEnabled(true)
                    applyProfileLocked(effects)
                }
            } else {
                sessions.values.forEach { it.setEnabled(false) }
            }
            syncNativeLocked()
        }
        publishSnapshot()
    }

    fun setBand(index: Int, band: EqBandParameter) {
        if (index !in 0 until BAND_COUNT) return
        synchronized(lock) {
            val normalizedBand = band.sanitized()
            val updated = profile.bands.toMutableList()
            updated[index] = normalizedBand
            profile = profile.copy(bands = updated)
            if (nativeHandle == 0L) ensureNativeEngineLocked()
            if (nativeHandle != 0L) {
                runCatching {
                    NativeDspBridge.nativeSetBand(
                        nativeHandle,
                        index,
                        normalizedBand.filterType,
                        normalizedBand.frequencyHz,
                        normalizedBand.gainDb,
                        normalizedBand.qFactor,
                        normalizedBand.enabled
                    )
                    NativeDspBridge.nativeSetPreampGain(nativeHandle, effectivePreampDbLocked())
                }.onFailure { Log.w(TAG, "Could not update native EQ band $index", it) }
            }
            applyBandToAllSessionsLocked()
        }
        publishSnapshot()
    }

    fun setTone(bassDb: Float, trebleDb: Float) {
        if (!bassDb.isFinite() || !trebleDb.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(
                bassGainDb = bassDb.coerceIn(-12.0f, 12.0f),
                trebleGainDb = trebleDb.coerceIn(-12.0f, 12.0f)
            )
            if (nativeHandle == 0L) ensureNativeEngineLocked()
            if (nativeHandle != 0L) {
                runCatching {
                    NativeDspBridge.nativeSetTone(nativeHandle, profile.bassGainDb, profile.trebleGainDb)
                    NativeDspBridge.nativeSetPreampGain(nativeHandle, effectivePreampDbLocked())
                }.onFailure { Log.w(TAG, "Could not update native tone stack", it) }
            }
            sessions.values.forEach(::applyProfileLocked)
        }
        publishSnapshot()
    }

    fun setPreampGain(gainDb: Float) {
        if (!gainDb.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(preampGainDb = gainDb.coerceIn(-24.0f, 12.0f))
            if (nativeHandle == 0L) ensureNativeEngineLocked()
            if (nativeHandle != 0L) {
                runCatching { NativeDspBridge.nativeSetPreampGain(nativeHandle, effectivePreampDbLocked()) }
                    .onFailure { Log.w(TAG, "Could not update native preamp", it) }
            }
            applyInputGainToAllSessionsLocked()
        }
        publishSnapshot()
    }

    fun setDvcCompensationDb(compensationDb: Float) {
        if (!compensationDb.isFinite()) return
        synchronized(lock) {
            val normalized = compensationDb.coerceIn(-24.0f, 0.0f)
            if (abs(dvcCompensationDb - normalized) < 0.001f) return@synchronized
            dvcCompensationDb = normalized
            if (nativeHandle == 0L) ensureNativeEngineLocked()
            if (nativeHandle != 0L) {
                runCatching { NativeDspBridge.nativeSetPreampGain(nativeHandle, effectivePreampDbLocked()) }
                    .onFailure { Log.w(TAG, "Could not update DVC preamp", it) }
            }
            applyInputGainToAllSessionsLocked()
        }
        publishSnapshot()
    }

    fun setLimiter(enabled: Boolean, thresholdDb: Float, releaseMs: Float) {
        if (!thresholdDb.isFinite() || !releaseMs.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(
                limiterEnabled = enabled,
                limiterThresholdDb = thresholdDb.coerceIn(-12.0f, 0.0f),
                limiterReleaseMs = releaseMs.coerceIn(10.0f, 300.0f)
            )
            if (nativeHandle == 0L) ensureNativeEngineLocked()
            if (nativeHandle != 0L) {
                runCatching {
                    NativeDspBridge.nativeSetLimiter(
                        nativeHandle,
                        profile.limiterEnabled,
                        profile.limiterThresholdDb,
                        profile.limiterKneeDb,
                        profile.limiterLookAheadMs,
                        profile.limiterReleaseMs
                    )
                    NativeDspBridge.nativeSetPreampGain(nativeHandle, effectivePreampDbLocked())
                }.onFailure { Log.w(TAG, "Could not update native limiter", it) }
            }
            sessions.values.forEach { effects ->
                val dynamics = effects.dynamics
                if (dynamics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { applyLimiterStagesLocked(dynamics) }
                        .onFailure { Log.w(TAG, "Could not update session limiter", it) }
                } else {
                    applyFallbackLocked(effects)
                }
            }
            applyInputGainToAllSessionsLocked()
        }
        publishSnapshot()
    }

    fun setStereoWidthPercent(widthPercent: Float) {
        if (!widthPercent.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(stereoWidthPercent = widthPercent.coerceIn(0.0f, 200.0f))
            syncNativeLocked()
        }
        publishSnapshot()
    }

    fun setStereoBalance(balance: Float) {
        if (!balance.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(stereoBalance = balance.coerceIn(-1.0f, 1.0f))
            syncNativeLocked()
        }
        publishSnapshot()
    }

    fun setProfile(newProfile: EqAudioProfile) {
        synchronized(lock) {
            profile = newProfile.sanitized()
            syncNativeLocked()
            applyProfileToAllSessionsLocked()
        }
        publishSnapshot()
    }

    fun nativeEngineHandle(): Long = synchronized(lock) {
        ensureNativeEngineLocked()
        nativeHandle
    }

    fun shutdown() {
        synchronized(lock) {
            sessions.values.forEach(SessionEffects::release)
            sessions.clear()
            if (nativeHandle != 0L) {
                runCatching { NativeDspBridge.nativeDestroy(nativeHandle) }
                nativeHandle = 0L
            }
            applicationContext = null
            activePlayerPackages = emptyList()
        }
        publishSnapshot()
    }

    private fun ensureNativeEngineLocked() {
        if (nativeHandle == 0L) {
            nativeHandle = runCatching {
                NativeDspBridge.nativeCreate(SAMPLE_RATE_HZ)
            }.getOrDefault(0L)
        }
        if (nativeHandle != 0L) syncNativeLocked()
    }

    /**
     * Estimate the highest combined EQ + tone response across the audible
     * spectrum. This is a practical digital headroom reserve, not a guarantee
     * against clipping already present in the source or downstream hardware.
     */
    private fun automaticHeadroomCompensationDbLocked(): Float =
        if (profile.limiterEnabled) estimatedPeakBoostDbLocked() else 0.0f

    private fun estimatedPeakBoostDbLocked(): Float {
        var maximumResponseDb = 0.0f
        for (index in 0..64) {
            val progress = index / 64.0f
            val frequencyHz = (20.0 * 1000.0.pow(progress)).toFloat()
            val response = smoothBandGainAtFrequency(profile.bands, frequencyHz) +
                bassToneGainAtFrequency(frequencyHz) +
                trebleToneGainAtFrequency(frequencyHz) +
                profile.preampGainDb
            maximumResponseDb = maxOf(maximumResponseDb, response)
        }
        return maximumResponseDb.coerceIn(0.0f, 18.0f)
    }

    private fun gaussianOctaveWeight(frequencyHz: Float, centerHz: Float, sigmaOctaves: Float): Float {
        val distance = ln((frequencyHz / centerHz).coerceAtLeast(0.0001f)) / LN_2
        return exp(-0.5f * (distance / sigmaOctaves).pow(2))
    }

    /**
     * Convert parametric peaks into a smooth overlapping graphic-EQ response
     * for Android DynamicsProcessing. Q controls the overlap width.
     */
    private fun smoothBandGainAtFrequency(
        bands: List<EqBandParameter>,
        frequencyHz: Float
    ): Float {
        var weightedGain = 0.0f
        var totalWeight = 0.0f
        bands.forEach { band ->
            if (!band.enabled) return@forEach
            val sigma = (0.22f / band.qFactor.coerceIn(0.1f, 10.0f)).coerceIn(0.08f, 0.42f)
            val weight = gaussianOctaveWeight(frequencyHz, band.frequencyHz, sigma)
            weightedGain += band.gainDb * weight
            totalWeight += weight
        }
        return if (totalWeight > 0.0001f) weightedGain / totalWeight else 0.0f
    }

    /** Broad low shelf plus controlled 31 / 62 / 125 Hz warm-bass overlap. */
    private fun bassToneGainAtFrequency(frequencyHz: Float): Float {
        val shelf = 0.65f / (1.0f + (frequencyHz / 180.0f).pow(4))
        val sub = 0.18f * gaussianOctaveWeight(frequencyHz, 31.0f, 0.65f)
        val punch = 0.20f * gaussianOctaveWeight(frequencyHz, 62.0f, 0.65f)
        val body = 0.18f * gaussianOctaveWeight(frequencyHz, 125.0f, 0.65f)
        return profile.bassGainDb * (shelf + sub + punch + body).coerceIn(0.0f, 1.0f)
    }

    /** Presence / air shelf spread across 8 / 12 / 16 kHz instead of one edge band. */
    private fun trebleToneGainAtFrequency(frequencyHz: Float): Float {
        val shelf = 0.50f / (1.0f + (10_000.0f / frequencyHz.coerceAtLeast(20.0f)).pow(4))
        val presence = 0.20f * gaussianOctaveWeight(frequencyHz, 8_000.0f, 0.50f)
        val air = 0.25f * gaussianOctaveWeight(frequencyHz, 12_000.0f, 0.50f)
        val topAir = 0.25f * gaussianOctaveWeight(frequencyHz, 16_000.0f, 0.50f)
        return profile.trebleGainDb * (shelf + presence + air + topAir).coerceIn(0.0f, 1.0f)
    }

    private fun effectivePreampDbLocked(): Float =
        (
            profile.preampGainDb +
                dvcCompensationDb -
                automaticHeadroomCompensationDbLocked()
            ).coerceIn(-24.0f, 12.0f)

    private fun syncNativeLocked() {
        val handle = nativeHandle
        if (handle == 0L) return
        runCatching {
            NativeDspBridge.nativeSetSampleRate(handle, SAMPLE_RATE_HZ)
            profile.bands.forEachIndexed { index, band ->
                NativeDspBridge.nativeSetBand(
                    handle, index, band.filterType, band.frequencyHz,
                    band.gainDb, band.qFactor, band.enabled
                )
            }
            NativeDspBridge.nativeSetTone(handle, profile.bassGainDb, profile.trebleGainDb)
            NativeDspBridge.nativeSetPreampGain(handle, effectivePreampDbLocked())
            NativeDspBridge.nativeSetStereoWidthPercent(handle, profile.stereoWidthPercent)
            NativeDspBridge.nativeSetStereoBalance(handle, profile.stereoBalance)
            NativeDspBridge.nativeSetLimiter(
                handle,
                profile.limiterEnabled,
                profile.limiterThresholdDb,
                profile.limiterKneeDb,
                profile.limiterLookAheadMs,
                profile.limiterReleaseMs
            )
        }.onFailure { Log.w(TAG, "Native DSP parameter synchronization failed", it) }
    }

    private fun attachDynamicsLocked(effects: SessionEffects): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNEL_COUNT,
                true,
                BAND_COUNT,
                true,
                MBC_BAND_COUNT,
                true,
                BAND_COUNT,
                true
            ).build()

            val dynamics = DynamicsProcessing(0, effects.sessionId, config)
            effects.dynamics = dynamics
            applyDynamicsLocked(dynamics)
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "DynamicsProcessing could not attach to session ${effects.sessionId}", error)
            runCatching { effects.dynamics?.release() }
            effects.dynamics = null
            false
        } catch (error: LinkageError) {
            Log.w(TAG, "DynamicsProcessing is not linked on this device", error)
            effects.dynamics = null
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyDynamicsLocked(effect: DynamicsProcessing) {
        effect.setInputGainAllChannelsTo(effectivePreampDbLocked())
        val orderedBands = profile.bands.sortedBy { it.frequencyHz }
        for (index in orderedBands.indices) {
            val band = orderedBands[index]
            val previous = orderedBands.getOrNull(index - 1)?.frequencyHz ?: 20.0f
            val next = orderedBands.getOrNull(index + 1)?.frequencyHz ?: 20_000.0f
            val cutoff = sqrt(
                (band.frequencyHz.coerceAtLeast(previous + 1.0f)) *
                    next.coerceAtLeast(band.frequencyHz + 1.0f)
            ).coerceIn(20.0f, 20_000.0f)
            val eqGain = smoothBandGainAtFrequency(orderedBands, cutoff)
            val toneGain = bassToneGainAtFrequency(cutoff) + trebleToneGainAtFrequency(cutoff)

            effect.setPreEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(profile.enabled, cutoff, eqGain)
            )
            effect.setPostEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(profile.enabled, cutoff, toneGain)
            )
        }

        val mbcInUse = profile.enabled && profile.limiterEnabled
        val mbc = DynamicsProcessing.Mbc(true, mbcInUse, MBC_BAND_COUNT)
        val mbcCutoffs = floatArrayOf(120.0f, 4_000.0f, 20_000.0f)
        mbcCutoffs.forEachIndexed { index, cutoff ->
            val ratio = when (index) {
                0 -> 2.0f
                1 -> 2.5f
                else -> 3.0f
            }
            val threshold = if (index == 0) -10.0f else -14.0f
            mbc.setBand(
                index,
                DynamicsProcessing.MbcBand(
                    mbcInUse,
                    cutoff,
                    8.0f,
                    60.0f,
                    ratio,
                    threshold,
                    4.0f,
                    -80.0f,
                    1.0f,
                    0.0f,
                    0.0f
                )
            )
        }
        effect.setMbcAllChannelsTo(mbc)
        applyLimiterStagesLocked(effect)
        effect.enabled = profile.enabled
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyLimiterStagesLocked(effect: DynamicsProcessing) {
        effect.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true,
                profile.enabled && profile.limiterEnabled,
                0,
                1.0f,
                60.0f,
                10.0f,
                minOf(profile.limiterThresholdDb, -1.5f),
                0.0f
            )
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyInputGainToAllSessionsLocked() {
        sessions.values.forEach { effects ->
            val dynamics = effects.dynamics
            if (dynamics != null && profile.enabled) {
                runCatching { dynamics.setInputGainAllChannelsTo(effectivePreampDbLocked()) }
                    .onFailure { Log.w(TAG, "Could not update session input gain", it) }
            } else if (!profile.enabled) {
                effects.setEnabled(false)
            }
        }
    }

    private fun attachFallbackEffectsLocked(effects: SessionEffects) {
        if (effects.equalizer == null) {
            runCatching {
                Equalizer(0, effects.sessionId).also { eq ->
                    eq.enabled = profile.enabled
                    effects.equalizer = eq
                }
            }.onFailure { Log.w(TAG, "Equalizer fallback failed", it) }
        }
        if (effects.bassBoost == null) {
            runCatching {
                BassBoost(0, effects.sessionId).also { boost ->
                    boost.enabled = profile.enabled
                    effects.bassBoost = boost
                }
            }.onFailure { Log.w(TAG, "BassBoost fallback failed", it) }
        }
        if (effects.loudnessEnhancer == null) {
            runCatching {
                LoudnessEnhancer(effects.sessionId).also { enhancer ->
                    enhancer.setTargetGain(0)
                    enhancer.enabled = profile.enabled
                    effects.loudnessEnhancer = enhancer
                }
            }.onFailure { Log.w(TAG, "LoudnessEnhancer fallback failed", it) }
        }
        applyFallbackLocked(effects)
    }

    private fun applyFallbackLocked(effects: SessionEffects) {
        effects.equalizer?.let { equalizer ->
            runCatching {
                val count = equalizer.numberOfBands.toInt()
                val range = equalizer.bandLevelRange
                val low = range[0].toInt()
                val high = range[1].toInt()
                for (platformIndex in 0 until count) {
                    val centerHz =
                        (equalizer.getCenterFreq(platformIndex.toShort()) / 1000.0f)
                            .coerceAtLeast(1.0f)
                    val equalizerGain = smoothBandGainAtFrequency(profile.bands, centerHz)
                    val toneGain = bassToneGainAtFrequency(centerHz) + trebleToneGainAtFrequency(centerHz)
                    val gainMb = ((equalizerGain + toneGain) * 100.0f)
                        .toInt()
                        .coerceIn(low, high)
                    equalizer.setBandLevel(platformIndex.toShort(), gainMb.toShort())
                }
                equalizer.enabled = profile.enabled
            }.onFailure { Log.w(TAG, "Could not update Equalizer fallback", it) }
        }
        effects.bassBoost?.let { boost ->
            runCatching {
                // Keep the hardware BassBoost complementary to the 31/62/125 Hz EQ contour.
                val strength = (
                    profile.bassGainDb.coerceIn(0.0f, 12.0f) / 12.0f * 650.0f
                    ).toInt()
                boost.setStrength(strength.toShort())
                boost.enabled = profile.enabled
            }.onFailure { Log.w(TAG, "Could not update BassBoost fallback", it) }
        }
        effects.loudnessEnhancer?.let { enhancer ->
            runCatching {
                enhancer.setTargetGain(0)
                enhancer.enabled = profile.enabled
            }.onFailure { Log.w(TAG, "Could not update LoudnessEnhancer fallback", it) }
        }
    }

    private fun applyProfileLocked(effects: SessionEffects) {
        val dynamics = effects.dynamics
        if (dynamics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { applyDynamicsLocked(dynamics) }
                .onFailure { Log.w(TAG, "Could not update DynamicsProcessing", it) }
        } else {
            applyFallbackLocked(effects)
        }
    }

    /**
     * Live fader fast path. Update the smoothed 32-band pre-EQ curve off the UI
     * thread without re-synchronizing all native parameters or tone stages.
     */
    private fun applyBandToAllSessionsLocked() {
        val ordered = profile.bands.sortedBy { it.frequencyHz }
        sessions.values.forEach { effects ->
            if (!profile.enabled) {
                effects.setEnabled(false)
                return@forEach
            }
            val dynamics = effects.dynamics
            if (dynamics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching {
                    dynamics.setInputGainAllChannelsTo(effectivePreampDbLocked())
                    ordered.forEachIndexed { index, band ->
                        val previous = ordered.getOrNull(index - 1)?.frequencyHz ?: 20.0f
                        val next = ordered.getOrNull(index + 1)?.frequencyHz ?: 20_000.0f
                        val cutoff = sqrt(
                            band.frequencyHz.coerceAtLeast(previous + 1.0f) *
                                next.coerceAtLeast(band.frequencyHz + 1.0f)
                        ).coerceIn(20.0f, 20_000.0f)
                        dynamics.setPreEqBandAllChannelsTo(
                            index,
                            DynamicsProcessing.EqBand(
                                band.enabled,
                                cutoff,
                                smoothBandGainAtFrequency(ordered, cutoff)
                            )
                        )
                    }
                    dynamics.enabled = true
                }.onFailure { Log.w(TAG, "Could not update live pre-EQ curve", it) }
            } else {
                applyFallbackLocked(effects)
            }
        }
    }

    private fun applyProfileToAllSessionsLocked() {
        sessions.values.forEach { effects ->
            if (profile.enabled) {
                applyProfileLocked(effects)
                effects.setEnabled(true)
            } else {
                effects.setEnabled(false)
            }
        }
    }

    private fun snapshotLocked() = GlobalEqSnapshot(
        masterEnabled = profile.enabled,
        activePlayers = activePlayerPackages,
        sessions = sessions.values.map {
            AudioSessionStatus(it.sessionId, it.packageName, it.attachment)
        },
        preampGainDb = profile.preampGainDb,
        effectivePreampGainDb = effectivePreampDbLocked(),
        bassGainDb = profile.bassGainDb,
        trebleGainDb = profile.trebleGainDb,
        limiterEnabled = profile.limiterEnabled
    )

    private fun publishSnapshot() {
        val current = snapshot()
        listeners.forEach { listener -> runCatching { listener(current) } }
    }
}

data class EqBandParameter(
    val frequencyHz: Float,
    val gainDb: Float = 0.0f,
    val qFactor: Float = 1.0f,
    val filterType: Int = NativeDspBridge.FILTER_PEAKING,
    val enabled: Boolean = true
) {
    fun sanitized() = copy(
        frequencyHz = frequencyHz.takeIf(Float::isFinite)?.coerceIn(20.0f, 20_000.0f) ?: 1000.0f,
        gainDb = gainDb.takeIf(Float::isFinite)?.coerceIn(-24.0f, 24.0f) ?: 0.0f,
        qFactor = qFactor.takeIf(Float::isFinite)?.coerceIn(0.1f, 10.0f) ?: 1.0f,
        filterType = filterType.coerceIn(0, 2)
    )
}

private fun defaultEqBands(): List<EqBandParameter> =
    List(NativeDspBridge.MAX_PARAMETRIC_BANDS) { index ->
        val fraction = index.toDouble() /
            (NativeDspBridge.MAX_PARAMETRIC_BANDS - 1).toDouble()
        EqBandParameter(frequencyHz = 20.0f * 1000.0.pow(fraction).toFloat())
    }

data class EqAudioProfile(
    val enabled: Boolean = true,
    val bands: List<EqBandParameter> = defaultEqBands(),
    val preampGainDb: Float = 0.0f,
    val bassGainDb: Float = 0.0f,
    val trebleGainDb: Float = 0.0f,
    val limiterEnabled: Boolean = true,
    val limiterThresholdDb: Float = -1.0f,
    val limiterKneeDb: Float = 6.0f,
    val limiterLookAheadMs: Float = 5.0f,
    val limiterReleaseMs: Float = 80.0f,
    val stereoWidthPercent: Float = 100.0f,
    val stereoBalance: Float = 0.0f
) {
    fun sanitized(): EqAudioProfile {
        val cleanBands = bands.take(NativeDspBridge.MAX_PARAMETRIC_BANDS)
            .map(EqBandParameter::sanitized)
        val paddedBands = cleanBands + defaultEqBands().drop(cleanBands.size)
        return copy(
            bands = paddedBands,
            preampGainDb = preampGainDb.takeIf(Float::isFinite)?.coerceIn(-24.0f, 12.0f) ?: 0.0f,
            bassGainDb = bassGainDb.takeIf(Float::isFinite)?.coerceIn(-12.0f, 12.0f) ?: 0.0f,
            trebleGainDb = trebleGainDb.takeIf(Float::isFinite)?.coerceIn(-12.0f, 12.0f) ?: 0.0f,
            limiterThresholdDb = limiterThresholdDb.takeIf(Float::isFinite)?.coerceIn(-12.0f, 0.0f) ?: -1.0f,
            limiterKneeDb = limiterKneeDb.takeIf(Float::isFinite)?.coerceIn(0.0f, 12.0f) ?: 6.0f,
            limiterLookAheadMs = limiterLookAheadMs.takeIf(Float::isFinite)?.coerceIn(0.0f, 20.0f) ?: 5.0f,
            limiterReleaseMs = limiterReleaseMs.takeIf(Float::isFinite)?.coerceIn(10.0f, 300.0f) ?: 80.0f,
            stereoWidthPercent = stereoWidthPercent.takeIf(Float::isFinite)?.coerceIn(0.0f, 200.0f) ?: 100.0f,
            stereoBalance = stereoBalance.takeIf(Float::isFinite)?.coerceIn(-1.0f, 1.0f) ?: 0.0f
        )
    }

    companion object {
        fun defaults() = EqAudioProfile()
    }
}

data class AudioSessionStatus(
    val sessionId: Int,
    val packageName: String,
    val attachment: String
)

data class GlobalEqSnapshot(
    val masterEnabled: Boolean,
    val activePlayers: List<String>,
    val sessions: List<AudioSessionStatus>,
    val preampGainDb: Float,
    val effectivePreampGainDb: Float,
    val bassGainDb: Float,
    val trebleGainDb: Float,
    val limiterEnabled: Boolean
)
