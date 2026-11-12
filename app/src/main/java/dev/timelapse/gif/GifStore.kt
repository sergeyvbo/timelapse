package dev.timelapse.gif

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * @param length committed size of the GIF file in bytes, including the trailing 0x3B
 * @param day the shooting day this GIF covers (null for GIFs made before daily rotation)
 */
data class GifState(val length: Long, val frames: Int, val width: Int, val height: Int, val day: LocalDate? = null)

/**
 * The local animated GIF of the current day. A new one is started with the first frame of each day
 * ([rotate]); the finished one moves to `done/timelapse_<day>.gif` until it has been uploaded.
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

    private val doneDir = File(dir, "done")

    /** Finished GIFs of earlier days that still have to be uploaded, oldest first. */
    @Synchronized
    fun finished(): List<File> = doneDir.listFiles { f -> f.name.endsWith(".gif") }?.sortedBy { it.name }.orEmpty()

    /** If the current GIF belongs to a day other than [day], moves it to [finished] so the next frame starts a new one. */
    @Synchronized
    fun rotate(day: LocalDate) {
        val st = _state.value ?: return
        if (st.day == day) return
        val oldDay = st.day ?: Instant.ofEpochMilli(gifFile.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
        doneDir.mkdirs()
        Files.move(gifFile.toPath(), File(doneDir, fileName(oldDay)).toPath(), StandardCopyOption.REPLACE_EXISTING)
        stateFile.delete()
        _state.value = null
    }

    /** Deletes the current and all finished GIFs. */
    @Synchronized
    fun reset() {
        gifFile.delete()
        stateFile.delete()
        doneDir.deleteRecursively()
        _state.value = null
    }

    @Synchronized
    fun append(frame: GifFrame, delayMs: Int, day: LocalDate? = null): GifState {
        var st = _state.value
        if (st == null) {
            dir.mkdirs()
            val header = GifEncoder.header(frame.width, frame.height)
            gifFile.outputStream().use {
                it.write(header)
                it.write(GifEncoder.TRAILER)
                it.fd.sync()
            }
            st = GifState(header.size + 1L, 0, frame.width, frame.height, day)
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
        GifState(parts[0].toLong(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts.getOrNull(4)?.let(LocalDate::parse))
    } catch (e: Exception) {
        null
    }

    private fun writeState(st: GifState) {
        val tmp = File(dir, "timelapse.state.tmp")
        tmp.outputStream().use {
            it.write(("${st.length} ${st.frames} ${st.width} ${st.height}" + (st.day?.let { " $it" } ?: "")).toByteArray())
            it.fd.sync()
        }
        Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /** Drive/archive name of the GIF for [day]. */
        fun fileName(day: LocalDate) = "timelapse_$day.gif"

        /** Day encoded in a [fileName], or null. */
        fun dayOf(file: File): LocalDate? = try {
            LocalDate.parse(file.nameWithoutExtension.removePrefix("timelapse_"))
        } catch (e: Exception) {
            null
        }
    }
}
