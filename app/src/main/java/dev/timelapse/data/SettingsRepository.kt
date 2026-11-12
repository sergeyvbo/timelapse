package dev.timelapse.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val ds = context.applicationContext.dataStore

    private object K {
        val interval = intPreferencesKey("interval_minutes")
        val nightStart = intPreferencesKey("night_start_minutes")
        val nightHours = intPreferencesKey("night_hours")
        val frontCamera = booleanPreferencesKey("front_camera")
        val jpegQuality = intPreferencesKey("jpeg_quality")
        val gifWidth = intPreferencesKey("gif_width")
        val gifDelay = intPreferencesKey("gif_delay_ms")
        val folderName = stringPreferencesKey("drive_folder_name")
        val resolution = stringPreferencesKey("resolution")
        val videoFps = intPreferencesKey("video_fps")
        val gifEnabled = booleanPreferencesKey("gif_enabled")

        val running = booleanPreferencesKey("running")
        val lastSlot = longPreferencesKey("last_slot_at")
        val lastShot = longPreferencesKey("last_shot_at")
        val nextShot = longPreferencesKey("next_shot_at")
        val driveConnected = booleanPreferencesKey("drive_connected")
        val driveFolderId = stringPreferencesKey("drive_folder_id")
        val driveFolderResolvedName = stringPreferencesKey("drive_folder_resolved_name")
        val gifFileId = stringPreferencesKey("gif_file_id")
        val gifUploadedLength = longPreferencesKey("gif_uploaded_length")
        val lastUpload = longPreferencesKey("last_upload_at")
        val lastError = stringPreferencesKey("last_error")
        val video = stringPreferencesKey("video_info")
        val videoFileId = stringPreferencesKey("video_file_id")
        val videoUploadedStamp = longPreferencesKey("video_uploaded_stamp")
        val statsResetAt = longPreferencesKey("stats_reset_at")
    }

    val settings: Flow<Settings> = ds.data.map { p ->
        val d = Settings()
        Settings(
            intervalMinutes = p[K.interval] ?: d.intervalMinutes,
            nightStartMinutes = p[K.nightStart] ?: d.nightStartMinutes,
            nightHours = p[K.nightHours] ?: d.nightHours,
            useFrontCamera = p[K.frontCamera] ?: d.useFrontCamera,
            jpegQuality = p[K.jpegQuality] ?: d.jpegQuality,
            gifWidth = p[K.gifWidth] ?: d.gifWidth,
            gifFrameDelayMs = p[K.gifDelay] ?: d.gifFrameDelayMs,
            driveFolderName = p[K.folderName] ?: d.driveFolderName,
            resolution = p[K.resolution]?.let { r -> Resolution.entries.firstOrNull { it.name == r } } ?: d.resolution,
            videoFps = p[K.videoFps] ?: d.videoFps,
            gifEnabled = p[K.gifEnabled] ?: d.gifEnabled,
        ).sanitized()
    }

    val state: Flow<RuntimeState> = ds.data.map { p ->
        RuntimeState(
            running = p[K.running] ?: false,
            lastSlotAt = p[K.lastSlot],
            lastShotAt = p[K.lastShot],
            nextShotAt = p[K.nextShot],
            driveConnected = p[K.driveConnected] ?: false,
            driveFolderId = p[K.driveFolderId],
            driveFolderName = p[K.driveFolderResolvedName],
            gifFileId = p[K.gifFileId],
            gifUploadedLength = p[K.gifUploadedLength] ?: 0,
            lastUploadAt = p[K.lastUpload],
            lastError = p[K.lastError],
            video = p[K.video]?.let(::decodeVideo),
            videoFileId = p[K.videoFileId],
            videoUploadedStamp = p[K.videoUploadedStamp] ?: 0,
            statsResetAt = p[K.statsResetAt],
        )
    }

    suspend fun settings(): Settings = settings.first()
    suspend fun state(): RuntimeState = state.first()

    suspend fun saveSettings(s: Settings) {
        val v = s.sanitized()
        ds.edit {
            it[K.interval] = v.intervalMinutes
            it[K.nightStart] = v.nightStartMinutes
            it[K.nightHours] = v.nightHours
            it[K.frontCamera] = v.useFrontCamera
            it[K.jpegQuality] = v.jpegQuality
            it[K.gifWidth] = v.gifWidth
            it[K.gifDelay] = v.gifFrameDelayMs
            it[K.folderName] = v.driveFolderName
            it[K.resolution] = v.resolution.name
            it[K.videoFps] = v.videoFps
            it[K.gifEnabled] = v.gifEnabled
        }
    }

    suspend fun setRunning(running: Boolean) = ds.edit {
        it[K.running] = running
        if (!running) it.remove(K.nextShot)
    }

    suspend fun setLastSlot(slotAt: Long) = ds.edit { it[K.lastSlot] = slotAt }
    suspend fun setLastShot(at: Long) = ds.edit { it[K.lastShot] = at }
    suspend fun setNextShot(at: Long?) = ds.edit { it.put(K.nextShot, at) }
    suspend fun setDriveConnected(connected: Boolean) = ds.edit { it[K.driveConnected] = connected }

    suspend fun setDriveFolder(id: String, name: String) = ds.edit {
        it[K.driveFolderId] = id
        it[K.driveFolderResolvedName] = name
    }

    suspend fun setGifUploaded(fileId: String, length: Long) = ds.edit {
        it[K.gifFileId] = fileId
        it[K.gifUploadedLength] = length
    }

    suspend fun clearGifFileId() = ds.edit { it.remove(K.gifFileId) }
    suspend fun setLastUpload(at: Long) = ds.edit { it[K.lastUpload] = at }
    suspend fun setLastError(message: String?) = ds.edit { it.put(K.lastError, message) }

    suspend fun setVideo(info: VideoInfo) = ds.edit {
        it[K.video] = listOf(info.renderedAt, info.frames, info.width, info.height, info.bytes, info.key).joinToString("|")
    }

    suspend fun setVideoUploaded(fileId: String, stamp: Long) = ds.edit {
        it[K.videoFileId] = fileId
        it[K.videoUploadedStamp] = stamp
    }

    /**
     * Clears the statistics: photo counts restart from now and last shot/upload/error are forgotten.
     * Photos, the video, the GIF and the schedule are not touched.
     */
    suspend fun resetStats(at: Long) = ds.edit {
        it[K.statsResetAt] = at
        it.remove(K.lastShot)
        it.remove(K.lastUpload)
        it.remove(K.lastError)
    }

    suspend fun clearVideoFileId() = ds.edit { it.remove(K.videoFileId) }

    private fun decodeVideo(s: String): VideoInfo? = try {
        val p = s.split("|", limit = 6)
        VideoInfo(p[0].toLong(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toLong(), p[5])
    } catch (_: Exception) {
        null
    }

    private fun <T> MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }
}
