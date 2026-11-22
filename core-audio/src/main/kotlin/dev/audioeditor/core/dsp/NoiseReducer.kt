package dev.audioeditor.core.dsp

import dev.audioeditor.core.pcm.PcmBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

/** Average magnitude spectrum of a user-selected noise-only passage. */
data class NoiseProfile(
    val magnitudes: DoubleArray,
    val fftSize: Int,
    val sampleRate: Int,
    val channels: Int,
) {
    val bins: Int get() = magnitudes.size
}

/** Audacity-style spectral gating: learn stationary noise, then attenuate it. */
object NoiseReducer {

    private const val NOISE_SCALE = 1.2
    private const val MIN_GAIN = 0.02
    private const val EPSILON = 1e-12

    fun captureProfile(
        buffer: PcmBuffer,
        fromFrame: Int,
        toFrame: Int,
        fftSize: Int = 2_048,
    ): NoiseProfile {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0)
        val mono = buffer.mono()
        val start = fromFrame.coerceIn(0, mono.size)
        val end = toFrame.coerceIn(start, mono.size)
        val bins = fftSize / 2 + 1
        val sums = DoubleArray(bins)
        val window = hann(fftSize)
        val hop = fftSize / 2
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        var frames = 0

        var offset = start
        while (offset + fftSize <= end) {
            analyzeFrame(mono, offset, window, re, im)
            Fft.transform(re, im)
            for (bin in 0 until bins) sums[bin] += hypot(re[bin], im[bin])
            frames++
            offset += hop
        }
        if (frames == 0 && end > start) {
            analyzeFrame(mono, start, window, re, im)
            Fft.transform(re, im)
            for (bin in 0 until bins) sums[bin] += hypot(re[bin], im[bin])
            frames = 1
        }
        if (frames == 0) return NoiseProfile(DoubleArray(bins), fftSize, buffer.sampleRate, buffer.channels)

        return NoiseProfile(
            DoubleArray(bins) { sums[it] / frames },
            fftSize,
            buffer.sampleRate,
            buffer.channels,
        )
    }

    /**
     * Applies the profile to every channel. [reductionDb] is the attenuation applied
     * where the signal matches the learned noise. Return false from [progress] to stop.
     */
    fun reduce(
        buffer: PcmBuffer,
        profile: NoiseProfile,
        reductionDb: Float = 12f,
        smoothingBins: Int = 2,
        temporalWeight: Float = 0.5f,
        progress: (fraction: Float) -> Boolean = { true },
    ): PcmBuffer {
        require(profile.fftSize > 0 && profile.bins == profile.fftSize / 2 + 1)
        require(reductionDb >= 0f)

        val fftSize = profile.fftSize
        val bins = profile.bins
        val hop = fftSize / 2
        val window = hann(fftSize)
        val strength = 1.0 - 10.0.pow(-reductionDb.toDouble() / 20.0)
        val channels = buffer.channels
        val frameCount = buffer.frameCount
        if (frameCount == 0) return buffer.copy()

        val reduced = Array(channels) { FloatArray(frameCount) }
        val normalization = FloatArray(frameCount)
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        val previousGains = DoubleArray(bins) { 1.0 }
        val rawGains = DoubleArray(bins)

        for (channel in 0 until channels) {
            previousGains.fill(1.0)
            normalization.fill(0f)
            // Start one hop before zero so the first samples still get a window contribution.
            var offset = -hop
            var frameIndex = 0

            while (offset < frameCount) {
                analyzeFrame(buffer.samples, channel, channels, offset, window, re, im)
                Fft.transform(re, im)

                for (bin in 0 until bins) {
                    val magnitude = hypot(re[bin], im[bin])
                    val noise = profile.magnitudes[bin] * NOISE_SCALE
                    rawGains[bin] = (1.0 - strength * noise / (magnitude + EPSILON))
                        .coerceIn(MIN_GAIN, 1.0)
                }

                for (bin in 0 until bins) {
                    val smoothed = averageAround(rawGains, bin, smoothingBins)
                    val gain = temporalWeight * previousGains[bin] + (1 - temporalWeight) * smoothed
                    previousGains[bin] = gain
                    re[bin] *= gain
                    im[bin] *= gain
                    if (bin in 1 until fftSize / 2) {
                        val mirror = fftSize - bin
                        re[mirror] *= gain
                        im[mirror] *= gain
                    }
                }

                Fft.transform(re, im, inverse = true)
                for (j in 0 until fftSize) {
                    val index = offset + j
                    if (index in 0 until frameCount) {
                        reduced[channel][index] += re[j].toFloat()
                        normalization[index] += window[j]
                    }
                }

                offset += hop
                frameIndex++
                if (frameIndex % 64 == 0 && !progress((offset.toFloat() / frameCount).coerceIn(0f, 1f))) {
                    break
                }
            }

            for (i in 0 until frameCount) {
                reduced[channel][i] /= maxOf(normalization[i], 1e-6f)
            }
        }

        val interleaved = FloatArray(frameCount * channels)
        for (frame in 0 until frameCount) {
            for (channel in 0 until channels) {
                interleaved[frame * channels + channel] = reduced[channel][frame]
            }
        }
        return PcmBuffer(buffer.sampleRate, channels, interleaved)
    }

    private fun hann(size: Int): FloatArray =
        FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }

    private fun analyzeFrame(
        mono: FloatArray,
        offset: Int,
        window: FloatArray,
        re: DoubleArray,
        im: DoubleArray,
    ) {
        for (i in re.indices) {
            val index = offset + i
            re[i] = if (index in mono.indices) (mono[index] * window[i]).toDouble() else 0.0
            im[i] = 0.0
        }
    }

    private fun analyzeFrame(
        interleaved: FloatArray,
        channel: Int,
        channels: Int,
        offset: Int,
        window: FloatArray,
        re: DoubleArray,
        im: DoubleArray,
    ) {
        for (i in re.indices) {
            val frame = offset + i
            val index = frame * channels + channel
            re[i] = if (frame >= 0 && index in interleaved.indices) {
                (interleaved[index] * window[i]).toDouble()
            } else {
                0.0
            }
            im[i] = 0.0
        }
    }

    private fun averageAround(values: DoubleArray, center: Int, radius: Int): Double {
        if (radius <= 0) return values[center]
        val from = maxOf(0, center - radius)
        val to = minOf(values.lastIndex, center + radius)
        var sum = 0.0
        for (i in from..to) sum += values[i]
        return sum / (to - from + 1)
    }
}
