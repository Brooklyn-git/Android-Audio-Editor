package dev.audioeditor.core.pcm

import kotlin.math.roundToInt

/** Conversion between interleaved float PCM and little-endian 16-bit PCM bytes. */
object Pcm16 {

    fun toBytes(samples: FloatArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = samples[i].coerceIn(-1f, 1f)
            val pcm = (value * 32767f).roundToInt()
            bytes[i * 2] = (pcm and 0xff).toByte()
            bytes[i * 2 + 1] = ((pcm shr 8) and 0xff).toByte()
        }
        return bytes
    }

    fun toFloats(bytes: ByteArray): FloatArray {
        require(bytes.size % 2 == 0) { "Truncated 16-bit PCM data" }
        return FloatArray(bytes.size / 2) { i ->
            val low = bytes[i * 2].toInt() and 0xff
            val high = bytes[i * 2 + 1].toInt() and 0xff
            ((low or (high shl 8)).toShort()) / 32768f
        }
    }
}
