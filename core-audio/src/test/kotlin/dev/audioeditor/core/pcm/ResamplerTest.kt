package dev.audioeditor.core.pcm

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ResamplerTest {

    @Test
    fun sameRateReturnsCopy() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        val output = Resampler.resample(input, 44_100, 44_100)
        assertEquals(3, output.size)
        assertEquals(0.2f, output[1], 0f)
        input[1] = 0.9f
        assertEquals(0.2f, output[1], 0f)
    }

    @Test
    fun constantSignalStaysConstantWhenUpsampling() {
        val input = FloatArray(100) { 0.25f }
        val output = Resampler.resample(input, 1_000, 4_000)
        assertEquals(397, output.size)
        output.forEach { assertEquals(0.25f, it, 1e-6f) }
    }

    @Test
    fun resamplesStereoBufferByFrames() {
        val input = PcmBuffer(1_000, 2, floatArrayOf(0f, 0f, 1f, -1f))
        val output = Resampler.resample(input, 2_000)
        assertEquals(2_000, output.sampleRate)
        assertEquals(2, output.channels)
        assertEquals(3, output.frameCount)
        assertEquals(0f, output[0, 0], 1e-6f)
        assertEquals(0.5f, output[1, 0], 1e-6f)
        assertEquals(1f, output[2, 0], 1e-6f)
        assertEquals(-1f, output[2, 1], 1e-6f)
    }

    @Test
    fun downsampledSineKeepsItsFrequency() {
        val rate = 8_000
        val input = FloatArray(rate) { sin(2 * PI * 200 * it / rate).toFloat() }
        val output = Resampler.resample(input, rate, 4_000)
        assertEquals(4_000, output.size)
        // 200 Hz has 20 samples per period at 4 kHz.
        val firstPeak = (0..39).maxByOrNull { output[it] }!!
        val secondPeak = (firstPeak + 10..firstPeak + 30).maxByOrNull { output[it] }!!
        assertEquals(20, secondPeak - firstPeak)
    }
}
