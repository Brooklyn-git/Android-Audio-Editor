package dev.audioeditor.core.pcm

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/** Minimal reader/writer for uncompressed 16-bit PCM WAV files. */
object WavCodec {

    private const val PCM_FORMAT = 1

    fun decode(input: InputStream): PcmBuffer = DataInputStream(input).use { data ->
        expectAscii(data, "RIFF")
        data.readIntLe() // riff size
        expectAscii(data, "WAVE")

        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var pcmBytes: ByteArray? = null

        while (true) {
            val id = readAsciiOrNull(data) ?: break
            val size = data.readIntLe()
            when (id) {
                "fmt " -> {
                    audioFormat = data.readUnsignedShortLe()
                    channels = data.readUnsignedShortLe()
                    sampleRate = data.readIntLe()
                    data.readIntLe() // byte rate
                    data.readUnsignedShortLe() // block align
                    bitsPerSample = data.readUnsignedShortLe()
                    skipBytes(data, size - 16)
                }
                "data" -> pcmBytes = data.readNBytes(size)
                else -> skipBytes(data, size)
            }
            if (size % 2 != 0) skipBytes(data, 1)
        }

        require(audioFormat == PCM_FORMAT) { "Only uncompressed PCM WAV is supported" }
        require(bitsPerSample == 16) { "Only 16-bit WAV is supported, got $bitsPerSample" }
        require(channels > 0 && sampleRate > 0) { "Invalid WAV format" }
        val bytes = pcmBytes ?: error("WAV data chunk not found")
        require(bytes.size % (channels * 2) == 0) { "Truncated WAV data chunk" }

        val samples = FloatArray(bytes.size / 2) { i -> shortLe(bytes, i * 2) / 32768f }
        PcmBuffer(sampleRate, channels, samples)
    }

    fun decode(file: File): PcmBuffer = file.inputStream().use { decode(it) }

    fun encode(output: OutputStream, sampleRate: Int, channels: Int, samples: FloatArray) {
        require(sampleRate > 0 && channels > 0)
        require(samples.size % channels == 0)

        val dataSize = samples.size * 2
        val header = ByteArray(44)
        writeAscii(header, 0, "RIFF")
        writeIntLe(header, 4, 36 + dataSize)
        writeAscii(header, 8, "WAVE")
        writeAscii(header, 12, "fmt ")
        writeIntLe(header, 16, 16)
        writeShortLe(header, 20, PCM_FORMAT)
        writeShortLe(header, 22, channels)
        writeIntLe(header, 24, sampleRate)
        writeIntLe(header, 28, sampleRate * channels * 2)
        writeShortLe(header, 32, channels * 2)
        writeShortLe(header, 34, 16)
        writeAscii(header, 36, "data")
        writeIntLe(header, 40, dataSize)
        output.write(header)

        val chunk = ByteArray(8192)
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(chunk.size / 2, samples.size - offset)
            for (i in 0 until count) {
                val value = samples[offset + i].coerceIn(-1f, 1f)
                writeShortLe(chunk, i * 2, (value * 32767f).roundToInt())
            }
            output.write(chunk, 0, count * 2)
            offset += count
        }
    }

    fun encode(file: File, sampleRate: Int, channels: Int, samples: FloatArray) =
        file.outputStream().use { encode(it, sampleRate, channels, samples) }

    private fun expectAscii(data: DataInputStream, expected: String) {
        val actual = readAsciiOrNull(data)
        require(actual == expected) { "Expected '$expected' in WAV header, got '$actual'" }
    }

    private fun readAsciiOrNull(data: DataInputStream): String? {
        val bytes = data.readNBytes(4)
        if (bytes.size < 4) return null
        return String(bytes, Charsets.US_ASCII)
    }

    private fun skipBytes(data: DataInputStream, count: Int) {
        require(count >= 0) { "Invalid WAV chunk size" }
        var remaining = count
        while (remaining > 0) {
            val skipped = data.skipBytes(remaining)
            if (skipped == 0) {
                if (data.read() == -1) error("Unexpected end of WAV file")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun shortLe(bytes: ByteArray, offset: Int): Short =
        ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort()

    private fun DataInputStream.readUnsignedShortLe(): Int =
        shortLe(readNBytes(2), 0).toInt() and 0xffff

    private fun DataInputStream.readIntLe(): Int {
        val b = readNBytes(4)
        require(b.size == 4) { "Unexpected end of WAV file" }
        return (b[0].toInt() and 0xff) or
            ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or
            ((b[3].toInt() and 0xff) shl 24)
    }

    private fun writeAscii(target: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.US_ASCII).copyInto(target, offset)
    }

    private fun writeShortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) target[offset + i] = ((value shr (i * 8)) and 0xff).toByte()
    }
}
