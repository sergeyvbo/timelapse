package dev.timelapse.media

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Renders JPEG frames into an H.264 MP4 with the platform encoder (MediaCodec + MediaMuxer).
 *
 * Frames are fed as YUV420 through [MediaCodec.getInputImage], so presentation times are exact
 * (frame i is shown at i / fps). Every frame is scaled/center-cropped to the video size.
 */
object Mp4Renderer {
    private const val TAG = "Mp4Renderer"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L

    /** Bits per pixel per frame. Timelapse frames differ a lot from each other, so this is fairly generous. */
    private const val BITS_PER_PIXEL = 0.12

    data class Output(val width: Int, val height: Int, val frames: Int, val bytes: Long)

    /** Largest size ≤ [w]×[h] with the same aspect ratio that a hardware (preferred) AVC encoder accepts. */
    fun fitToEncoder(w: Int, h: Int): Triple<String, Int, Int> {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } }
            .filter { MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in it.getCapabilitiesForType(MIME).colorFormats }
            .sortedBy { if (isSoftware(it)) 1 else 0 }
        require(encoders.isNotEmpty()) { "No H.264 encoder on this device" }

        val portrait = h > w
        val longSide = maxOf(w, h)
        val shortSide = minOf(w, h)
        val aspect = longSide.toDouble() / shortSide
        // Walk the short side down in steps of 8 px; keep both sides even (required by YUV420).
        var s = shortSide - shortSide % 2
        while (s >= 240) {
            val l = (s * aspect).roundToInt().let { it - it % 2 }
            val (cw, ch) = if (portrait) s to l else l to s
            for (e in encoders) {
                if (e.getCapabilitiesForType(MIME).videoCapabilities.isSizeSupported(cw, ch)) return Triple(e.name, cw, ch)
            }
            s -= if (s % 8 == 0) 8 else s % 8
        }
        throw IOException("No encoder supports a video near ${w}x$h")
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
        else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

    /**
     * @param size requested video size (frames are fitted to it; it may be reduced to what the encoder supports)
     * @param onProgress called after each frame with (done, total)
     */
    fun render(
        frames: List<File>,
        out: File,
        size: Pair<Int, Int>,
        fps: Int,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Output {
        require(frames.isNotEmpty()) { "No frames" }
        val (codecName, w, h) = fitToEncoder(size.first, size.second)
        Log.i(TAG, "Encoding ${frames.size} frames at ${w}x$h $fps fps with $codecName")

        val codec = MediaCodec.createByCodecName(codecName)
        val caps = codec.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
        val bitrate = caps.bitrateRange.clamp((w.toDouble() * h * fps * BITS_PER_PIXEL).toInt())
        val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        out.delete()
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val drain = Drainer(codec, muxer)
            val argb = IntArray(w * h)
            var written = 0

            for ((i, file) in frames.withIndex()) {
                try {
                    val bmp = Frames.loadFitted(file, w, h)
                    bmp.getPixels(argb, 0, w, 0, 0, w, h)
                    bmp.recycle()
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable frame ${file.name}", e)
                    continue
                }
                val index = dequeueInput(codec, drain)
                val image = codec.getInputImage(index) ?: throw IOException("Encoder gave no input image")
                writeYuv(image, argb, w, h)
                codec.queueInputBuffer(index, 0, w * h * 3 / 2, written * 1_000_000L / fps, 0)
                written++
                drain.drain(endOfStream = false)
                onProgress(i + 1, frames.size)
            }
            if (written == 0) throw IOException("No readable frames")

            codec.queueInputBuffer(dequeueInput(codec, drain), 0, 0, written * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain.drain(endOfStream = true)
            muxer.stop()
            return Output(w, h, written, out.length())
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    private fun dequeueInput(codec: MediaCodec, drain: Drainer): Int {
        var tries = 0
        while (true) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) return index
            drain.drain(endOfStream = false)
            if (++tries > 1000) throw IOException("Encoder stalled")
        }
    }

    private class Drainer(private val codec: MediaCodec, private val muxer: MediaMuxer) {
        private val info = MediaCodec.BufferInfo()
        private var track = -1

        fun drain(endOfStream: Boolean) {
            var idle = 0
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (++idle > 500) throw IOException("Encoder did not finish")
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(track < 0) { "Output format changed twice" }
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && track >= 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }

    /** ARGB → YUV420 (BT.601, limited range), chroma averaged over 2×2 blocks. Handles planar and semi-planar layouts. */
    private fun writeYuv(image: Image, argb: IntArray, w: Int, h: Int) {
        val (yPlane, uPlane, vPlane) = image.planes
        val yBuf = yPlane.buffer
        val yRow = ByteArray(w)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val c = argb[base + x]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                yRow[x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            val rowStart = y * yPlane.rowStride
            if (yPlane.pixelStride == 1) {
                yBuf.position(rowStart)
                yBuf.put(yRow)
            } else {
                for (x in 0 until w) yBuf.put(rowStart + x * yPlane.pixelStride, yRow[x])
            }
        }

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        for (cy in 0 until h / 2) {
            for (cx in 0 until w / 2) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val c = argb[(cy * 2 + dy) * w + cx * 2 + dx]
                    r += (c shr 16) and 0xff
                    g += (c shr 8) and 0xff
                    b += c and 0xff
                }
                r = r shr 2
                g = g shr 2
                b = b shr 2
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                uBuf.put(cy * uPlane.rowStride + cx * uPlane.pixelStride, u.toByte())
                vBuf.put(cy * vPlane.rowStride + cx * vPlane.pixelStride, v.toByte())
            }
        }
    }
}
