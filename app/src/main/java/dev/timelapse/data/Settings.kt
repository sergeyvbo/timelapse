package dev.timelapse.data

/** User-configurable settings. */
data class Settings(
    /** Minutes between shots during the day. */
    val intervalMinutes: Int = 60,
    /** Time of day the night pause starts, in minutes after midnight. */
    val nightStartMinutes: Int = 21 * 60,
    /** Length of the night pause in hours (0 = shoot around the clock). */
    val nightHours: Int = 9,
    val useFrontCamera: Boolean = false,
    val jpegQuality: Int = 95,
    /** Width of GIF frames. Only applies when a new GIF is started; the existing GIF keeps its size. */
    val gifWidth: Int = 480,
    val gifFrameDelayMs: Int = 200,
    val driveFolderName: String = "Timelapse",
) {
    fun sanitized() = copy(
        intervalMinutes = intervalMinutes.coerceIn(1, 24 * 60),
        nightStartMinutes = nightStartMinutes.coerceIn(0, 24 * 60 - 1),
        nightHours = nightHours.coerceIn(0, 23),
        jpegQuality = jpegQuality.coerceIn(1, 100),
        gifWidth = gifWidth.coerceIn(64, 1920),
        gifFrameDelayMs = gifFrameDelayMs.coerceIn(20, 10_000),
        driveFolderName = driveFolderName.trim().ifEmpty { "Timelapse" },
    )
}

/** Runtime state persisted across process restarts. Times are epoch millis. */
data class RuntimeState(
    val running: Boolean = false,
    /** The schedule slot the last scheduled shot was taken for. */
    val lastSlotAt: Long? = null,
    val lastShotAt: Long? = null,
    val nextShotAt: Long? = null,
    val driveConnected: Boolean = false,
    val driveFolderId: String? = null,
    /** Folder name [driveFolderId] was resolved for; a rename in settings triggers a new lookup. */
    val driveFolderName: String? = null,
    val gifFileId: String? = null,
    /** Local GIF length that was last uploaded successfully. */
    val gifUploadedLength: Long = 0,
    val lastUploadAt: Long? = null,
    val lastError: String? = null,
)
