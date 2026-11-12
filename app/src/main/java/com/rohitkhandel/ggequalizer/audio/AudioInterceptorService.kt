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
 * Foreground coordinator for audio-effect session IDs and active media-player
 * package names. It observes session metadata; it does not claim that every
 * third-party player exposes an effect session or allows external DSP attachment.
 */
class AudioInterceptorService : Service() {

    private val audioSessionPackages = ConcurrentHashMap<Int, String>()
    private val activeMediaPackages = LinkedHashSet<String>()

    private var mediaReceiverRegistered = false

    private val mediaSessionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_MEDIA_SESSIONS_CHANGED) return

            val packages = intent.getStringArrayListExtra(EXTRA_ACTIVE_MEDIA_PACKAGES).orEmpty()
            synchronized(activeMediaPackages) {
                activeMediaPackages.clear()
                activeMediaPackages.addAll(
                    packages.filter { it.isNotBlank() }.take(MAX_TRACKED_PACKAGES)
                )
            }
            refreshForegroundNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerMediaSessionsReceiver()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            refreshForegroundNotification()
            return START_STICKY
        }

        when (intent.action) {
            ACTION_AUDIO_SESSION_OPEN -> registerAudioSession(intent)
            ACTION_AUDIO_SESSION_CLOSE -> unregisterAudioSession(intent)
            else -> Log.d(TAG, "Interceptor started without a session event")
        }

        refreshForegroundNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (mediaReceiverRegistered) {
            try {
                unregisterReceiver(mediaSessionsReceiver)
            } catch (error: IllegalArgumentException) {
                Log.d(TAG, "Media-session receiver was already unregistered", error)
            }
            mediaReceiverRegistered = false
        }
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
        Log.i(TAG, "Observed audio session " + sessionId + " from " + clientPackage)
    }

    private fun unregisterAudioSession(intent: Intent) {
        val sessionId = intent.getIntExtra(EXTRA_AUDIO_SESSION_ID, -1)
        val clientPackage = intent.getStringExtra(EXTRA_CLIENT_PACKAGE).orEmpty()

        if (sessionId < 0) return

        val previousPackage = audioSessionPackages.remove(sessionId)
        Log.i(
            TAG,
            "Closed audio session " + sessionId + " from " +
                (clientPackage.ifBlank { previousPackage.orEmpty() })
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
            description = "Shows when GG Equalizer is tracking audio sessions"
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val sessionCount = audioSessionPackages.size
        val mediaSummary = synchronized(activeMediaPackages) {
            if (activeMediaPackages.isEmpty()) {
                "No active media player detected"
            } else {
                activeMediaPackages.joinToString(", ")
            }
        }

        val sessionSummary = if (sessionCount == 1) {
            "1 audio-effect session observed"
        } else {
            sessionCount.toString() + " audio-effect sessions observed"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("GG Equalizer audio monitor")
            .setContentText(mediaSummary)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(mediaSummary + ". " + sessionSummary)
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun refreshForegroundNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val ACTION_AUDIO_SESSION_OPEN =
            "com.rohitkhandel.ggequalizer.action.AUDIO_SESSION_OPEN"
        const val ACTION_AUDIO_SESSION_CLOSE =
            "com.rohitkhandel.ggequalizer.action.AUDIO_SESSION_CLOSE"
        const val ACTION_MEDIA_SESSIONS_CHANGED =
            "com.rohitkhandel.ggequalizer.action.MEDIA_SESSIONS_CHANGED"

        const val EXTRA_AUDIO_SESSION_ID = "extra_audio_session_id"
        const val EXTRA_CLIENT_PACKAGE = "extra_client_package"
        const val EXTRA_ACTIVE_MEDIA_PACKAGES = "extra_active_media_packages"

        private const val TAG = "AudioInterceptor"
        private const val NOTIFICATION_ID = 4101
        private const val NOTIFICATION_CHANNEL_ID = "ggequalizer_audio_monitor"
        private const val MAX_TRACKED_PACKAGES = 16
    }
}
