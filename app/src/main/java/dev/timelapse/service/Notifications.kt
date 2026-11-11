package dev.timelapse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.timelapse.R
import dev.timelapse.ui.MainActivity

object Notifications {
    private const val CHANNEL_STATUS = "status"
    private const val CHANNEL_ALERTS = "alerts"
    const val ID_STATUS = 1
    const val ID_AUTH = 2
    const val ID_RESUME = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Timelapse status", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Needs attention", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun status(context: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle("Timelapse running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun updateStatus(context: Context, text: String) = notify(context, ID_STATUS, status(context, text))

    fun showAuthNeeded(context: Context) = notify(
        context, ID_AUTH,
        alert(context, "Google Drive needs re-authorization", "Open the app and tap \"Connect Google Drive\". Photos are kept until then."),
    )

    fun showResumeNeeded(context: Context) = notify(
        context, ID_RESUME,
        alert(context, "Timelapse paused", "Tap to resume taking photos."),
    )

    fun cancel(context: Context, id: Int) = context.getSystemService(NotificationManager::class.java).cancel(id)

    private fun alert(context: Context, title: String, text: String) =
        NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(context: Context, id: Int, n: Notification) {
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // Notification permission denied; nothing else to do.
        }
    }
}
