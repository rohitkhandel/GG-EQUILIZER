package com.rohitkhandel.ggequalizer.audio

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rohitkhandel.ggequalizer.MainActivity

/**
 * Helpers for best-effort background persistence. Android 15+ forbids a
 * BOOT_COMPLETED receiver from starting a mediaPlayback foreground service,
 * so the receiver records a pending resume and shows a notification directing
 * the user to reopen GG Equalizer instead of attempting a prohibited start.
 */
object BootAndPersistenceHelper {
    private const val PREFS_NAME = "ggequalizer_runtime"
    private const val KEY_BOOT_RESUME_PENDING = "boot_resume_pending"
    private const val KEY_LAST_BOOT_AT = "last_boot_at"
    private const val CHANNEL_ID = "ggequalizer_boot_resume"
    private const val NOTIFICATION_ID = 4102
    private const val TAG = "GGBootPersistence"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(activity: Activity): Boolean {
        if (isIgnoringBatteryOptimizations(activity)) return true
        return try {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:" + activity.packageName)
            }
            activity.startActivity(request)
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Battery optimization request screen is unavailable", error)
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.isSuccess
        }
    }

    fun hasPendingBootResume(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BOOT_RESUME_PENDING, false)

    fun consumePendingBootResume(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BOOT_RESUME_PENDING, false)
            .apply()
    }

    internal fun recordBootAndNotify(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BOOT_RESUME_PENDING, true)
            .putLong(KEY_LAST_BOOT_AT, System.currentTimeMillis())
            .apply()

        val notificationManager =
            context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "GG Equalizer startup",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Lets you reopen GG Equalizer after device restart"
                    setShowBadge(false)
                }
            )
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            4102,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        runCatching {
            notificationManager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("GG Equalizer is ready")
                    .setContentText("Open the app to resume audio-session monitoring.")
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            "Android limits automatic media-playback service startup after boot. " +
                                "Open GG Equalizer to resume monitoring."
                        )
                    )
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build()
            )
        }.onFailure { Log.w(TAG, "Unable to post startup notification", it) }
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        BootAndPersistenceHelper.recordBootAndNotify(context.applicationContext)
    }
}
