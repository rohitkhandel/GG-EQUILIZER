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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Per-player-session DSP controller.
 *
 * DynamicsProcessing provides the main 32-band graphic response. The Android
 * BassBoost effect is attached in parallel when the HAL accepts it, and the
 * post-EQ tone contour supplies the broad low-frequency and high-frequency
 * shaping. Hardware effects are best-effort because OEM audio effect chains
 * differ between devices and music players.
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
            runCatching { dynamics?.setEnabled(enabled) }
            runCatching { equalizer?.setEnabled(enabled) }
            runCatching { bassBoost?.setEnabled(enabled) }
            runCatching { loudnessEnhancer?.setEnabled(enabled) }
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

    /** Estimated positive response, used only by DVC when the limiter is off. */
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
                if (existing.packageName == packageName) return@synchronized existing.attachment
                existing.release()
                sessions.remove(sessionId)
            }

            ensureNativeEngineLocked()
            val effects = SessionEffects(sessionId, packageName)
            if (profile.enabled) {
                if (!attachDynamicsLocked(effects)) attachFallbackEffectsLocked(effects)
                applyProfileLocked(effects)
            }
            effects.attachment = when {
                effects.dynamics != null -> {
                    if (effects.bassBoost != null) "dynamics-processing + hardware-bass"
                    else "dynamics-processing"
                }
                effects.equalizer != null || effects.bassBoost != null ||
                    effects.loudnessEnhancer != null -> "legacy-effects"
                else -> "session-observed-no-compatible-effect"
            }
            sessions[sessionId] = effects
            Log.i(TAG, "Session $sessionId ($packageName): ${effects.attachment}")
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
                    applyProfileLocked(effects)
                    effects.setEnabled(true)
                    applySessionPreampLocked(effects)
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
            val normalized = band.sanitized()
            val updated = profile.bands.toMutableList()
            updated[index] = normalized
            profile = profile.copy(bands = updated)
            if (nativeHandle == 0L) ensureNativeEngineLocked()
            if (nativeHandle != 0L) {
                runCatching {
                    NativeDspBridge.nativeSetBand(
                        nativeHandle,
                        index,
                        normalized.filterType,
                        normalized.frequencyHz,
                        normalized.gainDb,
                        normalized.qFactor,
                        normalized.enabled
                    )
                    NativeDspBridge.nativeSetPreampGain(nativeHandle, effectivePreampDbLocked())
                }.onFailure { Log.w(TAG, "Native band update failed", it) }
            }
            applyBandToAllSessionsLocked()
        }
        publishSnapshot()
    }

    fun setTone(bassDb: Float, trebleDb: Float) {
        if (!bassDb.isFinite() || !trebleDb.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(
                bassGainDb = bassDb.coerceIn(-12f, 12f),
                trebleGainDb = trebleDb.coerceIn(-12f, 12f)
            )
            if (nativeHandle == 0L) ensureNativeEngineLocked()
            if (nativeHandle != 0L) {
                runCatching {
                    NativeDspBridge.nativeSetTone(nativeHandle, profile.bassGainDb, profile.trebleGainDb)
                    NativeDspBridge.nativeSetPreampGain(nativeHandle, effectivePreampDbLocked())
                }.onFailure { Log.w(TAG, "Native tone update failed", it) }
            }
            sessions.values.forEach(::applyProfileLocked)
        }
        publishSnapshot()
    }

    fun setPreampGain(gainDb: Float) {
        if (!gainDb.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(preampGainDb = gainDb.coerceIn(-24f, 12f))
            if (nativeHandle == 0L) ensureNativeEngineLocked()
            if (nativeHandle != 0L) {
                runCatching { NativeDspBridge.nativeSetPreampGain(nativeHandle, effectivePreampDbLocked()) }
                    .onFailure { Log.w(TAG, "Native preamp update failed", it) }
            }
            applyInputGainToAllSessionsLocked()
        }
        publishSnapshot()
    }

    fun setDvcCompensationDb(compensationDb: Float) {
        if (!compensationDb.isFinite()) return
        synchronized(lock) {
            val normalized = compensationDb.coerceIn(-24f, 0f)
            if (abs(dvcCompensationDb - normalized) < 0.001f) return@synchronized
            dvcCompensationDb = normalized
            if (nativeHandle == 0L) ensureNativeEngineLocked()
            if (nativeHandle != 0L) {
                runCatching { NativeDspBridge.nativeSetPreampGain(nativeHandle, effectivePreampDbLocked()) }
                    .onFailure { Log.w(TAG, "Native DVC update failed", it) }
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
                limiterThresholdDb = thresholdDb.coerceIn(-12f, 0f),
                limiterReleaseMs = releaseMs.coerceIn(10f, 300f)
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
                }.onFailure { Log.w(TAG, "Native limiter update failed", it) }
            }
            sessions.values.forEach { effects ->
                val dynamics = effects.dynamics
                if (dynamics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    runCatching { applyLimiterStagesLocked(dynamics) }
                        .onFailure { Log.w(TAG, "Session limiter update failed", it) }
                } else {
                    applyFallbackLocked(effects)
                }
                applySessionPreampLocked(effects)
            }
        }
        publishSnapshot()
    }

    fun setStereoWidthPercent(widthPercent: Float) {
        if (!widthPercent.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(stereoWidthPercent = widthPercent.coerceIn(0f, 200f))
            syncNativeLocked()
        }
        publishSnapshot()
    }

    fun setStereoBalance(balance: Float) {
        if (!balance.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(stereoBalance = balance.coerceIn(-1f, 1f))
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
            nativeHandle = runCatching { NativeDspBridge.nativeCreate(SAMPLE_RATE_HZ) }.getOrDefault(0L)
        }
        if (nativeHandle != 0L) syncNativeLocked()
    }

    private fun estimatedPeakBoostDbLocked(): Float {
        var maximum = 0f
        for (index in 0..96) {
            val frequency = (20.0 * 1000.0.pow(index / 96.0)).toFloat()
            val response = smoothBandGainAtFrequency(profile.bands, frequency) +
                bassToneGainAtFrequency(frequency) +
                trebleToneGainAtFrequency(frequency) +
                profile.preampGainDb
            maximum = maxOf(maximum, response)
        }
        return maximum.coerceIn(0f, 24f)
    }

    private fun gaussianOctaveWeight(frequencyHz: Float, centerHz: Float, sigmaOctaves: Float): Float {
        val distance = ln((frequencyHz / centerHz).coerceAtLeast(0.0001f)) / LN_2
        return exp(-0.5f * (distance / sigmaOctaves).pow(2))
    }

    /** Overlapping bell response. Q=1.41 gives useful adjacent-band overlap. */
    private fun smoothBandGainAtFrequency(
        bands: List<EqBandParameter>,
        frequencyHz: Float
    ): Float {
        var response = 0f
        bands.forEach { band ->
            if (!band.enabled) return@forEach
            val sigma = (0.55f / band.qFactor.coerceIn(0.1f, 10f)).coerceIn(0.18f, 0.55f)
            response += band.gainDb * gaussianOctaveWeight(frequencyHz, band.frequencyHz, sigma)
        }
        return response.coerceIn(-24f, 24f)
    }

    private fun bassToneGainAtFrequency(frequencyHz: Float): Float {
        val anchors = listOf(
            25f to 1.00f,
            31f to 1.00f,
            62f to 0.90f,
            90f to 0.65f,
            125f to 0.35f,
            220f to 0.00f
        )
        val weight = interpolateLogCurve(frequencyHz, anchors)
        return (profile.bassGainDb / 12f * 15f * weight).coerceIn(-15f, 15f)
    }

    private fun trebleToneGainAtFrequency(frequencyHz: Float): Float {
        val anchors = listOf(
            4_000f to 0.10f,
            6_000f to 0.40f,
            8_000f to 0.75f,
            12_000f to 1.00f,
            16_000f to 1.00f,
            20_000f to 0.75f
        )
        val weight = interpolateLogCurve(frequencyHz, anchors)
        return (profile.trebleGainDb / 12f * 12f * weight).coerceIn(-12f, 12f)
    }

    private fun interpolateLogCurve(frequencyHz: Float, anchors: List<Pair<Float, Float>>): Float {
        if (frequencyHz <= anchors.first().first) return anchors.first().second
        if (frequencyHz >= anchors.last().first) return anchors.last().second
        for (index in 0 until anchors.lastIndex) {
            val (leftHz, leftValue) = anchors[index]
            val (rightHz, rightValue) = anchors[index + 1]
            if (frequencyHz in leftHz..rightHz) {
                val progress = (
                    ln(frequencyHz / leftHz) / ln(rightHz / leftHz)
                    ).coerceIn(0f, 1f)
                return leftValue + (rightValue - leftValue) * progress
            }
        }
        return 0f
    }

    /** User preamp is preserved; there is no automatic -15 dB headroom cut. */
    private fun effectivePreampDbLocked(): Float =
        (profile.preampGainDb + dvcCompensationDb).coerceIn(-24f, 12f)

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
        }.onFailure { Log.w(TAG, "Native DSP synchronization failed", it) }
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
            attachAuxiliaryEffectsLocked(effects)
            applyDynamicsLocked(effects, dynamics)
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "DynamicsProcessing attach failed for session ${effects.sessionId}", error)
            runCatching { effects.dynamics?.release() }
            effects.dynamics = null
            false
        } catch (error: LinkageError) {
            Log.w(TAG, "DynamicsProcessing unavailable on this device", error)
            effects.dynamics = null
            false
        }
    }

    private fun attachAuxiliaryEffectsLocked(effects: SessionEffects) {
        if (effects.bassBoost == null) {
            runCatching {
                BassBoost(0, effects.sessionId).also { effects.bassBoost = it }
            }.onFailure { Log.w(TAG, "Hardware BassBoost unavailable for session ${effects.sessionId}", it) }
        }
        if (effects.loudnessEnhancer == null) {
            runCatching {
                LoudnessEnhancer(effects.sessionId).also { effects.loudnessEnhancer = it }
            }.onFailure { Log.w(TAG, "LoudnessEnhancer unavailable for session ${effects.sessionId}", it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyDynamicsLocked(effects: SessionEffects, effect: DynamicsProcessing) {
        applySessionPreampLocked(effects)
        val orderedBands = profile.bands.sortedBy { it.frequencyHz }
        for (index in orderedBands.indices) {
            val band = orderedBands[index]
            val previous = orderedBands.getOrNull(index - 1)?.frequencyHz ?: 20f
            val next = orderedBands.getOrNull(index + 1)?.frequencyHz ?: 20_000f
            val cutoff = sqrt(
                band.frequencyHz.coerceAtLeast(previous + 1f) *
                    next.coerceAtLeast(band.frequencyHz + 1f)
            ).coerceIn(20f, 20_000f)
            val preEqGain = smoothBandGainAtFrequency(orderedBands, cutoff)
            val postEqGain = bassToneGainAtFrequency(cutoff) + trebleToneGainAtFrequency(cutoff)
            effect.setPreEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(profile.enabled, cutoff, preEqGain)
            )
            effect.setPostEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(profile.enabled, cutoff, postEqGain)
            )
        }

        val mbcInUse = profile.enabled && profile.limiterEnabled
        val mbc = DynamicsProcessing.Mbc(true, mbcInUse, MBC_BAND_COUNT)
        val cutoffs = floatArrayOf(120f, 4_000f, 20_000f)
        cutoffs.forEachIndexed { index, cutoff ->
            val ratio = when (index) {
                0 -> 2.0f
                1 -> 2.5f
                else -> 3.0f
            }
            val threshold = if (index == 0) -10f else -14f
            mbc.setBand(
                index,
                DynamicsProcessing.MbcBand(
                    mbcInUse, cutoff, 8f, 60f, ratio, threshold,
                    4f, -80f, 1f, 0f, 0f
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
                1f,
                50f,
                10f,
                -0.8f,
                2.5f
            )
        )
    }

    private fun applySessionPreampLocked(effects: SessionEffects) {
        val requestedPreamp = profile.preampGainDb
        val enhancer = effects.loudnessEnhancer
        val usesMakeup = enhancer != null && requestedPreamp > 0f
        val sessionInputDb = (if (usesMakeup) 0f else requestedPreamp) + dvcCompensationDb
        runCatching {
            effects.dynamics?.setInputGainAllChannelsTo(sessionInputDb)
        }.onFailure { Log.w(TAG, "Session input gain update failed", it) }
        runCatching {
            enhancer?.setTargetGain(if (requestedPreamp > 0f) (requestedPreamp * 100f).toInt() else 0)
            enhancer?.enabled = profile.enabled && requestedPreamp > 0f
        }.onFailure { Log.w(TAG, "Session makeup gain update failed", it) }
    }

    private fun applyInputGainToAllSessionsLocked() {
        sessions.values.forEach { effects ->
            if (effects.dynamics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                applySessionPreampLocked(effects)
            } else {
                applyFallbackLocked(effects)
            }
        }
    }

    private fun attachFallbackEffectsLocked(effects: SessionEffects) {
        attachAuxiliaryEffectsLocked(effects)
        if (effects.equalizer == null) {
            runCatching {
                Equalizer(0, effects.sessionId).also { effects.equalizer = it }
            }.onFailure { Log.w(TAG, "Equalizer fallback unavailable", it) }
        }
        applyFallbackLocked(effects)
    }

    private fun applyFallbackLocked(effects: SessionEffects) {
        effects.equalizer?.let { equalizer ->
            runCatching {
                val count = equalizer.numberOfBands.toInt()
                val range = equalizer.bandLevelRange
                for (index in 0 until count) {
                    val centerHz = (equalizer.getCenterFreq(index.toShort()) / 1000f).coerceAtLeast(1f)
                    val combinedGain = smoothBandGainAtFrequency(profile.bands, centerHz) +
                        bassToneGainAtFrequency(centerHz) + trebleToneGainAtFrequency(centerHz)
                    val millibels = (combinedGain * 100f).toInt()
                        .coerceIn(range[0].toInt(), range[1].toInt())
                    equalizer.setBandLevel(index.toShort(), millibels.toShort())
                }
                equalizer.enabled = profile.enabled
            }.onFailure { Log.w(TAG, "Equalizer fallback update failed", it) }
        }
        effects.bassBoost?.let { boost ->
            runCatching {
                val level = (profile.bassGainDb.coerceIn(0f, 12f) / 12f)
                boost.setStrength((level * 1000f).toInt().coerceIn(0, 1000).toShort())
                boost.enabled = profile.enabled && level > 0f
            }.onFailure { Log.w(TAG, "Hardware BassBoost update failed", it) }
        }
        applySessionPreampLocked(effects)
    }

    private fun applyProfileLocked(effects: SessionEffects) {
        val dynamics = effects.dynamics
        if (dynamics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                applyDynamicsLocked(effects, dynamics)
                effects.bassBoost?.let { boost ->
                    val level = profile.bassGainDb.coerceIn(0f, 12f) / 12f
                    boost.setStrength((level * 1000f).toInt().coerceIn(0, 1000).toShort())
                    boost.enabled = profile.enabled && level > 0f
                }
            }.onFailure { Log.w(TAG, "Session DSP profile update failed", it) }
        } else {
            applyFallbackLocked(effects)
        }
        effects.loudnessEnhancer?.let { enhancer ->
            runCatching {
                enhancer.enabled = profile.enabled && profile.preampGainDb > 0f
            }
        }
    }

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
                    dynamics.setInputGainAllChannelsTo(
                        if (effects.loudnessEnhancer != null && profile.preampGainDb > 0f) dvcCompensationDb
                        else effectivePreampDbLocked()
                    )
                    ordered.forEachIndexed { index, band ->
                        val previous = ordered.getOrNull(index - 1)?.frequencyHz ?: 20f
                        val next = ordered.getOrNull(index + 1)?.frequencyHz ?: 20_000f
                        val cutoff = sqrt(
                            band.frequencyHz.coerceAtLeast(previous + 1f) *
                                next.coerceAtLeast(band.frequencyHz + 1f)
                        ).coerceIn(20f, 20_000f)
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
                }.onFailure { Log.w(TAG, "Live 32-band curve update failed", it) }
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
                applySessionPreampLocked(effects)
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
    val gainDb: Float = 0f,
    val qFactor: Float = 1f,
    val filterType: Int = NativeDspBridge.FILTER_PEAKING,
    val enabled: Boolean = true
) {
    fun sanitized() = copy(
        frequencyHz = frequencyHz.takeIf(Float::isFinite)?.coerceIn(20f, 20_000f) ?: 1_000f,
        gainDb = gainDb.takeIf(Float::isFinite)?.coerceIn(-24f, 24f) ?: 0f,
        qFactor = qFactor.takeIf(Float::isFinite)?.coerceIn(0.1f, 10f) ?: 1f,
        filterType = filterType.coerceIn(0, 2)
    )
}

private fun defaultEqBands(): List<EqBandParameter> =
    List(NativeDspBridge.MAX_PARAMETRIC_BANDS) { index ->
        val fraction = index.toDouble() / (NativeDspBridge.MAX_PARAMETRIC_BANDS - 1).toDouble()
        EqBandParameter(frequencyHz = (20.0 * 1000.0.pow(fraction)).toFloat())
    }

data class EqAudioProfile(
    val enabled: Boolean = true,
    val bands: List<EqBandParameter> = defaultEqBands(),
    val preampGainDb: Float = 0f,
    val bassGainDb: Float = 0f,
    val trebleGainDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    val limiterThresholdDb: Float = -0.8f,
    val limiterKneeDb: Float = 6f,
    val limiterLookAheadMs: Float = 1f,
    val limiterReleaseMs: Float = 50f,
    val stereoWidthPercent: Float = 100f,
    val stereoBalance: Float = 0f
) {
    fun sanitized(): EqAudioProfile {
        val cleanBands = bands.take(NativeDspBridge.MAX_PARAMETRIC_BANDS).map(EqBandParameter::sanitized)
        val paddedBands = cleanBands + defaultEqBands().drop(cleanBands.size)
        return copy(
            bands = paddedBands,
            preampGainDb = preampGainDb.takeIf(Float::isFinite)?.coerceIn(-24f, 12f) ?: 0f,
            bassGainDb = bassGainDb.takeIf(Float::isFinite)?.coerceIn(-12f, 12f) ?: 0f,
            trebleGainDb = trebleGainDb.takeIf(Float::isFinite)?.coerceIn(-12f, 12f) ?: 0f,
            limiterThresholdDb = limiterThresholdDb.takeIf(Float::isFinite)?.coerceIn(-12f, 0f) ?: -0.8f,
            limiterKneeDb = limiterKneeDb.takeIf(Float::isFinite)?.coerceIn(0f, 12f) ?: 6f,
            limiterLookAheadMs = limiterLookAheadMs.takeIf(Float::isFinite)?.coerceIn(0f, 20f) ?: 1f,
            limiterReleaseMs = limiterReleaseMs.takeIf(Float::isFinite)?.coerceIn(10f, 300f) ?: 50f,
            stereoWidthPercent = stereoWidthPercent.takeIf(Float::isFinite)?.coerceIn(0f, 200f) ?: 100f,
            stereoBalance = stereoBalance.takeIf(Float::isFinite)?.coerceIn(-1f, 1f) ?: 0f
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
