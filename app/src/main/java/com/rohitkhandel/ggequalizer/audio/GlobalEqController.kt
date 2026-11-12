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
            val updated = profile.bands.toMutableList()
            updated[index] = band.sanitized()
            profile = profile.copy(bands = updated)
            syncNativeLocked()
            applyProfileToAllSessionsLocked()
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
            syncNativeLocked()
            applyProfileToAllSessionsLocked()
        }
        publishSnapshot()
    }

    fun setPreampGain(gainDb: Float) {
        if (!gainDb.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(preampGainDb = gainDb.coerceIn(-24.0f, 12.0f))
            syncNativeLocked()
            applyProfileToAllSessionsLocked()
        }
        publishSnapshot()
    }

    fun setDvcCompensationDb(compensationDb: Float) {
        if (!compensationDb.isFinite()) return
        synchronized(lock) {
            dvcCompensationDb = compensationDb.coerceIn(-24.0f, 0.0f)
            applyProfileToAllSessionsLocked()
            syncNativeLocked()
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
            syncNativeLocked()
            applyProfileToAllSessionsLocked()
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
     * Conservative headroom estimate from the four strongest enabled bands
     * plus positive tone shelves. When the safety limiter is enabled the DSP
     * input gain is lowered before the soft-knee limiter to reduce clipping risk.
     */
    private fun automaticHeadroomCompensationDbLocked(): Float {
        if (!profile.limiterEnabled) return 0.0f
        val strongestBandBoost = profile.bands
            .asSequence()
            .filter { it.enabled }
            .map { it.gainDb.coerceAtLeast(0.0f) }
            .sortedDescending()
            .take(4)
            .sum() * 0.45f
        val toneBoost = (
            profile.bassGainDb.coerceAtLeast(0.0f) +
                profile.trebleGainDb.coerceAtLeast(0.0f)
            ) * 0.25f
        return (strongestBandBoost + toneBoost).coerceIn(0.0f, 12.0f)
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
        orderedBands.forEachIndexed { index, band ->
            val next = orderedBands.getOrNull(index + 1)?.frequencyHz ?: 20_000.0f
            val cutoff = sqrt(
                band.frequencyHz.coerceAtLeast(20.0f) *
                    next.coerceAtLeast(band.frequencyHz + 1.0f)
            ).coerceIn(20.0f, 20_000.0f)

            effect.setPreEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(
                    band.enabled && profile.enabled,
                    cutoff,
                    if (band.enabled) band.gainDb else 0.0f
                )
            )

            val postGain = when (index) {
                0 -> profile.bassGainDb
                BAND_COUNT - 1 -> profile.trebleGainDb
                else -> 0.0f
            }
            effect.setPostEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(profile.enabled, cutoff, postGain)
            )
        }

        val mbc = DynamicsProcessing.Mbc(true, profile.enabled, MBC_BAND_COUNT)
        floatArrayOf(250.0f, 4_000.0f, 20_000.0f).forEachIndexed { index, cutoff ->
            mbc.setBand(
                index,
                DynamicsProcessing.MbcBand(
                    profile.enabled,
                    cutoff,
                    10.0f,
                    100.0f,
                    1.5f,
                    -18.0f,
                    6.0f,
                    -80.0f,
                    1.0f,
                    0.0f,
                    0.0f
                )
            )
        }
        effect.setMbcAllChannelsTo(mbc)
        effect.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true,
                profile.limiterEnabled && profile.enabled,
                0,
                5.0f,
                profile.limiterReleaseMs,
                20.0f,
                profile.limiterThresholdDb,
                0.0f
            )
        )
        effect.enabled = profile.enabled
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
                    val nearest = profile.bands.minByOrNull { band ->
                        abs(ln(band.frequencyHz.coerceAtLeast(1.0f) / centerHz))
                    }
                    val gainMb = ((if (nearest?.enabled == true) nearest.gainDb else 0.0f) * 100.0f)
                        .toInt()
                        .coerceIn(low, high)
                    equalizer.setBandLevel(platformIndex.toShort(), gainMb.toShort())
                }
                equalizer.enabled = profile.enabled
            }.onFailure { Log.w(TAG, "Could not update Equalizer fallback", it) }
        }
        effects.bassBoost?.let { boost ->
            runCatching {
                val strength = (
                    profile.bassGainDb.coerceIn(0.0f, 12.0f) / 12.0f * 1000.0f
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
