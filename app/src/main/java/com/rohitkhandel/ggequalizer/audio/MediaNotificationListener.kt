package com.rohitkhandel.ggequalizer.audio

import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.os.Handler
import android.os.Looper

/**
 * Uses Android's user-enabled notification-listener access to observe active
 * media sessions and publish the originating player package names to the app.
 *
 * A media session does not expose the player's AudioEffect audioSessionId.
 * Those IDs are tracked separately by AudioSessionReceiver.
 */
class MediaNotificationListener : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var listenerRegistered = false

    private val callbackHandler = Handler(Looper.getMainLooper())

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { _: List<MediaController>? ->
            publishActiveSessions()
        }

    override fun onListenerConnected() {
        super.onListenerConnected()

        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, MediaNotificationListener::class.java)

        try {
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                component,
                callbackHandler
            )
            listenerRegistered = mediaSessionManager != null
        } catch (error: SecurityException) {
            listenerRegistered = false
            Log.w(TAG, "Notification-listener access is not currently available", error)
        } catch (error: IllegalStateException) {
            listenerRegistered = false
            Log.w(TAG, "Media-session service is not ready", error)
        }

        publishActiveSessions()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        publishActiveSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        publishActiveSessions()
    }

    override fun onListenerDisconnected() {
        unregisterMediaSessionListener()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unregisterMediaSessionListener()
        super.onDestroy()
    }

    private fun publishActiveSessions() {
        val manager = mediaSessionManager ?: return
        val component = ComponentName(this, MediaNotificationListener::class.java)

        val controllers = try {
            manager.getActiveSessions(component)
        } catch (error: SecurityException) {
            Log.w(TAG, "Unable to read active media sessions", error)
            return
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Media-session service is not available", error)
            return
        }

        val activePackages = ArrayList<String>()
        for (controller in controllers) {
            val state = controller.playbackState?.state ?: continue
            if (state in ACTIVE_PLAYBACK_STATES &&
                controller.packageName.isNotBlank() &&
                !activePackages.contains(controller.packageName)
            ) {
                activePackages.add(controller.packageName)
            }
        }

        val update = Intent(AudioInterceptorService.ACTION_MEDIA_SESSIONS_CHANGED)
            .setPackage(packageName)
            .putStringArrayListExtra(
                AudioInterceptorService.EXTRA_ACTIVE_MEDIA_PACKAGES,
                activePackages
            )

        sendBroadcast(update)
    }

    private fun unregisterMediaSessionListener() {
        val manager = mediaSessionManager
        if (listenerRegistered && manager != null) {
            try {
                manager.removeOnActiveSessionsChangedListener(activeSessionsListener)
            } catch (error: RuntimeException) {
                Log.d(TAG, "Media-session listener was already disconnected", error)
            }
        }
        listenerRegistered = false
        mediaSessionManager = null
    }

    private companion object {
        const val TAG = "GGMediaListener"

        val ACTIVE_PLAYBACK_STATES = setOf(
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
