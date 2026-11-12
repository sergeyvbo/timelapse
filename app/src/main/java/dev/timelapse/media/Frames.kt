package dev.timelapse.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import dev.timelapse.data.Resolution
import java.io.File
import java.io.IOException
import kotlin.math.max

/** JPEG helpers shared by capture, the preview GIF and the MP4 renderer. */
object Frames {

    fun exifRotation(file: File): Int = try {
        when (ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: IOException) {
        0
    }

    /** Width and height as the photo is meant to be viewed (EXIF rotation applied). */
    fun uprightSize(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        require(o.outWidth > 0 && o.outHeight > 0) { "Cannot decode ${file.name}" }
        return if (exifRotation(file) % 180 == 0) o.outWidth to o.outHeight else o.outHeight to o.outWidth
    }

    /** The exact output size for [res], in the same orientation (landscape/portrait) as the photo. */
    fun target(res: Resolution, upright: Pair<Int, Int>): Pair<Int, Int> =
        if (upright.second > upright.first) res.short to res.long else res.long to res.short

    /**
     * Decodes [file] upright, center-cropped to the aspect of [w]×[h] and scaled to exactly that size.
     * Rotation, crop and scale happen in one draw, so at most the decoded and the output bitmap are in memory.
     */
    fun loadFitted(file: File, w: Int, h: Int): Bitmap {
        val rotation = exifRotation(file)
        val (srcW, srcH) = uprightSize(file)

        var sample = 1
        while (srcW / (sample * 2) >= w && srcH / (sample * 2) >= h) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Cannot decode ${file.name}")
        val upW = if (rotation % 180 == 0) decoded.width else decoded.height
        val upH = if (rotation % 180 == 0) decoded.height else decoded.width

        val scale = max(w.toFloat() / upW, h.toFloat() / upH)
        val m = Matrix().apply {
            // Rotate around the origin, move back into positive space, then scale and center.
            postRotate(rotation.toFloat())
            when (rotation) {
                90 -> postTranslate(decoded.height.toFloat(), 0f)
                180 -> postTranslate(decoded.width.toFloat(), decoded.height.toFloat())
                270 -> postTranslate(0f, decoded.width.toFloat())
            }
            postScale(scale, scale)
            postTranslate((w - upW * scale) / 2f, (h - upH * scale) / 2f)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(decoded, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        decoded.recycle()
        return out
    }

    /**
     * Turns a raw camera JPEG into the stored photo: exactly [res] (in the photo's orientation).
     * If the camera already delivered that size the file is moved as-is (no re-compression).
     */
    fun normalize(raw: File, out: File, res: Resolution, quality: Int) {
        val upright = uprightSize(raw)
        val (w, h) = target(res, upright)
        if (upright == (w to h)) {
            if (!raw.renameTo(out)) throw IOException("Cannot move ${raw.name}")
            return
        }
        val bmp = loadFitted(raw, w, h)
        try {
            out.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, it)) throw IOException("JPEG encode failed") }
        } finally {
            bmp.recycle()
        }
        copyExif(raw, out)
        raw.delete()
    }

    private fun copyExif(from: File, to: File) = try {
        val src = ExifInterface(from)
        val dst = ExifInterface(to)
        for (tag in listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_WHITE_BALANCE,
        )) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.saveAttributes()
    } catch (_: IOException) {
        // Metadata is nice to have; the pixels are what matter.
    }
}
