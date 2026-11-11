package dev.timelapse.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One captured photo. Lifecycle: captured → appended to the local GIF → uploaded to Drive (local file deleted).
 */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val takenAt: Long,
    val gifAppended: Boolean = false,
    val uploaded: Boolean = false,
    val driveFileId: String? = null,
)

@Dao
interface PhotoDao {
    @Insert
    suspend fun insert(photo: PhotoEntity): Long

    @Query("SELECT * FROM photos WHERE gifAppended = 0 ORDER BY takenAt")
    suspend fun pendingGif(): List<PhotoEntity>

    /** Only photos already in the GIF may be uploaded, because the upload deletes the local original. */
    @Query("SELECT * FROM photos WHERE gifAppended = 1 AND uploaded = 0 ORDER BY takenAt")
    suspend fun pendingUpload(): List<PhotoEntity>

    @Query("UPDATE photos SET gifAppended = 1 WHERE id = :id")
    suspend fun markGifAppended(id: Long)

    @Query("UPDATE photos SET uploaded = 1, driveFileId = :driveFileId WHERE id = :id")
    suspend fun markUploaded(id: Long, driveFileId: String?)

    @Query("SELECT COUNT(*) FROM photos WHERE uploaded = 0")
    fun observePendingUploads(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE uploaded = 0")
    suspend fun countPendingUploads(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE takenAt >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM photos")
    fun observeTotal(): Flow<Int>
}

@Database(entities = [PhotoEntity::class], version = 1, exportSchema = false)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    companion object {
        fun build(context: Context): PhotoDatabase =
            Room.databaseBuilder(context.applicationContext, PhotoDatabase::class.java, "timelapse.db").build()
    }
}
