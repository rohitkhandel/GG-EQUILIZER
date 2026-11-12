package com.rohitkhandel.ggequalizer.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.content.ComponentName
import android.media.audiofx.AudioEffect
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Foreground coordinator for session-attached effects, DVC headroom management,
 * best-effort playback discovery and media-player notifications.
 */
class AudioInterceptorService : Service() {
    private val audioSessionPackages = ConcurrentHashMap<Int, String>()
    private val trackerSessionPackages = ConcurrentHashMap<Int, String>()
    private val activeMediaPackages = LinkedHashSet<String>()

    private var mediaReceiverRegistered = false
    private var audioEffectReceiverRegistered = false
    private var playbackCallbackRegistered = false
    private var fallbackSessionPackage: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionExecutor = Executors.newSingleThreadExecutor()
    private var audioManager: AudioManager? = null
    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            super.onPlaybackConfigChanged(configs)
            // Re-query off the main thread when AudioFlinger reports a player change.
            advancedTracker?.refreshNow()
        }
    }
    private val audioEffectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent == null) return
            val mappedAction = when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> ACTION_AUDIO_SESSION_OPEN
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> ACTION_AUDIO_SESSION_CLOSE
                else -> return
            }
            val forwarded = Intent(mappedAction)
                .putExtra(EXTRA_AUDIO_SESSION_ID, intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1))
                .putExtra(EXTRA_CLIENT_PACKAGE, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty())
            sessionExecutor.execute {
                if (mappedAction == ACTION_AUDIO_SESSION_OPEN) {
                    registerAudioSession(forwarded)
                } else {
                    unregisterAudioSession(forwarded)
                }
                mainHandler.post { refreshForegroundNotification() }
            }
        }
    }
    private var dvcManager: DvcManager? = null
    private var advancedTracker: AdvancedDumpTracker? = null
    @Volatile
    private var trackerDiagnostic = "Player discovery has not started"
    private var controllerListener: ((GlobalEqSnapshot) -> Unit)? = null

    private val mediaSessionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_MEDIA_SESSIONS_CHANGED) return
            val packages =
                intent.getStringArrayListExtra(EXTRA_ACTIVE_MEDIA_PACKAGES).orEmpty()

            synchronized(activeMediaPackages) {
                activeMediaPackages.clear()
                activeMediaPackages.addAll(
                    packages.filter { it.isNotBlank() }.distinct().take(MAX_TRACKED_PACKAGES)
                )
            }
            sessionExecutor.execute {
                refreshDetectedPlayerPackages()
                mainHandler.post { refreshForegroundNotification() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerMediaSessionsReceiver()
        registerAudioEffectReceiver()

        GlobalEqController.initialize(applicationContext)
        val dvc = DvcManager(applicationContext)
        dvcManager = dvc
        dvc.start()
        dvc.setEnabled(DEFAULT_DVC_ENABLED)
        dvc.updateProfile(GlobalEqController.currentProfile())

        val listener: (GlobalEqSnapshot) -> Unit = {
            dvcManager?.updateProfile(GlobalEqController.currentProfile())
            refreshForegroundNotification()
        }
        controllerListener = listener
        GlobalEqController.addListener(listener)

        advancedTracker = AdvancedDumpTracker(applicationContext) { snapshot ->
            sessionExecutor.execute {
                trackerDiagnostic = snapshot.diagnostic
                val nextSessions = snapshot.sessions.associateBy { it.audioSessionId }

                trackerSessionPackages.keys.toList().forEach { sessionId ->
                    if (!nextSessions.containsKey(sessionId)) {
                        trackerSessionPackages.remove(sessionId)
                        if (!audioSessionPackages.containsKey(sessionId)) {
                            GlobalEqController.detachSession(sessionId)
                        }
                    }
                }

                nextSessions.values.forEach { session ->
                    val previous = trackerSessionPackages.put(
                        session.audioSessionId,
                        session.packageName
                    )
                    if (previous != session.packageName &&
                        audioSessionPackages[session.audioSessionId] != session.packageName
                    ) {
                        val attachment = GlobalEqController.attachSession(
                            session.audioSessionId,
                            session.packageName
                        )
                        Log.d(
                            TAG,
                            "Fallback session ${session.audioSessionId} from ${session.packageName}: $attachment"
                        )
                    }
                }

                refreshDetectedPlayerPackages(snapshot.activePackages)
                mainHandler.post { refreshForegroundNotification() }
            }
        }.also { tracker ->
            tracker.start()
            registerAudioPlaybackCallback()
            queryActiveMediaSessions()
            // Pull active playback configurations immediately; do not wait for the 5 s polling interval.
            tracker.refreshNow()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            refreshForegroundNotification()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_AUDIO_SESSION_OPEN -> sessionExecutor.execute { registerAudioSession(intent) }
            ACTION_AUDIO_SESSION_CLOSE -> sessionExecutor.execute { unregisterAudioSession(intent) }
            ACTION_SET_DVC_ENABLED -> sessionExecutor.execute {
                dvcManager?.setEnabled(intent.getBooleanExtra(EXTRA_DVC_ENABLED, false))
            }
            ACTION_SET_MASTER_ENABLED -> sessionExecutor.execute {
                GlobalEqController.setMasterEnabled(intent.getBooleanExtra(EXTRA_MASTER_ENABLED, true))
            }
            else -> Log.d(TAG, "Interceptor started without a session event")
        }
        refreshForegroundNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controllerListener?.let(GlobalEqController::removeListener)
        controllerListener = null
        advancedTracker?.stop()
        advancedTracker = null
        dvcManager?.stop()
        dvcManager = null

        if (mediaReceiverRegistered) {
            runCatching { unregisterReceiver(mediaSessionsReceiver) }
            mediaReceiverRegistered = false
        }
        if (audioEffectReceiverRegistered) {
            runCatching { applicationContext.unregisterReceiver(audioEffectReceiver) }
            audioEffectReceiverRegistered = false
        }
        if (playbackCallbackRegistered) {
            runCatching { audioManager?.unregisterAudioPlaybackCallback(audioPlaybackCallback) }
            playbackCallbackRegistered = false
        }
        audioManager = null
        sessionExecutor.shutdown()
        if (fallbackSessionPackage != null) {
            GlobalEqController.detachSession(0)
            fallbackSessionPackage = null
        }

        trackerSessionPackages.keys.forEach { sessionId ->
            if (!audioSessionPackages.containsKey(sessionId)) {
                GlobalEqController.detachSession(sessionId)
            }
        }
        trackerSessionPackages.clear()
        GlobalEqController.detachAllSessions()
        GlobalEqController.shutdown()
        super.onDestroy()
    }

    private fun registerAudioSession(intent: Intent) {
        val sessionId = intent.getIntExtra(EXTRA_AUDIO_SESSION_ID, -1)
        val clientPackage = intent.getStringExtra(EXTRA_CLIENT_PACKAGE).orEmpty()
        if (sessionId < 0 || clientPackage.isBlank()) {
            Log.w(TAG, "Ignoring audio session with invalid metadata")
            return
        }

        audioSessionPackages[sessionId] = clientPackage
        if (sessionId > 0 && fallbackSessionPackage == clientPackage) {
            GlobalEqController.detachSession(0)
            fallbackSessionPackage = null
        }
        val attachment = GlobalEqController.attachSession(sessionId, clientPackage)
        Log.i(TAG, "Session $sessionId from $clientPackage; attachment=$attachment")
        refreshDetectedPlayerPackages()
    }

    private fun unregisterAudioSession(intent: Intent) {
        val sessionId = intent.getIntExtra(EXTRA_AUDIO_SESSION_ID, -1)
        val clientPackage = intent.getStringExtra(EXTRA_CLIENT_PACKAGE).orEmpty()
        if (sessionId < 0) return

        val previousPackage = audioSessionPackages.remove(sessionId)
        if (!trackerSessionPackages.containsKey(sessionId)) {
            GlobalEqController.detachSession(sessionId)
        }
        Log.i(
            TAG,
            "Closed session $sessionId from " +
                clientPackage.ifBlank { previousPackage.orEmpty() }
        )
        refreshDetectedPlayerPackages()
        ensureSessionZeroFallback()
    }

    private fun refreshDetectedPlayerPackages(extraPackages: List<String> = emptyList()) {
        val mediaPackages = synchronized(activeMediaPackages) { activeMediaPackages.toList() }
        val allPackages = (
            mediaPackages +
                audioSessionPackages.values +
                trackerSessionPackages.values +
                extraPackages
            ).filter { it.isNotBlank() }.distinct().take(MAX_TRACKED_PACKAGES)
        GlobalEqController.updateActivePlayers(allPackages)
        ensureSessionZeroFallback()
    }

    /**
     * Some players expose an active MediaSession but never send an
     * AudioEffect session broadcast. Session 0 is a best-effort output-mix
     * fallback on devices that permit global audio effects. It is removed as
     * soon as a real session ID for that same player is observed.
     */
    private fun ensureSessionZeroFallback() {
        val activePackage = synchronized(activeMediaPackages) {
            activeMediaPackages.firstOrNull()
        }
        if (activePackage == null) {
            if (fallbackSessionPackage != null) {
                GlobalEqController.detachSession(0)
                fallbackSessionPackage = null
            }
            return
        }

        val hasRealSession = (audioSessionPackages.entries + trackerSessionPackages.entries)
            .any { (sessionId, packageName) -> sessionId > 0 && packageName == activePackage }
        if (hasRealSession) {
            if (fallbackSessionPackage == activePackage) {
                GlobalEqController.detachSession(0)
                fallbackSessionPackage = null
            }
            return
        }

        if (fallbackSessionPackage != activePackage) {
            if (fallbackSessionPackage != null) GlobalEqController.detachSession(0)
            fallbackSessionPackage = activePackage
            val result = GlobalEqController.attachSession(0, activePackage)
            Log.i(TAG, "Session-0 fallback for $activePackage; attachment=$result")
        }
    }

    private fun queryActiveMediaSessions() {
        sessionExecutor.execute {
            val manager = getSystemService(MediaSessionManager::class.java)
            if (manager == null) return@execute
            val component = ComponentName(this, MediaNotificationListener::class.java)
            val controllers = try {
                manager.getActiveSessions(component)
            } catch (error: SecurityException) {
                Log.d(TAG, "Notification Listener access is not enabled; session bootstrap skipped", error)
                return@execute
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not query already-active media sessions", error)
                return@execute
            }

            val playingPackages = controllers.mapNotNull { controller: MediaController ->
                val state = controller.playbackState?.state ?: return@mapNotNull null
                if (state in ACTIVE_PLAYBACK_STATES) {
                    controller.packageName.takeIf(String::isNotBlank)
                } else null
            }.distinct().take(MAX_TRACKED_PACKAGES)

            synchronized(activeMediaPackages) {
                activeMediaPackages.clear()
                activeMediaPackages.addAll(playingPackages)
            }
            refreshDetectedPlayerPackages()
            mainHandler.post { refreshForegroundNotification() }
        }
    }

    private fun registerAudioEffectReceiver() {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationContext.registerReceiver(audioEffectReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                applicationContext.registerReceiver(audioEffectReceiver, filter)
            }
            audioEffectReceiverRegistered = true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to register dynamic AudioEffect session receiver", error)
        }
    }

    private fun registerAudioPlaybackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        try {
            manager.registerAudioPlaybackCallback(audioPlaybackCallback, mainHandler)
            audioManager = manager
            playbackCallbackRegistered = true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Audio playback callback is unavailable", error)
        }
    }

    private fun registerMediaSessionsReceiver() {
        val filter = IntentFilter(ACTION_MEDIA_SESSIONS_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mediaSessionsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(mediaSessionsReceiver, filter)
            }
            mediaReceiverRegistered = true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to register the media-session update receiver", error)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Audio monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active players and audio sessions tracked by GG Equalizer"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun activePlayerSummary(): String {
        val packages = synchronized(activeMediaPackages) { activeMediaPackages.toList() }
        if (packages.isNotEmpty()) return packages.joinToString(", ")
        return (audioSessionPackages.values + trackerSessionPackages.values)
            .distinct()
            .firstOrNull()
            ?: "No active media player detected"
    }

    private fun buildNotification(): Notification {
        val uiPreferences = getSharedPreferences("gg_equalizer_ui_preferences", MODE_PRIVATE)
        val showPlayerName = uiPreferences.getBoolean("notification_show_player", true)
        val showProcessingStatus = uiPreferences.getBoolean("notification_show_processing", true)
        val detectedPlayer = activePlayerSummary()
        val playerSummary = if (showPlayerName) detectedPlayer else "Audio monitoring"
        val allSessions = audioSessionPackages.keys + trackerSessionPackages.keys
        val sessionCount = allSessions.distinct().size
        val dvc = dvcManager?.snapshot()
        val dvcText = when {
            !showProcessingStatus -> "GG Equalizer is monitoring audio sessions"
            dvc == null -> "DVC unavailable"
            dvc.enabled -> "DVC on · preamp compensation ${"%.1f".format(dvc.preampCompensationDb)} dB"
            else -> "DVC off"
        }
        val attachmentSummary = GlobalEqController.snapshot().sessions
            .groupingBy { it.attachment }
            .eachCount()
            .entries
            .joinToString { "${it.key}: ${it.value}" }
            .ifBlank { "No effect session attached" }
        val sessionText = if (sessionCount == 1) {
            "1 audio-effect session observed"
        } else {
            "$sessionCount audio-effect sessions observed"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("GG Equalizer · $playerSummary")
            .setContentText(dvcText)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(if (showProcessingStatus) "$playerSummary · $sessionText · $dvcText · $attachmentSummary · $trackerDiagnostic" else playerSummary)
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun refreshForegroundNotification() {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val ACTION_AUDIO_SESSION_OPEN =
            "com.rohitkhandel.ggequalizer.action.AUDIO_SESSION_OPEN"
        const val ACTION_AUDIO_SESSION_CLOSE =
            "com.rohitkhandel.ggequalizer.action.AUDIO_SESSION_CLOSE"
        const val ACTION_MEDIA_SESSIONS_CHANGED =
            "com.rohitkhandel.ggequalizer.action.MEDIA_SESSIONS_CHANGED"
        const val ACTION_SET_DVC_ENABLED =
            "com.rohitkhandel.ggequalizer.action.SET_DVC_ENABLED"
        const val ACTION_SET_MASTER_ENABLED =
            "com.rohitkhandel.ggequalizer.action.SET_MASTER_ENABLED"

        const val EXTRA_AUDIO_SESSION_ID = "extra_audio_session_id"
        const val EXTRA_CLIENT_PACKAGE = "extra_client_package"
        const val EXTRA_ACTIVE_MEDIA_PACKAGES = "extra_active_media_packages"
        const val EXTRA_DVC_ENABLED = "extra_dvc_enabled"
        const val EXTRA_MASTER_ENABLED = "extra_master_enabled"

        private const val TAG = "AudioInterceptor"
        private const val NOTIFICATION_ID = 4101
        private const val NOTIFICATION_CHANNEL_ID = "ggequalizer_audio_monitor"
        private const val MAX_TRACKED_PACKAGES = 16
        private const val DEFAULT_DVC_ENABLED = false
        private val ACTIVE_PLAYBACK_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS
        )
    }
}
