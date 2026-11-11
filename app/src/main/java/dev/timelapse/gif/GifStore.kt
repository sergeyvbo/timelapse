package dev.timelapse.gif

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * @param length committed size of the GIF file in bytes, including the trailing 0x3B
 */
data class GifState(val length: Long, val frames: Int, val width: Int, val height: Int)

/**
 * The local, ever-growing animated GIF. It is the source of truth: originals are deleted after upload.
 *
 * Appending overwrites the trailer byte with a new frame and re-adds the trailer. A sidecar state file
 * records the last committed length; on open, any partially written frame is cut off and the trailer restored.
 * Bytes before `length - 1` never change once committed, so they can be streamed safely while new frames are added.
 */
class GifStore(private val dir: File) {
    val gifFile = File(dir, "timelapse.gif")
    private val stateFile = File(dir, "timelapse.state")

    private val _state = MutableStateFlow(loadAndRepair())
    val stateFlow: StateFlow<GifState?> = _state.asStateFlow()

    @Synchronized
    fun state(): GifState? = _state.value

    @Synchronized
    fun append(frame: GifFrame, delayMs: Int): GifState {
        var st = _state.value
        if (st == null) {
            dir.mkdirs()
            val header = GifEncoder.header(frame.width, frame.height)
            gifFile.outputStream().use {
                it.write(header)
                it.write(GifEncoder.TRAILER)
                it.fd.sync()
            }
            st = GifState(header.size + 1L, 0, frame.width, frame.height)
            writeState(st)
        }
        require(frame.width == st.width && frame.height == st.height) {
            "Frame ${frame.width}x${frame.height} does not match GIF ${st.width}x${st.height}"
        }
        val bytes = GifEncoder.frame(frame, delayMs)
        RandomAccessFile(gifFile, "rw").use { raf ->
            raf.seek(st.length - 1)
            raf.write(bytes)
            raf.write(GifEncoder.TRAILER)
            raf.fd.sync()
        }
        val next = st.copy(length = st.length + bytes.size, frames = st.frames + 1)
        writeState(next)
        _state.value = next
        return next
    }

    private fun loadAndRepair(): GifState? {
        val st = readState()
        if (st == null || !gifFile.exists() || gifFile.length() < st.length) {
            gifFile.delete()
            stateFile.delete()
            return null
        }
        RandomAccessFile(gifFile, "rw").use { raf ->
            raf.seek(st.length - 1)
            if (raf.length() != st.length || raf.read() != GifEncoder.TRAILER) {
                raf.setLength(st.length)
                raf.seek(st.length - 1)
                raf.write(GifEncoder.TRAILER)
                raf.fd.sync()
            }
        }
        return st
    }

    private fun readState(): GifState? = try {
        val parts = stateFile.readText().trim().split(' ')
        GifState(parts[0].toLong(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
    } catch (e: Exception) {
        null
    }

    private fun writeState(st: GifState) {
        val tmp = File(dir, "timelapse.state.tmp")
        tmp.outputStream().use {
            it.write("${st.length} ${st.frames} ${st.width} ${st.height}".toByteArray())
            it.fd.sync()
        }
        Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
