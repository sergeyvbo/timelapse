package dev.timelapse.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.timelapse.camera.CameraController
import dev.timelapse.data.PhotoEntity
import dev.timelapse.graph
import dev.timelapse.work.SyncScheduler
import dev.timelapse.work.VideoScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Foreground service (type camera) that owns the capture schedule.
 *
 * It stays alive the whole time the timelapse runs, including the night. Between shots it does nothing:
 * an exact alarm ([AlarmReceiver]) wakes the device and sends [ACTION_TICK]. Android 11+ only allows
 * camera access from a camera FGS that was started while the app was visible, so this service is started
 * from the UI and never restarted from the background on those versions.
 */
class CaptureService : LifecycleService() {

    companion object {
        const val ACTION_START = "dev.timelapse.START"
        const val ACTION_TICK = "dev.timelapse.TICK"
        const val ACTION_CAPTURE_NOW = "dev.timelapse.CAPTURE_NOW"
        const val ACTION_STOP = "dev.timelapse.STOP"
        private const val TAG = "CaptureService"

        /** True while the service is in the foreground in this process. */
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) = ContextCompat.startForegroundService(context, intent(context, ACTION_START))

        fun send(context: Context, action: String) {
            context.startService(intent(context, action))
        }

        private fun intent(context: Context, action: String) = Intent(context, CaptureService::class.java).setAction(action)

        private val fileNameFormat = DateTimeFormatter.ofPattern("'IMG_'yyyyMMdd_HHmmss'.jpg'")
    }

    private val tickMutex = Mutex()
    private lateinit var camera: CameraController

    override fun onCreate() {
        super.onCreate()
        camera = CameraController(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action ?: ACTION_START // null intent = restarted by the system after a kill

        if (action == ACTION_STOP) {
            stopTimelapse()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            if (!enterForeground()) {
                Log.w(TAG, "Not allowed to start camera service now; asking the user to resume")
                Notifications.showResumeNeeded(this)
                WakeLocks.release()
                stopSelf()
                return START_NOT_STICKY
            }
            isRunning = true
            Notifications.cancel(this, Notifications.ID_RESUME)
        }

        lifecycleScope.launch {
            if (action == ACTION_START) graph.settings.setRunning(true)
            graph.gifAppender.processPending() // finish anything interrupted by a crash
            tick(force = action == ACTION_CAPTURE_NOW)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, Notifications.ID_STATUS, Notifications.status(this, "Starting…"), type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed", e)
        false
    }

    private fun stopTimelapse() {
        AlarmScheduler.cancel(this)
        lifecycleScope.launch {
            graph.settings.setRunning(false)
            isRunning = false
            ServiceCompat.stopForeground(this@CaptureService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            WakeLocks.release()
        }
    }

    /**
     * Takes the scheduled shot if one is due (or a forced one), then arms the alarm for the next slot.
     * Slots missed by more than [MISSED_SLOT_GRACE] (phone off, process dead) are skipped, not caught up.
     */
    private suspend fun tick(force: Boolean) = tickMutex.withLock {
        try {
            val settings = graph.settings.settings()
            val state = graph.settings.state()
            val zone = ZoneId.systemDefault()
            val now = Instant.now()

            val floor = now.minus(MISSED_SLOT_GRACE)
            val after = state.lastSlotAt?.let { maxOf(Instant.ofEpochMilli(it), floor) } ?: floor
            val slot = Scheduler.nextSlot(after, settings, zone)
            val slotDue = !slot.isAfter(now.plus(EARLY_TOLERANCE))

            if (slotDue || force) capture()

            val next = if (slotDue) {
                graph.settings.setLastSlot(slot.toEpochMilli())
                Scheduler.nextSlot(slot, settings, zone)
            } else {
                slot
            }
            // Daily MP4: rebuild after the last shot before the night (or after a day without one).
            if (slotDue) {
                val lastOfDay = Scheduler.isLastOfDay(slot, settings, zone)
                val stale = state.video?.let { now.toEpochMilli() - it.renderedAt > VIDEO_MAX_AGE.toMillis() } ?: true
                if (lastOfDay || stale) VideoScheduler.enqueue(this, force = false)
            }
            AlarmScheduler.schedule(this, next)
            graph.settings.setNextShot(next.toEpochMilli())
            updateNotification(next)
        } catch (e: Exception) {
            Log.e(TAG, "tick failed", e)
            graph.settings.setLastError("Scheduler: ${e.message}")
        } finally {
            WakeLocks.release()
        }
    }

    private suspend fun capture() {
        val now = Instant.now()
        val file = File(graph.photosDir, fileNameFormat.format(now.atZone(ZoneId.systemDefault())))
        try {
            camera.capture(file, graph.settings.settings())
            graph.photos.insert(PhotoEntity(path = file.absolutePath, takenAt = now.toEpochMilli()))
            graph.settings.setLastShot(now.toEpochMilli())
            graph.settings.setLastError(null)
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            file.delete()
            graph.settings.setLastError("Capture failed: ${e.message}")
            return
        }
        graph.gifAppender.processPending()
        SyncScheduler.enqueue(this)
    }

    private suspend fun updateNotification(next: Instant) {
        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val today = graph.photos.countSince(startOfDay)
        val pending = graph.photos.countPendingUploads()
        val nextText = DateTimeFormatter.ofPattern("HH:mm").format(next.atZone(zone))
        val text = buildString {
            append("Next shot $nextText · $today today")
            if (pending > 0) append(" · $pending waiting for upload")
        }
        Notifications.updateStatus(this, text)
    }
}

private val MISSED_SLOT_GRACE: Duration = Duration.ofMinutes(5)
private val EARLY_TOLERANCE: Duration = Duration.ofSeconds(30)
private val VIDEO_MAX_AGE: Duration = Duration.ofHours(25)
