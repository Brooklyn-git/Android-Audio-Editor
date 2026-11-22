package dev.audioeditor.core.pcm

import kotlin.math.floor
import kotlin.math.roundToInt

/** Linear-interpolation resampling; adequate for 44.1 kHz project sources. */
object Resampler {

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate) return input.copyOf()
        require(fromRate > 0 && toRate > 0)
        if (input.isEmpty()) return FloatArray(0)

        val factor = toRate.toDouble() / fromRate
        val outputSize = floor((input.size - 1) * factor).toInt() + 1
        if (outputSize <= 0) return FloatArray(0)

        return FloatArray(outputSize) { i ->
            val position = i / factor
            val left = floor(position).toInt()
            val right = minOf(left + 1, input.size - 1)
            val fraction = (position - left).toFloat()
            input[left] * (1f - fraction) + input[right] * fraction
        }
    }

    fun resample(input: PcmBuffer, toRate: Int): PcmBuffer {
        if (input.sampleRate == toRate) return input.copy()
        val factor = toRate.toDouble() / input.sampleRate
        val outputFrames = floor((input.frameCount - 1) * factor).toInt() + 1
        val output = FloatArray(maxOf(0, outputFrames) * input.channels)

        for (frame in 0 until maxOf(0, outputFrames)) {
            val position = frame / factor
            val left = floor(position).toInt()
            val right = minOf(left + 1, input.frameCount - 1)
            val fraction = (position - left).toFloat()
            for (channel in 0 until input.channels) {
                val a = input[left, channel]
                val b = input[right, channel]
                output[frame * input.channels + channel] = a * (1f - fraction) + b * fraction
            }
        }
        return PcmBuffer(toRate, input.channels, output)
    }

    fun durationFrames(durationMs: Long, sampleRate: Int): Int =
        ((durationMs * sampleRate).toDouble() / 1_000.0).roundToInt()
}
