package dev.timelapse.gif

import android.util.Log
import dev.timelapse.data.PhotoDao
import dev.timelapse.data.SettingsRepository
import dev.timelapse.media.Frames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Appends every captured photo that is not yet in the preview GIF, in capture order. */
class GifAppender(
    private val photos: PhotoDao,
    private val store: GifStore,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    suspend fun processPending(): Unit = mutex.withLock {
        withContext(Dispatchers.Default) {
            val s = settings.settings()
            for (photo in photos.pendingGif()) {
                if (!s.gifEnabled) { // GIF off: let the photo move on to upload without a frame
                    photos.markGifAppended(photo.id)
                    continue
                }
                try {
                    val file = File(photo.path)
                    if (file.exists()) {
                        val canvas = store.state()?.let { it.width to it.height }
                        val frame = FrameRenderer.render(file, canvas, s.gifWidth)
                        store.append(frame, s.gifFrameDelayMs)
                    } else {
                        Log.w(TAG, "Photo ${photo.path} missing, skipped in GIF")
                    }
                } catch (e: Exception) {
                    // A broken JPEG must not block the queue forever (uploads wait for the GIF step).
                    Log.e(TAG, "Could not add ${photo.path} to GIF", e)
                    settings.setLastError("GIF: ${e.message}")
                }
                photos.markGifAppended(photo.id)
            }
        }
    }

    private companion object {
        const val TAG = "GifAppender"
    }
}

/** Scales/center-crops a photo to the GIF canvas. */
object FrameRenderer {
    fun render(file: File, canvas: Pair<Int, Int>?, defaultWidth: Int): GifFrame {
        val (targetW, targetH) = canvas ?: run {
            val (srcW, srcH) = Frames.uprightSize(file)
            defaultWidth to max(1, (defaultWidth.toDouble() * srcH / srcW).roundToInt())
        }
        val out = Frames.loadFitted(file, targetW, targetH)
        val pixels = IntArray(targetW * targetH)
        out.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        out.recycle()
        return GifFrame(targetW, targetH, pixels)
    }
}
