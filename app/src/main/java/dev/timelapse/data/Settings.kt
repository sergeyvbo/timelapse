package dev.timelapse.data

/** Photo and video resolution (4:3, matching the phone's sensor). Portrait framing swaps width and height. */
enum class Resolution(val long: Int, val short: Int) {
    R1440(1440, 1080),
    R2016(2016, 1512),
    R4032(4032, 3024);

    val label get() = "$long×$short"
}

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
    /** Resolution of the stored photos and of the MP4 (the video may be scaled down if the encoder can't do it). */
    val resolution: Resolution = Resolution.R1440,
    val videoFps: Int = 24,
    /** Also build the small preview GIF. */
    val gifEnabled: Boolean = true,
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
        videoFps = videoFps.coerceIn(1, 60),
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
    /** Day of the GIF that [gifFileId] holds. */
    val gifDay: String? = null,
    /** Local GIF length that was last uploaded successfully. */
    val gifUploadedLength: Long = 0,
    val lastUploadAt: Long? = null,
    val lastError: String? = null,
    val video: VideoInfo? = null,
    /** When statistics were last cleared; photo counts only include photos taken after it. */
    val statsResetAt: Long? = null,
    val videoFileId: String? = null,
    /** lastModified of the local MP4 that was last uploaded. */
    val videoUploadedStamp: Long = 0,
)

/** The last rendered local MP4. [key] identifies its inputs so an unchanged video is not re-rendered. */
data class VideoInfo(val renderedAt: Long, val frames: Int, val width: Int, val height: Int, val bytes: Long, val key: String)
