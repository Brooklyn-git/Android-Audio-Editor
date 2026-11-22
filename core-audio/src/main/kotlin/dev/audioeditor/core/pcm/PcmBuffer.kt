package dev.audioeditor.core.pcm

/** Interleaved floating-point PCM in [-1, 1]. */
data class PcmBuffer(
    val sampleRate: Int,
    val channels: Int,
    val samples: FloatArray,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
        require(samples.size % channels == 0) { "sample count must be a multiple of channels" }
    }

    val frameCount: Int get() = samples.size / channels

    operator fun get(frame: Int, channel: Int): Float =
        samples[frame * channels + channel]

    fun mono(): FloatArray {
        if (channels == 1) return samples.copyOf()
        return FloatArray(frameCount) { frame ->
            var sum = 0f
            for (channel in 0 until channels) sum += this[frame, channel]
            sum / channels
        }
    }

    fun copyOfFrames(fromFrame: Int, toFrame: Int): PcmBuffer {
        require(fromFrame in 0..toFrame && toFrame <= frameCount)
        val from = fromFrame * channels
        val to = toFrame * channels
        return copy(samples = samples.copyOfRange(from, to))
    }
}
