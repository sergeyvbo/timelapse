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

    private fun <T> MutablePreferences.put(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }
}
