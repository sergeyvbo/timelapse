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
 * Uploads pending photos (oldest first), deletes each local original once Drive confirms it,
 * then replaces the GIF on Drive if the local one has grown.
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
        if (id != state.driveFolderId) g.settings.clearGifFileId() // GIF goes into the new folder
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
            file.delete()
        }
    }

    private suspend fun uploadGif(drive: DriveClient, folderId: String) {
        val gif = g.gifStore.state() ?: return
        val state = g.settings.state()
        if (state.gifFileId != null && state.gifUploadedLength == gif.length) return

        val body = GifSnapshotBody(g.gifStore.gifFile, gif.length)
        val existing = state.gifFileId?.takeIf { drive.isAlive(it) }
        val id = if (existing != null) drive.replaceContent(existing, body) else drive.createFile(GIF_NAME, folderId, body)
        g.settings.setGifUploaded(id, gif.length)
    }

    private suspend fun retryOrFail(message: String): Result {
        g.settings.setLastError(message)
        return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    private companion object {
        const val TAG = "SyncWorker"
        const val GIF_NAME = "timelapse.gif"
        const val MAX_ATTEMPTS = 12
    }
}
