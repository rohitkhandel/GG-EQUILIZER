package com.rohitkhandel.ggaudio.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Tracks media players and owns external audio-session effects independently
 * from GG Audio's optional local playback service.
 */
object MediaPlayerTrackingStatus {
    private val mutablePlayingPackages = MutableStateFlow<Set<String>>(emptySet())
    val playingPackages: StateFlow<Set<String>> = mutablePlayingPackages.asStateFlow()

    internal fun update(packages: Set<String>) {
        mutablePlayingPackages.value = packages
    }
}

class GgMediaSessionListenerService : NotificationListenerService() {

    private val listenerComponent by lazy {
        ComponentName(this, GgMediaSessionListenerService::class.java)
    }
    private var mediaSessionManager: MediaSessionManager? = null
    private var externalSessionEffects: AppAudioSessionEffects? = null
    private var audioEffectReceiverRegistered = false
    private var settingsScope: CoroutineScope? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            publishPlayingPackages(controllers.orEmpty())
        }

    private val audioEffectSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val incoming = intent ?: return
            val event = AppAudioEffectSessionEventParser.parse(
                action = incoming.action,
                packageName = incoming.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME),
                sessionId = incoming.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            ) ?: return

            if (event.packageName == packageName) return
            if (Build.VERSION.SDK_INT >= 34) {
                val senderPackage = sentFromPackage ?: return
                if (senderPackage != event.packageName) return
            }
            when (event) {
                is AppAudioEffectSessionEvent.Open ->
                    externalSessionEffects?.openSession(event.packageName, event.sessionId)
                is AppAudioEffectSessionEvent.Close ->
                    externalSessionEffects?.closeSession(event.packageName, event.sessionId)
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        startExternalAudioTracking()
        runCatching {
            val manager = getSystemService(MediaSessionManager::class.java)
            mediaSessionManager = manager
            publishPlayingPackages(manager.getActiveSessions(listenerComponent))
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
        }
    }

    private fun startExternalAudioTracking() {
        if (externalSessionEffects == null) {
            AppAudioSessionEffects().also {
                externalSessionEffects = it
                PlaybackDspSettings.attachExternalSessionEffects(it)
            }
        }
        if (!audioEffectReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    audioEffectSessionReceiver,
                    filter,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    null,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(
                    audioEffectSessionReceiver,
                    filter,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    null
                )
            }
            audioEffectReceiverRegistered = true
        }

        if (settingsScope == null) {
            settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
                scope.launch {
                    runCatching {
                        val repository = SavedEqualizerPresetRepository(applicationContext)
                        val savedSettings = repository.settings.first()
                        val savedProfiles = repository.appProfiles.first()
                        PlaybackDspSettings.update(
                            bands = savedSettings.toBands(),
                            preampDb = savedSettings.preampDb,
                            bassBoostPercent = savedSettings.bassBoostPercent,
                            stereoWidthPercent = savedSettings.virtualizerPercent
                        )
                        PlaybackDspSettings.updateExternalAppProfiles(
                            savedProfiles.associate { it.packageName to it.toDspSettings() }
                        )
                    }
                }
            }
        }
    }

    override fun onListenerDisconnected() {
        stopExternalAudioTracking()
        runCatching { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        mediaSessionManager = null
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
            val manager = mediaSessionManager ?: getSystemService(MediaSessionManager::class.java)
                .also { mediaSessionManager = it }
            publishPlayingPackages(manager.getActiveSessions(listenerComponent))
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

    private fun stopExternalAudioTracking() {
        if (audioEffectReceiverRegistered) {
            runCatching { unregisterReceiver(audioEffectSessionReceiver) }
            audioEffectReceiverRegistered = false
        }
        externalSessionEffects?.let { controller ->
            PlaybackDspSettings.detachExternalSessionEffects(controller)
            controller.close()
        }
        externalSessionEffects = null
        settingsScope?.cancel()
        settingsScope = null
        AppAudioSessionStatus.update(emptySet())
    }

    override fun onDestroy() {
        stopExternalAudioTracking()
        runCatching { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        mediaSessionManager = null
        MediaPlayerTrackingStatus.update(emptySet())
        super.onDestroy()
    }
}
