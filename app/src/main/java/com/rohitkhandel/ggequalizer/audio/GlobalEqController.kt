package com.rohitkhandel.ggequalizer.audio

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
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
    private const val KNOWN_PLAYER_PREFS = "gg_equalizer_known_players"
    private const val DISABLED_PLAYERS_KEY = "disabled_packages"

    private data class SessionEffects(
        val sessionId: Int,
        val packageName: String,
        var dynamics: DynamicsProcessing? = null,
        var equalizer: Equalizer? = null,
        var attachment: String = "unavailable",
        val appliedZoneGainsDb: FloatArray = FloatArray(BAND_COUNT) { Float.NaN }
    ) {
        fun release() {
            runCatching { dynamics?.release() }
            runCatching { equalizer?.release() }
            dynamics = null
            equalizer = null
        }

        fun setEnabled(enabled: Boolean) {
            runCatching { dynamics?.setEnabled(enabled) }
            runCatching { equalizer?.setEnabled(enabled) }
        }
    }

    private val lock = Any()
    private val sessions = LinkedHashMap<Int, SessionEffects>()
    private val smoothingHandler = Handler(Looper.getMainLooper())
    private var smoothingUpdateScheduled = false
    private val listeners = CopyOnWriteArraySet<(GlobalEqSnapshot) -> Unit>()

    private var applicationContext: Context? = null
    private var nativeHandle = 0L
    private var activePlayerPackages: List<String> = emptyList()
    private var profile = EqAudioProfile.defaults()
    private var dvcCompensationDb = 0f
    private val disabledKnownPlayers = linkedSetOf<String>()

    fun initialize(context: Context) {
        synchronized(lock) {
            applicationContext = context.applicationContext
            val savedDisabled = context.applicationContext
                .getSharedPreferences(KNOWN_PLAYER_PREFS, Context.MODE_PRIVATE)
                .getStringSet(DISABLED_PLAYERS_KEY, emptySet())
                .orEmpty()
            disabledKnownPlayers.clear()
            disabledKnownPlayers.addAll(savedDisabled)
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

    fun isKnownPlayerEnabled(packageName: String): Boolean = synchronized(lock) {
        packageName !in disabledKnownPlayers
    }

    fun setKnownPlayerEnabled(packageName: String, enabled: Boolean) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            if (enabled) disabledKnownPlayers.remove(packageName)
            else disabledKnownPlayers.add(packageName)
            applicationContext?.getSharedPreferences(KNOWN_PLAYER_PREFS, Context.MODE_PRIVATE)
                ?.edit()
                ?.putStringSet(DISABLED_PLAYERS_KEY, disabledKnownPlayers.toSet())
                ?.apply()

            if (!enabled) {
                val matching = sessions.filterValues { it.packageName == packageName }.keys.toList()
                matching.forEach { sessionId -> sessions.remove(sessionId)?.release() }
            }
        }
        publishSnapshot()
    }

    fun attachSession(sessionId: Int, packageName: String): String {
        if (sessionId < 0 || packageName.isBlank()) return "invalid"
        val attachment = synchronized(lock) {
            if (packageName in disabledKnownPlayers) {
                sessions.remove(sessionId)?.release()
                return@synchronized "disabled in Known Players"
            }
            sessions[sessionId]?.let { current ->
                if (current.packageName == packageName) return@synchronized current.attachment
                current.release()
                sessions.remove(sessionId)
            }

            ensureNativeEngineLocked()
            val effects = SessionEffects(sessionId, packageName)
            if (profile.enabled) {
                if (!attachDynamicsLocked(effects)) applyFallbackEffectsLocked(effects)
                applyProfileLocked(effects)
            }
            effects.attachment = when {
                effects.dynamics != null -> "dynamics · 32-zone EQ"
                effects.equalizer != null -> "legacy graphic EQ"
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
                    if (effects.dynamics == null && effects.equalizer == null) {
                        if (!attachDynamicsLocked(effects)) applyFallbackEffectsLocked(effects)
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
            applyInputGainToAllSessionsLocked()
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

    /** Reserve the complete positive EQ peak plus a small safety margin. */
    private fun automaticHeadroomDbLocked(): Float =
        EqResponseMath.automaticHeadroomDb(profile)

    private fun estimatedPeakBoostDbLocked(): Float {
        var peak = 0f
        for (index in 0..96) {
            val frequency = (20.0 * 1000.0.pow(index / 96.0)).toFloat()
            val response = EqResponseMath.profileGainDb(profile, frequency, includePreamp = true)
            peak = maxOf(peak, response)
        }
        return peak.coerceIn(0f, 36f)
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
            // A single EQ response mapper avoids duplicate BassBoost/MBC colouring.
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNEL_COUNT,
                true,
                BAND_COUNT,
                false,
                0,
                false,
                0,
                true
            ).build()
            val dynamics = DynamicsProcessing(0, effects.sessionId, config)
            effects.dynamics = dynamics
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

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyDynamicsLocked(effects: SessionEffects, effect: DynamicsProcessing) {
        applySessionPreampLocked(effects)

        // Android EqBand has cutoff + gain but no Q. Sample the complete RBJ
        // response into 32 logarithmic zones rather than guessing a Gaussian shape.
        if (applyDynamicsZonesLocked(effects, effect)) scheduleSmoothingUpdateLocked()
        applyLimiterStagesLocked(effect)
        effect.enabled = profile.enabled
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyDynamicsZonesLocked(
        effects: SessionEffects,
        effect: DynamicsProcessing
    ): Boolean {
        var needsAnotherStep = false
        for (index in 0 until BAND_COUNT) {
            // Allocate more of Android's fixed 32 EQ zones below 500 Hz,
            // where bass shelves and kick fundamentals need finer resolution.
            val zonePosition = (index + 0.5) / BAND_COUNT.toDouble()
            val warpedPosition = zonePosition.pow(1.28)
            val centerFrequencyHz = (20.0 * 1_000.0.pow(warpedPosition))
                .toFloat()
                .coerceIn(20f, 20_000f)
            val targetGainDb = EqResponseMath.profileGainDb(profile, centerFrequencyHz)
                .coerceIn(-24f, 24f)
            val previousGainDb = effects.appliedZoneGainsDb[index]
            val appliedGainDb = if (profile.smoothGains && previousGainDb.isFinite()) {
                val delta = targetGainDb - previousGainDb
                if (kotlin.math.abs(delta) > 0.04f) needsAnotherStep = true
                previousGainDb + delta * 0.32f
            } else {
                targetGainDb
            }
            effects.appliedZoneGainsDb[index] = appliedGainDb
            effect.setPreEqBandAllChannelsTo(
                index,
                DynamicsProcessing.EqBand(
                    profile.enabled,
                    centerFrequencyHz,
                    appliedGainDb
                )
            )
        }
        return needsAnotherStep
    }

    private fun scheduleSmoothingUpdateLocked() {
        if (smoothingUpdateScheduled) return
        smoothingUpdateScheduled = true
        smoothingHandler.postDelayed({
            synchronized(lock) {
                smoothingUpdateScheduled = false
                if (!profile.enabled || !profile.smoothGains) return@synchronized
                var needsAnotherStep = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    sessions.values.forEach { effects ->
                        val dynamics = effects.dynamics ?: return@forEach
                        if (runCatching {
                                applyDynamicsZonesLocked(effects, dynamics)
                            }.getOrDefault(false)
                        ) {
                            needsAnotherStep = true
                        }
                    }
                }
                if (needsAnotherStep) scheduleSmoothingUpdateLocked()
            }
        }, 16L)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun applyLimiterStagesLocked(effect: DynamicsProcessing) {
        val protected = profile.enabled && profile.limiterEnabled
        effect.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                true,
                protected,
                0,
                1f,
                profile.limiterReleaseMs.coerceIn(10f, 300f),
                20f,
                profile.limiterThresholdDb.coerceIn(-12f, 0f),
                0f
            )
        )
    }

    private fun applySessionPreampLocked(effects: SessionEffects) {
        val dynamics = effects.dynamics ?: return
        val baseGain = profile.preampGainDb + dvcCompensationDb -
            automaticHeadroomDbLocked()
        val leftAttenuation = if (profile.stereoBalance > 0f) {
            balanceAttenuationDb(profile.stereoBalance)
        } else {
            0f
        }
        val rightAttenuation = if (profile.stereoBalance < 0f) {
            balanceAttenuationDb(-profile.stereoBalance)
        } else {
            0f
        }
        runCatching {
            dynamics.setInputGainbyChannel(0, (baseGain + leftAttenuation).coerceIn(-24f, 12f))
            dynamics.setInputGainbyChannel(1, (baseGain + rightAttenuation).coerceIn(-24f, 12f))
        }.onFailure { Log.w(TAG, "Session input gain update failed", it) }
    }

    private fun balanceAttenuationDb(amount: Float): Float {
        // The public session input-gain range is kept within the same -24 dB
        // headroom envelope used by preamp compensation.
        val linearGain = (1f - amount.coerceIn(0f, 1f)).coerceAtLeast(0.06309573f)
        return (20.0 * kotlin.math.log10(linearGain.toDouble())).toFloat()
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
                    val gain = EqResponseMath.profileGainDb(profile, frequencyHz) + effectivePreampDbLocked()
                    val level = (gain * 100f).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(index.toShort(), level.toShort())
                }
                eq.enabled = profile.enabled
            }.onFailure { Log.w(TAG, "Equalizer fallback update failed", it) }
        }

    }

    private fun applyProfileLocked(effects: SessionEffects) {
        if (effects.dynamics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { applyDynamicsLocked(effects, effects.dynamics!!) }
                .onFailure { Log.w(TAG, "Session DSP update failed", it) }
        } else {
            applyFallbackLocked(effects)
        }
    }

    /** Rebuild the full session response from the complete parametric profile. */
    private fun applyBandToAllSessionsLocked() {
        sessions.values.forEach { effects ->
            if (!profile.enabled) {
                effects.setEnabled(false)
                return@forEach
            }
            val dynamics = effects.dynamics
            if (dynamics != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { applyDynamicsLocked(effects, dynamics) }
                    .onFailure { Log.w(TAG, "Live band curve update failed", it) }
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
        qFactor = qFactor.takeIf(Float::isFinite)?.coerceIn(0.1f, 12f) ?: 1f,
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
            bassGainDb = bassGainDb.takeIf(Float::isFinite)?.coerceIn(-12f, 12f) ?: 0f,
            trebleGainDb = trebleGainDb.takeIf(Float::isFinite)?.coerceIn(-12f, 12f) ?: 0f,
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
