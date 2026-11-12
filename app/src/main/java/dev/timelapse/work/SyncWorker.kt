package dev.timelapse.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.timelapse.drive.DriveAuth
import dev.timelapse.drive.DriveClient
import dev.timelapse.drive.GifSnapshotBody
import dev.timelapse.gif.GifStore
import dev.timelapse.graph
import dev.timelapse.service.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val WORK_NAME = "drive-sync"

    /** Queues a sync that runs on Wi-Fi (unmetered) only. Safe to call after every shot. */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/**
 * Uploads pending photos (oldest first; local files are kept as video frames), then replaces the preview GIF
 * and the MP4 on Drive if the local ones changed.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val g = context.graph

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = try {
            DriveAuth.accessToken(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "authorize failed", e)
            return@withContext retryOrFail("Drive sign-in failed: ${e.message}")
        }
        if (token == null) {
            g.settings.setDriveConnected(false)
            g.settings.setLastError("Google Drive needs re-authorization")
            Notifications.showAuthNeeded(applicationContext)
            return@withContext Result.failure()
        }

        try {
            val drive = DriveClient(g.http, token)
            val folderId = ensureFolder(drive)
            uploadPhotos(drive, folderId)
            uploadGif(drive, folderId)
            uploadVideo(drive, folderId)
            g.settings.setLastUpload(System.currentTimeMillis())
            g.settings.setLastError(null)
            Notifications.cancel(applicationContext, Notifications.ID_AUTH)
            Result.success()
        } catch (e: DriveClient.HttpException) {
            Log.w(TAG, "Drive error", e)
            if (e.code == 401) DriveAuth.invalidate(applicationContext, token)
            retryOrFail("Drive: ${e.message}")
        } catch (e: IOException) {
            Log.w(TAG, "Network error", e)
            retryOrFail("Upload: ${e.message}")
        }
    }

    private suspend fun ensureFolder(drive: DriveClient): String {
        val name = g.settings.settings().driveFolderName
        val state = g.settings.state()
        if (state.driveFolderId != null && state.driveFolderName == name && drive.isAlive(state.driveFolderId)) {
            return state.driveFolderId
        }
        val id = drive.findFolder(name) ?: drive.createFolder(name)
        if (id != state.driveFolderId) { // GIF and video go into the new folder
            g.settings.clearGifFileId()
            g.settings.clearVideoFileId()
        }
        g.settings.setDriveFolder(id, name)
        return id
    }

    private suspend fun uploadPhotos(drive: DriveClient, folderId: String) {
        val jpeg = "image/jpeg".toMediaType()
        for (photo in g.photos.pendingUpload()) {
            val file = File(photo.path)
            if (!file.exists()) {
                g.photos.markUploaded(photo.id, null)
                continue
            }
            val id = drive.createFile(file.name, folderId, file.asRequestBody(jpeg))
            g.photos.markUploaded(photo.id, id)
        }
    }

    /** Finished GIFs of earlier days first (completing the file uploaded during that day), then today's. */
    private suspend fun uploadGif(drive: DriveClient, folderId: String) {
        for (file in g.gifStore.finished()) {
            val day = GifStore.dayOf(file)?.toString()
            val state = g.settings.state()
            val existing = state.gifFileId?.takeIf { day != null && state.gifDay == day && drive.isAlive(it) }
            val body = file.asRequestBody(GIF_TYPE.toMediaType())
            if (existing != null) drive.replaceContent(existing, body) else drive.createFile(file.name, folderId, body)
            g.settings.clearGifFileId()
            file.delete()
        }

        if (!g.settings.settings().gifEnabled) return
        val gif = g.gifStore.state() ?: return
        val day = gif.day?.toString() ?: LEGACY_DAY
        val state = g.settings.state()
        val sameFile = state.gifFileId != null && (state.gifDay ?: LEGACY_DAY) == day
        if (sameFile && state.gifUploadedLength == gif.length) return

        val body = GifSnapshotBody(g.gifStore.gifFile, gif.length)
        val existing = state.gifFileId?.takeIf { sameFile && drive.isAlive(it) }
        val name = gif.day?.let(GifStore::fileName) ?: "timelapse.gif"
        val id = if (existing != null) drive.replaceContent(existing, body) else drive.createFile(name, folderId, body)
        g.settings.setGifUploaded(id, gif.length, day)
    }

    private suspend fun uploadVideo(drive: DriveClient, folderId: String) {
        val file = g.videoFile
        if (!file.exists()) return
        val stamp = file.lastModified()
        val state = g.settings.state()
        if (state.videoFileId != null && state.videoUploadedStamp == stamp) return

        val body = file.asRequestBody("video/mp4".toMediaType())
        val existing = state.videoFileId?.takeIf { drive.isAlive(it) }
        val id = if (existing != null) drive.replaceContent(existing, body) else drive.createFile(VIDEO_NAME, folderId, body)
        g.settings.setVideoUploaded(id, stamp)
    }

    private suspend fun retryOrFail(message: String): Result {
        g.settings.setLastError(message)
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val TAG = "SyncWorker"
        const val GIF_TYPE = "image/gif"

        /** gifDay of a GIF made before daily rotation. */
        const val LEGACY_DAY = "legacy"
        const val VIDEO_NAME = "timelapse.mp4"
        const val MAX_ATTEMPTS = 12
    }
}
