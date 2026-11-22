package dev.audioeditor.core.pcm

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Random-access PCM storage. Samples are interleaved floats in [-1, 1]. */
interface PcmSource : Closeable {
    val sampleRate: Int
    val channels: Int
    val frameCount: Int

    /**
     * Reads up to [framesToRead] frames starting at [startFrame] into [out].
     * Returns the number of frames actually read.
     */
    fun readFrames(startFrame: Int, framesToRead: Int, out: FloatArray, outOffset: Int = 0): Int
}

class ArrayPcmSource(private val buffer: PcmBuffer) : PcmSource {
    override val sampleRate: Int get() = buffer.sampleRate
    override val channels: Int get() = buffer.channels
    override val frameCount: Int get() = buffer.frameCount

    override fun readFrames(startFrame: Int, framesToRead: Int, out: FloatArray, outOffset: Int): Int {
        val available = (frameCount - startFrame).coerceAtLeast(0)
        val count = minOf(framesToRead, available)
        if (count == 0) return 0
        val from = startFrame * channels
        val to = from + count * channels
        buffer.samples.copyInto(out, outOffset, from, to)
        return count
    }

    override fun close() = Unit
}

/** Reads a WAV file without loading the whole file into memory. */
class FilePcmSource(file: File) : PcmSource {
    private val randomAccessFile = RandomAccessFile(file, "r")
    private val header: PcmBuffer = file.inputStream().use { WavCodec.decode(it) }

    override val sampleRate: Int get() = header.sampleRate
    override val channels: Int get() = header.channels
    override val frameCount: Int get() = header.frameCount

    private val dataOffset: Long = 44L

    override fun readFrames(startFrame: Int, framesToRead: Int, out: FloatArray, outOffset: Int): Int {
        if (startFrame !in 0..frameCount || framesToRead <= 0) return 0
        val count = minOf(framesToRead, frameCount - startFrame)
        val values = count * channels
        val bytes = ByteArray(values * 2)
        randomAccessFile.seek(dataOffset + startFrame.toLong() * channels * 2)
        randomAccessFile.readFully(bytes)
        for (i in 0 until values) {
            val low = bytes[i * 2].toInt() and 0xff
            val high = bytes[i * 2 + 1].toInt() and 0xff
            out[outOffset + i] = ((low or (high shl 8)).toShort()) / 32768f
        }
        return count
    }

    override fun close() {
        randomAccessFile.close()
    }
}
