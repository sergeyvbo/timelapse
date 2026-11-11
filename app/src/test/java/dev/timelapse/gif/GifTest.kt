package dev.timelapse.gif

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.random.Random

class GifTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // --- LZW round trip with an independent decoder -------------------------------------------

    @Test
    fun `lzw round trips random data with table resets`() {
        val rnd = Random(42)
        val data = ByteArray(300_000) { rnd.nextInt(256).toByte() }
        assertArrayEquals(data, lzwRoundTrip(data))
    }

    @Test
    fun `lzw round trips repetitive data`() {
        val data = ByteArray(200_000) { (it / 1000 % 7).toByte() }
        assertArrayEquals(data, lzwRoundTrip(data))
    }

    @Test
    fun `lzw round trips tiny inputs`() {
        for (n in 1..20) {
            val data = ByteArray(n) { (it * 37).toByte() }
            assertArrayEquals(data, lzwRoundTrip(data))
        }
    }

    // --- Full GIF via javax.imageio --------------------------------------------------------------

    @Test
    fun `appended frames decode with expected colors`() {
        val store = GifStore(tmp.newFolder())
        val colors = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0x808080)
        for (c in colors) store.append(solid(64, 48, c), 200)

        val st = store.state()!!
        assertEquals(4, st.frames)
        assertEquals(st.length, store.gifFile.length())

        val frames = decode(store.gifFile)
        assertEquals(4, frames.size)
        colors.forEachIndexed { i, c ->
            val img = frames[i]
            assertEquals(64, img.width)
            assertEquals(48, img.height)
            assertColorNear(c, img.getRGB(10, 10))
        }
    }

    @Test
    fun `photo-like gradient frame decodes close to source`() {
        val w = 160
        val h = 120
        val px = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or (((x + y) * 255 / (w + h)))
        }
        val store = GifStore(tmp.newFolder())
        store.append(GifFrame(w, h, px), 100)
        val img = decode(store.gifFile).single()
        var totalErr = 0L
        for (y in 0 until h) for (x in 0 until w) {
            val a = px[y * w + x]
            val b = img.getRGB(x, y)
            totalErr += channelDiff(a, b)
        }
        val avgErr = totalErr.toDouble() / (w * h * 3)
        assertTrue("average channel error $avgErr", avgErr < 8)
    }

    @Test
    fun `reopening keeps frames and truncates a partial write`() {
        val dir = tmp.newFolder()
        GifStore(dir).apply {
            append(solid(32, 32, 0xFF0000), 100)
            append(solid(32, 32, 0x00FF00), 100)
        }
        val gif = File(dir, "timelapse.gif")
        val committed = gif.length()
        // Simulate a crash mid-append: trailer overwritten by a frame start, plus junk.
        java.io.RandomAccessFile(gif, "rw").use {
            it.seek(committed - 1)
            it.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 1, 2, 3))
        }

        val reopened = GifStore(dir)
        assertEquals(2, reopened.state()!!.frames)
        assertEquals(committed, gif.length())
        assertEquals(2, decode(gif).size)

        reopened.append(solid(32, 32, 0x0000FF), 100)
        val frames = decode(gif)
        assertEquals(3, frames.size)
        assertColorNear(0x0000FF, frames[2].getRGB(5, 5))
    }

    @Test
    fun `missing state starts a fresh gif`() {
        val dir = tmp.newFolder()
        GifStore(dir).append(solid(16, 16, 0xFFFFFF), 100)
        File(dir, "timelapse.state").delete()
        val store = GifStore(dir)
        assertNull(store.state())
        store.append(solid(20, 10, 0), 100)
        assertNotNull(store.state())
        assertEquals(1, decode(store.gifFile).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `frame size mismatch is rejected`() {
        val store = GifStore(tmp.newFolder())
        store.append(solid(16, 16, 0), 100)
        store.append(solid(17, 16, 0), 100)
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun solid(w: Int, h: Int, rgb: Int) = GifFrame(w, h, IntArray(w * h) { rgb or (0xFF shl 24) })

    private class Image(val width: Int, val height: Int, val rgb: IntArray) {
        fun getRGB(x: Int, y: Int) = rgb[y * width + x]
    }

    /** Minimal GIF parser: walks the blocks, decodes every image with its local palette. */
    private fun decode(file: File): List<Image> {
        val b = file.readBytes()
        assertEquals("GIF89a", String(b, 0, 6, Charsets.US_ASCII))
        assertEquals(GifEncoder.TRAILER, b.last().toInt() and 0xff)
        fun u16(i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
        var pos = 13
        val lsdPacked = b[10].toInt() and 0xff
        if (lsdPacked and 0x80 != 0) pos += 3 * (1 shl ((lsdPacked and 7) + 1))
        val images = ArrayList<Image>()
        while (true) {
            when (b[pos++].toInt() and 0xff) {
                0x21 -> {
                    pos++ // label
                    while (true) {
                        val len = b[pos++].toInt() and 0xff
                        if (len == 0) break
                        pos += len
                    }
                }
                0x2C -> {
                    val w = u16(pos + 4)
                    val h = u16(pos + 6)
                    val packed = b[pos + 8].toInt() and 0xff
                    pos += 9
                    assertTrue("local color table expected", packed and 0x80 != 0)
                    val palSize = 1 shl ((packed and 7) + 1)
                    val palette = IntArray(palSize) { i ->
                        val o = pos + i * 3
                        ((b[o].toInt() and 0xff) shl 16) or ((b[o + 1].toInt() and 0xff) shl 8) or (b[o + 2].toInt() and 0xff)
                    }
                    pos += palSize * 3
                    val input = ByteArrayInputStream(b, pos, b.size - pos)
                    val indices = lzwDecode(input)
                    pos = b.size - input.available()
                    assertEquals(w * h, indices.size)
                    images.add(Image(w, h, IntArray(w * h) { palette[indices[it].toInt() and 0xff] }))
                }
                0x3B -> return images
                else -> error("Unexpected block at ${pos - 1}")
            }
        }
    }

    private fun channelDiff(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)) +
            abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)) +
            abs((a and 0xff) - (b and 0xff))

    private fun assertColorNear(expected: Int, actual: Int) {
        assertTrue(
            "expected ${Integer.toHexString(expected)} got ${Integer.toHexString(actual and 0xFFFFFF)}",
            channelDiff(expected, actual) <= 24,
        )
    }

    private fun lzwRoundTrip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        LzwEncoder.encode(data, out)
        return lzwDecode(ByteArrayInputStream(out.toByteArray()))
    }

    /** Straightforward GIF LZW decoder, written independently from the encoder. */
    private fun lzwDecode(input: InputStream): ByteArray {
        val minCodeSize = input.read()
        val bytes = ByteArrayOutputStream()
        while (true) {
            val len = input.read()
            if (len <= 0) break
            val block = ByteArray(len)
            var read = 0
            while (read < len) read += input.read(block, read, len - read)
            bytes.write(block)
        }
        val data = bytes.toByteArray()
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val result = ByteArrayOutputStream()
        val table = ArrayList<ByteArray>()
        fun reset() {
            table.clear()
            for (i in 0 until clear) table.add(byteArrayOf(i.toByte()))
            table.add(ByteArray(0))
            table.add(ByteArray(0))
        }
        reset()
        var codeSize = minCodeSize + 1
        var bitPos = 0L
        var prev: ByteArray? = null
        while (true) {
            var code = 0
            for (b in 0 until codeSize) {
                val byteIndex = ((bitPos + b) / 8).toInt()
                val bit = (data[byteIndex].toInt() shr (((bitPos + b) % 8).toInt())) and 1
                code = code or (bit shl b)
            }
            bitPos += codeSize
            if (code == clear) {
                reset()
                codeSize = minCodeSize + 1
                prev = null
                continue
            }
            if (code == eoi) break
            val entry: ByteArray = when {
                code < table.size -> table[code]
                code == table.size && prev != null -> prev + prev[0]
                else -> error("bad code $code (table ${table.size})")
            }
            result.write(entry)
            if (prev != null && table.size < 4096) table.add(prev + entry[0])
            prev = entry
            if (table.size == (1 shl codeSize) && codeSize < 12) codeSize++
        }
        return result.toByteArray()
    }
}
