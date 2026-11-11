package dev.timelapse.gif

import java.io.OutputStream

/**
 * GIF-flavoured variable-length LZW encoder. Writes the "LZW minimum code size" byte, the compressed data
 * split into sub-blocks of at most 255 bytes, and the terminating zero-length block.
 */
object LzwEncoder {
    private const val MAX_BITS = 12
    private const val MAX_CODES = 1 shl MAX_BITS

    fun encode(indices: ByteArray, out: OutputStream, minCodeSize: Int = 8) {
        require(indices.isNotEmpty()) { "Empty image" }
        out.write(minCodeSize)
        val writer = SubBlockBitWriter(out)
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1

        val dict = HashMap<Int, Int>(MAX_CODES * 2)
        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1

        // A code is written with the current width; the width grows once the table has outgrown it.
        // This matches the decoder, which runs one table entry behind the encoder.
        fun emit(code: Int) {
            writer.write(code, codeSize)
            if (nextCode > (1 shl codeSize) - 1 && codeSize < MAX_BITS) codeSize++
        }

        emit(clearCode)
        var prefix = indices[0].toInt() and 0xff
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xff
            val key = (prefix shl 8) or k
            val code = dict[key]
            if (code != null) {
                prefix = code
                continue
            }
            emit(prefix)
            if (nextCode < MAX_CODES) {
                dict[key] = nextCode++
            } else {
                emit(clearCode)
                dict.clear()
                codeSize = minCodeSize + 1
                nextCode = eoiCode + 1
            }
            prefix = k
        }
        emit(prefix)
        emit(eoiCode)
        writer.finish()
    }

    private class SubBlockBitWriter(private val out: OutputStream) {
        private val block = ByteArray(255)
        private var blockLen = 0
        private var acc = 0
        private var bitCount = 0

        fun write(code: Int, size: Int) {
            acc = acc or (code shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                putByte(acc and 0xff)
                acc = acc ushr 8
                bitCount -= 8
            }
        }

        fun finish() {
            if (bitCount > 0) putByte(acc and 0xff)
            acc = 0
            bitCount = 0
            flushBlock()
            out.write(0)
        }

        private fun putByte(b: Int) {
            block[blockLen++] = b.toByte()
            if (blockLen == 255) flushBlock()
        }

        private fun flushBlock() {
            if (blockLen == 0) return
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }
    }
}
