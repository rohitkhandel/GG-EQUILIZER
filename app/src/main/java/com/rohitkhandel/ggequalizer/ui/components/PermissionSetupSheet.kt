package com.rohitkhandel.ggequalizer.ui.components

import android.app.Activity
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rohitkhandel.ggequalizer.audio.BootAndPersistenceHelper
import com.rohitkhandel.ggequalizer.audio.MediaNotificationListener

private data class PermissionItemState(
    val title: String,
    val detail: String,
    val granted: Boolean,
    val actionLabel: String,
    val onAction: () -> Unit
)

@Composable
fun PermissionSetupSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var refreshToken by remember { mutableStateOf(0) }
    val items = remember(refreshToken) { readPermissionItems(context) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111821))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "AUDIO ACCESS",
                            color = Color(0xFFEAF3FA),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Android permissions used by player detection",
                            color = Color(0xFF8EA0B4),
                            fontSize = 10.sp
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("CLOSE", color = Color(0xFF42D9F5))
                    }
                }

                items.forEach { item ->
                    PermissionItem(item)
                    Divider(color = Color(0xFF283544))
                }

                Text(
                    text = "DUMP is a protected system permission. A normal APK usually cannot receive it through pm grant; the command below is provided for checking supported test/system builds only.",
                    color = Color(0xFFFFC276),
                    fontSize = 10.sp
                )
                Button(
                    onClick = {
                        copyDumpCommand(context)
                        Toast.makeText(context, "ADB command copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("COPY ADB / SHIZUKU CHECK COMMAND")
                }
                TextButton(
                    onClick = { refreshToken++ },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("REFRESH PERMISSION STATUS", color = Color(0xFF42D9F5))
                }
            }
        }
    }
}

@Composable
private fun PermissionItem(item: PermissionItemState) {
    val statusColor = if (item.granted) Color(0xFF52D68A) else Color(0xFFFF6874)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, color = Color(0xFFEAF3FA), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(item.detail, color = Color(0xFF8EA0B4), fontSize = 9.sp)
            }
            Text(
                if (item.granted) "GRANTED" else "NOT GRANTED",
                color = statusColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(30.dp))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            )
        }
        if (!item.granted && item.actionLabel.isNotBlank()) {
            TextButton(onClick = item.onAction) {
                Text(item.actionLabel, color = Color(0xFF42D9F5), fontSize = 10.sp)
            }
        }
    }
}

private fun readPermissionItems(context: Context): List<PermissionItemState> {
    val notificationGranted = runCatching {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as? android.app.NotificationManager
        manager?.isNotificationListenerAccessGranted(
            ComponentName(context, MediaNotificationListener::class.java)
        ) == true
    }.getOrDefault(false)

    val batteryGranted = BootAndPersistenceHelper.isIgnoringBatteryOptimizations(context)

    val usageGranted = runCatching {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    val dumpGranted = context.checkSelfPermission(android.Manifest.permission.DUMP) ==
        PackageManager.PERMISSION_GRANTED

    return listOf(
        PermissionItemState(
            title = "Notification Listener",
            detail = "Reads active media-session package names after you enable access",
            granted = notificationGranted,
            actionLabel = "OPEN NOTIFICATION ACCESS",
            onAction = { launchSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        ),
        PermissionItemState(
            title = "Battery Optimization",
            detail = "Optional exemption; may increase battery use",
            granted = batteryGranted,
            actionLabel = "REQUEST EXEMPTION",
            onAction = {
                val activity = context as? Activity
                if (activity != null) {
                    BootAndPersistenceHelper.requestIgnoreBatteryOptimizations(activity)
                } else {
                    launchSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        ),
        PermissionItemState(
            title = "Usage Access",
            detail = "Shows recently foregrounded apps; it does not prove audio playback",
            granted = usageGranted,
            actionLabel = "OPEN USAGE ACCESS",
            onAction = { launchSettings(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        ),
        PermissionItemState(
            title = "DUMP",
            detail = "System/privileged permission; ordinary APKs cannot normally obtain it",
            granted = dumpGranted,
            actionLabel = "",
            onAction = {}
        )
    )
}

private fun launchSettings(context: Context, intent: Intent) {
    runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, "This settings page is not available on this device", Toast.LENGTH_SHORT).show()
    }
}

private fun copyDumpCommand(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val command = "pm grant ${context.packageName} android.permission.DUMP"
    clipboard.setPrimaryClip(ClipData.newPlainText("GG Equalizer DUMP permission check", command))
}
