package dev.audioeditor.core.pcm

import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16Test {

    @Test
    fun roundTripsInterleavedSamples() {
        val samples = floatArrayOf(0f, 0.25f, -0.5f, 0.999f, -1f)
        val restored = Pcm16.toFloats(Pcm16.toBytes(samples))
        assertEquals(samples.size, restored.size)
        samples.indices.forEach { i -> assertEquals(samples[i], restored[i], 1e-4f) }
    }

    @Test
    fun clampsOutOfRangeValues() {
        val bytes = Pcm16.toBytes(floatArrayOf(2f, -2f))
        val restored = Pcm16.toFloats(bytes)
        assertEquals(1f, restored[0], 1e-4f)
        assertEquals(-1f, restored[1], 1e-4f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOddByteCounts() {
        Pcm16.toFloats(byteArrayOf(1, 2, 3))
    }
}
