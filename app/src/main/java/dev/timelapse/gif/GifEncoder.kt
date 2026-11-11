package dev.timelapse.gif

import java.io.ByteArrayOutputStream

/** A frame of ARGB pixels (alpha ignored), row-major. */
class GifFrame(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width in 1..65535 && height in 1..65535) { "Bad size ${width}x$height" }
        require(pixels.size == width * height) { "Pixel count mismatch" }
    }
}

/** Builds the byte pieces of a GIF89a file so frames can be appended one at a time. */
object GifEncoder {
    const val TRAILER = 0x3B

    /** Header + logical screen descriptor (no global color table) + infinite-loop extension. */
    fun header(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        out.writeShort(width)
        out.writeShort(height)
        out.write(0x70) // no global color table, 8-bit color resolution
        out.write(0) // background color index
        out.write(0) // pixel aspect ratio
        // NETSCAPE2.0 application extension: loop forever
        out.write(0x21)
        out.write(0xFF)
        out.write(11)
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(3)
        out.write(1)
        out.writeShort(0)
        out.write(0)
        return out.toByteArray()
    }

    /** One complete frame: graphic control extension + image descriptor + local palette + LZW data. */
    fun frame(frame: GifFrame, delayMs: Int, sampleFactor: Int = 10): ByteArray {
        val (palette, indices) = quantize(frame.pixels, sampleFactor)
        val out = ByteArrayOutputStream(indices.size / 2 + 1024)

        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        out.write(1 shl 2) // disposal method 1 (do not dispose), no transparency
        out.writeShort(((delayMs + 5) / 10).coerceIn(0, 65535))
        out.write(0) // transparent color index (unused)
        out.write(0)

        out.write(0x2C)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(frame.width)
        out.writeShort(frame.height)
        out.write(0x80 or 7) // local color table, 2^(7+1) = 256 entries, not interlaced
        out.write(palette)

        LzwEncoder.encode(indices, out)
        return out.toByteArray()
    }

    private fun quantize(pixels: IntArray, sampleFactor: Int): Pair<ByteArray, ByteArray> {
        val rgb = ByteArray(pixels.size * 3)
        var k = 0
        for (p in pixels) {
            rgb[k++] = (p shr 16).toByte()
            rgb[k++] = (p shr 8).toByte()
            rgb[k++] = p.toByte()
        }
        val nq = NeuQuant(rgb, rgb.size, sampleFactor)
        val palette = nq.process()
        val indices = ByteArray(pixels.size)
        // Photos repeat colors a lot; cache lookups per exact RGB value.
        val cache = HashMap<Int, Int>()
        for (i in pixels.indices) {
            val c = pixels[i] and 0xFFFFFF
            val idx = cache.getOrPut(c) { nq.map((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff) }
            indices[i] = idx.toByte()
        }
        return palette to indices
    }

    private fun ByteArrayOutputStream.writeShort(v: Int) {
        write(v and 0xff)
        write((v shr 8) and 0xff)
    }
}
