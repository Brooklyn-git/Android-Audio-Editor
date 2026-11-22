package dev.audioeditor.core.pcm

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import kotlin.math.max
import kotlin.math.min

data class PeakLevel(
    val samplesPerBucket: Int,
    val min: FloatArray,
    val max: FloatArray,
)

/** Multi-resolution min/max waveform data so zooming never re-reads full PCM. */
class PeakPyramid(
    val sampleRate: Int,
    val channels: Int,
    val levels: List<PeakLevel>,
) {
    val frameCount: Int get() = levels.firstOrNull()?.let { it.min.size * it.samplesPerBucket } ?: 0

    /** Smallest bucket that still gives at least one bar per pixel at this zoom. */
    fun levelFor(samplesPerPixel: Int): PeakLevel =
        levels.lastOrNull { it.samplesPerBucket <= samplesPerPixel } ?: levels.first()

    fun write(output: OutputStream) {
        output.write(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))
        writeInt(output, FORMAT_VERSION)
        writeInt(output, sampleRate)
        writeInt(output, channels)
        writeInt(output, levels.size)
        levels.forEach { level ->
            writeInt(output, level.samplesPerBucket)
            writeInt(output, level.min.size)
            writeFloats(output, level.min)
            writeFloats(output, level.max)
        }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val BASE_BUCKET = 256
        private const val ZOOM_FACTOR = 4

        fun build(buffer: PcmBuffer, baseBucket: Int = BASE_BUCKET): PeakPyramid {
            require(baseBucket > 0)
            val mono = buffer.mono()
            if (mono.isEmpty()) {
                return PeakPyramid(buffer.sampleRate, buffer.channels, listOf(PeakLevel(baseBucket, floatArrayOf(), floatArrayOf())))
            }

            val levels = mutableListOf<PeakLevel>()
            var bucket = minOf(baseBucket, max(1, mono.size))
            var previous: PeakLevel? = null

            while (bucket <= mono.size) {
                val level = if (previous == null) {
                    val count = (mono.size + bucket - 1) / bucket
                    val mins = FloatArray(count) { Float.MAX_VALUE }
                    val maxes = FloatArray(count) { -Float.MAX_VALUE }
                    for (i in mono.indices) {
                        val index = i / bucket
                        mins[index] = min(mins[index], mono[i])
                        maxes[index] = max(maxes[index], mono[i])
                    }
                    PeakLevel(bucket, mins, maxes)
                } else {
                    val factor = bucket / previous.samplesPerBucket
                    val count = (previous.min.size + factor - 1) / factor
                    val mins = FloatArray(count) { Float.MAX_VALUE }
                    val maxes = FloatArray(count) { -Float.MAX_VALUE }
                    for (i in previous.min.indices) {
                        val index = i / factor
                        mins[index] = min(mins[index], previous.min[i])
                        maxes[index] = max(maxes[index], previous.max[i])
                    }
                    PeakLevel(bucket, mins, maxes)
                }
                levels.add(level)
                previous = level
                bucket *= ZOOM_FACTOR
            }
            return PeakPyramid(buffer.sampleRate, buffer.channels, levels)
        }

        fun read(input: InputStream): PeakPyramid {
            val header = input.readNBytes(4)
            require(header.size == 4 && String(header, Charsets.US_ASCII) == "PKPY") {
                "Invalid peak cache magic"
            }
            require(readInt(input) == FORMAT_VERSION) { "Unsupported peak cache version" }
            val sampleRate = readInt(input)
            val channels = readInt(input)
            val levelCount = readInt(input)
            require(levelCount > 0)

            val levels = (0 until levelCount).map {
                val bucket = readInt(input)
                val count = readInt(input)
                PeakLevel(bucket, readFloats(input, count), readFloats(input, count))
            }
            return PeakPyramid(sampleRate, channels, levels)
        }

        private fun writeInt(output: OutputStream, value: Int) {
            for (shift in 0..3) output.write((value shr (shift * 8)) and 0xff)
        }

        private fun readInt(input: InputStream): Int {
            val bytes = input.readNBytes(4)
            require(bytes.size == 4) { "Unexpected end of peak cache" }
            return (bytes[0].toInt() and 0xff) or
                ((bytes[1].toInt() and 0xff) shl 8) or
                ((bytes[2].toInt() and 0xff) shl 16) or
                ((bytes[3].toInt() and 0xff) shl 24)
        }

        private fun writeFloats(output: OutputStream, values: FloatArray) {
            val buffer = ByteBuffer.allocate(values.size * 4).order(LITTLE_ENDIAN)
            values.forEach { buffer.putFloat(it) }
            output.write(buffer.array())
        }

        private fun readFloats(input: InputStream, count: Int): FloatArray {
            val bytes = input.readNBytes(count * 4)
            require(bytes.size == count * 4) { "Unexpected end of peak cache" }
            val buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
            return FloatArray(count) { buffer.getFloat() }
        }
    }
}
