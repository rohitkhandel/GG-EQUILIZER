package com.rohitkhandel.ggequalizer.audio

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class TrackedPlayerSession(
    val packageName: String,
    val audioSessionId: Int,
    val detectionSource: String
)

data class AdvancedTrackerSnapshot(
    val activePackages: List<String>,
    val sessions: List<TrackedPlayerSession>,
    val activePlaybackConfigurationCount: Int,
    val hasUsageAccess: Boolean,
    val hasDumpPermission: Boolean,
    val diagnostic: String,
    val capturedAtEpochMs: Long
)

/**
 * Best-effort player discovery. It never attempts to elevate its own UID.
 *
 * AudioManager returns active playback configurations, but the public SDK
 * surface does not consistently expose a client package and session ID on all
 * Android releases. Reflection and dumpsys parsing are guarded fallbacks only.
 * UsageStats can identify a recently foregrounded package, not prove that it
 * is currently producing audio. A session is attached only when a non-negative
 * session ID and a package name are both available.
 *
 * android.permission.DUMP is signature/privileged on standard Android builds.
 * A normal APK cannot grant itself that permission using pm grant.
 */
class AdvancedDumpTracker(
    context: Context,
    private val onSnapshot: (AdvancedTrackerSnapshot) -> Unit
) {
    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    @Volatile
    private var stopped = false

    fun start() {
        if (pollingJob?.isActive == true) return
        stopped = false
        pollingJob = scope.launch {
            while (isActive && !stopped) {
                val snapshot = collectSnapshot()
                handler.post {
                    if (!stopped) {
                        runCatching { onSnapshot(snapshot) }
                            .onFailure { Log.w(TAG, "Tracker callback failed", it) }
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        stopped = true
        pollingJob?.cancel()
        pollingJob = null
        scope.cancel()
    }

    fun hasUsageAccess(): Boolean = checkUsageAccess(appContext)

    fun hasDumpPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.DUMP) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun collectSnapshot(): AdvancedTrackerSnapshot {
        val sessions = LinkedHashMap<Int, TrackedPlayerSession>()
        val packages = LinkedHashSet<String>()
        val diagnostics = ArrayList<String>()

        val playbackRead = readActivePlaybackConfigurations()
        playbackRead.sessions.forEach { session ->
            sessions[session.audioSessionId] = session
            packages.add(session.packageName)
        }
        diagnostics.add("Playback configs: ${playbackRead.configurationCount}")

        val hasUsage = checkUsageAccess(appContext)
        val hasDump = hasDumpPermission()

        if (hasUsage) {
            val foregroundPackages = queryRecentForegroundPackages()
            packages.addAll(foregroundPackages)
            if (foregroundPackages.isNotEmpty()) {
                diagnostics.add("UsageStats foreground hints: ${foregroundPackages.size}")
            }
        } else {
            diagnostics.add("Usage Access not granted")
        }

        if (hasDump) {
            val dumpSessions = readDumpSessions()
            dumpSessions.forEach { session ->
                sessions[session.audioSessionId] = session
                packages.add(session.packageName)
            }
            diagnostics.add("DUMP sessions: ${dumpSessions.size}")
        } else {
            diagnostics.add("DUMP unavailable (system/privileged permission)")
        }

        return AdvancedTrackerSnapshot(
            activePackages = packages.filter { it.isNotBlank() }.take(MAX_PACKAGES),
            sessions = sessions.values.toList(),
            activePlaybackConfigurationCount = playbackRead.configurationCount,
            hasUsageAccess = hasUsage,
            hasDumpPermission = hasDump,
            diagnostic = diagnostics.joinToString(" · "),
            capturedAtEpochMs = System.currentTimeMillis()
        )
    }

    private data class PlaybackReadResult(
        val sessions: List<TrackedPlayerSession>,
        val configurationCount: Int
    )

    private fun readActivePlaybackConfigurations(): PlaybackReadResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return PlaybackReadResult(emptyList(), 0)
        }

        val configurations = try {
            audioManager.activePlaybackConfigurations
        } catch (error: SecurityException) {
            Log.d(TAG, "Playback configuration query is restricted", error)
            return PlaybackReadResult(emptyList(), 0)
        } catch (error: RuntimeException) {
            Log.d(TAG, "Playback configuration query failed", error)
            return PlaybackReadResult(emptyList(), 0)
        }

        val result = configurations.mapNotNull(::sessionFromPlaybackConfiguration)
            .distinctBy { it.audioSessionId }
        return PlaybackReadResult(result, configurations.size)
    }

    private fun sessionFromPlaybackConfiguration(
        configuration: AudioPlaybackConfiguration
    ): TrackedPlayerSession? {
        val clientUid = reflectedInt(configuration, "getClientUid", "getClientUidInternal")
            ?: extractInt(configuration.toString(), UID_REGEX)
            ?: return null

        val sessionId = reflectedInt(
            configuration,
            "getAudioSessionId",
            "getSessionId",
            "getAudioSessionIdInternal"
        ) ?: extractInt(configuration.toString(), SESSION_REGEX)
            ?: return null

        if (sessionId < 0) return null
        val packageName = packageForUid(clientUid) ?: return null
        return TrackedPlayerSession(packageName, sessionId, "AudioManager")
    }

    private fun reflectedInt(target: Any, vararg methodNames: String): Int? {
        for (name in methodNames) {
            val value = runCatching {
                val method = target.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty()
                } ?: target.javaClass.declaredMethods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty()
                } ?: return@runCatching null
                method.isAccessible = true
                (method.invoke(target) as? Number)?.toInt()
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    private fun queryRecentForegroundPackages(): List<String> {
        val usageManager =
            appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyList()
        val now = System.currentTimeMillis()
        val events = runCatching {
            usageManager.queryEvents(now - USAGE_LOOKBACK_MS, now)
        }.getOrNull() ?: return emptyList()

        val packageOrder = LinkedHashSet<String>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            if (isForeground && event.packageName.isNotBlank()) {
                packageOrder.remove(event.packageName)
                packageOrder.add(event.packageName)
            }
        }
        return packageOrder.toList().asReversed().take(MAX_PACKAGES)
    }

    private fun readDumpSessions(): List<TrackedPlayerSession> {
        if (!hasDumpPermission()) return emptyList()

        val parsed = LinkedHashMap<Int, TrackedPlayerSession>()
        DUMPSYS_TARGETS.forEach { target ->
            val output = runCatching { executeDumpsys(target) }.getOrElse { error ->
                Log.d(TAG, "dumpsys $target was not readable", error)
                return@forEach
            }
            parseDumpOutput(output, target).forEach { session ->
                parsed[session.audioSessionId] = session
            }
        }
        return parsed.values.toList()
    }

    private fun executeDumpsys(serviceName: String): String {
        val process = ProcessBuilder("dumpsys", serviceName)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().useLines { lines ->
            lines.take(MAX_DUMP_LINES).joinToString("
")
        }
        if (!process.waitFor(DUMPSYS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return ""
        }
        if (process.exitValue() != 0) return ""
        return output
    }

    private fun parseDumpOutput(output: String, source: String): List<TrackedPlayerSession> {
        if (output.isBlank()) return emptyList()
        val sessions = LinkedHashMap<Int, TrackedPlayerSession>()
        var currentUid: Int? = null
        var currentPackage: String? = null

        output.lineSequence().forEach { line ->
            extractInt(line, UID_REGEX)?.let { currentUid = it }
            PACKAGE_REGEX.find(line)?.groupValues?.getOrNull(1)?.let { packageName ->
                currentPackage = packageName
            }

            val sessionId = extractInt(line, SESSION_REGEX) ?: return@forEach
            val packageName = currentPackage
                ?: currentUid?.let(::packageForUid)
                ?: return@forEach
            sessions[sessionId] = TrackedPlayerSession(packageName, sessionId, "dumpsys:$source")
        }
        return sessions.values.toList()
    }

    private fun packageForUid(uid: Int): String? {
        if (uid < 0) return null
        return runCatching {
            appContext.packageManager.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull()
    }

    private fun extractInt(value: String, regex: Regex): Int? =
        regex.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

    companion object {
        private const val TAG = "AdvancedDumpTracker"
        private const val POLL_INTERVAL_MS = 5_000L
        private const val USAGE_LOOKBACK_MS = 10 * 60 * 1000L
        private const val MAX_PACKAGES = 16
        private const val MAX_DUMP_LINES = 5_000
        private const val DUMPSYS_TIMEOUT_SECONDS = 2L
        private val DUMPSYS_TARGETS = listOf("media.audio_flinger", "audio")

        private val UID_REGEX = Regex("""(?i)(?:m?client)?uid\s*[=: ]\s*(\d+)""")
        private val SESSION_REGEX = Regex(
            """(?i)(?:m?audio)?session(?:id)?\s*[=: ]\s*(\d+)"""
        )
        private val PACKAGE_REGEX = Regex(
            """(?i)(?:package(?:name)?)\s*[=: ]\s*([a-zA-Z0-9_.]+)"""
        )

        fun checkUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            return runCatching {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                ) == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
        }
    }
}
