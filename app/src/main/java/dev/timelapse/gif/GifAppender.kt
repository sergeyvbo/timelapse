package dev.timelapse.gif

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.timelapse.data.PhotoDao
import dev.timelapse.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Appends every captured photo that is not yet in the GIF, in capture order. */
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

/** Decodes a JPEG, applies EXIF rotation and scales/center-crops it to the GIF canvas. */
object FrameRenderer {
    fun render(file: File, canvas: Pair<Int, Int>?, defaultWidth: Int): GifFrame {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot decode ${file.name}" }

        val rotation = when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val srcW = if (rotation % 180 == 0) bounds.outWidth else bounds.outHeight
        val srcH = if (rotation % 180 == 0) bounds.outHeight else bounds.outWidth

        val (targetW, targetH) = canvas ?: run {
            val w = defaultWidth
            w to max(1, (w.toDouble() * srcH / srcW).roundToInt())
        }

        var sample = 1
        while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Cannot decode ${file.name}")

        val upright = if (rotation != 0) {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        // Center-crop to the target aspect ratio, then scale.
        val scale = max(targetW.toFloat() / upright.width, targetH.toFloat() / upright.height)
        val cropW = (targetW / scale).roundToInt().coerceIn(1, upright.width)
        val cropH = (targetH / scale).roundToInt().coerceIn(1, upright.height)
        val left = (upright.width - cropW) / 2
        val top = (upright.height - cropH) / 2

        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            upright,
            Rect(left, top, left + cropW, top + cropH),
            Rect(0, 0, targetW, targetH),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        upright.recycle()

        val pixels = IntArray(targetW * targetH)
        out.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        out.recycle()
        return GifFrame(targetW, targetH, pixels)
    }
}
