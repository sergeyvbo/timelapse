package dev.timelapse.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dev.timelapse.graph
import kotlinx.coroutines.runBlocking
import java.time.Instant

/** Exact, Doze-proof wake-ups for each scheduled shot. */
object AlarmScheduler {
    fun schedule(context: Context, at: Instant) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Keeps the CPU awake from the alarm until the service has finished the shot. */
object WakeLocks {
    private var lock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        val l = lock ?: context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "timelapse:capture")
            .apply { setReferenceCounted(false) }
            .also { lock = it }
        l.acquire(3 * 60 * 1000L)
    }

    @Synchronized
    fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WakeLocks.acquire(context)
        if (CaptureService.isRunning) {
            try {
                CaptureService.send(context, CaptureService.ACTION_TICK)
                return
            } catch (e: Exception) {
                Log.w("AlarmReceiver", "Could not reach service", e)
            }
        }
        ServiceResumer.resumeFromBackground(context)
        WakeLocks.release()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val running = runBlocking { context.graph.settings.state().running }
        if (running) ServiceResumer.resumeFromBackground(context)
    }
}

object ServiceResumer {
    /**
     * Restarting a camera service from the background is only allowed before Android 11 (while-in-use
     * camera restriction). On newer versions ask the user to tap a notification instead.
     */
    fun resumeFromBackground(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try {
                CaptureService.start(context)
                return
            } catch (e: Exception) {
                Log.w("ServiceResumer", "Background start failed", e)
            }
        }
        Notifications.showResumeNeeded(context)
    }
}
