package dev.audioeditor.core.dsp

import dev.audioeditor.core.pcm.PcmBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class NoiseReducerTest {

    private val rate = 1_024
    private val fftSize = 256

    private fun rms(values: FloatArray): Double =
        sqrt(values.sumOf { (it * it).toDouble() } / values.size)

    @Test
    fun zeroReductionReconstructsSignal() {
        val random = Random(7)
        val input = FloatArray(4_096) { random.nextFloat() * 2f - 1f }
        val buffer = PcmBuffer(rate, 1, input)
        val profile = NoiseReducer.captureProfile(buffer, 0, 1_024, fftSize)

        val output = NoiseReducer.reduce(buffer, profile, reductionDb = 0f, smoothingBins = 0, temporalWeight = 0f)
        input.indices.forEach { assertEquals(input[it], output.samples[it], 1e-5f) }
    }

    @Test
    fun profileCaptureAveragesNoiseSpectrum() {
        val random = Random(11)
        val noise = FloatArray(2_048) { random.nextFloat() * 0.1f - 0.05f }
        val profile = NoiseReducer.captureProfile(PcmBuffer(rate, 1, noise), 0, 2_048, fftSize)

        assertEquals(fftSize / 2 + 1, profile.bins)
        assertEquals(fftSize, profile.fftSize)
        assertTrue(profile.magnitudes.sum() > 0.0)
    }

    @Test
    fun reducesStationaryNoiseWhileKeepingTone() {
        val random = Random(99)
        val frameCount = 8_192
        val noise = FloatArray(frameCount) { random.nextFloat() * 0.1f - 0.05f }
        val tone = FloatArray(frameCount) { (0.4 * sin(2 * PI * 128 * it / rate)).toFloat() }
        val noisy = FloatArray(frameCount) { tone[it] + noise[it] }

        val noiseBuffer = PcmBuffer(rate, 1, noise)
        val profile = NoiseReducer.captureProfile(noiseBuffer, 0, 2_048, fftSize)
        val reduced = NoiseReducer.reduce(
            PcmBuffer(rate, 1, noisy),
            profile,
            reductionDb = 20f,
            smoothingBins = 1,
            temporalWeight = 0.4f,
        )

        fun error(samples: FloatArray): Double = rms(FloatArray(frameCount) { samples[it] - tone[it] })
        val before = error(noisy)
        val after = error(reduced.samples)

        assertTrue("noise RMS before=$before after=$after", after < before * 0.7)
        assertTrue("tone should survive: ${rms(reduced.samples)}", rms(reduced.samples) > 0.2)
    }

    @Test
    fun stereoNoiseIsProcessedOnBothChannels() {
        val random = Random(5)
        val noise = FloatArray(4_096) { random.nextFloat() * 0.1f - 0.05f }
        val input = PcmBuffer(rate, 2, noise.copyOf() + noise.copyOf())
        val profile = NoiseReducer.captureProfile(input, 0, 2_048, fftSize)
        val output = NoiseReducer.reduce(input, profile, reductionDb = 12f)

        assertEquals(2, output.channels)
        assertEquals(input.frameCount, output.frameCount)
        assertTrue(rms(output.samples) < rms(input.samples))
    }
}
