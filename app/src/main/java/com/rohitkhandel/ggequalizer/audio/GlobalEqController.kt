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
 * Owns per-session Android effects and the GG native parameter engine.
 * External players are processed only where Android exposes a usable session
 * ID and the device audio-effect framework permits attachment.
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
    private var dvcCompensationDb = 0f

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
            sessions[sessionId]?.let { current ->
                if (current.packageName == packageName) return@synchronized current.attachment
                current.release()
                sessions.remove(sessionId)
            }

            ensureNativeEngineLocked()
            val effects = SessionEffects(sessionId, packageName)
            if (profile.enabled) {
                if (!attachDynamicsLocked(effects)) attachFallbackEffectsLocked(effects)
                applyProfileLocked(effects)
            }
            effects.attachment = when {
                effects.dynamics != null && effects.bassBoost != null -> "dynamics + hardware bass"
                effects.dynamics != null -> "dynamics-processing"
                effects.equalizer != null || effects.bassBoost != null ||
                    effects.loudnessEnhancer != null -> "legacy session effects"
                else -> "session observed; no compatible effect"
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
            val clean = band.sanitized()
            val updated = profile.bands.toMutableList()
            updated[index] = clean
            profile = profile.copy(bands = updated)
            if (nativeHandle == 0L) ensureNativeEngineLocked()
            if (nativeHandle != 0L) {
                runCatching {
                    NativeDspBridge.nativeSetBand(
                        nativeHandle, index, clean.filterType,
                        clean.frequencyHz, clean.gainDb, clean.qFactor, clean.enabled
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
                bassGainDb = bassDb.coerceIn(0f, 12f),
                trebleGainDb = trebleDb.coerceIn(0f, 12f)
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

    fun setToneParameters(
        bassFrequencyHz: Float,
        bassQFactor: Float,
        trebleFrequencyHz: Float,
        trebleQFactor: Float
    ) {
        if (!bassFrequencyHz.isFinite() || !bassQFactor.isFinite() ||
            !trebleFrequencyHz.isFinite() || !trebleQFactor.isFinite()
        ) return
        synchronized(lock) {
            profile = profile.copy(
                bassFrequencyHz = bassFrequencyHz.coerceIn(20f, 250f),
                bassQFactor = bassQFactor.coerceIn(0.5f, 2f),
                trebleFrequencyHz = trebleFrequencyHz.coerceIn(4_000f, 16_000f),
                trebleQFactor = trebleQFactor.coerceIn(0.5f, 2f)
            )
            sessions.values.forEach(::applyProfileLocked)
        }
        publishSnapshot()
    }

    fun setDspOptions(
        smoothGains: Boolean,
        bandsOverlapEnabled: Boolean,
        bandsOverlapPercent: Float,
        avoidOverloading: Boolean
    ) {
        if (!bandsOverlapPercent.isFinite()) return
        synchronized(lock) {
            profile = profile.copy(
                smoothGains = smoothGains,
                bandsOverlapEnabled = bandsOverlapEnabled,
                bandsOverlapPercent = bandsOverlapPercent.coerceIn(0f, 100f),
                avoidOverloading = avoidOverloading
            )
            syncNativeLocked()
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
            syncNativeLocked()
            sessions.values.forEach(::applyProfileLocked)
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

    /** Reserve only the part of peak EQ/tone response that exceeds +8 dB. */
    private fun automaticHeadroomDbLocked(): Float {
        if (!profile.avoidOverloading) return 0f
        return (estimatedPeakBoostDbLocked() - 8f).coerceIn(0f, 16f)
    }

    private fun estimatedPeakBoostDbLocked(): Float {
        var peak = 0f
        for (index in 0..96) {
            val frequency = (20.0 * 1000.0.pow(index / 96.0)).toFloat()
            val response = smoothBandGainAtFrequency(profile.bands, frequency) +
                bassToneGainAtFrequency(frequency) +
                trebleToneGainAtFrequency(frequency) +
                profile.preampGainDb
            peak = maxOf(peak, response)
        }
        return peak.coerceIn(0f, 36f)
    }

    private fun gaussianWeight(frequencyHz: Float, centerHz: Float, sigmaOctaves: Float): Float {
        val distance = ln((frequencyHz / centerHz).coerceAtLeast(0.0001f)) / LN_2
        return exp(-0.5f * (distance / sigmaOctaves.coerceAtLeast(0.05f)).pow(2))
    }

    /** Adjacent bands can contribute up to 35% of their level into each neighbour. */
    private fun smoothBandGainAtFrequency(
        bands: List<EqBandParameter>,
        frequencyHz: Float
    ): Float {
        val ordered = bands.sortedBy { it.frequencyHz }
        var response = 0f
        val sigmaBase = if (profile.smoothGains) 0.24f else 0.12f
        val overlap = if (profile.bandsOverlapEnabled) {
            (profile.bandsOverlapPercent / 100f) * 0.35f
        } else 0f

        ordered.forEachIndexed { index, band ->
            if (!band.enabled) return@forEachIndexed
            val sigma = (
                sigmaBase * (1f / band.qFactor.coerceIn(0.1f, 10f)).coerceIn(0.55f, 1.8f) +
                    overlap * 0.36f
                ).coerceIn(0.09f, 0.62f)
            var weight = gaussianWeight(frequencyHz, band.frequencyHz, sigma)
            if (overlap > 0f) {
                ordered.getOrNull(index - 1)?.let { previous ->
                    weight += overlap * gaussianWeight(frequencyHz, previous.frequencyHz, sigma * 1.1f)
                }
                ordered.getOrNull(index + 1)?.let { next ->
                    weight += overlap * gaussianWeight(frequencyHz, next.frequencyHz, sigma * 1.1f)
                }
            }
            response += band.gainDb * weight
        }
        return response.coerceIn(-30f, 30f)
    }

    /** Soft low-shelf response centred at the selected bass frequency. */
    private fun bassToneGainAtFrequency(frequencyHz: Float): Float {
        val octaveDistance = ln((frequencyHz / profile.bassFrequencyHz).coerceAtLeast(0.0001f)) / LN_2
        val transition = (0.62f / profile.bassQFactor).coerceIn(0.30f, 1.30f)
        val weight = 1f / (1f + exp(octaveDistance / transition))
        return (profile.bassGainDb / 12f * 16f * weight).coerceIn(0f, 16f)
    }

    /** Soft high-shelf response centred at the selected treble frequency. */
    private fun trebleToneGainAtFrequency(frequencyHz: Float): Float {
        val octaveDistance = ln((frequencyHz / profile.trebleFrequencyHz).coerceAtLeast(0.0001f)) / LN_2
        val transition = (0.62f / profile.trebleQFactor).coerceIn(0.30f, 1.30f)
        val weight = 1f / (1f + exp(-octaveDistance / transition))
        return (profile.trebleGainDb / 12f * 12f * weight).coerceIn(0f, 12f)
    }

    private fun effectivePreampDbLocked(): Float =
        (profile.preampGainDb + dvcCompensationDb - automaticHeadroomDbLocked()).coerceIn(-24f, 12f)

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
                profile.limiterEnabled && profile.avoidOverloading,
                if (profile.avoidOverloading) -0.5f else profile.limiterThresholdDb,
                profile.limiterKneeDb,
                profile.limiterLookAheadMs,
                if (profile.avoidOverloading) 40f else profile.limiterReleaseMs
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
            }.onFailure { Log.w(TAG, "Hardware BassBoost attach failed for ${effects.sessionId}", it) }
        }
        if (effects.loudnessEnhancer == null) {
            runCatching {
                LoudnessEnhancer(effects.sessionId).also { effects.loudnessEnhancer = it }
            }.onFailure { Log.w(TAG, "LoudnessEnhancer attach failed for ${effects.sessionId}", it) }
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
            val preGain = smoothBandGainAtFrequency(orderedBands, cutoff)
            val postGain = bassToneGainAtFrequency(cutoff) + trebleToneGainAtFrequency(cutoff)

            effect.setPreEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(profile.enabled, cutoff, preGain.coerceIn(-30f, 30f))
            )
            effect.setPostEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(profile.enabled, cutoff, postGain.coerceIn(-24f, 24f))
            )
        }

        val compressionOn = profile.enabled && profile.avoidOverloading
        val mbc = DynamicsProcessing.Mbc(true, compressionOn, MBC_BAND_COUNT)
        floatArrayOf(120f, 4_000f, 20_000f).forEachIndexed { index, cutoff ->
            val ratio = if (index == 0) 2.2f else 2.8f
            val threshold = if (index == 0) -11f else -14f
            mbc.setBand(
                index,
                DynamicsProcessing.MbcBand(
                    compressionOn, cutoff, 8f, 60f, ratio, threshold,
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
        val protected = profile.enabled && profile.limiterEnabled && profile.avoidOverloading
        effect.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true,
                protected,
                0,
                1f,
                40f,
                12f,
                -0.5f,
                2.5f
            )
        )
    }

    private fun applySessionPreampLocked(effects: SessionEffects) {
        val enhancer = effects.loudnessEnhancer
        val positivePreamp = profile.preampGainDb.coerceAtLeast(0f)
        val headroom = automaticHeadroomDbLocked()
        val inputGain = profile.preampGainDb + dvcCompensationDb - headroom -
            if (enhancer != null && positivePreamp > 0f) positivePreamp else 0f

        runCatching {
            effects.dynamics?.setInputGainAllChannelsTo(inputGain.coerceIn(-24f, 12f))
        }.onFailure { Log.w(TAG, "Session input gain update failed", it) }

        // Makeup is deliberately modest; the DynamicsProcessing limiter remains
        // responsible for peaks rather than an unlimited post-limiter volume lift.
        val makeupDb = if (enhancer != null && profile.avoidOverloading) {
            positivePreamp + minOf(headroom, 1.5f)
        } else if (enhancer != null) positivePreamp else 0f
        runCatching {
            enhancer?.setTargetGain((makeupDb.coerceIn(0f, 12f) * 100f).toInt())
            enhancer?.enabled = profile.enabled && makeupDb > 0f
        }.onFailure { Log.w(TAG, "LoudnessEnhancer makeup update failed", it) }
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

    private fun applyFallbackEffectsLocked(effects: SessionEffects) {
        attachAuxiliaryEffectsLocked(effects)
        if (effects.equalizer == null) {
            runCatching { Equalizer(0, effects.sessionId).also { effects.equalizer = it } }
                .onFailure { Log.w(TAG, "Equalizer fallback attach failed", it) }
        }
        applyFallbackLocked(effects)
    }

    private fun applyFallbackLocked(effects: SessionEffects) {
        effects.equalizer?.let { eq ->
            runCatching {
                val range = eq.bandLevelRange
                for (index in 0 until eq.numberOfBands.toInt()) {
                    val frequencyHz = (eq.getCenterFreq(index.toShort()) / 1000f).coerceAtLeast(1f)
                    val gain = smoothBandGainAtFrequency(profile.bands, frequencyHz) +
                        bassToneGainAtFrequency(frequencyHz) + trebleToneGainAtFrequency(frequencyHz)
                    val level = (gain * 100f).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(index.toShort(), level.toShort())
                }
                eq.enabled = profile.enabled
            }.onFailure { Log.w(TAG, "Equalizer fallback update failed", it) }
        }
        effects.bassBoost?.let { boost ->
            runCatching {
                val level = profile.bassGainDb.coerceIn(0f, 12f) / 12f
                boost.setStrength((level * 1000f).toInt().coerceIn(0, 1000).toShort())
                boost.enabled = profile.enabled && level > 0f
            }.onFailure { Log.w(TAG, "Hardware BassBoost update failed", it) }
        }
        applySessionPreampLocked(effects)
    }

    private fun applyProfileLocked(effects: SessionEffects) {
        if (effects.dynamics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                applyDynamicsLocked(effects, effects.dynamics!!)
                effects.bassBoost?.let { boost ->
                    val level = profile.bassGainDb.coerceIn(0f, 12f) / 12f
                    boost.setStrength((level * 1000f).toInt().coerceIn(0, 1000).toShort())
                    boost.enabled = profile.enabled && level > 0f
                }
            }.onFailure { Log.w(TAG, "Session DSP update failed", it) }
        } else {
            applyFallbackLocked(effects)
        }
        applySessionPreampLocked(effects)
    }

    /** Fast path for a single fader move. */
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
                    applySessionPreampLocked(effects)
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
                }.onFailure { Log.w(TAG, "Live band curve update failed", it) }
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
        sessions = sessions.values.map { AudioSessionStatus(it.sessionId, it.packageName, it.attachment) },
        preampGainDb = profile.preampGainDb,
        effectivePreampGainDb = effectivePreampDbLocked(),
        bassGainDb = profile.bassGainDb,
        trebleGainDb = profile.trebleGainDb,
        limiterEnabled = profile.limiterEnabled && profile.avoidOverloading
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
    val bassFrequencyHz: Float = 85f,
    val bassQFactor: Float = 0.8f,
    val trebleFrequencyHz: Float = 10_000f,
    val trebleQFactor: Float = 0.8f,
    val smoothGains: Boolean = true,
    val bandsOverlapEnabled: Boolean = true,
    val bandsOverlapPercent: Float = 35f,
    val avoidOverloading: Boolean = true,
    val limiterEnabled: Boolean = true,
    val limiterThresholdDb: Float = -0.5f,
    val limiterKneeDb: Float = 6f,
    val limiterLookAheadMs: Float = 1f,
    val limiterReleaseMs: Float = 40f,
    val stereoWidthPercent: Float = 100f,
    val stereoBalance: Float = 0f
) {
    fun sanitized(): EqAudioProfile {
        val cleanBands = bands.take(NativeDspBridge.MAX_PARAMETRIC_BANDS)
            .map(EqBandParameter::sanitized)
        return copy(
            bands = cleanBands + defaultEqBands().drop(cleanBands.size),
            preampGainDb = preampGainDb.takeIf(Float::isFinite)?.coerceIn(-24f, 12f) ?: 0f,
            bassGainDb = bassGainDb.takeIf(Float::isFinite)?.coerceIn(0f, 12f) ?: 0f,
            trebleGainDb = trebleGainDb.takeIf(Float::isFinite)?.coerceIn(0f, 12f) ?: 0f,
            bassFrequencyHz = bassFrequencyHz.takeIf(Float::isFinite)?.coerceIn(20f, 250f) ?: 85f,
            bassQFactor = bassQFactor.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 0.8f,
            trebleFrequencyHz = trebleFrequencyHz.takeIf(Float::isFinite)?.coerceIn(4_000f, 16_000f) ?: 10_000f,
            trebleQFactor = trebleQFactor.takeIf(Float::isFinite)?.coerceIn(0.5f, 2f) ?: 0.8f,
            bandsOverlapPercent = bandsOverlapPercent.takeIf(Float::isFinite)?.coerceIn(0f, 100f) ?: 35f,
            limiterThresholdDb = limiterThresholdDb.takeIf(Float::isFinite)?.coerceIn(-12f, 0f) ?: -0.5f,
            limiterKneeDb = limiterKneeDb.takeIf(Float::isFinite)?.coerceIn(0f, 12f) ?: 6f,
            limiterLookAheadMs = limiterLookAheadMs.takeIf(Float::isFinite)?.coerceIn(0f, 20f) ?: 1f,
            limiterReleaseMs = limiterReleaseMs.takeIf(Float::isFinite)?.coerceIn(10f, 300f) ?: 40f,
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
