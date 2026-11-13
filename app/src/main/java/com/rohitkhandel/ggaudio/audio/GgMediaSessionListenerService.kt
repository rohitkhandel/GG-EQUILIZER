package com.rohitkhandel.ggaudio.audio

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.ComponentName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Detects apps publishing an active Android media session.
 *
 * This is discovery/diagnostics only: MediaSession does not expose the app's
 * AudioTrack session ID. DSP is attached only when the player separately
 * publishes ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.
 */
object MediaPlayerTrackingStatus {
    private val mutablePlayingPackages = MutableStateFlow<Set<String>>(emptySet())
    val playingPackages: StateFlow<Set<String>> = mutablePlayingPackages.asStateFlow()

    internal fun update(packages: Set<String>) {
        mutablePlayingPackages.value = packages
    }
}

class GgMediaSessionListenerService : NotificationListenerService() {

    private val sessionsListener =
        MediaController.OnActiveSessionsChangedListener { controllers ->
            publishPlayingPackages(controllers.orEmpty())
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            val controllers = getSystemService(android.media.session.MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(this, GgMediaSessionListenerService::class.java))
            publishPlayingPackages(controllers)
            getSystemService(android.media.session.MediaSessionManager::class.java)
                .addOnActiveSessionsChangedListener(
                    sessionsListener,
                    ComponentName(this, GgMediaSessionListenerService::class.java)
                )
        }
    }

    override fun onListenerDisconnected() {
        runCatching {
            getSystemService(android.media.session.MediaSessionManager::class.java)
                .removeOnActiveSessionsChangedListener(sessionsListener)
        }
        MediaPlayerTrackingStatus.update(emptySet())
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshSessions()
    }

    private fun refreshSessions() {
        runCatching {
            val controllers = getSystemService(android.media.session.MediaSessionManager::class.java)
                .getActiveSessions(ComponentName(this, GgMediaSessionListenerService::class.java))
            publishPlayingPackages(controllers)
        }
    }

    private fun publishPlayingPackages(controllers: List<MediaController>) {
        val playing = controllers.mapNotNullTo(mutableSetOf()) { controller ->
            val state = controller.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_CONNECTING
            ) controller.packageName else null
        }
        MediaPlayerTrackingStatus.update(playing)
    }
}
