package com.rohitkhandel.ggequalizer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.rohitkhandel.ggequalizer.audio.AudioInterceptorService
import com.rohitkhandel.ggequalizer.ui.MainEqualizerScreen

class MainActivity : ComponentActivity() {
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                startAudioMonitorService()
            }

        startAudioMonitorService()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF42D9F5),
                    secondary = Color(0xFFFFB454),
                    background = Color(0xFF090C11),
                    surface = Color(0xFF111821),
                    onPrimary = Color(0xFF06131A),
                    onBackground = Color(0xFFEAF3FA),
                    onSurface = Color(0xFFEAF3FA)
                )
            ) {
                MainEqualizerScreen()
            }
        }

        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permissionState = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        )
        if (permissionState != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startAudioMonitorService() {
        val serviceIntent = Intent(this, AudioInterceptorService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (error: RuntimeException) {
            startService(serviceIntent)
        }
    }
}
