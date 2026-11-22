package dev.audioeditor.core.pcm

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * Writes 16-bit PCM WAV to a file when the final frame count is not known yet.
 * The RIFF and data sizes are patched when the writer is closed.
 */
class WavFileWriter(
    file: File,
    val sampleRate: Int,
    val channels: Int,
) : Closeable {

    private val output = RandomAccessFile(file, "rw")
    private var framesWritten = 0L

    init {
        require(sampleRate > 0 && channels > 0)
        output.setLength(0)
        val header = ByteArray(44)
        header.writeAscii(0, "RIFF")
        header.writeIntLe(4, 0)
        header.writeAscii(8, "WAVE")
        header.writeAscii(12, "fmt ")
        header.writeIntLe(16, 16)
        header.writeShortLe(20, 1)
        header.writeShortLe(22, channels)
        header.writeIntLe(24, sampleRate)
        header.writeIntLe(28, sampleRate * channels * 2)
        header.writeShortLe(32, channels * 2)
        header.writeShortLe(34, 16)
        header.writeAscii(36, "data")
        header.writeIntLe(40, 0)
        output.write(header)
    }

    fun write(samples: FloatArray) {
        require(samples.size % channels == 0)
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = samples[i].coerceIn(-1f, 1f)
            val pcm = (value * 32767f).roundToInt()
            bytes.writeShortLe(i * 2, pcm)
        }
        output.write(bytes)
        framesWritten += samples.size / channels
    }

    override fun close() {
        val dataBytes = framesWritten * channels * 2
        output.seek(4)
        output.write(intLeBytes(36 + dataBytes.toInt()))
        output.seek(40)
        output.write(intLeBytes(dataBytes.toInt()))
        output.close()
    }

    private fun intLeBytes(value: Int) = ByteArray(4) { i -> ((value shr (i * 8)) and 0xff).toByte() }
}

private fun ByteArray.writeAscii(offset: Int, value: String) {
    value.toByteArray(Charsets.US_ASCII).copyInto(this, offset)
}

private fun ByteArray.writeShortLe(offset: Int, value: Int) {
    this[offset] = (value and 0xff).toByte()
    this[offset + 1] = ((value shr 8) and 0xff).toByte()
}

private fun ByteArray.writeIntLe(offset: Int, value: Int) {
    for (i in 0..3) this[offset + i] = ((value shr (i * 8)) and 0xff).toByte()
}
