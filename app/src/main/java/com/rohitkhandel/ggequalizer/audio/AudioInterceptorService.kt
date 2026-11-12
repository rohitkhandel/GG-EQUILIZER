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
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground coordinator for session-attached effects, DVC headroom management
 * and media-player notifications. Session IDs are required for effect
 * attachment; a media session package name alone is not an audio session ID.
 */
class AudioInterceptorService : Service() {
    private val audioSessionPackages = ConcurrentHashMap<Int, String>()
    private val activeMediaPackages = LinkedHashSet<String>()

    private var mediaReceiverRegistered = false
    private var dvcManager: DvcManager? = null
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
            GlobalEqController.updateActivePlayers(packages)
            refreshForegroundNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerMediaSessionsReceiver()

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            refreshForegroundNotification()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_AUDIO_SESSION_OPEN -> registerAudioSession(intent)
            ACTION_AUDIO_SESSION_CLOSE -> unregisterAudioSession(intent)
            ACTION_SET_DVC_ENABLED -> {
                dvcManager?.setEnabled(
                    intent.getBooleanExtra(EXTRA_DVC_ENABLED, false)
                )
            }
            ACTION_SET_MASTER_ENABLED -> {
                GlobalEqController.setMasterEnabled(
                    intent.getBooleanExtra(EXTRA_MASTER_ENABLED, true)
                )
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
        dvcManager?.stop()
        dvcManager = null

        if (mediaReceiverRegistered) {
            runCatching { unregisterReceiver(mediaSessionsReceiver) }
            mediaReceiverRegistered = false
        }

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
        val attachment = GlobalEqController.attachSession(sessionId, clientPackage)
        Log.i(TAG, "Session $sessionId from $clientPackage; attachment=$attachment")
    }

    private fun unregisterAudioSession(intent: Intent) {
        val sessionId = intent.getIntExtra(EXTRA_AUDIO_SESSION_ID, -1)
        val clientPackage = intent.getStringExtra(EXTRA_CLIENT_PACKAGE).orEmpty()
        if (sessionId < 0) return

        val previousPackage = audioSessionPackages.remove(sessionId)
        GlobalEqController.detachSession(sessionId)
        Log.i(
            TAG,
            "Closed session $sessionId from " +
                clientPackage.ifBlank { previousPackage.orEmpty() }
        )
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
        return audioSessionPackages.values.distinct().firstOrNull()
            ?: "No active media player detected"
    }

    private fun buildNotification(): Notification {
        val playerSummary = activePlayerSummary()
        val sessionCount = audioSessionPackages.size
        val dvc = dvcManager?.snapshot()
        val dvcText = when {
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
                    .bigText("$playerSummary · $sessionText · $dvcText · $attachmentSummary")
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
    }
}
