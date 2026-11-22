package dev.audioeditor.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class FftTest {

    @Test
    fun sineWavePeaksInExpectedBin() {
        val size = 64
        val re = DoubleArray(size) { sin(2 * PI * 8 * it / size) }
        val im = DoubleArray(size)

        Fft.transform(re, im)
        val magnitudes = Fft.magnitudes(re, im)
        assertTrue("bin 8 should hold the tone: ${magnitudes[8]}", magnitudes[8] > 20.0)
        assertTrue("bin 8 should dominate its neighbour: ${magnitudes[7]}", magnitudes[8] > magnitudes[7] * 10)
    }

    @Test
    fun inverseTransformReconstructsInput() {
        val random = Random(42)
        val size = 128
        val re = DoubleArray(size) { random.nextDouble() - 0.5 }
        val im = DoubleArray(size) { random.nextDouble() - 0.5 }
        val originalRe = re.copyOf()
        val originalIm = im.copyOf()

        Fft.transform(re, im)
        Fft.transform(re, im, inverse = true)

        re.indices.forEach { i ->
            assertEquals(originalRe[i], re[i], 1e-9)
            assertEquals(originalIm[i], im[i], 1e-9)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPowerOfTwo() {
        Fft.transform(DoubleArray(3), DoubleArray(3))
    }
}
