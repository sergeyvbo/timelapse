package dev.timelapse.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.timelapse.data.VideoInfo
import dev.timelapse.graph
import dev.timelapse.media.Frames
import dev.timelapse.media.Mp4Renderer
import dev.timelapse.service.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object VideoScheduler {
    const val WORK_NAME = "video-render"

    /**
     * Re-renders the MP4 from all frames. [force] restarts a running render and renders even if nothing changed;
     * otherwise a render already queued/running is kept and an unchanged video is skipped.
     */
    fun enqueue(context: Context, force: Boolean) {
        val request = OneTimeWorkRequestBuilder<VideoWorker>()
            .setInputData(workDataOf(VideoWorker.KEY_FORCE to force))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}

/** Rebuilds `timelapse.mp4` from every stored photo, then queues a Drive sync to upload it. */
class VideoWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val g = context.graph

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val settings = g.settings.settings()
        val frames = g.photos.all().map { File(it.path) }.filter { it.exists() }
        if (frames.isEmpty()) return@withContext Result.success()

        val target = g.videoFile
        val key = "${frames.size}:${frames.last().name}:${settings.resolution}:${settings.videoFps}"
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!force && target.exists() && g.settings.state().video?.key == key) return@withContext Result.success()

        // Long job: run as a foreground service so it is not stopped after WorkManager's 10-minute limit.
        try {
            setForeground(foregroundInfo("Rendering video: 0 / ${frames.size} frames"))
        } catch (e: Exception) {
            Log.w(TAG, "Could not run in foreground; rendering anyway", e)
        }

        val tmp = File(target.parentFile, "timelapse.tmp.mp4")
        try {
            // Orientation follows the most recent photo.
            val size = Frames.target(settings.resolution, Frames.uprightSize(frames.last()))
            var lastNotified = 0L
            val out = Mp4Renderer.render(frames, tmp, size, settings.videoFps) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastNotified > 2_000 || done == total) {
                    lastNotified = now
                    Notifications.updateVideoProgress(applicationContext, "Rendering video: $done / $total frames")
                }
                if (isStopped) throw InterruptedException("Render cancelled")
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) error("Cannot replace ${target.name}")
            }
            g.settings.setVideo(VideoInfo(System.currentTimeMillis(), out.frames, out.width, out.height, out.bytes, key))
            SyncScheduler.enqueue(applicationContext)
            Result.success()
        } catch (e: InterruptedException) {
            tmp.delete()
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Render failed", e)
            tmp.delete()
            g.settings.setLastError("Video: ${e.message}")
            Result.failure()
        } finally {
            Notifications.cancel(applicationContext, Notifications.ID_VIDEO)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Rendering video")

    private fun foregroundInfo(text: String): ForegroundInfo {
        val n = Notifications.videoProgress(applicationContext, text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_VIDEO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_VIDEO, n)
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        private const val TAG = "VideoWorker"
    }
}
