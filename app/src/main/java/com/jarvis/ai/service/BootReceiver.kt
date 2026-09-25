package com.jarvis.ai.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.ai.AppSettings
import com.jarvis.ai.JarvisApp
import com.jarvis.ai.MainActivity

/** Restores listening after boot where Android allows it, otherwise asks the user to resume. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppSettings.assistantEnabled(context)) return

        val audioPermissionGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!audioPermissionGranted) {
            AppSettings.setAssistantEnabled(context, false)
            return
        }

        // Android 14+ restricts microphone foreground services from boot receivers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            postResumeNotification(context)
            AppSettings.setStatus(context, "Open JarvisAI to resume listening after device restart.")
            return
        }

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, JarvisForegroundService::class.java)
                    .setAction(JarvisForegroundService.ACTION_START),
            )
        } catch (_: SecurityException) {
            postResumeNotification(context)
        } catch (_: IllegalStateException) {
            postResumeNotification(context)
        }
    }

    private fun postResumeNotification(context: Context) {
        val open = PendingIntent.getActivity(
            context,
            5,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(context, JarvisApp.CHANNEL_BOOT)
            .setSmallIcon(com.jarvis.ai.R.drawable.ic_notification)
            .setContentTitle("JarvisAI is ready")
            .setContentText("Tap to resume background listening.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java)
                .notify(BOOT_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val BOOT_NOTIFICATION_ID = 4103
    }
}
